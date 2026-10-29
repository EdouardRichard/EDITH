package com.sheen.rokid.phone.audio

import android.speech.tts.UtteranceProgressListener
import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.plugin.smarthelper.host.DetachGate
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettingsStore
import com.sheen.rokid.phone.session.EchoMuteWindow
import com.sheen.rokid.phone.session.PlaybackGate
import com.sheen.rokid.phone.storage.EncryptedBlobStore
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.SpeechPlayback
import com.sheen.rokid.plugin.SpeechPlayer
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.testsupport.FakeClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 0.5.1 C5: the root's speech switch and the detached wake acknowledgement against the player/echo ownership.
 *
 * The review found two lifecycle holes on this seam: the wake acknowledgement opened the echo window itself and then
 * the player's `onStart` opened it again, and turning 「语音朗读」 off raced an in-flight `play()` so a handle (and its
 * open window) could survive the switch. These tests drive `setSpeechEnabled` through the real root wiring with a fake
 * platform player, and prove the acknowledgement is muted by the **player's** onset rather than by the root.
 */
class SpeechRoutingTest {

    private fun assistance() = Assistance(
        hintId = "h-1",
        sessionId = SessionId("s-1"),
        generation = Generation(1),
        sourceTurnId = null,
        priority = 0,
        text = "答案",
        optionalSpeech = null,
        expiresAtNanos = Long.MAX_VALUE,
    )

