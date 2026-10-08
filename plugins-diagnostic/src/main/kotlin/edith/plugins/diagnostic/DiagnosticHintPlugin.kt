package edith.plugins.diagnostic

import edith.contracts.Assistance
import edith.contracts.HostEvent
import edith.plugin.Capability
import edith.plugin.ConfigSchema
import edith.plugin.DeactivationReason
import edith.plugin.MonotonicClock
import edith.plugin.OutputSink
import edith.plugin.OutputSubmission
import edith.plugin.PluginContext
import edith.plugin.PluginDescriptor
import edith.plugin.PluginLifecycle

/**
 * Debug-only hint source for the T18 display acceptance.
 *
 * The shipped plugins are shells and the diagnostic text plugin needs the whole audio/ASR/speaker/model stack, so
 * until the plugin-side scene logic is delivered there is nothing in the product that submits a hint. Plan Task 18
 * Step 4 nevertheless requires **ten synthetic short hints to be sent and read while worn**, and a wearer cannot
 * verify a path that nothing drives.
 *
 * This is that driver and nothing more: it declares only `TextOutput`, submits one fixed non-sensitive Chinese
 * sentence per call, and lives in the debug-only diagnostic module - it is registered only in debug builds. It is
 * **not** a product feature and claims no model, audio or business behaviour.
 *
 * The sentences are fixed, non-personal and non-sensitive (D33's "preannounced fixed test text"): no credentials, no
 * personal data, no audio.
 */
class DiagnosticHintPlugin(
    private val clock: MonotonicClock,
    /** How long the wearer has to read one hint before it expires on the glasses. */
    private val ttlMillis: Long = 10_000,
) : PluginLifecycle {
    init { require(ttlMillis > 0) }

    override val descriptor = PluginDescriptor("p2-hint", "1.0.0", 1, "P2 Hint Probe",
        listOf(Capability.TextOutput), ConfigSchema(1))

    private val gate = Any()
    private var context: PluginContext? = null
    private var submitted = 0

    override fun activate(context: PluginContext) {
        synchronized(gate) { this.context = context }
    }

    override fun deactivate(reason: DeactivationReason) {
        synchronized(gate) { context = null }
    }

    override fun handle(event: HostEvent) = Unit

    /**
     * Submits the next fixed hint through the sink the host gave this plugin.
     *
     * Returns false when nothing can display it - the plugin is not active, or the display is not real yet - so the
     * caller can tell the user instead of reporting a hint that never existed. Never throws: this is called from a UI
     * callback.
     */
    fun submitNext(): Boolean {
        val current = synchronized(gate) {
            val c = context ?: return false
            submitted++
            c to submitted
        }
        val (pluginContext, index) = current
        val text = HINTS[(index - 1) % HINTS.size]
        val now = clock.nowNanos()
        val assistance = Assistance(
            hintId = "hint-probe-$index",
            sessionId = pluginContext.sessionId,
            generation = pluginContext.generation,
            sourceTurnId = null,
            priority = 1,
            text = text,
            optionalSpeech = null,
            expiresAtNanos = now + ttlMillis * 1_000_000,
        )
        val sink = runCatching {
            pluginContext.services.require(Capability.TextOutput) as OutputSink
        }.getOrNull() ?: return false
        return runCatching { sink.submit(assistance) }.getOrNull() == OutputSubmission.Accepted
    }

    companion object {
        /**
         * The fixed sentences a wearer reads off the glasses. Short on purpose: the layout shows at most three points
         * and the point of this list is readability, not content.
         */
        val HINTS = listOf(
            "先讲结论，再讲理由。",
            "回答控制在三点以内。",
            "先说结果，再说过程。",
            "强调与岗位相关的经验。",
            "补充一个量化指标。",
            "主动说明取舍与代价。",
            "结尾用一句话总结。",
            "放慢语速，讲清关键点。",
            "如果不确定，先说明依据。",
            "如需展开，等对方追问。",
        )
    }
}
