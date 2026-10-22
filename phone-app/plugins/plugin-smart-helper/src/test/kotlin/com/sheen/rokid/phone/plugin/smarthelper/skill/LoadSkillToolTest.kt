package com.sheen.rokid.phone.plugin.smarthelper.skill

import com.sheen.rokid.agent.SCOPE_TERMINAL
import com.sheen.rokid.agent.ToolCall
import com.sheen.rokid.agent.ToolRegistry
import com.sheen.rokid.agent.ToolResult
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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

    @Test fun `a successful pull states the done facts it knows and reports no observation`() {
        // 0.3 Phase 2.2 Task 7: reading a SKILL is not a screen action, so observation is null. The pull was accepted
        // and its body handed over. Independent verification of a later request and a device-state change are unknown
        // here, so those tri-state facts stay null rather than being guessed.
        val result = tool().execute("load_skill", """{"name":"alpha"}""")

        assertEquals(ToolExecution(outcome = ToolOutcome.Done, accepted = true), result.execution)
        assertNull(result.execution?.applied)
        assertNull(result.execution?.verified)
        assertNull(result.execution?.changed)
        assertNull(result.observation)
        assertEquals("loaded:1", result.detail, "the tool-specific data stays the envelope's data")
    }

    @Test fun `the model sees the envelope with a null observation and no legacy prefix`() {
        val registry = ToolRegistry(listOf(tool()))

        val result = registry.execute(
            ToolCall("c1", "load_skill", """{"name":"alpha"}"""),
        )

        val payload = Json.parseToJsonElement(result.payload).jsonObject
        assertEquals(setOf("execution", "observation", "data"), payload.keys)
        val execution = payload.getValue("execution").jsonObject
        assertEquals("done", execution.getValue("outcome").jsonPrimitive.content)
        assertEquals(JsonPrimitive(true), execution.getValue("accepted"))
        assertEquals(JsonNull, payload.getValue("observation"))
        assertEquals("loaded:1", payload.getValue("data").jsonPrimitive.content)
        assertFalse("Failed: " in result.payload, "no legacy prefix: ${result.payload}")
        assertFalse("Refused: " in result.payload, "no legacy prefix: ${result.payload}")
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

    @Test fun `a missing name reaches the model as a recoverable refusal with explicit facts`() {
        // 0.3 Phase 2.2 Task 7 fix review: a bare `ActionResult(Refused, code)` let the registry project null facts and
        // `recoverable=false`. An unknown name is a catalog mistake the model can correct, so it is explicitly
        // recoverable; the pull was rejected (nothing was accepted or applied) and no observation exists.
        val registry = ToolRegistry(listOf(tool(SkillLoad.NotFound(SkillLoadReason.NOT_IN_LIBRARY))))

        val result = registry.execute(ToolCall("c1", "load_skill", """{"name":"nope"}"""))

        assertRefusalFacts(result, LoadSkillTool.NOT_FOUND, recoverable = true)
    }

    @Test fun `a disabled skill reaches the model as a non-recoverable refusal with explicit facts`() {
        // The user turned this entry off: retrying the same pull cannot help until the user acts, so recoverable=false
        // is the one difference from NOT_FOUND. The rest of the refusal facts are identical.
        val registry = ToolRegistry(listOf(tool(SkillLoad.NotFound(SkillLoadReason.DISABLED))))

        val result = registry.execute(ToolCall("c1", "load_skill", """{"name":"off"}"""))

        assertRefusalFacts(result, LoadSkillTool.DISABLED, recoverable = false)
    }

    @Test fun `a structurally malformed call reaches the model with the not-found refusal facts`() {
        // `{}` parses as JSON, so the registry dispatches it; the tool itself rejects the missing name. That is the
        // refusal path, not the registry's pre-dispatch ARGUMENTS_INVALID path.
        val registry = ToolRegistry(listOf(tool()))

        val result = registry.execute(ToolCall("c1", "load_skill", "{}"))

        assertRefusalFacts(result, LoadSkillTool.NOT_FOUND, recoverable = true)
    }

    /** Asserts both the projected [ToolResult] facts and the envelope the model actually receives. */
    private fun assertRefusalFacts(result: ToolResult, code: String, recoverable: Boolean) {
        assertFalse(result.ok)
        assertEquals(ToolOutcome.Refused, result.execution.outcome)
        assertEquals(code, result.execution.code)
        assertEquals(false, result.execution.accepted)
        assertEquals(false, result.execution.applied)
        assertNull(result.execution.verified)
        assertNull(result.execution.changed)
        assertEquals(recoverable, result.execution.recoverable)
        assertNull(result.observation)
        assertEquals(code, result.data, "the stable code stays the envelope's data")
        assertFalse("Refused: " in result.payload, "no legacy prefix: ${result.payload}")

        val payload = Json.parseToJsonElement(result.payload).jsonObject
        assertEquals(JsonNull, payload.getValue("observation"))
        val execution = payload.getValue("execution").jsonObject
        assertEquals("refused", execution.getValue("outcome").jsonPrimitive.content)
        assertEquals(code, execution.getValue("code").jsonPrimitive.content)
        assertEquals(JsonPrimitive(false), execution.getValue("accepted"))
        assertEquals(JsonPrimitive(false), execution.getValue("applied"))
        assertEquals(JsonPrimitive(recoverable), execution.getValue("recoverable"))
        assertEquals(JsonNull, execution.getValue("verified"))
        assertEquals(JsonNull, execution.getValue("changed"))
    }
}
