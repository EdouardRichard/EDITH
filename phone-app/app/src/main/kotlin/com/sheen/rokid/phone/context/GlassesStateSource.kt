package com.sheen.rokid.phone.context

/**
 * The glasses' tri-state facts (edith-0.3 Phase 2 Task 4; spec §7.2).
 *
 * Every field is nullable on purpose: `null` means "this host has not reported the fact", which is different from
 * `false` (a fact that was reported). The downstream coverage renders `null` as unavailable rather than guessing.
 */
internal data class GlassesPerception(
    val connected: Boolean? = null,
    val wearing: Boolean? = null,
    val panelVisible: Boolean? = null,
)

/**
 * The glasses facts over injectable reads, so a test can drive every null/true/false combination without a link.
 *
 * Each read is guarded on its own: one dead fact leaves the other two exactly as reported, because a throwing link
 * getter must not turn a known wearing state into an unknown one (and vice versa).
 */
internal class GlassesStateSource(
    private val connected: () -> Boolean?,
    private val wearing: () -> Boolean?,
    private val panelVisible: () -> Boolean?,
) {

    fun snapshot(): GlassesPerception = GlassesPerception(
        connected = read(connected),
        wearing = read(wearing),
        panelVisible = read(panelVisible),
    )

    private fun read(fact: () -> Boolean?): Boolean? = runCatching { fact() }.getOrNull()
}
