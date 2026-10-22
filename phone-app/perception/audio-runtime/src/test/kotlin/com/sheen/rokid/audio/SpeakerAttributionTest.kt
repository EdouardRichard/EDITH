package com.sheen.rokid.audio

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.storage.MemorySpeakerProfileStore
import com.sheen.rokid.storage.SpeakerProfileStore
import java.util.concurrent.Executor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.FutureTask
import java.lang.management.ManagementFactory
import kotlin.test.*

class SpeakerAttributionTest {
    @Test fun `conflict crossing retirement frontier cannot become a confident source`() {
        val t = TurnTimeline()
        proven(t, "short", 0, 1_000_000_000)
        t.registerTranscriptProvenance("retained", epoch, "b")
        assertTrue(t.applyTranscript(transcript("retained", 1_000_000_000, 4_000_000_000)))
        t.registerSpeakerWindow(window(0, 4_000_000_000, "a"), epoch)
        t.registerSpeakerWindow(window(1_000_000_000, 4_000_000_000, "b"), epoch)
        assertTrue(t.applySpeaker(assessment(2_000_000_000, 3_000_000_000), t.speakerAdmission(epoch, "b")).isEmpty())
        assertEquals(SpeakerLabel.UNKNOWN, t.speakerFor("retained"))
        repeat(127) { index ->
            val start = 5_000_000_000 + index * 2_000_000_000L
            proven(t, "later-$index", start, start + 1_000_000_000)
        }
        t.applySpeaker(assessment(2_000_000_000, 3_000_000_000))
        assertEquals(SpeakerLabel.UNKNOWN, t.speakerFor("retained"))
    }

    @Test fun `cancelled work still counts toward executor retention capacity`() {
        val jobs = mutableListOf<Runnable>()
        val service = LocalSpeakerService(EmbeddingExtractor { error("cancelled extraction") }, MemorySpeakerProfileStore(),
            SpeakerPolicy(1000, .8, .3), executor = Executor { jobs += it })
        val w = window(); service.registerWindow(w, epoch)
        try {
            repeat(300) { service.assess(w) {}.cancel() }
            assertTrue(jobs.size <= 8, "external executor must never accumulate more than eight tasks")
            jobs.forEach { it.run() }
            assertEquals(0, service.retentionCounts().pending)
        } finally { service.close() }
    }

    @Test fun `oversized enrollment list rejects before accessing its elements`() {
        val service = LocalSpeakerService(EmbeddingExtractor { error("not admitted") }, MemorySpeakerProfileStore(),
            SpeakerPolicy(1000, .8, .3), { true }, goodQuality)
        val oversized = object : AbstractList<SpeakerWindow>() {
            override val size = 9
            override fun get(index: Int): SpeakerWindow = error("oversized list accessed")
        }
        var delivered = false
        try {
            service.enroll(oversized) { assertNull(it); delivered = true }
            assertTrue(delivered)
        } finally { service.close() }
    }

    @Test fun `revocation saturation blocks new and queued enrollment`() {
        val jobs = mutableListOf<Runnable>(); var extracts = 0; var saves = 0; var successes = 0
        val store = object : SpeakerProfileStore {
            override fun save(embedding: FloatArray): SpeakerProfileRef { saves++; return SpeakerProfileRef("new", 0) }
            override fun load(ref: SpeakerProfileRef) = floatArrayOf(1f, 0f)
            override fun delete(ref: SpeakerProfileRef) {}
        }
        val service = LocalSpeakerService(EmbeddingExtractor { extracts++; floatArrayOf(1f, 0f) }, store,
            SpeakerPolicy(1000, .8, .3), { true }, goodQuality, Executor { jobs += it })
        val w = window(); service.registerWindow(w, epoch)
        try {
            service.enroll(listOf(w)) { if (it != null) successes++ }
            repeat(1025) { service.delete(SpeakerProfileRef("deleted-$it", 0)) }
            service.enroll(listOf(w)) { if (it != null) successes++ }
            jobs.forEach { it.run() }
            assertEquals(0, extracts); assertEquals(0, saves); assertEquals(0, successes)
        } finally { service.close() }
    }

    @Test fun `saturation during enrollment save deletes the unpublished profile`() {
        val memory = MemorySpeakerProfileStore(); lateinit var service: LocalSpeakerService
        var saved: SpeakerProfileRef? = null; var successes = 0
        val store = object : SpeakerProfileStore {
            override fun save(embedding: FloatArray): SpeakerProfileRef {
                saved = memory.save(embedding)
                repeat(1025) { service.delete(SpeakerProfileRef("deleted-$it", 0)) }
                return saved!!
            }
            override fun load(ref: SpeakerProfileRef) = memory.load(ref)
            override fun delete(ref: SpeakerProfileRef) = memory.delete(ref)
        }
        service = LocalSpeakerService(EmbeddingExtractor { floatArrayOf(1f, 0f) }, store,
            SpeakerPolicy(1000, .8, .3), { true }, goodQuality)
        val w = window(); service.registerWindow(w, epoch)
        try {
            service.enroll(listOf(w)) { if (it != null) successes++ }
            assertNotNull(saved); assertNull(memory.load(saved!!)); assertEquals(0, successes)
        } finally { service.close() }
    }

