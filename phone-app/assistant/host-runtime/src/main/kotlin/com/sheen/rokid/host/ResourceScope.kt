package com.sheen.rokid.host

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.plugin.CancellationHandle
import java.util.concurrent.atomic.AtomicBoolean

class ResourceScope(val pluginId: String, val sessionId: SessionId, val generation: Generation) {
    private val lock = Any()
    private var revoked = false
    private val admissionClosed = AtomicBoolean()
    private val handles = mutableSetOf<CancellationHandle>()
    init { require(pluginId.isNotBlank()) }
    val isRevoked: Boolean get() = admissionClosed.get() || synchronized(lock) { revoked }

    internal fun closeAdmission() { admissionClosed.set(true) }

    fun checkActive() {
        if (admissionClosed.get()) throw ContractError.Cancelled()
        synchronized(lock) { if (revoked || admissionClosed.get()) throw ContractError.Cancelled() }
    }

    fun bind(handle: CancellationHandle): CancellationHandle {
        val cancelled = AtomicBoolean()
        val bound = object : CancellationHandle {
            override fun cancel() {
                if (cancelled.compareAndSet(false, true)) {
                    synchronized(lock) { handles.remove(this) }
                    runCatching { handle.cancel() }
                }
            }
        }
        val cancelNow = synchronized(lock) { if (revoked || admissionClosed.get()) true else { handles += bound; false } }
        if (cancelNow) bound.cancel()
        return bound
    }

    fun revoke() {
        val pending = synchronized(lock) {
            if (revoked) return
            revoked = true
            handles.toList().also { handles.clear() }
        }
        pending.forEach { it.cancel() }
    }

    // Admission reserves cancellation before external work; a late returned handle is cancelled immediately.
    internal fun <T> active(block: () -> T): T = synchronized(lock) { checkActive(); block() }
    internal fun start(block: () -> CancellationHandle): CancellationHandle {
        val pending = DeferredCancellation()
        val bound = synchronized(lock) { checkActive(); bind(pending) }
        return try {
            checkActive()
            pending.attach(block())
            bound
        } catch (error: Throwable) {
            bound.cancel()
            throw error
        }
    }
    internal fun deliver(block: () -> Unit) {
        if (admissionClosed.get()) return
        val admitted = synchronized(lock) { !revoked && !admissionClosed.get() }
        if (admitted) block()
    }

    private class DeferredCancellation : CancellationHandle {
        private val lock = Any()
        private var cancelled = false
        private var delegate: CancellationHandle? = null
        fun attach(handle: CancellationHandle) {
            val cancelNow = synchronized(lock) { if (cancelled) true else { delegate = handle; false } }
            if (cancelNow) runCatching { handle.cancel() }
        }
        override fun cancel() {
            val handle = synchronized(lock) {
                if (cancelled) return
                cancelled = true
                delegate.also { delegate = null }
            }
            handle?.let { runCatching { it.cancel() } }
        }
    }
}
