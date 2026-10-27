package com.sheen.rokid.contracts

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

/**
 * 0.4 Task 10 (`D203`, spec §5.2): a wearer action, optionally carrying a **typed** body.
 *
 * [Typed][Action.Typed] is the phone's text input. It is deliberately additive: every pre-0.4 action keeps its meaning
 * and no codec is involved - this type is phone-internal, never serialised and never on the CXR wire. The smart plugin
 * is the only exhaustive `when` over [Action], so the compiler forces the new branch there.
 *
 * [text] is meaningful only for [Action.Typed] and defaults to null so every positional construction that predates the
 * typed path still compiles. Callers must not log it (it is the wearer's content, counts only).
 */
data class UserAction(
    override val envelope: EventEnvelope,
    val action: Action,
    val text: String? = null,
) : HostEvent {
    enum class Action { Pause, Resume, Stop, Help, Typed }
}

data class WakeDetected(
    override val envelope: EventEnvelope,
    val word: String,
    val confidence: Double,
) : HostEvent {
    init {
        require(word.isNotBlank())
        require(confidence in 0.0..1.0)
    }
}

enum class AcousticLabel { Speech, Silence, Noisy }

data class AcousticEvent(
    override val envelope: EventEnvelope,
    val label: AcousticLabel,
) : HostEvent

/** Raw fields rather than `plugin-api`'s `ContextSnapshot`: `core-contracts` must not depend on `plugin-api`. */
data class ContextChanged(
    override val envelope: EventEnvelope,
    val capturedAtNanos: Long,
    val values: Map<String, String>,
) : HostEvent {
    init {
        require(capturedAtNanos >= 0)
        require(values.keys.all { it.isNotBlank() })
        require(values.keys.size == values.keys.toSet().size)
    }
}

data class ConfirmationResult(
    override val envelope: EventEnvelope,
    val requestId: String,
    val approved: Boolean,
) : HostEvent {
    init { require(requestId.isNotBlank()) }
}

/**
 * 0.24 spec §5.5: the wearer pinned the glasses panel (the glasses' `PIN` command).
 *
 * Host→plugin: the plugin sets its `pinned` flag; it is deliberately not a `ConversationDisplay` call because that
 * port runs in the opposite direction. `UI_READY`/`UI_CLOSED` never reach the plugin as events.
 */
data class PinChanged(
    override val envelope: EventEnvelope,
    val route: AssistantRoute,
) : HostEvent

/**
 * 0.24 spec §5.5: a speech request for [hintId] finished on the phone.
 *
 * Host→plugin and phone-internal (there is no wire command for it): from [result] the plugin decides whether the next
 * listening turn may start. [SpeechResult] lives here rather than in `plugin-api` so both sides of the boundary share
 * one type.
 *
 * [emitId] is the per-emit identity of the hint that was spoken (see `Assistance.emitId`; 0.24 S3 Task 14 fix C1):
 * a superseded emit's playback end can outlive its replacement, and the plugin must not consume it as the current
 * answer's speech end. Null is the legacy/proactive case where the hint had only one emit.
 */
data class SpeechFinished(
    override val envelope: EventEnvelope,
    val hintId: String,
    val result: SpeechResult,
    val emitId: String? = null,
) : HostEvent {
    init { require(hintId.isNotBlank()); require(emitId == null || emitId.isNotBlank()) }
}
