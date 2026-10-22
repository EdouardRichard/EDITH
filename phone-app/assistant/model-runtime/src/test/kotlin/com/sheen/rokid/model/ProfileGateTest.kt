package com.sheen.rokid.model

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.plugin.*
import kotlin.test.*

class ProfileGateTest {
    private val textSupport = ProtocolSupport("DEEPSEEK_RESPONSES", setOf(InputModality.TEXT), setOf(OutputModality.TEXT))
    private val consent = RequestConsent(true, true, true, false)

    @Test fun `image declaration cannot bypass text adapter`() {
        denied(profileFixture(inputs = setOf(InputModality.TEXT, InputModality.IMAGE)),
            requestFixture(listOf(InputPart.Image("local:test-image"))))
    }

    @Test fun `text profile rejects image and audio even if adapter advertises them`() {
        for (part in listOf(InputPart.Image("local:dummy"), InputPart.Audio("local:dummy"))) {
            denied(profileFixture(), requestFixture(listOf(part)),
                textSupport.copy(inputs = InputModality.entries.toSet()))
        }
    }

    @Test fun `image declaration does not grant audio input`() {
        denied(profileFixture(inputs = setOf(InputModality.TEXT, InputModality.IMAGE)),
            requestFixture(listOf(InputPart.Audio("local:dummy"))), textSupport.copy(inputs = InputModality.entries.toSet()))
    }

    @Test fun `audio input does not grant audio output`() {
        denied(profileFixture(inputs = setOf(InputModality.TEXT, InputModality.AUDIO)),
            requestFixture(output = OutputModality.AUDIO), textSupport.copy(outputs = OutputModality.entries.toSet()))
    }

    @Test fun `adapter output intersection is enforced independently`() {
        denied(profileFixture(outputs = OutputModality.entries.toSet(), verifications = listOf(verification(), verification("AUDIO_OUTPUT"))),
            requestFixture(output = OutputModality.AUDIO))
    }

    @Test fun `missing and mismatched protocols never reach effects`() {
        denied(profileFixture(), requestFixture(), null)
        denied(profileFixture(), requestFixture(), textSupport.copy(id = "unimplemented"))
    }

    @Test fun `unverified failed and stale evidence cannot authorize normal calls`() {
        for (evidence in listOf(emptyList(), listOf(verification(state = VerificationState.Unverified)),
            listOf(verification(state = VerificationState.Failed)), listOf(verification(revision = 0)),
            listOf(verification(), verification(state = VerificationState.Failed, time = 2)))) {
            denied(profileFixture(verifications = evidence), requestFixture())
        }
    }

    @Test fun `text verification never validates image audio or audio output`() {
        val profile = profileFixture(inputs = InputModality.entries.toSet(), outputs = OutputModality.entries.toSet())
        val support = textSupport.copy(inputs = profile.inputs, outputs = profile.outputs)
        denied(profile, requestFixture(listOf(InputPart.Image("local:dummy"))), support)
        denied(profile, requestFixture(listOf(InputPart.Audio("local:dummy"))), support)
        denied(profile, requestFixture(output = OutputModality.AUDIO), support)
    }

    @Test fun `text consent cannot be bypassed by verified profile or probe flag`() {
        denied(profileFixture(), requestFixture(), consent = consent.copy(text = false))
        denied(profileFixture(), requestFixture(), consent = consent.copy(text = false, syntheticProbe = true), probe = true)
    }

    @Test fun `each media input requires its own upload consent`() {
        val profile = profileFixture(inputs = InputModality.entries.toSet(), verifications = listOf(verification(),
            verification("IMAGE_REQUEST"), verification("AUDIO_REQUEST")))
        val support = textSupport.copy(inputs = profile.inputs)
        denied(profile, requestFixture(listOf(InputPart.Image("local:dummy"))), support, consent.copy(image = false))
        denied(profile, requestFixture(listOf(InputPart.Audio("local:dummy"))), support, consent.copy(audio = false))
    }

    @Test fun `probe requires explicit synthetic consent and accepts only text`() {
        val unverified = profileFixture(verifications = emptyList())
        denied(unverified, requestFixture(), probe = true)
        CapabilityGate().check(unverified, requestFixture(), textSupport, consent.copy(syntheticProbe = true), true)
        val media = unverified.copy(inputs = InputModality.entries.toSet(), outputs = OutputModality.entries.toSet())
        val support = textSupport.copy(inputs = media.inputs, outputs = media.outputs)
        denied(media, requestFixture(listOf(InputPart.Image("local:dummy"))), support, consent.copy(syntheticProbe = true), true)
        denied(media, requestFixture(output = OutputModality.AUDIO), support, consent.copy(syntheticProbe = true), true)
    }

    @Test fun `request identity and revision must match profile`() {
        for (request in listOf(requestFixture().copy(profileId = "other"), requestFixture().copy(profileRevision = 0))) {
            assertFailsWith<ContractError.ConfigurationRejected> {
                CapabilityGate().check(profileFixture(), request, textSupport, consent, false)
            }
        }
    }

