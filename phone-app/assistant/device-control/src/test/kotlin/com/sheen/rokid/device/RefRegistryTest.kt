package com.sheen.rokid.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Ref identity, reuse across observations, and the deliberately bounded rebind rule:
 * a ref may follow a node whose text/description changed while the node stayed in the same
 * structural slot and geometry, at most twice; anything else is stale.
 */
class RefRegistryTest {

    @Test
    fun `assign keys every node by its structural path`() {
        val registry = RefRegistry()

        val refs = registry.assign(observation(1, node(depth = 0), node(depth = 1)))

        assertEquals(setOf("0", "0/0"), refs.keys)
        assertTrue(refs.getValue("0").value.matches(Regex("#n\\d+")))
    }

    @Test
    fun `the same tree gets the same refs on a later observation`() {
        val registry = RefRegistry()

        val first = registry.assign(observation(1, node()))
        val second = registry.assign(observation(2, node()))

        assertEquals(first, second)
    }

    @Test
    fun `an unchanged tree still resolves the old ref in the next revision`() {
        val registry = RefRegistry()
        val ref = registry.assign(observation(1, node())).getValue("0")

        assertEquals(RefResult.Ok(ref), registry.resolve(observation(2, node()), ref))
    }

    @Test
    fun `a changed text is re-bound to the node that stayed in place`() {
        val registry = RefRegistry()
        val oldRef = registry.assign(observation(1, node(text = "Wi-Fi"))).getValue("0")
        val next = observation(2, node(text = "Wi-Fi, connected"))
        val newRef = registry.assign(next).getValue("0")

        assertNotEquals(oldRef, newRef)
        val result = assertIs<RefResult.Ok>(registry.resolve(next, oldRef))
        assertEquals(newRef, result.ref)
    }

    @Test
    fun `a ref is re-bound at most twice and then goes stale`() {
        val registry = RefRegistry()
        val ref = registry.assign(observation(1, node(text = "v1"))).getValue("0")

        val second = observation(2, node(text = "v2"))
        registry.assign(second)
        assertIs<RefResult.Ok>(registry.resolve(second, ref))

        val third = observation(3, node(text = "v3"))
        registry.assign(third)
        assertIs<RefResult.Ok>(registry.resolve(third, ref))

        val fourth = observation(4, node(text = "v4"))
        registry.assign(fourth)
        val stale = assertIs<RefResult.Stale>(registry.resolve(fourth, ref))
        assertTrue(
            stale.reason.contains("rebind"),
            "the reason should say the identity rebind budget ran out: ${stale.reason}",
        )
    }

    @Test
    fun `an identity that comes back after its ref was rebound away gets a fresh usable ref`() {
        val registry = RefRegistry()
        val original = registry.assign(observation(1, node(text = "A"))).getValue("0")

        val b = observation(2, node(text = "B"))
        registry.assign(b)
        assertIs<RefResult.Ok>(registry.resolve(b, original))

        val c = observation(3, node(text = "C"))
        registry.assign(c)
        assertIs<RefResult.Ok>(registry.resolve(c, original))

        val aAgain = observation(4, node(text = "A"))
        val reassigned = registry.assign(aAgain).getValue("0")

        assertNotEquals(
            original,
            reassigned,
            "the original ref now aliases another node; reusing it would print an unusable ref",
        )
        assertEquals(RefResult.Ok(reassigned), registry.resolve(aAgain, reassigned))
    }

    @Test
    fun `absence from a truncated observation is stale without spending the rebind budget`() {
        val registry = RefRegistry()
        val ref = registry.assign(observation(1, node(text = "v1"))).getValue("0")

        repeat(3) { attempt ->
            val truncated = observation(
                2,
                node(text = "something else", viewId = "android:id/other"),
                truncated = true,
            )
            val stale = assertIs<RefResult.Stale>(registry.resolve(truncated, ref))
            assertTrue(
                stale.reason.contains("truncated"),
                "attempt $attempt should report the truncation-specific reason: ${stale.reason}",
            )
        }

        val visibleAgain = observation(3, node(text = "v2"))
        registry.assign(visibleAgain)

        assertIs<RefResult.Ok>(registry.resolve(visibleAgain, ref))
    }

    @Test
    fun `reset forgets old refs but never recycles their numbers`() {
        val registry = RefRegistry()
        val old = registry.assign(observation(1, node())).getValue("0")

        registry.reset()

        val stale = assertIs<RefResult.Stale>(registry.resolve(observation(2, node()), old))
        assertTrue(stale.reason.contains("unknown"), "unexpected reason: ${stale.reason}")

        val fresh = registry.assign(observation(3, node())).getValue("0")
        assertNotEquals(old, fresh, "a ref value must not be recycled after reset")
        assertEquals(RefResult.Ok(fresh), registry.resolve(observation(3, node()), fresh))
    }

    @Test
    fun `ref identity ignores collection range tooltip and drawing order metadata`() {
        val registry = RefRegistry()
        val first = registry.assign(observation(1, node())).getValue("0")
        val withMetadata = node().copy(
            collection = CollectionInfo(rowCount = 2, columnCount = 1),
            range = RangeInfo(current = 0.5f, min = 0f, max = 1f),
            tooltip = "Help",
            drawingOrder = 4,
        )

        assertEquals(first, registry.assign(observation(2, withMetadata)).getValue("0"))
    }

    @Test
    fun `a ref whose node disappeared is stale`() {
        val registry = RefRegistry()
        val ref = registry.assign(observation(1, node())).getValue("0")

        val stale = assertIs<RefResult.Stale>(
            registry.resolve(observation(2, node(text = "Settings", viewId = "android:id/other")), ref),
        )
        assertTrue(stale.reason.isNotBlank())
    }

    @Test
    fun `a node that moved is not silently re-targeted by the same ref`() {
        val registry = RefRegistry()
        val ref = registry.assign(observation(1, node(bounds = Bounds(0, 0, 100, 100)))).getValue("0")

        val moved = observation(2, node(bounds = Bounds(0, 300, 100, 400)))
        registry.assign(moved)

        assertIs<RefResult.Stale>(registry.resolve(moved, ref))
    }

    @Test
    fun `an unknown ref is stale instead of being resolved by position`() {
        val registry = RefRegistry()

        val stale = assertIs<RefResult.Stale>(registry.resolve(observation(1, node()), Ref("#n99")))

        assertTrue(stale.reason.contains("unknown"), "unexpected reason: ${stale.reason}")
    }

    private fun node(
        role: String = "button",
        text: String? = "Wi-Fi",
        desc: String? = null,
        viewId: String? = "android:id/title",
        bounds: Bounds = Bounds(0, 0, 100, 100),
        states: Set<String> = setOf("clickable"),
        actions: List<String> = listOf("click"),
        depth: Int = 0,
    ) = DeviceNode(
        role = role,
        text = text,
        contentDescription = desc,
        viewId = viewId,
        bounds = bounds,
        states = states,
        actions = actions,
        hint = null,
        paneTitle = null,
        windowId = 0,
        depth = depth,
    )

    private fun observation(
        revision: Long,
        vararg nodes: DeviceNode,
        truncated: Boolean = false,
    ) = Observation(
        revision = revision,
        windows = listOf(
            ObservationWindow(
                id = 0,
                type = "application",
                layer = 0,
                bounds = Bounds(0, 0, 1080, 2400),
                packageName = "com.example",
                title = "Demo",
            ),
        ),
        nodes = nodes.toList(),
        truncated = truncated,
    )
}
