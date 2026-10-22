package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.withP3Sink
import com.sheen.rokid.phone.app.MainActivity
import com.sheen.rokid.phone.session.SessionService
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.UserAction
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.output.HintCommand
import com.sheen.rokid.output.HintWireCodec
import com.sheen.rokid.plugin.*
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import com.sheen.rokid.testsupport.RecordingInputPort
import com.sheen.rokid.transport.CxrAuthorizationOutcome
import com.sheen.rokid.storage.MemoryProfileRepository
import org.junit.Assert.*
import org.junit.Test

/**
 * T17 App-side CXR wiring: the app must carry the exact glasses pairing, route the AI-App
 * authorization result into the transport, and keep the audio capability missing while no
 * connection exists. These symbols do not exist yet — this run is the behavioral RED.
 */
class CxrWiringTest {
    @Test fun appCarriesTheExactCxrPairingFromTheRecordedPreflight() {
        assertEquals("com.sheen.rokid.glasses", CxrGlassesTarget.GLASSES_PACKAGE_NAME)
        assertEquals("com.sheen.rokid.glasses.app.GlassesActivity", CxrGlassesTarget.GLASSES_ACTIVITY_NAME)
        val config = CxrGlassesTarget.config()
        assertEquals("com.sheen.rokid.glasses", config.glassesPackageName)
        assertEquals("com.sheen.rokid.glasses.app.GlassesActivity", config.glassesActivityName)
        assertNull("the glasses app is installed on the glasses, not pushed by the SDK", config.glassesApkPath)
        assertEquals(CxrGlassesTarget.GLASSES_FIRMWARE, config.firmware)
        assertEquals("1.26.011-20260929-150201", config.firmware)
    }

    @Test fun compositionWithoutAConnectionReportsAudioAsMissingAndRefusesToConnect() {
        val model = UnusedModel()
        val root = HostCompositionRoot(HostRuntime(1), MemoryProfileRepository(), model, null)
        try {
            assertNull(root.rokidConnectionFactory)
            assertEquals("TRANSPORT_MISSING", root.state.value.capabilityReasons[Capability.Audio])
            assertFalse("an unwired app must not claim a CXR connection", root.connectCxr(activity = Any()))
        } finally { root.close() }
    }

    @Test fun theAppBuiltConnectionIsUsedForConnectAndReleasedExactlyOnce() {
        val model = UnusedModel()
        val connection = FakeConnection()
        var factories = 0
        val root = HostCompositionRoot(HostRuntime(1), MemoryProfileRepository(), model, null,
            rokidConnectionFactory = { factories++; connection })
        try {
            assertTrue(root.connectCxr(activity = Any()))
            assertEquals(1, factories)
            assertEquals(1, connection.connectedWith.size)
            assertTrue("a second connect must reuse the same connection", root.connectCxr(activity = Any()))
            assertEquals("one connection per composition", 1, factories)
            assertEquals(2, connection.connectedWith.size)
        } finally { root.close() }
        root.close()
        assertEquals(1, connection.closes)
    }

    @Test fun authorizationResultReachesOnlyTheLiveConnection() {
        val model = UnusedModel()
        val connection = FakeConnection()
        var factories = 0
        val root = HostCompositionRoot(HostRuntime(1), MemoryProfileRepository(), model, null,
            rokidConnectionFactory = { factories++; connection })
        try {
            root.onCxrAuthorizationResult(0, null)
            assertNull("an authorization result before connect has no transport to reach", connection.resultCode)
            assertTrue(root.connectCxr(activity = Any()))
            assertEquals("factory must have built the connection", 1, factories)
            root.onCxrAuthorizationResult(-1, null)
            assertEquals(-1, connection.resultCode)
            assertEquals(false, connection.sawIntent)
        } finally { root.close() }
    }

    @Test fun transportReadinessIsFalseWithoutAConnectionAndFollowsTheLiveConnection() {
        val model = UnusedModel()
        val connection = FakeConnection()
        val root = HostCompositionRoot(HostRuntime(1), MemoryProfileRepository(), model, null,
            rokidConnectionFactory = { connection })
        try {
            assertFalse("an unwired composition has no ready transport", root.cxrReady())
            assertTrue(root.connectCxr(activity = Any()))
            assertFalse("connect alone must not claim readiness", root.cxrReady())
            connection.ready = true
            assertTrue("readiness must follow the live connection", root.cxrReady())
        } finally { root.close() }
    }

