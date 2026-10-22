package com.sheen.rokid.host

import com.sheen.rokid.contracts.*
import com.sheen.rokid.plugin.*
import com.sheen.rokid.testsupport.*
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

    @Test fun `a granted device-state channel forwards the provider`() {
        val provider = object : DeviceStateProvider {
            override fun profile() = DeviceProfile(
                manufacturer = "Xiaomi",
                model = "M2012K11AC",
                deviceName = null,
                androidRelease = "13",
                apiLevel = 33,
                screenWidthPx = 1080,
                screenHeightPx = 2400,
                densityDpi = 440,
                glassesModel = null,
                glassesFirmware = null,
                glassesWidthPx = null,
                glassesHeightPx = null,
                appVersion = "0.3",
                locale = "zh-CN",
                timeZone = "Asia/Shanghai",
                wakeWords = listOf("EDITH"),
                capabilities = setOf("a11y"),
            )

            override fun state() = DeviceState(
                capturedAtEpochMillis = 0,
                timeZone = "Asia/Shanghai",
                localDate = "2026-10-20",
                weekday = "星期一",
                localTime = "18:23",
            )
        }
        val services = ScopedHostServices(
            scope(), setOf(Capability.DeviceState), mapOf(Capability.DeviceState to provider), FakeTaskScope(),
        )
        assertSame(provider, services.deviceState())
        assertEquals("M2012K11AC", services.deviceState().profile().model)
    }

    // edith-0.3 Phase 2 Task 1: perception is additive. The host forwards the provider untouched, exactly as it does
    // the other stateless read ports, and granting it must not disturb the device-state channel beside it.
    @Test fun `a granted perception channel forwards the provider and device state still forwards`() {
        val perception = object : PerceptionProvider {
            override fun beginTurn() = fakeSnapshot()
            override fun snapshot(include: Set<PerceptionSource>, filter: PerceptionFilter, limit: Int?) = fakeSnapshot()
            override fun afterAction() = fakeSnapshot()
            override fun screenshot(): PerceptionScreenshotResult = PerceptionScreenshotResult.Unavailable("test double")
            override fun endTurn() = Unit
        }
        val device = object : DeviceStateProvider {
            override fun profile() = DeviceProfile(
                manufacturer = "Xiaomi", model = "M2012K11AC", deviceName = null, androidRelease = "13",
                apiLevel = 33, screenWidthPx = 1080, screenHeightPx = 2400, densityDpi = 440,
                glassesModel = null, glassesFirmware = null, glassesWidthPx = null, glassesHeightPx = null,
                appVersion = "0.3", locale = "zh-CN", timeZone = "Asia/Shanghai", wakeWords = listOf("EDITH"),
                capabilities = setOf("a11y"),
            )
            override fun state() = DeviceState(
                capturedAtEpochMillis = 0, timeZone = "Asia/Shanghai", localDate = "2026-10-20",
                weekday = "星期一", localTime = "18:23",
            )
        }
        val services = ScopedHostServices(
            scope(), setOf(Capability.Perception, Capability.DeviceState),
            mapOf(Capability.Perception to perception, Capability.DeviceState to device), FakeTaskScope(),
        )

        assertSame(perception, services.perception())
        assertSame(device, services.deviceState())
    }

    private fun fakeSnapshot() = PerceptionSnapshot(
        snapshotId = "snap",
        capturedAtEpochMillis = 0,
        coverage = PerceptionCoverage(
            screen = "unavailable", notifications = "unavailable", media = "unavailable",
            calendar = "unavailable", acoustic = "unavailable", clipboard = "unavailable",
            vision = "disabled", glasses = "unavailable", device = "available",
        ),
    )

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
            emptyList(), OutputModality.TEXT, 100)
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

    @Test fun `a granted Confirmation channel forwards the question and a revoked scope refuses it`() {
        val seen = mutableListOf<Triple<String, String, Long>>()
        val provider = object : ConfirmationChannel {
            override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle {
                seen += Triple(requestId, summary, timeoutMillis)
                return object : CancellationHandle { override fun cancel() = Unit }
            }
        }
        val scope = scope()
        val services = ScopedHostServices(scope, setOf(Capability.Confirmation),
            mapOf(Capability.Confirmation to provider), FakeTaskScope())

        services.confirmation().present("r1", "卸载探针 App", 10_000L)
        assertEquals(listOf(Triple("r1", "卸载探针 App", 10_000L)), seen)

        scope.revoke()
        assertFailsWith<ContractError.Cancelled> { services.confirmation().present("r2", "x", 1_000L) }
        assertEquals(1, seen.size)
    }

    @Test fun `a scoped Confirmation channel forwards availability and reports a revoked scope unavailable`() {
        // 0.3 Phase 4B (B4): the plugin asks the *scoped* channel whether its surface can serve. Without this
        // forwarding the interface default `true` would run and the production channel's unavailability would never
        // reach the loop's early refusal.
        var canAsk = true
        val provider = object : ConfirmationChannel {
            override fun available(): Boolean = canAsk
            override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle =
                object : CancellationHandle { override fun cancel() = Unit }
        }
        val scope = scope()
        val services = ScopedHostServices(scope, setOf(Capability.Confirmation),
            mapOf(Capability.Confirmation to provider), FakeTaskScope())
        val confirmation = services.confirmation()

        assertTrue(confirmation.available())
        canAsk = false
        assertFalse(confirmation.available(), "the check must be live, not a snapshot taken at activation")

        scope.revoke()
        assertFalse(confirmation.available(), "a revoked scope can ask nobody")
    }

    @Test fun `a granted actions channel forwards beginTurn so a later turn re-arms the provider`() {
        var begins = 0
        var cancels = 0
        val provider = object : ActionExecutor {
            override fun descriptors() = emptyList<ActionDescriptor>()
            override fun execute(name: String, argsJson: String) = ActionResult(ActionOutcome.Done, "")
            override fun cancel() { cancels++ }
            override fun beginTurn() { begins++ }
        }
        val scope = scope()
        val services = ScopedHostServices(scope, setOf(Capability.Actions),
            mapOf(Capability.Actions to provider), FakeTaskScope())
        val actions = services.actions()

        // Teardown cancels the turn-scoped executor through the wrapper; the next turn must re-arm it
        // through the same wrapper, or the interface default no-op leaves it cancelled for the process.
        actions.cancel()
        assertEquals(1, cancels)
        actions.beginTurn()
        assertEquals(1, begins)

        scope.revoke()
        assertFailsWith<ContractError.Cancelled> { actions.beginTurn() }
        assertEquals(1, begins)
    }

    @Test fun `a granted actions channel forwards runtimeState under the same scope fence`() {
        // 0.25 Task 12 (R81): the plugin reads the runtime-state provider through the **scoped** executor it was
        // granted. Without an override here the interface default `null` runs, so the plugin would never see the
        // device kernel's state even though the production provider implements it.
        val provider = object : ActionExecutor {
            override fun descriptors() = emptyList<ActionDescriptor>()
            override fun execute(name: String, argsJson: String) = ActionResult(ActionOutcome.Done, "")
            override fun cancel() = Unit
            override fun runtimeState() = "foreground=com.android.settings revision=4 generation=2"
        }
        val scope = scope()
        val services = ScopedHostServices(scope, setOf(Capability.Actions),
            mapOf(Capability.Actions to provider), FakeTaskScope())
        val actions = services.actions()

        assertEquals("foreground=com.android.settings revision=4 generation=2", actions.runtimeState())

        scope.revoke()
        assertFailsWith<ContractError.Cancelled> { actions.runtimeState() }
    }

    @Test fun `an executor without runtime state answers null through the wrapper`() {
        val provider = object : ActionExecutor {
            override fun descriptors() = emptyList<ActionDescriptor>()
            override fun execute(name: String, argsJson: String) = ActionResult(ActionOutcome.Done, "")
            override fun cancel() = Unit
        }
        val services = ScopedHostServices(scope(), setOf(Capability.Actions),
            mapOf(Capability.Actions to provider), FakeTaskScope())

        assertNull(services.actions().runtimeState())
    }

    @Test fun `a granted actions channel forwards precheck and a revoked scope never reaches the classifier`() {
        val seen = mutableListOf<Pair<String, String>>()
        val provider = object : ActionExecutor {
            override fun descriptors() = emptyList<ActionDescriptor>()
            override fun execute(name: String, argsJson: String) = ActionResult(ActionOutcome.Done, "")
            override fun cancel() = Unit
            override fun precheck(name: String, argsJson: String): ActionPrecheck {
                seen += name to argsJson
                return ActionPrecheck(PrecheckDecision.RequireConfirmation, "终端命令：会删除数据", "IRREVERSIBLE")
            }
        }
        val scope = scope()
        val services = ScopedHostServices(scope, setOf(Capability.Actions),
            mapOf(Capability.Actions to provider), FakeTaskScope())
        val actions = services.actions()

        // 0.25 Task 11: `ConfirmingActions` asks the scoped executor before every call. Without this override the
        // interface default `Allow` answered here, so the production shell classifier never ran.
        val precheck = actions.precheck("shell", "{\"command\":\"pm clear com.x\"}")
        assertEquals(PrecheckDecision.RequireConfirmation, precheck.decision)
        assertEquals("IRREVERSIBLE", precheck.reason)
        assertEquals(listOf("shell" to "{\"command\":\"pm clear com.x\"}"), seen)

        scope.revoke()
        assertFailsWith<ContractError.Cancelled> { actions.precheck("shell", "{\"command\":\"dumpsys window\"}") }
        assertEquals(1, seen.size)
    }

    @Test fun `a plugin without the Confirmation capability is refused`() {
        val services = ScopedHostServices(scope(), emptySet(), emptyMap(), FakeTaskScope())
        assertFailsWith<ContractError.CapabilityMissing> { services.confirmation() }
    }

    @Test fun `a speech provider is handed over when granted`() {
        val player = object : SpeechPlayer {
            override fun play(assistance: Assistance, onState: (SpeechPlayback) -> Unit) = FakeCancellation()
        }
        val services = ScopedHostServices(scope(), setOf(Capability.SpeechOutput),
            mapOf(Capability.SpeechOutput to player), FakeTaskScope())
        val got = services.require(Capability.SpeechOutput)
        assertTrue(got is SpeechPlayer)
    }

    @Test fun `without a speech provider the capability still throws`() {
        val services = ScopedHostServices(scope(), emptySet(), emptyMap(), FakeTaskScope())
        assertFailsWith<ContractError.CapabilityMissing> { services.require(Capability.SpeechOutput) }
    }

    @Test fun `a blank confirmation id and a non positive window are rejected at the port`() {
        val provider = object : ConfirmationChannel {
            override fun present(requestId: String, summary: String, timeoutMillis: Long): CancellationHandle =
                object : CancellationHandle { override fun cancel() = Unit }
        }
        val services = ScopedHostServices(scope(), setOf(Capability.Confirmation),
            mapOf(Capability.Confirmation to provider), FakeTaskScope())
        assertFailsWith<IllegalArgumentException> { services.confirmation().present("  ", "s", 1_000L) }
        assertFailsWith<IllegalArgumentException> { services.confirmation().present("r1", "s", 0L) }
    }

    // edith-0.2 Phase A: SystemOne and Embedding capability registration
    @Test fun `require SystemOne returns SystemOneService`() {
        val mockService = object : SystemOneService {
            // A test double, so it answers every question the same way: the service is unavailable. What this test
            // pins is the capability plumbing, not the decision model.
            override fun ask(state: String, question: SystemOneQuestion) =
                SystemOneResult.Failure(FailureReason.MODEL_NOT_LOADED, "test double")
            override val isAvailable = true
            override val status = SystemOneStatus.READY
            override val modelInfo: ModelInfo? = null
        }
        val services = ScopedHostServices(scope(), setOf(Capability.SystemOne),
            mapOf(Capability.SystemOne to mockService), FakeTaskScope())
        assertSame(mockService, services.require(Capability.SystemOne))
    }

    @Test fun `require Embedding returns EmbeddingService`() {
        val mockService = object : EmbeddingService {
            override fun embed(text: String) = EmbedResult.Success(floatArrayOf(0.1f))
            override val isAvailable = true
            override val modelInfo: ModelInfo? = null
            override val dimensions = 256
        }
        val services = ScopedHostServices(scope(), setOf(Capability.Embedding),
            mapOf(Capability.Embedding to mockService), FakeTaskScope())
        assertSame(mockService, services.require(Capability.Embedding))
    }

    // edith-0.2 Phase B: the SKILL directory grant
    @Test fun `require StorageAccess returns the provider`() {
        val mockAccess = object : StorageAccess {
            override fun listFiles(dirPath: String, suffix: String) = emptyList<String>()
            override fun readFile(path: String): String? = null
            override fun deleteFile(path: String): Boolean = false
        }
        val services = ScopedHostServices(scope(), setOf(Capability.StorageAccess),
            mapOf(Capability.StorageAccess to mockAccess), FakeTaskScope())
        assertSame(mockAccess, services.require(Capability.StorageAccess))
    }

    @Test fun `a host that grants Storage but not StorageAccess does not hand over the directory`() {
        // The two grants are separate: an app-private key/value namespace is not a licence to read the user's files.
        val services = ScopedHostServices(scope(), setOf(Capability.Storage),
            mapOf(Capability.Storage to object : StorageHandle {
                override fun read(key: String): ByteArray? = null
                override fun write(key: String, value: ByteArray) = Unit
                override fun delete(key: String) = Unit
                override fun clear() = Unit
            }), FakeTaskScope())

        assertFailsWith<ContractError.CapabilityMissing> { services.require(Capability.StorageAccess) }
    }
}
