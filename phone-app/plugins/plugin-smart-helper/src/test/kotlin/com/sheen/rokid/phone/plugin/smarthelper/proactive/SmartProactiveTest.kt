package com.sheen.rokid.phone.plugin.smarthelper.proactive

import com.sheen.rokid.contracts.AcousticLabel
import kotlin.test.*

/**
 * Task 7: the cheap filter that runs before any model request.
 *
 * Every assertion here is about a **local** decision: no gateway, no plugin, no network. The order of the rules is part
 * of the contract (`DISARMED -> BUSY -> NOISY -> VAGUE -> COOLDOWN -> DEDUP`), because it decides which reason a
 * trigger is refused for when more than one applies.
 *
 * **P3.5 §W3 changed what "the transcript clears the gate" means.** It used to be a length threshold, so any long
 * enough filler would pass; it is now a feature rule ([admitByFeatures]), so a test that wants "this utterance would
 * be admitted" has to use an utterance that carries a real request. [ADMITTED] is that utterance, and every test whose
 * subject is **not** the content judgement uses it, deliberately, so it is testing the rule it names.
 *
 * Assertion messages come last (`kotlin.test`).
 */
class SmartProactiveTest {

    private val cooldown = 60_000_000_000L

    private fun trigger(text: String, now: Long, label: AcousticLabel? = AcousticLabel.Speech,
        foreground: String? = null) = ProactiveTrigger(text, label, foreground, now)

    private fun filter(sensitivity: ProactiveSensitivity = ProactiveSensitivity.MEDIUM) =
        SmartProactive(cooldownNanos = cooldown, sensitivity = sensitivity)

    private companion object {
        /** An utterance the feature rule admits: a real question, so tests about the *other* gates are not silently
         * also testing (and failing) this one. */
        const val ADMITTED = "现在几点了"

        /** Long, but carrying no request at all - the shape the feature rule refuses as `VAGUE`. */
        const val NO_REQUEST = "今天天气不错我一会儿出门走走看看"
    }

    @Test
    fun `an unarmed filter refuses without consuming the cooldown`() {
        val proactive = filter()

        assertEquals(ProactiveSkip.DISARMED,
            proactive.admit(trigger(ADMITTED, 0), armed = false, busy = false))
        assertNull(proactive.admit(trigger(ADMITTED, 1), armed = true, busy = false),
            "a DISARMED refusal must not start the cooldown, or turning the switch on would be blocked")
    }

    @Test
    fun `a turn already in flight refuses`() {
        assertEquals(ProactiveSkip.BUSY,
            filter().admit(trigger(ADMITTED, 0), armed = true, busy = true))
    }

    @Test
    fun `noisy audio refuses and consumes nothing`() {
        val proactive = filter()

        assertEquals(ProactiveSkip.NOISY,
            proactive.admit(trigger(ADMITTED, 0, label = AcousticLabel.Noisy), true, false))
        assertNull(proactive.admit(trigger(ADMITTED, 1), true, false),
            "once the noise is gone the next utterance must still be admitted")
    }

    @Test
    fun `the content judgement is the feature rule, not a length gate`() {
        // The regression this pins: the old rule refused a 5-character real question because it was short, while
        // admitting a 14-character piece of chatter because it was long.
        assertNull(
            filter(ProactiveSensitivity.LOW).admit(trigger(ADMITTED, 0), true, false),
            "a short real question must now be admitted even at the strictest sensitivity",
        )
        assertEquals(
            ProactiveSkip.VAGUE,
            filter(ProactiveSensitivity.LOW).admit(trigger(NO_REQUEST, 0), true, false),
            "and a long utterance with no request must now be refused",
        )
    }

    @Test
    fun `a second attempt inside the cooldown is refused and restarts the window`() {
        val proactive = filter()
        proactive.noteRequest(0)

        assertEquals(ProactiveSkip.COOLDOWN,
            proactive.admit(trigger(ADMITTED, cooldown - 1), true, false))
        assertEquals(ProactiveSkip.COOLDOWN, proactive.admit(trigger(ADMITTED, cooldown), true, false),
            "the refusal itself restarts the window")
        // Two refusals have slid the window to `cooldown`, so the first instant that clears it is `2 * cooldown`.
        assertNull(proactive.admit(trigger(ADMITTED, 2 * cooldown), true, false),
            "and past the restarted window the next utterance is admitted again")
    }

    @Test
    fun `a repeated sentence is refused once the cooldown has passed`() {
        val proactive = filter()
        proactive.noteDedup(trigger(ADMITTED, 0))

        assertEquals(ProactiveSkip.DEDUP,
            proactive.admit(trigger("  $ADMITTED ", cooldown + 1), true, false))
        assertEquals(ProactiveSkip.DEDUP, proactive.admit(trigger(ADMITTED, 2 * cooldown + 2), true, false),
            "case and whitespace do not make it a new sentence")
        assertNull(proactive.admit(trigger(ADMITTED, 3 * cooldown + 3, foreground = "com.other"), true, false),
            "the same words about another app are a different event")
    }

    @Test
    fun `a refusal after a settled request already restarts the window`() {
        val proactive = filter()
        // Same effect as a request: a refusal must not let the next utterance retry immediately.
        proactive.noteRefused(0)

        assertEquals(ProactiveSkip.COOLDOWN, proactive.admit(trigger(ADMITTED, 1), true, false))
    }

    /**
     * `D53`① removed the session upload consent, so there is no upload refusal left to report.
     *
     * Pinned as a **vocabulary** test because the skip reasons are the device log's contract (`P3_PROACTIVE skip
     * reason=…`): leaving `GATE` in the enum would let a future caller log a refusal that can no longer happen, and an
     * acceptance run would then read a gate that does not exist.
     */
    @Test
    fun `there is no upload gate skip reason any more`() {
        assertFalse(
            ProactiveSkip.entries.any { it.name == "GATE" },
            "D53①: nothing may report an upload-gate skip: ${ProactiveSkip.entries}",
        )
    }

    /**
     * The same rule as `GATE`, applied to the reason the feature rule retired.
     *
     * `SHORT` meant "below the length threshold", and no candidate is refused for length any more - the length signal is
     * one feature among several. Leaving it in the enum would let a caller log a refusal the rule cannot produce, and an
     * acceptance run would then read a length gate that no longer exists.
     */
    @Test
    fun `there is no length-only skip reason any more`() {
        assertFalse(
            ProactiveSkip.entries.any { it.name == "SHORT" },
            "P3.5 §W3: 长度不再是门，不得再报 SHORT: ${ProactiveSkip.entries}",
        )
    }
}
