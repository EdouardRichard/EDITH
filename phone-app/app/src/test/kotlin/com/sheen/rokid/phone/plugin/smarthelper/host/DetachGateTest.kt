package com.sheen.rokid.phone.plugin.smarthelper.host

import com.sheen.rokid.contracts.SessionState
import com.sheen.rokid.phone.storage.EncryptedBlobStore
import com.sheen.rokid.phone.ui.profile.TestBlobs
import com.sheen.rokid.phone.withP3Sink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 0.4 Task 5 (`D199`/`D202`): the 「脱离眼镜」 gate - persistence, the stop-before-save rule and the live value.
 *
 * `DetachGate` is the **only** writer of [SmartSettings.glassesDetached]. Its contract is deliberately narrow but
 * has one hard ordering rule (spec §4.1): a switch change while a session is running stops that session **first**;
 * it never auto-restarts, and a refused stop/save is a visible result rather than a silent "saved". The stored
 * 「主动辅助」 value is never touched - detaching suppresses it only *effectively* (`D202`).
 */
class DetachGateTest {

    /**
     * Records blob operations so the stop-before-save order is observable, and can fail reads/writes on demand.
     * Delegates the actual bytes to the project's [TestBlobs], so the store's real JSON path is still exercised.
     */
    private class RecordingBlobs : EncryptedBlobStore {
        val delegate = TestBlobs()
        val order = mutableListOf<String>()
        var failWrite = false
        var failRead = false

        override fun read(id: String): ByteArray? {
            order += "read"
            if (failRead) throw IOException("synthetic read failure")
            return delegate.read(id)
        }

        override fun write(id: String, value: ByteArray) {
            order += "write"
            if (failWrite) throw IOException("synthetic disk failure")
            delegate.write(id, value)
        }

        override fun delete(id: String) { delegate.delete(id) }
    }

    private fun seeded(blobs: RecordingBlobs, settings: SmartSettings): SmartSettingsStore =
        SmartSettingsStore(blobs).also {
            it.save(settings)
            blobs.order.clear()
        }

    private val shipped = SmartSettings()

    // ---- load ---------------------------------------------------------------------------------------------------

    @Test
    fun loadReadsThePersistedSwitchWithoutChangingIt() {
        val blobs = RecordingBlobs()
        val store = seeded(blobs, shipped.copy(glassesDetached = true))
        val gate = DetachGate(store)

        assertFalse("the gate starts in glasses mode until it is loaded", gate.enabled.value)
        gate.load()

        assertTrue("the persisted detach must be what the UI and the host see", gate.enabled.value)
        assertTrue("loading is a read: the stored value must be unchanged", store.load().glassesDetached)
        assertTrue("loading must not write", blobs.order.none { it == "write" })
    }

    @Test
    fun aStoreThatCannotBeLoadedKeepsTheGateInItsCurrentMode() {
        // The safe state is the shipped one (glasses mode). An unreadable blob must not be treated as "detached on"
        // just because the reader fell back to defaults - and it must not be written either.
        val blobs = RecordingBlobs()
        val store = seeded(blobs, shipped.copy(glassesDetached = true))
        blobs.failRead = true
        val gate = DetachGate(store)

        gate.load()

        assertFalse(gate.enabled.value)
        assertTrue(blobs.order.none { it == "write" })
    }

    // ---- set: persistence ----------------------------------------------------------------------------------------

