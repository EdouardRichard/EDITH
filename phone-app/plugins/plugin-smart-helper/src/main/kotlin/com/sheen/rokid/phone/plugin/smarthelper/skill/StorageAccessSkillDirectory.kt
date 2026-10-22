package com.sheen.rokid.phone.plugin.smarthelper.skill

import com.sheen.rokid.plugin.StorageAccess

/**
 * The one place that knows where the SKILL library lives on the device, and the bridge from the host's
 * `Capability.StorageAccess` grant to the manager's [SkillDirectory] port.
 *
 * The path and the suffix are constants rather than settings: spec §3.5 fixes `/sdcard/EDITH/skills/` and `.md`, and
 * making either configurable would mean a user could point the assistant at a directory no spec describes.
 */
class StorageAccessSkillDirectory(
    private val access: StorageAccess,
    private val directoryPath: String = SKILL_DIRECTORY,
    private val suffix: String = SUFFIX,
) : SkillDirectory {

    override fun list(): List<String> = access.listFiles(directoryPath, suffix)

    override fun read(path: String): String? = access.readFile(path)

    companion object {
        /** Spec §3.5. */
        const val SKILL_DIRECTORY = "/sdcard/EDITH/skills"

        /** Spec §3.5: a SKILL is a `.md` file, UTF-8. */
        const val SUFFIX = ".md"
    }
}
