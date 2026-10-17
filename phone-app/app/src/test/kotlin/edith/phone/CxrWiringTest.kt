package edith.phone

import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.plugin.*
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
        assertEquals("edith.glasses", CxrGlassesTarget.GLASSES_PACKAGE_NAME)
        assertEquals("edith.glasses.GlassesActivity", CxrGlassesTarget.GLASSES_ACTIVITY_NAME)
        val config = CxrGlassesTarget.config()
        assertEquals("edith.glasses", config.glassesPackageName)
        assertEquals("edith.glasses.GlassesActivity", config.glassesActivityName)
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