    @Test fun `verified text request permits effects exactly once`() {
        val effects = BoundaryEffects()
        effects.send(profileFixture(), requestFixture(), textSupport, consent, false)
        assertEquals(listOf("secret", "send"), effects.events)
    }

    @Test fun `mixed text media and audio output require independent verified operations`() {
        val inputs = InputModality.entries.toSet()
        val outputs = OutputModality.entries.toSet()
        val evidence = listOf(verification(), verification("IMAGE_REQUEST"), verification("AUDIO_REQUEST"), verification("AUDIO_OUTPUT"))
        val profile = profileFixture(inputs, outputs, evidence)
        val support = textSupport.copy(inputs = inputs, outputs = outputs)
        val request = requestFixture(listOf(InputPart.Text("synthetic"), InputPart.Image("local:dummy"), InputPart.Audio("local:dummy")), OutputModality.AUDIO)
        val effects = BoundaryEffects()
        effects.send(profile, request, support, consent, false)
        assertEquals(listOf("secret", "localRef", "localRef", "send"), effects.events)
        denied(profile.copy(verifications = evidence.drop(1)), request, support)
        denied(profile.copy(inputs = setOf(InputModality.IMAGE, InputModality.AUDIO)), request, support)
    }

    @Test fun `audio only instructions do not imply a text input operation`() {
        val profile = profileFixture(inputs = setOf(InputModality.AUDIO), verifications = listOf(verification("AUDIO_REQUEST")))
        val support = textSupport.copy(inputs = setOf(InputModality.AUDIO))
        val request = requestFixture(listOf(InputPart.Audio("local:dummy")))
        CapabilityGate().check(profile, request, support, consent, false)
        denied(profile, request, support, consent.copy(text = false))
        for (textRequest in listOf(
            request.copy(history = listOf(ModelHistoryItem.Text(MessageRole.USER, "synthetic"), ModelHistoryItem.Text(MessageRole.ASSISTANT, "reply"))))) {
            denied(profile, textRequest, support)
            val declaredText = profile.copy(inputs = setOf(InputModality.TEXT, InputModality.AUDIO))
            val fullSupport = support.copy(inputs = declaredText.inputs)
            denied(declaredText, textRequest, support)
            denied(declaredText, textRequest, fullSupport)
            CapabilityGate().check(declaredText.copy(verifications = profile.verifications + verification()), textRequest, fullSupport, consent, false)
        }
    }

    @Test fun `a history only request is still charged as a text input operation`() {
        // The post-tool continuation carries no `input` of its own, but its transcript is text, so the gate must
        // charge it as a TEXT_REQUEST (and require that operation's verification) rather than admit it input-free.
        val request = requestFixture(
            input = emptyList(),
            history = listOf(
                ModelHistoryItem.Text(MessageRole.USER, "synthetic"),
                ModelHistoryItem.Text(MessageRole.ASSISTANT, "calling record_note"),
                ModelHistoryItem.ToolCall("call_1", "record_note", "{}", "fc_1"),
                ModelHistoryItem.ToolOutput("call_1", "noted"),
            ),
        )
        CapabilityGate().check(profileFixture(), request, textSupport, consent, false)
        denied(profileFixture(verifications = listOf(verification("AUDIO_REQUEST"))), request, textSupport)
    }

    @Test fun `latest timestamp and last tie determine verification state`() {
        denied(profileFixture(verifications = listOf(verification(state = VerificationState.Failed, time = 3), verification(time = 2))), requestFixture())
        denied(profileFixture(verifications = listOf(verification(time = 3), verification(state = VerificationState.Unverified, time = 3))), requestFixture())
    }

    @Test fun `repository edits revoke gate admission until current revision is verified`() {
        val repo = com.sheen.rokid.storage.MemoryProfileRepository()
        val first = repo.save(profileFixture())
        denied(first, requestFixture().copy(profileRevision = first.revision))
        repo.verify(first.id, verification(revision = first.revision))
        CapabilityGate().check(repo.get(first.id)!!, requestFixture().copy(profileRevision = first.revision), textSupport, consent, false)
        val next = repo.save(first.copy(name = "renamed"))
        repo.verify(next.id, verification(revision = first.revision))
        denied(repo.get(next.id)!!, requestFixture().copy(profileRevision = next.revision))
    }

    private fun denied(profile: ModelProfile, request: ModelRequest, support: ProtocolSupport? = textSupport,
        consent: RequestConsent = this.consent, probe: Boolean = false) {
        val effects = BoundaryEffects()
        assertFailsWith<ContractError.CapabilityMissing> { effects.send(profile, request, support, consent, probe) }
        assertEquals(emptyList(), effects.events)
    }

    // Only the external side effects are fake; admission uses the real gate.
    private class BoundaryEffects {
        val events = mutableListOf<String>()
        fun send(profile: ModelProfile, request: ModelRequest, support: ProtocolSupport?, consent: RequestConsent, probe: Boolean) {
            CapabilityGate().check(profile, request, support, consent, probe)
            events += "secret"
            request.input.filter { it !is InputPart.Text }.forEach { events += "localRef" }
            events += "send"
        }
    }
}
