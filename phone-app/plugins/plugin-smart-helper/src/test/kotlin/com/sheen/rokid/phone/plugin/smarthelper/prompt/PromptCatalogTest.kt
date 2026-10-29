package com.sheen.rokid.phone.plugin.smarthelper.prompt

import com.sheen.rokid.phone.plugin.smarthelper.proactive.INTERVENTION_PROPOSITION
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The prompt catalog is the single place that selects, orders and versions the system prompt
 * (spec §4.1, §4.6; Phase 3 §5.1 splits the selection into [PromptRoute]).
 *
 * These tests pin the contract the rest of the harness builds on:
 * - the manifest is the catalog: every entry loads and every entry has a legal kind;
 * - a system plan never contains an unresolved slot or a DYNAMIC section;
 * - the section order is core 00→50, the route posture, then the rendered device profile;
 * - the dynamic runtime template stays out of `systemText`;
 * - the same route + profile is deterministic (same text, same hash);
 * - each of the three routes selects exactly its own posture, and the manifest must list all three;
 * - the loop/pinned/summarizer resources load through their accessors and never enter a system plan;
 * - `core/20-tools.md` describes exactly the 24-tool default face (0.3 Phase 4A).
 */
class PromptCatalogTest {

    private val coreIds = listOf(
        "core.identity",
        "core.operating",
        "core.tools",
        "core.safety",
        "core.output",
        "core.memory-policy",
    )

    @Test
    fun `every manifest entry loads and every system plan has no slots`() {
        val plan = PromptCatalog.plan(PromptRoute.WAKE, profileFixture())

        assertTrue(plan.systemText.isNotBlank())
        assertFalse("{{" in plan.systemText, "a rendered system prompt must not keep any slot syntax")
        assertFalse("}}" in plan.systemText)
        assertEquals(PromptCatalog.VERSION, plan.version)

        for (entry in PromptCatalog.manifest()) {
            assertTrue(
                promptText(entry.file).isNotBlank(),
                "manifest entry ${entry.id} must load",
            )
        }
    }

    @Test
    fun `the prompt never teaches the pre-envelope failed or refused result prefixes`() {
        // 0.3 Phase 2.2: the model-facing tool result is the `{execution, observation|null, data}` envelope. A prompt
        // that still shows the pre-0.3 `Failed: …`/`Refused: …` string would teach the model a shape the runtime no
        // longer produces, so the authored resources are checked as a whole (including the unselected follow-up route).
        val corpus = buildString {
            PromptCatalog.manifest().forEach { appendLine(promptText(it.file)) }
            appendLine(PromptCatalog.dynamicTemplate())
            appendLine(PromptCatalog.perceptionTemplate())
        }

        assertFalse("Failed: " in corpus, "the prompt must not teach the old Failed prefix")
        assertFalse("Refused: " in corpus, "the prompt must not teach the old Refused prefix")
    }

    @Test
    fun `the same profile and route produce the same hash twice`() {
        assertEquals(
            PromptCatalog.plan(PromptRoute.WAKE, profileFixture()).hash,
            PromptCatalog.plan(PromptRoute.WAKE, profileFixture()).hash,
        )
    }

    @Test
    fun `the manifest lists each prompt resource exactly once with a legal kind`() {
        val entries = PromptCatalog.manifest()

        assertEquals(entries.size, entries.map { it.id }.toSet().size, "ids must be unique")
        assertEquals(entries.size, entries.map { it.file }.toSet().size, "files must be unique")
        assertTrue(entries.all { it.version.isNotBlank() }, "every entry needs a revision")
        assertTrue(entries.all { it.description.isNotBlank() }, "every entry needs a description")

        val kindOf = entries.associate { it.file to it.kind }
        listOf(
            "core/00-identity.md",
            "core/10-operating.md",
            "core/20-tools.md",
            "core/30-safety.md",
            "core/40-output.md",
            "core/50-memory-policy.md",
        ).forEach { assertEquals(PromptKind.STATIC, kindOf[it], "$it must be STATIC") }
        assertEquals(PromptKind.STATIC, kindOf["route/wake.md"])
        assertEquals(PromptKind.STATIC, kindOf["route/proactive-hint.md"])
        assertEquals(PromptKind.STATIC, kindOf["route/proactive-followup.md"])
        assertEquals(PromptKind.SESSION_STATIC, kindOf["context/device-profile.md"])
        assertEquals(PromptKind.DYNAMIC, kindOf["context/runtime-state.md"])
        assertEquals(PromptKind.STATIC, kindOf["phone-only/overlay.md"], "the phone-only note is a fixed section")
        listOf("loop/retry.md", "loop/plan-nudge.md", "loop/timeout.md", "summarizer.md").forEach {
            assertEquals(PromptKind.STATIC, kindOf[it], "$it must be a static fragment")
        }
        assertEquals(PromptKind.PINNED, kindOf["pinned/proactive-gate.md"], "the gate question is recalibrated, not edited")
        assertEquals(
            PromptKind.PINNED,
            kindOf["pinned/proactive-gate-options.md"],
            "the gate options are frozen with the question they belong to",
        )
        assertEquals(18, entries.size, "the 0.5.1 catalog has exactly eighteen resources")
    }

