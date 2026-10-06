package edith.phone

import edith.plugin.DeadlineScheduler
import edith.plugin.MonotonicClock
import edith.plugins.diagnostic.DiagnosticTextPlugin

internal object DiagnosticRegistration {
    fun register(root: HostCompositionRoot, clock: MonotonicClock, scheduler: DeadlineScheduler) {
        root.host.register(DiagnosticTextPlugin(clock, scheduler, root.trace, root.profiles::get,
            hostTextAllowed = { false }, cancelOutputTurn = root.host::cancelOutputTurn,
            cancelOutputHint = root.host::cancelOutputHint))
    }
}
