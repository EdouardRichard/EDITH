package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log

import com.sheen.rokid.contracts.OutputStateEvent
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.RiskLevel
import kotlin.test.*

/**
 * The `P3_` observability lines the smart plugin emits for Task 15's acceptance.
 *
 * Each test captures the module's own sink with a local collector (never stdout) and asserts both halves of the
 * brief's contract: the line carries exactly the fields it must, and the forbidden content - the transcript's text,
 * the hint's text, an executor detail that embeds user content - is absent. The plugin's behaviour is not asserted
 * here; the existing suites own that.
 */
class SmartObservabilityTest {

    /** Swaps [P3Log]'s sink for a collector and always restores it, so one test cannot leak into the next. */
    private fun <T> withLines(block: (MutableList<String>) -> T): T {
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        return try {
            block(lines)
        } finally {
            P3Log.sink = previous
        }
    }

    @Test
    fun `a wake that opens a turn logs the turn identity only`() {
        val rig = SmartTestRig()
        rig.activate()

        val lines = withLines { captured ->
            rig.plugin.handle(rig.wakeEvent(word = "伊迪丝"))
            captured.toList()
        }

        assertEquals(1, lines.size, lines.toString())
        val line = lines.single()
        assertTrue(line.startsWith("P3_WAKE turn=smart-"), line)
        assertFalse(line.contains("伊迪丝"), "the plugin's wake line carries the turn, never the word: $line")
    }

