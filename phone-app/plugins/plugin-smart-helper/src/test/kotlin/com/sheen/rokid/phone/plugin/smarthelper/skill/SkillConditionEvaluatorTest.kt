package com.sheen.rokid.phone.plugin.smarthelper.skill

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Spec §3.8.3: machine-checkable preconditions for SKILL filtering.
 *
 * Conditions are simple strings like `app_installed:com.example.app` or `channel_available:shizuku`.
 * Unknown condition formats are treated as satisfied (forward compatibility).
 */
class SkillConditionEvaluatorTest {

    private fun skill(
        appliesWhen: List<String> = emptyList(),
        notAppliesWhen: List<String> = emptyList(),
    ) = Skill(
        name = "test-skill",
        version = "1.0.0",
        description = "d",
        appliesWhen = appliesWhen,
        notAppliesWhen = notAppliesWhen,
        body = "body",
    )

    private fun evaluator(
        packages: Set<String> = emptySet(),
        channels: Set<String> = emptySet(),
    ) = SkillConditionEvaluator(installedPackages = packages, availableChannels = channels)

    @Test
    fun `empty appliesWhen and empty notAppliesWhen means include`() {
        assertTrue(evaluator().shouldInclude(skill()))
    }

    @Test
    fun `app_installed condition with package present means include`() {
        val eval = evaluator(packages = setOf("com.miui.player"))
        assertTrue(eval.shouldInclude(skill(appliesWhen = listOf("app_installed:com.miui.player"))))
    }

    @Test
    fun `app_installed condition with package absent means exclude`() {
        val eval = evaluator(packages = setOf("com.other.app"))
        assertFalse(eval.shouldInclude(skill(appliesWhen = listOf("app_installed:com.nonexistent"))))
    }

    @Test
    fun `channel_available condition with channel present means include`() {
        val eval = evaluator(channels = setOf("shizuku"))
        assertTrue(eval.shouldInclude(skill(appliesWhen = listOf("channel_available:shizuku"))))
    }

    @Test
    fun `channel_available condition with channel absent means exclude`() {
        val eval = evaluator(channels = setOf("other"))
        assertFalse(eval.shouldInclude(skill(appliesWhen = listOf("channel_available:shizuku"))))
    }

    @Test
    fun `not_applies_when condition met means exclude`() {
        val eval = evaluator(packages = setOf("com.bloat.app"))
        assertFalse(eval.shouldInclude(skill(notAppliesWhen = listOf("app_installed:com.bloat.app"))))
    }

    @Test
    fun `not_applies_when condition not met means include`() {
        val eval = evaluator(packages = emptySet())
        assertTrue(eval.shouldInclude(skill(notAppliesWhen = listOf("app_installed:com.bloat.app"))))
    }

    @Test
    fun `unknown condition format is treated as satisfied for forward compatibility`() {
        // A condition type this version doesn't know should not block a SKILL.
        assertTrue(evaluator().shouldInclude(skill(appliesWhen = listOf("future_condition:something"))))
    }

    @Test
    fun `multiple appliesWhen where one fails means exclude`() {
        val eval = evaluator(packages = setOf("com.present"), channels = emptySet())
        assertFalse(
            eval.shouldInclude(
                skill(appliesWhen = listOf("app_installed:com.present", "channel_available:missing")),
            ),
        )
    }

    @Test
    fun `both appliesWhen and notAppliesWhen all pass means include`() {
        val eval = evaluator(packages = setOf("com.needed"), channels = setOf("shizuku"))
        assertTrue(
            eval.shouldInclude(
                skill(
                    appliesWhen = listOf("app_installed:com.needed"),
                    notAppliesWhen = listOf("app_installed:com.absent"),
                ),
            ),
        )
    }
}
