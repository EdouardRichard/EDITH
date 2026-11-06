package com.sheen.rokid.phone.model.catalog

import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.model.CapabilityGate
import com.sheen.rokid.model.ContextBudgeter
import com.sheen.rokid.model.ControlledModelClient
import com.sheen.rokid.model.ControlledProfileTester
import com.sheen.rokid.model.GatewayModelSnapshot
import com.sheen.rokid.model.ModelListResult
import com.sheen.rokid.model.ModelListSource
import com.sheen.rokid.model.RequestConsent
import com.sheen.rokid.phone.storage.AndroidModelCatalogRepository
import com.sheen.rokid.phone.storage.CatalogProfileRepository
import com.sheen.rokid.phone.storage.TestBlobs
import com.sheen.rokid.phone.ui.settings.ModelCatalogViewModel
import com.sheen.rokid.phone.ui.settings.ProviderDraft
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.ModelEnd
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.SecretHandle
import com.sheen.rokid.storage.MemorySecretResolver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Final review C1: the protocol id the catalog UI creates must be executable by the runtime.
 *
 * The case deliberately builds the provider through the same [ProviderDraft] the provider editor submits (protocol
 * field omitted, so the UI default is used), fetches its model list through the real [ModelCatalogService], projects the
 * row with [ModelProfileProjection], and then drives the **real** [ControlledModelClient] over MockWebServer for both
 * `probe` and `request`. A fixture-only `DEEPSEEK_RESPONSES` test cannot catch the original bug: the bug was exactly that
 * the catalog's default vocabulary did not match the adapter's support id.
 */
class CatalogProtocolRuntimeTest {

    private lateinit var server: MockWebServer
    private lateinit var resolver: MemorySecretResolver
    private lateinit var repository: AndroidModelCatalogRepository
    private lateinit var client: ControlledModelClient

