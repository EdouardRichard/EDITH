package edith.plugin

import edith.contracts.Assistance
import edith.contracts.Generation
import edith.contracts.InputEpoch
import edith.contracts.OutputStatus
import edith.contracts.SessionId

interface MonotonicClock { fun nowNanos(): Long }

interface DeadlineScheduler {
    fun at(deadlineNanos: Long, task: () -> Unit): CancellationHandle
}

interface InputPort {
    fun start(epoch: InputEpoch)
    fun stop(epoch: InputEpoch)
}

interface HintDevice {
    fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit): CancellationHandle
    fun clear(sessionId: SessionId, generation: Generation)
}
