package com.sheen.rokid.phone.plugin.smarthelper.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The SKILL library's own observability (edith-0.2 B4, and spec §3.7's "an empty directory is normal").
 *
 * The load path used to log **only** a duplicate or a skip, so a successful read left no evidence at all: on a device
 * log "the SKILL directory was read and one SKILL is being injected" and "the directory was missing" were the same
 * thing — nothing. That is the difference the device half of B4 has to be able to read, so the counts are logged, and
 * the test pins both halves: the counts appear, and **no SKILL body ever does**.
 */
class SkillManagerLogTest {

    private class FakeDirectory(private val files: Map<String, String>) : SkillDirectory {
        override fun list(): List<String> = files.keys.sorted()
        override fun read(path: String): String? = files[path]
    }

    private fun document(name: String, body: String) = """
        ---
        name: $name
        version: 1.0.0
        description: 测试用 SKILL
        enabled: true
        ---

        $body
    """.trimIndent()

    @Test
    fun `a successful load reports how many files it read and how many are injected`() {
        val lines = mutableListOf<String>()
        val marker = "正文标记-9182"
        val manager = SkillManager(
            directory = FakeDirectory(
                mapOf(
                    "/sdcard/EDITH/skills/one.md" to document("one", "# 一${marker}"),
                    "/sdcard/EDITH/skills/two.md" to document("two", "# 二"),
                ),
            ),
            log = { lines += it },
        )

        val injected = manager.enabled()

        assertEquals(2, injected.size)
        assertTrue(
            lines.any { it == "P3_SKILL load files=2 skills=2 enabled=2" },
            "a successful load must be visible in the device log: $lines",
        )
        assertTrue(
            lines.none { it.contains(marker) },
            "a SKILL body is the user's own text and must never reach a log line: $lines",
        )
    }

    @Test
    fun `an empty directory reports zero rather than saying nothing`() {
        val lines = mutableListOf<String>()
        val manager = SkillManager(directory = FakeDirectory(emptyMap()), log = { lines += it })

        assertEquals(emptyList(), manager.enabled())
        // "No SKILLs installed" is a normal state (spec §3.7) - and it is a *measured* one only if it is reported.
        assertTrue(
            lines.any { it == "P3_SKILL load files=0 skills=0 enabled=0" },
            "an empty library must still say so: $lines",
        )
    }

    @Test
    fun `an invalid file is counted as read but not as a SKILL`() {
        val lines = mutableListOf<String>()
        val manager = SkillManager(
            directory = FakeDirectory(
                mapOf(
                    "/sdcard/EDITH/skills/good.md" to document("good", "# 好"),
                    "/sdcard/EDITH/skills/bad.md" to "# 没有 front matter",
                ),
            ),
            log = { lines += it },
        )

        assertEquals(1, manager.enabled().size)
        assertTrue(
            lines.any { it == "P3_SKILL load files=2 skills=1 enabled=1" },
            "the pair (read, parsed) is what distinguishes a bad file from an empty directory: $lines",
        )
        assertTrue(lines.any { it.startsWith("P3_SKILL skip reason=") }, lines.toString())
    }
}
