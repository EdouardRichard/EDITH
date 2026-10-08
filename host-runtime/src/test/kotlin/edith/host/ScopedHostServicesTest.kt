package edith.host

import edith.contracts.*
import edith.plugin.*
import edith.testsupport.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.*

class ScopedHostServicesTest {
    private fun scope() = ResourceScope("probe", SessionId("s"), Generation(0))
    private val envelope = EventEnvelope(SessionId("s"), Generation(0), "e", 0, 1)

    @Test fun `revocation cancels once and rejects later work`() {
        val scope = scope()
        val handle = FakeCancellation()
        val bound = scope.bind(handle)
        scope.revoke(); scope.revoke(); bound.cancel()
        assertEquals(1, handle.cancelCount)
        assertFailsWith<ContractError.Cancelled> { scope.checkActive() }
        val late = FakeCancellation()
        scope.bind(late)
        assertEquals(1, late.cancelCount)
    }

    @Test fun `throwing cancellation still releases remaining handles`() {
        val scope = scope()
        scope.bind(object : CancellationHandle { override fun cancel() = error("synthetic") })
        val good = FakeCancellation()
        scope.bind(good)
        scope.revoke()
        assertEquals(1, good.cancelCount)
    }

    @Test fun `concurrent bind and revoke cannot leak a handle`() {
        repeat(50) {
            val scope = scope()
            val ready = CountDownLatch(2)
            val go = CountDownLatch(1)
            val count = AtomicInteger()
            val handle = object : CancellationHandle { override fun cancel() { count.incrementAndGet() } }
            val workers = listOf(thread { ready.countDown(); check(go.await(5, TimeUnit.SECONDS)); scope.bind(handle) },
                thread { ready.countDown(); check(go.await(5, TimeUnit.SECONDS)); scope.revoke() })
            assertTrue(ready.await(5, TimeUnit.SECONDS)); go.countDown()
            workers.forEach { it.join(5000); assertFalse(it.isAlive) }
            assertEquals(1, count.get())
        }
    }

    @Test fun `capability injection rejects missing and wrong provider types`() {
        assertFailsWith<ContractError.CapabilityMissing> {
            ScopedHostServices(scope(), setOf(Capability.Model), emptyMap(), FakeTaskScope())
        }
        assertFailsWith<ContractError.ConfigurationRejected> {
            ScopedHostServices(scope(), setOf(Capability.Model), mapOf(Capability.Model to "wrong"), FakeTaskScope())
        }
        val services = ScopedHostServices(scope(), emptySet(), emptyMap(), FakeTaskScope())
        assertFailsWith<ContractError.CapabilityMissing> { services.require(Capability.Storage) }
    }

    @Test fun `individual cancellation fences uncooperative late audio callback`() {
        lateinit var callback: (AudioSegment) -> Unit
        val handle = FakeCancellation()
        val provider = object : AudioSubscription {
            override fun listen(listener: (AudioSegment) -> Unit): CancellationHandle {
                callback = listener
                return handle
            }
        }
        val scope = scope()
        val services = ScopedHostServices(scope, setOf(Capability.Audio), mapOf(Capability.Audio to provider), FakeTaskScope())
        var received = 0
        val subscription = services.audio().listen { received++ }
        subscription.cancel(); subscription.cancel()
        callback(AudioSegment(envelope, AudioFormat.Unknown, byteArrayOf(1)))
        assertEquals(0, received)
        assertEquals(1, handle.cancelCount)
        assertFalse(scope.isRevoked)
    }

    @Test fun `old storage and tasks cannot start after cancellation`() {
        val scope = scope()
        val values = mutableMapOf<String, ByteArray>()
        val provider = object : StorageHandle {
            override fun read(key: String) = values[key]
            override fun write(key: String, value: ByteArray) { values[key] = value }
            override fun delete(key: String) { values.remove(key) }
            override fun clear() { values.clear() }
        }
        val services = ScopedHostServices(scope, setOf(Capability.Storage), mapOf(Capability.Storage to provider), FakeTaskScope())
        val storage = services.storage()
        storage.write("k", byteArrayOf(1))
        assertContentEquals(byteArrayOf(1), storage.read("k"))
        services.tasks.cancelAll()
        assertFailsWith<ContractError.Cancelled> { services.tasks.launch { fail("ran stopped task") } }
        scope.revoke()
        assertFailsWith<ContractError.Cancelled> { storage.read("k") }
        assertFailsWith<ContractError.Cancelled> { storage.write("k", byteArrayOf(2)) }
        assertContentEquals(byteArrayOf(1), values["k"])
    }