    @Test
    fun `the loop pinned and summarizer accessors load their authored resources`() {
        assertEquals(promptText("loop/retry.md"), PromptCatalog.loop("retry"))
        assertEquals(promptText("loop/plan-nudge.md"), PromptCatalog.loop("plan-nudge"))
        assertEquals(promptText("loop/timeout.md"), PromptCatalog.loop("timeout"))
        assertEquals(promptText("pinned/proactive-gate.md"), PromptCatalog.pinned("proactive-gate"))
        assertEquals(
            promptText("pinned/proactive-gate-options.md"),
            PromptCatalog.pinned("proactive-gate-options"),
        )
        assertEquals(promptText("summarizer.md"), PromptCatalog.summarizer())
    }

    @Test
    fun `the pinned proactive gate is the production fine tune question verbatim`() {
        // The fine-tune spec (§3.3) pins one exact string; a paraphrase would make the deployed gate a question
        // the model was never calibrated on. Compare both the production constant and the literal so the resource
        // cannot drift from either source.
        val pinned = PromptCatalog.pinned("proactive-gate")

        assertEquals(INTERVENTION_PROPOSITION, pinned)
        assertEquals("此刻是否存在一个需要大模型介入的缺口", pinned)
    }

    @Test
    fun `the system plan never includes a pinned resource`() {
        val pinned = listOf(
            PromptCatalog.pinned("proactive-gate"),
            PromptCatalog.pinned("proactive-gate-options"),
        )
        val plan = PromptCatalog.plan(PromptRoute.WAKE, profileFixture())

        for (file in listOf("pinned/proactive-gate.md", "pinned/proactive-gate-options.md")) {
            assertTrue(
                PromptCatalog.manifest().any { it.file == file && it.kind == PromptKind.PINNED },
                "the pinned resource '$file' must be in the manifest for this test to prove its exclusion",
            )
        }
        assertFalse(plan.sections.any { it.kind == PromptKind.PINNED }, "PINNED content is not a system section")
        for (text in pinned) {
            assertFalse(text in plan.systemText, "the gate question/options belong to its own call, not the system prompt")
        }
    }

    @Test
    fun `the tools prompt names the twenty three tool face and no route only tool`() {
        val tools = promptText("core/20-tools.md")

        DEFAULT_TOOL_NAMES.forEach { name ->
            assertTrue(mentions(tools, name), "core/20-tools.md must name the advertised tool '$name'")
        }
        assertFalse(mentions(tools, "propose_hint"), "propose_hint is advertised only on the hint turn, not in core")
    }

    @Test
    fun `the manifest rejects an unknown kind`() {
        assertFailsWith<IllegalArgumentException> {
            PromptCatalog.parseManifest("x\tcore/00-identity.md\tNOT_A_KIND\t1\tdescription") { "text" }
        }
    }

    @Test
    fun `the manifest rejects a resource that does not load`() {
        assertFailsWith<IllegalArgumentException> {
            PromptCatalog.parseManifest("x\tcore/00-identity.md\tSTATIC\t1\tdescription") {
                throw IllegalArgumentException("missing prompt resource")
            }
        }
    }

    @Test
    fun `the manifest rejects a static section that still has a slot`() {
        assertFailsWith<IllegalArgumentException> {
            PromptCatalog.parseManifest("x\tcore/00-identity.md\tSTATIC\t1\tdescription") { "hello {{name}}" }
        }
    }

    @Test
    fun `the manifest rejects duplicate ids`() {
        val duplicate = buildString {
            append("x\tcore/00-identity.md\tSTATIC\t1\tdescription\n")
            append("x\tcore/10-operating.md\tSTATIC\t1\tdescription\n")
        }
        assertFailsWith<IllegalArgumentException> {
            PromptCatalog.parseManifest(duplicate) { "text" }
        }
    }

