package edith.plugins.smart.skill

import edith.plugins.smart.library.LibraryGovernor
import edith.plugins.smart.library.LibraryState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The catalog half of the two-layer SKILL injection (Phase 6, design D-1/D-4/D-6/D-10).
 *
 * The catalog text lists `name` + `description` only, so the system frame is bounded while the bodies stay behind
 * [SkillManager.load]. A dormant entry (drift-demoted, or pushed out by the cap) is still reachable by name — that is
 * design D-4's second gate, the protection against a full catalog silently retiring an entry the wearer can no longer
 * pull. The body never appears in the catalog, and the bookkeeping counts are read back through the manager.
 */
class SkillCatalogTest {

    private class FakeDirectory(private val files: Map<String, String>) : SkillDirectory {
        override fun list(): List<String> = files.keys.toList()
        override fun read(path: String): String? = files[path]
    }

    private fun doc(name: String, body: String = "body of $name", enabled: Boolean = true) =
        "---\nname: $name\nversion: 1.0.0\ndescription: d-$name\nenabled: $enabled\n---\n$body\n"

    @Test fun `the catalog lists name and description and never the body`() {
        val text = catalogFor(listOf(
            Skill("alpha", "1", "d-alpha", body = "SECRET-BODY-A"),
            Skill("beta", "1", "d-beta", body = "SECRET-BODY-B"),
        ))
        assertTrue(text.contains("- alpha: d-alpha"))
        assertTrue(text.contains("- beta: d-beta"))
        assertTrue("SECRET-BODY-A" !in text && "SECRET-BODY-B" !in text)
    }

    @Test fun `an empty library produces no catalog text at all`() {
        assertEquals("", catalogFor(emptyList()))
    }

    @Test fun `the catalog is sorted by name`() {
        // The brief's line is `Skill("zeta", "1", "z")`, which does not compile: `body` has no default. The two
        // assertions are the brief's verbatim; only the missing required argument was supplied.
        val text = catalogFor(listOf(
            Skill("zeta", "1", "z", body = "z-body"),
            Skill("alpha", "1", "a", body = "a-body"),
        ))
        assertTrue(text.indexOf("alpha") < text.indexOf("zeta"))
    }

    @Test fun `a duplicated name appears once and the first sorted path wins`() {
        val manager = SkillManager(FakeDirectory(mapOf(
            "b.md" to doc("dup", body = "FROM-B"),
            "a.md" to doc("dup", body = "FROM-A"),
        )))
        assertEquals(listOf("dup"), manager.all().map { it.name })
        assertTrue((manager.load("dup") as SkillLoad.Loaded).skill.body == "FROM-A")
    }

    @Test fun `a disabled skill is not in the catalog and reports DISABLED on pull`() {
        val manager = SkillManager(FakeDirectory(mapOf("a.md" to doc("off", enabled = false))))
        assertEquals("", manager.catalog())
        assertEquals(SkillLoadReason.DISABLED, (manager.load("off") as SkillLoad.NotFound).reason)
    }

    @Test fun `an unknown name reports NOT_IN_LIBRARY`() {
        val manager = SkillManager(FakeDirectory(mapOf("a.md" to doc("a"))))
        assertEquals(SkillLoadReason.NOT_IN_LIBRARY, (manager.load("nope") as SkillLoad.NotFound).reason)
    }

    @Test fun `a dormant entry is still pullable and reports wasDormant`() {
        val governor = LibraryGovernor()
        val manager = SkillManager(FakeDirectory(mapOf("a.md" to doc("a"))), governor = governor)
        val k = LibraryGovernor.MIN_OBSERVED_TURNS
        repeat(k) { manager.beginTurn(); manager.endTurn() }
        assertEquals(LibraryState.DORMANT, governor.entry("a")?.state)
        val loaded = manager.load("a") as SkillLoad.Loaded
        assertTrue(loaded.wasDormant)
        assertEquals(LibraryState.ACTIVE, governor.entry("a")?.state)
    }

