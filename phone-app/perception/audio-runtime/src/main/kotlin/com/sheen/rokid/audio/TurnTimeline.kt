package com.sheen.rokid.audio

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.SpeakerWindow

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
    private var retiredThroughNanos = 0L
    private var exhausted = false

    @Synchronized internal fun speakerAdmission(epoch: InputEpoch, continuity: String) =
        SpeakerAdmission(version, epoch, continuity)

    @Synchronized internal fun applySpeaker(assessment: SpeakerAssessment, admission: SpeakerAdmission): Set<String> {
        if (admission.version != version || sourceFor(assessment) != Provenance(admission.epoch, admission.continuity))
            return emptySet()
        return applySpeaker(assessment)
    }

    @Synchronized fun registerTranscriptProvenance(turnId: String, epoch: InputEpoch, continuityId: String) {
        require(turnId.isNotBlank() && continuityId.isNotBlank())
        if (exhausted) return
        if (turnId !in provenance && provenance.size >= 128) {
            val oldest = turns.values.minByOrNull { it.endNanos }
            if (oldest == null) { exhausted = true; return }
            retireThrough(oldest.endNanos)
            if (provenance.size >= 128) { exhausted = true; return }
        }
        val sources = provenance.getOrPut(turnId) { mutableSetOf() }
        // Two distinct sources already prove ambiguity; retaining more cannot resolve it.
        if (sources.size < 2) sources += Provenance(epoch, continuityId)
    }

    @Synchronized fun registerSpeakerWindow(window: SpeakerWindow, epoch: InputEpoch) {
        val envelope = window.segment.envelope
        if (!exhausted && envelope.startNanos >= retiredThroughNanos &&
            envelope.sessionId == epoch.sessionId && envelope.generation == epoch.generation &&
            window.range.endExclusive > window.range.first && envelope.endNanos > envelope.startNanos) {
            val value = Window(envelope, Provenance(epoch, window.range.continuityId), window.overlap)
            if (value in windows) return
            if (windows.size >= 256) retireThrough(windows.minOf { it.envelope.endNanos })
            if (envelope.startNanos >= retiredThroughNanos) windows += value
        }
    }

    @Synchronized fun applyTranscript(revision: TranscriptRevision): Boolean {
        if (exhausted || revision.startNanos < retiredThroughNanos) return false
        val old = turns[revision.turnId]
        if (old != null && (old.final || !revision.isNewerThan(old) || revision.startNanos != old.startNanos ||
                revision.endNanos < old.endNanos)) return false
        if (old == null && turns.size >= 128) retireThrough(turns.values.minOf { it.endNanos })
        if (revision.startNanos < retiredThroughNanos) return false
        turns[revision.turnId] = revision
        return true
    }

    @Synchronized fun applySpeaker(assessment: SpeakerAssessment): Set<String> {
        if (exhausted || assessment.startNanos < retiredThroughNanos || assessment.endNanos <= assessment.startNanos)
            return emptySet()
        if (assessments.size >= 256) retireThrough(assessments.minOf { it.value.endNanos })
        if (assessment.startNanos < retiredThroughNanos) return emptySet()
        assessments += Assessment(assessment, sourceFor(assessment))
        return turns.values.filter { matches(it, assessment) }.map { it.turnId }.toSet()
    }

    @Synchronized fun speakerFor(turnId: String): SpeakerLabel {
        if (exhausted) return SpeakerLabel.UNKNOWN
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
        retiredThroughNanos = 0; exhausted = false
    }

    internal data class RetentionCounts(val turns: Int, val provenance: Int, val sources: Int,
        val windows: Int, val assessments: Int)
    @Synchronized internal fun retentionCounts() = RetentionCounts(turns.size, provenance.size,
        provenance.values.sumOf { it.size }, windows.size, assessments.size)

    private fun retireThrough(endNanos: Long) {
        retiredThroughNanos = maxOf(retiredThroughNanos, endNanos)
        // Extend through every crossing interval before forgetting conflicting evidence.
        while (true) {
            val ends = turns.values.filter { it.startNanos < retiredThroughNanos }.map { it.endNanos } +
                windows.filter { it.envelope.startNanos < retiredThroughNanos }.map { it.envelope.endNanos } +
                assessments.filter { it.value.startNanos < retiredThroughNanos }.map { it.value.endNanos }
            val extended = ends.maxOrNull() ?: break
            if (extended <= retiredThroughNanos) break
            retiredThroughNanos = extended
        }
        val retired = turns.values.filter { it.startNanos < retiredThroughNanos }.map { it.turnId }
        retired.forEach { turns.remove(it); provenance.remove(it) }
        windows.removeAll { it.envelope.startNanos < retiredThroughNanos }
        assessments.removeAll { it.value.startNanos < retiredThroughNanos }
    }

    private fun sameSession(a: EventEnvelope, b: EventEnvelope) = a.sessionId == b.sessionId && a.generation == b.generation
    private fun sourceFor(assessment: SpeakerAssessment): Provenance? {
        if (exhausted || assessment.startNanos < retiredThroughNanos) return null
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