    @Test
    fun `the plan orders core then route then the rendered device profile`() {
        val plan = PromptCatalog.plan(PromptRoute.WAKE, profileFixture())

        assertEquals(
            listOf(
                "core.identity",
                "core.operating",
                "core.tools",
                "core.safety",
                "core.output",
                "core.memory-policy",
                "route.wake",
                "context.device-profile",
            ),
            plan.sections.map { it.id },
        )
        assertTrue(
            plan.sections.all { it.kind == PromptKind.STATIC || it.kind == PromptKind.SESSION_STATIC },
            "the system prompt may only contain STATIC or SESSION_STATIC sections",
        )
    }

    @Test
    fun `each prompt route selects only its own posture resource`() {
        val wake = PromptCatalog.plan(PromptRoute.WAKE, profileFixture())
        val hint = PromptCatalog.plan(PromptRoute.PROACTIVE_HINT, profileFixture())
        val followUp = PromptCatalog.plan(PromptRoute.PROACTIVE_FOLLOWUP, profileFixture())
        val wakePosture = promptText("route/wake.md")
        val hintPosture = promptText("route/proactive-hint.md")
        val followUpPosture = promptText("route/proactive-followup.md")

        assertTrue(wakePosture.isNotBlank())
        assertTrue(hintPosture.isNotBlank())
        assertTrue(followUpPosture.isNotBlank())
        assertTrue(wakePosture in wake.systemText)
        assertTrue(hintPosture in hint.systemText)
        assertTrue(followUpPosture in followUp.systemText)

        assertFalse(hintPosture in wake.systemText)
        assertFalse(followUpPosture in wake.systemText)
        assertFalse(wakePosture in hint.systemText)
        assertFalse(followUpPosture in hint.systemText)
        assertFalse(wakePosture in followUp.systemText)
        assertFalse(hintPosture in followUp.systemText)

        assertEquals(
            3,
            setOf(wake.hash, hint.hash, followUp.hash).size,
            "every prompt route must fingerprint its own plan",
        )
    }

    @Test
    fun `all three plans share the core sections and add only their own route posture`() {
        val expectedRouteIds = mapOf(
            PromptRoute.WAKE to "route.wake",
            PromptRoute.PROACTIVE_HINT to "route.proactive-hint",
            PromptRoute.PROACTIVE_FOLLOWUP to "route.proactive-followup",
        )
        val plans = PromptRoute.entries.associateWith { PromptCatalog.plan(it, profileFixture()) }
        val wakeCoreTexts = plans.getValue(PromptRoute.WAKE).sections.take(coreIds.size).map { it.text }

        for ((route, plan) in plans) {
            assertEquals(
                coreIds,
                plan.sections.take(coreIds.size).map { it.id },
                "$route must start with the shared core sections",
            )
            assertEquals(
                wakeCoreTexts,
                plan.sections.take(coreIds.size).map { it.text },
                "$route must not rewrite the shared core sections",
            )
            assertEquals(
                expectedRouteIds.getValue(route),
                plan.sections[coreIds.size].id,
                "$route's seventh section must be its own posture",
            )
            assertEquals(
                listOf("context.device-profile"),
                plan.sections.drop(coreIds.size + 1).map { it.id },
                "$route ends with the same rendered device profile",
            )
        }
    }

    @Test
    fun `the follow up plan carries the 用户已经接话 posture`() {
        val followUp = PromptCatalog.plan(PromptRoute.PROACTIVE_FOLLOWUP, profileFixture()).systemText
        val followUpPosture = promptText("route/proactive-followup.md")

        assertTrue(followUpPosture in followUp, "the follow-up plan must select its own route resource")
        assertTrue("用户已经接话" in followUp, "spec §8.2: the follow-up turn starts from a user reply")
    }

    @Test
    fun `validateCatalog rejects a manifest that omits any route posture`() {
        for (missing in listOf("route/wake.md", "route/proactive-hint.md", "route/proactive-followup.md")) {
            val withoutRoute = PromptCatalog.manifest().filterNot { it.file == missing }
            assertFailsWith<IllegalArgumentException>("a manifest without $missing must be rejected") {
                PromptCatalog.validateCatalog(withoutRoute)
            }
        }
    }

