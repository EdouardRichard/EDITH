package com.sheen.rokid.agent

/**
 * One short, **non-persistent** snapshot of the device/loop state injected into a model request (Task 12, R59; D3 §1.5).
 *
 * The content is pure state — foreground package/window, the latest observation revision/generation, how many steps and
 * observations this run has spent — never user text. It travels as a `RUNTIME_STATE` segment: untrusted, folded into the
 * request input by the gateway, never written to a `ConversationStore` and never part of a run's transcript. It exists
 * so the model can reason about where it is without paying for a full observation, and it is at most [MAX_CHARS]
 * characters so it cannot become the request's dominant cost.
 *
 * A producer that cannot describe the state returns no runtime state at all, never a fabricated one.
 */
data class TurnRuntimeState(val text: String) {
    init {
        require(text.isNotBlank()) { "TurnRuntimeState text must not be blank" }
        require(text.length <= MAX_CHARS) {
            "TurnRuntimeState must be at most $MAX_CHARS characters, was ${text.length}"
        }
    }

    companion object {
        /** D3 §1.5's cap: a few hundred characters of state, not a second context layer. */
        const val MAX_CHARS: Int = 300
    }
}
