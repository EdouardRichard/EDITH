package com.sheen.rokid.phone.actions

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * edith-0.3 Phase 4A Task 5 (A5): the **routing** half of the tool-face contract, as a JVM decision test (spec §4.3).
 *
 * The prompt resources are migrated by Phase 4A Task 6, so at this point the only tier guidance the model receives is
 * the descriptor prose. Each test below is one routing scenario the model has to get right, and asserts the sentence a
 * correct decision depends on. A description that loses its tier clause fails this suite even though every schema is
 * unchanged — that is the point. Device latency is not measured here (Phase 4B's matrix owns it).
 */
class ToolRoutingTest {

    private val face: Map<String, com.sheen.rokid.plugin.ActionDescriptor> by lazy { ToolFaceFixture.face() }

    @Test
    fun `a one step read routes to read_screen while structural work stays on device`() {
        val readScreen = face.getValue("read_screen").description

        assertTrue("a single read must say the umbrella is the heavier route: $readScreen", readScreen.contains("用 device"))
        assertTrue("the atomic read is only useful if the model knows its filter argument: $readScreen", readScreen.contains("filter"))
        assertFalse(
            "read_screen must not advertise screenshot/act powers it does not have: $readScreen",
            readScreen.contains("screenshot"),
        )

        assertTrue(
            "device must claim the ref-based multi-step route: ${face.getValue("device").description}",
            face.getValue("device").description.contains("ref"),
        )
    }

    @Test
    fun `a multi step observe act verify route stays on device`() {
        val device = face.getValue("device").description

        listOf("observe", "act", "ref", "STALE_REF").forEach { token ->
            assertTrue("a multi-step route depends on '$token' being explained: $device", device.contains(token))
        }
    }

    @Test
    fun `an ambiguous app name routes to open, which can carry the candidates`() {
        val open = face.getValue("open").description
        val openApp = face.getValue("open_app").description

        assertTrue("open must name the ambiguity refusal: $open", open.contains("APP_AMBIGUOUS"))
        assertTrue("open must keep the no-Shizuku fallback discoverable: $open", open.contains("open_app"))
        assertTrue("open_app must also surface ambiguity now: $openApp", openApp.contains("APP_AMBIGUOUS"))
    }

    @Test
    fun `a known exact app name may route to the atomic open_app, which points back at open`() {
        val openApp = face.getValue("open_app").description

        assertTrue(
            "open_app must name the missing-app code: $openApp",
            openApp.contains("APP_NOT_FOUND"),
        )
        assertTrue("open_app must name the ambiguity code: $openApp", openApp.contains("APP_AMBIGUOUS"))
        assertTrue("open_app must point at open for the ambiguous case: $openApp", openApp.contains("open"))
    }

    @Test
    fun `a dropped channel keeps the same tier and names the stable refusal`() {
        // A channel that went down does not change which tool is right: the call must come back with the stable code
        // and the model must wait for recovery instead of switching to a different tier or simulating through shell.
        listOf(
            "read_screen", "tap", "long_press", "scroll", "input_text",
            "system_action",
        ).forEach { name ->
            val description = face.getValue(name).description
            assertTrue("$name must name its channel refusal: $description", description.contains("ACTION_CHANNEL_UNAVAILABLE"))
            assertTrue("$name must say the channel can come back: $description", description.contains("恢复"))
        }
        assertTrue(
            "the umbrella layer names its own refusal: ${face.getValue("device").description}",
            face.getValue("device").description.contains("CHANNEL_UNAVAILABLE"),
        )
        assertTrue(
            "shell names the unbound channel: ${face.getValue("shell").description}",
            face.getValue("shell").description.contains("SHELL_NOT_BOUND"),
        )
    }

    @Test
    fun `an irreversible action defers confirmation to the current permission policy`() {
        listOf("clear_data", "uninstall", "disable_app", "set_permission").forEach { name ->
            val description = face.getValue(name).description
            assertTrue(
                "$name must tell the model confirmation depends on the current policy: $description",
                description.contains("权限策略"),
            )
            assertTrue("$name must still name the confirmation: $description", description.contains("确认"))
            assertFalse(
                "$name must not promise a question the current default (L1) ceiling refuses instead: $description",
                description.contains("执行前用户会收到确认"),
            )
        }
    }
}