    @Test
    fun setPersistsDetachAndKeepsTheStoredProactiveChoice() {
        val blobs = RecordingBlobs()
        val store = seeded(blobs, SmartSettings(
            wakeWords = listOf("伊迪丝", "edith", "小助手"),
            maxLevel = "L2",
            whitelist = listOf("open_app|navigation"),
            cooldownMillis = 4500L,
            proactiveCooldownMillis = 300_000L,
            proactiveSensitivity = "HIGH",
            proactiveEnabled = false,
            speechEnabled = true,
            continueDialogue = false,
            visionEnabled = false,
            noInterception = true,
        ))
        val gate = DetachGate(store)
        gate.load()

        val result = gate.set(true)

        assertTrue("the save must succeed", result.saved)
        assertFalse("an idle composition has no session to stop", result.stoppedSession)
        assertEquals(null, result.error)
        assertTrue(gate.enabled.value)

        val reloaded = store.load()
        assertTrue(reloaded.glassesDetached)
        assertFalse("D202: detaching must not rewrite the stored proactive switch", reloaded.proactiveEnabled)
        assertEquals("every other stored setting must survive the gate's write", "L2", reloaded.maxLevel)
        assertEquals(listOf("伊迪丝", "edith", "小助手"), reloaded.wakeWords)
        assertEquals(listOf("open_app|navigation"), reloaded.whitelist)
        assertEquals(4500L, reloaded.cooldownMillis)
        assertEquals(300_000L, reloaded.proactiveCooldownMillis)
        assertEquals("HIGH", reloaded.proactiveSensitivity)
        assertTrue(reloaded.speechEnabled)
        assertFalse(reloaded.continueDialogue)
        assertFalse(reloaded.visionEnabled)
        assertTrue(reloaded.noInterception)
    }

    @Test
    fun theGateCanCloseDetachAgainAndDoesNotAutoReopenIt() {
        val blobs = RecordingBlobs()
        val store = seeded(blobs, shipped.copy(glassesDetached = true))
        val gate = DetachGate(store).also { it.load() }
        assertTrue(gate.enabled.value)

        val result = gate.set(false)

        assertTrue(result.saved)
        assertFalse(gate.enabled.value)
        assertFalse(store.load().glassesDetached)
    }

    // ---- set: the session ordering rule --------------------------------------------------------------------------

    @Test
    fun setWhileASessionRunsStopsItBeforeSavingAndDoesNotRestartIt() {
        val blobs = RecordingBlobs()
        val store = seeded(blobs, shipped)
        var state = SessionState.Ready
        var stops = 0
        val gate = DetachGate(
            settings = store,
            stopSession = {
                stops++
                blobs.order += "stop"
                state = SessionState.Idle
            },
            sessionState = { state },
        )
        gate.load()
        blobs.order.clear()

        val result = gate.set(true)

        assertTrue(result.saved)
        assertTrue("the result must say the running session was stopped", result.stoppedSession)
        assertEquals("stop must be called exactly once", 1, stops)
        assertEquals("the stop must happen before the blob write",
            listOf("stop", "read", "write"), blobs.order)
        assertTrue(gate.enabled.value)
        assertEquals("no auto-restart: the session stays stopped", SessionState.Idle, state)
    }

    @Test
    fun setWhileASessionRunsStopsTheCompositionThenTheHostingServiceBeforeSaving() {
        // 0.4 Task 9 fix: `root.stop()` only ends the host session; the foreground `SessionService` (and its
        // 「会话已开启」 notification) stayed up for an idle session. The gate's stop path is therefore two calls -
        // composition teardown, then the existing service stop - and both must happen before the blob write, or the
        // mode is persisted while the notification still claims a running session.
        val blobs = RecordingBlobs()
        val store = seeded(blobs, shipped)
        var state = SessionState.Ready
        val gate = DetachGate(
            settings = store,
            stopSession = {
                blobs.order += "stop"
                state = SessionState.Idle
            },
            stopService = { blobs.order += "stopService"; true },
            sessionState = { state },
        )
        gate.load()
        blobs.order.clear()

        val result = gate.set(true)

        assertTrue(result.saved)
        assertTrue(result.stoppedSession)
        assertEquals("the composition stop, then the service stop, then the write",
            listOf("stop", "stopService", "read", "write"), blobs.order)
        assertTrue(gate.enabled.value)
    }

