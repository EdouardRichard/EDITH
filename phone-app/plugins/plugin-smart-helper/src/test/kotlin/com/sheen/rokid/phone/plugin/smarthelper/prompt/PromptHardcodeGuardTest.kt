package com.sheen.rokid.phone.plugin.smarthelper.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Production prompt resources may contain policy and placeholders, never concrete device facts
 * (spec §4.2.1 rule 1, §4.6). Otherwise every device-wording change would silently ship a prompt
 * that describes the developer's phone rather than the wearer's. The scan covers the system
 * sections **and** A7's independent loop/pinned/summarizer fragments, which are model-facing too.
 *
 * This is the resource-side guard; `PromptDeviceFidelityTest` proves that the values really come
 * from the device at render time.
 */
class PromptHardcodeGuardTest {

    private val fixedResources = listOf(
        "core/00-identity.md",
        "core/10-operating.md",
        "core/20-tools.md",
        "core/30-safety.md",
        "core/40-output.md",
        "core/50-memory-policy.md",
        "route/wake.md",
        "route/proactive-hint.md",
        "route/proactive-followup.md",
        // A7's independent fixed prompts are model-facing too: the loop instructions, the pinned gate
        // question/options and the summarizer template must not acquire a device fact either.
        "loop/retry.md",
        "loop/plan-nudge.md",
        "loop/timeout.md",
        "pinned/proactive-gate.md",
        "pinned/proactive-gate-options.md",
        "summarizer.md",
    )

    private val deviceLiterals = listOf(
        "K40",
        "K70",
        "Rokid",
        "MIUI",
        "Redmi",
        "Xiaomi",
        "Android 13",
        "Android 14",
        "API 33",
        "1080",
        "2400",
        "com.sheen",
        "1.26.011",
    )

    @Test
    fun `the device profile resource only uses device profile placeholders`() {
        val text = promptText("context/device-profile.md")

        for (literal in deviceLiterals) {
            assertFalse(text.contains(literal), "context/device-profile.md must not hardcode $literal")
        }
        assertEquals(DEVICE_PROFILE_SLOTS, placeholdersOf(text))
    }

    @Test
    fun `the runtime state template only uses the per request slots`() {
        val text = PromptCatalog.dynamicTemplate()

        for (literal in deviceLiterals) {
            assertFalse(text.contains(literal), "context/runtime-state.md must not hardcode $literal")
        }
        assertEquals(DYNAMIC_SLOTS, placeholdersOf(text))
    }

    @Test
    fun `no prompt resource carries a concrete date or wall clock value`() {
        val date = Regex("""\b20\d{2}-\d{2}-\d{2}\b""")
        val clock = Regex("""\b([01]\d|2[0-3]):[0-5]\d\b""")

        for (resource in fixedResources + "context/device-profile.md") {
            val text = promptText(resource)
            assertFalse(date.containsMatchIn(text), "$resource must not hardcode a date")
            assertFalse(clock.containsMatchIn(text), "$resource must not hardcode a wall clock time")
        }
    }

    private fun placeholdersOf(text: String): Set<String> =
        PLACEHOLDER.findAll(text).map { it.groupValues[1] }.toSet()

    private companion object {
        val PLACEHOLDER = Regex("""\{\{([A-Za-z_][A-Za-z0-9_]*)\}\}""")

        /** The 17 `DeviceProfile` facts of spec §4.2.1, no more and no fewer. */
        val DEVICE_PROFILE_SLOTS = setOf(
            "deviceManufacturer",
            "deviceModel",
            "deviceName",
            "androidRelease",
            "apiLevel",
            "screenWidth",
            "screenHeight",
            "densityDpi",
            "glassesModel",
            "glassesFirmware",
            "glassesWidth",
            "glassesHeight",
            "appVersion",
            "locale",
            "timeZone",
            "wakeWords",
            "capabilities",
        )

        /** The per-request slots of spec §4.3. */
        val DYNAMIC_SLOTS = setOf(
            "date",
            "weekday",
            "time",
            "timeZone",
            "deviceState",
            "foreground",
            "media",
            "perception",
            "coverage",
            "notifications",
            "calendar",
            "acoustic",
            "clipboard",
            "memoryEvidence",
            "skillBodies",
            "loopState",
        )
    }
}
