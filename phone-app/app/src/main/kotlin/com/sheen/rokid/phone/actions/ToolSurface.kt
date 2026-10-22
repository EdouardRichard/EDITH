package com.sheen.rokid.phone.actions

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionPrecheck
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.PrecheckDecision
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome

/**
 * edith-0.3 Phase 4A Task 3 (A3): the one ordered `ActionExecutor` that joins the atomic backends with the umbrella
 * [com.sheen.rokid.phone.actions.device.ToolCatalog], so a composition has a single `Capability.Actions` provider.
 *
 * This is an adapter, not a second router with policy: it advertises what its executors declare, routes a call to the
 * one executor that declared the name, forwards the turn lifecycle, and leaves every availability/confirmation
 * decision to the executor that owns it. In particular, the catalog's four umbrella tools are advertised
 * unconditionally (A3 made `ToolCatalog.descriptors()` stable) and a channel that is down answers its own stable
 * refusal - the umbrella catalog's `CHANNEL_UNAVAILABLE`, the legacy atomic backends' `ACTION_CHANNEL_UNAVAILABLE` (A4
 * does not normalize the two historical shapes) - which keeps the schema and the cached prompt prefix unchanged
 * mid-session.
 *
 * Two executors claiming one action name is a wiring bug: routing would be ambiguous, so it is rejected in the
 * constructor rather than silently resolved by list order. Descriptor order is fixed - the [atomic] executors in list
 * order, then the catalog's four - so repeated reads produce one stable schema.
 */
class ToolSurface(
    private val catalog: ActionExecutor,
    private val atomic: List<ActionExecutor>,
    private val onClose: () -> Unit = {},
) : ActionExecutor, AutoCloseable {

    init {
        val claimed = (atomic.flatMap { it.descriptors() } + catalog.descriptors())
            .groupingBy { it.name }
            .eachCount()
        val duplicated = claimed.filterValues { it > 1 }.keys
        require(duplicated.isEmpty()) {
            "an action name must have exactly one executor: ${duplicated.sorted()}"
        }
    }

    /** The atomic descriptors first (in [atomic] order), then the catalog's four umbrella tools. */
    override fun descriptors(): List<ActionDescriptor> =
        atomic.flatMap { it.descriptors() } + catalog.descriptors()

    /**
     * A call is routed by the name its executor **declares**; a name nobody declares is [UNKNOWN_ACTION].
     *
     * The check is on descriptors, not on trying executors in turn: an executor that accepts arbitrary names must not
     * make an unadvertised name executable. A declared action is always delegated, even while its channel is down, so
     * the executor can answer its own stable channel-refusal code - that refusal is why the tool stays advertised.
     */
    override fun execute(name: String, argsJson: String): ActionResult {
        val atomicOwner = atomic.firstOrNull { executor ->
            executor.descriptors().any { descriptor -> descriptor.name == name }
        }
        if (atomicOwner != null) return atomicOwner.execute(name, argsJson)
        if (catalog.descriptors().any { it.name == name }) return catalog.execute(name, argsJson)
        return ActionResult(
            ActionOutcome.Refused,
            // Task 8 convention: the stable code is an execution fact; `data` repeats nothing.
            "{}",
            execution = ToolExecution(
                outcome = ToolOutcome.Refused,
                code = UNKNOWN_ACTION,
                accepted = false,
                applied = false,
            ),
        )
    }

    /**
     * 0.25 Task 11 (R49): only the catalog declares a call-level risk (`shell`'s command classifier), so only a
     * catalog-declared name is forwarded. Every other name keeps the interface default (`Allow`) and no atomic executor
     * is asked: their descriptors carry no argument-level question.
     */
    override fun precheck(name: String, argsJson: String): ActionPrecheck =
        if (catalog.descriptors().any { it.name == name }) {
            catalog.precheck(name, argsJson)
        } else {
            ActionPrecheck(PrecheckDecision.Allow)
        }

    /**
     * Turn-scoped lifecycle is broadcast: the catalog re-arms/cancels the device kernel, the atomics their own state.
     *
     * Phase 4A close fix: "their own state" is load-bearing for the accessibility leg - its `read_screen` snapshot is
     * valid for one turn only, and [AtomicActionExecutor] forwards this boundary to the backend's turn seam so an
     * index from the previous turn is refused as `ARGUMENTS_INVALID` instead of acting on a stale screen. The plugin
     * calls this at the start of every assistant turn.
     */
    override fun beginTurn() {
        atomic.forEach { it.beginTurn() }
        catalog.beginTurn()
    }

    override fun cancel() {
        atomic.forEach { it.cancel() }
        catalog.cancel()
    }

    /** R59: the one device-state line; the device kernel lives in the catalog, so the atomic backends have none. */
    override fun runtimeState(): String? = catalog.runtimeState()

    /**
     * The atomic executors may own resources (`AutoCloseable` is optional on the interface), so each one that does is
     * closed first; [onClose] then closes what the composition injected it for (the catalog), after everything above it.
     */
    override fun close() {
        atomic.forEach { (it as? AutoCloseable)?.close() }
        onClose()
    }

    companion object {
        /** No executor in this surface declared the name. */
        const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
    }
}