    private class InertModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            object : CancellationHandle { override fun cancel() = Unit }
    }

    private class FakePlayer : SpeechPlayer, AutoCloseable {
        var plays = 0
        var cancels = 0
        var closes = 0

        override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit): CancellationHandle {
            plays++
            return object : CancellationHandle {
                override fun cancel() { cancels++ }
            }
        }

        override fun close() { closes++ }
    }

    /** A TTS engine that records the phrase and lets the test fire lifecycle callbacks on demand. */
    private class RecordingTtsEngine : TtsEngine {
        val speaking = CountDownLatch(1)
        var spoken: String? = null
        private var listener: UtteranceProgressListener? = null
        override var isLanguageAvailable: Int = 0
        override var initStatus: Int = 0

        override fun speak(text: String, utteranceId: String): Boolean {
            spoken = text
            speaking.countDown()
            return true
        }

        override fun stop() = Unit
        override fun shutdown() = Unit
        override fun setOnProgressListener(listener: UtteranceProgressListener?) { this.listener = listener }

        fun fireStart(utteranceId: String) { listener?.onStart(utteranceId) }
        fun fireDone(utteranceId: String) { listener?.onDone(utteranceId) }
    }

    private class RecordingBlobs : EncryptedBlobStore {
        val values = linkedMapOf<String, ByteArray>()
        val writes = AtomicInteger(0)
        var failReadId: String? = null

        override fun read(id: String): ByteArray? {
            if (id == failReadId) throw IOException("synthetic read failure")
            return values[id]?.copyOf()
        }

        override fun write(id: String, value: ByteArray) {
            writes.incrementAndGet()
            values[id] = value.copyOf()
        }

        override fun delete(id: String) { values.remove(id) }
    }

    private fun composition(
        router: SpeechPlayerRouter,
        blobs: RecordingBlobs,
        gate: DetachGate = DetachGate(settings = SmartSettingsStore(blobs)),
        factory: () -> SpeechPlayer?,
    ): HostCompositionRoot = HostCompositionRoot(
        host = HostRuntime(1),
        profiles = MemoryProfileRepository(),
        model = InertModel(),
        environment = null,
        detachGate = gate,
        speechRouter = router,
        speechPlayerFactory = factory,
        smartSettingsStore = SmartSettingsStore(blobs),
    )

    @Test
    fun `setSpeechEnabled creates one player per on and a second off is idempotent`() {
        val blobs = RecordingBlobs()
        val router = SpeechPlayerRouter()
        val created = mutableListOf<FakePlayer>()
        val root = composition(router, blobs) { FakePlayer().also { created += it } }
        try {
            assertFalse("the switch ships off", root.speechEnabled.value)
            assertFalse("no player is installed while the switch is off", router.isAvailable())

            root.setSpeechEnabled(true)
            assertTrue(root.speechEnabled.value)
            assertTrue("the created player is installed", router.isAvailable())
            assertEquals(1, created.size)

            root.setSpeechEnabled(true)
            assertEquals("an on while a player is held reuses it", 1, created.size)
            assertEquals("and the same player stays installed", created[0], router.current())

            root.setSpeechEnabled(false)
            assertFalse(root.speechEnabled.value)
            assertFalse("off detaches the player", router.isAvailable())
            assertEquals("off releases (closes) the held player exactly once", 1, created[0].closes)

            root.setSpeechEnabled(false)
            assertEquals("a second off has nothing left to close", 1, created[0].closes)

            root.setSpeechEnabled(true)
            assertEquals("a later on creates a fresh player", 2, created.size)
            assertTrue(router.isAvailable())

            val stored = SmartSettingsStore(blobs).load()
            assertTrue("an on is the user's explicit choice", stored.speechConfigured)
            assertTrue(stored.speechEnabled)

            root.setSpeechEnabled(false)
            val off = SmartSettingsStore(blobs).load()
            assertFalse(off.speechEnabled)
            assertTrue("an explicit off is recorded too", off.speechConfigured)
        } finally {
            root.close()
        }
    }

    @Test
    fun `setSpeechEnabled keeps the live change but does not persist when the blob cannot be read`() {
        val blobs = RecordingBlobs()
        blobs.values[SmartSettingsStore.BLOB_ID] = """{"version":1,"speechEnabled":false}""".toByteArray()
        blobs.failReadId = SmartSettingsStore.BLOB_ID
        val router = SpeechPlayerRouter()
        val root = composition(router, blobs) { FakePlayer() }
        try {
            root.setSpeechEnabled(true)

            assertTrue("the live switch still moves", root.speechEnabled.value)
            assertTrue(router.isAvailable())
            assertEquals("an unreadable blob must not be overwritten with defaults", 0, blobs.writes.get())
        } finally {
            root.close()
        }
    }

    @Test
    fun `the wake ack opens the echo window only at the player's audible onset`() {
        // C5: the ack used to call `echoMute.open()` itself and the player's `onStart` opened it again. The player is
        // now the single owner, and the root is given the same window through the test seam below so a reintroduced
        // root-side open cannot silently no-op on a null field.
        val clock = FakeClock()
        val window = EchoMuteWindow(clock)
        val engine = RecordingTtsEngine()
        val blobs = RecordingBlobs()
        val router = SpeechPlayerRouter()
        val speech = AndroidSpeechPlayer(engine, PlaybackGate(), window)
        val gate = DetachGate(settings = SmartSettingsStore(blobs), sessionState = { SessionState.Idle })
        val root = composition(router, blobs, gate) { speech }
        // Test seam: production sets this field only from `create`. Binding the player's window here makes a
        // reintroduced root-side `echoMute.open()` observable; with the field left null that call would no-op and the
        // "nothing is muted before the onset" assertion below would pass without testing ownership at all.
        root.echoMute = window
        try {
            assertTrue("the phone-only mode must be on", gate.set(true).saved)
            root.setSpeechEnabled(true)
            assertFalse("nothing is muted before the phrase is requested", window.covers(clock.nowNanos()))

            assertTrue("the ack must be accepted by the installed player", root.speakDetachedWakeAck("我在"))
            assertTrue("the ack speaks on its own daemon thread", engine.speaking.await(2, TimeUnit.SECONDS))
            assertFalse(
                "the root must not open the window itself; only the audible onset may",
                window.covers(clock.nowNanos()),
            )
            assertEquals("我在", engine.spoken)

            engine.fireStart("detached-wake-ack")
            assertTrue("the audible interval is muted", window.covers(clock.nowNanos()))

            engine.fireDone("detached-wake-ack")
            clock.advance(151_000_000L)
            assertFalse("one reverb tail after the end, the mic is live again", window.covers(clock.nowNanos()))
        } finally {
            root.close()
        }
    }

    @Test
    fun `setSpeechEnabled off closes the utterance in flight`() {
        // The switch is the owner that may close a player: off stops the tracked utterance, detaches, then closes the
        // player. The player's own `close()` releases the echo window (tested in AndroidSpeechPlayerTest).
        val blobs = RecordingBlobs()
        val router = SpeechPlayerRouter()
        val created = mutableListOf<FakePlayer>()
        val root = composition(router, blobs) { FakePlayer().also { created += it } }
        try {
            root.setSpeechEnabled(true)
            router.play(assistance()) { }
            assertEquals(1, created[0].plays)

            root.setSpeechEnabled(false)

            assertEquals("off stops what is playing", 1, created[0].cancels)
            assertEquals("then releases the player", 1, created[0].closes)
            assertNull("no player is left to receive a later request", router.current())
        } finally {
            root.close()
        }
    }

    @Test
    fun `setSpeechEnabled off drops the echo window immediately`() {
        // C5: the player's close keeps the 150 ms reverb tail, but an off switch is the user saying "no more muting".
        // With the 120 s watchdog gone, leaving the tail behind is the only mute source left; off must clear it.
        val blobs = RecordingBlobs()
        val router = SpeechPlayerRouter()
        val clock = FakeClock()
        val window = EchoMuteWindow(clock)
        val created = mutableListOf<FakePlayer>()
        val root = composition(router, blobs) { FakePlayer().also { created += it } }
        root.echoMute = window
        try {
            root.setSpeechEnabled(true)
            router.play(assistance()) { }
            window.open() // what the player's audible onset does
            assertTrue("the answer is speaking", window.covers(clock.nowNanos()))

            root.setSpeechEnabled(false)

            assertFalse(
                "turning speech off must release the microphone now, not after a reverb tail",
                window.covers(clock.nowNanos()),
            )
        } finally {
            root.close()
        }
    }

    @Test
    fun `stopping the session drops the echo window`() {
        // C5: a stopped session must not leave the next session's first words muted; the lost end callback is released
        // by the explicit clear on the stop path (the watchdog used to be the fallback and is now removed).
        val blobs = RecordingBlobs()
        val router = SpeechPlayerRouter()
        val clock = FakeClock()
        val window = EchoMuteWindow(clock)
        val root = composition(router, blobs) { FakePlayer() }
        root.echoMute = window
        try {
            window.open()
            clock.advance(121_000_000_000L)
            assertTrue("a lost end callback keeps the window open", window.covers(clock.nowNanos()))

            root.stop()

            assertFalse("a stopped session must release the echo window", window.covers(clock.nowNanos()))
        } finally {
            root.close()
        }
    }

    @Test
    fun `a throwing host stop cannot skip the echo window clear`() {
        // C5: `host.stop()` can throw (here: a host closed by another path answers HOST_CLOSED), and the session is
        // over either way. The explicit clear must therefore live where it runs on both exits - if it stays after the
        // try/finally, a failed stop silently leaves the window armed and the next session's first words muted.
        val blobs = RecordingBlobs()
        val router = SpeechPlayerRouter()
        val clock = FakeClock()
        val window = EchoMuteWindow(clock)
        val root = composition(router, blobs) { FakePlayer() }
        root.echoMute = window
        try {
            window.open()
            assertTrue("the window starts armed", window.covers(clock.nowNanos()))

            // The existing throwing seam: HostRuntime refuses every command once it is closed, so `root.stop()`
            // propagates HOST_CLOSED instead of returning normally.
            root.host.close()
            val failure = assertThrows(IllegalStateException::class.java) { root.stop() }
            assertEquals("the host must have thrown where the test intended", "HOST_CLOSED", failure.message)

            assertFalse(
                "a failed host stop must still release the echo window",
                window.covers(clock.nowNanos()),
            )
        } finally {
            // The root never reached its own `closed = true` (stop threw first) and the host is already closed;
            // cleanup must not turn the synthetic failure into a test failure.
            runCatching { root.close() }
        }
    }

    /**
     * 0.5.1 T8 (the T2 out-of-scope finding): `close()` called `stop()` **before** setting `closed`, outside any
     * try/finally, so a throwing stop aborted the whole teardown and left the composition "open". The host is closed by
     * another path here, which is the existing throwing seam: `stop()` propagates `HOST_CLOSED`. The composition must
     * still be closed and torn down - observable as the second `close()` being a no-op instead of another `HOST_CLOSED`.
     */
    @Test
    fun `a throwing host stop cannot skip the composition close`() {
        val blobs = RecordingBlobs()
        val root = composition(SpeechPlayerRouter(), blobs) { FakePlayer() }
        root.host.close()

        val failure = assertThrows(IllegalStateException::class.java) { root.close() }
        assertEquals("the host must have thrown where the test intended", "HOST_CLOSED", failure.message)

        root.close() // must be a no-op: the composition is closed even though its stop threw
    }

    @Test
    fun `long press on the ball stops the speaking answer before the manual dictation`() {
        // C4: the ball long press is the takeover path. The answer must stop and its echo window must go before the BALL
        // lease is requested, or the answer's own echo (or its NoSpeech) would swallow the wearer's first words.
        val blobs = RecordingBlobs()
        val router = SpeechPlayerRouter()
        val clock = FakeClock()
        val window = EchoMuteWindow(clock)
        val created = mutableListOf<FakePlayer>()
        val root = composition(router, blobs) { FakePlayer().also { created += it } }
        root.echoMute = window
        try {
            root.setSpeechEnabled(true)
            router.play(assistance()) { }
            window.open() // the player's audible onset
            assertTrue("the answer is speaking", window.covers(clock.nowNanos()))

            root.stopCurrentSpeechForBall()

            assertEquals("the speaking answer is stopped before the takeover", 1, created[0].cancels)
            assertFalse("the takeover must not leave the phone mic muted", window.covers(clock.nowNanos()))
        } finally {
            root.close()
        }
    }
}
