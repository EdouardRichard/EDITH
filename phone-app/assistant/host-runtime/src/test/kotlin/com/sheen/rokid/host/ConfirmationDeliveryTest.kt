package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.testsupport.*
import kotlin.concurrent.thread
import kotlin.test.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Spec §17.4, the half P3.3's plan deferred and never wrote: "待确认期间事件仍能投递到插件".
 *
 * The plugin's own side of that rule is pinned by `SmartTurnThreadingTest` (plugins-smart): a turn must hand its thread
 * back while the question is open. This is the **host's** side - the answer is an event like any other, delivered by the
 * host's single dispatcher, so it must reach a plugin whose turn is blocked on the question rather than queue behind it.
 *
 * **What its RED proves, and what it does not.** The property holds in production today (the plugin offloads the turn
 * to `services.tasks`, and P3.3's task pool is what makes that a different thread). So this file cannot fail from a
 * production defect: it fails because the rig's [FakeTaskScope] runs `launch` **inline**, which reproduces the shape
 * where the blocked turn holds the dispatcher. That is coverage of an already-correct property, not a bug fix - and it
 * is also why the plan put this file next to the task-pool change in T13.
 */
class ConfirmationDeliveryTest {

    /** The task scope the production composition really has: a pool, so a blocked turn is not the dispatcher's thread. */
    private class ThreadedTaskScope : TaskScope {
        private val threads = mutableListOf<Thread>()
        override fun launch(block: () -> Unit): CancellationHandle {
            val worker = thread(isDaemon = true, name = "edith-plugin-tasks-${threads.size + 1}", block = block)
            threads += worker
            return object : CancellationHandle { override fun cancel() { worker.interrupt() } }
        }
        override fun cancelAll() { threads.forEach { it.interrupt() } }
    }

    /**
     * Declares WakeWord (so a detection starts a turn) and Actions (so a `ConfirmationResult` is mapped to it).
     * Its turn blocks on the answer exactly the way `SmartConfirmation.request` blocks the real turn.
     */
    private class BlockingTurnPlugin : PluginLifecycle, ConfiguredPlugin {
        val turnStarted = CountDownLatch(1)
        val blockedUntilAnswered = CountDownLatch(1)
        val turnFinished = CountDownLatch(1)
        @Volatile var answer: ConfirmationResult? = null
        private var context: PluginContext? = null

        override val descriptor = PluginDescriptor(
            "blocking", "1", 1, "blocking",
            listOf(Capability.WakeWord, Capability.Actions), ConfigSchema(1),
        )

        override fun validate(configuration: PluginConfiguration) = Unit
        override fun configure(configuration: PluginConfiguration) = Unit
        override fun activate(context: PluginContext) { this.context = context }
        override fun deactivate(reason: DeactivationReason) = Unit

        override fun handle(event: HostEvent) {
            when (event) {
                is WakeDetected -> context!!.services.tasks.launch {
                    turnStarted.countDown()
                    if (blockedUntilAnswered.await(5, TimeUnit.SECONDS)) turnFinished.countDown()
                }
                is ConfirmationResult -> {
                    answer = event
                    blockedUntilAnswered.countDown()
                }
                else -> Unit
            }
        }
    }

    private fun hostWithBlockingPlugin(tasks: TaskScope): Pair<HostRuntime, BlockingTurnPlugin> {
        val rig = HostTestRig()
        val capabilities = setOf(Capability.WakeWord, Capability.Actions)
        val providers: Map<Capability, Any> = mapOf(
            Capability.WakeWord to object : WakeWordRegistry {
                override fun declare(words: List<String>) = true
                override fun current(): List<String> = emptyList()
            },
            Capability.Actions to object : ActionExecutor {
                override fun descriptors(): List<ActionDescriptor> = emptyList()
                override fun execute(name: String, argsJson: String) = ActionResult(ActionOutcome.Done, name)
                override fun cancel() = Unit
            },
        )
        val environment = object : ScheduledHostEnvironment by rig.environment {
            override val availableCapabilities: Set<Capability> = capabilities
            override fun services(scope: ResourceScope, granted: Set<Capability>): HostServices =
                ScopedHostServices(scope, granted, providers, tasks)
        }
        val host = HostRuntime(1, environment)
        host.startSession(SessionId("s"))
        host.markSceneReady()
        val plugin = BlockingTurnPlugin()
        host.register(plugin)
        assertEquals(ActivationResult.Activated, host.activate(plugin.descriptor.pluginId))
        return host to plugin
    }

    @Test
    fun aConfirmationAnswerReachesAPluginWhoseTurnThreadIsBlocked() {
        // `ThreadedTaskScope`, not the rig's inline `FakeTaskScope`: the production composition gives a plugin a real
        // pool, and the property under test is precisely that the answer does not need the turn's thread to be free.
        val (host, plugin) = hostWithBlockingPlugin(tasks = ThreadedTaskScope())
        val session = SessionId("s")

        assertTrue(host.onEvent(WakeDetected(EventEnvelope(session, host.currentGeneration, "w1", 0, 1), "edith", 0.9)))
        assertTrue(plugin.turnStarted.await(2, TimeUnit.SECONDS), "the turn must reach its question")

        assertTrue(host.onEvent(ConfirmationResult(EventEnvelope(session, host.currentGeneration, "k1", 0, 1), "k1", true)))
        assertTrue(plugin.turnFinished.await(2, TimeUnit.SECONDS),
            "§17.4: an answer must reach a plugin whose turn thread is blocked")
        assertEquals(true, plugin.answer?.approved)
    }
}
