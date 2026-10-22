package com.sheen.rokid.audio

import com.sheen.rokid.contracts.SpeakerAssessment
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.SpeakerWindow

data class VadDecision(val speech: Boolean, val endpoint: Boolean)
data class AsrHypothesis(val text: String, val final: Boolean)

interface VadEngine {
    fun accept(frame: PcmFrame): VadDecision
    fun reset()
    fun close()
}

interface StreamingAsrEngine {
    fun accept(frame: PcmFrame): List<AsrHypothesis>
    fun finish(): AsrHypothesis
    fun reset()
    fun close()
}

interface SpeakerEngine {
    fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit): CancellationHandle
    fun reset()
    fun close()
}
