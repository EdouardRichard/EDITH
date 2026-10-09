package edith.phone

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
 * previous one finished): the gate stays closed until the **last** playback ends. [end] never drives the depth
 * below zero, so an unbalanced `end` from a failed playback cannot leave the gate looking open forever.
 *
 * Written from the playback/audio thread and read from the detection loop, so every operation is atomic.
 */
class PlaybackGate {

    private val depth = AtomicInteger(0)

    /** A playback started. */
    fun begin() {
        depth.incrementAndGet()
    }

    /** A playback finished. Idempotent: an unmatched call does not push the depth below zero. */
    fun end() {
        depth.updateAndGet { current -> if (current > 0) current - 1 else 0 }
    }

    /** True while at least one playback is in flight. */
    val isPlaying: Boolean get() = depth.get() > 0
}