    @Test fun `full speaker budget abstains while ASR continues to final`() {
        val queue = QueueExecutor(); val service = LocalSpeakerService(EmbeddingExtractor { error("full budget") },
            MemorySpeakerProfileStore(), SpeakerPolicy(1, .8, .3), executor = queue)
        val w = window(); service.registerWindow(w, epoch)
        repeat(8) { service.enroll(listOf(w)) {} }
        val vad = ScriptedVad(); val events = java.util.concurrent.CopyOnWriteArrayList<HostEvent>()
        val asr = RecordingAsr(); val delivered = CountDownLatch(1)
        val pipeline = SpeechPipeline(vad, asr, service, { events += it; if (it is SpeakerEvent) delivered.countDown() }, {})
        try {
            pipeline.accept(pipelineFrame(0))
            assertTrue(delivered.await(2, TimeUnit.SECONDS), "capacity must deliver UNKNOWN, not pause ASR")
            vad.decision = VadDecision(false, true); pipeline.accept(pipelineFrame(1))
            assertTrue(events.filterIsInstance<TranscriptEvent>().any { it.revision.final })
            assertTrue(events.none { it is ServiceFaultEvent || it is SessionStateEvent && it.state == SessionState.Paused })
            assertTrue(events.filterIsInstance<SpeakerEvent>().all { it.assessment.label == SpeakerLabel.UNKNOWN })
        } finally { pipeline.close(); queue.drain() }
    }

    @Test fun `continuous timeline retires old turns but retains recent attribution`() {
        val timeline = TurnTimeline()
        repeat(600) { index ->
            val start = index * 2_000_000_000L
            proven(timeline, "turn-$index", start, start + 1_000_000_000)
            timeline.registerSpeakerWindow(window(start, start + 1_000_000_000), epoch)
            timeline.applySpeaker(assessment(start, start + 1_000_000_000))
            val counts = timeline.retentionCounts()
            assertTrue(counts.turns <= 128 && counts.provenance <= 128 && counts.sources <= 256 &&
                counts.windows <= 256 && counts.assessments <= 256)
        }
        assertEquals(SpeakerLabel.UNKNOWN, timeline.speakerFor("turn-0"))
        assertEquals(SpeakerLabel.SELF, timeline.speakerFor("turn-599"))
        timeline.registerTranscriptProvenance("turn-0", epoch, "c")
        assertFalse(timeline.applyTranscript(transcript("turn-0", 0, 1_000_000_000, 1)))
        assertTrue(timeline.applySpeaker(assessment(0, 1_000_000_000)).isEmpty())
    }

    @Test fun `retired overlapping evidence never turns an ambiguous assessment into a unique match`() {
        val timeline = TurnTimeline()
        proven(timeline, "old", 0, 2_000_000_000)
        proven(timeline, "overlap", 1_000_000_000, 3_000_000_000)
        timeline.registerSpeakerWindow(window(0, 3_000_000_000), epoch)
        timeline.applySpeaker(assessment(1_000_000_000, 2_000_000_000))
        assertEquals(SpeakerLabel.UNKNOWN, timeline.speakerFor("overlap"))
        repeat(128) { index ->
            val start = 4_000_000_000 + index * 2_000_000_000L
            proven(timeline, "next-$index", start, start + 1_000_000_000)
        }
        timeline.registerTranscriptProvenance("overlap", epoch, "c")
        assertFalse(timeline.applyTranscript(transcript("overlap", 1_000_000_000, 3_000_000_000, 1)))
        timeline.registerSpeakerWindow(window(1_000_000_000, 3_000_000_000), epoch)
        assertTrue(timeline.applySpeaker(assessment(1_000_000_000, 2_000_000_000)).isEmpty())
        assertEquals(SpeakerLabel.UNKNOWN, timeline.speakerFor("overlap"))
    }

    @Test fun `service proof eviction fences pending work and leaves a recent window usable`() {
        val queue = QueueExecutor(); val store = MemorySpeakerProfileStore()
        val ref = store.save(floatArrayOf(1f, 0f)); var extracts = 0
        val service = LocalSpeakerService(EmbeddingExtractor { extracts++; floatArrayOf(1f, 0f) }, store,
            SpeakerPolicy(1000, .8, .3), quality = goodQuality, executor = queue)
        try {
            assertTrue(service.selectProfile(ref)); val old = window()
            service.registerWindow(old, epoch); var oldCallbacks = 0
            service.assess(old) { oldCallbacks++ }
            repeat(300) { index ->
                val start = (index + 1) * 2_000_000_000L
                service.registerWindow(window(start, start + 1_000_000_000), epoch)
                val counts = service.retentionCounts()
                assertTrue(counts.proofs <= 256 && counts.sources <= 512 && counts.pending <= 8)
            }
            queue.drain()
            assertEquals(0, oldCallbacks); assertEquals(0, extracts)
            var label: SpeakerLabel? = null
            service.assess(old) { label = it.label }; queue.drain()
            assertEquals(SpeakerLabel.UNKNOWN, label)
            val recent = window(600_000_000_000, 601_000_000_000)
            service.assess(recent) { label = it.label }; queue.drain()
            assertEquals(SpeakerLabel.SELF, label); assertEquals(1, extracts)
        } finally { service.close() }
    }

