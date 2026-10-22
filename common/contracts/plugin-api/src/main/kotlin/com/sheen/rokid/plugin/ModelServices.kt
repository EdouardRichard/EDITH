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
 * One image the host holds as an artifact, referenced from a request or a request history instead of inlined.
 *
 * The bytes stay in the artifact store; this is the metadata that travels with a tool result or a request-flow
 * segment (0.25 spec §4.6/§5.3) so the encoder can resolve the artifact through `MediaSource.resolveImage` at encode
 * time. [sha256] is the artifact's content hash, which tells two stored revisions apart without reading the bytes.
 */
data class ImageRef(
    val artifactId: String,
    val mime: String,
    val width: Int,
    val height: Int,
    val sha256: String,
)

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
    data class ToolOutput(
        val callId: String,
        val output: String,
        /**
         * Images the tool produced, referenced by artifact id (0.25 spec §4.6/§5.3). Empty is the default, so a
         * pre-0.25 tool round keeps its exact shape and a tool that produces no image pays nothing extra.
         */
        val images: List<ImageRef> = emptyList(),
    ) : ModelHistoryItem {
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
    /**
     * 0.3 design §5.2: this turn's output cap, overriding the profile's window-reservation value at the adapter.
     * `null` — the default — means "use `ModelProfile.maxOutputTokens`". It is a per-turn declaration (a short answer,
     * a compression summary, a proactive hint), never a zero-budget request.
     */
    val maxOutputTokens: Int? = null,
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

/**
 * One model turn's provider-reported accounting. Counts only — no body text ever rides on this type.
 *
 * [cachedInputTokens] and [cacheMissInputTokens] are the provider's cache telemetry (0.3 design §5.3), added
 * additively in 0.3: `null` means the provider did not report that count, which is **not** the same as a zero and is
 * never fabricated. Only an explicit provider member may fill the miss count; a protocol that has no cache notion
 * leaves both null.
 */
data class ModelUsage(
    val inputTokens: Long?,
    val outputTokens: Long?,
    /** Input tokens served from the provider's prompt cache, when it reports them. Null = unreported. */
    val cachedInputTokens: Long? = null,
    /** Input tokens the provider reports as a cache miss, when it reports them. Null = unreported, never derived. */
    val cacheMissInputTokens: Long? = null,
) {
    init {
        require(inputTokens == null || inputTokens >= 0); require(outputTokens == null || outputTokens >= 0)
        require(cachedInputTokens == null || cachedInputTokens >= 0)
        require(cacheMissInputTokens == null || cacheMissInputTokens >= 0)
    }
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
