package edith.phone

import edith.plugin.DeadlineScheduler
import edith.plugin.MonotonicClock

internal object DiagnosticRegistration {
    fun register(root: HostCompositionRoot, clock: MonotonicClock, scheduler: DeadlineScheduler) = Unit
}
