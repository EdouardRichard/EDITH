package com.sheen.rokid.phone.audio

import com.sheen.rokid.audio.SpeechPipeline
import com.sheen.rokid.contracts.HostEvent

/**
 * 0.4 Task 12b-ii (spec §5.3, D204): the production [DictationPipeline] over the one shared [SpeechPipeline].
 *
 * The dictation host owns no recognizer of its own (reloading the ASR would cost ~241 MB and would compete with the
 * wake engine), so this adapter is the whole "second output destination" mechanism: it forwards the controller's
 * redirect to [SpeechPipeline.redirectEmit] and its stop to [SpeechPipeline.finishCurrentTurn]. Nothing else about the
 * pipeline changes - the wake route is whichever sink the constructor installed as long as no redirect is active.
 *
 * [hasActiveTurn] is the host's pre-start guard: a live wake turn must not be stolen by a dictation.
 */
internal class SpeechPipelineDictation(private val pipeline: SpeechPipeline) : DictationPipeline {

    override fun redirect(sink: ((HostEvent) -> Unit)?) = pipeline.redirectEmit(sink)

    override fun finishCurrentTurn(): Boolean = pipeline.finishCurrentTurn()

    /** Whether the shared pipeline has a speech turn in flight, i.e. a dictation would capture a wake request's final. */
    fun hasActiveTurn(): Boolean = pipeline.hasActiveTurn()
}
