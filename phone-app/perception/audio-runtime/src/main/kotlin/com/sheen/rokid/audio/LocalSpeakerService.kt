package com.sheen.rokid.audio

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.storage.SpeakerProfileStore
import java.util.concurrent.Executor
import java.util.UUID
import java.security.MessageDigest
import kotlin.math.sqrt

fun interface EmbeddingExtractor { fun extract(window: SpeakerWindow): FloatArray? }
data class SpeakerQuality(val verifiedSource: Boolean, val voicedMillis: Long, val overlap: Boolean)
fun interface SpeakerQualityEvaluator { fun evaluate(window: SpeakerWindow): SpeakerQuality }

class LocalSpeakerService(private val extractor: EmbeddingExtractor, private val store: SpeakerProfileStore,
    private val policy: SpeakerPolicy, private val enrollmentAllowed: () -> Boolean = { false },
    private val quality: SpeakerQualityEvaluator = SpeakerQualityEvaluator { SpeakerQuality(false, 0, true) },
    private val executor: Executor = Executor { it.run() }) : SpeakerService, SpeakerEngine {
    private data class WindowKey(val session: SessionId, val generation: Generation, val start: Long,
        val end: Long, val range: SampleRange, val format: AudioFormat, val pcmFingerprint: List<Byte>)
    private data class Source(val key: WindowKey, val epoch: InputEpoch)
    private class Request(val copies: List<SpeakerWindow>, val sources: List<Source>, val profile: SpeakerProfileRef?) {
        var work: (() -> Unit)? = null
        var running = false
    }
    private val lock = Any()
    private val windows = mutableMapOf<WindowKey, MutableSet<InputEpoch>>()
    private val pending = mutableSetOf<Request>()
    private val retained = mutableSetOf<Request>()
    private val revoked = mutableSetOf<SpeakerProfileRef>()
    private var selected: SpeakerProfileRef? = null
    private var closed = false
    private var retiredThroughNanos = 0L
    private var revocationSaturated = false

    internal fun startupDurationSatisfied(sampleCount: Long, sampleRateHz: Int): Boolean =
        sampleCount > 0 && sampleRateHz > 0 && sampleCount * 1000 / sampleRateHz >= policy.minVoicedMillis

    fun registerWindow(window: SpeakerWindow, epoch: InputEpoch) {
        synchronized(lock) {
            check(!closed)
            if (duration(window) == null) return
            val key = key(window)
            if (key.session == epoch.sessionId && key.generation == epoch.generation && duration(window) != null &&
                key.start >= retiredThroughNanos) {
                if (key !in windows && windows.size >= 256) retireProofsThrough(windows.keys.minOf { it.end })
                if (key.start >= retiredThroughNanos) {
                    val sources = windows.getOrPut(key) { mutableSetOf() }
                    if (sources.size < 2) sources += epoch
                }
            }
        }
    }

    fun selectProfile(ref: SpeakerProfileRef): Boolean {
        val embedding = store.load(ref) ?: return false
        try {
            return synchronized(lock) {
                check(!closed)
                if (revocationSaturated || ref in revoked) false else {
                    if (selected != ref) invalidate { it.profile != null }
                    selected = ref
                    true
                }
            }
        } finally { embedding.fill(0f) }
    }

    override fun enroll(windows: List<SpeakerWindow>, listener: (SpeakerProfileRef?) -> Unit): CancellationHandle {
        val request = prepare(windows, false) ?: return abstain { listener(null) }
        val copies = request.copies
        return submit(request) {
            val vectors = mutableListOf<FloatArray>()
            var saved: SpeakerProfileRef? = null
            var deliveryAdmitted = false
            var failure: Throwable? = null
            try {
                if (copies.isNotEmpty() && request.sources.size == copies.size && enrollmentAllowed() &&
                    copies.all { acceptable(it) } && current(request)) {
                    for (window in copies) {
                        if (!current(request)) break
                        val vector = extractor.extract(window) ?: break
                        vectors += vector
                        if (!valid(vector)) break
                    }
                    if (vectors.size == copies.size && vectors.all { valid(it) && it.size == vectors.first().size } &&
                        enrollmentAllowed() && current(request)) {
                        val average = FloatArray(vectors.first().size) { index ->
                            (vectors.sumOf { it[index].toDouble() } / vectors.size).toFloat()
                        }
                        try { if (valid(average)) saved = store.save(average) } finally { average.fill(0f) }
                    }
                }
                val result = if (enrollmentAllowed()) saved else null
                val admitted = admit(request)
                deliveryAdmitted = admitted && result != null
                if (!deliveryAdmitted) {
                    val discard = saved
                    saved = null
                    discard?.let { store.delete(it) }
                }
                if (admitted) listener(result)
            } catch (error: Throwable) {
                failure = error
                throw error
            } finally {
                try {
                    if (!deliveryAdmitted) saved?.let { ref ->
                        try { store.delete(ref) } catch (cleanup: Throwable) {
                            val original = failure
                            if (original == null) throw cleanup
                            if (cleanup !== original) original.addSuppressed(cleanup)
                        }
                    }
                } finally { vectors.forEach { it.fill(0f) } }
            }
        }
    }

    override fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit): CancellationHandle {
        val request = prepare(listOf(window), true) ?: return abstain {
            val e = window.segment.envelope.copy(eventId = UUID.randomUUID().toString())
            listener(SpeakerAssessment(e, SpeakerLabel.UNKNOWN, null, e.startNanos, e.endNanos))
        }
        val copy = request.copies.single()
        return submit(request) {
            var reference: FloatArray? = null
            var vector: FloatArray? = null
            var score: Double? = null
            var label = SpeakerLabel.UNKNOWN
            try {
                val q = checkedQuality(copy)
                if (q != null && request.sources.size == 1 && current(request) && request.profile != null) {
                    reference = store.load(request.profile)
                    if (reference != null && valid(reference) && current(request)) {
                        vector = extractor.extract(copy)
                        score = vector?.let { cosine(reference, it) }
                        label = policy.classify(score, q.voicedMillis, q.overlap || copy.overlap)
                    }
                }
                if (reference != null && request.profile != null && !profileAvailable(request.profile)) {
                    synchronized(lock) {
                        revokeProfile(request.profile)
                    }
                }
                if (admit(request)) {
                    val e = copy.segment.envelope.copy(eventId = UUID.randomUUID().toString())
                    listener(SpeakerAssessment(e, label, score, e.startNanos, e.endNanos))
                }
            } finally { reference?.fill(0f); vector?.fill(0f) }
        }
    }

    override fun delete(profile: SpeakerProfileRef) {
        synchronized(lock) {
            check(!closed)
            revokeProfile(profile)
        }
        store.delete(profile)
    }

    override fun reset() { synchronized(lock) { check(!closed); invalidate { true }; windows.clear(); retiredThroughNanos = 0 } }
    override fun close() { synchronized(lock) { closed = true; invalidate { true }; windows.clear(); selected = null } }

    private fun key(window: SpeakerWindow): WindowKey {
        val e = window.segment.envelope
        return WindowKey(e.sessionId, e.generation, e.startNanos, e.endNanos, window.range, window.segment.format,
            MessageDigest.getInstance("SHA-256").digest(window.segment.pcm).toList())
    }

    private fun prepare(input: List<SpeakerWindow>, comparison: Boolean): Request? = synchronized(lock) {
        check(!closed)
        if (revocationSaturated || retained.size >= 8 || input.size !in 1..8) return null
        if (input.any { duration(it) == null }) return null
        val bytes = input.sumOf { it.segment.pcm.size.toLong() }
        val retainedBytes = retained.sumOf { request -> request.copies.sumOf { it.segment.pcm.size.toLong() } }
        if (bytes > 10_240_000 - retainedBytes) return null
        val copies = input.map { copy(it) }
        val sources = copies.mapNotNull { window ->
            val key = key(window)
            windows[key]?.singleOrNull()?.let { Source(key, it) }
        }
        // An absent source remains an abstaining request; it must never reach the extractor.
        Request(copies, if (sources.size == copies.size) sources else emptyList(), if (comparison) selected else null)
            .also { pending += it; retained += it }
    }

    private fun current(request: Request) = synchronized(lock) { currentLocked(request) }
    private fun currentLocked(request: Request) = !closed && !revocationSaturated && request in pending &&
        (request.profile == null || request.profile !in revoked && selected == request.profile) &&
        request.sources.all { windows[it.key]?.singleOrNull() == it.epoch }

    private fun admit(request: Request): Boolean = synchronized(lock) {
        val admitted = currentLocked(request)
        pending.remove(request)
        admitted
    }

    internal data class RetentionCounts(val proofs: Int, val sources: Int, val pending: Int, val revoked: Int,
        val retainedTasks: Int, val retainedPcmBytes: Long)
    internal fun retentionCounts() = synchronized(lock) {
        RetentionCounts(windows.size, windows.values.sumOf { it.size }, pending.size, revoked.size,
            retained.size, retained.sumOf { request -> request.copies.sumOf { it.segment.pcm.size.toLong() } })
    }

    private fun retireProofsThrough(endNanos: Long) {
        retiredThroughNanos = maxOf(retiredThroughNanos, endNanos)
        // Retiring a crossing window must also fence its entire remaining interval.
        while (true) {
            val extended = windows.keys.filter { it.start < retiredThroughNanos }.maxOfOrNull { it.end } ?: break
            if (extended <= retiredThroughNanos) break
            retiredThroughNanos = extended
        }
        windows.keys.removeAll { it.start < retiredThroughNanos }
        invalidate { request -> request.sources.any { it.key.start < retiredThroughNanos } }
    }

    private fun revokeProfile(profile: SpeakerProfileRef) {
        if (profile !in revoked && revoked.size >= 1024) {
            revocationSaturated = true
            selected = null
            invalidate { true }
        } else {
            revoked += profile
            if (selected == profile) selected = null
            invalidate { it.profile == profile }
        }
    }

    private fun submit(request: Request, work: () -> Unit): CancellationHandle {
        synchronized(lock) { if (currentLocked(request)) request.work = work }
        try {
            executor.execute { execute(request) }
        } catch (failure: RuntimeException) {
            finish(request)
            throw failure
        }
        return object : CancellationHandle {
            override fun cancel() { synchronized(lock) { invalidate { it === request } } }
        }
    }

    private fun execute(request: Request) {
        val work = synchronized(lock) {
            if (!currentLocked(request)) null else request.work.also { request.running = true; request.work = null }
        }
        try { work?.invoke() } finally { finish(request) }
    }

    private fun finish(request: Request) = synchronized(lock) {
        pending.remove(request); retained.remove(request); request.work = null
        request.copies.forEach { it.segment.pcm.fill(0) }
    }

    private fun invalidate(predicate: (Request) -> Boolean) {
        pending.filter(predicate).forEach { request ->
            pending.remove(request); request.work = null
            if (!request.running) request.copies.forEach { it.segment.pcm.fill(0) }
        }
    }

    private fun abstain(deliver: () -> Unit): CancellationHandle {
        deliver()
        return object : CancellationHandle { override fun cancel() {} }
    }

    private fun copy(window: SpeakerWindow) = window.copy(segment = window.segment.copy(pcm = window.segment.pcm.copyOf()))

    private fun duration(window: SpeakerWindow): Long? {
        val format = window.segment.format as? AudioFormat.Pcm ?: return null
        if (format.channels != 1 || format.bitsPerSample != 16 || format.sampleRateHz !in 8000..192000) return null
        val samples = window.range.endExclusive - window.range.first
        if (samples <= 0 || samples > format.sampleRateHz.toLong() * 20 || samples * 2 != window.segment.pcm.size.toLong()) return null
        val expectedNanos = samples * 1_000_000_000 / format.sampleRateHz
        val e = window.segment.envelope
        if (kotlin.math.abs(e.endNanos - e.startNanos - expectedNanos) > 1) return null
        return samples * 1000 / format.sampleRateHz
    }

    private fun checkedQuality(window: SpeakerWindow): SpeakerQuality? {
        val duration = duration(window) ?: return null
        val known = synchronized(lock) { windows[key(window)]?.singleOrNull() != null }
        if (!known) return null
        val q = quality.evaluate(window)
        return q.takeIf { it.verifiedSource && it.voicedMillis in policy.minVoicedMillis..duration && !it.overlap && !window.overlap }
    }
    private fun acceptable(window: SpeakerWindow) = checkedQuality(window) != null
    private fun profileAvailable(ref: SpeakerProfileRef): Boolean {
        val copy = store.load(ref) ?: return false
        return try { valid(copy) } finally { copy.fill(0f) }
    }
    private fun valid(vector: FloatArray) = vector.isNotEmpty() && vector.all { it.isFinite() } && vector.any { it != 0f }
    private fun cosine(a: FloatArray, b: FloatArray): Double? {
        if (a.size != b.size || !valid(a) || !valid(b)) return null
        var dot = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { dot += a[i].toDouble() * b[i]; aa += a[i].toDouble() * a[i]; bb += b[i].toDouble() * b[i] }
        return (dot / sqrt(aa * bb)).takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
    }
}
