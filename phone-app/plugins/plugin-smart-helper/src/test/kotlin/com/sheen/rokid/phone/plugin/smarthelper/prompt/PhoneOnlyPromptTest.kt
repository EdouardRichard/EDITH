package com.sheen.rokid.phone.plugin.smarthelper.prompt

import com.sheen.rokid.phone.plugin.smarthelper.SmartTestRig
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.smartStores
import com.sheen.rokid.phone.plugin.smarthelper.streamText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * 0.5.1 Task 9 (`C8`, spec §3.6): the phone-only prompt tells the model that EDITH's own floating ball and message
 * bubbles are **its** overlay, not the target App's content.
 *
 * The note is a stable `STATIC` section appended to every phone-only plan and absent from every glasses plan; because it
 * is part of the ordered sections, `PromptPlan.hash` (and therefore the `P3_PROMPT` line) distinguishes the two modes.
 * It carries no user content and no observation: it only names EDITH's own surface.
 */
class PhoneOnlyPromptTest {

    /**
     * The exact §3.6 text, duplicated here on purpose: the test proves both the spec literal and the authored resource,
     * so a paraphrase in either place fails. It contains no user text, screenshot content or device fact.
     */
    private val overlay =
        "纯手机模式屏幕观察注意事项：屏幕截图/无障碍树中可能包含 EDITH 自己的悬浮球和消息气泡（半透明卡片）。" +
            "它们是 EDITH 的界面，不是目标 App 的内容。不要把它们识别成目标 App 的控件、文本或状态，" +
            "也不要点击悬浮球或气泡区域；需要读取目标 App 时，以目标 App 的窗口为准。"

    private val overlayFile = "phone-only/overlay.md"
    private val overlayId = "phone-only.overlay"

    @Test
    fun `the phone-only overlay resource is the spec text verbatim`() {
        assertEquals(overlay, promptText(overlayFile))
    }

    @Test
    fun `phone-only plan contains the EDITH overlay ownership note`() {
        val glasses = PromptCatalog.plan(PromptRoute.WAKE, profileFixture())
        val phoneOnly = PromptCatalog.plan(PromptRoute.WAKE, profileFixture(), phoneOnly = true)

        assertTrue(overlay in phoneOnly.systemText, "the phone-only plan must carry the §3.6 note")
        val section = phoneOnly.sections.single { it.id == overlayId }
        assertEquals(PromptKind.STATIC, section.kind, "the note is a static section, not a slot or device fact")
        assertEquals(overlay, section.text)
        assertEquals(section, phoneOnly.sections.last(), "the note is appended after the rendered device profile")
        assertEquals(
            glasses.sections,
            phoneOnly.sections.dropLast(1),
            "phone-only adds one section and rewrites none of the shared/glasses ones",
        )
        assertFalse(overlay in glasses.systemText)
        assertNotEquals(glasses.hash, phoneOnly.hash, "the two modes must fingerprint differently")
        assertEquals(PromptPlan.hashOf(phoneOnly.sections), phoneOnly.hash)
    }

    @Test
    fun `glasses plan does not contain the note and keeps its hash behavior`() {
        val default = PromptCatalog.plan(PromptRoute.WAKE, profileFixture())
        val explicitGlasses = PromptCatalog.plan(PromptRoute.WAKE, profileFixture(), phoneOnly = false)
        val repeated = PromptCatalog.plan(PromptRoute.WAKE, profileFixture())

        assertFalse(overlay in default.systemText)
        assertTrue(default.sections.none { it.id == overlayId }, "glasses mode must not select the phone-only section")
        assertEquals(default, explicitGlasses, "the defaulted parameter must be exactly the glasses plan")
        assertEquals(default.hash, repeated.hash, "the same route + profile must stay deterministic")
        assertEquals(64, default.hash.length)
    }

    @Test
    fun `the phone-only plan keeps the prompt plan invariants`() {
        val plan = PromptCatalog.plan(PromptRoute.PROACTIVE_HINT, profileFixture(), phoneOnly = true)

        assertFalse(plan.sections.any { it.kind == PromptKind.DYNAMIC }, "a system plan never carries a DYNAMIC section")
        assertFalse(plan.sections.any { it.kind == PromptKind.PINNED }, "PINNED content stays out of the system plan")
        assertFalse("{{" in plan.systemText, "a rendered system prompt must not keep slot syntax")
        assertFalse("}}" in plan.systemText)
        assertEquals(PromptPlan.hashOf(plan.sections), plan.hash)
        assertTrue(plan.sections.all { it.kind == PromptKind.STATIC || it.kind == PromptKind.SESSION_STATIC })
    }

    @Test
    fun `the manifest declares the phone-only overlay as a static section`() {
        val entry = PromptCatalog.manifest().single { it.file == overlayFile }

        assertEquals(overlayId, entry.id)
        assertEquals(PromptKind.STATIC, entry.kind)
        assertTrue(entry.version.isNotBlank())
        assertTrue(entry.description.isNotBlank())
    }

    @Test
    fun `the catalog rejects a manifest that omits the phone-only overlay`() {
        val withoutOverlay = PromptCatalog.manifest().filterNot { it.file == overlayFile }

        assertFailsWith<IllegalArgumentException> { PromptCatalog.validateCatalog(withoutOverlay) }
    }

    @Test
    fun `the plugin pins the overlay only in phone-only mode and the prompt hash distinguishes them`() {
        val (glassesText, glassesHash) = firstWakeRequest(phoneOnly = false)
        val (phoneText, phoneHash) = firstWakeRequest(phoneOnly = true)

        assertFalse(overlay in glassesText, "glasses mode must stay byte-identical: no phone-only note")
        assertTrue(overlay in phoneText, "the plugin's default catalog must use the live phoneOnly() flag")
        assertEquals(
            PromptCatalog.plan(PromptRoute.WAKE, null).hash,
            glassesHash,
            "the glasses plan hash must be unchanged",
        )
        assertEquals(
            PromptCatalog.plan(PromptRoute.WAKE, null, phoneOnly = true).hash,
            phoneHash,
            "the phone-only plan hash must fingerprint the appended section",
        )
        assertNotEquals(glassesHash, phoneHash, "P3_PROMPT's hash must distinguish the two modes")
    }

    /** One wake turn on an activated rig, returning the model-facing system text and the request's `P3_PROMPT` hash. */
    private fun firstWakeRequest(phoneOnly: Boolean): Pair<String, String> {
        val rig = SmartTestRig(conversationStores = smartStores(), phoneOnly = phoneOnly)
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.wakeAndFinal("现在几点了")
        } finally {
            P3Log.sink = previous
        }

        val instructions = rig.model.requests.single().instructions
        val promptLine = lines.single { it.startsWith("P3_PROMPT ") }
        val hash = promptLine.substringAfter("hash=").substringBefore(' ')
        return instructions to hash
    }
}
