package com.sheen.rokid.phone.plugin.smarthelper.skill

import com.sheen.rokid.plugin.StorageAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The bridge from the host's `Capability.StorageAccess` grant to the manager's [SkillDirectory] port.
 *
 * It exists so that exactly one place knows the on-device path and the file suffix, and so the manager can be
 * tested without a file system.
 */
class StorageAccessSkillDirectoryTest {

    private class RecordingAccess(
        private val files: Map<String, String> = emptyMap(),
        val listed: MutableList<Pair<String, String>> = mutableListOf(),
        val read: MutableList<String> = mutableListOf(),
    ) : StorageAccess {
        override fun listFiles(dirPath: String, suffix: String): List<String> {
            listed += dirPath to suffix
            return files.keys.toList()
        }

        override fun readFile(path: String): String? {
            read += path
            return files[path]
        }

        override fun deleteFile(path: String): Boolean = false
    }

    @Test
    fun `it lists markdown files from the directory the spec names`() {
        val access = RecordingAccess(mapOf("/sdcard/EDITH/skills/a.md" to "x"))

        val paths = StorageAccessSkillDirectory(access).list()

        assertEquals(listOf("/sdcard/EDITH/skills/a.md"), paths)
        assertEquals(listOf(StorageAccessSkillDirectory.SKILL_DIRECTORY to ".md"), access.listed)
        assertEquals("/sdcard/EDITH/skills", StorageAccessSkillDirectory.SKILL_DIRECTORY)
    }

    @Test
    fun `it reads a path straight through`() {
        val access = RecordingAccess(mapOf("/sdcard/EDITH/skills/a.md" to "body"))

        assertEquals("body", StorageAccessSkillDirectory(access).read("/sdcard/EDITH/skills/a.md"))
        assertEquals(listOf("/sdcard/EDITH/skills/a.md"), access.read)
    }

    @Test
    fun `a missing directory yields nothing rather than throwing`() {
        // A host with no SD card, or a user who has installed no SKILLs, must not turn into a plugin failure
        // (spec §3.7: an empty directory simply means no SKILLs).
        val access = RecordingAccess()

        assertEquals(emptyList(), StorageAccessSkillDirectory(access).list())
        assertNull(StorageAccessSkillDirectory(access).read("/sdcard/EDITH/skills/gone.md"))
    }
}
