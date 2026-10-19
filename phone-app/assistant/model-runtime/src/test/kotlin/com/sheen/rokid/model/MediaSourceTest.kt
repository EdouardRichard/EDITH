package com.sheen.rokid.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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

    @Test fun `an audio-only source resolves no image`() {
        // Existing implementations must keep compiling and behaving: `resolveImage` is a defaulted port that answers
        // null until a host provides an artifact store.
        val source = object : MediaSource {
            override fun resolveAudio(ref: String) = null
        }
        assertNull(source.resolveImage("artifact:image"))
    }

    @Test fun `resolved image identity follows its bytes and metadata`() {
        val image = ResolvedImage(byteArrayOf(1, 2, 3), "image/jpeg", 768, 1664)
        assertEquals(image, ResolvedImage(byteArrayOf(1, 2, 3), "image/jpeg", 768, 1664))
        assertEquals(image.hashCode(), ResolvedImage(byteArrayOf(1, 2, 3), "image/jpeg", 768, 1664).hashCode())
        assertNotEquals(image, ResolvedImage(byteArrayOf(1, 2, 4), "image/jpeg", 768, 1664))
        assertNotEquals(image, ResolvedImage(byteArrayOf(1, 2, 3), "image/png", 768, 1664))
        assertNotEquals(image, ResolvedImage(byteArrayOf(1, 2, 3), "image/jpeg", 768, 1665))
    }
}
