package com.sheen.rokid.output

import kotlinx.serialization.json.*

enum class TraceMark {
    AUDIO_RECEIVED, SPEECH_END, ASR_PARTIAL, ASR_FINAL, SPEAKER_READY, MANUAL_DECISION,
    REQUEST_START, FIRST_TOKEN, FIRST_READABLE_HINT, DISPLAY_ACK,
}
data class TraceRecord(val sessionId: String, val requestId: String?, val mark: TraceMark,
    val timeNanos: Long, val safeCode: String?, val queuedBytes: Int?, val versionHash: String?)

class TraceRecorder {
    private val records = ArrayDeque<TraceRecord>()
    private val uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    private val sessionPattern = Regex("s-$uuid")
    private val requestPattern = Regex("r-$uuid")
    private val safeCodes = setOf("HINT_TOO_LONG", "HINT_INVALID_UNICODE", "MODEL_FAILED", "MODEL_INCOMPLETE",
        "MODEL_CANCELLED", "MODEL_DEADLINE", "REQUEST_DENIED")
    @Synchronized fun record(record: TraceRecord) {
        if (!sessionPattern.matches(record.sessionId) ||
            (record.requestId != null && !requestPattern.matches(record.requestId)) || record.timeNanos < 0 ||
            (record.safeCode != null && record.safeCode !in safeCodes) ||
            (record.queuedBytes != null && record.queuedBytes < 0) ||
            (record.versionHash != null && !Regex("[0-9a-fA-F]{64}").matches(record.versionHash))) return
        if (records.size == 4096) records.removeFirst()
        records.addLast(record)
    }
    @Synchronized fun snapshot(): List<TraceRecord> = records.toList()
    fun toJson(): String = buildJsonArray {
        snapshot().forEach { r -> add(buildJsonObject {
            put("sessionId", r.sessionId); put("requestId", r.requestId?.let(::JsonPrimitive) ?: JsonNull)
            put("mark", r.mark.name); put("timeNanos", r.timeNanos)
            put("safeCode", r.safeCode?.let(::JsonPrimitive) ?: JsonNull)
            put("queuedBytes", r.queuedBytes?.let(::JsonPrimitive) ?: JsonNull)
            put("versionHash", r.versionHash?.let(::JsonPrimitive) ?: JsonNull)
        }) }
    }.toString()
}
