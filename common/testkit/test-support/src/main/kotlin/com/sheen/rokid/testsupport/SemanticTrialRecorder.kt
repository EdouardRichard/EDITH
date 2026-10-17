package com.sheen.rokid.testsupport

/**
 * T16 gap #5: locally judged semantic trials for the frozen corpus, plus the speech-interval annotation
 * that makes the "partial before speech end" condition provable.
 *
 * Recognized text is shown transiently for a local judgment and then released; only trial ids, sample
 * positions, correctness and concept counts are meant to be persisted. A turn that never produced a partial
 * stays in the denominator as an incorrect trial - the sample is never allowed to shrink.
 *
 * The frozen corpus is all-or-nothing: production paths build the recorder with [of], which refuses any set
 * that is not exactly the 30 frozen ids. [forTesting] exists only for per-turn behaviour checks.
 *
 * Note: the class deliberately exposes no `invoke` factory, because a private primary constructor plus a
 * companion `invoke` would make call sites resolve to the raw constructor and silently skip validation.
 */
class SemanticTrialRecorder private constructor(
    private val entries: List<Entry>,
) {
    /** A frozen corpus id, its local speech interval in sample positions, and the required concepts. */
    data class Entry(val id: String, val speechStartSample: Long, val speechEndSample: Long,
        val requiredConcepts: List<String>) {
        init {
            require(EngineMetricsSchema.isTrialId(id)) { "UNKNOWN_TRIAL_ID" }
            require(speechStartSample >= 0 && speechEndSample > speechStartSample) { "INVALID_SPEECH_INTERVAL" }
            require(requiredConcepts.isNotEmpty() && requiredConcepts.none { it.isBlank() }) {
                "REQUIRED_CONCEPTS_MISSING"
            }
        }
    }

    /** Persistable annotation of one turn: ids, sample positions and status only, never the transcript. */
    data class Annotation(val id: String, val speechStartSample: Long, val speechEndSample: Long,
        val firstPartialSample: Long?, val finalPresent: Boolean, val conceptsPresent: Int,
        val conceptsRequired: Int) {
        val partialBeforeSpeechEnd: Boolean
            get() = firstPartialSample != null && firstPartialSample < speechEndSample
    }

    class TrialSetMismatch(message: String) : IllegalArgumentException(message)

    private class State(val entry: Entry) {
        var firstPartial: Long? = null
        var finalText: String? = null
        var judged: Boolean = false
        var conceptsPresent: Int = 0
    }

    private val states = entries.associateBy({ it.id }, { State(it) })
    private val order = entries.map { it.id }

    /**
     * Records one observed turn. A partial outside the annotated speech interval is rejected: that interval
     * is the frozen annotation this measurement exists to compare against, so evidence outside it would be
     * meaningless rather than merely imprecise.
     */
    fun observe(id: String, partialSample: Long?, finalText: String) {
        val state = requireState(id)
        if (partialSample != null) {
            require(partialSample in state.entry.speechStartSample until state.entry.speechEndSample) {
                "PARTIAL_OUTSIDE_ANNOTATED_SPEECH"
            }
            state.firstPartial = minOf(state.firstPartial ?: partialSample, partialSample)
        }
        state.finalText = finalText
    }

    /** Judges one turn. Passing [concepts] overrides the frozen list so a test can model a missed concept. */
    fun judge(id: String, concepts: List<String>? = null): List<SemanticTrial> {
        val state = requireState(id)
        check(!state.judged) { "TRIAL_ALREADY_JUDGED" }
        val required = state.entry.requiredConcepts
        val observed = concepts ?: required
        require(observed.isNotEmpty()) { "REQUIRED_CONCEPTS_MISSING" }
        state.judged = true
        state.conceptsPresent = required.count { it in observed }
        return toTrials()
    }

    fun toTrials(): List<SemanticTrial> = order.map { id ->
        val state = requireState(id)
        SemanticTrial(id, state.judged && state.conceptsPresent == state.entry.requiredConcepts.size)
    }

    fun annotations(): List<Annotation> = order.map { id ->
        val state = requireState(id)
        Annotation(id, state.entry.speechStartSample, state.entry.speechEndSample, state.firstPartial,
            state.finalText != null, state.conceptsPresent, state.entry.requiredConcepts.size)
    }

    fun partialBeforeSpeechEnd(id: String): Boolean = requireState(id).let { state ->
        val partial = state.firstPartial ?: return false
        partial < state.entry.speechEndSample
    }

    fun firstPartialSample(id: String): Long? = requireState(id).firstPartial

    fun allPartialsPrecededSpeechEnd(): Boolean = order.all { partialBeforeSpeechEnd(it) }

    private fun requireState(id: String): State = requireNotNull(states[id]) { "UNKNOWN_TRIAL_ID: $id" }

    companion object {
        /** Builds the recorder for a corpus. A set that is not exactly the frozen 30 ids is refused. */
        fun of(entries: List<Entry>): SemanticTrialRecorder {
            if (entries.size != EngineMetricsSchema.REQUIRED_TRIALS) {
                throw TrialSetMismatch("expected ${EngineMetricsSchema.REQUIRED_TRIALS} frozen trials, got ${entries.size}")
            }
            val ids = entries.map { it.id }
            if (ids.toSet().size != ids.size) throw TrialSetMismatch("duplicate frozen trial id")
            return SemanticTrialRecorder(entries)
        }

        /**
         * Per-turn behaviour checks only. Production paths must use [of], which enforces that the whole
         * frozen 30-trial corpus is present.
         */
        internal fun forTesting(vararg entries: Entry): SemanticTrialRecorder =
            SemanticTrialRecorder(entries.toList())
    }
}
