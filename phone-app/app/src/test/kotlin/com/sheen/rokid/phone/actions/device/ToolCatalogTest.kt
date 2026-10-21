package com.sheen.rokid.phone.actions.device

import com.sheen.rokid.agent.ToolRegistry
import com.sheen.rokid.device.ActKind
import com.sheen.rokid.device.Bounds
import com.sheen.rokid.device.DeviceEnvelope
import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.ObservationRenderer
import com.sheen.rokid.device.ObservationWindow
import com.sheen.rokid.device.Ref
import com.sheen.rokid.device.RefRegistry
import com.sheen.rokid.device.ScreenFilter
import com.sheen.rokid.device.Timing
import com.sheen.rokid.phone.actions.intent.AppCatalog
import com.sheen.rokid.phone.actions.intent.AppEntry
import com.sheen.rokid.phone.actions.shizuku.PrivilegedShell
import com.sheen.rokid.phone.actions.shizuku.ShellResult
import com.sheen.rokid.phone.plugin.smarthelper.output.NotifyUserTool
import com.sheen.rokid.phone.plugin.smarthelper.skill.LoadSkillTool
import com.sheen.rokid.phone.plugin.smarthelper.skill.SkillLoad
import com.sheen.rokid.phone.plugin.smarthelper.skill.SkillLoadReason
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PrecheckDecision
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolObservation
import com.sheen.rokid.plugin.ToolOutcome
import com.sheen.rokid.plugin.ToolVision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R40/R47: the production `Capability.Actions` provider is exactly the four host tools, gated live,
 * with no fallback to the retired 21.
 */
class ToolCatalogTest {

    private val json = Json

    @Test
    fun `all channels up advertises exactly device shell open dial`() {
        val catalog = catalog()

        val names = catalog.descriptors().map { it.name }

        assertEquals(listOf("device", "shell", "open", "dial"), names)
        val retired = setOf(
            "read_screen", "tap", "long_press", "scroll", "input_text", "open_app", "open_url", "open_settings",
            "set_alarm", "compose_note", "launch_app", "list_apps", "system_action", "clear_data", "uninstall",
            "disable_app", "set_permission", "set_brightness",
        )
        assertTrue("the old tool surface must not survive: ${retired.intersect(names.toSet())}",
            retired.intersect(names.toSet()).isEmpty())
    }

    @Test
    fun `the plugins two tools make the model-visible surface exactly six`() {
        val registry = ToolRegistry(
            listOf(
                catalog(),
                NotifyUserTool { _, _ -> },
                LoadSkillTool(
                    lookup = { SkillLoad.NotFound(SkillLoadReason.NOT_IN_LIBRARY) },
                    onLoaded = { },
                ),
            ),
        )

        assertEquals(
            setOf("device", "shell", "open", "dial", "notify_user", "load_skill"),
            registry.descriptors().map { it.name }.toSet(),
        )
    }

    @Test
    fun `an unavailable a11y channel keeps device advertised for status while shell and call still gate`() {
        val probes = Probes(a11y = false)

        // Task 7 review I2 (R11): `status` is the call that reports `accessibility=false`, so hiding the whole tool
        // would hide the diagnosis behind the condition. The device tool stays advertised; every non-status action is
        // still refused at execution time by the a11y check below.
        val a11yDown = catalog(probes).descriptors().map { it.name }
        assertTrue("device must stay advertised while a11y is down", a11yDown.contains("device"))
        assertEquals(listOf("device", "shell", "open", "dial"), a11yDown)

        probes.a11y = true
        probes.shell = false
        val shellDown = catalog(probes).descriptors().map { it.name }
        assertFalse(shellDown.contains("shell"))
        assertFalse(shellDown.contains("open"))
        assertEquals(listOf("device", "dial"), shellDown)

        probes.shell = true
        probes.call = false
        val callDown = catalog(probes).descriptors().map { it.name }
        assertFalse(callDown.contains("dial"))
        assertEquals(listOf("device", "shell", "open"), callDown)
    }

    @Test
    fun `descriptors re-probe their channels on every read`() {
        val probes = Probes()
        val catalog = catalog(probes)
        assertTrue(catalog.descriptors().any { it.name == "shell" })

        probes.shell = false
        assertFalse(catalog.descriptors().any { it.name == "shell" })

        probes.shell = true
        assertTrue(catalog.descriptors().any { it.name == "shell" })
    }

