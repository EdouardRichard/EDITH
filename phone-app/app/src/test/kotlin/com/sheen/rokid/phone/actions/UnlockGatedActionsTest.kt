package com.sheen.rokid.phone.actions

import com.sheen.rokid.phone.lock.UnlockGate
import com.sheen.rokid.phone.lock.UnlockOutcome
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionPrecheck
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.PrecheckDecision
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * B (2026-10-22 acceptance): one decorator applies the lock-screen gate to every tool that needs an unlocked phone,
 * so which tool the model happens to pick cannot decide whether unlocking happens.
 */
class UnlockGatedActionsTest {

    private open class FakeExecutor(private val names: List<String>) : ActionExecutor {
        val executed = mutableListOf<String>()
        var begins = 0
        var cancellations = 0

        override fun descriptors(): List<ActionDescriptor> = names.map { name ->
            ActionDescriptor(
                name = name,
                title = "Title of $name",
                parametersJson = """{"type":"object"}""",
                risk = RiskLevel.L1,
                requiresConfirmation = false,
                idempotent = false,
                reversible = false,
                scope = "test",
            )
        }

        override fun execute(name: String, argsJson: String): ActionResult {
            executed += name
            return ActionResult(ActionOutcome.Done, "done")
        }

        override fun precheck(name: String, argsJson: String): ActionPrecheck = ActionPrecheck(PrecheckDecision.Allow)
        override fun beginTurn() { begins++ }
        override fun cancel() { cancellations++ }
        override fun runtimeState(): String? = "runtime"
    }

    private fun gated(
        names: List<String>,
        outcome: UnlockOutcome = UnlockOutcome.ALREADY_UNLOCKED,
        calls: MutableList<Boolean> = mutableListOf(),
        lines: MutableList<String> = mutableListOf(),
    ): Pair<UnlockGatedActions, FakeExecutor> {
        val inner = FakeExecutor(names)
        val gate = UnlockGate { requireAccessibility -> calls += requireAccessibility; outcome }
        return UnlockGatedActions(inner, gate, log = { lines += it }) to inner
    }

    @Test
    fun aScreenToolIsGatedAndAsksForAccessibility() {
        val calls = mutableListOf<Boolean>()
        val (surface, inner) = gated(listOf("read_screen"), calls = calls)

        assertEquals(ActionOutcome.Done, surface.execute("read_screen", "{}").outcome)
        assertEquals(listOf(true), calls)
        assertEquals(listOf("read_screen"), inner.executed)
    }

    @Test
    fun anIntentToolIsGatedWithoutWaitingForAccessibility() {
        val calls = mutableListOf<Boolean>()
        val (surface, inner) = gated(listOf("open_app"), calls = calls)

        surface.execute("open_app", """{"target":"com.tencent.mm"}""")

        assertEquals(listOf(false), calls)
        assertEquals(listOf("open_app"), inner.executed)
    }

    @Test
    fun aRefusedUnlockKeepsTheCatalogRefusalShapeAndNeverReachesTheTool() {
        val (surface, inner) = gated(listOf("open_app"), outcome = UnlockOutcome.STILL_LOCKED)

        val result = surface.execute("open_app", """{"name":"微信"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("{}", result.detail)
        assertEquals("LOCKED_UNLOCK_FAILED:STILL_LOCKED", result.execution?.code)
        assertEquals(ToolOutcome.Refused, result.execution?.outcome)
        assertEquals(true, result.execution?.recoverable)
        assertEquals(false, result.execution?.accepted)
        assertEquals(false, result.execution?.applied)
        assertTrue("a refused unlock must not run the tool", inner.executed.isEmpty())
    }

    @Test
    fun toolsThatNeedNoScreenAreNeverGated() {
        val calls = mutableListOf<Boolean>()
        val (surface, inner) = gated(listOf("shell", "list_apps", "set_brightness", "notify_user"), calls = calls)

        listOf("shell", "list_apps", "set_brightness", "notify_user").forEach { surface.execute(it, "{}") }

        assertTrue("no-screen tools must not be blocked by a locked phone: $calls", calls.isEmpty())
        assertEquals(listOf("shell", "list_apps", "set_brightness", "notify_user"), inner.executed)
    }

    @Test
    fun theFourUmbrellaToolsStayTheCatalogsResponsibility() {
        val calls = mutableListOf<Boolean>()
        val (surface, inner) = gated(listOf("device", "open", "shell", "dial"), calls = calls)

        listOf("device", "open", "shell", "dial").forEach { surface.execute(it, "{}") }

        assertTrue("the decorator must not gate the catalog's umbrellas twice: $calls", calls.isEmpty())
        assertEquals(listOf("device", "open", "shell", "dial"), inner.executed)
    }

    @Test
    fun anUndeclaredNameIsNotGated() {
        val calls = mutableListOf<Boolean>()
        val (surface, _) = gated(listOf("read_screen"), calls = calls)

        surface.execute("not_a_declared_name", "{}")

        assertTrue(calls.isEmpty())
    }

    @Test
    fun lifecycleDescriptorAndRuntimeReadsAreForwarded() {
        val (surface, inner) = gated(listOf("read_screen", "shell"))

        assertEquals(inner.descriptors().map { it.name }, surface.descriptors().map { it.name })
        assertEquals("runtime", surface.runtimeState())
        surface.beginTurn()
        surface.cancel()
        assertEquals(1, inner.begins)
        assertEquals(1, inner.cancellations)
    }

    @Test
    fun aGateRunIsLoggedWithoutAnyPayload() {
        val lines = mutableListOf<String>()
        val (surface, _) = gated(listOf("read_screen"), lines = lines)

        surface.execute("read_screen", "{}")

        assertEquals(1, lines.size)
        assertTrue(
            "the log must name the requirement and the outcome: ${lines.single()}",
            lines.single() == "P3_UNLOCK tool=read_screen requireA11y=true outcome=ALREADY_UNLOCKED",
        )
        assertFalse("arguments must never reach the log", lines.any { it.contains("{") })
    }

    @Test
    fun closingTheDecoratorClosesACloseableInner() {
        var closes = 0
        val inner = object : FakeExecutor(listOf("shell")), AutoCloseable {
            override fun close() { closes++ }
        }
        val surface = UnlockGatedActions(inner, UnlockGate { UnlockOutcome.ALREADY_UNLOCKED }, log = {})
        surface.close()
        assertEquals(1, closes)
    }
}
