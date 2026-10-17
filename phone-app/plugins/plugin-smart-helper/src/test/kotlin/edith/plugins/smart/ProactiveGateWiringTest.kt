package edith.plugins.smart

import edith.contracts.AcousticEvent
import edith.contracts.AcousticLabel
import edith.contracts.ContextChanged
import edith.contracts.TranscriptEvent
import edith.plugins.smart.proactive.GateForm
import edith.plugins.smart.proactive.GateOutcome
import edith.plugins.smart.proactive.ProactiveGate
import edith.plugins.smart.proactive.ProactiveGateState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec §4.7: the gate sits **after** P3's own admission and throttle — arming, sensitivity, cooldown, dedup — so it
 * only sees a candidate that already passed them, and it replaces the cloud model's decision rather than adding to it.
 *
 * The other half is the C10 invariant's teeth: because the state contains no time feature, a `ContextChanged` is the
 * only thing that can re-open a question the transcript alone had settled.
 */
class ProactiveGateWiringTest {

    private class RecordingGate(private val intervene: Boolean) : ProactiveGate {
        val seen = mutableListOf<ProactiveGateState>()
        override val form = GateForm.NOUL
        override fun shouldIntervene(state: ProactiveGateState): GateOutcome {
            seen += state
            return GateOutcome(intervene, 0.9, 231.0)
        }
    }

    private fun rig(gate: ProactiveGate?): SmartTestRig {
        val rig = SmartTestRig(interventionGate = gate)
        rig.activate()
        rig.model.onRequest = { request, listener ->
            listener(smartToolCall("c1", "propose_hint", """{"text":"要不要我帮你？"}"""))
            listener(
                edith.plugin.ModelStreamEvent.Terminal(
                    request.requestId, edith.plugin.ModelEnd.COMPLETED, null, null,
                ),
            )
        }
        return rig
    }

    private fun arm(rig: SmartTestRig) {
        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
    }

    @Test
    fun `a disarmed trigger never reaches the gate`() {
        val gate = RecordingGate(intervene = true)
        val rig = rig(gate)

        // No AcousticEvent: the host produced no perception, so this session is not armed.
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了")))

        assertEquals(emptyList(), gate.seen, "the first stage must refuse before the gate is consulted")
        assertTrue(rig.model.requests.isEmpty())
    }

    @Test
    fun `the gate is consulted for a trigger the first stage admitted and it decides`() {
        val gate = RecordingGate(intervene = true)
        val rig = rig(gate)

        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))

        assertEquals(1, gate.seen.size, "the admitted trigger reaches the gate once")
        assertEquals(1, rig.model.requests.size, "an intervening gate lets the turn proceed")
    }

    @Test
    fun `a gate that declines keeps the cloud model out of it`() {
        val gate = RecordingGate(intervene = false)
        val rig = rig(gate)

        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))

        assertEquals(1, gate.seen.size)
        assertTrue(rig.model.requests.isEmpty(), "a declined trigger must cost nothing")
        assertTrue(rig.hintTexts.isEmpty())
    }

    @Test
    fun `a context change after an utterance re-evaluates the gate`() {
        // This is what the C10 invariant buys: with no time feature in the state, the only way the answer can change
        // is a change in an input dimension - and a foreground switch is one.
        val gate = RecordingGate(intervene = true)
        val rig = rig(gate)
        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))
        val afterTranscript = gate.seen.size

        // The first stage's cooldown is part of the contract (C11), so the clock has to clear it before the second
        // evaluation is even a candidate; the dedup key includes the foreground, which the change moves.
        rig.tick(61_000)
        rig.plugin.handle(ContextChanged(rig.envelope(), 2, mapOf("app.foreground" to "设置")))

        assertEquals(afterTranscript + 1, gate.seen.size, "a context change must re-open the question")
    }

    @Test
    fun `a context change with no utterance behind it is not a trigger`() {
        // The state's leading field is the transcript; without one there is nothing to judge, and the gate would be
        // called once per context change to answer the same empty question.
        val gate = RecordingGate(intervene = true)
        val rig = rig(gate)
        arm(rig)

        rig.plugin.handle(ContextChanged(rig.envelope(), 2, mapOf("app.foreground" to "设置")))

        assertEquals(emptyList(), gate.seen)
    }

    @Test
    fun `a plugin with no gate behaves exactly as before`() {
        val rig = rig(null)

        arm(rig)
        rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))

        assertEquals(1, rig.model.requests.size)
    }

    @Test
    fun `the gate log line carries the verdict and never the state`() {
        val rig = rig(RecordingGate(intervene = true))
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            arm(rig)
            rig.plugin.handle(TranscriptEvent(rig.transcript("这个页面怎么一直转圈")))
        } finally {
            P3Log.sink = previous
        }

        val gateLine = lines.single { it.startsWith("P3_PROACTIVE gate") }
        assertTrue(gateLine.contains("intervene=true"), gateLine)
        assertTrue("转圈" !in gateLine, "the log carries the verdict, never the state: $gateLine")
    }
}
