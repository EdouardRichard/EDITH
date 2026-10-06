package edith.contracts

data class TranscriptRevision(
    val envelope: EventEnvelope,
    val turnId: String,
    val revision: Long,
    val text: String,
    val final: Boolean,
    val startNanos: Long,
    val endNanos: Long,
) {
    init {
        require(turnId.isNotBlank())
        require(revision >= 0)
        require(startNanos >= 0)
        require(endNanos >= startNanos)
    }
    fun isNewerThan(previous: TranscriptRevision): Boolean =
        envelope.sessionId == previous.envelope.sessionId &&
            envelope.generation == previous.envelope.generation &&
            turnId == previous.turnId && revision > previous.revision
}

enum class SpeakerLabel { SELF, OTHER, UNKNOWN }

data class SpeakerAssessment(
    val envelope: EventEnvelope,
    val label: SpeakerLabel,
    val confidence: Double?,
    val startNanos: Long,
    val endNanos: Long,
) {
    init {
        require(confidence == null || confidence in 0.0..1.0)
        require(startNanos >= 0)
        require(endNanos >= startNanos)
    }
}

sealed interface HostEvent {
    val envelope: EventEnvelope
}

data class SessionStateEvent(override val envelope: EventEnvelope, val state: SessionState) : HostEvent
data class TranscriptEvent(val revision: TranscriptRevision) : HostEvent { override val envelope = revision.envelope }
data class SpeakerEvent(val assessment: SpeakerAssessment) : HostEvent { override val envelope = assessment.envelope }
data class AudioEvent(val segment: AudioSegment) : HostEvent { override val envelope = segment.envelope }

data class UserAction(override val envelope: EventEnvelope, val action: Action) : HostEvent {
    enum class Action { Pause, Resume, Stop, Help }
}
