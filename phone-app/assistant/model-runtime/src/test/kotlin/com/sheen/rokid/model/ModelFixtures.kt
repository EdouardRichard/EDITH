package com.sheen.rokid.model

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*

internal fun profileFixture(
    inputs: Set<InputModality> = setOf(InputModality.TEXT),
    outputs: Set<OutputModality> = setOf(OutputModality.TEXT),
    verifications: List<CapabilityVerification> = listOf(verification()),
) = ModelProfile("p", 1, "test", "service", "https://example.test", "DEEPSEEK_RESPONSES", "model",
    object : SecretHandle { override val id = "dummy" }, 4096, 3000, 256, 128, 10_000,
    inputs, outputs, verifications)

internal fun verification(operation: String = "TEXT_REQUEST", state: VerificationState = VerificationState.Verified,
    revision: Long = 1, time: Long = 1) = CapabilityVerification(revision, operation, state, time)

internal fun requestFixture(input: List<InputPart> = listOf(InputPart.Text("synthetic question")),
    output: OutputModality = OutputModality.TEXT,
    history: List<ModelHistoryItem> = emptyList()) = ModelRequest(
    EventEnvelope(SessionId("s"), Generation(1), "e", 0, 1), "r", "t", "p", 1,
    "synthetic instructions", input, history, output, 10_000_000_000)
