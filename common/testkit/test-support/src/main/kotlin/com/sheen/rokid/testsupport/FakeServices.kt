package com.sheen.rokid.testsupport

import com.sheen.rokid.plugin.*

class FakeClock(var nowNanos: Long = 0) : MonotonicClock {
    override fun nowNanos() = nowNanos
    fun advance(delta: Long) { require(delta >= 0); nowNanos = Math.addExact(nowNanos, delta) }
}
class FakeCancellation : CancellationHandle {
    var cancelCount = 0
    val cancelled get() = cancelCount > 0
    override fun cancel() { cancelCount++ }
}
class FakeTaskScope : TaskScope {
    val handles = mutableListOf<FakeCancellation>()
    override fun launch(block: () -> Unit): CancellationHandle { val h = FakeCancellation(); handles += h; block(); return h }
    override fun cancelAll() { handles.forEach { it.cancel() } }
}
class FakeServices : HostServices {
    override val tasks = FakeTaskScope()
    override fun require(capability: Capability): Any = error("fake capability unavailable: $capability")
}