    @Test
    fun `the adopted final transcript logs revision and char count but never its text`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }
        val marker = "MARKER-SECRET-4242"

        val lines = withLines { captured ->
            rig.plugin.handle(rig.wakeEvent())
            rig.plugin.handle(TranscriptEvent(rig.transcript(marker, turnId = "asr-7", revision = 4)))
            captured.toList()
        }

        val line = lines.single { it.startsWith("P3_TRANSCRIPT") }
        assertEquals("P3_TRANSCRIPT turn=asr-7 rev=4 final=true chars=${marker.length}", line)
        assertFalse(line.contains(marker), "the transcript text must never be logged: $line")
    }

    @Test
    fun `a turn logs its start and its completed terminal outcome`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "答案。") }

        val lines = withLines { captured ->
            rig.wakeAndFinal("问题", turnId = "asr-3")
            captured.toList()
        }

        assertEquals(
            listOf(
                "P3_TURN start turn=asr-3",
                "P3_TURN end turn=asr-3 outcome=COMPLETED code=- steps=0",
            ),
            lines.filter { it.startsWith("P3_TURN") },
        )
    }

    @Test
    fun `a failed terminal outcome logs its safe code`() {
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener ->
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.FAILED, null, "PROVIDER_DOWN"))
        }

        val lines = withLines { captured ->
            rig.wakeAndFinal("问题")
            captured.toList()
        }

        assertTrue(
            lines.contains("P3_TURN end turn=asr-1 outcome=FAILED code=PROVIDER_DOWN steps=0"),
            lines.toString(),
        )
    }

    @Test
    fun `each tool step logs its name outcome and safe detail but never user content`() {
        val rig = SmartTestRig()
        rig.actions.descriptors += smartAction("compose_note", risk = RiskLevel.L1, scope = "note")
        // A successful executor detail that embeds user content is exactly what must not reach the log: the loop
        // reduces a successful step to its own `ok` marker, so the note title stays out of the line.
        rig.actions.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "note written: MARKER-NOTE-TITLE") }
        rig.activate(maxLevel = "L1", whitelist = "compose_note|note")
        var turn = 0
        rig.model.onRequest = { request, listener ->
            turn += 1
            if (turn == 1) {
                listener(ModelStreamEvent.ToolCallTerminal(request.requestId, "fc-1", "call-1", "compose_note", "{}"))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "已经记好了。")
            }
        }

        val lines = withLines { captured ->
            rig.wakeAndFinal("帮我记一条笔记")
            rig.tick()
            captured.toList()
        }

        // Phase 3 Task 4 adds `P3_TOOLRESULT ...` on the same sink, so the space is part of the filter.
        val tool = lines.single { it.startsWith("P3_TOOL ") }
        assertEquals("P3_TOOL call=compose_note ok=true detail=ok", tool)
        assertFalse(tool.contains("MARKER-NOTE-TITLE"), "an executor detail with user content must not be logged: $tool")
        assertTrue(
            lines.contains("P3_TURN end turn=asr-1 outcome=COMPLETED code=- steps=1"),
            lines.toString(),
        )
    }

    @Test
    fun `a refused tool step logs the executor's own code`() {
        val rig = SmartTestRig()
        rig.actions.descriptors += smartAction("open_app", risk = RiskLevel.L1, scope = "app")
        rig.actions.outcome = { _, _ -> ActionResult(ActionOutcome.Refused, "APP_NOT_VISIBLE") }
        rig.activate(maxLevel = "L1", whitelist = "open_app|app")
        var turn = 0
        rig.model.onRequest = { request, listener ->
            turn += 1
            if (turn == 1) {
                listener(ModelStreamEvent.ToolCallTerminal(request.requestId, "fc-1", "call-1", "open_app", "{}"))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "没能打开。")
            }
        }

        val lines = withLines { captured ->
            rig.wakeAndFinal("打开这个应用")
            rig.tick()
            captured.toList()
        }

        assertEquals(
            "P3_TOOL call=open_app ok=false detail=APP_NOT_VISIBLE",
            lines.single { it.startsWith("P3_TOOL ") },
        )
    }

    @Test
    fun `a run that throws is reported by its exception class and never by its message`() {
        val rig = SmartTestRig()
        rig.activate()
        // A refusing provider throws synchronously, before any stream starts — exactly how the app's own capability
        // gate refuses a request. `PLUGIN_RUN_THREW` alone cannot say which layer refused; the class name can.
        rig.model.onRequest = { _, _ -> throw IllegalStateException("MARKER-PROVIDER-DETAIL") }

        val lines = withLines { captured ->
            rig.wakeAndFinal("问题")
            captured.toList()
        }

        assertEquals(
            "P3_TURN end turn=asr-1 outcome=FAILED code=PLUGIN_RUN_THREW steps=0",
            lines.single { it.startsWith("P3_TURN end") },
        )
        val cause = lines.single { it.startsWith("P3_RUN ") }
        // The class name alone cannot say *which* refusal it was (four different `ConfigurationRejected`s exist), so the
        // line also carries a shape-validated code. The invariant this test protects is unchanged and is the next
        // assertion: the exception's **message** never reaches the log. An unclassifiable message yields UNSPECIFIED
        // rather than the message itself, which is what makes that hold for text this codebase did not write.
        assertTrue(cause.startsWith("P3_RUN error=IllegalStateException code="), cause)
        assertFalse(cause.contains("MARKER-PROVIDER-DETAIL"), "the exception's message must never be logged: $cause")
    }

    @Test
    fun `the official ten second window closes the conversation without a turn outcome`() {
        // 0.24 S3 replaces the old "listening window expired -> INSUFFICIENT/NO_UTTERANCE hint" with the official
        // `RokidAIController.onTimeout`: close the panel, leave the session running, say nothing.
        val rig = SmartTestRig(panelVisible = true)
        rig.activate()

        val lines = withLines { captured ->
            rig.plugin.handle(rig.wakeEvent())
            rig.tick(millis = 10_000)
            captured.toList()
        }

        assertTrue(lines.any { it == "P3_CONV close route=WAKE reason=timeout" }, lines.toString())
        assertTrue(lines.none { it.startsWith("P3_TURN start") }, "nothing ran, so there is no start line: $lines")
        assertTrue(lines.none { it.startsWith("P3_TURN end") }, "the official timeout is not a turn outcome: $lines")
        assertTrue(rig.hintTexts.isEmpty(), "the official timeout must not submit the old failure sentence")
    }

    @Test
    fun `a hint logs its identity and size on submit and its status on the display ack`() {
        val rig = SmartTestRig()
        rig.activate()
        val marker = "MARKER-HINT-TEXT"
        rig.model.onRequest = { request, listener -> streamText(request, listener, marker) }

        val lines = withLines { captured ->
            rig.wakeAndFinal("问题")
            val hintId = rig.output.hints.single().hintId
            rig.plugin.handle(OutputStateEvent(rig.envelope(), hintId, OutputStatus.Displayed))
            captured.toList()
        }

        val hintId = rig.output.hints.single().hintId
        val submitted = lines.single { it.startsWith("P3_HINT show") }
        assertEquals("P3_HINT show hint=$hintId chars=${marker.length}", submitted)
        assertFalse(submitted.contains(marker), "the hint text must never be logged: $submitted")
        assertEquals(
            "P3_HINT ack hint=$hintId status=Displayed",
            lines.single { it.startsWith("P3_HINT ack") },
        )
    }
}
