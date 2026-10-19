package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.device.AccessibilityPort
import com.sheen.rokid.device.ActKind
import com.sheen.rokid.device.Bounds
import com.sheen.rokid.device.DeviceEnvelope
import com.sheen.rokid.device.DeviceError
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
import com.sheen.rokid.plugin.ImageRef
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
    ): DeviceTool = DeviceTool(
        operations,
        ScreenProjector(ObservationRenderer(registry), registry),
        clock,
    )

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
        val body = body(result)
        assertEquals(true, body["ok"]!!.jsonPrimitive.boolean)
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

    // - act -

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
        val body = body(result)
        assertEquals("DEVICE_SCREEN_OFF", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertTrue(body["error"]!!.jsonObject["recoverable"]!!.jsonPrimitive.boolean)
        assertFalse(body["ok"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `an unsupported kind is refused with INVALID_ARGUMENTS and never reaches the kernel`() {
        val operations = FakeDeviceOperations(observation())

        val result = tool(operations).execute("""{"action":"act","kind":"teleport"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("INVALID_ARGUMENTS", result.detail)
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
        assertEquals("VERIFY_TIMEOUT", body(result)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
        assertEquals(1, operations.actCalls.size)
    }

    @Test
    fun `scroll_to_text needs a text target`() {
        val operations = FakeDeviceOperations(observation())

        val result = tool(operations).execute("""{"action":"act","kind":"scroll_to_text"}""")

        assertEquals("INVALID_ARGUMENTS", result.detail)
        assertTrue(operations.actCalls.isEmpty())
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
        val images = body(result)["images"]!!.jsonArray
        assertEquals(1, images.size)
        assertEquals("pending", images[0].jsonObject["artifactId"]!!.jsonPrimitive.content)
        assertEquals("image/jpeg", images[0].jsonObject["mime"]!!.jsonPrimitive.content)
        assertEquals(768, images[0].jsonObject["width"]!!.jsonPrimitive.int)
        assertFalse(result.detail.contains("base64", ignoreCase = true))
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
        val diagnostic = body(result)["data"]!!.jsonObject["diagnostic"]!!.jsonPrimitive.content
        assertTrue(
            "the kernel diagnostic must survive the projection, was `$diagnostic`",
            diagnostic.contains("screenshot_artifact_pending"),
        )
        assertTrue(
            "the store's reason must survive too: `$diagnostic`",
            diagnostic.contains("artifact store unavailable"),
        )
        assertEquals("pending", body(result)["images"]!!.jsonArray[0].jsonObject["artifactId"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a failed screenshot keeps the kernel error code as its diagnostic`() {
        val operations = FakeDeviceOperations(observation())
        operations.screenshotEnvelope = failure("screenshot", "UNSUPPORTED", recoverable = false)

        val result = tool(operations).execute("""{"action":"screenshot"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        val body = body(result)
        assertEquals(
            "a failed screenshot must name the kernel's error code, not a generic sentence",
            "UNSUPPORTED",
            body["data"]!!.jsonObject["diagnostic"]!!.jsonPrimitive.content,
        )
        assertEquals("UNSUPPORTED", body["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
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
                    body["data"]!!.jsonObject["diagnostic"]!!.jsonPrimitive.content,
                )
                assertEquals(
                    image.artifactId,
                    body["images"]!!.jsonArray.single().jsonObject["artifactId"]!!.jsonPrimitive.content,
                )
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
            val diagnostic = body(result)["data"]!!.jsonObject["diagnostic"]!!.jsonPrimitive.content
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
        assertEquals("INVALID_ARGUMENTS", result.detail)
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
        val data = body(result)["data"]!!.jsonObject
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
        val data = body(result)["data"]!!.jsonObject
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

        val data = body(result)["data"]!!.jsonObject
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
        assertEquals("UNSUPPORTED", body(result)["error"]!!.jsonObject["code"]!!.jsonPrimitive.content)
    }

    @Test
    fun `the device tool refuses an unknown name rather than guessing`() {
        val operations = FakeDeviceOperations(observation())

        assertEquals("UNKNOWN_ACTION", tool(operations).execute("shell", "{}").detail)
        assertTrue(operations.calls.isEmpty())
    }

    // - helpers -

    private fun body(result: ActionResult): JsonObject =
        json.parseToJsonElement(result.detail).jsonObject

    private fun JsonObject.screen(): JsonObject = this["data"]!!.jsonObject["screen"]!!.jsonObject

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
    ) : DeviceOperations {
        val calls = mutableListOf<String>()
        val actCalls = mutableListOf<ActCall>()
        val waitCalls = mutableListOf<WaitCall>()
        var observeFilter: ScreenFilter? = null
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
            observeFilter = filter
            calls += "observe:${filter.name}"
            if (advanceRevisionOnObserve) revisionValue = revisionValue?.plus(1)
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
            calls += "act:${kind.name}"
            actCalls += ActCall(kind, ref, text, value, direction, key, timeoutMillis)
            if (advanceRevisionOnAct) revisionValue = revisionValue?.plus(1)
            return actEnvelope
        }

        override fun screenshot(): DeviceEnvelope = screenshotEnvelope

        override fun waitFor(
            text: String?,
            foreground: String?,
            idle: Boolean,
            timeoutMillis: Long,
        ): DeviceEnvelope {
            calls += "wait:${text.orEmpty()}"
            waitCalls += WaitCall(text, foreground, idle, timeoutMillis)
            onWait?.invoke()
            return waitEnvelopes.removeFirstOrNull() ?: waitEnvelope
        }

        override fun status(): DeviceEnvelope = statusEnvelope

        override fun cancel() = Unit

        override fun beginTurn() { begins++ }

        override fun close() = Unit
    }
}
