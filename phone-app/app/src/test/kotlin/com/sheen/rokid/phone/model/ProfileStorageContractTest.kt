package com.sheen.rokid.phone.model
import com.sheen.rokid.phone.ui.profile.profile
import com.sheen.rokid.phone.ui.profile.TestBlobs
import com.sheen.rokid.phone.ui.profile.ProbeConsent
import com.sheen.rokid.phone.storage.KeystoreVault
import com.sheen.rokid.phone.storage.AndroidProfileRepository

import com.sheen.rokid.contracts.*
import com.sheen.rokid.model.*
import com.sheen.rokid.phone.storage.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.storage.SecretResolver
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ProfileStorageContractTest {
    @Test fun profileChangingBetweenProbeConfirmationAndClientAdmissionCannotUpload() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs))
        val original = repo.saveWithSecret(profile("a"), "synthetic-dummy-secret".toCharArray()); val gate = ProfileProbeGate(repo)
        gate.grant(ProbeConsent(original.id, original.revision, "synthetic.example"))
        val tester = object : ProfileTester {
            override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
                repo.save(original.copy(baseUrl = "https://changed.example/v1"))
                assertFalse(gate.requestConsent().text); assertFalse(gate.requestConsent().syntheticProbe)
                return object : CancellationHandle { override fun cancel() = Unit }
            }
        }
        gate.test(tester, original.id) {}; assertFalse(gate.requestConsent().text)
    }
    @Test fun directVaultHandleSavesAndRepositoryCopiesCallerCollections() {
        val blobs = TestBlobs(); val vault = KeystoreVault(blobs); val repo = AndroidProfileRepository(blobs, vault)
        val inputs = mutableSetOf(InputModality.TEXT); val outputs = mutableSetOf(OutputModality.TEXT)
        val key = vault.putSecret("a", "synthetic-dummy-secret".toCharArray())
        val saved = repo.save(profile("a").copy(secret = key, inputs = inputs, outputs = outputs))
        inputs += InputModality.IMAGE; outputs += OutputModality.AUDIO
        assertEquals(setOf(InputModality.TEXT), saved.inputs); assertEquals(setOf(OutputModality.TEXT), repo.get("a")!!.outputs)
        vault.withSecret(saved.secret) { assertEquals("synthetic-dummy-secret", String(it)) }
    }
    @Test fun concurrentVerificationCannotSurviveEditOrRecreateDeletedProfile() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs))
            val old = repo.saveWithSecret(profile("a"), "synthetic-dummy-secret".toCharArray())
            val begin = CountDownLatch(1)
            val verification = executor.submit { begin.await(); repo.verify("a", CapabilityVerification(old.revision, "TEXT_REQUEST", VerificationState.Verified, 1)) }
            val edit = executor.submit { begin.await(); repo.save(old.copy(model = "edited")) }
            begin.countDown(); verification.get(3, TimeUnit.SECONDS); edit.get(3, TimeUnit.SECONDS)
            assertEquals("edited", repo.get("a")!!.model); assertTrue(repo.get("a")!!.verifications.isEmpty())
            val current = repo.get("a")!!; val again = CountDownLatch(1)
            val late = executor.submit { again.await(); repo.verify("a", CapabilityVerification(current.revision, "TEXT_REQUEST", VerificationState.Verified, 2)) }
            val deletion = executor.submit { again.await(); repo.delete("a") }
            again.countDown(); late.get(3, TimeUnit.SECONDS); deletion.get(3, TimeUnit.SECONDS); assertNull(repo.get("a"))
        } finally { executor.shutdownNow() }
    }
    @Test fun cancelledAndThrowingListenersDoNotSuppressOtherRequestCancellation() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs))
        val saved = repo.saveWithSecret(profile("a"), "synthetic-dummy-secret".toCharArray())
        val failure = IllegalStateException("synthetic listener"); var delivered = 0
        val cancelled = repo.listenChanges { fail("cancelled listener admitted") }; cancelled.cancel()
        repo.listenChanges { throw failure }; repo.listenChanges { throw failure }; repo.listenChanges { delivered++ }
        assertSame(failure, assertThrows(IllegalStateException::class.java) { repo.save(saved.copy(model = "edited")) })
        assertEquals(1, delivered); assertEquals("edited", repo.get("a")!!.model)
    }
    @Test fun unsupportedSavedProtocolFailsBothProbeAndBusinessBeforeSecretOrTransport() {
        val blobs = TestBlobs(); val vault = KeystoreVault(blobs); val repo = AndroidProfileRepository(blobs, vault)
        val saved = repo.saveWithSecret(profile("a").copy(protocolId = "CUSTOM"), "synthetic-dummy-secret".toCharArray())
        var resolutions = 0; var sends = 0
        val resolver = object : SecretResolver {
            override fun withSecret(handle: SecretHandle, block: (CharArray) -> Unit) { resolutions++; fail("unsupported credential admitted") }
            override fun revoke(handle: SecretHandle) = Unit
        }
        val http = OkHttpClient.Builder().addInterceptor { sends++; throw AssertionError("unsupported request sent") }.build()
        val clock = FakeClock(); val client = ControlledModelClient(http, repo, resolver, CapabilityGate(), ContextBudgeter(), clock,
            ManualScheduler(clock), { RequestConsent(true, true, true, true) })
        try {
            val request = ModelRequest(EventEnvelope(SessionId("synthetic"), Generation(1), "synthetic-event", 0, 0), "synthetic-request", "synthetic-turn", saved.id, saved.revision,
                "synthetic instructions", listOf(InputPart.Text("synthetic text")), emptyList(), OutputModality.TEXT, Long.MAX_VALUE)
            assertThrows(ContractError.CapabilityMissing::class.java) { client.request(request) {} }
            assertThrows(ContractError.CapabilityMissing::class.java) { ControlledProfileTester(client, repo, clock).testText(saved.id) {} }
            assertEquals(0, resolutions); assertEquals(0, sends)
        } finally { client.close(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll() }
    }
}
