package com.sheen.rokid.testsupport

/**
 * Measurement-window arithmetic for the T16 combined benchmark, kept pure so it is testable off-device.
 *
 * The approved measurement is a 60 s warmup followed by at least 600 s of combined replay. Windows are
 * half-open `[start, end)` intervals of ten seconds each, so a 600 s run yields exactly 60 measured
 * windows and the interval that closes at the end of the run — the final-drain window — is kept rather
 * than discarded, which the earlier scaffold runner got wrong.
 */
class BenchmarkPlan(val measuredSeconds: Long) {
    init {
        require(measuredSeconds > 0) { "MEASURED_SECONDS_MUST_BE_POSITIVE" }
    }

    val measuredMillis: Long = measuredSeconds * 1000
    val windowMillis: Long = (EngineMetricsSchema.WINDOW_SECONDS * 1000).toLong()
    /** Every whole or partial window that the measured interval covers, including the final one. */
    val windowCount: Int = ((measuredMillis + windowMillis - 1) / windowMillis).toInt()

    /** Index 0 means "warmup" and never counts as a measured window. */
    fun windowIndexAt(elapsedMillis: Long): Int = when {
        elapsedMillis < 0 -> 0
        elapsedMillis >= measuredMillis -> windowCount
        else -> (elapsedMillis / windowMillis).toInt() + 1
    }

    fun isWarmup(elapsedMillis: Long): Boolean = elapsedMillis < 0

    fun windowStartMillis(index: Int): Long {
        require(index in 1..windowCount) { "WINDOW_INDEX_OUT_OF_RANGE" }
        return (index - 1) * windowMillis
    }

    fun windowEndMillis(index: Int): Long {
        require(index in 1..windowCount) { "WINDOW_INDEX_OUT_OF_RANGE" }
        return minOf(index * windowMillis, measuredMillis)
    }

    /** The window that owns each scheduled resource sample, one per minute of the measured interval. */
    fun resourceOwnerIndices(): List<Int> {
        val owner = RESOURCE_INTERVAL_MILLIS / windowMillis
        val indices = mutableListOf<Int>()
        var index = owner
        while (index <= windowCount) {
            indices += index.toInt()
            index += owner
        }
        return indices
    }

    /** The runner's first resource sample lands one minute after measurement start, not at its start. */
    fun firstResourceOffsetMillis(): Long = RESOURCE_INTERVAL_MILLIS

    /**
     * Window that owns a resource sample taken at [elapsedMillis]. A sample taken exactly at the end of the
     * measured interval belongs to the final window, so it is not dropped by falling one past the last one.
     */
    fun resourceOwnerAt(elapsedMillis: Long): Int {
        require(elapsedMillis >= 0) { "NEGATIVE_RESOURCE_OFFSET" }
        return windowIndexAt(elapsedMillis).coerceIn(1, windowCount)
    }

    fun isSufficientForSelection(): Boolean =
        measuredSeconds >= EngineMetricsSchema.MIN_MEASURED_SECONDS.toLong() &&
            windowCount >= EngineMetricsSchema.MIN_WINDOWS

    /**
     * The reporter requires `sum(audioSeconds) == measuredSeconds`. A replay that loses wall-clock time
     * must therefore reconcile the measured duration down to the audio that actually arrived, rather than
     * reporting a nominal 600 s over less audio, and it must never round up past the window capacity.
     */
    fun reconciledMeasuredSeconds(observedSeconds: Double): Double {
        require(observedSeconds > 0 && observedSeconds <= windowCount * EngineMetricsSchema.WINDOW_SECONDS) {
            "OBSERVED_SECONDS_OUT_OF_RANGE"
        }
        return observedSeconds
    }

    fun newAccumulator(): BenchmarkAccumulator = BenchmarkAccumulator(windowCount)

    companion object {
        /** One resource observation per minute of measured audio. */
        const val RESOURCE_INTERVAL_MILLIS = 60_000L
    }
}

/**
 * Collects per-window measurement facts. Audio work, speaker jobs, faults, drops and queue depth are all
 * attributed to the window that owned the frame, so a boundary can never split a frame's count from its
 * cost and no scheduled speaker job can be silently omitted.
 */
class BenchmarkAccumulator(private val windowCount: Int) {
    private val frames = LongArray(windowCount)
    private val workNanos = LongArray(windowCount)
    private val speakerJobs = IntArray(windowCount)
    private val faults = IntArray(windowCount)
    private val drops = IntArray(windowCount)
    private val queueDepth = IntArray(windowCount)
    private val resources = HashMap<Int, ResourceSample>()

    fun record(ownerIndex: Int, frames: Long, workNanos: Long, speakerJobs: Int) {
        synchronized(this) {
            if (ownerIndex !in 1..windowCount) return
            val slot = ownerIndex - 1
            this.frames[slot] += frames
            this.workNanos[slot] += workNanos
            this.speakerJobs[slot] += speakerJobs
        }
    }

    /**
     * Adds work that belongs to an already-counted window, such as an asynchronous speaker extraction. The
     * reporter's RTF convention is combined VAD+ASR+speaker work, so leaving speaker time out would
     * understate the load the decision is based on.
     */
    fun recordWork(ownerIndex: Int, workNanos: Long) {
        synchronized(this) {
            if (ownerIndex in 1..windowCount && workNanos > 0) this.workNanos[ownerIndex - 1] += workNanos
        }
    }

    fun recordFault(ownerIndex: Int) {
        synchronized(this) { if (ownerIndex in 1..windowCount) faults[ownerIndex - 1]++ }
    }