    @Test fun `beyond the cap the catalog is bounded and the overflow is dormant`() {
        val files = (1..LibraryGovernor.MAX_CATALOG_ENTRIES + 2).associate { "s%02d.md".format(it) to doc("s%02d".format(it)) }
        val governor = LibraryGovernor()
        val manager = SkillManager(FakeDirectory(files), governor = governor)
        manager.beginTurn()
        val verdict = manager.endTurn()
        // The brief's line here is `assertEquals(MAX_CATALOG_ENTRIES, verdict.demoted.size)`, which is arithmetically
        // impossible: 12 is the cap, and it is the *overflow* beyond it (14 - 12 = 2) that is demoted. The assertion
        // therefore pins the measured overflow while keeping this test's criterion ("the overflow is dormant").
        assertEquals(files.size - LibraryGovernor.MAX_CATALOG_ENTRIES, verdict.demoted.size)
        val listed = manager.catalog().lines().filter { it.startsWith("- ") }.size
        assertEquals(LibraryGovernor.MAX_CATALOG_ENTRIES, listed)
    }

    @Test fun `an absent directory yields an empty catalog, not a header with nothing under it`() {
        val manager = SkillManager(FakeDirectory(emptyMap()))
        assertEquals("", manager.catalog())
        assertTrue(manager.all().isEmpty())
    }

    @Test fun `the catalog line reports measured counts and repeats only when they change`() {
        // D-10 fixes the line's shape and requires it to be assertable verbatim; the three numbers must be measured,
        // not guessed: `listed` is what the text actually carries, `bounded` is the cap demotion this call performed,
        // and `dormant` is the library-wide dormant total afterwards.
        val lines = mutableListOf<String>()
        val cap = LibraryGovernor.MAX_CATALOG_ENTRIES
        val overflow = 2
        val files = (1..cap + overflow).associate { "s%02d.md".format(it) to doc("s%02d".format(it)) }
        val governor = LibraryGovernor()
        val manager = SkillManager(FakeDirectory(files), log = { lines += it }, governor = governor)

        manager.beginTurn()                                       // nothing demoted yet
        manager.catalog()                                         // this request overflows the catalog
        assertTrue(
            "P3_SKILL catalog listed=$cap bounded=$overflow dormant=$overflow" in lines,
            "the overflow must be counted, not guessed: $lines",
        )

        lines.clear()
        manager.catalog()                                         // the cap already applied: bounded drops to 0
        assertTrue("P3_SKILL catalog listed=$cap bounded=0 dormant=$overflow" in lines, lines.toString())

        lines.clear()
        manager.catalog()                                         // same numbers -> no repeated line
        assertTrue(lines.none { it.startsWith("P3_SKILL catalog ") }, lines.toString())
    }

    @Test fun `turn bookkeeping delegates to the governor and reads zero without one`() {
        val governor = LibraryGovernor()
        val manager = SkillManager(FakeDirectory(mapOf("a.md" to doc("a"))), governor = governor)
        manager.beginTurn()
        manager.catalog()
        manager.load("a")

        assertEquals(1, manager.pullsThisTurn)
        assertEquals(1, manager.pullsThisSession)
        assertEquals(0, manager.neverPulledCount(), "the only listed entry was pulled")
        manager.endTurn()
        assertEquals(1, manager.pullsThisTurn, "the design reads the turn count after endTurn")

        // With no governor the manager behaves exactly as before: every accessor reads zero and endTurn is quiet.
        val ungoverned = SkillManager(FakeDirectory(mapOf("a.md" to doc("a"))))
        ungoverned.beginTurn()
        ungoverned.catalog()
        ungoverned.load("a")
        assertEquals(0, ungoverned.pullsThisTurn)
        assertEquals(0, ungoverned.pullsThisSession)
        assertEquals(0, ungoverned.neverPulledCount())
        assertTrue(ungoverned.endTurn().isEmpty)
    }
}
