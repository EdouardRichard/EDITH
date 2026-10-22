package com.sheen.rokid.phone.actions.intent

import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult

/**
 * edith-0.3 Phase 4A Task 4 (A4): the atomic intent leg of the host tool face.
 *
 * [IntentActionExecutor] still declares `dial` — that L2 row is its historical declaration, and the **body** behind it
 * is the one implementation the umbrella catalog's `DialTool` delegates to. But Phase 4A's design keeps a single
 * advertised owner for the name (`DialTool`), and `ToolSurface` refuses a duplicate at construction, so this wrapper
 * filters `dial` out of the **advertised** list. The filter is on descriptors rather than on execution alone: a name
 * nobody advertises must not become executable through this leg either, so an (unreachable in production) `dial` call
 * is refused here instead of being forwarded a second time.
 *
 * edith-0.3 Phase 4A Task 4 review: the wrapper forwards the executor's call classification (`precheck`) and turn
 * lifecycle (`beginTurn`, `runtimeState`) as well. [IntentActionExecutor] is final and overrides none of the three
 * today, so each forwarded call resolves to the same interface default (`Allow`, no-op, `null`) the wrapper already
 * answered - but the forwards are what keep this leg transparent if the executor ever gains its own implementation; a
 * wrapper that silently replaced a future override with the interface default would be a behaviour bug nobody sees.
 */
internal class IntentAtomicExecutor(private val executor: IntentActionExecutor) : ActionExecutor {

    override fun descriptors(): List<ActionDescriptor> =
        executor.descriptors().filterNot { it.name == DIAL }

    override fun execute(name: String, argsJson: String): ActionResult =
        if (name == DIAL) {
            ActionResult(ActionOutcome.Refused, UNKNOWN_ACTION)
        } else {
            executor.execute(name, argsJson)
        }

    override fun cancel() = executor.cancel()

    override fun precheck(name: String, argsJson: String) = executor.precheck(name, argsJson)

    override fun beginTurn() = executor.beginTurn()

    override fun runtimeState(): String? = executor.runtimeState()

    private companion object {
        /** The catalog's `DialTool` is the one advertised owner; this leg must never act on it. */
        const val DIAL = "dial"

        /** The same stable code the intent executor answers for a name it does not declare. */
        const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
    }
}
