package com.sheen.rokid.phone.actions

import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.plugin.smarthelper.output.NotifyUserTool
import com.sheen.rokid.phone.plugin.smarthelper.skill.LoadSkillTool
import com.sheen.rokid.phone.plugin.smarthelper.skill.SkillLoad
import com.sheen.rokid.phone.plugin.smarthelper.skill.SkillLoadReason
import com.sheen.rokid.plugin.ActionDescriptor
import org.junit.Assert.assertEquals
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
    fun `the default face is exactly the twenty four described tools`() {
        val face = ToolFaceFixture.face()

        assertEquals("the default face is 24 tools", 24, face.size)
        assertEquals(
            "the model-visible names are the spec's 24, whatever the executors' order",
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

        FAILURE_BEHAVIOUR.forEach { (tool, marker) ->
            val description = face.getValue(tool).description
            assertTrue("$tool must describe its failure behaviour ('$marker'): $description", description.contains(marker))
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
                    description.contains("用 $umbrella"),
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

    private companion object {
        /** The exact default face after Phase 4A Task 4: 18 atomic + 4 umbrella + the plugin's 2 delivery tools. */
        val ATOMIC_TO_UMBRELLA: Map<String, List<String>> = linkedMapOf(
            "read_screen" to listOf("device"),
            "tap" to listOf("device"),
            "long_press" to listOf("device"),
            "scroll" to listOf("device"),
            "input_text" to listOf("device"),
            "open_app" to listOf("open"),
            "open_url" to listOf("open"),
            "open_settings" to listOf("open"),
            "launch_app" to listOf("open"),
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
            "launch_app" to listOf("name"),
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
         * The one stable token each description must use to say what a failed call means. The tokens are the codes the
         * executors already answer with (or, for `list_apps`, its only truncation fact), so the prose cannot invent a
         * failure shape the tool does not produce.
         */
        val FAILURE_BEHAVIOUR: Map<String, String> = linkedMapOf(
            "read_screen" to "ACTION_CHANNEL_UNAVAILABLE",
            "tap" to "NODE_NOT_CLICKABLE",
            "long_press" to "NODE_NOT_CLICKABLE",
            "scroll" to "NO_SCREEN_CHANGE",
            "input_text" to "ARGUMENTS_INVALID",
            "open_app" to "APP_NOT_FOUND",
            "open_url" to "INVALID_URL",
            "open_settings" to "INVALID_SETTINGS_PAGE",
            "set_alarm" to "INVALID_TIME",
            "compose_note" to "STORAGE_UNAVAILABLE",
            "clear_data" to "STATE_UNCHANGED",
            "uninstall" to "STATE_UNCHANGED",
            "disable_app" to "STATE_UNCHANGED",
            "set_permission" to "STATE_UNCHANGED",
            "set_brightness" to "STATE_UNCHANGED",
            "launch_app" to "APP_AMBIGUOUS",
            "list_apps" to "截断",
            "system_action" to "ACTION_CHANNEL_UNAVAILABLE",
            "device" to "STALE_REF",
            "shell" to "SHELL_NOT_BOUND",
            "open" to "APP_AMBIGUOUS",
            "dial" to "CALL_PERMISSION_MISSING",
            "notify_user" to "NOTIFY_ARGUMENTS_INVALID",
            "load_skill" to "SKILL_NOT_FOUND",
        )
    }
}

/**
 * The default 24-tool face, assembled from the production executors. Test code only: both the contract and the routing
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
        // atomic launch/system
        "launch_app", "list_apps", "system_action",
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
