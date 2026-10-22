package com.sheen.rokid.phone.ui.profile
import com.sheen.rokid.phone.ui.profile.ProfileViewModel
import com.sheen.rokid.phone.ui.profile.domain
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.phone.storage.opaqueSecret
import com.sheen.rokid.phone.storage.KeystoreVault
import com.sheen.rokid.phone.storage.EncryptedBlobStore
import com.sheen.rokid.phone.storage.ProfileBlobRecovery
import com.sheen.rokid.phone.storage.AndroidProfileRepository

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.model.ProfileTester
import com.sheen.rokid.phone.storage.*
import com.sheen.rokid.plugin.*
import org.junit.Assert.*
import org.junit.Test

class ProfileViewModelTest {
    @Test fun invalidUrlHasSafeFieldErrorAndDoesNotPublishProfile() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs))
        val vm = ProfileViewModel(repo, TestTester())
        val secret = "synthetic-dummy-secret".toCharArray()
        vm.save(profile("invalid").copy(baseUrl = "http://synthetic.example/v1"), secret)
        assertEquals("BASE_URL_INVALID", vm.state.value.error)
        assertTrue(repo.list().isEmpty()); assertTrue(secret.all { it == '\u0000' }); vm.close()
    }

    @Test fun missingNewCredentialHasSpecificErrorAndCanBeCorrectedWithoutRecreatingEditor() {
        val blobs = TestBlobs(); val vault = KeystoreVault(blobs); val repo = AndroidProfileRepository(blobs, vault)
        val vm = ProfileViewModel(repo, TestTester())
        vm.save(profile("retry"), null)
        assertEquals("API_KEY_REQUIRED", vm.state.value.error); assertTrue(repo.list().isEmpty())
        val replacement = "synthetic-retry-secret".toCharArray()
        vm.save(profile("retry"), replacement)
        assertNull(vm.state.value.error)
        vault.withSecret(repo.get("retry")!!.secret) { assertEquals("synthetic-retry-secret", String(it)) }
        assertTrue(replacement.all { it == '\u0000' }); vm.close()
    }

    @Test fun unavailableCredentialTerminatesTestWithReconfigurationState() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs))
        val tester = TestTester().apply { failure = ContractError.ConfigurationRejected("Storage unavailable; reconfigure") }
        val vm = ProfileViewModel(repo, tester)
        vm.save(profile("a"), "synthetic-dummy-secret".toCharArray()); vm.testText("a"); vm.confirmTest()
        assertNull(vm.state.value.loadingId); assertEquals("STORAGE_RECONFIGURE", vm.state.value.error); vm.close()
    }
    @Test fun committedDeleteRevokesDirectHandleEvenWhenVaultIndexWriteFails() {
        val blobs = TestBlobs(); val vault = KeystoreVault(blobs); val repo = AndroidProfileRepository(blobs, vault)
        val key = vault.putSecret("a", "synthetic-dummy-secret".toCharArray())
        val other = vault.putSecret("b", "synthetic-other-secret".toCharArray()); repo.save(profile("a").copy(secret = key))
        blobs.failId = "vault-manifest"
        assertThrows(ContractError.ConfigurationRejected::class.java) { repo.delete("a") }
        assertNull(repo.get("a"))
        assertThrows(ContractError.ConfigurationRejected::class.java) { vault.withSecret(key) { fail("deleted direct handle admitted") } }
        vault.withSecret(other) { assertEquals("synthetic-other-secret", String(it)) }
        blobs.failId = null
        assertThrows(ContractError.ConfigurationRejected::class.java) { KeystoreVault(blobs).withSecret(key) { fail("deleted direct handle restored") } }
    }
    @Test fun foreignSecretHandlesCannotDeleteNamespaceOrBecomeProfileCredentials() {
        val blobs = TestBlobs(); blobs.write("namespace-smart-assistant", byteArrayOf(1, 2, 3))
        val vault = KeystoreVault(blobs); val repo = AndroidProfileRepository(blobs, vault)
        vault.revoke(opaqueSecret("namespace-smart-assistant"))
        assertArrayEquals(byteArrayOf(1, 2, 3), blobs.read("namespace-smart-assistant"))
        assertThrows(ContractError.ConfigurationRejected::class.java) { repo.save(profile("a").copy(secret = opaqueSecret("namespace-smart-assistant"))) }
    }
    @Test fun reconstructionReclaimsUnpublishedPayloadsWithoutRemovingOtherOwners() {
        val blobs = TestBlobs(); val vault = KeystoreVault(blobs); val repo = AndroidProfileRepository(blobs, vault)
        val saved = repo.saveWithSecret(profile("a"), "synthetic-dummy-secret".toCharArray())
        val direct = vault.putSecret("other-owner", "synthetic-other-secret".toCharArray())
        val abandoned = vault.stage("synthetic-abandoned-secret".toCharArray())
        blobs.write("profile-abandoned", byteArrayOf(1)); blobs.write("namespace-smart-assistant", byteArrayOf(2)); blobs.write("speaker-other", byteArrayOf(3))
        val restoredVault = KeystoreVault(blobs); val restored = AndroidProfileRepository(blobs, restoredVault)
        assertEquals(saved.revision, restored.get("a")!!.revision)
        assertFalse(blobs.values.containsKey(abandoned.id)); assertFalse(blobs.values.containsKey("profile-abandoned"))
        restoredVault.withSecret(saved.secret) { assertEquals("synthetic-dummy-secret", String(it)) }
        restoredVault.withSecret(direct) { assertEquals("synthetic-other-secret", String(it)) }
        assertNotNull(blobs.read("namespace-smart-assistant")); assertNotNull(blobs.read("speaker-other"))
    }
    @Test fun unreadableVerificationStorageCannotLeaveTestLoading() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs)); val tester = TestTester(); val vm = ProfileViewModel(repo, tester)
        vm.save(profile("a"), "synthetic-dummy-secret".toCharArray()); val revision = repo.get("a")!!.revision
        vm.testText("a"); vm.confirmTest(); blobs.failReadId = "profiles-manifest"
        runCatching { repo.verify("a", CapabilityVerification(revision, "TEXT_REQUEST", VerificationState.Verified, 1)) }
        assertNull(vm.state.value.loadingId); assertEquals("STORAGE_RECONFIGURE", vm.state.value.error); vm.close()
    }
    @Test fun postcommitDeleteErrorStillUnselectsTheConfiguredHostConsumer() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs)); var selected: ModelProfile? = null
        val vm = ProfileViewModel(repo, TestTester(), selectionChanged = { _, value -> selected = value })
        vm.save(profile("a"), "synthetic-dummy-secret".toCharArray()); vm.select("smart-assistant", "a")
        assertNotNull(selected); repo.listenChanges { throw IllegalStateException("synthetic postcommit error") }
        vm.delete("a"); assertNull(repo.get("a")); assertNull(selected); vm.close()
    }
    @Test fun replacementRevokesOldHandleAndDeleteSurvivesReconstruction() {
        val blobs = TestBlobs(); val vault = KeystoreVault(blobs)
        val old = vault.putSecret("a", "synthetic-dummy-secret".toCharArray())
        val current = vault.putSecret("a", "synthetic-replacement".toCharArray())
        assertNotEquals(old.id, current.id)
        assertThrows(ContractError.ConfigurationRejected::class.java) { vault.withSecret(old) { fail("revoked callback admitted") } }
        vault.revoke(old)
        vault.withSecret(current) { assertEquals("synthetic-replacement", String(it)) }
        vault.deleteSecret("a")
        assertThrows(ContractError.ConfigurationRejected::class.java) { KeystoreVault(blobs).withSecret(current) { fail("deleted callback admitted") } }
    }

    @Test fun scopedSecretAndInputAreWipedEvenAfterCallbackFailure() {
        val vault = KeystoreVault(TestBlobs()); val input = "synthetic-dummy-secret".toCharArray()
        val handle = vault.putSecret("a", input)
        assertTrue(input.all { it == '\u0000' })
        var admitted: CharArray? = null
        assertThrows(IllegalArgumentException::class.java) { vault.withSecret(handle) { admitted = it; throw IllegalArgumentException() } }
        assertTrue(requireNotNull(admitted).all { it == '\u0000' })
    }

    @Test fun revisionHighWaterSurvivesDeletingAllAndReconstruction() {
        val blobs = TestBlobs(); val vault = KeystoreVault(blobs); var repo = AndroidProfileRepository(blobs, vault)
        val saved = repo.saveWithSecret(profile("a"), "synthetic-dummy-secret".toCharArray())
        repo.delete("a")
        repo = AndroidProfileRepository(blobs, KeystoreVault(blobs))
        val recreated = repo.saveWithSecret(profile("a"), "synthetic-new-secret".toCharArray())
        assertTrue(recreated.revision > saved.revision)
        repo.verify("a", CapabilityVerification(saved.revision, "TEXT_REQUEST", VerificationState.Verified, 1))
        assertTrue(requireNotNull(repo.get("a")).verifications.isEmpty())
    }

    @Test fun verificationPersistsWithoutChangingRevisionOrNotifyingRequests() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs))
        val saved = repo.saveWithSecret(profile("a"), "synthetic-dummy-secret".toCharArray())
        var changes = 0; repo.listenChanges { changes++ }
        repo.verify("a", CapabilityVerification(saved.revision, "TEXT_REQUEST", VerificationState.Verified, 7))
        val restored = requireNotNull(AndroidProfileRepository(blobs, KeystoreVault(blobs)).get("a"))
        assertEquals(saved.revision, restored.revision); assertEquals(VerificationState.Verified, restored.verifications.single().state)
        assertEquals(0, changes)
    }

    @Test fun failedManifestCommitPreservesOldCredentialAndVerification() {
        val blobs = TestBlobs(); val vault = KeystoreVault(blobs); val repo = AndroidProfileRepository(blobs, vault)
        val old = repo.saveWithSecret(profile("a"), "synthetic-dummy-secret".toCharArray())
        repo.verify("a", CapabilityVerification(old.revision, "TEXT_REQUEST", VerificationState.Verified, 7))
        blobs.failId = "profiles-manifest"
        assertThrows(ContractError.ConfigurationRejected::class.java) { repo.saveWithSecret(old.copy(model = "edited"), "synthetic-new-secret".toCharArray()) }
        vault.withSecret(old.secret) { assertEquals("synthetic-dummy-secret", String(it)) }
        assertEquals(old.revision, repo.get("a")!!.revision)
        assertEquals(VerificationState.Verified, repo.get("a")!!.verifications.single().state)
        blobs.failId = null
        assertEquals(old.revision, AndroidProfileRepository(blobs, KeystoreVault(blobs)).get("a")!!.revision)
    }

    @Test fun postcommitListenerFailureStillDeliversCancellationAndKeepsNewCredential() {
        val blobs = TestBlobs(); val vault = KeystoreVault(blobs); val repo = AndroidProfileRepository(blobs, vault)
        val old = repo.saveWithSecret(profile("a"), "synthetic-dummy-secret".toCharArray())
        val failure = IllegalStateException("synthetic listener"); var cancelled = false
        repo.listenChanges { throw failure }; repo.listenChanges { cancelled = true }
        val vm = ProfileViewModel(repo, TestTester())
        vm.save(old.copy(model = "edited"), "synthetic-new-secret".toCharArray())
        assertTrue(cancelled)
        val actual = requireNotNull(repo.get("a")); assertEquals("edited", actual.model)
        vault.withSecret(actual.secret) { assertEquals("synthetic-new-secret", String(it)) }
        assertEquals(actual.revision, vm.state.value.profiles.single().revision)
        vm.close()
    }

    @Test fun blankSecretRetainsHandleAndReplacementClearsEvidence() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs)); val vm = ProfileViewModel(repo, TestTester())
        vm.save(profile("a"), "synthetic-dummy-secret".toCharArray()); val first = requireNotNull(repo.get("a"))
        repo.verify("a", CapabilityVerification(first.revision, "TEXT_REQUEST", VerificationState.Verified, 1))
        vm.save(first.copy(baseUrl = "https://other.example/v1"), null)
        assertEquals(first.secret.id, repo.get("a")!!.secret.id); assertTrue(repo.get("a")!!.verifications.isEmpty())
        vm.save(repo.get("a")!!, "synthetic-new-secret".toCharArray())
        assertNotEquals(first.secret.id, repo.get("a")!!.secret.id); vm.close()
    }

    @Test fun modalitiesAndPluginSelectionsAreIndependentAndDurable() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs))
        val inputs = listOf(setOf(InputModality.TEXT), setOf(InputModality.TEXT, InputModality.IMAGE),
            setOf(InputModality.TEXT, InputModality.AUDIO), InputModality.entries.toSet())
        inputs.forEachIndexed { i, set -> repo.saveWithSecret(profile("p$i").copy(inputs = set,
            outputs = if (i == 3) OutputModality.entries.toSet() else setOf(OutputModality.TEXT)), "synthetic-dummy-secret".toCharArray()) }
        repo.select("smart-assistant", "p0"); repo.select("interview-assistant", "p3")
        val reconstructed = AndroidProfileRepository(blobs, KeystoreVault(blobs))
        inputs.forEachIndexed { i, expected -> assertEquals(expected, reconstructed.get("p$i")!!.inputs) }
        assertEquals(OutputModality.entries.toSet(), reconstructed.get("p3")!!.outputs)
        reconstructed.delete("p0")
        assertEquals(mapOf("interview-assistant" to "p3"), reconstructed.selections())
    }

    @Test fun textTestRequiresFreshDomainConfirmationAndCancelFencesLateTerminal() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs)); val tester = TestTester(); val vm = ProfileViewModel(repo, tester)
        vm.save(profile("a"), "synthetic-dummy-secret".toCharArray()); vm.testText("a")
        assertEquals(0, tester.calls); assertEquals("synthetic.example", vm.state.value.confirmation?.domain)
        vm.save(repo.get("a")!!.copy(model = "edited"), null); vm.confirmTest(); assertEquals(0, tester.calls)
        vm.testText("a"); vm.confirmTest(); assertEquals("a", vm.state.value.loadingId)
        vm.cancelTest(); tester.emit(ModelEnd.COMPLETED)
        assertNull(vm.state.value.loadingId); assertEquals(1, tester.cancellations); vm.close()
    }

    @Test fun synchronousTerminalCannotResurrectPendingHandle() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs)); val tester = TestTester().apply { synchronous = true }; val vm = ProfileViewModel(repo, tester)
        vm.save(profile("a"), "synthetic-dummy-secret".toCharArray()); vm.testText("a"); vm.confirmTest()
        assertEquals(1, tester.calls); assertNull(vm.state.value.loadingId); vm.cancelTest(); assertEquals(0, tester.cancellations); vm.close()
    }

    @Test fun verificationStorageFailureEndsLoadingWithoutVerifiedEvidence() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs)); val tester = TestTester(); val vm = ProfileViewModel(repo, tester)
        vm.save(profile("a"), "synthetic-dummy-secret".toCharArray()); vm.testText("a"); vm.confirmTest()
        blobs.failId = "profiles-manifest"
        runCatching { repo.verify("a", CapabilityVerification(repo.get("a")!!.revision, "TEXT_REQUEST", VerificationState.Verified, 1)) }
        assertNull(vm.state.value.loadingId); assertEquals("STORAGE_RECONFIGURE", vm.state.value.error)
        assertTrue(repo.get("a")!!.verifications.isEmpty()); vm.close()
    }

    @Test fun unsupportedProtocolOrTextDeclarationNeverCallsTester() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs)); val tester = TestTester(); val vm = ProfileViewModel(repo, tester)
        vm.save(profile("a").copy(protocolId = "CUSTOM"), "synthetic-dummy-secret".toCharArray()); vm.testText("a"); vm.confirmTest()
        assertEquals(0, tester.calls); assertEquals("CAPABILITY_MISSING", vm.state.value.error)
        vm.save(repo.get("a")!!.copy(protocolId = "DEEPSEEK_RESPONSES", inputs = setOf(InputModality.AUDIO)), null)
        vm.testText("a"); vm.confirmTest(); assertEquals(0, tester.calls); vm.close()
    }

    @Test fun deleteCancelsPendingAndRemovesSelection() {
        val blobs = TestBlobs(); val repo = AndroidProfileRepository(blobs, KeystoreVault(blobs)); val tester = TestTester(); val vm = ProfileViewModel(repo, tester)
        vm.save(profile("a"), "synthetic-dummy-secret".toCharArray()); vm.select("smart-assistant", "a")
        vm.testText("a"); vm.confirmTest(); vm.delete("a"); tester.emit(ModelEnd.COMPLETED)
        assertEquals(1, tester.cancellations); assertTrue(vm.state.value.profiles.isEmpty()); assertTrue(vm.state.value.selections.isEmpty()); vm.close()
    }
}

