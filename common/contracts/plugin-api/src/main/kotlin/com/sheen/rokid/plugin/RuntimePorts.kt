package com.sheen.rokid.plugin

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.OutputStatus
import com.sheen.rokid.contracts.SessionId

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
