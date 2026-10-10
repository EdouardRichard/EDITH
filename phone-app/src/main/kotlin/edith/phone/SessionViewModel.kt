package edith.phone

import androidx.lifecycle.ViewModel
import edith.contracts.SessionState
import edith.plugin.Capability
import kotlinx.coroutines.flow.StateFlow

/** P3.3 (spec §17.4): the question the session page is showing, if one is up. */
data class PendingConfirmation(val requestId: String, val summary: String)

data class SessionUiState(
    val session: SessionState,
    val selectedPluginId: String?,
    val capabilityReasons: Map<Capability, String>,
    val errorCode: String?,
    /** The confirmation currently awaiting an answer on the phone, or null. */
    val pendingConfirmation: PendingConfirmation? = null,
    /** P3.3 (spec §17.2 rule 1): the execution channels, named individually so a down one cannot hide. */
    val executionChannels: List<ExecutionChannelStatus> = emptyList(),
)

class SessionViewModel(private val root: HostCompositionRoot) : ViewModel() {
    val state: StateFlow<SessionUiState> = root.state
    fun start(pluginId: String) = root.start(pluginId)
    fun pause() = root.pause()
    fun resume() = root.resume()
    fun stop() = root.stop()
    fun selectPlugin(pluginId: String) = root.selectPlugin(pluginId)
}
