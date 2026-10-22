package com.sheen.rokid.phone.lock

/** The minimum a lock-screen state probe must answer. */
interface LockState {
    fun isLocked(): Boolean
    fun isSecure(): Boolean
}

/** One command on the privileged shell; `false` means it could not be dispatched. */
fun interface LockShell {
    fun run(command: String): Boolean
}

/** A persisted PIN, already validated by [LockPinValidator] before [save]. */
interface LockPinStore {
    fun hasPin(): Boolean
    fun read(): CharArray?
    fun save(pin: String)
    fun clear()
}

enum class UnlockOutcome {
    ALREADY_UNLOCKED,
    UNLOCKED,
    NO_PIN,

    /** The display never became interactive, so the keyguard could never accept input. */
    SCREEN_NOT_INTERACTIVE,

    /** The PIN was dispatched but the keyguard is still locked afterwards; never retried automatically. */
    STILL_LOCKED,

    A11Y_NOT_READY,
    ;

    val usable: Boolean get() = this == ALREADY_UNLOCKED || this == UNLOCKED
}

/** The tool-execution gate: every device-facing tool asks this before it runs. */
fun interface UnlockGate {
    fun ensureUnlocked(requireAccessibility: Boolean): UnlockOutcome
}

/**
 * Single-flight unlock for the two lock-screen shapes on the K40 / K70:
 *
 * - a swipe lock is dismissed by the shell (`wm dismiss-keyguard`) without a credential;
 * - a PIN/password lock is opened by sending the configured PIN as **key codes** and ENTER. The PIN is never put in a
 *   shell command as text, so it does not appear in an `input text` command or a shell argument.
 *
 * **Waiting is part of the contract (K70 acceptance, 2026-10-22).** The screen is not interactive the moment the
 * wake key is injected, and the PIN keyguard is drawn after it: the device log showed `CUJ=LOCKSCREEN_PIN_APPEAR`
 * only ~1.9 s after the wake, while the digits were injected immediately - so every digit was dropped and the gate
 * reported a failure with a correct PIN. [interactive] is polled before asking for the keyguard, and
 * [keyguardPinVisible] is polled (0.2 s steps, at most ten, i.e. a bounded 2 s) before any digit is typed. A probe
 * that cannot tell (`null`, e.g. the accessibility service is off) falls back to the same bounded settle rather
 * than refusing a secure unlock.
 *
 * The coordinator only observes the keyguard afterwards; a successful command is not evidence that the phone
 * unlocked. A failed PIN attempt is reported and **not retried**: another automatic attempt could trip the
 * platform's own lockout. Callers that need the accessibility service (the `device` tool) wait for it to reconnect
 * before returning success, including on the already-unlocked path.
 */
