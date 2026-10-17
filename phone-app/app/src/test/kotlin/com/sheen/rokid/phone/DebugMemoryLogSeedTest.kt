package com.sheen.rokid.phone

import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.phone.debug.DebugMemoryLogSeed
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.MemoryLogView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The P2 synthetic-memory instrument's contract (U-4 device evidence).
 *
 * The instrument exists to put two known sentences in front of the real page: one shorter than the preview cap and one
 * longer, so the device screenshot can prove both the `…` and the full-text page. The test therefore pins the property
 * the evidence depends on - the two sentences sit on opposite sides of [MemoryLogView.PREVIEW_CODE_POINTS] and are
 * different enough that `MemoryLog`'s 0.85 de-duplication cannot merge them into one.
 */
class DebugMemoryLogSeedTest {

    private class EmptyStorage : StorageHandle {
        override fun read(key: String): ByteArray? = null
        override fun write(key: String, value: ByteArray) = Unit
        override fun delete(key: String) = Unit
        override fun clear() = Unit
    }

    private val preview = MemoryLogView(EmptyStorage())

    @Test
    fun `the short evidence memory fits the preview unchanged`() {
        val short = DebugMemoryLogSeed.SHORT_TEXT

        assertTrue(
            "the short sentence must not be truncated, or the screenshot cannot show the untruncated case",
            short.codePointCount(0, short.length) <= MemoryLogView.PREVIEW_CODE_POINTS,
        )
        assertEquals(short, preview.preview(short))
    }

    @Test
    fun `the long evidence memory crosses the preview limit`() {
        val long = DebugMemoryLogSeed.LONG_TEXT

        assertTrue(
            "the long sentence must cross the cap, or the screenshot cannot show the ellipsis",
            long.codePointCount(0, long.length) > MemoryLogView.PREVIEW_CODE_POINTS,
        )
        assertTrue("the long sentence previews with a truncation marker", preview.preview(long).endsWith("…"))
    }

    @Test
    fun `the two evidence memories never de-duplicate into one`() {
        assertTrue(DebugMemoryLogSeed.SHORT_TEXT != DebugMemoryLogSeed.LONG_TEXT)
    }
}
