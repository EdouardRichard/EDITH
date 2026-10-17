package com.sheen.rokid.plugin

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WakeWordsContractTest {
    @Test fun `declare succeeds and current preserves insertion order`() {
        val registry = InMemoryWakeWordRegistry()
        assertTrue(registry.declare(listOf("伊迪丝", "edith")))
        assertEquals(listOf("伊迪丝", "edith"), registry.current())
    }

    @Test fun `refused declarations return false and leave current unchanged`() {
        val registry = InMemoryWakeWordRegistry()
        registry.declare(listOf("伊迪丝"))
        assertFalse(registry.declare(listOf("  ")))
        assertFalse(registry.declare(emptyList()))
        assertEquals(listOf("伊迪丝"), registry.current())
    }
}

// Executable consumer fixture only; the host-side registry is implemented in a later task (phone-app). It pins the
// semantics that host implementation must satisfy: a refused declaration never mutates the current word set.
private class InMemoryWakeWordRegistry : WakeWordRegistry {
    private var words: List<String> = emptyList()
    override fun declare(words: List<String>): Boolean {
        if (words.isEmpty() || words.any { it.isBlank() }) return false
        this.words = words.toList()
        return true
    }
    override fun current(): List<String> = words
}
