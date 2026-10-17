package edith.plugins.smart.library

/**
 * What one [LibraryGovernor.endTurn] or [LibraryGovernor.catalog] call did to the governed entries.
 *
 * A verdict is a report, never a command: it tells the caller which ids changed lifecycle so it can log the drift. The
 * governor has already applied every change it reports.
 */
data class GovernorVerdict(
    /** Ids that left the catalog this step — drift ([LibraryState.ACTIVE] → [LibraryState.DORMANT]) or the size cap. */
    val demoted: List<String> = emptyList(),
    /** Ids that left the library entirely: [LibraryState.DORMANT] → [LibraryState.RETIRED]. */
    val retired: List<String> = emptyList(),
    /** Ids that moved back into play, e.g. a dormant entry pulled again. */
    val promoted: List<String> = emptyList(),
) {
    /** True when this step changed nothing. The quiet case every drift-avoiding test asserts on. */
    val isEmpty: Boolean get() = demoted.isEmpty() && retired.isEmpty() && promoted.isEmpty()
}

/**
 * The generic three-state lifecycle model for a library of advertised entries.
 *
 * The model answers one question: *which entries keep earning their place in the catalog?* An entry that is advertised
 * on every turn but never pulled accumulates drift evidence; enough of it and the entry is demoted out of the catalog
 * ([LibraryState.DORMANT]), and twice that and it is retired outright ([LibraryState.RETIRED]). One pull — the wearer
 * actually using the entry — erases the evidence and returns it to [LibraryState.ACTIVE] from any state, and from then
 * on this process stops counting drift against it at all. The freeze is derived, not stored: `pulls > 0` is the whole
 * condition, and it is checked at both guard sites ([observeTurn] does not count, [endTurn] does not advance).
 *
 * ## Why a pull silences drift for the rest of the process
 *
 * The design fixes this as an unbounded guarantee, not a cooldown: after a pull, *any* number of quiet advertised
 * turns must leave the entry ACTIVE and in the catalog (`2026-10-17-…-design.md` D-4 §5, protection side). A pull
 * means the entry demonstrably has a user, and drift is an inference from silence; once a use has been observed, the
 * inference is not merely weakened, it is retired as an instrument for that entry. Re-arming the counter after a pull
 * would let an entry be demoted `minObservedTurns` turns after its last genuine use — the exact premature retirement
 * this task exists to prevent. Drift is therefore only ever measured against entries that have never been used here.
 *
 * ## What this class deliberately does not know
 *
 * Nothing about SKILLs and nothing about memories. It sees ids, states and counts. Phase 6 governs the SKILL catalog
 * with it (Task 2) and reaches the memory store through the same seam (Task 6); keeping the vocabulary empty of both
 * is what lets the second consumer reuse it unchanged.
 *
 * Purely in-memory (design decision D-5): there is no file, database or any other persistence here, and adding one
 * would be a design change, not an implementation detail.
 *
 * Not thread-safe. One instance is driven by one turn loop.
 */
