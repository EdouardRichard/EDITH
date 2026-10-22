package com.sheen.rokid.storage

import com.sheen.rokid.plugin.StorageHandle

class MemoryNamespaceBacking {
    internal val entries = mutableMapOf<String, MutableMap<String, ByteArray>>()
}

class NamespaceStorage(private val namespace: String, private val backing: MemoryNamespaceBacking = MemoryNamespaceBacking()) : StorageHandle {
    init { validate(namespace) }

    override fun read(key: String): ByteArray? {
        validate(key)
        return synchronized(backing) { backing.entries[namespace]?.get(key)?.copyOf() }
    }

    override fun write(key: String, value: ByteArray) {
        validate(key)
        synchronized(backing) { backing.entries.getOrPut(namespace) { mutableMapOf() }[key] = value.copyOf() }
    }

    override fun delete(key: String) {
        validate(key)
        synchronized(backing) { backing.entries[namespace]?.remove(key) }
    }

    override fun clear() {
        synchronized(backing) { backing.entries.remove(namespace) }
    }

    private fun validate(value: String) {
        require(value != "." && value != ".." && value.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
    }
}
