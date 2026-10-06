package edith.host

import edith.contracts.ContractError
import edith.contracts.Generation
import edith.contracts.SessionId
import edith.plugin.CancellationHandle
import java.util.concurrent.atomic.AtomicBoolean

class ResourceScope(val pluginId: String, val sessionId: SessionId, val generation: Generation) {
    private val lock = Any()
    private var revoked = false
    private val handles = mutableSetOf<CancellationHandle>()
    init { require(pluginId.isNotBlank()) }
    val isRevoked: Boolean get() = synchronized(lock) { revoked }

    fun checkActive() { synchronized(lock) { if (revoked) throw ContractError.Cancelled() } }

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
        val cancelNow = synchronized(lock) { if (revoked) true else { handles += bound; false } }
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
        val admitted = synchronized(lock) { !revoked }
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
