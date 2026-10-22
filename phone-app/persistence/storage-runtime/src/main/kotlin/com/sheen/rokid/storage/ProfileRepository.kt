package com.sheen.rokid.storage

import com.sheen.rokid.plugin.*
import java.util.concurrent.atomic.AtomicBoolean

interface ProfileRepository {
    fun list(): List<ModelProfile>
    fun get(id: String): ModelProfile?
    fun save(profile: ModelProfile): ModelProfile
    fun delete(id: String)
    fun verify(id: String, result: CapabilityVerification)
    fun listenChanges(listener: (String) -> Unit): CancellationHandle
}

class MemoryProfileRepository(private val revokeSecret: (SecretHandle) -> Unit = {}) : ProfileRepository {
    private val lock = Any()
    private val profiles = linkedMapOf<String, ModelProfile>()
    private val listeners = linkedSetOf<ChangeListener>()
    private var revision = 0L

    override fun list(): List<ModelProfile> = synchronized(lock) { profiles.values.map(::snapshot) }
    override fun get(id: String): ModelProfile? = synchronized(lock) { profiles[id]?.let(::snapshot) }

    override fun save(profile: ModelProfile): ModelProfile {
        val old: ModelProfile?
        val saved: ModelProfile
        val deliveries: List<ChangeListener>
        synchronized(lock) {
            old = profiles[profile.id]
            revision = Math.addExact(revision, 1L)
            saved = snapshot(profile.copy(revision = revision, verifications = emptyList()))
            profiles[profile.id] = saved
            deliveries = listeners.toList()
        }
        try {
            if (old != null && old.secret.id != saved.secret.id) revokeSecret(old.secret)
        } finally {
            notifyChanges(profile.id, deliveries)
        }
        return snapshot(saved)
    }

    override fun delete(id: String) {
        val old: ModelProfile
        val deliveries: List<ChangeListener>
        synchronized(lock) {
            old = profiles.remove(id) ?: return
            deliveries = listeners.toList()
        }
        try { revokeSecret(old.secret) } finally { notifyChanges(id, deliveries) }
    }

    override fun verify(id: String, result: CapabilityVerification) {
        synchronized(lock) {
            val profile = profiles[id] ?: return
            if (result.profileRevision != profile.revision) return
            profiles[id] = profile.copy(verifications = profile.verifications + result)
        }
    }

    override fun listenChanges(listener: (String) -> Unit): CancellationHandle {
        val subscription = ChangeListener(listener)
        synchronized(lock) { listeners += subscription }
        return object : CancellationHandle {
            override fun cancel() {
                subscription.cancel()
                synchronized(lock) { listeners -= subscription }
            }
        }
    }

    private fun snapshot(profile: ModelProfile) = profile.copy(inputs = profile.inputs.toSet(),
        outputs = profile.outputs.toSet(), verifications = profile.verifications.toList())

    private fun notifyChanges(id: String, deliveries: List<ChangeListener>) {
        var failure: Throwable? = null
        deliveries.forEach {
            try { it.deliver(id) } catch (error: Throwable) {
                if (failure == null) failure = error else failure!!.addSuppressed(error)
            }
        }
        failure?.let { throw it }
    }

    private class ChangeListener(private val callback: (String) -> Unit) {
        private val active = AtomicBoolean(true)
        fun cancel() { active.set(false) }
        fun deliver(id: String) {
            // Cancellation fences new admission; an already admitted callback may finish.
            if (active.get()) callback(id)
        }
    }
}
