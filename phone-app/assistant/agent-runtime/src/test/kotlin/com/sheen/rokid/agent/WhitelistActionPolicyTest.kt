package com.sheen.rokid.agent

import com.sheen.rokid.plugin.RiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * [`WhitelistActionPolicy`] is the gate spec §7.2 puts in front of every tool call: an **auto-run ceiling** that
 * escalates anything above it to a confirmation (4B D190), plus免确认 entries keyed on the **(action, scope)** pair —
 * "message contact A", never "send any message".
 *
 * These tests pin the three hard properties: L3/L4 are never免确认 even when whitelisted, an entry for one scope never
 * authorises another, and a plugin declaration can only tighten (never loosen) what the user must confirm.
 */
class WhitelistActionPolicyTest {

    /**
     * A descriptor whose user-visible [ToolDescriptor.title] is `"Title of <name>"` and whose model-facing
     * [ToolDescriptor.description] is intentionally longer prose: the confirmation summary must only ever read the
     * title (edith-0.3 Phase 4A A5).
     */
    private fun descriptor(
        name: String = "message.send",
        scope: String = "contact:A",
        risk: RiskLevel = RiskLevel.L2,
        requiresConfirmation: Boolean = false,
    ) = ToolDescriptor(
        name = name,
        title = "Title of $name",
        description = "Model-facing guidance for $name: when to use it, when not to, and how it fails.",
        parametersJson = """{"type":"object"}""",
        risk = risk,
        requiresConfirmation = requiresConfirmation,
        idempotent = true,
        reversible = true,
        scope = scope,
    )

    @Test fun `a tool above the configured ceiling asks for confirmation instead of being refused`() {
        val policy = WhitelistActionPolicy(maxLevel = RiskLevel.L2, whitelist = emptyList())

        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            policy.decide(descriptor(risk = RiskLevel.L3)),
            "D190: the ceiling bounds auto-run, it is no longer a wall that refuses outright",
        )
        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            policy.decide(descriptor(risk = RiskLevel.L4)),
        )
    }

    @Test fun `an L2 action above an L1 ceiling asks instead of being refused`() {
        val policy = WhitelistActionPolicy(maxLevel = RiskLevel.L1, whitelist = emptyList())

        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            policy.decide(descriptor(risk = RiskLevel.L2)),
        )
    }

    @Test fun `the ceiling asks before a whitelist entry or a plugin declaration can be considered`() {
        val policy = WhitelistActionPolicy(
            maxLevel = RiskLevel.L1,
            whitelist = listOf(WhitelistEntry("message.send", "contact:A")),
        )

        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            policy.decide(descriptor(risk = RiskLevel.L2)),
            "a grant must never lift an action above the user's auto-run ceiling",
        )
        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            policy.decide(descriptor(risk = RiskLevel.L2, requiresConfirmation = true)),
            "a declaration can only tighten; it never turns an over-ceiling action into an allow",
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

    @Test fun `a long model description never becomes the confirmation summary`() {
        // edith-0.3 Phase 4A Task 5 (A5): the description is the model's instruction prose. Once the 24 tools carry
        // real multi-sentence descriptions, reading it back as the wearer's question would show a paragraph in a
        // confirmation card. The summary is the short user-facing title, whatever the description says.
        val longDescription = "Use this action when the wearer asks for it, never when the screen is locked. " +
            "It may fail with STATE_UNCHANGED. ".repeat(6)
        val described = descriptor(risk = RiskLevel.L2).copy(description = longDescription)

        // Both RequireConfirmation paths must read the title: the L2-whitelist fallback and the B1 ceiling escalation.
        val belowCeiling = WhitelistActionPolicy(maxLevel = RiskLevel.L3, whitelist = emptyList())
        val aboveCeiling = WhitelistActionPolicy(maxLevel = RiskLevel.L1, whitelist = emptyList())

        for (decision in listOf(belowCeiling.decide(described), aboveCeiling.decide(described))) {
            assertEquals(PolicyDecision.RequireConfirmation("Title of message.send"), decision)
            assertEquals(
                "Title of message.send",
                (decision as PolicyDecision.RequireConfirmation).summary,
                "the confirmation card shows the user title, not the model-facing description",
            )
        }
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

    /**
     * 0.3 Phase 4B (`D189`, spec §5.1): the host's explicit 「不拦截」 mode is a live global gate, not a grant.
     *
     * It supersedes the ceiling, the whitelist and the plugin's own tightening at once: the mode exists precisely so
     * that the wearer's explicit choice wins over every rule that would otherwise ask. This is the one place the
     * otherwise absolute D182 L3/L4 boundary is intentionally lifted.
     */
    @Test fun `no interception allows every level and bypasses the ceiling`() {
        val policy = WhitelistActionPolicy(
            maxLevel = RiskLevel.L1,
            whitelist = emptyList(),
            noInterception = { true },
        )

        RiskLevel.entries.forEach { risk ->
            assertEquals(
                PolicyDecision.Allow,
                policy.decide(descriptor(risk = risk)),
                "no-interception must allow $risk without consulting the L1 ceiling",
            )
        }
        assertEquals(
            PolicyDecision.Allow,
            policy.decide(descriptor(risk = RiskLevel.L4, requiresConfirmation = true)),
            "the host's explicit mode supersedes a plugin's tightening (spec §5.1)",
        )
    }

    @Test fun `the no interception gate is read live on every decision`() {
        var enabled = false
        val policy = WhitelistActionPolicy(
            maxLevel = RiskLevel.L1,
            whitelist = emptyList(),
            noInterception = { enabled },
        )

        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            policy.decide(descriptor(risk = RiskLevel.L2)),
            "with the gate off the normal ceiling still applies",
        )

        enabled = true
        assertEquals(
            PolicyDecision.Allow,
            policy.decide(descriptor(risk = RiskLevel.L4)),
            "a saved switch must apply from the next decision, not the next App assembly",
        )

        enabled = false
        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            policy.decide(descriptor(risk = RiskLevel.L4)),
            "turning the mode off must restore L4 confirmation immediately",
        )
    }

    @Test fun `a snapshot keeps the live gate rather than capturing its current value`() {
        var enabled = true
        val policy = WhitelistActionPolicy(
            maxLevel = RiskLevel.L1,
            whitelist = emptyList(),
            noInterception = { enabled },
        )

        val snapshot = policy.snapshot()
        enabled = false

        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            snapshot.decide(descriptor(risk = RiskLevel.L3)),
            "only the ceiling and grants are turn-snapshotted; the host mode is live by design",
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
        assertEquals(
            PolicyDecision.RequireConfirmation("Title of message.send"),
            snapshot.decide(descriptor(risk = RiskLevel.L3)),
        )
    }
}
