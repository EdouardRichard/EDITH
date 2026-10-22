package com.sheen.rokid.model

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.*
import com.sheen.rokid.storage.*
import okhttp3.*
import okhttp3.mockwebserver.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class ControlledModelClientTest {
    @Test fun `503 zero waits for application retry scheduler before second POST`() = Rig().use { rig ->
        repeat(2) { rig.server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0")) }
        rig.start()
        val retry = rig.timers.awaitRetry()
        assertEquals(0L, retry.deadline)
        assertEquals(1, rig.server.requestCount, "only the application scheduler may repeat a POST")
        assertTrue(rig.ends().isEmpty())
    }

    @Test fun `503 zero on both attempts sends at most two identical POSTs`() = Rig().use { rig ->
        repeat(4) { rig.server.enqueue(MockResponse().setResponseCode(503).addHeader("Retry-After", "0")) }
        rig.start()
        rig.timers.fire(rig.timers.awaitRetry())
        rig.awaitTerminal()
        assertEquals(2, rig.server.requestCount)
        assertEquals(listOf(ModelEnd.FAILED), rig.ends())
        val first = rig.awaitRequest()
        val second = rig.awaitRequest()
        assertEquals("POST", first.method)
        assertEquals("/v1/responses", first.path)
        assertEquals("Bearer dummy-key", first.getHeader("Authorization"))
        assertEquals("text/event-stream", first.getHeader("Accept"))
        assertEquals("application/json", first.getHeader("Content-Type"))
        assertEquals(first.headers, second.headers)
        val bytes = first.body.readByteArray()
        assertContentEquals(bytes, second.body.readByteArray())
        assertEquals(bytes.size.toString(), first.getHeader("Content-Length"))
        val json = kotlinx.serialization.json.Json.parseToJsonElement(bytes.toString(Charsets.UTF_8))
        assertEquals(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"model":"model","instructions":"synthetic instructions","stream":true,"max_output_tokens":256,"input":[{"role":"user","content":"synthetic question"}]}"""), json)
    }

    @Test fun `injected authenticator cannot replay 401`() = Rig(authenticator = Authenticator { _, response ->
        response.request.newBuilder().header("X-Synthetic-Auth", "retry").build()
    }).use { rig ->
        rig.server.enqueue(MockResponse().setResponseCode(401))
        rig.server.enqueue(sse(COMPLETED))
        rig.start(); rig.awaitTerminal()
        assertEquals(1, rig.server.requestCount)
        assertEquals(listOf(ModelEnd.FAILED), rig.ends())
        assertEquals("HTTP_AUTH", rig.events.filterIsInstance<ModelStreamEvent.Terminal>().single().safeCode)
    }

    @Test fun `injected proxy authenticator cannot replay 407`() = Rig(proxyAuthenticator = Authenticator { _, response ->
        response.request.newBuilder().header("X-Synthetic-Proxy-Auth", "retry").build()
    }, useProxy = true).use { rig ->
        rig.server.enqueue(MockResponse().setResponseCode(407))
        rig.server.enqueue(sse(COMPLETED))
        rig.start(); rig.awaitTerminal()
        assertEquals(1, rig.server.requestCount)
        assertEquals(listOf(ModelEnd.FAILED), rig.ends())
        assertEquals("HTTP_STATUS", rig.events.filterIsInstance<ModelStreamEvent.Terminal>().single().safeCode)
    }

    @Test fun `a provider context overflow 400 is reported as CONTEXT_OVERFLOW`() = Rig().use { rig ->
        // 0.25 Task 13 / R70: the provider's own overflow signal is one of the two detectors the gateway recovers from.
        rig.server.enqueue(MockResponse().setResponseCode(400)
            .addHeader("Content-Type", "application/json")
            .setBody("""{"error":{"code":"context_length_exceeded","message":"maximum context length exceeded"}}"""))
        rig.start(); rig.awaitTerminal()
        assertEquals(listOf(ModelEnd.FAILED), rig.ends())
        assertEquals(
            com.sheen.rokid.agent.CONTEXT_OVERFLOW,
            rig.events.filterIsInstance<ModelStreamEvent.Terminal>().single().safeCode,
        )
    }

    @Test fun `an unrelated provider 400 keeps the existing HTTP_STATUS code`() = Rig().use { rig ->
        rig.server.enqueue(MockResponse().setResponseCode(400)
            .addHeader("Content-Type", "application/json")
            .setBody("""{"error":{"code":"invalid_api_key","message":"bad key"}}"""))
        rig.start(); rig.awaitTerminal()
        assertEquals(listOf(ModelEnd.FAILED), rig.ends())
        assertEquals(
            "HTTP_STATUS",
            rig.events.filterIsInstance<ModelStreamEvent.Terminal>().single().safeCode,
        )
    }

    @Test fun `profile change before registration is fenced before secret or call`() = Rig().use { rig ->
        val input = rig.request()
        var first = true
        var resolutions = 0
        val repository = object : ProfileRepository by rig.repository {
            override fun get(id: String): ModelProfile? {
                val captured = rig.repository.get(id)
                if (first) { first = false; rig.repository.save(rig.profile.copy(name = "raced")) }
                return captured
            }
        }
        val resolver = object : SecretResolver {
            override fun withSecret(handle: SecretHandle, block: (CharArray) -> Unit) {
                resolutions++; rig.resolver.withSecret(handle, block)
            }
            override fun revoke(handle: SecretHandle) { rig.resolver.revoke(handle) }
        }
        ControlledModelClient(OkHttpClient(), repository, resolver, CapabilityGate(), ContextBudgeter(), rig.clock,
            rig.timers, { rig.consent }, rig.wall, allowLocalHttp = true).use { client ->
            client.request(input, rig::event)
            rig.awaitTerminal()
            assertEquals(listOf(ModelEnd.CANCELLED), rig.ends())
            assertEquals(0, resolutions)
            assertEquals(0, rig.server.requestCount)
        }
    }

    @Test fun `expired admission emits deadline without a network call`() = Rig().use { rig ->
        rig.client.request(rig.request().copy(deadlineNanos = 0), rig::event)
        rig.awaitTerminal()
        assertEquals(listOf(ModelEnd.DEADLINE), rig.ends())
        assertEquals(0, rig.server.requestCount)
    }

    @Test fun `cancel fences late service text`() = Rig().use { rig ->
        val arrived = CountDownLatch(1)
        val release = CountDownLatch(1)
        rig.server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                arrived.countDown()
                assertTrue(release.await(5, TimeUnit.SECONDS))
                return sse(CREATED + DELTA + COMPLETED)
            }
        }
        val handle = rig.start()
        assertTrue(arrived.await(5, TimeUnit.SECONDS))
        handle.cancel(); handle.cancel(); release.countDown()
        rig.awaitTerminal()
        assertTrue(rig.failed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(ModelEnd.CANCELLED), rig.ends())
        assertTrue(rig.events.filterIsInstance<ModelStreamEvent.TextDelta>().isEmpty())
    }

    @Test fun `5xx without retry header uses delay then completes`() = Rig().use { rig ->
        rig.server.enqueue(MockResponse().setResponseCode(503))
        rig.server.enqueue(sse(COMPLETED))
        rig.start(); rig.awaitRequest()
        val retry = rig.timers.awaitRetry()
        assertEquals(250_000_000L, retry.deadline)
        rig.timers.fire(retry); rig.awaitTerminal()
        assertEquals(listOf(ModelEnd.COMPLETED), rig.ends())
        assertEquals(2, rig.server.requestCount)
        assertTrue(rig.timers.entries.all { it.cancelled })
    }

    @Test fun `cancelling during retry wait prevents another call`() = Rig().use { rig ->
        rig.server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", "2"))
        val handle = rig.start(); rig.awaitRequest()
        val retry = rig.timers.awaitRetry()
        handle.cancel(); rig.timers.fire(retry); rig.awaitTerminal()
        assertEquals(listOf(ModelEnd.CANCELLED), rig.ends())
        assertEquals(1, rig.server.requestCount)
    }

    @Test fun `same turn calls cancel independently`() = Rig().use { rig ->
        rig.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        rig.server.enqueue(sse(COMPLETED))
        val first = rig.start(); rig.awaitRequest()
        val secondDone = CountDownLatch(1)
        val secondEvents = CopyOnWriteArrayList<ModelStreamEvent>()
        rig.client.request(rig.request().copy(requestId = "other")) {
            secondEvents += it; if (it is ModelStreamEvent.Terminal) secondDone.countDown()
        }
        first.cancel()
        assertTrue(secondDone.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(ModelEnd.COMPLETED), secondEvents.filterIsInstance<ModelStreamEvent.Terminal>().map { it.end })
        assertEquals(listOf(ModelEnd.CANCELLED), rig.ends())
    }

    @Test fun `deep JSON becomes safe terminal rather than escaping worker`() = Rig().use { rig ->
        rig.server.enqueue(sse("data: " + "[".repeat(20000) + "0" + "]".repeat(20000) + "\n\n"))
        rig.start(); rig.awaitTerminal()
        assertEquals(listOf(ModelEnd.FAILED), rig.ends())
        assertEquals("JSON_DEPTH_LIMIT", rig.events.filterIsInstance<ModelStreamEvent.Terminal>().single().safeCode)
    }

    @Test fun `cancel closes call and emits one terminal`() = Rig().use { rig ->
        rig.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val handle = rig.start()
        rig.awaitRequest()
        handle.cancel(); handle.cancel()
        assertTrue(rig.failed.await(5, TimeUnit.SECONDS), "transport must close before service responds")
        rig.awaitTerminal()
        assertEquals(listOf(ModelEnd.CANCELLED), rig.ends())
        assertTrue(rig.events.filterIsInstance<ModelStreamEvent.TextDelta>().isEmpty())
    }

    @Test fun `admission gates precede secrets and network`() = Rig().use { rig ->
        rig.consent = RequestConsent(false, false, false, false)
        assertFailsWith<ContractError.CapabilityMissing> { rig.start() }
        rig.consent = RequestConsent(true, false, false, false)
        rig.profile = rig.repository.save(rig.profile)
        assertFailsWith<ContractError.CapabilityMissing> { rig.start() }
        assertEquals(0, rig.resolutions.get())
        rig.verify()
        rig.resolver.revoke(rig.profile.secret)
        assertFailsWith<ContractError.ConfigurationRejected> { rig.start() }
        assertEquals(0, rig.server.requestCount)
    }

    @Test fun `unsafe endpoint and budget fail before secret access`() = Rig().use { rig ->
        rig.resolver.revoke(rig.profile.secret)
        rig.profile = rig.repository.save(rig.profile.copy(baseUrl = "https://user:pass@example.test/?secret=x"))
        rig.verify()
        assertFailsWith<ContractError.ConfigurationRejected> { rig.start() }
        // R101: the budget's own overflow is a dedicated signal; a plain `ConfigurationRejected` stays for every
        // configuration refusal that a cheaper context could not fix. The budget is in token units, so the
        // instructions have to exceed `profileFixture()`'s 3_000-token cap at one unit per four ASCII characters:
        // 13_000 characters price at 3_250 units; the byte-era 5_000 characters priced at 5_000 byte units.
        assertFailsWith<ContractError.ContextWindowExceeded> {
            rig.client.request(rig.request().copy(instructions = "x".repeat(13_000)), rig::event)
        }
        assertEquals(0, rig.resolutions.get())
        assertEquals(0, rig.server.requestCount)
    }

    @Test fun `401 never retries and error body stays private`() = Rig().use { rig ->
        rig.server.enqueue(MockResponse().setResponseCode(401).setBody("error-body dummy-key Authorization"))
        rig.start(); rig.awaitTerminal()
        assertEquals(listOf(ModelEnd.FAILED), rig.ends())
        assertEquals(1, rig.server.requestCount)
        assertEquals("HTTP_AUTH", rig.events.filterIsInstance<ModelStreamEvent.Terminal>().single().safeCode)
        assertFalse(rig.events.toString().contains("dummy-key"))
        assertFalse(rig.events.toString().contains("error-body"))
    }

    @Test fun `retry waits for seconds or HTTP date and replays only once`() {
        for (header in listOf("2", "Wed, 07 Oct 2026 00:00:02 GMT")) Rig().use { rig ->
            rig.server.enqueue(MockResponse().setResponseCode(429).addHeader("Retry-After", header))
            rig.server.enqueue(MockResponse().setResponseCode(503))
            rig.start(); rig.awaitRequest()
            val retry = rig.timers.awaitRetry()
            assertEquals(2_000_000_000L, retry.deadline)
            assertEquals(1, rig.server.requestCount)
            rig.timers.fire(retry)
            rig.awaitTerminal()
            assertEquals(2, rig.server.requestCount)
            assertEquals(listOf(ModelEnd.FAILED), rig.ends())
        }
    }

    @Test fun `retry refuses invalid overflow or deadline exceeding delay`() {
        for (header in listOf("11", "9223372036854775808", "invalid")) Rig().use { rig ->
            rig.server.enqueue(MockResponse().setResponseCode(500).addHeader("Retry-After", header))
            rig.start(); rig.awaitTerminal()
            assertEquals(1, rig.server.requestCount)
            assertEquals(listOf(ModelEnd.FAILED), rig.ends())
        }
    }

    @Test fun `redirect never forwards authorization`() = Rig().use { rig ->
        MockWebServer().use { target ->
            target.start()
            rig.server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", target.url("/responses")))
            rig.start(); rig.awaitTerminal()
            assertEquals(0, target.requestCount)
            assertEquals(1, rig.server.requestCount)
        }
    }

    @Test fun `delta followed by disconnect is not replayed`() = Rig().use { rig ->
        rig.server.enqueue(sse(CREATED + DELTA).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
        rig.start(); rig.awaitTerminal()
        assertEquals(listOf("hi"), rig.events.filterIsInstance<ModelStreamEvent.TextDelta>().map { it.text })
        assertEquals(1, rig.server.requestCount)
        assertEquals(1, rig.ends().size)
    }

    @Test fun `profile edit cancels stream but preserves unchanged handle`() = Rig().use { rig ->
        rig.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        rig.start(); rig.awaitRequest()
        rig.repository.save(rig.profile.copy(name = "edited"))
        rig.awaitTerminal()
        assertEquals(listOf(ModelEnd.CANCELLED), rig.ends())
        rig.resolver.withSecret(rig.profile.secret) { assertEquals("dummy-key", String(it)) }
        assertTrue(rig.repository.get("p")!!.verifications.isEmpty())
    }

    @Test fun `deadline closes waiting call`() = Rig().use { rig ->
        rig.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        rig.start(); rig.awaitRequest()
        rig.timers.fire(rig.timers.entries.first())
        rig.awaitTerminal()
        assertEquals(listOf(ModelEnd.DEADLINE), rig.ends())
        assertTrue(rig.failed.await(5, TimeUnit.SECONDS))
    }

    @Test fun `raw comments cannot bypass response cap`() = Rig().use { rig ->
        rig.server.enqueue(sse(": comment\n\n".repeat(100_000)))
        rig.start(); rig.awaitTerminal()
        assertEquals("RESPONSE_LIMIT", rig.events.filterIsInstance<ModelStreamEvent.Terminal>().single().safeCode)
    }

    @Test fun `wrong content type is rejected before text`() = Rig().use { rig ->
        rig.server.enqueue(MockResponse().setBody(CREATED + DELTA + COMPLETED).addHeader("Content-Type", "application/json"))
        rig.start(); rig.awaitTerminal()
        assertTrue(rig.events.filterIsInstance<ModelStreamEvent.TextDelta>().isEmpty())
        assertEquals("CONTENT_TYPE", rig.events.filterIsInstance<ModelStreamEvent.Terminal>().single().safeCode)
    }

    @Test fun `new turn replaces old and stale generation is rejected`() = Rig().use { rig ->
        rig.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        rig.server.enqueue(sse(COMPLETED))
        rig.start(); rig.awaitRequest()
        val secondDone = CountDownLatch(1)
        rig.client.request(rig.request().copy(requestId = "r2", turnId = "t2",
            envelope = rig.request().envelope.copy(generation = com.sheen.rokid.contracts.Generation(2)))) {
            if (it is ModelStreamEvent.Terminal) secondDone.countDown()
        }
        rig.awaitTerminal(); assertTrue(secondDone.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(ModelEnd.CANCELLED), rig.ends())
        assertFailsWith<ContractError.ConfigurationRejected> { rig.start() }
    }

    @Test fun `host probe fixes input and only verifies current completed revision`() = Rig().use { rig ->
        rig.profile = rig.repository.save(rig.profile)
        rig.consent = RequestConsent(true, false, false, true)
        rig.server.enqueue(sse(COMPLETED))
        val tester = ControlledProfileTester(rig.client, rig.repository, rig.clock, rig.wall)
        tester.testText("p", rig::event)
        val recorded = rig.awaitRequest()
        rig.awaitTerminal()
        val json = kotlinx.serialization.json.Json.parseToJsonElement(recorded.body.readUtf8()).toString()
        assertTrue(json.contains("只回复测试通过"))
        assertFalse(json.contains("synthetic question"))
        assertEquals(VerificationState.Verified, rig.repository.get("p")!!.verifications.single().state)
    }

    @Test fun `cancelled probe cannot verify edited revision`() = Rig().use { rig ->
        rig.profile = rig.repository.save(rig.profile)
        rig.consent = RequestConsent(true, false, false, true)
        rig.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        ControlledProfileTester(rig.client, rig.repository, rig.clock, rig.wall).testText("p", rig::event)
        rig.awaitRequest()
        rig.repository.save(rig.profile.copy(name = "new"))
        rig.awaitTerminal()
        assertTrue(rig.repository.get("p")!!.verifications.isEmpty())
    }

    @Test fun `unverified probe 401 records Failed and denies ordinary admission`() = Rig().use { rig ->
        rig.profile = rig.repository.save(rig.profile)
        rig.consent = RequestConsent(true, false, false, true)
        rig.server.enqueue(MockResponse().setResponseCode(401))
        ControlledProfileTester(rig.client, rig.repository, rig.clock, rig.wall).testText("p", rig::event)
        rig.awaitTerminal()
        assertEquals(listOf(ModelEnd.FAILED), rig.ends())
        assertEquals(listOf(verification(state = VerificationState.Failed, revision = rig.profile.revision,
            time = rig.wall.millis())), rig.repository.get("p")!!.verifications)
        assertProbeFailureDeniesOrdinaryRequest(rig)
    }

    @Test fun `verified probe 401 supersedes equal timestamp evidence and denies ordinary admission`() = Rig().use { rig ->
        rig.repository.verify("p", verification(revision = rig.profile.revision, time = rig.wall.millis()))
        val previous = rig.repository.get("p")!!.verifications
        rig.consent = RequestConsent(true, false, false, true)
        rig.server.enqueue(MockResponse().setResponseCode(401))
        ControlledProfileTester(rig.client, rig.repository, rig.clock, rig.wall).testText("p", rig::event)
        rig.awaitTerminal()
        assertEquals(listOf(ModelEnd.FAILED), rig.ends())
        assertEquals(previous + verification(state = VerificationState.Failed, revision = rig.profile.revision,
            time = rig.wall.millis()), rig.repository.get("p")!!.verifications)
        assertProbeFailureDeniesOrdinaryRequest(rig)
    }

    @Test fun `unsuccessful provider and incomplete EOF probes record Failed`() {
        for ((body, end) in listOf(FAILED to ModelEnd.FAILED, INCOMPLETE to ModelEnd.INCOMPLETE,
            CREATED to ModelEnd.INCOMPLETE)) Rig().use { rig ->
            rig.consent = RequestConsent(true, false, false, true)
            rig.server.enqueue(sse(body))
            ControlledProfileTester(rig.client, rig.repository, rig.clock, rig.wall).testText("p", rig::event)
            rig.awaitTerminal()
            assertEquals(listOf(end), rig.ends())
            assertEquals(verification(state = VerificationState.Failed, revision = rig.profile.revision,
                time = rig.wall.millis()), rig.repository.get("p")!!.verifications.last())
            assertProbeFailureDeniesOrdinaryRequest(rig)
        }
    }

    @Test fun `deadline probe records Failed and denies ordinary admission`() = Rig().use { rig ->
        rig.consent = RequestConsent(true, false, false, true)
        rig.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        ControlledProfileTester(rig.client, rig.repository, rig.clock, rig.wall).testText("p", rig::event)
        rig.awaitRequest()
        rig.timers.fire(rig.timers.entries.single())
        rig.awaitTerminal()
        assertEquals(listOf(ModelEnd.DEADLINE), rig.ends())
        assertTrue(rig.failed.await(5, TimeUnit.SECONDS))
        assertEquals(verification(state = VerificationState.Failed, revision = rig.profile.revision,
            time = rig.wall.millis()), rig.repository.get("p")!!.verifications.last())
        assertProbeFailureDeniesOrdinaryRequest(rig)
    }

    @Test fun `cancelled probe preserves current Verified evidence`() = Rig().use { rig ->
        val previous = rig.repository.get("p")!!.verifications
        rig.consent = RequestConsent(true, false, false, true)
        rig.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val handle = ControlledProfileTester(rig.client, rig.repository, rig.clock, rig.wall).testText("p", rig::event)
        rig.awaitRequest()
        handle.cancel(); handle.cancel()
        rig.awaitTerminal()
        assertTrue(rig.failed.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(ModelEnd.CANCELLED), rig.ends())
        assertEquals(previous, rig.repository.get("p")!!.verifications)
        assertEquals(1, rig.server.requestCount)
    }

    @Test fun `probe rejection before admission preserves evidence without network failure inference`() {
        for (reason in listOf("consent", "endpoint", "secret")) Rig().use { rig ->
            rig.consent = RequestConsent(true, false, false, reason != "consent")
            if (reason == "endpoint") {
                rig.profile = rig.repository.save(rig.profile.copy(baseUrl = "https://user:pass@example.test"))
                rig.verify()
            }
            if (reason == "secret") rig.resolver.revoke(rig.profile.secret)
            val previous = rig.repository.get("p")!!.verifications
            val tester = ControlledProfileTester(rig.client, rig.repository, rig.clock, rig.wall)
            if (reason == "consent") assertFailsWith<ContractError.CapabilityMissing> { tester.testText("p", rig::event) }
            else assertFailsWith<ContractError.ConfigurationRejected> { tester.testText("p", rig::event) }
            assertEquals(previous, rig.repository.get("p")!!.verifications)
            assertTrue(rig.events.isEmpty())
            assertEquals(0, rig.server.requestCount)
            assertEquals(if (reason == "secret") 1 else 0, rig.resolutions.get())
        }
    }

    @Test fun `probe terminal racing profile edit cannot overwrite current evidence`() {
        for (response in listOf(sse(COMPLETED), MockResponse().setResponseCode(401))) Rig().use { rig ->
            rig.consent = RequestConsent(true, false, false, true)
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val repository = object : ProfileRepository by rig.repository {
                override fun verify(id: String, result: CapabilityVerification) {
                    entered.countDown()
                    assertTrue(release.await(5, TimeUnit.SECONDS))
                    rig.repository.verify(id, result)
                }
            }
            rig.server.enqueue(response)
            try {
                ControlledProfileTester(rig.client, repository, rig.clock, rig.wall).testText("p", rig::event)
                assertTrue(entered.await(5, TimeUnit.SECONDS), "probe terminal must attempt an evidence write")
                val edited = rig.repository.save(rig.profile.copy(name = "current"))
                val current = verification(revision = edited.revision, time = rig.wall.millis() + 1)
                rig.repository.verify("p", current)
                release.countDown()
                rig.awaitTerminal()
                assertEquals(listOf(current), rig.repository.get("p")!!.verifications)
                assertEquals(edited.revision, rig.repository.get("p")!!.revision)
                assertEquals(1, rig.ends().size)
            } finally { release.countDown() }
        }
    }

    @Test fun `synchronous deadline probe writes before listener edit and never writes again`() = Rig().use { rig ->
        rig.consent = RequestConsent(true, false, false, true)
        val now = AtomicLong(0)
        val clock = object : MonotonicClock { override fun nowNanos() = now.get() }
        val scheduler = object : DeadlineScheduler {
            override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
                now.set(deadlineNanos); task()
                return object : CancellationHandle { override fun cancel() {} }
            }
        }
        ControlledModelClient(OkHttpClient(), rig.repository, rig.resolver, CapabilityGate(), ContextBudgeter(), clock,
            scheduler, { rig.consent }, rig.wall, allowLocalHttp = true).use { client ->
            var observed: CapabilityVerification? = null
            var current: CapabilityVerification? = null
            val handle = ControlledProfileTester(client, rig.repository, clock, rig.wall).testText("p") { event ->
                if (event is ModelStreamEvent.Terminal) {
                    observed = rig.repository.get("p")!!.verifications.last()
                    val edited = rig.repository.save(rig.profile.copy(name = "listener edit"))
                    current = verification(revision = edited.revision, time = rig.wall.millis() + 1)
                    rig.repository.verify("p", current!!)
                }
                rig.event(event)
            }
            handle.cancel()
            assertEquals(listOf(ModelEnd.DEADLINE), rig.ends())
            assertEquals(verification(state = VerificationState.Failed, revision = rig.profile.revision,
                time = rig.wall.millis()), observed)
            assertEquals(listOf(current), rig.repository.get("p")!!.verifications)
            assertEquals(0, rig.server.requestCount)
        }
    }

    private fun assertProbeFailureDeniesOrdinaryRequest(rig: Rig) {
        assertEquals(1, rig.server.requestCount)
        assertEquals(1, rig.resolutions.get())
        assertFailsWith<ContractError.CapabilityMissing> { rig.start() }
        assertEquals(1, rig.resolutions.get(), "denied ordinary request must not resolve another secret")
        assertEquals(1, rig.server.requestCount, "denied ordinary request must not send another POST")
    }

    @Test fun `endpoint preserves path and rejects credential carrying urls`() {
        assertEquals("http://127.0.0.1/responses", ModelEndpoint.responses("http://127.0.0.1", true).toString())
        assertEquals("https://example.test/v1/responses", ModelEndpoint.responses("https://example.test/v1/").toString())
        assertEquals("https://example.test/a/responses", ModelEndpoint.responses("https://example.test/a/responses/").toString())
        for (url in listOf("https://u:p@example.test", "https://@example.test", "https://example.test?q=x", "https://example.test#x", "ftp://example.test", "http://example.test"))
            assertFailsWith<ContractError.ConfigurationRejected> { ModelEndpoint.responses(url, true) }
        assertFailsWith<ContractError.ConfigurationRejected> { ModelEndpoint.responses("http://127.0.0.1") }
    }

    private class Rig(authenticator: Authenticator = Authenticator.NONE,
        proxyAuthenticator: Authenticator = Authenticator.NONE, useProxy: Boolean = false) : AutoCloseable {
        val server = MockWebServer().apply { start(java.net.InetAddress.getByName("127.0.0.1"), 0) }
        val failed = CountDownLatch(1)
        private val http = OkHttpClient.Builder().authenticator(authenticator).proxyAuthenticator(proxyAuthenticator).apply {
            if (useProxy) proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress("127.0.0.1", server.port)))
        }.eventListener(object : EventListener() {
            override fun callFailed(call: Call, ioe: java.io.IOException) { failed.countDown() }
        }).build()
        val resolver = MemorySecretResolver()
        val resolutions = java.util.concurrent.atomic.AtomicInteger()
        private val resolving = object : SecretResolver {
            override fun withSecret(handle: SecretHandle, block: (CharArray) -> Unit) {
                resolutions.incrementAndGet(); resolver.withSecret(handle, block)
            }
            override fun revoke(handle: SecretHandle) { resolver.revoke(handle) }
        }
        val repository = MemoryProfileRepository(resolver::revoke)
        private val time = AtomicLong(0)
        val clock = object : MonotonicClock { override fun nowNanos() = time.get() }
        val timers = Timers(time)
        val wall = Clock.fixed(Instant.parse("2026-10-07T00:00:00Z"), ZoneOffset.UTC)
        var consent = RequestConsent(true, false, false, false)
        var profile = repository.save(profileFixture().copy(baseUrl = server.url("/v1").newBuilder().host("127.0.0.1").build().toString(), secret = resolver.put("dummy-key".toCharArray())))
        val events = CopyOnWriteArrayList<ModelStreamEvent>()
        private val terminal = CountDownLatch(1)
        val client = ControlledModelClient(http, repository, resolving, CapabilityGate(), ContextBudgeter(), clock, timers,
            { consent }, wall, allowLocalHttp = true)
        init { verify() }
        fun verify() { repository.verify("p", verification(revision = profile.revision)); profile = repository.get("p")!! }
        fun request() = requestFixture().copy(profileRevision = repository.get("p")!!.revision)
        fun event(value: ModelStreamEvent) { events += value; if (value is ModelStreamEvent.Terminal) terminal.countDown() }
        fun start() = client.request(request(), ::event)
        fun awaitRequest() = assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
        fun awaitTerminal() { assertTrue(terminal.await(5, TimeUnit.SECONDS), "terminal missing") }
        fun ends() = events.filterIsInstance<ModelStreamEvent.Terminal>().map { it.end }
        override fun close() { client.close(); resolver.close(); http.dispatcher.executorService.shutdownNow(); http.connectionPool.evictAll(); server.close() }
    }

    private class Timers(private val now: AtomicLong) : DeadlineScheduler {
        data class Entry(val deadline: Long, val callback: () -> Unit, @Volatile var cancelled: Boolean = false)
        val entries = CopyOnWriteArrayList<Entry>()
        private val retry = LinkedBlockingQueue<Entry>()
        override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
            val entry = Entry(deadlineNanos, task)
            entries += entry
            if (entries.size > 1) retry.add(entry)
            return object : CancellationHandle { override fun cancel() { entry.cancelled = true } }
        }
        fun awaitRetry() = assertNotNull(retry.poll(5, TimeUnit.SECONDS))
        fun fire(entry: Entry) { now.set(entry.deadline); if (!entry.cancelled) entry.callback() }
    }

    companion object {
        fun sse(body: String) = MockResponse().addHeader("Content-Type", "text/event-stream; charset=utf-8").setBody(body)
        const val CREATED = "data: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"s\"}}\n\n"
        const val DELTA = "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":1,\"item_id\":\"m\",\"content_index\":0,\"delta\":\"hi\"}\n\n"
        const val COMPLETED = "data: {\"type\":\"response.completed\",\"sequence_number\":2,\"response\":{\"id\":\"s\",\"status\":\"completed\"}}\n\n"
        const val FAILED = "data: {\"type\":\"response.failed\",\"sequence_number\":2,\"response\":{\"id\":\"s\",\"status\":\"failed\"}}\n\n"
        const val INCOMPLETE = "data: {\"type\":\"response.incomplete\",\"sequence_number\":2,\"response\":{\"id\":\"s\",\"status\":\"incomplete\"}}\n\n"
    }
}
