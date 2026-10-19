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
import com.sheen.rokid.phone.plugin.smarthelper.output.NotifyUserTool
import com.sheen.rokid.phone.plugin.smarthelper.skill.LoadSkillTool
import com.sheen.rokid.phone.plugin.smarthelper.skill.SkillLoad
import com.sheen.rokid.phone.plugin.smarthelper.skill.SkillLoadReason
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.PrecheckDecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `each unavailable channel removes only its own tools`() {
        val probes = Probes(a11y = false)

        val a11yDown = catalog(probes).descriptors().map { it.name }
        assertFalse(a11yDown.contains("device"))
        assertEquals(listOf("shell", "open", "dial"), a11yDown)

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
        listOf("filter", "kind", "ref", "text", "value", "direction", "key", "timeout_ms")
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

    // - helpers -

    private fun catalog(
        probes: Probes = Probes(),
        deviceOperations: RecordingDeviceOperations = RecordingDeviceOperations(),
        dialExecutor: RecordingExecutor = RecordingExecutor(),
    ): ToolCatalog {
        val registry = RefRegistry()
        val device = DeviceTool(
            deviceOperations,
            ScreenProjector(ObservationRenderer(registry), registry),
        )
        return ToolCatalog(
            device = device,
            shell = ShellTool(null),
            open = OpenTool(null, EmptyCatalog),
            dial = DialTool(dialExecutor) { probes.call },
            a11yAvailable = { probes.a11y },
            shellAvailable = { probes.shell },
            callAvailable = { probes.call },
        )
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

        override fun descriptors(): List<com.sheen.rokid.plugin.ActionDescriptor> = emptyList()

        override fun execute(name: String, argsJson: String): com.sheen.rokid.plugin.ActionResult {
            calls += name to argsJson
            return com.sheen.rokid.plugin.ActionResult(ActionOutcome.Done, "done")
        }

        override fun cancel() = Unit
    }

    private class RecordingDeviceOperations(
        private val observation: Observation? = null,
        override val revision: Long? = null,
        override val generation: Long? = null,
    ) : DeviceOperations {
        override val latestObservation: Observation?
            get() = observation
        val observeEnvelopes = mutableListOf<DeviceEnvelope>()
        var cancels = 0
        var begins = 0
        var closes = 0

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

        override fun status(): DeviceEnvelope =
            DeviceEnvelope.success("status", "accessibility", true, null, null, Timing(0, 0, 0))

        override fun cancel() { cancels++ }

        override fun beginTurn() { begins++ }

        override fun close() { closes++ }
    }
}
