package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelStreamEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A turn that ended without an answer must still leave a transcript the **next** turn can carry.
 *
 * Found on the K40 on 2026-10-14 (Task 3, B8): after one turn ended `FAILED`, **every** later turn in that session
 * died with `outcome=FAILED code=PLUGIN_RUN_THREW`, `P3_RUN error=ConfigurationRejected code=Explicit context exceeds
 * safe budget or is malformed`, `steps=0`, until the app was restarted. The same message is what the 08 session
 * recorded as an unexplained transient ("16:13 连续两回合 `PLUGIN_RUN_THREW` … 重启 App 后恢复正常").
 *
 * The cause is here: `recordTurnAndCompress` appended the user's utterance unconditionally and the assistant's reply
 * only when the run produced text, so a failed (or cancelled) turn left the route's transcript ending on a user
 * message with no reply. The model path refuses that shape on purpose - `ContextBudgeter.prepare` rejects a trailing
 * one-item group, because whole-group eviction could never drop it - so the poison outlived the turn that caused it.
 */
class FailedTurnTranscriptTest {

    @Test
    fun `a turn that produced no answer still leaves an answerable transcript`() {
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.Storage to SmartFakeStorage()))
        var turn = 0
        rig.model.onRequest = { request, listener ->
            turn++
            if (turn == 1) {
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.FAILED, null, "provider_error"))
            } else {
                streamText(request, listener, "好了。")
            }
        }

        rig.wakeAndFinal("帮我看看", turnId = "asr-1")
        // The wake path has a 3 s cooldown, so a second wake inside it is skipped and never reaches the model.
        rig.tick(4_000)
        rig.wakeAndFinal("帮我看看", turnId = "asr-2")

        val history = rig.model.requests.last().history
        val last = history.lastOrNull()
        assertTrue(
            last is ModelHistoryItem.Text && last.role == MessageRole.ASSISTANT,
            "the transcript ends on an unanswered user message ($last); the model path refuses that shape, so every " +
                "later turn in the session would fail until the app was restarted",
        )
        assertEquals(
            "智能助手这次没能给出结果。",
            (last as ModelHistoryItem.Text).text,
            "the transcript must carry the sentence the wearer was actually shown for this terminal state",
        )
    }

    @Test
    fun `a turn that notified and also closed records what the model told the wearer`() {
        // §6.1: the wearer reaches the model only through `notify_user`, so the notification bodies are the assistant
        // turn's content - and `submitOutcome` suppresses the closing text precisely because it was never displayed.
        // Recording only `finalText` dropped every notification from the conversation (review I-1/M1's gap): the
        // wearer could ask "what did you just remind me?" and the model had no record of having said it.
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.Storage to SmartFakeStorage()))
        var turn = 0
        rig.model.onRequest = { request, listener ->
            turn++
            if (turn == 1) {
                listener(smartToolCall("c1", "notify_user", """{"text":"progress-body","channel":"screen"}"""))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "closing-note")
            }
        }

        rig.wakeAndFinal("帮我看看", turnId = "asr-1")
        rig.tick(4_000)
        rig.wakeAndFinal("第二个问题", turnId = "asr-2")

        val history = rig.model.requests.last().history
            .filterIsInstance<ModelHistoryItem.Text>()
            .map { it.text }
        assertTrue(
            history.any { it.contains("progress-body") },
            "the notification body never reached the conversation: $history",
        )
    }
}
