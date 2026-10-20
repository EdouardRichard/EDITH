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
    FAILED,
    A11Y_NOT_READY,
    ;

    val usable: Boolean get() = this == ALREADY_UNLOCKED || this == UNLOCKED
}

/** The tool-execution gate: every device-facing tool asks this before it runs. */
fun interface UnlockGate {
    fun ensureUnlocked(requireAccessibility: Boolean): UnlockOutcome
}

/**
 * Single-flight unlock for the two lock-screen shapes on the K40:
 *
 * - a swipe lock is dismissed by the shell (`wm dismiss-keyguard`) without a credential;
 * - a PIN/password lock is opened by sending the configured PIN as **key codes** and ENTER. The PIN is never put in a
 *   shell command as text, so it does not appear in an `input text` command or a shell argument.
 *
 * The coordinator only observes the keyguard afterwards; a successful command is not evidence that the phone unlocked.
 * A failed PIN attempt is reported and **not retried**: another automatic attempt could trip the platform's own lockout.
 * Callers that need the accessibility service (the `device` tool) wait for it to reconnect before returning success.
 */
class UnlockCoordinator(
    private val lockState: LockState,
    private val shell: LockShell,
    private val pins: LockPinStore,
    private val accessibilityReady: () -> Boolean,
    private val keepAlive: () -> Unit,
    private val nowMillis: () -> Long,
    private val sleepMillis: (Long) -> Unit,
) : UnlockGate {

    @Synchronized
    override fun ensureUnlocked(requireAccessibility: Boolean): UnlockOutcome {
        keepAlive()
        if (!lockState.isLocked()) return UnlockOutcome.ALREADY_UNLOCKED

        if (lockState.isSecure()) {
            val pin = pins.read() ?: return UnlockOutcome.NO_PIN
            try {
                shell.run(WAKE)
                shell.run(SHOW_BOUNCER)
                for (digit in pin) shell.run("input keyevent ${KEYCODE_0 + (digit - '0')}")
                shell.run(ENTER)
            } finally {
                pin.fill('\u0000')
            }
        } else {
            shell.run(WAKE)
            shell.run(DISMISS_KEYGUARD)
            // Some Android 12 builds ignore wm dismiss-keyguard on a swipe lock; MENU is the platform's own fallback.
            if (lockState.isLocked()) shell.run(SHOW_BOUNCER)
        }

        if (!await({ !lockState.isLocked() }, UNLOCK_TIMEOUT_MILLIS)) return UnlockOutcome.FAILED
        if (requireAccessibility && !await(accessibilityReady, A11Y_TIMEOUT_MILLIS)) {
            return UnlockOutcome.A11Y_NOT_READY
        }
        return UnlockOutcome.UNLOCKED
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
        const val UNLOCK_TIMEOUT_MILLIS = 3_000L
        const val A11Y_TIMEOUT_MILLIS = 5_000L
        const val WAKE = "input keyevent 224"
        const val SHOW_BOUNCER = "input keyevent 82"
        const val ENTER = "input keyevent 66"
        const val DISMISS_KEYGUARD = "wm dismiss-keyguard"
        /** `KEYCODE_0` is 7, so digit d is key code 7 + d. */
        const val KEYCODE_0 = 7
    }
}
