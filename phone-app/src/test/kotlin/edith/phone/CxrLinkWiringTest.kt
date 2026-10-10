package edith.phone

import android.app.Activity
import edith.host.HostRuntime
import edith.plugin.CancellationHandle
import edith.plugin.ModelClient
import edith.plugin.ModelRequest
import edith.plugin.ModelStreamEvent
import edith.storage.MemoryProfileRepository
import edith.testsupport.FakeClock
import edith.testsupport.ManualScheduler
import edith.testsupport.RecordingInputPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The authorization fact belongs to the SDK's own callback, not to the connect button: `MainActivity.onActivityResult`
 * forwards the result, and the page may only report what arrived there. This test keeps the two apart, which is what the
 * owner-reported defect (2026-10-10) was about.
 */
class CxrLinkWiringTest {

    private class UnusedModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            error("not used")
    }

    private class Rig {
        val clock = FakeClock(0)
        val root: HostCompositionRoot

        init {
            val environment = CxrHostEnvironment(
                clock = clock, input = RecordingInputPort(), liveOutput = { null }, scheduler = ManualScheduler(clock),
            )
            root = HostCompositionRoot(
                HostRuntime(1, environment), MemoryProfileRepository(), UnusedModel(), environment,
            )
        }
    }

    @Test
    fun theSdkAuthorizationResultBecomesAPageFact() {
        val rig = Rig()
        assertNull("nothing is claimed before the SDK answers", rig.root.state.value.cxrAuthorization)

        rig.root.onCxrAuthorizationResult(Activity.RESULT_OK, null)
        assertEquals(CxrAuthorizationOutcome.CONFIRMED, rig.root.state.value.cxrAuthorization)

        rig.root.onCxrAuthorizationResult(Activity.RESULT_CANCELED, null)
        assertEquals(CxrAuthorizationOutcome.CANCELLED, rig.root.state.value.cxrAuthorization)

        rig.root.onCxrAuthorizationResult(42, null)
        assertEquals(CxrAuthorizationOutcome.UNEXPECTED, rig.root.state.value.cxrAuthorization)
    }

    @Test
    fun theTransportReadinessBecomesAPageFact() {
        // The page's "connected" wording used to hang off `Capability.Audio`, which `CxrHostEnvironment` deliberately
        // never advertises - so the ready branch was unreachable on a phone. The transport's own signal is the fact.
        val rig = Rig()
        assertFalse("nothing may be claimed before the transport reports", rig.root.state.value.cxrLinkReady)

        rig.root.onSceneReady()

        assertTrue("the SDK's live-session signal must reach the page", rig.root.state.value.cxrLinkReady)
    }
}
