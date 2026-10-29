package com.sheen.rokid.phone.plugin.smarthelper.host

import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.platform.P3Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The result of one 「纯手机模式」 switch change (0.4 Task 5).
 *
 * A change is **two** effects - stop the running session (if any) and persist the new mode - and either can refuse. A
 * boolean would have made "the stop failed" and "the blob refused the write" indistinguishable in the UI, so [saved]
 * says whether the mode was actually persisted, [stoppedSession] says whether the host session was ended first (true
 * even when the following service stop refused, because that side effect is real), and [error] is the stable code the
 * settings page turns into visible Chinese copy. There is deliberately no `restarted` member: the gate never
 * auto-restarts (spec §4.1).
 */
internal data class DetachSetResult(
    val saved: Boolean,
    val stoppedSession: Boolean = false,
    val error: String? = null,
)

/**
 * The host-only 「纯手机模式」 gate (`D199`, spec §4.1; owner-approved save semantics).
 *
 * This is the **only** writer of [SmartSettings.glassesDetached]. It owns:
 *
 * - the persisted value, written through a load -> copy -> save of the whole settings blob so every other setting is
 *   carried through untouched;
 * - the **stop-before-save** rule: whenever the composition has any non-`Idle` session state (including the CXR
 *   startup states `Authorizing`/`Connecting`/`BuildingScene` and the interrupted/retry states), a change first stops
 *   that session through the composition's existing `stop()` **and** stops the foreground service that hosted it
 *   (`Context.stopService`), and does **not** restart it. Changing the audio source or display target under a live
 *   session is exactly the hot switch the owner ruled out; leaving the service foreground would keep a 「会话已开启」
 *   notification for an idle session. An idle composition stops nothing;
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
    /**
     * The composition's existing stop path. Injected so the ordering rule is testable without a device.
     */
    private val stopSession: () -> Unit = {},
    /**
     * Ends the foreground service that hosted the stopped session (0.4 Task 9 fix) by stopping the service directly
     * (`Context.stopService`). `stopSession()` only tears down the host session; without this the service stayed
     * foreground and its notification kept saying 会话已开启 for an idle session. The service's own destroy path removes
     * the foreground notification, so the stop is not silent.
     *
     * Returns **true only when a matching running service was stopped** - the same contract as
     * `Context.stopService`, which answers false when nothing was running. A false is a refusal, not a success: the
     * gate cannot prove the notification is gone, so it must not persist a mode over it. A throw (a permission or
     * state refusal) is the same refusal. The production wiring passes `Context.stopService` unwrapped, so this
     * failure really reaches the switch instead of being swallowed by a `runCatching`.
     */
    private val stopService: () -> Boolean = { true },
    /** The live session state, read once per change. Defaults to idle for a rig with no host. */
    private val sessionState: () -> SessionState = { SessionState.Idle },
    /**
     * The mode's CXR side effect: called once, after a change was persisted, with the new value. Production wires it to
     * [com.sheen.rokid.phone.app.HostCompositionRoot.onDetachModeChanged]; a rig with no host keeps the default.
     */
    private val onModeChanged: (Boolean) -> Unit = {},
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
                // The two calls are separate effects: end the host session, then stop the foreground service that
                // hosted it and whose notification claims the session is running. Set `stopped` as soon as the first
                // succeeds - if the service stop then refuses, the mode is still not persisted, but the session side
                // effect is real and the result must disclose it rather than claim "会话未能先停止".
                stopSession()
                stopped = true
            } catch (failure: Exception) {
                android.util.Log.i("EdithHost", "detach gate: session stop failed, not persisting the mode")
                return DetachSetResult(saved = false, error = ERROR_STOP_FAILED)
            }

            // A false/throw here is a refusal of the service teardown, not of the already-completed session stop.
            val serviceStopped = try {
                stopService()
            } catch (failure: Exception) {
                false
            }
            if (!serviceStopped) {
                android.util.Log.i("EdithHost", "detach gate: hosting service stop refused, not persisting the mode")
                return DetachSetResult(saved = false, stoppedSession = stopped, error = ERROR_STOP_FAILED)
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

        // 0.4 strict-local fix (`D199`): the mode change is two effects plus the link transition. The side effect runs
        // **after** the write - a refused stop/save returned above, and running it against an unpersisted mode would
        // leave the CXR link neutralized while the store (and the next restart) still says glasses mode. It runs
        // **before** the live flow moves, so no reader can start a detached session on a link that has not been
        // neutralized yet.
        onModeChanged(enabled)

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

        /** The composition's own `stop()` or the hosting service's stop refused; nothing was persisted. */
        const val ERROR_STOP_FAILED = "SESSION_STOP_FAILED"

        /** The blob exists but cannot be read/parsed; writing defaults over it would erase the user's settings. */
        const val ERROR_SETTINGS_UNREADABLE = "SETTINGS_UNREADABLE"

        /** The blob write was refused; the live gate was not moved. */
        const val ERROR_SAVE_FAILED = "SAVE_FAILED"

        /**
         * Every state in which a session (and the foreground `SessionService` hosting it) can exist: all entries
         * except [SessionState.Idle]. The service starts foreground before the CXR scene reaches Ready, so
         * `Authorizing`/`Connecting`/`BuildingScene` need the same stop as `Ready`/`Listening`/`Paused`; a session
         * that is `Interrupted`, `Reconnecting` or `Error` still owns that service too. Only Idle means there is
         * nothing to stop (the current enum has no Closed entry).
         */
        private val RUNNING_STATES = SessionState.entries.toSet() - SessionState.Idle
    }
}
