package com.sheen.rokid.phone.plugin.smarthelper.host

import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.platform.P3Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The result of one 「脱离眼镜」 switch change (0.4 Task 5).
 *
 * A change is **two** effects - stop the running session (if any) and persist the new mode - and either can refuse. A
 * boolean would have made "the stop failed" and "the blob refused the write" indistinguishable in the UI, so [saved]
 * says whether the mode was actually persisted, [stoppedSession] says whether an already-running session had to be
 * ended first, and [error] is the stable code the settings page turns into visible Chinese copy. There is deliberately
 * no `restarted` member: the gate never auto-restarts (spec §4.1).
 */
internal data class DetachSetResult(
    val saved: Boolean,
    val stoppedSession: Boolean = false,
    val error: String? = null,
)

/**
 * The host-only 「脱离眼镜」 gate (`D199`, spec §4.1; owner-approved save semantics).
 *
 * This is the **only** writer of [SmartSettings.glassesDetached]. It owns:
 *
 * - the persisted value, written through a load -> copy -> save of the whole settings blob so every other setting is
 *   carried through untouched;
 * - the **stop-before-save** rule: when the composition is running (`Ready`/`Listening`/`Paused`), a change first
 *   stops that session through the composition's existing `stop()` and does **not** restart it. Changing the audio
 *   source or display target under a live session is exactly the hot switch the owner ruled out;
 * - the live [enabled] flow the host reads at runtime (the `D202` effective arming value follows it without a App
 *   restart).
 *
 * Failure is visible, never silent: a failed stop refuses the write (the session may still be running, so persisting
 * a mode that was not applied would be a lie); an unreadable blob refuses the write (saving defaults over it would
 * erase the user's other settings); a refused save leaves the live gate where the store still has it.
 *
 * **It never writes [SmartSettings.proactiveEnabled].** Detaching suppresses proactive assistance *effectively*
 * (`stored && !detached`, `D202`); closing detach restores the user's own stored choice.
 */
internal class DetachGate(
    /**
     * Where the mode is persisted, or null for a composition built by hand (tests/harnesses). A null store refuses
     * the change visibly rather than keeping an in-memory mode no restart will remember.
     */
    private val settings: SmartSettingsStore? = null,
    /** The composition's existing stop path. Injected so the ordering rule is testable without a device. */
    private val stopSession: () -> Unit = {},
    /** The live session state, read once per change. Defaults to idle for a rig with no host. */
    private val sessionState: () -> SessionState = { SessionState.Idle },
) {
    private val detached = MutableStateFlow(false)

    /** What the settings switch and the host's runtime gates read. Updated only after a successful save. */
    val enabled: StateFlow<Boolean> = detached.asStateFlow()

    /**
     * Reads the persisted mode once, at composition assembly.
     *
     * A failed read leaves the gate where it is (the shipped glasses mode). The tempting alternative - treat the
     * store's defaults as the answer - is what [SmartSettingsStore.load] already does for *readers*, but for this
     * gate it would silently re-attach a user who had detached if the blob were temporarily unreadable; keeping the
     * current value and logging is the honest failure. (`loadForWrite` is used here because it distinguishes a missing
     * blob - first install - from an unreadable one.)
     */
    @Synchronized
    fun load() {
        val target = settings ?: return
        val stored = target.loadForWrite()
        if (stored == null) {
            android.util.Log.i("EdithHost", "detach gate: settings unreadable, keeping the current mode")
            return
        }
        detached.value = stored.glassesDetached
        logState(stored.glassesDetached, stored)
    }

    /**
     * The user's switch.
     *
     * Order is the contract: **stop first, then persist**. A change on an idle composition skips the stop. Either
     * refusal returns a visible [DetachSetResult] and leaves the live gate matching what the store still holds; a
     * successful save updates [enabled] so the host's sinks and context signal follow immediately.
     */
    @Synchronized
    fun set(enabled: Boolean): DetachSetResult {
        val target = settings
            ?: return DetachSetResult(saved = false, error = ERROR_STORE_UNAVAILABLE)

        var stopped = false
        if (sessionState() in RUNNING_STATES) {
            try {
                stopSession()
                stopped = true
            } catch (failure: Exception) {
                android.util.Log.i("EdithHost", "detach gate: session stop failed, not persisting the mode")
                return DetachSetResult(saved = false, error = ERROR_STOP_FAILED)
            }
        }

        // Do not build the write on the store's defaults: `load` answers defaults for an unreadable blob, and saving
        // those back would wipe the user's other settings. `loadForWrite` answers null in exactly that case.
        val stored = try { target.loadForWrite() } catch (_: Exception) { null }
        if (stored == null) {
            android.util.Log.i("EdithHost", "detach gate: settings unreadable, not persisting this change")
            return DetachSetResult(saved = false, stoppedSession = stopped, error = ERROR_SETTINGS_UNREADABLE)
        }

        val updated = stored.copy(glassesDetached = enabled)
        try {
            target.save(updated)
        } catch (failure: Exception) {
            android.util.Log.i("EdithHost", "detach gate: settings save failed")
            return DetachSetResult(saved = false, stoppedSession = stopped, error = ERROR_SAVE_FAILED)
        }

        detached.value = enabled
        logState(enabled, updated)
        return DetachSetResult(saved = true, stoppedSession = stopped)
    }

    /**
     * `P4_DETACH state=on|off effective_proactive=<bool> audio=phone_mic|cxr` - state only, no content.
     *
     * The effective value is computed from the stored snapshot, which is also what the host's live gates will read
     * after this change: the stored 「主动辅助」 value itself is never rewritten.
     */
    private fun logState(detached: Boolean, stored: SmartSettings) {
        val effective = stored.proactiveEnabled && !detached
        P3Log.line(
            "P4_DETACH state=${if (detached) "on" else "off"} " +
                "effective_proactive=$effective audio=${if (detached) "phone_mic" else "cxr"}",
        )
    }

    companion object {
        /** A change cannot be persisted because the composition has no store (a hand-built rig). */
        const val ERROR_STORE_UNAVAILABLE = "SETTINGS_STORE_UNAVAILABLE"

        /** The composition's own `stop()` refused; nothing was persisted. */
        const val ERROR_STOP_FAILED = "SESSION_STOP_FAILED"

        /** The blob exists but cannot be read/parsed; writing defaults over it would erase the user's settings. */
        const val ERROR_SETTINGS_UNREADABLE = "SETTINGS_UNREADABLE"

        /** The blob write was refused; the live gate was not moved. */
        const val ERROR_SAVE_FAILED = "SAVE_FAILED"

        /**
         * The states the owner's ruling calls "a session is running" (`D199`): a ready, listening or paused session
         * has a live identity/epoch, so changing the audio source or display target under it must stop first.
         */
        private val RUNNING_STATES = setOf(SessionState.Ready, SessionState.Listening, SessionState.Paused)
    }
}
