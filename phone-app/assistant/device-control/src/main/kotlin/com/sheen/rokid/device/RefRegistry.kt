package com.sheen.rokid.device

/**
 * An opaque, stable handle for one node, in the form `#n<number>`.
 *
 * A ref carries no channel or coordinate parameters (D84); it is only meaningful to the
 * [RefRegistry] that minted it.
 */
data class Ref(val value: String)

/**
 * One observed screen, as recorded by [RefRegistry.beginSnapshot].
 *
 * [snapshotId] identifies the observation a ref belongs to; [issuedAtEpochMillis] and the foreground
 * facts are the action-time gate used by [RefRegistry.resolveForAction]. The foreground values are
 * nullable because the producer cannot always determine the app or activity in front.
 */
data class RefSnapshot(
    val snapshotId: String,
    val issuedAtEpochMillis: Long,
    val foregroundPackage: String?,
    val foregroundActivity: String?,
)

/** The outcome of resolving a [Ref] against an observation. */
sealed interface RefResult {

    /** The ref still addresses exactly one node in this observation. */
    data class Ok(val ref: Ref) : RefResult

    /**
     * The ref cannot address a node any more; the caller must re-observe.
     *
     * [reason] is diagnostic text meant for logs and error envelopes, not for the model's action
     * parameters. [code] is the machine-readable reason the caller maps to an error envelope:
     * `STALE_REF` for an unknown or rebind-exhausted ref, `REF_EXPIRED` for an expired snapshot,
     * `FOREGROUND_CHANGED` when the app in front is no longer the observed one, and
     * `CROSS_SNAPSHOT` when the ref was not assigned in the current snapshot.
     */
    data class Stale(val reason: String, val code: String = "STALE_REF") : RefResult
}

