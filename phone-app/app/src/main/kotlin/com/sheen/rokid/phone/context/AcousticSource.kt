package com.sheen.rokid.phone.context

/**
 * The last acoustic label the host delivered (edith-0.3 Phase 2 Task 4; spec §7.2).
 *
 * A perception read must not invent a sound: when no `AcousticEvent` has arrived in the current activation there is no
 * acoustic fact (`null`), not a fabricated `speech`. [snapshot] only ever reports the label the host already named.
 */
internal class AcousticSource(private val label: () -> String?) {

    /** The delivered label's stable name, or `null` when no event has been seen (or the host is unavailable). */
    fun snapshot(): String? =
        runCatching { label() }.getOrNull()?.trim()?.takeIf { it.isNotEmpty() }
}
