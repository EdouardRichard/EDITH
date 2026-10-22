package com.sheen.rokid.storage

import com.sheen.rokid.plugin.SpeakerProfileRef
import kotlin.test.*

class SpeakerDeletionTest {
    @Test fun `save and load isolate owned arrays and delete only exact ref`() {
        val store = MemorySpeakerProfileStore()
        val input = floatArrayOf(1f, 0f)
        val a = store.save(input)
        val b = store.save(floatArrayOf(0f, 1f))
        input.fill(9f)
        assertContentEquals(floatArrayOf(1f, 0f), store.load(a))
        store.load(a)!!.fill(7f)
        assertContentEquals(floatArrayOf(1f, 0f), store.load(a))
        store.delete(SpeakerProfileRef(a.id, a.revision + 1))
        assertNotNull(store.load(a))
        store.delete(a)
        assertNull(store.load(a))
        assertContentEquals(floatArrayOf(0f, 1f), store.load(b))
    }

    @Test fun `invalid embeddings never become loadable profiles`() {
        val store = MemorySpeakerProfileStore()
        for (input in listOf(floatArrayOf(), floatArrayOf(Float.NaN), floatArrayOf(0f, 0f))) {
            assertFailsWith<IllegalArgumentException> { store.save(input) }
        }
    }
}
