package com.sheen.rokid.phone.actions.intent

import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local app-catalog tool (T2.3).
 *
 * `launch_app` was retired by the 2026-10-22 K70 acceptance (0 successful calls out of 10, all redundant with `open`);
 * this executor now advertises **only** `list_apps`, and the list is a local `PackageManager` read that needs no
 * Shizuku channel. Its output is line-formatted and truncated at 100 entries so a device with hundreds of packages
 * does not flood the context window.
 */
class LaunchAppExecutorTest {

    private class FakeCatalog(private val apps: List<AppEntry>) : AppCatalog {
        override fun visibleApps(): List<AppEntry> = apps
    }

    private fun entry(
        label: String,
        packageName: String = "pkg.${label.replace(' ', '.')}",
        launchIntentAction: String? = "android.intent.action.MAIN",
    ) = AppEntry(packageName, label, launchIntentAction)

    private fun executor(apps: List<AppEntry>) = LaunchAppExecutor(FakeCatalog(apps))

    @Test
    fun `the executor advertises only list_apps and is channel free`() {
        val executor = executor(emptyList())

        assertEquals(listOf("list_apps"), executor.descriptors().map { it.name })
        assertEquals(RiskLevel.L0, executor.descriptors().single().risk)
        assertTrue("a local PackageManager read has no channel to wait for", executor.available())
    }

    @Test
    fun `list_apps formats launchable apps one per line`() {
        val result = executor(listOf(entry("WeChat", "com.tencent.mm"), entry("设置", "com.android.settings")))
            .execute("list_apps", "{}")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("WeChat (com.tencent.mm)\n设置 (com.android.settings)", result.detail)
    }

    @Test
    fun `apps without a launch intent are omitted`() {
        val result = executor(listOf(entry("Visible"), entry("Hidden", "pkg.hidden", launchIntentAction = null)))
            .execute("list_apps", "{}")

        assertEquals("Visible (pkg.Visible)", result.detail)
    }

    @Test
    fun `an empty catalog is a Done with an empty list, not a failure`() {
        val result = executor(emptyList()).execute("list_apps", "{}")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("", result.detail)
    }

    @Test
    fun `a list over one hundred entries is truncated with an explicit marker`() {
        val apps = (1..101).map { entry("App$it", "pkg.app$it") }
        val result = executor(apps).execute("list_apps", "{}")

        val lines = result.detail.lines()
        assertEquals(101, lines.size)
        assertTrue(lines.first().startsWith("App1 ("))
        assertEquals("truncated: true", lines.last())
        assertEquals("the 101st app must not be printed", false, result.detail.contains("App101 ("))
    }

    @Test
    fun `any other action name is refused rather than executed`() {
        val result = executor(emptyList()).execute("launch_app", """{"name":"WeChat"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("UNKNOWN_ACTION", result.detail)
    }
}
