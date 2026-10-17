package com.sheen.rokid.phone.inference.laya

import com.laya.LayaPromptBuilder
import com.sheen.rokid.plugin.SystemOneCriterion
import com.sheen.rokid.plugin.SystemOneQuestion
import com.sheen.rokid.plugin.SystemOneQuestionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * The adapter is the one piece of the Laya path provable without the 679 MB publisher set on a device, so it is
 * pinned field by field here rather than left to the instrumentation smoke test.
 */
class LayaQuestionAdapterTest {

    @Test
    fun `a choice question becomes an ordered label to description object`() {
        val official = officialQuestion(
            SystemOneQuestion(
                SystemOneQuestionType.CHOICE,
                "此刻该不该让大模型介入？",
                listOf(
                    SystemOneCriterion("介入", "用户此刻需要帮助"),
                    SystemOneCriterion("沉默", "用户不需要帮助"),
                ),
            ),
        )

        assertEquals("choice", official["type"])
        assertEquals("此刻该不该让大模型介入？", official["instructions"])
        // Order is the contract: the decoder maps marker positions back to criteria positions.
        assertEquals(listOf("介入", "沉默"), (official["criteria"] as Map<*, *>).keys.toList())
        assertEquals("用户此刻需要帮助", (official["criteria"] as Map<*, *>)["介入"])
    }

    @Test
    fun `a criterion with no description is emitted as a null value so the publisher renders the bare label`() {
        val official = officialQuestion(
            SystemOneQuestion(SystemOneQuestionType.CHOICE, "选一个", listOf(SystemOneCriterion("继续"))),
        )

        assertNull((official["criteria"] as Map<*, *>)["继续"])
    }

    @Test
    fun `duplicate choice labels are refused rather than silently collapsing an option`() {
        val question = SystemOneQuestion(
            SystemOneQuestionType.CHOICE,
            "选一个",
            listOf(SystemOneCriterion("介入", "第一次"), SystemOneCriterion("介入", "第二次")),
        )

        assertThrows(IllegalArgumentException::class.java) { officialQuestion(question) }
    }

    @Test
    fun `a score question becomes an ordered array of level texts`() {
        // The publisher numbers the levels itself ("level 0: …"), so only the text travels.
        val official = officialQuestion(
            SystemOneQuestion(
                SystemOneQuestionType.SCORE,
                "用户有多需要帮助？",
                listOf(
                    SystemOneCriterion("0", "不需要"),
                    SystemOneCriterion("1", "有点需要"),
                    SystemOneCriterion("2", "很需要"),
                ),
            ),
        )

        assertEquals("score", official["type"])
        assertEquals(listOf("不需要", "有点需要", "很需要"), official["criteria"])
    }

    @Test
    fun `a score criterion with only a label falls back to it as the level text`() {
        val official = officialQuestion(
            SystemOneQuestion(SystemOneQuestionType.SCORE, "有多需要？", listOf(SystemOneCriterion("很需要"))),
        )

        assertEquals(listOf("很需要"), official["criteria"])
    }

    @Test
    fun `a noul question carries null criteria so the publisher supplies its own option text`() {
        // Passing criteria here would change the rendered options rather than extend them.
        val official = officialQuestion(
            SystemOneQuestion(SystemOneQuestionType.NOUL, "用户此刻需要帮助"),
        )

        assertEquals("noul", official["type"])
        assertEquals("用户此刻需要帮助", official["instructions"])
        assertNull(official["criteria"])
    }

    @Test
    fun `every form puts type first so the publisher's insertion-order contract holds`() {
        val forms = listOf(
            SystemOneQuestion(SystemOneQuestionType.NOUL, "x"),
            SystemOneQuestion(SystemOneQuestionType.CHOICE, "x", listOf(SystemOneCriterion("a"))),
            SystemOneQuestion(SystemOneQuestionType.SCORE, "x", listOf(SystemOneCriterion("a"))),
        )

        forms.forEach { question ->
            assertEquals(listOf("type", "instructions", "criteria"), officialQuestion(question).keys.toList())
        }
    }

    @Test
    fun `the adapter result survives the publisher's own normalize unchanged`() {
        // `normalize` is the publisher's gate into the prompt builder; running the adapter's output through it must
        // not rewrite the schema, which is what proves the adapter emits the already-normal form.
        val official = officialQuestion(
            SystemOneQuestion(
                SystemOneQuestionType.CHOICE,
                "该不该介入？",
                listOf(SystemOneCriterion("介入", "需要帮助"), SystemOneCriterion("沉默", null)),
            ),
        )

        val normalized = LayaPromptBuilder.normalize(official)

        assertEquals("choice", normalized.type)
        assertEquals("该不该介入？", normalized.instructions)
        assertEquals(2, normalized.optionCount)
        assertEquals(listOf("介入: 需要帮助", "沉默"), LayaPromptBuilder.renderOptions(normalized))
    }

    @Test
    fun `the rendered options are one per criterion so the marker count matches`() {
        // A mismatch here is what the decoder reports as "Logits/criteria option count mismatch" at run time, far
        // from the question that caused it.
        listOf(
            SystemOneQuestion(SystemOneQuestionType.CHOICE, "x", listOf(SystemOneCriterion("a"), SystemOneCriterion("b"))),
            SystemOneQuestion(SystemOneQuestionType.SCORE, "x", listOf(SystemOneCriterion("a"), SystemOneCriterion("b"))),
            SystemOneQuestion(SystemOneQuestionType.NOUL, "x"),
        ).forEach { question ->
            val normalized = LayaPromptBuilder.normalize(officialQuestion(question))
            assertEquals(
                "option count must match the criteria count for ${question.type}",
                normalized.optionCount,
                LayaPromptBuilder.renderOptions(normalized).size,
            )
        }
    }
}
