package com.sheen.rokid.output

/**
 * 0.24 spec §3.3 / §5.3: the official 「乐奇」 conversation, as wire statuses and pure decisions.
 *
 * Every value here is the decompiled official behavior (`AIModeManager`, `AiAssistantDialog`, `AiAdapter`), not a
 * redesign. Two distinctions drive the whole file: status 0 is `showNoneView`, **not** the `showIdleStatus` idle
 * state, and status 3 is the only status that both disables double-click exit and asks the wearer to interrupt.
 *
 * This object is the one place these rules are decided, so the phone state machine, the wire codec (`STATE.status`)
 * and the glasses panel controller cannot each invent their own; a drift fails a JVM test instead of surfacing only
 * on the glasses. There are no Android imports here on purpose: these decisions must be testable without the device.
 */
enum class OfficialStatus(val wire: Int) {
    EMPTY(0),
    LISTENING(1),
    ASR(2),
    ANSWERING(3),
    ASR_END(4),
    AUDIO_FINISHED(5),

    /** The official JSUI card status (6) is out of scope; the tool status (7) is not. */
    TOOL(7),
}

/** The two outcomes the official BACK key has while the panel is up (spec §3.3). */
enum class BackAction { EXIT, STOP }

object OfficialConversation {

    /** Official `RokidAIController.TIME_OUT`: 10 s of silence while listening closes (or idles) the panel. */
    const val LISTEN_TIMEOUT_MILLIS = 10_000L

    /**
     * Official `TIME_WAITE_QUIT_TTS`: after a pinned panel drops to idle, the service closes it 120 s later.
     *
     * The value is code-level (J); the service-side late exit has not been observed on device and stays
     * `NotMeasured` until S3.
     */
    const val PINNED_CLOSE_MILLIS = 120_000L

    /**
     * Official `RokidAIController.TIME_WAITE_TTS`: how long the official assistant waits for the TTS result.
     *
     * The official scheduler starts this wait at ASR end and, when it fires, plays a cue and re-arms itself with
     * `TIME_WAITE_TTS_INTERVAL` (4 s) forever (`AiHandlerScheduler`, message 3). That makes it a **cue cadence**, not an
     * end-of-turn deadline: the official assistant never treats one 1 200 ms expiry as "the answer has finished".
     *
     * R106 (user report 2026-10-20): EDITH used this value as the terminal liveness bound for `SpeechFinished`, so a
     * long TTS that outlived the answer's display window plus 1.2 s was cut by the next continuation/teardown. The
     * terminal bound is now [SPEECH_LIVENESS_MILLIS]; this constant keeps the decompiled official value for the cue
     * cadence it really is.
     */
    const val SPEECH_WAIT_MILLIS = 1_200L

    /**
     * R106: the bounded wait for `SpeechFinished` once the answer leaves the display window.
     *
     * Long TTS legitimately outlives the hint's reading window, and a finished playback is what gates the next round,
     * so the old `display window + 1 200 ms` bound ended turns the wearer could still hear. `ConversationCycle` now
     * waits up to this long after the answer's own display window (later of "now" and the reported window end) and only
     * then records `P3_CONV no_display`. The wait stays **bounded** on purpose: a host that never reports the end must
     * not leave the panel answering forever (spec §5.5 line 324), and 120 s mirrors the official
     * [PINNED_CLOSE_MILLIS] scale - longer than any answer the wearer is still hearing, short enough to repair a lost
     * event.
     */
    const val SPEECH_LIVENESS_MILLIS = 120_000L

    /**
     * Official `launcher_chatbox_tips_exit`. The 0.24 rules own the copy; a consumer (the panel, its render model)
     * must reference these constants instead of restating the literals.
     */
    const val TIPS_EXIT = "滑动翻页 / 双击退出"

    /** Official `launcher_chatbox_tips_stop`, shown only while pinned and answering. */
    const val TIPS_STOP = "滑动翻页 / 双击打断"

    /**
     * Whether the listener indicator (the ASR "…" row with the listener orb) is on screen.
     *
     * It belongs to the two waiting statuses only. [idle] is the separate `showIdleStatus` state and hides it;
     * [hiSeen] deliberately does not change the answer: official `showListenerUI` never restores the Hi line, so a
     * continuing turn still listens with "…" after Hi has already been shown. [hiVisible] decides the Hi text.
     */
    fun listeningVisible(status: OfficialStatus, idle: Boolean, hiSeen: Boolean): Boolean =
        !idle && (status == OfficialStatus.EMPTY || status == OfficialStatus.LISTENING)

    /**
     * Whether the "…" ASR loading row is visible.
     *
     * `AiAdapter` shows `imv_asr_loading` for status 0 and 1 and hides it from status 2 on, where the user's own
     * text (2) or the assistant's row (3/5) replaces it.
     */
    fun asrLoadingVisible(status: OfficialStatus): Boolean =
        status == OfficialStatus.EMPTY || status == OfficialStatus.LISTENING

    /**
     * Whether `Hi, 我在听` is visible.
     *
     * `showNoneView` (status 0) sets `idTvSpeak` visible, `showListenerUI` (status 1) never touches it, and the
     * later statuses hide it. So it appears on the first open of a listening status and never on the automatic
     * next turn — continuous dialogue must not re-show it.
     */
    fun hiVisible(status: OfficialStatus, firstOpen: Boolean): Boolean =
        firstOpen && (status == OfficialStatus.EMPTY || status == OfficialStatus.LISTENING)

    /**
     * The bottom-left tip.
     *
     * The official text switches to "interrupt" only when pinned **and** status 3; every other combination keeps
     * the exit copy, even while pinned (spec §3.3).
     */
    fun tipsText(status: OfficialStatus, pinned: Boolean): String =
        if (pinned && status == OfficialStatus.ANSWERING) TIPS_STOP else TIPS_EXIT

    /**
     * What BACK does: unpinned always exits; pinned while answering interrupts and keeps the panel; pinned in any
     * other status exits (spec §3.3).
     */
    fun backAction(status: OfficialStatus, pinned: Boolean): BackAction =
        if (pinned && status == OfficialStatus.ANSWERING) BackAction.STOP else BackAction.EXIT

    /**
     * Whether pinning stops the scrolling ticker.
     *
     * `D133`'s "pin only stops/restarts the ticker" is replaced by 0.24: pin is one-way "do not auto-exit", and the
     * ticker stops only when the wearer scrolls, reaches the end, or the panel hides (spec §3.3/§5.3).
     */
    fun pinStopsTicker(): Boolean = false
}
