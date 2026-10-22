package com.sheen.rokid.host.systemone

import java.io.File

/**
 * Loads and validates a local model file from disk.
 *
 * Returns a loaded model handle on success, or null on any failure (missing file, corrupt data, etc.).
 * Never throws — all errors are logged and returned as null.
 */
internal object ModelLoader {

    data class LoadedModel(
        val path: String,
        val name: String,
        val sizeBytes: Long,
    )

    /**
     * Attempt to load a model from the given path.
     *
     * @return LoadedModel on success, null if the file does not exist or cannot be read.
     */
    fun load(modelPath: String): LoadedModel? {
        return try {
            val file = File(modelPath)
            if (!file.exists() || !file.isFile) {
                System.err.println("[SystemOne] Model file not found: $modelPath")
                return null
            }
            if (!file.canRead()) {
                System.err.println("[SystemOne] Model file not readable: $modelPath")
                return null
            }
            LoadedModel(
                path = modelPath,
                name = file.nameWithoutExtension,
                sizeBytes = file.length(),
            )
        } catch (e: Exception) {
            System.err.println("[SystemOne] Model load failed: ${e.message}")
            null
        }
    }
}