internal class TestBlobs : EncryptedBlobStore, ProfileBlobRecovery {
    val values = linkedMapOf<String, ByteArray>(); var failId: String? = null; var failReadId: String? = null
    override fun read(id: String): ByteArray? { if (id == failReadId) throw java.io.IOException("synthetic read failure"); return values[id]?.copyOf() }
    override fun write(id: String, value: ByteArray) { if (id == failId) throw java.io.IOException("synthetic disk failure"); values[id] = value.copyOf() }
    override fun delete(id: String) { values.remove(id)?.fill(0) }
    override fun pruneProfilePayloads(keep: Set<String>) {
        values.keys.filter { (it.startsWith("credential-") || it.startsWith("profile-")) && it !in keep }.toList().forEach(::delete)
    }
}
internal fun profile(id: String) = ModelProfile(id, 0, "Synthetic $id", "Synthetic", "https://synthetic.example/v1", "DEEPSEEK_RESPONSES", "synthetic-model",
    object : SecretHandle { override val id = "draft-placeholder" }, 4096, 2048, 1024, 128, 5000,
    setOf(InputModality.TEXT), setOf(OutputModality.TEXT), emptyList())
private class TestTester : ProfileTester {
    var failure: Exception? = null
    var calls = 0; var cancellations = 0; var synchronous = false; private var listener: ((ModelStreamEvent) -> Unit)? = null
    override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
        failure?.let { throw it }
        calls++; this.listener = listener; if (synchronous) emit(ModelEnd.COMPLETED)
        return object : CancellationHandle { override fun cancel() { cancellations++ } }
    }
    fun emit(end: ModelEnd) { listener?.invoke(ModelStreamEvent.Terminal("synthetic-test", end, null, null)) }
}
