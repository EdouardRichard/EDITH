package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.withP3Sink

import com.sheen.rokid.contracts.ContextChanged
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.InputEpoch
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.host.ActivationResult
import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.plugin.smarthelper.host.ProactiveArming
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.ContextSnapshot
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.InputPort
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle
import com.sheen.rokid.testsupport.FakeClock
import com.sheen.rokid.testsupport.ManualScheduler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Task 6: `Capability.Context` gets its first real provider.
 *
 * The keys are the contract's, the values are the device's: Stage A exposes only what needs no permission
 * (`time.local`, `time.period`), and Stage B appends its three permissioned sources to the same list. The provider
 * reports a change to its listeners, and the composition turns that into one `ContextChanged` - **only while
 * 「主动辅助」 is armed**, so a disarmed session delivers no context signal at all (spec §16.2).
 */
class CxrContextSignalsTest {

    private val clock = FakeClock(0)
    private val session = SessionId("s-1")

    private class NoInput : InputPort {
        override fun start(epoch: InputEpoch) = Unit
        override fun stop(epoch: InputEpoch) = Unit
    }

    private class Recorder : PluginLifecycle {
        override val descriptor = PluginDescriptor("p-1", "1.0.0", 1, "P1", listOf(Capability.Context), ConfigSchema(1))
        val handled = CopyOnWriteArrayList<HostEvent>()
        override fun activate(context: PluginContext) = Unit
        override fun deactivate(reason: DeactivationReason) = Unit
        override fun handle(event: HostEvent) { handled += event }
    }

    // ---- the values ----------------------------------------------------------------------------------------------

    @Test
    fun aSnapshotCarriesTheLocalTimeAndItsPeriod() {
        val signals = CxrContextSignals(clock, localTime = { LocalTime.of(14, 30) })

        val snapshot = signals.snapshot()

        assertEquals("14:30", snapshot.values["time.local"])
        assertEquals("afternoon", snapshot.values["time.period"])
        assertEquals("the capture time is the monotonic clock the host validates against",
            clock.nowNanos(), snapshot.capturedAtNanos)
    }

    @Test
    fun thePeriodCoversEveryHourOfTheDay() {
        val cases = mapOf(5 to "morning", 11 to "morning", 12 to "afternoon", 17 to "afternoon",
            18 to "evening", 22 to "evening", 23 to "night", 4 to "night")

        cases.forEach { (hour, period) ->
            val signals = CxrContextSignals(clock, localTime = { LocalTime.of(hour, 0) })
            assertEquals("hour $hour", period, signals.snapshot().values["time.period"])
        }
    }

    @Test
    fun sourcesMergeAndTheLaterSourceWins() {
        val signals = CxrContextSignals(clock,
            listOf({ mapOf("a" to "1", "b" to "1") }, { mapOf("b" to "2") }),
            localTime = { LocalTime.NOON })

        assertEquals(mapOf("a" to "1", "b" to "2", "time.local" to "12:00", "time.period" to "afternoon"),
            signals.snapshot().values)
    }

    @Test
    fun aSourceWithNothingToSayAddsNoKeysAndNoBlankValues() {
        val signals = CxrContextSignals(clock,
            listOf({ emptyMap() }, { mapOf("" to "x", "k" to " ", "good" to "v") }),
            localTime = { LocalTime.NOON })

        assertEquals(setOf("good", "time.local", "time.period"), signals.snapshot().values.keys)
    }

    // ---- the change notification ---------------------------------------------------------------------------------

    @Test
    fun listenersAreToldOncePerChangeAndNotForTheSameValuesAgain() {
        var source = mapOf("k" to "1")
        val signals = CxrContextSignals(clock, listOf({ source }), localTime = { LocalTime.of(9, 0) })
        val seen = mutableListOf<ContextSnapshot>()
        signals.listen { seen += it }

        signals.snapshot() // the baseline: nothing has changed yet
        signals.snapshot()
        assertTrue("an unchanged snapshot must not be reported as a change", seen.isEmpty())

        source = mapOf("k" to "2")
        signals.snapshot()
        assertEquals("a change is reported exactly once", 1, seen.size)
        assertEquals("2", seen.single().values["k"])

        signals.snapshot()
        assertEquals("and not again while the values hold", 1, seen.size)
    }

    @Test
    fun aCancelledListenerStopsReceiving() {
        var source = mapOf("k" to "1")
        val signals = CxrContextSignals(clock, listOf({ source }), localTime = { LocalTime.of(9, 0) })
        val seen = mutableListOf<ContextSnapshot>()
        val handle = signals.listen { seen += it }
        signals.snapshot()

        handle.cancel()
        source = mapOf("k" to "2")
        signals.snapshot()

        assertTrue(seen.isEmpty())
    }

    // ---- the arming key the plugin reads (review pass: the switch must be able to disarm) --------------------------

