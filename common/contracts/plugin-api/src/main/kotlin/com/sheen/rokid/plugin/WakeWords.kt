package com.sheen.rokid.plugin

/**
 * Declaration contract for the wake words the plugin wants the host to listen for.
 *
 * This is registration only: there is no audio capture, model or detection behaviour here. Audio-side detection is a
 * separate port owned by the audio layer and must not be merged into this type.
 * `declare` returns `true` when the host accepted the declaration; a refused declaration leaves [current] unchanged.
 */
interface WakeWordRegistry {
    fun declare(words: List<String>): Boolean
    fun current(): List<String>
}
