package com.sheen.rokid.phone.plugin.smarthelper.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Spec §3.2/§3.3: a `.md` file whose YAML front matter is exactly the six declared fields. */
class SkillParserTest {

    private val conforming = """
        ---
        name: reply-wechat-message
        version: 1.0.0
        description: 帮用户在微信中回复指定联系人的消息
        triggers: [回复微信, 回微信消息]
        tags: [社交, 微信]
        priority: high
        enabled: true
        ---

        # 回复微信消息

        ## 任务目标
        在微信中找到指定联系人，发送用户指定的消息内容。
    """.trimIndent()

    private fun ok(text: String): Skill = assertIs<SkillParse.Ok>(SkillParser.parse("f.md", text)).skill
    private fun invalid(text: String): SkillRejection =
        assertIs<SkillParse.Invalid>(SkillParser.parse("f.md", text)).reason

    @Test
    fun `a conforming document parses into its fields and its body`() {
        val skill = ok(conforming)

        assertEquals("reply-wechat-message", skill.name)
        assertEquals("1.0.0", skill.version)
        assertEquals("帮用户在微信中回复指定联系人的消息", skill.description)
        assertEquals(listOf("回复微信", "回微信消息"), skill.triggers)
        assertEquals(listOf("社交", "微信"), skill.tags)
        assertEquals(SkillPriority.HIGH, skill.priority)
        assertTrue(skill.enabled)
        // The body is everything after the front matter, and it keeps its Markdown headings verbatim.
        assertTrue(skill.body.startsWith("# 回复微信消息"))
        assertTrue(skill.body.contains("## 任务目标"))
        assertTrue("---" !in skill.body, "the front matter delimiter must not survive into the body")
    }

    @Test
    fun `a document with no front matter is rejected rather than guessed at`() {
        assertEquals(SkillRejection.NO_FRONT_MATTER, invalid("# just a document\n"))
    }

    @Test
    fun `an unterminated front matter block is malformed`() {
        assertEquals(SkillRejection.MALFORMED_FRONT_MATTER, invalid("---\nname: x\nversion: 1.0.0\n"))
    }

    @Test
    fun `each required field is required`() {
        assertEquals(
            SkillRejection.MISSING_REQUIRED_FIELD,
            invalid("---\nversion: 1.0.0\ndescription: d\n---\nbody\n"),
        )
        assertEquals(
            SkillRejection.MISSING_REQUIRED_FIELD,
            invalid("---\nname: a\nversion: 1.0.0\n---\nbody\n"),
        )
        assertEquals(
            SkillRejection.MISSING_REQUIRED_FIELD,
            invalid("---\nname: a\ndescription: d\n---\nbody\n"),
        )
    }

    @Test
    fun `an empty body is rejected because there is nothing to inject`() {
        assertEquals(
            SkillRejection.BLANK_BODY,
            invalid("---\nname: a\nversion: 1.0.0\ndescription: d\n---\n   \n"),
        )
    }

    @Test
    fun `enabled defaults to true and priority to normal`() {
        val skill = ok("---\nname: a\nversion: 1.0.0\ndescription: d\n---\nbody\n")

        assertTrue(skill.enabled)
        assertEquals(SkillPriority.NORMAL, skill.priority)
        assertEquals(emptyList(), skill.triggers)
        assertEquals(emptyList(), skill.tags)
    }

    @Test
    fun `an explicit false disables a skill`() {
        assertEquals(false, ok("---\nname: a\nversion: 1.0.0\ndescription: d\nenabled: false\n---\nbody\n").enabled)
    }

    @Test
    fun `an unknown priority is refused rather than defaulted`() {
        assertEquals(
            SkillRejection.MALFORMED_FRONT_MATTER,
            invalid("---\nname: a\nversion: 1.0.0\ndescription: d\npriority: urgent\n---\nbody\n"),
        )
    }

    @Test
    fun `a crlf document parses the same as an lf one`() {
        val lf = ok(conforming)
        val crlf = ok(conforming.replace("\n", "\r\n"))

        assertEquals(lf.name, crlf.name)
        assertEquals(lf.triggers, crlf.triggers)
        assertTrue(!crlf.body.contains("\r"), "carriage returns must not reach the injected body")
    }

    @Test
    fun `a block list is parsed as well as an inline one`() {
        val skill = ok(
            "---\nname: a\nversion: 1.0.0\ndescription: d\ntriggers:\n  - 一\n  - 二\n---\nbody\n",
        )

        assertEquals(listOf("一", "二"), skill.triggers)
    }

    @Test
    fun `bytes that were not valid utf8 are rejected rather than injected as mojibake`() {
        // `StorageAccessServiceImpl.readFile` decodes with a replacing decoder, so invalid bytes arrive here as
        // U+FFFD. Injecting that into the prompt would put a garbled SKILL in front of the model with no signal.
        assertEquals(SkillRejection.INVALID_ENCODING, invalid("---\nname: a\nversion: 1.0.0\ndescription: \uFFFD\n---\nbody\n"))
    }

    @Test
    fun `an unknown field does not fail the parse`() {
        // Forward compatibility: a SKILL written for a later version carries fields this one does not know.
        val skill = ok("---\nname: a\nversion: 1.0.0\ndescription: d\nauthor: someone\n---\nbody\n")

        assertEquals("a", skill.name)
    }

    @Test
    fun `the file name is not the identity`() {
        // `name` is the identity (spec §3.3); the file could be called anything, and a duplicate `name` across two
        // files is the manager's problem, not the parser's.
        val skill = ok(conforming)
        assertEquals("reply-wechat-message", skill.name)
    }

    // --- T2.5: applies_when / not_applies_when front matter fields (spec §3.8.3) ---

    @Test
    fun `applies_when block list parses correctly`() {
        val skill = ok(
            "---\nname: a\nversion: 1.0.0\ndescription: d\napplies_when:\n  - app_installed:com.miui.player\n  - channel_available:shizuku\n---\nbody\n",
        )

        assertEquals(listOf("app_installed:com.miui.player", "channel_available:shizuku"), skill.appliesWhen)
    }

    @Test
    fun `not_applies_when block list parses correctly`() {
        val skill = ok(
            "---\nname: a\nversion: 1.0.0\ndescription: d\nnot_applies_when:\n  - app_installed:com.example.bloat\n---\nbody\n",
        )

        assertEquals(listOf("app_installed:com.example.bloat"), skill.notAppliesWhen)
    }

    @Test
    fun `missing applies_when and not_applies_when default to empty lists`() {
        // Backward compatibility: SKILLs written before §3.8.3 must still parse.
        val skill = ok("---\nname: a\nversion: 1.0.0\ndescription: d\n---\nbody\n")

        assertEquals(emptyList(), skill.appliesWhen)
        assertEquals(emptyList(), skill.notAppliesWhen)
    }

    @Test
    fun `applies_when and not_applies_when inline bracket syntax works`() {
        val skill = ok(
            "---\nname: a\nversion: 1.0.0\ndescription: d\napplies_when: [app_installed:com.foo, channel_available:bar]\nnot_applies_when: [app_installed:com.baz]\n---\nbody\n",
        )

        assertEquals(listOf("app_installed:com.foo", "channel_available:bar"), skill.appliesWhen)
        assertEquals(listOf("app_installed:com.baz"), skill.notAppliesWhen)
    }
}
