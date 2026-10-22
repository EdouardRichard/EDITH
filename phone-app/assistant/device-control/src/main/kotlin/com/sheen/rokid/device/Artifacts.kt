package com.sheen.rokid.device

import com.sheen.rokid.plugin.ImageRef

/**
 * One artifact as the store holds it: the encoded bytes plus the metadata that travels in [ImageRef] (0.25 spec §5.2).
 *
 * The bytes exist here, at resolution time, and nowhere on the wire: a request carries only an [ImageRef], and
 * `MediaSource.resolveImage` asks the store for the bytes when the encoder needs them.
 */
data class StoredArtifact(
    val artifactId: String,
    val bytes: ByteArray,
    val mime: String,
    val width: Int,
    val height: Int,
    val sha256: String,
)

/**
 * The device-control port over an artifact store (0.25 spec §5.2; ruling R67).
 *
 * **Dependency direction.** This port lives in `device-control` — the module that produces the bytes — not in
 * `agent-runtime`: `device-control` does not depend on `agent-runtime`, while the reverse direction is possible and
 * already exists. It only uses `plugin-api`'s [ImageRef], so the kernel can report a real reference without learning
 * anything about the App's file layout.
 *
 * The implementation is the App's (`FileArtifactStore`, under the App cache). This interface deliberately says nothing
 * about where bytes go; it only promises that a reference returned by [put] can be read back by [get] until the
 * retention policy evicts it.
 */
interface ArtifactStore {
    /**
     * Stores one encoded image and answers the reference that travels with it.
     *
     * The returned [ImageRef]'s `artifactId`/`mime`/`width`/`height`/`sha256` describe the stored bytes. A store that
     * cannot write (disk full, cache cleared mid-flight) throws; the caller decides how to degrade. Implementations
     * must never inline the bytes into the reference.
     */
    fun put(bytes: ByteArray, mime: String, width: Int, height: Int): ImageRef

    /** The artifact's bytes and metadata, or `null` when it is unknown/expired/evicted. */
    fun get(artifactId: String): StoredArtifact?

    /**
     * Keeps only the newest [keep] artifacts, evicting older ones.
     *
     * The retention floor the context relies on (ruling R72: the newest K = 3 references still in a request must
     * survive) is the implementation's; this method is the explicit way a caller may ask for a particular floor.
     */
    fun evictBefore(keep: Int)
}
