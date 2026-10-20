package com.sheen.rokid.phone.perception

import com.sheen.rokid.plugin.PerceptionCoverage
import com.sheen.rokid.plugin.PerceptionElement
import com.sheen.rokid.plugin.PerceptionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * edith-0.3 Phase 2 Task 7: the compact, model-facing screen text.
 *
 * The shape is one element per line, `#n12 role "text" center=(x,y) [flags]`. Bounds stay on the evidence contract
 * ([PerceptionElement.bounds]) and must never leak into this text: the model is not allowed to reason in coordinates
 * (D84), and a rendered `bounds=` would turn the compact view back into a coordinate instruction.
 */
class CompactSnapshotRendererTest {

    @Test
    fun `renders one compact line per element with center and only the true flags`() {
        val snapshot = snapshot(
            elements = listOf(
                element(
                    ref = "#n12",
                    role = "button",
                    text = "搜索",
                    centerX = 540,
                    centerY = 320,
                    clickable = true,
                    bounds = "360,280,720,360",
                ),
                element(
                    ref = "#n13",
                    role = "text",
                    text = "稻香 - 周杰伦",
                    centerX = 540,
                    centerY = 180,
                    clickable = false,
                ),
                element(
                    ref = "#n14",
                    role = "edittext",
                    text = "输入框",
                    centerX = 540,
                    centerY = 470,
                    clickable = true,
                    editable = true,
                ),
            ),
        )

        val text = CompactSnapshotRenderer.render(snapshot)

        assertEquals(
            """
            #n12 button "搜索" center=(540,320) [clickable]
            #n13 text "稻香 - 周杰伦" center=(540,180)
            #n14 edittext "输入框" center=(540,470) [clickable,editable]
            """.trimIndent(),
            text,
        )
    }

    @Test
    fun `never renders bounds or a coordinates-only instruction`() {
        val snapshot = snapshot(
            elements = listOf(
                element(
                    ref = "#n1",
                    role = "button",
                    text = "确定",
                    centerX = 10,
                    centerY = 20,
                    bounds = "0,0,20,40",
                    clickable = true,
                ),
            ),
        )

        val text = CompactSnapshotRenderer.render(snapshot)

        assertFalse("compact text must not carry bounds=: $text", text.contains("bounds="))
        assertFalse("the raw bounds must not leak: $text", text.contains("0,0,20,40"))
        assertFalse("no click-at coordinate instruction: $text", text.contains("点(", ignoreCase = true))
    }

    @Test
    fun `falls back to the content description and an unknown role`() {
        val snapshot = snapshot(
            elements = listOf(
                element(
                    ref = "#n7",
                    role = "",
                    text = null,
                    contentDescription = "关闭",
                    centerX = 1,
                    centerY = 2,
                ),
            ),
        )

        assertEquals("#n7 unknown \"关闭\" center=(1,2)", CompactSnapshotRenderer.render(snapshot))
    }

    @Test
    fun `keeps one element per line when the platform text carries control characters and quotes`() {
        val snapshot = snapshot(
            elements = listOf(
                element(ref = "#n1", text = "第一行\n第二行", centerX = 1, centerY = 1),
                element(ref = "#n2", text = "say \"hi\"", centerX = 2, centerY = 2),
            ),
        )

        val lines = CompactSnapshotRenderer.render(snapshot).lines()

        assertEquals("a line break in the platform text must not become an element line", 2, lines.size)
        assertTrue(lines[0].startsWith("#n1 "))
        assertTrue(lines[1].startsWith("#n2 "))
        assertTrue("quotes are escaped so the field stays readable: ${lines[1]}", lines[1].contains("say \\\"hi\\\""))
    }

    @Test
    fun `marks truncation once and only when the snapshot is truncated`() {
        val truncated = snapshot(
            elements = listOf(element(ref = "#n1", text = "A", centerX = 1, centerY = 1)),
            truncated = true,
        )

        val text = CompactSnapshotRenderer.render(truncated)

        assertTrue("a bounded snapshot must say so: $text", text.lines().last().contains("truncated"))
        assertEquals(1, text.lines().count { it.contains("truncated") })
        assertFalse(CompactSnapshotRenderer.render(snapshot(truncated = false)).contains("truncated"))
    }

    @Test
    fun `renders an empty snapshot as empty text`() {
        assertEquals("", CompactSnapshotRenderer.render(snapshot(elements = emptyList())))
    }

    // - helpers -

    private fun snapshot(
        elements: List<PerceptionElement> = emptyList(),
        truncated: Boolean = false,
    ): PerceptionSnapshot = PerceptionSnapshot(
        snapshotId = "snap-1",
        capturedAtEpochMillis = 0L,
        elements = elements,
        coverage = coverage(),
        truncated = truncated,
    )

    private fun element(
        ref: String,
        role: String = "text",
        text: String? = null,
        contentDescription: String? = null,
        centerX: Int? = 0,
        centerY: Int? = 0,
        clickable: Boolean = false,
        editable: Boolean = false,
        bounds: String? = null,
    ): PerceptionElement = PerceptionElement(
        ref = ref,
        role = role,
        text = text,
        contentDescription = contentDescription,
        centerX = centerX,
        centerY = centerY,
        bounds = bounds,
        clickable = clickable,
        editable = editable,
    )

    private fun coverage(): PerceptionCoverage = PerceptionCoverage(
        screen = "ok",
        notifications = "ok",
        media = "ok",
        calendar = "ok",
        acoustic = "ok",
        clipboard = "ok",
        vision = "ok",
        glasses = "ok",
        device = "ok",
    )
}
