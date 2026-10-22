package com.sheen.rokid.phone.plugin.smarthelper.memory

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The device probe's own rule: a retrieval reports **which ranking ran** and **which interval** the best match fell in
 * - never the score, and never the memory.
 *
 * Why this exists (B5, 2026-10-14): the JVM proved the semantic order with a fake embedding, but on the device
 * "ranked by meaning" and "ranked by characters" produce the same `P3_MEMORY inject=` line, because that line carried
 * no ranking fact at all. The owner's constraint for such a probe is "只记分数区间", so the observable is an interval
 * label. The reference points are the K40's own embedding measurements: a paraphrase scored 0.8586 and an unrelated
 * sentence 0.3701, and those two must land in different buckets.
 */
class MemoryRankingProbeTest {

    @Test
    fun `a similarity becomes an interval label, and a missing one is named rather than zero`() {
        assertEquals("0.9-1.0", similarityBucket(0.93))
        assertEquals("0.8-0.9", similarityBucket(0.8586))
        assertEquals("0.3-0.4", similarityBucket(0.3701))
        assertEquals("0.0-0.1", similarityBucket(0.0))
        assertEquals("<0.0", similarityBucket(-0.2))
        assertEquals("none", similarityBucket(null))
    }

    @Test
    fun `the bucket edges are half-open so one similarity has exactly one label`() {
        assertEquals("0.4-0.5", similarityBucket(0.4))
        assertEquals("0.3-0.4", similarityBucket(0.3999))
        assertEquals("0.9-1.0", similarityBucket(1.0))
    }
}