    @Test fun readinessNeedsAConnectionEvenWhenTheAudioCapabilityLooksWired() {
        val model = UnusedModel()
        val root = HostCompositionRoot(HostRuntime(1), MemoryProfileRepository(), model, null)
        try {
            assertNull(root.cxrInputPort())
            assertFalse("no connection means no ready transport", root.cxrReady())
        } finally { root.close() }
    }

    /**
     * W1 (F17/F18): the session start path establishes the link itself.
     *
     * `start()` runs inside `SessionService`, which has no Activity, and mixing a placeholder in would hand the
     * vendor adapter something it cannot cast. So the attempt is **deferred and said out loud** until the Activity
     * that `MainActivity` hands over exists - and then it really connects, exactly once.
     */
    @Test fun ensureCxrLinkDefersWithoutAnActivityAndConnectsOnceOneExists() {
        val model = UnusedModel()
        val connection = FakeConnection()
        var factories = 0
        val root = HostCompositionRoot(HostRuntime(1), MemoryProfileRepository(), model, null,
            rokidConnectionFactory = { factories++; connection })
        try {
            withP3Sink { lines ->
                assertFalse("nothing can be built without the Activity the SDK needs", root.ensureCxrLink())
                assertEquals("no connection may be built out of a placeholder", 0, factories)
                assertEquals("and nothing may be connected", 0, connection.connectedWith.size)
                assertTrue("the deferred attempt must be visible, never silent",
                    lines.any { it == "P3_CXR connect attempt=start result=no_activity" })
                assertFalse("and it must not claim a result it does not have",
                    lines.any { it.contains("result=ok") })
            }
            withP3Sink { lines ->
                root.attachCxrActivity(Any())
                assertTrue("with an Activity the start path must establish the link", root.ensureCxrLink())
                assertEquals(1, factories)
                assertEquals(1, connection.connectedWith.size)
                assertTrue(lines.any { it.contains("result=ok") })
                assertTrue("a second ensure must reuse the connection", root.ensureCxrLink())
                assertEquals("one connection per composition", 1, factories)
            }
        } finally { root.close() }
    }

    /** A composition with no CXR assembly must not be silent about it (F17/F18: "no CXR line at all" was the symptom). */
    @Test fun aCompositionWithoutATransportLogsMissingInsteadOfSilence() {
        val model = UnusedModel()
        val root = HostCompositionRoot(HostRuntime(1), MemoryProfileRepository(), model, null)
        try {
            root.attachCxrActivity(Any())
            withP3Sink { lines ->
                assertFalse("an unwired app must not claim a link", root.ensureCxrLink())
                assertTrue("the missing transport must be a log line, not silence",
                    lines.any { it == "P3_CXR connect attempt=start result=transport_missing" })
            }
        } finally { root.close() }
    }

    /** The wiring that replaces the old UI button: `MainActivity` hands over the Activity, and the session start's own call keeps the link, without a second connect. */
    @Test fun aWiredCompositionConnectsFromTheStartPathAlone() {
        val model = UnusedModel()
        val connection = FakeConnection()
        val root = HostCompositionRoot(HostRuntime(1), MemoryProfileRepository(), model, null,
            rokidConnectionFactory = { connection })
        try {
            withP3Sink { lines ->
                root.attachCxrActivity(Any())
                assertTrue(lines.any { it == "P3_CXR connect attempt=start result=ok" })
            }
            // The invariant the mutation "make ensureCxrLink connect unconditionally" breaks: `start()` reaches this
            // method on every session start while `onCreate` already connected, so a second ask would be a real
            // duplicate - exactly the crash the transport had to be hardened against.
            withP3Sink { lines ->
                assertTrue("the start path must find the link already established", root.ensureCxrLink())
                assertTrue("and again", root.ensureCxrLink())
                assertEquals("but must never ask the SDK a second time", 1, connection.connectedWith.size)
                assertTrue("nothing new had to happen, so nothing is claimed",
                    lines.none { it.contains("result=ok") })
            }
        } finally { root.close() }
    }

    // ---------------------------------------------------------------- Task 20: wear-off + CXR reconnect

    /** A plugin whose only job is to show what the host actually delivered. */
    private class ScenePlugin : PluginLifecycle {
        val events = java.util.concurrent.CopyOnWriteArrayList<HostEvent>()
        override val descriptor = PluginDescriptor("scene", "1.0.0", 1, "Scene", emptyList(), ConfigSchema(1))
        override fun activate(context: PluginContext) = Unit
        override fun handle(event: HostEvent) { events += event }
        override fun deactivate(reason: DeactivationReason) = Unit
    }

