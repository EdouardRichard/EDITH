package com.sheen.rokid.phone.plugin.smarthelper.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T2.7: SkillConditionEvaluator integration into SkillManager.
 *
 * The evaluator is an optional constructor parameter so existing callers continue to work unchanged (backward compat).
 * When present, it filters SKILLs by their `appliesWhen`/`notAppliesWhen` conditions **before** the duplicate-name
 * check, so a skipped SKILL does not block a later one with the same name.
 */
class SkillManagerFilteringTest {

    private class FakeDirectory(private val files: Map<String, String>) : SkillDirectory {
        override fun list(): List<String> = files.keys.toList()
        override fun read(path: String): String? = files[path]
    }

    private fun doc(
        name: String,
        body: String = "body of $name",
        enabled: Boolean = true,
        appliesWhen: List<String> = emptyList(),
        notAppliesWhen: List<String> = emptyList(),
    ) = buildString {
        append("---\n")
        append("name: $name\n")
        append("version: 1.0.0\n")
        append("description: d\n")
        append("enabled: $enabled\n")
        if (appliesWhen.isNotEmpty()) {
            append("applies_when:\n")
            appliesWhen.forEach { append("  - $it\n") }
        }
        if (notAppliesWhen.isNotEmpty()) {
            append("not_applies_when:\n")
            notAppliesWhen.forEach { append("  - $it\n") }
        }
        append("---\n")
        append("$body\n")
    }

    @Test
    fun `evaluator that rejects a SKILL excludes it from enabled and all`() {
        // The evaluator rejects any SKILL requiring `app_installed:com.missing.app`.
        val evaluator = SkillConditionEvaluator(
            installedPackages = emptySet(),
            availableChannels = emptySet(),
        )
        val logs = mutableListOf<String>()
        val manager = SkillManager(
            FakeDirectory(mapOf(
                "a.md" to doc("kept", appliesWhen = emptyList()),
                "b.md" to doc("filtered-out", appliesWhen = listOf("app_installed:com.missing.app")),
            )),
            log = { logs += it },
            conditionEvaluator = evaluator,
        )

        assertEquals(listOf("kept"), manager.enabled().map { it.name })
        assertEquals(listOf("kept"), manager.all().map { it.name })
    }

    @Test
    fun `evaluator that accepts all produces same behavior as before`() {
        // An evaluator with all packages and channels present should include everything.
        val evaluator = SkillConditionEvaluator(
            installedPackages = setOf("com.example.app"),
            availableChannels = setOf("shizuku"),
        )
        val manager = SkillManager(
            FakeDirectory(mapOf(
                "a.md" to doc("alpha", appliesWhen = listOf("app_installed:com.example.app")),
                "b.md" to doc("beta", appliesWhen = listOf("channel_available:shizuku")),
            )),
            conditionEvaluator = evaluator,
        )

        assertEquals(listOf("alpha", "beta"), manager.enabled().map { it.name })
    }

    @Test
    fun `null evaluator preserves backward compatibility`() {
        // Without an evaluator, all SKILLs are included regardless of their conditions.
        val manager = SkillManager(
            FakeDirectory(mapOf(
                "a.md" to doc("unconditional"),
                "b.md" to doc("conditional", appliesWhen = listOf("app_installed:com.nonexistent")),
            )),
            conditionEvaluator = null,
        )

        assertEquals(listOf("conditional", "unconditional"), manager.enabled().map { it.name })
    }

    @Test
    fun `skipped SKILL produces log line with CONDITION_NOT_MET reason`() {
        val evaluator = SkillConditionEvaluator(
            installedPackages = emptySet(),
            availableChannels = emptySet(),
        )
        val logs = mutableListOf<String>()
        val manager = SkillManager(
            FakeDirectory(mapOf(
                "a.md" to doc("rejected-skill", appliesWhen = listOf("app_installed:com.missing")),
            )),
            log = { logs += it },
            conditionEvaluator = evaluator,
        )

        // Trigger loading
        manager.all()

        assertTrue(
            logs.any { it.contains("P3_SKILL") && it.contains("skipped=rejected-skill") && it.contains("CONDITION_NOT_MET") },
            "Expected a P3_SKILL skip log with CONDITION_NOT_MET, got: $logs",
        )
    }

    @Test
    fun `skipped SKILL does not block a later SKILL with the same name`() {
        // If the first file's SKILL is filtered out by conditions, the second file with the same name should be
        // considered (and included if it passes). This verifies filtering happens BEFORE the duplicate-name check.
        val evaluator = SkillConditionEvaluator(
            installedPackages = setOf("com.present"),
            availableChannels = emptySet(),
        )
        val logs = mutableListOf<String>()
        val manager = SkillManager(
            FakeDirectory(mapOf(
                "a.md" to doc("dup", body = "first-body", appliesWhen = listOf("app_installed:com.missing")),
                "b.md" to doc("dup", body = "second-body", appliesWhen = listOf("app_installed:com.present")),
            )),
            log = { logs += it },
            conditionEvaluator = evaluator,
        )

        // The first was filtered, so the second should be the one that survives — no duplicate warning.
        assertEquals(1, manager.enabled().size)
        assertEquals("second-body", manager.enabled().single().body)
        // No duplicate log should appear since the first was skipped before the name check.
        assertTrue(
            logs.none { it.contains("duplicate") },
            "No duplicate should be reported when the first was filtered, got: $logs",
        )
    }
}