    fun recordDrop(ownerIndex: Int) {
        synchronized(this) { if (ownerIndex in 1..windowCount) drops[ownerIndex - 1]++ }
    }

    fun recordQueueDepth(ownerIndex: Int, bytes: Int) {
        synchronized(this) { if (ownerIndex in 1..windowCount) queueDepth[ownerIndex - 1] = maxOf(0, bytes) }
    }

    fun recordResource(ownerIndex: Int, sample: ResourceSample) {
        synchronized(this) { if (ownerIndex in 1..windowCount) resources[ownerIndex] = sample }
    }

    /**
     * Builds the reporter-facing window rows.
     *
     * Every window claims the frozen [EngineMetricsSchema.WINDOW_SECONDS] of audio except the last, which
     * carries whatever the reconciled measured duration leaves. The reporter asserts
     * `sum(audioSeconds) == measuredSeconds` exactly, so a run that lost wall-clock time must shorten its
     * final window rather than keep claiming a full ten seconds for it.
     *
     * A measured window that processed no frames is counted as dropped work: leaving its RTF at zero would
     * make an unprocessed window look like a fast one and help the RTF gate that nothing was measured for.
     */
    fun windows(measuredSeconds: Double = windowCount * EngineMetricsSchema.WINDOW_SECONDS): List<MetricsWindow> {
        require(measuredSeconds > 0 && measuredSeconds <= windowCount * EngineMetricsSchema.WINDOW_SECONDS) {
            "MEASURED_SECONDS_OUT_OF_RANGE"
        }
        // Round to whole milliseconds: `599.4 - 590.0` is 9.399999999999977 in binary floating point, and the
        // reporter compares the window sum against measuredSeconds exactly.
        val lastWindowSeconds = kotlin.math.round(
            (measuredSeconds - (windowCount - 1) * EngineMetricsSchema.WINDOW_SECONDS) * 1000.0) / 1000.0
        return synchronized(this) {
            (1..windowCount).map { index ->
                val slot = index - 1
                val unprocessed = frames[slot] == 0L
                MetricsWindow(
                    index = index,
                    audioSeconds = if (index == windowCount) lastWindowSeconds else EngineMetricsSchema.WINDOW_SECONDS,
                    rtf = if (unprocessed) 0.0 else workNanos[slot] / (frames[slot] * FRAME_NANOS),
                    queueDepth = queueDepth[slot],
                    speakerCalls = speakerJobs[slot],
                    faults = faults[slot],
                    droppedFrames = drops[slot] + if (unprocessed) 1 else 0,
                )
            }
        }
    }

    fun resourceRows(): List<ResourceMeasurement> = synchronized(this) {
        resources.entries.sortedBy { it.key }.map { (index, sample) ->
            ResourceMeasurement(
                windowIndex = index,
                pssKiB = sample.pssKiB,
                rssKiB = sample.rssKiB,
                batteryTemperatureTenthsC = sample.batteryTemperatureTenthsC,
                batteryPercent = sample.batteryPercent,
                temperatureSource = if (sample.batteryTemperatureTenthsC < 0) BATTERY_UNAVAILABLE else BATTERY_SENSOR,
            )
        }
    }

    companion object {
        /** 20 ms of audio per frame expressed in nanoseconds; the RTF denominator per frame. */
        const val FRAME_NANOS = EngineMetricsSchema.FRAME_MILLIS * 1_000_000.0
        const val BATTERY_SENSOR = "battery-temperature"
        const val BATTERY_UNAVAILABLE = "battery-temperature-unavailable"
        /** One resource observation per minute of measured audio. */
        const val RESOURCE_INTERVAL_MILLIS = 60_000L
    }
}

/** One minute-cadence resource observation. Unavailable sensors stay negative and explicitly named. */
data class ResourceSample(val pssKiB: Int, val rssKiB: Int, val batteryTemperatureTenthsC: Int,
    val batteryPercent: Int) {
    companion object {
        fun batteryUnavailable(pssKiB: Int, rssKiB: Int): ResourceSample =
            ResourceSample(pssKiB, rssKiB, -1, -1)
    }
}

data class ResourceMeasurement(val windowIndex: Int, val pssKiB: Int, val rssKiB: Int,
    val batteryTemperatureTenthsC: Int, val batteryPercent: Int, val temperatureSource: String) {
    fun toJson(): String = "{\"windowIndex\":$windowIndex,\"pssKiB\":$pssKiB,\"rssKiB\":$rssKiB," +
        "\"batteryTemperatureTenthsC\":$batteryTemperatureTenthsC,\"batteryPercent\":$batteryPercent," +
        "\"temperatureSource\":\"$temperatureSource\"}"
}

/**
 * Frozen host identity for the measurement, recorded so a result can never be attributed to another
 * device or OS by accident. [matches] is the guard the runner asserts before it accepts a window set.
 */
data class DeviceIdentity(val deviceId: String, val model: String, val osVersion: String, val sdkInt: Int,
    val abi: String, val appVersionName: String, val appVersionCode: Long) {

    fun matches(expectedDeviceId: String, expectedModel: String, expectedOsVersion: String): Boolean =
        deviceId == expectedDeviceId && model == expectedModel && osVersion == expectedOsVersion

    fun toJson(): String = "{\"deviceId\":\"${escape(deviceId)}\",\"model\":\"${escape(model)}\"," +
        "\"osVersion\":\"${escape(osVersion)}\",\"sdkInt\":$sdkInt,\"abi\":\"${escape(abi)}\"," +
        "\"appVersionName\":\"${escape(appVersionName)}\",\"appVersionCode\":$appVersionCode}"

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
}