    @Test
    fun `execute re-checks the channel and refuses instead of delegating`() {
        val probes = Probes(a11y = false, shell = false, call = false)
        val operations = RecordingDeviceOperations()
        val executor = RecordingExecutor()
        val catalog = catalog(
            probes = probes,
            deviceOperations = operations,
            dialExecutor = executor,
        )

        assertEquals("CHANNEL_UNAVAILABLE", catalog.execute("device", "{}").detail)
        assertEquals("CHANNEL_UNAVAILABLE", catalog.execute("shell", "{}").detail)
        assertEquals("CHANNEL_UNAVAILABLE", catalog.execute("open", "{}").detail)
        assertEquals("CALL_PERMISSION_MISSING", catalog.execute("dial", "{}").detail)
        assertTrue("no device operation may run while the channel is down", operations.observeEnvelopes.isEmpty())
        assertTrue("the dial executor must not be reached", executor.calls.isEmpty())
    }

    @Test
    fun `an unknown name is UNKNOWN_ACTION`() {
        assertEquals("UNKNOWN_ACTION", catalog().execute("teleport", "{}").detail)
    }

    @Test
    fun `the device schema never advertises channel or coordinate parameters`() {
        val device = descriptor("device")
        val schema = json.parseToJsonElement(device.parametersJson).jsonObject
        val properties = schema["properties"]!!.jsonObject

        assertFalse("D84 forbids a via parameter", device.parametersJson.contains("\"via\""))
        assertFalse("D84 forbids point/coordinate parameters", device.parametersJson.contains("\"point\""))
        assertFalse(device.parametersJson.contains("region"))
        assertFalse(device.parametersJson.contains("annotate"))
        assertEquals(
            listOf("observe", "act", "screenshot", "wait", "status"),
            schema["properties"]!!.jsonObject["action"]!!.jsonObject["enum"]!!.jsonArray
                .map { it.jsonPrimitive.content },
        )
        listOf("filter", "kind", "ref", "text", "value", "direction", "key", "timeout_ms", "include", "limit")
            .forEach { parameter ->
                assertTrue("device schema must carry $parameter", properties.containsKey(parameter))
            }
    }

