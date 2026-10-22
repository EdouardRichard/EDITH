package com.sheen.rokid.phone.plugin.smarthelper.prompt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The strict renderer and the plan hash (spec §4.1, task 3).
 *
 * The renderer is deliberately not a template engine: it only substitutes `{{name}}` tokens whose
 * names are both allowed and supplied. Everything else — an unresolved opener, a slot that is not
 * allowed, a value whose slot is not allowed — is an error rather than a silently preserved token,
 * because an unrendered placeholder in a system prompt is a prompt-injection surface.
 */
class PromptRendererTest {

    @Test
    fun `render replaces every allowed slot exactly once`() {
        val out = PromptRenderer.render(
            "model={{model}} time={{time}}",
            mapOf("model" to "K40", "time" to "18:23"),
            setOf("model", "time"),
        )
        assertEquals("model=K40 time=18:23", out)
    }

    @Test
    fun `render preserves the template around slots exactly`() {
        val out = PromptRenderer.render(
            "before\n  [{{x}}]  \nafter",
            mapOf("x" to "值"),
            setOf("x"),
        )
        assertEquals("before\n  [值]  \nafter", out)
    }

    @Test
    fun `render rejects a placeholder with no supplied value`() {
        assertFailsWith<IllegalArgumentException> {
            PromptRenderer.render("{{model}}", emptyMap(), setOf("model"))
        }
    }

    @Test
    fun `render rejects a placeholder outside the allowed set`() {
        assertFailsWith<IllegalArgumentException> {
            PromptRenderer.render("{{missing}}", emptyMap(), setOf("model"))
        }
    }

    @Test
    fun `render rejects a supplied value that is not an allowed slot`() {
        assertFailsWith<IllegalArgumentException> {
            PromptRenderer.render("x", mapOf("extra" to "v"), setOf("model"))
        }
    }

    @Test
    fun `render rejects an unresolved placeholder opener or closer`() {
        assertFailsWith<IllegalArgumentException> {
            PromptRenderer.render("{{model", mapOf("model" to "K40"), setOf("model"))
        }
        assertFailsWith<IllegalArgumentException> {
            PromptRenderer.render("model}}", mapOf("model" to "K40"), setOf("model"))
        }
        assertFailsWith<IllegalArgumentException> {
            PromptRenderer.render("{{ model }}", mapOf("model" to "K40"), setOf("model"))
        }
    }

    @Test
    fun `render strips control characters and caps values`() {
        val out = PromptRenderer.render("{{x}}", mapOf("x" to "#".repeat(600) + "\n"), setOf("x"))
        assertEquals(500, out.length)
        assertFalse('\n' in out)
    }

    @Test
    fun `render strips control characters before applying the cap`() {
        val out = PromptRenderer.render("{{x}}", mapOf("x" to "\u0000a\nb\tc\u0007"), setOf("x"))
        assertEquals("abc", out)
    }

    @Test
    fun `render accepts an explicit code point cap`() {
        val out = PromptRenderer.render(
            "{{x}}",
            mapOf("x" to "#".repeat(1_200)),
            setOf("x"),
            maxValueCodePoints = 1_000,
        )

        assertEquals(1_000, out.length)
    }

    @Test
    fun `the cap boundary is exact and a negative cap is rejected`() {
        fun renderCap(value: String) = PromptRenderer.render(
            "{{x}}",
            mapOf("x" to value),
            setOf("x"),
            maxValueCodePoints = 600,
        )

        assertEquals("#".repeat(599), renderCap("#".repeat(599)))
        assertEquals("#".repeat(600), renderCap("#".repeat(600)), "a value exactly at the cap is not cut")
        assertEquals("#".repeat(600), renderCap("#".repeat(601)), "one code point over is cut to the cap exactly")
        assertFailsWith<IllegalArgumentException> {
            PromptRenderer.render("{{x}}", mapOf("x" to "v"), setOf("x"), maxValueCodePoints = -1)
        }
    }

    @Test
    fun `the cap override never splits a surrogate pair`() {
        val out = PromptRenderer.render(
            "{{x}}",
            mapOf("x" to "\uD83D\uDE00".repeat(600)),
            setOf("x"),
            maxValueCodePoints = 501,
        )

        assertEquals(501, out.codePointCount(0, out.length), "an odd cap must still end on a whole code point")
        assertEquals(1_002, out.length, "the cap counts code points, not UTF-16 units")
    }

    @Test
    fun `plan hash is stable for identical sections and changes with text`() {
        val first = listOf(PromptSection("a", PromptKind.STATIC, "A"))
        val second = listOf(PromptSection("a", PromptKind.STATIC, "A"))
        val changed = listOf(PromptSection("a", PromptKind.STATIC, "B"))

        val plan = PromptPlan("0.3.0", first, "A", PromptPlan.hashOf(first))
        val twin = PromptPlan("0.3.0", second, "A", PromptPlan.hashOf(second))

        assertEquals(plan.hash, twin.hash)
        assertNotEquals(plan.hash, PromptPlan.hashOf(changed))
        assertEquals(64, plan.hash.length)
        assertTrue(plan.hash.all { it in "0123456789abcdef" }, "hash must be lowercase hex")
    }

    @Test
    fun `plan hash is the sha-256 of the newline joined section texts`() {
        val sections = listOf(
            PromptSection("a", PromptKind.STATIC, "A"),
            PromptSection("b", PromptKind.SESSION_STATIC, "B"),
        )

        assertEquals(
            "23519a43c66b4c342f25b32e09797ec5f3fc0be388cd8243fb3449afbdce4013",
            PromptPlan.hashOf(sections),
        )
    }

    @Test
    fun `plan hash depends on section order`() {
        val forward = listOf(
            PromptSection("a", PromptKind.STATIC, "A"),
            PromptSection("b", PromptKind.STATIC, "B"),
        )
        val backward = forward.reversed()

        assertNotEquals(PromptPlan.hashOf(forward), PromptPlan.hashOf(backward))
    }
}
