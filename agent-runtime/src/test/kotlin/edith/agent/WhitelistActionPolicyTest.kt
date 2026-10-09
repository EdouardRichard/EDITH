package edith.agent

import edith.plugin.RiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * [`WhitelistActionPolicy`] is the gate spec §7.2 puts in front of every tool call: a level ceiling that can refuse
 * outright, plus免确认 entries keyed on the **(action, scope)** pair — "message contact A", never "send any message".
 *
 * These tests pin the three hard properties: L3/L4 are never免确认 even when whitelisted, an entry for one scope never
 * authorises another, and a plugin declaration can only tighten (never loosen) what the user must confirm.
 */
class WhitelistActionPolicyTest {

    /** A descriptor whose [ToolDescriptor.description] is the projected `ActionDescriptor.title`. */
    private fun descriptor(
        name: String = "message.send",
        scope: String = "contact:A",
        risk: RiskLevel = RiskLevel.L2,
        requiresConfirmation: Boolean = false,
    ) = ToolDescriptor(
        name = name,
        description = "Title of $name",
        parametersJson = """{"type":"object"}""",
        risk = risk,
        requiresConfirmation = requiresConfirmation,
        idempotent = true,
        reversible = true,
        scope = scope,
    )

    @Test fun `a tool above the configured ceiling is refused outright`() {
        val policy = WhitelistActionPolicy(maxLevel = RiskLevel.L2, whitelist = emptyList())

        assertEquals(PolicyDecision.Refuse, policy.decide(descriptor(risk = RiskLevel.L3)))
        assertEquals(PolicyDecision.Refuse, policy.decide(descriptor(risk = RiskLevel.L4)))
    }

    @Test fun `the ceiling wins over a whitelist entry and over a plugin declaration`() {
        val policy = WhitelistActionPolicy(
            maxLevel = RiskLevel.L1,
            whitelist = listOf(WhitelistEntry("message.send", "contact:A")),
        )

        assertEquals(PolicyDecision.Refuse, policy.decide(descriptor(risk = RiskLevel.L2)))
        assertEquals(
            PolicyDecision.Refuse,
            policy.decide(descriptor(risk = RiskLevel.L2, requiresConfirmation = true)),
            "a declaration can only tighten; it never lifts an action over the ceiling",
        )
    }

    @Test fun `L0 and L1 are allowed without confirmation`() {
        val policy = WhitelistActionPolicy(maxLevel = RiskLevel.L4, whitelist = emptyList())

        assertEquals(PolicyDecision.Allow, policy.decide(descriptor(risk = RiskLevel.L0)))
        assertEquals(PolicyDecision.Allow, policy.decide(descriptor(risk = RiskLevel.L1)))
    }

    @Test fun `L2 is allowed when the exact action and scope pair is whitelisted`() {
        val policy = WhitelistActionPolicy(
            maxLevel = RiskLevel.L2,
            whitelist = listOf(WhitelistEntry("message.send", "contact:A")),
        )

        assertEquals(PolicyDecision.Allow, policy.decide(descriptor(name = "message.send", scope = "contact:A")))
    }

    @Test fun `an L2 action without a whitelist entry asks for confirmation`() {
        val policy = WhitelistActionPolicy(maxLevel = RiskLevel.L2, whitelist = emptyList())

        assertEquals(PolicyDecision.RequireConfirmation("Title of message.send"), policy.decide(descriptor()))
    }

    @Test fun `a whitelist entry for one scope never authorises another scope`() {
        val policy = WhitelistActionPolicy(
            maxLevel = RiskLevel.L4,
            whitelist = listOf(WhitelistEntry("message.send", "contact:A")),
        )

        assertEquals(PolicyDecision.Allow, policy.decide(descriptor(scope = "contact:A")))
        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            policy.decide(descriptor(scope = "contact:B")),
            "the whitelist is per action type + target scope, never per action alone",
        )
    }

    @Test fun `L3 can never be whitelisted`() {
        val policy = WhitelistActionPolicy(
            maxLevel = RiskLevel.L4,
            whitelist = listOf(WhitelistEntry("message.send", "contact:A")),
        )

        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            policy.decide(descriptor(risk = RiskLevel.L3)),
        )
    }

    @Test fun `L4 can never be whitelisted even when the ceiling is L4`() {
        val policy = WhitelistActionPolicy(
            maxLevel = RiskLevel.L4,
            whitelist = listOf(WhitelistEntry("message.send", "contact:A")),
        )

        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            policy.decide(descriptor(risk = RiskLevel.L4)),
        )
    }

    @Test fun `a plugin declared confirmation requirement forces confirmation at L0`() {
        val policy = WhitelistActionPolicy(maxLevel = RiskLevel.L4, whitelist = emptyList())

        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            policy.decide(descriptor(risk = RiskLevel.L0, requiresConfirmation = true)),
        )
    }

    @Test fun `a whitelist entry cannot override a plugin declared confirmation requirement`() {
        val policy = WhitelistActionPolicy(
            maxLevel = RiskLevel.L4,
            whitelist = listOf(WhitelistEntry("message.send", "contact:A")),
        )

        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            policy.decide(descriptor(risk = RiskLevel.L1, requiresConfirmation = true)),
        )
    }

    @Test fun `the constructor snapshots the whitelist collection`() {
        val entries = mutableListOf(WhitelistEntry("message.send", "contact:A"))
        val policy = WhitelistActionPolicy(maxLevel = RiskLevel.L4, whitelist = entries)

        entries.clear()

        assertEquals(PolicyDecision.Allow, policy.decide(descriptor()))
    }

    @Test fun `snapshot returns an independent instance carrying the whole rule set`() {
        val entries = mutableListOf(WhitelistEntry("message.send", "contact:A"))
        val policy = WhitelistActionPolicy(maxLevel = RiskLevel.L2, whitelist = entries)

        val snapshot = policy.snapshot()
        entries.clear()

        assertNotSame(policy, snapshot)
        assertEquals(PolicyDecision.Allow, snapshot.decide(descriptor()))
        assertEquals(PolicyDecision.Allow, policy.decide(descriptor()))
        // The ceiling travelled with the snapshot: a turn-start copy is a copy of the rule, not just of the list.
        assertEquals(PolicyDecision.Refuse, snapshot.decide(descriptor(risk = RiskLevel.L3)))
    }
}
