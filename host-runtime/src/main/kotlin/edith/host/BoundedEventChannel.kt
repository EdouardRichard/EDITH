package edith.host

import edith.contracts.HostEvent

class BoundedEventChannel<T>(private val capacity: Int = 32) {
    init { require(capacity > 0) }
    private val queue = ArrayDeque<T>()
    fun offer(value: T): Boolean {
        if (queue.size >= capacity) queue.removeFirst()
        queue.addLast(value)
        return true
    }
    fun poll(): T? = if (queue.isEmpty()) null else queue.removeFirst()
    fun size(): Int = queue.size
}
