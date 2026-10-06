package edith.audio

import edith.contracts.*
import edith.plugin.SpeakerWindow

internal class TurnRevision(private val turnId: String, private val startNanos: Long) {
    private var revision = -1L
    private var text: String? = null
    private var sealed = false

    fun revise(hypothesis: AsrHypothesis, envelope: EventEnvelope): TranscriptRevision? {
        if (sealed || (text == hypothesis.text && !hypothesis.final)) return null
        revision = Math.addExact(revision, 1)
        text = hypothesis.text
        sealed = hypothesis.final
        return TranscriptRevision(envelope, turnId, revision, hypothesis.text, hypothesis.final,
            startNanos, envelope.endNanos)
    }
}

class TurnTimeline {
    internal data class SpeakerAdmission(val version: Long, val epoch: InputEpoch, val continuity: String)
    private data class Provenance(val epoch: InputEpoch, val continuity: String)
    private data class Window(val envelope: EventEnvelope, val provenance: Provenance, val overlap: Boolean)
    private data class Assessment(val value: SpeakerAssessment, val provenance: Provenance?)
    private val provenance = mutableMapOf<String, MutableSet<Provenance>>()
    private val turns = mutableMapOf<String, TranscriptRevision>()
    private val windows = mutableSetOf<Window>()
    private val assessments = mutableListOf<Assessment>()
    private var version = 0L

    @Synchronized internal fun speakerAdmission(epoch: InputEpoch, continuity: String) =
        SpeakerAdmission(version, epoch, continuity)

    @Synchronized internal fun applySpeaker(assessment: SpeakerAssessment, admission: SpeakerAdmission): Set<String> {
        if (admission.version != version || sourceFor(assessment) != Provenance(admission.epoch, admission.continuity))
            return emptySet()
        return applySpeaker(assessment)
    }

    @Synchronized fun registerTranscriptProvenance(turnId: String, epoch: InputEpoch, continuityId: String) {
        require(turnId.isNotBlank() && continuityId.isNotBlank())
        provenance.getOrPut(turnId) { mutableSetOf() } += Provenance(epoch, continuityId)
    }

    @Synchronized fun registerSpeakerWindow(window: SpeakerWindow, epoch: InputEpoch) {
        val envelope = window.segment.envelope
        if (envelope.sessionId == epoch.sessionId && envelope.generation == epoch.generation &&
            window.range.endExclusive > window.range.first && envelope.endNanos > envelope.startNanos) {
            windows += Window(envelope, Provenance(epoch, window.range.continuityId), window.overlap)
        }
    }

    @Synchronized fun applyTranscript(revision: TranscriptRevision): Boolean {
        val old = turns[revision.turnId]
        if (old != null && (old.final || !revision.isNewerThan(old) || revision.startNanos != old.startNanos ||
                revision.endNanos < old.endNanos)) return false
        turns[revision.turnId] = revision
        return true
    }

    @Synchronized fun applySpeaker(assessment: SpeakerAssessment): Set<String> {
        if (assessment.endNanos <= assessment.startNanos) return emptySet()
        assessments += Assessment(assessment, sourceFor(assessment))
        return turns.values.filter { matches(it, assessment) }.map { it.turnId }.toSet()
    }

    @Synchronized fun speakerFor(turnId: String): SpeakerLabel {
        val turn = turns[turnId] ?: return SpeakerLabel.UNKNOWN
        val source = provenance[turnId]?.singleOrNull() ?: return SpeakerLabel.UNKNOWN
        if (source.epoch.sessionId != turn.envelope.sessionId || source.epoch.generation != turn.envelope.generation)
            return SpeakerLabel.UNKNOWN
        val relevant = assessments.filter { matches(turn, it.value) }
        if (relevant.isEmpty()) return SpeakerLabel.UNKNOWN
        val labels = relevant.map { entry ->
            val affected = turns.values.count { matches(it, entry.value) }
            if (entry.provenance != source || sourceFor(entry.value) != source || affected != 1)
                SpeakerLabel.UNKNOWN else entry.value.label
        }.toSet()
        return labels.singleOrNull() ?: SpeakerLabel.UNKNOWN
    }

    @Synchronized fun clear() {
        version = Math.addExact(version, 1)
        provenance.clear(); turns.clear(); windows.clear(); assessments.clear()
    }

    private fun sameSession(a: EventEnvelope, b: EventEnvelope) = a.sessionId == b.sessionId && a.generation == b.generation
    private fun sourceFor(assessment: SpeakerAssessment): Provenance? {
        val intersecting = windows.filter { sameSession(it.envelope, assessment.envelope) &&
            overlaps(it.envelope.startNanos, it.envelope.endNanos, assessment.startNanos, assessment.endNanos) }
        if (intersecting.any { it.overlap } || intersecting.none {
                it.envelope.startNanos <= assessment.startNanos && it.envelope.endNanos >= assessment.endNanos }) return null
        return intersecting.map { it.provenance }.toSet().singleOrNull()
    }
    private fun matches(turn: TranscriptRevision, assessment: SpeakerAssessment) =
        sameSession(turn.envelope, assessment.envelope) &&
            overlaps(turn.startNanos, turn.endNanos, assessment.startNanos, assessment.endNanos)
}

fun overlaps(aStart: Long, aEnd: Long, bStart: Long, bEnd: Long): Boolean =
    aStart < bEnd && bStart < aEnd
