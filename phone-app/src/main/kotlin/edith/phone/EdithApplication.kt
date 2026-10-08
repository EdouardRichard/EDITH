package edith.phone

import android.app.Application

open class EdithApplication : Application() {
    val composition: HostCompositionRoot by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { createComposition() }
    protected open fun createComposition(): HostCompositionRoot = HostCompositionRoot.create(this)
}
