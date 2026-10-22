package com.sheen.rokid.agent

/**
 * One short, **non-persistent** snapshot of the device/loop state injected into a model request (Task 12, R59; D3 §1.5).
 *
 * The content is pure state — the loop-owned step/observation counters and write/typing flags, then the device's
 * foreground package/window and latest observation revision/generation — never user text. It travels as a
 * `RUNTIME_STATE` segment: untrusted, folded into the request input by the gateway, never written to a
 * `ConversationStore` and never part of a run's transcript. It exists so the model can reason about where it is without
 * paying for a full observation, and it is at most [MAX_CHARS] characters so it cannot become the request's dominant
 * cost.
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

        /**
         * 0.25 Task 12 fix (R100): compose the one request line from the loop-owned state and the provider's device
         * line.
         *
         * The loop fields come first so the cap can never hide them; the provider's device text takes whatever room is
         * left. The flags are counts/booleans only — the line never carries a typed value, a tool payload or any other
         * user content, and the loop never reads the text it reported.
         *
         * `deviceText` is the provider's already-bounded line (a provider that has nothing to say passes none at all
         * and the loop injects nothing). This method only composes; it never persists or logs the result.
         */
        internal fun compose(
            deviceText: String,
            spentSteps: Int,
            spentObservations: Int,
            lastWriteUnverified: Boolean,
            typedUnsent: Boolean,
        ): TurnRuntimeState {
            val loop = buildString {
                append("steps=").append(spentSteps)
                append(" observations=").append(spentObservations)
                append(" writeUnverified=").append(if (lastWriteUnverified) "yes" else "no")
                append(" typedUnsent=").append(if (typedUnsent) "yes" else "no")
            }
            val separator = if (deviceText.isBlank()) "" else " | "
            val room = MAX_CHARS - loop.length - separator.length
            val device = if (room <= 0) "" else deviceText.take(room)
            val line = if (device.isEmpty()) loop else loop + separator + device
            return TurnRuntimeState(line)
        }
    }
}
