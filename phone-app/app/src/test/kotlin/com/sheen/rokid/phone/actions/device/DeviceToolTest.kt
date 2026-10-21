package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.device.AccessibilityPort
import com.sheen.rokid.device.ActKind
import com.sheen.rokid.device.Bounds
import com.sheen.rokid.device.DeviceEnvelope
import com.sheen.rokid.device.DeviceError
import com.sheen.rokid.device.DeviceErrorCodes
import com.sheen.rokid.device.DeviceKernel
import com.sheen.rokid.device.DeviceNode
import com.sheen.rokid.device.Foreground
import com.sheen.rokid.device.Gesture
import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.ObservationRenderer
import com.sheen.rokid.device.ObservationWindow
import com.sheen.rokid.device.Ref
import com.sheen.rokid.device.RefRegistry
import com.sheen.rokid.device.RefResult
import com.sheen.rokid.device.ScreenFilter
import com.sheen.rokid.device.Screenshot
import com.sheen.rokid.device.Timing
import com.sheen.rokid.phone.artifacts.FileArtifactStore
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.DeviceStateProvider
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.MediaState
import com.sheen.rokid.plugin.NotificationItem
import com.sheen.rokid.plugin.PerceptionCoverage
import com.sheen.rokid.plugin.PerceptionElement
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.PerceptionSource
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolVision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

// - Auto vision (0.3 Phase 2.1 Task 3): cross-fake capture ordering -

private const val EVENT_REFRESH = "perception.refresh"
private const val EVENT_SNAPSHOT = "perception.snapshot"
private const val EVENT_AFTER_ACTION = "perception.afterAction"
private const val EVENT_CAPTURE = "perception.screenshot"
private const val EVENT_KERNEL_ACT = "kernel.act"
private const val EVENT_KERNEL_WAIT = "kernel.wait"
private const val EVENT_KERNEL_OBSERVE = "kernel.observe"
private const val EVENT_KERNEL_SCREENSHOT = "kernel.screenshot"
private const val EVENT_KERNEL_STATUS = "kernel.status"
private const val KERNEL_EVENT_PREFIX = "kernel."

/**
 * Ordered event log shared by `FakePerception` and `FakeDeviceOperations` (test-only).
 *
 * Counters prove *that* a read or a capture happened; only the shared order can prove the auto-vision invariant that
 * the screenshot is taken **after** the observation it describes. Tests that assert order pass one instance to both
 * fakes, so kernel dispatches, forced reads and captures land on one timeline.
 */
private class EventLog {
    val entries = mutableListOf<String>()

    fun record(event: String) {
        entries += event
    }

    fun count(event: String): Int = entries.count { it == event }

    fun indexOf(event: String): Int = entries.indexOf(event)

    fun lastKernelIndex(): Int = entries.indexOfLast { it.startsWith(KERNEL_EVENT_PREFIX) }

    override fun toString(): String = entries.joinToString(prefix = "[", postfix = "]")
}

/**
 * R41/R42/R44/R46: `device` tool behaviour over the kernel seam.
 *
 * The tool is driven through a scripted [DeviceOperations] (the brief's "fake DeviceOperations"
 * option), so the assertions are about the tool's own argument handling, projection and envelopes,
 * not about the kernel's threading/settle logic (covered by the device-control tests).
 */
class DeviceToolTest {

    private val json = Json
    private val registry = RefRegistry()

    private fun tool(
        operations: FakeDeviceOperations,
        clock: () -> Long = { 0L },
        perception: PerceptionProvider? = null,
        deviceState: DeviceStateProvider? = null,
        refRegistry: RefRegistry? = null,
        foreground: () -> Foreground? = { null },
        nowMillis: () -> Long = { 0L },
        refTtlMillis: () -> Long = { 20_000L },
        visionEnabled: () -> Boolean = { true },
    ): DeviceTool {
        val projectorRegistry = refRegistry ?: registry
        return DeviceTool(
            operations,
            ScreenProjector(ObservationRenderer(projectorRegistry), projectorRegistry),
            clock,
            perception = perception,
            deviceState = deviceState,
            registry = refRegistry,
            foreground = foreground,
            nowMillis = nowMillis,
            refTtlMillis = refTtlMillis,
            visionEnabled = visionEnabled,
        )
    }

    // - observe / data.screen -

