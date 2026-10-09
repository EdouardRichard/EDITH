package edith.phone.actions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The spoken-name -> package decision `open_app` depends on.
 *
 * The rule under test is the measured one from the P3 agent probe: a tool must **expose** ambiguity instead of
 * heuristically picking, because a wrong pick launches the wrong app and neither the user nor the audit trail can
 * tell that the assistant guessed. So exactly one candidate has to survive every filter, and "two partial matches"
 * is deliberately not a candidate set.
 */
class AppLaunchResolverTest {

    private class FakeCatalog(private val apps: List<AppEntry>) : AppCatalog {
        override fun visibleApps(): List<AppEntry> = apps
    }

    private fun entry(label: String, packageName: String = "pkg.${label.replace(' ', '.')}",
        launchIntentAction: String? = "android.intent.action.MAIN") =
        AppEntry(packageName, label, launchIntentAction)

    private fun resolver(vararg apps: AppEntry) = AppLaunchResolver(FakeCatalog(apps.toList()))

    @Test
    fun anExactLabelMatchWinsOverASubstringMatch() {
        val target = resolver(entry("Camera Pro", "com.pro"), entry("Camera", "com.camera")).resolve("camera")

        assertEquals("com.camera", target?.packageName)
        assertEquals("Camera", target?.label)
    }

    @Test
    fun matchingIsCaseInsensitiveAndTrimsTheSpokenName() {
        val target = resolver(entry("WeChat", "com.tencent.mm")).resolve("  wechat  ")

        assertEquals("com.tencent.mm", target?.packageName)
    }

    @Test
    fun aBlankQueryResolvesToNothing() {
        val resolver = resolver(entry("Camera"))

        assertNull(resolver.resolve(""))
        assertNull(resolver.resolve("   "))
    }

    @Test
    fun anEntryWithoutALaunchIntentIsSkipped() {
        val launchable = resolver(entry("Camera", "com.stub", launchIntentAction = null),
            entry("Camera", "com.camera")).resolve("camera")

        assertEquals("com.camera", launchable?.packageName)
        assertNull("an app that cannot be launched must not be offered at all",
            resolver(entry("Camera", "com.stub", launchIntentAction = null)).resolve("camera"))
    }

    @Test
    fun twoPartialMatchesAreAmbiguousSoNothingIsPicked() {
        val target = resolver(entry("Camera"), entry("CamScanner")).resolve("cam")

        assertNull("two partial matches must be exposed as ambiguity, never guessed between", target)
    }

    @Test
    fun twoExactMatchesAreAmbiguousSoNothingIsPicked() {
        val target = resolver(entry("Camera", "com.one"), entry("Camera", "com.two")).resolve("camera")

        assertNull(target)
    }

    @Test
    fun anUnknownNameResolvesToNothing() {
        assertNull(resolver(entry("Camera"), entry("WeChat")).resolve("地图"))
    }
}
