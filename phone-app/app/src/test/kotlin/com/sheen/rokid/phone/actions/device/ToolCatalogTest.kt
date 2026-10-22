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
import com.sheen.rokid.plugin.PerceptionCoverage
import com.sheen.rokid.plugin.PerceptionElement
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionProvider
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PerceptionSnapshot
import com.sheen.rokid.plugin.PerceptionSource
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
 * R40/R47: the umbrella half of the production tool face is exactly the four host tools, with no fallback to the retired
 * 21-tool router. Since edith-0.3 Phase 4A Task 4 the one `Capability.Actions` provider is `ToolSurface`, which joins
 * this catalog with the 18 atomic tools (22 host descriptors in all); this suite still pins the catalog **alone**.
 *
 * edith-0.3 Phase 4A Task 3 (A3): advertising is **stable**. A channel that goes down no longer removes its tool - the
 * schema and the cached prompt prefix must not change under the model mid-session - so `descriptors()` always answers
 * the same four names and `execute` is where the channel refusal is produced.
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
        assertTrue(
            "the catalog must not re-advertise the atomic names (ToolSurface adds them beside it): " +
                "${retired.intersect(names.toSet())}",
            retired.intersect(names.toSet()).isEmpty(),
        )
    }

    @Test
    fun `a catalog-only registry answers the umbrella four plus the plugin's two`() {
        // The production face is 22 host descriptors plus these two (ToolFaceCompositionTest and
        // SmartToolFaceRegistryTest pin that). This test keeps the catalog-alone seam honest, not the production count.
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
    fun `the four host tools are advertised in every channel state and on every read`() {
        val probes = Probes()
        val catalog = catalog(probes)
        val expected = listOf("device", "shell", "open", "dial")

        fun assertAdvertised(state: String) {
            assertEquals(state, expected, catalog.descriptors().map { it.name })
        }

        assertAdvertised("all channels up")
        assertAdvertised("a second read of the same catalog")

        probes.a11y = false
        assertAdvertised("accessibility down")
        probes.a11y = true

        probes.shell = false
        assertAdvertised("Shizuku down")
        probes.shell = true

        probes.call = false
        assertAdvertised("call permission revoked")
        probes.call = true

        probes.a11y = false
        probes.shell = false
        probes.call = false
        assertAdvertised("every channel down")
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

        // Task 8: the stable code is an execution fact; `data` is the tool-specific payload only.
        assertEquals("CHANNEL_UNAVAILABLE", catalog.execute("device", "{}").execution?.code)
        assertEquals("CHANNEL_UNAVAILABLE", catalog.execute("shell", "{}").execution?.code)
        assertEquals("CHANNEL_UNAVAILABLE", catalog.execute("open", "{}").execution?.code)
        assertEquals("CALL_PERMISSION_MISSING", catalog.execute("dial", "{}").execution?.code)
        assertEquals("{}", catalog.execute("dial", "{}").detail)
        assertTrue("no device operation may run while the channel is down", operations.observeEnvelopes.isEmpty())
        assertTrue("the dial executor must not be reached", executor.calls.isEmpty())
    }

    @Test
    fun `an unknown name is UNKNOWN_ACTION`() {
        assertEquals("UNKNOWN_ACTION", catalog().execute("teleport", "{}").execution?.code)
        assertEquals("{}", catalog().execute("teleport", "{}").detail)
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
            com.sheen.rokid.phone.lock.UnlockOutcome.STILL_LOCKED
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
            com.sheen.rokid.phone.lock.UnlockOutcome.STILL_LOCKED
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
            com.sheen.rokid.phone.lock.UnlockOutcome.STILL_LOCKED
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
            com.sheen.rokid.phone.lock.UnlockOutcome.STILL_LOCKED
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
            unlocked.execute("device", """{"action":"observe"}""").execution?.code,
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
            val data = json.parseToJsonElement(result.detail).jsonObject
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
        assertTrue("the tool's own code is kept: ${result.detail}", result.execution?.code == "SHELL_REPLY_TOO_LARGE")
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
        assertEquals(ToolCatalog.CHANNEL_UNAVAILABLE, result.execution?.code)
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
    fun `the argument refusal classifier reads structured execution not the old data envelope`() {
        // Task 8 migration: the catalog used to parse `data.error.code`. These synthetic results carry the stable code
        // only in `execution` (their `data` is `{}`), so an old-shape reader would classify both as real attempts.
        var captureCalls = 0
        val catalog = catalog(
            observe = { screenFacts() },
            capture = { captureCalls++; imageResult() },
        )
        fun refusal(code: String) = ActionResult(
            ActionOutcome.Refused,
            "{}",
            execution = ToolExecution(
                outcome = ToolOutcome.Refused,
                code = code,
                recoverable = code != "ARGUMENTS_INVALID",
                accepted = false,
                applied = false,
            ),
        )

        val argument = catalog.enrichScreenResult("dial", refusal("ARGUMENTS_INVALID"))
        assertEquals("an argument refusal takes no picture", 0, captureCalls)
        assertEquals(ToolVision.Unavailable, requireNotNull(argument.observation).vision)
        assertEquals("not_attempted", argument.observation!!.visionReason)

        val realAttempt = catalog.enrichScreenResult("dial", refusal("CALL_PERMISSION_MISSING"))
        assertEquals("a real refusal still observes the screen it left", 1, captureCalls)
        assertEquals(ToolVision.Ok, requireNotNull(realAttempt.observation).vision)
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
        assertEquals("the bare argument code is unchanged", "INVALID_ARGUMENTS", result.execution?.code)
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
        assertTrue("the tool's own code is unchanged: ${result.execution}", result.execution?.code == "UNSUPPORTED")
        assertEquals(0, captureCalls)
        assertEquals("not_attempted", requireNotNull(result.observation).visionReason)
        val execution = requireNotNull(result.execution)
        assertEquals("UNSUPPORTED", execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertEquals(false, execution.recoverable)
    }

    // - P3-9: P3_OBSERVE source=tool timing -

    @Test
    fun `a real screen attempt emits one count only P3_OBSERVE tool line`() {
        val lines = mutableListOf<String>()
        val catalog = catalog(
            observe = { screenFacts() },
            capture = { imageResult() },
        )
        val previous = com.sheen.rokid.phone.platform.P3Log.sink
        com.sheen.rokid.phone.platform.P3Log.sink = { lines += it }
        try {
            val result = catalog.execute("dial", """{"number":"10086"}""")
            assertEquals(ActionOutcome.Done, result.outcome)
        } finally {
            com.sheen.rokid.phone.platform.P3Log.sink = previous
        }

        val observeLines = lines.filter { it.startsWith("P3_OBSERVE") }
        assertEquals(lines.toString(), 1, observeLines.size)
        assertTrue(
            "the exact count-only shape is required: ${observeLines.single()}",
            Regex("^P3_OBSERVE source=tool tool=dial observeMs=\\d+ captureMs=\\d+ result=ok$")
                .matches(observeLines.single()),
        )
        assertFalse("no screen text may reach the line: ${observeLines.single()}", observeLines.single().contains("OK"))
        assertFalse(
            "no package name may reach the line: ${observeLines.single()}",
            observeLines.single().contains("com.example.launcher"),
        )
    }

    @Test
    fun `a disabled and an unavailable capture report their own result token`() {
        fun lineFor(capture: () -> PerceptionScreenshotResult): String {
            val lines = mutableListOf<String>()
            val catalog = catalog(observe = { screenFacts() }, capture = capture)
            val previous = com.sheen.rokid.phone.platform.P3Log.sink
            com.sheen.rokid.phone.platform.P3Log.sink = { lines += it }
            try {
                catalog.execute("shell", """{"command":"id"}""")
            } finally {
                com.sheen.rokid.phone.platform.P3Log.sink = previous
            }
            return lines.single { it.startsWith("P3_OBSERVE") }
        }

        assertTrue(
            Regex("^P3_OBSERVE source=tool tool=shell observeMs=\\d+ captureMs=\\d+ result=disabled$")
                .matches(lineFor { PerceptionScreenshotResult.Disabled }),
        )
        assertTrue(
            Regex("^P3_OBSERVE source=tool tool=shell observeMs=\\d+ captureMs=\\d+ result=unavailable$")
                .matches(lineFor { PerceptionScreenshotResult.Unavailable("SCREENSHOT_FAILED") }),
        )
    }

    @Test
    fun `a text only observation emits no tool observe line because no capture was attempted`() {
        val lines = mutableListOf<String>()
        var captureCalls = 0
        val catalog = catalog(
            shell = ShellTool(FakeShell { command -> ShellResult(command, 0, "", "") }),
            observe = { screenFacts() },
            capture = { captureCalls++; imageResult() },
        )
        val previous = com.sheen.rokid.phone.platform.P3Log.sink
        com.sheen.rokid.phone.platform.P3Log.sink = { lines += it }
        try {
            val result = catalog.execute("shell", """{"command":""}""")
            assertEquals("INVALID_ARGUMENTS", result.execution?.code)
        } finally {
            com.sheen.rokid.phone.platform.P3Log.sink = previous
        }

        assertEquals(0, captureCalls)
        assertTrue(
            "an observation-only attempt has no capture timing to report: $lines",
            lines.none { it.startsWith("P3_OBSERVE") },
        )
    }

    @Test
    fun `the catalog does not double count the open tool's own observation`() {
        // `open` enriches itself (OpenTool), and the catalog delegates to it. Exactly one real capture happened, so
        // exactly one line may be emitted: the catalog must not wrap the delegation in a second measurement.
        val shell = FakeShell { command ->
            when {
                command.startsWith("cmd package resolve-activity") -> ShellResult(
                    command,
                    0,
                    "priority=0 preferredOrder=0\ncom.example.music/.MainActivity\n",
                    "",
                )
                command.startsWith("am start -W") -> ShellResult(command, 0, "Status: ok\n", "")
                else -> ShellResult(command, 0, "", "")
            }
        }
        val open = OpenTool(
            shell = shell,
            catalog = object : AppCatalog {
                override fun visibleApps(): List<AppEntry> =
                    listOf(AppEntry("com.example.music", "Music", "android.intent.action.MAIN"))
            },
            foreground = { "com.example.music" },
            foregroundActivity = { "com.example.music/.MainActivity" },
            rootPackage = { "com.example.music" },
            waitForForeground = { _, _ -> true },
            capture = { imageResult() },
            observe = { screenFacts() },
        )
        val registry = RefRegistry()
        val catalog = ToolCatalog(
            device = DeviceTool(
                RecordingDeviceOperations(),
                ScreenProjector(ObservationRenderer(registry), registry),
            ),
            shell = ShellTool(null),
            open = open,
            dial = DialTool(RecordingExecutor()) { true },
            a11yAvailable = { true },
            shellAvailable = { true },
            callAvailable = { true },
        )
        val lines = mutableListOf<String>()
        val previous = com.sheen.rokid.phone.platform.P3Log.sink
        com.sheen.rokid.phone.platform.P3Log.sink = { lines += it }
        try {
            val result = catalog.execute("open", """{"target":"com.example.music"}""")
            assertEquals(ActionOutcome.Done, result.outcome)
        } finally {
            com.sheen.rokid.phone.platform.P3Log.sink = previous
        }

        val observeLines = lines.filter { it.startsWith("P3_OBSERVE") }
        assertEquals("one real capture, one line: $lines", 1, observeLines.size)
        assertTrue("the line names the open tool: ${observeLines.single()}", observeLines.single().contains(" tool=open "))
        assertTrue("the line reports the capture: ${observeLines.single()}", observeLines.single().endsWith(" result=ok"))
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
        assertEquals("CALL_PERMISSION_MISSING", result.execution?.code)
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
    fun `a dial blocked by the lock gate is refused with the screen one image and refused facts`() {
        val gate = com.sheen.rokid.phone.lock.UnlockGate { com.sheen.rokid.phone.lock.UnlockOutcome.STILL_LOCKED }
        var captureCalls = 0
        val catalog = catalog(
            unlock = gate,
            observe = { screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("dial", """{"number":"10086"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue("the gate's own code must survive: ${result.execution}",
            result.execution?.code?.startsWith("LOCKED_UNLOCK_FAILED") == true)
        assertEquals("a locked phone still shows the model what is on screen", 1, captureCalls)
        assertEquals(ToolVision.Ok, requireNotNull(result.observation).vision)
        assertEquals(1, result.images.size)
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Refused, execution.outcome)
        assertEquals("LOCKED_UNLOCK_FAILED:STILL_LOCKED", execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertEquals("an unlock can succeed on the next attempt", true, execution.recoverable)
        assertNull(execution.verified)
        assertNull(execution.changed)
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
        assertTrue("the tool's stable code is unchanged: ${result.execution}", result.execution?.code == "ARGUMENTS_INVALID")
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
    fun `a throwing capture with a live source reports SCREENSHOT_FAILED and keeps the source facts`() {
        val catalog = catalog(
            shell = ShellTool(FakeShell { command -> ShellResult(command, 0, "", "") }),
            observe = { screenFacts() },
            capture = { throw IllegalStateException("capture gone") },
        )

        val result = catalog.execute("shell", """{"command":"id"}""")

        assertEquals("the command's own outcome is untouched", ActionOutcome.Done, result.outcome)
        val observation = requireNotNull(result.observation)
        assertEquals("com.example.launcher", observation.foregroundPackage)
        assertEquals("#n1 button \"OK\"", observation.screen)
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("a throwing capture is an unavailable reason, never a TOOL_THREW", "SCREENSHOT_FAILED",
            observation.visionReason)
        assertTrue(result.images.isEmpty())
        assertTrue(result.nonPersistent)
    }

    @Test
    fun `the final vision follows the final image list and at most one image is kept`() {
        // Future-proofing: today's shell/dial return no images of their own, but if one ever does, the catalog must not
        // pair its picture with the capture's `disabled` marker, and the result still carries at most one image. The
        // enrichment step is fed a synthetic result because the current tools have no image channel.
        val catalog = catalog(
            observe = { screenFacts() },
            capture = { PerceptionScreenshotResult.Disabled },
        )
        val synthetic = ActionResult(ActionOutcome.Done, "done", images = listOf(IMAGE, SECOND_IMAGE))

        val result = catalog.enrichScreenResult("dial", synthetic)

        assertEquals("one result, one image", listOf(IMAGE), result.images)
        val observation = requireNotNull(result.observation)
        assertEquals("a picture is really attached, so vision is ok", ToolVision.Ok, observation.vision)
        assertNull("an attached picture needs no unavailable reason", observation.visionReason)
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
        assertEquals(ToolCatalog.UNKNOWN_ACTION, result.execution?.code)
        assertNull("an unknown name is not a screen operation", result.observation)
        assertTrue(result.images.isEmpty())
        assertEquals("no observation for an unknown name", 0, observeCalls)
        assertEquals("no capture for an unknown name", 0, captureCalls)
    }

    // - M5: catalog-level device/open refusals carry an observation and one best-effort picture -

    /*
     * M5: the refusals the **catalog** constructs (a11y off, shell off, unlock refused) are the only ones the tool never
     * saw, so they are enriched exactly like `shell`/`dial`. Tool results - including a successful `device`/`open` that
     * already carries its own observation and image - are never re-enriched; `status` success stays text-only and
     * `open list`/an unknown action never take a picture.
     */

    @Test
    fun `a catalog accessibility refusal for device observes the screen with one image`() {
        var captureCalls = 0
        val catalog = catalog(
            probes = Probes(a11y = false),
            observe = { screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("device", """{"action":"observe"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals(ToolCatalog.CHANNEL_UNAVAILABLE, result.execution?.code)
        assertEquals("a real catalog refusal takes one best-effort picture", 1, captureCalls)
        val observation = requireNotNull(result.observation) {
            "a catalog-level device refusal must not silently drop its observation"
        }
        assertEquals("#n1 button \"OK\"", observation.screen)
        assertEquals(ToolVision.Ok, observation.vision)
        assertNull("an attached picture needs no unavailable reason", observation.visionReason)
        assertEquals(listOf(IMAGE), result.images)
        assertTrue("observation and picture content stay request-scoped", result.nonPersistent)
    }

    @Test
    fun `a catalog shell refusal for open observes the screen with one image`() {
        var captureCalls = 0
        val catalog = catalog(
            probes = Probes(shell = false),
            observe = { screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("open", """{"target":"com.example.absent"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals(ToolCatalog.CHANNEL_UNAVAILABLE, result.execution?.code)
        assertEquals(1, captureCalls)
        val observation = requireNotNull(result.observation) {
            "a catalog-level open refusal must not silently drop its observation"
        }
        assertEquals(ToolVision.Ok, observation.vision)
        assertEquals(1, result.images.size)
        assertTrue(result.nonPersistent)
    }

    @Test
    fun `a catalog unlock refusal for device and open observes the screen with one image`() {
        val gate = com.sheen.rokid.phone.lock.UnlockGate { com.sheen.rokid.phone.lock.UnlockOutcome.STILL_LOCKED }
        var captureCalls = 0
        val catalog = catalog(
            unlock = gate,
            observe = { screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        listOf("device" to """{"action":"observe"}""", "open" to """{"target":"com.example.absent"}""")
            .forEach { (name, args) ->
                val result = catalog.execute(name, args)
                assertEquals("$name keeps its own outcome", ActionOutcome.Refused, result.outcome)
                assertEquals(
                    "$name keeps the gate's own code",
                    "LOCKED_UNLOCK_FAILED:STILL_LOCKED",
                    result.execution?.code,
                )
                val observation = requireNotNull(result.observation) {
                    "$name's catalog refusal must not silently drop its observation"
                }
                assertEquals("$name observes the screen that refused it", "#n1 button \"OK\"", observation.screen)
                assertEquals(ToolVision.Ok, observation.vision)
                assertEquals("$name takes one best-effort picture", 1, result.images.size)
                assertTrue(result.nonPersistent)
            }
        assertEquals("one picture per catalog refusal", 2, captureCalls)
    }

    @Test
    fun `a catalog refusal with a disabled capture reports disabled without an image`() {
        val catalog = catalog(
            probes = Probes(a11y = false),
            observe = { screenFacts() },
            capture = { PerceptionScreenshotResult.Disabled },
        )

        val result = catalog.execute("device", """{"action":"observe"}""")

        val observation = requireNotNull(result.observation)
        assertEquals("the source facts survive with an honest disabled vision", ToolVision.Disabled, observation.vision)
        assertNull(observation.visionReason)
        assertEquals("com.example.launcher", observation.foregroundPackage)
        assertTrue("a disabled switch contributes no image", result.images.isEmpty())
        assertTrue(result.nonPersistent)
    }

    @Test
    fun `open list is a read so its catalog refusal observes text only without an image`() {
        // Task 5: `list` is not a screen operation. M5 still answers the catalog's own refusal with an observation, but
        // never with a picture.
        var captureCalls = 0
        var observeCalls = 0
        val catalog = catalog(
            probes = Probes(shell = false),
            observe = { observeCalls++; screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("open", """{"list":true}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals(ToolCatalog.CHANNEL_UNAVAILABLE, result.execution?.code)
        val observation = requireNotNull(result.observation) { "a catalog refusal still observes the screen" }
        assertEquals(ToolVision.Unavailable, observation.vision)
        assertEquals("not_attempted", observation.visionReason)
        assertEquals("a list read takes no picture", 0, captureCalls)
        assertEquals("the text observation is the one read", 1, observeCalls)
        assertTrue(result.images.isEmpty())
        assertTrue(result.nonPersistent)
    }

    @Test
    fun `a successful device result is not re-enriched by the catalog`() {
        var captureCalls = 0
        var observeCalls = 0
        val catalog = catalog(
            deviceOperations = RecordingDeviceOperations(),
            perception = StubPerception(catalogSnapshot(), imageResult()),
            observe = { observeCalls++; screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("device", """{"action":"observe"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val observation = requireNotNull(result.observation) { "the tool's own observation must survive" }
        assertTrue("the tool's compact screen is kept: ${observation.screen}", observation.screen!!.contains("#n1"))
        assertEquals("the tool's own capture is the only picture", 1, result.images.size)
        assertEquals("the catalog must not capture a second picture", 0, captureCalls)
        assertEquals("the catalog must not read the screen again", 0, observeCalls)
    }

    @Test
    fun `a status success stays the tool's text-only observation and is not re-enriched`() {
        var captureCalls = 0
        var observeCalls = 0
        val catalog = catalog(
            deviceOperations = RecordingDeviceOperations(),
            perception = StubPerception(catalogSnapshot(), imageResult()),
            observe = { observeCalls++; screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("device", """{"action":"status"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val observation = requireNotNull(result.observation) { "status success carries the tool's observation" }
        assertEquals("status is not a screen read", ToolVision.NotRequested, observation.vision)
        assertNull("status success reports no screen tree", observation.screen)
        assertTrue("status success takes no picture", result.images.isEmpty())
        assertEquals("the catalog must not capture for status", 0, captureCalls)
        assertEquals("the catalog must not read the screen for status", 0, observeCalls)
    }

    @Test
    fun `an unknown action still keeps its bare refusal without an observation or a picture`() {
        var captureCalls = 0
        var observeCalls = 0
        val catalog = catalog(
            observe = { observeCalls++; screenFacts() },
            capture = { captureCalls++; imageResult() },
        )

        val result = catalog.execute("teleport", "{}")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals(ToolCatalog.UNKNOWN_ACTION, result.execution?.code)
        assertNull("an unknown name is not a screen operation", result.observation)
        assertTrue(result.images.isEmpty())
        assertEquals("no observation for an unknown name", 0, observeCalls)
        assertEquals("no capture for an unknown name", 0, captureCalls)
    }

    @Test
    fun `the production composition backs open and shell with the perception observation source`() {        val root = com.sheen.rokid.phone.app.HostCompositionRoot.create(android.content.ContextWrapper(null))
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
            // The exact reason is the discriminator: `OpenTool`'s own no-source fallback answers `no_source` too, so a
            // blank-reason assertion would still pass if `OpenTool(observe = ...)` were dropped. Only the composition's
            // source answers `no_accessibility` here.
            assertEquals("no_accessibility", openObservation.visionReason)
            assertNull("a blind phone has no fabricated screen", openObservation.screen)
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
        perception: PerceptionProvider? = null,
    ): ToolCatalog {
        val registry = RefRegistry()
        val device = DeviceTool(
            deviceOperations,
            ScreenProjector(ObservationRenderer(registry), registry),
            perception = perception,
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

    /** One healthy perception read for the device tool, so a successful `device` result is a real tool result. */
    private fun catalogSnapshot(): PerceptionSnapshot = PerceptionSnapshot(
        snapshotId = "snap-catalog",
        capturedAtEpochMillis = 1_000L,
        foregroundPackage = "com.example.app",
        foregroundActivity = ".MainActivity",
        elements = listOf(
            PerceptionElement(ref = "#n1", role = "button", text = "OK", centerX = 1, centerY = 2, clickable = true),
        ),
        coverage = PerceptionCoverage(
            screen = "ok",
            notifications = "ok",
            media = "ok",
            calendar = "ok",
            acoustic = "ok",
            clipboard = "ok",
            vision = "ok",
            glasses = "ok",
            device = "ok",
        ),
    )

    /** A perception port that answers one fixed snapshot; used to prove tool results are not re-enriched. */
    private class StubPerception(
        private val snapshot: PerceptionSnapshot,
        private val shot: PerceptionScreenshotResult = PerceptionScreenshotResult.Disabled,
    ) : PerceptionProvider {
        override fun beginTurn(): PerceptionSnapshot = snapshot

        override fun snapshot(
            include: Set<PerceptionSource>,
            filter: PerceptionFilter,
            limit: Int?,
        ): PerceptionSnapshot = snapshot

        override fun refresh(): PerceptionSnapshot = snapshot

        override fun afterAction(): PerceptionSnapshot = snapshot

        override fun screenshot(): PerceptionScreenshotResult = shot

        override fun endTurn() = Unit
    }

    private fun imageResult(): PerceptionScreenshotResult.Image =
        PerceptionScreenshotResult.Image(IMAGE, "image/png", 100, 50)

    private class FakeShell(private val answer: (String) -> ShellResult) : PrivilegedShell {
        override fun run(command: String): ShellResult = answer(command)
    }

    private companion object {
        val IMAGE = ImageRef("artifact-1", "image/png", 100, 50, "sha-1")
        val SECOND_IMAGE = ImageRef("artifact-2", "image/png", 100, 50, "sha-2")
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
