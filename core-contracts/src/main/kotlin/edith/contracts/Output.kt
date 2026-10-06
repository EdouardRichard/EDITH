package edith.contracts

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
