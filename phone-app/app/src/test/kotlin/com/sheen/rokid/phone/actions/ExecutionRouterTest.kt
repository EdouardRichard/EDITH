package com.sheen.rokid.phone.actions
import com.sheen.rokid.phone.storage.unavailable

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [`ExecutionRouter`] is the single `Capability.Actions` provider (spec §17.2). Its two rules are what keep a channel
 * that is down from becoming a silent success: an unavailable backend's actions are **not advertised**, and a call that
 * arrives after its channel went down is **refused** rather than executed.
 */
class ExecutionRouterTest {

    private class FakeBackend(
        override val id: String,
        var available: Boolean = true,
        private val actions: List<String>,
    ) : ExecutionBackend {
        val executed = mutableListOf<Pair<String, String>>()
        var cancellations = 0
        var result: ActionResult = ActionResult(ActionOutcome.Done, "done")

        override fun available() = available
        override fun descriptors(): List<ActionDescriptor> = actions.map { name ->
            ActionDescriptor(
                name = name,
                title = "Title of $name",
                parametersJson = """{"type":"object"}""",
                risk = RiskLevel.L2,
                requiresConfirmation = false,
                idempotent = true,
                reversible = true,
                scope = "test",
            )
        }

        override fun execute(name: String, argsJson: String): ActionResult {
            executed += name to argsJson
            return result
        }

        override fun cancel() { cancellations++ }
    }

    @Test fun `only available backends are advertised`() {
        val up = FakeBackend("intent", available = true, actions = listOf("open_app"))
        val down = FakeBackend("shizuku", available = false, actions = listOf("uninstall"))
        val router = ExecutionRouter(listOf(up, down))

        assertEquals(listOf("open_app"), router.descriptors().map { it.name })
    }

    @Test fun `a channel that goes down after advertising refuses instead of executing`() {
        val backend = FakeBackend("shizuku", available = true, actions = listOf("uninstall"))
        val router = ExecutionRouter(listOf(backend))
        assertEquals("advertised while the channel is up", listOf("uninstall"), router.descriptors().map { it.name })

        backend.available = false

        assertEquals(ActionResult(ActionOutcome.Refused, "ACTION_CHANNEL_UNAVAILABLE"),
            router.execute("uninstall", "{}"))
        assertTrue("a call must never reach a backend whose channel is down", backend.executed.isEmpty())
    }

    @Test fun `an unknown name is refused with UNKNOWN_ACTION rather than a channel error`() {
        val router = ExecutionRouter(listOf(FakeBackend("intent", actions = listOf("open_app"))))

        assertEquals(ActionResult(ActionOutcome.Refused, "UNKNOWN_ACTION"), router.execute("nope", "{}"))
    }

    @Test fun `two backends claiming the same action name are rejected at construction`() {
        val first = FakeBackend("intent", actions = listOf("open_app"))
        val second = FakeBackend("accessibility", actions = listOf("open_app"))

        assertThrows(IllegalArgumentException::class.java) { ExecutionRouter(listOf(first, second)) }
    }

    @Test fun `execute routes to the backend that declared the action`() {
        val intent = FakeBackend("intent", actions = listOf("open_app"))
        val shizuku = FakeBackend("shizuku", actions = listOf("uninstall"))
        val router = ExecutionRouter(listOf(intent, shizuku))

        router.execute("uninstall", """{"package":"x"}""")

        assertEquals(listOf("uninstall" to """{"package":"x"}"""), shizuku.executed)
        assertTrue(intent.executed.isEmpty())
    }

    @Test fun `cancel reaches every backend`() {
        val intent = FakeBackend("intent", actions = listOf("open_app"))
        val shizuku = FakeBackend("shizuku", actions = listOf("uninstall"))

        ExecutionRouter(listOf(intent, shizuku)).cancel()

        assertEquals(1, intent.cancellations)
        assertEquals(1, shizuku.cancellations)
    }

    @Test fun `a router with no backends advertises nothing and refuses everything`() {
        val router = ExecutionRouter(emptyList<ExecutionBackend>())

        assertTrue(router.descriptors().isEmpty())
        assertFalse(router.execute("anything", "{}").outcome == ActionOutcome.Done)
    }
}
