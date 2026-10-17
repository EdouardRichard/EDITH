package com.sheen.rokid.plugin

import com.sheen.rokid.contracts.EventEnvelope

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

/**
 * A tool the model may call. [parametersJson] is the JSON-Schema document for the call arguments; it is kept as text
 * because this contract module parses nothing, and is validated when it is encoded.
 */
data class ToolSpec(val name: String, val description: String, val parametersJson: String) {
    init { require(name.isNotBlank() && description.isNotBlank() && parametersJson.isNotBlank()) }
    override fun toString() = "ToolSpec(name=$name, redacted)"
}

/**
 * One entry of a stateless request history. [Text] is what the P2 history always was; [ToolCall] and [ToolOutput] are
 * the extension that makes a tool exchange round-trip: the endpoint is stateless, so the call the model produced must
 * be echoed back beside its result.
 */
sealed interface ModelHistoryItem {
    data class Text(val role: MessageRole, val text: String) : ModelHistoryItem {
        init { require(text.isNotBlank()) }
        override fun toString() = "Text(role=$role, redacted)"
    }
    data class ToolCall(val callId: String, val name: String, val argsJson: String,
        // The proven echo carries the raw item id as well. A caller that does not know it (a hand-built history) gets
        // the call id, which is stable and deterministic; the decoder always passes the real item id through.
        val itemId: String = callId) : ModelHistoryItem {
        init {
            require(callId.isNotBlank() && name.isNotBlank() && itemId.isNotBlank())
        }
        override fun toString() = "ToolCall(callId=$callId, redacted)"
    }
    data class ToolOutput(val callId: String, val output: String) : ModelHistoryItem {
        init { require(callId.isNotBlank()) }
        override fun toString() = "ToolOutput(callId=$callId, redacted)"
    }
}

data class ModelRequest(
    val envelope: EventEnvelope,
    val requestId: String,
    val turnId: String,
    val profileId: String,
    val profileRevision: Long,
    val instructions: String,
    val input: List<InputPart>,
    val history: List<ModelHistoryItem>,
    val output: OutputModality,
    val deadlineNanos: Long,
    /** Empty means a text-only request: the P2 body shape is preserved byte for byte. */
    val tools: List<ToolSpec> = emptyList(),
) {
    init {
        require(listOf(requestId, turnId, profileId, instructions).all { it.isNotBlank() })
        require(profileRevision >= 0 && deadlineNanos >= 0)
        // A request must carry something. `input` is the in-flight user turn and `history` the transcript that precedes
        // it; after a tool round the transcript itself is the whole request, so a history-only request (empty `input`,
        // non-empty `history`) is legal. Both empty stays rejected.
        require(input.isNotEmpty() || history.isNotEmpty())
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
    /** A fragment of the arguments document of the call the model is currently emitting. */
    data class ToolCallDelta(
        val requestId: String, val itemId: String, val name: String?, val argsFragment: String,
    ) : ModelStreamEvent {
        init { require(requestId.isNotBlank() && itemId.isNotBlank()) }
        override fun toString() = "ToolCallDelta(redacted)"
    }
    /**
     * A complete tool call. [argsJson] may legitimately be an empty string (the model called the tool with no
     * arguments), so it is deliberately not blank-checked; whether it is valid JSON is the caller's decision.
     * [itemId] is the id of the observed `function_call` item (the endpoint's own item identity, distinct from
     * [callId]); the history echo feeds it back so the call is returned to the model exactly as it was produced.
     */
    data class ToolCallTerminal(
        val requestId: String, val itemId: String, val callId: String, val name: String, val argsJson: String,
    ) : ModelStreamEvent {
        init { require(requestId.isNotBlank() && itemId.isNotBlank() && callId.isNotBlank() && name.isNotBlank()) }
        override fun toString() = "ToolCallTerminal(redacted)"
    }
}

interface ModelClient {
    fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle
}