    /** The production-shaped link/connection pair, so the composition reset is observed on the real objects. */
    private class Task20Rig : AutoCloseable {
        val clock = FakeClock(1_000_000_000L)
        val scheduler = ManualScheduler(clock)
        val connection = FakeConnection()
        val plugin = ScenePlugin()
        /** Every app-start request the link sent; the lambda is the SDK's asynchronous answer for the test. */
        val appStarts = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        val host: HostRuntime
        val root: HostCompositionRoot
        val link: CxrHintLink

        init {
            val environment = CxrHostEnvironment(
                clock = clock, input = RecordingInputPort(), liveOutput = { null }, scheduler = scheduler)
            host = HostRuntime(1, environment)
            link = CxrHintLink(
                clock,
                scheduler,
                sendJson = { json -> connection.sendCommand(json) },
                startGlassesApp = { onResult -> appStarts += { onResult(true) }; true },
            )
            root = HostCompositionRoot(
                host, MemoryProfileRepository(), UnusedModel(), environment,
                rokidConnectionFactory = { connection }, hintLink = link,
            )
            root.registerPlugin(plugin)
        }

        /** 开始 with the transport already reporting a session: the production order that introduces the identity. */
        fun startEstablished() {
            root.connectCxr(activity = Any())
            connection.ready = true
            root.onSceneReady()
            root.start("scene")
            requireNotNull(host.currentIdentity()) { "the scene plugin must be active" }
        }

        /** Completes the clock exchange, which is what makes the display real. */
        fun completeExchange() {
            val ping = connection.sentCommands.mapNotNull { HintWireCodec.decode(it) as? HintCommand.Ping }.last()
            clock.nowNanos = ping.p0 + 10_000_000
            connection.inbound!!(HintWireCodec.encode(HintCommand.Pong(ping.syncId, ping.p0,
                ping.p0 + 1_005_000_000, ping.p0 + 1_005_000_000)))
        }

        fun identity(): HintIdentity {
            val current = requireNotNull(host.currentIdentity())
            return HintIdentity(current.first.value, current.second.value)
        }

        /** What the glasses report while the window is on screen: readiness, visibility and a live mapping. */
        fun markPanelVisibleAndReady() {
            val identity = identity()
            assertTrue(link.onInbound(HintWireCodec.encode(
                HintCommand.UiReady(identity.sessionId, identity.generation))))
            assertTrue(link.onInbound(HintWireCodec.encode(
                HintCommand.PanelShown(identity.sessionId, identity.generation, AssistantRoute.WAKE))))
        }

        override fun close() = root.close()
    }

    @Test
    fun wearOffCancelsTheTurnAndSendsTheAllRouteClose() {
        Task20Rig().use { rig ->
            rig.startEstablished()
            rig.completeExchange()
            rig.markPanelVisibleAndReady()
            val identity = rig.identity()
            rig.connection.sentCommands.clear()

            withP3Sink { lines ->
                rig.root.onWearingChanged(false)

                val closes = rig.connection.sentCommands.mapNotNull { HintWireCodec.decode(it) }
                    .filterIsInstance<HintCommand.Close>()
                assertEquals("wear-off must tell the glasses to close the panel", 1, closes.size)
                assertNull("the ALL form closes whichever route is visible", closes.single().route)
                assertEquals(identity.sessionId, closes.single().sessionId)
                assertTrue("the wear status must be recorded with its value",
                    lines.contains("P3_CXR glasses_event=wearing=false"))
                assertTrue("the wear-off action must be recorded: ${lines.filter { it.contains("wear") }}",
                    lines.any { it.startsWith("P3_CXR wear=off") })
            }

            val deadline = System.nanoTime() + 2_000_000_000L
            while (rig.plugin.events.none { it is UserAction && it.action == UserAction.Action.Stop } &&
                System.nanoTime() < deadline) {
                Thread.sleep(10)
            }
            assertTrue("wear-off must cancel the current turn: ${rig.plugin.events}",
                rig.plugin.events.any { it is UserAction && it.action == UserAction.Action.Stop })

            rig.connection.sentCommands.clear()
            rig.plugin.events.clear()
            rig.root.onWearingChanged(true)
            Thread.sleep(100)
            assertTrue("putting the glasses back on is diagnostic only: ${rig.connection.sentCommands}",
                rig.connection.sentCommands.isEmpty())
            assertTrue("and it must not cancel a turn", rig.plugin.events.isEmpty())
        }
    }