    @Test
    fun setWhileTheCxrSessionIsStillStartingOrFaultedStopsTheCompositionAndServiceBeforeSaving() {
        // `SessionService` can already be foreground before the host reaches Ready: Authorizing, Connecting and
        // BuildingScene all own a live session, and Interrupted/Reconnecting/Error can still have both the session and
        // its notification. A detach save must stop every one of them, not just the states that looked "running".
        val liveStates = listOf(
            SessionState.Authorizing,
            SessionState.Connecting,
            SessionState.BuildingScene,
            SessionState.Interrupted,
            SessionState.Reconnecting,
            SessionState.Error,
        )
        liveStates.forEach { state ->
            val blobs = RecordingBlobs()
            val store = seeded(blobs, shipped)
            var stops = 0
            var serviceStops = 0
            val gate = DetachGate(
                settings = store,
                stopSession = {
                    stops++
                    blobs.order += "stop"
                },
                stopService = {
                    serviceStops++
                    blobs.order += "stopService"
                    true
                },
                sessionState = { state },
            )

            val result = gate.set(true)

            assertTrue("$state: the save must succeed", result.saved)
            assertTrue("$state: the result must report the stopped session", result.stoppedSession)
            assertEquals("$state: stop the composition exactly once", 1, stops)
            assertEquals("$state: stop the hosting service exactly once", 1, serviceStops)
            assertEquals("$state: both stops must precede the write",
                listOf("stop", "stopService", "read", "write"), blobs.order)
            assertTrue("$state: the mode is persisted", store.load().glassesDetached)
        }
    }

    @Test
    fun aRefusedServiceStopAfterTheCompositionStoppedDoesNotSaveButReportsTheStoppedSession() {
        // The session stop and the service stop are separate effects. Once `stopSession()` returns, the session is
        // gone even if `Context.stopService` then refuses (false/throw), so the result must disclose that side effect
        // while still refusing the write. Reporting `stoppedSession=false` would turn "会话未能先停止" into a lie.
        val liveStates = listOf(
            SessionState.Authorizing,
            SessionState.Connecting,
            SessionState.BuildingScene,
            SessionState.Interrupted,
            SessionState.Reconnecting,
            SessionState.Error,
        )
        liveStates.forEach { state ->
            val blobs = RecordingBlobs()
            val store = seeded(blobs, shipped)
            var stops = 0
            var serviceStops = 0
            val gate = DetachGate(
                settings = store,
                stopSession = {
                    stops++
                    blobs.order += "stop"
                },
                stopService = {
                    serviceStops++
                    blobs.order += "stopService"
                    false
                },
                sessionState = { state },
            )

            val result = gate.set(true)

            assertFalse("$state: a refused service stop must not report a save", result.saved)
            assertTrue("$state: the host session was stopped, so the side effect must be reported",
                result.stoppedSession)
            assertEquals(DetachGate.ERROR_STOP_FAILED, result.error)
            assertEquals("$state: stop the composition exactly once", 1, stops)
            assertEquals("$state: the service stop must still be attempted", 1, serviceStops)
            assertEquals("$state: nothing may be read or written after the refused service stop",
                listOf("stop", "stopService"), blobs.order)
            assertFalse("$state: the live gate must stay in the stored mode", gate.enabled.value)
            assertFalse("$state: the blob must be untouched", store.load().glassesDetached)
        }
    }

    @Test
    fun aThrowingServiceStopDoesNotSaveAndStillReportsTheStoppedSession() {
        // Production wiring is the unwrapped `Context.stopService` call, which throws on a platform refusal
        // (SecurityException) instead of being swallowed by a `runCatching`. The gate must treat a throw as a failed
        // service stop and refuse the save, but `stopSession()` already succeeded: the session side effect must still
        // be reported so the UI can say so.
        val blobs = RecordingBlobs()
        val store = seeded(blobs, shipped)
        var state = SessionState.Ready
        val gate = DetachGate(
            settings = store,
            stopSession = { state = SessionState.Idle },
            stopService = { throw IllegalStateException("synthetic service stop failure") },
            sessionState = { state },
        )
        gate.load()
        blobs.order.clear()

        val result = gate.set(true)

        assertFalse("a refused service stop must not report a save", result.saved)
        assertTrue("the session stop already ran, so its side effect must be reported", result.stoppedSession)
        assertEquals(DetachGate.ERROR_STOP_FAILED, result.error)
        assertFalse("the live gate must stay in the stored mode", gate.enabled.value)
        assertFalse("the blob must be untouched", store.load().glassesDetached)
        assertTrue("no write may happen after a failed stop", blobs.order.none { it == "write" })
    }

