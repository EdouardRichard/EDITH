package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.testsupport.FakeClock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 0.3 Phase 1 final-fix wave (spec §5.2): the loop only forwards policy; it reads no profile itself.
 *
 * The per-turn output cap and the send-time hard limit are decided by the wiring that owns the profile and the
 * session policy. The loop must carry both into every [GatewayTurn] of the run — and keep the pre-0.3 defaults when
 * the caller supplies neither, so a legacy caller is unaffected.
 */
class TurnPolicyForwardingTest {

    private fun request(
        maxOutputTokens: Int? = null,
        limitTokens: Int = Int.MAX_VALUE,
        contextFailure: String? = null,
    ) = AgentRequest(
        turnId = "turn-7",
        utterance = "把音量调小一点",
        instructions = "You are Edith.",
        profileId = "deepseek-default",
        deadlineNanos = Long.MAX_VALUE,
        maxOutputTokens = maxOutputTokens,
        limitTokens = limitTokens,
        contextFailure = contextFailure,
    )

    private fun loop(gateway: ModelGateway) = AgentLoop(
        gateway = gateway,
        registry = ToolRegistry(emptyList()),
        policy = ScriptedPolicy(),
        guard = ProgressGuard(),
        audit = RecordingAuditSink(),
        clock = FakeClock(),
    )

    @Test
    fun `the loop forwards the turn's output cap hard limit and fail-closed code`() {
        val gateway = ScriptedGateway(listOf(reply(text = "好。", terminal = ModelEnd.COMPLETED)))

        loop(gateway).run(
            request(
                maxOutputTokens = 777,
                limitTokens = 321,
                contextFailure = ContextPolicy.PROFILE_TOO_SMALL,
            ),
        )

        val sent = gateway.requests.single()
        assertEquals(777, sent.maxOutputTokens, "the per-turn cap must reach the gateway verbatim")
        assertEquals(321, sent.limitTokens, "the session policy's hard limit must reach the gateway verbatim")
        assertEquals(
            ContextPolicy.PROFILE_TOO_SMALL,
            sent.contextFailure,
            "the fail-closed code belongs to every request of the run, not only the opening one",
        )
    }

    @Test
    fun `a pre-0_3 request keeps both defaults`() {
        val gateway = ScriptedGateway(listOf(reply(text = "好。", terminal = ModelEnd.COMPLETED)))

        loop(gateway).run(request())

        val sent = gateway.requests.single()
        assertNull(sent.maxOutputTokens, "no cap means the adapter falls back to the profile's value")
        assertEquals(Int.MAX_VALUE, sent.limitTokens, "no override means the gateway's own policy window")
        assertNull(sent.contextFailure, "no failure means no fail-closed refusal")
    }
}