    @Test
    fun screenAndAppEventsAreRecordedWithoutBehaviour() {
        Task20Rig().use { rig ->
            rig.startEstablished()
            rig.connection.sentCommands.clear()
            withP3Sink { lines ->
                rig.root.onGlassesSceneEvent("screen_off")
                rig.root.onGlassesSceneEvent("screen_on")
                rig.root.onGlassesSceneEvent("app_paused")
                rig.root.onGlassesSceneEvent("app_resumed")
                listOf("screen_off", "screen_on", "app_paused", "app_resumed").forEach { event ->
                    assertTrue("$event must be recorded: $lines", lines.contains("P3_CXR glasses_event=$event"))
                }
            }
            Thread.sleep(100)
            assertTrue("screen/app events must not send anything: ${rig.connection.sentCommands}",
                rig.connection.sentCommands.isEmpty())
        }
    }

    @Test
    fun aCxrDisconnectResetsStaleLinkStateSoTheNextLiveSessionTakesTheRecoveryPath() {
        Task20Rig().use { rig ->
            rig.startEstablished()
            rig.completeExchange()
            rig.markPanelVisibleAndReady()
            assertNotNull("precondition: the mapping is live", rig.link.liveSyncId())
            assertTrue(rig.link.hasUiReady())
            assertTrue(rig.link.isPanelVisible())
            assertTrue(rig.root.state.value.cxrLinkReady)

            rig.host.interrupt()

            assertNull("a disconnect must drop the live clock mapping", rig.link.liveSyncId())
            assertFalse("UI_READY belonged to the dead session", rig.link.hasUiReady())
            assertFalse("a panel the dead link reported must not stay visible", rig.link.isPanelVisible())
            assertFalse("the page must not keep claiming a live link", rig.root.state.value.cxrLinkReady)

            // A new session exists on the phone, but the transport has not reported a live session for it yet. The
            // stale readiness from the closed link must not let a wake start the scene before that signal, and the
            // dead transport itself must be asked to reconnect by 开始 (D53-③), or the next scene can never come up.
            rig.connection.ready = true
            val connectsBefore = rig.connection.connectedWith.size
            rig.root.start("scene")
            assertEquals("开始 must reconnect the transport the disconnect killed",
                connectsBefore + 1, rig.connection.connectedWith.size)
            val appStartsBefore = rig.appStarts.size
            assertFalse("a wake before the new transport is live may not use the dead link's readiness",
                rig.root.requestPanelOpen(AssistantRoute.WAKE))
            assertEquals("and must not start the scene into a dead session", appStartsBefore, rig.appStarts.size)

            rig.root.onSceneReady()
            assertTrue("the first wake on the new live session must take the recovery path",
                rig.root.requestPanelOpen(AssistantRoute.WAKE))
            assertEquals("appStart is the recovery path, not a stale PANEL_OPEN",
                appStartsBefore + 1, rig.appStarts.size)
        }
    }

    // -------------------------------------------------- 0.3 Phase 2 Task 2: the wearing fact

    /** `glassesWearing` is the host's own tri-state: no event is `null`, and a lost link cannot keep an old answer. */
    @Test
    fun theWearingStateIsUnknownUntilAnEventAndClearedWhenTheLinkDies() {
        Task20Rig().use { rig ->
            assertNull("no wearing event has arrived, so there is no wearing fact", rig.root.glassesWearing())

            rig.startEstablished()
            assertNull("establishing the session is not a wearing event", rig.root.glassesWearing())

            rig.root.onWearingChanged(false)
            assertEquals(false, rig.root.glassesWearing())
            rig.root.onWearingChanged(true)
            assertEquals(true, rig.root.glassesWearing())

            rig.host.interrupt()
            assertNull("the dead link's last wearing fact must not survive it", rig.root.glassesWearing())
        }
    }

    @Test
    fun stoppingTheSessionForgetsTheWearingState() {
        Task20Rig().use { rig ->
            rig.startEstablished()
            rig.root.onWearingChanged(true)
            assertEquals(true, rig.root.glassesWearing())

            rig.root.stop()

            assertNull("a stopped session has no wearer to describe", rig.root.glassesWearing())
        }
    }

    private class FakeConnection : CxrConnection {
        val connectedWith = mutableListOf<Any>()
        var resultCode: Int? = null
        var sawIntent: Boolean? = null
        var closes = 0
        var sentCommands = mutableListOf<String>()
        var inbound: ((String) -> Unit)? = null
        override var ready: Boolean = false
        override fun connect(activity: Any) { connectedWith += activity }
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? {
            this.resultCode = resultCode
            sawIntent = data != null
            return null
        }
        override fun sendCommand(json: String): Boolean {
            if (!ready) return false
            sentCommands += json
            return true
        }
        override fun onInboundCommand(listener: (String) -> Unit) { inbound = listener }
        override fun close() { closes++ }
    }

    private class UnusedModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            error("not used")
    }
}
