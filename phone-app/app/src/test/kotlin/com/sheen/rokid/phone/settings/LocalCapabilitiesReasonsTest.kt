package com.sheen.rokid.phone.settings

import com.sheen.rokid.phone.actions.A11Y_CHANNEL
import com.sheen.rokid.phone.actions.ExecutionChannelStatus
import com.sheen.rokid.phone.actions.INTENT_CHANNEL
import com.sheen.rokid.phone.actions.ROOT_CHANNEL
import com.sheen.rokid.phone.actions.SHIZUKU_CHANNEL
import com.sheen.rokid.phone.model.ModelReadiness
import com.sheen.rokid.phone.model.ProvisioningState
import com.sheen.rokid.phone.ui.settings.LocalCapabilitiesUi
import com.sheen.rokid.phone.ui.settings.localCapabilityReasons
import com.sheen.rokid.plugin.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52 design §3.6, fix round 1: the two inputs the 本地能力 page actually receives in production.
 *
 * **Models:** the composition advertises `Capability.SystemOne`/`Embedding` unconditionally (the services exist and
 * refuse their own calls), so `SessionUiState.capabilityReasons` can never name them. Their real availability is the
 * bundled-model readiness gate; [localCapabilityReasons] is the adapter the shell applies before passing the map to
 * [com.sheen.rokid.phone.ui.settings.LocalCapabilitiesScreen]. This test drives real [ModelReadiness] states through it
 * instead of handing the page a synthetic reason code production cannot produce.
 *
 * **Channels:** the host publishes the four rows only around a session. The page must still name them before the first
 * session with the honest unknown reason, and must render published rows unchanged.
 */
class LocalCapabilitiesReasonsTest {

    private fun reasonsFor(readiness: ProvisioningState?): Map<Capability, String> =
        localCapabilityReasons(
            capabilityReasons = mapOf(Capability.Actions to "ACTION_PROVIDER_MISSING"),
            readiness = readiness,
        )

    @Test
    fun `a failed model bundle marks both local models unavailable through the production adapter`() {
        val readiness = ModelReadiness().apply { markFailed("MODELS_PROVISIONING_FAILED") }

        val reasons = reasonsFor(readiness.state.value)

        assertEquals("MODELS_UNAVAILABLE", reasons[Capability.SystemOne])
        assertEquals("MODELS_UNAVAILABLE", reasons[Capability.Embedding])
        // The existing page status path is what renders it; Task 15 maps the gate code to Chinese copy.
        assertEquals("尚不可用（模型不可用）", LocalCapabilitiesUi.modelStatus(reasons[Capability.SystemOne]))
        assertEquals("尚不可用（模型不可用）", LocalCapabilitiesUi.modelStatus(reasons[Capability.Embedding]))
    }

    @Test
    fun `a materializing model bundle reports preparing instead of available`() {
        val reasons = reasonsFor(ModelReadiness().state.value)

        assertEquals("MODELS_PREPARING", reasons[Capability.SystemOne])
        assertEquals("MODELS_PREPARING", reasons[Capability.Embedding])
        assertEquals("尚不可用（模型准备中）", LocalCapabilitiesUi.modelStatus(reasons[Capability.SystemOne]))
    }

    @Test
    fun `a ready bundle adds no model reason so absence still means available`() {
        val readiness = ModelReadiness().apply { markReady("bundle-1") }

        val reasons = reasonsFor(readiness.state.value)

        assertFalse(reasons.containsKey(Capability.SystemOne))
        assertFalse(reasons.containsKey(Capability.Embedding))
        assertEquals(LocalCapabilitiesUi.AVAILABLE, LocalCapabilitiesUi.modelStatus(reasons[Capability.SystemOne]))
        assertEquals(LocalCapabilitiesUi.AVAILABLE, LocalCapabilitiesUi.modelStatus(reasons[Capability.Embedding]))
    }

    @Test
    fun `a composition without readiness keeps the environment's capability map untouched`() {
        val environment = mapOf(Capability.Actions to "ACTION_PROVIDER_MISSING")

        assertSame(environment, localCapabilityReasons(environment, readiness = null))
    }

    @Test
    fun `readiness never hides the other capability reasons`() {
        val reasons = reasonsFor(ModelReadiness().apply { markFailed("MODELS_PROVISIONING_FAILED") }.state.value)

        assertEquals("ACTION_PROVIDER_MISSING", reasons[Capability.Actions])
    }

    @Test
    fun `before the first session every known channel names the unknown reason`() {
        val lines = LocalCapabilitiesUi.channelLines(emptyList())

        assertEquals(
            listOf("指令执行（Intent）", "无障碍通道", "Shizuku 通道", "Root"),
            lines.map { it.label },
        )
        assertEquals(List(4) { LocalCapabilitiesUi.CHANNEL_NOT_PROBED }, lines.map { it.status })
        assertTrue("the rows are unknown, not known-unavailable", lines.all { it.unknown })
        assertFalse(lines.any { it.available })
    }

    @Test
    fun `published channel rows keep the probe's own status and the root note`() {
        val published = listOf(
            ExecutionChannelStatus(INTENT_CHANNEL, available = true, reason = null),
            ExecutionChannelStatus(A11Y_CHANNEL, available = false, reason = "A11Y_DISABLED"),
            ExecutionChannelStatus(SHIZUKU_CHANNEL, available = false, reason = "SHIZUKU_UNAVAILABLE"),
            ExecutionChannelStatus(
                ROOT_CHANNEL,
                available = false,
                reason = "ROOT_UNAVAILABLE",
                note = "Root：不可用（设备无 su）",
            ),
        )

        val lines = LocalCapabilitiesUi.channelLines(published)

        assertEquals(4, lines.size)
        assertEquals(LocalCapabilitiesUi.AVAILABLE, lines[0].status)
        assertEquals("尚不可用（未启用无障碍）", lines[1].status)
        assertEquals("尚不可用（Shizuku 未运行或未授权）", lines[2].status)
        assertEquals("Root：不可用（设备无 su）", lines[3].status)
        assertFalse(lines.any { it.unknown })
    }
}
