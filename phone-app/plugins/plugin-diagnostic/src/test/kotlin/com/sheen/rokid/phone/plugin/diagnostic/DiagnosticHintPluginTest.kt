package com.sheen.rokid.phone.plugin.diagnostic

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.HostServices
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.OutputSink
import com.sheen.rokid.plugin.OutputSubmission
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.TaskScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The debug-only hint probe.
 *
 * It exists because plan Task 18 Step 4 requires ten synthetic short hints to be read while worn, and nothing in the
 * product submits a hint yet. What must hold: it submits through the sink the **host** gave it (that is the only path
 * into the real arbiter), it advances through the fixed list, it refuses cleanly when it is not active, and it never
 * throws into a UI callback.
 */
class DiagnosticHintPluginTest {

    private class FakeClock : MonotonicClock {
        override fun nowNanos(): Long = 1_000_000_000L
    }

    private class RecordingSink(var accept: Boolean = true) : OutputSink {
        val submitted = mutableListOf<Assistance>()
        override fun submit(assistance: Assistance): OutputSubmission {
            submitted += assistance
            return if (accept) OutputSubmission.Accepted else OutputSubmission.Rejected
        }
    }

    private fun contextWith(sink: OutputSink, session: String = "s-1"): PluginContext {
        val services = object : HostServices {
            override fun require(capability: Capability): Any =
                if (capability == Capability.TextOutput) sink else error("unexpected: $capability")
            override val tasks: TaskScope = object : TaskScope {
                override fun launch(block: () -> Unit): CancellationHandle {
                    block()
                    return object : CancellationHandle { override fun cancel() = Unit }
                }
                override fun cancelAll() = Unit
            }
        }
        return PluginContext(SessionId(session), Generation(1), listOf(Capability.TextOutput).toSet(), services)
    }

    @Test
    fun theProbeDeclaresOnlyTheDisplayCapabilityItActuallyUses() {
        val plugin = DiagnosticHintPlugin(FakeClock())

        assertEquals(setOf(Capability.TextOutput), plugin.descriptor.capabilities)
    }

    @Test
    fun submittingRequiresAnActivePluginAndReportsAcceptanceHonestly() {
        val sink = RecordingSink()
        val plugin = DiagnosticHintPlugin(FakeClock())

        assertFalse(plugin.submitNext(), "an inactive probe must not claim it submitted anything")

        plugin.activate(contextWith(sink))
        assertTrue(plugin.submitNext())
        assertTrue(plugin.submitNext())
        assertEquals(2, sink.submitted.size)

        plugin.deactivate(DeactivationReason.Stopped)
        assertFalse(plugin.submitNext(), "a deactivated probe must stop submitting")
        assertEquals(2, sink.submitted.size)
    }

    @Test
    fun eachSubmissionCarriesTheNextFixedNonSensitiveSentence() {
        val sink = RecordingSink()
        val plugin = DiagnosticHintPlugin(FakeClock())
        plugin.activate(contextWith(sink))

        repeat(DiagnosticHintPlugin.HINTS.size) { plugin.submitNext() }

        assertEquals(DiagnosticHintPlugin.HINTS, sink.submitted.map { it.text })
        assertTrue(sink.submitted.all { it.text.length <= 24 },
            "the sentences must be short enough for the fixed layout")
        assertTrue(sink.submitted.all { it.expiresAtNanos > 0 },
            "and they must carry a deadline the glasses can expire")
    }

    @Test
    fun aRefusedSubmissionIsReportedRatherThanSwallowed() {
        val sink = RecordingSink(accept = false)
        val plugin = DiagnosticHintPlugin(FakeClock())
        plugin.activate(contextWith(sink))

        assertFalse(plugin.submitNext(), "a refused hint must not be reported as sent")
    }

    @Test
    fun aFailingSinkNeverThrowsIntoTheCaller() {
        val failing = object : OutputSink {
            override fun submit(assistance: Assistance): OutputSubmission =
                throw IllegalStateException("synthetic display failure")
        }
        val plugin = DiagnosticHintPlugin(FakeClock())
        plugin.activate(contextWith(failing))

        assertFalse(plugin.submitNext())
    }

    @Test
    fun handleIsAnAcceptedNoOpSoTheProbeCannotConsumeBusinessEvents() {
        val sink = RecordingSink()
        val plugin = DiagnosticHintPlugin(FakeClock())
        plugin.activate(contextWith(sink))

        // The probe is not a business plugin; a host event must change nothing at all.
        plugin.handle(UserAction(EventEnvelope(SessionId("s-1"), Generation(1), "e-1", 0L, 0L), UserAction.Action.Help))

        assertEquals(0, sink.submitted.size)
    }

    /**
     * P3.2 Stage 0: the capacity probe must drive the panel with a hint of a **known length**, which the fixed list
     * cannot do. The break this catches: an entry point that silently reshapes or trims the text (the measurement
     * would then be of the wrong length), one that submits while inactive, and one that disturbs the fixed-list
     * rotation the worn-readability procedure depends on.
     */
    @Test
    fun aGivenTextIsSubmittedUnchangedAndDoesNotDisturbTheFixedList() {
        val sink = RecordingSink()
        val plugin = DiagnosticHintPlugin(FakeClock())

        assertFalse(plugin.submitText("字".repeat(30)), "an inactive probe must not claim it submitted anything")

        plugin.activate(contextWith(sink))
        assertFalse(plugin.submitText("   \n  "), "a blank hint must be refused, not rendered as an empty line")
        assertEquals(0, sink.submitted.size)

        val probe = "字".repeat(120)
        assertTrue(plugin.submitText(probe))
        assertEquals(1, sink.submitted.size)
        assertEquals(probe, sink.submitted.single().text, "the measured text must reach the display unchanged")
        assertEquals(120, sink.submitted.single().text.length)

        // The next fixed sentence is still the first one: the probe's own list must not have advanced.
        assertTrue(plugin.submitNext())
        assertEquals(DiagnosticHintPlugin.HINTS.first(), sink.submitted.last().text)
    }

    @Test
    fun aGivenTextThatTheSinkRefusesIsNotReportedAsSubmitted() {
        val sink = RecordingSink(accept = false)
        val plugin = DiagnosticHintPlugin(FakeClock())
        plugin.activate(contextWith(sink))

        assertFalse(plugin.submitText("字".repeat(60)))
    }
}
