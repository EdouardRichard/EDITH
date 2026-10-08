package edith.rokid

import android.app.Activity
import com.rokid.cxr.session.*
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicReference

class CxrBackendTest {
    @Test fun documentedReplyChannelCompletesCorrelatedOutboundCommand() {
        val rig = CxrBackendRig(); rig.authorize()
        val replies = mutableListOf<String?>()
        rig.backend.send(RokidCommandRoute.CustomClient, "{\"seq\":1}", RokidReplyCorrelation { it == "one" }, replies::add)
        assertEquals(listOf("rk_custom_client"), rig.sends)
        rig.reply("rk_custom_client", "one")
        assertTrue(replies.isEmpty())
        rig.reply("rk_custom_key", "one")
        assertEquals(listOf("one"), replies)
    }
    @Test fun repeatedChannelIgnoresOldReplyAndReleasesCompletedCancelledAndFailedRequests() {
        val rig = CxrBackendRig(); rig.authorize()
        val replies = mutableListOf<String?>()
        fun send(id: String) = rig.backend.send(RokidCommandRoute.CustomClient, id, RokidReplyCorrelation { it == id }, replies::add)
        send("one"); rig.reply("rk_custom_key", "one")
        val cancelled = send("two"); cancelled.cancel()
        send("three")
        rig.reply("rk_custom_key", "one"); rig.reply("rk_custom_key", "two")
        assertEquals(listOf("one"), replies)
        rig.reply("rk_custom_key", "three")
        assertEquals(listOf("one", "three"), replies)
        rig.sendSuccess = false; send("four")
        assertEquals(listOf("one", "three", null), replies)
        rig.sendSuccess = true; send("five"); rig.reply("rk_custom_key", "five")
        assertEquals(listOf("one", "three", null, "five"), replies)
        assertEquals(5, rig.sends.size)
    }
    @Test fun uncorrelatedLegacyRouteIsSingleUseAndCannotStealLaterCorrelatedReply() {
        val rig = CxrBackendRig(); rig.authorize()
        val replies = mutableListOf<String?>()
        rig.backend.send(RokidCommandRoute.CustomClient, "old", null, replies::add)
        rig.backend.send(RokidCommandRoute.CustomClient, "again", null, replies::add)
        assertEquals(1, rig.sends.size)
        assertEquals(listOf<String?>(null), replies)
        rig.reply("rk_custom_key", "old")
        rig.backend.send(RokidCommandRoute.CustomClient, "new", RokidReplyCorrelation { it == "new" }, replies::add)
        rig.reply("rk_custom_key", "old")
        assertEquals(listOf(null, "old"), replies)
        rig.reply("rk_custom_key", "new")
        assertEquals(listOf(null, "old", "new"), replies)
    }
    @Test fun retiredSdkCallbackIsInertAfterCloseAndExecutorRejection() {
        val rig = CxrBackendRig(); rig.authorize()
        // The active inbound layer is captured the way the SDK holds it, then the connection is closed underneath it.
        val retired = rig.activeInboundCallback()
        rig.backend.close()
        rig.worker.closed = true
        val thrown = runCatching { retired("rk_custom_key", "late".toByteArray()) }.exceptionOrNull()
        assertNull("Late vendor callback may not throw on callback thread", thrown)
    }
    @Test fun cancellationDuringSessionCreationPreventsConnectAfterRevocation() {
        val rig = CxrBackendRig()
        rig.duringCreate = { rig.backend.cancelAuthorization() }
        rig.authorize()
        assertEquals("Accepted create must be retired without connect", 0, rig.connects)
        assertEquals(1, rig.closes)
    }
    @Test fun queuedAuthorizationDispatchAfterCloseNeverRequestsPermission() {
        val rig = CxrBackendRig()
        rig.backend.connect(Activity(), Events())
        rig.backend.cancelAuthorization()
        rig.ui.removeFirst().invoke()
        assertEquals(0, rig.authorizationRequests)
    }
    @Test fun closeOrderedWithAuthorizationDispatchCannotReturnBeforeDispatchCompletes() {
        val rig = CxrBackendRig()
        val inside = CountDownLatch(1); val release = CountDownLatch(1); val revoked = CountDownLatch(1)
        rig.duringAuthorization = {
            inside.countDown()
            check(release.await(2, TimeUnit.SECONDS))
        }
        rig.backend.connect(Activity(), Events())
        val dispatch = rig.ui.removeFirst()
        val callbackFailure = AtomicReference<Throwable?>()
        val dispatchThread = Thread { try { dispatch() } catch (error: Throwable) { callbackFailure.set(error) } }
        dispatchThread.start()
        assertTrue(inside.await(2, TimeUnit.SECONDS))
        val cancelThread = Thread { rig.backend.cancelAuthorization(); revoked.countDown() }
        cancelThread.start()
        assertFalse("Cancellation waits for accepted dispatch boundary", revoked.await(50, TimeUnit.MILLISECONDS))
        release.countDown()
        dispatchThread.join(2000); cancelThread.join(2000)
        assertNull(callbackFailure.get())
        assertEquals(0L, revoked.count)
    }
    private class Events : RokidBackend.Events {
        override fun ready() = Unit
        override fun interrupted() = Unit
        override fun error(code: String) = Unit
    }
    private class Worker : Executor {
        var closed = false
        override fun execute(command: Runnable) {
            if (closed) throw RejectedExecutionException("synthetic closed executor")
            command.run()
        }
    }
}