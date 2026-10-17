package com.sheen.rokid.host

class BoundedEventChannel<T>(private val capacity: Int = 32) {
    init { require(capacity > 0) }
    private val queue = ArrayDeque<T>()
    @Synchronized fun offer(value: T): Boolean {
        if (queue.size >= capacity) return false
        queue.addLast(value)
        return true
    }
    @Synchronized fun poll(): T? = if (queue.isEmpty()) null else queue.removeFirst()
    @Synchronized fun size(): Int = queue.size
}
