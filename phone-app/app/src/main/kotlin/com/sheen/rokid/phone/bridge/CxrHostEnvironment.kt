package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.storage.unavailable

import com.sheen.rokid.contracts.ContractError
import com.sheen.rokid.host.ResourceScope
import com.sheen.rokid.host.ScheduledHostEnvironment
import com.sheen.rokid.host.ScopedHostServices
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.DeadlineScheduler
import com.sheen.rokid.plugin.HintDevice
import com.sheen.rokid.plugin.HostServices
import com.sheen.rokid.plugin.InputPort
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.OutputSink
import com.sheen.rokid.plugin.OutputSubmission
import com.sheen.rokid.plugin.StorageHandle
import com.sheen.rokid.plugin.TaskScope
import java.util.concurrent.Executor
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

/**
 * The App-side host environment: a real display path **and** the plugin-facing providers that are genuinely wired.
 *
 * Why it was needed: `HostRuntime(1)` falls back to `MissingHostEnvironment`, whose output throws
 * `CapabilityMissing("display unavailable")` by design, and **no production `HostEnvironment` existed anywhere in
 * `main`** - every implementation lived in test code. Display hints therefore could not reach the glasses no matter
 * how correct the hint code was, and no plugin could pass the host's capability gate. Audio only worked because it is
 * wired separately through `RokidConnection`.
 *
 * The rule this type exists to keep honest: [availableCapabilities] is **derived from the providers that are actually
 * present**, not from a second hand-written list that could drift away from them. The host refuses any plugin whose
 * required capabilities are not in that set, so claiming one that cannot be answered would not enable more - it would
 * let a plugin start and then fail confusingly later.
 *
 * Two things are deliberately **absent** and must stay absent until they are approved in their own right:
 * `Capability.Audio` (the host starts `input` for audio-requiring plugins, so advertising it would add a
 * microphone-start path) and `Capability.Speaker` (a real extractor would have to decode real microphone audio
 * against the decision-labelled PCM format, and enrolment is outside the current authorization). Both therefore
 * remain unanswered and unadvertised, which is what makes `interview-assistant` still refuse to activate.
 */
class CxrHostEnvironment(
    override val clock: MonotonicClock,
    override val input: InputPort,
    /** The live display, or null while the glasses are not connected. Read per call, never cached. */
    private val liveOutput: () -> HintDevice?,
    override val scheduler: DeadlineScheduler,
    /**
     * Providers that are the same for every plugin (the model client, and nothing else today).
     *
     * [Capability.TextOutput] is refused here because the host owns it: for a granted `TextOutput` the host
     * substitutes its own arbiter-backed `OutputSink`, so an environment-supplied one would never be used.
     */
    private val providers: Map<Capability, Any> = emptyMap(),
    /**
     * Plugin-facing storage, resolved from the plugin id on every activation because a namespace is per plugin by
     * contract. Null (the default) means storage is not wired at all, and it is then not advertised either.
     */
    private val storageFor: ((String) -> StorageHandle)? = null,
    /** Plugin background work; [UnavailableTaskScope] refuses rather than silently running work inline. */
    private val taskScope: TaskScope = UnavailableTaskScope,
    /** P5 (spec §S1): the speech player, or null when TTS is unavailable or the user switch is off. */
    override val speech: com.sheen.rokid.plugin.SpeechPlayer? = null,
    /**
     * 0.5 (`D213`): whether a player is actually installed **now**.
     *
     * The 0.4 field was read once at assembly, so toggling 「语音朗读」 could not add or remove
     * `Capability.SpeechOutput` without a restart. Production passes the same live predicate the plugin's
     * `speechAvailable` uses; a hand-built environment keeps the old `speech != null` meaning.
     */
    private val speechAvailable: () -> Boolean = { speech != null },
    /** P5 (C2 fix): audio segment source for the direct-audio path, or null when not wired. */
    override val audioSegmentSource: com.sheen.rokid.plugin.AudioSegmentSource? = null,
) : ScheduledHostEnvironment {

    init {
        require(Capability.TextOutput !in providers) { "TEXT_OUTPUT_IS_OWNED_BY_THE_HOST" }
    }

    /**
     * Replaced by the host for a granted `TextOutput`, but it must still be present: `ScopedHostServices` resolves a
     * provider for every granted capability during construction.
     */
    private val hostOwnedOutput = object : OutputSink {
        override fun submit(assistance: com.sheen.rokid.contracts.Assistance): OutputSubmission = OutputSubmission.Rejected
    }

    /**
     * Everything that is genuinely wired right now: the static providers, per-plugin storage when a factory exists,
     * and the display **only while a mapping makes it real**. Derived from the same sources [services] uses, so the
     * advertised set and the answerable set cannot disagree.
     */
    override val availableCapabilities: Set<Capability>
        get() = buildSet {
            addAll(providers.keys)
            if (storageFor != null) add(Capability.Storage)
            if (liveOutput() != null) add(Capability.TextOutput)
            // P5 (spec §S1): SpeechOutput is advertised only when the player exists (engine ready AND switch on).
            // 0.5: the predicate is live, so a runtime switch changes the advertised set without a composition rebuild.
            if (speechAvailable()) add(Capability.SpeechOutput)
        }

    /**
     * A stable device that resolves the live output **per call**.
     *
     * `HostRuntime` reads `environment.output` once, when it constructs its arbiter, so the environment cannot hand it
     * a null before the connection is up - the construction itself would throw. The forwarding object keeps the
     * liveness per call while giving the host something to hold:
     *
     * - before a mapping is live, `show` **throws** `CapabilityMissing("display unavailable")`, matching the absent
     *   environment and the contract that an undisplayable hint is refused rather than silently dropped;
     * - `clear` is a no-op when nothing is displayed, because there is nothing to clear and a throw there would turn
     *   teardown into an error path.
     */
    private val forwarding = object : HintDevice {
        override fun show(assistance: com.sheen.rokid.contracts.Assistance, revision: Long,
            listener: (com.sheen.rokid.contracts.OutputStatus) -> Unit): CancellationHandle =
            (liveOutput() ?: throw ContractError.CapabilityMissing("display unavailable"))
                .show(assistance, revision, listener)

        override fun clear(sessionId: com.sheen.rokid.contracts.SessionId, generation: com.sheen.rokid.contracts.Generation) {
            liveOutput()?.clear(sessionId, generation)
        }
    }

    /**
     * The stable display handle the host holds.
     *
     * It throws while nothing can display, exactly as the absent environment does - a silent no-op would look like
     * success and lose the hint.
     */
    override val output: HintDevice get() = forwarding

    /**
     * The plugin-facing services, scoped to the plugin that is activating.
     *
     * Providers are assembled per activation because storage is per plugin; anything not assembled stays refused by
     * `ScopedHostServices`, so an unwired capability cannot be answered by accident.
     */
    override fun services(scope: ResourceScope, granted: Set<Capability>): HostServices {
        val resolved = providers.toMutableMap()
        // Only when this plugin actually asked for storage. Building it for every activation coupled the storage
        // factory's namespace whitelist to plugins that never touch storage: on device that refused the debug hint
        // probe with `UNKNOWN_PLUGIN_NAMESPACE` the moment the display finally became live, and the host could only
        // report `Failed`. A plugin that *does* request storage still gets the factory's refusal, which is correct.
        if (storageFor != null && Capability.Storage in granted) {
            resolved[Capability.Storage] = storageFor(scope.pluginId)
        }
        resolved[Capability.TextOutput] = hostOwnedOutput
        return ScopedHostServices(scope, granted, resolved, taskScope)
    }
}

