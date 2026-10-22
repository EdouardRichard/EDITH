package com.sheen.rokid.phone.actions.intent
import kotlinx.serialization.json.JsonObject
import android.content.Intent
import android.provider.AlarmClock
import android.provider.Settings
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.StorageHandle
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five L0/L1 actions the Agent may take on the phone, driven entirely through the two seams.
 *
 * Two properties matter as much as the happy paths:
 * - every refusal is [ActionOutcome.Refused] with the code **exactly** in the payload - never `Failed` and never a
 *   thrown exception, because `agent-runtime`'s registry projects the outcome and the model sees the code as the
 *   envelope's `data` (the old `"Refused: <code>"` / `"Failed: <code>"` text is gone), so a refusal reads as a
 *   decision rather than a broken tool;
 * - a refusal performs no side effect: the launcher is not called and nothing is written.
 */
class IntentActionExecutorTest {

    private class RecordingLauncher(var accepts: Boolean = true) : IntentLauncher {
        data class Call(val action: String, val data: String?, val extras: Map<String, Any>)
        val calls = mutableListOf<Call>()
        override fun launch(action: String, data: String?, extras: Map<String, Any>): Boolean {
            calls += Call(action, data, extras)
            return accepts
        }
    }

    private class FakeCatalog(private val apps: List<AppEntry>) : AppCatalog {
        override fun visibleApps(): List<AppEntry> = apps
    }

    private class FakeStorage : StorageHandle {
        val written = LinkedHashMap<String, String>()
        override fun read(key: String): ByteArray? = written[key]?.toByteArray(Charsets.UTF_8)
        override fun write(key: String, value: ByteArray) { written[key] = value.toString(Charsets.UTF_8) }
        override fun delete(key: String) { written.remove(key) }
        override fun clear() { written.clear() }
    }

    private class Rig(apps: List<AppEntry> = emptyList(), storage: StorageHandle? = FakeStorage()) {
        val launcher = RecordingLauncher()
        val storage: StorageHandle? = storage
        val executor = IntentActionExecutor(AppLaunchResolver(FakeCatalog(apps)), launcher, storage)

        fun execute(name: String, argsJson: String): ActionResult = executor.execute(name, argsJson)

        fun refused(name: String, argsJson: String, code: String) {
            val result = execute(name, argsJson)
            assertEquals("a refusal must stay Refused, never Failed", ActionOutcome.Refused, result.outcome)
            assertEquals(code, result.detail)
        }

        fun done(name: String, argsJson: String): ActionResult {
            val result = execute(name, argsJson)
            assertEquals(result.detail, ActionOutcome.Done, result.outcome)
            return result
        }
    }

    private val weChat = AppEntry("com.tencent.mm", "微信", Intent.ACTION_MAIN)

    private fun rig(vararg apps: AppEntry) = Rig(apps.toList())

    // ---- descriptors --------------------------------------------------------------------------------------

    @Test
    fun descriptorsDeclareExactlyTheFiveL0AndL1Tools() {
        val byName = Rig().executor.descriptors().associateBy { it.name }

        // P3.3 Task 9 added the first L2 action. The five L0/L1 tools are unchanged, and `dial` is the one row above
        // them - so this test now names six and checks the level of each rather than asserting "L0/L1 only".
        assertEquals(setOf("open_app", "open_url", "open_settings", "set_alarm", "compose_note", "dial"), byName.keys)
        val expected = mapOf(
            "open_app" to Triple(RiskLevel.L0, "device", true),
            "open_url" to Triple(RiskLevel.L0, "device", true),
            "open_settings" to Triple(RiskLevel.L0, "device", true),
            "set_alarm" to Triple(RiskLevel.L1, "local", false),
            "compose_note" to Triple(RiskLevel.L1, "local", false),
            "dial" to Triple(RiskLevel.L2, "phone.number", false),
        )
        byName.forEach { (name, descriptor) ->
            val (risk, scope, idempotent) = expected.getValue(name)
            assertEquals(name, risk, descriptor.risk)
            assertEquals(name, scope, descriptor.scope)
            assertEquals("$name idempotent", idempotent, descriptor.idempotent)
            // L0/L1 are exempt from confirmation by the P3.1 spec; irreversible on purpose (we cannot unlaunch an
            // app or unwrite a note).
            assertEquals("$name requiresConfirmation", false, descriptor.requiresConfirmation)
            assertEquals("$name reversible", false, descriptor.reversible)
            assertTrue("$name needs a human-auditable title", descriptor.title.isNotBlank())
        }
        assertTrue("the five P3.1 tools stay at or below L1",
            byName.filterKeys { it != "dial" }.values.all { it.risk.ordinal <= RiskLevel.L1.ordinal })
        assertEquals("dial is the phase's one L2 row", RiskLevel.L2, byName.getValue("dial").risk)
    }

