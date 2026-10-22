package com.sheen.rokid.phone.plugin.smarthelper.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Spec §3.6: every `enabled=true` SKILL body is injected **whole**, sorted by `name`, with **no scoring and no token
 * budget cap** (D60). Spec §3.5: the directory is re-read rather than watched, so a restart or a manual refresh is
 * what picks up an edit.
 */
class SkillManagerTest {

    private class FakeDirectory(private val files: Map<String, String>) : SkillDirectory {
        override fun list(): List<String> = files.keys.toList()
        override fun read(path: String): String? = files[path]
    }

    private fun doc(name: String, body: String = "body of $name", enabled: Boolean = true) =
        "---\nname: $name\nversion: 1.0.0\ndescription: d\nenabled: $enabled\n---\n$body\n"

    private fun manager(vararg files: Pair<String, String>, log: (String) -> Unit = {}) =
        SkillManager(FakeDirectory(files.toMap()), log = log)

    @Test
    fun `an empty directory yields no skills and does not throw`() {
        assertEquals(emptyList(), manager().enabled())
        assertEquals(emptyList(), manager().all())
    }

    @Test
    fun `a directory whose listing fails yields no skills and does not throw`() {
        // The real port answers an empty list for a directory that does not exist; this pins that the manager treats
        // "nothing there" as a normal state rather than a failure (spec §3.7).
        val manager = SkillManager(object : SkillDirectory {
            override fun list(): List<String> = emptyList()
            override fun read(path: String): String? = null
        })

        assertEquals(emptyList(), manager.enabled())
    }

    @Test
    fun `one broken file does not remove the others`() {
        val manager = manager(
            "a.md" to doc("good-one"),
            "b.md" to "no front matter at all",
            "c.md" to doc("good-two"),
        )

        assertEquals(listOf("good-one", "good-two"), manager.enabled().map { it.name })
    }

    @Test
    fun `a file that disappears between listing and reading is skipped`() {
        val manager = SkillManager(object : SkillDirectory {
            override fun list(): List<String> = listOf("gone.md", "here.md")
            override fun read(path: String): String? = if (path == "here.md") doc("here") else null
        })

        assertEquals(listOf("here"), manager.enabled().map { it.name })
    }

    @Test
    fun `only enabled skills are offered for injection`() {
        val manager = manager(
            "a.md" to doc("on", enabled = true),
            "b.md" to doc("off", enabled = false),
        )

        assertEquals(listOf("on"), manager.enabled().map { it.name })
        // `all` still reports it: the phone UI lists disabled skills so the user can see what is installed.
        assertEquals(listOf("off", "on"), manager.all().map { it.name })
    }

    @Test
    fun `skills come back sorted by name so a request is reproducible`() {
        val manager = manager(
            "b.md" to doc("zulu"),
            "a.md" to doc("alpha"),
            "c.md" to doc("mike"),
        )

        assertEquals(listOf("alpha", "mike", "zulu"), manager.enabled().map { it.name })
    }

    @Test
    fun `two files declaring the same name keep the first and report a duplicate`() {
        val reported = mutableListOf<String>()
        val manager = manager(
            "a.md" to doc("dup", body = "first body"),
            "b.md" to doc("dup", body = "second body"),
            log = { reported += it },
        )

        assertEquals(1, manager.enabled().size)
        assertEquals("first body", manager.enabled().single().body)
        assertTrue(
            reported.any { it.contains("duplicate") && it.contains("dup") },
            "the duplicate must be reported, was $reported",
        )
    }

    @Test
    fun `the duplicate report never carries the body`() {
        val reported = mutableListOf<String>()
        val manager = manager(
            "a.md" to doc("dup", body = "SECRET-BODY-MARKER"),
            "b.md" to doc("dup", body = "SECOND-MARKER"),
            log = { reported += it },
        )

        // Constructing the manager does no I/O; the directory is read when it is asked for skills.
        manager.enabled()

        // The property is "no SKILL body reaches the log", not "exactly one line is written": the load also reports
        // how many files it read and how many are injected, which is the only device-side evidence that this path ran.
        assertTrue("SECRET-BODY-MARKER" !in reported.joinToString("\n"), reported.toString())
        assertTrue("SECOND-MARKER" !in reported.joinToString("\n"), reported.toString())
        assertTrue(
            reported.any { it.contains("duplicate") },
            "the duplicate itself must still be reported: $reported",
        )
    }

    @Test
    fun `a body of any size is returned whole`() {
        // C9/D60: no token budget cap and no truncation at this layer. 200k characters is far past any sane SKILL,
        // which is the point - the manager must not be the thing that decides what is too big.
        val huge = "x".repeat(200_000)
        val manager = manager("a.md" to doc("big", body = huge))

        assertEquals(200_000, manager.enabled().single().body.length)
    }

    @Test
    fun `the directory is re-read on every call so an edit is picked up without a watcher`() {
        // Spec §3.5: 0.2 does no file watching, so "restart or refresh" is the only way an edit takes effect - which
        // requires that a call does not serve a cached list.
        var body = "first"
        val manager = SkillManager(object : SkillDirectory {
            override fun list(): List<String> = listOf("a.md")
            override fun read(path: String): String = doc("a", body = body)
        })

        assertEquals("first", manager.enabled().single().body)
        body = "second"
        assertEquals("second", manager.enabled().single().body)
    }
}
