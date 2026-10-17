package com.sheen.rokid.testsupport

import com.sheen.rokid.contracts.*
import kotlin.test.*

class RuntimePortsTest {
    @Test fun `manual deadlines order cancel and allow reentrant scheduling`() {
        val clock = FakeClock()
        val scheduler = ManualScheduler(clock)
        val fired = mutableListOf<String>()
        scheduler.at(20) { fired += "later" }
        val cancelled = scheduler.at(10) { fired += "cancelled" }
        scheduler.at(10) { fired += "first"; scheduler.at(10) { fired += "nested" } }
        cancelled.cancel(); cancelled.cancel()
        clock.advance(9); scheduler.runDue()
        assertTrue(fired.isEmpty())
        clock.advance(1); scheduler.runDue()
        assertEquals(listOf("first", "nested"), fired)
        clock.advance(10); scheduler.runDue()
        assertEquals(listOf("first", "nested", "later"), fired)
    }

    @Test fun `recording ports retain input and output arguments`() {
        val input = RecordingInputPort()
        val epoch = InputEpoch(SessionId("s"), Generation(0), 1)
        input.start(epoch); input.stop(epoch)
        assertEquals(listOf(epoch), input.started)
        assertEquals(listOf(epoch), input.stopped)
        val device = RecordingHintDevice()
        val hint = Assistance("h", epoch.sessionId, epoch.generation, null, 1, "test", null, 20)
        var state: OutputStatus? = null
        val handle = device.show(hint, 2) { state = it }
        device.callbacks.single()(OutputStatus.Displayed)
        handle.cancel(); device.clear(epoch.sessionId, epoch.generation)
        assertEquals(listOf(hint to 2L), device.shown)
        assertEquals(OutputStatus.Displayed, state)
        assertEquals(listOf(epoch.sessionId to epoch.generation), device.clears)
        assertEquals(1, device.handles.single().cancelCount)
    }
}
