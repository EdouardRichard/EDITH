package com.sheen.rokid.testsupport

/**
 * Writes a complete, structurally valid [EngineMetrics] document for tooling checks.
 *
 * Usage: `metrics-document --out <path> [--seconds 600] [--windows 60] [--trials 30] [--no-resources]`
 *
 * This deliberately does NOT claim a measurement: it produces the shape `tools/p2/report-engine.ps1`
 * validates so the reporter contract can be checked without a device run. The values were fabricated by
 * a generator and must never be presented as T16 evidence.
 */
object MetricsDocumentFixture {
    @JvmStatic
    fun main(args: Array<String>) {
        var out: String? = null
        var seconds = 600.0
        var windowCount = 60
        var trialCount = 30
        var withResources = true
        var index = 0
        while (index < args.size) {
            when (val flag = args[index]) {
                "--out" -> out = args.getOrNull(++index)
                "--seconds" -> seconds = args.getOrNull(++index)?.toDoubleOrNull() ?: seconds
                "--windows" -> windowCount = args.getOrNull(++index)?.toIntOrNull() ?: windowCount
                "--trials" -> trialCount = args.getOrNull(++index)?.toIntOrNull() ?: trialCount
                "--no-resources" -> withResources = false
                else -> error("unknown argument: $flag")
            }
            index++
        }
        requireNotNull(out) { "--out is required" }
        require(seconds > 0 && windowCount > 0) { "seconds and windows must be positive" }

        val target = java.io.File(out)
        target.parentFile?.mkdirs()
        target.writeText(document(seconds, windowCount, trialCount, withResources).toJson())
        println("wrote ${target.absolutePath} (${target.length()} bytes): $windowCount windows, $trialCount trials")
    }

    fun document(seconds: Double, windowCount: Int, trialCount: Int, withResources: Boolean): EngineMetrics {
        // The window list is derived from the same plan the resources come from, so `seconds` stays
        // authoritative: a document for 599 s must not claim 60 windows of a full ten seconds each.
        val plan = BenchmarkPlan(seconds.toLong())
        require(windowCount == plan.windowCount) {
            "windowCount $windowCount does not match the plan's ${plan.windowCount} windows for $seconds s"
        }
        val accumulator = plan.newAccumulator()
        (1..plan.windowCount).forEach { index ->
            accumulator.record(ownerIndex = index, frames = 500, workNanos = 1_000_000_000L, speakerJobs = 1)
        }
        return EngineMetrics(
            candidate = MetricsCandidate(
                deviceId = "fixture-device",
                runtimeSha256 = "a".repeat(64),
                provider = "cpu",
                threads = 2,
                endpointMillis = 350,
                frameMillis = 20,
                sampleRateHz = 16000,
                modelHashes = mapOf("asr" to "b".repeat(64), "vad" to "c".repeat(64),
                    "speaker" to "d".repeat(64)),
            ),
            nativeExecuted = true,
            nativeLifecyclePassed = true,
            interrupted = false,
            warmupSeconds = 60.0,
            measuredSeconds = seconds,
            windows = accumulator.windows(measuredSeconds = seconds),
            semanticTrials = (1..trialCount).map { SemanticTrial("P2Q%03d".format(it), true) },
            resources = if (!withResources) emptyList() else {
                plan.resourceOwnerIndices().mapIndexed { order, owner ->
                    ResourceMeasurement(owner, 900_000 + order * 1000, 1_800_000 + order * 1000,
                        310 + order, 90 - order, BenchmarkAccumulator.BATTERY_SENSOR)
                }
            },
            identity = DeviceIdentity(deviceId = "fixture-device", model = "FIXTURE-MODEL",
                osVersion = "13", sdkInt = 33, abi = "arm64-v8a",
                appVersionName = "fixture", appVersionCode = 1),
        )
    }
}
