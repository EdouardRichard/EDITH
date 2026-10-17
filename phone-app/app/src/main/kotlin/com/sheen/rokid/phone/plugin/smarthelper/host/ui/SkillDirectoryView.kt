package com.sheen.rokid.phone.plugin.smarthelper.host.ui
import com.sheen.rokid.plugin.StorageAccess

/** The user's SKILL directory (spec §3.5). The same path a turn reads, so the page shows what a turn would load. */
internal const val SKILLS_DIR = "/sdcard/EDITH/skills"

/** What the skills page has to say: the two empty-looking cases must stay apart (see [SkillDirectoryView.list]). */
internal sealed interface SkillListing {
    /** The directory cannot be read at all: no grant, or the app has no shared-storage permission. */
    data object Unreadable : SkillListing

    /** The directory was read; [names] may be empty, and that means the user has installed no SKILLs. */
    data class Listed(val names: List<String>) : SkillListing
}

/** The SKILL files in a listing, in a stable order. Only `.md` counts — the parser reads Markdown. */
internal fun skillNames(entries: List<String>): List<String> =
    entries.map { it.substringAfterLast('/') }.filter { it.endsWith(".md") }.sorted()

/**
 * Whether a name may be deleted.
 *
 * The page's names come from the directory listing, but the value still reaches `deleteFile` as a **path**, so a name
 * carrying a separator or a parent reference is refused here rather than resolved. This is the one place in the UI
 * where a user-visible string becomes a file path.
 */
internal fun deletable(name: String): Boolean =
    name.isNotBlank() && name.endsWith(".md") && '/' !in name && '\\' !in name && name != "." && name != ".."

/**
 * The skills page's data, over the same `Capability.StorageAccess` the plugin uses.
 *
 * **Why the two empty cases matter.** B4's device work was blocked by exactly this: a refused grant and an empty
 * directory both produce an empty listing, and "the user has installed no SKILLs" was read from a state that really
 * meant "the App cannot see the directory". `listFiles` returns an empty list in both cases, so the grant is asked
 * about separately.
 */
internal class StorageAccessSkillView(
    private val storage: StorageAccess?,
    private val sharedStorageGranted: () -> Boolean,
) : SkillDirectoryView {

    override fun list(): SkillListing {
        val access = storage ?: return SkillListing.Unreadable
        if (!sharedStorageGranted()) return SkillListing.Unreadable
        return SkillListing.Listed(skillNames(access.listFiles(SKILLS_DIR, ".md")))
    }

    override fun delete(name: String): Boolean {
        if (!deletable(name)) return false
        val access = storage ?: return false
        if (!sharedStorageGranted()) return false
        return access.deleteFile("$SKILLS_DIR/$name")
    }
}

/** What the skills page needs from the composition; a seam so the page is testable without one. */
internal interface SkillDirectoryView {
    fun list(): SkillListing
    fun delete(name: String): Boolean
}
