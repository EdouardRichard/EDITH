package com.sheen.rokid.audio

import com.sheen.rokid.contracts.SpeakerLabel

class SpeakerPolicy(val minVoicedMillis: Long, val selfThreshold: Double?, val otherThreshold: Double?) {
    init {
        require(minVoicedMillis > 0)
        require(selfThreshold == null || selfThreshold.isFinite() && selfThreshold in 0.0..1.0)
        require(otherThreshold == null || otherThreshold.isFinite() && otherThreshold in 0.0..1.0)
        require(selfThreshold == null || otherThreshold == null || otherThreshold < selfThreshold)
    }

    fun classify(score: Double?, voicedMillis: Long, overlap: Boolean): SpeakerLabel {
        if (overlap || voicedMillis < minVoicedMillis || score == null || !score.isFinite() ||
            score !in 0.0..1.0 || selfThreshold == null || otherThreshold == null) return SpeakerLabel.UNKNOWN
        return when {
            score >= selfThreshold -> SpeakerLabel.SELF
            score <= otherThreshold -> SpeakerLabel.OTHER
            else -> SpeakerLabel.UNKNOWN
        }
    }
}
