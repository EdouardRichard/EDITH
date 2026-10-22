package com.sheen.rokid.storage

import kotlin.test.*

class NamespaceStorageTest {
    @Test fun `host assigned namespaces isolate shared backing reads writes deletion and clear`() {
        val backing = MemoryNamespaceBacking()
        val smart = NamespaceStorage("smart", backing)
        val interview = NamespaceStorage("interview", backing)
        val smartAgain = NamespaceStorage("smart", backing)
        smart.write("key", byteArrayOf(1)); interview.write("key", byteArrayOf(2))
        assertContentEquals(byteArrayOf(1), smartAgain.read("key"))
        smart.delete("key")
        assertContentEquals(byteArrayOf(2), interview.read("key"))
        smart.write("key", byteArrayOf(3)); smart.clear()
        assertNull(smartAgain.read("key"))
        assertContentEquals(byteArrayOf(2), interview.read("key"))
    }

    @Test fun `storage buffers cannot be mutated through aliases`() {
        val storage = NamespaceStorage("smart")
        val input = byteArrayOf(1, 2)
        storage.write("key", input); input.fill(9)
        storage.read("key")!!.fill(8)
        assertContentEquals(byteArrayOf(1, 2), storage.read("key"))
    }

    @Test fun `namespace and every key operation reject traversal and paths`() {
        val invalid = listOf("../other", ".", "..", "", "a/b", "a\\b", "a:b", "a b", "x".repeat(65))
        for (value in invalid) {
            assertFailsWith<IllegalArgumentException> { NamespaceStorage(value) }
            val storage = NamespaceStorage("smart")
            assertFailsWith<IllegalArgumentException> { storage.read(value) }
            assertFailsWith<IllegalArgumentException> { storage.write(value, byteArrayOf(1)) }
            assertFailsWith<IllegalArgumentException> { storage.delete(value) }
        }
        val valid = NamespaceStorage("Plugin_1.0-" + "x".repeat(53))
        valid.write("x".repeat(64), byteArrayOf(7))
        assertContentEquals(byteArrayOf(7), valid.read("x".repeat(64)))
    }
}
