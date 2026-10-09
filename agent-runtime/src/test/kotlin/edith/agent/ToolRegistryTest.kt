package edith.agent

import edith.plugin.ActionOutcome
import edith.plugin.ActionResult
import edith.plugin.RiskLevel
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ToolRegistryTest {

    @Test fun `the terminal scope constant keeps its wire value`() {
        // Task 6's loop reads this exact string; the value is a contract, not a local choice.
        assertEquals("terminal", SCOPE_TERMINAL)
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
        assertEquals("UNKNOWN_TOOL", result.payload)
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
        assertEquals(lenientOnly, result.payload)
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
        assertEquals(rawNewline, result.payload)
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
            assertEquals("ARGUMENTS_INVALID", result.payload, "arguments: <$args>")
            assertTrue(executor.calls.isEmpty(), "executor ran for arguments: <$args>")
        }
    }

    @Test fun `a Done outcome becomes a successful result carrying the detail`() {
        val executor = executorOf("device.set_volume")
        executor.outcome = { _, _ -> ActionResult(ActionOutcome.Done, "volume set to 3") }
        val registry = ToolRegistry(listOf(executor))

        val result = registry.execute(ToolCall("call-5", "device.set_volume", """{"level": 3}"""))

        assertEquals("call-5", result.id)
        assertTrue(result.ok)
        assertEquals("volume set to 3", result.payload)
    }

    @Test fun `Refused and Failed outcomes become failed steps prefixed with the outcome name`() {
        listOf(
            ActionOutcome.Refused to "user declined",
            ActionOutcome.Failed to "device offline",
        ).forEach { (outcome, detail) ->
            val executor = executorOf("device.set_volume")
            executor.outcome = { _, _ -> ActionResult(outcome, detail) }
            val registry = ToolRegistry(listOf(executor))

            val result = registry.execute(ToolCall("call-6", "device.set_volume", "{}"))

            assertFalse(result.ok, "outcome: $outcome")
            assertEquals("${outcome.name}: $detail", result.payload)
        }
    }

    @Test fun `an executor that throws never propagates and becomes a TOOL_THREW step`() {
        val executor = executorOf("exploding.tool")
        executor.failure = IllegalStateException("boom")
        val registry = ToolRegistry(listOf(executor))

        val result = registry.execute(ToolCall("call-7", "exploding.tool", "{}"))

        assertEquals("call-7", result.id)
        assertFalse(result.ok)
        assertEquals("TOOL_THREW", result.payload)
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
}
