package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.testsupport.*
import kotlin.test.*

class WakeEventDeliveryTest {
    /**
     * `HostTestRig`'s environment only advertises Audio/TextOutput, and `ScopedHostServices` requires a provider for
     * every granted capability, so the P3.1 capabilities are added as an environment override with minimal fakes.
     * Capabilities the rig already provides are taken from the rig.
     */
    private fun hostWith(vararg capabilities: Capability): HostRuntime {
        val rig = HostTestRig()
        val providers: Map<Capability, Any> = capabilities.distinct().associateWith { capability ->
            rig.providers[capability] ?: when (capability) {
                Capability.WakeWord -> object : WakeWordRegistry {
                    override fun declare(words: List<String>) = true
                    override fun current(): List<String> = emptyList()
                }
                Capability.Context -> object : ContextSignals {
                    override fun snapshot() = ContextSnapshot(0L, emptyMap())
                    override fun listen(listener: (ContextSnapshot) -> Unit): CancellationHandle = FakeCancellation()
                }
                Capability.Actions -> object : ActionExecutor {
                    override fun descriptors(): List<ActionDescriptor> = emptyList()
                    override fun execute(name: String, argsJson: String) = ActionResult(ActionOutcome.Done, name)
                    override fun cancel() = Unit
                }
                else -> error("unsupported test capability: $capability")
            }
        }
        val environment = object : ScheduledHostEnvironment by rig.environment {
            override val availableCapabilities: Set<Capability> = capabilities.toSet()
            override fun services(scope: ResourceScope, granted: Set<Capability>): HostServices =
                ScopedHostServices(scope, granted, providers, FakeTaskScope())
        }
        return HostRuntime(1, environment).also {
            it.startSession(SessionId("s"))
            it.markSceneReady()
        }
    }

    /** Activates a plugin that declares exactly [pluginCapabilities] while the environment advertises [available]. */
    private fun activated(
        pluginCapabilities: List<Capability>,
        vararg available: Capability,
    ): Pair<HostRuntime, ProbePlugin> {
        val host = hostWith(*(available.toList() + pluginCapabilities).distinct().toTypedArray())
        val plugin = ProbePlugin("plugin", pluginCapabilities)
        host.register(plugin)
        assertEquals(ActivationResult.Activated, host.activate(plugin.descriptor.pluginId))
        return host to plugin
    }

    private fun activated(vararg capabilities: Capability): Pair<HostRuntime, ProbePlugin> =
        activated(capabilities.toList(), *capabilities)

    private fun wake(host: HostRuntime, id: String, generation: Generation = host.currentGeneration) =
        WakeDetected(EventEnvelope(SessionId("s"), generation, id, 0L, 1L), "edith", 0.9)

    private fun acoustic(host: HostRuntime, id: String, label: AcousticLabel, generation: Generation = host.currentGeneration) =
        AcousticEvent(EventEnvelope(SessionId("s"), generation, id, 0L, 1L), label)

    private fun confirmation(host: HostRuntime, id: String, generation: Generation = host.currentGeneration) =
        ConfirmationResult(EventEnvelope(SessionId("s"), generation, id, 0L, 1L), "r1", true)

    private fun context(host: HostRuntime, id: String, generation: Generation = host.currentGeneration) =
        ContextChanged(EventEnvelope(SessionId("s"), generation, id, 0L, 1L), 7L, mapOf("foreground" to "camera"))

    /** Every command runs on the owner after the pending drain, so it is a delivery barrier. */
    private fun settle(host: HostRuntime) { host.currentState }

    @Test fun `the current identity follows activation rather than the session alone`() {
        // The audio layer builds a wake event from this value: the host drops any event whose
        // (sessionId, generation) is not the identity it gates delivery by, so "what is current" has to be readable
        // from outside the host or the composition cannot arm the wake path at all.
        val (host, plugin) = activated(Capability.WakeWord)
        assertEquals(SessionId("s"), host.currentIdentity()?.first)
        assertEquals(host.currentGeneration, host.currentIdentity()?.second)

        host.pause()
        assertNull(host.currentIdentity(), "a paused host gates no events, so it must expose no identity")
        assertEquals(plugin.descriptor.pluginId, "plugin")
    }

    @Test fun `plugin that declares wake word receives the detection`() {
        val (host, plugin) = activated(Capability.WakeWord)
        assertTrue(host.onEvent(wake(host, "w1")))
        settle(host)
        assertEquals(listOf("edith"), plugin.events.filterIsInstance<WakeDetected>().map { it.word })
    }

    @Test fun `plugin that does not declare wake word receives nothing`() {
        val (host, plugin) = activated(listOf(Capability.TextOutput), Capability.WakeWord, Capability.TextOutput)
        assertTrue(host.onEvent(wake(host, "w1")))
        settle(host)
        assertTrue(plugin.events.none { it is WakeDetected })
    }

