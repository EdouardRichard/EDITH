package com.sheen.rokid.phone.plugin.diagnostic
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.MonotonicClock

/**
 * Release builds register nothing.
 *
 * The signature matches the debug variant so the composition calls one shape in both, and the null return is what
 * keeps the debug-only hint probe out of the UI: there is nothing to send hints from in a release build.
 */
internal object DiagnosticRegistration {
    fun register(root: HostCompositionRoot, clock: MonotonicClock, scheduler: DeadlineScheduler): (() -> Boolean)? = null
}
