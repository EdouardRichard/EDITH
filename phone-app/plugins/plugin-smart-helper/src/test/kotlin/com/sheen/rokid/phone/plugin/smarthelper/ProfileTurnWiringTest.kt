package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.ContextTuning
import com.sheen.rokid.contracts.AcousticEvent
import com.sheen.rokid.contracts.AcousticLabel
import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.scene.ProactiveHintSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.3 Phase 1 final-fix wave: the profile drives **both** the output cap and the fail-closed budget on every route.
 *
 * - Item 3: the wake `AgentLoop` GatewayTurn and the proactive gateway turn carry `profile.maxOutputTokens`
 *   explicitly (the adapter also has a fallback, but an explicit per-turn cap is what makes the decision inspectable
 *   at the turn and assertable on the wire).
 * - Item 4: a profile that cannot produce a `ContextPolicy` refuses the turn with `PROFILE_TOO_SMALL` /
 *   `PROFILE_INVALID`; it is never reported as the generic `PROFILE_MISSING`, and no model request is made.
 */
class ProfileTurnWiringTest {

    private fun withLines(block: () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        return try {
            block()
            lines.toList()
        } finally {
            P3Log.sink = previous
        }
    }

    @Test
    fun `the wake turn sends the profile's output cap`() {
        val rig = SmartTestRig()
        rig.profile = smartProfile(maxOutputTokens = 777)
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

        rig.wakeAndFinal("现在几点了")

        assertEquals(
            777,
            rig.model.requests.single().maxOutputTokens,
            "the wake turn must declare the profile's per-turn output cap explicitly",
        )
    }

    @Test
    fun `the proactive turn sends the profile's output cap`() {
        val rig = SmartTestRig()
        rig.profile = smartProfile(maxOutputTokens = 777)
        rig.activate()
        rig.model.onRequest = { request, listener ->
            listener(smartToolCall("p1", ProactiveHintSpec.PROPOSE_HINT.name, """{"text":"现在正合适"}""", requestId = request.requestId))
            listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
        }

        rig.plugin.handle(AcousticEvent(rig.envelope(), AcousticLabel.Speech))
        rig.plugin.handle(TranscriptEvent(rig.transcript("现在几点了", turnId = "asr-p1")))

        assertEquals(
            777,
            rig.model.requests.single().maxOutputTokens,
            "the proactive gateway turn must declare the profile's per-turn output cap explicitly",
        )
    }

    @Test
    fun `a too-small profile refuses the turn with PROFILE_TOO_SMALL and never calls the model`() {
        val rig = SmartTestRig()
        // A consistent profile (1 + 1 + 1 <= 100) whose derived hard input is 1: the 0.75 soft limit rounds to zero.
        rig.profile = smartProfile(
            usableWindowTokens = 100,
            inputBudgetTokens = 1,
            maxOutputTokens = 1,
            safetyTokens = 1,
        )
        rig.model.onRequest = { request, listener -> streamText(request, listener, "must not happen") }

        val lines = withLines {
            rig.activate()
            rig.wakeAndFinal("现在几点了")
        }

        assertTrue(rig.model.requests.isEmpty(), "a profile with no usable budget must be refused before the transport")
        assertTrue(
            "P3_CONTEXT policy=PROFILE_TOO_SMALL" in lines,
            "the activation failure must be named with its own code: $lines",
        )
        assertTrue(
            lines.any { it.startsWith("P3_TURN end ") && "code=PROFILE_TOO_SMALL" in it },
            "the turn must fail closed with PROFILE_TOO_SMALL, not PROFILE_MISSING: $lines",
        )
    }

    @Test
    fun `invalid policy tuning refuses the turn with PROFILE_INVALID`() {
        val rig = SmartTestRig(contextTuning = ContextTuning(compressionThreshold = 1.0))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "must not happen") }

        val lines = withLines {
            rig.activate()
            rig.wakeAndFinal("现在几点了")
        }

        assertTrue(rig.model.requests.isEmpty(), "an invalid policy must be refused before the transport")
        assertTrue(
            "P3_CONTEXT policy=PROFILE_INVALID" in lines,
            "invalid tuning is the profile/configuration failure, not a size failure: $lines",
        )
        assertTrue(
            lines.any { it.startsWith("P3_TURN end ") && "code=PROFILE_INVALID" in it },
            "the turn must report PROFILE_INVALID: $lines",
        )
    }
}
