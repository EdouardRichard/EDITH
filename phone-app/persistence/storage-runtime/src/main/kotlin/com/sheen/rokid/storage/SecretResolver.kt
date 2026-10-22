package com.sheen.rokid.storage

import com.sheen.rokid.plugin.SecretHandle
import java.util.UUID

interface SecretResolver {
    fun withSecret(handle: SecretHandle, block: (CharArray) -> Unit)
    fun revoke(handle: SecretHandle)
}

class MemorySecretResolver : SecretResolver, AutoCloseable {
    private val lock = Any()
    private val secrets = mutableMapOf<String, CharArray>()
    private var closed = false
    fun put(secret: CharArray): SecretHandle {
        try {
            require(secret.isNotEmpty() && secret.any { !it.isWhitespace() })
            val id = UUID.randomUUID().toString()
            synchronized(lock) { check(!closed); secrets[id] = secret.copyOf() }
            return object : SecretHandle { override val id = id; override fun toString() = "SecretHandle(redacted)" }
        } finally { secret.fill('\u0000') }
    }
    override fun withSecret(handle: SecretHandle, block: (CharArray) -> Unit) {
        val copy = synchronized(lock) { secrets[handle.id]?.copyOf() ?: throw IllegalStateException("SECRET_UNAVAILABLE") }
        try { block(copy) } finally { copy.fill('\u0000') }
    }
    override fun revoke(handle: SecretHandle) { synchronized(lock) { secrets.remove(handle.id)?.fill('\u0000') } }
    override fun close() { synchronized(lock) { closed = true; secrets.values.forEach { it.fill('\u0000') }; secrets.clear() } }
}