/**
 * Assigns stable refs to observation nodes and resolves refs against later observations.
 *
 * **Identity.** A node identity is `viewId + role + text + contentDescription + structural path +
 * bounds` (spec §4.4). The same identity keeps the same ref for the lifetime of the registry, so
 * two observations of an unchanged tree resolve to the same refs. A node whose text changes gets a
 * new identity — and therefore a new ref from [assign].
 *
 * **Rebind.** A ref whose exact identity is gone may follow a node that kept the same structural
 * slot, view id, role and bounds but changed only its text/description (a label update, a counter,
 * a connection state). That weaker key excludes text on purpose: a recycled list row or any other
 * replacement occupying the same slot with the same geometry is treated as the same node and IS
 * rebound — those fields are the only anchor the producer gives us. A node that moved, or that
 * changed view id, role or structural path, is never silently re-targeted and goes
 * [RefResult.Stale]. A ref gets at most [MAX_REBINDS] successful identity rebinds; a further
 * mismatch is [RefResult.Stale]. A ref that is merely absent from a truncated observation is stale
 * with a truncation-specific reason and does not spend the rebind budget, because absence there
 * proves nothing.
 *
 * **Snapshots (Phase 2).** [beginSnapshot] records the id, issue time and foreground facts of the
 * screen that is about to be assigned; [assign] stamps the current snapshot id onto every binding it
 * assigns or reuses, so a surviving node keeps its ref and is re-stamped. [resolveForAction] is the
 * action-time gate: it returns `REF_EXPIRED` when the snapshot is older than the caller's TTL
 * (comparison is strict `>`; a non-positive TTL expires every snapshot immediately),
 * `FOREGROUND_CHANGED` when the package or activity in front differs from the snapshot, and
 * `CROSS_SNAPSHOT` when the ref's binding was not assigned in the current snapshot. A ref whose node
 * is absent from the current observation is therefore stale as `CROSS_SNAPSHOT`, never silently
 * pointed at a new node. [beginSnapshot] never clears the identity maps; [resolve] never consults
 * this metadata, so pre-Phase-2 callers behave unchanged.
 *
 * **Lifecycle.** Every distinct identity ever assigned keeps one map entry for the lifetime of the
 * registry, so a long-lived registry grows with the app's UI history. A kernel should own one
 * registry per task/session and [reset] it between them (or create a fresh one). [reset] clears the
 * identity maps and the snapshot metadata. Ref numbers stay monotonic across a reset, so a retained
 * old ref can never silently address a node minted later.
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
        /** The snapshot this binding was last assigned in; null until an [assign] under a snapshot. */
        var snapshotId: String? = null,
    )

    private val byIdentity = HashMap<String, Ref>()
    private val byReference = HashMap<String, Binding>()
    private var issued = 0
    private var current: RefSnapshot? = null

    /**
     * Records the metadata of the observation the caller is about to [assign].
     *
     * Only metadata is recorded: the identity maps are left untouched, and a ref becomes actionable
     * for the new snapshot only when [assign] stamps it.
     */
    @Synchronized
    fun beginSnapshot(
        snapshotId: String,
        issuedAtEpochMillis: Long,
        foregroundPackage: String?,
        foregroundActivity: String?,
    ) {
        current = RefSnapshot(
            snapshotId = snapshotId,
            issuedAtEpochMillis = issuedAtEpochMillis,
            foregroundPackage = foregroundPackage,
            foregroundActivity = foregroundActivity,
        )
    }

    /** The snapshot most recently recorded by [beginSnapshot], or null before the first one. */
    @Synchronized
    fun currentSnapshot(): RefSnapshot? = current

    /**
     * Assigns or reuses a ref for every node in [observation].
     *
     * The returned map is keyed by the node's structural path and preserves the observation's node
     * order. Nodes hidden from a render filter still get refs; the registry does not know about
     * filters. Every assigned or reused binding is stamped with the current snapshot id (null when
     * [beginSnapshot] has not been called), which is what makes it actionable for that snapshot.
     */
    @Synchronized
    fun assign(observation: Observation): Map<String, Ref> {
        val snapshotId = current?.snapshotId
        val paths = NodeStructure.paths(observation.nodes)
        val refs = LinkedHashMap<String, Ref>(observation.nodes.size)
        observation.nodes.forEachIndexed { index, node ->
            val ref = refFor(node, paths[index])
            // A node that survived from an earlier snapshot keeps its binding and is re-stamped
            // here; this is the only place that stamps, so resolve() cannot resurrect a ref.
            byReference.getValue(ref.value).snapshotId = snapshotId
            refs[paths[index]] = ref
        }
        return refs
    }

    /**
     * Forgets every issued ref and the snapshot metadata, e.g. at the task/session boundary of a
     * long-lived registry.
     *
     * Ref numbers keep increasing, so a ref retained from before the reset goes [RefResult.Stale]
     * as unknown instead of silently addressing a node minted afterwards.
     */
    @Synchronized
    fun reset() {
        byIdentity.clear()
        byReference.clear()
        current = null
    }

    /**
     * Resolves [ref] against [observation].
     *
     * Resolution order: exact identity, then a single identity rebind (up to [MAX_REBINDS] times
     * per ref), then [RefResult.Stale]. When the ref's node was replaced by a node that already has
     * another ref, that canonical ref is returned; the caller never receives two different live
     * refs for what is now one node.
     *
     * This is the pre-Phase-2 resolution path: it deliberately ignores snapshot ids, TTL and the
     * foreground. Use [resolveForAction] before dispatching an action.
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
        val previousRef = binding.ref
        val rebound = refFor(node, paths[candidate])
        // The identity this ref used to answer to is no longer this binding's identity. Drop the
        // stale lookup entry so a later observation of that old identity mints a fresh ref instead
        // of handing out a ref that has been rebound away.
        if (byIdentity[binding.identity] == previousRef) {
            byIdentity.remove(binding.identity)
        }
        binding.identity = identityOf(node, paths[candidate])
        binding.ref = rebound
        binding.rebinds += 1
        return RefResult.Ok(rebound)
    }

    /**
     * Resolves [ref] for an action about to be dispatched, refusing refs that no longer belong to
     * the screen the model saw.
     *
     * Checks, in order: a current snapshot exists ([RefSnapshot]); the snapshot is not older than
     * [ttlMillis] (strict `>`; `ttlMillis <= 0` expires every snapshot immediately); the foreground
     * package and activity still match the snapshot; the ref was assigned in the current snapshot
     * (otherwise `CROSS_SNAPSHOT`). Only then does it delegate to [resolve]. Unknown and
     * rebind-exhausted refs are `STALE_REF`; the reason strings are diagnostic.
     */
    @Synchronized
    fun resolveForAction(
        observation: Observation,
        ref: Ref,
        nowEpochMillis: Long,
        foregroundPackage: String?,
        foregroundActivity: String?,
        ttlMillis: Long,
    ): RefResult {
        val snapshot = current
            ?: return RefResult.Stale("no current snapshot")
        if (ttlMillis <= 0L || nowEpochMillis - snapshot.issuedAtEpochMillis > ttlMillis) {
            return RefResult.Stale(
                "ref ${ref.value} expired: snapshot ${snapshot.snapshotId} was issued " +
                    "${nowEpochMillis - snapshot.issuedAtEpochMillis} ms ago " +
                    "(ttl ${ttlMillis} ms)",
                "REF_EXPIRED",
            )
        }
        if (snapshot.foregroundPackage != foregroundPackage ||
            snapshot.foregroundActivity != foregroundActivity
        ) {
            return RefResult.Stale(
                "foreground changed since snapshot ${snapshot.snapshotId}: was " +
                    "${snapshot.foregroundPackage}/${snapshot.foregroundActivity}, now " +
                    "$foregroundPackage/$foregroundActivity",
                "FOREGROUND_CHANGED",
            )
        }
        val binding = byReference[ref.value]
            ?: return RefResult.Stale(
                "unknown ref ${ref.value}: it was not issued by this registry",
            )
        if (binding.snapshotId != snapshot.snapshotId) {
            return RefResult.Stale(
                "ref ${ref.value} was not assigned in snapshot ${snapshot.snapshotId}",
                "CROSS_SNAPSHOT",
            )
        }
        return when (val resolved = resolve(observation, ref)) {
            is RefResult.Ok -> resolved
            is RefResult.Stale -> RefResult.Stale(resolved.reason)
        }
    }

    private fun refFor(node: DeviceNode, path: String): Ref {
        val identity = identityOf(node, path)
        val existing = byIdentity[identity]
        if (existing != null && byReference[existing.value]?.identity == identity) {
            return existing
        }
        val minted = Ref("#n${issued++}")
        byIdentity[identity] = minted
        byReference[minted.value] = Binding(
            identity = identity,
            rebindKey = rebindKeyOf(node, path),
            ref = minted,
        )
        return minted
    }

    private fun identityOf(node: DeviceNode, path: String): String = buildString {
        append(node.viewId.orEmpty()).append(FIELD)
        append(node.role).append(FIELD)
        append(node.text.orEmpty()).append(FIELD)
        append(node.contentDescription.orEmpty()).append(FIELD)
        append(path).append(FIELD)
        append(node.bounds.toFieldString())
    }

    /** Identity minus text/description: what must still match for a ref to be re-bound. */
    private fun rebindKeyOf(node: DeviceNode, path: String): String = buildString {
        append(node.viewId.orEmpty()).append(FIELD)
        append(node.role).append(FIELD)
        append(path).append(FIELD)
        append(node.bounds.toFieldString())
    }

    private companion object {
        /** e2e practice: a ref may be relocated by identity twice before it goes stale. */
        const val MAX_REBINDS = 2

        /** Separates identity fields; identity text can contain anything except this character. */
        const val FIELD = '\u0000'
    }
}
