package com.sheen.rokid.phone.plugin.smarthelper.library

/**
 * Where an entry sits in the three-state library lifecycle.
 *
 * The states are ordered by "how much unbroken drift evidence has accumulated against this entry", not by quality:
 * [ACTIVE] is what a newly seen entry is, [DORMANT] is an entry that has been listed for [LibraryGovernor]
 * `minObservedTurns` turns without anyone pulling it, and [RETIRED] is twice that in total. A single pull returns the
 * entry to [ACTIVE] from any state and stops the evidence counting for the rest of the process (see
 * [LibraryGovernor.recordPull]) — the states describe drift, not worth.
 */
enum class LibraryState { ACTIVE, DORMANT, RETIRED }

/**
 * One governed library entry: an id, its lifecycle [state], how many drift observations it has accumulated, and how
 * many times it has been pulled in total.
 *
 * [observedTurns] is progress along the drift, not the entry's age. It counts up while the entry is listed and
 * untouched, is wiped to zero by a pull, and drives the thresholds in [LibraryGovernor]: `minObservedTurns` demotes,
 * twice that retires. Once [pulls] is greater than zero the counter stops moving entirely for this process, so it can
 * never carry the entry back over a threshold it has already been rescued from.
 *
 * The freeze is **derived, not stored**: `pulls > 0` *is* "the wearer has used this entry here, so drift is no longer
 * counted against it" ([LibraryGovernor] guards both the counting and the advancing on that expression). Carrying a
 * separate flag for it would admit the inconsistent combination `pulls > 0 && !flag`, a state in which the protection
 * silently vanishes — so there is no such field.
 *
 * This type is deliberately anonymous about what the id *means*. It carries ids, states and counts only, so the SKILL
 * catalog and the memory store can both be governed by the same machinery without either one leaking into it.
 */
data class LibraryEntry(
    val id: String,
    val state: LibraryState,
    val observedTurns: Int,
    val pulls: Int,
)
