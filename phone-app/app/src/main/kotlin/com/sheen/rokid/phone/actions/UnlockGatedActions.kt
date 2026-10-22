package com.sheen.rokid.phone.actions

import com.sheen.rokid.phone.lock.UnlockGate
import com.sheen.rokid.phone.lock.UnlockOutcome
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionPrecheck
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome

/**
 * B (2026-10-22 K70 acceptance): the lock-screen gate as one decorator over the whole tool surface.
 *
 * A tool that needs an unlocked phone must be gated no matter which name the model happens to pick. Before this,
 * only the catalog's `device`/`open`/`dial` asked for an unlock; the atomic `read_screen`/`tap`/`open_app`
 * family ran straight into the keyguard, reported `APP_NOT_VISIBLE` or an empty tree, and the model told the wearer
 * "the phone is locked" instead of opening the app. The gate therefore lives here, once, keyed by tool name.
 *
 * **Two umbrellas stay the catalog's.** `device`, `open` and `dial` are already gated inside `ToolCatalog`; gating them
 * again here would run two keep-alive/unlock cycles and log the second as `ALREADY_UNLOCKED`. `shell` is deliberately
 * ungated (a raw command needs no screen), and `list_apps`/`set_brightness`/the L3-L4 package operations act without a
 * UI, so they are not in the table at all.
 *
 * A refusal keeps the catalog's own shape (`LOCKED_UNLOCK_FAILED:<outcome>`, recoverable) rather than inventing a
 * second code the model would have to learn. The log line carries only the tool name, the requirement and the outcome,
 * never an argument.
 */
class UnlockGatedActions(
    private val inner: ActionExecutor,
    private val gate: UnlockGate,
    private val log: (String) -> Unit = { P3Log.line(it) },
) : ActionExecutor, AutoCloseable {

    /** One tool's requirement: it always needs an unlock; [requireAccessibility] also waits for the a11y reconnect. */
    data class Policy(val requireAccessibility: Boolean)

    private val declared: Set<String> = inner.descriptors().map { it.name }.toSet()

    override fun descriptors(): List<ActionDescriptor> = inner.descriptors()

    override fun execute(name: String, argsJson: String): ActionResult {
        val policy = POLICIES[name]?.takeIf { name in declared } ?: return inner.execute(name, argsJson)
        val outcome = gate.ensureUnlocked(policy.requireAccessibility)
        log("P3_UNLOCK tool=$name requireA11y=${policy.requireAccessibility} outcome=${outcome.name}")
        if (!outcome.usable) return lockedRefusal(outcome)
        return inner.execute(name, argsJson)
    }

    override fun precheck(name: String, argsJson: String): ActionPrecheck = inner.precheck(name, argsJson)

    override fun beginTurn() = inner.beginTurn()

    override fun cancel() = inner.cancel()

    override fun runtimeState(): String? = inner.runtimeState()

    override fun close() {
        (inner as? AutoCloseable)?.close()
    }

    private fun lockedRefusal(outcome: UnlockOutcome): ActionResult = ActionResult(
        ActionOutcome.Refused,
        "{}",
        execution = ToolExecution(
            outcome = ToolOutcome.Refused,
            code = "LOCKED_UNLOCK_FAILED:${outcome.name}",
            recoverable = true,
            accepted = false,
            applied = false,
        ),
    )

    companion object {
        /**
         * The tools whose result depends on an unlocked, readable screen.
         *
         * The accessibility five also need the accessibility service to be back after the unlock: a `read_screen`
         * that runs before the service reconnects answers `ACTION_CHANNEL_UNAVAILABLE`, which is the same false
         * refusal this gate exists to remove.
         */
        val POLICIES: Map<String, Policy> = mapOf(
            "read_screen" to Policy(requireAccessibility = true),
            "tap" to Policy(requireAccessibility = true),
            "long_press" to Policy(requireAccessibility = true),
            "scroll" to Policy(requireAccessibility = true),
            "input_text" to Policy(requireAccessibility = true),
            "open_app" to Policy(requireAccessibility = false),
            "open_url" to Policy(requireAccessibility = false),
            "open_settings" to Policy(requireAccessibility = false),
            "set_alarm" to Policy(requireAccessibility = false),
            "system_action" to Policy(requireAccessibility = false),
        )
    }
}
