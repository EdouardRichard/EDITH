package edith.plugins.smart

import com.sheen.rokid.contracts.TranscriptEvent
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.RiskLevel
import kotlin.test.*

/**
 * The whitelist boundary: the plugin constructs `WhitelistActionPolicy` from the user's configured ceiling and grants,
 * and it may only ever **tighten** what the app's own action descriptors already declared.
 *
 * These tests drive the real loop through the plugin (a real `ToolRegistry`, a real `WhitelistActionPolicy`, a real
 * `AgentLoop`) rather than asserting on the policy class in isolation, because the property that matters is the
 * end-to-end one: a riskier action must not run, whatever the user listed.
 */
class SmartWhitelistTest {

    private fun rigWith(descriptor: com.sheen.rokid.plugin.ActionDescriptor, maxLevel: String, whitelist: String?): SmartTestRig {
        val rig = SmartTestRig()
        rig.actions.descriptors += descriptor
        rig.activate(maxLevel = maxLevel, whitelist = whitelist)
        var turn = 0
        rig.model.onRequest = { request, listener ->
            turn += 1
            if (turn == 1) {
                listener(ModelStreamEvent.ToolCallTerminal(request.requestId, "fc-1", "call-1", descriptor.name, "{}"))
                listener(ModelStreamEvent.Terminal(request.requestId, ModelEnd.COMPLETED, null, null))
            } else {
                streamText(request, listener, "处理完了。")
            }
        }
        return rig
    }

    private fun runOnce(rig: SmartTestRig) {
        rig.plugin.handle(rig.wakeEvent())
        rig.plugin.handle(TranscriptEvent(rig.transcript("执行一下")))
        rig.tick()
    }

    @Test fun `an L0 action runs with no grant at all`() {
        val rig = rigWith(smartAction("open_app", risk = RiskLevel.L0), maxLevel = "L1", whitelist = null)
        runOnce(rig)
        assertEquals(listOf("open_app" to "{}"), rig.actions.executed)
    }

    @Test fun `an L1 action runs under the default ceiling`() {
        val rig = rigWith(smartAction("compose_note", risk = RiskLevel.L1), maxLevel = "L1", whitelist = null)
        runOnce(rig)
        assertEquals(listOf("compose_note" to "{}"), rig.actions.executed)
    }

    @Test fun `an L2 action matching the grant does not run when the ceiling is L1`() {
        val rig = rigWith(
            smartAction("send_message", risk = RiskLevel.L2, scope = "contact:A"),
            maxLevel = "L1",
            whitelist = "send_message|contact:A",
        )
        runOnce(rig)
        assertTrue(rig.actions.executed.isEmpty(), "a grant must not lift the user's ceiling")
    }

    @Test fun `an L2 action above the ceiling is refused even when listed`() {
        val rig = rigWith(smartAction("send_message", risk = RiskLevel.L2), maxLevel = "L0", whitelist = "send_message|app")
        runOnce(rig)
        assertTrue(rig.actions.executed.isEmpty())
    }

    @Test fun `an L3 action can never become an allow even with a matching grant`() {
        val rig = rigWith(
            smartAction("delete_account", risk = RiskLevel.L3, scope = "app"),
            maxLevel = "L4",
            whitelist = "delete_account|app",
        )
        runOnce(rig)
        assertTrue(rig.actions.executed.isEmpty(), "L3/L4 are never pre-approved")
    }

    @Test fun `an L4 action at the L4 ceiling is still refused without a confirmation channel`() {
        val rig = rigWith(smartAction("wipe_device", risk = RiskLevel.L4), maxLevel = "L4", whitelist = "wipe_device|app")
        runOnce(rig)
        assertTrue(rig.actions.executed.isEmpty())
    }

    @Test fun `an app-side requiresConfirmation flag forces a question even for an L0 action`() {
        val rig = rigWith(
            smartAction("share_screen", risk = RiskLevel.L0, requiresConfirmation = true),
            maxLevel = "L4",
            whitelist = "share_screen|app",
        )
        runOnce(rig)
        assertTrue(rig.actions.executed.isEmpty(), "the app's own tightening cannot be undone by a grant")
    }

    @Test fun `a grant for a different scope does not authorize the action`() {
        val rig = rigWith(
            smartAction("send_message", risk = RiskLevel.L2, scope = "contact:B"),
            maxLevel = "L2",
            whitelist = "send_message|contact:A",
        )
        runOnce(rig)
        assertTrue(rig.actions.executed.isEmpty(), "a grant is one exact action|scope pair")
    }

    @Test fun `an L2 action with the exact grant and a sufficient ceiling runs`() {
        val rig = rigWith(
            smartAction("send_message", risk = RiskLevel.L2, scope = "contact:A"),
            maxLevel = "L2",
            whitelist = "send_message|contact:A",
        )
        runOnce(rig)
        assertEquals(listOf("send_message" to "{}"), rig.actions.executed)
    }
}
