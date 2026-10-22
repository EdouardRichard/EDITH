package com.sheen.rokid.host.storage

import com.sheen.rokid.plugin.StorageAccess
import java.io.File

/**
 * Provides safe read access to files on external storage (SD card).
 *
 * This is the implementation behind `Capability.StorageAccess`; the plugin-layer `SkillManager` reads
 * `/sdcard/EDITH/skills/` through the [StorageAccess] contract, which this class satisfies.
 *
 * Handles path validation and turns every failure into `null`/empty rather than an exception: a missing directory
 * is "the user has installed no SKILLs", which spec §3.7 makes a normal state, not an error.
 */
class StorageAccessServiceImpl : StorageAccess {

    /**
     * Read a file from external storage.
     *
     * @param path Absolute path to the file
     * @return File content as string, or null if file doesn't exist or can't be read
     */
    override fun readFile(path: String): String? {
        return try {
            val file = File(path)
            if (!file.exists() || !file.isFile || !file.canRead()) return null
            file.readText(Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * List files in a directory matching a suffix.
     *
     * @param dirPath Absolute path to the directory
     * @param suffix File suffix filter (e.g., ".md")
     * @return List of absolute file paths, empty if directory doesn't exist
     */
    override fun listFiles(dirPath: String, suffix: String): List<String> {
        return try {
            val dir = File(dirPath)
            if (!dir.exists() || !dir.isDirectory) return emptyList()
            dir.listFiles { f -> f.isFile && f.name.endsWith(suffix) }
                ?.map { it.absolutePath }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Delete a file.
     *
     * @param path Absolute path to the file
     * @return true if deleted successfully
     */
    override fun deleteFile(path: String): Boolean {
        return try {
            File(path).delete()
        } catch (_: Exception) {
            false
        }
    }
}