    @Test fun `context and action events follow their capability mapping`() {
        val (host, plugin) = activated(Capability.Context, Capability.Actions)
        assertTrue(host.onEvent(acoustic(host, "a1", AcousticLabel.Speech)))
        assertTrue(host.onEvent(context(host, "c1")))
        assertTrue(host.onEvent(confirmation(host, "k1")))
        settle(host)
        assertEquals(1, plugin.events.filterIsInstance<AcousticEvent>().size)
        assertEquals(1, plugin.events.filterIsInstance<ContextChanged>().size)
        assertEquals(listOf(true), plugin.events.filterIsInstance<ConfirmationResult>().map { it.approved })
    }

    @Test fun `context event is dropped for a plugin without the context capability`() {
        val (host, plugin) = activated(listOf(Capability.WakeWord), Capability.Context, Capability.WakeWord)
        assertTrue(host.onEvent(context(host, "c1")))
        settle(host)
        assertTrue(plugin.events.none { it is ContextChanged })
    }

    @Test fun `confirmation result is dropped for a plugin without the actions capability`() {
        // Declares WakeWord + Context but not Actions, so a missing `ConfirmationResult -> Actions` arm (or a swap to
        // either declared capability) would deliver the event and fail this assertion.
        val (host, plugin) = activated(Capability.WakeWord, Capability.Context)
        assertTrue(host.onEvent(confirmation(host, "k1")))
        settle(host)
        assertTrue(plugin.events.none { it is ConfirmationResult })
    }

    @Test fun `context events are dropped for a plugin that declares actions but not context`() {
        // Declares Actions only: a missing/incorrect `AcousticEvent | ContextChanged -> Context` arm would let both
        // events through (unmapped -> null, or wrongly mapped to Actions), so this pins the Context mapping.
        val (host, plugin) = activated(Capability.Actions)
        assertTrue(host.onEvent(acoustic(host, "a1", AcousticLabel.Speech)))
        assertTrue(host.onEvent(context(host, "c1")))
        settle(host)
        assertTrue(plugin.events.none { it is AcousticEvent })
        assertTrue(plugin.events.none { it is ContextChanged })
    }

    @Test fun `event whose envelope carries an old generation is dropped`() {
        val (host, plugin) = activated(Capability.WakeWord)
        val stale = Generation(host.currentGeneration.value - 1)
        assertFalse(host.onEvent(wake(host, "old", generation = stale)))
        settle(host)
        assertTrue(plugin.events.isEmpty())
    }

    @Test fun `consecutive identical acoustic labels are delivered once`() {
        val (host, plugin) = activated(Capability.Context)
        assertTrue(host.onEvent(acoustic(host, "a1", AcousticLabel.Speech)))
        assertTrue(host.onEvent(acoustic(host, "a2", AcousticLabel.Speech)))
        settle(host)
        assertEquals(1, plugin.events.filterIsInstance<AcousticEvent>().size)
    }

    @Test fun `alternating acoustic labels are all delivered`() {
        val (host, plugin) = activated(Capability.Context)
        assertTrue(host.onEvent(acoustic(host, "a1", AcousticLabel.Speech)))
        assertTrue(host.onEvent(acoustic(host, "a2", AcousticLabel.Silence)))
        assertTrue(host.onEvent(acoustic(host, "a3", AcousticLabel.Speech)))
        settle(host)
        assertEquals(
            listOf(AcousticLabel.Speech, AcousticLabel.Silence, AcousticLabel.Speech),
            plugin.events.filterIsInstance<AcousticEvent>().map { it.label },
        )
    }

    @Test fun `the last acoustic label is readable and follows label transitions`() {
        // edith-0.3 Phase 2 Task 4: the perception path has to read the acoustic fact without waiting for another
        // event. Before any transition there is no label (null), and after one the stable enum name is published.
        val (host, _) = activated(Capability.Context)
        assertNull(host.lastAcousticLabel(), "no event yet is not a label")

        assertTrue(host.onEvent(acoustic(host, "a1", AcousticLabel.Speech)))
        settle(host)
        assertEquals("Speech", host.lastAcousticLabel())

        assertTrue(host.onEvent(acoustic(host, "a2", AcousticLabel.Silence)))
        settle(host)
        assertEquals("Silence", host.lastAcousticLabel())
    }

    @Test fun `the last acoustic label is cleared when the session ends`() {
        val (host, _) = activated(Capability.Context)
        assertTrue(host.onEvent(acoustic(host, "a1", AcousticLabel.Noisy)))
        settle(host)
        assertEquals("Noisy", host.lastAcousticLabel())

        host.stop()

        assertNull(host.lastAcousticLabel(), "a stopped session has no acoustic fact")
    }

    @Test fun `acoustic memory resets with a new session`() {
        val (host, plugin) = activated(Capability.Context)
        assertTrue(host.onEvent(acoustic(host, "a1", AcousticLabel.Speech)))
        settle(host)
        assertEquals(1, plugin.events.filterIsInstance<AcousticEvent>().size)
        host.startSession(SessionId("s"))
        host.markSceneReady()
        assertEquals(ActivationResult.Activated, host.activate(plugin.descriptor.pluginId))
        assertTrue(host.onEvent(acoustic(host, "a2", AcousticLabel.Speech)))
        settle(host)
        assertEquals(2, plugin.events.filterIsInstance<AcousticEvent>().size)
    }
}
