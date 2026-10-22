package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log

import com.sheen.rokid.contracts.SpeechRequest
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ModelEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec §6.2/§6.3: the fallback that stops a turn from ever being silent, and the guard that stops a multi-step task
 * from talking over the wearer.
 *
 * The model's reply text is **not** displayed by default: `notify_user` is the only channel (spec §6.1). The fallback
 * exists precisely so that a model which forgets still reaches the wearer.
 */
class NotifyFallbackTest {

    /** One proactive-free passive turn whose model asks for a notification, then answers. */
    private fun rigThatNotifies(channel: String, times: Int = 1): SmartTestRig {
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.Storage to SmartFakeStorage()))
        var turn = 0
        rig.model.onRequest = { request, listener ->
            turn++
            if (turn <= times) {
                listener(smartToolCall("c$turn", "notify_user", """{"text":"进度 $turn","channel":"$channel"}"""))
                listener(com.sheen.rokid.plugin.ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "好了。")
            }
        }
        return rig
    }

    private fun rigThatAnswersOnly(): SmartTestRig {
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.Storage to SmartFakeStorage()))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "已经帮你办好了。") }
        return rig
    }

    @Test
    fun `a completed turn that never notified is followed by a screen notification`() {
        val rig = rigThatAnswersOnly()

        rig.wakeAndFinal("帮我看看")

        assertEquals(listOf("已经帮你办好了。"), rig.hintTexts)
        // §6.2's fallback uses `screen`: the model forgot to notify, so the system says it quietly rather than
        // speaking something the model never chose to say out loud.
        assertEquals(null, rig.output.hints.single().optionalSpeech)
    }

    @Test
    fun `a turn that did notify does not also display its reply`() {
        // Otherwise the wearer would see the progress sentence and then the same answer twice.
        val rig = rigThatNotifies("screen")

        rig.wakeAndFinal("帮我看看")

        assertEquals(listOf("进度 1"), rig.hintTexts)
    }

    @Test
    fun `the model's channel decides whether the notification is spoken`() {
        val spoken = rigThatNotifies("both")
        spoken.wakeAndFinal("帮我看看")

        assertEquals(SpeechRequest(), spoken.output.hints.single().optionalSpeech)
    }

    @Test
    fun `an explicitly suppressed turn is never backfilled`() {
        // §6.2: `silent` means "do not notify me", and the fallback would otherwise contradict the model at once.
        val rig = rigThatNotifies("silent")

        rig.wakeAndFinal("帮我看看")

        assertEquals(emptyList(), rig.hintTexts)
    }

    @Test
    fun `the first notification of a turn is not downgraded and the second one is`() {
        // §6.3: a second notification inside one turn is a mid-step one - the task is still running - so its voice is
        // suppressed while its screen text is not.
        val rig = rigThatNotifies("both", times = 2)

        rig.wakeAndFinal("帮我看看")

        assertEquals(listOf("进度 1", "进度 2"), rig.hintTexts)
        assertEquals(SpeechRequest(), rig.output.hints[0].optionalSpeech, "the first notification may speak")
        assertEquals(null, rig.output.hints[1].optionalSpeech, "the second is a mid-step one and must not")
    }

    @Test
    fun `a screen notification is never downgraded because it was never spoken`() {
        val rig = rigThatNotifies("screen", times = 2)

        rig.wakeAndFinal("帮我看看")

        assertEquals(listOf("进度 1", "进度 2"), rig.hintTexts)
        assertTrue(rig.output.hints.all { it.optionalSpeech == null })
    }

    @Test
    fun `the notification log line carries the channel and a count but never the text`() {
        val rig = rigThatNotifies("both", times = 2)
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.wakeAndFinal("帮我看看")
        } finally {
            P3Log.sink = previous
        }

        val notifications = lines.filter { it.startsWith("P3_NOTIFY") }
        assertTrue(notifications.isNotEmpty(), lines.toString())
        assertTrue(notifications.none { it.contains("进度") }, notifications.toString())
        assertTrue(notifications.any { it.contains("downgraded=true") }, notifications.toString())
    }

    @Test
    fun `a failed turn still says something even when the model never notified`() {
        // The passive loop's oldest invariant: every turn that reaches a terminal state says something.
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener ->
            listener(com.sheen.rokid.plugin.ModelStreamEvent.Terminal(request.requestId, ModelEnd.FAILED, null, "provider_error"))
        }

        rig.wakeAndFinal("帮我看看")

        assertEquals(1, rig.hintTexts.size)
    }
}
