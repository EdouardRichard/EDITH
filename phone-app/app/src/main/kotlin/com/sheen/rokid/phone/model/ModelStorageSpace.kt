package com.sheen.rokid.phone.model

import java.io.File

/**
 * Free-space readings for a target directory that may not exist yet (0.4 Task 3 fix round 2).
 *
 * `File.usableSpace` asks the filesystem about the path itself: for an absent path it answers 0 (the same on the JVM
 * and on Android, where it delegates to `statvfs`). A first install has exactly that shape - `filesDir` exists but
 * `<externalFilesDir>/models/embeddinggemma` does not until the provisioner creates it - so a raw reading made the
 * space gate refuse a device with ample room, and the refusal happened before the `mkdirs` that would have fixed the
 * reading, so a retry could never succeed.
 *
 * The free space of the volume a directory will live on belongs to its nearest existing ancestor: every ancestor of
 * a not-yet-created path is necessarily on the same volume (creating a directory cannot cross filesystems). Only a
 * path with no existing ancestor at all - which the App's absolute directories never have - reads as 0, and then the
 * caller must refuse rather than guess.
 */
internal object ModelStorageSpace {

    /**
     * Returns the usable bytes of [target]'s volume, read from the nearest existing ancestor when [target] itself
     * does not exist. 0 only when nothing in the chain exists.
     */
    fun usableBytes(target: File): Long {
        var candidate: File? = target
        while (candidate != null) {
            if (candidate.exists()) return candidate.usableSpace
            candidate = candidate.parentFile
        }
        return 0L
    }
}
