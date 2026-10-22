package com.sheen.rokid.model

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.*
import kotlin.test.*

/**
 * 0.25 Task 14 (R77): an image that reaches a model request through **history** is gated exactly like one in the
 * current input — the profile must declare `IMAGE`, the upload consent must allow images, and the current revision must
 * carry a verified `IMAGE_REQUEST`. A text-only history is unaffected, and probe mode does not quietly widen the
 * production image path: only the explicit synthetic image probe does.
 *
 * The two history carriers are the contract's: [ModelHistoryItem.ToolOutput.images] (the live path, restored from the
 * persisted tool round by `ModelClientGateway`) and a request-flow segment's `Segment.images`, which the caller hands to
 * the gate as `segmentImages` because the budgeted request shape cannot carry a segment list.
 */
class CapabilityGateImageTest {

    private val image = ImageRef("img-1", "image/png", 8, 8, "sha-256-of-the-image")
    private val support = ProtocolSupport("DEEPSEEK_RESPONSES",
        setOf(InputModality.TEXT, InputModality.IMAGE), setOf(OutputModality.TEXT))
    private val consent = RequestConsent(text = true, image = true, audio = false, syntheticProbe = false)
    private val bothVerified = listOf(verification(), verification("IMAGE_REQUEST"))

    @Test
    fun `a tool result image in history needs the image modality consent and current verification`() {
        val profile = imageProfile()
        val history = historyWith(ModelHistoryItem.ToolOutput("call-1", "seen", listOf(image)))

        CapabilityGate().check(profile, historyOnly(history), support, consent, false)

        denied(profile.copy(verifications = listOf(verification())), historyOnly(history))
        denied(profile, historyOnly(history), consent = consent.copy(image = false))
        denied(profile.copy(inputs = setOf(InputModality.TEXT)), historyOnly(history))
        denied(profile.copy(verifications = listOf(verification("IMAGE_REQUEST", revision = 0))), historyOnly(history))
    }

    @Test
    fun `a text only history is unaffected by the image requirement`() {
        val history = historyWith(ModelHistoryItem.ToolOutput("call-1", "text result"))
        CapabilityGate().check(profileFixture(), historyOnly(history), support, consent, false)
        denied(profileFixture(verifications = emptyList()), historyOnly(history))
    }

    @Test
    fun `segment carried images are charged the same image gate`() {
        val profile = imageProfile()
        val request = requestFixture()

        denied(profile.copy(verifications = listOf(verification())), request,
            segmentImages = listOf(image))
        denied(profile, request, consent = consent.copy(image = false), segmentImages = listOf(image))
        CapabilityGate().check(profile, request, support, consent, false, segmentImages = listOf(image))
    }

    @Test
    fun `probe mode admits only an explicit synthetic image probe`() {
        val profile = imageProfile()
        val request = requestFixture(listOf(InputPart.Text("synthetic probe"), InputPart.Image("local:probe")))
        val probeConsent = consent.copy(syntheticProbe = true)

        denied(profile, request, probe = true, consent = probeConsent)
        CapabilityGate().check(profile, request, support,
            probeConsent.copy(syntheticImageProbe = true), true)
        denied(profile, request, probe = true,
            consent = probeConsent.copy(syntheticImageProbe = true, image = false))
    }

    @Test
    fun `the synthetic probe flag never relaxes the production path`() {
        val profile = imageProfile().copy(verifications = listOf(verification()))
        val request = requestFixture(listOf(InputPart.Image("local:image")))
        denied(profile, request, consent = consent.copy(syntheticProbe = true, syntheticImageProbe = true))
    }

    // ---------------------------------------------------------------- fixtures

    private fun imageProfile() = profileFixture(
        inputs = setOf(InputModality.TEXT, InputModality.IMAGE),
        verifications = bothVerified,
    )

    private fun historyWith(output: ModelHistoryItem.ToolOutput) = listOf(
        ModelHistoryItem.Text(MessageRole.USER, "synthetic question"),
        ModelHistoryItem.Text(MessageRole.ASSISTANT, "calling the tool"),
        ModelHistoryItem.ToolCall("call-1", "device.observe", "{}", "fc-1"),
        output.copy(callId = "call-1"),
    )

    private fun historyOnly(history: List<ModelHistoryItem>) = requestFixture(input = emptyList(), history = history)

    private fun denied(
        profile: ModelProfile,
        request: ModelRequest,
        consent: RequestConsent = this.consent,
        probe: Boolean = false,
        segmentImages: List<ImageRef> = emptyList(),
    ) {
        assertFailsWith<ContractError.CapabilityMissing> {
            CapabilityGate().check(profile, request, support, consent, probe, segmentImages)
        }
    }
}
