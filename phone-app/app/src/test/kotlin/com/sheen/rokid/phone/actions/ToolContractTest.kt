package com.sheen.rokid.phone.actions

import android.content.Intent
import com.sheen.rokid.phone.actions.device.DialTool
import com.sheen.rokid.phone.actions.intent.AppCatalog
import com.sheen.rokid.phone.actions.intent.AppEntry
import com.sheen.rokid.phone.actions.intent.AppLaunchResolver
import com.sheen.rokid.phone.actions.intent.CallPermission
import com.sheen.rokid.phone.actions.intent.IntentActionExecutor
import com.sheen.rokid.phone.actions.intent.IntentLauncher
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.plugin.smarthelper.output.NotifyUserTool
import com.sheen.rokid.phone.plugin.smarthelper.skill.LoadSkillTool
import com.sheen.rokid.phone.plugin.smarthelper.skill.SkillLoad
import com.sheen.rokid.phone.plugin.smarthelper.skill.SkillLoadReason
import com.sheen.rokid.plugin.ActionDescriptor
import com.sheen.rokid.plugin.ActionOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * edith-0.3 Phase 4A Task 5 (A5): the **description contract** of the 24-tool default face.
 *
 * The face is read from production, never from a hand-written fixture: `HostCompositionRoot.create` supplies the 22
 * host descriptors (18 atomic + the 4 umbrella catalog tools) exactly as the JVM composition wires them, and the
 * plugin's own two executors supply `notify_user`/`load_skill`. Those are the same objects the tool registry projects
 * into the model schema, so a description asserted here is the one the model receives.
 *
 * `propose_hint` is deliberately not part of this face: it is the proactive hint turn's only tool and is advertised
 * only inside that route, so a default-face coverage test that counted it would be pinning the wrong surface.
 */
class ToolContractTest {

    @Test
    fun `the default face is exactly the twenty three described tools`() {
        val face = ToolFaceFixture.face()

        assertEquals("the default face is 23 tools", 23, face.size)
        assertEquals(
            "the model-visible names are the spec's 23, whatever the executors' order",
            ToolFaceFixture.DEFAULT_FACE,
            face.keys,
        )
    }

    @Test
    fun `the proactive hint tool is advertised only on the hint turn`() {
        val face = ToolFaceFixture.face()

        assertTrue(
            "propose_hint must not be part of the default face: ${face.keys}",
            "propose_hint" !in face,
        )
    }

    @Test
    fun `every description is model facing prose rather than the user title`() {
        ToolFaceFixture.face().forEach { (name, descriptor) ->
            assertTrue("$name must carry a description", descriptor.description.isNotBlank())
            assertNotEquals("$name must not repeat its title as the description", descriptor.title, descriptor.description)
            assertTrue(
                "$name's description must say more than its title: ${descriptor.description}",
                descriptor.description.length > descriptor.title.length,
            )
        }
    }

    @Test
    fun `every description names the arguments its schema constrains`() {
        val face = ToolFaceFixture.face()

        ARGUMENT_NAMES.forEach { (tool, arguments) ->
            val description = face.getValue(tool).description
            arguments.forEach { argument ->
                assertTrue("$tool must tell the model how '$argument' is bounded: $description", description.contains(argument))
            }
        }
    }

    @Test
    fun `every description says what a failed call means`() {
        val face = ToolFaceFixture.face()

        FAILURE_BEHAVIOUR.forEach { (tool, markers) ->
            val description = face.getValue(tool).description
            markers.forEach { marker ->
                assertTrue("$tool must describe its failure behaviour ('$marker'): $description", description.contains(marker))
            }
        }
    }

    @Test
    fun `an atomic tool says which umbrella tier supersedes it`() {
        val face = ToolFaceFixture.face()

        ATOMIC_TO_UMBRELLA.forEach { (atomic, umbrellas) ->
            val description = face.getValue(atomic).description
            umbrellas.forEach { umbrella ->
                assertTrue(
                    "$atomic must tell the model when to use $umbrella instead: $description",
                    saysToUse(description, umbrella),
                )
            }
        }
    }

    @Test
    fun `an umbrella tool names the atomic tools it replaces`() {
        val face = ToolFaceFixture.face()

        ATOMIC_TO_UMBRELLA.forEach { (atomic, umbrellas) ->
            umbrellas.forEach { umbrella ->
                val description = face.getValue(umbrella).description
                assertTrue("$umbrella must name $atomic when describing the cheaper tier: $description", description.contains(atomic))
            }
        }
    }

