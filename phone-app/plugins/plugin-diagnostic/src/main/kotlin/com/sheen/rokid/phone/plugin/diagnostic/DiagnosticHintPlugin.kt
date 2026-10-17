package com.sheen.rokid.phone.plugin.diagnostic

import com.sheen.rokid.contracts.Assistance
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.output.HintReadingBudget
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.ConfigSchema
import com.sheen.rokid.plugin.DeactivationReason
import com.sheen.rokid.plugin.MonotonicClock
import com.sheen.rokid.plugin.OutputSink
import com.sheen.rokid.plugin.OutputSubmission
import com.sheen.rokid.plugin.PluginContext
import com.sheen.rokid.plugin.PluginDescriptor
import com.sheen.rokid.plugin.PluginLifecycle

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

    /** Given-text submissions, counted apart from [submitted] so a measurement never shifts the fixed-list rotation. */
    private var measured = 0

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
        return submit(pluginContext, "hint-probe-$index", HINTS[(index - 1) % HINTS.size])
    }

    /**
     * Submits [text] exactly as given, through the same sink and with the same TTL as [submitNext].
     *
     * P3.2 Stage 0's capacity probe: the fixed list above is deliberately short, so measuring what the glasses do with
     * a hint of a **known** length needs an entry point that neither reshapes nor trims the text - otherwise the
     * measurement would be of a different length than the one recorded. A blank text is refused rather than rendered
     * as an empty hint, and the fixed-list rotation is left untouched so a worn-readability pass can still be run
     * afterwards from the same probe.
     */
    fun submitText(text: String): Boolean {
        if (text.isBlank()) return false
        val current = synchronized(gate) {
            val c = context ?: return false
            measured++
            c to measured
        }
        val (pluginContext, index) = current
        return submit(pluginContext, "hint-probe-measured-$index", text)
    }

    /** The one submission path: resolve the sink the host gave this plugin, submit, and report acceptance honestly. */
    private fun submit(pluginContext: PluginContext, hintId: String, text: String): Boolean {
        val now = clock.nowNanos()
        val assistance = Assistance(
            hintId = hintId,
            sessionId = pluginContext.sessionId,
            generation = pluginContext.generation,
            sourceTurnId = null,
            priority = 1,
            text = text,
            // Spec §S6: diagnostic hints keep optionalSpeech = null. TTS exercise uses the smart assistant's
            // proactive path instead.
            optionalSpeech = null,
            // P3.2-CLIP: this instrument exists to push hints the panel cannot show at once, so it has to hold them
            // long enough to slide through; the base 10 s is kept and the slide is added on top.
            expiresAtNanos = now + HintReadingBudget.readMillis(text.length, ttlMillis) * 1_000_000,
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
