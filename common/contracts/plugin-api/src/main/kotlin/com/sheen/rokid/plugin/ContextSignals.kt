package com.sheen.rokid.plugin

/**
 * Point-in-time view of the context signals a plugin may read. [values] is a flat projection of the fields the host
 * has decided to expose; keys are non-blank and unique (uniqueness is structural: this is a [Map]).
 */
data class ContextSnapshot(val capturedAtNanos: Long, val values: Map<String, String>) {
    init {
        require(capturedAtNanos >= 0)
        require(values.keys.none { it.isBlank() }) { "context signal keys must be non-blank" }
    }
}

interface ContextSignals {
    fun snapshot(): ContextSnapshot
    fun listen(listener: (ContextSnapshot) -> Unit): CancellationHandle
}
