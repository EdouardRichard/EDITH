package com.sheen.rokid.phone.app

import android.app.Application

open class EdithApplication : Application() {
    /**
     * The App's one composition, built on first use.
     *
     * The lazy construction is manual rather than `by lazy` so [liveComposition] can exist: a notification tap arrives
     * in a `BroadcastReceiver` that must be able to ask "is there a composition?" **without building one** - the App's
     * graph opens blobs, a keystore-backed vault and engine provisioning, and a receiver has seconds, not a session.
     */
    val composition: HostCompositionRoot
        get() = built ?: synchronized(this) { built ?: createComposition().also { built = it } }

    @Volatile
    private var built: HostCompositionRoot? = null

    /** The composition only if something already asks for it; null before [composition] was first used. */
    val liveComposition: HostCompositionRoot? get() = built

    protected open fun createComposition(): HostCompositionRoot = HostCompositionRoot.create(this)
}