/**
 * The host's audio input path, deliberately left refusing while `Capability.Audio` is not advertised.
 *
 * The host starts `environment.input` for any plugin that requires audio, and the CXR input port is the glasses
 * microphone. Wiring it here would add a microphone-start path to the App as a side effect of a display change, and
 * that needs its own approval and its own D33 reminder - so it stays refused, which is also what
 * `MissingHostEnvironment` did.
 */
internal object UnwiredInputPort : InputPort {
    override fun start(epoch: com.sheen.rokid.contracts.InputEpoch) =
        throw ContractError.CapabilityMissing("audio unavailable")

    override fun stop(epoch: com.sheen.rokid.contracts.InputEpoch) = Unit
}

/** Refuses plugin background work rather than pretending it ran; the default until a real scope is provided. */
internal object UnavailableTaskScope : TaskScope {
    override fun launch(block: () -> Unit): CancellationHandle =
        throw ContractError.CapabilityMissing("tasks unavailable")

    override fun cancelAll() = Unit
}

/**
 * Runs plugin background work on a dedicated executor, with cancellation that only cancels what it launched.
 *
 * `cancelAll` deliberately cancels pending work instead of shutting the executor down: the scope belongs to the
 * composition and outlives any one plugin's use of it.
 */
internal class ExecutorTaskScope(private val executor: Executor) : TaskScope {
    private val pending = java.util.Collections.synchronizedSet(mutableSetOf<Future<*>>())

    override fun launch(block: () -> Unit): CancellationHandle {
        val future = try {
            (executor as? java.util.concurrent.ExecutorService)?.submit(block) ?: run { executor.execute(block); null }
        } catch (_: RejectedExecutionException) {
            throw ContractError.CapabilityMissing("tasks unavailable")
        }
        future?.let { pending.add(it) }
        return object : CancellationHandle {
            override fun cancel() {
                future?.let { pending.remove(it); it.cancel(false) }
            }
        }
    }

    override fun cancelAll() {
        val snapshot = synchronized(pending) { pending.toList().also { pending.clear() } }
        snapshot.forEach { runCatching { it.cancel(false) } }
    }
}
