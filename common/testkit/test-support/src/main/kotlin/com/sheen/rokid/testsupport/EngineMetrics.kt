package com.sheen.rokid.testsupport

import kotlin.math.abs
import kotlin.math.ceil

/**
 * Versioned engine-measurement record shared by the phone measurement runner and `tools/p2/report-engine.ps1`.
 * This type carries the same contract the reporter asserts; it deliberately lives in test-support so it is
 * never packaged into the production application.
 */
object EngineMetricsSchema {
    const val SCHEMA_VERSION = 1
    const val CORPUS_ID = "p2-fixed-zh-30-v1"
    const val WINDOW_SECONDS = 10.0
    const val MIN_WARMUP_SECONDS = 60.0
    const val MIN_MEASURED_SECONDS = 600.0
    const val MIN_WINDOWS = 60
    const val REQUIRED_TRIALS = 30
    const val MAX_TRIALS = 30
    const val SAMPLE_RATE_HZ = 16000
    const val FRAME_MILLIS = 20
    val THREADS = setOf(1, 2, 4)
    val ENDPOINTS = setOf(250, 350, 450)
    val PROVIDERS = setOf("cpu")
    private val DEVICE_PATTERN = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val SHA_PATTERN = Regex("^[A-Fa-f0-9]{64}$")
    private val TRIAL_PATTERN = Regex("^P2Q(00[1-9]|0[12][0-9]|030)$")
    val INVALID = "INVALID_METRICS"
    val INSUFFICIENT_DURATION = "INSUFFICIENT_DURATION"
    val INSUFFICIENT_SEMANTIC_TRIALS = "INSUFFICIENT_SEMANTIC_TRIALS"
    val INCOMPLETE_COMBINED_NATIVE_RUN = "INCOMPLETE_COMBINED_NATIVE_RUN"
    val LOST_OR_FAULTED_WORK = "LOST_OR_FAULTED_WORK"
    val RTF_P95_FAILED = "RTF_P95_FAILED"
    val BACKLOG_FAILED = "BACKLOG_FAILED"
    val SEMANTIC_ACCURACY_FAILED = "SEMANTIC_ACCURACY_FAILED"
    internal fun isDeviceId(value: String) = DEVICE_PATTERN.matches(value)
    internal fun isSha(value: String) = SHA_PATTERN.matches(value)
    internal fun isTrialId(value: String) = TRIAL_PATTERN.matches(value)
}

data class MetricsCandidate(
    val deviceId: String,
    val runtimeSha256: String,
    val provider: String,
    val threads: Int,
    val endpointMillis: Int,
    val frameMillis: Int,
    val sampleRateHz: Int,
    val modelHashes: Map<String, String>,
)

data class MetricsWindow(
    val index: Int,
    val audioSeconds: Double,
    val rtf: Double,
    val queueDepth: Int,
    val speakerCalls: Int,
    val faults: Int,
    val droppedFrames: Int,
)

data class SemanticTrial(val id: String, val correct: Boolean)