    @Test fun `task scope cancellation reaches each task once`() {
        val scope = scope()
        val provider = FakeTaskScope()
        val services = ScopedHostServices(scope, emptySet(), emptyMap(), provider)
        services.tasks.launch {}
        services.tasks.cancelAll(); scope.revoke()
        assertEquals(listOf(1), provider.handles.map { it.cancelCount })
    }

    @Test fun `running task can wait for its own cancellation without blocking revoke`() {
        val scope = scope()
        val entered = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        lateinit var worker: Thread
        val provider = object : TaskScope {
            override fun launch(block: () -> Unit): CancellationHandle {
                worker = thread { block() }
                return object : CancellationHandle { override fun cancel() { cancelled.countDown() } }
            }
            override fun cancelAll() = Unit
        }
        val services = ScopedHostServices(scope, emptySet(), emptyMap(), provider)
        var cancelledInTime = false
        services.tasks.launch { entered.countDown(); cancelledInTime = cancelled.await(2, TimeUnit.SECONDS) }
        assertTrue(entered.await(5, TimeUnit.SECONDS))
        scope.revoke()
        worker.join(5000)
        assertTrue(cancelledInTime)
    }

    @Test fun `cancelAll fences queued task whose provider ignores cancellation`() {
        lateinit var queued: () -> Unit
        val raw = FakeCancellation()
        val provider = object : TaskScope {
            override fun launch(block: () -> Unit): CancellationHandle { queued = block; return raw }
            override fun cancelAll() = Unit
        }
        val services = ScopedHostServices(scope(), emptySet(), emptyMap(), provider)
        var ran = false
        services.tasks.launch { ran = true }
        services.tasks.cancelAll(); queued()
        assertFalse(ran)
        assertEquals(1, raw.cancelCount)
    }

    @Test fun `revocation fences late network audio asr speaker and display callbacks`() {
        val scope = scope()
        val callbacks = mutableListOf<() -> Unit>()
        val handles = mutableListOf<FakeCancellation>()
        fun add(callback: () -> Unit) = FakeCancellation().also { callbacks += callback; handles += it }
        val model = object : ModelClient {
            override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit) =
                add { listener(ModelStreamEvent.TextDelta("q", "synthetic")) }
        }
        val audio = object : AudioSubscription {
            override fun listen(listener: (AudioSegment) -> Unit) =
                add { listener(AudioSegment(envelope, AudioFormat.Unknown, byteArrayOf(1))) }
        }
        val asr = object : AsrSession {
            override fun listen(listener: (TranscriptRevision) -> Unit) =
                add { listener(TranscriptRevision(envelope, "t", 1, "synthetic", true, 0, 1)) }
        }
        val speaker = object : SpeakerService {
            override fun enroll(windows: List<SpeakerWindow>, listener: (SpeakerProfileRef?) -> Unit) = add { listener(null) }
            override fun assess(window: SpeakerWindow, listener: (SpeakerAssessment) -> Unit) =
                add { listener(SpeakerAssessment(envelope, SpeakerLabel.UNKNOWN, null, 0, 1)) }
            override fun delete(profile: SpeakerProfileRef) = Unit
        }
        var submissions = 0
        val output = object : OutputSink { override fun submit(assistance: Assistance): OutputSubmission {
            submissions++; return OutputSubmission.Accepted
        } }
        val services = ScopedHostServices(scope, setOf(Capability.Model, Capability.Audio, Capability.Asr,
            Capability.Speaker, Capability.TextOutput), mapOf(Capability.Model to model, Capability.Audio to audio,
            Capability.Asr to asr, Capability.Speaker to speaker, Capability.TextOutput to output), FakeTaskScope())
        var delivered = 0
        val request = ModelRequest(envelope, "q", "t", "p", 1, "test", listOf(InputPart.Text("test")),
            emptyList(), emptyList(), OutputModality.TEXT, 100)
        services.model().request(request) { delivered++ }
        services.audio().listen { delivered++ }
        services.asr().listen { delivered++ }
        services.speaker().enroll(emptyList()) { delivered++ }
        val window = SpeakerWindow(AudioSegment(envelope, AudioFormat.Unknown, byteArrayOf(1)), SampleRange("c", 0, 1), false)
        services.speaker().assess(window) { delivered++ }
        callbacks.forEach { it() }
        assertEquals(5, delivered)
        scope.revoke(); callbacks.forEach { it() }
        assertEquals(5, delivered)
        assertTrue(handles.all { it.cancelCount == 1 })
        assertFailsWith<ContractError.Cancelled> { services.model().request(request) {} }
        assertFailsWith<ContractError.Cancelled> { services.textOutput().submit(
            Assistance("h", SessionId("s"), Generation(0), "t", 1, "test", null, 100)) }
        assertEquals(0, submissions)
    }
}
