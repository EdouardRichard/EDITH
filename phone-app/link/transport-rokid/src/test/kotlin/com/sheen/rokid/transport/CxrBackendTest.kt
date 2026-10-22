package com.sheen.rokid.transport

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
    @Test fun anActivityResultIsClaimedOnlyWhileAPromptIsPending() {
        // Review I-3: the App may only report an authorization answer for a result the SDK actually asked for. The
        // proof is the callback `connect` armed (it arms it as the connection starts asking and clears it the moment it
        // is answered) - not a guessed request code - so the answer is good exactly once per real prompt.
        val rig = CxrBackendRig()
        assertNull("nothing asked, so an unrelated result is not an answer", rig.backend.onAuthorizationResult(Activity.RESULT_OK, null))

        rig.backend.connect(Activity(), Events())
        rig.ui.removeFirst().invoke()
        assertEquals(1, rig.authorizationRequests)

        assertEquals(
            "the prompt's own answer must be claimed, with the verdict the SDK's parse produced",
            CxrAuthorizationOutcome.CONFIRMED, rig.backend.onAuthorizationResult(Activity.RESULT_OK, null),
        )
        assertNull("the same result cannot answer twice", rig.backend.onAuthorizationResult(Activity.RESULT_OK, null))
    }

    @Test fun anActivityResultAfterRevocationIsNotClaimed() {
        val rig = CxrBackendRig()
        rig.backend.connect(Activity(), Events())
        rig.ui.removeFirst().invoke()
        rig.backend.cancelAuthorization()

        assertNull("a revoked attempt has no pending prompt", rig.backend.onAuthorizationResult(Activity.RESULT_OK, null))
    }

    @Test fun aResultOkTheSdkParsedAsAFailureIsReportedAsAnAnomaly() {
        // Review Minor 6: the page's verdict used to come from the bare result code, so a `RESULT_OK` whose `AuthResult`
        // is a failure still read "眼镜授权确认已完成". The vendor's parser does exactly that for an `auth_result` extra
        // it does not recognise (javap of the pinned `client-l-1.1.2.aar`: `AuthorizationHelper.parseAuthorizationResult`
        // -> `AuthResult$AuthFail`), so the verdict has to come from the parsed answer.
        val rig = CxrBackendRig()
        rig.parsedAuthorizationSuccess = false
        rig.backend.connect(Activity(), Events())
        rig.ui.removeFirst().invoke()

        assertEquals(
            "a parsed failure is not a confirmation, however the activity result was coded",
            CxrAuthorizationOutcome.UNEXPECTED, rig.backend.onAuthorizationResult(Activity.RESULT_OK, null),
        )
    }

    @Test fun aResultOkWithoutATokenIsNotAConfirmationEither() {
        // The same rule the authorization callback applies before it creates a session (`!result.isSuccess ||
        // token.isNullOrBlank()` => AUTHORIZATION_FAILED): a parsed "success" that carries no usable token cannot
        // complete a session, so the page must not be told the authorization was confirmed.
        val rig = CxrBackendRig()
        rig.parsedAuthorizationToken = "   "
        rig.backend.connect(Activity(), Events())
        rig.ui.removeFirst().invoke()

        assertEquals(
            CxrAuthorizationOutcome.UNEXPECTED, rig.backend.onAuthorizationResult(Activity.RESULT_OK, null),
        )
    }

    @Test fun aCancelledPromptIsReportedAsCancelledRatherThanAsAnAnomaly() {
        // A decline is a legitimate answer, not a fault, and it is the code (not the intent) that carries it.
        val rig = CxrBackendRig()
        rig.backend.connect(Activity(), Events())
        rig.ui.removeFirst().invoke()

        assertEquals(
            CxrAuthorizationOutcome.CANCELLED, rig.backend.onAuthorizationResult(Activity.RESULT_CANCELED, null),
        )
    }

    @Test fun aParsedFailureIsNotTheCancelVerdict() {
        // The complement of the test above, on a fresh connection (the armed callback is good for exactly one answer):
        // only the cancel code earns `CANCELLED`, so a parsed failure that arrives as `RESULT_OK` cannot hide behind it.
        val rig = CxrBackendRig()
        rig.parsedAuthorizationSuccess = false
        rig.backend.connect(Activity(), Events())
        rig.ui.removeFirst().invoke()

        assertEquals(
            CxrAuthorizationOutcome.UNEXPECTED, rig.backend.onAuthorizationResult(Activity.RESULT_OK, null),
        )
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