class LibraryGovernor(
    val minObservedTurns: Int = MIN_OBSERVED_TURNS,
    val maxCatalogEntries: Int = MAX_CATALOG_ENTRIES,
) {

    /**
     * One drift step per observed turn: how many turns an entry may be advertised without a pull before it is demoted,
     * and again before it is retired. The second threshold is measured from creation, not from the demotion, so an
     * entry runs continuously from one threshold to the next while its evidence supports it.
     */
    private val retirementHorizon: Int get() = 2 * minObservedTurns

    private val entries = LinkedHashMap<String, LibraryEntry>()
    private val seenThisTurn = LinkedHashSet<String>()
    private val everListed = LinkedHashSet<String>()
    private var pendingCatalogDemotions = LinkedHashSet<String>()
    private var turnOpen = false

    private var pullsThisTurnCount = 0
    private var pullsThisSessionCount = 0

    /**
     * How many pulls happened during the turn that just completed.
     *
     * The count survives [endTurn] — that is exactly when the design reads it (`P3_SKILL pulls turn=… session=…`) —
     * and is reset when the next turn opens in [observeTurn]. Before any turn has been observed, and during a turn
     * that has seen no pull, it reads 0.
     */
    val pullsThisTurn: Int get() = pullsThisTurnCount

    /** How many pulls have happened since this governor was created. */
    val pullsThisSession: Int get() = pullsThisSessionCount

    /**
     * Record the ids advertised to the model this turn.
     *
     * A never-before-seen id is created here, in [LibraryState.ACTIVE] with one observed turn already counted: being
     * advertised *is* the first observation, and that first observation is real drift evidence against an entry nobody
     * has asked for. The observation unit is the **turn**, not the call: an id counts once however many times and
     * however many calls this turn advertises it, so a turn that rebuilds the catalog repeatedly cannot inflate the
     * evidence. The batch is closed by [endTurn] and the next [observeTurn] starts the next turn.
     *
     * An entry with `pulls > 0` is not counted at all — its drift evidence is not "reset and then allowed to regrow",
     * it is absent (see the class comment). That is the freeze, and `pulls > 0` is the whole of it.
     *
     * Returns the verdict for the creation step, which is always empty today: entries are only ever created ACTIVE,
     * and only [endTurn] and [catalog] move them.
     */
    fun observeTurn(advertised: Collection<String>): GovernorVerdict {
        if (!turnOpen) {
            // The turn boundary is here, not in endTurn(): the design reads pullsThisTurn at turn end, right after
            // endTurn(), so resetting there would erase the number at the one moment it is needed. The counter is
            // therefore "pulls during the last completed turn" until the next turn opens.
            pullsThisTurnCount = 0
            seenThisTurn.clear()
            turnOpen = true
        }
        advertised.toSet().forEach { id ->
            // The observation unit is the turn: only the FIRST sighting of an id this turn may count, so an id that a
            // later call this turn advertises again is already in the set and falls through untouched.
            if (!seenThisTurn.add(id)) return@forEach
            val existing = entries[id]
            entries[id] = when {
                existing == null ->
                    LibraryEntry(id = id, state = LibraryState.ACTIVE, observedTurns = 1, pulls = 0)
                existing.pulls > 0 -> existing
                else -> existing.copy(observedTurns = existing.observedTurns + 1)
            }
        }
        // The size cap is deliberately not applied here: it belongs to catalog(), which is the one place that knows
        // what the catalog is. endTurn() applies it too, so the verdict for "this turn overflowed the catalog" is
        // reported with the turn that caused it.
        return GovernorVerdict()
    }

    /**
     * Close the current turn and advance the entries that were seen in it.
     *
     * An entry advances only if it was advertised at least once this turn **and** has never been pulled in this process
     * (`pulls == 0`). An entry that this turn never advertised is left where it is — it is not in the catalog, so it
     * cannot drift — and an entry the wearer has pulled is left ACTIVE, which is what keeps a used entry from being
     * retired out from under them.
     *
     * Also enforces the size cap, so the turn that overflows the catalog reports the overflow in its own verdict.
     * The cap is evaluated over the entries **seen this turn** only (the intersection of [seenThisTurn] and
     * [LibraryState.ACTIVE]): an entry this turn did not advertise cannot overflow a catalog it is not in, so it is
     * neither capped nor named — its lifecycle state is left exactly where it was.
     *
     * Idempotent: a second call with nothing newly seen advances nothing and reports nothing.
     */
    fun endTurn(): GovernorVerdict {
        val demoted = mutableListOf<String>()
        val retired = mutableListOf<String>()

        // Keys are visited in sorted order so the verdict is stable regardless of the order ids were advertised in.
        seenThisTurn.sorted().forEach { id ->
            val entry = entries[id] ?: return@forEach
            if (entry.pulls > 0) return@forEach
            when (advance(entry)) {
                LibraryState.DORMANT -> {
                    demoted += id
                    entries[id] = entry.copy(state = LibraryState.DORMANT)
                }
                LibraryState.RETIRED -> {
                    retired += id
                    entries[id] = entry.copy(state = LibraryState.RETIRED)
                }
                // The entry has not drifted past its current threshold yet.
                LibraryState.ACTIVE -> return@forEach
            }
        }

        val overflow = applyCatalogCap(seenThisTurn) + pendingCatalogDemotions
        pendingCatalogDemotions = LinkedHashSet()
        turnOpen = false
        seenThisTurn.clear()

        return GovernorVerdict(
            demoted = demoted + overflow,
            retired = retired,
            promoted = emptyList(),
        )
    }

    /**
     * Record that the wearer actually pulled [id].
     *
     * A pull increments the lifetime count, wipes the drift evidence, forces the entry back to [LibraryState.ACTIVE]
     * from **any** state — including [LibraryState.RETIRED] — and makes `pulls > 0`, which freezes the drift counter
     * for the rest of this process (see the class comment for why that guarantee is unbounded).
     *
     * Pulling an id this governor has never seen is a no-op: an unseen id has nothing to reset, and inventing an entry
     * here would let a stale caller repopulate the catalog.
     */
    fun recordPull(id: String) {
        val entry = entries[id] ?: return
        entries[id] = entry.copy(
            state = LibraryState.ACTIVE,
            observedTurns = 0,
            pulls = entry.pulls + 1,
        )
        pullsThisTurnCount += 1
        pullsThisSessionCount += 1
    }

    /** The current state of [id], or null if it has never been advertised. */
    fun entry(id: String): LibraryEntry? = entries[id]?.copy()

    /**
     * The catalog as the model should see it this step: the [LibraryState.ACTIVE] ids **from [advertised]** only,
     * sorted, at most [maxCatalogEntries] of them.
     *
     * [advertised] is the catalog's universe, not a hint. An id it does not contain — a SKILL the user disabled or
     * deleted after it was observed — is neither listed nor cap-demoted, and its lifecycle state is left untouched, so
     * re-enabling it makes it visible again without a restart. Governing an absent entry would let a ghost hold a slot
     * and push a genuinely advertised entry out of the catalog: the silent loss this phase exists to remove. The cap is
     * therefore taken over `advertised ∩ ACTIVE`, and only entries that could actually reach the model are bounded.
     *
     * When more advertised entries are ACTIVE than the catalog holds, the lexicographically smallest ids keep their
     * places and the remainder are demoted to [LibraryState.DORMANT]. The comparison is on the id alone, so the rule is
     * deterministic and independent of the order ids were advertised in. Those demotions are **not** swallowed here:
     * they are queued and reported by the next [endTurn] verdict, so the turn that caused the overflow names what it
     * demoted (design D-6 "不静默"). No name or content from an entry is ever reported, only its id.
     *
     * [everListed] is pruned to [advertised] and grows only by what this call returns. An id that stops being
     * advertised leaves the "appeared in a catalog" set with it, so a disabled or deleted entry cannot inflate
     * [neverPulledCount] (that count is the signal "the catalog shows entries nobody pulls", and an entry the catalog
     * no longer shows is not part of it); an id that is advertised again re-enters. This pruning touches bookkeeping
     * only — never a lifecycle state.
     *
     * Idempotent: the demotion is applied to the entries, so a second call finds the excess already DORMANT and
     * queues nothing further.
     */
    fun catalog(advertised: Collection<String>): List<String> {
        val universe = advertised.toSet()
        // A ghost drops out of the never-pulled signal the moment it stops being advertised. Its state is untouched.
        everListed.retainAll(universe)
        // An overflow the cap applies here is not thrown away: it is queued for the next endTurn() verdict, because
        // the design's ordering is "catalog per request, endTurn at turn end" — without this, the request that
        // overflowed the catalog would demote entries and nothing would ever report it (D-6 "不静默").
        pendingCatalogDemotions += applyCatalogCap(universe)
        val listed = universe
            .filter { entries[it]?.state == LibraryState.ACTIVE }
            .sorted()
            .take(maxCatalogEntries)
        everListed += listed
        return listed
    }

    /**
     * How many entries have appeared in a catalog and have never been pulled (design D-10).
     *
     * "Listed" means the id was actually handed out by [catalog]: it survived that call's cap and was part of the
     * sorted, bounded result. An entry that the cap demoted before it was ever listed is therefore *not* counted —
     * it never reached the model, so it cannot be part of the "the catalog advertises entries nobody uses" signal that
     * this count exists to detect. Counting every known-but-unpulled entry instead would make the count grow with the
     * overflow, i.e. with the very entries the model was never shown.
     */
    fun neverPulledCount(): Int = everListed.count { entries[it]?.pulls == 0 }

    /**
     * The state an entry moves to once its drift reaches the next threshold, or [LibraryState.ACTIVE] when it has not.
     *
     * Progression is one step at a time, so an entry cannot skip [LibraryState.DORMANT] on its way to
     * [LibraryState.RETIRED] no matter how many turns pass without an [endTurn]: the counter runs continuously from
     * creation, and the DORMANT threshold is checked first.
     */
    private fun advance(entry: LibraryEntry): LibraryState = when (entry.state) {
        LibraryState.ACTIVE ->
            if (entry.observedTurns >= minObservedTurns) LibraryState.DORMANT else LibraryState.ACTIVE
        LibraryState.DORMANT ->
            if (entry.observedTurns >= retirementHorizon) LibraryState.RETIRED else LibraryState.ACTIVE
        LibraryState.RETIRED -> LibraryState.ACTIVE
    }

    /**
     * Demote the [LibraryState.ACTIVE] ids among [candidates] beyond [maxCatalogEntries], smallest ids first; returns
     * them sorted. [candidates] is the advertised set in [catalog] and the entries seen this turn in [endTurn], so an
     * id outside it is never capped.
     */
    private fun applyCatalogCap(candidates: Collection<String>): List<String> {
        val overflow = candidates
            .filter { entries[it]?.state == LibraryState.ACTIVE }
            .sorted()
            .drop(maxCatalogEntries)
        overflow.forEach { id ->
            val entry = entries[id] ?: return@forEach
            entries[id] = entry.copy(state = LibraryState.DORMANT)
        }
        return overflow
    }

    companion object {
        /** Turns an entry may be advertised without a pull before it is demoted. */
        const val MIN_OBSERVED_TURNS = 3

        /** How many entries the catalog carries at once. */
        const val MAX_CATALOG_ENTRIES = 12
    }
}