    @Test
    fun aServiceStopThatReportsNoRunningServiceRefusesTheSaveButReportsTheStoppedSession() {
        // Production wiring is a direct `Context.stopService(...)`, whose own contract returns **false when no
        // matching service was running**. The gate must not read that as "the notification is gone": a session it
        // believes is running without a hosting service is an inconsistent state the gate cannot verify, so it
        // refuses the save visibly instead of persisting a mode over a notification it never proved removed. The
        // composition stop did happen, though, so the untruthful part is only the missing service teardown.
        val blobs = RecordingBlobs()
        val store = seeded(blobs, shipped)
        var state = SessionState.Ready
        val gate = DetachGate(
            settings = store,
            stopSession = { state = SessionState.Idle },
            stopService = { false },
            sessionState = { state },
        )
        gate.load()
        blobs.order.clear()

        val result = gate.set(true)

        assertFalse("an unproven service stop must not report a save", result.saved)
        assertTrue("the host session was stopped, so the side effect must be reported", result.stoppedSession)
        assertEquals(DetachGate.ERROR_STOP_FAILED, result.error)
        assertFalse("the live gate must stay in the stored mode", gate.enabled.value)
        assertFalse("the blob must be untouched", store.load().glassesDetached)
        assertTrue("no write may happen after a failed stop", blobs.order.none { it == "write" })
    }

    @Test
    fun setWhilePausedAlsoStopsTheSessionFirst() {
        // `D199`: Paused is a live session (its audio epoch/identity exist), so changing the mode must end it too.
        val blobs = RecordingBlobs()
        val store = seeded(blobs, shipped)
        var stopped = false
        val gate = DetachGate(store, stopSession = { stopped = true }, sessionState = { SessionState.Paused })

        val result = gate.set(true)

        assertTrue(result.saved)
        assertTrue(result.stoppedSession)
        assertTrue(stopped)
    }

    @Test
    fun setWhileIdleDoesNotStopAnything() {
        val store = seeded(RecordingBlobs(), shipped)
        var stops = 0
        var serviceStops = 0
        val gate = DetachGate(
            store,
            stopSession = { stops++ },
            stopService = { serviceStops++; true },
            sessionState = { SessionState.Idle },
        )

        val result = gate.set(true)

        assertTrue(result.saved)
        assertFalse(result.stoppedSession)
        assertEquals("an idle composition has no session to stop", 0, stops)
        assertEquals("and no hosting service to stop", 0, serviceStops)
    }

    @Test
    fun aFailedStopDoesNotClaimSavedOrWriteTheBlob() {
        val blobs = RecordingBlobs()
        val store = seeded(blobs, shipped)
        val gate = DetachGate(
            settings = store,
            stopSession = { throw IllegalStateException("synthetic stop failure") },
            sessionState = { SessionState.Listening },
        )

        val result = gate.set(true)

        assertFalse("a failed stop must not report a save", result.saved)
        assertFalse(result.stoppedSession)
        assertEquals(DetachGate.ERROR_STOP_FAILED, result.error)
        assertFalse("the gate must stay in the mode the store still holds", gate.enabled.value)
        assertFalse("the blob must be untouched", store.load().glassesDetached)
        assertTrue("no write may happen after a failed stop", blobs.order.none { it == "write" })
    }

    // ---- set: the CXR link side effect (0.4 strict-local fix) -----------------------------------------------------

    @Test
    fun aSavedChangeReportsThePersistedModeToTheLinkSideEffectAfterTheWrite() {
        // The gate itself owns no transport. Its mode side effect is what neutralizes the CXR link on detach (and
        // re-arms it on reattach), and it must run **after** the blob write, with the value the store now holds: running
        // it before the write would let a refused save leave a detached link behind an attached gate.
        val blobs = RecordingBlobs()
        val store = seeded(blobs, shipped)
        val modes = mutableListOf<Boolean>()
        val gate = DetachGate(
            settings = store,
            onModeChanged = { enabled ->
                modes += enabled
                blobs.order += "link"
            },
        )

        val result = gate.set(true)

        assertTrue("the save must succeed", result.saved)
        assertEquals("the side effect runs once, with the persisted mode", listOf(true), modes)
        assertEquals("and only after the blob write", listOf("read", "write", "link"), blobs.order)
        assertTrue(gate.enabled.value)
    }