    // ---- dial (L2) -----------------------------------------------------------------------------------------

    private fun dialRig(permission: Boolean): Pair<IntentActionExecutor, RecordingLauncher> {
        val launcher = RecordingLauncher()
        val executor = IntentActionExecutor(
            AppLaunchResolver(FakeCatalog(emptyList())),
            launcher,
            null,
            CallPermission { permission },
        )
        return executor to launcher
    }

    @Test
    fun dialCallsTheExactTelUriWhenTheCallPermissionIsGranted() {
        val (executor, launcher) = dialRig(permission = true)

        val result = executor.execute("dial", """{"number":"12345678901"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(listOf(RecordingLauncher.Call(Intent.ACTION_CALL, "tel:12345678901", emptyMap())), launcher.calls)
    }

    @Test
    fun dialWithoutTheCallPermissionRefusesAndNeverLaunches() {
        val (executor, launcher) = dialRig(permission = false)

        val result = executor.execute("dial", """{"number":"12345678901"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("CALL_PERMISSION_MISSING", result.detail)
        assertTrue("a refusal must perform no side effect", launcher.calls.isEmpty())
    }

    @Test
    fun dialNeverSilentlyFallsBackToTheDialerScreen() {
        val (executor, launcher) = dialRig(permission = true)

        executor.execute("dial", """{"number":"12345678901"}""")

        assertTrue("the dialer is not a substitute for a direct call", launcher.calls.none { it.action == Intent.ACTION_DIAL })
    }

    @Test
    fun dialRefusesANumberOutsideTheDialAlphabet() {
        val (executor, launcher) = dialRig(permission = true)

        listOf("12345678901; id", "1234 5678", "tel:123", "").forEach { number ->
            val result = executor.execute("dial", """{"number":"$number"}""")
            assertEquals("refused: $number", ActionOutcome.Refused, result.outcome)
            assertEquals("refused: $number", "ARGUMENTS_INVALID", result.detail)
        }
        assertTrue(launcher.calls.isEmpty())
    }

    @Test
    fun everyDescriptorSchemaIsAJsonObjectNamingItsRequiredFields() {
        val required = mapOf(
            "open_app" to listOf("name"),
            "open_url" to listOf("url"),
            "open_settings" to listOf("page"),
            "set_alarm" to listOf("hour", "minute", "label"),
            "compose_note" to listOf("title", "body"),
            // P3.3 Task 9's L2 action. Its schema is the same shape as the rest: a JSON object naming what it needs.
            "dial" to listOf("number"),
        )
        val descriptors = Rig().executor.descriptors()
        // Asserted here too, so this test cannot pass vacuously over an empty descriptor list.
        assertEquals(required.keys, descriptors.map { it.name }.toSet())
        descriptors.forEach { descriptor ->
            val schema = Json.parseToJsonElement(descriptor.parametersJson) as JsonObject
            val fields = schema.getValue("required").jsonArray.map { it.jsonPrimitive.content }
            assertEquals(descriptor.name, required.getValue(descriptor.name), fields)
        }
    }

    @Test
    fun cancelIsANoOpBecauseIntentActionsAreAlreadyFinished() {
        val executor = Rig().executor

        executor.cancel()
        executor.cancel()
    }

    // ---- happy paths --------------------------------------------------------------------------------------

    @Test
    fun openAppLaunchesTheResolvedPackageWithTheMainLauncherIntent() {
        val rig = rig(weChat)

        val result = rig.done("open_app", """{"name":"微信"}""")

        assertEquals("opened 微信", result.detail)
        val call = rig.launcher.calls.single()
        assertEquals(Intent.ACTION_MAIN, call.action)
        assertEquals("package:com.tencent.mm", call.data)
        assertTrue("open_app carries no extras", call.extras.isEmpty())
    }

    @Test
    fun openUrlViewsTheValidatedHttpUrl() {
        val rig = Rig()
        val url = "https://example.com/a?b=1"

        val result = rig.done("open_url", """{"url":"$url"}""")

        assertEquals("opened $url", result.detail)
        val call = rig.launcher.calls.single()
        assertEquals(Intent.ACTION_VIEW, call.action)
        assertEquals(url, call.data)
        assertTrue(call.extras.isEmpty())
    }

    @Test
    fun openSettingsMapsEveryAllowlistedPageToItsSettingsAction() {
        val pages = mapOf(
            "wifi" to Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to Settings.ACTION_BLUETOOTH_SETTINGS,
            "apps" to Settings.ACTION_APPLICATION_SETTINGS,
            "display" to Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to Settings.ACTION_SOUND_SETTINGS,
            "battery" to Settings.ACTION_BATTERY_SAVER_SETTINGS,
            "location" to Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "notifications" to Settings.ACTION_ALL_APPS_NOTIFICATION_SETTINGS,
            "accessibility" to Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "data_usage" to Settings.ACTION_DATA_USAGE_SETTINGS,
        )
        val rig = Rig()

        pages.forEach { (page, action) ->
            rig.done("open_settings", """{"page":"$page"}""")
            val call = rig.launcher.calls.last()
            assertEquals(page, action, call.action)
            assertNull("$page must not carry data", call.data)
            assertTrue("$page must not carry extras", call.extras.isEmpty())
        }
        assertEquals(pages.size, rig.launcher.calls.size)
    }

    @Test
    fun setAlarmPassesTheTimeAndLabelToTheClockApp() {
        val rig = Rig()

        val result = rig.done("set_alarm", """{"hour":7,"minute":30,"label":"起床"}""")

        assertEquals("alarm set: 07:30 起床", result.detail)
        val call = rig.launcher.calls.single()
        assertEquals(AlarmClock.ACTION_SET_ALARM, call.action)
        assertNull(call.data)
        assertEquals(7, call.extras[AlarmClock.EXTRA_HOUR])
        assertEquals(30, call.extras[AlarmClock.EXTRA_MINUTES])
        assertEquals("起床", call.extras[AlarmClock.EXTRA_MESSAGE])
    }

    @Test
    fun composeNoteWritesTheTitleAndBodyIntoTheAppOwnNamespace() {
        val storage = FakeStorage()
        val rig = Rig(storage = storage)
        val body = "明天十点给张老师回电话"

        val result = rig.done("compose_note", """{"title":"回电话","body":"$body"}""")

        assertEquals("note written: 回电话", result.detail)
        val (key, text) = storage.written.entries.single()
        // The production namespace validates keys with this exact character class, so a title-derived key must obey it.
        assertTrue("key '$key' must satisfy the storage namespace contract",
            key.matches(Regex("[A-Za-z0-9_.-]{1,64}")))
        assertTrue("the key must be derived from the title", key.startsWith("note-"))
        assertEquals("回电话\n$body", text)
        assertTrue(rig.launcher.calls.isEmpty())
    }

    @Test
    fun twoNotesWithTheSameTitleAreStoredAsTwoEntries() {
        // `compose_note` is declared non-idempotent, so a repeat is a new note and must not overwrite the first.
        val storage = FakeStorage()
        val rig = Rig(storage = storage)
        val args = """{"title":"Shopping","body":"milk"}"""

        rig.done("compose_note", args)
        rig.done("compose_note", args)

        assertEquals(2, storage.written.size)
    }

    // ---- refusals -----------------------------------------------------------------------------------------

    @Test
    fun openAppReportsMissingAndAmbiguousNamesAsDistinctRetryableAnswers() {
        val rig = rig(AppEntry("com.camera", "Camera", Intent.ACTION_MAIN),
            AppEntry("com.scanner", "CamScanner", Intent.ACTION_MAIN))

        val ambiguous = rig.execute("open_app", """{"name":"cam"}""")
        assertEquals("an ambiguity is a Refused, never a Done", ActionOutcome.Refused, ambiguous.outcome)
        assertTrue("the candidates must travel: ${ambiguous.detail}", ambiguous.detail.startsWith("APP_AMBIGUOUS:["))
        assertTrue("both packages must be visible to the model: ${ambiguous.detail}",
            ambiguous.detail.contains("com.camera") && ambiguous.detail.contains("com.scanner"))
        assertEquals("APP_AMBIGUOUS", ambiguous.execution?.code)
        assertEquals("the model can retry with open", true, ambiguous.execution?.recoverable)

        rig.refused("open_app", """{"name":"地图"}""",
            "APP_NOT_FOUND:打开应用优先用 open（支持包名与候选）")
        rig.refused("open_app", """{"name":"   "}""",
            "APP_NOT_FOUND:打开应用优先用 open（支持包名与候选）")
        assertTrue("a refusal must not launch anything", rig.launcher.calls.isEmpty())
    }

    @Test
    fun openUrlRefusesAnythingThatIsNotAnAbsoluteHttpUrl() {
        val rig = Rig()
        listOf("file:///etc/passwd", "javascript:alert(1)", "https://", "example.com", "不是链接", "").forEach { url ->
            rig.refused("open_url", """{"url":"$url"}""", "INVALID_URL")
        }

        assertTrue(rig.launcher.calls.isEmpty())
    }

    @Test
    fun openSettingsRefusesAPageOutsideTheAllowlist() {
        val rig = Rig()

        rig.refused("open_settings", """{"page":"airplane_mode"}""", "INVALID_SETTINGS_PAGE")
        rig.refused("open_settings", """{"page":"whatever"}""", "INVALID_SETTINGS_PAGE")
        assertTrue(rig.launcher.calls.isEmpty())
    }

    @Test
    fun setAlarmRefusesAnImpossibleTime() {
        val rig = Rig()

        rig.refused("set_alarm", """{"hour":-1,"minute":30,"label":"x"}""", "INVALID_TIME")
        rig.refused("set_alarm", """{"hour":24,"minute":30,"label":"x"}""", "INVALID_TIME")
        rig.refused("set_alarm", """{"hour":7,"minute":-1,"label":"x"}""", "INVALID_TIME")
        rig.refused("set_alarm", """{"hour":7,"minute":60,"label":"x"}""", "INVALID_TIME")
        assertTrue(rig.launcher.calls.isEmpty())
    }

    @Test
    fun setAlarmRefusesABlankLabel() {
        val rig = Rig()

        rig.refused("set_alarm", """{"hour":7,"minute":30,"label":"   "}""", "INVALID_TIME")
        assertTrue(rig.launcher.calls.isEmpty())
    }

    @Test
    fun composeNoteRefusesWithoutAStorageHandle() {
        val rig = Rig(storage = null)

        rig.refused("compose_note", """{"title":"回电话","body":"十点"}""", "STORAGE_UNAVAILABLE")
    }

    @Test
    fun composeNoteRefusesWhenBothFieldsAreBlank() {
        val storage = FakeStorage()
        val rig = Rig(storage = storage)

        rig.refused("compose_note", """{"title":"  ","body":""}""", "INVALID_NOTE")
        assertTrue("a refused note must not be written", storage.written.isEmpty())
    }

    @Test
    fun aDeclinedLaunchBecomesALaunchRefusedWithNoSilentSuccess() {
        val rig = rig(weChat)
        rig.launcher.accepts = false

        rig.refused("open_app", """{"name":"微信"}""", "LAUNCH_REFUSED")
        rig.refused("open_url", """{"url":"https://example.com"}""", "LAUNCH_REFUSED")
        rig.refused("open_settings", """{"page":"wifi"}""", "LAUNCH_REFUSED")
        rig.refused("set_alarm", """{"hour":7,"minute":30,"label":"起床"}""", "LAUNCH_REFUSED")
    }

    // ---- malformed input ----------------------------------------------------------------------------------

    @Test
    fun malformedArgumentsAreRefusedRatherThanThrown() {
        val rig = rig(weChat)
        val cases = mapOf(
            "open_app" to listOf("{", "{}", "[]", """{"name":123}""", """{"other":"x"}"""),
            "open_url" to listOf("{}", """{"url":123}"""),
            "open_settings" to listOf("{}", """{"page":123}"""),
            "set_alarm" to listOf("{}", """{"hour":7,"minute":30}""", """{"hour":"7","minute":30,"label":"x"}""",
                """{"hour":7,"minute":30,"label":9}"""),
            "compose_note" to listOf("{}", """{"title":"a"}""", """{"title":"a","body":9}"""),
        )

        cases.forEach { (name, payloads) ->
            payloads.forEach { payload -> rig.refused(name, payload, "ARGUMENTS_INVALID") }
        }
        assertTrue("malformed arguments must not reach the launcher or the storage",
            rig.launcher.calls.isEmpty())
    }

    @Test
    fun anUnknownToolNameIsRefused() {
        rig(weChat).refused("send_sms", """{"to":"10086"}""", "UNKNOWN_ACTION")
        rig(weChat).refused("", "{}", "UNKNOWN_ACTION")
    }
}
