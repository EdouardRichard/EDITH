package com.sheen.rokid.phone.plugin.smarthelper.library

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
 * The model answers one question: *which entries keep earning their place in the catalog?* An entry that is listed
 * on every turn but never pulled accumulates drift evidence; enough of it and the entry is demoted out of the catalog
 * ([LibraryState.DORMANT]), and twice that and it is retired outright ([LibraryState.RETIRED]). One pull — the wearer
 * actually using the entry — erases the evidence and returns it to [LibraryState.ACTIVE] from any state, and from then
 * on this process stops counting drift against it at all. The freeze is derived, not stored: `pulls > 0` is the whole
 * condition, and it is checked at both guard sites ([catalog] does not count, [endTurn] does not advance).
 *
 * ## Observations are recorded where listing is decided
 *
 * [observeTurn] only **creates** entries and records the turn's advertised set. The observation counter moves in
 * [catalog], once per turn for each id that call actually offered. An entry is therefore created [LibraryState.ACTIVE]
 * with `observedTurns = 0`, and its **first observation is the first turn it is actually listed** — not the turn it
 * first appears in the directory. A turn that ends before its first request (a run that hits its deadline or is
 * cancelled before the first frame still reaches the turn-end bookkeeping) builds no catalog, lists nothing, and so
 * produces no drift evidence at all. Counting the sighting instead would let `K` such turns demote an entry on its
 * first listed turn, manufacturing evidence from a period in which the entry was never offered — the failure the
 * "offered and not used" rule exists to prevent.
 *
 * ## Drift is evidence of "offered and not used"
 *
 * Drift is counted only while an entry is being offered, because a name the model never saw cannot be pulled. An
 * advertised entry that the size cap keeps out of the catalog is never listed, so the model cannot pull it by name and
 * it never accumulates evidence — otherwise the governor would retire an entry nobody was ever shown a name for. A
 * DORMANT entry that left ACTIVE by drift *did* reach the model while it was listed, and its name can still be pulled,
 * so it keeps counting on a turn that builds a catalog; a cap-demoted entry never gets that far.
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
     * One drift step per observed turn: how many turns an entry may be listed without a pull before it is demoted,
     * and again before it is retired. The second threshold is measured from creation, not from the demotion, so an
     * entry runs continuously from one threshold to the next while its evidence supports it.
     */
    private val retirementHorizon: Int get() = 2 * minObservedTurns

    private val entries = LinkedHashMap<String, LibraryEntry>()
    private val seenThisTurn = LinkedHashSet<String>()

    /**
     * Ids [catalog] actually returned during the current turn — the entries the model was offered this turn. Cleared at
     * the turn boundary. Drift is evidence of "offered and not used", and an entry that was advertised but kept out of
     * the catalog by the size cap was never offered, so it cannot be pulled by name and must not accumulate evidence.
     */
    private val listedThisTurn = LinkedHashSet<String>()

    /**
     * Ids already observed during the current turn. [catalog] may be called once per request, with several requests in
     * one turn; the observation unit is the turn, so the first call that offers an id counts it and later calls in the
     * same turn are no-ops. Cleared with the rest of the turn state.
     */
    private val observedThisTurn = LinkedHashSet<String>()

    /** Whether a catalog was built at all this turn. A turn with no catalog request lists nothing and advances nothing. */
    private var catalogRequestedThisTurn = false

    /**
     * Ids that left [LibraryState.ACTIVE] for [LibraryState.DORMANT] because drift reached its threshold while they
     * were listed. This is the only kind of DORMANT entry that may keep accumulating drift: its name reached the model
     * and remains pullable by name. A cap demotion never enters this set (and a pull removes the id).
     */
    private val dormantFromDrift = LinkedHashSet<String>()

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
     * A never-before-seen id is created here, in [LibraryState.ACTIVE] with **zero** observations: appearing in the
     * directory is not evidence that the entry was offered. Its first observation is the first turn [catalog] actually
     * lists it (see the class comment). Existing entries keep their counter untouched — this call never increments
     * anything, so a turn that ends before its first request leaves every counter exactly where it was.
     *
     * The observation unit is the **turn**, not the call: an id is recorded once however many times and however many
     * calls this turn advertise it. The batch is closed by [endTurn] and the next [observeTurn] starts the next turn.
     *
     * An entry with `pulls > 0` is never counted at all — its drift evidence is not "reset and then allowed to regrow",
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
            listedThisTurn.clear()
            observedThisTurn.clear()
            catalogRequestedThisTurn = false
            turnOpen = true
        }
        advertised.toSet().forEach { id ->
            // The observation unit is the turn: only the FIRST sighting of an id this turn is considered, so an id that
            // a later call this turn advertises again is already in the set and falls through untouched.
            if (!seenThisTurn.add(id)) return@forEach
            if (entries[id] == null) {
                // New-entry semantics: a directory sighting creates the entry but is not an observation. An entry that
                // is advertised but never listed (the size cap, or a turn that ends before its first request) carries
                // no drift evidence — the model never saw its name, so it cannot have used it.
                entries[id] = LibraryEntry(id = id, state = LibraryState.ACTIVE, observedTurns = 0, pulls = 0)
            }
        }
        // The size cap is deliberately not applied here: it belongs to catalog(), which is the one place that knows
        // what the catalog is. endTurn() applies it too, so the verdict for "this turn overflowed the catalog" is
        // reported with the turn that caused it.
        return GovernorVerdict()
    }

    /**
     * Close the current turn and advance the entries that were **offered** in it.
     *
     * An ACTIVE entry advances only if it was advertised this turn, was actually listed by [catalog] this turn, and has
     * never been pulled in this process (`pulls == 0`). An entry that this turn advertised but never listed — the size cap
     * pushed it out — is left where it is: the model never saw its name, cannot pull it by name, and so the turn is not
     * evidence that the entry is going unused (see the class comment). An entry the wearer has pulled stays ACTIVE,
     * which is what keeps a used entry from being retired out from under them.
     *
     * A DORMANT entry that drift demoted while it was listed keeps advancing on a turn that builds a catalog, because
     * its name did reach the model and can still be pulled. A turn with no catalog request at all lists nothing, so it
     * advances nothing — not even a drift-demoted entry (a deliberate consequence of ruling 11). The suite pins that.
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
            if (!mayDrift(id, entry)) return@forEach
            when (advance(entry)) {
                LibraryState.DORMANT -> {
                    demoted += id
                    entries[id] = entry.copy(state = LibraryState.DORMANT)
                    // It left ACTIVE while the model was being offered it, so this is the one DORMANT kind that may
                    // keep accruing drift. The cap path below never adds here.
                    dormantFromDrift += id
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
        listedThisTurn.clear()
        observedThisTurn.clear()
        catalogRequestedThisTurn = false

        return GovernorVerdict(
            demoted = demoted + overflow,
            retired = retired,
            promoted = emptyList(),
        )
    }

    /**
     * Whether drift may advance [entry] this turn.
     *
     * [LibraryState.ACTIVE] requires the entry to have been listed this turn: a turn that never built a catalog, or
     * one whose cap kept the entry out, is not evidence of disuse. [LibraryState.DORMANT] requires the entry to have
     * been demoted by drift (so its name reached the model) **and** the turn to have built a catalog; a cap-demoted
     * entry was never offered, so it never qualifies. A [LibraryState.RETIRED] entry has nowhere left to advance.
     */
    private fun mayDrift(id: String, entry: LibraryEntry): Boolean = when (entry.state) {
        LibraryState.ACTIVE -> id in listedThisTurn
        LibraryState.DORMANT -> catalogRequestedThisTurn && id in dormantFromDrift
        LibraryState.RETIRED -> false
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
        // Back in ACTIVE: it is no longer a drift-demoted entry. A later demotion must be earned again, and a later
        // cap demotion must not inherit the drift mark.
        dormantFromDrift.remove(id)
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
     * The returned ids are also recorded as the turn's **offered** set, which is what [endTurn] advances. Only an id
     * that this call actually returns counts: an advertised entry the cap pushed out is not offered, so the model never
     * sees its name and the turn cannot be drift evidence against it.
     *
     * **This is also the one place that records an observation** (ruling 14). It increments `observedTurns` by one for
     * each id the call actually offered, at most once per turn: an id listed by two requests in the same turn counts
     * once. "Offered" is the returned ids plus a drift-demoted [LibraryState.DORMANT] entry — the one kind that may
     * continue to [LibraryState.RETIRED], because its name did reach the model while it was listed and can still be
     * pulled (ruling 13's companion clause). A cap-demoted entry is in neither group, and a pulled entry (`pulls > 0`)
     * is frozen; neither counter moves here. A turn with no catalog request records nothing at all, so a run that ends
     * before its first request cannot manufacture drift.
     *
     * Idempotent: the demotion is applied to the entries, so a second call finds the excess already DORMANT and
     * queues nothing further; and the observation is per turn, so a second call this turn adds no evidence.
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
        listedThisTurn += listed
        catalogRequestedThisTurn = true
        recordObservations(listed, universe)
        return listed
    }

    /**
     * Record one observation this turn for each id this catalog call actually offered.
     *
     * [observedThisTurn] makes the turn the unit: a second [catalog] call this turn offers the same ids and records
     * nothing further. The returned ids are the entries the model was shown; a drift-demoted DORMANT entry that is
     * still advertised is counted too — its name reached the model on the turn it was listed and remains pullable, and
     * without this D-3's DORMANT → RETIRED half would be unreachable. A cap-demoted or unadvertised entry is in
     * neither group, which is exactly the entry the model was never offered. A pulled entry is frozen: `pulls > 0`
     * means the inference from silence no longer applies to it.
     *
     * A call made outside an open turn (after [endTurn]) records nothing: it cannot reopen a turn, just as it cannot
     * make a turn's evidence count twice.
     */
    private fun recordObservations(listed: List<String>, universe: Set<String>) {
        if (!turnOpen) return
        val offered = listed + dormantFromDrift.filter { it in universe }
        offered.forEach { id ->
            if (!observedThisTurn.add(id)) return@forEach
            val entry = entries[id] ?: return@forEach
            if (entry.pulls > 0) return@forEach
            entries[id] = entry.copy(observedTurns = entry.observedTurns + 1)
        }
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
     * [LibraryState.RETIRED] no matter how many turns pass without an [endTurn]: the counter runs continuously from the
     * entry's first listing, and the DORMANT threshold is checked first.
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
            // A cap demotion is not a drift demotion: the entry is out of the catalog by size, so it must not inherit
            // the right to keep counting drift. (It cannot be in the set today — only a pull returns an entry to
            // ACTIVE and the pull removes it — but the invariant is cheaper to state than to reason about here.)
            dormantFromDrift.remove(id)
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
