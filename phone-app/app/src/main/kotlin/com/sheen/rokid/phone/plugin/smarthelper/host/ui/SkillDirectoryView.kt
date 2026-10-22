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

/**
 * What the detail page gets back for one SKILL name (U-3).
 *
 * The three cases are the ones a reader acts on differently: a refused read and an empty file both look like "nothing
 * here", but only one of them is the user's own doing. [Text] carries the **whole** UTF-8 body - the page shows what a
 * turn would load, so truncating here would show a sentence the file does not contain.
 */
internal sealed interface SkillContent {
    /** Refused, missing, or unreadable: no grant, no storage service, a missing file, or an unsafe name. */
    data object Unreadable : SkillContent

    /** The file was read and holds nothing but whitespace. */
    data object Empty : SkillContent

    /** The complete body. [name] is the name the page asked for, not a resolved path. */
    data class Text(val name: String, val body: String) : SkillContent
}

/** The SKILL files in a listing, in a stable order. Only `.md` counts — the parser reads Markdown. */
internal fun skillNames(entries: List<String>): List<String> =
    entries.map { it.substringAfterLast('/') }.filter { it.endsWith(".md") }.sorted()

/**
 * Whether a name may be turned into a path under [SKILLS_DIR].
 *
 * The page's names come from the directory listing, but the value still reaches `deleteFile`/`readFile` as a **path**,
 * so a name carrying a separator or a parent reference is refused here rather than resolved. This is the one place in
 * the UI where a user-visible string becomes a file path, and both verbs use it so they cannot drift apart.
 */
internal fun safeSkillName(name: String): Boolean =
    name.isNotBlank() && name.endsWith(".md") && '/' !in name && '\\' !in name && name != "." && name != ".."

/** Whether a name may be deleted. Kept as the delete verb's own name; the path rule itself is [safeSkillName]. */
internal fun deletable(name: String): Boolean = safeSkillName(name)

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

    override fun read(name: String): SkillContent {
        // The name is checked before anything else, so a refused name can never reach `readFile` (U-3).
        if (!safeSkillName(name)) return SkillContent.Unreadable
        val access = storage ?: return SkillContent.Unreadable
        if (!sharedStorageGranted()) return SkillContent.Unreadable
        val body = access.readFile("$SKILLS_DIR/$name") ?: return SkillContent.Unreadable
        return if (body.isBlank()) SkillContent.Empty else SkillContent.Text(name, body)
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
    fun read(name: String): SkillContent
    fun delete(name: String): Boolean
}