data class EngineMetrics(
    val candidate: MetricsCandidate,
    val nativeExecuted: Boolean,
    val nativeLifecyclePassed: Boolean,
    val interrupted: Boolean,
    val warmupSeconds: Double,
    val measuredSeconds: Double,
    val windows: List<MetricsWindow>,
    val semanticTrials: List<SemanticTrial>,
    /**
     * Minute-cadence resource observations and the frozen host identity. Both are evidence the reporter
     * does not read yet, so they ride along without changing selection: the verdict still comes from the
     * frozen window/trial rules alone.
     */
    val resources: List<ResourceMeasurement> = emptyList(),
    val identity: DeviceIdentity? = null,
) {
    /** Failure codes mirroring report-engine.ps1; [EngineMetricsSchema.INVALID] short-circuits structure issues. */
    fun failures(): List<String> {
        if (!isStructurallyValid()) return listOf(EngineMetricsSchema.INVALID)
        val failures = mutableListOf<String>()
        if (warmupSeconds < EngineMetricsSchema.MIN_WARMUP_SECONDS ||
            measuredSeconds < EngineMetricsSchema.MIN_MEASURED_SECONDS ||
            windows.size < EngineMetricsSchema.MIN_WINDOWS) {
            failures += EngineMetricsSchema.INSUFFICIENT_DURATION
        }
        if (semanticTrials.size != EngineMetricsSchema.REQUIRED_TRIALS) {
            failures += EngineMetricsSchema.INSUFFICIENT_SEMANTIC_TRIALS
        }
        val speakerCalls = windows.sumOf { it.speakerCalls }
        if (!nativeExecuted || !nativeLifecyclePassed || interrupted || speakerCalls == 0) {
            failures += EngineMetricsSchema.INCOMPLETE_COMBINED_NATIVE_RUN
        }
        if (windows.any { it.faults > 0 || it.droppedFrames > 0 }) {
            failures += EngineMetricsSchema.LOST_OR_FAULTED_WORK
        }
        if (rtfP95() > 0.7) failures += EngineMetricsSchema.RTF_P95_FAILED
        if (backlogGrowing()) failures += EngineMetricsSchema.BACKLOG_FAILED
        val accuracy = if (semanticTrials.isEmpty()) null
        else semanticTrials.count { it.correct }.toDouble() / semanticTrials.size
        if (accuracy == null || accuracy < 0.9) failures += EngineMetricsSchema.SEMANTIC_ACCURACY_FAILED
        return failures
    }

    fun complete(): Boolean = failures().isEmpty()

    fun rtfP95(): Double {
        val sorted = windows.map { it.rtf }.sorted()
        return sorted[ceil(0.95 * sorted.size).toInt() - 1]
    }

    private fun backlogGrowing(): Boolean {
        val queue = windows.map { it.queueDepth.toDouble() }
        val half = queue.subList(queue.size / 2, queue.size)
        var sumX = 0.0
        var sumY = 0.0
        var sumXY = 0.0
        var sumXX = 0.0
        for (index in half.indices) {
            sumX += index
            sumY += half[index]
            sumXY += index * half[index]
            sumXX += index.toDouble() * index
        }
        val denominator = half.size * sumXX - sumX * sumX
        val slope = if (denominator > 0) (half.size * sumXY - sumX * sumY) / denominator else 0.0
        return slope > 0 || half.last() > half.first() || queue.last() > 0
    }

    private fun isStructurallyValid(): Boolean {
        val candidate = candidate
        if (!EngineMetricsSchema.isDeviceId(candidate.deviceId)) return false
        if (!EngineMetricsSchema.isSha(candidate.runtimeSha256)) return false
        if (candidate.provider !in EngineMetricsSchema.PROVIDERS) return false
        if (candidate.threads !in EngineMetricsSchema.THREADS) return false
        if (candidate.endpointMillis !in EngineMetricsSchema.ENDPOINTS) return false
        if (candidate.frameMillis != EngineMetricsSchema.FRAME_MILLIS) return false
        if (candidate.sampleRateHz != EngineMetricsSchema.SAMPLE_RATE_HZ) return false
        if (candidate.modelHashes.isEmpty()) return false
        if (candidate.modelHashes.values.any { !EngineMetricsSchema.isSha(it) }) return false
        if (warmupSeconds.isNaN() || measuredSeconds.isNaN()) return false
        if (windows.isEmpty() || windows.size > 10_000) return false
        windows.forEachIndexed { index, window -> if (window.index != index + 1) return false }
        for (window in windows) {
            // A short reconciled run shortens its final window, so the frozen ten seconds is an upper bound:
            // interior windows carry exactly ten and the last window carries the remainder.
            if (window.audioSeconds <= 0 || window.audioSeconds > EngineMetricsSchema.WINDOW_SECONDS) return false
            if (window.index < windows.size && window.audioSeconds != EngineMetricsSchema.WINDOW_SECONDS) return false
            if (window.rtf.isNaN() || window.rtf.isInfinite() || window.rtf < 0) return false
            for (count in intArrayOf(window.queueDepth, window.speakerCalls, window.faults, window.droppedFrames)) {
                if (count < 0 || count > 1_000_000_000) return false
            }
        }
        if (abs(audioTotal(windows) - measuredSeconds) >= 0.000001) return false
        if (semanticTrials.size > EngineMetricsSchema.MAX_TRIALS) return false
        if (semanticTrials.map { it.id }.toSet().size != semanticTrials.size) return false
        if (semanticTrials.any { !EngineMetricsSchema.isTrialId(it.id) }) return false
        return true
    }

    fun toJson(): String {
        val candidateJson = buildString {
            append("{\"deviceId\":\"").append(escape(candidate.deviceId)).append("\",")
            append("\"runtimeSha256\":\"").append(escape(candidate.runtimeSha256)).append("\",")
            append("\"provider\":\"").append(escape(candidate.provider)).append("\",")
            append("\"threads\":").append(candidate.threads).append(',')
            append("\"endpointMillis\":").append(candidate.endpointMillis).append(',')
            append("\"frameMillis\":").append(candidate.frameMillis).append(',')
            append("\"sampleRateHz\":").append(candidate.sampleRateHz).append(',')
            append("\"modelHashes\":{")
            append(candidate.modelHashes.entries.sortedBy { it.key }.joinToString(",") { (role, hash) ->
                "\"${escape(role)}\":\"${escape(hash)}\""
            })
            append("}}")
        }
        val windowsJson = windows.joinToString(",", "[", "]") { window ->
            "{\"index\":${window.index},\"audioSeconds\":${number(window.audioSeconds)}," +
                "\"rtf\":${number(window.rtf)},\"queueDepth\":${window.queueDepth}," +
                "\"speakerCalls\":${window.speakerCalls},\"faults\":${window.faults}," +
                "\"droppedFrames\":${window.droppedFrames}}"
        }
        val trialsJson = semanticTrials.joinToString(",", "[", "]") { trial ->
            "{\"id\":\"${escape(trial.id)}\",\"correct\":${trial.correct}}"
        }
        return buildString {
            append("{\"schemaVersion\":").append(EngineMetricsSchema.SCHEMA_VERSION).append(',')
            append("\"corpusId\":\"").append(EngineMetricsSchema.CORPUS_ID).append("\",")
            append("\"candidate\":").append(candidateJson).append(',')
            append("\"nativeExecuted\":").append(nativeExecuted).append(',')
            append("\"nativeLifecyclePassed\":").append(nativeLifecyclePassed).append(',')
            append("\"interrupted\":").append(interrupted).append(',')
            append("\"warmupSeconds\":").append(number(warmupSeconds)).append(',')
            append("\"measuredSeconds\":").append(number(measuredSeconds)).append(',')
            append("\"windows\":").append(windowsJson).append(',')
            append("\"semanticTrials\":").append(trialsJson).append(',')
            append("\"resources\":").append(resources.joinToString(",", "[", "]") { it.toJson() }).append(',')
            append("\"identity\":").append(identity?.toJson() ?: "null")
            append('}')
        }
    }

    private fun number(value: Double): String =
        if (value == value.toLong().toDouble()) value.toLong().toString() else value.toString()

    private fun escape(value: String): String = buildString {
        for (character in value) {
            when (character) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
            }
        }
    }

    companion object {
        @Suppress("unused")
        internal fun audioTotal(windows: List<MetricsWindow>): Double = windows.sumOf { it.audioSeconds }

        @Suppress("unused")
        internal fun withinTolerance(a: Double, b: Double): Boolean = abs(a - b) < 0.000001
    }
}
