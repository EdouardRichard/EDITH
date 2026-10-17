package com.sheen.rokid.plugin

import com.sheen.rokid.contracts.*
import kotlin.test.*

class ModelServicesContractTest {
    private val secret = object : SecretHandle { override val id = "opaque-ref" }
    private fun profile() = ModelProfile("p", 1, "test", "service", "https://example.test",
        "DEEPSEEK_RESPONSES", "test-model", secret, 4096, 3000, 256, 128, 10000,
        setOf(InputModality.TEXT, InputModality.AUDIO), setOf(OutputModality.TEXT), emptyList())

    @Test fun `profile budget rejects overcommit and integer overflow`() {
        assertFailsWith<IllegalArgumentException> { profile().copy(maxOutputTokens = 4000) }
        assertFailsWith<IllegalArgumentException> {
            profile().copy(usableWindowTokens = Int.MAX_VALUE, inputBudgetTokens = Int.MAX_VALUE,
                maxOutputTokens = Int.MAX_VALUE)
        }
        assertEquals(256, profile().maxOutputTokens)
    }

    @Test fun `profile requires usable identity modalities and limits`() {
        listOf<() -> ModelProfile>(
            { profile().copy(id = " ") }, { profile().copy(revision = -1) },
            { profile().copy(model = "") }, { profile().copy(inputs = emptySet()) },
            { profile().copy(outputs = emptySet()) }, { profile().copy(timeoutMillis = 0) },
            { profile().copy(inputBudgetTokens = 0) }, { profile().copy(safetyTokens = -1) },
        ).forEach { create -> assertFailsWith<IllegalArgumentException> { create(); Unit } }
    }

    @Test fun `audio input profile remains text output only`() {
        val profile = profile()
        assertTrue(InputModality.AUDIO in profile.inputs)
        assertFalse(OutputModality.AUDIO in profile.outputs)
    }

    @Test fun `profile requires positive safety reserve`() {
        assertFailsWith<IllegalArgumentException> { profile().copy(safetyTokens = 0) }
    }

    @Test fun `request rejects malformed routing and suppresses body in diagnostics`() {
        val request = ModelRequest(EventEnvelope(SessionId("s"), Generation(0), "e", 0, 1),
            "q", "t", "p", 1, "private-instructions", listOf(InputPart.Text("private-input")),
            listOf(ModelHistoryItem.Text(MessageRole.USER, "private-history")),
            OutputModality.TEXT, 100)
        assertFailsWith<IllegalArgumentException> { request.copy(requestId = "") }
        assertFailsWith<IllegalArgumentException> { request.copy(profileRevision = -1) }
        assertFailsWith<IllegalArgumentException> { request.copy(deadlineNanos = -1) }
        // A request must carry something: input and history cannot both be empty.
        assertFailsWith<IllegalArgumentException> { request.copy(input = emptyList(), history = emptyList()) }
        // A history-only request is legal: the continuation of a stateless transcript after a tool round has no new
        // input of its own, and the transcript is the whole request.
        assertEquals(emptyList(), request.copy(input = emptyList()).input)
        listOf(request.toString(), request.input.toString(), request.history.toString(),
            ModelStreamEvent.TextDelta("q", "private-delta").toString()).forEach {
            assertFalse(it.contains("private-"))
        }
    }

    @Test fun `typed extensions retain existing HostServices contract`() {
        val model = object : ModelClient {
            override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit) =
                object : CancellationHandle { override fun cancel() = Unit }
        }
        val services = object : HostServices {
            override fun require(capability: Capability): Any {
                assertEquals(Capability.Model, capability)
                return model
            }
            override val tasks = EmptyHostServices.tasks
        }
        assertSame(model, services.model())
    }

    @Test fun `tool specification requires a name description and parameters schema`() {
        assertEquals(ToolSpec("record_note", "Record a note.", "{}"),
            ToolSpec("record_note", "Record a note.", "{}"))
        for (blank in listOf(
            { ToolSpec("", "d", "{}") }, { ToolSpec(" ", "d", "{}") },
            { ToolSpec("n", "", "{}") }, { ToolSpec("n", " ", "{}") }, { ToolSpec("n", "d", "") },
        )) {
            assertFailsWith<IllegalArgumentException> { blank() }
        }
    }

    @Test fun `tool stream events require identity and carry no body in diagnostics`() {
        for (blank in listOf(
            { ModelStreamEvent.ToolCallDelta("", "i", null, "{}") },
            { ModelStreamEvent.ToolCallDelta("q", "", null, "{}") },
            { ModelStreamEvent.ToolCallTerminal("", "i", "c", "n", "{}") },
            { ModelStreamEvent.ToolCallTerminal("q", "", "c", "n", "{}") },
            { ModelStreamEvent.ToolCallTerminal("q", "i", "", "n", "{}") },
            { ModelStreamEvent.ToolCallTerminal("q", "i", "c", "", "{}") },
        )) {
            assertFailsWith<IllegalArgumentException> { blank() }
        }
        // The endpoint legitimately streams an empty arguments document, so only blank identity is rejected.
        assertEquals("", ModelStreamEvent.ToolCallTerminal("q", "i", "c", "n", "").argsJson)
        val delta = ModelStreamEvent.ToolCallDelta("q", "i", "record_note", "private-arguments")
        val terminal = ModelStreamEvent.ToolCallTerminal("q", "i", "c", "record_note", "private-arguments")
        val history = listOf<ModelHistoryItem>(
            ModelHistoryItem.Text(MessageRole.USER, "private-question"),
            ModelHistoryItem.ToolCall("c", "record_note", "private-arguments", "i"),
            ModelHistoryItem.ToolOutput("c", "private-observation"),
        )
        for (rendered in listOf(delta.toString(), terminal.toString(), history.toString())) {
            assertFalse(rendered.contains("private-"))
        }
        assertEquals("q", delta.requestId)
        assertEquals("i", delta.itemId)
        assertEquals("i", terminal.itemId)
        assertEquals("c", terminal.callId)
        assertNull(ModelStreamEvent.ToolCallDelta("q", "i", null, "{}").name)
    }
}
