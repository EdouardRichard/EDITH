package com.sheen.rokid.phone.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The bundled-model lifecycle the App UI and every session start path share (spec §6.3). */
sealed interface ProvisioningState {
    /** The first-start materialization is running; no session may be built or started. */
    data object Preparing : ProvisioningState

    /** The bundle is materialized and a session may use it. */
    data class Ready(val bundleId: String) : ProvisioningState

    /** Materialization failed; [reason] is a stable code, never model content or a path. */
    data class Failed(val reason: String) : ProvisioningState
}

/**
 * The one readiness state the App UI, the composition and the session start path share.
 *
 * **Ready is terminal for the process.** A background re-read that fails after the models already worked must not
 * take the working models away from the running App (spec §6.3: "已有可用模型时不得因后台校验失败而阻断"). The only
 * legitimate route back to [ProvisioningState.Preparing] is a new process (an APK change restarts it), whose
 * [ModelReadiness] starts in that state anyway.
 */
class ModelReadiness {
    private val _state = MutableStateFlow<ProvisioningState>(ProvisioningState.Preparing)
    val state: StateFlow<ProvisioningState> = _state.asStateFlow()

    fun markPreparing() {
        _state.update { current -> if (current is ProvisioningState.Ready) current else ProvisioningState.Preparing }
    }

    fun markReady(bundleId: String) {
        _state.value = ProvisioningState.Ready(bundleId)
    }

    fun markFailed(reason: String) {
        _state.update { current -> if (current is ProvisioningState.Ready) current else ProvisioningState.Failed(reason) }
    }
}

/**
 * The one pure decision `dispatch`/`start`/`activateSelected` use before touching the foreground service or the
 * session host.
 *
 * `null` means "this composition carries no readiness state" - the pre-0.4 behavior every hand-built harness keeps.
 */
object ModelStartGate {
    /** @return the stable error code to show, or null when the session may proceed. */
    fun refusal(state: ProvisioningState?): String? = when (state) {
        null, is ProvisioningState.Ready -> null
        ProvisioningState.Preparing -> "MODELS_PREPARING"
        is ProvisioningState.Failed -> "MODELS_UNAVAILABLE"
    }
}
