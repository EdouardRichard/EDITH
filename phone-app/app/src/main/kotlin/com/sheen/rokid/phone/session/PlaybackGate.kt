package com.sheen.rokid.phone.session

import java.util.concurrent.atomic.AtomicInteger

/**
 * True while this App is producing audio the microphone could pick up (TTS/playback).
 *
 * **What this is, honestly (P3.1).** P3.1 produces **no** audio output at all: hints are submitted with
 * `optionalSpeech = null` and no provider answers `Capability.SpeechOutput`. The gate therefore starts open and, in
 * this phase, **changes nothing** - it is a seam for P3.2, which is when speech output arrives and the code that
 * plays it can call [begin]/[end] around a real playback. The wake path consumes it today so the wiring is
 * exercised and cannot drift, not because there is anything to suppress yet.
 *
 * **What it must not become.** No suppression *window* may be derived from it - in particular "suppress while a
 * hint is displayed" would be wrong: a hint is on the glasses for its whole TTL, and a person saying the wake word
 * during that window is a legitimate wake that would be silently dropped. The gate is closed only for the exact
 * interval in which the App itself is emitting sound, and nothing else.
 *
 * The depth is a counter rather than a flag because playbacks can nest (one hint's speech starting before the
 * previous one finished): the gate stays closed until the **last** playback ends.
 *
 * **The counter does not clamp (fixed 2026-10-09, found by the P3.2 full regression).** It used to stop `end` at zero,
 * which reads as a guard against an unbalanced call but is not: `begin` and `end` arrive on different threads, so
 * `end` routinely runs ahead of its matching `begin`, and every decrement the clamp discarded was a **lost update** -
 * a balanced pair could leave the gate closed for good, silently suppressing the wake word from then on. A plain
 * decrement makes "balanced begin/end leaves nothing playing" true for every interleaving, which is the property that
 * matters. The accepted trade-off is the opposite one: an unmatched `end` leaves a *credit* that swallows one later
 * `begin`, costing a single un-suppressed playback (mild, transient) rather than a gate that never opens (severe,
 * permanent). [isPlaying] is `depth > 0`, so a negative depth still reads as "not playing".
 *
 * Written from the playback/audio thread and read from the detection loop, so every operation is atomic.
 */
class PlaybackGate {

    private val depth = AtomicInteger(0)

    /** A playback started. */
    fun begin() {
        depth.incrementAndGet()
    }

    /** A playback finished. Unmatched calls leave a credit rather than being discarded; see the class note. */
    fun end() {
        depth.decrementAndGet()
    }

    /** True while at least one playback is in flight. */
    val isPlaying: Boolean get() = depth.get() > 0
}
