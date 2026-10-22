package com.sheen.rokid.contracts

@JvmInline
value class SessionId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class Generation(val value: Long) {
    init { require(value >= 0) }
}

@JvmInline
value class EventId(val value: String) {
    init { require(value.isNotBlank()) }
}

@JvmInline
value class TurnId(val value: String) {
    init { require(value.isNotBlank()) }
}

data class EventEnvelope(
    val sessionId: SessionId,
    val generation: Generation,
    val eventId: String,
    val startNanos: Long,
    val endNanos: Long,
) {
    init {
        require(eventId.isNotBlank())
        require(startNanos >= 0)
        require(endNanos >= startNanos)
    }
}
