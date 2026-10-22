package com.sheen.rokid.model

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.*
import kotlin.test.*

class ContextBudgeterTest {
    private val budgeter = ContextBudgeter()
    private fun smallProfile(budget: Int = 220) = profileFixture().copy(inputBudgetTokens = budget)
    private fun required() = requestFixture(listOf(InputPart.Text("q".repeat(60)))).copy(instructions = "i".repeat(40))

    @Test fun `old complete history removed while required text stays intact`() {
        val request = required().copy(history = listOf(ModelHistoryItem.Text(MessageRole.USER, "u".repeat(100)),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "a".repeat(100))))
        val result = budgeter.prepare(smallProfile(), request)
        assertEquals(listOf("history:0"), result.removedIds)
        assertEquals(emptyList(), result.request.history)
        assertEquals(request.instructions, result.request.instructions)
        assertEquals(request.input, result.request.input)
        assertTrue(result.estimated)
        assertTrue(result.estimatedInputTokens <= 220)
        assertTrue(result.estimatedInputTokens.toLong() + 256 + 128 <= 4096)
        assertEquals(2, request.history.size)
    }

    @Test fun `oldest pair ids remain original after multiple removals`() {
        val history = (0..2).flatMap {
            listOf(ModelHistoryItem.Text(MessageRole.USER, "u".repeat(50)),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "a".repeat(50)))
        }
        // Token units: the base request is 121 and each 50-character pair is 90. Removing one pair leaves 301, so a
        // 300 budget forces the second pair out as well; the byte-era 365 would have stopped after one removal.
        val result = budgeter.prepare(smallProfile(300), required().copy(history = history))
        assertEquals(listOf("history:0", "history:1"), result.removedIds)
        assertEquals(history.takeLast(2), result.request.history)
    }

    @Test fun `history is removed to fit within budget while preserving required input`() {
        val request = required().copy(history = listOf(ModelHistoryItem.Text(MessageRole.USER, "u".repeat(100)), ModelHistoryItem.Text(MessageRole.ASSISTANT, "a".repeat(100))))
        val result = budgeter.prepare(smallProfile(220), request)
        assertEquals(listOf("history:0"), result.removedIds)
        assertEquals(emptyList(), result.request.history)
        assertEquals(request.input, result.request.input)
    }

    @Test fun `required input over limit is rejected without truncation`() {
        assertFailsWith<ContractError.ContextWindowExceeded> { budgeter.prepare(smallProfile(99), required()) }
    }

    @Test fun `a history only continuation that cannot keep any history is rejected`() {
        // The handoff after a tool round is a history-only request: empty `input`, the transcript as history. When the
        // transcript is one user turn that does not fit, eviction can only drop that whole group, and the budgeted
        // request would then carry neither `input` nor `history` — which `ModelRequest` itself rejects. The budgeter
        // must fail through its own `rejected()` path instead of letting a raw `IllegalArgumentException` escape.
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "u"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "a"),
            ModelHistoryItem.ToolCall("call_1", "record_note", "{}"),
            ModelHistoryItem.ToolOutput("call_1", "o".repeat(200)),
        )
        val request = requestFixture(input = emptyList(), history = history).copy(instructions = "i".repeat(40))
        // Token units price this request at 258: 250 is under it but still over the 74 the budget refuses to leave
        // behind once the only user turn is gone, so the request reaches the "nothing left to send" rejection.
        assertFailsWith<ContractError.ConfigurationRejected> { budgeter.prepare(smallProfile(250), request) }
    }

    @Test fun `a Chinese text is priced by TokenMeter not by UTF-8 bytes`() {
        // Each "你好" is six UTF-8 bytes but two token units (one per CJK code point), so the old byte estimator
        // priced this request at 32 + (6 + 32) + (6 + 32) = 108. TokenMeter prices it at
        // 32 + (2 + 32) + (2 + 32) = 100. The bound is the old byte count itself: it can only pass once the request
        // text is priced by TokenMeter.
        val profile = profileFixture().copy(
            inputBudgetTokens = 150,
            usableWindowTokens = 1_000,
            maxOutputTokens = 100,
            safetyTokens = 50,
        )
        val request = requestFixture(listOf(InputPart.Text("你好"))).copy(instructions = "你好")
        val prepared = budgeter.prepare(profile, request)
        assertEquals(100, prepared.estimatedInputTokens)
        assertTrue(prepared.estimatedInputTokens < 108, "the byte count (108) must no longer be the estimate")
    }

    @Test fun `token units and framing are counted even for empty optional context`() {
        val result = budgeter.prepare(profileFixture(), requestFixture(listOf(InputPart.Text("\u4e2d"))).copy(instructions = "i"))
        assertEquals(98, result.estimatedInputTokens) // 1 + 1 tokens + 32 request + 2 * 32 message framing.
        assertTrue(result.estimated)
        assertFailsWith<ContractError.ContextWindowExceeded> {
            budgeter.prepare(smallProfile(97), result.request)
        }
    }

    @Test fun `malformed history is never half cropped`() {
        for (history in listOf(listOf(ModelHistoryItem.Text(MessageRole.USER, "x")),
            listOf(ModelHistoryItem.Text(MessageRole.ASSISTANT, "x"), ModelHistoryItem.Text(MessageRole.USER, "y")),
            listOf(ModelHistoryItem.Text(MessageRole.USER, "x"), ModelHistoryItem.Text(MessageRole.USER, "y")))) {
            assertFailsWith<ContractError.ConfigurationRejected> { budgeter.prepare(smallProfile(), required().copy(history = history)) }
        }
    }

    @Test fun `audio without an estimator is still missing even when optional history is removable`() {
        // 0.25 admits images (ContextBudgeterImageTest pins their cost); audio encoding is not part of this version, so
        // an audio part keeps the pre-0.25 refusal whatever the profile claims to support.
        assertFailsWith<ContractError.CapabilityMissing> {
            budgeter.prepare(
                profileFixture(inputs = InputModality.entries.toSet()),
                requestFixture(listOf(InputPart.Audio("local:dummy"))),
            )
        }
    }

    @Test fun `large valid int window does not overflow combined accounting`() {
        val profile = profileFixture().copy(usableWindowTokens = Int.MAX_VALUE, inputBudgetTokens = 1,
            maxOutputTokens = Int.MAX_VALUE - 2, safetyTokens = 1)
        assertFailsWith<ContractError.ContextWindowExceeded> { budgeter.prepare(profile, requestFixture()) }
    }

    @Test fun `profile mismatch is rejected by budget preparation`() {
        assertFailsWith<ContractError.ConfigurationRejected> {
            budgeter.prepare(profileFixture(), requestFixture().copy(profileRevision = 0))
        }
    }

    @Test fun `prepared request owns list snapshots`() {
        val input = mutableListOf<InputPart>(InputPart.Text("synthetic"))
        val history = mutableListOf<ModelHistoryItem>(ModelHistoryItem.Text(MessageRole.USER, "old"), ModelHistoryItem.Text(MessageRole.ASSISTANT, "reply"))
        val result = budgeter.prepare(profileFixture(), requestFixture(input).copy(history = history))
        input.clear(); history.clear()
        assertEquals(listOf(InputPart.Text("synthetic")), result.request.input)
        assertEquals(2, result.request.history.size)
    }

    @Test fun `tool exchange after an assistant text item is accepted`() {
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "remember this"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "calling record_note"),
            ModelHistoryItem.ToolCall("call_1", "record_note", """{"title":"call back"}"""),
            ModelHistoryItem.ToolOutput("call_1", "noted"),
            ModelHistoryItem.Text(MessageRole.USER, "what did you record?"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "the call back note"),
        )
        val result = budgeter.prepare(profileFixture(), required().copy(history = history))
        assertEquals(history, result.request.history)
        assertTrue(result.removedIds.isEmpty())
    }

    @Test fun `tool run without a preceding assistant text item is rejected`() {
        // Each shape starts a tool run whose preceding text item is not an assistant message (or which has no
        // preceding item at all), so the call is orphaned from the turn that produced it.
        for (history in listOf(
            listOf(ModelHistoryItem.ToolCall("call_1", "record_note", "{}")),
            listOf(ModelHistoryItem.Text(MessageRole.USER, "q"), ModelHistoryItem.ToolCall("call_1", "record_note", "{}")),
            listOf(ModelHistoryItem.Text(MessageRole.USER, "q"), ModelHistoryItem.Text(MessageRole.ASSISTANT, "a"),
                ModelHistoryItem.ToolCall("call_1", "record_note", "{}"),
                ModelHistoryItem.Text(MessageRole.USER, "q2"), ModelHistoryItem.ToolOutput("call_1", "noted")),
        )) {
            assertFailsWith<ContractError.ConfigurationRejected> {
                budgeter.prepare(profileFixture(), required().copy(history = history))
            }
        }
    }

    @Test fun `trailing text after a tool run is not a whole group`() {
        // The run itself is well placed after the assistant text, but the trailing lone USER item leaves a half
        // exchange that whole-group eviction could not drop; the shape is rejected for that, not for placement.
        for (history in listOf(
            listOf(ModelHistoryItem.Text(MessageRole.USER, "q"), ModelHistoryItem.Text(MessageRole.ASSISTANT, "a"),
                ModelHistoryItem.ToolCall("call_1", "record_note", "{}"), ModelHistoryItem.Text(MessageRole.USER, "q2")),
            listOf(ModelHistoryItem.Text(MessageRole.USER, "q"), ModelHistoryItem.Text(MessageRole.ASSISTANT, "a"),
                ModelHistoryItem.ToolOutput("call_1", "noted"), ModelHistoryItem.Text(MessageRole.USER, "q2")),
        )) {
            assertFailsWith<ContractError.ConfigurationRejected> {
                budgeter.prepare(profileFixture(), required().copy(history = history))
            }
        }
    }

    @Test fun `tool exchange is removed as one group when over budget`() {
        // The oldest group is removed whole: dropping only its text pair would orphan the call from the turn that
        // produced it, which the alternation rule forbids. The surviving group must stay intact.
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "u".repeat(100)),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "a".repeat(100)),
            ModelHistoryItem.ToolCall("call_1", "record_note", """{"title":"${"t".repeat(100)}"}"""),
            ModelHistoryItem.ToolOutput("call_1", "o".repeat(100)),
            ModelHistoryItem.Text(MessageRole.USER, "q".repeat(10)),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "r".repeat(10)),
        )
        val result = budgeter.prepare(smallProfile(300), required().copy(history = history))
        assertEquals(listOf("history:0"), result.removedIds)
        assertEquals(history.drop(4), result.request.history)
        assertTrue(result.estimatedInputTokens <= 300)
    }

    // ------------------------------------------------------- the relaxed text rule (Task 9a)

    @Test fun `an assistant text may continue the same turn after a tool round`() {
        // A real assistant turn spans several tool rounds with no user text between them, which is exactly how the
        // stateless endpoint's `input` array works. The text items are USER, ASSISTANT, ASSISTANT — not alternating —
        // because the second assistant text continues the turn the first one opened.
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "remember this"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "calling record_note"),
            ModelHistoryItem.ToolCall("call_1", "record_note", """{"title":"call back"}"""),
            ModelHistoryItem.ToolOutput("call_1", "noted"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "now the calendar"),
            ModelHistoryItem.ToolCall("call_2", "read_calendar", "{}"),
            ModelHistoryItem.ToolOutput("call_2", "free at 5"),
        )
        val result = budgeter.prepare(profileFixture(), required().copy(history = history))
        assertEquals(history, result.request.history)
        assertTrue(result.removedIds.isEmpty())
    }

    @Test fun `an assistant text may close a tool round`() {
        // The canonical completion of a tool round: the assistant calls a tool and then answers. The trailing lone
        // assistant text is a whole assistant turn, so the old "trailing text has no partner" rejection must not fire.
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "remember this"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "calling record_note"),
            ModelHistoryItem.ToolCall("call_1", "record_note", """{"title":"call back"}"""),
            ModelHistoryItem.ToolOutput("call_1", "noted"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "the note is recorded"),
        )
        val result = budgeter.prepare(profileFixture(), required().copy(history = history))
        assertEquals(history, result.request.history)
        assertTrue(result.removedIds.isEmpty())
    }

    @Test fun `a user turn that spans two tool rounds is one group and is evicted whole`() {
        // The second tool round has no `USER` text before it: it continues the same assistant turn, so the group
        // boundary — a group opens only at `Text(USER)` — keeps it inside the user turn that produced it. Eviction
        // drops that whole seven-item turn at once; cropping at the second assistant text (`history.drop(4)`) would
        // leave a leading assistant text the shape rule rejects and would lose the user turn from the wire.
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "u".repeat(100)),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "a".repeat(100)),
            ModelHistoryItem.ToolCall("call_1", "record_note", """{"title":"${"t".repeat(100)}"}"""),
            ModelHistoryItem.ToolOutput("call_1", "o".repeat(100)),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "second"),
            ModelHistoryItem.ToolCall("call_2", "record_note", "{}"),
            ModelHistoryItem.ToolOutput("call_2", "noted"),
            ModelHistoryItem.Text(MessageRole.USER, "q".repeat(10)),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "r".repeat(10)),
        )
        val result = budgeter.prepare(smallProfile(400), required().copy(history = history))
        assertEquals(listOf("history:0"), result.removedIds)
        assertEquals(history.drop(7), result.request.history)
        assertEquals(MessageRole.USER, (result.request.history.first() as ModelHistoryItem.Text).role)
        assertTrue(result.estimatedInputTokens <= 400)
    }

    @Test fun `a history only continuation that fits is prepared unchanged`() {
        // The full post-tool handoff: no `input` of its own, the whole `[USER, ASSISTANT, ToolCall, ToolOutput]`
        // transcript as history. Nothing needs evicting, so the budgeted request still carries that transcript.
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "ask"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "calling record_note"),
            ModelHistoryItem.ToolCall("call_1", "record_note", "{}"),
            ModelHistoryItem.ToolOutput("call_1", "noted"),
        )
        val request = requestFixture(input = emptyList(), history = history).copy(instructions = "i".repeat(40))
        val result = budgeter.prepare(profileFixture(), request)
        assertTrue(result.request.input.isEmpty())
        assertEquals(history, result.request.history)
        assertTrue(result.removedIds.isEmpty())
    }

    @Test fun `the full relaxed transcript with a closing assistant text is accepted`() {
        // The whole shape the loop produces over two tool rounds: `[USER, ASSISTANT, call, output, ASSISTANT, call,
        // output, ASSISTANT]`. The closing assistant text ends the turn, and the second assistant text continues it;
        // the text items are therefore USER, ASSISTANT, ASSISTANT, ASSISTANT — not alternating.
        val history = listOf(
            ModelHistoryItem.Text(MessageRole.USER, "remember this"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "calling record_note"),
            ModelHistoryItem.ToolCall("call_1", "record_note", "{}"),
            ModelHistoryItem.ToolOutput("call_1", "noted"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "now the calendar"),
            ModelHistoryItem.ToolCall("call_2", "read_calendar", "{}"),
            ModelHistoryItem.ToolOutput("call_2", "free at 5"),
            ModelHistoryItem.Text(MessageRole.ASSISTANT, "you are free at 5"),
        )
        val result = budgeter.prepare(profileFixture(), required().copy(history = history))
        assertEquals(history, result.request.history)
        assertTrue(result.removedIds.isEmpty())
    }

    @Test fun `an assistant text still cannot open the history`() {
        // Relaxing the continuation rule must not licence a history that starts mid-turn: no leading user text is
        // still a malformed transcript, whether or not a tool call follows the assistant text.
        for (history in listOf(
            listOf(ModelHistoryItem.Text(MessageRole.ASSISTANT, "a")),
            listOf(ModelHistoryItem.Text(MessageRole.ASSISTANT, "a"), ModelHistoryItem.ToolCall("call_1", "record_note", "{}")),
            listOf(ModelHistoryItem.Text(MessageRole.ASSISTANT, "a"), ModelHistoryItem.Text(MessageRole.USER, "q")),
        )) {
            assertFailsWith<ContractError.ConfigurationRejected> {
                budgeter.prepare(profileFixture(), required().copy(history = history))
            }
        }
    }

    @Test fun `a tool round still cannot continue a user text`() {
        // The tool-run opener rule is untouched: a call directly after the user's turn is orphaned from the assistant
        // turn that must have produced it, even though the text rule is now more permissive.
        for (history in listOf(
            listOf(ModelHistoryItem.Text(MessageRole.USER, "q"), ModelHistoryItem.ToolCall("call_1", "record_note", "{}")),
            listOf(ModelHistoryItem.Text(MessageRole.USER, "q"), ModelHistoryItem.ToolOutput("call_1", "noted")),
        )) {
            assertFailsWith<ContractError.ConfigurationRejected> {
                budgeter.prepare(profileFixture(), required().copy(history = history))
            }
        }
    }
}
