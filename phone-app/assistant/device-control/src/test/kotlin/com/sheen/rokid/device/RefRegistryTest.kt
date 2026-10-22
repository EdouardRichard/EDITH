package com.sheen.rokid.device

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ref identity, reuse across observations, and the deliberately bounded rebind rule:
 * a ref may follow a node whose text/description changed while the node stayed in the same
 * structural slot and geometry, at most twice; anything else is stale.
 *
 * The snapshot tests pin Phase 2's action-time contract: a ref is only actionable while the
 * snapshot that assigned it is still current, unexpired, and in front. The legacy [RefRegistry.resolve]
 * keeps its pre-Phase-2 behavior and never consults that metadata.
 */
class RefRegistryTest {

    @Test
    fun `assign keys every node by its structural path`() {
        val registry = RefRegistry()

        val refs = registry.assign(
            testObservation(listOf(testNode(depth = 0), testNode(depth = 1))),
        )

        assertEquals(setOf("0", "0/0"), refs.keys)
        assertTrue(refs.getValue("0").value.matches(Regex("#n\\d+")))
    }

    @Test
    fun `the same tree gets the same refs on a later observation`() {
        val registry = RefRegistry()

        val first = registry.assign(testObservation())
        val second = registry.assign(testObservation())

        assertEquals(first, second)
    }

    @Test
    fun `an unchanged tree still resolves the old ref in the next revision`() {
        val registry = RefRegistry()
        val ref = registry.assign(testObservation()).getValue("0")

        assertEquals(RefResult.Ok(ref), registry.resolve(testObservation(), ref))
    }

    @Test
    fun `a changed text is re-bound to the node that stayed in place`() {
        val registry = RefRegistry()
        val oldRef = registry.assign(testObservation(listOf(testNode(text = "Wi-Fi")))).getValue("0")
        val next = testObservation(listOf(testNode(text = "Wi-Fi, connected")))
        val newRef = registry.assign(next).getValue("0")

        assertNotEquals(oldRef, newRef)
        val result = assertIs<RefResult.Ok>(registry.resolve(next, oldRef))
        assertEquals(newRef, result.ref)
    }

    @Test
    fun `a ref is re-bound at most twice and then goes stale`() {
        val registry = RefRegistry()
        val ref = registry.assign(testObservation(listOf(testNode(text = "v1")))).getValue("0")

        val second = testObservation(listOf(testNode(text = "v2")))
        registry.assign(second)
        assertIs<RefResult.Ok>(registry.resolve(second, ref))

        val third = testObservation(listOf(testNode(text = "v3")))
        registry.assign(third)
        assertIs<RefResult.Ok>(registry.resolve(third, ref))

        val fourth = testObservation(listOf(testNode(text = "v4")))
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
        val original = registry.assign(testObservation(listOf(testNode(text = "A")))).getValue("0")

        val b = testObservation(listOf(testNode(text = "B")))
        registry.assign(b)
        assertIs<RefResult.Ok>(registry.resolve(b, original))

        val c = testObservation(listOf(testNode(text = "C")))
        registry.assign(c)
        assertIs<RefResult.Ok>(registry.resolve(c, original))

        val aAgain = testObservation(listOf(testNode(text = "A")))
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
        val ref = registry.assign(testObservation(listOf(testNode(text = "v1")))).getValue("0")

        repeat(3) { attempt ->
            val truncated = testObservation(
                listOf(testNode(text = "something else", viewId = "android:id/other")),
                truncated = true,
            )
            val stale = assertIs<RefResult.Stale>(registry.resolve(truncated, ref))
            assertTrue(
                stale.reason.contains("truncated"),
                "attempt $attempt should report the truncation-specific reason: ${stale.reason}",
            )
        }

        val visibleAgain = testObservation(listOf(testNode(text = "v2")))
        registry.assign(visibleAgain)

        assertIs<RefResult.Ok>(registry.resolve(visibleAgain, ref))
    }

    @Test
    fun `reset forgets old refs but never recycles their numbers`() {
        val registry = RefRegistry()
        val old = registry.assign(testObservation()).getValue("0")

        registry.reset()

        val stale = assertIs<RefResult.Stale>(registry.resolve(testObservation(), old))
        assertTrue(stale.reason.contains("unknown"), "unexpected reason: ${stale.reason}")

        val fresh = registry.assign(testObservation()).getValue("0")
        assertNotEquals(old, fresh, "a ref value must not be recycled after reset")
        assertEquals(RefResult.Ok(fresh), registry.resolve(testObservation(), fresh))
    }

    @Test
    fun `ref identity ignores collection range tooltip and drawing order metadata`() {
        val registry = RefRegistry()
        val first = registry.assign(testObservation()).getValue("0")
        val withMetadata = testNode().copy(
            collection = CollectionInfo(rowCount = 2, columnCount = 1),
            range = RangeInfo(current = 0.5f, min = 0f, max = 1f),
            tooltip = "Help",
            drawingOrder = 4,
        )

        assertEquals(first, registry.assign(testObservation(listOf(withMetadata))).getValue("0"))
    }