    @Test fun `finite revocation storage closes profile admission rather than forgetting deleted refs`() {
        val store = object : SpeakerProfileStore {
            override fun save(embedding: FloatArray) = SpeakerProfileRef("unused", 0)
            override fun load(ref: SpeakerProfileRef) = floatArrayOf(1f, 0f)
            override fun delete(ref: SpeakerProfileRef) {}
        }
        val service = LocalSpeakerService(EmbeddingExtractor { floatArrayOf(1f, 0f) }, store, SpeakerPolicy(1000, .8, .3))
        try {
            repeat(1025) { service.delete(SpeakerProfileRef("deleted-$it", 0)) }
            service.reset()
            assertTrue(service.retentionCounts().revoked <= 1024)
            assertFalse(service.selectProfile(SpeakerProfileRef("deleted-0", 0)))
            assertFalse(service.selectProfile(SpeakerProfileRef("deleted-1024", 0)))
            assertFalse(service.selectProfile(SpeakerProfileRef("fresh", 0)))
        } finally { service.close() }
    }

    private val epoch = InputEpoch(SessionId("synthetic"), Generation(1), 0)
    private fun envelope(start: Long, end: Long, epoch: InputEpoch = this.epoch) =
        EventEnvelope(epoch.sessionId, epoch.generation, "synthetic-event", start, end)
    private fun window(start: Long = 0, end: Long = 1_000_000_000, continuity: String = "c") =
        SpeakerWindow(AudioSegment(envelope(start, end), AudioFormat.Pcm(16000, 1, 16),
            ByteArray(((end - start) / 1_000_000_000.0 * 32000).toInt())),
            SampleRange(continuity, 0, (end - start) * 16000 / 1_000_000_000), false)
    private fun transcript(id: String, start: Long, end: Long, revision: Long = 0, final: Boolean = false) =
        TranscriptRevision(envelope(start, end), id, revision, "synthetic text", final, start, end)
    private fun assessment(start: Long, end: Long, label: SpeakerLabel = SpeakerLabel.SELF) =
        SpeakerAssessment(envelope(start, end), label, .9, start, end)
    private fun proven(timeline: TurnTimeline, id: String, start: Long, end: Long) {
        timeline.registerTranscriptProvenance(id, epoch, "c")
        assertTrue(timeline.applyTranscript(transcript(id, start, end)))
    }

    @Test fun `uncalibrated overlapping short nonfinite and borderline scores abstain`() {
        assertEquals(SpeakerLabel.UNKNOWN, SpeakerPolicy(1000, null, null).classify(.99, 3000, false))
        val p = SpeakerPolicy(1000, .8, .3)
        for ((score, duration, overlap) in listOf(Triple(.99, 3000L, true), Triple(.95, 100L, false),
            Triple(.5, 3000L, false), Triple(Double.NaN, 3000L, false), Triple(null, 3000L, false))) {
            assertEquals(SpeakerLabel.UNKNOWN, p.classify(score, duration, overlap))
        }
        assertEquals(SpeakerLabel.SELF, p.classify(.8, 1000, false))
        assertEquals(SpeakerLabel.OTHER, p.classify(.3, 1000, false))
    }

    @Test fun `late interval attribution touches the earlier turn only`() {
        val t = TurnTimeline(); proven(t, "t1", 0, 1_000_000_000); proven(t, "t2", 2_000_000_000, 3_000_000_000)
        t.registerSpeakerWindow(window(), epoch)
        assertEquals(setOf("t1"), t.applySpeaker(assessment(0, 1_000_000_000)))
        assertEquals(SpeakerLabel.SELF, t.speakerFor("t1"))
        assertEquals(SpeakerLabel.UNKNOWN, t.speakerFor("t2"))
    }

    @Test fun `missing conflicting continuity and cross turn windows abstain`() {
        val t = TurnTimeline(); proven(t, "t1", 0, 1_000_000_000); proven(t, "t2", 2_000_000_000, 3_000_000_000)
        t.applySpeaker(assessment(0, 1_000_000_000))
        assertEquals(SpeakerLabel.UNKNOWN, t.speakerFor("t1"))
        t.registerSpeakerWindow(window(0, 3_000_000_000), epoch)
        t.applySpeaker(assessment(0, 3_000_000_000))
        assertEquals(SpeakerLabel.UNKNOWN, t.speakerFor("t1"))
        assertEquals(SpeakerLabel.UNKNOWN, t.speakerFor("t2"))
        t.registerSpeakerWindow(window(), epoch); t.registerSpeakerWindow(window(continuity = "other"), epoch)
        t.applySpeaker(assessment(0, 1_000_000_000))
        assertEquals(SpeakerLabel.UNKNOWN, t.speakerFor("t1"))
    }

    @Test fun `conflicting labels noise wrong epoch and clear cannot label turns`() {
        val t = TurnTimeline(); proven(t, "t1", 0, 1_000_000_000); t.registerSpeakerWindow(window(), epoch)
        t.applySpeaker(assessment(0, 1_000_000_000))
        t.applySpeaker(assessment(0, 1_000_000_000, SpeakerLabel.OTHER))
        assertEquals(SpeakerLabel.UNKNOWN, t.speakerFor("t1"))
        t.clear(); proven(t, "t1", 0, 1_000_000_000)
        t.registerSpeakerWindow(window(), epoch.copy(serial = 1))
        t.applySpeaker(assessment(0, 1_000_000_000))
        assertEquals(SpeakerLabel.UNKNOWN, t.speakerFor("t1"))
        t.clear(); proven(t, "t1", 0, 1_000_000_000)
        t.applySpeaker(assessment(0, 1_000_000_000))
        assertEquals(SpeakerLabel.UNKNOWN, t.speakerFor("t1"))
    }