class UnlockCoordinator(
    private val lockState: LockState,
    private val shell: LockShell,
    private val pins: LockPinStore,
    private val accessibilityReady: () -> Boolean,
    private val keepAlive: () -> Unit,
    private val nowMillis: () -> Long,
    private val sleepMillis: (Long) -> Unit,
    private val interactive: () -> Boolean = { true },
    private val keyguardPinVisible: () -> Boolean? = { null },
    private val trace: (String) -> Unit = {},
) : UnlockGate {

    @Synchronized
    override fun ensureUnlocked(requireAccessibility: Boolean): UnlockOutcome {
        keepAlive()
        val wasLocked = lockState.isLocked()
        if (wasLocked) {
            val outcome = if (lockState.isSecure()) unlockWithPin() else dismissSwipe()
            if (!outcome.usable) return outcome
        }
        if (requireAccessibility && !await(accessibilityReady, A11Y_TIMEOUT_MILLIS)) {
            return UnlockOutcome.A11Y_NOT_READY
        }
        return if (wasLocked) UnlockOutcome.UNLOCKED else UnlockOutcome.ALREADY_UNLOCKED
    }

    private fun unlockWithPin(): UnlockOutcome {
        val pin = pins.read() ?: return UnlockOutcome.NO_PIN
        try {
            run("wake", WAKE)
            if (!await(interactive, WAKE_TIMEOUT_MILLIS)) {
                trace("stage=screen_not_interactive")
                return UnlockOutcome.SCREEN_NOT_INTERACTIVE
            }
            run("show_bouncer", SHOW_BOUNCER)
            awaitPinView()
            // One digit per command with a settle between them: the K70 keyguard dropped a whole batch injected as
            // one `input keyevent 8 9 ...` invocation (accepted digits were incomplete, so a correct PIN failed).
            for (digit in pin) {
                run("digit", "input keyevent ${KEYCODE_0 + (digit - '0')}")
                sleepMillis(KEY_SETTLE_MILLIS)
            }
            run("enter", ENTER)
        } finally {
            pin.fill('\u0000')
        }
        if (!await({ !lockState.isLocked() }, UNLOCK_TIMEOUT_MILLIS)) {
            trace("stage=still_locked")
            return UnlockOutcome.STILL_LOCKED
        }
        return UnlockOutcome.UNLOCKED
    }

    private fun dismissSwipe(): UnlockOutcome {
        run("wake", WAKE)
        if (!await(interactive, WAKE_TIMEOUT_MILLIS)) {
            trace("stage=screen_not_interactive")
            return UnlockOutcome.SCREEN_NOT_INTERACTIVE
        }
        run("dismiss_keyguard", DISMISS_KEYGUARD)
        // `wm dismiss-keyguard` returns before the keyguard's own state settles; read it after a short beat.
        sleepMillis(DISMISS_SETTLE_MILLIS)
        if (lockState.isLocked()) run("show_bouncer", SHOW_BOUNCER)
        return if (await({ !lockState.isLocked() }, UNLOCK_TIMEOUT_MILLIS)) UnlockOutcome.UNLOCKED
        else UnlockOutcome.STILL_LOCKED
    }

    /**
     * Dispatches one unlock step and records a rejected command.
     *
     * This is the only place that reads the shell's own answer: before it, a channel that was not bound (exactly the
     * state a stale or unstarted session leaves behind) made every unlock step fail while the gate still reported a
     * plain `STILL_LOCKED`, which cannot be told apart from a wrong PIN. The step name is a constant, never the PIN
     * or a command's output.
     */
    private fun run(step: String, command: String) {
        if (!shell.run(command)) trace("stage=command_failed step=$step")
    }

    /**
     * Bounded wait for the keyguard's PIN view: one probe now, then 0.2 s steps for ten attempts (2 s in total).
     *
     * When the probe can see the keyguard (`true`) the wait ends at once and the stage is logged, so acceptance can
     * tell "the PIN view was up" from "the probe could not tell". While the probe reports `false` the show-bouncer key
     * is re-sent on every step: the K70 log showed the keyguard ignores that key until it is ready, and a single early
     * press left the PIN view unopened while digits were still typed. `null` means the accessibility channel is off;
     * there is then nothing to poll, so the same bounded budget is spent once and typing proceeds, which keeps a
     * correct PIN usable instead of turning a missing probe into a refusal.
     */
    private fun awaitPinView() {
        repeat(PIN_VIEW_ATTEMPTS) {
            when (keyguardPinVisible()) {
                true -> {
                    trace("stage=pin_view_visible")
                    return
                }
                null -> sleepMillis(PIN_VIEW_STEP_MILLIS)
                false -> {
                    run("show_bouncer", SHOW_BOUNCER)
                    sleepMillis(PIN_VIEW_STEP_MILLIS)
                }
            }
        }
        trace("stage=pin_view_unconfirmed")
    }

    private fun await(check: () -> Boolean, timeoutMillis: Long): Boolean {
        val deadline = nowMillis() + timeoutMillis
        do {
            if (check()) return true
            if (nowMillis() >= deadline) return false
            sleepMillis(POLL_MILLIS)
        } while (true)
    }

    private companion object {
        const val POLL_MILLIS = 100L
        const val WAKE_TIMEOUT_MILLIS = 2_000L
        const val A11Y_TIMEOUT_MILLIS = 5_000L
        const val UNLOCK_TIMEOUT_MILLIS = 8_000L
        const val DISMISS_SETTLE_MILLIS = 150L
        const val KEY_SETTLE_MILLIS = 20L
        const val PIN_VIEW_STEP_MILLIS = 200L
        const val PIN_VIEW_ATTEMPTS = 10
        const val WAKE = "input keyevent 224"
        const val SHOW_BOUNCER = "input keyevent 82"
        const val ENTER = "input keyevent 66"
        const val DISMISS_KEYGUARD = "wm dismiss-keyguard"

        /** `KEYCODE_0` is 7, so digit d is key code 7 + d. */
        const val KEYCODE_0 = 7
    }
}
