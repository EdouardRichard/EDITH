package edith.audio

import edith.contracts.EventEnvelope
import edith.contracts.TranscriptRevision

internal class TurnTimeline(private val turnId: String, private val startNanos: Long) {
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
