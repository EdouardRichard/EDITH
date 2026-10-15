package edith.phone

import edith.contracts.Assistance
import edith.contracts.Generation
import edith.contracts.SessionId
import edith.contracts.SpeechRequest
import edith.plugin.MonotonicClock
import edith.plugin.SpeechPlayer

/**
 * Phase C (spec §6.4): the assistant's answer to being woken, said immediately.
 *
 * The flow the spec fixes is `唤醒词检测成功 → 【同步】TTS 播报"在的"（固定短语，不等 LLM）→ 加载动画`, and the reason is
 * latency: everything after the wake involves a model round trip, so the wearer needs to hear that they were heard
 * before any of it finishes.
 *
 * Three deliberate properties, each of which is a way this could go wrong:
 *
 * 1. **It never reaches the display.** It is not a hint, so it is not in the conversation and not on the glasses -
 *    the wearer is watching the loading ring, and a sentence saying "I am here" would be the app narrating itself.
 * 2. **It never waits.** [speakWakeAcknowledgement] asks the player and returns; there is no callback to await and no
 *    model in the path.
 * 3. **It cannot take the wake down with it.** This runs on the same path that emits `WakeDetected`, so a player that
 *    throws must cost the wearer a phrase, never the wake itself.
 *
 * The 「语音朗读」 switch is honoured by the caller passing `null`, not by a second check here: a host with no player is
 * a host whose user turned speech off (P5), and the silent path is a normal state rather than a degraded one.
 */
object WakeAcknowledgement {

    /** The exact phrase, fixed by the spec: short, instant, and the same every time. */
    const val PHRASE = "在的"

    /** How long the acknowledgement's `Assistance` stays valid; it is a moment, not a message. */
    const val VALID_FOR_NANOS = 3_000_000_000L
}

/**
 * States [WakeAcknowledgement.PHRASE] through [player], and returns what was asked for.
 *
 * Returns the [Assistance] whether or not a player was present, so a caller (and a test) can see what the wake would
 * have said without a speech engine attached.
 */
internal fun speakWakeAcknowledgement(
    player: SpeechPlayer?,
    sessionId: SessionId,
    generation: Generation,
    clock: MonotonicClock,
): Assistance {
    val now = clock.nowNanos()
    val assistance = Assistance(
        hintId = "wake-ack",
        sessionId = sessionId,
        generation = generation,
        // No turn produced this: it is the acknowledgement *of* the wake, and attributing it to a turn would let a
        // stale acknowledgement look like an answer.
        sourceTurnId = null,
        priority = 2,
        text = WakeAcknowledgement.PHRASE,
        optionalSpeech = SpeechRequest(),
        expiresAtNanos = now + WakeAcknowledgement.VALID_FOR_NANOS,
    )
    // Swallowed on purpose: see property 3 above. A speech engine that has died must not be able to swallow a wake.
    runCatching { player?.play(assistance) { } }
    return assistance
}
