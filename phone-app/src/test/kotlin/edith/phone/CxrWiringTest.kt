package edith.phone

import edith.host.HostRuntime
import edith.plugin.*
import edith.storage.MemoryProfileRepository
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

    private class FakeConnection : CxrConnection {
        val connectedWith = mutableListOf<Any>()
        var resultCode: Int? = null
        var sawIntent: Boolean? = null
        var closes = 0
        var sentCommands = mutableListOf<String>()
        var inbound: ((String) -> Unit)? = null
        override var ready: Boolean = false
        override fun connect(activity: Any) { connectedWith += activity }
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): Boolean {
            this.resultCode = resultCode
            sawIntent = data != null
            return false
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