    @Test
    fun aSnapshotReportsWhetherProactiveAssistanceIsCurrentlyArmed() {
        val arming = ProactiveArming()
        val signals = CxrContextSignals(clock, listOf(CxrContextSignals.armingSource(arming)),
            localTime = { LocalTime.of(9, 0) })

        // `D53`②: the switch is a persisted global setting and defaults **on**, so the first snapshot says true.
        assertEquals("true", signals.snapshot().values[ProactiveArming.CONTEXT_KEY])

        arming.set(false)
        assertEquals("false", signals.snapshot().values[ProactiveArming.CONTEXT_KEY])

        arming.set(true)
        assertEquals("true", signals.snapshot().values[ProactiveArming.CONTEXT_KEY])
    }

    // ---- 0.4 Task 5 (`D202`): the key publishes the EFFECTIVE value ----------------------------------------------

    @Test
    fun theArmingKeyReportsTheEffectiveValueWhileDetachedButKeepsTheStoredSwitch() {
        var detached = true
        val arming = ProactiveArming(detached = { detached })
        val signals = CxrContextSignals(clock, listOf(CxrContextSignals.armingSource(arming)),
            localTime = { LocalTime.of(9, 0) })

        assertTrue("the user's stored switch is untouched", arming.enabled.value)
        assertEquals("D202: the plugin must read the effective value", "false",
            signals.snapshot().values[ProactiveArming.CONTEXT_KEY])

        detached = false
        assertEquals("closing detach restores the stored choice", "true",
            signals.snapshot().values[ProactiveArming.CONTEXT_KEY])
        assertTrue(arming.enabled.value)
    }

    // ---- the delivered event, behind the arming gate ---------------------------------------------------------------

    private class Rig(arming: ProactiveArming, session: SessionId, clock: FakeClock) {
        var source: Map<String, String> = mapOf("k" to "1")
        val plugin = Recorder()
        val signals = CxrContextSignals(clock, listOf({ source }), localTime = { LocalTime.of(9, 0) })
        val host: HostRuntime

        init {
            val environment = CxrHostEnvironment(clock = clock, input = NoInput(), liveOutput = { null },
                scheduler = ManualScheduler(clock), providers = mapOf(Capability.Context to signals))
            host = HostRuntime(1, environment)
            host.register(plugin)
            host.startSession(session)
            host.markSceneReady()
            assertEquals(ActivationResult.Activated, host.activate(plugin.descriptor.pluginId))
            signals.listen(HostCompositionRoot.armedContextSink({ host }, clock, arming))
            signals.snapshot()
        }

        fun changeAndSettle() {
            source = mapOf("k" to "2")
            signals.snapshot()
            host.currentState
        }

        fun close() { runCatching { host.close() } }
    }

    @Test
    fun anArmedChangeIsDeliveredAsOneContextChangedWithItsValues() {
        val arming = ProactiveArming()
        arming.set(true)
        val rig = Rig(arming, session, clock)
        try {
            rig.changeAndSettle()

            val delivered = rig.plugin.handled.filterIsInstance<ContextChanged>()
            assertEquals("one change, one event", 1, delivered.size)
            assertEquals("2", delivered.single().values["k"])
            assertEquals("the event keeps the snapshot's capture time", clock.nowNanos(), delivered.single().capturedAtNanos)
        } finally {
            rig.close()
        }
    }

    @Test
    fun aDisarmedChangeProducesNoContextChangedAtAll() {
        // `D53`②: the switch defaults on now, so the disarmed state is set up explicitly.
        val arming = ProactiveArming()
        arming.set(false)
        val rig = Rig(arming, session, clock)
        try {
            rig.changeAndSettle()

            assertTrue("a disarmed composition must not deliver context at all",
                rig.plugin.handled.none { it is ContextChanged })
        } finally {
            rig.close()
        }
    }

    @Test
    fun aDetachedChangeProducesNoContextChangedEvenWithTheStoredSwitchOn() {
        // 0.4 Task 5 (`D202`): detaching suppresses the context signal too. The stored switch stays on, so this test
        // proves the gate reads the *effective* value rather than the user's stored one.
        var detached = true
        val arming = ProactiveArming(detached = { detached })
        assertTrue(arming.enabled.value)
        val rig = Rig(arming, session, clock)
        try {
            withP3Sink { lines ->
                rig.changeAndSettle()

                assertTrue("a detached composition must not deliver context at all",
                    rig.plugin.handled.none { it is ContextChanged })
                assertTrue("and no P3_CONTEXT line may claim it was emitted",
                    lines.none { it.startsWith("P3_CONTEXT") })
            }

            detached = false
            // A fresh value, because the first suppressed change already moved the source to "2"; the listener only
            // reports a *change*, so the restored gate needs a new one to observe.
            rig.source = mapOf("k" to "3")
            rig.signals.snapshot()
            rig.host.currentState
            assertEquals("closing detach restores the glasses-mode behaviour",
                1, rig.plugin.handled.filterIsInstance<ContextChanged>().size)
        } finally {
            rig.close()
        }
    }
}
