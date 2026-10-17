package com.sheen.rokid.glasses

/**
 * What the view should do about the scroll when the conversation or the hint changes (device report, 2026-10-15).
 *
 * The wearer heard a fresh answer while the screen still showed the previous, longer hint. Nothing was wrong with what
 * was sent or stored — the entry was in the log — but the scroll never brought it into view: the reveal held still
 * because the wearer had paused it, and when it does run it travels the whole scrollback at reading pace.
 */
enum class Reveal {
    /** Do nothing: there is nothing new to show. */
    HOLD,

    /** Bring the newest entry into view at once, in a short bounded slide. */
    IMMEDIATE,

    /** Slide at reading pace — the owner's requirement for reading *within* a long hint. */
    PACED,
}

/**
 * The three inputs, and the rule they produce.
 *
 * **A new entry outranks the pause.** Pausing exists so a slide never fights the person reading (who pressed the pad
 * to stop it); it was never meant to hide the next message, and on the device it did exactly that — the answer to a
 * question the wearer had just asked sat below the fold until the hint expired.
 *
 * **A new entry also outranks the pacing.** The paced slide is computed over the *whole* scrollback, so with one long
 * previous hint in the window it takes nearly seventeen seconds to reach the newest entry — measured, not estimated
 * (`scrollMillis=16800` in the glasses' log for the turn in question). That is the right way to read a long hint and
 * the wrong way to deliver a new one.
 */
object HintReveal {

    fun forChange(promptly: Boolean, paused: Boolean, atNewest: Boolean): Reveal = when {
        promptly -> Reveal.IMMEDIATE
        paused -> Reveal.HOLD
        atNewest -> Reveal.HOLD
        else -> Reveal.PACED
    }
}
