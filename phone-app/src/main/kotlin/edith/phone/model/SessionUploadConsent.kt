package edith.phone.model

import edith.model.RequestConsent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The session-scoped text-upload consent (D13).
 *
 * The gated model client is handed one consent source. Until this type existed that source was only the probe gate,
 * which answers for a *probe* request, so a real turn was refused before any HTTP with no path at all for a user to
 * allow it. This is that path - and only that path:
 *
 * - **Off by default, and session state rather than a stored secret.** Nothing here is persisted, no permission is
 *   needed and nothing else consults it.
 * - **It never becomes a probe consent.** The probe answer's `syntheticProbe` flag is only ever the probe flow's, so
 *   the merge adds `text` and nothing else.
 * - **With the toggle off the answer is the probe gate's, unchanged** - which is exactly the behaviour measured on the
 *   device today (refused, honest failure hint, nothing sent).
 */
internal class SessionUploadConsent {
    private val on = MutableStateFlow(false)

    /** What the 会话 page's switch shows; a flow so the Compose layer follows a reset that happens outside it. */
    val enabled: StateFlow<Boolean> = on.asStateFlow()

    /** The user's switch. Turning it on is the only way a real turn's text may leave the device. */
    fun set(enabled: Boolean) { on.value = enabled }

    /** The session ended (stop): the grant does not outlive it. */
    fun deactivate() { on.value = false }

    /** Merges this session's toggle into the probe gate's own answer. */
    fun requestConsent(probe: RequestConsent): RequestConsent =
        // Only `text`: `syntheticProbe` stays the probe flow's, so an on toggle can never substitute for the probe
        // confirmation that flow requires, and with the toggle off this returns the probe answer object-for-object.
        if (on.value) probe.copy(text = true) else probe
}