    @Test
    fun `the shell open and dial schemas advertise their documented parameters`() {
        val shell = json.parseToJsonElement(descriptor("shell").parametersJson).jsonObject
        assertEquals(listOf("command"), shell["required"]!!.jsonArray.map { it.jsonPrimitive.content })
        listOf("command", "timeout_ms", "max_bytes", "stdin").forEach {
            assertTrue("shell schema must carry $it", shell["properties"]!!.jsonObject.containsKey(it))
        }

        val open = json.parseToJsonElement(descriptor("open").parametersJson).jsonObject["properties"]!!.jsonObject
        listOf("target", "list", "relaunch").forEach {
            assertTrue("open schema must carry $it", open.containsKey(it))
        }

        val dial = json.parseToJsonElement(descriptor("dial").parametersJson).jsonObject
        assertEquals(listOf("number"), dial["required"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `dial is L1 without confirmation and never audits the number`() {
        val dial = descriptor("dial")

        // R129: the user ruled that dial never asks, so the tool is L1 and the default L1 ceiling allows it outright.
        assertEquals(com.sheen.rokid.plugin.RiskLevel.L1, dial.risk)
        assertFalse(dial.requiresConfirmation)
        assertEquals(null, dial.auditTargetArgument)
    }

    @Test
    fun `cancel beginTurn and close reach the kernel`() {
        val operations = RecordingDeviceOperations()
        val catalog = catalog(deviceOperations = operations)

        catalog.cancel()
        catalog.beginTurn()
        catalog.close()

        assertEquals(1, operations.cancels)
        assertEquals(1, operations.begins)
        assertEquals(1, operations.closes)
    }

    @Test
    fun `precheck classifies only shell and leaves every other tool without a question`() {
        val catalog = catalog()

        assertEquals(PrecheckDecision.RequireConfirmation,
            catalog.precheck("shell", """{"command":"pm clear com.x"}""").decision)
        assertEquals(PrecheckDecision.Allow,
            catalog.precheck("shell", """{"command":"dumpsys window"}""").decision)
        assertEquals(PrecheckDecision.Reject,
            catalog.precheck("shell", """{"command":"ls\u0000x"}""").decision)

        listOf("device", "open", "dial", "notify_user", "load_skill", "teleport").forEach { name ->
            assertEquals("$name is not command-classified", PrecheckDecision.Allow,
                catalog.precheck(name, "{}").decision)
        }
    }

    @Test
    fun `only device declares observation and its runtime state is forwarded`() {
        val operations = RecordingDeviceOperations(
            observation = Observation(
                7,
                listOf(ObservationWindow(0, "application", 0, Bounds(0, 0, 10, 10), "com.example.settings", null)),
                emptyList(),
                false,
            ),
            revision = 7L,
            generation = 3L,
        )
        val catalog = catalog(deviceOperations = operations)

        val byName = catalog.descriptors().associateBy { it.name }
        assertTrue(byName.getValue("device").observation)
        listOf("shell", "open", "dial").forEach { name ->
            assertFalse("$name must stay outside the observation seam", byName.getValue(name).observation)
        }
        assertEquals("foreground=com.example.settings window=0 revision=7 generation=3", catalog.runtimeState())
    }

    @Test
    fun `runtime state is absent while the device channel is unavailable`() {
        val operations = RecordingDeviceOperations(
            observation = Observation(1, emptyList(), emptyList(), false),
            revision = 1L,
            generation = 0L,
        )
        val catalog = catalog(probes = Probes(a11y = false), deviceOperations = operations)

        assertEquals(null, catalog.runtimeState())
    }

    @Test
    fun `the shell call declares a human wait allowance and keeps its machine cap`() {
        val descriptors = catalog().descriptors().associateBy { it.name }

        // The plugin's ConfirmingActions asks the wearer inside the same execute call; D47's decision window is 120 s.
        // R99: the descriptor keeps the ordinary machine cap and declares the human wait separately, so the loop adds
        // the allowance. Inflating timeoutMillis instead would be clamped by the turn deadline and cut a legal answer.
        assertTrue(descriptors.getValue("shell").mayAskHuman)
        assertEquals(30_000L, descriptors.getValue("shell").timeoutMillis)
        // The other tools have no human wait inside execute and must not claim the allowance.
        listOf("device", "open", "dial").forEach { name ->
            assertFalse("$name has no human wait", descriptors.getValue(name).mayAskHuman)
            assertEquals("$name keeps the ordinary cap", 30_000L, descriptors.getValue(name).timeoutMillis)
        }
    }

    @Test
    fun `device open and dial are gated by unlock but shell is not`() {
        val calls = mutableListOf<Boolean>()
        val gate = com.sheen.rokid.phone.lock.UnlockGate { requireAccessibility ->
            calls += requireAccessibility
            com.sheen.rokid.phone.lock.UnlockOutcome.FAILED
        }
        val catalog = catalog(unlock = gate)

        assertEquals(ActionOutcome.Refused, catalog.execute("device", "{}").outcome)
        assertEquals(ActionOutcome.Refused, catalog.execute("open", "{}").outcome)
        assertEquals(ActionOutcome.Refused, catalog.execute("dial", "{}").outcome)
        assertEquals("device needs accessibility; open/dial do not", listOf(true, false, false), calls)

        calls.clear()
        catalog.execute("shell", "{}")
        assertTrue("the raw shell is never blocked by the lock-screen gate", calls.isEmpty())
    }

    @Test
    fun `execute emits a privacy safe arg shape before delegating`() {
        val lines = mutableListOf<String>()
        val previous = com.sheen.rokid.phone.platform.P3Log.sink
        com.sheen.rokid.phone.platform.P3Log.sink = { lines += it }
        try {
            catalog().execute("shell", """{"command":"id hunter2"}""")
        } finally {
            com.sheen.rokid.phone.platform.P3Log.sink = previous
        }

        assertTrue("M1 must log one shape line: $lines", lines.any {
            it == "P3_TOOLARG tool=shell commandClass=identity chars=10 hasStdin=false timeoutMs=30000"
        })
        assertFalse("the command must never reach the log", lines.any { it.contains("hunter2") })
    }

    @Test
    fun `device unlock gate runs before the a11y probe so a locked phone can wake it`() {
        val calls = mutableListOf<Boolean>()
        val gate = com.sheen.rokid.phone.lock.UnlockGate { requireAccessibility ->
            calls += requireAccessibility
            com.sheen.rokid.phone.lock.UnlockOutcome.FAILED
        }
        val catalog = catalog(probes = Probes(a11y = false), unlock = gate)

        val result = catalog.execute("device", "{}")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("the gate must run even while a11y is down", listOf(true), calls)
    }

    // - Task 7: status is exempt from the unlock and accessibility gates -

    @Test
    fun `device status bypasses the unlock gate and the accessibility refusal`() {
        val calls = mutableListOf<Boolean>()
        val gate = com.sheen.rokid.phone.lock.UnlockGate { requireAccessibility ->
            calls += requireAccessibility
            com.sheen.rokid.phone.lock.UnlockOutcome.FAILED
        }
        val operations = RecordingDeviceOperations(
            statusEnvelope = DeviceEnvelope.success(
                action = "status",
                channel = "accessibility",
                accepted = true,
                applied = null,
                verified = null,
                timing = Timing(0, 0, 0),
                data = "foreground=?/? interactive=false locked=true accessibility=false shell=false",
            ),
        )
        val catalog = catalog(probes = Probes(a11y = false), deviceOperations = operations, unlock = gate)

        val result = catalog.execute("device", """{"action":"status"}""")

        assertEquals("status must be able to report a locked phone with a11y off", ActionOutcome.Done, result.outcome)
        assertTrue("status must not ask for an unlock", calls.isEmpty())
        assertEquals(1, operations.statusCalls)
        assertTrue(
            "the diagnostic line must survive",
            result.detail.contains("locked=true accessibility=false"),
        )
    }

    @Test
    fun `every other device action still passes the unlock gate and the a11y check`() {
        val calls = mutableListOf<Boolean>()
        val gate = com.sheen.rokid.phone.lock.UnlockGate { requireAccessibility ->
            calls += requireAccessibility
            com.sheen.rokid.phone.lock.UnlockOutcome.FAILED
        }
        val catalog = catalog(probes = Probes(a11y = false), unlock = gate)

        assertEquals(ActionOutcome.Refused, catalog.execute("device", """{"action":"observe"}""").outcome)
        assertEquals(ActionOutcome.Refused, catalog.execute("device", """{"action":"act","kind":"tap"}""").outcome)
        assertEquals("observe and act ask for an unlock with accessibility", listOf(true, true), calls)

        calls.clear()
        val unlocked = catalog(
            probes = Probes(a11y = false),
            unlock = com.sheen.rokid.phone.lock.UnlockGate {
                calls += it
                com.sheen.rokid.phone.lock.UnlockOutcome.ALREADY_UNLOCKED
            },
        )
        assertEquals(
            "an unlocked but a11y-down device still refuses observe",
            "CHANNEL_UNAVAILABLE",
            unlocked.execute("device", """{"action":"observe"}""").detail,
        )
        assertEquals(listOf(true), calls)
    }

    @Test
    fun `a blind phone advertises device and status reports accessibility false through the real kernel`() {
        // Review I2 end to end: the production composition builds its accessibility port with a null service (the JVM
        // test has no bound service). Before the fix `status` asked that port for the foreground, got the port's
        // IllegalStateException, and the kernel turned it into CHANNEL_UNAVAILABLE - so the one call that exists to
        // report `accessibility=false` could not run on the phone it describes.
        val root = com.sheen.rokid.phone.app.HostCompositionRoot.create(android.content.ContextWrapper(null))
        try {
            val catalog = requireNotNull(root.toolCatalogForProbe) {
                "the production composition must expose its catalog"
            }
            assertTrue(
                "device must be in the model-visible surface on a blind phone",
                catalog.descriptors().any { it.name == "device" },
            )

            val result = catalog.execute("device", """{"action":"status"}""")

            assertEquals(
                "status must complete on a phone with no accessibility service",
                ActionOutcome.Done,
                result.outcome,
            )
            val data = json.parseToJsonElement(result.detail).jsonObject["data"]!!.jsonObject
            val status = data["status"]!!.jsonPrimitive.content
            assertTrue("the diagnostic must report the blind channel: $status", status.contains("accessibility=false"))
            assertFalse(
                "status must not be answered with a channel failure: ${result.detail}",
                result.detail.contains("CHANNEL_UNAVAILABLE"),
            )
        } finally {
            root.close()
        }
    }

    // - edith-0.3 Phase 2.2 Task 6: shell/dial post-call observation -

    @Test
    fun `shell and dial declare screen observation while keeping the fingerprint seam off`() {
        val descriptors = catalog().descriptors().associateBy { it.name }

        listOf("device", "shell", "open", "dial").forEach { name ->
            assertTrue("$name must declare a post-call observation", descriptors.getValue(name).screenObservation)
        }
        // Task 3's flag means "the host has a screen to observe after the call"; the fingerprint seam (`observation`)
        // stays device-only so shell/dial do not start feeding the progress detectors.
        listOf("shell", "open", "dial").forEach { name ->
            assertFalse("$name must stay outside the fingerprint seam", descriptors.getValue(name).observation)
        }
    }

    @Test
    fun `a successful shell call carries the observation one image and done facts`() {
        var captureCalls = 0
        val catalog = catalog(
            shell = ShellTool(FakeShell { command -> ShellResult(command, 0, "hi\n", "") }),
            observe = { screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("shell", """{"command":"echo hi"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("one best-effort picture", 1, captureCalls)
        val observation = requireNotNull(result.observation) { "shell success must carry an observation" }
        assertEquals("com.example.launcher", observation.foregroundPackage)
        assertEquals("#n1 button \"OK\"", observation.screen)
        assertEquals(ToolVision.Ok, observation.vision)
        assertNull("a picture needs no unavailable reason", observation.visionReason)
        assertEquals(listOf(IMAGE), result.images)
        val execution = requireNotNull(result.execution) { "shell must carry structured execution facts" }
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertNull("an exit code is not a target-state verdict, so applied stays unknown", execution.applied)
        assertNull("a raw command is not verified against a target", execution.verified)
        assertNull(execution.changed)
        assertTrue("observation and picture content stay request-scoped", result.nonPersistent)
    }

    @Test
    fun `a shell command that exits non zero reports failed facts with the screen and one image`() {
        val catalog = catalog(
            shell = ShellTool(FakeShell { command -> ShellResult(command, 2, "", "bad thing") }),
            observe = { screenFacts() },
            capture = { imageResult() },
        )

        val result = catalog.execute("shell", """{"command":"false"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals(1, result.images.size)
        assertEquals(ToolVision.Ok, requireNotNull(result.observation).vision)
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Failed, execution.outcome)
        assertEquals("the command ran", true, execution.accepted)
        assertNull("a non-zero exit does not prove the side effects did not happen", execution.applied)
        assertNull(execution.verified)
        assertNull(execution.changed)
        assertTrue(result.nonPersistent)
    }

    @Test
    fun `a shell reply that was cut is refused but known to have run`() {
        val catalog = catalog(
            shell = ShellTool(FakeShell { command -> ShellResult(command, 0, "prefix", "", truncated = true) }),
            observe = { screenFacts() },
            capture = { PerceptionScreenshotResult.Disabled },
        )

        val result = catalog.execute("shell", """{"command":"dumpsys window"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue("the tool's own code is kept: ${result.detail}", result.detail.contains("SHELL_REPLY_TOO_LARGE"))
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("SHELL_REPLY_TOO_LARGE", execution.code)
        assertEquals("a cut reply proves the command ran", true, execution.accepted)
        assertNull("only the answer was cut; applying is unknown", execution.applied)
        assertEquals(true, execution.recoverable)
    }

    @Test
    fun `a shell channel refusal still observes the screen and captures one image`() {
        var captureCalls = 0
        val catalog = catalog(
            probes = Probes(shell = false),
            observe = { screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("shell", """{"command":"id"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals(ToolCatalog.CHANNEL_UNAVAILABLE, result.detail)
        assertEquals(1, captureCalls)
        assertEquals(ToolVision.Ok, requireNotNull(result.observation).vision)
        assertEquals(1, result.images.size)
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals(ToolCatalog.CHANNEL_UNAVAILABLE, execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertEquals("the channel can come back", true, execution.recoverable)
        assertTrue(result.nonPersistent)
    }

    @Test
    fun `shell invalid arguments observe as text only and never capture`() {
        var captureCalls = 0
        val catalog = catalog(
            shell = ShellTool(FakeShell { command -> ShellResult(command, 0, "", "") }),
            observe = { screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("shell", """{"command":""}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("the bare argument code is unchanged", "INVALID_ARGUMENTS", result.detail)
        assertEquals("an argument refusal takes no picture", 0, captureCalls)
        val observation = requireNotNull(result.observation) { "a refusal still observes the current screen" }
        assertEquals("#n1 button \"OK\"", observation.screen)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("not_attempted", observation.visionReason)
        assertTrue(result.images.isEmpty())
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("INVALID_ARGUMENTS", execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertEquals("a malformed call has no recoverable channel", false, execution.recoverable)
        assertTrue("observation text stays request-scoped", result.nonPersistent)
    }

    @Test
    fun `the unsupported stdin refusal is an argument refusal with a text-only observation`() {
        var captureCalls = 0
        val catalog = catalog(
            shell = ShellTool(FakeShell { command -> ShellResult(command, 0, "", "") }),
            observe = { screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("shell", """{"command":"id","stdin":"x"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue("the tool envelope is unchanged: ${result.detail}", result.detail.contains("UNSUPPORTED"))
        assertEquals(0, captureCalls)
        assertEquals("not_attempted", requireNotNull(result.observation).visionReason)
        val execution = requireNotNull(result.execution)
        assertEquals("UNSUPPORTED", execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertEquals(false, execution.recoverable)
    }

    @Test
    fun `a successful dial carries the observation one image and accepted only facts`() {
        val executor = RecordingExecutor()
        var captureCalls = 0
        val catalog = catalog(
            dialExecutor = executor,
            observe = { screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("dial", """{"number":"10086"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(listOf("dial" to """{"number":"10086"}"""), executor.calls)
        assertEquals(1, captureCalls)
        assertEquals(ToolVision.Ok, requireNotNull(result.observation).vision)
        assertEquals(1, result.images.size)
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertNull("an intent hand-off is not verified as applied", execution.applied)
        assertNull(execution.verified)
        assertNull(execution.changed)
        assertTrue(result.nonPersistent)
    }

    @Test
    fun `a failed dial reports failed facts with the screen and one image`() {
        val executor = RecordingExecutor().apply {
            outcome = ActionResult(ActionOutcome.Failed, "boom")
        }
        val catalog = catalog(
            dialExecutor = executor,
            observe = { screenFacts() },
            capture = { imageResult() },
        )

        val result = catalog.execute("dial", """{"number":"10086"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertEquals(1, result.images.size)
        assertEquals(ToolVision.Ok, requireNotNull(result.observation).vision)
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Failed, execution.outcome)
        assertEquals("DIAL_FAILED", execution.code)
        assertEquals("the valid call reached the executor", true, execution.accepted)
        assertEquals("the intent did not launch", false, execution.applied)
        assertNull(execution.verified)
        assertNull(execution.changed)
        assertTrue(result.nonPersistent)
    }

    @Test
    fun `a dial permission refusal observes the screen and captures one image`() {
        val executor = RecordingExecutor()
        var captureCalls = 0
        val catalog = catalog(
            probes = Probes(call = false),
            dialExecutor = executor,
            observe = { screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("dial", """{"number":"10086"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CALL_PERMISSION_MISSING", result.detail)
        assertTrue("the executor must not be reached", executor.calls.isEmpty())
        assertEquals(1, captureCalls)
        assertEquals(ToolVision.Ok, requireNotNull(result.observation).vision)
        assertEquals(1, result.images.size)
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("CALL_PERMISSION_MISSING", execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertEquals(true, execution.recoverable)
    }

    @Test
    fun `dial invalid arguments observe as text only and never capture`() {
        val executor = RecordingExecutor()
        var captureCalls = 0
        val catalog = catalog(
            dialExecutor = executor,
            observe = { screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("dial", """{"number":"   "}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue("the tool envelope is unchanged: ${result.detail}", result.detail.contains("ARGUMENTS_INVALID"))
        assertTrue("an undialable number never reaches the executor", executor.calls.isEmpty())
        assertEquals(0, captureCalls)
        assertEquals("not_attempted", requireNotNull(result.observation).visionReason)
        assertTrue(result.images.isEmpty())
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("ARGUMENTS_INVALID", execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertEquals(false, execution.recoverable)
    }

    @Test
    fun `shell and dial without an observation source report unavailable no source`() {
        val catalog = catalog(
            shell = ShellTool(FakeShell { command -> ShellResult(command, 0, "", "") }),
        )

        val shell = catalog.execute("shell", """{"command":"id"}""")
        val dial = catalog.execute("dial", """{"number":"10086"}""")

        listOf("shell" to shell, "dial" to dial).forEach { (name, result) ->
            val observation = requireNotNull(result.observation) { "$name must never silently drop its observation" }
            assertEquals("$name reports the missing source", ToolVision.Unavailable, observation.vision)
            assertEquals("no_source", observation.visionReason)
            assertTrue("$name attaches no picture without a source", result.images.isEmpty())
            assertTrue(result.nonPersistent)
        }
    }

    @Test
    fun `a disabled capture keeps the source facts with an honest disabled vision`() {
        val catalog = catalog(
            probes = Probes(call = false),
            observe = { screenFacts().copy(vision = ToolVision.Ok) },
            capture = { PerceptionScreenshotResult.Disabled },
        )

        val result = catalog.execute("dial", """{"number":"10086"}""")

        val observation = requireNotNull(result.observation)
        assertEquals("com.example.launcher", observation.foregroundPackage)
        assertEquals("a source may not claim ok without an image", ToolVision.Disabled, observation.vision)
        assertNull(observation.visionReason)
        assertTrue(result.images.isEmpty())
    }

    @Test
    fun `an unavailable capture reports its own vision reason`() {
        val catalog = catalog(
            probes = Probes(call = false),
            observe = { screenFacts() },
            capture = { PerceptionScreenshotResult.Unavailable("SCREENSHOT_FAILED") },
        )

        val result = catalog.execute("dial", """{"number":"10086"}""")

        val observation = requireNotNull(result.observation)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("SCREENSHOT_FAILED", observation.visionReason)
        assertTrue(result.images.isEmpty())
    }

    @Test
    fun `a throwing observation or capture source never fails the shell result`() {
        val catalog = catalog(
            shell = ShellTool(FakeShell { command -> ShellResult(command, 0, "", "") }),
            observe = { throw IllegalStateException("observer gone") },
            capture = { throw IllegalStateException("capture gone") },
        )

        val result = catalog.execute("shell", """{"command":"id"}""")

        assertEquals("the command's own outcome is untouched", ActionOutcome.Done, result.outcome)
        val observation = requireNotNull(result.observation)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("no_source", observation.visionReason)
        assertTrue(result.images.isEmpty())
        assertTrue(result.nonPersistent)
    }

    @Test
    fun `an unknown action keeps its bare refusal without an observation or a picture`() {
        var captureCalls = 0
        var observeCalls = 0
        val catalog = catalog(
            observe = { observeCalls++; screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("teleport", "{}")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals(ToolCatalog.UNKNOWN_ACTION, result.detail)
        assertNull("an unknown name is not a screen operation", result.observation)
        assertTrue(result.images.isEmpty())
        assertEquals("no observation for an unknown name", 0, observeCalls)
        assertEquals("no capture for an unknown name", 0, captureCalls)
    }

    @Test
    fun `open keeps its own observation policy and is never enriched by the catalog`() {
        var captureCalls = 0
        var observeCalls = 0
        val catalog = catalog(
            observe = { observeCalls++; screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        // `list` through the shell-less fixture refuses at the channel; it is Task 5's non-screen path, not ours.
        val result = catalog.execute("open", """{"list":true}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue("the tool's own channel refusal is kept: ${result.detail}",
            result.detail.contains("CHANNEL_UNAVAILABLE"))
        assertNull("the catalog must not add an observation to open", result.observation)
        assertTrue(result.images.isEmpty())
        assertEquals(0, observeCalls)
        assertEquals(0, captureCalls)
    }

    @Test
    fun `the production composition backs open and shell with the perception observation source`() {
        val root = com.sheen.rokid.phone.app.HostCompositionRoot.create(android.content.ContextWrapper(null))
        try {
            // a11y is down on the JVM composition: the source must answer an explicit unavailable observation, never a
            // fabricated screen and never a silent null.
            val open = requireNotNull(root.openToolForProbe) {
                "the production composition must expose its open tool for the wiring check"
            }
            val openResult = open.execute("""{"target":"com.example.absent"}""")
            val openObservation = requireNotNull(openResult.observation) {
                "open must carry the host observation source's answer"
            }
            assertEquals(ToolVision.Unavailable, openObservation.vision)
            assertTrue("open must say why vision is unavailable", !openObservation.visionReason.isNullOrBlank())
            assertTrue("a blind composition attaches no image", openResult.images.isEmpty())

            val shellResult = requireNotNull(root.toolCatalogForProbe).execute("shell", """{"command":"id"}""")
            val shellObservation = requireNotNull(shellResult.observation) {
                "shell must carry the host observation source's answer"
            }
            assertEquals(ToolVision.Unavailable, shellObservation.vision)
            assertEquals("no_accessibility", shellObservation.visionReason)
            assertNull("a blind phone has no fabricated screen", shellObservation.screen)
            assertTrue("a blind composition attaches no image", shellResult.images.isEmpty())
        } finally {
            root.close()
        }
    }

    // - helpers -

    private fun catalog(
        probes: Probes = Probes(),
        deviceOperations: RecordingDeviceOperations = RecordingDeviceOperations(),
        dialExecutor: RecordingExecutor = RecordingExecutor(),
        unlock: com.sheen.rokid.phone.lock.UnlockGate =
            com.sheen.rokid.phone.lock.UnlockGate { com.sheen.rokid.phone.lock.UnlockOutcome.ALREADY_UNLOCKED },
        shell: ShellTool = ShellTool(null),
        observe: () -> ToolObservation? = { null },
        capture: () -> PerceptionScreenshotResult = { PerceptionScreenshotResult.Disabled },
    ): ToolCatalog {
        val registry = RefRegistry()
        val device = DeviceTool(
            deviceOperations,
            ScreenProjector(ObservationRenderer(registry), registry),
        )
        return ToolCatalog(
            device = device,
            shell = shell,
            open = OpenTool(null, EmptyCatalog),
            dial = DialTool(dialExecutor) { probes.call },
            a11yAvailable = { probes.a11y },
            shellAvailable = { probes.shell },
            callAvailable = { probes.call },
            unlock = unlock,
            observe = observe,
            capture = capture,
        )
    }

    /** The post-call facts a source would answer on a healthy phone. */
    private fun screenFacts(): ToolObservation = ToolObservation(
        snapshotId = "snap-1",
        capturedAtEpochMillis = 1_000L,
        foregroundPackage = "com.example.launcher",
        foregroundActivity = ".Home",
        coverage = "screen=ok vision=ok",
        screen = "#n1 button \"OK\"",
        vision = ToolVision.NotRequested,
    )

    private fun imageResult(): PerceptionScreenshotResult.Image =
        PerceptionScreenshotResult.Image(IMAGE, "image/png", 100, 50)

    private class FakeShell(private val answer: (String) -> ShellResult) : PrivilegedShell {
        override fun run(command: String): ShellResult = answer(command)
    }

    private companion object {
        val IMAGE = ImageRef("artifact-1", "image/png", 100, 50, "sha-1")
    }

    private fun descriptor(name: String): com.sheen.rokid.plugin.ActionDescriptor =
        catalog().descriptors().single { it.name == name }

    private class Probes(
        var a11y: Boolean = true,
        var shell: Boolean = true,
        var call: Boolean = true,
    )

    private object EmptyCatalog : AppCatalog {
        override fun visibleApps(): List<AppEntry> = emptyList()
    }

    private class RecordingExecutor : com.sheen.rokid.plugin.ActionExecutor {
        val calls = mutableListOf<Pair<String, String>>()
        var outcome: ActionResult = ActionResult(ActionOutcome.Done, "done")

        override fun descriptors(): List<com.sheen.rokid.plugin.ActionDescriptor> = emptyList()

        override fun execute(name: String, argsJson: String): ActionResult {
            calls += name to argsJson
            return outcome
        }

        override fun cancel() = Unit
    }

    private class RecordingDeviceOperations(
        private val observation: Observation? = null,
        override val revision: Long? = null,
        override val generation: Long? = null,
        private val statusEnvelope: DeviceEnvelope =
            DeviceEnvelope.success("status", "accessibility", true, null, null, Timing(0, 0, 0)),
    ) : DeviceOperations {
        override val latestObservation: Observation?
            get() = observation
        val observeEnvelopes = mutableListOf<DeviceEnvelope>()
        var cancels = 0
        var begins = 0
        var closes = 0
        var statusCalls = 0

        override fun observe(filter: ScreenFilter): DeviceEnvelope {
            observeEnvelopes += DeviceEnvelope.success("observe", "accessibility", true, null, null, Timing(0, 0, 0))
            return observeEnvelopes.last()
        }

        override fun act(
            kind: ActKind,
            ref: Ref?,
            text: String?,
            value: String?,
            direction: String?,
            key: String?,
            timeoutMillis: Long,
        ): DeviceEnvelope = DeviceEnvelope.success("act", "accessibility", true, null, null, Timing(0, 0, 0))

        override fun screenshot(): DeviceEnvelope =
            DeviceEnvelope.success("screenshot", "accessibility", true, null, null, Timing(0, 0, 0))

        override fun waitFor(text: String?, foreground: String?, idle: Boolean, timeoutMillis: Long): DeviceEnvelope =
            DeviceEnvelope.success("wait", "accessibility", true, null, null, Timing(0, 0, 0))

        override fun status(): DeviceEnvelope {
            statusCalls++
            return statusEnvelope
        }

        override fun cancel() { cancels++ }

        override fun beginTurn() { begins++ }

        override fun close() { closes++ }
    }
}
