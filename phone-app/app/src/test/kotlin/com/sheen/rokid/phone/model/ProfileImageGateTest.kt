package com.sheen.rokid.phone.model

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.model.CapabilityGate
import com.sheen.rokid.model.ControlledProfileTester
import com.sheen.rokid.model.ProtocolSupport
import com.sheen.rokid.model.ProfileProbeTransport
import com.sheen.rokid.model.ProfileTester
import com.sheen.rokid.model.RequestConsent
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.storage.AndroidProfileRepository
import com.sheen.rokid.phone.storage.KeystoreVault
import com.sheen.rokid.phone.ui.profile.ProbeConsent
import com.sheen.rokid.phone.ui.profile.ProfileViewModel
import com.sheen.rokid.phone.ui.profile.TestBlobs
import com.sheen.rokid.phone.ui.profile.profile as profileFixture
import com.sheen.rokid.plugin.*
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.testsupport.FakeClock
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock

/**
 * 0.25 Task 14 (R74–R76): an assistant session runs only on an image-capable profile, image upload consent comes from
 * that profile (never an unconditional host OR), and the synthetic image probe records `IMAGE_REQUEST` evidence for the
 * revision it actually tested.
 *
 * The probes here drive the production `ControlledProfileTester`/`ProfileProbeGate`/`CapabilityGate` types. Only the
 * transport is fake, so a test can decide the terminal without a network; the request shape, the gate and the recorded
 * evidence are the production ones.
 */
class ProfileImageGateTest {

    private val imageRef = ImageRef("probe-image", "image/png", 32, 32, "synthetic-sha256")

    // ---------------------------------------------------------------- R74: startup gate

    @Test
    fun aPlainTextProfileIsRefusedAtSessionStartAndThePromptSaysToChangeIt() {
        assertEquals("PROFILE_MODEL_NOT_MULTIMODAL", profileStartupError(profileFixture("text")))
        assertNull("an image-capable profile starts", profileStartupError(imageProfile()))
        assertNull("an absent profile is a different failure", profileStartupError(null))

        val message = profileFormErrorMessage("PROFILE_MODEL_NOT_MULTIMODAL")
        assertTrue("the prompt must name images: $message", message.contains("图片"))
        assertTrue("the prompt must tell the user to change the profile: $message", message.contains("更换"))
    }

    // ---------------------------------------------------------------- R77: gate over the current input

    @Test
    fun anImageRequestNeedsTheDeclaredModalityConsentAndCurrentRevisionVerification() {
        val repository = MemoryProfileRepository()
        val saved = repository.save(imageProfile())
        val request = request(saved, listOf(InputPart.Image(imageRef.artifactId)))
        val support = imageSupport()
        val consent = RequestConsent(text = true, image = true, audio = false, syntheticProbe = false)

        assertThrows(ContractError.CapabilityMissing::class.java) {
            CapabilityGate().check(saved, request, support, consent, false)
        }

        repository.verify(saved.id, CapabilityVerification(saved.revision, "IMAGE_REQUEST", VerificationState.Verified, 1))
        val verified = repository.get(saved.id)!!
        CapabilityGate().check(verified, request, support, consent, false)

        assertThrows(ContractError.CapabilityMissing::class.java) {
            CapabilityGate().check(verified, request, support, consent.copy(image = false), false)
        }
        assertThrows(ContractError.CapabilityMissing::class.java) {
            CapabilityGate().check(verified.copy(inputs = setOf(InputModality.TEXT)), request, support, consent, false)
        }
    }

    // ---------------------------------------------------------------- R76: synthetic image probe

    @Test
    fun testImageRecordsVerifiedForTheCurrentRevisionAndUnlocksTheGate() {
        val repository = MemoryProfileRepository()
        val saved = repository.save(imageProfile())
        val transport = FakeProbeTransport(ModelEnd.COMPLETED)

        ControlledProfileTester(transport, repository, FakeClock(), Clock.systemUTC(), { imageRef.artifactId })
            .testImage(saved.id) {}

        val evidence = repository.get(saved.id)!!.verifications.single()
        assertEquals("IMAGE_REQUEST", evidence.operation)
        assertEquals(VerificationState.Verified, evidence.state)
        assertEquals(saved.revision, evidence.profileRevision)
        assertEquals(listOf("probe-image"), transport.requests.single().input
            .filterIsInstance<InputPart.Image>().map { it.localRef })

        CapabilityGate().check(repository.get(saved.id)!!, request(repository.get(saved.id)!!,
            listOf(InputPart.Image(imageRef.artifactId))), imageSupport(),
            RequestConsent(true, true, false, false), false)
    }

    @Test
    fun failedIncompleteAndDeadlineImageProbesRecordFailedAndCancelRecordsNothing() {
        for ((end, expected) in listOf(
            ModelEnd.FAILED to VerificationState.Failed,
            ModelEnd.INCOMPLETE to VerificationState.Failed,
            ModelEnd.DEADLINE to VerificationState.Failed,
            ModelEnd.CANCELLED to null,
        )) {
            val repository = MemoryProfileRepository()
            val saved = repository.save(imageProfile())
            ControlledProfileTester(FakeProbeTransport(end), repository, FakeClock(), Clock.systemUTC(),
                { imageRef.artifactId }).testImage(saved.id) {}
            val evidence = repository.get(saved.id)!!.verifications
            if (expected == null) assertTrue("cancelled must leave no evidence: $evidence", evidence.isEmpty())
            else assertEquals("terminal=$end", expected, evidence.single().state)
        }
    }

