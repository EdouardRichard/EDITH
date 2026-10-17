package com.sheen.rokid.output

/**
 * Why the speech player is or is not ready to produce audio.
 *
 * Each value maps to a reason code shown on the capability panel and written to logs (spec §S1). The ordering of
 * checks matters: no engine wins over language, which wins over init failure. This is a pure function so it can be
 * tested without any Android framework dependency.
 */
enum class TtsAvailability {
    READY,
    NO_ENGINE,
    LANGUAGE_UNSUPPORTED,
    INIT_FAILED,
}

/**
 * Pure-function availability check from three observable facts about the platform TTS engine.
 *
 * - [enginePresent]: whether the system reported at least one TTS engine during discovery.
 * - [languageResult]: the return value of `TextToSpeech.isLanguageAvailable(Locale.SIMPLIFIED_CHINESE)`. Negative
 *   values (`LANG_MISSING_DATA = -1`, `LANG_NOT_SUPPORTED = -2`) mean the engine cannot produce Chinese audio.
 * - [initSucceeded]: whether the `TextToSpeech.OnInitListener` callback reported `SUCCESS`.
 *
 * No vendor engine package name is consulted here; the caller passes in the framework's own answers.
 */
fun availability(enginePresent: Boolean, languageResult: Int, initSucceeded: Boolean): TtsAvailability {
    if (!enginePresent) return TtsAvailability.NO_ENGINE
    if (languageResult < 0) return TtsAvailability.LANGUAGE_UNSUPPORTED
    if (!initSucceeded) return TtsAvailability.INIT_FAILED
    return TtsAvailability.READY
}
