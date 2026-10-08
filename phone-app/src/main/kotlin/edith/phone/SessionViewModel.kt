package edith.phone

import androidx.lifecycle.ViewModel
import edith.contracts.SessionState
import edith.plugin.Capability
import kotlinx.coroutines.flow.StateFlow

data class SessionUiState(
    val session: SessionState,
    val selectedPluginId: String?,
    val capabilityReasons: Map<Capability, String>,
    val errorCode: String?,
)

class SessionViewModel(private val root: HostCompositionRoot) : ViewModel() {
    val state: StateFlow<SessionUiState> = root.state
    fun start(pluginId: String) = root.start(pluginId)
    fun pause() = root.pause()
    fun resume() = root.resume()
    fun stop() = root.stop()
    fun selectPlugin(pluginId: String) = root.selectPlugin(pluginId)
}