    @Test
    fun `a ref whose node disappeared is stale`() {
        val registry = RefRegistry()
        val ref = registry.assign(testObservation()).getValue("0")

        val stale = assertIs<RefResult.Stale>(
            registry.resolve(
                testObservation(listOf(testNode(text = "Settings", viewId = "android:id/other"))),
                ref,
            ),
        )
        assertTrue(stale.reason.isNotBlank())
    }

    @Test
    fun `a node that moved is not silently re-targeted by the same ref`() {
        val registry = RefRegistry()
        val ref = registry.assign(
            testObservation(listOf(testNode(bounds = Bounds(0, 0, 100, 100)))),
        ).getValue("0")

        val moved = testObservation(listOf(testNode(bounds = Bounds(0, 300, 100, 400))))
        registry.assign(moved)

        assertIs<RefResult.Stale>(registry.resolve(moved, ref))
    }

    @Test
    fun `an unknown ref is stale instead of being resolved by position`() {
        val registry = RefRegistry()

        val stale = assertIs<RefResult.Stale>(registry.resolve(testObservation(), Ref("#n99")))

        assertTrue(stale.reason.contains("unknown"), "unexpected reason: ${stale.reason}")
        assertEquals("STALE_REF", stale.code)
    }

    @Test
    fun `a snapshot records when it was issued and what is in front`() {
        val registry = RefRegistry()

        assertNull(registry.currentSnapshot(), "a registry starts without a snapshot")

        registry.beginSnapshot(SNAPSHOT, ISSUED_AT, PACKAGE, ACTIVITY)

        assertEquals(
            RefSnapshot(SNAPSHOT, ISSUED_AT, PACKAGE, ACTIVITY),
            registry.currentSnapshot(),
        )
    }

    @Test
    fun `a fresh ref of the current snapshot resolves for an action`() {
        val registry = registryInSnapshot()
        val observation = testObservation()
        val ref = registry.assign(observation).getValue("0")

        assertEquals(
            RefResult.Ok(ref),
            registry.resolveForAction(observation, ref, ISSUED_AT, PACKAGE, ACTIVITY, TTL),
        )
    }

    @Test
    fun `ttl expiry is strict so the exact boundary still resolves`() {
        val registry = registryInSnapshot()
        val observation = testObservation()
        val ref = registry.assign(observation).getValue("0")

        val atBoundary = ISSUED_AT + TTL
        assertEquals(
            RefResult.Ok(ref),
            registry.resolveForAction(observation, ref, atBoundary, PACKAGE, ACTIVITY, TTL),
        )
    }

    @Test
    fun `a ref older than the ttl is expired`() {
        val registry = registryInSnapshot()
        val observation = testObservation()
        val ref = registry.assign(observation).getValue("0")

        val stale = assertIs<RefResult.Stale>(
            registry.resolveForAction(
                observation,
                ref,
                ISSUED_AT + TTL + 1,
                PACKAGE,
                ACTIVITY,
                TTL,
            ),
        )
        assertEquals("REF_EXPIRED", stale.code)
    }

    @Test
    fun `a non-positive ttl expires every snapshot immediately`() {
        listOf(0L, -1L).forEach { ttl ->
            val registry = registryInSnapshot()
            val observation = testObservation()
            val ref = registry.assign(observation).getValue("0")

            val stale = assertIs<RefResult.Stale>(
                registry.resolveForAction(observation, ref, ISSUED_AT, PACKAGE, ACTIVITY, ttl),
            )
            assertEquals("REF_EXPIRED", stale.code, "ttl=$ttl should expire the snapshot at once")
        }
    }

    @Test
    fun `a foreground package change makes an action ref stale`() {
        val registry = registryInSnapshot()
        val observation = testObservation()
        val ref = registry.assign(observation).getValue("0")

        val stale = assertIs<RefResult.Stale>(
            registry.resolveForAction(observation, ref, ISSUED_AT, "com.other.app", ACTIVITY, TTL),
        )
        assertEquals("FOREGROUND_CHANGED", stale.code)
    }

    @Test
    fun `a foreground activity change makes an action ref stale`() {
        val registry = registryInSnapshot()
        val observation = testObservation()
        val ref = registry.assign(observation).getValue("0")

        val stale = assertIs<RefResult.Stale>(
            registry.resolveForAction(observation, ref, ISSUED_AT, PACKAGE, ".OtherActivity", TTL),
        )
        assertEquals("FOREGROUND_CHANGED", stale.code)
    }

    @Test
    fun `a ref not re-assigned in the new snapshot is cross-snapshot stale`() {
        val registry = registryInSnapshot()
        val observation = testObservation()
        val ref = registry.assign(observation).getValue("0")

        registry.beginSnapshot("snapshot-2", ISSUED_AT + 1, PACKAGE, ACTIVITY)

        val stale = assertIs<RefResult.Stale>(
            registry.resolveForAction(observation, ref, ISSUED_AT + 2, PACKAGE, ACTIVITY, TTL),
        )
        assertEquals(
            "CROSS_SNAPSHOT",
            stale.code,
            "the node survives but was not assigned in the current snapshot",
        )
    }

