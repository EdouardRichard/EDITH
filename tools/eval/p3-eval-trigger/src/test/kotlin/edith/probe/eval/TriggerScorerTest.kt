package edith.probe.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P3.4 §6.3: the trigger metrics - precision and recall per scenario, and the chat mis-trigger window.
 *
 * The scoring rules are the whole point, so they are pinned before the harness exists: what a *missed* positive costs
 * (recall only), what a *triggered* negative costs (precision, against its own scenario), what an unobserved label
 * means (not triggered - never "assume it worked"), and the one case that would otherwise flatter the system (a
 * scenario with no positive predictions at all).
 *
 * Assertion messages come last (`kotlin.test`).
 */
class TriggerScorerTest {

    private fun label(id: String, scenario: String, label: String, text: String = "句子") =
        EvalLabel(id = id, scenario = scenario, label = label, text = text)

    private fun observed(vararg pairs: Pair<String, Boolean>) =
        pairs.map { Observed(it.first, it.second) }

    @Test fun `a perfect run scores one and one`() {
        val labels = listOf(
            label("K-P-01", "knowledge", "positive"),
            label("K-N-01", "knowledge", "negative"),
        )

        val score = score(labels, observed("K-P-01" to true, "K-N-01" to false))

        assertEquals(1.0, score.precision, 1e-9)
        assertEquals(1.0, score.recall, 1e-9)
        assertEquals(1, score.perScenario.getValue("knowledge").truePositives)
    }

    @Test fun `a missed positive costs recall and not precision`() {
        val labels = listOf(
            label("K-P-01", "knowledge", "positive"),
            label("K-P-02", "knowledge", "positive"),
        )

        val score = score(labels, observed("K-P-01" to true, "K-P-02" to false))

        assertEquals(1.0, score.precision, 1e-9, "没有误触发就不该扣 precision")
        assertEquals(0.5, score.recall, 1e-9)
    }

    @Test fun `a triggered negative costs precision against its own scenario`() {
        val labels = listOf(
            label("K-P-01", "knowledge", "positive"),
            label("K-N-01", "knowledge", "negative"),
            label("D-P-01", "dilemma", "positive"),
        )

        val score = score(labels, observed("K-P-01" to true, "K-N-01" to true, "D-P-01" to true))

        assertEquals(0.5, score.perScenario.getValue("knowledge").precision, 1e-9)
        assertEquals(1.0, score.perScenario.getValue("dilemma").precision, 1e-9, "误触发不得跨场景传染")
        assertEquals(1.0, score.perScenario.getValue("dilemma").recall, 1e-9)
    }

    @Test fun `a scenario that never predicts anything has precision one and recall zero`() {
        // The vacuous case, pinned on purpose: no positive prediction means no false positive, and reporting that as a
        // pass is exactly how a system that never fires would look perfect. Recall zero is what says it fired at nothing.
        val labels = listOf(
            label("PR-P-01", "privacy", "positive"),
            label("PR-P-02", "privacy", "positive"),
        )

        val score = score(labels, observed("PR-P-01" to false, "PR-P-02" to false))

        assertEquals(1.0, score.perScenario.getValue("privacy").precision, 1e-9)
        assertEquals(0.0, score.perScenario.getValue("privacy").recall, 1e-9)
    }

    @Test fun `an unobserved label counts as not triggered`() {
        val labels = listOf(label("K-P-01", "knowledge", "positive"))

        val score = score(labels, emptyList())

        assertEquals(0.0, score.recall, 1e-9, "没观测到就是没触发，不得算成功")
    }

    @Test fun `an observation for an id that is not in the corpus is ignored`() {
        val labels = listOf(label("K-P-01", "knowledge", "positive"))

        val score = score(labels, observed("K-P-01" to true, "NOPE-99" to true))

        assertEquals(1.0, score.precision, 1e-9)
        assertEquals(1, score.perScenario.getValue("knowledge").truePositives)
    }

    @Test fun `the chat window is counted per ten minutes and only over chat negatives`() {
        val labels = listOf(
            label("C-N-01", "chat", "negative"),
            label("C-N-02", "chat", "negative"),
            label("K-N-01", "knowledge", "negative"),
        )

        val score = score(
            labels,
            observed("C-N-01" to true, "C-N-02" to true, "K-N-01" to true),
            windowMinutes = 20.0,
        )

        assertEquals(2, score.chatTriggers, "只数 chat 类")
        assertEquals(1.0, score.chatTriggersPerTenMinutes, 1e-9, "20 分钟 2 次 = 每 10 分钟 1 次")
    }

    @Test fun `the overall figures are micro-averaged over the scenarios`() {
        val labels = listOf(
            label("K-P-01", "knowledge", "positive"),
            label("K-N-01", "knowledge", "negative"),
            label("D-P-01", "dilemma", "positive"),
            label("D-N-01", "dilemma", "negative"),
        )

        val score = score(labels, observed("K-P-01" to true, "K-N-01" to false, "D-P-01" to false, "D-N-01" to true))

        assertEquals(0.5, score.precision, 1e-9)
        assertEquals(0.5, score.recall, 1e-9)
    }

    @Test fun `the corpus parser reads the table rows and ignores everything else`() {
        val markdown = """
            # 语料

            | id | scenario | label | text | note |
            |---|---|---|---|---|
            | K-P-01 | knowledge | positive | 冰箱里还有什么可以做的？ | 该提示 |
            | K-N-01 | knowledge | negative | 哈哈哈哈 | 闲聊 |

            计数表：knowledge 1 / 1
        """.trimIndent()

        val labels = parseLabels(markdown)

        assertEquals(2, labels.size)
        assertEquals(EvalLabel("K-P-01", "knowledge", "positive", "冰箱里还有什么可以做的？"), labels.first())
        assertEquals("negative", labels[1].label)
        assertTrue(labels.none { it.id == "id" }, "表头不得被当成数据")
    }

    /**
     * W2/F19: the report must say out loud that its trigger count is a **lower bound**.
     *
     * The device's `request=` count is what the campaign could *record*: a row whose window happens to contain no
     * decision line records nothing, and any `skip reason=DEDUP` line proves an earlier hint that was never recorded
     * (`noteDedup` sits next to `noteRequest` in the success branch). Printing a bare "recall" off that number is how
     * P3.4's 0% came to look like a measurement instead of a floor.
     */
    @Test fun `the report always states that the recorded count is a lower bound`() {
        val notice = lowerBoundNotice()

        assertTrue(notice.contains("LOWER BOUND"), "the floor must be named: $notice")
        assertTrue(notice.contains("DEDUP"), "the proof of an unrecorded hint must be named: $notice")
    }
}
