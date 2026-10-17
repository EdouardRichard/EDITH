package edith.plugins.smart.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryGovernorTest {

    @Test fun `an entry is created ACTIVE on its first sighting`() {
        val g = LibraryGovernor()
        g.observeTurn(listOf("a"))
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state)
        assertEquals(1, g.entry("a")?.observedTurns)
    }

    @Test fun `two observeTurn calls in one turn count one observation`() {
        val g = LibraryGovernor()
        g.observeTurn(listOf("a"))
        g.observeTurn(listOf("a"))                                   // same turn, rebuilt catalog
        assertEquals(1, g.entry("a")?.observedTurns)
        assertTrue(g.endTurn().isEmpty, "one observation must be below the minimum")
    }

    @Test fun `fewer observations than the minimum change nothing`() {
        val g = LibraryGovernor()
        repeat(LibraryGovernor.MIN_OBSERVED_TURNS - 1) { g.observeTurn(listOf("a")); assertTrue(g.endTurn().isEmpty) }
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state)
    }

    @Test fun `an entry with no pulls is demoted after the minimum then retired after twice the minimum`() {
        val g = LibraryGovernor()
        val k = LibraryGovernor.MIN_OBSERVED_TURNS
        repeat(k) { g.observeTurn(listOf("a")); g.endTurn() }
        assertEquals(LibraryState.DORMANT, g.entry("a")?.state)      // forward side
        repeat(k) { g.observeTurn(listOf("a")); g.endTurn() }
        assertEquals(LibraryState.RETIRED, g.entry("a")?.state)
    }

    @Test fun `COUNTER-EXAMPLE a pulled entry is never demoted however many quiet turns follow`() {
        val g = LibraryGovernor()
        val k = LibraryGovernor.MIN_OBSERVED_TURNS
        g.observeTurn(listOf("a"))
        g.endTurn()
        g.recordPull("a")                                            // the wearer used it, early
        val quiet = k * 3 + 1
        repeat(quiet) { g.observeTurn(listOf("a")); assertTrue(g.endTurn().isEmpty, "must not demote a used entry") }
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state)
        assertTrue("a" in g.catalog(listOf("a")), "a used entry must stay in the catalog")
    }

    @Test fun `COUNTER-EXAMPLE a pull rescues an entry that had already drifted to DORMANT`() {
        val g = LibraryGovernor()
        val k = LibraryGovernor.MIN_OBSERVED_TURNS
        repeat(k) { g.observeTurn(listOf("a")); g.endTurn() }
        assertEquals(LibraryState.DORMANT, g.entry("a")?.state)
        g.recordPull("a")                                            // used again -> back in play
        repeat(k * 3 + 1) { g.observeTurn(listOf("a")); assertTrue(g.endTurn().isEmpty) }
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state)
    }

    @Test fun `the freeze is derived from pulls and not from separate state`() {
        // A governor whose demotion threshold is 1, so any drift advance shows up in the very next verdict.
        val g = LibraryGovernor(minObservedTurns = 1)
        g.observeTurn(listOf("a"))
        g.endTurn()
        g.recordPull("a")
        repeat(2) { g.observeTurn(listOf("a")); assertTrue(g.endTurn().isEmpty, "pulls > 0 must freeze the drift") }
        assertEquals(LibraryState.ACTIVE, g.entry("a")?.state)
        // The entry is exactly the four public fields; there is no fifth field that could disagree with pulls > 0.
        assertEquals(LibraryEntry("a", LibraryState.ACTIVE, 0, 1), g.entry("a"))
    }

    @Test fun `a pull on a RETIRED entry brings it back to ACTIVE`() {
        val g = LibraryGovernor()
        repeat(LibraryGovernor.MIN_OBSERVED_TURNS * 2) { g.observeTurn(listOf("a")); g.endTurn() }
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