    @Test fun `timeline rejects stale revisions and any revision after final`() {
        val t = TurnTimeline(); proven(t, "t1", 0, 1_000_000_000)
        assertFalse(t.applyTranscript(transcript("t1", 0, 1_000_000_000)))
        assertTrue(t.applyTranscript(transcript("t1", 0, 1_000_000_000, 1, true)))
        assertFalse(t.applyTranscript(transcript("t1", 0, 1_000_000_000, 2)))
    }

    private class QueueExecutor : Executor {
        val tasks = ArrayDeque<Runnable>()
        val submitted = CountDownLatch(1)
        override fun execute(command: Runnable) { synchronized(tasks) { tasks += command }; submitted.countDown() }
        fun drain() { while (true) { val next = synchronized(tasks) { tasks.removeFirstOrNull() } ?: return; next.run() } }
    }
    private val goodQuality = SpeakerQualityEvaluator { SpeakerQuality(true, 1000, false) }

    @Test fun `enrollment requires explicit consent quality and known provenance before extraction`() {
        val store = MemorySpeakerProfileStore(); var extracts = 0
        val extractor = EmbeddingExtractor { extracts++; floatArrayOf(1f, 0f) }
        val w = window()
        for (service in listOf(LocalSpeakerService(extractor, store, SpeakerPolicy(1000, .8, .3), quality = goodQuality),
            LocalSpeakerService(extractor, store, SpeakerPolicy(1000, .8, .3), { true }),
            LocalSpeakerService(extractor, store, SpeakerPolicy(1000, .8, .3), { true }, SpeakerQualityEvaluator { SpeakerQuality(true, 2000, false) }))) {
            service.registerWindow(w, epoch)
            var result: SpeakerProfileRef? = SpeakerProfileRef("sentinel", 0)
            service.enroll(listOf(w)) { result = it }
            assertNull(result); service.close()
        }
        assertEquals(0, extracts)
    }

    @Test fun `selected profile compares synthetic embeddings and deleted ref stays revoked`() {
        val store = MemorySpeakerProfileStore(); val ref = store.save(floatArrayOf(1f, 0f))
        val service = LocalSpeakerService(EmbeddingExtractor { floatArrayOf(1f, 0f) }, store,
            SpeakerPolicy(1000, .8, .3), quality = goodQuality)
        val w = window(); service.registerWindow(w, epoch)
        assertTrue(service.selectProfile(ref))
        var label: SpeakerLabel? = null
        service.assess(w) { label = it.label }
        assertEquals(SpeakerLabel.SELF, label)
        service.delete(SpeakerProfileRef(ref.id, ref.revision + 1))
        service.assess(w) { label = it.label }; assertEquals(SpeakerLabel.SELF, label)
        service.delete(ref)
        assertNull(store.load(ref)); assertFalse(service.selectProfile(ref))
        service.assess(w) { label = it.label }; assertEquals(SpeakerLabel.UNKNOWN, label)
        service.close()
    }

    @Test fun `delete during extraction fences delivery and wipes returned arrays`() {
        val store = MemorySpeakerProfileStore(); val ref = store.save(floatArrayOf(1f, 0f))
        val owned = floatArrayOf(1f, 0f)
        lateinit var service: LocalSpeakerService
        service = LocalSpeakerService(EmbeddingExtractor { service.delete(ref); owned }, store,
            SpeakerPolicy(1000, .8, .3), quality = goodQuality)
        val w = window(); service.registerWindow(w, epoch); assertTrue(service.selectProfile(ref))
        var callbacks = 0
        service.assess(w) { callbacks++ }
        assertEquals(0, callbacks); assertContentEquals(floatArrayOf(0f, 0f), owned)
        service.close()
    }

    @Test fun `pending cancellation reset and close reject future callbacks`() {
        val queue = QueueExecutor(); val store = MemorySpeakerProfileStore(); val ref = store.save(floatArrayOf(1f, 0f))
        var extracts = 0
        val service = LocalSpeakerService(EmbeddingExtractor { extracts++; floatArrayOf(1f, 0f) }, store,
            SpeakerPolicy(1000, .8, .3), { true }, goodQuality, queue)
        val w = window(); service.registerWindow(w, epoch); assertTrue(service.selectProfile(ref))
        var callbacks = 0
        service.assess(w) { callbacks++ }.cancel()
        service.assess(w) { callbacks++ }; service.reset()
        service.registerWindow(w, epoch); service.enroll(listOf(w)) { callbacks++ }; service.close()
        queue.drain()
        assertEquals(0, callbacks); assertEquals(0, extracts)
        assertFailsWith<IllegalStateException> { service.assess(w) {} }
    }

    @Test fun `consent revoked or reset during enrollment never saves a profile`() {
        val store = MemorySpeakerProfileStore(); var allowed = true; val owned = floatArrayOf(1f, 0f)
        val service = LocalSpeakerService(EmbeddingExtractor { allowed = false; owned }, store,
            SpeakerPolicy(1000, .8, .3), { allowed }, goodQuality)
        val w = window(); service.registerWindow(w, epoch)
        var result: SpeakerProfileRef? = SpeakerProfileRef("sentinel", 0)
        service.enroll(listOf(w)) { result = it }
        assertNull(result); assertContentEquals(floatArrayOf(0f, 0f), owned)
        service.close()
    }

