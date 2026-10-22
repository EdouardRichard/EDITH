package com.sheen.rokid.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The `MemoryRetrieval` boundary's public contract (spec §6.1).
 *
 * These two tests lived in `ContextPlannerTest` and were deleted together with `ContextPlanner` in 0.25 Task 4. They
 * test the boundary itself, not the planner, so the deletion lost coverage that had nothing to do with the removed
 * dead code (review minor 3 / ruling R17). `FakeMemoryRetrieval` is the test double the second test pins, so it stays
 * in use rather than becoming an unused fixture.
 */
class MemoryRetrievalContractTest {

    @Test
    fun `MemoryRetrieval is one method that returns context parts`() {
        val declared = MemoryRetrieval::class.java.declaredMethods
            .filter { it.declaringClass == MemoryRetrieval::class.java }

        assertEquals(listOf("retrieve"), declared.map { it.name })
        val retrieve = declared.single()
        assertEquals(listOf(String::class.java, Int::class.javaPrimitiveType), retrieve.parameterTypes.toList())
        assertEquals(List::class.java, retrieve.returnType)
        assertTrue(
            retrieve.modifiers and java.lang.reflect.Modifier.ABSTRACT != 0,
            "retrieve must stay abstract: P3.1 ships the boundary, not an implementation",
        )
    }

    @Test
    fun `the retrieval boundary can be implemented and hands back segments`() {
        val parts = listOf(
            Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "喜欢喝茶"),
            Segment(SegmentOrigin.MEMORY_EVIDENCE, false, "上午工作"),
        )
        val retrieval: MemoryRetrieval = FakeMemoryRetrieval(parts)

        assertEquals(parts, retrieval.retrieve("preferences", 5))
        assertEquals(parts.take(1), retrieval.retrieve("preferences", 1))
    }
}
