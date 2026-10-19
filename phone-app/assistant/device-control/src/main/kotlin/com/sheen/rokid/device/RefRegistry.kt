package com.sheen.rokid.device

/**
 * An opaque, stable handle for one node, in the form `#n<number>`.
 *
 * A ref carries no channel or coordinate parameters (D84); it is only meaningful to the
 * [RefRegistry] that minted it.
 */
data class Ref(val value: String)

/** The outcome of resolving a [Ref] against an observation. */
sealed interface RefResult {

    /** The ref still addresses exactly one node in this observation. */
    data class Ok(val ref: Ref) : RefResult

    /**
     * The ref cannot address a node any more; the caller must re-observe.
     *
     * [reason] is diagnostic text meant for logs and error envelopes, not for the model's action
     * parameters.
     */
    data class Stale(val reason: String) : RefResult
}

/**
 * Assigns stable refs to observation nodes and resolves refs against later observations.
 *
 * **Identity.** A node identity is `viewId + role + text + contentDescription + structural path +
 * bounds` (spec §4.4). The same identity keeps the same ref for the lifetime of the registry, so
 * two observations of an unchanged tree resolve to the same refs. A node whose text changes gets a
 * new identity — and therefore a new ref from [assign].
 *
 * **Rebind.** A ref whose exact identity is gone may follow a node that stayed in the same
 * structural slot with the same view id, role and bounds but a different text/description (a label
 * update, a counter, a connection state). That weaker key deliberately includes the structural
 * path and the bounds, so list reflow (a different item occupying the slot) or a node that moved
 * is never silently re-targeted. A ref gets at most [MAX_REBINDS] successful identity rebinds; a
 * further mismatch is [RefResult.Stale]. A ref that is merely absent from a truncated observation
 * is stale with a truncation-specific reason and does not spend the rebind budget, because
 * absence there proves nothing.
 *
 * **Keys.** [assign] returns one entry per node keyed by the structural path also used by
 * [ObservationDiff] (`0`, `0/1`, ...). That is the key callers use to associate a diff entry with
 * the ref of the node at that path.
 *
 * Instances are thread-safe, so the kernel may observe and act from different threads.
 */
class RefRegistry {

    private class Binding(
        var identity: String,
        val rebindKey: String,
        var ref: Ref,
        var rebinds: Int = 0,
    )

    private val byIdentity = HashMap<String, Ref>()
    private val byReference = HashMap<String, Binding>()
    private var issued = 0

    /**
     * Assigns or reuses a ref for every node in [observation].
     *
     * The returned map is keyed by the node's structural path and preserves the observation's node
     * order. Nodes hidden from a render filter still get refs; the registry does not know about
     * filters.
     */
    @Synchronized
    fun assign(observation: Observation): Map<String, Ref> {
        val paths = NodeStructure.paths(observation.nodes)
        val refs = LinkedHashMap<String, Ref>(observation.nodes.size)
        observation.nodes.forEachIndexed { index, node ->
            refs[paths[index]] = refFor(node, paths[index])
        }
        return refs
    }

    /**
     * Resolves [ref] against [observation].
     *
     * Resolution order: exact identity, then a single identity rebind (up to [MAX_REBINDS] times
     * per ref), then [RefResult.Stale]. When the ref's node was replaced by a node that already has
     * another ref, that canonical ref is returned; the caller never receives two different live
     * refs for what is now one node.
     */
    @Synchronized
    fun resolve(observation: Observation, ref: Ref): RefResult {
        val binding = byReference[ref.value]
            ?: return RefResult.Stale("unknown ref ${ref.value}: it was not issued by this registry")
        val paths = NodeStructure.paths(observation.nodes)

        val exact = observation.nodes.indices.firstOrNull { index ->
            identityOf(observation.nodes[index], paths[index]) == binding.identity
        }
        if (exact != null) return RefResult.Ok(binding.ref)

        val candidate = observation.nodes.indices.firstOrNull { index ->
            rebindKeyOf(observation.nodes[index], paths[index]) == binding.rebindKey
        }
        if (candidate == null) {
            val reason = if (observation.truncated) {
                "ref ${ref.value} is not visible in a truncated observation"
            } else {
                "ref ${ref.value} no longer matches a node"
            }
            return RefResult.Stale(reason)
        }
        if (binding.rebinds >= MAX_REBINDS) {
            return RefResult.Stale("ref ${ref.value} exhausted its $MAX_REBINDS identity rebinds")
        }

        val node = observation.nodes[candidate]
        val rebound = refFor(node, paths[candidate])
        binding.identity = identityOf(node, paths[candidate])
        binding.ref = rebound
        binding.rebinds += 1
        return RefResult.Ok(rebound)
    }

    private fun refFor(node: DeviceNode, path: String): Ref {
        val identity = identityOf(node, path)
        return byIdentity.getOrPut(identity) {
            val minted = Ref("#n${issued++}")
            byReference[minted.value] = Binding(
                identity = identity,
                rebindKey = rebindKeyOf(node, path),
                ref = minted,
            )
            minted
        }
    }

    private fun identityOf(node: DeviceNode, path: String): String = buildString {
        append(node.viewId.orEmpty()).append(FIELD)
        append(node.role).append(FIELD)
        append(node.text.orEmpty()).append(FIELD)
        append(node.contentDescription.orEmpty()).append(FIELD)
        append(path).append(FIELD)
        appendBounds(node.bounds)
    }

    /** Identity minus text/description: what must still match for a ref to be re-bound. */
    private fun rebindKeyOf(node: DeviceNode, path: String): String = buildString {
        append(node.viewId.orEmpty()).append(FIELD)
        append(node.role).append(FIELD)
        append(path).append(FIELD)
        appendBounds(node.bounds)
    }

    private fun StringBuilder.appendBounds(bounds: Bounds) {
        append(bounds.left).append(',')
        append(bounds.top).append(',')
        append(bounds.right).append(',')
        append(bounds.bottom)
    }

    private companion object {
        /** e2e practice: a ref may be relocated by identity twice before it goes stale. */
        const val MAX_REBINDS = 2

        /** Separates identity fields; identity text can contain anything except this character. */
        const val FIELD = '\u0000'
    }
}
