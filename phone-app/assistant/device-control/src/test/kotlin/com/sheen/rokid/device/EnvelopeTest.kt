package com.sheen.rokid.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The envelope's invariants: a failure envelope is never `ok`, and `applied = null` (we could not
 * compare observations) is a different statement from `applied = false` (we compared and saw no
 * change). The kernel relies on those two facts to describe an action without over-claiming.
 */
class EnvelopeTest {

    private val timing = Timing(actMs = 3, observeMs = 7, totalMs = 12)

    @Test
    fun `a failure envelope is never ok and carries the error`() {
        val envelope = DeviceEnvelope.failure(
            action = "act",
            channel = "accessibility",
            error = DeviceError(DeviceErrorCodes.NODE_STALE, "ref #n4 no longer matches a node", recoverable = true),
            accepted = false,
            applied = false,
            timing = timing,
        )

        assertFalse(envelope.ok)
        assertEquals(DeviceEnvelope.TOOL_NAME, envelope.tool)
        assertEquals("act", envelope.action)
        assertEquals("accessibility", envelope.channel)
        assertFalse(envelope.accepted)
        assertEquals(DeviceErrorCodes.NODE_STALE, envelope.error?.code)
        assertEquals("ref #n4 no longer matches a node", envelope.error?.message)
        assertTrue(envelope.error?.recoverable == true)
        assertNull(envelope.verified)
        assertNull(envelope.data)
        assertTrue(envelope.images.isEmpty())
        assertEquals(timing, envelope.timing)
    }

    @Test
    fun `a success envelope has no error and keeps what the caller supplied`() {
        val verified = Verified("screen_change", revision = 42)
        val image = com.sheen.rokid.plugin.ImageRef("img-1", "image/jpeg", 768, 1664, "sha")
        val envelope = DeviceEnvelope.success(
            action = "act",
            channel = "accessibility",
            accepted = true,
            applied = true,
            verified = verified,
            timing = timing,
            data = "screen text",
            images = listOf(image),
        )

        assertTrue(envelope.ok)
        assertNull(envelope.error)
        assertTrue(envelope.accepted)
        assertEquals(true, envelope.applied)
        assertEquals(verified, envelope.verified)
        assertEquals("screen text", envelope.data)
        assertEquals(listOf(image), envelope.images)
    }

    @Test
    fun `applied unknown is not applied false`() {
        val unknown = DeviceEnvelope.success(
            action = "act",
            channel = "accessibility",
            accepted = true,
            applied = null,
            verified = null,
            timing = timing,
        )
        val unchanged = DeviceEnvelope.failure(
            action = "act",
            channel = "accessibility",
            error = DeviceError(DeviceErrorCodes.ACCEPTED_NO_EFFECT, "no visible change", recoverable = true),
            accepted = true,
            applied = false,
            timing = timing,
        )

        assertNull(unknown.applied, "no baseline observation means we do not know")
        assertTrue(unknown.ok)
        assertEquals(false, unchanged.applied, "an independent observation proved no change")
        assertFalse(unchanged.ok)
    }

    @Test
    fun `verified keeps the revision it was observed at and timing keeps its parts`() {
        assertEquals(12L, timing.totalMs)
        assertEquals(3L, timing.actMs)
        assertEquals(7L, timing.observeMs)
        assertEquals(42L, Verified("screen_change", 42).revision)
        assertEquals("idle", Verified("idle", 7).kind)
    }
}
