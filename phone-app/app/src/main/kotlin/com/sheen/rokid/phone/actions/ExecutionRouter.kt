package com.sheen.rokid.phone.actions
import com.sheen.rokid.phone.actions.intent.IntentActionExecutor
import com.sheen.rokid.phone.storage.unavailable
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionExecutor
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult

/**
 * One execution channel behind `Capability.Actions` (spec §17.2).
 *
 * [available] is asked on every read, never cached: a channel's answer changes while the App runs - the accessibility
 * service is switched off in system settings, Shizuku's binder dies, a permission is revoked - and a cached answer is
 * exactly how a tool would keep being advertised after it stopped working.
 */
interface ExecutionBackend {
    /** Stable identifier for the probe line and the capability panel (`intent`, `accessibility`, `shizuku`). */
    val id: String

    fun available(): Boolean

    fun descriptors(): List<ActionDescriptor>

    fun execute(name: String, argsJson: String): ActionResult

    fun cancel() = Unit
}

/**
 * The single `Capability.Actions` provider: it merges the channels that are **up** and routes a call to the one that
 * declared it.
 *
 * **Retired from production in 0.25 Task 10 (R47).** `HostCompositionRoot` now wires
 * `com.sheen.rokid.phone.actions.device.ToolCatalog`, whose four tools (`device`/`shell`/`open`/`dial`) are the whole
 * model-visible device surface; this class stays only for the existing router tests and the historical record. It is
 * deliberately not `@Deprecated` because those tests still exercise it as the generic channel-router pattern, not as a
 * production path.
 *
 * Two properties are the whole reason this type exists rather than handing a list of executors to `ToolRegistry`:
 * 1. **an unavailable channel's actions are not advertised.** The model never sees a tool that would only be refused,
 *    so a degradation costs no turns and the capability panel can name the channel that is down;
 * 2. **availability is re-checked at execution.** A call that arrives after its channel went down is refused with
 *    [ACTION_CHANNEL_UNAVAILABLE] - never executed, and never confused with an unknown name, which keeps "this action
 *    does not exist" and "this action exists but its channel is down" distinguishable in the audit trail.
 *
 * Two backends claiming one action name is a wiring bug that would make routing ambiguous, so it is refused at
 * construction rather than resolved by list order.
 */
class ExecutionRouter(private val backends: List<ExecutionBackend>) : ActionExecutor {

    init {
        val claimed = backends.flatMap { it.descriptors() }.groupingBy { it.name }.eachCount()
        val duplicated = claimed.filterValues { it > 1 }.keys
        require(duplicated.isEmpty()) { "two execution backends claim the same action: ${duplicated.sorted()}" }
    }

    override fun descriptors(): List<ActionDescriptor> =
        backends.filter { it.available() }.flatMap { it.descriptors() }

    override fun execute(name: String, argsJson: String): ActionResult {
        val backend = backends.firstOrNull { it.available() && it.descriptors().any { action -> action.name == name } }
        if (backend != null) return backend.execute(name, argsJson)
        val exists = backends.any { it.descriptors().any { action -> action.name == name } }
        return ActionResult(ActionOutcome.Refused, if (exists) ACTION_CHANNEL_UNAVAILABLE else UNKNOWN_ACTION)
    }

    override fun cancel() {
        backends.forEach { runCatching { it.cancel() } }
    }

    companion object {
        /** The action exists but its channel is down. Distinct from [UNKNOWN_ACTION] on purpose. */
        const val ACTION_CHANNEL_UNAVAILABLE = "ACTION_CHANNEL_UNAVAILABLE"

        /** No backend ever declared this name. */
        const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
    }
}

/**
 * The L0/L1 channel: standard Android intents plus the App's encrypted note storage, exactly as P3.1 delivered them.
 *
 * Always available, and deliberately a thin adapter: the five actions, their schemas and their refusal codes stay in
 * [IntentActionExecutor], whose behaviour this wrapper must not change.
 */
class IntentExecutionBackend(private val executor: IntentActionExecutor) : ExecutionBackend {
    override val id = "intent"

    override fun available() = true

    override fun descriptors(): List<ActionDescriptor> = executor.descriptors()

    override fun execute(name: String, argsJson: String): ActionResult = executor.execute(name, argsJson)

    override fun cancel() = executor.cancel()
}
