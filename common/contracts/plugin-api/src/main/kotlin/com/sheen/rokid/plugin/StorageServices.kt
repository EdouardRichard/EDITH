package com.sheen.rokid.plugin

interface StorageHandle {
    fun read(key: String): ByteArray?
    fun write(key: String, value: ByteArray)
    fun delete(key: String)
    fun clear()
}

/**
 * Read access to a directory the user manages **outside** the app sandbox — the SKILL library at
 * `/sdcard/EDITH/skills/` (spec §3.5).
 *
 * Kept apart from [StorageHandle] on purpose. `StorageHandle` is a key/value namespace inside the app's encrypted
 * storage, reached through `Capability.Storage`; this is a user-visible directory on shared storage, reached through
 * `Capability.StorageAccess`. The two carry different permission and privacy stories, so a plugin that asks for one
 * must not receive the other.
 *
 * Implementations own path validation and permission handling; a caller passes an absolute path and gets `null` or
 * an empty list rather than an exception when the directory is absent — "no SKILLs installed" is a normal state,
 * not an error (spec §3.7).
 */
interface StorageAccess {
    /** Absolute paths of the files under [dirPath] ending in [suffix]; empty when the directory does not exist. */
    fun listFiles(dirPath: String, suffix: String): List<String>

    /** The file's UTF-8 text, or `null` when it is missing or unreadable. */
    fun readFile(path: String): String?

    /** Whether the file was removed; `false` when it was not there. */
    fun deleteFile(path: String): Boolean
}
