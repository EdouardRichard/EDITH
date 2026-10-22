package com.sheen.rokid.transport

import com.rokid.cxr.session.CloseReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phone-side `appStart`: the only way to bring the glasses scene back after its process died.
 *
 * The shape is the official one (verified with `javap` on the pinned `client-l-1.1.2.aar`):
 * `ExternalAppClient.appStart(String, IGlassAppCbk)` and the outcome arrives asynchronously through
 * `IGlassAppCbk.onOpenAppResult(boolean)`. The synchronous boolean answers a different question: was there a live
 * client to ask? A scene that refuses to open is still an accepted call whose answer is `false`.
 */
class CxrGlassesAppTest {
    private fun rig(): CxrBackendRig = CxrBackendRig().also { it.authorize() }

    @Test
    fun appStartUsesTheConfiguredActivityAndReportsTheSdkResult() {
        val rig = rig()
        val starter = rig.glassAppStarter as RecordingGlassAppStarter
        val results = mutableListOf<Boolean>()

        assertTrue(rig.backend.openGlassesApp { results += it })

        assertEquals("the activity name must be the session's configured glasses activity",
            listOf("com.sheen.rokid.glasses.Main"), starter.activityNames)
        assertEquals(listOf(true), results)
    }

    @Test
    fun aFailedSceneOpenIsReportedAsFalseRatherThanAsADeliveryFailure() {
        val rig = rig()
        val starter = rig.glassAppStarter as RecordingGlassAppStarter
        starter.result = false
        val results = mutableListOf<Boolean>()

        assertTrue("the SDK call itself was accepted", rig.backend.openGlassesApp { results += it })

        assertEquals("the scene's refusal is the callback's fact, not the call's", listOf(false), results)
    }

    @Test
    fun noReachableClientMeansFalseAndNoInventedCallbackResult() {
        val rig = rig()
        rig.glassAppStarter = null
        var callbacks = 0

        assertFalse(rig.backend.openGlassesApp { callbacks++ })

        assertEquals("a callback must not be invented when nothing was asked", 0, callbacks)
    }

    @Test
    fun anSdkCallThatThrowsIsReportedAsFalseAndFabricatesNoResult() {
        val rig = rig()
        val starter = rig.glassAppStarter as RecordingGlassAppStarter
        starter.throwOnStart = true
        var callbacks = 0

        assertFalse(rig.backend.openGlassesApp { callbacks++ })

        assertEquals("a throw proves no result exists, so none may be reported", 0, callbacks)
    }

    @Test
    fun theSdkResultIsDeliveredOnTheBackendWorkerNotOnTheCallersThread() {
        val rig = rig()
        rig.worker.inline = false
        val results = mutableListOf<Boolean>()

        assertTrue(rig.backend.openGlassesApp { results += it })

        assertTrue("the SDK callback must be marshalled onto the backend worker: $results", results.isEmpty())
        rig.worker.runPending()
        assertEquals(listOf(true), results)
    }

    @Test
    fun appStartBeforeAnyConnectedSessionIsRefused() {
        val rig = CxrBackendRig()
        val starter = rig.glassAppStarter as RecordingGlassAppStarter

        assertFalse(rig.backend.openGlassesApp { })

        assertEquals(0, starter.starts)
    }

    @Test
    fun appStartAfterCloseIsRefused() {
        val rig = rig()
        val starter = rig.glassAppStarter as RecordingGlassAppStarter
        rig.backend.close()

        assertFalse(rig.backend.openGlassesApp { })

        assertEquals(0, starter.starts)
    }

    @Test
    fun appStartAfterTheSessionReportedClosedIsRefused() {
        // The binding is still current (no reconnect replaced it), but the SDK already reported the session closed. A
        // dead session must not be asked to start a scene, and no late answer from it may be delivered.
        val rig = rig()
        val starter = rig.glassAppStarter as RecordingGlassAppStarter
        requireNotNull(rig.lifecycleCallback).onSessionClosed(CloseReason.USER_CLOSED)

        assertFalse(rig.backend.openGlassesApp { })

        assertEquals(0, starter.starts)
    }

    @Test
    fun anAppStartResultThatArrivesAfterTheSessionClosedIsDropped() {
        // Review I1: the appStart callback is marshalled through the worker, so the session can close between the SDK
        // answer and its delivery. A closed session's answer must not touch the link's handshake state.
        val rig = rig()
        rig.worker.inline = false
        val results = mutableListOf<Boolean>()

        assertTrue(rig.backend.openGlassesApp { results += it })
        requireNotNull(rig.lifecycleCallback).onSessionClosed(CloseReason.USER_CLOSED)
        rig.worker.runPending()

        assertTrue("a closed session's appStart answer must not reach the host: $results", results.isEmpty())
    }
}
