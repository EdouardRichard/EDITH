package com.sheen.rokid.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The port action wire format: one `(ref, action)` string, with arguments appended after a NUL
 * separator. The format cannot escape a NUL inside a value, so the encoder must refuse one instead
 * of producing a string the adapter would mis-split.
 */
class DeviceActionsTest {

    @Test
    fun `encode refuses an argument value that contains the separator`() {
        assertNull(DeviceActions.encode(DeviceActions.SET_TEXT, mapOf("text" to "hello\u0000text=evil")))
    }

    @Test
    fun `encode refuses an action that contains the separator`() {
        assertNull(DeviceActions.encode("click\u0000evil", emptyMap()))
    }

    @Test
    fun `encode refuses an argument key that would break decoding`() {
        assertNull(DeviceActions.encode(DeviceActions.SET_TEXT, mapOf("te=xt" to "hello")))
    }

    @Test
    fun `a normal set text action round-trips`() {
        val encoded = requireNotNull(DeviceActions.encode(DeviceActions.SET_TEXT, mapOf("text" to "a=b")))

        assertEquals(DeviceActions.SET_TEXT to mapOf("text" to "a=b"), DeviceActions.decode(encoded))
    }
}
