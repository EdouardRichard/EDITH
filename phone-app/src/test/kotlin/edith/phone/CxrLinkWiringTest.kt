package edith.phone

import android.app.Activity
import edith.host.HostRuntime
import edith.plugin.CancellationHandle
import edith.plugin.ModelClient
import edith.plugin.ModelRequest
import edith.plugin.ModelStreamEvent
import edith.rokid.CxrAuthorizationOutcome
import edith.storage.MemoryProfileRepository
import edith.testsupport.FakeClock
import edith.testsupport.ManualScheduler
import edith.testsupport.RecordingInputPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
        val connection = CodeVerdictConnection()
        val root: HostCompositionRoot

        init {
            val environment = CxrHostEnvironment(
                clock = clock, input = RecordingInputPort(), liveOutput = { null }, scheduler = ManualScheduler(clock),
            )
            root = HostCompositionRoot(
                HostRuntime(1, environment), MemoryProfileRepository(), UnusedModel(), environment,
                rokidConnectionFactory = { connection },
            )
        }
    }

    /**
     * A live transport whose own parse of the result is the verdict, exactly as the real transport's is.
     *
     * The double answers the verdict **given to it** rather than deriving it, because the parse lives in the transport
     * (the vendor's `AuthResult` never leaves `transport-rokid`); what these tests pin is that the page repeats it.
     */
    private class VerdictConnection(private val verdict: CxrAuthorizationOutcome?) : CxrConnection {
        var lastResultCode: Int? = null
        override var ready: Boolean = false
        override fun connect(activity: Any) = Unit
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? {
            lastResultCode = resultCode
            return verdict
        }
        override fun sendCommand(json: String): Boolean = false
        override fun onInboundCommand(listener: (String) -> Unit) = Unit
        override fun close() = Unit
    }

    /**
     * The transport's verdict for a result the SDK **did** parse successfully, i.e. where the raw code and the parse
     * agree. Standing in for the success path is what keeps these tests about the page: the parsed-failure path is the
     * one [VerdictConnection] is for, and the parse rule itself is pinned in `transport-rokid`'s `CxrBackendTest`.
     */
    private class CodeVerdictConnection : CxrConnection {
        override var ready: Boolean = false
        override fun connect(activity: Any) = Unit
        override fun onAuthorizationResult(resultCode: Int, data: android.content.Intent?): CxrAuthorizationOutcome? =
            when (resultCode) {                Activity.RESULT_OK -> CxrAuthorizationOutcome.CONFIRMED
                Activity.RESULT_CANCELED -> CxrAuthorizationOutcome.CANCELLED
                else -> CxrAuthorizationOutcome.UNEXPECTED
            }
        override fun sendCommand(json: String): Boolean = false
        override fun onInboundCommand(listener: (String) -> Unit) = Unit
        override fun close() = Unit
    }

    @Test
    fun theSdkAuthorizationResultBecomesAPageFact() {
        val rig = Rig()
        assertNull("nothing is on the page before the SDK answers", rig.root.state.value.cxrAuthorization)
        assertTrue("the transport is the party that asks", rig.root.connectCxr(Any()))

        rig.root.onCxrAuthorizationResult(Activity.RESULT_OK, null)
        assertEquals(CxrAuthorizationOutcome.CONFIRMED, rig.root.state.value.cxrAuthorization)

        rig.root.onCxrAuthorizationResult(Activity.RESULT_CANCELED, null)
        assertEquals(CxrAuthorizationOutcome.CANCELLED, rig.root.state.value.cxrAuthorization)

        rig.root.onCxrAuthorizationResult(42, null)
        assertEquals(CxrAuthorizationOutcome.UNEXPECTED, rig.root.state.value.cxrAuthorization)
    }

    @Test
    fun aResultTheTransportParsedAsAFailureIsNotAPageConfirmation() {
        // Review Minor 6: the verdict was derived from the **bare** `resultCode`, so a result the SDK's own parser
        // refused (`RESULT_OK` with an `AuthResult` that is not a success - the vendor's parser reports a fail for an
        // `auth_result` extra it does not recognise, and for a missing/blank token) still printed
        // "眼镜授权确认已完成". The page may only say what the transport parsed, and the transport is the party that
        // holds the vendor's `AuthResult`.
        val connection = VerdictConnection(CxrAuthorizationOutcome.UNEXPECTED)
        val root = HostCompositionRoot(
            HostRuntime(1), MemoryProfileRepository(), UnusedModel(), null,
            rokidConnectionFactory = { connection },
        )
        try {
            assertTrue(root.connectCxr(Any()))

            root.onCxrAuthorizationResult(Activity.RESULT_OK, null)

            assertEquals("the result must still reach the transport", Activity.RESULT_OK, connection.lastResultCode)
            assertNotEquals(
                "a result the transport parsed as a failure may not be reported as a confirmed authorization",
                CxrAuthorizationOutcome.CONFIRMED, root.state.value.cxrAuthorization,
            )
        } finally { root.close() }
    }

    @Test
    fun theTransportReadinessBecomesAPageFact() {
        // The page's "connected" wording used to hang off `Capability.Audio`, which `CxrHostEnvironment` deliberately
        // never advertises - so the ready branch was unreachable on a phone. The transport's own signal is the fact.
        val rig = Rig()
        assertFalse("nothing may be reported before the transport says so", rig.root.state.value.cxrLinkReady)

        rig.root.onSceneReady()

        assertTrue("the SDK's live-session signal must reach the page", rig.root.state.value.cxrLinkReady)
    }

    @Test
    fun aNewConnectAttemptClearsThePreviousAuthorizationAnswer() {
        // Review I-2: a cancelled prompt used to stay on the page forever, next to a link that came up afterwards. The
        // answer describes one attempt, so a new attempt starts it empty again.
        val rig = Rig()
        assertTrue(rig.root.connectCxr(Any()))
        rig.root.onCxrAuthorizationResult(Activity.RESULT_CANCELED, null)
        assertEquals(CxrAuthorizationOutcome.CANCELLED, rig.root.state.value.cxrAuthorization)

        rig.root.connectCxr(rig.root)

        assertNull("a new attempt must not carry the previous verdict", rig.root.state.value.cxrAuthorization)
    }

    @Test
    fun anActivityResultNoPromptAskedForIsNotAPageFact() {
        // Review I-3: `onActivityResult` also carries the results of everything else this Activity starts for -
        // including the App's own `permissions.launch(...)` dialogs - and the App recorded all of them as the SDK's
        // authorization answer. The transport is the only party that knows whether a prompt is pending (it armed the
        // callback inside `connect`), so a result it does not answer must leave the page alone instead of inventing
        // "眼镜授权被取消".
        val connection = VerdictConnection(null)
        val root = HostCompositionRoot(
            HostRuntime(1), MemoryProfileRepository(), UnusedModel(), null,
            rokidConnectionFactory = { connection },
        )
        try {
            assertTrue(root.connectCxr(Any()))
            root.onCxrAuthorizationResult(Activity.RESULT_OK, null)

            assertEquals("the result must still reach the transport", Activity.RESULT_OK, connection.lastResultCode)
            assertNull("a result no prompt asked for must not become a page fact", root.state.value.cxrAuthorization)
        } finally { root.close() }
    }

    @Test
    fun theCompositionOwnedAuthorizationRouteIsAHonestClaimToo() {
        // Review I-3, Minor: the second route an activity result can take into the transport (the composition's own
        // connection holder) carries the same verdict contract, and no test had ever passed a non-default one - a
        // lambda that recorded the result while quietly answering "not mine" would have gone unnoticed.
        val root = HostCompositionRoot(
            HostRuntime(1), MemoryProfileRepository(), UnusedModel(), null,
            authorizeResult = { _, _ -> CxrAuthorizationOutcome.CONFIRMED },
        )
        try {
            root.onCxrAuthorizationResult(Activity.RESULT_OK, null)

            assertEquals(CxrAuthorizationOutcome.CONFIRMED, root.state.value.cxrAuthorization)
        } finally { root.close() }
    }

    @Test
    fun aFailureVerdictOnTheCompositionOwnedRouteIsNotAConfirmationEither() {
        // Review Minor 6 also has to hold on the second injection point: this route hands the page a verdict of its
        // own, so a `RESULT_OK` whose parsed answer failed must be reported as an anomaly there too - the point of
        // deriving the page fact from the verdict is that it cannot depend on which route delivered it.
        val root = HostCompositionRoot(
            HostRuntime(1), MemoryProfileRepository(), UnusedModel(), null,
            authorizeResult = { _, _ -> CxrAuthorizationOutcome.UNEXPECTED },
        )
        try {
            root.onCxrAuthorizationResult(Activity.RESULT_OK, null)

            assertNotEquals(
                "the second route's verdict is the page's fact too",
                CxrAuthorizationOutcome.CONFIRMED, root.state.value.cxrAuthorization,
            )
            assertEquals(CxrAuthorizationOutcome.UNEXPECTED, root.state.value.cxrAuthorization)
        } finally { root.close() }
    }
}
