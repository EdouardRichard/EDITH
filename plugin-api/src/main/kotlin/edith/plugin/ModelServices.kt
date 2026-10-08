package edith.plugin

import edith.contracts.EventEnvelope

enum class InputModality { TEXT, IMAGE, AUDIO }
enum class OutputModality { TEXT, AUDIO }
enum class VerificationState { Unverified, Verified, Failed }

data class CapabilityVerification(
    val profileRevision: Long,
    val operation: String,
    val state: VerificationState,
    val testedAtEpochMillis: Long,
) {
    init {
        require(profileRevision >= 0)
        require(operation.isNotBlank())
        require(testedAtEpochMillis >= 0)
    }
}

data class ModelProfile(
    val id: String,
    val revision: Long,
    val name: String,
    val service: String,
    val baseUrl: String,
    val protocolId: String,
    val model: String,
    val secret: SecretHandle,
    val usableWindowTokens: Int,
    val inputBudgetTokens: Int,
    val maxOutputTokens: Int,
    val safetyTokens: Int,
    val timeoutMillis: Long,
    val inputs: Set<InputModality>,
    val outputs: Set<OutputModality>,
    val verifications: List<CapabilityVerification>,
) {
    init {
        require(listOf(id, name, service, baseUrl, protocolId, model, secret.id).all { it.isNotBlank() })
        require(revision >= 0)
        require(usableWindowTokens > 0 && inputBudgetTokens > 0 && maxOutputTokens > 0 && safetyTokens > 0)
        require(inputBudgetTokens.toLong() + maxOutputTokens + safetyTokens <= usableWindowTokens.toLong())
        require(timeoutMillis > 0)
        require(inputs.isNotEmpty() && outputs.isNotEmpty())
    }
    override fun toString() = "ModelProfile(revision=$revision, protocolId=$protocolId)"
}

sealed interface InputPart {
    data class Text(val text: String) : InputPart {
        init { require(text.isNotBlank()) }
        override fun toString() = "Text(redacted)"
    }
    data class Image(val localRef: String) : InputPart {
        init { require(localRef.isNotBlank()) }
        override fun toString() = "Image(redacted)"
    }
    data class Audio(val localRef: String) : InputPart {
        init { require(localRef.isNotBlank()) }
        override fun toString() = "Audio(redacted)"
    }
}

enum class MessageRole { USER, ASSISTANT }

data class TextMessage(val role: MessageRole, val text: String) {
    init { require(text.isNotBlank()) }
    override fun toString() = "TextMessage(role=$role, redacted)"
}

data class ContextPart(val id: String, val text: String, val importance: Int) {
    init { require(id.isNotBlank()); require(text.isNotBlank()); require(importance >= 0) }
    override fun toString() = "ContextPart(redacted)"
}

data class ModelRequest(
    val envelope: EventEnvelope,
    val requestId: String,
    val turnId: String,
    val profileId: String,
    val profileRevision: Long,
    val instructions: String,
    val input: List<InputPart>,
    val history: List<TextMessage>,
    val context: List<ContextPart>,
    val output: OutputModality,
    val deadlineNanos: Long,
) {
    init {
        require(listOf(requestId, turnId, profileId, instructions).all { it.isNotBlank() })
        require(profileRevision >= 0 && deadlineNanos >= 0)
        require(input.isNotEmpty())
    }
    override fun toString() = "ModelRequest(profileRevision=$profileRevision, output=$output, redacted)"
}

enum class ModelEnd { COMPLETED, INCOMPLETE, FAILED, CANCELLED, DEADLINE }

data class ModelUsage(val inputTokens: Long?, val outputTokens: Long?) {
    init { require(inputTokens == null || inputTokens >= 0); require(outputTokens == null || outputTokens >= 0) }
}

sealed interface ModelStreamEvent {
    data class TextDelta(val requestId: String, val text: String) : ModelStreamEvent {
        init { require(requestId.isNotBlank()) }
        override fun toString() = "TextDelta(redacted)"
    }
    data class Terminal(val requestId: String, val end: ModelEnd, val usage: ModelUsage?, val safeCode: String?) : ModelStreamEvent {
        init { require(requestId.isNotBlank()) }
    }
}

interface ModelClient {
    fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle
}
