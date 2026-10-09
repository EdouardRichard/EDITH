package edith.audio

import edith.contracts.*
import kotlin.test.*

class AcousticLabelerTest {
    private val epoch = InputEpoch(SessionId("synthetic-session"), Generation(1), 1)
    private val frameSamples = 320

    /** 20 ms frames at 16 kHz, exactly like [PcmFramer] produces. */
    private fun frame(index: Int, amplitude: Float) = PcmFrame(epoch,
        FloatArray(frameSamples) { if (it % 2 == 0) amplitude else -amplitude }, 16000,
        SampleRange("c", index * frameSamples.toLong(), (index + 1) * frameSamples.toLong()),
        index * 20_000_000L, (index + 1) * 20_000_000L)

    private fun silence(index: Int) = PcmFrame(epoch, FloatArray(frameSamples), 16000,
        SampleRange("c", index * frameSamples.toLong(), (index + 1) * frameSamples.toLong()),
        index * 20_000_000L, (index + 1) * 20_000_000L)

    @Test fun `sustained silence is reported once on the transition and never again`() {
        val labeler = AcousticLabeler()
        val seen = (0 until 50).map { labeler.accept(silence(it), speech = false) }
        assertEquals(listOf(AcousticLabel.Silence), seen.filterNotNull())
    }

    @Test fun `a speech burst crosses to speech once and holds through a short gap`() {
        val labeler = AcousticLabeler()
        val seen = mutableListOf<AcousticLabel?>()
        var index = 0
        repeat(2) {
            repeat(15) { seen += labeler.accept(frame(index++, 0.2f), speech = true) }
            repeat(15) { seen += labeler.accept(silence(index++), speech = false) }
        }
        assertEquals(listOf(AcousticLabel.Speech), seen.filterNotNull())
    }

    @Test fun `sustained steady loud noise is reported as noisy not speech`() {
        val labeler = AcousticLabeler()
        val seen = (0 until 125).map { labeler.accept(frame(it, 0.15f), speech = false) }
        assertEquals(listOf(AcousticLabel.Noisy), seen.filterNotNull())
    }

    @Test fun `a labeler returns nothing until a hold window has elapsed`() {
        val labeler = AcousticLabeler()
        assertTrue((0 until 39).all { labeler.accept(silence(it), speech = false) == null })
        assertEquals(AcousticLabel.Silence, labeler.accept(silence(39), speech = false))
    }
}
