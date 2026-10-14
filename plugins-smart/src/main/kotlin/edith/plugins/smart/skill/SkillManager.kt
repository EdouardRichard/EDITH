package edith.plugins.smart.skill

import edith.plugins.smart.P3Log

/**
 * The directory the SKILL library lives in, as the plugin sees it.
 *
 * A port rather than a direct `StorageAccess` call so the manager can be tested without a file system, and so the
 * one place that knows the on-device path is the wiring that builds it.
 */
interface SkillDirectory {
    /** Paths to consider, in any order. An absent directory answers an empty list, never an exception. */
    fun list(): List<String>

    /** The file's text, or `null` when it vanished between the listing and this call. */
    fun read(path: String): String?
}

/**
 * The SKILL library (spec §3.5–§3.7).
 *
 * **Every call re-reads the directory.** 0.2 does no file watching (spec §3.5), so an edit takes effect on the next
 * turn — which requires that no call serves a cached list. Caching here would silently make "restart or refresh"
 * mean "restart, and then also notice nothing changed".
 *
 * **Duplicates keep the first file and are reported.** `name` is the identity (spec §3.3) and the tool-call contract
 * keys on it, so two files claiming one name would make injection order decide which one the model sees. Sorted
 * paths make "the first" deterministic rather than a directory-listing accident.
 *
 * **Nothing here scores, ranks or truncates.** D60: every `enabled` SKILL's body is injected whole.
 */
class SkillManager(
    private val directory: SkillDirectory,
    private val log: (String) -> Unit = { P3Log.line(it) },
) {

    /** The SKILLs to inject, sorted by name so one request is reproducible (spec §3.6 step 3). */
    fun enabled(): List<Skill> = all().filter { it.enabled }

    /**
     * The prompt section every enabled SKILL contributes: each body **whole**, in name order, with no cap (C9/D60).
     *
     * Empty when nothing is installed, which is the normal state on a device where the user has written none — and
     * which must not become a heading with nothing under it.
     */
    fun injection(): String = injectionFor(enabled())

    /** Everything that parsed, enabled or not — the phone UI's list shows a disabled SKILL so the user can see it. */
    fun all(): List<Skill> = load()

    private fun load(): List<Skill> {
        val byName = LinkedHashMap<String, Skill>()
        val paths = directory.list().sorted()
        paths.forEach { path ->
            val text = directory.read(path) ?: return@forEach
            when (val parsed = SkillParser.parse(path, text)) {
                is SkillParse.Ok -> {
                    val skill = parsed.skill
                    if (byName.putIfAbsent(skill.name, skill) != null) {
                        // Name and count only: a SKILL body is the user's own text and never reaches a log line.
                        log("P3_SKILL duplicate name=${skill.name}")
                    }
                }

                is SkillParse.Invalid -> log("P3_SKILL skip reason=${parsed.reason}")
            }
        }
        val skills = byName.values.sortedBy { it.name }
        // The counts are the only device-side evidence that this path ran at all. Without them "the directory was read
        // and N SKILLs are being injected" and "there is no directory" are both the empty log, which is exactly the
        // ambiguity B4's device half was stuck on. A SKILL's text is never logged - only how many were read and used.
        log("P3_SKILL load files=${paths.size} skills=${skills.size} enabled=${skills.count { it.enabled }}")
        return skills
    }
}
