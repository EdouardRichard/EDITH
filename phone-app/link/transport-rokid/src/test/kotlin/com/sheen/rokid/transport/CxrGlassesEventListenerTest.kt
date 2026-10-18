package com.sheen.rokid.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `IGlassesEventListener` forwarding: the wearer taking the glasses off, the screen going dark and the AI App pausing
 * are SDK facts the phone must be able to act on.
 *
 * Official sample evidence (CXR-L 1.1.2 `SessionHubViewModel`): the listener is registered on the **session** with
 * `addGlassesEventListener`, not on the glasses client. `javap` shows the interface's nine callbacks; only the five
 * the App consumes are forwarded, the rest keep the SDK's default no-op implementations.
 */
class CxrGlassesEventListenerTest {
    private class Sink : GlassesEvents {
        val received = mutableListOf<String>()
        override fun appResumed() { received += "resumed" }
        override fun appPaused() { received += "paused" }
        override fun wearingChanged(wearing: Boolean) { received += "wearing=$wearing" }
        override fun screenOn() { received += "screenOn" }
        override fun screenOff() { received += "screenOff" }
    }

    private fun rig(): CxrBackendRig = CxrBackendRig().also { it.authorize() }

    @Test
    fun theListenerIsInstalledOnTheSessionAndEveryConsumedEventIsForwarded() {
        val rig = rig()
        val sink = Sink()

        val handle = rig.backend.addGlassesEventListener(sink)

        assertNotNull(handle)
        assertEquals(1, rig.glassesEventListenerRegistrations)
        val sdk = requireNotNull(rig.glassesEventListener) { "no SDK listener was registered" }
        sdk.onGlassesAppResumed()
        sdk.onGlassesAppPaused()
        sdk.onWearingStatusChanged(false)
        sdk.onWearingStatusChanged(true)
        sdk.onScreenOff()
        sdk.onScreenOn()
        assertEquals(listOf("resumed", "paused", "wearing=false", "wearing=true", "screenOff", "screenOn"),
            sink.received)
    }

    @Test
    fun closingTheHandleRemovesTheListenerAndSilencesAnInFlightCallback() {
        val rig = rig()
        val sink = Sink()
        val handle = requireNotNull(rig.backend.addGlassesEventListener(sink))
        val sdk = requireNotNull(rig.glassesEventListener)
        sdk.onScreenOff()
        sink.received.clear()

        handle.close()

        assertEquals("the SDK registration must be removed", 1, rig.glassesEventListenerRemovals)
        sdk.onScreenOff()
        sdk.onGlassesAppResumed()
        assertTrue("a listener released by its owner must be inert: ${sink.received}", sink.received.isEmpty())
    }

    @Test
    fun releasingTheBackendRemovesAStillRegisteredListener() {
        val rig = rig()
        val sink = Sink()
        val handle = requireNotNull(rig.backend.addGlassesEventListener(sink))

        rig.backend.close()

        assertEquals(1, rig.glassesEventListenerRemovals)
        // Closing the owner's handle afterwards must stay harmless.
        handle.close()
    }

    @Test
    fun anEventFromAReplacedSessionIsNotForwardedToTheHost() {
        val rig = rig()
        val oldSink = Sink()
        val oldHandle = requireNotNull(rig.backend.addGlassesEventListener(oldSink))
        val oldAdapter = requireNotNull(rig.glassesEventListener)

        // A second connection replaces the binding. The SDK may still hold the old session callback; nothing it says
        // may speak for the session the host is now using.
        rig.authorize()
        val newSink = Sink()
        requireNotNull(rig.backend.addGlassesEventListener(newSink))
        val newAdapter = requireNotNull(rig.glassesEventListener)
        oldAdapter.onScreenOff()
        oldAdapter.onWearingStatusChanged(false)

        assertTrue("a listener installed by a previous binding must be inert: ${oldSink.received}",
            oldSink.received.isEmpty())
        newAdapter.onScreenOn()
        assertEquals(listOf("screenOn"), newSink.received)
        // The owner's handle must stay harmless after the replacement.
        oldHandle.close()
    }

    @Test
    fun registeringBeforeAnySessionExistsIsRefused() {
        val rig = CxrBackendRig()

        assertNull(rig.backend.addGlassesEventListener(Sink()))

        assertEquals(0, rig.glassesEventListenerRegistrations)
    }

    @Test
    fun registeringAfterCloseIsRefused() {
        val rig = rig()
        rig.backend.close()

        assertNull(rig.backend.addGlassesEventListener(Sink()))
    }

    @Test
    fun aThrowingListenerCannotEscapeIntoTheVendorCallbackThread() {
        val rig = rig()
        val sdk = { sink: GlassesEvents -> rig.backend.addGlassesEventListener(sink) }
        sdk(object : GlassesEvents {
            override fun appResumed() = error("host died")
            override fun appPaused() = Unit
            override fun wearingChanged(wearing: Boolean) = Unit
            override fun screenOn() = Unit
            override fun screenOff() = Unit
        })
        val listener = requireNotNull(rig.glassesEventListener)

        // The adapter's contract: a host failure is contained, because escaping here kills the vendor's callback.
        listener.onGlassesAppResumed()
        listener.onWearingStatusChanged(false)
    }

    @Test
    fun theFiveConsumedEventsDoNotRequireTheUnusedCallbacksToBeImplementedByTheListener() {
        // GlassesEvents only names what EDITH consumes. The SDK interface's other four callbacks must stay default
        // no-ops rather than becoming accidental requirements on every host listener.
        val rig = rig()
        val sink = Sink()
        rig.backend.addGlassesEventListener(sink)
        val sdk = requireNotNull(rig.glassesEventListener)

        sdk.onDeviceInfoChanged(com.rokid.cxr.session.GlassesInfo("os", 1, 1L, false, 480, 640, true))
        sdk.onLauncherResumed()
        sdk.onAiWake()
        sdk.onAiInterruptChanged(true)

        assertFalse("only the five consumed events may be forwarded: ${sink.received}", sink.received.isNotEmpty())
    }
}
