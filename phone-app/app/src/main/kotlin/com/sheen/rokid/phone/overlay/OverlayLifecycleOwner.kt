package com.sheen.rokid.phone.overlay

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner

/**
 * 0.5: the minimal lifecycle/saved-state owner a `ComposeView` hosted by a `Service` needs.
 *
 * A Compose window in a Service has no Activity to provide `ViewTreeLifecycleOwner`, so the service owns one of these
 * and drives it: created when the windows are added, destroyed when the service stops. It is intentionally small - no
 * ViewModelStore, because the overlay keeps all of its state in the service and the repository, not in a ViewModel.
 */
class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateController.savedStateRegistry

    fun onCreate() {
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onStart() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onResume() {
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    fun onPause() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
    }

    fun onStop() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }
}
