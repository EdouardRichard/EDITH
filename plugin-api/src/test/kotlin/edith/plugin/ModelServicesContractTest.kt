package edith.plugin

import edith.contracts.*
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

    @Test fun `request rejects malformed routing and suppresses body in diagnostics`() {
        val request = ModelRequest(EventEnvelope(SessionId("s"), Generation(0), "e", 0, 1),
            "q", "t", "p", 1, "private-instructions", listOf(InputPart.Text("private-input")),
            listOf(TextMessage(MessageRole.USER, "private-history")),
            listOf(ContextPart("c", "private-context", 1)), OutputModality.TEXT, 100)
        assertFailsWith<IllegalArgumentException> { request.copy(requestId = "") }
        assertFailsWith<IllegalArgumentException> { request.copy(profileRevision = -1) }
        assertFailsWith<IllegalArgumentException> { request.copy(deadlineNanos = -1) }
        assertFailsWith<IllegalArgumentException> { request.copy(input = emptyList()) }
        listOf(request.toString(), request.input.toString(), request.history.toString(), request.context.toString(),
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
}
