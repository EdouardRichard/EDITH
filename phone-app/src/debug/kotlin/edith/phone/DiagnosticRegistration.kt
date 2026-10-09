package edith.phone

import edith.plugin.DeadlineScheduler
import edith.plugin.MonotonicClock
import edith.plugins.diagnostic.DiagnosticHintPlugin
import edith.plugins.diagnostic.DiagnosticTextPlugin

/**
 * Debug-only registrations.
 *
 * Both plugins exist so a developer can drive a path that the shipped shells do not drive yet: the text plugin owns
 * the trace/latency harness, the hint plugin submits fixed, non-sensitive sentences so a wearer can check what the
 * glasses actually show. Returns the hint sender (null when the probe is not registered), so the UI can offer it
 * without knowing which build it is in.
 */
internal object DiagnosticRegistration {
    fun register(root: HostCompositionRoot, clock: MonotonicClock, scheduler: DeadlineScheduler): (() -> Boolean)? {
        // Registered through the composition rather than straight on the host, so the activation side effects can
        // see what these plugins declare (Task 14a-fix item 4).
        root.registerPlugin(DiagnosticTextPlugin(clock, scheduler, root.trace, root.profiles::get,
            hostTextAllowed = { false }, cancelOutputTurn = root.host::cancelOutputTurn,
            cancelOutputHint = root.host::cancelOutputHint))
        val hintProbe = DiagnosticHintPlugin(clock)
        root.registerPlugin(hintProbe)
        return hintProbe::submitNext
    }
}
