package com.sheen.rokid.model

import com.sheen.rokid.plugin.*
import com.sheen.rokid.contracts.ContractError

data class ProtocolSupport(val id: String, val inputs: Set<InputModality>, val outputs: Set<OutputModality>)

/**
 * What one model request may upload, already resolved by its owner.
 *
 * [syntheticProbe] admits the host's fixed synthetic probe request (a text probe on a profile whose own operations are
 * not verified yet). [syntheticImageProbe] is the **separate** exception for the synthetic image probe (0.25 R76/R77):
 * probe mode must not widen the production IMAGE path, so only an explicit image-probe admission sets it. Both default
 * to `false`, keeping every pre-0.25 construction unchanged.
 */
data class RequestConsent(
    val text: Boolean,
    val image: Boolean,
    val audio: Boolean,
    val syntheticProbe: Boolean,
    val syntheticImageProbe: Boolean = false,
)

class CapabilityGate {
    /**
     * Admits one model request, or throws [ContractError.CapabilityMissing].
     *
     * [segmentImages] is the images a non-tool request-flow segment carries ([com.sheen.rokid.agent.Segment.images]).
     * The budgeted [ModelRequest] shape has no segment list, so a caller that renders such segments hands them over
     * here; they are charged exactly like a history `ToolOutput.images` (0.25 R77). Production currently transports
     * tool images as `ModelHistoryItem.ToolOutput.images`, so this list is empty on the live path.
     */
    fun check(
        profile: ModelProfile,
        request: ModelRequest,
        support: ProtocolSupport?,
        consent: RequestConsent,
        probe: Boolean,
        segmentImages: List<ImageRef> = emptyList(),
    ) {
        if (request.profileId != profile.id || request.profileRevision != profile.revision) {
            throw ContractError.ConfigurationRejected("Profile revision mismatch")
        }
        fun missing(): Nothing = throw ContractError.CapabilityMissing("Request capability unavailable")
        if (support == null || support.id != profile.protocolId) missing()
        val inputs = buildSet {
            request.input.forEach {
                add(
                    when (it) {
                        is InputPart.Text -> InputModality.TEXT
                        is InputPart.Image -> InputModality.IMAGE
                        is InputPart.Audio -> InputModality.AUDIO
                    },
                )
            }
            // A non-empty transcript is a text request even when it carries no current input: the stateless post-tool
            // continuation sends the whole conversation as history.
            if (request.history.isNotEmpty()) add(InputModality.TEXT)
            // 0.25 R77: a tool result's images are images on the wire exactly like an `InputPart.Image`, whether the
            // artifact still resolves or the adapter degrades it to a visible placeholder at encode time.
            request.history.forEach { item ->
                if (item is ModelHistoryItem.ToolOutput && item.images.isNotEmpty()) add(InputModality.IMAGE)
            }
            if (segmentImages.isNotEmpty()) add(InputModality.IMAGE)
        }
        if (!profile.inputs.containsAll(inputs) || !support.inputs.containsAll(inputs) ||
            request.output !in profile.outputs || request.output !in support.outputs) missing()
        if (!consent.text || (InputModality.IMAGE in inputs && !consent.image) ||
            (InputModality.AUDIO in inputs && !consent.audio)) missing()
        if (probe) {
            // P5 Task 12: probe mode allows AUDIO input when syntheticProbe consent is given, so the AUDIO_REQUEST
            // verification can use a fixed synthetic WAV without requiring a prior Verified record (chicken-and-egg).
            // Task 14 R77: IMAGE is widened only by the explicit synthetic image-probe admission, never by probe mode
            // itself and never by the text probe's own flag; the production path below is unchanged.
            val allowedInputs = buildSet {
                add(InputModality.TEXT)
                if (consent.audio) add(InputModality.AUDIO)
                if (consent.syntheticImageProbe) add(InputModality.IMAGE)
            }
            if (!consent.syntheticProbe || !inputs.all { it in allowedInputs } || request.output != OutputModality.TEXT) missing()
            return
        }
        val operations = inputs.map {
            when (it) {
                InputModality.TEXT -> "TEXT_REQUEST"
                InputModality.IMAGE -> "IMAGE_REQUEST"
                InputModality.AUDIO -> "AUDIO_REQUEST"
            }
        } + if (request.output == OutputModality.AUDIO) listOf("AUDIO_OUTPUT") else emptyList()
        for (operation in operations) {
            val latest = profile.verifications.withIndex()
                .filter { it.value.profileRevision == profile.revision && it.value.operation == operation }
                .maxWithOrNull(compareBy<IndexedValue<CapabilityVerification>> { it.value.testedAtEpochMillis }.thenBy { it.index })
                ?.value
            if (latest?.state != VerificationState.Verified) missing()
        }
    }
}
