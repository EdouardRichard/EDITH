package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*

interface HostEnvironment {
    val clock: MonotonicClock
    val input: InputPort
    val output: HintDevice
    val availableCapabilities: Set<Capability>
    /** Optional speech player; null when the device has no TTS engine or the user switch is off. */
    val speech: SpeechPlayer? get() = null
    /** P5 (C2 fix): optional audio segment source for the direct-audio path. Null when not wired. */
    val audioSegmentSource: AudioSegmentSource? get() = null
    fun services(scope: ResourceScope, granted: Set<Capability>): HostServices
}

interface ScheduledHostEnvironment : HostEnvironment { val scheduler: DeadlineScheduler }

internal object MissingHostEnvironment : HostEnvironment {
    override val clock = object : MonotonicClock { override fun nowNanos() = System.nanoTime().coerceAtLeast(0) }
    override val availableCapabilities = emptySet<Capability>()
    override val input = object : InputPort {
        override fun start(epoch: InputEpoch) = throw ContractError.CapabilityMissing("audio unavailable")
        override fun stop(epoch: InputEpoch) = Unit
    }
    override val output = object : HintDevice {
        override fun show(assistance: Assistance, revision: Long, listener: (OutputStatus) -> Unit): CancellationHandle =
            throw ContractError.CapabilityMissing("display unavailable")
        override fun clear(sessionId: SessionId, generation: Generation) = Unit
    }
    override fun services(scope: ResourceScope, granted: Set<Capability>): HostServices =
        ScopedHostServices(scope, granted, emptyMap(), object : TaskScope {
            override fun launch(block: () -> Unit): CancellationHandle = throw ContractError.CapabilityMissing("tasks unavailable")
            override fun cancelAll() = Unit
        })
}
