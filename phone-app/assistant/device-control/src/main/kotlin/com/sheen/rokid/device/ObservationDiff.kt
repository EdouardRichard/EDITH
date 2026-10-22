package com.sheen.rokid.device

/**
 * The observable change between two observations.
 *
 * Every list holds structural node paths (`0`, `0/1`, ...) — the same keys [RefRegistry.assign]
 * returns. [added] and [updated] follow the new observation's pre-order, [removed] follows the old
 * one.
 *
 * [mode] is `"full"` when the caller should render the whole screen instead of these lists: the
 * first non-empty observation, more than 60 changed paths, or more than half of the old tree
 * changing (spec §4.4). Otherwise it is `"diff"`.
 */
data class Diff(
    val added: List<String>,
    val updated: List<String>,
    val removed: List<String>,
    val mode: String,
)

/**
 * Computes [Diff]s between two snapshots.
 *
 * Nodes are paired by structural path, not by text, so an in-place text change is [Diff.updated]
 * rather than remove + add. An insertion that shifts its siblings' paths shows up as one added
 * node plus updated entries for the shifted siblings; once that crosses a threshold the mode is
 * `"full"`, which is the safe recovery. When the new observation is truncated, absence from the
 * visible subset cannot prove that a baseline node disappeared, so [Diff.removed] is empty and only
 * the visible part is reported; the removed count therefore never inflates the change ratio on a
 * truncated tree.
 *
 * The caller is expected to pass the newest observation as [new].
 */
object ObservationDiff {

    private const val MAX_CHANGED_PATHS = 60

    fun between(old: Observation, new: Observation): Diff {
        val oldPaths = NodeStructure.paths(old.nodes)
        val newPaths = NodeStructure.paths(new.nodes)
        val oldByPath = oldPaths.zip(old.nodes).toMap()
        val newByPath = newPaths.zip(new.nodes).toMap()

        val added = newPaths.filter { it !in oldByPath }
        val updated = newPaths.filter { path ->
            oldByPath[path]?.let { it != newByPath.getValue(path) } == true
        }
        val removed = if (new.truncated) {
            emptyList()
        } else {
            oldPaths.filter { it !in newByPath }
        }

        val changed = added.size + updated.size + removed.size
        val mode = when {
            old.nodes.isEmpty() && new.nodes.isNotEmpty() -> "full"
            changed > MAX_CHANGED_PATHS -> "full"
            old.nodes.isNotEmpty() && changed > old.nodes.size / 2 -> "full"
            else -> "diff"
        }
        return Diff(added = added, updated = updated, removed = removed, mode = mode)
    }
}
