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
        g.recordPull("a")
        assertEquals(1, g.neverPulledCount())
    }
}
