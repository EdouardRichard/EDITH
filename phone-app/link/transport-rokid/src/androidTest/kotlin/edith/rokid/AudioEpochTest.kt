package com.sheen.rokid.transport

import android.app.Activity
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.audio.PcmOffer
import com.sheen.rokid.contracts.*
import com.sheen.rokid.host.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.testsupport.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.Executor

@RunWith(AndroidJUnit4::class)
class AudioEpochTest {
    @Test fun realHostRejectsOldEpochAndOnlyExplicitResumeStartsNewInput() {
        val clock = FakeClock()
        val ingress = PcmIngress(clock)
        val backend = Backend()
        lateinit var transport: RokidSessionTransport
        val environment = object : HostEnvironment {
            override val clock = clock
            override val input: InputPort get() = transport
            override val output = RecordingHintDevice()
            override val availableCapabilities = setOf(Capability.Audio)
            override fun services(scope: ResourceScope, granted: Set<Capability>) =
                ScopedHostServices(scope, granted, mapOf(Capability.Audio to object : AudioSubscription {
                    override fun listen(listener: (AudioSegment) -> Unit) = FakeCancellation()
                }), FakeTaskScope())
        }
        val host = HostRuntime(1, environment)
        transport = RokidSessionTransport(ingress, host::markSceneReady, host::onAudioReceived,
            host::interrupt, backend, Executor { it.run() })
        val plugin = object : PluginLifecycle {
            override val descriptor = PluginDescriptor("probe", "1", 1, "probe", listOf(Capability.Audio), ConfigSchema(1))
            override fun activate(context: PluginContext) = Unit
            override fun handle(event: HostEvent) = Unit
            override fun deactivate(reason: DeactivationReason) = Unit
        }
        try {
            host.register(plugin); host.startSession(SessionId("instrumented"))
            transport.connect(Activity())
            assertEquals(ActivationResult.SessionNotReady, host.activate("probe"))
            assertEquals(0, backend.starts)
            backend.events!!.ready()
            assertEquals(ActivationResult.Activated, host.activate("probe"))
            assertEquals(SessionState.Ready, host.currentState)
            val old = backend.audio!!
            val oldEpoch = transport.state.epoch!!
            old.received(ByteArray(640))
            assertEquals(SessionState.Listening, host.currentState)
            assertEquals(AudioFormat.Unknown, transport.state.format)
            assertNull(ingress.poll())
            host.pause()
            old.received(ByteArray(640))
            assertEquals(PcmOffer.STALE_EPOCH, ingress.offer(oldEpoch, ByteArray(640)))
            assertEquals(SessionState.Paused, host.currentState)
            host.resume()
            assertEquals(SessionState.Ready, host.currentState)
            old.received(ByteArray(640))
            assertEquals(SessionState.Ready, host.currentState)
            backend.audio!!.received(ByteArray(640))
            assertEquals(SessionState.Listening, host.currentState)
            backend.events!!.interrupted()
            assertEquals(SessionState.Interrupted, host.currentState)
            backend.events!!.ready()
            assertEquals(SessionState.Ready, host.currentState)
            val count = backend.starts
            assertNull(transport.state.epoch)
            assertEquals(count, backend.starts)
            host.resume()
            assertEquals(count + 1, backend.starts)
            assertEquals(SessionState.Ready, host.currentState)
            backend.audio!!.received(ByteArray(640))
            assertEquals(SessionState.Listening, host.currentState)
        } finally { host.close(); transport.close() }
    }
    private class Backend : RokidBackend {
        var events: RokidBackend.Events? = null
        var audio: RokidBackend.Audio? = null
        var starts = 0
        override fun connect(activity: Activity, events: RokidBackend.Events) { this.events = events }
        override fun start(audio: RokidBackend.Audio): Boolean { this.audio = audio; starts++; return true }
        override fun stop(audio: RokidBackend.Audio) = Unit
        override fun send(route: RokidCommandRoute, json: String, correlation: RokidReplyCorrelation?,
            listener: (String?) -> Unit) = FakeCancellation()
        override fun close() = Unit
    }
}
