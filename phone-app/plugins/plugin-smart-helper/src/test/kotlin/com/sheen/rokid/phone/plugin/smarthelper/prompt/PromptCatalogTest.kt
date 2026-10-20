package com.sheen.rokid.phone.plugin.smarthelper.prompt

import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The prompt catalog is the single place that selects, orders and versions the system prompt
 * (spec §4.1, §4.6).
 *
 * These tests pin the contract the rest of the harness builds on:
 * - the manifest is the catalog: every entry loads and every entry has a legal kind;
 * - a system plan never contains an unresolved slot or a DYNAMIC section;
 * - the section order is core 00→50, the route posture, then the rendered device profile;
 * - the dynamic runtime template stays out of `systemText`;
 * - the same route + profile is deterministic (same text, same hash).
 */
class PromptCatalogTest {

    @Test
    fun `every manifest entry loads and every system plan has no slots`() {
        val plan = PromptCatalog.plan(ContextRoute.WAKE, profileFixture())

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
    fun `the same profile and route produce the same hash twice`() {
        assertEquals(
            PromptCatalog.plan(ContextRoute.WAKE, profileFixture()).hash,
            PromptCatalog.plan(ContextRoute.WAKE, profileFixture()).hash,
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
        assertEquals(11, entries.size, "the Phase 1 catalog has exactly eleven resources")
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
        val plan = PromptCatalog.plan(ContextRoute.WAKE, profileFixture())

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
    fun `the wake and proactive routes select their own posture resource`() {
        val wake = PromptCatalog.plan(ContextRoute.WAKE, profileFixture())
        val proactive = PromptCatalog.plan(ContextRoute.PROACTIVE, profileFixture())
        val wakePosture = promptText("route/wake.md")
        val hintPosture = promptText("route/proactive-hint.md")

        assertTrue(wakePosture.isNotBlank())
        assertTrue(hintPosture.isNotBlank())
        assertTrue(wakePosture in wake.systemText)
        assertTrue(hintPosture in proactive.systemText)
        assertFalse(hintPosture in wake.systemText)
        assertFalse(wakePosture in proactive.systemText)
        assertNotEquals(wake.hash, proactive.hash)
    }

    @Test
    fun `the phase three follow up resource is authored but not selected yet`() {
        val followUp = promptText("route/proactive-followup.md")

        assertTrue(followUp.isNotBlank())
        assertFalse(followUp in PromptCatalog.plan(ContextRoute.WAKE, profileFixture()).systemText)
        assertFalse(followUp in PromptCatalog.plan(ContextRoute.PROACTIVE, profileFixture()).systemText)
    }

    @Test
    fun `a null profile drops the device profile section`() {
        val withProfile = PromptCatalog.plan(ContextRoute.WAKE, profileFixture())
        val withoutProfile = PromptCatalog.plan(ContextRoute.WAKE, null)

        assertTrue(withProfile.sections.any { it.kind == PromptKind.SESSION_STATIC })
        assertFalse(withoutProfile.sections.any { it.kind == PromptKind.SESSION_STATIC })
        assertNotEquals(withProfile.hash, withoutProfile.hash)
    }

    @Test
    fun `the dynamic template stays outside the system prompt`() {
        val template = PromptCatalog.dynamicTemplate()
        val systemText = PromptCatalog.plan(ContextRoute.WAKE, profileFixture()).systemText

        assertTrue("{{time}}" in template)
        assertTrue("{{deviceState}}" in template)
        assertFalse(template.trim() in systemText)
        assertFalse("{{" in systemText)
    }
}