    @Test
    fun `observe fills data screen from the shared registry`() {
        val observation = observation(
            rev = 41,
            nodes = listOf(node("Wi-Fi")),
            windows = listOf(
                window(id = 0, type = "application", packageName = "com.android.settings"),
                window(id = 1, type = "ime", packageName = null),
            ),
        )
        val operations = FakeDeviceOperations(observation)

        val result = tool(operations).execute("""{"action":"observe"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        // Legacy composition (no perception port): the projector's tree is the only screen carrier and stays in `data`.
        val body = body(result)
        val screen = body.screen()
        assertEquals(41L, screen["rev"]!!.jsonPrimitive.long)
        assertEquals("com.android.settings", screen["app"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, screen["activity"])
        assertTrue(screen["ime"]!!.jsonPrimitive.boolean)
        assertFalse(screen["truncated"]!!.jsonPrimitive.boolean)
        val changes = screen["changes"]!!.jsonObject
        assertEquals("full", changes["mode"]!!.jsonPrimitive.content)
        assertTrue(changes["added"]!!.jsonArray.isEmpty())
        assertTrue(changes["updated"]!!.jsonArray.isEmpty())
        assertTrue(changes["removed"]!!.jsonArray.isEmpty())
        val text = screen["text"]!!.jsonPrimitive.content
        assertTrue("full render must carry node lines: $text", text.contains("#n"))
        val ref = Regex("#n\\d+").find(text)!!.value
        assertTrue(registry.resolve(observation, Ref(ref)) is RefResult.Ok)
        assertEquals(ScreenFilter.INTERACTIVE, operations.observeFilter)
    }

    @Test
    fun `a second observe projects a diff with text null`() {
        val operations = FakeDeviceOperations(
            observation(rev = 1, nodes = listOf(node("A"), node("B"), node("C"))),
        )
        val tool = tool(operations)
        tool.execute("""{"action":"observe"}""")

        operations.observation = observation(rev = 2, nodes = listOf(node("A"), node("B2"), node("C")))
        val result = tool.execute("""{"action":"observe","filter":"all"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val screen = body(result).screen()
        assertEquals("diff", screen["changes"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, screen["text"])
        assertEquals(1, screen["changes"]!!.jsonObject["updated"]!!.jsonArray.size)
        assertEquals(ScreenFilter.ALL, operations.observeFilter)
    }

    @Test
    fun `an act projects the kernel's post-action observation as a diff`() {
        val operations = FakeDeviceOperations(
            observation(rev = 1, nodes = listOf(node("A"), node("B"), node("C"))),
        )
        val tool = tool(operations)
        tool.execute("""{"action":"observe"}""")
        // The kernel's auto-observation after a write is a new revision of the same screen.
        operations.observation = observation(rev = 2, nodes = listOf(node("A"), node("B2"), node("C")))

        val result = tool.execute("""{"action":"act","kind":"tap","ref":"#n1"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val screen = body(result).screen()
        assertEquals(2L, screen["rev"]!!.jsonPrimitive.long)
        assertEquals("diff", screen["changes"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, screen["text"])
        assertEquals(1, screen["changes"]!!.jsonObject["updated"]!!.jsonArray.size)
    }

    @Test
    fun `observe without application window or ime reports null app and ime false`() {
        val operations = FakeDeviceOperations(
            observation(rev = 3, nodes = listOf(node("A")), windows = emptyList()),
        )

        val screen = body(tool(operations).execute("""{"action":"observe"}""")).screen()

        assertEquals(JsonNull, screen["app"])
        assertFalse(screen["ime"]!!.jsonPrimitive.boolean)
        assertEquals(JsonNull, screen["activity"])
    }

    @Test
    fun `the schema tells the model to use a media key after opening a player`() {
        val descriptor = tool(FakeDeviceOperations(observation())).descriptors().single()

        assertTrue(descriptor.parametersJson.contains("MEDIA_PLAY_PAUSE"))
        assertTrue(descriptor.parametersJson.contains("播放"))
    }

    // - act -

    @Test
    fun `act accepts a ref without the hash prefix`() {
        val operations = FakeDeviceOperations(observation())
        val result = tool(operations).execute("""{"action":"act","kind":"tap","ref":"n1"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(Ref("#n1"), operations.actCalls.single().ref)
    }

    @Test
    fun `act passes kind ref value direction key and timeout through to the kernel`() {
        val operations = FakeDeviceOperations(observation())
        val tool = tool(operations)

        tool.execute("""{"action":"act","kind":"type","ref":"#n3","value":"hello","timeout_ms":1234}""")
        tool.execute("""{"action":"act","kind":"scroll","direction":"backward"}""")
        tool.execute("""{"action":"act","kind":"press_key","key":"HOME"}""")

        val typed = operations.actCalls[0]
        assertEquals(ActKind.TYPE, typed.kind)
        assertEquals(Ref("#n3"), typed.ref)
        assertEquals("hello", typed.value)
        assertEquals(1234L, typed.timeoutMillis)
        assertEquals("backward", operations.actCalls[1].direction)
        assertEquals("HOME", operations.actCalls[2].key)
        assertEquals(ActKind.PRESS_KEY, operations.actCalls[2].kind)
    }

    @Test
    fun `act projects data screen and maps a recoverable kernel error to Refused`() {
        val operations = FakeDeviceOperations(observation())
        operations.actEnvelope = failure("act", "DEVICE_SCREEN_OFF", recoverable = true)

        val result = tool(operations).execute("""{"action":"act","kind":"tap","ref":"#n1"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val execution = executionOf(result)
        assertEquals("DEVICE_SCREEN_OFF", execution.code)
        assertTrue(execution.recoverable)
        assertEquals(ToolOutcome.Refused, execution.outcome)
    }

    @Test
    fun `an unsupported kind is refused with INVALID_ARGUMENTS and never reaches the kernel`() {
        val operations = FakeDeviceOperations(observation())

        val result = tool(operations).execute("""{"action":"act","kind":"teleport"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("INVALID_ARGUMENTS", executionOf(result).code)
        assertTrue(operations.actCalls.isEmpty())
    }

    // - scroll_to_text (R46) -

    @Test
    fun `scroll_to_text scrolls once then waits and stops on a hit`() {
        val operations = FakeDeviceOperations(observation())
        operations.waitEnvelopes.add(success("wait"))

        val result = tool(operations).execute(
            """{"action":"act","kind":"scroll_to_text","text":"Wi-Fi","timeout_ms":5000}""",
        )

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(listOf("act:SCROLL_TO_TEXT", "wait:Wi-Fi"), operations.calls)
        assertEquals("forward", operations.actCalls.single().direction)
        assertEquals(1, operations.waitCalls.size)
        assertEquals("Wi-Fi", operations.waitCalls.single().text)
    }

    @Test
    fun `scroll_to_text repeats scroll and wait until the text appears`() {
        val operations = FakeDeviceOperations(observation())
        operations.waitEnvelopes.add(failure("wait", "VERIFY_TIMEOUT", recoverable = true))
        operations.waitEnvelopes.add(success("wait"))

        val result = tool(operations).execute("""{"action":"act","kind":"scroll_to_text","text":"Wi-Fi"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(2, operations.actCalls.size)
        assertEquals(2, operations.waitCalls.size)
    }

    @Test
    fun `scroll_to_text returns VERIFY_TIMEOUT when the total budget is spent`() {
        var now = 0L
        val operations = FakeDeviceOperations(observation())
        operations.onWait = { now += 100 }
        operations.waitEnvelope = failure("wait", "VERIFY_TIMEOUT", recoverable = true)

        val result = tool(operations, clock = { now }).execute(
            """{"action":"act","kind":"scroll_to_text","text":"never-there","timeout_ms":50}""",
        )

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("VERIFY_TIMEOUT", executionOf(result).code)
        assertEquals(1, operations.actCalls.size)
    }

    @Test
    fun `scroll_to_text needs a text target`() {
        val operations = FakeDeviceOperations(observation())

        val result = tool(operations).execute("""{"action":"act","kind":"scroll_to_text"}""")

        assertEquals("INVALID_ARGUMENTS", executionOf(result).code)
        assertTrue(operations.actCalls.isEmpty())
    }

    @Test
    fun `scroll_to_text with perception runs one post-action read and returns a compact screen`() {
        // Task 7 review C1: scroll_to_text dispatches a real kernel act, so it must run the same forced post-action
        // read every other act runs. Before the fix it returned the projector's `bounds=` text and left the provider
        // cache one screen behind.
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        // Review important 3: a real DeviceKernel wait success reports applied=null (it applies nothing) and its own
        // `expected` post-condition. The old fixture invented applied=true, which no kernel wait can produce.
        operations.waitEnvelopes.add(
            DeviceEnvelope.success(
                action = "wait",
                channel = "accessibility",
                accepted = true,
                applied = null,
                verified = com.sheen.rokid.device.Verified("expected", 2L),
                timing = Timing(0, 0, 0),
            ),
        )
        val perception = FakePerception(
            snapshot = perceptionSnapshot(),
            afterActionSnapshot = perceptionSnapshot(
                snapshotId = "snap-after-scroll",
                elements = listOf(
                    perceptionElement(
                        ref = "#n4",
                        role = "button",
                        text = "目标",
                        centerX = 11,
                        centerY = 22,
                        clickable = true,
                        bounds = "0,0,22,44",
                    ),
                ),
            ),
        )
        perception.screenshotResult = screenshotImage("img-scroll-success")

        val result = tool(operations, perception = perception).execute(
            """{"action":"act","kind":"scroll_to_text","text":"目标","timeout_ms":5000}""",
        )

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("the forced post-action read must run exactly once", 1, perception.afterActions)
        val observation = observationOf(result)
        assertEquals("snap-after-scroll", observation.snapshotId)
        assertEquals(
            "screen=ok notifications=ok media=ok calendar=ok acoustic=ok clipboard=ok vision=ok glasses=ok device=ok",
            observation.coverage,
        )
        // Task 8: the unknown `changed` is never written into `data`; the change marker here is the full/empty view of
        // the compact observation, not a claimed state change.
        val data = body(result)
        assertFalse("no data.changed when applied is unknown", data.containsKey("changed"))
        assertEquals("full", data["changes"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertTrue(
            "the compact post-scroll screen replaces the projected bounds view: ${observation.screen}",
            observation.screen!!.contains("""#n4 button "目标" center=(11,22) [clickable]"""),
        )
        assertFalse("scroll_to_text must not leak projector bounds: ${observation.screen}", observation.screen!!.contains("bounds="))

        // Task 4: the scroll is one write result with its own post-action observation and one picture. The facts come
        // from the final wait envelope: applied/changed are unknown, the wait's own post-condition is verified.
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertNull("a kernel wait success reports applied=null", execution.applied)
        assertNull("without applied evidence there is no changed fact", execution.changed)
        assertEquals(true, execution.verified)
        assertFalse(execution.partial)
        assertTrue(observation.screen!!.contains("#n4"))
        assertEquals(ToolVision.Ok, observation.vision)
        assertEquals("the whole scroll loop attaches exactly one image", 1, result.images.size)
    }

    @Test
    fun `scroll_to_text timeout with perception still runs the post-action read and keeps the failure`() {
        var now = 0L
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        operations.onWait = { now += 100 }
        operations.waitEnvelope = failure("wait", "VERIFY_TIMEOUT", recoverable = true)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(),
            afterActionSnapshot = perceptionSnapshot(
                snapshotId = "snap-timeout",
                elements = listOf(perceptionElement(ref = "#n1", text = "A")),
            ),
        )
        perception.screenshotResult = screenshotImage("img-scroll-timeout")

        val result = tool(operations, clock = { now }, perception = perception).execute(
            """{"action":"act","kind":"scroll_to_text","text":"never","timeout_ms":50}""",
        )

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("VERIFY_TIMEOUT", executionOf(result).code)
        assertEquals(1, perception.afterActions)
        assertFalse("no data.changed when applied is unknown", body(result).containsKey("changed"))
        assertEquals("full", body(result)["changes"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertFalse(
            "no bounds in the failed scroll either: ${observationOf(result).screen}",
            observationOf(result).screen.orEmpty().contains("bounds="),
        )
        assertEquals(1, operations.actCalls.size)

        // Task 4: a scroll that ran but never proved the target is a partial execution, and its observation is the
        // final screen it saw. `applied`/`changed` stay null because no post-condition was proven.
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("VERIFY_TIMEOUT", execution.code)
        assertNull(execution.applied)
        assertNull(execution.changed)
        assertTrue("a scroll that dispatched but did not verify is partial", execution.partial)
        val observation = observationOf(result)
        assertEquals("snap-timeout", observation.snapshotId)
        assertTrue(observation.screen!!.contains("#n1"))
        assertEquals(ToolVision.Ok, observation.vision)
        assertEquals(1, result.images.size)
    }

    @Test
    fun `an early scroll_to_text kernel failure still runs the post-action read`() {
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        operations.actEnvelope = failure("act", "CHANNEL_UNAVAILABLE", recoverable = true)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(),
            afterActionSnapshot = perceptionSnapshot(
                snapshotId = "snap-failed-scroll",
                elements = listOf(perceptionElement(ref = "#n1", text = "A")),
            ),
        )

        val result = tool(operations, perception = perception).execute(
            """{"action":"act","kind":"scroll_to_text","text":"目标"}""",
        )

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CHANNEL_UNAVAILABLE", executionOf(result).code)
        assertEquals("a dispatched scroll always gets the forced read", 1, perception.afterActions)
        assertEquals("snap-failed-scroll", observationOf(result).snapshotId)
        assertFalse(
            "no bounds after a failed scroll: ${observationOf(result).screen}",
            observationOf(result).screen.orEmpty().contains("bounds="),
        )
    }

    // - screenshot (R44) -

    @Test
    fun `screenshot keeps the pending ImageRef and never inlines bytes`() {
        val operations = FakeDeviceOperations(observation())
        operations.screenshotEnvelope = DeviceEnvelope.success(
            action = "screenshot",
            channel = "accessibility",
            accepted = true,
            applied = null,
            verified = null,
            timing = Timing(0, 0, 0),
            images = listOf(ImageRef("pending", "image/jpeg", 768, 1664, "")),
        )

        val result = tool(operations).execute("""{"action":"screenshot"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val images = result.images
        assertEquals(1, images.size)
        assertEquals("pending", images[0].artifactId)
        assertEquals("image/jpeg", images[0].mime)
        assertEquals(768, images[0].width)
        assertFalse(result.detail.contains("base64", ignoreCase = true))
        assertFalse("data no longer repeats the image list", body(result).containsKey("images"))
    }

    @Test
    fun `screenshot preserves the kernel artifact diagnostic instead of a generic line`() {
        // R131: the kernel writes `diagnostic=screenshot_artifact_pending; reason=...` when the bytes were captured but
        // the store failed. Replacing it with "screenshot is an artifact reference" makes a broken store look like a
        // normal pending artifact, which is the failure the wearer would never learn about.
        val operations = FakeDeviceOperations(observation())
        operations.screenshotEnvelope = DeviceEnvelope.success(
            action = "screenshot",
            channel = "accessibility",
            accepted = true,
            applied = null,
            verified = null,
            timing = Timing(0, 0, 0),
            data = "diagnostic=screenshot_artifact_pending; reason=artifact store unavailable",
            images = listOf(ImageRef("pending", "image/jpeg", 768, 1664, "")),
        )

        val result = tool(operations).execute("""{"action":"screenshot"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val diagnostic = body(result)["diagnostic"]!!.jsonPrimitive.content
        assertTrue(
            "the kernel diagnostic must survive the projection, was `$diagnostic`",
            diagnostic.contains("screenshot_artifact_pending"),
        )
        assertTrue(
            "the store's reason must survive too: `$diagnostic`",
            diagnostic.contains("artifact store unavailable"),
        )
        assertEquals("pending", result.images.single().artifactId)
    }

    @Test
    fun `a failed screenshot does not repeat its kernel error code in data`() {
        // Fix review: the failure reason is `execution.code`; the pre-fix `data.diagnostic=UNSUPPORTED` was a second
        // copy of the same fact. With no extra kernel diagnostic, `data` is empty.
        val operations = FakeDeviceOperations(observation())
        operations.screenshotEnvelope = failure("screenshot", "UNSUPPORTED", recoverable = false)

        val result = tool(operations).execute("""{"action":"screenshot"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        val body = body(result)
        assertFalse("the stable code is not duplicated into data", body.containsKey("diagnostic"))
        assertEquals("UNSUPPORTED", executionOf(result).code)
    }

    @Test
    fun `a real kernel screenshot carries its stored artifact reference in the action result`() {
        // 0.25 Task 24 (C1/R133/R135): production shape, not a hand-built envelope. The real kernel stores the bytes
        // and answers the real ImageRef; the tool must project it onto ActionResult.images, which is the only channel
        // the agent loop can carry to the model request.
        val root = Files.createTempDirectory("edith-device-tool-shot-").toFile()
        try {
            val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
            val kernel = DeviceKernel(
                accessibility = ScreenshotPort(Screenshot(bytes, "image/jpeg", 768, 1664)),
                shell = null,
                artifactStore = FileArtifactStore(root),
            )
            val screenTool = DeviceTool(
                DeviceKernelOperations(kernel),
                ScreenProjector(ObservationRenderer(registry), registry),
                { 0L },
            )
            try {
                val result = screenTool.execute("""{"action":"screenshot"}""")

                assertEquals(ActionOutcome.Done, result.outcome)
                val image = result.images.single()
                assertNotEquals("a stored screenshot is a real reference, not the pending placeholder", "pending", image.artifactId)
                assertEquals("image/jpeg", image.mime)
                assertEquals(768, image.width)
                assertEquals(1664, image.height)
                assertTrue("the artifact identity is the store's content hash: $image", image.sha256.isNotBlank())

                // R131: the existing diagnostic JSON is unchanged; the image reference is carried beside it.
                val body = body(result)
                assertEquals(
                    "screenshot is an artifact reference; bytes are not inlined",
                    body["diagnostic"]!!.jsonPrimitive.content,
                )
                assertFalse("data no longer repeats the image list", body.containsKey("images"))
            } finally {
                screenTool.close()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `a screenshot without a store still carries the pending reference and the kernel diagnostic`() {
        val kernel = DeviceKernel(
            accessibility = ScreenshotPort(Screenshot(byteArrayOf(9, 9, 9), "image/jpeg", 768, 1664)),
            shell = null,
            artifactStore = null,
        )
        val screenTool = DeviceTool(
            DeviceKernelOperations(kernel),
            ScreenProjector(ObservationRenderer(registry), registry),
            { 0L },
        )
        try {
            val result = screenTool.execute("""{"action":"screenshot"}""")

            assertEquals(ActionOutcome.Done, result.outcome)
            assertEquals(ImageRef("pending", "image/jpeg", 768, 1664, ""), result.images.single())
            val diagnostic = body(result)["diagnostic"]!!.jsonPrimitive.content
            assertTrue("the kernel's pending reason must survive: $diagnostic", diagnostic.contains("screenshot_artifact_pending"))
            assertTrue("the store's absence must survive: $diagnostic", diagnostic.contains("NO_STORE"))
        } finally {
            screenTool.close()
        }
    }

    // - wait -

    @Test
    fun `wait needs at least one condition`() {
        val operations = FakeDeviceOperations(observation())

        val result = tool(operations).execute("""{"action":"wait"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("INVALID_ARGUMENTS", executionOf(result).code)
        assertTrue(operations.waitCalls.isEmpty())
    }

    @Test
    fun `wait passes text foreground idle and timeout through`() {
        val operations = FakeDeviceOperations(observation())

        val result = tool(operations).execute(
            """{"action":"wait","text":"Done","foreground":"com.example","idle":true,"timeout_ms":700}""",
        )

        assertEquals(ActionOutcome.Done, result.outcome)
        val call = operations.waitCalls.single()
        assertEquals("Done", call.text)
        assertEquals("com.example", call.foreground)
        assertTrue(call.idle)
        assertEquals(700L, call.timeoutMillis)
    }

    // - status -

    @Test
    fun `status data is a JSON object that preserves the kernel diagnostic text`() {
        val operations = FakeDeviceOperations(observation())
        operations.statusEnvelope = success(
            "status",
            data = "foreground=com.example.app/? interactive=true locked=false accessibility=true shell=false",
        )

        val result = tool(operations).execute("""{"action":"status"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val data = body(result)
        assertEquals(
            "foreground=com.example.app/? interactive=true locked=false accessibility=true shell=false",
            data["status"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `an unobserved shell write keeps the kernel diagnostic instead of a bare error code`() {
        val operations = FakeDeviceOperations(observation = null)
        operations.actEnvelope = DeviceEnvelope.success(
            action = "act",
            channel = "shell",
            accepted = true,
            applied = null,
            verified = null,
            timing = Timing(0, 0, 0),
            data = "diagnostic=shell_accepted_unobserved; the accessibility service is not bound",
        )

        val result = tool(operations).execute("""{"action":"act","kind":"tap","ref":"#n1"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        // Legacy composition (no perception port): the projector's tree is the only screen carrier, so it stays in data.
        val data = body(result)
        assertEquals(JsonNull, data["screen"])
        assertEquals(
            "diagnostic=shell_accepted_unobserved; the accessibility service is not bound",
            data["diagnostic"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun `a stale observation is not shown in place of an unobserved write diagnostic`() {
        val operations = FakeDeviceOperations(observation())
        operations.actEnvelope = DeviceEnvelope.success(
            action = "act",
            channel = "shell",
            accepted = true,
            applied = null,
            verified = null,
            timing = Timing(0, 0, 0),
            data = "diagnostic=shell_accepted_unobserved; the screen observation failed: VERIFY_TIMEOUT",
        )

        val result = tool(operations).execute("""{"action":"act","kind":"tap","ref":"#n1"}""")

        val data = body(result)
        assertEquals(JsonNull, data["screen"])
        assertTrue(
            data["diagnostic"]!!.jsonPrimitive.content.startsWith("diagnostic=shell_accepted_unobserved"),
        )
    }

    // - turn life cycle (R42) -

    @Test
    fun `beginTurn re-arms the kernel and makes the first observation of the turn full again`() {
        val operations = FakeDeviceOperations(
            observation(rev = 1, nodes = listOf(node("A"), node("B"), node("C"))),
        )
        val tool = tool(operations)
        tool.execute("""{"action":"observe"}""")
        operations.observation = observation(rev = 2, nodes = listOf(node("A"), node("B2"), node("C")))
        assertEquals(
            "diff",
            body(tool.execute("""{"action":"observe"}"""))
                .screen()["changes"]!!.jsonObject["mode"]!!.jsonPrimitive.content,
        )

        tool.beginTurn()
        operations.observation = observation(rev = 3, nodes = listOf(node("D"), node("E"), node("F")))
        val screen = body(tool.execute("""{"action":"observe"}""")).screen()

        assertEquals(1, operations.begins)
        assertEquals("full", screen["changes"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertFalse(screen["text"] is JsonNull)
    }

    // - failure mapping -

    @Test
    fun `a non-recoverable kernel error maps to Failed`() {
        val operations = FakeDeviceOperations(observation())
        operations.observeEnvelope = failure("observe", "UNSUPPORTED", recoverable = false)

        val result = tool(operations).execute("""{"action":"observe"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals("UNSUPPORTED", executionOf(result).code)
    }

    @Test
    fun `the device tool refuses an unknown name rather than guessing`() {
        val operations = FakeDeviceOperations(observation())

        assertEquals(
            "UNKNOWN_ACTION",
            tool(operations).execute("shell", "{}").execution!!.code,
        )
        assertTrue(operations.calls.isEmpty())
    }

    // - Task 7: compact observe, act verification, status/coverage -

    @Test
    fun `observe with perception returns the compact snapshot and never bounds`() {
        val operations = FakeDeviceOperations(observation())
        val perception = FakePerception(
            snapshot = perceptionSnapshot(
                elements = listOf(
                    perceptionElement(
                        ref = "#n12",
                        role = "button",
                        text = "搜索",
                        centerX = 540,
                        centerY = 320,
                        clickable = true,
                        bounds = "360,280,720,360",
                    ),
                ),
                truncated = true,
                screenCoverage = "truncated",
            ),
        )

        val result = tool(operations, perception = perception).execute("""{"action":"observe"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        // Task 8: the observation is the one screen carrier (a perception composition has no `data.screen`).
        val observation = observationOf(result)
        assertEquals("snap-7", observation.snapshotId)
        assertEquals(
            "screen=truncated notifications=ok media=ok calendar=ok acoustic=ok clipboard=ok vision=ok glasses=ok device=ok",
            observation.coverage,
        )
        assertEquals("com.example.app", observation.foregroundPackage)
        assertEquals(".MainActivity", observation.foregroundActivity)
        assertTrue(observation.truncated)
        val text = observation.screen!!
        assertTrue("the compact line must be model-facing: $text", text.contains("""#n12 button "搜索" center=(540,320) [clickable]"""))
        assertTrue("truncation must be visible: $text", text.contains("truncated"))
        assertFalse("bounds must never face the model: $text", text.contains("bounds="))
        assertFalse("a perception composition does not repeat the tree in data", body(result).containsKey("screen"))
        assertEquals("the default include is screen only", setOf(PerceptionSource.SCREEN), perception.lastInclude)
        assertEquals(PerceptionFilter.INTERACTIVE, perception.lastFilter)
        assertNull(perception.lastLimit)
        assertTrue("a perception observe never falls back to the old kernel projection", operations.calls.isEmpty())
    }

    @Test
    fun `observe with perception refreshes the cached observation before projecting`() {
        // 0.3 Phase 2 final review C1: `snapshot()` is cache-only, so a `device.observe` that only called it would serve
        // the turn's opening screen after a manual change. An explicit observe must read first and project the fresh
        // snapshot; `snapshot` then provides the caller's include/filter/limit projection from the fresh cache.
        val perception = FakePerception(
            snapshot = perceptionSnapshot(
                snapshotId = "snap-cached",
                elements = listOf(perceptionElement(ref = "#n1", text = "缓存的旧屏幕")),
            ),
            refreshedSnapshot = perceptionSnapshot(
                snapshotId = "snap-fresh",
                elements = listOf(perceptionElement(ref = "#n2", text = "手动切换后的新屏幕")),
            ),
        )

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"observe"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(1, perception.refreshCalls)
        val observation = observationOf(result)
        assertEquals("snap-fresh", observation.snapshotId)
        assertTrue(
            "the explicit observe must answer from the refreshed observation: ${observation.screen}",
            observation.screen!!.contains("手动切换后的新屏幕"),
        )
        assertFalse(observation.screen!!.contains("缓存的旧屏幕"))
        assertEquals("the caller's projection still applies after the refresh", PerceptionFilter.INTERACTIVE, perception.lastFilter)
    }

    @Test
    fun `every device result is flagged non-persistent including wait screenshot and legacy paths`() {
        // 0.3 Phase 2 final review C2 (re-review): every DeviceTool result can carry screen text. The legacy projector
        // path (`perception == null`) still renders the raw kernel observation into `wait`/`observe`, and the wait path
        // used `result()`'s default, so a wait result was persisted as-is. The flag now covers the whole tool, not only
        // the perception-backed branches; `screenshot` is included because its image references must not outlive the run.
        val perception = FakePerception(perceptionSnapshot())
        val device = tool(FakeDeviceOperations(observation()), perception = perception)

        assertTrue("observe carries perception content", device.execute("""{"action":"observe"}""").nonPersistent)
        assertTrue("status carries notification/media content", device.execute("""{"action":"status"}""").nonPersistent)
        assertTrue(
            "act returns the post-action compact snapshot",
            device.execute("""{"action":"act","kind":"home"}""").nonPersistent,
        )
        assertTrue(
            "wait projects the kernel screen for the current run",
            device.execute("""{"action":"wait","idle":true}""").nonPersistent,
        )
        assertTrue("a screenshot carries an artifact reference", device.execute("""{"action":"screenshot"}""").nonPersistent)
        assertTrue(
            "the pre-perception observe still projects raw screen text",
            tool(FakeDeviceOperations(observation())).execute("""{"action":"observe"}""").nonPersistent,
        )
        assertTrue(
            "the pre-perception wait is the same leak",
            tool(FakeDeviceOperations(observation())).execute("""{"action":"wait","idle":true}""").nonPersistent,
        )
    }

    @Test
    fun `wait with a provider keeps the sentinel for the run but is flagged non-persistent`() {
        // Re-review C2: the wait path never set the flag, so AgentLoop stored this detail verbatim. The model still
        // needs the observed screen in this run; the action result must now say the body may not be persisted.
        val sentinel = "WAIT-SCREEN-SENTINEL-3f81"
        val operations = FakeDeviceOperations(observation(nodes = listOf(node(sentinel))))
        val perception = FakePerception(
            perceptionSnapshot(elements = listOf(perceptionElement(ref = "#n1", text = sentinel))),
        )
        val result = tool(operations, perception = perception)
            .execute("""{"action":"wait","idle":true}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue(
            "the in-run model request must still see the observed screen: ${result.observation}",
            observationOf(result).screen!!.contains(sentinel),
        )
        assertTrue("a wait result may not be persisted", result.nonPersistent)
    }

    @Test
    fun `observe passes include and limit to the provider and returns the requested sources`() {
        val operations = FakeDeviceOperations(observation())
        val perception = FakePerception(
            snapshot = perceptionSnapshot(
                notifications = listOf(
                    NotificationItem(
                        packageName = "com.tencent.mm",
                        appLabel = "微信",
                        title = "张三",
                        text = "晚上一起吃饭吗？",
                        postedAtEpochMillis = 1_700_000_000_000L,
                    ),
                ),
                media = MediaState("PLAYING", title = "稻香", artist = "周杰伦", album = "魔杰座"),
                calendarTitles = listOf("19:30 项目评审"),
                acoustic = "speech",
                clipboardText = "合成剪贴板文本",
            ),
        )
        val deviceState = FakeDeviceState(
            deviceState(
                batteryPercent = 76,
                charging = true,
                network = "wifi",
                panelVisible = false,
                glassesConnected = true,
                glassesWearing = false,
            ),
        )

        val result = tool(operations, perception = perception, deviceState = deviceState).execute(
            """{"action":"observe","include":["screen","notifications","media","calendar","acoustic","clipboard","glasses","device"],"limit":4}""",
        )

        assertEquals(ActionOutcome.Done, result.outcome)
        val data = body(result)
        assertEquals("微信", data["notifications"]!!.jsonArray[0].jsonObject["app"]!!.jsonPrimitive.content)
        assertEquals("晚上一起吃饭吗？", data["notifications"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("PLAYING", data["media"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals("稻香", data["media"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("19:30 项目评审", data["calendar"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("speech", data["acoustic"]!!.jsonPrimitive.content)
        assertEquals("合成剪贴板文本", data["clipboard"]!!.jsonPrimitive.content)
        assertTrue(data["glasses"]!!.jsonObject["connected"]!!.jsonPrimitive.boolean)
        assertFalse(data["glasses"]!!.jsonObject["wearing"]!!.jsonPrimitive.boolean)
        assertTrue(data["device"]!!.jsonPrimitive.content.contains("battery=76%"))
        assertEquals(PerceptionSource.entries.toSet(), perception.lastInclude)
        assertEquals(4, perception.lastLimit)
    }

    @Test
    fun `observe all expands to every perception source`() {
        val perception = FakePerception(snapshot = perceptionSnapshot())

        tool(FakeDeviceOperations(observation()), perception = perception).execute("""{"action":"observe","include":["all"]}""")

        assertEquals(PerceptionSource.entries.toSet(), perception.lastInclude)
    }

    @Test
    fun `act with a stale ref refuses with STALE_REF and never calls the kernel act`() {
        val observation = observation(rev = 1, nodes = listOf(node("A")))
        val operations = FakeDeviceOperations(observation)
        val actionRegistry = RefRegistry()
        actionRegistry.beginSnapshot("snap-old", 500L, "com.example.settings", null)
        actionRegistry.assign(observation)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(
                snapshotId = "snap-old-cache",
                elements = listOf(perceptionElement(ref = "#n0", text = "旧缓存")),
            ),
            refreshedSnapshot = perceptionSnapshot(
                snapshotId = "snap-fresh",
                elements = listOf(perceptionElement(ref = "#n9", text = "A")),
            ),
        )

        val result = tool(
            operations,
            perception = perception,
            refRegistry = actionRegistry,
            foreground = { Foreground("com.example.settings", null) },
            nowMillis = { 1_000L },
            refTtlMillis = { 0L },
        ).execute("""{"action":"act","kind":"tap","ref":"#n0"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("STALE_REF", executionOf(result).code)
        assertTrue(
            "a stale ref must be answered with a freshly observed compact snapshot: ${result.observation}",
            observationOf(result).screen!!.contains("#n9"),
        )
        assertTrue("the action must not be dispatched", operations.actCalls.isEmpty())
        assertTrue("the stale answer carries fresh perception content", result.nonPersistent)
        assertEquals("the stale answer is a real read, not the cached turn snapshot", 1L, perception.refreshCalls.toLong())
        assertEquals("afterAction is reserved for post-act only", 0L, perception.afterActions.toLong())
    }

    @Test
    fun `act with a fresh ref dispatches and returns the post-action snapshot and changed`() {
        val before = observation(rev = 1, nodes = listOf(node("A")))
        val operations = FakeDeviceOperations(before)
        operations.actEnvelope = DeviceEnvelope.success(
            action = "act",
            channel = "accessibility",
            accepted = true,
            applied = true,
            verified = com.sheen.rokid.device.Verified("screen_change", 2L),
            timing = Timing(0, 0, 0),
        )
        // The kernel's post-action read: the tool's pre-check must still see the pre-action screen (the latest
        // observation at call time), so the fake switches to the post screen only inside `act`.
        operations.actObservation = observation(rev = 2, nodes = listOf(node("B")))
        val actionRegistry = RefRegistry()
        actionRegistry.beginSnapshot("snap-before", 900L, "com.example.settings", null)
        actionRegistry.assign(before)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(),
            afterActionSnapshot = perceptionSnapshot(
                snapshotId = "snap-after",
                elements = listOf(perceptionElement(ref = "#n5", text = "B")),
            ),
        )

        val result = tool(
            operations,
            perception = perception,
            refRegistry = actionRegistry,
            foreground = { Foreground("com.example.settings", null) },
            nowMillis = { 1_000L },
            refTtlMillis = { 20_000L },
        ).execute("""{"action":"act","kind":"tap","ref":"#n0"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(1, operations.actCalls.size)
        assertEquals(Ref("#n0"), operations.actCalls.single().ref)
        assertEquals(1, perception.afterActions)
        // Task 8: the observation is the one screen carrier; `data` keeps only the change marker and timing.
        val observation = observationOf(result)
        assertEquals("snap-after", observation.snapshotId)
        assertEquals(
            "screen=ok notifications=ok media=ok calendar=ok acoustic=ok clipboard=ok vision=ok glasses=ok device=ok",
            observation.coverage,
        )
        assertEquals("com.example.app", observation.foregroundPackage)
        assertEquals(".MainActivity", observation.foregroundActivity)
        assertTrue(
            "the action's own screen projection must be replaced by the compact post-action view",
            observation.screen!!.contains("""#n5 text "B" center=(0,0)"""),
        )
        assertFalse("act must not leak projector bounds either", observation.screen.orEmpty().contains("bounds="))
        val data = body(result)
        assertEquals("full", data["changes"]!!.jsonObject["mode"]!!.jsonPrimitive.content)
        assertFalse("data no longer repeats the screen facts", data.containsKey("screen"))
        assertFalse("data no longer repeats the snapshot id", data.containsKey("snap"))
        assertFalse("data no longer repeats coverage", data.containsKey("coverage"))
        assertFalse("data no longer repeats the applied answer", data.containsKey("changed"))
        assertEquals(true, executionOf(result).changed)
    }

    // - Phase 2.1 Task 3: auto vision on observe/act -

    /*
     * Auto vision ordering and scope.
     *
     * Authoritative contract (Task 8): `ActionResult.images` and the `observation.vision` member are the auto-vision
     * result. The tool-specific `data` no longer repeats a vision marker (the pre-Task-8 `data.vision` copy is gone), so
     * these tests assert the structured observation and must not "fix" a missing data marker by adding one back.
     *
     * Capture scope: argument validation runs before any perception call, so early `INVALID_ARGUMENTS` refusals
     * never capture. A stale-ref refusal and a kernel-dispatched refusal do capture, each after its own fresh read
     * (`refresh()` for stale, `afterAction()` for a dispatch). This matches the brief.
     *
     * Ordering evidence: counters alone cannot tell "captured after the read" from "captured before it", so these
     * tests share one [EventLog] between the perception fake and the device-operations fake.
     */

    private fun screenshotImage(id: String): PerceptionScreenshotResult.Image =
        PerceptionScreenshotResult.Image(
            image = ImageRef(id, "image/jpeg", 720, 1280, "sha-$id"),
            mime = "image/jpeg",
            width = 720,
            height = 1280,
        )

    private fun visionOf(result: ActionResult): String {
        val observation = observationOf(result)
        return when (observation.vision) {
            ToolVision.Ok -> "ok"
            ToolVision.Disabled -> "disabled"
            ToolVision.Unavailable -> "unavailable(${observation.visionReason ?: "?"})"
            ToolVision.NotRequested -> "not_requested"
        }
    }

    /** Fails unless both events were logged and [before] happened first. */
    private fun assertBefore(log: EventLog, before: String, after: String) {
        val beforeIndex = log.indexOf(before)
        val afterIndex = log.indexOf(after)
        assertTrue(
            "$before must happen before $after; ordered fake log: $log",
            beforeIndex >= 0 && afterIndex >= 0 && beforeIndex < afterIndex,
        )
    }

    /** A non-observe/act action must never attach an automatic image or advertise a vision marker. */
    private fun assertNoAutoVision(action: String, result: ActionResult) {
        assertTrue("$action must not attach an auto-captured image: ${result.images}", result.images.isEmpty())
        assertFalse(
            "$action must not carry a duplicated vision marker: ${result.detail}",
            body(result).containsKey("vision"),
        )
    }

    @Test
    fun `observe attaches one screenshot and vision ok`() {
        val log = EventLog()
        val perception = FakePerception(snapshot = perceptionSnapshot(), events = log)
        perception.screenshotResult = screenshotImage("img-1")

        val result = tool(FakeDeviceOperations(observation(), events = log), perception = perception)
            .execute("""{"action":"observe"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("the screen include asks for exactly one capture", 1, perception.screenshotCalls)
        assertEquals("img-1", result.images.single().artifactId)
        assertEquals("ok", visionOf(result))
        assertBefore(log, EVENT_REFRESH, EVENT_CAPTURE)
        assertBefore(log, EVENT_SNAPSHOT, EVENT_CAPTURE)
    }

    @Test
    fun `observe without screen include does not capture`() {
        val perception = FakePerception(snapshot = perceptionSnapshot())
        perception.screenshotResult = screenshotImage("img-notifications")

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"observe","include":["notifications"]}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(0, perception.screenshotCalls)
        assertTrue(result.images.isEmpty())
        assertFalse(
            "a non-screen observe must not advertise vision it did not capture: ${result.detail}",
            body(result).containsKey("vision"),
        )
        val observation = observationOf(result)
        assertEquals("no picture was wanted, so none is claimed", ToolVision.NotRequested, observation.vision)
        assertNull(observation.visionReason)
        assertNull("a non-screen observe must not report a screen tree", observation.screen)
        assertFalse("without a screen read nothing can be truncated", observation.truncated)
    }

    @Test
    fun `observe when vision disabled still succeeds without image`() {
        val perception = FakePerception(snapshot = perceptionSnapshot())
        perception.screenshotResult = PerceptionScreenshotResult.Disabled

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"observe"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(1, perception.screenshotCalls)
        assertTrue(result.images.isEmpty())
        assertEquals("disabled", visionOf(result))
        val observation = observationOf(result)
        assertEquals("snap-7", observation.snapshotId)
        assertEquals(ToolVision.Disabled, observation.vision)
        assertNull("the disabled value is its own reason", observation.visionReason)
    }

    @Test
    fun `act attaches post action screenshot and vision ok`() {
        val log = EventLog()
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))), events = log)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(),
            afterActionSnapshot = perceptionSnapshot(snapshotId = "snap-after-act"),
            events = log,
        )
        perception.screenshotResult = screenshotImage("img-act")

        val result = tool(operations, perception = perception)
            .execute("""{"action":"act","kind":"home"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("the act forces exactly one post-action read", 1, perception.afterActions)
        assertEquals(1, perception.screenshotCalls)
        assertEquals("img-act", result.images.single().artifactId)
        assertEquals("ok", visionOf(result))
        assertEquals(
            "the capture must follow the dispatched act and its forced read; ordered fake log: $log",
            listOf(EVENT_KERNEL_ACT, EVENT_AFTER_ACTION, EVENT_CAPTURE),
            log.entries.filter { it == EVENT_KERNEL_ACT || it == EVENT_AFTER_ACTION || it == EVENT_CAPTURE },
        )
    }

    @Test
    fun `stale ref answer returns fresh screenshot and stale code`() {
        val log = EventLog()
        val observation = observation(rev = 1, nodes = listOf(node("A")))
        val operations = FakeDeviceOperations(observation, events = log)
        val actionRegistry = RefRegistry()
        actionRegistry.beginSnapshot("snap-old", 500L, "com.example.settings", null)
        actionRegistry.assign(observation)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(
                snapshotId = "snap-old-cache",
                elements = listOf(perceptionElement(ref = "#n0", text = "旧缓存")),
            ),
            refreshedSnapshot = perceptionSnapshot(
                snapshotId = "snap-fresh",
                elements = listOf(perceptionElement(ref = "#n9", text = "A")),
            ),
            events = log,
        )
        perception.screenshotResult = screenshotImage("img-stale")

        val result = tool(
            operations,
            perception = perception,
            refRegistry = actionRegistry,
            foreground = { Foreground("com.example.settings", null) },
            nowMillis = { 1_000L },
            refTtlMillis = { 0L },
        ).execute("""{"action":"act","kind":"tap","ref":"#n0"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("STALE_REF", executionOf(result).code)
        assertEquals("the stale answer reads the screen once", 1, perception.refreshCalls)
        assertEquals("afterAction is reserved for post-act only", 0, perception.afterActions)
        // CompactSnapshotRenderer renders elements, not the snapshot id; `#n9` is the model-visible proof that the
        // fresh `snap-fresh` read (not the cached `snap-old-cache`) produced this answer.
        val snapshotText = observationOf(result).screen.orEmpty()
        assertTrue("the refreshed screen must be in the observation: $snapshotText", snapshotText.contains("#n9"))
        assertFalse("the cached turn snapshot must not answer the stale ref: $snapshotText", snapshotText.contains("旧缓存"))
        assertEquals("the refreshed screen is captured for the refusal", 1, perception.screenshotCalls)
        assertEquals("img-stale", result.images.single().artifactId)
        assertEquals("ok", visionOf(result))
        assertTrue("the stale answer remains non-persistent", result.nonPersistent)
        assertTrue("the action must not be dispatched", operations.actCalls.isEmpty())
        assertBefore(log, EVENT_REFRESH, EVENT_CAPTURE)
        assertEquals("the stale answer captures exactly once", 1, log.count(EVENT_CAPTURE))
        assertEquals("the stale answer never forces a post-act read", 0, log.count(EVENT_AFTER_ACTION))

        // Task 4: the refusal is a real execution fact and its observation is the refreshed screen.
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("STALE_REF", execution.code)
        assertEquals(true, execution.recoverable)
        assertEquals("the action was never dispatched", false, execution.accepted)
        assertEquals(false, execution.applied)
        assertNull(execution.changed)
        val observed = observationOf(result)
        assertEquals("snap-fresh", observed.snapshotId)
        assertTrue("the stale observation is the refreshed screen: ${observed.screen}", observed.screen!!.contains("#n9"))
        assertEquals(ToolVision.Ok, observed.vision)
        assertEquals(1, result.images.size)
    }

    @Test
    fun `observe screenshot unavailable keeps result and carries reason`() {
        val perception = FakePerception(snapshot = perceptionSnapshot())
        perception.screenshotResult = PerceptionScreenshotResult.Unavailable("SCREENSHOT_FAILED")

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"observe"}""")

        assertEquals("a failed capture does not fail the observation", ActionOutcome.Done, result.outcome)
        assertTrue(result.images.isEmpty())
        assertEquals("unavailable(SCREENSHOT_FAILED)", visionOf(result))
        val observation = observationOf(result)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("SCREENSHOT_FAILED", observation.visionReason)
    }

    @Test
    fun `a throwing provider capture degrades to unavailable instead of failing the call`() {
        // The provider contract says screenshot() returns an answer, never throws. If a future implementation breaks
        // that, the tool must still not turn a successful observe into TOOL_THREW: degrade the vision marker only.
        val perception = FakePerception(snapshot = perceptionSnapshot())
        perception.screenshotThrows = true

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"observe"}""")

        assertEquals("a broken capture port must not fail the observation", ActionOutcome.Done, result.outcome)
        assertEquals(1, perception.screenshotCalls)
        assertTrue("a throwing capture contributes no image", result.images.isEmpty())
        assertEquals("unavailable(SCREENSHOT_FAILED)", visionOf(result))
        val observation = observationOf(result)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("SCREENSHOT_FAILED", observation.visionReason)
    }

    @Test
    fun `scroll to text captures one screenshot for the final result`() {
        val log = EventLog()
        val operations = FakeDeviceOperations(
            observation(rev = 1, nodes = listOf(node("A"))),
            events = log,
        )
        operations.waitEnvelopes.add(failure("wait", "VERIFY_TIMEOUT", recoverable = true))
        operations.waitEnvelopes.add(
            DeviceEnvelope.success(
                action = "wait",
                channel = "accessibility",
                accepted = true,
                applied = null,
                verified = com.sheen.rokid.device.Verified("expected", 2L),
                timing = Timing(0, 0, 0),
            ),
        )
        val perception = FakePerception(
            snapshot = perceptionSnapshot(),
            afterActionSnapshot = perceptionSnapshot(snapshotId = "snap-after-scroll"),
            events = log,
        )
        perception.screenshotResult = screenshotImage("img-scroll")

        val result = tool(operations, perception = perception).execute(
            """{"action":"act","kind":"scroll_to_text","text":"目标","timeout_ms":5000}""",
        )

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("two inner scroll rounds ran", 2, operations.actCalls.size)
        assertEquals("only the one final actResult captures", 1, perception.screenshotCalls)
        assertEquals("img-scroll", result.images.single().artifactId)
        assertEquals("ok", visionOf(result))
        assertEquals("the whole scroll loop captures exactly once", 1, log.count(EVENT_CAPTURE))
        assertEquals("the final envelope forces exactly one post-action read", 1, log.count(EVENT_AFTER_ACTION))
        assertBefore(log, EVENT_AFTER_ACTION, EVENT_CAPTURE)
        assertTrue(
            "the single capture must follow the last dispatch/wait; ordered fake log: $log",
            log.indexOf(EVENT_CAPTURE) > log.lastKernelIndex(),
        )
    }

    @Test
    fun `status wait and explicit screenshot never auto capture`() {
        val log = EventLog()
        val perception = FakePerception(snapshot = perceptionSnapshot(), events = log)
        perception.screenshotResult = screenshotImage("img-must-not-be-used")
        val operations = FakeDeviceOperations(observation(), events = log)
        val device = tool(operations, perception = perception)

        val status = device.execute("""{"action":"status"}""")
        assertEquals("status never captures", 0, perception.screenshotCalls)
        assertNoAutoVision("status", status)

        val waited = device.execute("""{"action":"wait","idle":true}""")
        assertEquals("wait never captures", 0, perception.screenshotCalls)
        assertNoAutoVision("wait", waited)

        val screenshot = device.execute("""{"action":"screenshot"}""")
        assertEquals("the explicit screenshot does not route through auto vision", 0, perception.screenshotCalls)
        assertEquals("the explicit action uses the kernel's own screenshot path", 1, operations.screenshotCalls)
        assertNoAutoVision("screenshot", screenshot)

        assertEquals("no auto capture ran for any of the three actions", 0, log.count(EVENT_CAPTURE))
    }

    @Test
    fun `observe truncation keys the screen flag off the screen coverage not the snapshot`() {
        // Task 7 review I3: notifications cut at the policy cap set the snapshot-wide `truncated`, which used to mark a
        // complete screen as truncated and append the marker. The screen flag must follow `coverage.screen`.
        val perception = FakePerception(
            snapshot = perceptionSnapshot(
                elements = listOf(perceptionElement(ref = "#n1", text = "A")),
                truncated = true,
                screenCoverage = "ok",
                notificationsCoverage = "truncated",
            ),
        )

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"observe"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val observation = observationOf(result)
        assertFalse("a complete screen must stay untruncated: ${observation.coverage}", observation.truncated)
        assertFalse(
            "a notification cut must not append a screen truncation marker: ${observation.screen}",
            observation.screen!!.contains("truncated"),
        )
        assertTrue(
            "the notifications source must still report its own cut",
            observation.coverage!!.contains("notifications=truncated"),
        )
    }

    @Test
    fun `act truncation also keys the screen flag off the screen coverage`() {
        val before = observation(rev = 1, nodes = listOf(node("A")))
        val operations = FakeDeviceOperations(before)
        operations.actEnvelope = DeviceEnvelope.success(
            action = "act",
            channel = "accessibility",
            accepted = true,
            applied = true,
            verified = com.sheen.rokid.device.Verified("screen_change", 2L),
            timing = Timing(0, 0, 0),
        )
        operations.actObservation = observation(rev = 2, nodes = listOf(node("B")))
        val actionRegistry = RefRegistry()
        actionRegistry.beginSnapshot("snap-before", 900L, "com.example.settings", null)
        actionRegistry.assign(before)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(),
            afterActionSnapshot = perceptionSnapshot(
                snapshotId = "snap-after",
                elements = listOf(perceptionElement(ref = "#n5", text = "B")),
                truncated = true,
                screenCoverage = "ok",
                notificationsCoverage = "truncated",
            ),
        )

        val result = tool(
            operations,
            perception = perception,
            refRegistry = actionRegistry,
            foreground = { Foreground("com.example.settings", null) },
            nowMillis = { 1_000L },
            refTtlMillis = { 20_000L },
        ).execute("""{"action":"act","kind":"tap","ref":"#n0"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        // Task 8: the notification cut is an observation fact; the screen flag follows the screen source only.
        val observation = observationOf(result)
        assertFalse("act must apply the same screen-specific rule", observation.truncated)
        assertFalse(
            "act's compact text must not append the marker for a notification cut",
            observation.screen!!.contains("truncated"),
        )
    }

    @Test
    fun `an act that changed nothing stays a refusal and tells the model to observe first`() {
        val before = observation(rev = 1, nodes = listOf(node("A")))
        val operations = FakeDeviceOperations(before)
        operations.actEnvelope = failure("act", DeviceErrorCodes.ACCEPTED_NO_EFFECT, recoverable = true)
            .copy(applied = false)
        val actionRegistry = RefRegistry()
        actionRegistry.beginSnapshot("snap-before", 900L, "com.example.settings", null)
        actionRegistry.assign(before)

        val result = tool(
            operations,
            perception = FakePerception(perceptionSnapshot()),
            refRegistry = actionRegistry,
            foreground = { Foreground("com.example.settings", null) },
            nowMillis = { 1_000L },
            refTtlMillis = { 20_000L },
        ).execute("""{"action":"act","kind":"tap","ref":"#n0"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals(DeviceErrorCodes.ACCEPTED_NO_EFFECT, executionOf(result).code)
        val data = body(result)
        assertFalse("no data.changed=false for an unknown applied", data.containsKey("changed"))
        assertTrue(
            "the refusal must tell the model to observe before deciding again: $data",
            data["note"]!!.jsonPrimitive.content.contains("先观察再决定"),
        )
    }

    @Test
    fun `status with perception reports coverage device media glasses and no screen tree`() {
        val operations = FakeDeviceOperations(observation())
        operations.statusEnvelope = success(
            "status",
            data = "foreground=com.example.app/.MainActivity interactive=false locked=true accessibility=false shell=false",
        )
        val perception = FakePerception(
            snapshot = perceptionSnapshot(
                notifications = listOf(
                    NotificationItem(packageName = "com.android.mms", title = "信息", text = "文件收到了", postedAtEpochMillis = 1L),
                ),
                media = MediaState("PLAYING", title = "稻香", artist = "周杰伦"),
            ),
        )
        val deviceState = FakeDeviceState(
            deviceState(
                batteryPercent = 76,
                charging = true,
                network = "wifi",
                panelVisible = false,
                glassesConnected = true,
                glassesWearing = false,
            ),
        )

        val result = tool(operations, perception = perception, deviceState = deviceState).execute(
            """{"action":"status","include":["device","notifications","media"]}""",
        )

        assertEquals(ActionOutcome.Done, result.outcome)
        val data = body(result)
        assertEquals(
            "foreground=com.example.app/.MainActivity interactive=false locked=true accessibility=false shell=false",
            data["status"]!!.jsonPrimitive.content,
        )
        // Task 8: coverage and foreground are observation facts; `data` keeps the status-specific sources.
        val observation = observationOf(result)
        assertEquals(
            "screen=ok notifications=ok media=ok calendar=ok acoustic=ok clipboard=ok vision=ok glasses=ok device=ok",
            observation.coverage,
        )
        assertTrue(data["device"]!!.jsonPrimitive.content.contains("battery=76%"))
        assertEquals("com.example.app", observation.foregroundPackage)
        assertEquals(".MainActivity", observation.foregroundActivity)
        assertEquals("PLAYING", data["media"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals("稻香", data["media"]!!.jsonObject["title"]!!.jsonPrimitive.content)
        assertEquals("信息", data["notifications"]!!.jsonArray[0].jsonObject["title"]!!.jsonPrimitive.content)
        assertFalse(data["panel"]!!.jsonPrimitive.boolean)
        assertTrue(data["glasses"]!!.jsonObject["connected"]!!.jsonPrimitive.boolean)
        assertFalse("data no longer repeats coverage", data.containsKey("coverage"))
        assertFalse("data no longer repeats the foreground", data.containsKey("foreground"))
        assertFalse("status must never repeat the screen tree", data.containsKey("screen"))
        assertFalse("status must never carry compact screen text", data.containsKey("text"))
        assertFalse("no screen refs in a status result", result.detail.contains("#n"))
    }

    @Test
    fun `status default include asks only for device and omits notification content`() {
        val perception = FakePerception(
            snapshot = perceptionSnapshot(
                notifications = listOf(
                    NotificationItem(packageName = "com.android.mms", title = "信息", postedAtEpochMillis = 1L),
                ),
            ),
        )

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"status"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("the ambient status question defaults to device facts", setOf(PerceptionSource.DEVICE), perception.lastInclude)
        val data = body(result)
        assertFalse("notification content must not be read unasked", data.containsKey("notifications"))
        assertTrue("the device facts are always present", data.containsKey("device"))
    }

    @Test
    fun `status ignores a shared-schema source it does not serve`() {
        // Review minor 6: the one schema advertises every `observe` source, so a model may send `screen` for status.
        // Refusing that would answer "invalid" to a legal tool-call shape; status ignores the sources it cannot serve.
        val perception = FakePerception(
            snapshot = perceptionSnapshot(
                notifications = listOf(
                    NotificationItem(packageName = "com.android.mms", title = "信息", postedAtEpochMillis = 1L),
                ),
            ),
        )

        val result = tool(FakeDeviceOperations(observation()), perception = perception).execute(
            """{"action":"status","include":["screen","calendar","notifications"]}""",
        )

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(setOf(PerceptionSource.NOTIFICATIONS), perception.lastInclude)
        assertTrue(
            "the notifications that were requested must still be returned",
            body(result).containsKey("notifications"),
        )
    }

    @Test
    fun `status still refuses an unknown include token`() {
        val perception = FakePerception(perceptionSnapshot())

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"status","include":["telepathy"]}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("INVALID_ARGUMENTS", executionOf(result).code)
        assertEquals("an invalid status include must not read the device", 0, perception.refreshCalls)
        assertEquals(0, perception.screenshotCalls)
        val execution = executionOf(result)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertEquals(ToolVision.Unavailable, observationOf(result).vision)
    }

    @Test
    fun `observe refuses malformed include tokens and non-positive limits without a device read`() {
        val perception = FakePerception(perceptionSnapshot())
        val device = tool(FakeDeviceOperations(observation()), perception = perception)

        listOf(
            """{"action":"observe","include":["screen","bogus"]}""",
            """{"action":"observe","include":"screen"}""",
            """{"action":"observe","include":["screen",1]}""",
            """{"action":"observe","include":[] ,"limit":0}""",
            """{"action":"observe","limit":-3}""",
            """{"action":"observe","limit":"4"}""",
            """{"action":"observe","filter":"telepathy"}""",
        ).forEach { args ->
            val result = device.execute(args)
            assertEquals("$args must be INVALID_ARGUMENTS", ActionOutcome.Refused, result.outcome)
            assertEquals("$args must be INVALID_ARGUMENTS", "INVALID_ARGUMENTS", executionOf(result).code)
            // Task 4: an argument refusal still answers with the current screen text, but never a picture.
            val execution = executionOf(result)
            assertEquals("$args must not reach a device channel", false, execution.accepted)
            assertEquals("$args must not claim the action applied", false, execution.applied)
            val observation = observationOf(result)
            assertEquals(ToolVision.Unavailable, observation.vision)
            assertEquals("not_attempted", observation.visionReason)
            assertTrue("$args must not picture the screen", result.images.isEmpty())
        }
        assertEquals("no real perception read may run for invalid arguments", 0, perception.refreshCalls)
        assertEquals(0, perception.afterActions)
        assertEquals(0, perception.screenshotCalls)
    }

    @Test
    fun `act refreshes one observation when the turn has no latest one`() {
        // Minor 4: the gate's "no latest observation yet" branch. One `observe(ALL)` must run, the tool must re-read
        // the kernel's cache, and the resolved ref must be dispatched - not skipped.
        val refreshed = observation(rev = 1, nodes = listOf(node("A")))
        val operations = FakeDeviceOperations(observation = null)
        operations.observeObservation = refreshed
        val actionRegistry = RefRegistry()
        actionRegistry.beginSnapshot("snap-gate", 0L, "com.example.settings", null)
        actionRegistry.assign(refreshed)

        val result = tool(
            operations,
            perception = FakePerception(perceptionSnapshot()),
            refRegistry = actionRegistry,
            foreground = { Foreground("com.example.settings", null) },
            nowMillis = { 0L },
            refTtlMillis = { 20_000L },
        ).execute("""{"action":"act","kind":"tap","ref":"#n0"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("the gate must refresh with one full observation", ScreenFilter.ALL, operations.observeFilter)
        assertEquals(1, operations.calls.count { it == "observe:ALL" })
        assertEquals(Ref("#n0"), operations.actCalls.single().ref)
    }

    @Test
    fun `act skips the ref gate when one refresh observation still cannot be made`() {
        // Minor 4: still unobservable after the refresh -> the kernel decides. The raw ref is passed through, never a
        // refusal invented by the tool.
        val operations = FakeDeviceOperations(observation = null)

        val result = tool(
            operations,
            perception = FakePerception(perceptionSnapshot()),
            refRegistry = RefRegistry(),
        ).execute("""{"action":"act","kind":"tap","ref":"#n7"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("the fallback observation was attempted", 1, operations.calls.count { it == "observe:ALL" })
        assertEquals("the kernel gets the model's ref unchanged", Ref("#n7"), operations.actCalls.single().ref)
    }

    @Test
    fun `screenshot is refused with VISION_DISABLED and never reaches the kernel when vision is off`() {
        val operations = FakeDeviceOperations(observation())
        val perception = FakePerception(perceptionSnapshot())

        val result = tool(operations, visionEnabled = { false }, perception = perception)
            .execute("""{"action":"screenshot"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("VISION_DISABLED", executionOf(result).code)
        assertEquals(0, operations.screenshotCalls)
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("VISION_DISABLED", execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertNull(execution.changed)
        val observation = observationOf(result)
        assertEquals(ToolVision.Disabled, observation.vision)
        assertNull(observation.visionReason)
        assertTrue("a disabled switch never captures", result.images.isEmpty())
        assertEquals(0, perception.screenshotCalls)
    }

    @Test
    fun `isStatus is true only for the status action`() {
        val device = tool(FakeDeviceOperations(observation()))

        assertTrue(device.isStatus("""{"action":"status"}"""))
        assertTrue("the include field must not stop the exemption", device.isStatus("""{"action":"status","include":["device"]}"""))
        assertFalse(device.isStatus("""{"action":"observe"}"""))
        assertFalse(device.isStatus("""{"action":"act","kind":"tap"}"""))
        assertFalse(device.isStatus("""{"include":["device"]}"""))
        assertFalse(device.isStatus("not json"))
    }

    // - 0.3 Phase 2.2 Task 4: execution facts + observation on every result path -

    /*
     * Every `device` result must let the model answer four questions without prose: did the call run/apply/verify, and
     * what does the screen look like now? The tests below pin that on every reachable result path, including the
     * argument and vision refusals that never reach the kernel. Images stay on `ActionResult.images` and are never
     * fabricated: a picture is attached at most once, and `vision=ok` only when one really was.
     */

    private fun executionOf(result: ActionResult): ToolExecution {
        assertNotNull("the device result must carry structured execution facts", result.execution)
        return result.execution!!
    }

    private fun observationOf(result: ActionResult): ToolObservation {
        assertNotNull("the device result must carry a post-call observation", result.observation)
        return result.observation!!
    }

    @Test
    fun `the device descriptor declares that its results observe the screen`() {
        assertTrue(tool(FakeDeviceOperations(observation())).descriptors().single().screenObservation)
    }

    @Test
    fun `a result without a perception port carries execution facts but no fabricated observation`() {
        val result = tool(FakeDeviceOperations(observation())).execute("""{"action":"observe"}""")

        val execution = executionOf(result)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertNull("a read applies nothing", execution.applied)
        assertNull("this composition has no perception port", result.observation)
    }

    @Test
    fun `a kernel observe failure carries execution facts and no invented observation`() {
        val operations = FakeDeviceOperations(observation())
        operations.observeEnvelope = failure("observe", "CHANNEL_UNAVAILABLE", recoverable = true)

        val result = tool(operations).execute("""{"action":"observe"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("CHANNEL_UNAVAILABLE", execution.code)
        assertEquals(true, execution.recoverable)
        assertEquals(false, execution.accepted)
        assertNull(execution.applied)
        assertNull(execution.changed)
        assertNull("without a perception port the tool must not invent a screen", result.observation)
    }

    @Test
    fun `observe success reports done execution and a compact observation with vision ok`() {
        val perception = FakePerception(
            snapshot = perceptionSnapshot(
                elements = listOf(perceptionElement(ref = "#n1", role = "button", text = "搜索", clickable = true)),
            ),
        )
        perception.screenshotResult = screenshotImage("img-observe")

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"observe"}""")

        val execution = executionOf(result)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertNull("a read applies nothing", execution.applied)
        assertNull(execution.verified)
        assertNull("a read has no screen-change evidence", execution.changed)
        assertFalse(execution.uncertain)
        assertFalse(execution.partial)

        val observation = observationOf(result)
        assertEquals("snap-7", observation.snapshotId)
        assertEquals("com.example.app", observation.foregroundPackage)
        assertEquals(".MainActivity", observation.foregroundActivity)
        assertEquals(
            "screen=ok notifications=ok media=ok calendar=ok acoustic=ok clipboard=ok vision=ok glasses=ok device=ok",
            observation.coverage,
        )
        assertTrue(observation.screen!!.contains("""#n1 button "搜索" center=(0,0) [clickable]"""))
        assertFalse(observation.truncated)
        assertEquals(ToolVision.Ok, observation.vision)
        assertNull(observation.visionReason)
        assertEquals("the screen include takes exactly one picture", 1, perception.screenshotCalls)
        assertEquals(1, result.images.size)
    }

    @Test
    fun `act success reports applied and changed facts with one post-action observation`() {
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        operations.actEnvelope = DeviceEnvelope.success(
            action = "act",
            channel = "accessibility",
            accepted = true,
            applied = true,
            verified = com.sheen.rokid.device.Verified("screen_change", 2L),
            timing = Timing(0, 0, 0),
        )
        val perception = FakePerception(
            snapshot = perceptionSnapshot(),
            afterActionSnapshot = perceptionSnapshot(
                snapshotId = "snap-after-act",
                elements = listOf(perceptionElement(ref = "#n5", text = "B")),
            ),
        )
        perception.screenshotResult = screenshotImage("img-act")

        val result = tool(operations, perception = perception).execute("""{"action":"act","kind":"home"}""")

        val execution = executionOf(result)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertEquals(true, execution.applied)
        assertEquals(true, execution.verified)
        assertEquals(true, execution.changed)

        val observation = observationOf(result)
        assertEquals("the action's own post-action read is the observation", "snap-after-act", observation.snapshotId)
        assertTrue(observation.screen!!.contains("""#n5 text "B" center=(0,0)"""))
        assertEquals(ToolVision.Ok, observation.vision)
        assertEquals(1, result.images.size)
        assertEquals(1, perception.afterActions)
        assertEquals(1, perception.screenshotCalls)
    }

    @Test
    fun `an act refusal reports applied false and changed false with a best-effort observation image`() {
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        operations.actEnvelope = failure("act", DeviceErrorCodes.ACCEPTED_NO_EFFECT, recoverable = true)
            .copy(applied = false)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(),
            afterActionSnapshot = perceptionSnapshot(snapshotId = "snap-no-effect"),
        )
        perception.screenshotResult = screenshotImage("img-no-effect")

        val result = tool(operations, perception = perception)
            .execute("""{"action":"act","kind":"tap","ref":"#n0"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals(DeviceErrorCodes.ACCEPTED_NO_EFFECT, execution.code)
        assertEquals(true, execution.recoverable)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertNull(execution.verified)
        assertEquals(false, execution.changed)
        assertTrue("accepted but provably no effect is a partial execution", execution.partial)

        val observation = observationOf(result)
        assertEquals("snap-no-effect", observation.snapshotId)
        assertEquals(ToolVision.Ok, observation.vision)
        assertEquals(1, result.images.size)
    }

    @Test
    fun `an act failure with unknown applied keeps the facts null and still observes with one image`() {
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        operations.actEnvelope = failure("act", "VERIFY_TIMEOUT", recoverable = true)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(),
            afterActionSnapshot = perceptionSnapshot(
                snapshotId = "snap-failed-act",
                elements = listOf(perceptionElement(ref = "#n1", text = "A")),
            ),
        )
        perception.screenshotResult = screenshotImage("img-failed-act")

        val result = tool(operations, perception = perception)
            .execute("""{"action":"act","kind":"tap","ref":"#n0"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("VERIFY_TIMEOUT", execution.code)
        assertEquals(false, execution.accepted)
        assertNull("a failed write is not known to have applied", execution.applied)
        assertNull(execution.verified)
        assertNull("a failed write is not known to have changed the screen", execution.changed)
        assertFalse(execution.uncertain)

        val observation = observationOf(result)
        assertEquals("snap-failed-act", observation.snapshotId)
        assertTrue(observation.screen!!.contains("#n1"))
        assertEquals(ToolVision.Ok, observation.vision)
        assertEquals(1, result.images.size)
    }

    @Test
    fun `an act timeout is uncertain and keeps its best-effort observation and image`() {
        // Review critical 1: a timed-out write may still have been applied by the platform. The envelope's
        // accepted=false only means the kernel never got the platform's answer, so no tri-state fact may be claimed and
        // the best-effort observation/picture must survive.
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        operations.actEnvelope = failure("act", DeviceErrorCodes.ACTION_TIMEOUT, recoverable = true)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(),
            afterActionSnapshot = perceptionSnapshot(
                snapshotId = "snap-timeout-act",
                elements = listOf(perceptionElement(ref = "#n1", text = "A")),
            ),
        )
        perception.screenshotResult = screenshotImage("img-timeout-act")

        val result = tool(operations, perception = perception)
            .execute("""{"action":"act","kind":"tap","ref":"#n0"}""")

        assertEquals("the legacy 3-state projection cannot express uncertainty", ActionOutcome.Refused, result.outcome)
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Uncertain, execution.outcome)
        assertEquals(DeviceErrorCodes.ACTION_TIMEOUT, execution.code)
        assertTrue("a timeout is explicitly uncertain", execution.uncertain)
        assertNull("the platform may have accepted the write", execution.accepted)
        assertNull("the platform may have applied the write", execution.applied)
        assertNull("there is no screen-change evidence", execution.changed)
        assertNull(execution.verified)
        assertFalse(execution.partial)

        val observation = observationOf(result)
        assertEquals("snap-timeout-act", observation.snapshotId)
        assertTrue(observation.screen!!.contains("#n1"))
        assertEquals(ToolVision.Ok, observation.vision)
        assertEquals("the timed-out act keeps its best-effort picture", 1, result.images.size)
        assertEquals(1, perception.screenshotCalls)
    }

    @Test
    fun `a wait timeout is uncertain and keeps its best-effort observation and image`() {
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        operations.waitEnvelope = failure("wait", DeviceErrorCodes.ACTION_TIMEOUT, recoverable = true)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(elements = listOf(perceptionElement(ref = "#n1", text = "A"))),
        )
        perception.screenshotResult = screenshotImage("img-timeout-wait")

        val result = tool(operations, perception = perception)
            .execute("""{"action":"wait","text":"never"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Uncertain, execution.outcome)
        assertEquals(DeviceErrorCodes.ACTION_TIMEOUT, execution.code)
        assertTrue(execution.uncertain)
        assertNull(execution.accepted)
        assertNull(execution.applied)
        assertNull(execution.changed)

        val observation = observationOf(result)
        assertTrue(observation.screen!!.contains("#n1"))
        assertEquals(ToolVision.Ok, observation.vision)
        assertEquals(1, result.images.size)
        assertEquals("the timed-out wait still refreshes once for its observation", 1, perception.refreshCalls)
        assertEquals(1, perception.screenshotCalls)
    }

    @Test
    fun `an act cancellation is reported as cancelled and never claimed applied`() {
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        operations.actEnvelope = failure("act", DeviceErrorCodes.CANCELLED, recoverable = true)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(),
            afterActionSnapshot = perceptionSnapshot(snapshotId = "snap-cancelled-act"),
        )
        perception.screenshotResult = screenshotImage("img-cancelled-act")

        val result = tool(operations, perception = perception)
            .execute("""{"action":"act","kind":"home"}""")

        val execution = executionOf(result)
        assertEquals(ToolOutcome.Cancelled, execution.outcome)
        assertEquals(DeviceErrorCodes.CANCELLED, execution.code)
        assertFalse("a cancellation is its own outcome, not an uncertain one", execution.uncertain)
        assertEquals("the kernel's accepted=false is copied verbatim", false, execution.accepted)
        assertNull(execution.applied)
        assertNull(execution.changed)
        assertEquals("the post-action observation still rides along", "snap-cancelled-act", observationOf(result).snapshotId)
        assertEquals(1, result.images.size)
    }

    @Test
    fun `status success reports a text observation with vision not requested and never captures`() {
        // Review minor: status success reports no screen text, so it must not inherit the snapshot's screen truncation.
        val perception = FakePerception(
            snapshot = perceptionSnapshot(truncated = true, screenCoverage = "truncated"),
        )
        perception.screenshotResult = screenshotImage("img-must-not-be-used")

        val result = tool(
            FakeDeviceOperations(observation()),
            perception = perception,
            deviceState = FakeDeviceState(deviceState(batteryPercent = 76)),
        ).execute("""{"action":"status"}""")

        val execution = executionOf(result)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertNull(execution.applied)
        assertNull(execution.changed)

        val observation = observationOf(result)
        assertEquals("snap-7", observation.snapshotId)
        assertEquals("com.example.app", observation.foregroundPackage)
        assertTrue(observation.coverage!!.contains("device=ok"))
        assertNull("status is not a screen read; the screen tree stays out of its observation", observation.screen)
        assertFalse("a null screen cannot claim the snapshot's truncation", observation.truncated)
        assertEquals(ToolVision.NotRequested, observation.vision)
        assertNull(observation.visionReason)
        assertTrue(result.images.isEmpty())
        assertEquals(0, perception.screenshotCalls)
    }

    @Test
    fun `status failure reports execution facts and a best-effort observation with one image`() {
        val operations = FakeDeviceOperations(observation())
        operations.statusEnvelope = failure("status", "ACTION_CHANNEL_UNAVAILABLE", recoverable = true)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(elements = listOf(perceptionElement(ref = "#n1", text = "A"))),
        )
        perception.screenshotResult = screenshotImage("img-status-failure")

        val result = tool(operations, perception = perception).execute("""{"action":"status"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", execution.code)
        assertEquals(false, execution.accepted)
        assertNull(execution.applied)
        assertNull(execution.changed)

        val observation = observationOf(result)
        assertTrue("a failed status is answered with the current screen", observation.screen!!.contains("#n1"))
        assertEquals(ToolVision.Ok, observation.vision)
        assertEquals("a failure observation is a real read", 1, perception.refreshCalls)
        assertEquals(1, result.images.size)
        assertEquals(1, perception.screenshotCalls)
    }

    @Test
    fun `wait success reports a text observation with vision not requested and never captures`() {
        val perception = FakePerception(
            snapshot = perceptionSnapshot(elements = listOf(perceptionElement(ref = "#n1", text = "A"))),
        )
        perception.screenshotResult = screenshotImage("img-must-not-be-used")

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"wait","idle":true}""")

        val execution = executionOf(result)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertNull(execution.applied)
        assertNull(execution.changed)

        val observation = observationOf(result)
        assertEquals("snap-7", observation.snapshotId)
        assertTrue(observation.screen!!.contains("#n1"))
        assertEquals(ToolVision.NotRequested, observation.vision)
        assertNull(observation.visionReason)
        assertTrue(result.images.isEmpty())
        assertEquals(0, perception.screenshotCalls)
    }

    @Test
    fun `wait failure reports execution facts and a best-effort observation with one image`() {
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        operations.waitEnvelope = failure("wait", "VERIFY_TIMEOUT", recoverable = true)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(elements = listOf(perceptionElement(ref = "#n1", text = "A"))),
        )
        perception.screenshotResult = screenshotImage("img-wait-failure")

        val result = tool(operations, perception = perception)
            .execute("""{"action":"wait","text":"never"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("VERIFY_TIMEOUT", execution.code)
        assertEquals(false, execution.accepted)
        assertNull(execution.applied)

        val observation = observationOf(result)
        assertEquals(ToolVision.Ok, observation.vision)
        assertEquals(1, result.images.size)
        assertEquals(1, perception.screenshotCalls)
    }

    @Test
    fun `a wait whose refresh throws falls back to the cached projection and still captures on failure`() {
        // Review important 2: refresh() may throw even though the provider contract says it never does. The observation
        // must fall back to the cache projection instead of vanishing, and a failed wait still takes its picture.
        val operations = FakeDeviceOperations(observation())
        operations.waitEnvelope = failure("wait", "VERIFY_TIMEOUT", recoverable = true)
        val perception = FakePerception(
            snapshot = perceptionSnapshot(
                snapshotId = "snap-cached-wait",
                elements = listOf(perceptionElement(ref = "#n1", text = "A")),
            ),
        )
        perception.refreshThrows = true
        perception.screenshotResult = screenshotImage("img-wait-refresh-throw")

        val result = tool(operations, perception = perception)
            .execute("""{"action":"wait","text":"never"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("the real read was attempted and threw", 1, perception.refreshCalls)
        assertEquals("the observation falls back to the cached projection", 1, perception.snapshotCalls)
        val observation = observationOf(result)
        assertEquals("snap-cached-wait", observation.snapshotId)
        assertTrue(observation.screen!!.contains("#n1"))
        assertEquals(ToolVision.Ok, observation.vision)
        assertEquals("a failed wait still takes its best-effort picture", 1, result.images.size)
        assertEquals(1, perception.screenshotCalls)
    }

    @Test
    fun `a failed wait whose projection throws still reports its attached picture as vision ok`() {
        // Review important: `vision` describes the picture channel, so an observation that fell back because the
        // provider threw must not deny a picture the same result really attached. Both the refresh and the cache read
        // throw here; the best-effort screenshot still succeeds, so the result is one image plus `vision=ok` with no
        // reason. The mandated `unavailable(provider_threw)` stays for the no-picture case.
        val operations = FakeDeviceOperations(observation())
        operations.waitEnvelope = failure("wait", "VERIFY_TIMEOUT", recoverable = true)
        val perception = FakePerception(perceptionSnapshot())
        perception.refreshThrows = true
        perception.snapshotThrows = true
        perception.screenshotResult = screenshotImage("img-wait-broken-projection")

        val result = tool(operations, perception = perception)
            .execute("""{"action":"wait","text":"never"}""")

        assertEquals("both the real read and the cache projection threw", 1, perception.refreshCalls)
        assertEquals("the failed wait still attaches its best-effort picture", 1, result.images.size)
        assertEquals(1, perception.screenshotCalls)
        val observation = observationOf(result)
        assertEquals("the attached usable image is the picture channel fact", ToolVision.Ok, observation.vision)
        assertNull("a usable image needs no unavailable reason", observation.visionReason)
        assertNull("nothing was read, so no screen may be claimed", observation.screen)
        assertFalse("a screen that was not read cannot be truncated", observation.truncated)
    }

    @Test
    fun `invalid arguments carry execution facts and a text observation without an image`() {
        val perception = FakePerception(
            snapshot = perceptionSnapshot(elements = listOf(perceptionElement(ref = "#n1", text = "A"))),
        )
        perception.screenshotResult = screenshotImage("img-must-not-be-used")

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"act","kind":"teleport"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("INVALID_ARGUMENTS", executionOf(result).code)
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("INVALID_ARGUMENTS", execution.code)
        assertEquals(false, execution.recoverable)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertNull(execution.verified)
        assertNull(execution.changed)

        val observation = observationOf(result)
        assertEquals("the current screen is still described", "snap-7", observation.snapshotId)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("not_attempted", observation.visionReason)
        assertTrue("an argument refusal never pictures the screen", result.images.isEmpty())
        assertEquals(0, perception.refreshCalls)
        assertEquals(0, perception.afterActions)
        assertEquals(0, perception.screenshotCalls)
        assertTrue("the observation text may not be persisted", result.nonPersistent)
    }

    @Test
    fun `screenshot success carries an observation and its own image without a second capture`() {
        val operations = FakeDeviceOperations(observation())
        operations.screenshotEnvelope = DeviceEnvelope.success(
            action = "screenshot",
            channel = "accessibility",
            accepted = true,
            applied = null,
            verified = null,
            timing = Timing(0, 0, 0),
            images = listOf(ImageRef("artifact-shot", "image/jpeg", 768, 1664, "sha-shot")),
        )
        val perception = FakePerception(
            snapshot = perceptionSnapshot(elements = listOf(perceptionElement(ref = "#n1", text = "A"))),
        )
        perception.screenshotResult = screenshotImage("img-must-not-be-used")

        val result = tool(operations, perception = perception).execute("""{"action":"screenshot"}""")

        val execution = executionOf(result)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertNull(execution.applied)
        assertNull(execution.changed)

        val observation = observationOf(result)
        assertTrue(observation.screen!!.contains("#n1"))
        assertEquals(ToolVision.Ok, observation.vision)
        assertNull(observation.visionReason)
        assertEquals(1, result.images.size)
        assertEquals("the explicit screenshot is the only capture", 0, perception.screenshotCalls)
    }

    @Test
    fun `a screenshot whose cached projection throws falls back to a real read`() {
        // Review important 2: a throwing cache projection used to leave observation=null even though the tool had a
        // provider and the screenshot had its own image. One real read answers the screen facts instead.
        val operations = FakeDeviceOperations(observation())
        operations.screenshotEnvelope = DeviceEnvelope.success(
            action = "screenshot",
            channel = "accessibility",
            accepted = true,
            applied = null,
            verified = null,
            timing = Timing(0, 0, 0),
            images = listOf(ImageRef("artifact-shot", "image/jpeg", 768, 1664, "sha-shot")),
        )
        val perception = FakePerception(
            snapshot = perceptionSnapshot(
                snapshotId = "snap-refreshed-shot",
                elements = listOf(perceptionElement(ref = "#n9", text = "B")),
            ),
        )
        perception.snapshotThrows = true

        val result = tool(operations, perception = perception).execute("""{"action":"screenshot"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("the cache projection threw", 1, perception.snapshotCalls)
        assertEquals("the observation is rebuilt from one real read", 1, perception.refreshCalls)
        val observation = observationOf(result)
        assertEquals("snap-refreshed-shot", observation.snapshotId)
        assertTrue(observation.screen!!.contains("#n9"))
        assertEquals("the screenshot's own resolvable image is still vision ok", ToolVision.Ok, observation.vision)
        assertEquals(1, result.images.size)
        assertEquals("no second capture on the fallback", 0, perception.screenshotCalls)
    }

    @Test
    fun `a pending screenshot artifact is not claimed as a usable image`() {
        // The kernel reports a failed artifact store as a successful screenshot with the `pending` placeholder and an
        // empty sha; an unresolvable id must read as unavailable, exactly like `PerceptionScreenshotMapping` maps it.
        val operations = FakeDeviceOperations(observation())
        operations.screenshotEnvelope = DeviceEnvelope.success(
            action = "screenshot",
            channel = "accessibility",
            accepted = true,
            applied = null,
            verified = null,
            timing = Timing(0, 0, 0),
            images = listOf(ImageRef("pending", "image/jpeg", 768, 1664, "")),
            data = "diagnostic=screenshot_artifact_pending; reason=NO_STORE",
        )
        val perception = FakePerception(snapshot = perceptionSnapshot())

        val result = tool(operations, perception = perception).execute("""{"action":"screenshot"}""")

        assertEquals("the kernel capture still succeeded", ActionOutcome.Done, result.outcome)
        val observation = observationOf(result)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("SCREENSHOT_PENDING", observation.visionReason)
        assertEquals("the action's own reference is still the result", 1, result.images.size)
        assertEquals(0, perception.screenshotCalls)
    }

    @Test
    fun `a failed screenshot carries vision unavailable with the kernel reason and no image`() {
        val operations = FakeDeviceOperations(observation())
        operations.screenshotEnvelope = failure("screenshot", "UNSUPPORTED", recoverable = false)
        val perception = FakePerception(snapshot = perceptionSnapshot())
        perception.screenshotResult = screenshotImage("img-must-not-be-used")

        val result = tool(operations, perception = perception).execute("""{"action":"screenshot"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        val execution = executionOf(result)
        assertEquals(ToolOutcome.Failed, execution.outcome)
        assertEquals("UNSUPPORTED", execution.code)
        assertEquals(false, execution.accepted)
        assertNull(execution.applied)
        assertNull(execution.changed)

        val observation = observationOf(result)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("UNSUPPORTED", observation.visionReason)
        assertTrue("a failed screenshot never fabricates an image", result.images.isEmpty())
        assertEquals("the failure does not try a second capture", 0, perception.screenshotCalls)
    }

    // - M2/M3: explicit observation degradation and data.device de-duplication -

    /*
     * M2: a provider that is present but broken must answer an explicit observation instead of a silent `null`.
     * "No observation" means "this composition has no perception port"; a provider that threw is a different fact and
     * must say `unavailable(provider_threw)`. M3: `data.device` no longer repeats `foreground=`, which the observation
     * already carries as `foregroundPackage`/`foregroundActivity`.
     */

    @Test
    fun `a throwing projection answers an explicit provider_threw observation instead of null`() {
        // `refused()` is the path that projects the provider's cache itself (`observeForResult` with no snapshot), so a
        // throwing `snapshot()` used to return null and hide the broken port behind "no observation".
        val perception = FakePerception(perceptionSnapshot())
        perception.snapshotThrows = true

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"act","kind":"teleport"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("INVALID_ARGUMENTS", executionOf(result).code)
        val observation = observationOf(result)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("provider_threw", observation.visionReason)
        assertNull("a projection that threw has no screen text", observation.screen)
        assertFalse("nothing readable can be truncated", observation.truncated)
        assertTrue("an explicit observation is still perception content", result.nonPersistent)
    }

    @Test
    fun `a done result with a broken cache and refresh keeps provider_threw instead of a silent null`() {
        // A `Done` device result must not silently drop its observation when a provider is still wired.
        val operations = FakeDeviceOperations(observation())
        val perception = FakePerception(perceptionSnapshot())
        perception.refreshThrows = true
        perception.snapshotThrows = true

        val result = tool(operations, perception = perception)
            .execute("""{"action":"wait","idle":true}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val observation = observationOf(result)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("provider_threw", observation.visionReason)
        assertNull(observation.screen)
        assertFalse(observation.truncated)
    }

    @Test
    fun `an unavailable screen coverage is not rendered as an empty screen`() {
        // The snapshot-wide renderer would otherwise turn an unavailable read (no elements) into `screen=""`, which reads
        // like a genuinely empty screen. The coverage token is the honest answer and the screen stays absent.
        val perception = FakePerception(
            perceptionSnapshot(
                elements = listOf(perceptionElement(ref = "#n1", text = "A")),
                screenCoverage = "unavailable(no_accessibility)",
            ),
        )

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"observe"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val observation = observationOf(result)
        assertNull("an unavailable screen read has no screen text", observation.screen)
        assertFalse("a screen that was not read cannot be truncated", observation.truncated)
        assertTrue(
            "the coverage line must still name the unavailable source: ${observation.coverage}",
            observation.coverage!!.contains("screen=unavailable(no_accessibility)"),
        )
    }

    @Test
    fun `a non-screen observe never reports a rendered screen`() {
        val perception = FakePerception(
            perceptionSnapshot(elements = listOf(perceptionElement(ref = "#n1", text = "A"))),
        )

        val result = tool(FakeDeviceOperations(observation()), perception = perception)
            .execute("""{"action":"observe","include":["notifications"]}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val observation = observationOf(result)
        assertNull("a notification-only read has no screen fact", observation.screen)
        assertFalse(observation.truncated)
        assertTrue("the foreground facts still ride the observation", observation.foregroundPackage == "com.example.app")
    }

    @Test
    fun `data device omits the duplicated foreground and the observation keeps it`() {
        val state = deviceState(batteryPercent = 76).copy(
            foregroundPackage = "com.example.app",
            foregroundActivity = ".MainActivity",
        )
        val perception = FakePerception(perceptionSnapshot())

        val result = tool(
            FakeDeviceOperations(observation()),
            perception = perception,
            deviceState = FakeDeviceState(state),
        ).execute("""{"action":"status"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val deviceLine = body(result)["device"]!!.jsonPrimitive.content
        assertTrue("the device line keeps the real state facts: $deviceLine", deviceLine.contains("battery=76%"))
        assertFalse(
            "foreground is the observation's fact; data.device must not repeat it: $deviceLine",
            deviceLine.contains("foreground="),
        )
        val observation = observationOf(result)
        assertEquals("com.example.app", observation.foregroundPackage)
        assertEquals(".MainActivity", observation.foregroundActivity)
    }

    @Test
    fun `runtimeState keeps its own foreground line because it is not data`() {
        val operations = FakeDeviceOperations(
            observation(rev = 9, windows = listOf(window(packageName = "com.android.settings"))),
        )

        assertEquals("foreground=com.android.settings window=0 revision=9", tool(operations).runtimeState())
    }

    // - helpers -

    private fun body(result: ActionResult): JsonObject =
        json.parseToJsonElement(result.detail).jsonObject

    /**
     * The legacy (no perception port) projection's screen object. A perception composition has no `data.screen` at all
     * (Task 8): its tree is `observation.screen`, and tests that way assert the observation directly.
     */
    private fun JsonObject.screen(): JsonObject = this["screen"]!!.jsonObject

    private fun success(action: String, data: String? = null): DeviceEnvelope = DeviceEnvelope.success(
        action = action,
        channel = "accessibility",
        accepted = true,
        applied = null,
        verified = null,
        timing = Timing(0, 0, 0),
        data = data,
    )

    // - Task 12 (R57/R59): observation fingerprint and runtime state -

    @Test
    fun `observe and act carry a content fingerprint that ignores the revision`() {
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        operations.revisionValue = 1L
        val tool = tool(operations)

        val first = tool.execute("""{"action":"observe"}""")
        // The same screen observed again: the revision advanced, the content did not.
        operations.observation = observation(rev = 2, nodes = listOf(node("A")))
        val second = tool.execute("""{"action":"observe"}""")

        assertNotNull(first.fingerprint)
        assertEquals("revision is not screen content", first.fingerprint, second.fingerprint)

        // The kernel's post-action observation is a different screen, so the fingerprint changes.
        operations.observation = observation(rev = 3, nodes = listOf(node("B")))
        val act = tool.execute("""{"action":"act","kind":"tap","ref":"#n1"}""")

        assertNotNull(act.fingerprint)
        assertNotEquals(first.fingerprint, act.fingerprint)
    }

    @Test
    fun `a failed observe returns no fingerprint when the revision did not advance`() {
        // The kernel's `latestObservation` is still the pre-call screen: the failed read observed nothing, so the
        // tool must not report the previous screen as this call's post-state (poll/global would see a false "no change").
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        operations.revisionValue = 1L
        operations.advanceRevisionOnObserve = false
        operations.observeEnvelope = failure("observe", "CHANNEL_UNAVAILABLE", recoverable = true)

        val result = tool(operations).execute("""{"action":"observe"}""")

        assertNull("a call that observed nothing must not report the previous screen", result.fingerprint)
    }

    @Test
    fun `an early rejected act returns no fingerprint`() {
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        operations.revisionValue = 1L
        operations.advanceRevisionOnAct = false
        operations.actEnvelope = failure("act", "DEVICE_LOCKED", recoverable = true)

        val result = tool(operations).execute("""{"action":"act","kind":"tap","ref":"#n1"}""")

        assertNull("a rejected act never observed its effect", result.fingerprint)
    }

    @Test
    fun `a failed act that still observed reports the current fingerprint`() {
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        operations.revisionValue = 1L
        // The act was refused, yet the kernel did observe afterwards: the revision advanced and the new screen is
        // exactly the state the failed call left behind.
        operations.actEnvelope = failure("act", "VERIFY_TIMEOUT", recoverable = true)
        operations.observation = observation(rev = 2, nodes = listOf(node("B")))

        val result = tool(operations).execute("""{"action":"act","kind":"tap","ref":"#n1"}""")

        assertNotNull(result.fingerprint)
        val reread = tool(operations).execute("""{"action":"observe"}""")
        assertEquals("the fingerprint names the screen the call observed", result.fingerprint, reread.fingerprint)
    }

    @Test
    fun `a device call without a screen carries no fingerprint`() {
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        // A write whose result could not be observed: the kernel reports a diagnostic line instead of a snapshot.
        operations.actEnvelope = DeviceEnvelope.success(
            action = "act",
            channel = "accessibility",
            accepted = true,
            applied = null,
            verified = null,
            timing = Timing(0, 0, 0),
            data = "no observation",
        )

        val result = tool(operations).execute("""{"action":"act","kind":"tap","ref":"#n1"}""")

        assertNull("a diagnostic is not an observation and must not be fingerprinted", result.fingerprint)
    }

    @Test
    fun `act is state changing while the read-only device actions are not`() {
        // R98: `device` declares observation=true for all its calls, so the descriptor cannot tell a write from a read.
        // The per-call result does: only `act` (including scroll_to_text) feeds the loop's ProgressGuard.
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        val tool = tool(operations)

        assertFalse(tool.execute("""{"action":"observe"}""").stateChanging)
        assertFalse(tool.execute("""{"action":"status"}""").stateChanging)
        assertFalse(tool.execute("""{"action":"screenshot"}""").stateChanging)
        assertFalse(tool.execute("""{"action":"wait","idle":true}""").stateChanging)
        assertTrue(tool.execute("""{"action":"act","kind":"tap","ref":"#n1"}""").stateChanging)
        assertTrue(tool.execute("""{"action":"act","kind":"scroll_to_text","text":"A"}""").stateChanging)
    }

    @Test
    fun `wait status and screenshot carry no observation fingerprint`() {
        val operations = FakeDeviceOperations(observation(rev = 1, nodes = listOf(node("A"))))
        val tool = tool(operations)

        assertNull(tool.execute("""{"action":"status"}""").fingerprint)
        assertNull(tool.execute("""{"action":"screenshot"}""").fingerprint)
        assertNull(tool.execute("""{"action":"wait","idle":true}""").fingerprint)
    }

    @Test
    fun `runtimeState is one bounded line of kernel state`() {
        val operations = FakeDeviceOperations(
            observation(rev = 9, windows = listOf(window(packageName = "com.android.settings"))),
        )
        operations.revisionValue = 9L
        operations.generationValue = 4L

        val state = tool(operations).runtimeState()

        assertEquals("foreground=com.android.settings window=0 revision=9 generation=4", state)
        assertTrue("the state line must fit TurnRuntimeState.MAX_CHARS", state!!.length <= 300)
    }

    @Test
    fun `runtimeState is null before the first observation`() {
        assertNull(tool(FakeDeviceOperations()).runtimeState())
    }

    // - Task 7 perception helpers -

    private fun perceptionSnapshot(
        snapshotId: String = "snap-7",
        elements: List<PerceptionElement> = emptyList(),
        notifications: List<NotificationItem> = emptyList(),
        media: MediaState? = null,
        calendarTitles: List<String> = emptyList(),
        acoustic: String? = null,
        clipboardText: String? = null,
        truncated: Boolean = false,
        screenCoverage: String = "ok",
        notificationsCoverage: String = "ok",
    ): PerceptionSnapshot = PerceptionSnapshot(
        snapshotId = snapshotId,
        capturedAtEpochMillis = 0L,
        foregroundPackage = "com.example.app",
        foregroundActivity = ".MainActivity",
        elements = elements,
        notifications = notifications,
        media = media,
        calendarTitles = calendarTitles,
        acoustic = acoustic,
        clipboardText = clipboardText,
        coverage = PerceptionCoverage(
            screen = screenCoverage,
            notifications = notificationsCoverage,
            media = "ok",
            calendar = "ok",
            acoustic = "ok",
            clipboard = "ok",
            vision = "ok",
            glasses = "ok",
            device = "ok",
        ),
        truncated = truncated,
    )

    private fun perceptionElement(
        ref: String,
        role: String = "text",
        text: String? = null,
        centerX: Int? = 0,
        centerY: Int? = 0,
        clickable: Boolean = false,
        editable: Boolean = false,
        bounds: String? = null,
    ): PerceptionElement = PerceptionElement(
        ref = ref,
        role = role,
        text = text,
        centerX = centerX,
        centerY = centerY,
        bounds = bounds,
        clickable = clickable,
        editable = editable,
    )

    private fun deviceState(
        batteryPercent: Int? = null,
        charging: Boolean? = null,
        network: String? = null,
        panelVisible: Boolean? = null,
        glassesConnected: Boolean? = null,
        glassesWearing: Boolean? = null,
    ): DeviceState = DeviceState(
        capturedAtEpochMillis = 0L,
        timeZone = "Asia/Shanghai",
        localDate = "2026-10-20",
        weekday = "周二",
        localTime = "18:23",
        batteryPercent = batteryPercent,
        charging = charging,
        network = network,
        panelVisible = panelVisible,
        glassesConnected = glassesConnected,
        glassesWearing = glassesWearing,
    )

    private class FakeDeviceState(private val current: DeviceState) : DeviceStateProvider {
        override fun profile(): DeviceProfile = DeviceProfile(
            manufacturer = "Xiaomi",
            model = "K40",
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
            wakeWords = emptyList(),
            capabilities = emptySet(),
        )

        override fun state(): DeviceState = current
    }

    private class FakePerception(
        var snapshot: PerceptionSnapshot,
        var afterActionSnapshot: PerceptionSnapshot? = null,
        /**
         * What a real `refresh()` would observe; when set the fake also updates its cache, exactly like
         * `AndroidPerceptionProvider.refresh()` (the cached projection follows the fresh read).
         */
        var refreshedSnapshot: PerceptionSnapshot? = null,
        /** Shared with [FakeDeviceOperations] when a test asserts capture ordering; private otherwise. */
        private val events: EventLog = EventLog(),
    ) : PerceptionProvider {
        var afterActions = 0
        var refreshCalls = 0
        var snapshotCalls = 0
        var screenshotCalls = 0
        var screenshotResult: PerceptionScreenshotResult = PerceptionScreenshotResult.Disabled
        var screenshotThrows = false
        /** A broken cache projection: `snapshot()` throws, exactly like a misbehaving provider. */
        var snapshotThrows = false
        /** A broken real read: `refresh()` throws instead of answering. */
        var refreshThrows = false
        var lastInclude: Set<PerceptionSource>? = null
        var lastFilter: PerceptionFilter? = null
        var lastLimit: Int? = null

        override fun beginTurn(): PerceptionSnapshot = snapshot

        override fun refresh(): PerceptionSnapshot {
            events.record(EVENT_REFRESH)
            refreshCalls++
            if (refreshThrows) error("refresh port died")
            refreshedSnapshot?.let { snapshot = it }
            return snapshot
        }

        override fun snapshot(
            include: Set<PerceptionSource>,
            filter: PerceptionFilter,
            limit: Int?,
        ): PerceptionSnapshot {
            events.record(EVENT_SNAPSHOT)
            snapshotCalls++
            if (snapshotThrows) error("snapshot port died")
            lastInclude = include
            lastFilter = filter
            lastLimit = limit
            return snapshot
        }

        override fun afterAction(): PerceptionSnapshot {
            events.record(EVENT_AFTER_ACTION)
            afterActions++
            return afterActionSnapshot ?: snapshot
        }

        override fun screenshot(): PerceptionScreenshotResult {
            events.record(EVENT_CAPTURE)
            screenshotCalls++
            if (screenshotThrows) error("capture port died")
            return screenshotResult
        }

        override fun endTurn() = Unit
    }

    /** The accessibility port seam, scripted with one screenshot and no observation; the surface the real kernel reads. */
    private class ScreenshotPort(private val shot: Screenshot) : AccessibilityPort {
        override fun isBound() = true
        override fun isInteractive() = true
        override fun isKeyguardLocked() = false
        override fun observe(filter: ScreenFilter) = Observation(0, emptyList(), emptyList(), false)
        override fun performNodeAction(ref: Ref, action: String) = false
        override fun dispatchGesture(gesture: Gesture) = false
        override fun screenshot(): Screenshot? = shot
        override fun foreground() = Foreground("com.example.app", null)
    }

    private fun failure(action: String, code: String, recoverable: Boolean): DeviceEnvelope =
        DeviceEnvelope.failure(
            action = action,
            channel = "accessibility",
            error = DeviceError(code, "scripted $code", recoverable),
            timing = Timing(0, 0, 0),
        )

    private fun observation(
        rev: Long = 1,
        nodes: List<DeviceNode> = listOf(node("Wi-Fi")),
        windows: List<ObservationWindow> = listOf(window()),
        truncated: Boolean = false,
    ): Observation = Observation(rev, windows, nodes, truncated)

    private fun node(
        text: String?,
        viewId: String? = "id/${text.orEmpty()}",
        depth: Int = 0,
        windowId: Int = 0,
    ): DeviceNode = DeviceNode(
        role = "button",
        text = text,
        contentDescription = null,
        viewId = viewId,
        bounds = Bounds(0, 0, 100, 50),
        states = setOf("clickable"),
        actions = listOf("click"),
        hint = null,
        paneTitle = null,
        windowId = windowId,
        depth = depth,
    )

    private fun window(
        id: Int = 0,
        type: String = "application",
        packageName: String? = "com.example.settings",
    ): ObservationWindow = ObservationWindow(
        id = id,
        type = type,
        layer = 0,
        bounds = Bounds(0, 0, 1080, 2400),
        packageName = packageName,
        title = null,
    )

    private data class ActCall(
        val kind: ActKind,
        val ref: Ref?,
        val text: String?,
        val value: String?,
        val direction: String?,
        val key: String?,
        val timeoutMillis: Long,
    )

    private data class WaitCall(
        val text: String?,
        val foreground: String?,
        val idle: Boolean,
        val timeoutMillis: Long,
    )

    private class FakeDeviceOperations(
        var observation: Observation? = null,
        /** Shared with [FakePerception] when a test asserts capture ordering; private otherwise. */
        private val events: EventLog = EventLog(),
    ) : DeviceOperations {
        val calls = mutableListOf<String>()
        val actCalls = mutableListOf<ActCall>()
        val waitCalls = mutableListOf<WaitCall>()
        var observeFilter: ScreenFilter? = null
        /** When set, `observe` leaves this observation as the kernel's new latest (the gate's refresh read). */
        var observeObservation: Observation? = null
        /** When set, `act` leaves this observation as the kernel's new latest (the post-action read). */
        var actObservation: Observation? = null
        var observeEnvelope: DeviceEnvelope = DeviceEnvelope.success(
            "observe", "accessibility", true, null, null, Timing(0, 0, 0),
        )
        var actEnvelope: DeviceEnvelope = DeviceEnvelope.success(
            "act", "accessibility", true, null, null, Timing(0, 0, 0),
        )
        var waitEnvelope: DeviceEnvelope = DeviceEnvelope.success(
            "wait", "accessibility", true, null, null, Timing(0, 0, 0),
        )
        var screenshotEnvelope: DeviceEnvelope = DeviceEnvelope.success(
            "screenshot", "accessibility", true, null, null, Timing(0, 0, 0),
        )
        var statusEnvelope: DeviceEnvelope = DeviceEnvelope.success(
            "status", "accessibility", true, null, null, Timing(0, 0, 0),
        )
        val waitEnvelopes = ArrayDeque<DeviceEnvelope>()
        var onWait: (() -> Unit)? = null
        var begins = 0
        var screenshotCalls = 0

        override val latestObservation: Observation?
            get() = observation

        var revisionValue: Long? = null
        var generationValue: Long? = null
        // A successful observe/act really advances the kernel's observation revision; a failed one answers before
        // observing. Tests that script a failed call flip the matching switch off.
        var advanceRevisionOnObserve = true
        var advanceRevisionOnAct = true

        override val revision: Long?
            get() = revisionValue

        override val generation: Long?
            get() = generationValue

        override fun observe(filter: ScreenFilter): DeviceEnvelope {
            events.record(EVENT_KERNEL_OBSERVE)
            observeFilter = filter
            calls += "observe:${filter.name}"
            if (advanceRevisionOnObserve) revisionValue = revisionValue?.plus(1)
            observeObservation?.let { observation = it }
            return observeEnvelope
        }

        override fun act(
            kind: ActKind,
            ref: Ref?,
            text: String?,
            value: String?,
            direction: String?,
            key: String?,
            timeoutMillis: Long,
        ): DeviceEnvelope {
            events.record(EVENT_KERNEL_ACT)
            calls += "act:${kind.name}"
            actCalls += ActCall(kind, ref, text, value, direction, key, timeoutMillis)
            if (advanceRevisionOnAct) revisionValue = revisionValue?.plus(1)
            actObservation?.let { observation = it }
            return actEnvelope
        }

        override fun screenshot(): DeviceEnvelope {
            events.record(EVENT_KERNEL_SCREENSHOT)
            screenshotCalls++
            return screenshotEnvelope
        }

        override fun waitFor(
            text: String?,
            foreground: String?,
            idle: Boolean,
            timeoutMillis: Long,
        ): DeviceEnvelope {
            events.record(EVENT_KERNEL_WAIT)
            calls += "wait:${text.orEmpty()}"
            waitCalls += WaitCall(text, foreground, idle, timeoutMillis)
            onWait?.invoke()
            return waitEnvelopes.removeFirstOrNull() ?: waitEnvelope
        }

        override fun status(): DeviceEnvelope {
            events.record(EVENT_KERNEL_STATUS)
            return statusEnvelope
        }

        override fun cancel() = Unit

        override fun beginTurn() { begins++ }

        override fun close() = Unit
    }
}