    @Test
    fun aRefusedSaveNeverTouchesTheLinkSideEffect() {
        // The half-written/refused save must leave the CXR link exactly as it was: a link neutralized while the store
        // still says "attached" (or vice versa) is a state no later read can repair.
        val blobs = RecordingBlobs()
        val store = seeded(blobs, shipped)
        blobs.failWrite = true
        val modes = mutableListOf<Boolean>()

        val result = DetachGate(settings = store, onModeChanged = { modes += it }).set(true)

        assertFalse("a refused write must not report a save", result.saved)
        assertTrue("and must not touch the link", modes.isEmpty())
        assertFalse("the stored mode is still attached", store.load().glassesDetached)
    }

    // ---- set: visible failures ------------------------------------------------------------------------------------

    @Test
    fun aFailedSaveLeavesAVisibleErrorAndDoesNotClaimSaved() {
        val blobs = RecordingBlobs()
        val store = seeded(blobs, shipped)
        blobs.failWrite = true
        val gate = DetachGate(store)

        val result = gate.set(true)

        assertFalse("a refused blob write must not be reported as saved", result.saved)
        assertEquals(DetachGate.ERROR_SAVE_FAILED, result.error)
        assertFalse("the live gate must not move to a mode the store does not hold", gate.enabled.value)
        assertFalse(store.load().glassesDetached)
    }

    @Test
    fun anUnreadableBlobIsNotOverwrittenAndTheFailureIsVisible() {
        val blobs = RecordingBlobs()
        val store = seeded(blobs, shipped.copy(glassesDetached = true, maxLevel = "L2"))
        blobs.failRead = true
        val gate = DetachGate(store)

        val result = gate.set(false)

        assertFalse(result.saved)
        assertEquals(DetachGate.ERROR_SETTINGS_UNREADABLE, result.error)
        assertFalse("no defaults may be written over a blob that could not be read",
            blobs.order.any { it == "write" })

        blobs.failRead = false
        val untouched = store.load()
        assertTrue("the user's detach survives", untouched.glassesDetached)
        assertEquals("and so does every other field", "L2", untouched.maxLevel)
    }

    @Test
    fun aCorruptBlobIsNotOverwrittenEither() {
        val blobs = RecordingBlobs()
        val store = SmartSettingsStore(blobs)
        // A blob that exists but is not the settings JSON at all: the reader may fall back to defaults, but a writer
        // must not treat that as "there is nothing here to preserve".
        blobs.delegate.values[SmartSettingsStore.BLOB_ID] = "{ not json".toByteArray()
        blobs.order.clear()

        val result = DetachGate(store).set(true)

        assertFalse(result.saved)
        assertEquals(DetachGate.ERROR_SETTINGS_UNREADABLE, result.error)
        assertTrue(blobs.order.none { it == "write" })
    }

    @Test
    fun aCompositionWithNoStoreRefusesVisiblyInsteadOfPretendingToSave() {
        val gate = DetachGate()

        val result = gate.set(true)

        assertFalse(result.saved)
        assertEquals(DetachGate.ERROR_STORE_UNAVAILABLE, result.error)
        assertFalse(gate.enabled.value)
    }

    // ---- telemetry -----------------------------------------------------------------------------------------------

    @Test
    fun detachChangesAreLoggedWithTheEffectiveProactiveValueAndAudioSourceOnly() {
        val blobs = RecordingBlobs()
        // The stored proactive switch is on; detached makes it effectively off without changing the stored value.
        val store = seeded(blobs, shipped.copy(proactiveEnabled = true))
        val gate = DetachGate(store)

        val lines = withP3Sink { captured ->
            gate.load()
            gate.set(true)
            gate.set(false)
            captured.filter { it.startsWith("P4_DETACH") }
        }

        assertEquals(listOf(
            "P4_DETACH state=off effective_proactive=true audio=cxr",
            "P4_DETACH state=on effective_proactive=false audio=phone_mic",
            "P4_DETACH state=off effective_proactive=true audio=cxr",
        ), lines)
        assertTrue("the stored proactive choice must still be on", store.load().proactiveEnabled)
    }
}
