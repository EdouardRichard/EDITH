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
 * - `core/20-tools.md` describes exactly the 25-tool default face (0.3 Phase 4A, plus 0.5.4's `deep_link`/`media`).
 * - `core/15-app-entry.md` carries the app task flow: SKILL catalog first, verified steps, fallback and no invented
 *   deep-link target (0.5.4 Task 16).
 */
class PromptCatalogTest {

    private val coreIds = listOf(
        "core.identity",
        "core.operating",
        "core.app-entry",
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
            "core/15-app-entry.md",
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
        assertEquals(19, entries.size, "the 0.5.4 catalog has exactly nineteen resources")
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
    fun `the tools prompt names the twenty five tool face and no route only tool`() {
        val tools = promptText("core/20-tools.md")

        DEFAULT_TOOL_NAMES.forEach { name ->
            assertTrue(mentions(tools, name), "core/20-tools.md must name the advertised tool '$name'")
        }
        assertFalse(mentions(tools, "propose_hint"), "propose_hint is advertised only on the hint turn, not in core")
    }

    @Test
    fun `the app entry section runs the SKILL catalog first and verifies every step`() {
        val entry = promptText("core/15-app-entry.md")

        assertTrue("SKILL 目录" in entry, "the app task starts from the injected SKILL catalog")
        assertTrue("load_skill" in entry, "a matching SKILL must be pulled before acting")
        listOf("deep_link", "media", "read_screen").forEach {
            assertTrue(it in entry, "the app entry rules must name the '$it' leg")
        }
        assertTrue("验证" in entry, "each step must be verified, not assumed")
        assertTrue("回退" in entry, "a missing entry needs a fallback order")
    }

    @Test
    fun `the app entry section forbids invented deep link targets and requires confirmation for transactions`() {
        val entry = promptText("core/15-app-entry.md")

        assertTrue("uri" in entry && "package" in entry, "deep_link's uri/package are the values that must never be invented")
        assertTrue("不要自己拼" in entry, "a URI must not be assembled from memory")
        assertTrue("猜" in entry, "a package or scheme must not be guessed")
        assertTrue(
            "交易" in entry && "支付" in entry && "确认" in entry,
            "a transactional deep link needs explicit user confirmation before it opens",
        )
    }

    @Test
    fun `the app entry section keeps the lock screen inside the platform unlock gate`() {
        val entry = promptText("core/15-app-entry.md")

        assertTrue("锁屏" in entry, "the app flow must name the locked-phone case")
        assertTrue("解锁" in entry, "the locked phone is opened by the platform unlock gate")
        assertTrue("shell" in entry && "绕过" in entry, "shell must not be used to bypass the lock screen")
    }

    @Test
    fun `the operating loop asks the user and changes strategy after two failures`() {
        val operating = promptText("core/10-operating.md")

        assertTrue("awaiting_reply" in operating, "a question must pause the turn on notify_user.awaiting_reply")
        assertTrue("进度" in operating, "progress reporting must not set awaiting_reply")
        assertTrue("ASR" in operating, "the input is ASR and its entities can be misheard")
        assertTrue("确认" in operating, "an uncertain entity is confirmed before acting")
        assertTrue("两次" in operating, "the same action stops after two failures")
        assertTrue("策略" in operating, "a repeated failure must change strategy or ask the user")
    }

    @Test
    fun `the operating rules are exact and never await progress or license a third attempt`() {
        val operating = promptText("core/10-operating.md")
        val awaiting = operating.lines().first { it.startsWith("4. ") }
        val twoFailures = operating.lines().first { it.startsWith("5. ") }

        // A substring ("进度", "两次") would also pass on an inverted rule. The exact sentences pin the direction.
        assertEquals(
            "4. 需要用户答复才能继续时，用 notify_user 提问并带 awaiting_reply=true，然后结束本回合等用户回答；" +
                "进度汇报、结果告知不要用它。",
            awaiting,
            "notify_user.awaiting_reply must only be used for a question that ends the turn",
        )
        assertEquals(
            "5. 同一个动作连续失败两次就停：换一种策略，或向用户说明卡在哪里并请用户选择，不要重复同一调用。",
            twoFailures,
            "two consecutive failures must stop the identical call, not license another one",
        )
        assertTrue("进度汇报、结果告知不要用它" in awaiting, "progress/result reporting must not await the user")
        assertFalse(
            Regex("""进度[^。\n]*awaiting_reply=true""").containsMatchIn(operating),
            "no rule may attach awaiting_reply=true to progress reporting",
        )
        assertFalse("第三次" in twoFailures, "the retry rule must not describe or allow a third attempt")
        assertFalse("继续重试" in twoFailures, "a repeated failure changes strategy instead of retrying again")
    }

    @Test
    fun `the safety section is the single confirmation authority and covers order transactions`() {
        val entry = PromptCatalog.manifest().single { it.file == "core/30-safety.md" }
        val safety = promptText("core/30-safety.md")
        val body = safety.lines().drop(1).filter { it.isNotBlank() }

        assertEquals("2", entry.version, "the confirmation-authority edit must bump core.safety's revision")
        assertEquals(
            "- 本节是「必须确认」集合的唯一权威；其他段落只引用本节，不得另设例外。",
            body[0],
            "30-safety must name itself the only confirmation authority instead of exposing a second rule set",
        )
        assertEquals(
            "- 可逆、低风险的操作可以直接执行；删除、卸载、支付、下单、改单、订单状态变化（包括 deep_link 打开的交易页）、" +
                "发送、修改权限或系统设置等不可逆或影响他人的操作，必须先向用户确认，并说明后果。",
            body[1],
            "placing, changing or state-changing an order is irreversible and must be in the must-confirm set",
        )
        assertFalse("不包括下单" in safety, "the blanket order carve-out contradicted 15-app-entry/20-tools")
        assertFalse("不包括" in safety, "the authority section may not carry an exception")
    }

    @Test
    fun `the app entry transaction rule defers to the safety authority without inventing a URI`() {
        val entry = promptText("core/15-app-entry.md")
        val transactionRule = entry.lines().first { it.startsWith("4. ") }

        assertEquals(
            "4. 有交易后果的深链先确认。支付、下单、改单、订单状态变化等会扣款或改变订单状态的深链，" +
                "属于「安全与确认」的必确认集合；先向用户说明并确认，再打开；打开后仍要验证。",
            transactionRule,
            "the transactional deep-link rule must defer to 30-safety and keep confirmation before opening",
        )
        assertFalse("不包括下单" in entry, "the app-entry rule must not restate the old carve-out")
        assertFalse("://" in entry, "a concrete URI example would invite copying an invented target")
    }

    @Test
    fun `the tools discipline defers transactional deep links to the safety authority`() {
        val tools = promptText("core/20-tools.md")
        val deepLinkRule = tools.lines().single { it.startsWith("- deep_link ") }

        assertEquals(
            "- deep_link 只接受 http(s) 或带 package 的自定义 scheme；uri/package 只能来自 SKILL、用户明确给出或可证实候选，" +
                "不要编造；有交易后果的深链（支付、下单、改单、订单状态变化）属于「安全与确认」的必确认集合，先按该节确认。",
            deepLinkRule,
            "the tools section must reference the one confirmation authority, not define a competing set",
        )
        assertFalse("不包括下单" in tools, "the tools section must not restate the old carve-out")
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
                "core.app-entry",
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
        /** The 25-tool default face after 0.5.4's `deep_link`/`media`; the same set `ToolFaceFixture` reads from production. */
        val DEFAULT_TOOL_NAMES: Set<String> = linkedSetOf(
            "read_screen", "tap", "long_press", "scroll", "input_text",
            "open_app", "open_url", "open_settings", "set_alarm", "compose_note",
            "clear_data", "uninstall", "disable_app", "set_permission", "set_brightness",
            "list_apps", "system_action", "deep_link", "media",
            "device", "shell", "open", "dial",
            "notify_user", "load_skill",
        )

        /** A tool name as a whole token, so `open` is not satisfied by `open_app`. */
        fun mentions(text: String, name: String): Boolean =
            Regex("(?<![A-Za-z0-9_])${Regex.escape(name)}(?![A-Za-z0-9_])").containsMatchIn(text)
    }
}
