package com.sheen.rokid.phone.plugin.smarthelper.skill
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextManager

/**
 * A SKILL: a prompt template that guides the model through a task it would otherwise explore its way through
 * (spec §3.1). One `.md` file on the user's SD card is one SKILL (spec §3.2).
 *
 * This is the **parsed** form. The body is the Markdown after the front matter, verbatim: D60 injects it whole, so
 * nothing here trims, summarises or scores it.
 */
data class Skill(
    /** The identity, kebab-case (spec §3.3). Unique across the library; the file name is not the identity. */
    val name: String,
    val version: String,
    val description: String,
    val triggers: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val appliesWhen: List<String> = emptyList(),
    val notAppliesWhen: List<String> = emptyList(),
    val priority: SkillPriority = SkillPriority.NORMAL,
    val enabled: Boolean = true,
    val body: String,
)

/** Spec §3.3. Nothing reads `HIGH`/`LOW` yet — D60 removed selection, so ordering is the only place it could act. */
enum class SkillPriority { HIGH, NORMAL, LOW }

/**
 * The injectable form of a SKILL list: every body **whole**, in `name` order (C9/D60 — no scoring, no ranking, no
 * cap). One formatter, used by both the plugin's instruction assembly and `ContextManager`'s, so the two cannot
 * disagree about what a SKILL looks like in a prompt.
 */
internal fun injectionFor(skills: List<Skill>): String =
    skills.sortedBy { it.name }.joinToString("\n\n") { skill ->
        buildString {
            append("## SKILL: ").append(skill.name).append('\n')
            append(skill.description).append('\n')
            append(skill.body)
        }
    }

/**
 * The catalog form of a SKILL list (Phase 6, design D-1): one `- <name>: <description>` line per entry, sorted by
 * `name`, under a `## SKILL 目录` header — and **never a body**. The model reads the catalog to decide what to pull;
 * the body only follows an explicit [SkillManager.load].
 *
 * Empty input yields the empty string, not a header with nothing under it: "no SKILL is available" must make the
 * caller omit the segment entirely, not put an empty section in the system frame.
 */
internal fun catalogFor(skills: List<Skill>): String {
    if (skills.isEmpty()) return ""
    return skills.sortedBy { it.name }.joinToString(
        separator = "\n",
        prefix = "## SKILL 目录\n\n",
    ) { skill -> "- ${skill.name}: ${skill.description}" }
}

/** The outcome of parsing one file. */
sealed class SkillParse {
    data class Ok(val skill: Skill) : SkillParse()
    data class Invalid(val reason: SkillRejection) : SkillParse()
}

/**
 * Why a file did not become a [Skill].
 *
 * A rejection is always local to its file: the manager skips it and keeps every other SKILL (spec §3.7 — an empty
 * directory is a normal state, and so is a directory with one bad file in it).
 */
enum class SkillRejection {
    /** The first line is not the `---` delimiter. */
    NO_FRONT_MATTER,

    /** The block opened and never closed, or a field is not one of the shapes this version understands. */
    MALFORMED_FRONT_MATTER,

    /** `name`, `version` or `description` is absent or empty. */
    MISSING_REQUIRED_FIELD,

    /** The body is blank, so there is nothing to inject. */
    BLANK_BODY,

    /**
     * The text carries U+FFFD, which can only be there because the bytes were not valid UTF-8.
     *
     * `StorageAccessServiceImpl.readFile` decodes with a replacing decoder, so invalid bytes arrive as U+FFFD rather
     * than throwing. Injecting that into the prompt would put a garbled SKILL in front of the model with no signal
     * that anything went wrong — and a HALF-garbled SKILL is worse than a missing one, because the model follows the
     * readable half.
     */
    INVALID_ENCODING,
}

/**
 * The result of pulling one SKILL body by name (Phase 6, design D-1/D-4).
 *
 * A refusal names *why* it refused so the two cases stay distinguishable at the tool boundary: a disabled SKILL is
 * still installed (the user turned it off), while an absent name is a call the model can self-correct.
 */
sealed class SkillLoad {
    /**
     * The body, plus whether the governor held the entry DORMANT **before** the pull. Drift-demoted and
     * cap-demoted entries both arrive here; the flag lets the caller tell "it was in the catalog" from "it was only
     * reachable by name" (design D-4's second gate).
     */
    data class Loaded(val skill: Skill, val wasDormant: Boolean) : SkillLoad()

    /** Nothing was returned. [reason] says whether the name exists but is disabled, or does not exist at all. */
    data class NotFound(val reason: SkillLoadReason) : SkillLoad()
}

/** Why a [SkillLoad] found no body. */
enum class SkillLoadReason {
    /** No entry with that name in the whole library. */
    NOT_IN_LIBRARY,

    /** The entry exists but the user disabled it, so its body must not be handed to the model. */
    DISABLED,
}
