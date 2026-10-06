package edith.testsupport

import edith.plugin.*

class FakeClock(var nowNanos: Long = 0) { fun advance(delta: Long) { require(delta >= 0); nowNanos += delta } }
class FakeCancellation : CancellationHandle { var cancelled = false; override fun cancel() { cancelled = true } }
class FakeTaskScope : TaskScope {
    val handles = mutableListOf<FakeCancellation>()
    override fun launch(block: () -> Unit): CancellationHandle { val h = FakeCancellation(); handles += h; block(); return h }
    override fun cancelAll() { handles.forEach { it.cancel() } }
}
class FakeServices : HostServices {
    override val tasks = FakeTaskScope()
    override fun require(capability: Capability): Any = error("fake capability unavailable: $capability")
}