    @Test
    fun `open_app and dial name the codes their production executors really return`() {
        val face = ToolFaceFixture.face()
        val executor = IntentActionExecutor(
            AppLaunchResolver(FakeCatalog(listOf(
                AppEntry("com.camera", "Camera", Intent.ACTION_MAIN),
                AppEntry("com.scanner", "CamScanner", Intent.ACTION_MAIN),
            ))),
            FakeLauncher(accepts = false),
            noteStorage = null,
            callPermission = CallPermission { true },
        )

        fun refusedCode(name: String, argsJson: String): String {
            val result = executor.execute(name, argsJson)
            assertEquals("$name must refuse, never fail", ActionOutcome.Refused, result.outcome)
            return result.detail
        }

        // Two distinct answers, because they are two different facts: a missing name is not an ambiguity, and both are
        // worth telling the model to retry with `open` (which resolves packages and returns candidates).
        val notFound = refusedCode("open_app", """{"name":"地图"}""")
        val ambiguous = refusedCode("open_app", """{"name":"cam"}""")
        val packageName = refusedCode("open_app", """{"name":"com.camera"}""")
        assertTrue("a missing name is APP_NOT_FOUND: $notFound", notFound.startsWith("APP_NOT_FOUND"))
        assertTrue("an ambiguity is APP_AMBIGUOUS with candidates: $ambiguous", ambiguous.startsWith("APP_AMBIGUOUS:["))
        assertTrue("a package name is not a display label: $packageName", packageName.startsWith("APP_NOT_FOUND"))
        val refused = executor.execute("open_app", """{"name":"地图"}""")
        assertEquals("the model must be told to retry with open", true, refused.execution?.recoverable)
        assertEquals("APP_NOT_FOUND", refused.execution?.code)
        val openApp = face.getValue("open_app").description
        assertTrue("open_app must name the missing-app code: $openApp", openApp.contains("APP_NOT_FOUND"))
        assertTrue("open_app must name the ambiguity code: $openApp", openApp.contains("APP_AMBIGUOUS"))
        assertTrue("open_app's name is a display label, not a package name: $openApp", openApp.contains("不是包名"))

        // A refused launch is passed through from the intent leg; DIAL_FAILED belongs to the post-dispatch shape.
        val dial = DialTool(executor, callAvailable = { true })
            .execute("dial", """{"number":"123456"}""")
        assertEquals("a refused launch must stay Refused", ActionOutcome.Refused, dial.outcome)
        assertEquals("LAUNCH_REFUSED", dial.execution?.code)
        val dialDescription = face.getValue("dial").description
        assertTrue("dial must name the refused-launch code: $dialDescription", dialDescription.contains("LAUNCH_REFUSED"))
        assertTrue("dial must keep DIAL_FAILED for the uncertain shape: $dialDescription", dialDescription.contains("DIAL_FAILED"))
    }

    @Test
    fun `index tools tie snapshot invalidation to scrolling rather than to time`() {
        listOf("tap", "long_press", "input_text").forEach { name ->
            val description = ToolFaceFixture.face().getValue(name).description
            assertTrue(
                "$name must say a scroll invalidates the snapshot it uses: $description",
                description.contains("滚动后"),
            )
            assertFalse(
                "$name must not imply the snapshot expires by time (only scroll clears it): $description",
                description.contains("过期"),
            )
        }
    }

    @Test
    fun `high risk tools state the policy condition instead of promising a confirmation`() {
        listOf("clear_data", "uninstall", "disable_app", "set_permission").forEach { name ->
            val description = ToolFaceFixture.face().getValue(name).description
            assertFalse(
                "$name must not promise a confirmation the default L1 ceiling refuses: $description",
                description.contains("执行前用户会收到确认"),
            )
            assertTrue(
                "$name must say the current policy may ask or refuse: $description",
                description.contains("权限策略") && description.contains("可能需要确认"),
            )
        }
    }

    /** A catalog whose `visibleApps` is the list under test; the resolver remains the production one. */
    private class FakeCatalog(private val apps: List<AppEntry>) : AppCatalog {
        override fun visibleApps(): List<AppEntry> = apps
    }

    /** A launcher that never accepts, so every dispatch answers the production refusal. */
    private class FakeLauncher(private val accepts: Boolean) : IntentLauncher {
        override fun launch(action: String, data: String?, extras: Map<String, Any>): Boolean = accepts
    }

