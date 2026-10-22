package com.sheen.rokid.contracts

enum class OutputPriority { LOW, NORMAL, HIGH, URGENT }

data class Assistance(
    val hintId: String,
    val sessionId: SessionId,
    val generation: Generation,
    val sourceTurnId: String?,
    val priority: Int,
    val text: String,
    val optionalSpeech: SpeechRequest?,
    val expiresAtNanos: Long,
    /**
     * Which conversation this hint belongs to (Phase C, spec §6.6).
     *
     * The glasses now show one conversation per route, so a hint that did not say where it came from could only be
     * filed by guessing. The default is [AssistantRoute.WAKE] because that is what every hint built before this
     * field existed was: the assistant answering something the wearer said. It precedes [emitId] on purpose —
     * existing callers pass the first eight parameters positionally.
     */
    val route: AssistantRoute = AssistantRoute.WAKE,
    /**
     * 0.24 S3 Task 14 fix (C1): the identity of **this emit**, not of the turn.
     *
     * [hintId] names the conversation entry: a task turn emits one entry, and the closing answer / a fallback / a
     * mid-run notification that supersedes it all keep the same [hintId] (spec §6: `say-${hintId}` is one row, final
     * `SHOW` replaces the same entry). The display arbiter therefore reports the **replaced** item's `Rejected` under
     * the same [hintId] as the item that replaced it, and a host event alone cannot tell the two apart. This id is
     * minted by the plugin for every `submit`, travels unchanged through the arbiter and back in
     * [OutputStateEvent]/[SpeechFinished], and lets the plugin keep a terminal event for a superseded emit from
     * settling the current one. It is phone-internal; nothing about it is on the wire.
     */
    val emitId: String? = null,
) {
    init {
        require(hintId.isNotBlank())
        require(sourceTurnId == null || sourceTurnId.isNotBlank())
        require(priority >= 0)
        require(text.isNotBlank())
        require(expiresAtNanos >= 0)
        require(emitId == null || emitId.isNotBlank())
    }
    fun isCurrent(session: SessionId, currentGeneration: Generation): Boolean = sessionId == session && generation == currentGeneration
}

data class SpeechRequest(val voiceId: String? = null)

enum class OutputStatus { Accepted, Queued, Displayed, Expired, Rejected }
