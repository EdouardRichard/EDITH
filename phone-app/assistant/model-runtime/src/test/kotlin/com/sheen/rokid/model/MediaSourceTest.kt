package com.sheen.rokid.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MediaSourceTest {
    @Test fun `known ref resolves to correct bytes and format`() {
        val source = object : MediaSource {
            override fun resolveAudio(ref: String) = if (ref == "r1")
                ResolvedAudio(byteArrayOf(1, 2, 3), "wav", 100) else null
        }
        val resolved = source.resolveAudio("r1")
        assertContentEquals(byteArrayOf(1, 2, 3), resolved?.bytes)
        assertEquals("wav", resolved?.format)
        assertEquals(100, resolved?.durationMillis)
    }

    @Test fun `unknown ref returns null`() {
        val source = object : MediaSource {
            override fun resolveAudio(ref: String) = null
        }
        assertNull(source.resolveAudio("nonexistent"))
    }
}