    @Test
    fun `the prompt route names are the P3_PROMPT labels`() {
        assertEquals(
            listOf("WAKE", "PROACTIVE_HINT", "PROACTIVE_FOLLOWUP"),
            PromptRoute.entries.map { it.name },
            "P3_PROMPT must name the three prompt routes, not the two ContextRoute values",
        )
    }

    @Test
    fun `the dynamic template carries a coverage slot`() {
        assertTrue(
            "{{coverage}}" in PromptCatalog.dynamicTemplate(),
            "spec §4.3: the dynamic block must show which sources are unavailable or truncated",
        )
    }

    @Test
    fun `a dynamic template that omits coverage is rejected`() {
        val withoutCoverage = PromptCatalog.dynamicTemplate().lines()
            .filterNot { "{{coverage}}" in it }
            .joinToString("\n")

        assertFailsWith<IllegalArgumentException> {
            PromptCatalog.parseManifest("x\tcontext/runtime-state.md\tDYNAMIC\t1\tdescription") {
                withoutCoverage
            }
        }
    }

    @Test
    fun `the perception template is the thirteen-slot request tail`() {
        val template = PromptCatalog.perceptionTemplate()

        // The slots the perception block renders (0.3 Phase 2 Task 9). Memory/skill/loop stay dedicated tail segments,
        // so the perception template must not carry them.
        listOf("coverage", "perception", "notifications", "media").forEach { slot ->
            assertTrue("{{$slot}}" in template, "the perception template must carry {{$slot}}")
        }
        listOf("memoryEvidence", "skillBodies", "loopState").forEach { slot ->
            assertFalse("{{$slot}}" in template, "the perception template must not carry {{$slot}}")
        }
        assertEquals(13, template.lines().size, "the perception template has exactly thirteen lines")
        assertEquals(13, Regex("""\{\{[A-Za-z_][A-Za-z0-9_]*\}\}""").findAll(template).count())
        assertFalse(template.trimEnd().endsWith("记忆："), "the memory header belongs to the full template, not this one")
    }

    @Test
    fun `a null profile drops the device profile section`() {
        val withProfile = PromptCatalog.plan(PromptRoute.WAKE, profileFixture())
        val withoutProfile = PromptCatalog.plan(PromptRoute.WAKE, null)

        assertTrue(withProfile.sections.any { it.kind == PromptKind.SESSION_STATIC })
        assertFalse(withoutProfile.sections.any { it.kind == PromptKind.SESSION_STATIC })
        assertNotEquals(withProfile.hash, withoutProfile.hash)
    }

    @Test
    fun `the dynamic template stays outside the system prompt`() {
        val template = PromptCatalog.dynamicTemplate()
        val systemText = PromptCatalog.plan(PromptRoute.WAKE, profileFixture()).systemText

        assertTrue("{{time}}" in template)
        assertTrue("{{deviceState}}" in template)
        assertFalse(template.trim() in systemText)
        assertFalse("{{" in systemText)
    }

    @Test
    fun `the follow up posture describes a user who replied in a normal task turn`() {
        val followUp = promptText("route/proactive-followup.md")

        assertTrue("用户已经接话" in followUp, "spec §8.2: the follow-up turn starts from a user reply")
        assertTrue("正常任务回合" in followUp, "spec §8.2: follow-up is a normal task turn")
        listOf("观察", "执行", "确认").forEach {
            assertTrue(it in followUp, "spec §8.2: the follow-up turn may $it")
        }
        assertTrue("修正之前的提醒" in followUp, "spec §8.2: revise the earlier reminder from the latest input")
        assertFalse("没有回应" in followUp, "the user did reply; silence is the wrong starting state")
        assertFalse("保持沉默" in followUp, "a follow-up is a normal task turn, not another hint decision")
    }

    private companion object {
        /** The 23-tool default face after the 2026-10-22 retirement; the same set `ToolFaceFixture` reads from production. */
        val DEFAULT_TOOL_NAMES: Set<String> = linkedSetOf(
            "read_screen", "tap", "long_press", "scroll", "input_text",
            "open_app", "open_url", "open_settings", "set_alarm", "compose_note",
            "clear_data", "uninstall", "disable_app", "set_permission", "set_brightness",
            "list_apps", "system_action",
            "device", "shell", "open", "dial",
            "notify_user", "load_skill",
        )

        /** A tool name as a whole token, so `open` is not satisfied by `open_app`. */
        fun mentions(text: String, name: String): Boolean =
            Regex("(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])").containsMatchIn(text)
    }
}
