package com.sheen.rokid.phone.actions.intent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The spoken-name -> package decision `open_app` depends on.
 *
 * The rule under test is the measured one from the P3 agent probe: a tool must **expose** ambiguity instead of
 * heuristically picking, because a wrong pick launches the wrong app and neither the user nor the audit trail can
 * tell that the assistant guessed. So exactly one candidate has to survive every filter, and "two partial matches"
 * is deliberately not a candidate set — it is [LaunchResolution.Ambiguous].
 */
class AppLaunchResolverTest {

    private class FakeCatalog(private val apps: List<AppEntry>) : AppCatalog {
        override fun visibleApps(): List<AppEntry> = apps
    }

    private fun entry(label: String, packageName: String = "pkg.${label.replace(' ', '.')}",
        launchIntentAction: String? = "android.intent.action.MAIN") =
        AppEntry(packageName, label, launchIntentAction)

    private fun resolver(vararg apps: AppEntry) = AppLaunchResolver(FakeCatalog(apps.toList()))

    // ── 1. Exact match unique → Found ───────────────────────────────────────

    @Test
    fun exactMatchUniqueReturnsFound() {
        val result = resolver(entry("Camera", "com.camera"), entry("WeChat", "com.tencent.mm"))
            .resolve("camera")

        assertTrue("expected Found but was $result", result is LaunchResolution.Found)
        val found = result as LaunchResolution.Found
        assertEquals("com.camera", found.target.packageName)
        assertEquals("Camera", found.target.label)
    }

    // ── 2. Substring match unique → Found ───────────────────────────────────

    @Test
    fun substringMatchUniqueReturnsFound() {
        val result = resolver(entry("WeChat", "com.tencent.mm")).resolve("  wechat  ")

        assertTrue("expected Found but was $result", result is LaunchResolution.Found)
        assertEquals("com.tencent.mm", (result as LaunchResolution.Found).target.packageName)
    }

    // ── 3. Exact match >1 → Ambiguous with correct candidates ───────────────

    @Test
    fun exactMatchMultipleReturnsAmbiguous() {
        val result = resolver(
            entry("Camera", "com.one"),
            entry("Camera", "com.two")
        ).resolve("camera")

        assertTrue("expected Ambiguous but was $result", result is LaunchResolution.Ambiguous)
        val ambiguous = result as LaunchResolution.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
        val packages = ambiguous.candidates.map { it.packageName }.toSet()
        assertTrue(packages.contains("com.one"))
        assertTrue(packages.contains("com.two"))
    }

    // ── 4. Substring match >1 → Ambiguous with correct candidates ───────────

    @Test
    fun substringMatchMultipleReturnsAmbiguous() {
        val result = resolver(entry("Camera"), entry("CamScanner")).resolve("cam")

        assertTrue("expected Ambiguous but was $result", result is LaunchResolution.Ambiguous)
        val ambiguous = result as LaunchResolution.Ambiguous
        assertEquals(2, ambiguous.candidates.size)
        val labels = ambiguous.candidates.map { it.label }.toSet()
        assertTrue(labels.contains("Camera"))
        assertTrue(labels.contains("CamScanner"))
    }

    // ── 5. No match → NotFound ──────────────────────────────────────────────

    @Test
    fun unknownNameReturnsNotFound() {
        val result = resolver(entry("Camera"), entry("WeChat")).resolve("地图")

        assertTrue("expected NotFound but was $result", result is LaunchResolution.NotFound)
    }

    // ── 6. Empty query → NotFound ───────────────────────────────────────────

    @Test
    fun blankQueryReturnsNotFound() {
        val r = resolver(entry("Camera"))

        assertTrue("empty string should be NotFound", r.resolve("") is LaunchResolution.NotFound)
        assertTrue("whitespace-only should be NotFound", r.resolve("   ") is LaunchResolution.NotFound)
    }

    // ── 7. Only non-launchable apps match → NotFound ────────────────────────

    @Test
    fun onlyNonLaunchableAppsMatchReturnsNotFound() {
        val result = resolver(
            entry("Camera", "com.stub", launchIntentAction = null)
        ).resolve("camera")

        assertTrue("expected NotFound but was $result", result is LaunchResolution.NotFound)
    }

    // ── Preserved behaviors adapted to new return type ──────────────────────

    @Test
    fun anExactLabelMatchDoesNotHideANearbyCandidate() {
        val result = resolver(entry("Camera Pro", "com.pro"), entry("Camera", "com.camera"))
            .resolve("camera")

        assertTrue("expected Ambiguous but was $result", result is LaunchResolution.Ambiguous)
        val ambiguous = result as LaunchResolution.Ambiguous
        assertEquals(setOf("com.pro", "com.camera"), ambiguous.candidates.map { it.packageName }.toSet())
    }

    @Test
    fun aGenericFileWordReturnsBothFileApps() {
        val result = resolver(
            entry("文件管理", "com.android.fileexplorer"),
            entry("文件", "com.google.android.documentsui"),
        ).resolve("文件")

        assertTrue("P4 requires `文件` to be ambiguous, was $result", result is LaunchResolution.Ambiguous)
        val ambiguous = result as LaunchResolution.Ambiguous
        assertEquals(
            setOf("com.android.fileexplorer", "com.google.android.documentsui"),
            ambiguous.candidates.map { it.packageName }.toSet(),
        )
    }

    @Test
    fun matchingIsCaseInsensitiveAndTrimsTheSpokenName() {
        val result = resolver(entry("WeChat", "com.tencent.mm")).resolve("  wechat  ")

        assertTrue(result is LaunchResolution.Found)
        assertEquals("com.tencent.mm", (result as LaunchResolution.Found).target.packageName)
    }

    @Test
    fun anEntryWithoutALaunchIntentIsSkipped() {
        val launchable = resolver(
            entry("Camera", "com.stub", launchIntentAction = null),
            entry("Camera", "com.camera")
        ).resolve("camera")

        assertTrue(launchable is LaunchResolution.Found)
        assertEquals("com.camera", (launchable as LaunchResolution.Found).target.packageName)

        val onlyStub = resolver(
            entry("Camera", "com.stub", launchIntentAction = null)
        ).resolve("camera")
        assertTrue("an app that cannot be launched must not be offered at all",
            onlyStub is LaunchResolution.NotFound)
    }
}
