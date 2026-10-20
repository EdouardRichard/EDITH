package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.phone.plugin.smarthelper.prompt.PromptCatalog
import com.sheen.rokid.phone.plugin.smarthelper.prompt.profileFixture

import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.DeactivationReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 0.3 Phase 1 Task 12 (spec §4.1/§4.2.1): the one prompt-provenance line.
 *
 * `P3_PROMPT` is how a device log can prove **which** prompt ran without ever printing it: the catalog version, the
 * ordered-section hash, the deterministic device-profile hash and the static character count. It is emitted once at
 * activation, because the plan is pinned for the session; a per-request line would claim a reload that does not
 * happen. The line is asserted to be ASCII/counts only, so a future edit that prints the system text (or a device
 * value) fails here.
 */
class PromptTelemetryTest {

    private val profile = profileFixture(model = "M2012K11AC")

    /** The production activation path: the device provider arrives through `services.deviceState()`. */
    private fun activateWithDeviceProfile(rig: SmartTestRig = SmartTestRig()): SmartTestRig {
        rig.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.DeviceState to SmartFakeDeviceState(profile),
            ),
        )
        return rig
    }

    /** Runs [block] with the plugin's `P3` sink diverted; the previous sink is restored even when it throws. */
    private fun withLines(block: () -> Unit): List<String> {
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        return try {
            block()
            lines.toList()
        } finally {
            P3Log.sink = previous
        }
    }

    private fun promptLine(lines: List<String>): String =
        lines.filter { it.startsWith("P3_PROMPT ") }.single()

    @Test
    fun `P3_PROMPT carries version, hashes and sizes but no prompt text`() {
        val lines = withLines { activateWithDeviceProfile() }

        val line = promptLine(lines)
        val plan = PromptCatalog.plan(ContextRoute.WAKE, profile)
        assertTrue(
            Regex("""^P3_PROMPT version=0\.3\.0 hash=[0-9a-f]{64} deviceHash=[0-9a-f]{64} staticChars=\d+$""")
                .matches(line),
            "the line must carry exactly the pinned plan's identity and sizes: $line",
        )
        assertTrue("hash=${plan.hash}" in line, "the hash must fingerprint the rendered plan: $line")
        assertTrue(
            "staticChars=${plan.systemText.length}" in line,
            "staticChars must be the plan's own system length: $line",
        )
        // No prompt prose, no device values, no user/model text: the whole line is ASCII counts and hex.
        assertTrue(line.all { it.code < 128 }, "P3_PROMPT must never carry prompt or device text: $line")
        assertFalse(line.contains("M2012K11AC"), "a device value leaked into the log: $line")
        assertFalse(line.contains("FixtureCorp"), "a device value leaked into the log: $line")
        assertFalse(line.contains("你是一名"), "prompt prose leaked into the log: $line")
        assertFalse(line.contains("【设备画像】"), "prompt prose leaked into the log: $line")
    }

    @Test
    fun `P3_PROMPT is emitted exactly once per activation`() {
        val rig = SmartTestRig()
        val lines = withLines {
            activateWithDeviceProfile(rig)
            // A live session announces itself once; turns and re-configuration do not re-announce it.
            rig.plugin.deactivate(DeactivationReason.Stopped)
            activateWithDeviceProfile(rig)
        }

        assertEquals(
            2,
            lines.count { it.startsWith("P3_PROMPT ") },
            "one line per activation, and no line for the deactivate in between: $lines",
        )
    }

    @Test
    fun `no device profile is announced as deviceHash none instead of inventing one`() {
        val rig = SmartTestRig()
        val lines = withLines {
            rig.activate(overrides = mapOf(Capability.Storage to SmartFakeStorage()))
        }

        val line = promptLine(lines)
        assertTrue("deviceHash=none" in line, "a missing provider is a stable 'none', not an invented profile: $line")
        val plan = PromptCatalog.plan(ContextRoute.WAKE, null)
        assertTrue("hash=${plan.hash}" in line, "the plan hash must be the profile-less plan: $line")
        assertTrue("staticChars=${plan.systemText.length}" in line, line)

        // And the system prompt really omitted the device section.
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }
        rig.wakeAndFinal("现在几点了")
        val instructions = rig.model.requests.single().instructions
        assertEquals(plan.systemText, instructions, "no profile means the catalog's profile-less plan")
        assertFalse("【设备画像】" in instructions, "the device section must be omitted: $instructions")
    }

    @Test
    fun `the device hash follows the device profile and is deterministic`() {
        fun hashFor(model: String): String = withLines {
            SmartTestRig().activate(
                overrides = mapOf(
                    Capability.Storage to SmartFakeStorage(),
                    Capability.DeviceState to SmartFakeDeviceState(profileFixture(model = model)),
                ),
            )
        }.filter { it.startsWith("P3_PROMPT ") }.single().substringAfter("deviceHash=").substringBefore(' ')

        val first = hashFor("M2012K11AC")
        val second = hashFor("M2012K11AC")
        val other = hashFor("M2012K11AG")

        assertEquals(first, second, "the same profile must fingerprint the same")
        assertNotEquals(first, other, "a different device profile must fingerprint differently")
        assertNotEquals("none", first, "a supplied profile must not read as absent")
    }
}
