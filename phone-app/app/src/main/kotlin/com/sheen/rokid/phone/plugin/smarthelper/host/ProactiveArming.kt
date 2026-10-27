package com.sheen.rokid.phone.plugin.smarthelper.host
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The 「主动辅助」 switch (P3.2 spec §16.2; **`D53`② rewrote it as a persisted global setting**).
 *
 * It is the **arming gate**, not an authorisation: with it off the composition produces no perception event at all
 * (`HostCompositionRoot.armedAcousticSink`), so a plugin receives no signal and cannot start a proactive turn. That is
 * why arming needs no contract value - the events already exist, and the host's own decision to emit them is the
 * whole mechanism.
 *
 * What `D53`② changed, and what is therefore pinned by tests:
 *
 * - **On by default, and it takes effect at install time** - the default lives in [com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings.proactiveEnabled].
 * - **It persists.** The switch is stored with the other settings and survives a session ending, a reconnect, an App
 *   restart and a phone restart. It is therefore **not** session state and [deactivate] no longer exists: the old
 *   per-session reset is exactly what the ruling removed.
 * - **Nothing re-arms a disarmed switch.** [load] only ever *reads*; there is no path that writes `true` except the
 *   user's own [set]. A reset would silently re-enable perception the user had turned off, which is the behaviour the
 *   ruling forbids in as many words.
 *
 * It is still **not an upload grant**: turning it on sends nothing by itself. Since `D53`① there is no separate upload
 * switch either - a proactive turn is a model request, and "the session is running" is what allows it.
 */
internal class ProactiveArming(
    /**
     * Where the switch is persisted, or null for the harnesses that build a composition by hand. A null store keeps the
     * switch in memory only, which is what a test rig without storage wants - it does not make the switch default off.
     */
    private val store: SmartSettingsStore? = null,
    /**
     * 0.4 Task 5 (`D202`): whether the user is running detached from the glasses.
     *
     * The stored switch remains the user's value; this predicate is the **second input** the host combines into
     * [effectiveProactiveArmed]. It is a provider rather than a captured boolean so a detach change takes effect on
     * the very next event without an App restart. Production passes `{ detachGate.enabled.value }`; a rig without a
     * gate keeps the shipped glasses mode.
     */
    private val detached: () -> Boolean = { false },
) {
    private val on = MutableStateFlow(true)

    /** What the 会话 page's switch shows; a flow so the Compose layer follows a load that happened outside it. */
    val enabled: StateFlow<Boolean> = on.asStateFlow()

    /**
     * `D202`: the value every proactive **producer** must read - `stored && !detached`.
     *
     * The host's acoustic/context sinks and the `proactive.armed` context key all use this; the plugin needs no
     * change because it already treats `false` as DISARMED. Reading it never writes the stored value, so closing
     * detach restores the user's own choice.
     */
    val effectiveProactiveArmed: Boolean get() = on.value && !detached()

    /**
     * Reads the persisted answer. Called once by `create` at assembly time.
     *
     * A store that cannot be read leaves the switch **where it already is** and says so. The tempting alternative -
     * treat a failed read as "the default", which is on - would re-arm a switch the user had deliberately turned off,
     * which `D53`② forbids in as many words. The log line is what keeps the failure from being silent.
     */
    fun load() {
        val target = store ?: return
        val stored = runCatching { target.loadForWrite() }.onFailure {
            android.util.Log.i("EdithHost", "proactive switch: settings unreadable, keeping the current value")
        }.getOrNull() ?: return
        on.value = stored.proactiveEnabled
    }

    /**
     * The user's switch. Turning it on is the only way perception events are produced, and it is also the only writer
     * of the persisted value.
     *
     * **A failed read aborts the write.** [SmartSettingsStore.loadForWrite] answers `null` when the blob exists but
     * cannot be read, and is read-only for a missing one. Saving `load().copy(one field)` would otherwise quietly
     * persist the store's defaults - wake words, risk ceiling, whitelist, profile, cooldowns, sensitivity, **and since
     * 0.4 the detach mode** - over the user's real settings. The switch still moves in memory (the UI must respond),
     * and the next successful read reconciles; losing one preference change is recoverable, silently wiping the rest
     * is not.
     */
    fun set(enabled: Boolean) {
        on.value = enabled
        val target = store ?: return
        runCatching { target.loadForWrite() }.onFailure {
            android.util.Log.i("EdithHost", "proactive switch: settings unreadable, not persisting this change")
        }.getOrNull()?.let { stored -> runCatching { target.save(stored.copy(proactiveEnabled = enabled)) } }
    }

    companion object {
        /**
         * The context key the current arming state is published under.
         *
         * The switch cannot reach the plugin directly (no contract carries a session toggle), and the host's gate on
         * *producing* perception events is not enough on its own: local transcription deliberately continues while the
         * switch is off, so a transcript can still arrive at an armed-looking plugin. Publishing the state as a
         * context signal is what lets the plugin read the **current** answer at request time, through the capability
         * it already declares.
         */
        const val CONTEXT_KEY = "proactive.armed"
    }
}
