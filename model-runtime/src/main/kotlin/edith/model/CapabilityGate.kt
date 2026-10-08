package edith.model

import edith.plugin.*
import edith.contracts.ContractError

data class ProtocolSupport(val id: String, val inputs: Set<InputModality>, val outputs: Set<OutputModality>)
data class RequestConsent(val text: Boolean, val image: Boolean, val audio: Boolean, val syntheticProbe: Boolean)

class CapabilityGate {
    fun check(profile: ModelProfile, request: ModelRequest, support: ProtocolSupport?, consent: RequestConsent, probe: Boolean) {
        if (request.profileId != profile.id || request.profileRevision != profile.revision) {
            throw ContractError.ConfigurationRejected("Profile revision mismatch")
        }
        fun missing(): Nothing = throw ContractError.CapabilityMissing("Request capability unavailable")
        if (support == null || support.id != profile.protocolId) missing()
        val inputs = request.input.map {
            when (it) {
                is InputPart.Text -> InputModality.TEXT
                is InputPart.Image -> InputModality.IMAGE
                is InputPart.Audio -> InputModality.AUDIO
            }
        }.toSet() + if (request.history.isNotEmpty() || request.context.isNotEmpty()) setOf(InputModality.TEXT) else emptySet()
        if (!profile.inputs.containsAll(inputs) || !support.inputs.containsAll(inputs) ||
            request.output !in profile.outputs || request.output !in support.outputs) missing()
        if (!consent.text || (InputModality.IMAGE in inputs && !consent.image) ||
            (InputModality.AUDIO in inputs && !consent.audio)) missing()
        if (probe) {
            if (!consent.syntheticProbe || inputs != setOf(InputModality.TEXT) || request.output != OutputModality.TEXT) missing()
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
