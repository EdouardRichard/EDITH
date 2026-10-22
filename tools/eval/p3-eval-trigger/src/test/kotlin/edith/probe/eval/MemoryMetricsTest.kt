package edith.probe.eval

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * P3.4 §6.3's two memory metrics: how much of what the extractor wrote is worth keeping, and how often the memory a
 * query should find is actually in the top-k the turn would inject.
 *
 * Both are deliberately un-forgiving about emptiness: a run that wrote nothing gets precision `0.0`, not the
 * conventional `1.0` for an empty set, because "we stored nothing" is not evidence of storing the right things - it is
 * the absence of evidence, and the phase must not be able to quote a perfect score for a feature that never ran.
 *
 * Assertion messages come last (`kotlin.test`).
 */
class MemoryMetricsTest {

    @Test fun `extraction precision counts only what is worth keeping`() {
        val written = listOf("我的爱好是喝茶", "我不吃香菜", "今天天气不错")
        val keep = setOf("我的爱好是喝茶", "我不吃香菜")

        assertEquals(2.0 / 3.0, extractionPrecision(written, keep), 1e-9)
    }

    @Test fun `a run that wrote nothing has no precision to report`() {
        assertEquals(0.0, extractionPrecision(emptyList(), setOf("我的爱好是喝茶")), 1e-9)
    }

    @Test fun `every written memory being worth keeping scores one`() {
        assertEquals(1.0, extractionPrecision(listOf("我的爱好是喝茶"), setOf("我的爱好是喝茶")), 1e-9)
    }

    @Test fun `a query hits when its memory is inside the top k`() {
        val queries = listOf(
            QueryOutcome("我想喝点什么", expectedId = "m-tea", rankedIds = listOf("m-other", "m-tea")),
            QueryOutcome("我住哪儿", expectedId = "m-home", rankedIds = listOf("m-home")),
        )

        assertEquals(1.0, retrievalHitRate(queries, k = 2), 1e-9)
    }

    @Test fun `the top-k bound is real`() {
        val queries = listOf(
            QueryOutcome("我想喝点什么", expectedId = "m-tea", rankedIds = listOf("a", "b", "c", "d", "e", "m-tea")),
        )

        assertEquals(0.0, retrievalHitRate(queries, k = 5), 1e-9, "第 6 名不算命中")
        assertEquals(1.0, retrievalHitRate(queries, k = 6), 1e-9)
    }

    @Test fun `a memory that was not retrieved at all is a miss`() {
        val queries = listOf(QueryOutcome("我想喝点什么", expectedId = "m-tea", rankedIds = emptyList()))

        assertEquals(0.0, retrievalHitRate(queries, k = 5), 1e-9)
    }

    @Test fun `no queries has a zero hit rate rather than a perfect one`() {
        assertEquals(0.0, retrievalHitRate(emptyList(), k = 5), 1e-9)
    }
}