    @Test
    fun imageProbeWithoutAnImageReferenceRefusesBeforeTransport() {
        val repository = MemoryProfileRepository()
        val saved = repository.save(imageProfile())
        val transport = FakeProbeTransport(ModelEnd.COMPLETED)

        val failure = assertThrows(ContractError.CapabilityMissing::class.java) {
            ControlledProfileTester(transport, repository, FakeClock(), Clock.systemUTC()).testImage(saved.id) {}
        }
        assertTrue("the transport must never see a bodiless probe: $failure",
            transport.requests.isEmpty())
        assertTrue(repository.get(saved.id)!!.verifications.isEmpty())
    }

    // ---------------------------------------------------------------- R75: consent comes from the profile

    @Test
    fun requestConsentGrantsImagesFromTheProfileAndNeverFromTheHostAlone() {
        val repository = MemoryProfileRepository()
        val textOnly = repository.save(profileFixture("text"))
        val image = repository.save(imageProfile())
        val gate = ProfileProbeGate(repository)

        assertFalse("no probe admission means no image consent", gate.requestConsent().image)

        // A text probe on a text profile: image stays ungranted, only the synthetic probe flag is admitted.
        gate.grant(ProbeConsent(textOnly.id, textOnly.revision, "synthetic.example"))
        gate.test(object : ProfileTester {
            override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
                val consent = gate.requestConsent()
                assertTrue(consent.text)
                assertTrue(consent.syntheticProbe)
                assertFalse("a text-only profile never grants images", consent.image)
                assertFalse(consent.syntheticImageProbe)
                return NoCancellation
            }
        }, textOnly.id) {}

        // An image-capable profile grants the image upload, but only an explicit image probe widens probe mode.
        gate.grant(ProbeConsent(image.id, image.revision, "synthetic.example"))
        gate.test(object : ProfileTester {
            override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
                val consent = gate.requestConsent()
                assertTrue(consent.image)
                assertFalse("a text probe must not admit an image request", consent.syntheticImageProbe)
                return NoCancellation
            }
        }, image.id) {}

        gate.grant(ProbeConsent(image.id, image.revision, "synthetic.example", image = true))
        gate.testImage(object : ProfileTester {
            override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
                throw AssertionError("the image probe must call testImage")
            override fun testImage(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
                val consent = gate.requestConsent()
                assertTrue(consent.image)
                assertTrue(consent.syntheticProbe)
                assertTrue("the image probe is the one explicit synthetic-image path", consent.syntheticImageProbe)
                return NoCancellation
            }
        }, image.id) {}

        val source = HostCompositionRoot.consentSource { RequestConsent(false, false, false, false) }
        assertTrue("the running session still allows text", source(textOnly).text)
        assertFalse("and never imagines image consent for a text profile", source(textOnly).image)
        assertTrue("an image-capable profile is what grants image upload", source(image).image)
        assertFalse("audio is not granted by this version", source(image).audio)
    }

    // ---------------------------------------------------------------- R76: UI trigger

    @Test
    fun profileViewModelTriggersTheImageProbeOnlyForImageCapableProfiles() {
        val blobs = TestBlobs()
        val repository = AndroidProfileRepository(blobs, KeystoreVault(blobs))
        val tester = RecordingImageTester()
        val viewModel = ProfileViewModel(repository, tester)
        try {
            val image = repository.saveWithSecret(imageProfile("image"), "synthetic-dummy-secret".toCharArray())
            viewModel.testImage(image.id)
            assertEquals(image.id, viewModel.state.value.confirmation?.id)
            assertTrue("the confirmation must name an image probe", viewModel.state.value.confirmation?.image == true)
            viewModel.confirmTest()
            assertEquals(1, tester.imageCalls)
            assertEquals(0, tester.textCalls)
            assertNull(viewModel.state.value.loadingId)

            val textOnly = repository.saveWithSecret(profileFixture("text"), "synthetic-dummy-secret".toCharArray())
            viewModel.testImage(textOnly.id)
            assertEquals("CAPABILITY_MISSING", viewModel.state.value.error)
            assertEquals("a refused probe never reaches the transport", 1, tester.imageCalls)
        } finally {
            viewModel.close()
        }
    }

    // ---------------------------------------------------------------- fixtures

    private fun imageProfile(id: String = "image") =
        profileFixture(id).copy(inputs = setOf(InputModality.TEXT, InputModality.IMAGE))

    private fun imageSupport() =
        ProtocolSupport("DEEPSEEK_RESPONSES", setOf(InputModality.TEXT, InputModality.IMAGE),
            setOf(OutputModality.TEXT))

    private fun request(profile: ModelProfile, input: List<InputPart>) = ModelRequest(
        EventEnvelope(SessionId("synthetic-session"), Generation(1), "synthetic-event", 0, 0),
        "synthetic-request", "synthetic-turn", profile.id, profile.revision, "synthetic instructions",
        input, emptyList(), OutputModality.TEXT, 10_000_000_000L,
    )

    private class FakeProbeTransport(private val end: ModelEnd) : ProfileProbeTransport {
        val requests = mutableListOf<ModelRequest>()
        override fun probe(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
            requests += request
            listener(ModelStreamEvent.Terminal(request.requestId, end, ModelUsage(7, 3), null))
            return NoCancellation
        }
    }

    private class RecordingImageTester : ProfileTester {
        var imageCalls = 0
        var textCalls = 0
        override fun testText(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
            textCalls++
            return NoCancellation
        }
        override fun testImage(profileId: String, listener: (ModelStreamEvent) -> Unit): CancellationHandle {
            imageCalls++
            listener(ModelStreamEvent.Terminal("synthetic-image-probe", ModelEnd.COMPLETED, null, null))
            return NoCancellation
        }
    }

    private companion object {
        val NoCancellation = object : CancellationHandle {
            override fun cancel() = Unit
        }
    }
}