    @Test
    fun `a ref whose node did not survive into the new snapshot is cross-snapshot stale`() {
        val registry = registryInSnapshot()
        val ref = registry.assign(testObservation()).getValue("0")

        registry.beginSnapshot("snapshot-2", ISSUED_AT + 1, PACKAGE, ACTIVITY)
        val next = testObservation(
            listOf(testNode(text = "Settings", viewId = "android:id/other")),
        )
        registry.assign(next)

        val stale = assertIs<RefResult.Stale>(
            registry.resolveForAction(next, ref, ISSUED_AT + 2, PACKAGE, ACTIVITY, TTL),
        )
        assertEquals(
            "CROSS_SNAPSHOT",
            stale.code,
            "an absent node must never be silently re-targeted through the new snapshot",
        )
    }

    @Test
    fun `a surviving node re-assigned in the new snapshot keeps resolving`() {
        val registry = registryInSnapshot()
        val first = testObservation(listOf(testNode()))
        val ref = registry.assign(first).getValue("0")

        registry.beginSnapshot("snapshot-2", ISSUED_AT + 1, PACKAGE, ACTIVITY)
        val second = testObservation(listOf(testNode()))
        val reassigned = registry.assign(second).getValue("0")

        assertEquals(ref, reassigned, "a surviving node keeps its ref")
        assertEquals(
            RefResult.Ok(ref),
            registry.resolveForAction(second, ref, ISSUED_AT + 2, PACKAGE, ACTIVITY, TTL),
        )
    }

    @Test
    fun `resolving for an action without a current snapshot is stale`() {
        val registry = RefRegistry()
        val observation = testObservation()
        val ref = registry.assign(observation).getValue("0")

        val stale = assertIs<RefResult.Stale>(
            registry.resolveForAction(observation, ref, 0L, null, null, TTL),
        )
        assertEquals("STALE_REF", stale.code)
        assertEquals("no current snapshot", stale.reason)
    }

    @Test
    fun `an unknown ref is STALE_REF for an action`() {
        val registry = registryInSnapshot()
        val observation = testObservation()
        registry.assign(observation)

        val stale = assertIs<RefResult.Stale>(
            registry.resolveForAction(observation, Ref("#n99"), ISSUED_AT, PACKAGE, ACTIVITY, TTL),
        )
        assertEquals("STALE_REF", stale.code)
        assertTrue(stale.reason.contains("unknown"), "unexpected reason: ${stale.reason}")
    }

    @Test
    fun `reset clears the current snapshot metadata`() {
        val registry = registryInSnapshot()
        val observation = testObservation()
        val ref = registry.assign(observation).getValue("0")

        registry.reset()

        assertNull(registry.currentSnapshot())
        val stale = assertIs<RefResult.Stale>(
            registry.resolveForAction(observation, ref, ISSUED_AT, PACKAGE, ACTIVITY, TTL),
        )
        assertEquals("STALE_REF", stale.code)
        assertEquals("no current snapshot", stale.reason)
    }

    @Test
    fun `legacy resolve ignores snapshot id and foreground`() {
        val registry = registryInSnapshot()
        val observation = testObservation()
        val ref = registry.assign(observation).getValue("0")

        registry.beginSnapshot(
            "snapshot-2",
            ISSUED_AT + 10 * TTL,
            "com.other.app",
            ".OtherActivity",
        )

        assertEquals(
            RefResult.Ok(ref),
            registry.resolve(observation, ref),
            "pre-Phase-2 resolve must not consult snapshot, ttl or foreground",
        )
    }

    @Test
    fun `identity resolution does not stamp the new snapshot id`() {
        val registry = registryInSnapshot()
        val observation = testObservation()
        val ref = registry.assign(observation).getValue("0")

        registry.beginSnapshot("snapshot-2", ISSUED_AT, PACKAGE, ACTIVITY)

        assertEquals(RefResult.Ok(ref), registry.resolve(observation, ref))
        val stale = assertIs<RefResult.Stale>(
            registry.resolveForAction(observation, ref, ISSUED_AT, PACKAGE, ACTIVITY, TTL),
        )
        assertEquals(
            "CROSS_SNAPSHOT",
            stale.code,
            "assign is the only place that stamps the current snapshot id",
        )
    }

    private fun registryInSnapshot(
        snapshotId: String = SNAPSHOT,
        issuedAtEpochMillis: Long = ISSUED_AT,
        foregroundPackage: String? = PACKAGE,
        foregroundActivity: String? = ACTIVITY,
    ): RefRegistry = RefRegistry().apply {
        beginSnapshot(snapshotId, issuedAtEpochMillis, foregroundPackage, foregroundActivity)
    }

    private companion object {
        const val SNAPSHOT = "snapshot-1"
        const val PACKAGE = "com.example.app"
        const val ACTIVITY = ".MainActivity"
        const val ISSUED_AT = 1_000L
        const val TTL = 20_000L
    }
}
