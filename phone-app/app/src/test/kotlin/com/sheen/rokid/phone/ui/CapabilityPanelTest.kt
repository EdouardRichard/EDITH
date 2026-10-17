package com.sheen.rokid.phone.ui
import com.sheen.rokid.phone.actions.A11Y_CHANNEL
import com.sheen.rokid.phone.actions.ROOT_CHANNEL
import com.sheen.rokid.phone.actions.INTENT_CHANNEL
import com.sheen.rokid.phone.actions.SHIZUKU_CHANNEL
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.phone.bridge.CxrHintLink
import com.sheen.rokid.phone.bridge.DisplayRetryTest
import com.sheen.rokid.phone.bridge.CxrHostEnvironment
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.actions.shizuku.RootStatus
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.storage.MemoryProfileRepository
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import com.sheen.rokid.testsupport.RecordingInputPort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * D7 / evidence §15.7.1 (K40, 2026-10-09): the 会话 page still listed `眼镜显示` as 尚不可用 *after* a clock mapping had
 * made the display real and the plugin had activated on the strength of it. The composition froze the
 * missing-capability map at construction, so the panel reported an "unavailable" set that could no longer change even
 * though the environment it was derived from is asked afresh on every read.
 *
 * The break these tests catch: a frozen snapshot, a refresh that never recomputes, and an unwired capability
 * disappearing from the list (which would be a false advertisement in the other direction).
 *
 * The mapping -> `liveOutput` half of that chain is the real `CxrHintLink`'s, and `DisplayRetryTest` already proves it
 * end to end over the wire protocol; here the display's liveness is driven directly so the panel's own freshness is
 * the only thing under test.
 */
class CapabilityPanelTest {

    private class UnusedModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            error("not used")
    }

    @Test
    fun theMissingCapabilityListFollowsWhatIsWiredNowNotTheStartupSnapshot() {
        val clock = FakeClock(0)
        val scheduler = ManualScheduler(clock)
        val link = CxrHintLink(clock, scheduler, sendJson = { false })
        val displayIsLive = booleanArrayOf(false)
        val environment = CxrHostEnvironment(clock = clock, input = RecordingInputPort(),
            liveOutput = { if (displayIsLive[0]) link.device else null }, scheduler = scheduler)
        val root = HostCompositionRoot(HostRuntime(1, environment), MemoryProfileRepository(),
            UnusedModel(), environment, hintLink = link)
        try {
            assertTrue("nothing can display before a clock mapping exists",
                Capability.TextOutput in root.state.value.capabilityReasons)

            displayIsLive[0] = true
            link.onMappingLive?.invoke()

            assertFalse("a display that became real must not keep reading as 尚不可用: " +
                root.state.value.capabilityReasons,
                Capability.TextOutput in root.state.value.capabilityReasons)
            assertEquals("a capability that is genuinely unwired must stay listed, with its reason",
                "TRANSPORT_MISSING", root.state.value.capabilityReasons[Capability.Audio])
        } finally {
            root.close()
        }
    }

    @Test
    fun aCapabilityReasonIsShownAsALabelAndAnUnknownCodeStaysVisible() {
        assertEquals("眼镜未连接", capabilityReasonLabel("TRANSPORT_MISSING"))
        assertEquals("a code this build does not know must stay visible rather than be replaced by a comforting label",
            "SOME_FUTURE_CODE", capabilityReasonLabel("SOME_FUTURE_CODE"))
    }

    @Test
    fun onePanelReadAsksTheRootChannelExactlyOnce() {
        // Leftover-sweep review, Important 1: the panel row read `rootProbe.available()` through `channelProbes` and
        // then the Root note read it *again* through `rootChannelNote`, so every panel computation ran `su -c id` twice
        // - and a `su` waiting on a permission prompt costs the full probe timeout each time, on the session page.
        val clock = FakeClock(0)
        val scheduler = ManualScheduler(clock)
        val environment = CxrHostEnvironment(clock = clock, input = RecordingInputPort(),
            liveOutput = { null }, scheduler = scheduler)
        val root = HostCompositionRoot(HostRuntime(1, environment), MemoryProfileRepository(), UnusedModel(), environment)
        try {
            var rootProbes = 0
            root.channelProbes = listOf(
                INTENT_CHANNEL to { true },
                A11Y_CHANNEL to { false },
                SHIZUKU_CHANNEL to { false },
            )
            root.rootStatusProbe = { rootProbes++; RootStatus.ABSENT }

            root.refreshExecutionChannels()
            val rows = root.state.value.executionChannels

            assertEquals("one panel read must ask the root channel exactly once", 1, rootProbes)
            assertEquals("the root row must still be named, with the reason the probe observed",
                "Root：不可用（设备无 su）", rows.first { it.id == ROOT_CHANNEL }.note)
        } finally {
            root.close()
        }
    }
}
