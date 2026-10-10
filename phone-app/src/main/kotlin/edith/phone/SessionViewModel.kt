package edith.phone

import androidx.lifecycle.ViewModel
import edith.contracts.SessionState
import edith.plugin.Capability
import edith.rokid.CxrAuthorizationOutcome
import kotlinx.coroutines.flow.StateFlow

/** P3.3 (spec §17.4): the question the session page is showing, if one is up. */
data class PendingConfirmation(
    val requestId: String,
    val summary: String,
    /**
     * When this question stops being answerable, on the wall clock the phone UI can read (owner directive 2026-10-10:
     * the user gets an explicit countdown, and the question closes itself when it runs out).
     */
    val expiresAtMillis: Long = 0L,
)

data class SessionUiState(
    val session: SessionState,
    val selectedPluginId: String?,
    val capabilityReasons: Map<Capability, String>,
    val errorCode: String?,
    /** The confirmation currently awaiting an answer on the phone, or null. */
    val pendingConfirmation: PendingConfirmation? = null,
    /** P3.3 (spec §17.2 rule 1): the execution channels, named individually so a down one cannot hide. */
    val executionChannels: List<ExecutionChannelStatus> = emptyList(),
    /**
     * The SDK's answer to the CXR authorization prompt, once it actually asked (owner-reported defect 2026-10-10: the
     * connect button used to *claim* an authorization step on every tap). Null means "the SDK has not asked in this run",
     * which is not the same as "not authorized" and must not be rendered as one.
     */
    val cxrAuthorization: CxrAuthorizationOutcome? = null,
    /**
     * Whether the transport has reported a live glasses session in this process (`HostCompositionRoot.linkReady`).
     *
     * Not a live heartbeat: it is set once, never re-read, and the SDK's disconnect callback is not wired (spec
     * 不做/未测). The page's wording relies on that boundary - it says 已连接眼镜, never 在线.
     */
    val cxrLinkReady: Boolean = false,
)

class SessionViewModel(private val root: HostCompositionRoot) : ViewModel() {
    val state: StateFlow<SessionUiState> = root.state
    fun start(pluginId: String) = root.start(pluginId)
    fun pause() = root.pause()
    fun resume() = root.resume()
    fun stop() = root.stop()
    fun selectPlugin(pluginId: String) = root.selectPlugin(pluginId)
}
