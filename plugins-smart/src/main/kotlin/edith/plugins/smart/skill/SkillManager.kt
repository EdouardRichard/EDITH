package edith.plugins.smart.skill

import edith.plugins.smart.P3Log
import edith.plugins.smart.library.GovernorVerdict
import edith.plugins.smart.library.LibraryGovernor
import edith.plugins.smart.library.LibraryState

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
 * **Two layers since Phase 6 (design D-1).** [catalog] advertises the enabled entries by `name` and `description`;
 * the body is pulled by name through [load], which reaches dormant entries too. [injection] (every enabled body whole,
 * D60) is kept for rollback and for the tests that pin it, and is no longer the production path.
 */
class SkillManager(
    private val directory: SkillDirectory,
    private val log: (String) -> Unit = { P3Log.line(it) },
    /** T2.7: optional condition evaluator for SKILL filtering by appliesWhen/notAppliesWhen (spec §3.8.3). */
    private val conditionEvaluator: SkillConditionEvaluator? = null,
    /**
     * Phase 6 lifecycle governor. `null` (the default) keeps the pre-Phase-6 behaviour exactly: no catalog bound, no
     * drift, [load] records nothing — which is what keeps every existing caller and test unchanged.
     */
    private val governor: LibraryGovernor? = null,
) {

    /** Last reported `(listed, bounded, dormant)` triple, so a per-request rebuild logs only when the numbers move. */
    private var lastCatalogNumbers: Triple<Int, Int, Int>? = null

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
    fun all(): List<Skill> = readLibrary()

    /**
     * The SKILL catalog for one request (Phase 6, design D-1): `name` and `description` per entry, bodies omitted.
     *
     * Recomputed on every call because the directory is re-read (spec §3.5) and the governor's selection is
     * per-request. With a governor, the entries it lists are intersected with the enabled set — a disabled SKILL is
     * never advertised — and the overflow the cap demoted stays reachable through [load]. With no governor every
     * enabled entry is listed. An empty library yields the empty string, never a header with nothing under it.
     *
     * Logs one `P3_SKILL catalog listed=<n> bounded=<n> dormant=<n>` line whenever those measured numbers change
     * (design D-10). All three are read from real state: `listed` counts what the returned text actually carries,
     * `bounded` counts the ACTIVE→DORMANT transitions this call performed, and `dormant` counts the whole library's
     * dormant entries afterwards. Counts only — a body never reaches a log line.
     */
    fun catalog(): String {
        val skills = all()
        val names = skills.map { it.name }
        val governor = this.governor

        val bounded: Int
        val listedNames: List<String>
        if (governor == null) {
            bounded = 0
            listedNames = names
        } else {
            val before = names.associateWith { governor.entry(it)?.state }
            listedNames = governor.catalog(names)
            bounded = names.count {
                before[it] == LibraryState.ACTIVE && governor.entry(it)?.state == LibraryState.DORMANT
            }
        }

        // Only enabled, still-present skills can appear in the catalog the model sees. The governor's list may name an
        // entry the directory no longer holds, or one that was enabled when beginTurn() observed it and has since been
        // disabled — a file can change mid-turn. This intersection is the defence for that race, not the rule that
        // keeps disabled entries out of governance: beginTurn() never observes them.
        val enabledByName = skills.filter { it.enabled }.associateBy { it.name }
        val listedSkills = listedNames.mapNotNull { enabledByName[it] }
        val dormant = if (governor == null) 0 else names.count { governor.entry(it)?.state == LibraryState.DORMANT }

        reportCatalogNumbers(listed = listedSkills.size, bounded = bounded, dormant = dormant)
        return catalogFor(listedSkills)
    }

    private fun reportCatalogNumbers(listed: Int, bounded: Int, dormant: Int) {
        val numbers = Triple(listed, bounded, dormant)
        if (numbers == lastCatalogNumbers) return
        lastCatalogNumbers = numbers
        log("P3_SKILL catalog listed=$listed bounded=$bounded dormant=$dormant")
    }

    /**
     * Pull one SKILL body by name (Phase 6, design D-1/D-4).
     *
     * The search is the **whole library** — dormant and disabled entries included — so the refusals stay
     * distinguishable: a disabled entry reports [SkillLoadReason.DISABLED], an unknown name
     * [SkillLoadReason.NOT_IN_LIBRARY]. A successful pull is recorded on the governor, which brings the entry back to
     * ACTIVE from any state; [SkillLoad.Loaded.wasDormant] reports the state observed **before** the pull, so a
     * cap-demoted or drift-demoted entry is still pullable (design D-4's second gate).
     */
    fun load(name: String): SkillLoad {
        val skill = all().firstOrNull { it.name == name }
            ?: return SkillLoad.NotFound(SkillLoadReason.NOT_IN_LIBRARY)
        if (!skill.enabled) return SkillLoad.NotFound(SkillLoadReason.DISABLED)
        val wasDormant = governor?.entry(name)?.state == LibraryState.DORMANT
        governor?.recordPull(name)
        return SkillLoad.Loaded(skill, wasDormant)
    }

    /**
     * Open a turn: advertise every **enabled** entry to the governor **once** (design §5). Never called from [catalog] —
     * that is per-request, and counting it as an observation would inflate drift per tool round-trip (D-3).
     *
     * Disabled entries are deliberately not observed: they are never advertised in the catalog and [load] refuses them
     * with [SkillLoadReason.DISABLED], so they can never answer drift with a pull. Governing them would demote and
     * then retire a SKILL merely because the user turned it off — the premature retirement design D-4 exists to
     * prevent.
     */
    fun beginTurn() {
        governor?.observeTurn(enabled().map { it.name })
    }

    /**
     * Close a turn: advance the governor one step and report what it demoted/retired. Quiet without a governor, so a
     * caller that has not adopted Phase 6 keeps its old behaviour.
     */
    fun endTurn(): GovernorVerdict =
        governor?.endTurn() ?: GovernorVerdict(emptyList(), emptyList(), emptyList())

    /** Pulls during the turn that just completed (survives [endTurn]); 0 without a governor. */
    val pullsThisTurn: Int get() = governor?.pullsThisTurn ?: 0

    /** Pulls since this manager's governor was created; 0 without a governor. */
    val pullsThisSession: Int get() = governor?.pullsThisSession ?: 0

    /** How many catalog-listed entries have never been pulled this session (design D-10); 0 without a governor. */
    fun neverPulledCount(): Int = governor?.neverPulledCount() ?: 0

    private fun readLibrary(): List<Skill> {
        val byName = LinkedHashMap<String, Skill>()
        val paths = directory.list().sorted()
        paths.forEach { path ->
            val text = directory.read(path) ?: return@forEach
            when (val parsed = SkillParser.parse(path, text)) {
                is SkillParse.Ok -> {
                    val skill = parsed.skill
                    // T2.7: filter by machine-checkable conditions before the duplicate-name check, so a skipped
                    // SKILL does not block a later one with the same name (spec §3.8.3).
                    if (conditionEvaluator != null && !conditionEvaluator.shouldInclude(skill)) {
                        log("P3_SKILL skipped=${skill.name} reason=CONDITION_NOT_MET")
                        return@forEach
                    }
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
