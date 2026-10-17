package com.sheen.rokid.transport

import android.app.Activity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `connect` is reached from a Connect button, so it must tolerate an impatient second tap.
 *
 * A real crash was observed on the device: `MainActivity` called `connectCxr` from a Compose tap, the transport
 * already had a connection, and `check(!closed && connection == 0L)` threw
 * `IllegalStateException: TRANSPORT_ALREADY_CONNECTED_OR_CLOSED` on the main thread, killing the app. The user's intent
 * on a second tap is "make sure it is connected", which is already true.
 */
class TransportConnectIdempotencyTest {
    /** Records connect attempts without touching the vendor SDK. */
    private class Backend : RokidBackend {
        var connects = 0
        var closed = 0
        var events: RokidBackend.Events? = null
        override fun connect(activity: Activity, events: RokidBackend.Events) { connects++; this.events = events }
        override fun start(audio: RokidBackend.Audio) = true
        override fun stop(audio: RokidBackend.Audio) = Unit
        override fun send(route: RokidCommandRoute, json: String, correlation: RokidReplyCorrelation?,
            listener: (String?) -> Unit) = object : com.sheen.rokid.plugin.CancellationHandle {
            override fun cancel() = Unit
        }
        override fun close() { closed++ }
    }

    private class DirectExecutor : java.util.concurrent.Executor {
        override fun execute(command: Runnable) = command.run()
    }

    private fun transport(backend: Backend): RokidSessionTransport {
        val clock = object : com.sheen.rokid.plugin.MonotonicClock { override fun nowNanos() = 0L }
        return RokidSessionTransport(
            ingress = com.sheen.rokid.audio.PcmIngress(clock),
            onSceneReady = {},
            onInput = {},
            onInterrupted = {},
            backend = backend,
            worker = DirectExecutor(),
        )
    }

    @Test fun aSecondConnectIsANoOpRatherThanACrash() {
        val backend = Backend()
        val subject = transport(backend)
        subject.connect(Activity())
        // This is the exact call that crashed the app on the device.
        subject.connect(Activity())
        assertEquals("the backend must be asked to connect only once", 1, backend.connects)
    }

    @Test fun manyTapsStillConnectOnce() {
        val backend = Backend()
        val subject = transport(backend)
        repeat(5) { subject.connect(Activity()) }
        assertEquals(1, backend.connects)
    }

    /**
     * A session that died must be connectable again.
     *
     * Observed on the device (2026-10-08): the AI App ended the session underneath us (`Started -> Terminating -> Idle`
     * after the glasses app was reinstalled), every later send was refused with `ready=false`, and because `connected`
     * stayed true the Connect button became a permanent no-op - the user's only recovery was restarting the App.
     */
    @Test fun aSessionThatDiedCanBeConnectedAgain() {
        val backend = Backend()
        val subject = transport(backend)
        subject.connect(Activity())
        backend.events!!.interrupted()

        subject.connect(Activity())

        assertEquals("a dead session must not leave the Connect button a no-op", 2, backend.connects)
    }

    @Test fun connectingAfterCloseIsStillRefusedBecauseThatIsRealMisuse() {        val backend = Backend()
        val subject = transport(backend)
        subject.connect(Activity())
        subject.close()
        // A closed transport is a genuine misuse, not an impatient tap, so it keeps refusing - and loudly.
        val thrown = assertThrows(IllegalStateException::class.java) { subject.connect(Activity()) }
        assertTrue(thrown.message!!.contains("TRANSPORT_ALREADY_CONNECTED_OR_CLOSED"))
    }

    @Test fun aClosedTransportIsClosedOnlyOnceEvenWithRepeatedCalls() {
        val backend = Backend()
        val subject = transport(backend)
        subject.connect(Activity())
        subject.close()
        subject.close()
        assertTrue("closing twice must not crash", backend.closed >= 1)
    }
}
