package com.sheen.rokid.contracts

data class DiscontinuityEvent(override val envelope: EventEnvelope, val reason: GapReason) : HostEvent

data class ServiceFaultEvent(override val envelope: EventEnvelope, val code: String) : HostEvent {
    init { require(code.isNotBlank()) }
}

data class OutputStateEvent(
    override val envelope: EventEnvelope,
    val hintId: String,
    val status: OutputStatus,
    /**
     * 0.24 S3 Task 14 fix (C1): the per-emit identity of the hint this state belongs to (see `Assistance.emitId`).
     *
     * One turn's answer entry keeps one [hintId] across a superseding emit, so a replacement `Rejected` for the old
     * item and the new item's `Displayed` would be indistinguishable by [hintId] alone. Null means a producer that
     * predates the field (or a proactive hint, whose path does not mint one); a consumer that needs the distinction
     * must treat null as "the only emit of this hint" and keep the legacy hint-id comparison.
     */
    val emitId: String? = null,
) : HostEvent {
    init { require(hintId.isNotBlank()); require(emitId == null || emitId.isNotBlank()) }
}
