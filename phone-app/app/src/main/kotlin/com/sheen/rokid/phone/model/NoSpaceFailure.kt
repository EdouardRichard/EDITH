package com.sheen.rokid.phone.model

/**
 * Recognizes a "the volume is full" failure anywhere in [failure]'s cause chain.
 *
 * This exists so the two layers that can lose the cause agree on one definition: [AssetEngineBundleProvisioning]
 * must not relabel a full disk as "the packaged engine is missing", and [BundledModelProvisioner] must report such
 * a failure as `MODELS_NO_SPACE` rather than a group-specific reason.
 *
 * Two shapes are recognized:
 * - a message naming ENOSPC (`No space left on device`, `ENOSPC`, or "not enough space"), which is what
 *   `java.io.IOException` and Android file APIs usually carry; and
 * - the platform's errno-carrying exception (`android.system.ErrnoException`) when `errno` is `ENOSPC`, because
 *   `FileOutputStream`/`Os.write` can throw it directly without an English message our message match would see.
 *
 * The class is matched by name on purpose: the JVM unit tests cannot instantiate the real Android class, and the
 * shape (`*ErrnoException` with an `errno` field) is the only thing the mapper depends on.
 */
internal fun isNoSpaceFailure(failure: Throwable): Boolean {
    var current: Throwable? = failure
    var depth = 0
    while (current != null && depth < MAX_CAUSE_DEPTH) {
        if (mentionsNoSpace(current.message) || isErrnoNoSpace(current)) return true
        current = current.cause
        depth++
    }
    return false
}

/** The message the provisioner throws when its own pre-write free-space reading refuses a copy. */
internal const val NO_SPACE_MESSAGE = "No space left on device"

private fun mentionsNoSpace(message: String?): Boolean {
    val text = message?.lowercase() ?: return false
    return "no space" in text || "enospc" in text || "not enough space" in text
}

private fun isErrnoNoSpace(failure: Throwable): Boolean {
    if (!failure.javaClass.name.endsWith(ERRNO_EXCEPTION_SUFFIX)) return false
    val errno = runCatching {
        failure.javaClass.getDeclaredField("errno").also { it.isAccessible = true }.getInt(failure)
    }.getOrNull() ?: return false
    return errno == ENOSPC_ERRNO
}

/** A bounded walk: exception chains are short, but a hand-built cycle must not hang the provisioner thread. */
private const val MAX_CAUSE_DEPTH = 32

private const val ERRNO_EXCEPTION_SUFFIX = "ErrnoException"

/** `linux errno` ENOSPC, the same value as `android.system.OsConstants.ENOSPC`. */
private const val ENOSPC_ERRNO = 28