    @Test fun `later conflicting provenance revokes a previously assigned label`() {
        val t = TurnTimeline(); proven(t, "t1", 0, 1_000_000_000)
        t.registerSpeakerWindow(window(), epoch); t.applySpeaker(assessment(0, 1_000_000_000))
        assertEquals(SpeakerLabel.SELF, t.speakerFor("t1"))
        t.registerSpeakerWindow(window(500_000_000, 1_500_000_000, "other"), epoch)
        assertEquals(SpeakerLabel.UNKNOWN, t.speakerFor("t1"))
    }

    @Test fun `request with missing provenance cannot acquire proof after admission`() {
        val queue = QueueExecutor(); val store = MemorySpeakerProfileStore(); val ref = store.save(floatArrayOf(1f, 0f))
        var extracts = 0
        val service = LocalSpeakerService(EmbeddingExtractor { extracts++; floatArrayOf(1f, 0f) }, store,
            SpeakerPolicy(1000, .8, .3), quality = goodQuality, executor = queue)
        assertTrue(service.selectProfile(ref)); val w = window()
        var label: SpeakerLabel? = null
        service.assess(w) { label = it.label }; service.registerWindow(w, epoch); queue.drain()
        assertEquals(SpeakerLabel.UNKNOWN, label); assertEquals(0, extracts)
        service.close()
    }

    @Test fun `real pipeline supplies provenance while ASR arrives before queued speaker extraction`() {
        val queue = QueueExecutor(); val store = MemorySpeakerProfileStore(); val ref = store.save(floatArrayOf(1f, 0f))
        val service = LocalSpeakerService(EmbeddingExtractor { floatArrayOf(1f, 0f) }, store,
            SpeakerPolicy(20, .8, .3), quality = SpeakerQualityEvaluator { SpeakerQuality(true, 20, false) }, executor = queue)
        assertTrue(service.selectProfile(ref))
        val events = mutableListOf<HostEvent>()
        val pipeline = SpeechPipeline(ScriptedVad(), RecordingAsr(), service, { synchronized(events) { events += it } }, {})
        try {
            pipeline.accept(PcmFrame(epoch, FloatArray(320), 16000, SampleRange("c", 0, 320), 0, 20_000_000))
            assertTrue(queue.submitted.await(2, TimeUnit.SECONDS))
            val text = synchronized(events) { events.filterIsInstance<TranscriptEvent>().single().revision }
            assertEquals(SpeakerLabel.UNKNOWN, pipeline.timeline.speakerFor(text.turnId))
            assertTrue(synchronized(events) { events.none { it is SpeakerEvent } })
            queue.drain()
            assertEquals(SpeakerLabel.SELF, pipeline.timeline.speakerFor(text.turnId))
            assertEquals(1, synchronized(events) { events.filterIsInstance<SpeakerEvent>().size })
            pipeline.discontinuity(envelope(20_000_000, 20_000_000), GapReason.OVERFLOW)
            assertEquals(SpeakerLabel.UNKNOWN, pipeline.timeline.speakerFor(text.turnId))
        } finally { pipeline.close() }
    }

    @Test fun `successful enrollment stores a copy wipes vectors and permits reentrant delete callback`() {
        val store = MemorySpeakerProfileStore(); val vector = floatArrayOf(1f, 0f)
        val service = LocalSpeakerService(EmbeddingExtractor { vector }, store, SpeakerPolicy(1000, .8, .3), { true }, goodQuality)
        val w = window(); service.registerWindow(w, epoch)
        var ref: SpeakerProfileRef? = null
        service.enroll(listOf(w)) { ref = it }
        assertNotNull(ref); assertContentEquals(floatArrayOf(1f, 0f), store.load(ref!!))
        assertContentEquals(floatArrayOf(0f, 0f), vector)
        assertTrue(service.selectProfile(ref!!))
        service.assess(w) {
            val delete = FutureTask { service.delete(ref!!) }
            Thread(delete).start(); delete.get(2, TimeUnit.SECONDS)
        }
        assertNull(store.load(ref!!)); service.close()
    }

    @Test fun `close racing store save removes the newly saved profile and fences callback`() {
        val memory = MemorySpeakerProfileStore(); var saved: SpeakerProfileRef? = null
        lateinit var service: LocalSpeakerService
        val store = object : SpeakerProfileStore {
            override fun save(embedding: FloatArray): SpeakerProfileRef {
                val ref = memory.save(embedding); saved = ref; service.close(); return ref
            }
            override fun load(ref: SpeakerProfileRef) = memory.load(ref)
            override fun delete(ref: SpeakerProfileRef) = memory.delete(ref)
        }
        service = LocalSpeakerService(EmbeddingExtractor { floatArrayOf(1f, 0f) }, store,
            SpeakerPolicy(1000, .8, .3), { true }, goodQuality)
        val w = window(); service.registerWindow(w, epoch); var callbacks = 0
        service.enroll(listOf(w)) { callbacks++ }
        assertNotNull(saved); assertNull(memory.load(saved!!)); assertEquals(0, callbacks)
    }

