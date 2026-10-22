package com.sheen.rokid.transport

import android.app.Activity
import com.sheen.rokid.audio.*
import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.Executor

class AudioEpochUnitTest {
    @Test fun callbackSizeDoesNotVerifyFormat() {
        val rig = Rig()
        assertNull(PcmFormatEvidence.resolve("unverified-fw", "1.1.2"))
        assertNull(PcmFormatEvidence.resolve("1.26.011", "1.1.2"))
        rig.ready(); rig.transport.start(rig.first); rig.worker.drain()
        rig.audio().received(ByteArray(640)); rig.worker.drain()
        assertEquals(listOf(rig.first), rig.inputs)
        assertEquals(AudioFormat.Unknown, rig.transport.state.format)
        assertNull(rig.ingress.poll())
        assertEquals(PcmOffer.UNKNOWN_FORMAT, rig.ingress.offer(rig.first, ByteArray(640)))
    }
    @Test fun sceneMustBeReadyBeforeSdkStarts() {
        val rig = Rig()
        rig.transport.start(rig.first); rig.worker.drain()
        assertNull(rig.transport.state.epoch)
        assertEquals(0, rig.backend.starts)
        rig.ready(); rig.transport.start(rig.first); rig.worker.drain()
        assertEquals(rig.first, rig.transport.state.epoch)
        assertTrue(rig.inputs.isEmpty())
        rig.audio().received(byteArrayOf()); rig.worker.drain()
        assertTrue(rig.inputs.isEmpty())
    }
    @Test fun stopRevokesBeforeSdkStopAndOldCallbackCannotRestartInput() {
        val rig = Rig(); rig.ready(); rig.transport.start(rig.first); rig.worker.drain()
        val old = rig.audio()
        old.received(ByteArray(640))
        rig.transport.stop(rig.first)
        assertNull(rig.transport.state.epoch)
        assertEquals(PcmOffer.STALE_EPOCH, rig.ingress.offer(rig.first, ByteArray(640)))
        assertEquals(0, rig.backend.stops)
        rig.transport.start(rig.second); rig.worker.drain()
        old.received(ByteArray(640)); rig.worker.drain()
        assertTrue(rig.inputs.isEmpty())
        rig.audio().received(ByteArray(640)); rig.worker.drain()
        assertEquals(listOf(rig.second), rig.inputs)
        assertEquals(1, rig.backend.stops)
    }
    @Test fun interruptionRevokesAndResumeOnlyReportsReady() {
        val rig = Rig(); rig.ready(); rig.transport.start(rig.first); rig.worker.drain()
        val old = rig.audio()
        rig.backend.events!!.interrupted()
        assertEquals(PcmOffer.STALE_EPOCH, rig.ingress.offer(rig.first, ByteArray(640)))
        rig.worker.drain()
        assertEquals(1, rig.interruptions)
        rig.backend.events!!.ready(); rig.worker.drain()
        assertNull(rig.transport.state.epoch)
        assertTrue(rig.transport.state.ready)
        assertEquals(1, rig.backend.starts)
        old.received(ByteArray(640)); rig.worker.drain()
        assertTrue(rig.inputs.isEmpty())
        rig.transport.start(rig.second); rig.worker.drain()
        rig.audio().received(ByteArray(640)); rig.worker.drain()
        assertEquals(listOf(rig.second), rig.inputs)
    }
    @Test fun closeFencesQueuedInputAndIsIdempotent() {
        val rig = Rig(); rig.ready(); rig.transport.start(rig.first); rig.worker.drain()
        rig.audio().received(ByteArray(640))
        rig.transport.close(); rig.transport.close(); rig.worker.drain()
        assertTrue(rig.inputs.isEmpty())
        assertEquals(1, rig.backend.closes)
        assertNull(rig.transport.state.epoch)
    }
    @Test fun closeCancelsPendingAuthorizationBeforeWorkerCleanup() {
        val rig = Rig()
        rig.transport.close()
        assertFalse("Pending token grant revoked immediately", rig.backend.authorizing)
        assertEquals(0, rig.backend.closes)
        rig.worker.drain()
        assertEquals(1, rig.backend.closes)
    }
    @Test fun cancelledCommandRejectsLateResponse() {
        val rig = Rig(); rig.ready()
        val results = mutableListOf<String?>()
        val handle = rig.transport.send("rk_custom_client", "{}", results::add)
        rig.worker.drain(); handle.cancel()
        assertNotNull("Ready command must reach SDK", rig.backend.reply)
        rig.backend.reply!!.invoke("late"); rig.worker.drain()
        assertTrue(results.isEmpty())
        assertEquals(1, rig.backend.cancelled)
    }
    @Test fun customCommandUsesDocumentedOutboundAndReplyChannels() {
        val rig = Rig(); rig.ready()
        val replies = mutableListOf<String?>()
        rig.transport.send("rk_custom_client", "{\"seq\":1}", RokidReplyCorrelation { it == "one" }, replies::add)
        rig.worker.drain()
        assertEquals(listOf(RokidCommandRoute("rk_custom_client", "rk_custom_key")), rig.backend.routes)
        rig.backend.deliver("rk_custom_key", "one"); rig.worker.drain()
        assertEquals(listOf("one"), replies)
    }
    @Test fun repeatedCommandsReuseVendorChannelsOnlyWithCallerCorrelation() {
        val rig = Rig(); rig.ready()
        val replies = mutableListOf<String?>()
        rig.transport.send("rk_custom_client", "{\"seq\":1}", RokidReplyCorrelation { it == "one" }, replies::add)
        rig.transport.send("rk_custom_client", "{\"seq\":2}", RokidReplyCorrelation { it == "two" }, replies::add)
        rig.worker.drain()
        assertEquals(2, rig.backend.routes.size)
        rig.backend.deliver("rk_custom_key", "two"); rig.worker.drain()
        rig.backend.deliver("rk_custom_key", "one"); rig.worker.drain()
        assertEquals(listOf("two", "one"), replies)
    }
    @Test fun lateSdkReplyAfterCloseIsInertAndDoesNotThrow() {
        val rig = Rig(); rig.ready()
        rig.transport.send("rk_custom_client", "{}", RokidReplyCorrelation { true }, {})
        rig.worker.drain()
        rig.transport.close()
        val thrown = runCatching { rig.backend.deliver("rk_custom_key", "late"); rig.worker.drain() }.exceptionOrNull()
        assertNull(thrown)
    }
    @Test fun readyQueuedBeforeInterruptionCannotRestoreReady() {
        val rig = Rig()
        rig.backend.events!!.ready()
        rig.backend.events!!.interrupted()
        rig.worker.drain()
        assertFalse("Revocation fences prior queued ready", rig.transport.state.ready)
        assertEquals(0, rig.readyNotifications)
    }
    @Test fun commandReplyFromBeforeInterruptionCannotAppearAfterResume() {
        val rig = Rig(); rig.ready()
        val replies = mutableListOf<String?>()
        rig.transport.send("rk_custom_client", "{}", replies::add); rig.worker.drain()
        rig.backend.events!!.interrupted(); rig.worker.drain()
        rig.ready()
        rig.backend.reply!!.invoke("old"); rig.worker.drain()
        assertTrue("Lifecycle revokes old command listener", replies.isEmpty())
    }
    @Test fun sdkStartExceptionRevokesEpochAndReportsInterruption() {
        val rig = Rig(); rig.ready(); rig.backend.throwOnStart = true
        rig.transport.start(rig.first)
        val thrown = runCatching { rig.worker.drain() }.exceptionOrNull()
        assertNull("SDK exception must become transport interruption", thrown)
        assertNull(rig.transport.state.epoch)
        assertEquals("AUDIO_STREAM_FAILED", rig.transport.state.errorCode)
        assertEquals(1, rig.interruptions)
    }
    /**
     * The receiver's own outcome used to be visible only inside this class: the composition could not tell an
     * accepted chunk from an overflow, so `INGRESS_OVERFLOW` had no way to become a report. The new defaulted sink
     * is that seam, and it must forward exactly what the ingress decided - including `STALE_EPOCH`, which this
     * class must not reinterpret.
     */
    @Test fun theIngressOfferOutcomeIsForwardedToTheAdditiveSink() {
        val rig = Rig(VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "synthetic-test"))
        rig.ready(); rig.transport.start(rig.first); rig.worker.drain()

