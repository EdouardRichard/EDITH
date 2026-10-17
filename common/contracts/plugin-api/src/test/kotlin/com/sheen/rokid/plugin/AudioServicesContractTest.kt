package com.sheen.rokid.plugin

import com.sheen.rokid.contracts.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AudioServicesContractTest {
    @Test fun `consumer fixture cancels all listener types idempotently`() {
        val fixture = AudioProviderFixture()
        val received = mutableListOf<String>()
        val epoch = InputEpoch(SessionId("s"), Generation(0), 1)
        fixture.start(epoch)
        val handles = listOf(
            fixture.listen { _: AudioSegment -> received += "audio" },
            fixture.asr.listen { received += "asr" },
            fixture.enroll(emptyList()) { received += "enroll" },
            fixture.assess(fixture.window) { received += "speaker" },
            fixture.device.show(fixture.hint, 0) { received += "output" },
        )
        fixture.emit(epoch)
        assertEquals(listOf("audio", "asr", "enroll", "speaker", "output"), received)
        handles.forEach { it.cancel(); it.cancel() }
        fixture.emit(epoch)
        assertEquals(5, received.size)
        assertEquals(listOf(1, 1, 1, 1, 1), fixture.registrations.map { it.cancelCount })
    }

    @Test fun `consumer fixture ignores stopped and previous epochs`() {
        val fixture = AudioProviderFixture()
        val received = mutableListOf<AudioSegment>()
        val old = InputEpoch(SessionId("s"), Generation(0), 1)
        fixture.listen(received::add)
        fixture.start(old)
        fixture.stop(old)
        fixture.emit(old)
        val next = old.copy(serial = 2)
        fixture.start(next)
        fixture.stop(old)
        fixture.emit(old)
        fixture.emit(next)
        assertEquals(listOf(fixture.segment), received)
    }

    @Test fun `speaker references reject blank identity and negative revision`() {
        assertFailsWith<IllegalArgumentException> { SpeakerProfileRef("", 0) }
        assertFailsWith<IllegalArgumentException> { SpeakerProfileRef("p", -1) }
    }
}

// Executable consumer fixture only; real provider cancellation is verified in T03/B/C.
private class AudioProviderFixture : InputPort, AudioSubscription, SpeakerService {
    private var epoch: InputEpoch? = null
    val registrations = mutableListOf<Registration>()
    private val envelope = EventEnvelope(SessionId("s"), Generation(0), "event", 0, 20_000_000)
    val segment = AudioSegment(envelope, AudioFormat.Unknown, byteArrayOf(0, 1))
    val window = SpeakerWindow(segment, SampleRange("c", 0, 1), false)
    val hint = Assistance("h", SessionId("s"), Generation(0), null, 1, "hint", null, 100)
    val asr = object : AsrSession {
        override fun listen(listener: (TranscriptRevision) -> Unit) = register {
            listener(TranscriptRevision(envelope, "t", 1, "synthetic", true, 0, 20_000_000))
        }
    }
    val device = object : HintDevice {
        override fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit) =
            register { listener(OutputStatus.Displayed) }
        override fun clear(sessionId: SessionId, generation: Generation) = Unit
    }
    override fun start(epoch: InputEpoch) { this.epoch = epoch }
    override fun stop(epoch: InputEpoch) { if (this.epoch == epoch) this.epoch = null }
    override fun listen(listener: (AudioSegment) -> Unit) = register { listener(segment) }
    override fun enroll(windows: List<SpeakerWindow>, listener: (SpeakerProfileRef?) -> Unit) =
        register { listener(null) }
    override fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit) = register {
        listener(SpeakerAssessment(envelope, SpeakerLabel.UNKNOWN, null, 0, 20_000_000))
    }
    override fun delete(profile: SpeakerProfileRef) = Unit
    private fun register(deliver: () -> Unit) = Registration(deliver).also(registrations::add)
    fun emit(epoch: InputEpoch) { if (this.epoch == epoch) registrations.forEach { it.deliver() } }
}

private class Registration(private val callback: () -> Unit) : CancellationHandle {
    var cancelCount = 0
    override fun cancel() { if (cancelCount == 0) cancelCount++ }
    fun deliver() { if (cancelCount == 0) callback() }
}
