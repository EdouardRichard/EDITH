package com.sheen.rokid.agent

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolSpec
import com.sheen.rokid.plugin.ToolVision
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolRegistryTest {

    @Test fun `the terminal scope constant keeps its wire value`() {
        // Task 6's loop reads this exact string; the value is a contract, not a local choice.
        assertEquals("terminal", SCOPE_TERMINAL)
    }

    @Test fun `the descriptor carries the argument the executor declared safe to audit`() {
        val executor = RecordingActionExecutor(
            listOf(actionDescriptor("app.uninstall", risk = RiskLevel.L3, auditTargetArgument = "package")),
        )
        val registry = ToolRegistry(listOf(executor))

        assertEquals("package", registry.descriptors().single().auditTargetArgument)
        assertNull(ToolRegistry(listOf(RecordingActionExecutor(listOf(actionDescriptor("dial")))))
            .descriptors().single().auditTargetArgument)
    }

    @Test fun `an audit argument name must not be blank when it is declared`() {
        assertFailsWith<IllegalArgumentException> { actionDescriptor("a", auditTargetArgument = "  ") }
    }

    @Test fun `descriptors expose the plugin action fields as tool metadata`() {
        val executor = RecordingActionExecutor(
            listOf(
                actionDescriptor(
                    name = "device.set_volume",
                    risk = RiskLevel.L1,
                    parametersJson = """{"level":{"type":"integer"}}""",
                    requiresConfirmation = true,
                    idempotent = false,
                    reversible = false,
                    scope = "device",
                ),
            ),
        )
        val registry = ToolRegistry(listOf(executor))

        val descriptor = registry.descriptors().single()
        assertEquals("device.set_volume", descriptor.name)
        assertEquals("Title of device.set_volume", descriptor.description)
        assertEquals("""{"level":{"type":"integer"}}""", descriptor.parametersJson)
        assertEquals(RiskLevel.L1, descriptor.risk)
        assertTrue(descriptor.requiresConfirmation)
        assertFalse(descriptor.idempotent)
        assertFalse(descriptor.reversible)
        assertEquals("device", descriptor.scope)
    }

    @Test fun `a custom description is projected while an undeclared one keeps the title`() {
        // edith-0.3 Phase 4A (A1): the model reads the long description; the short title stays the user-visible label
        // source, so a tool that declares no description keeps the pre-4A projected copy exactly.
        val described = ActionDescriptor(
            name = "device",
            title = "设备控制",
            parametersJson = """{"type":"object"}""",
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = true,
            reversible = true,
            scope = "device",
            description = "Read the screen or change the phone when the wearer asks for it.",
        )
        val describedTool = ToolRegistry(listOf(RecordingActionExecutor(listOf(described)))).descriptors().single()

        assertEquals(
            "Read the screen or change the phone when the wearer asks for it.",
            ToolSpec(describedTool.name, describedTool.description, describedTool.parametersJson).description,
        )

        val titledTool = ToolRegistry(listOf(executorOf("device"))).descriptors().single()
        assertEquals(
            "Title of device",
            ToolSpec(titledTool.name, titledTool.description, titledTool.parametersJson).description,
            "without a declared description the title still stands in, so user labels are unchanged",
        )
    }

    @Test fun `the registry projects the user title separately from the model description`() {
        // edith-0.3 Phase 4A Task 5 (A5): the confirmation summary and the tool row read `title`; only the model schema
        // reads `description`. A descriptor that carries both must project both, or the long prose leaks into user copy.
        val described = ActionDescriptor(
            name = "clear_data",
            title = "清除 App 数据",
            parametersJson = """{"type":"object"}""",
            risk = RiskLevel.L3,
            requiresConfirmation = false,
            idempotent = false,
            reversible = false,
            scope = "app",
            description = "Clear the package's data. Irreversible; the wearer is asked first. STATE_UNCHANGED means it did not clear.",
        )

        val projected = ToolRegistry(listOf(RecordingActionExecutor(listOf(described)))).descriptors().single()

        assertEquals("清除 App 数据", projected.title, "the user-facing label is the descriptor title")
        assertEquals(described.description, projected.description, "the model still reads the long description")
        assertNotEquals(projected.title, projected.description, "the two channels must stay separate")
    }

    @Test fun `an old construction without a title keeps the description as its label`() {
        // Source compatibility: every pre-A5 `ToolDescriptor` construction compiles and keeps a non-blank label; the
        // default is the description, which is exactly the copy those constructions showed the user before the split.
        val legacy = ToolDescriptor(
            name = "legacy.tool",
            description = "Legacy copy that used to be both the label and the model description.",
            parametersJson = """{"type":"object"}""",
            risk = RiskLevel.L1,
            requiresConfirmation = false,
            idempotent = true,
            reversible = true,
            scope = "legacy",
        )

        assertEquals(legacy.description, legacy.title)
    }

    @Test fun `registration rejects a schema that is not well formed JSON and names the tool`() {
        // Every schema below is brace shaped, so `plugin-api` accepts it and only this module can reject it.
        listOf(
            """{ level: 3 }""",
            """{"level": }""",
            """{"level": 3,}""",
            """{"level": "3" "extra"}""",
        ).forEach { schema ->
            val executor = RecordingActionExecutor(listOf(actionDescriptor("broken.tool", parametersJson = schema)))

            val failure = assertFailsWith<IllegalArgumentException>("accepted schema: <$schema>") {
                ToolRegistry(listOf(executor))
            }

            assertTrue("INVALID_TOOL_SCHEMA" in failure.message.orEmpty(), "message: ${failure.message}")
            assertTrue("broken.tool" in failure.message.orEmpty(), "message: ${failure.message}")
        }
    }

    @Test fun `the schema validator rejects well formed JSON that is not an object`() {
        // `plugin-api` insists on a brace shaped schema, so a JSON array cannot reach the registry through an
        // `ActionDescriptor`; the branch is exercised directly to prove the object check is real.
        val failure = assertFailsWith<IllegalArgumentException> { validateToolSchema("array.tool", "[1, 2]") }
        assertTrue("INVALID_TOOL_SCHEMA" in failure.message.orEmpty(), "message: ${failure.message}")
        assertTrue("array.tool" in failure.message.orEmpty(), "message: ${failure.message}")
    }

    @Test fun `duplicate tool names merge to the higher risk with exactly one note`() {
        val notes = mutableListOf<String>()
        val low = executorOf("shared.tool", risk = RiskLevel.L1)
        val distinct = executorOf("other.tool", risk = RiskLevel.L0)
        val high = executorOf("shared.tool", risk = RiskLevel.L3)

        val registry = ToolRegistry(listOf(low, distinct, high), notes::add)

        assertEquals(listOf("shared.tool", "other.tool"), registry.descriptors().map { it.name })
        assertEquals(RiskLevel.L3, registry.resolve("shared.tool")!!.descriptor.risk)
        assertEquals(listOf("DUPLICATE_TOOL_MERGED:shared.tool"), notes)
    }

    @Test fun `a name declared three times still emits one note and keeps the highest risk`() {
        val notes = mutableListOf<String>()
        val first = executorOf("shared.tool", risk = RiskLevel.L0)
        val second = executorOf("shared.tool", risk = RiskLevel.L2)
        val third = executorOf("shared.tool", risk = RiskLevel.L1)

        val registry = ToolRegistry(listOf(first, second, third), notes::add)

        assertEquals(RiskLevel.L2, registry.descriptors().single().risk)
        assertEquals(listOf("DUPLICATE_TOOL_MERGED:shared.tool"), notes)
    }

    @Test fun `the merge keeps the higher risk whichever order the executors are registered in`() {
        val high = executorOf("shared.tool", risk = RiskLevel.L4)
        val low = executorOf("shared.tool", risk = RiskLevel.L1)

        val registry = ToolRegistry(listOf(high, low))

        assertEquals(RiskLevel.L4, registry.descriptors().single().risk)
    }

    @Test fun `the merged tool keeps the executor that declared the higher risk`() {
        val low = executorOf("shared.tool", risk = RiskLevel.L1)
        val high = executorOf("shared.tool", risk = RiskLevel.L3)

        val registry = ToolRegistry(listOf(low, high))

        // Descriptor and executor stay a coherent pair: the surviving tool is the higher-risk declaration.
        assertSame(high, registry.resolve("shared.tool")!!.executor)
    }

    @Test fun `resolve returns null for an unknown name and the registered tool for a known one`() {
        val executor = executorOf("known.tool")
        val registry = ToolRegistry(listOf(executor))

        assertNull(registry.resolve("missing.tool"))

        val tool = registry.resolve("known.tool")
        assertNotNull(tool)
        assertEquals("known.tool", tool.descriptor.name)
        assertSame(executor, tool.executor)
    }

    @Test fun `an unknown tool name is a failed step rather than an exception`() {
        val registry = ToolRegistry(listOf(executorOf("known.tool")))

        val result = registry.execute(ToolCall(id = "call-1", name = "missing.tool", argsJson = "{}"))

        assertEquals("call-1", result.id)
        assertFalse(result.ok)
        assertErrorResult(result, "UNKNOWN_TOOL")
    }

    @Test fun `arguments the strict parser rejects but the lenient parser accepts are executed`() {
        // An unquoted member name with a properly quoted value: measured on kotlinx-serialization-json 1.9.0 to be the
        // form that only the lenient tree parser accepts (a raw newline or an unquoted value parses strictly too).
        val lenientOnly = "{text: \"hello world\"}"
        assertFailsWith<SerializationException> { Json.parseToJsonElement(lenientOnly) }
        assertNotNull(Json { isLenient = true }.parseToJsonElement(lenientOnly))

        val executor = executorOf("echo")
        executor.outcome = { _, args -> ActionResult(ActionOutcome.Done, args) }
        val registry = ToolRegistry(listOf(executor))

        val result = registry.execute(ToolCall(id = "call-2", name = "echo", argsJson = lenientOnly))

        assertTrue(result.ok)
        assertEquals(ToolOutcome.Done, result.execution.outcome)
        assertNull(result.observation)
        assertEquals(lenientOnly, result.data)
        assertEquals(lenientOnly, payloadData(result), "the unparsed argument text is just the envelope's data")
        assertEquals(listOf("echo" to lenientOnly), executor.calls)
    }

    @Test fun `a raw newline inside a JSON string is accepted and forwarded verbatim`() {
        // The P3.0 tool-call probe measured models emitting a literal newline inside a JSON string value. kotlinx's
        // tree parser accepts it even without `isLenient`, so this fixture documents the real-world case rather than
        // the strict/lenient split (covered above with a single quoted document).
        val rawNewline = "{\"text\": \"line one\nline two\"}"
        assertTrue(rawNewline.contains('\n'))

        val executor = executorOf("echo")
        executor.outcome = { _, args -> ActionResult(ActionOutcome.Done, args) }
        val registry = ToolRegistry(listOf(executor))

        val result = registry.execute(ToolCall(id = "call-3", name = "echo", argsJson = rawNewline))

        assertTrue(result.ok)
        assertEquals(ToolOutcome.Done, result.execution.outcome)
        assertNull(result.observation)
        assertEquals(rawNewline, result.data)
        val encodedData = (Json.parseToJsonElement(result.payload) as JsonObject).getValue("data")
        assertTrue(
            encodedData is JsonObject,
            "a literal newline inside a JSON string is still valid JSON, so data is embedded as an object",
        )
        assertEquals(listOf("echo" to rawNewline), executor.calls)
    }

    @Test fun `arguments both parsers reject become an ARGUMENTS_INVALID step`() {
        listOf(
            """{"level": 3,}""",
            """{"text": "open""",
        ).forEach { args ->
            val executor = executorOf("echo")
            val registry = ToolRegistry(listOf(executor))

            val result = registry.execute(ToolCall(id = "call-4", name = "echo", argsJson = args))

            assertFalse(result.ok, "accepted arguments: <$args>")
            assertErrorResult(result, "ARGUMENTS_INVALID", id = "call-4")
            assertTrue(executor.calls.isEmpty(), "executor ran for arguments: <$args>")
        }
    }

    @Test fun `a Done outcome becomes a done envelope whose data is the tool detail`() {
        val executor = executorOf("device.set_volume")
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, """{"volume":3}""") }
        val registry = ToolRegistry(listOf(executor))

        val result = registry.execute(ToolCall("call-5", "device.set_volume", """{"level": 3}"""))

        assertEquals("call-5", result.id)
        assertTrue(result.ok)
        assertEquals(ToolOutcome.Done, result.execution.outcome)
        assertNull(result.execution.code)
        assertNull(result.observation, "a pure tool reports no observation instead of inventing one")
        assertEquals("""{"volume":3}""", result.data)
        assertEquals(
            """{"execution":{"outcome":"done","code":null,"message":null,"recoverable":false,"accepted":null,""" +
                """"applied":null,"verified":null,"changed":null,"uncertain":false,"partial":false},""" +
                """"observation":null,"data":{"volume":3}}""",
            result.payload,
        )
    }

    @Test fun `Refused and Failed outcomes become prefix free envelopes`() {
        listOf(
            ActionOutcome.Refused to ToolOutcome.Refused,
            ActionOutcome.Failed to ToolOutcome.Failed,
        ).forEach { (actionOutcome, toolOutcome) ->
            val detail = "user declined"
            val executor = executorOf("device.set_volume")
            executor.outcome = { _, _ -> ActionResult(actionOutcome, detail) }
            val registry = ToolRegistry(listOf(executor))

            val result = registry.execute(ToolCall("call-6", "device.set_volume", "{}"))

            assertFalse(result.ok, "outcome: $actionOutcome")
            assertEquals(toolOutcome, result.execution.outcome)
            assertNull(result.execution.code, "the registry must not fabricate a code from an unmigrated detail")
            assertNull(result.observation)
            assertEquals(detail, result.data)
            assertEquals(
                """{"execution":{"outcome":"${toolOutcome.name.lowercase()}","code":null,"message":null,""" +
                    """"recoverable":false,"accepted":null,"applied":null,"verified":null,"changed":null,""" +
                    """"uncertain":false,"partial":false},"observation":null,"data":"$detail"}""",
                result.payload,
            )
            assertFalse(result.payload.contains("Refused: "), "no legacy prefix: ${result.payload}")
            assertFalse(result.payload.contains("Failed: "), "no legacy prefix: ${result.payload}")
        }
    }

    @Test fun `an executor that throws never propagates and becomes an uncertain TOOL_THREW envelope`() {
        val executor = executorOf("exploding.tool")
        executor.failure = IllegalStateException("boom")
        val registry = ToolRegistry(listOf(executor))

        val result = registry.execute(ToolCall("call-7", "exploding.tool", "{}"))

        assertEquals("call-7", result.id)
        assertFalse(result.ok)
        // The executor was dispatched and may have applied something: no tri-state fact may claim "no change".
        assertEquals(ToolOutcome.Uncertain, result.execution.outcome)
        assertEquals("TOOL_THREW", result.execution.code)
        assertTrue(result.execution.uncertain, "a thrown call is explicitly uncertain")
        assertNull(result.execution.accepted, "a dispatched throw is not known to have been accepted")
        assertNull(result.execution.applied, "a dispatched throw is not known to have applied nothing")
        assertNull(result.execution.changed, "a dispatched throw is not known to have changed nothing")
        assertNull(result.observation)
        assertEquals(
            """{"execution":{"outcome":"uncertain","code":"TOOL_THREW","message":null,"recoverable":false,""" +
                """"accepted":null,"applied":null,"verified":null,"changed":null,"uncertain":true,"partial":false},""" +
                """"observation":null,"data":""}""",
            result.payload,
        )
    }

    @Test fun `a screen tool throw leaves the registry observation null for the loop to fill`() {
        // ToolRegistry has no observation source by design: the runner owns that host seam. Its thrown result must not
        // fabricate one, and Task 3's loop enrichment is what turns a screen tool's `TOOL_THREW` into an explicit
        // unavailable observation. This pins the boundary so a later refactor does not invent a screen here.
        val executor = RecordingActionExecutor(listOf(actionDescriptor("device", screenObservation = true)))
        executor.failure = IllegalStateException("boom")

        val result = ToolRegistry(listOf(executor)).execute(ToolCall("call-10", "device", "{}"))

        assertEquals(ToolOutcome.Uncertain, result.execution.outcome)
        assertEquals("TOOL_THREW", result.execution.code)
        assertTrue(result.execution.uncertain)
        assertNull(result.observation, "the registry has no source; the loop enrichment attaches the observation")
    }

    @Test fun `ok follows the explicit execution instead of contradicting it`() {
        // A migrated tool states its own execution facts. A legacy `Done` outcome must not report a success the
        // explicit execution denies: `ok` is derived from the execution the envelope and every consumer carry.
        val executor = executorOf("device")
        executor.outcome = { _, _ ->
            ActionResult(
                ActionOutcome.Done,
                "STATE_UNCHANGED",
                execution = ToolExecution(outcome = ToolOutcome.Failed, code = "STATE_UNCHANGED"),
            )
        }

        val result = ToolRegistry(listOf(executor)).execute(ToolCall("call-ok", "device", "{}"))

        assertFalse(result.ok)
        assertEquals(ToolOutcome.Failed, result.execution.outcome)
        assertEquals("STATE_UNCHANGED", result.execution.code)
    }

    @Test fun `an explicit execution and observation from the tool survive projection`() {
        val execution = ToolExecution(
            outcome = ToolOutcome.Done,
            code = "OPEN_VERIFIED",
            message = "opened",
            accepted = true,
            applied = true,
            verified = true,
            changed = true,
        )
        val observation = ToolObservation(
            snapshotId = "snap-9",
            foregroundPackage = "com.example",
            screen = "Home",
            vision = ToolVision.Ok,
        )
        val executor = executorOf("open")
        executor.outcome = { _, _ ->
            ActionResult(
                ActionOutcome.Done,
                """{"opened":true}""",
                execution = execution,
                observation = observation,
            )
        }
        val registry = ToolRegistry(listOf(executor))

        val result = registry.execute(ToolCall("call-9", "open", """{"target":"home"}"""))

        assertTrue(result.ok)
        assertEquals(execution, result.execution, "an explicit execution is the tool's fact, not the registry's guess")
        assertEquals(observation, result.observation)
        assertEquals("""{"opened":true}""", result.data)
        assertFalse(result.data.contains("Home"), "observation fields must not be repeated in data")
        assertEquals(
            """{"execution":{"outcome":"done","code":"OPEN_VERIFIED","message":"opened","recoverable":false,""" +
                """"accepted":true,"applied":true,"verified":true,"changed":true,"uncertain":false,"partial":false},""" +
                """"observation":{"snapshotId":"snap-9","capturedAtEpochMillis":null,"foregroundPackage":"com.example",""" +
                """"foregroundActivity":null,"rootPackage":null,"coverage":null,"screen":"Home","truncated":false,""" +
                """"vision":"ok","visionReason":null},"data":{"opened":true}}""",
            result.payload,
        )
    }

    @Test fun `registration applies no risk gate and no confirmation filter`() {
        // Risk gating, confirmation and budgets belong to Tasks 7/8 and the loop; the registry must not pre-empt them.
        val dangerous = executorOf("device.factory_reset", risk = RiskLevel.L4)
        val registry = ToolRegistry(listOf(dangerous))

        assertEquals(1, registry.descriptors().size)
        assertNotNull(registry.resolve("device.factory_reset"))

        val result = registry.execute(ToolCall("call-8", "device.factory_reset", "{}"))

        assertTrue(result.ok)
    }

    // ---------------------------------------------------------------- Task 12 (R57/R60/R61)

    @Test fun `descriptors project the observation and timeout declarations additively`() {
        val declared = RecordingActionExecutor(
            listOf(actionDescriptor("device", observation = true, timeoutMillis = 5_000L)),
        )
        val descriptor = ToolRegistry(listOf(declared)).descriptors().single()

        assertTrue(descriptor.observation)
        assertEquals(5_000L, descriptor.timeoutMillis)

        // The defaults reproduce the pre-0.25 declaration exactly: not an observation, ordinary 30 s cap.
        val plain = ToolRegistry(listOf(executorOf("lookup"))).descriptors().single()
        assertFalse(plain.observation)
        assertEquals(30_000L, plain.timeoutMillis)
    }

    @Test fun `descriptors project the screen observation declaration additively`() {
        // edith-0.3 Phase 2.2 Task 3: this is a screen-tool declaration, separate from `observation` (fingerprints).
        val screen = RecordingActionExecutor(listOf(actionDescriptor("device", screenObservation = true)))
        val descriptor = ToolRegistry(listOf(screen)).descriptors().single()

        assertTrue(descriptor.screenObservation)

        // Pure tools keep the default false, so the loop never invents a screen for notify_user/load_skill.
        assertFalse(ToolRegistry(listOf(executorOf("notify_user"))).descriptors().single().screenObservation)
    }

    @Test fun `descriptors project the human confirmation allowance additively`() {
        val shell = RecordingActionExecutor(listOf(actionDescriptor("shell", mayAskHuman = true)))
        assertTrue(ToolRegistry(listOf(shell)).descriptors().single().mayAskHuman)

        // No other tool may claim a human wait inside execute: the default keeps every existing action unchanged.
        assertFalse(ToolRegistry(listOf(executorOf("lookup"))).descriptors().single().mayAskHuman)
    }

    @Test fun `the fingerprint rides through every outcome`() {
        val executor = executorOf("device")
        val registry = ToolRegistry(listOf(executor))

        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "screen", fingerprint = "fp-1") }
        assertEquals("fp-1", registry.execute(ToolCall("c1", "device", "{}")).fingerprint)

        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Refused, "NODE_STALE", fingerprint = "fp-1") }
        val refused = registry.execute(ToolCall("c2", "device", "{}"))
        assertFalse(refused.ok)
        assertEquals("fp-1", refused.fingerprint, "a refused write still reports the screen it left behind")
    }

    @Test fun `the state changing declaration rides through every outcome`() {
        val executor = executorOf("device")
        val registry = ToolRegistry(listOf(executor))

        // Default: no declaration — reads and every plugin tool keep the pre-R98 behaviour.
        assertFalse(registry.execute(ToolCall("c0", "device", "{}")).stateChanging)

        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "screen", stateChanging = true) }
        assertTrue(registry.execute(ToolCall("c1", "device", "{}")).stateChanging)

        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Refused, "NODE_STALE", stateChanging = true) }
        assertTrue(
            registry.execute(ToolCall("c2", "device", "{}")).stateChanging,
            "a refused write is still a state-changing call",
        )

        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED", stateChanging = true) }
        assertTrue(registry.execute(ToolCall("c3", "device", "{}")).stateChanging)
    }

    @Test fun `the images a tool produced ride through every outcome`() {
        // 0.25 Task 24 (C1/R133): the result carrier is the only channel from a screenshot's artifact reference to the
        // model request. A refused or failed call keeps whatever the executor reported, exactly like the fingerprint.
        val executor = executorOf("device")
        val registry = ToolRegistry(listOf(executor))
        val image = ImageRef("img-shot-1", "image/jpeg", 768, 1664, "sha-256-shot-1")

        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "captured", images = listOf(image)) }
        assertEquals(listOf(image), registry.execute(ToolCall("c1", "device", "{}")).images)

        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Refused, "NODE_STALE", images = listOf(image)) }
        assertEquals(listOf(image), registry.execute(ToolCall("c2", "device", "{}")).images)

        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Failed, "SCREENSHOT_FAILED", images = listOf(image)) }
        assertEquals(listOf(image), registry.execute(ToolCall("c3", "device", "{}")).images)

        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "done") }
        assertEquals(emptyList(), registry.execute(ToolCall("c4", "device", "{}")).images)
    }

    @Test fun `cancel reaches every executor once including a duplicate that lost the merge`() {
        val survivor = executorOf("shared", risk = RiskLevel.L1)
        val loser = executorOf("shared", risk = RiskLevel.L0)
        val distinct = executorOf("other")
        val registry = ToolRegistry(listOf(survivor, loser, distinct))

        registry.cancel()

        assertEquals(1, survivor.cancellations)
        assertEquals(1, loser.cancellations, "an executor that declared a tool must stop even if it lost the merge")
        assertEquals(1, distinct.cancellations)
    }

    @Test fun `beginTurn reaches every executor once including a duplicate that lost the merge`() {
        // R97: the same host executor set `cancel` reaches must be re-armed after a confirmed tool timeout.
        val survivor = executorOf("shared", risk = RiskLevel.L1)
        val loser = executorOf("shared", risk = RiskLevel.L0)
        val distinct = executorOf("other")
        val registry = ToolRegistry(listOf(survivor, loser, distinct))

        registry.beginTurn()

        assertEquals(1, survivor.begins)
        assertEquals(1, loser.begins, "an executor that declared a tool must re-arm even if it lost the merge")
        assertEquals(1, distinct.begins)
    }

    @Test fun `a throwing beginTurn does not stop the other executors from re-arming`() {
        val throwing = executorOf("throwing")
        throwing.beginTurnFailure = IllegalStateException("boom")
        val healthy = executorOf("healthy")
        val registry = ToolRegistry(listOf(throwing, healthy))

        registry.beginTurn()

        assertEquals(1, throwing.begins, "every executor is attempted before the first failure is swallowed")
        assertEquals(1, healthy.begins)
    }

    /** The `data` member of a result's envelope, decoded back to the tool detail string. */
    private fun payloadData(result: ToolResult): String =
        (Json.parseToJsonElement(result.payload) as JsonObject).getValue("data").jsonPrimitive.content

    /**
     * The framework-synthesized **pre-dispatch** failure shape: the supplied stable code,
     * `accepted=false`/`applied=false` (the facts an unknown tool, unparsable arguments or a policy refusal
     * establish), and no invented observation. A **dispatched** throw is *not* this shape: it is `Uncertain` with
     * unknown tri-state fields (see the TOOL_THREW test).
     */
    private fun assertErrorResult(result: ToolResult, code: String, id: String? = null) {
        if (id != null) assertEquals(id, result.id)
        assertFalse(result.ok)
        assertEquals(ToolOutcome.Failed, result.execution.outcome)
        assertEquals(code, result.execution.code)
        assertEquals(false, result.execution.accepted)
        assertEquals(false, result.execution.applied)
        assertNull(result.observation)
        assertEquals(
            """{"execution":{"outcome":"failed","code":"$code","message":null,"recoverable":false,""" +
                """"accepted":false,"applied":false,"verified":null,"changed":null,"uncertain":false,""" +
                """"partial":false},"observation":null,"data":""}""",
            result.payload,
        )
        assertFalse(result.payload.contains("Failed: "), "no legacy prefix: ${result.payload}")
        assertFalse(result.payload.contains("Refused: "), "no legacy prefix: ${result.payload}")
    }
}
