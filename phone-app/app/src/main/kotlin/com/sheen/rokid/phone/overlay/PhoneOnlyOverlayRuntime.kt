package com.sheen.rokid.phone.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 0.5.1 (`C12`/`C14`): the phone-only overlay's published run state, process-wide.
 *
 * [PhoneOnlyOverlayService] and the settings page live in the same process, so this is the truthful channel between
 * them: the service publishes only what it actually did (windows up, failed with a reason, stopped), and the page reads
 * it instead of inferring "enabled" from `Settings.canDrawOverlays`. It is app-internal state - no cross-process or
 * plugin API.
 *
 * Writes happen on the service's main thread; [StateFlow] is safe to read from Compose. The state survives one service
 * instance on purpose: a failed start destroys the service, and the failure must still be visible (with its retry) after
 * that instance is gone.
 */
internal object PhoneOnlyOverlayRuntime {

    private val running = MutableStateFlow(false)
    private val failure = MutableStateFlow<PhoneOnlyStartFailure?>(null)

    /** True only while the service has both windows up. */
    val isRunning: StateFlow<Boolean> = running.asStateFlow()

    /** The last start failure, or null; kept across the failed instance's teardown so the page can offer the retry. */
    val startFailure: StateFlow<PhoneOnlyStartFailure?> = failure.asStateFlow()

    /**
     * A start is being requested (sync/retry). The previous failure no longer describes this attempt; if this attempt
     * also fails it publishes the new one.
     */
    fun startRequested() {
        failure.value = null
    }

    /** The service finished adding both windows: this is the only transition that reads as 「已启用」. */
    fun running() {
        running.value = true
        failure.value = null
    }

    /** This instance could not start; [isRunning] stays false and the reason is published for the settings page. */
    fun failed(reason: PhoneOnlyStartFailure) {
        running.value = false
        failure.value = reason
    }

    /** The service is gone (normal stop, revoke, or a failed instance's teardown). A recorded failure stays visible. */
    fun stopped() {
        running.value = false
    }
}
