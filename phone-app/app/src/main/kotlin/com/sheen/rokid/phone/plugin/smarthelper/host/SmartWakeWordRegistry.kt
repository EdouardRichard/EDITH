package com.sheen.rokid.phone.plugin.smarthelper.host
import com.sheen.rokid.plugin.WakeWordRegistry

/**
 * The phone-side `Capability.WakeWord` provider (Task 14a item 2).
 *
 * Two different things are deliberately kept apart and must not be merged:
 * - **this** registry is the plugin-facing *declaration* port - a plugin says which words it wants the host to listen
 *   for, and the host answers whether it accepted them;
 * - `com.sheen.rokid.audio.WakeWordEngine` is the detection port the audio layer runs.
 *
 * A declaration replaces the current set rather than adding to it, because "current" is the word set the configured
 * smart plugin asked for; merging lists across activations would keep a word from a previous configuration alive with
 * no way to withdraw it. Blanks are dropped and duplicates collapse in insertion order, so the audio layer can never
 * receive a word that is empty or repeated. An all-blank declaration is **refused** and leaves the current set
 * unchanged, which is what the interface's "a refused declaration leaves `current` unchanged" clause is for.
 */
internal class SmartWakeWordRegistry(initial: List<String> = emptyList()) : WakeWordRegistry {

    private val lock = Any()
    private val words = LinkedHashSet<String>()

    init { addAll(initial) }

    override fun declare(words: List<String>): Boolean {
        val usable = sanitize(words)
        if (usable.isEmpty()) return false
        synchronized(lock) {
            this.words.clear()
            this.words.addAll(usable)
        }
        return true
    }

    override fun current(): List<String> = synchronized(lock) { words.toList() }

    private fun addAll(values: List<String>) = synchronized(lock) { words.addAll(sanitize(values)) }

    /** The word as configured, minus anything that is not a word; de-duplication keeps the first occurrence's order. */
    private fun sanitize(values: List<String>) =
        values.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
}
