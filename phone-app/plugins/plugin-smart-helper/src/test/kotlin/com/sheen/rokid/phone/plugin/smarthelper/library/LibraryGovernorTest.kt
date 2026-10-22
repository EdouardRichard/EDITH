package com.sheen.rokid.phone.plugin.smarthelper.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryGovernorTest {

    @Test fun `an entry is created ACTIVE on its first sighting`() {
        val g = LibraryGovernor()
        g.observeTurn(listOf("a"))
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state)
        // A directory sighting only creates the entry: its first observation is the first turn it is actually
        // listed, not the turn it first appears in the directory (ruling 14).
        assertEquals(0, g.entry("a")?.observedTurns, "creation is not an observation")
    }

    @Test fun `two observeTurn calls in one turn create one entry and count one observation`() {
        val g = LibraryGovernor()
        g.observeTurn(listOf("a"))
        g.observeTurn(listOf("a"))                                   // same turn, still no catalog
        assertEquals(0, g.entry("a")?.observedTurns, "sightings create the entry; they do not observe it")
        g.catalog(listOf("a"))                                       // the entry was actually offered
        g.catalog(listOf("a"))                                       // same turn, rebuilt catalog
        assertEquals(1, g.entry("a")?.observedTurns, "the turn is the observation unit, not the call")
        assertTrue(g.endTurn().isEmpty, "one observation must be below the minimum")
    }

    @Test fun `a turn that builds no catalog adds no drift evidence`() {
        // Ruling 14's defect: a turn that ends before its first request (deadline, cancellation) still flows to
        // endTurn(). It advertises the library but never builds a catalog, so the model was shown nothing and cannot
        // have pulled anything. Pre-fix observeTurn() incremented every advertised id, so 2 * K such turns manufactured
        // enough drift to demote and then retire an entry that was never offered at all.
        val g = LibraryGovernor()
        val k = LibraryGovernor.MIN_OBSERVED_TURNS
        val ids = listOf("a", "b")
        repeat(2 * k + 1) {
            g.observeTurn(ids)
            assertTrue(g.endTurn().isEmpty, "a turn with no catalog request cannot produce a verdict")
        }
        ids.forEach { id ->
            assertEquals(0, g.entry(id)?.observedTurns, "$id was never listed, so it has no observations")
            assertEquals(LibraryState.ACTIVE, g.entry(id)?.state, "$id must stay ACTIVE while it is never listed")
        }
    }

    @Test fun `a newly observed entry is not demoted until it has been listed minObservedTurns times`() {
        // New-entry semantics (ruling 14): an entry is created ACTIVE with observedTurns = 0, and its first
        // observation is the first turn it is actually listed. K listed turns are needed to demote, not K turns of
        // merely appearing in the directory.
        val g = LibraryGovernor()
        val k = LibraryGovernor.MIN_OBSERVED_TURNS
        g.observeTurn(listOf("a"))
        assertEquals(0, g.entry("a")?.observedTurns, "appearing in the directory is not an observation")
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state)

        repeat(k - 1) {
            g.observeTurn(listOf("a"))
            assertEquals(listOf("a"), g.catalog(listOf("a")))
            assertTrue(g.endTurn().isEmpty, "fewer than $k listed turns must not demote")
        }
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state, "K-1 listed turns is below the threshold")
        assertEquals(k - 1, g.entry("a")?.observedTurns)

        g.observeTurn(listOf("a"))
        assertEquals(listOf("a"), g.catalog(listOf("a")))
        assertEquals(listOf("a"), g.endTurn().demoted, "the K-th listed turn is the one that demotes")
        assertEquals(LibraryState.DORMANT, g.entry("a")?.state)
    }

    @Test fun `two catalog calls in one turn count as one observation`() {
        val g = LibraryGovernor()
        g.observeTurn(listOf("a"))
        assertEquals(0, g.entry("a")?.observedTurns, "creation is not an observation")
        assertEquals(listOf("a"), g.catalog(listOf("a")))
        assertEquals(listOf("a"), g.catalog(listOf("a")))
        assertEquals(1, g.entry("a")?.observedTurns, "two requests in one turn are one observation")
    }

    @Test fun `fewer observations than the minimum change nothing`() {
        val g = LibraryGovernor()
        repeat(LibraryGovernor.MIN_OBSERVED_TURNS - 1) {
            g.observeTurn(listOf("a"))
            g.catalog(listOf("a"))
            assertTrue(g.endTurn().isEmpty)
        }
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state)
    }

    @Test fun `a listed entry still drifts normally`() {
        // The positive side of the rule. It is driven through catalog() because drift is now evidence of "listed and
        // not pulled" only: a turn that lists nothing no longer advances anything.
        val g = LibraryGovernor()
        val k = LibraryGovernor.MIN_OBSERVED_TURNS
        repeat(k) {
            g.observeTurn(listOf("a"))
            assertEquals(listOf("a"), g.catalog(listOf("a")))
            g.endTurn()
        }
        assertEquals(LibraryState.DORMANT, g.entry("a")?.state)      // forward side
        repeat(k) {
            g.observeTurn(listOf("a"))
            g.catalog(listOf("a"))                                   // it was offered before it went dormant
            g.endTurn()
        }
        assertEquals(LibraryState.RETIRED, g.entry("a")?.state)
    }

    @Test fun `a cap-demoted entry is not retired while it is not listed`() {
        // The defect ruling 11 removes: the cap pushes the overflow out of the catalog to DORMANT, but the entries
        // stay enabled and advertised. They are never listed, so the model never sees their names and can never pull
        // them by name; drift evidence against them would be manufactured from a period in which nobody was offered
        // them at all. They must stay DORMANT — and pullable — well past the retirement horizon.
        val g = LibraryGovernor()
        val cap = LibraryGovernor.MAX_CATALOG_ENTRIES
        val ids = (1..cap + 2).map { "s%02d".format(it) }
        val demoted = ids.sorted().drop(cap)
        val horizon = 2 * LibraryGovernor.MIN_OBSERVED_TURNS

        repeat(horizon * 2) {
            g.observeTurn(ids)
            val listed = g.catalog(ids)
            assertTrue(demoted.none { it in listed }, "the cap-demoted entries must stay unlisted")
            assertTrue(g.endTurn().retired.none { it in demoted }, "an entry the model was never shown must not retire")
        }

        demoted.forEach { id ->
            assertEquals(LibraryState.DORMANT, g.entry(id)?.state, "$id must never leave DORMANT while unlisted")
        }
        // Still reachable by name: D-6 demotes, it never retires, and a pull restores it (design D-4's second gate).
        val rescued = demoted.first()
        g.recordPull(rescued)
        assertEquals(LibraryState.ACTIVE, g.entry(rescued)?.state)
    }

    @Test fun `a pulled entry still never advances`() {
        // The D-4 protection is untouched by ruling 11: a pull freezes drift for the rest of the process, so even
        // though the entry is listed on every turn below it never moves.
        val g = LibraryGovernor()
        val k = LibraryGovernor.MIN_OBSERVED_TURNS
        g.observeTurn(listOf("a"))
        assertEquals(listOf("a"), g.catalog(listOf("a")))
        g.endTurn()
        g.recordPull("a")                                            // the wearer used it, early

        repeat(k * 3 + 1) {
            g.observeTurn(listOf("a"))
            assertTrue("a" in g.catalog(listOf("a")), "a used entry must stay in the catalog")
            assertTrue(g.endTurn().isEmpty, "pulls > 0 must leave the drift counter frozen")
        }
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state)
        assertEquals(1, g.entry("a")?.pulls)
    }

    @Test fun `a turn with no catalog request advances nothing`() {
        // The deliberate consequence of counting drift only for listed entries: a turn that never asks for the
        // catalog lists nothing, so nothing advances — not even an entry that was already dormant. Production makes at
        // least one request per turn, so this under-counts only turns that did no work at all.
        val g = LibraryGovernor()
        val k = LibraryGovernor.MIN_OBSERVED_TURNS
        repeat(k) { g.observeTurn(listOf("a")); g.catalog(listOf("a")); g.endTurn() }
        assertEquals(LibraryState.DORMANT, g.entry("a")?.state)

        repeat(2 * k) {
            g.observeTurn(listOf("a"))                               // advertised, but no catalog request this turn
            assertTrue(g.endTurn().isEmpty, "no catalog request this turn means no drift evidence")
        }
        assertEquals(LibraryState.DORMANT, g.entry("a")?.state)
    }

    @Test fun `COUNTER-EXAMPLE a pulled entry is never demoted however many quiet turns follow`() {
        val g = LibraryGovernor()
        val k = LibraryGovernor.MIN_OBSERVED_TURNS
        g.observeTurn(listOf("a"))
        g.catalog(listOf("a"))
        g.endTurn()
        g.recordPull("a")                                            // the wearer used it, early
        val quiet = k * 3 + 1
        repeat(quiet) {
            g.observeTurn(listOf("a"))
            g.catalog(listOf("a"))
            assertTrue(g.endTurn().isEmpty, "must not demote a used entry")
        }
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state)
        assertTrue("a" in g.catalog(listOf("a")), "a used entry must stay in the catalog")
    }

    @Test fun `COUNTER-EXAMPLE a pull rescues an entry that had already drifted to DORMANT`() {
        val g = LibraryGovernor()
        val k = LibraryGovernor.MIN_OBSERVED_TURNS
        repeat(k) { g.observeTurn(listOf("a")); g.catalog(listOf("a")); g.endTurn() }
        assertEquals(LibraryState.DORMANT, g.entry("a")?.state)
        g.recordPull("a")                                            // used again -> back in play
        repeat(k * 3 + 1) {
            g.observeTurn(listOf("a"))
            g.catalog(listOf("a"))
            assertTrue(g.endTurn().isEmpty)
        }
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state)
    }

    @Test fun `the freeze is derived from pulls and not from separate state`() {
        // A governor whose demotion threshold is 1, so any drift advance shows up in the very next verdict.
        val g = LibraryGovernor(minObservedTurns = 1)
        g.observeTurn(listOf("a"))
        g.catalog(listOf("a"))
        g.endTurn()
        g.recordPull("a")
        repeat(2) {
            g.observeTurn(listOf("a"))
            g.catalog(listOf("a"))
            assertTrue(g.endTurn().isEmpty, "pulls > 0 must freeze the drift")
        }
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state)
        // The entry is exactly the four public fields; there is no fifth field that could disagree with pulls > 0.
        assertEquals(LibraryEntry("a", LibraryState.ACTIVE, 0, 1), g.entry("a"))
    }

    @Test fun `a pull on a RETIRED entry brings it back to ACTIVE`() {
        val g = LibraryGovernor()
        repeat(LibraryGovernor.MIN_OBSERVED_TURNS * 2) {
            g.observeTurn(listOf("a"))
            g.catalog(listOf("a"))
            g.endTurn()
        }
        assertEquals(LibraryState.RETIRED, g.entry("a")?.state)
        g.recordPull("a")
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state)
    }

    @Test fun `exceeding the cap demotes the later names lexicographically and retires nothing`() {
        val g = LibraryGovernor()
        val ids = (1..LibraryGovernor.MAX_CATALOG_ENTRIES + 2).map { "s%02d".format(it) }
        g.observeTurn(ids)
        val verdict = g.endTurn()
        val expectedDemoted = ids.sorted().drop(LibraryGovernor.MAX_CATALOG_ENTRIES)
        assertEquals(expectedDemoted, verdict.demoted)
        assertTrue(verdict.retired.isEmpty())
        expectedDemoted.forEach { assertEquals(LibraryState.DORMANT, g.entry(it)?.state) }
    }

    @Test fun `a cap-demoted entry is never observed`() {
        // Counter-example to adding the cap-demoted ids to the counted set: they were never listed, so the model never
        // saw their names and the turn is not evidence about them (rulings 11/14). A mutation that counts them makes
        // this assertion fail.
        val g = LibraryGovernor()
        val cap = LibraryGovernor.MAX_CATALOG_ENTRIES
        val ids = (1..cap + 2).map { "s%02d".format(it) }
        val demoted = ids.sorted().drop(cap)
        g.observeTurn(ids)
        val listed = g.catalog(ids)
        assertEquals(cap, listed.size)
        demoted.forEach { id ->
            assertEquals(0, g.entry(id)?.observedTurns, "$id was cap-demoted before it was ever listed")
        }
        assertEquals(1, g.entry(listed.first())?.observedTurns, "a listed entry still earns its observation")
    }

    @Test fun `neverPulledCount counts entries seen in a catalog but never pulled`() {
        val g = LibraryGovernor()
        g.observeTurn(listOf("a", "b"))
        g.endTurn()
        g.catalog(listOf("a", "b"))                                  // both entries reach the model
        g.recordPull("a")
        assertEquals(1, g.neverPulledCount())
    }

    @Test fun `an entry demoted by the cap before ever being listed is not counted as never-pulled`() {
        val g = LibraryGovernor()
        val cap = LibraryGovernor.MAX_CATALOG_ENTRIES
        val ids = (1..cap + 2).map { "s%02d".format(it) }
        g.observeTurn(ids)
        g.endTurn()
        val listed = g.catalog(ids)                                  // the overflow is demoted before it is ever listed
        val demotedBeforeListing = ids.sorted().drop(cap)
        assertEquals(cap, listed.size)
        assertTrue(demotedBeforeListing.none { it in listed }, "the overflow must not be listed")
        assertEquals(cap, g.neverPulledCount(), "only listed entries count as never-pulled")
        g.recordPull("s01")                                          // pulling still subtracts from the listed set
        assertEquals(cap - 1, g.neverPulledCount())
    }

    @Test fun `an overflow consumed by catalog is still reported in that turn's endTurn verdict`() {
        val g = LibraryGovernor()
        val cap = LibraryGovernor.MAX_CATALOG_ENTRIES
        val ids = (1..cap + 2).map { "s%02d".format(it) }
        val expectedDemoted = ids.sorted().drop(cap)
        g.observeTurn(ids)
        val listed = g.catalog(ids)                                  // the request that overflows the catalog
        assertEquals(cap, listed.size)
        val verdict = g.endTurn()                                    // the turn that caused it
        assertEquals(expectedDemoted, verdict.demoted, "an overflow must not be silent")
        assertEquals(cap, g.catalog(ids).size, "catalog is idempotent")
        assertTrue(g.endTurn().demoted.isEmpty(), "endTurn is idempotent")
    }

    @Test fun `pullsThisTurn still reports the completed turn after endTurn`() {
        val g = LibraryGovernor()
        g.observeTurn(listOf("a"))
        g.recordPull("a")
        val verdict = g.endTurn()
        assertTrue(verdict.isEmpty)
        assertEquals(1, g.pullsThisTurn, "the design reads pullsThisTurn at turn end")
        g.observeTurn(listOf("a"))                                   // the next turn opens
        assertEquals(0, g.pullsThisTurn)
        assertEquals(1, g.pullsThisSession, "the session count stays monotonic")
    }

    @Test fun `catalog caps over its advertised set and never demotes an absent entry`() {
        // `ghost` was ACTIVE last turn and is no longer advertised (disabled or deleted after it was observed). The cap
        // must ignore it: counting every known ACTIVE entry would demote the ghost and, at the bound, push a genuinely
        // advertised entry out of the catalog.
        val g = LibraryGovernor(maxCatalogEntries = 2)
        g.observeTurn(listOf("a", "ghost"))
        g.endTurn()
        g.observeTurn(listOf("a", "b"))

        val listed = g.catalog(listOf("a", "b"))
        assertEquals(listOf("a", "b"), listed)
        assertEquals(LibraryState.ACTIVE, g.entry("ghost")?.state, "an unadvertised entry is left alone, not capped")
    }

    @Test fun `endTurn caps over the entries seen this turn and reports nothing for an absent one`() {
        val g = LibraryGovernor(maxCatalogEntries = 2)
        g.observeTurn(listOf("a", "ghost"))
        g.endTurn()
        g.observeTurn(listOf("a", "b"))

        val verdict = g.endTurn()
        assertTrue(verdict.demoted.isEmpty(), "an entry that was not advertised cannot overflow the catalog")
        assertEquals(LibraryState.ACTIVE, g.entry("ghost")?.state)
    }
}
