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
     * field existed was: the assistant answering something the wearer said. It is the last parameter on purpose —
     * existing callers pass the first eight positionally.
     */
    val route: AssistantRoute = AssistantRoute.WAKE,
) {
    init {
        require(hintId.isNotBlank())
        require(sourceTurnId == null || sourceTurnId.isNotBlank())
        require(priority >= 0)
        require(text.isNotBlank())
        require(expiresAtNanos >= 0)
    }
    fun isCurrent(session: SessionId, currentGeneration: Generation): Boolean = sessionId == session && generation == currentGeneration
}

data class SpeechRequest(val voiceId: String? = null)

enum class OutputStatus { Accepted, Queued, Displayed, Expired, Rejected }
