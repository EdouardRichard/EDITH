package com.sheen.rokid.transport

internal class BackendLifecycleFence {
    @Volatile private var closed = false
    @Synchronized fun <T> dispatch(action: () -> T): T? = if (closed) null else action()
    @Synchronized fun close() { closed = true }
    fun isClosed(): Boolean = closed
}
