package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.device.Bounds
import com.sheen.rokid.device.DeviceNode
import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.ObservationRenderer
import com.sheen.rokid.device.ObservationWindow
import com.sheen.rokid.device.RefRegistry
import com.sheen.rokid.device.ScreenFilter
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R41 (`data.screen`): the full/diff projection, its `app`/`ime`/`activity`/`rev`/`truncated`
 * mapping, `removed` carrying the *old* ref, and a renderer failure degrading to a diagnostic
 * instead of crashing the tool.
 */
class ScreenProjectorTest {

    private val registry = RefRegistry()

    private fun projector() = ScreenProjector(ObservationRenderer(registry), registry)

    @Test
    fun `the first projection is a full render with no change lists`() {
        val projection = projector().project(observation(rev = 7, nodes = listOf(node("Wi-Fi"))), ScreenFilter.ALL)
        val screen = projection.screen()

        assertEquals(7L, screen["rev"]!!.jsonPrimitive.long)
        assertEquals("com.example.settings", screen["app"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, screen["activity"])
        assertFalse(screen["ime"]!!.jsonPrimitive.boolean)
        assertFalse(screen["truncated"]!!.jsonPrimitive.boolean)
        assertEquals("full", screen.changes()["mode"]!!.jsonPrimitive.content)
        assertTrue(screen.changes()["added"]!!.jsonArray.isEmpty())
        assertTrue(screen.changes()["updated"]!!.jsonArray.isEmpty())
        assertTrue(screen.changes()["removed"]!!.jsonArray.isEmpty())
        assertTrue(screen["text"]!!.jsonPrimitive.content.contains("#n"))
    }

    @Test
    fun `the application window supplies app and an ime window flips ime`() {
        val windows = listOf(
            window(id = 0, type = "ime", packageName = null),
            window(id = 1, type = "application", packageName = "com.android.settings"),
        )

        val screen = projector()
            .project(observation(rev = 1, nodes = listOf(node("Wi-Fi")), windows = windows), ScreenFilter.ALL)
            .screen()

        assertEquals("com.android.settings", screen["app"]!!.jsonPrimitive.content)
        assertTrue(screen["ime"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `the foreground activity comes from the injected lambda`() {
        // edith-0.3 Phase 2 Task 7: the observation model has no activity class, so the screen projection asks the
        // same foreground fact the perception provider uses. A throwing lambda degrades to null, never a crash.
        val activity = ScreenProjector(
            ObservationRenderer(registry),
            registry,
            foregroundActivity = { ".SettingsActivity" },
        )
        val thrown = ScreenProjector(
            ObservationRenderer(RefRegistry()),
            RefRegistry(),
            foregroundActivity = { error("foreground unavailable") },
        )

        assertEquals(
            ".SettingsActivity",
            activity.project(observation(rev = 1), ScreenFilter.ALL).screen()["activity"]!!.jsonPrimitive.content,
        )
        assertEquals(
            JsonNull,
            thrown.project(observation(rev = 1), ScreenFilter.ALL).screen()["activity"],
        )
    }

    @Test
    fun `no application window maps app to null`() {
        val screen = projector().project(observation(windows = emptyList()), ScreenFilter.ALL).screen()

        assertEquals(JsonNull, screen["app"])
        assertFalse(screen["ime"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a second observation is a diff whose updated lines carry the new ref`() {
        val project = projector()
        project.project(
            observation(rev = 1, nodes = listOf(node("A"), node("B"), node("C"))),
            ScreenFilter.ALL,
        )

        val screen = project.project(
            observation(rev = 2, nodes = listOf(node("A"), node("B-changed"), node("C"))),
            ScreenFilter.ALL,
        ).screen()

        assertEquals("diff", screen.changes()["mode"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, screen["text"])
        val updated = screen.changes()["updated"]!!.jsonArray
        assertEquals(1, updated.size)
        val line = updated[0].jsonPrimitive.content
        assertTrue("updated line must carry the new ref: $line", line.startsWith("#n"))
        assertTrue("updated line must describe the new node: $line", line.contains("\"B-changed\""))
        assertTrue(screen.changes()["added"]!!.jsonArray.isEmpty())
        assertTrue(screen.changes()["removed"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `removed carries the old ref string`() {
        val project = projector()
        project.project(observation(rev = 1, nodes = listOf(node("A"), node("B", viewId = "id/b"))), ScreenFilter.ALL)

        val screen = project.project(observation(rev = 2, nodes = listOf(node("A"))), ScreenFilter.ALL).screen()

        val removed = screen.changes()["removed"]!!.jsonArray.map { it.jsonPrimitive.content }
        // "A" keeps #n0; "B" was the second root, so the process's only issued refs are #n0 and #n1.
        assertEquals(listOf("#n1"), removed)
    }

    @Test
    fun `a truncated observation never reports removals`() {
        val project = projector()
        project.project(observation(rev = 1, nodes = listOf(node("A"), node("B", viewId = "id/b"))), ScreenFilter.ALL)

        val screen = project.project(
            observation(rev = 2, nodes = listOf(node("A")), truncated = true),
            ScreenFilter.ALL,
        ).screen()

        assertTrue(screen["truncated"]!!.jsonPrimitive.boolean)
        assertTrue("absence in a truncated tree proves nothing", screen.changes()["removed"]!!.jsonArray.isEmpty())
    }

    @Test
    fun `the diff threshold falls back to a full render`() {
        val project = projector()
        project.project(observation(rev = 1, nodes = listOf(node("A"))), ScreenFilter.ALL)

        val many = (0 until 100).map { node("Item-$it", viewId = "id/$it") }
        val screen = project.project(observation(rev = 2, nodes = many), ScreenFilter.ALL).screen()

        assertEquals("full", screen.changes()["mode"]!!.jsonPrimitive.content)
        assertTrue(screen.changes()["added"]!!.jsonArray.isEmpty())
        assertTrue(screen["text"]!!.jsonPrimitive.content.contains("#n"))
    }

    @Test
    fun `a renderer failure becomes a diagnostic screen null instead of an exception`() {
        val broken = ScreenProjector(
            registry = registry,
            renderFull = { _, _ -> error("boom") },
            renderChanged = { _, _ -> error("boom") },
        )

        val projection = broken.project(observation(rev = 1, nodes = listOf(node("A"))), ScreenFilter.ALL)

        assertEquals(JsonNull, projection["screen"])
        assertEquals("boom", projection["diagnostic"]!!.jsonPrimitive.content)
    }

    private fun JsonObject.screen(): JsonObject = this["screen"]!!.jsonObject

    private fun JsonObject.changes(): JsonObject = this["changes"]!!.jsonObject

    private fun observation(
        rev: Long = 1,
        nodes: List<DeviceNode> = listOf(node("Wi-Fi")),
        windows: List<ObservationWindow> = listOf(window()),
        truncated: Boolean = false,
    ): Observation = Observation(rev, windows, nodes, truncated)

    private fun node(
        text: String?,
        viewId: String? = "id/${text.orEmpty()}",
        depth: Int = 0,
        windowId: Int = 0,
    ): DeviceNode = DeviceNode(
        role = "button",
        text = text,
        contentDescription = null,
        viewId = viewId,
        bounds = Bounds(0, 0, 100, 50),
        states = setOf("clickable"),
        actions = listOf("click"),
        hint = null,
        paneTitle = null,
        windowId = windowId,
        depth = depth,
    )

    private fun window(
        id: Int = 0,
        type: String = "application",
        packageName: String? = "com.example.settings",
    ): ObservationWindow = ObservationWindow(
        id = id,
        type = type,
        layer = 0,
        bounds = Bounds(0, 0, 1080, 2400),
        packageName = packageName,
        title = null,
    )
}
