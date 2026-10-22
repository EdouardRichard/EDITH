package com.sheen.rokid.phone.plugin.smarthelper.host.ui

import com.sheen.rokid.plugin.StorageAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase C (spec §6.7 技能页签): the SKILL directory as the page sees it.
 *
 * The case that matters is the one B4 lost a day to: **a refused grant and an empty directory both produce an empty
 * listing**, and "the user has installed no SKILLs" was read from a state that really meant "the App cannot see the
 * directory". The two are separated here, and a name is never turned into a path without being checked first.
 */
class SkillDirectoryViewTest {

    private class FakeStorage(
        private val files: MutableList<String> = mutableListOf(),
        private val suffixAnswer: List<String>? = null,
    ) : StorageAccess {
        val deleted = mutableListOf<String>()
        val reads = mutableListOf<String>()
        var content: String? = null
        override fun listFiles(dirPath: String, suffix: String): List<String> =
            (suffixAnswer ?: files).toList()
        override fun readFile(path: String): String? {
            reads += path
            return content
        }
        override fun deleteFile(path: String): Boolean {
            deleted += path
            return true
        }
    }

    @Test
    fun theNamesOfTheListingAreOnlyMarkdownFiles() {
        // A directory holds whatever the user put there; the parser reads Markdown, so nothing else is a SKILL.
        assertEquals(listOf("a.md", "b.md"), skillNames(listOf("/sdcard/EDITH/skills/b.md", "/x/notes.txt", "/x/a.md")))
        assertEquals(emptyList<String>(), skillNames(emptyList()))
    }

    @Test
    fun aRefusedGrantAndAnEmptyDirectoryAreDistinguishable() {
        val empty = StorageAccessSkillView(FakeStorage(), sharedStorageGranted = { true })
        assertEquals(SkillListing.Listed(emptyList()), empty.list())

        val refused = StorageAccessSkillView(FakeStorage(), sharedStorageGranted = { false })
        assertEquals(SkillListing.Unreadable, refused.list())

        val noService = StorageAccessSkillView(null, sharedStorageGranted = { true })
        assertEquals(SkillListing.Unreadable, noService.list())
    }

    @Test
    fun aListingUnderTheRightGrantIsReportedWithItsFiles() {
        val view = StorageAccessSkillView(
            FakeStorage(mutableListOf("/sdcard/EDITH/skills/device-task-discipline.md")),
            sharedStorageGranted = { true },
        )
        assertEquals(SkillListing.Listed(listOf("device-task-discipline.md")), view.list())
    }

    @Test
    fun onlyAMarkdownNameWithoutASeparatorMayBecomeAPath() {
        assertTrue(deletable("a.md"))
        assertFalse("a parent reference is not a SKILL name", deletable("../secrets.md"))
        assertFalse("a nested path is not a name", deletable("sub/a.md"))
        assertFalse("a windows separator is not a name", deletable("sub\\a.md"))
        assertFalse(deletable("a.txt"))
        assertFalse(deletable(""))
        assertFalse(deletable("."))
        assertFalse(deletable(".."))
    }

    @Test
    fun deletingBuildsThePathUnderTheSkillsDirectoryAndRefusesAnythingElse() {
        val storage = FakeStorage()
        val view = StorageAccessSkillView(storage, sharedStorageGranted = { true })

        assertTrue(view.delete("a.md"))
        assertEquals(listOf("/sdcard/EDITH/skills/a.md"), storage.deleted)
        assertFalse(view.delete("../secrets.md"))
        assertEquals("a refused name must never reach the filesystem", 1, storage.deleted.size)
    }

    @Test
    fun deletingUnderARefusedGrantTouchesNothing() {
        val storage = FakeStorage()
        val view = StorageAccessSkillView(storage, sharedStorageGranted = { false })

        assertFalse(view.delete("a.md"))
        assertTrue(storage.deleted.isEmpty())
    }

    @Test
    fun aValidNameIsReadAsItsFullText() {
        val storage = FakeStorage().apply { content = "# 设备任务纪律\n先读设备，再写代码。" }
        val view = StorageAccessSkillView(storage, sharedStorageGranted = { true })

        assertEquals(SkillContent.Text("a.md", "# 设备任务纪律\n先读设备，再写代码。"), view.read("a.md"))
        assertEquals(listOf("/sdcard/EDITH/skills/a.md"), storage.reads)
    }

    @Test
    fun anUnsafeNameNeverReachesTheFilesystem() {
        val storage = FakeStorage()
        val view = StorageAccessSkillView(storage, sharedStorageGranted = { true })

        listOf("../secrets.md", "sub/a.md", "sub\\a.md", ".", "..", "a.txt", "", "   ").forEach {
            assertEquals("refused: $it", SkillContent.Unreadable, view.read(it))
        }
        assertTrue("readFile must not be called for any unsafe name", storage.reads.isEmpty())
    }

    @Test
    fun unreadableEmptyAndRefusedAreDistinguished() {
        val missing = FakeStorage().apply { content = null }
        assertEquals(SkillContent.Unreadable, StorageAccessSkillView(missing, { true }).read("a.md"))

        val empty = FakeStorage().apply { content = "  \n" }
        assertEquals(SkillContent.Empty, StorageAccessSkillView(empty, { true }).read("a.md"))

        val refused = FakeStorage().apply { content = "x" }
        assertEquals(SkillContent.Unreadable, StorageAccessSkillView(refused, { false }).read("a.md"))
        assertTrue("a refused grant must not read", refused.reads.isEmpty())

        assertEquals(SkillContent.Unreadable, StorageAccessSkillView(null, { true }).read("a.md"))
    }

    @Test
    fun aLongBodyIsReturnedWhole() {
        val body = "技能全文😀\n".repeat(500) + "END-OF-SKILL"
        val storage = FakeStorage().apply { content = body }
        val view = StorageAccessSkillView(storage, sharedStorageGranted = { true })

        assertEquals(SkillContent.Text("long.md", body), view.read("long.md"))
    }
}