    private companion object {
        /**
         * True only when the description **recommends** [tier]. A clause that only prohibits it ("不要用 shell …")
         * must not satisfy the tier contract: that was the false positive this helper replaces.
         */
        fun saysToUse(description: String, tier: String): Boolean =
            description.split('。', '；').any { clause ->
                clause.contains("用 $tier") && !clause.contains("不要用 $tier")
            }

        /** The exact default face after the 2026-10-22 retirement: 17 atomic + 4 umbrella + the plugin's 2 delivery tools. */
        val ATOMIC_TO_UMBRELLA: Map<String, List<String>> = linkedMapOf(
            "read_screen" to listOf("device"),
            "tap" to listOf("device"),
            "long_press" to listOf("device"),
            "scroll" to listOf("device"),
            "input_text" to listOf("device"),
            "open_app" to listOf("open"),
            "open_url" to listOf("open"),
            "open_settings" to listOf("open"),
            "list_apps" to listOf("open"),
            "system_action" to listOf("device", "shell"),
        )

        /** The argument names each schema declares, which its model-facing prose has to bound. */
        val ARGUMENT_NAMES: Map<String, List<String>> = linkedMapOf(
            "read_screen" to listOf("filter"),
            "tap" to listOf("index"),
            "long_press" to listOf("index"),
            "scroll" to listOf("direction"),
            "input_text" to listOf("index", "text"),
            "open_app" to listOf("name"),
            "open_url" to listOf("url"),
            "open_settings" to listOf("page"),
            "set_alarm" to listOf("hour", "minute", "label"),
            "compose_note" to listOf("title", "body"),
            "clear_data" to listOf("package"),
            "uninstall" to listOf("package"),
            "disable_app" to listOf("package"),
            "set_permission" to listOf("package", "permission", "granted"),
            "set_brightness" to listOf("value"),
            "list_apps" to emptyList(),
            "system_action" to listOf("action"),
            "device" to listOf("action"),
            "shell" to listOf("command"),
            "open" to listOf("target"),
            "dial" to listOf("number"),
            "notify_user" to listOf("text", "channel"),
            "load_skill" to listOf("name"),
        )

        /**
         * The stable tokens each description must use to say what a failed call means. They are the codes the executors
         * already answer with (or, for `list_apps`, the wire marker of its only partial-result fact), so the prose cannot
         * invent a failure shape the tool does not produce. A tool with more than one reachable failure names each.
         */
        val FAILURE_BEHAVIOUR: Map<String, List<String>> = linkedMapOf(
            "read_screen" to listOf("ACTION_CHANNEL_UNAVAILABLE"),
            "tap" to listOf("NODE_NOT_CLICKABLE"),
            "long_press" to listOf("NODE_NOT_CLICKABLE"),
            "scroll" to listOf("NO_SCREEN_CHANGE"),
            "input_text" to listOf("ARGUMENTS_INVALID"),
            "open_app" to listOf("APP_NOT_FOUND", "APP_AMBIGUOUS"),
            "open_url" to listOf("INVALID_URL"),
            "open_settings" to listOf("INVALID_SETTINGS_PAGE"),
            "set_alarm" to listOf("INVALID_TIME"),
            "compose_note" to listOf("STORAGE_UNAVAILABLE"),
            "clear_data" to listOf("STATE_UNCHANGED"),
            "uninstall" to listOf("STATE_UNCHANGED"),
            "disable_app" to listOf("STATE_UNCHANGED"),
            "set_permission" to listOf("STATE_UNCHANGED"),
            "set_brightness" to listOf("STATE_UNCHANGED"),
            "list_apps" to listOf("truncated"),
            "system_action" to listOf("ACTION_CHANNEL_UNAVAILABLE", "COMMAND_FAILED"),
            "device" to listOf("STALE_REF"),
            "shell" to listOf("SHELL_NOT_BOUND"),
            "open" to listOf("APP_AMBIGUOUS"),
            "dial" to listOf("CALL_PERMISSION_MISSING"),
            "notify_user" to listOf("NOTIFY_ARGUMENTS_INVALID"),
            "load_skill" to listOf("SKILL_NOT_FOUND"),
        )
    }
}

/**
 * The default 23-tool face, assembled from the production executors. Test code only: both the contract and the routing
 * suite read this one fixture, so neither can drift into a hand-written stand-in for the composition.
 */
internal object ToolFaceFixture {

    val DEFAULT_FACE: Set<String> = linkedSetOf(
        // atomic a11y
        "read_screen", "tap", "long_press", "scroll", "input_text",
        // atomic intent (the retired intent `dial` is not part of the face; `ToolCatalog.DialTool` owns the name)
        "open_app", "open_url", "open_settings", "set_alarm", "compose_note",
        // atomic Shizuku
        "clear_data", "uninstall", "disable_app", "set_permission", "set_brightness",
        // atomic local directory / system keys (`launch_app` retired 2026-10-22, 24 → 23)
        "list_apps", "system_action",
        // the umbrella catalog
        "device", "shell", "open", "dial",
        // the plugin's delivery tools
        "notify_user", "load_skill",
    )

    fun face(): Map<String, ActionDescriptor> {
        val root = HostCompositionRoot.create(android.content.ContextWrapper(null))
        try {
            val surface = requireNotNull(root.toolSurfaceForProbe) {
                "the production composition must expose the wired Actions provider for the JVM gate"
            }
            val notify = NotifyUserTool { _, _ -> }
            val loadSkill = LoadSkillTool(
                lookup = { SkillLoad.NotFound(SkillLoadReason.NOT_IN_LIBRARY) },
                onLoaded = { },
            )
            return (surface.descriptors() + notify.descriptors() + loadSkill.descriptors())
                .associateBy { it.name }
        } finally {
            root.close()
        }
    }
}
