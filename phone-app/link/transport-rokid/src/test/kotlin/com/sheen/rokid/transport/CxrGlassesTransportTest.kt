package com.sheen.rokid.transport

import android.app.Activity
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The transport's half of the glasses-app seam: it must forward the request to the backend that owns the SDK session,
 * and it must not start an app or hold a listener after it has been closed.
 *
 * The transport adds no policy of its own. Whether a client is reachable, and which activity to start, belongs to the
 * backend; this class only proves the delegation is not silently dropped.
 */
class CxrGlassesTransportTest {
    private class Backend : RokidBackend {
        var appStartCalls = 0
        var appStartResult = true
        var lastResult: ((Boolean) -> Unit)? = null
        var lastListener: GlassesEvents? = null
        var listenerHandleClosed = 0
        var removals = 0
        val closed = mutableListOf<Int>()

        override fun connect(activity: Activity, events: RokidBackend.Events) = Unit
        override fun start(audio: RokidBackend.Audio) = true
        override fun stop(audio: RokidBackend.Audio) = Unit
        override fun send(route: RokidCommandRoute, json: String, correlation: RokidReplyCorrelation?,
            listener: (String?) -> Unit): CancellationHandle = object : CancellationHandle {
            override fun cancel() = Unit
        }
        override fun openGlassesApp(onResult: (Boolean) -> Unit): Boolean {
            appStartCalls++
            lastResult = onResult
            return appStartResult
        }
        override fun addGlassesEventListener(listener: GlassesEvents): AutoCloseable? {
            lastListener = listener
            return AutoCloseable { listenerHandleClosed++ }
        }
        override fun close() { closed += 1 }
    }

    private class DirectExecutor : java.util.concurrent.Executor {
        override fun execute(command: Runnable) = command.run()
    }

    private fun transport(backend: RokidBackend): RokidSessionTransport {
        val clock = object : MonotonicClock { override fun nowNanos() = 0L }
        return RokidSessionTransport(PcmIngress(clock), {}, {}, {}, backend, DirectExecutor())
    }

    @Test
    fun appStartIsForwardedToTheBackendAndTheCallbackIsPassedThrough() {
        val backend = Backend()
        val subject = transport(backend)
        val results = mutableListOf<Boolean>()

        assertTrue(subject.openGlassesApp { results += it })
        assertEquals(1, backend.appStartCalls)
        backend.lastResult!!.invoke(false)

        assertEquals(listOf(false), results)
    }

    @Test
    fun aBackendWithNoReachableClientKeepsItsFalseAnswer() {
        val backend = Backend().apply { appStartResult = false }
        val subject = transport(backend)

        assertFalse(subject.openGlassesApp { })
        assertEquals(1, backend.appStartCalls)
    }

    @Test
    fun glassesEventListenersAreForwardedAndTheirHandlesRemainTheOwners() {
        val backend = Backend()
        val subject = transport(backend)
        val sink = object : GlassesEvents {
            override fun appResumed() = Unit
            override fun appPaused() = Unit
            override fun wearingChanged(wearing: Boolean) = Unit
            override fun screenOn() = Unit
            override fun screenOff() = Unit
        }

        val handle = subject.addGlassesEventListener(sink)

        assertTrue(backend.lastListener === sink)
        handle!!.close()
        assertEquals(1, backend.listenerHandleClosed)
    }

    @Test
    fun aClosedTransportRefusesAnAppStartAndHoldsNoListener() {
        val backend = Backend()
        val subject = transport(backend)
        subject.close()

        assertFalse(subject.openGlassesApp { })
        assertNull(subject.addGlassesEventListener(object : GlassesEvents {
            override fun appResumed() = Unit
            override fun appPaused() = Unit
            override fun wearingChanged(wearing: Boolean) = Unit
            override fun screenOn() = Unit
            override fun screenOff() = Unit
        }))
        assertEquals("a closed transport must not reach the SDK", 0, backend.appStartCalls)
        assertNull(backend.lastListener)
    }
}
