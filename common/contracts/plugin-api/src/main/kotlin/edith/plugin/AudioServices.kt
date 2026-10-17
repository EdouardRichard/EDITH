package edith.plugin

import edith.contracts.AudioSegment
import edith.contracts.SampleRange
import edith.contracts.SpeakerAssessment
import edith.contracts.TranscriptRevision

interface AudioSubscription {
    fun listen(listener: (AudioSegment) -> Unit): CancellationHandle
}

interface AsrSession {
    fun listen(listener: (TranscriptRevision) -> Unit): CancellationHandle
}

data class SpeakerWindow(val segment: AudioSegment, val range: SampleRange, val overlap: Boolean)

data class SpeakerProfileRef(val id: String, val revision: Long) {
    init { require(id.isNotBlank()); require(revision >= 0) }
}

interface SpeakerService {
    fun enroll(windows: List<SpeakerWindow>, listener: (SpeakerProfileRef?) -> Unit): CancellationHandle
    fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit): CancellationHandle
    fun delete(profile: SpeakerProfileRef)
}
