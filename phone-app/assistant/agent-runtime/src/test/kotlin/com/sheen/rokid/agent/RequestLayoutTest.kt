package com.sheen.rokid.agent

import com.sheen.rokid.plugin.MessageRole
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RequestLayoutTest {

    private fun segment(origin: SegmentOrigin, text: String = "${origin.name.lowercase()}-text") =
        Segment(origin, trusted = true, text = text)

    @Test fun `a dynamic segment in the system slot is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            RequestLayout.validateSystem(listOf(Segment(SegmentOrigin.PERCEPTION, trusted = true, text = "x")))
        }
    }

    @Test fun `a system segment carrying a runtime placeholder is rejected`() {
        // 0.3 design §3.6 rule 4: a system plan is rendered once for the session. A `{{slot}}` that reaches
        // the system slot was never substituted, so it must fail here rather than reach the model as template syntax.
        assertFailsWith<IllegalArgumentException> {
            RequestLayout.validateSystem(
                listOf(Segment(SegmentOrigin.INSTRUCTIONS, trusted = true, text = "当前时间：{{time}}")),
            )
        }
    }

    @Test fun `every origin except the three stable ones is rejected from the system slot`() {
        val allowed = setOf(
            SegmentOrigin.INSTRUCTIONS,
            SegmentOrigin.SKILL,
            SegmentOrigin.MEMORY_POLICY,
        )
        val rejected = SegmentOrigin.values().filterNot { it in allowed }
        for (origin in rejected) {
            assertFailsWith<IllegalArgumentException>("$origin must not enter the system slot") {
                RequestLayout.validateSystem(listOf(segment(origin)))
            }
        }
        assertTrue(SegmentOrigin.SKILL_BODY in rejected, "the skill body is dynamic, not system-stable")
    }

    @Test fun `the three stable origins are accepted in the system slot`() {
        RequestLayout.validateSystem(
            listOf(
                segment(SegmentOrigin.INSTRUCTIONS),
                segment(SegmentOrigin.SKILL),
                segment(SegmentOrigin.MEMORY_POLICY),
            ),
        )
    }

    @Test fun `skill body is a dynamic tail segment before user input`() {
        val flow = listOf(
            Segment(SegmentOrigin.TRANSCRIPT, trusted = true, text = "u", role = SegmentRole.USER),
            Segment(SegmentOrigin.SKILL_BODY, trusted = true, text = "body"),
            Segment(SegmentOrigin.USER_INPUT, trusted = true, text = "now"),
        )
        assertEquals(listOf(SegmentOrigin.SKILL_BODY), RequestLayout.dynamicTail(flow).map { it.origin })
    }

    @Test fun `a skill body after user input is not part of the dynamic tail`() {
        val flow = listOf(
            Segment(SegmentOrigin.USER_INPUT, trusted = true, text = "now"),
            Segment(SegmentOrigin.SKILL_BODY, trusted = true, text = "late body"),
        )
        assertEquals(emptyList(), RequestLayout.dynamicTail(flow))
    }

    @Test fun `the dynamic tail preserves flow order and excludes the other origins`() {
        val flow = listOf(
            Segment(SegmentOrigin.PERCEPTION, trusted = false, text = "perception"),
            Segment(SegmentOrigin.TRANSCRIPT, trusted = true, text = "u", role = SegmentRole.USER),
            Segment(SegmentOrigin.SKILL_BODY, trusted = true, text = "body"),
            Segment(SegmentOrigin.MEMORY_EVIDENCE, trusted = false, text = "memory"),
            Segment(SegmentOrigin.RUNTIME_STATE, trusted = false, text = "state"),
            Segment(SegmentOrigin.RETRY, trusted = true, text = "retry"),
            Segment(SegmentOrigin.USER_INPUT, trusted = true, text = "now"),
        )
        assertEquals(
            listOf(
                SegmentOrigin.PERCEPTION,
                SegmentOrigin.SKILL_BODY,
                SegmentOrigin.MEMORY_EVIDENCE,
                SegmentOrigin.RUNTIME_STATE,
                SegmentOrigin.RETRY,
            ),
            RequestLayout.dynamicTail(flow).map { it.origin },
        )
    }

    @Test fun `a flow without user input has no dynamic tail`() {
        assertEquals(emptyList(), RequestLayout.dynamicTail(listOf(segment(SegmentOrigin.PERCEPTION))))
    }

    @Test fun `prefix hash delegates to PrefixHash`() {
        val tools = listOf(ToolSpec("tool", "description", "{}"))
        val history = listOf(ModelHistoryItem.Text(MessageRole.USER, "hi"))

        assertEquals(
            PrefixHash.of("sys", tools, history),
            RequestLayout.prefixHash("sys", tools, history),
        )
    }
}