    @Test fun `malformed short overlapped and unknown PCM cannot reach extraction`() {
        val store = MemorySpeakerProfileStore(); val ref = store.save(floatArrayOf(1f, 0f)); var extracts = 0
        val service = LocalSpeakerService(EmbeddingExtractor { extracts++; floatArrayOf(1f, 0f) }, store,
            SpeakerPolicy(1000, .8, .3), quality = goodQuality)
        assertTrue(service.selectProfile(ref)); val w = window()
        val fixtures = listOf(w.copy(segment = w.segment.copy(pcm = ByteArray(2))),
            w.copy(segment = w.segment.copy(format = AudioFormat.Unknown)), w.copy(overlap = true),
            window(0, 100_000_000), w.copy(segment = w.segment.copy(envelope = envelope(0, 2_000_000_000))))
        fixtures.forEach { input ->
            service.registerWindow(input, epoch); var label: SpeakerLabel? = null
            service.assess(input) { label = it.label }; assertEquals(SpeakerLabel.UNKNOWN, label)
        }
        assertEquals(0, extracts); service.close()
    }

    @Test fun `PCM replacement cannot reuse registered source metadata`() {
        val store = MemorySpeakerProfileStore(); val ref = store.save(floatArrayOf(1f, 0f)); var extracts = 0
        val service = LocalSpeakerService(EmbeddingExtractor { extracts++; floatArrayOf(1f, 0f) }, store,
            SpeakerPolicy(1000, .8, .3), quality = goodQuality)
        assertTrue(service.selectProfile(ref)); val registered = window()
        service.registerWindow(registered, epoch)
        val replacement = registered.copy(segment = registered.segment.copy(pcm = ByteArray(32000) { 1 }))
        var label: SpeakerLabel? = null
        service.assess(replacement) { label = it.label }
        assertEquals(SpeakerLabel.UNKNOWN, label); assertEquals(0, extracts)
        service.close()
    }

    @Test fun `overlapping source and unknown noise cannot produce a confident timeline label`() {
        val t = TurnTimeline(); proven(t, "t1", 0, 1_000_000_000)
        t.registerSpeakerWindow(window().copy(overlap = true), epoch)
        t.applySpeaker(assessment(0, 1_000_000_000))
        assertEquals(SpeakerLabel.UNKNOWN, t.speakerFor("t1"))
        t.clear(); proven(t, "t1", 0, 1_000_000_000); t.registerSpeakerWindow(window(), epoch)
        t.applySpeaker(assessment(0, 1_000_000_000)); t.applySpeaker(assessment(0, 1_000_000_000, SpeakerLabel.UNKNOWN))
        assertEquals(SpeakerLabel.UNKNOWN, t.speakerFor("t1"))
        val foreign = assessment(0, 1_000_000_000).let { it.copy(envelope = it.envelope.copy(generation = Generation(9))) }
        assertEquals(emptySet(), t.applySpeaker(foreign))
    }

    @Test fun `profile replacement and return still revoke an in flight comparison`() {
        val store = MemorySpeakerProfileStore(); val a = store.save(floatArrayOf(1f, 0f)); val b = store.save(floatArrayOf(0f, 1f))
        lateinit var service: LocalSpeakerService
        service = LocalSpeakerService(EmbeddingExtractor {
            assertTrue(service.selectProfile(b)); assertTrue(service.selectProfile(a)); floatArrayOf(1f, 0f)
        }, store, SpeakerPolicy(1000, .8, .3), quality = goodQuality)
        val w = window(); service.registerWindow(w, epoch); assertTrue(service.selectProfile(a))
        var callbacks = 0; service.assess(w) { callbacks++ }
        assertEquals(0, callbacks); service.close()
    }

    @Test fun `store deletion during extraction is checked before delivery`() {
        val store = MemorySpeakerProfileStore(); val ref = store.save(floatArrayOf(1f, 0f))
        val service = LocalSpeakerService(EmbeddingExtractor { store.delete(ref); floatArrayOf(1f, 0f) }, store,
            SpeakerPolicy(1000, .8, .3), quality = goodQuality)
        val w = window(); service.registerWindow(w, epoch); assertTrue(service.selectProfile(ref))
        var callbacks = 0; service.assess(w) { callbacks++ }
        assertEquals(0, callbacks); service.close()
    }

    @Test fun `failed quality gate performs no profile store reads or writes`() {
        val memory = MemorySpeakerProfileStore(); val ref = memory.save(floatArrayOf(1f, 0f))
        var reads = 0; var writes = 0
        val store = object : SpeakerProfileStore {
            override fun save(embedding: FloatArray): SpeakerProfileRef { writes++; return memory.save(embedding) }
            override fun load(ref: SpeakerProfileRef): FloatArray? { reads++; return memory.load(ref) }
            override fun delete(ref: SpeakerProfileRef) = memory.delete(ref)
        }
        val service = LocalSpeakerService(EmbeddingExtractor { error("quality must gate extraction") }, store,
            SpeakerPolicy(1000, .8, .3), { true })
        val w = window(); service.registerWindow(w, epoch); assertTrue(service.selectProfile(ref)); reads = 0
        var label: SpeakerLabel? = null; service.assess(w) { label = it.label }
        var result: SpeakerProfileRef? = ref; service.enroll(listOf(w)) { result = it }
        assertEquals(SpeakerLabel.UNKNOWN, label); assertNull(result)
        assertEquals(0, reads); assertEquals(0, writes); service.close()
    }

    private fun pipelineFrame(index: Int, continuity: String = "c", source: InputEpoch = epoch) =
        PcmFrame(source, FloatArray(320), 16000, SampleRange(continuity, index * 320L, (index + 1) * 320L),
            index * 20_000_000L, (index + 1) * 20_000_000L)

