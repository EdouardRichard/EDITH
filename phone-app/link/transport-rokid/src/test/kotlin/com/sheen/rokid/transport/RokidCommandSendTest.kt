package com.sheen.rokid.transport

import android.app.Activity
import com.rokid.cxr.session.AuthResult
import com.sheen.rokid.audio.PcmIngress
import com.sheen.rokid.plugin.MonotonicClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executor

/**
 * T18 outbound seam: a hint command must reach the SDK as a fire-and-forget custom command.
 *
 * The existing `send` path registers a pending reply correlation, and an **uncorrelated** send claims the reply
 * channel for the connection's lifetime. A hint cannot use that path: the first SHOW would consume the channel and
 * every later hint would be refused. Acks are correlated by their own sequence inside `RokidHintDevice`, so the
 * outbound side needs a seam that sends and registers nothing.
 *
 * `sendCommand` reports what its own layer can honestly know: the backend returns the SDK's synchronous result, while
 * the transport reports only that a live session accepted the command for delivery - the SDK call happens on the
 * transport worker, so its result is not awaited. A caller must not read the transport's `true` as "the glasses
 * received it"; that is what the ack (or the hint's expiry) decides.
 */
class RokidCommandSendTest {
    @Test
    fun aFireAndForgetCommandReachesTheSdkAndClaimsNoReplyChannel() {
        val rig = CxrBackendRig()
        rig.authorize()

        assertTrue(rig.backend.sendCommand(RokidCommandRoute.CustomClient, "{\"command\":\"PING\"}"))

        assertEquals(listOf(RokidCommandRoute.CustomClient.outbound to "{\"command\":\"PING\"}"), rig.sentJson)
        // The reply channel must be untouched, so an ordinary uncorrelated request is still registrable afterwards.
        var completed: String? = "not-registered"
        rig.backend.send(RokidCommandRoute.CustomClient, "{\"id\":\"x\"}", null) { completed = it }
        assertEquals("an uncorrelated send must still be registrable", "not-registered", completed)
        rig.reply("rk_custom_key", "{\"id\":\"x\"}")
        assertEquals("{\"id\":\"x\"}", completed)
    }

    @Test
    fun repeatedHintsAreAllDeliveredRatherThanRefusedAfterTheFirst() {
        val rig = CxrBackendRig()
        rig.authorize()

        repeat(3) { index -> assertTrue(rig.backend.sendCommand(RokidCommandRoute.CustomClient, "{\"n\":$index}")) }

        assertEquals(listOf("{\"n\":0}", "{\"n\":1}", "{\"n\":2}"), rig.sentJson.map { it.second })
    }

    @Test
    fun aSdkRefusalIsReportedToTheBackendCaller() {
        val rig = CxrBackendRig()
        rig.authorize()
        rig.sendSuccess = false

        assertFalse(rig.backend.sendCommand(RokidCommandRoute.CustomClient, "{}"))
    }

    @Test
    fun theTransportRefusesCommandsUntilTheSessionIsReadyAndAfterClose() {
        val rig = CxrBackendRig()
        val transport = RokidSessionTransport(PcmIngress(clock), {}, {}, {}, rig.backend, InlineWorker())
        assertFalse("no session yet", transport.sendCommand("{\"command\":\"HELLO\"}"))

        transport.connect(Activity())
        rig.ui.removeFirst().invoke()
        rig.auth!!.invoke(AuthResult(true, "memory-only-test-token", null, null))
        rig.ready()

        assertTrue(transport.sendCommand("{\"command\":\"HELLO\"}"))
        assertEquals(listOf("{\"command\":\"HELLO\"}"), rig.sentJson.map { it.second })

        transport.close()
        assertFalse("a closed transport must refuse", transport.sendCommand("{}"))
    }

    private val clock = object : MonotonicClock { override fun nowNanos() = 0L }

    private class InlineWorker : Executor {
        override fun execute(command: Runnable) = command.run()
    }
}