        rig.audio().received(byteArrayOf(1, 2, 3, 4)); rig.worker.drain()
        assertEquals(listOf(PcmOffer.ACCEPTED), rig.offers)

        // A closed ingress answers STALE_EPOCH; the transport must report the ingress's own decision unchanged.
        rig.ingress.close()
        rig.audio().received(byteArrayOf(5, 6, 7, 8)); rig.worker.drain()
        assertEquals(listOf(PcmOffer.ACCEPTED, PcmOffer.STALE_EPOCH), rig.offers)
    }

    @Test fun audioOfferedBeforeTheEpochIsRevokedIsNotReportedAsAnOffer() {
        val rig = Rig(VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "synthetic-test"))
        rig.ready(); rig.transport.start(rig.first); rig.worker.drain()
        val old = rig.audio()

        rig.transport.stop(rig.first); old.received(ByteArray(640)); rig.worker.drain()

        assertTrue("a callback from a revoked epoch must not reach the ingress at all", rig.offers.isEmpty())
    }

    @Test fun verifiedIngressCopiesSdkArrayAndPreservesCallbackTimestamp() {
        val rig = Rig(VerifiedPcm(AudioFormat.Pcm(16000, 1, 16), PcmEncoding.SIGNED_LE, "synthetic-test"))
        rig.ready(); rig.transport.start(rig.first); rig.worker.drain()
        val buffer = byteArrayOf(1, 2, 3, 4)
        rig.audio().received(buffer)
        buffer.fill(0)
        val chunk = rig.ingress.poll()
        assertNotNull(chunk)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), chunk!!.bytes)
        assertEquals(10L, chunk.receivedNanos)
        assertEquals(0L, chunk.sequence)
    }
    private class Rig(format: VerifiedPcm? = null) {
        val worker = QueueWorker()
        val backend = Backend()
        val ingress = PcmIngress(object : MonotonicClock { override fun nowNanos() = 10L })
        val inputs = mutableListOf<InputEpoch>()
        val offers = mutableListOf<PcmOffer>()
        var interruptions = 0
        var readyNotifications = 0
        val first = InputEpoch(SessionId("s"), Generation(1), 1)
        val second = InputEpoch(SessionId("s"), Generation(2), 2)
        val transport = RokidSessionTransport(ingress, { readyNotifications++ }, inputs::add, { interruptions++ }, backend, worker,
            { format }, onOffer = offers::add)
        init { transport.connect(Activity()) }
        fun ready() { backend.events!!.ready(); worker.drain() }
        fun audio(): RokidBackend.Audio {
            assertNotNull("Ready start must attach epoch callback", backend.audio)
            return backend.audio!!
        }
    }
    private class QueueWorker : Executor {
        private val tasks = ArrayDeque<Runnable>()
        override fun execute(command: Runnable) { tasks.add(command) }
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    private class Backend : RokidBackend {
        var events: RokidBackend.Events? = null
        var audio: RokidBackend.Audio? = null
        var starts = 0; var stops = 0; var closes = 0; var cancelled = 0
        val routes = mutableListOf<RokidCommandRoute>()
        val pending = mutableListOf<Triple<RokidCommandRoute, RokidReplyCorrelation?, (String?) -> Unit>>()
        var reply: ((String?) -> Unit)? = null
        var throwOnStart = false
        var authorizing = false
        override fun connect(activity: Activity, events: RokidBackend.Events) { this.events = events; authorizing = true }
        override fun cancelAuthorization() { authorizing = false }
        override fun start(audio: RokidBackend.Audio): Boolean {
            if (throwOnStart) throw IllegalStateException("synthetic SDK failure")
            this.audio = audio; starts++; return true
        }
        override fun stop(audio: RokidBackend.Audio) { stops++ }
        override fun send(route: RokidCommandRoute, json: String, correlation: RokidReplyCorrelation?,
            listener: (String?) -> Unit): CancellationHandle {
            routes += route
            pending += Triple(route, correlation, listener)
            reply = listener
            return object : CancellationHandle { override fun cancel() { cancelled++ } }
        }
        fun deliver(channel: String, payload: String?) {
            val match = pending.firstOrNull { it.first.replyChannel == channel &&
                (it.second == null || payload != null && it.second!!.matches(payload)) }
            if (match != null) { pending.remove(match); match.third(payload) }
        }
        override fun close() { closes++ }
    }
}
