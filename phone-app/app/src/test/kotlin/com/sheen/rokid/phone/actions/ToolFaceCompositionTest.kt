package com.sheen.rokid.phone.actions

import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * edith-0.3 Phase 4A Task 4 (A4): the **production composition** (`HostCompositionRoot.create`) installs the
 * 22-host-tool `ToolSurface` as the one `Capability.Actions` provider.
 *
 * The JVM composition is the honest environment for this test: the accessibility service is not bound and Shizuku's
 * binder is absent, so every channel-dependent leg is down. That is exactly the state the stable-advertising rule is
 * about — the face must stay 22 and `execute` must refuse, not disappear.
 */
class ToolFaceCompositionTest {

    private val hostNames = listOf(
        // atomic a11y
        "read_screen", "tap", "long_press", "scroll", "input_text",
        // atomic intent without `dial`
        "open_app", "open_url", "open_settings", "set_alarm", "compose_note",
        // atomic Shizuku
        "clear_data", "uninstall", "disable_app", "set_permission", "set_brightness",
        // atomic local directory / system keys (`launch_app` retired 2026-10-22, 24 → 23)
        "list_apps", "system_action",
        // the umbrella catalog
        "device", "shell", "open", "dial",
    )

    @Test
    fun `the production composition wires the 21 host tools into the one Actions surface`() {
        val root = HostCompositionRoot.create(android.content.ContextWrapper(null))
        try {
            val surface = requireNotNull(root.toolSurfaceForProbe) {
                "the composition must expose the wired Actions provider for the JVM gate"
            }

            val names = surface.descriptors().map { it.name }

            assertEquals(21, names.size)
            assertEquals("no duplicate name may be advertised", 21, names.toSet().size)
            assertEquals(hostNames, names)
            assertEquals("a second read is the same fixed schema", names, surface.descriptors().map { it.name })

            val dials = surface.descriptors().filter { it.name == "dial" }
            assertEquals("`dial` must have one owner", 1, dials.size)
            assertEquals("the owner is the catalog's L1 DialTool", RiskLevel.L1, dials.single().risk)
        } finally {
            root.close()
        }
    }

    @Test
    fun `the catalog probe still points at the catalog, not the surface`() {
        val root = HostCompositionRoot.create(android.content.ContextWrapper(null))
        try {
            val catalog = requireNotNull(root.toolCatalogForProbe) {
                "the debug probe must keep pointing at the umbrella catalog"
            }

            assertEquals(
                listOf("device", "shell", "open", "dial"),
                catalog.descriptors().map { it.name },
            )
        } finally {
            root.close()
        }
    }

    @Test
    fun `a blind JVM composition keeps the 21 advertised and refuses the channel dependent legs`() {
        val root = HostCompositionRoot.create(android.content.ContextWrapper(null))
        try {
            val surface = requireNotNull(root.toolSurfaceForProbe)

            // a11y is unbound on the JVM: the atomic leg refuses with its own channel code, the umbrella catalog with
            // `CHANNEL_UNAVAILABLE` — both are stable refusals and neither removes a tool from the face.
            val tap = surface.execute("tap", """{"index":0}""")
            assertEquals(ActionOutcome.Refused, tap.outcome)
            assertEquals("ACTION_CHANNEL_UNAVAILABLE", tap.detail)

            val readScreen = surface.execute("read_screen", "{}")
            assertEquals(ActionOutcome.Refused, readScreen.outcome)
            assertEquals("ACTION_CHANNEL_UNAVAILABLE", readScreen.detail)

            val clearData = surface.execute("clear_data", """{"package":"com.example.app"}""")
            assertEquals(ActionOutcome.Refused, clearData.outcome)
            assertEquals("ACTION_CHANNEL_UNAVAILABLE", clearData.detail)

            // The umbrella refusal is proven through `shell`, not `device observe`: on a JVM the absent KeyguardManager
            // reads as a locked phone, so `device`/`open`/`dial` intentionally enter the unlock gate and wait there.
            // `shell` has no unlock gate (a privileged command needs no UI), so it answers the catalog's stable refusal.
            val shell = surface.execute("shell", """{"command":"id"}""")
            assertEquals(ActionOutcome.Refused, shell.outcome)
            assertEquals("CHANNEL_UNAVAILABLE", shell.execution?.code)

            // Nothing above may have changed the advertised schema.
            assertEquals(21, surface.descriptors().size)
        } finally {
            root.close()
        }
    }

    @Test
    fun `an unknown name is refused by the surface rather than reaching a leg`() {
        val root = HostCompositionRoot.create(android.content.ContextWrapper(null))
        try {
            val surface = requireNotNull(root.toolSurfaceForProbe)

            val result = surface.execute("teleport", "{}")

            assertEquals(ActionOutcome.Refused, result.outcome)
            assertEquals(ToolSurface.UNKNOWN_ACTION, result.execution?.code)
            assertEquals("{}", result.detail)
            assertFalse("an unknown name applied nothing", result.execution!!.applied == true)
        } finally {
            root.close()
        }
    }
}
