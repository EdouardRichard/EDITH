package com.sheen.rokid.phone.plugin.smarthelper.skill

/**
 * Evaluates machine-checkable preconditions for SKILL filtering (spec §3.8.3).
 *
 * Conditions are simple strings: `app_installed:com.example.app` or `channel_available:shizuku`.
 * Unknown condition formats are treated as satisfied (forward compatibility).
 */
class SkillConditionEvaluator(
    private val installedPackages: Set<String>,
    private val availableChannels: Set<String>,
) {
    /** Returns true when ALL appliesWhen conditions are satisfied AND NO notAppliesWhen condition is satisfied. */
    fun shouldInclude(skill: Skill): Boolean {
        if (skill.appliesWhen.any { !evaluate(it) }) return false
        if (skill.notAppliesWhen.any { evaluate(it) }) return false
        return true
    }

    private fun evaluate(condition: String): Boolean {
        val parts = condition.split(":", limit = 2)
        if (parts.size != 2) return true // unknown format → pass (forward compat)
        return when (parts[0].trim()) {
            "app_installed" -> parts[1].trim() in installedPackages
            "channel_available" -> parts[1].trim() in availableChannels
            else -> true // unknown condition type → pass
        }
    }
}
