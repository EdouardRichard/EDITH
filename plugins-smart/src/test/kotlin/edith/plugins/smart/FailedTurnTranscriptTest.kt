package edith.plugins.smart

import edith.plugin.Capability
import edith.plugin.MessageRole
import edith.plugin.ModelEnd
import edith.plugin.ModelHistoryItem
import edith.plugin.ModelStreamEvent
import kotlin.test.Test
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
    }
}
