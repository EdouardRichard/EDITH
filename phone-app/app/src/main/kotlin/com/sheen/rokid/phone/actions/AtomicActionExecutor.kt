package com.sheen.rokid.phone.actions

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionResult

/**
 * edith-0.3 Phase 4A Task 4 (A4): the bridge from the legacy [ExecutionBackend] probe vocabulary to the
 * [ActionExecutor] the [ToolSurface] routes.
 *
 * The two interfaces declare the same `descriptors`/`execute`/`cancel` triad; the backend additionally answers
 * `available()`, which the surface never asks because A3 made advertising **stable**: a channel that is down keeps its
 * tools and refuses the call itself. The adapter therefore only delegates, and adds no availability policy of its own -
 * hiding a down channel's tools here would reintroduce exactly the schema churn A3 removed.
 *
 * It is [AutoCloseable] because the surface only closes an atomic executor that is: without this the adapter - the one
 * type that holds a legacy backend - would always look non-closeable and a backend that owns a resource would never be
 * closed. [ExecutionBackend] itself declares no `close`, so a backend that owns nothing (all five today) is a no-op.
 *
 * Phase 4A close fix: the adapter also carries the **turn boundary** for a backend that keeps turn-scoped state.
 * [ExecutionBackend] declares no `beginTurn` and adding one would widen the legacy probe contract, so the composition
 * supplies [onTurnBoundary] only for the leg that owns such state - today the accessibility backend, whose
 * `read_screen` snapshot is valid for one turn. Without this forward, [ToolSurface.beginTurn] would stop here and a
 * second turn could act on the previous turn's snapshot, contradicting the index tools' descriptor prose.
 */
internal class AtomicActionExecutor(
    private val backend: ExecutionBackend,
    /**
     * The narrow turn-boundary seam: invoked by [beginTurn], and a no-op for the backends that own no turn state
     * (`IntentAtomicExecutor` is not wrapped by this adapter, and the remaining three atomic legs need nothing).
     */
    private val onTurnBoundary: () -> Unit = {},
) : ActionExecutor, AutoCloseable {

    override fun descriptors(): List<ActionDescriptor> = backend.descriptors()

    override fun execute(name: String, argsJson: String): ActionResult = backend.execute(name, argsJson)

    override fun cancel() = backend.cancel()

    /** Phase 4A close fix: the surface's turn boundary must reach turn-scoped backend state instead of stopping here. */
    override fun beginTurn() = onTurnBoundary()

    /** Forwards the surface's close to the wrapped backend iff it is closeable; a resource-free backend is a no-op. */
    override fun close() {
        (backend as? AutoCloseable)?.close()
    }
}
