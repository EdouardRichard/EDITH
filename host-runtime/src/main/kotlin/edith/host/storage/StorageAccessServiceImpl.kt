package edith.host.storage

import java.io.File

/**
 * Provides safe read access to files on external storage (SD card).
 *
 * Handles permission checks and path validation. Used by SkillManager
 * to read SKILL files from /sdcard/EDITH/skills/.
 */
class StorageAccessServiceImpl {

    /**
     * Read a file from external storage.
     *
     * @param path Absolute path to the file
     * @return File content as string, or null if file doesn't exist or can't be read
     */
    fun readFile(path: String): String? {
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
    fun listFiles(dirPath: String, suffix: String): List<String> {
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
    fun deleteFile(path: String): Boolean {
        return try {
            File(path).delete()
        } catch (_: Exception) {
            false
        }
    }
}
