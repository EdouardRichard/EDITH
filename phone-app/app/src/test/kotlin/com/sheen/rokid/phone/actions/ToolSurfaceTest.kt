package com.sheen.rokid.phone.actions

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionPrecheck
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.PrecheckDecision
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * edith-0.3 Phase 4A Task 3 (A3): `ToolSurface` is the one ordered `ActionExecutor` over the atomic backends and the
 * umbrella `ToolCatalog`.
 *
 * The tests pin what makes it an adapter and not a second policy layer: the descriptor order (atomic first, then the
 * catalog's four), routing by declared name, the stable `UNKNOWN_ACTION` refusal, the construction-time duplicate check,
 * `precheck` confined to the catalog, and pure lifecycle forwarding.
 */
class ToolSurfaceTest {

    private open class FakeExecutor(
        private val names: List<String>,
        private val runtime: String? = null,
    ) : ActionExecutor {
        val executed = mutableListOf<Pair<String, String>>()
        val prechecks = mutableListOf<Pair<String, String>>()
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
            executed += name to argsJson
            return ActionResult(ActionOutcome.Done, "done")
        }

        override fun precheck(name: String, argsJson: String): ActionPrecheck {
            prechecks += name to argsJson
            return ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "ask")
        }

        override fun beginTurn() { begins++ }

        override fun cancel() { cancellations++ }

        override fun runtimeState(): String? = runtime
    }

    /** `AutoCloseable` is optional on the interface; this fake proves the surface closes the ones that have it. */
    private class CloseableFakeExecutor(
        names: List<String>,
        runtime: String? = null,
    ) : FakeExecutor(names, runtime), AutoCloseable {
        var closes = 0

        override fun close() { closes++ }
    }

    private fun atomicExecutors() = listOf(
        FakeExecutor(listOf("read_screen")),
        FakeExecutor(listOf("tap")),
        FakeExecutor(listOf("long_press")),
        FakeExecutor(listOf("scroll")),
        FakeExecutor(listOf("input_text")),
    )

    private fun catalogExecutor() = FakeExecutor(listOf("device", "shell", "open", "dial"))

    @Test
    fun `advertises the five atomic descriptors then the catalogs four in fixed order`() {
        val surface = ToolSurface(catalogExecutor(), atomicExecutors())

        assertEquals(
            listOf("read_screen", "tap", "long_press", "scroll", "input_text", "device", "shell", "open", "dial"),
            surface.descriptors().map { it.name },
        )
    }

    @Test
    fun `execute routes each name to the one executor that declares it`() {
        val atomic = atomicExecutors()
        val catalog = catalogExecutor()
        val surface = ToolSurface(catalog, atomic)

        val tapArgs = """{"index":0}"""
        surface.execute("tap", tapArgs)

        assertEquals(listOf("tap" to tapArgs), atomic[1].executed)
        assertTrue(
            "only the declaring executor may see the call",
            atomic.filterIndexed { index, _ -> index != 1 }.all { it.executed.isEmpty() },
        )
        assertTrue("the catalog must not see an atomic call", catalog.executed.isEmpty())

        val deviceArgs = """{"action":"status"}"""
        surface.execute("device", deviceArgs)

        assertEquals(listOf("device" to deviceArgs), catalog.executed)
    }

    @Test
    fun `an unknown name is refused with UNKNOWN_ACTION and reaches no executor`() {
        val atomic = atomicExecutors()
        val catalog = catalogExecutor()
        val surface = ToolSurface(catalog, atomic)

        val result = surface.execute("teleport", "{}")

        assertEquals("UNKNOWN_ACTION", ToolSurface.UNKNOWN_ACTION)
        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("{}", result.detail)
        val execution = requireNotNull(result.execution) { "the refusal must carry the stable code" }
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals(ToolSurface.UNKNOWN_ACTION, execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertTrue("an unknown name must not reach an atomic executor", atomic.all { it.executed.isEmpty() })
        assertTrue("an unknown name must not reach the catalog", catalog.executed.isEmpty())
    }

    @Test
    fun `a duplicate name is refused at construction`() {
        val duplicateAtomics = listOf(FakeExecutor(listOf("tap")), FakeExecutor(listOf("tap")))
        assertThrows(IllegalArgumentException::class.java) {
            ToolSurface(catalogExecutor(), duplicateAtomics)
        }

        val catalogCollision = FakeExecutor(listOf("device"))
        assertThrows(IllegalArgumentException::class.java) {
            ToolSurface(catalogExecutor(), listOf(catalogCollision))
        }
    }

    @Test
    fun `precheck forwards only catalog declared names`() {
        val atomic = atomicExecutors()
        val catalog = catalogExecutor()
        val surface = ToolSurface(catalog, atomic)

        val shellArgs = """{"command":"pm clear com.x"}"""
        assertEquals(
            ActionPrecheck(PrecheckDecision.RequireConfirmation, summary = "ask"),
            surface.precheck("shell", shellArgs),
        )
        assertEquals(listOf("shell" to shellArgs), catalog.prechecks)
        assertTrue("an atomic executor must not be consulted", atomic.all { it.prechecks.isEmpty() })

        assertEquals(PrecheckDecision.Allow, surface.precheck("tap", "{}").decision)
        assertEquals(PrecheckDecision.Allow, surface.precheck("teleport", "{}").decision)
        assertEquals("only the catalog call was forwarded", listOf("shell" to shellArgs), catalog.prechecks)
    }

    @Test
    fun `beginTurn and cancel are broadcast to every executor`() {
        val atomic = atomicExecutors()
        val catalog = catalogExecutor()
        val surface = ToolSurface(catalog, atomic)

        surface.beginTurn()
        surface.cancel()

        atomic.forEach { executor ->
            assertEquals("beginTurn must reach every atomic executor", 1, executor.begins)
            assertEquals("cancel must reach every atomic executor", 1, executor.cancellations)
        }
        assertEquals(1, catalog.begins)
        assertEquals(1, catalog.cancellations)
    }

    @Test
    fun `runtimeState is the catalogs line`() {
        val catalog = FakeExecutor(
            listOf("device", "shell", "open", "dial"),
            runtime = "foreground=com.example revision=7",
        )
        val surface = ToolSurface(catalog, listOf(FakeExecutor(listOf("tap"), runtime = "atomic=tap")))

        assertEquals("foreground=com.example revision=7", surface.runtimeState())
    }

    @Test
    fun `runtimeState is null when the catalog has no state`() {
        val surface = ToolSurface(catalogExecutor(), listOf(FakeExecutor(listOf("tap"), runtime = "atomic=tap")))

        assertNull("an atomic executor's state is not the catalog's", surface.runtimeState())
    }

    @Test
    fun `close closes the closeable atomic executors and then invokes onClose`() {
        val plain = FakeExecutor(listOf("tap"))
        val closeable = CloseableFakeExecutor(listOf("scroll"))
        val catalog = CloseableFakeExecutor(listOf("device", "shell", "open", "dial"))
        var onCloseCalls = 0
        val surface = ToolSurface(catalog, listOf(plain, closeable)) { onCloseCalls++ }

        surface.close()

        assertEquals(1, closeable.closes)
        assertEquals("the catalog is closed through onClose, not by the surface", 0, catalog.closes)
        assertEquals(1, onCloseCalls)
    }
}