    private fun admittedCallbackRace(revoke: (SpeechPipeline, RecordingAsr) -> Unit, replacement: Boolean) {
        val speaker = PendingSpeaker(); val asr = RecordingAsr(); val timeline = TurnTimeline()
        val events = mutableListOf<HostEvent>()
        val pipeline = SpeechPipeline(ScriptedVad(), asr, speaker, { synchronized(events) { events += it } }, {}, timeline)
        var callback: FutureTask<Unit>? = null
        try {
            pipeline.accept(pipelineFrame(0)); speaker.awaitStarted()
            val old = speaker.requests.single()
            synchronized(timeline) {
                callback = FutureTask { old.release(SpeakerLabel.SELF) }
                val thread = Thread(callback, "synthetic-admitted-callback"); thread.start()
                val bean = ManagementFactory.getThreadMXBean()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
                var atMutation = false
                while (!atMutation && System.nanoTime() < deadline) {
                    val info = bean.getThreadInfo(thread.threadId(), 32)
                    atMutation = info?.threadState == Thread.State.BLOCKED && info.stackTrace.any {
                        it.className == TurnTimeline::class.java.name && it.methodName.startsWith("applySpeaker")
                    }
                    Thread.onSpinWait()
                }
                assertTrue(atMutation, "callback must be admitted and waiting on timeline mutation")
                revoke(pipeline, asr)
                if (replacement) pipeline.accept(pipelineFrame(0, "replacement", epoch.copy(serial = 1)))
                else {
                    timeline.registerTranscriptProvenance("replacement", epoch.copy(serial = 1), "replacement")
                    timeline.applyTranscript(transcript("replacement", 0, 20_000_000))
                    val w = SpeakerWindow(AudioSegment(envelope(0, 20_000_000), AudioFormat.Pcm(16000, 1, 16), ByteArray(640)),
                        SampleRange("replacement", 0, 320), false)
                    timeline.registerSpeakerWindow(w, epoch.copy(serial = 1))
                }
            }
            callback!!.get(2, TimeUnit.SECONDS)
            val replacementId = if (replacement) synchronized(events) {
                events.filterIsInstance<TranscriptEvent>().last().revision.turnId
            } else "replacement"
            assertEquals(SpeakerLabel.UNKNOWN, timeline.speakerFor(replacementId))
        } finally { pipeline.close(); callback?.get(2, TimeUnit.SECONDS) }
    }

    @Test fun `admitted old callback cannot mutate replacement timeline after gap`() =
        admittedCallbackRace({ pipeline, _ -> pipeline.discontinuity(envelope(0, 20_000_000), GapReason.OVERFLOW) }, true)

    @Test fun `admitted old callback cannot repopulate timeline after close`() =
        admittedCallbackRace({ pipeline, _ -> pipeline.close() }, false)

    @Test fun `admitted old callback cannot repopulate timeline after fault`() =
        admittedCallbackRace({ pipeline, asr ->
            asr.failure = IllegalStateException("synthetic failure"); pipeline.accept(pipelineFrame(1))
        }, false)

    @Test fun `one second policy allows a long pipeline turn after early work is drained`() {
        val queue = QueueExecutor(); val store = MemorySpeakerProfileStore(); val ref = store.save(floatArrayOf(1f, 0f))
        val started = CountDownLatch(1); val finished = CountDownLatch(1)
        val executor = Executor { work -> queue.execute(work); started.countDown() }
        val service = LocalSpeakerService(EmbeddingExtractor { floatArrayOf(1f, 0f) }, store,
            SpeakerPolicy(1000, .8, .3), quality = SpeakerQualityEvaluator { w ->
                SpeakerQuality(true, (w.range.endExclusive - w.range.first) * 1000 / 16000, false)
            }, executor = executor)
        assertTrue(service.selectProfile(ref)); val vad = ScriptedVad(); val events = mutableListOf<HostEvent>()
        val pipeline = SpeechPipeline(vad, RecordingAsr(), service, { synchronized(events) { events += it }; if (it is SpeakerEvent) finished.countDown() }, {})
        try {
            pipeline.accept(pipelineFrame(0))
            // A marker request on the same speaker worker proves any startup task has completed submission.
            val field = SpeechPipeline::class.java.getDeclaredField("speakerExecutor").apply { isAccessible = true }
            val worker = field.get(pipeline) as java.util.concurrent.ThreadPoolExecutor
            val earlyDrained = CountDownLatch(1); worker.execute { earlyDrained.countDown() }
            assertTrue(earlyDrained.await(2, TimeUnit.SECONDS)); queue.drain()
            val id = synchronized(events) { events.filterIsInstance<TranscriptEvent>().single().revision.turnId }
            assertEquals(SpeakerLabel.UNKNOWN, pipeline.timeline.speakerFor(id))
            assertTrue(synchronized(events) { events.none { it is SpeakerEvent } })
            for (index in 1..59) pipeline.accept(pipelineFrame(index))
            assertTrue(started.await(2, TimeUnit.SECONDS), "sufficient live window must start before endpoint")
            vad.decision = VadDecision(false, true); pipeline.accept(pipelineFrame(60))
            assertTrue(started.await(2, TimeUnit.SECONDS))
            val endpointSubmitted = CountDownLatch(1); worker.execute { endpointSubmitted.countDown() }
            assertTrue(endpointSubmitted.await(2, TimeUnit.SECONDS)); queue.drain()
            assertTrue(finished.await(2, TimeUnit.SECONDS))
            assertEquals(SpeakerLabel.SELF, pipeline.timeline.speakerFor(id))
        } finally { pipeline.close() }
    }

