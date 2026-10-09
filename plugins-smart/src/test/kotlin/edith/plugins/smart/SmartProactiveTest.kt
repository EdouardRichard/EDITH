package edith.plugins.smart

import edith.contracts.AcousticLabel
import kotlin.test.*

/**
 * Task 7: the cheap filter that runs before any model request.
 *
 * Every assertion here is about a **local** decision: no gateway, no plugin, no network. The order of the rules is
 * part of the contract (`DISARMED -> BUSY -> NOISY -> SHORT -> COOLDOWN -> DEDUP`), because it decides which reason a
 * trigger is refused for when more than one applies.
 */
class SmartProactiveTest {

    private val cooldown = 60_000_000_000L

    private fun trigger(text: String, now: Long, label: AcousticLabel? = AcousticLabel.Speech,
        foreground: String? = null) = ProactiveTrigger(text, label, foreground, now)

    private fun filter(sensitivity: ProactiveSensitivity = ProactiveSensitivity.MEDIUM) =
        SmartProactive(cooldownNanos = cooldown, sensitivity = sensitivity)

    @Test
    fun `an unarmed filter refuses without consuming the cooldown`() {
        val proactive = filter()

        assertEquals(ProactiveSkip.DISARMED,
            proactive.admit(trigger("这是一句足够长的转写", 0), armed = false, busy = false))
        assertNull(proactive.admit(trigger("这是一句足够长的转写", 1), armed = true, busy = false),
            "a DISARMED refusal must not start the cooldown, or turning the switch on would be blocked")
    }

    @Test
    fun `a turn already in flight refuses`() {
        assertEquals(ProactiveSkip.BUSY,
            filter().admit(trigger("这是一句足够长的转写", 0), armed = true, busy = true))
    }

    @Test
    fun `noisy audio refuses and consumes nothing`() {
        val proactive = filter()

        assertEquals(ProactiveSkip.NOISY,
            proactive.admit(trigger("这是一句足够长的转写", 0, label = AcousticLabel.Noisy), true, false))
        assertNull(proactive.admit(trigger("这是一句足够长的转写", 1), true, false),
            "once the noise is gone the next utterance must still be admitted")
    }

    @Test
    fun `the transcript has to clear the sensitivity threshold`() {
        assertEquals(ProactiveSkip.SHORT,
            filter(ProactiveSensitivity.LOW).admit(trigger("六个字的一句话", 0), true, false))
        assertNull(filter(ProactiveSensitivity.HIGH).admit(trigger("六个字的一句话", 0), true, false),
            "HIGH admits the same six characters")
    }

    @Test
    fun `a second attempt inside the cooldown is refused and restarts the window`() {
        val proactive = filter()
        proactive.noteRequest(0)

        assertEquals(ProactiveSkip.COOLDOWN,
            proactive.admit(trigger("足够长的转写内容", cooldown - 1), true, false))
        assertEquals(ProactiveSkip.COOLDOWN, proactive.admit(trigger("足够长的转写内容", cooldown), true, false),
            "the refusal itself restarts the window")
        // Two refusals have slid the window to `cooldown`, so the first instant that clears it is `2 * cooldown`.
        assertNull(proactive.admit(trigger("足够长的转写内容", 2 * cooldown), true, false),
            "and past the restarted window the next utterance is admitted again")
    }

    @Test
    fun `a repeated sentence is refused once the cooldown has passed`() {
        val proactive = filter()
        proactive.noteDedup(trigger("今天下午三点开会", 0))

        assertEquals(ProactiveSkip.DEDUP,
            proactive.admit(trigger("  今天下午三点开会 ", cooldown + 1), true, false))
        assertEquals(ProactiveSkip.DEDUP, proactive.admit(trigger("今天下午三点开会", 2 * cooldown + 2), true, false),
            "case and whitespace do not make it a new sentence")
        assertNull(proactive.admit(trigger("今天下午三点开会", 3 * cooldown + 3, foreground = "com.other"), true, false),
            "the same words about another app are a different event")
    }

    @Test
    fun `a refusal by the upload gate also starts the cooldown`() {
        val proactive = filter()
        proactive.noteGateRefused(0)

        assertEquals(ProactiveSkip.COOLDOWN, proactive.admit(trigger("足够长的转写内容", 1), true, false))
    }
}
