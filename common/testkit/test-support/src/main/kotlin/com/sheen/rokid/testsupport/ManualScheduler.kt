package com.sheen.rokid.testsupport

import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.DeadlineScheduler

class ManualScheduler(private val clock: FakeClock) : DeadlineScheduler {
    private data class Entry(val deadline: Long, val sequence: Long, val task: () -> Unit, var cancelled: Boolean = false)
    private val entries = mutableListOf<Entry>()
    private var sequence = 0L
    override fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle {
        require(deadlineNanos >= 0)
        val entry = Entry(deadlineNanos, sequence++, task)
        entries += entry
        return object : CancellationHandle { override fun cancel() { entry.cancelled = true } }
    }
    fun runDue() {
        while (true) {
            val next = entries.filter { it.deadline <= clock.nowNanos }.minWithOrNull(compareBy({ it.deadline }, { it.sequence })) ?: return
            entries.remove(next)
            if (!next.cancelled) next.task()
        }
    }
}
