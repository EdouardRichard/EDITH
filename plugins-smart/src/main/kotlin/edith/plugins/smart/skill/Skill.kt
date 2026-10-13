package edith.plugins.smart.skill

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
