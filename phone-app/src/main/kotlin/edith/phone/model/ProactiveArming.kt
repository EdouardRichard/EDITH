package edith.phone.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The session-scoped 「主动辅助」 switch (P3.2, spec §16.2).
 *
 * It is the **arming gate**, not an authorisation: with it off the composition produces no perception event at all
 * (`HostCompositionRoot.armedAcousticSink`), so a plugin receives no signal and cannot start a proactive turn. That is
 * why arming needs no contract value - the events already exist, and the host's own decision to emit them is the
 * whole mechanism.
 *
 * Deliberately the same shape as [SessionUploadConsent] and for the same reasons:
 *
 * - **Off by default, session state rather than a stored secret.** Nothing here is persisted and nothing else
 *   consults it.
 * - **It is not an upload grant.** Turning it on sends nothing by itself: a proactive turn is a model request, and a
 *   request still needs the session's upload consent. The UI says so in as many words.
 * - **It does not outlive the session.** [deactivate] is called on `stop()` and on every transition back to `Idle`.
 */
internal class ProactiveArming {
    private val on = MutableStateFlow(false)

    /** What the 会话 page's switch shows; a flow so the Compose layer follows a reset that happens outside it. */
    val enabled: StateFlow<Boolean> = on.asStateFlow()

    /** The user's switch. Turning it on is the only way perception events are produced. */
    fun set(enabled: Boolean) { on.value = enabled }

    /** The session ended (stop, or any path back to `Idle`): the arming does not outlive it. */
    fun deactivate() { on.value = false }

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