    @Test fun `one second pipeline policy preserves short endpoint abstention`() {
        val store = MemorySpeakerProfileStore(); val ref = store.save(floatArrayOf(1f, 0f)); var extracts = 0
        val service = LocalSpeakerService(EmbeddingExtractor { extracts++; floatArrayOf(1f, 0f) }, store,
            SpeakerPolicy(1000, .8, .3), quality = SpeakerQualityEvaluator { w ->
                SpeakerQuality(true, (w.range.endExclusive - w.range.first) * 1000 / 16000, false)
            })
        assertTrue(service.selectProfile(ref)); val vad = ScriptedVad(); val delivered = CountDownLatch(1)
        val events = java.util.concurrent.CopyOnWriteArrayList<HostEvent>()
        val pipeline = SpeechPipeline(vad, RecordingAsr(), service, { events += it; if (it is SpeakerEvent) delivered.countDown() }, {})
        try {
            pipeline.accept(pipelineFrame(0)); vad.decision = VadDecision(false, true); pipeline.accept(pipelineFrame(1))
            assertTrue(delivered.await(2, TimeUnit.SECONDS))
            val id = events.filterIsInstance<TranscriptEvent>().last().revision.turnId
            assertEquals(SpeakerLabel.UNKNOWN, pipeline.timeline.speakerFor(id)); assertEquals(0, extracts)
            assertTrue(events.filterIsInstance<SpeakerEvent>().all { it.assessment.label == SpeakerLabel.UNKNOWN })
        } finally { pipeline.close() }
    }

    private fun postSaveConsentFailure(cleanupThrows: Boolean) {
        val memory = MemorySpeakerProfileStore(); var saved: SpeakerProfileRef? = null
        val vector = floatArrayOf(1f, 0f); var average: FloatArray? = null
        val consentFailure = IllegalStateException("synthetic consent failure")
        val cleanupFailure = IllegalStateException("synthetic cleanup failure")
        val store = object : SpeakerProfileStore {
            override fun save(embedding: FloatArray): SpeakerProfileRef {
                average = embedding; return memory.save(embedding).also { saved = it }
            }
            override fun load(ref: SpeakerProfileRef) = memory.load(ref)
            override fun delete(ref: SpeakerProfileRef) { memory.delete(ref); if (cleanupThrows) throw cleanupFailure }
        }
        var consentCalls = 0; var callbacks = 0
        val service = LocalSpeakerService(EmbeddingExtractor { vector }, store, SpeakerPolicy(1000, .8, .3),
            { consentCalls++; if (consentCalls == 3) throw consentFailure; true }, goodQuality)
        val w = window(); service.registerWindow(w, epoch)
        try {
            val actual = assertFailsWith<IllegalStateException> { service.enroll(listOf(w)) { callbacks++ } }
            assertSame(consentFailure, actual)
            assertNotNull(saved); assertNull(memory.load(saved!!)); assertEquals(0, callbacks)
            assertContentEquals(floatArrayOf(0f, 0f), vector); assertContentEquals(floatArrayOf(0f, 0f), average)
            if (cleanupThrows) assertTrue(actual.suppressed.any { it === cleanupFailure })
        } finally { service.close() }
    }

    @Test fun `post save consent exception releases undisclosed profile`() = postSaveConsentFailure(false)
    @Test fun `post save cleanup failure preserves original error and wipes arrays`() = postSaveConsentFailure(true)

    @Test fun `cancel during save releases profile without callback`() {
        val memory = MemorySpeakerProfileStore(); val queue = QueueExecutor(); var saved: SpeakerProfileRef? = null
        val vector = floatArrayOf(1f, 0f); lateinit var handle: CancellationHandle
        val store = object : SpeakerProfileStore {
            override fun save(embedding: FloatArray): SpeakerProfileRef {
                val ref = memory.save(embedding); saved = ref; handle.cancel(); return ref
            }
            override fun load(ref: SpeakerProfileRef) = memory.load(ref)
            override fun delete(ref: SpeakerProfileRef) = memory.delete(ref)
        }
        val service = LocalSpeakerService(EmbeddingExtractor { vector }, store,
            SpeakerPolicy(1000, .8, .3), { true }, goodQuality, queue)
        val w = window(); service.registerWindow(w, epoch); var callbacks = 0
        try {
            handle = service.enroll(listOf(w)) { callbacks++ }; queue.drain()
            assertNotNull(saved); assertNull(memory.load(saved!!)); assertEquals(0, callbacks)
            assertContentEquals(floatArrayOf(0f, 0f), vector)
        } finally { service.close() }
    }

    @Test fun `admitted successful profile remains available when callback throws`() {
        val memory = MemorySpeakerProfileStore(); val vector = floatArrayOf(1f, 0f)
        val service = LocalSpeakerService(EmbeddingExtractor { vector }, memory, SpeakerPolicy(1000, .8, .3), { true }, goodQuality)
        val w = window(); service.registerWindow(w, epoch); var delivered: SpeakerProfileRef? = null
        try {
            assertFailsWith<IllegalStateException> {
                service.enroll(listOf(w)) { delivered = it; throw IllegalStateException("synthetic listener failure") }
            }
            assertNotNull(delivered); assertContentEquals(floatArrayOf(1f, 0f), memory.load(delivered!!))
            assertContentEquals(floatArrayOf(0f, 0f), vector)
            service.delete(delivered!!)
        } finally { service.close() }
    }
}
