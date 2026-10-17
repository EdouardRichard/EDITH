package edith.contracts

data class DiscontinuityEvent(override val envelope: EventEnvelope, val reason: GapReason) : HostEvent

data class ServiceFaultEvent(override val envelope: EventEnvelope, val code: String) : HostEvent {
    init { require(code.isNotBlank()) }
}

data class OutputStateEvent(
    override val envelope: EventEnvelope,
    val hintId: String,
    val status: OutputStatus,
) : HostEvent {
    init { require(hintId.isNotBlank()) }
}