    private val now = 0L
    private val clock = object : MonotonicClock { override fun nowNanos() = now }
    private val wall: Clock = Clock.fixed(Instant.parse("2026-11-05T00:00:00Z"), ZoneOffset.UTC)
    private val scheduler = object : DeadlineScheduler {
        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class VaultBackedCredentials(private val resolver: MemorySecretResolver) : CatalogCredentialStore {
        override fun stage(value: CharArray): SecretHandle = resolver.put(value)
        override fun revoke(handle: SecretHandle) = resolver.revoke(handle)
    }

    private fun source() = ModelListSource { _, _ ->
        ModelListResult.Success(
            listOf(
                GatewayModelSnapshot(
                    id = "deepseek-chat",
                    displayName = "DeepSeek Chat",
                    contextWindowTokens = 131_072,
                    maxOutputTokens = 8_192,
                    inputs = setOf(InputModality.TEXT, InputModality.IMAGE),
                ),
            ),
            truncated = false,
        )
    }

    @Before
    fun setUp() {
        server = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
        resolver = MemorySecretResolver()
        repository = AndroidModelCatalogRepository(TestBlobs(), VaultBackedCredentials(resolver))
        val profiles = CatalogProfileRepository(repository)
        client = ControlledModelClient(
            OkHttpClient(),
            profiles,
            resolver,
            CapabilityGate(),
            ContextBudgeter(),
            clock,
            scheduler,
            { RequestConsent(text = true, image = false, audio = false, syntheticProbe = true) },
            wall,
            allowLocalHttp = true,
        )
    }

    @After
    fun tearDown() {
        client.close()
        resolver.close()
        server.close()
    }

    /** The reverse-DNS host of a bound loopback address is not "localhost"; pin the literal the URL rule accepts. */
    private fun baseUrl() = server.url("/v1").newBuilder().host("127.0.0.1").build().toString()

    /** Creates the provider through the editor's own draft path; `protocolId` null means the UI default. */
    private fun saveFromUiDraft(protocolId: String? = null): Provider {
        val viewModel = ModelCatalogViewModel(
            repository = repository,
            service = ModelCatalogService(repository, source()),
            tester = null,
            scope = CoroutineScope(Dispatchers.Unconfined),
            clock = { 0L },
        )
        viewModel.enter()
        viewModel.saveProvider(
            ProviderDraft(
                name = "Local gateway",
                baseUrl = baseUrl(),
                apiKey = "sk-synthetic-1234".toCharArray(),
                protocolId = protocolId ?: "OPENAI_RESPONSES",
            ),
        )
        viewModel.leave()
        val error = viewModel.state.value.error
        assertEquals("the provider draft (${baseUrl()}) must be accepted: $error", null, error)
        return requireNotNull(repository.catalog().activeProvider())
    }

    private fun sse(body: String) = MockResponse()
        .addHeader("Content-Type", "text/event-stream; charset=utf-8")
        .setBody(body)

    private fun probe(profile: com.sheen.rokid.plugin.ModelProfile): Pair<ModelEnd?, String?> {
        server.enqueue(sse(COMPLETED))
        val terminal = AtomicReference<ModelStreamEvent.Terminal?>()
        val latch = CountDownLatch(1)
        ControlledProfileTester(client, CatalogProfileRepository(repository), clock, wall)
            .testText(profile.id) { event ->
                if (event is ModelStreamEvent.Terminal) {
                    terminal.set(event)
                    latch.countDown()
                }
            }
        assertTrue("the probe terminal must arrive", latch.await(5, TimeUnit.SECONDS))
        return terminal.get()!!.end to terminal.get()!!.safeCode
    }

    private fun ordinaryRequest(profile: com.sheen.rokid.plugin.ModelProfile): ModelEnd? {
        server.enqueue(sse(COMPLETED))
        val terminal = AtomicReference<ModelStreamEvent.Terminal?>()
        val latch = CountDownLatch(1)
        val current = requireNotNull(CatalogProfileRepository(repository).get(profile.id))
        val request = ModelRequest(
            EventEnvelope(SessionId("session"), Generation(1), "event", now, now),
            requestId = "request-1",
            turnId = "turn-1",
            profileId = current.id,
            profileRevision = current.revision,
            instructions = "synthetic instructions",
            input = listOf(InputPart.Text("hello")),
            history = emptyList(),
            output = OutputModality.TEXT,
            deadlineNanos = Long.MAX_VALUE,
        )
        client.request(request) { event ->
            if (event is ModelStreamEvent.Terminal) {
                terminal.set(event)
                latch.countDown()
            }
        }
        assertTrue("the request terminal must arrive", latch.await(5, TimeUnit.SECONDS))
        return terminal.get()?.end
    }

    @Test
    fun `the UI default protocol reaches the Responses endpoint for probe and request`() {
        val provider = saveFromUiDraft()
        assertEquals("the provider editor's default must be the single-source Responses id",
            "OPENAI_RESPONSES", provider.protocolId)
        val entry = provider.models.single()
        val profile = requireNotNull(CatalogProfileRepository(repository)
            .get(ModelProfileProjection.profileId(provider.id, entry.id)))
        assertEquals("OPENAI_RESPONSES", profile.protocolId)

        assertEquals(ModelEnd.COMPLETED, probe(profile).first)

        val probeRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("POST", probeRequest.method)
        assertEquals("the Responses endpoint, not Chat Completions", "/v1/responses", probeRequest.path)
        assertEquals("Bearer sk-synthetic-1234", probeRequest.getHeader("Authorization"))
        assertEquals("text/event-stream", probeRequest.getHeader("Accept"))
        assertEquals("application/json", probeRequest.getHeader("Content-Type"))
        val probeBody = probeRequest.body.readUtf8()
        assertTrue("the probe body uses the Responses request shape: $probeBody",
            probeBody.contains("\"model\":\"deepseek-chat\""))
        assertTrue("the fixed synthetic probe prompt reaches the wire: $probeBody",
            probeBody.contains("只回复测试通过"))

        assertTrue("the probe records current-revision TEXT_REQUEST evidence",
            requireNotNull(CatalogProfileRepository(repository).get(profile.id)).verifications.any {
                it.operation == "TEXT_REQUEST" && it.profileRevision == profile.revision
            })

        assertEquals(ModelEnd.COMPLETED, ordinaryRequest(profile))

        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/responses", request.path)
        assertEquals("Bearer sk-synthetic-1234", request.getHeader("Authorization"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
        assertEquals("application/json", request.getHeader("Content-Type"))
        val body = request.body.readUtf8()
        assertTrue("the ordinary request is encoded by the Responses adapter: $body",
            body.contains("\"instructions\":\"synthetic instructions\""))
        assertTrue(body.contains("\"model\":\"deepseek-chat\""))
    }

    @Test
    fun `a legacy DEEPSEEK_RESPONSES provider keeps working through the documented alias`() {
        val provider = saveFromUiDraft(protocolId = "DEEPSEEK_RESPONSES")
        assertEquals("DEEPSEEK_RESPONSES", provider.protocolId)
        val profile = requireNotNull(CatalogProfileRepository(repository).get(
            ModelProfileProjection.profileId(provider.id, provider.models.single().id)))

        assertEquals(ModelEnd.COMPLETED, probe(profile).first)
        val probeRequest = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/responses", probeRequest.path)

        assertEquals(ModelEnd.COMPLETED, ordinaryRequest(profile))
        val request = requireNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        assertEquals("/v1/responses", request.path)
        assertNotNull(request.body)
    }

    private companion object {
        private const val COMPLETED =
            "data: {\"type\":\"response.completed\",\"sequence_number\":2,\"response\":{\"id\":\"s\",\"status\":\"completed\"}}\n\n"
    }
}
