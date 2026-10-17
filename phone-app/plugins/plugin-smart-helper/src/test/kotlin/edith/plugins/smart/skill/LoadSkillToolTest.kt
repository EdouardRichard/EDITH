package edith.plugins.smart.skill

import edith.agent.SCOPE_TERMINAL
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.RiskLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `load_skill` — the pull half of two-layer SKILL injection (Phase 6, design D-1/D-2/D-4/D-8).
 *
 * The tool is the model's only way to get a body that the catalog deliberately omits, so two properties are load
 * bearing beyond "it returns the body":
 *
 * - **The receipt carries counts, never content** (D-8). `AgentLoop` feeds every tool result's text to
 *   `ProgressGuard`, which trips on three identical consecutive fingerprints; a constant receipt would make two pulls
 *   of the same SKILL look like no progress and end the run. The per-instance sequence makes every successful pull a
 *   distinct fingerprint, and the body stays out of the receipt entirely.
 * - **Every malformed call is a refusal, never an exception** (a step the model can correct). The four parse failures
 *   the framework can produce — non-JSON, a JSON non-object, a missing/blank `name`, a non-string `name` — all reach
 *   the loop as `ActionOutcome.Refused`.
 */
class LoadSkillToolTest {

    private val skill = Skill("alpha", "1", "d", body = "SECRET-BODY")

    private fun tool(
        load: SkillLoad = SkillLoad.Loaded(skill, wasDormant = false),
        seen: MutableList<Skill> = mutableListOf(),
    ) = LoadSkillTool({ load }, { seen += it })

    @Test fun `the descriptor is L0, idempotent, non-terminal and audits no argument`() {
        val d = LoadSkillTool.DESCRIPTOR
        assertEquals("load_skill", d.name)
        assertEquals(RiskLevel.L0, d.risk)
        assertTrue(d.idempotent)
        assertFalse(d.requiresConfirmation)
        assertTrue(d.reversible)
        assertNull(d.auditTargetArgument)
        // A pull inside a multi-step task must not end the run, so the scope must not be the terminal one.
        assertNotEquals(SCOPE_TERMINAL, d.scope)
        assertEquals("skill", d.scope)
        // AC-18 / D-9: pulling a body is reference reading, not a step, so `load_skill` must not consume `maxSteps`.
        // This assertion deliberately reads the **real** descriptor: the agent-runtime tests drive fake executors, so
        // they would stay green even if the production tool declared nothing, and the exemption would be dead in
        // production with every suite passing. This is the only exemption in the repository.
        assertFalse(d.countsTowardStepLimit, "load_skill must not consume maxSteps (AC-18)")
        // The schema is the contract the model reads; pin it whole rather than by `contains`.
        assertEquals(
            """{"type":"object","properties":{"name":{"type":"string","description":"SKILL 目录中的 name"}},"required":["name"],"additionalProperties":false}""",
            d.parametersJson,
        )
    }

    @Test fun `a load hands the skill to the callback and returns a counter receipt without the body`() {
        val seen = mutableListOf<Skill>()
        val result = tool(seen = seen).execute("load_skill", """{"name":"alpha"}""")
        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(listOf(skill), seen)
        assertEquals("loaded:1", result.detail)
        assertTrue("SECRET-BODY" !in result.detail, "the body must never travel in the tool receipt")
    }

    @Test fun `two pulls of the same name get different receipts so the guard cannot call it no-progress`() {
        val t = tool()
        val first = t.execute("load_skill", """{"name":"alpha"}""").detail
        val second = t.execute("load_skill", """{"name":"alpha"}""").detail
        assertNotEquals(first, second)
        assertEquals(listOf("loaded:1", "loaded:2"), listOf(first, second))
    }

    @Test fun `a dormant hit is flagged in the receipt and still hands the body over`() {
        val seen = mutableListOf<Skill>()
        val result = tool(SkillLoad.Loaded(skill, wasDormant = true), seen).execute("load_skill", """{"name":"alpha"}""")
        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue(result.detail.contains("SKILL_DORMANT"), result.detail)
        assertEquals("SKILL_DORMANT loaded:1", result.detail)
        assertEquals(listOf(skill), seen)
    }

    @Test fun `an unknown name is a refusal with a stable code`() {
        val seen = mutableListOf<Skill>()
        val result = tool(SkillLoad.NotFound(SkillLoadReason.NOT_IN_LIBRARY), seen)
            .execute("load_skill", """{"name":"nope"}""")
        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("SKILL_NOT_FOUND", result.detail)
        assertTrue(seen.isEmpty(), "a refusal must not hand a body to the callback")
    }

    @Test fun `a disabled skill is a refusal with its own code`() {
        val seen = mutableListOf<Skill>()
        val result = tool(SkillLoad.NotFound(SkillLoadReason.DISABLED), seen)
            .execute("load_skill", """{"name":"off"}""")
        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("SKILL_DISABLED", result.detail)
        assertTrue(seen.isEmpty(), "a refusal must not hand a body to the callback")
    }

    @Test fun `malformed arguments are refused rather than thrown`() {
        val seen = mutableListOf<Skill>()
        val t = tool(seen = seen)
        listOf(
            "not json", "[]", "{}", """{"name":""}""", """{"name":"   "}""", """{"name":123}""",
            """{"name":null}""", """{"other":"x"}""",
        ).forEach { args ->
            assertEquals(ActionOutcome.Refused, t.execute("load_skill", args).outcome, "args=$args")
        }
        assertTrue(seen.isEmpty(), "a malformed call must not load anything")
    }
}
