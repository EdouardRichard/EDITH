package com.sheen.rokid.testsupport

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*

class RecordingInputPort : InputPort {
    val started = mutableListOf<InputEpoch>()
    val stopped = mutableListOf<InputEpoch>()
    override fun start(epoch: InputEpoch) { started += epoch }
    override fun stop(epoch: InputEpoch) { stopped += epoch }
}

class RecordingHintDevice : HintDevice {
    val shown = mutableListOf<Pair<Assistance, Long>>()
    val clears = mutableListOf<Pair<SessionId, Generation>>()
    val callbacks = mutableListOf<(OutputStatus) -> Unit>()
    val handles = mutableListOf<FakeCancellation>()
    override fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit): CancellationHandle {
        shown += assistance to revision
        callbacks += listener
        return FakeCancellation().also(handles::add)
    }
    override fun clear(sessionId: SessionId, generation: Generation) { clears += sessionId to generation }
}
