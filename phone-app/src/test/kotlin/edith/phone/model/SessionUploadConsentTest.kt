package edith.phone.model

import edith.contracts.*
import edith.host.HostRuntime
import edith.model.*
import edith.phone.HostCompositionRoot
import edith.phone.storage.*
import edith.plugin.*
import edith.storage.MemoryProfileRepository
import edith.testsupport.FakeClock
import edith.testsupport.ManualScheduler
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * D13: the text-upload consent gate has no production grant path.
 *
 * `ControlledModelClient` is handed exactly one consent source, and the probe gate only answers for a *probe*
 * request, so a real turn hits `CapabilityGate` (`!consent.text`) and is refused before any HTTP. These tests pin the
 * session-scoped toggle that closes that hole while keeping the default **exactly** today's refusal:
 *
 * 1. the type itself - off by default, off again after the session ends, and never a probe consent;
 * 2. the refusal a real turn gets, through a client wired exactly as `HostCompositionRoot.create` wires it: with the
 *    toggle off it is the same `CapabilityMissing` and nothing reaches the transport; with it on the turn may proceed;
 * 3. the session scope - stopping the session returns the toggle to off.
 *
 * No Compose is exercised here: the decision logic lives in [SessionUploadConsent], and the switch is a thin writer.
 */
class SessionUploadConsentTest {
    private val probeDenied = RequestConsent(text = false, image = false, audio = false, syntheticProbe = false)
    private val probeAdmitted = RequestConsent(text = true, image = false, audio = false, syntheticProbe = true)

    @Test fun `the toggle starts off and leaves the probe gate's own answer untouched`() {
        val consent = SessionUploadConsent()

        assertFalse("off is the default", consent.enabled.value)
        assertEquals("with the toggle off the answer must be exactly the probe gate's", probeDenied,
            consent.requestConsent(probeDenied))
        assertFalse(consent.requestConsent(probeDenied).text)
        assertEquals("an admitted probe stays exactly what the probe gate said", probeAdmitted,
            consent.requestConsent(probeAdmitted))
    }

    @Test fun `granting the toggle allows text for the session without granting the probe path`() {
        val consent = SessionUploadConsent()
        consent.set(true)

        assertTrue("a real turn may leave the device while the session toggle is on",
            consent.requestConsent(probeDenied).text)
        val merged = consent.requestConsent(probeDenied)
        assertFalse("the session toggle is not a probe consent", merged.syntheticProbe)
        assertFalse(merged.image)
        assertFalse(merged.audio)
    }

    @Test fun `deactivating returns the toggle to off and the refusal`() {
        val consent = SessionUploadConsent()
        consent.set(true)
        assertTrue(consent.requestConsent(probeDenied).text)

        consent.deactivate()

        assertFalse(consent.enabled.value)
        assertFalse(consent.requestConsent(probeDenied).text)
    }

    @Test fun `with the toggle off a real turn is refused with today's capability code and nothing is sent`() {
        Rig().use { rig ->
            val thrown = assertThrows(ContractError.CapabilityMissing::class.java) {
                rig.client.request(rig.request()) {}
            }

            assertEquals("the refusal must be the one measured on the device today",
                "Request capability unavailable", thrown.message)
            assertEquals("nothing may reach the transport", 0, rig.sendCount())
        }
    }

    @Test fun `with the toggle on the same turn may proceed to the transport`() {
        Rig().use { rig ->
            rig.consent.set(true)

            rig.client.request(rig.request()) {}

            assertTrue("an allowed turn must actually reach the transport", rig.awaitTransport())
            assertEquals(1, rig.sendCount())
        }
    }

    @Test fun `stopping the session returns the toggle to off`() {
        val consent = SessionUploadConsent()
        val root = HostCompositionRoot(HostRuntime(1), MemoryProfileRepository(), UnusedModel(), null,
            sessionUploadConsent = consent)
        try {
            consent.set(true)
            assertTrue(consent.requestConsent(probeDenied).text)

            root.stop()

            assertFalse("the grant is scoped to one session", consent.enabled.value)
            assertFalse(consent.requestConsent(probeDenied).text)
        } finally { root.close() }
    }

    /** A client wired exactly as `create` wires the production one, against a transport double that counts calls. */
    private class Rig : AutoCloseable {
        val clock = FakeClock()
        val consent = SessionUploadConsent()
        private val vault: KeystoreVault
        private val repo: AndroidProfileRepository
        private val saved: ModelProfile
        private val probeGate: ProfileProbeGate
        private val sends = AtomicInteger()
        private val reached = CountDownLatch(1)
        private val http: OkHttpClient
        val client: ControlledModelClient

        init {
            val blobs = TestBlobs()
            vault = KeystoreVault(blobs)
            repo = AndroidProfileRepository(blobs, vault)
            saved = repo.saveWithSecret(profile("a"), "synthetic-dummy-secret".toCharArray())
            // Verified so that the upload consent is the only thing the gate can refuse this turn for.
            repo.verify("a", CapabilityVerification(saved.revision, "TEXT_REQUEST", VerificationState.Verified, 1))
            probeGate = ProfileProbeGate(repo)
            http = OkHttpClient.Builder().addInterceptor { chain ->
                sends.incrementAndGet()
                reached.countDown()
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(401).message("synthetic").body("".toResponseBody(null)).build()
            }.build()
            client = ControlledModelClient(http, repo, vault, CapabilityGate(), ContextBudgeter(), clock,
                ManualScheduler(clock), HostCompositionRoot.consentSource(consent, probeGate::requestConsent))
        }

        fun request(): ModelRequest = ModelRequest(
            EventEnvelope(SessionId("synthetic-session"), Generation(1), "synthetic-event", 0, 0),
            "synthetic-request", "synthetic-turn", saved.id, saved.revision, "synthetic instructions",
            listOf(InputPart.Text("synthetic text")), emptyList(), emptyList(), OutputModality.TEXT, Long.MAX_VALUE)

        fun sendCount(): Int = sends.get()
        fun awaitTransport(): Boolean = reached.await(5, TimeUnit.SECONDS)
        override fun close() {
            client.close()
            http.dispatcher.executorService.shutdown()
            http.connectionPool.evictAll()
        }
    }

    private class UnusedModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            error("not used")
    }
}
