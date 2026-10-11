package edith.phone.model

import edith.phone.SmartSettingsStore
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
 * - **On by default, and it takes effect at install time** - the default lives in [edith.phone.SmartSettings.proactiveEnabled].
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
) {
    private val on = MutableStateFlow(true)

    /** What the 会话 page's switch shows; a flow so the Compose layer follows a load that happened outside it. */
    val enabled: StateFlow<Boolean> = on.asStateFlow()

    /**
     * Reads the persisted answer. Called once by `create` at assembly time.
     *
     * A store that cannot be read answers with the defaults ([SmartSettingsStore.load] never throws), so the composition
     * still comes up; and because the default is **on**, a failed read arms rather than silently disarming the feature
     * the user asked to have on.
     */
    fun load() {
        val stored = runCatching { store?.load() }.getOrNull() ?: return
        on.value = stored.proactiveEnabled
    }

    /**
     * The user's switch. Turning it on is the only way perception events are produced, and it is also the only writer
     * of the persisted value.
     *
     * The write is "re-read the settings, change this one bit, save" rather than "save a settings object this class
     * happens to hold": the settings screen can write the same blob, and saving a stale copy would silently revert
     * whatever it changed in between.
     */
    fun set(enabled: Boolean) {
        on.value = enabled
        val target = store ?: return
        runCatching { target.save(target.load().copy(proactiveEnabled = enabled)) }
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
