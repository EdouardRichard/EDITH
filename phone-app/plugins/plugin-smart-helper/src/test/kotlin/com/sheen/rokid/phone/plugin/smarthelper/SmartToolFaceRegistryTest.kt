package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.output.NotifyUserTool
import com.sheen.rokid.phone.plugin.smarthelper.skill.LoadSkillTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * edith-0.3 Phase 4A Task 4 (A4), updated 2026-10-22 (launch_app retired: 24 → 23): the plugin's half of the face.
 *
 * The host's 21 descriptors (17 atomic + 4 umbrella) are answered by the one scoped `Capability.Actions` executor; this
 * test feeds the plugin exactly the production host **names** and pins what the plugin adds beside them:
 * - `notify_user` (the model's only channel to the wearer) and `load_skill` (the catalog's only way to a body), for 23;
 * - no `propose_hint` on the default face — that tool belongs to the proactive hint turn alone;
 * - one count-only `P4_TOOLFACE tools=23 duplicate=0` line per **session** (the registry is rebuilt every turn, so a
 *   session-keyed one-shot is what keeps the line from repeating);
 *
 * The 21 host names are duplicated here on purpose: `plugin-smart-helper` must not depend on `phone-app`, and the list
 * is the contract the host side pins independently in `ToolFaceWiringTest`/`ToolFaceCompositionTest`.
 */
class SmartToolFaceRegistryTest {

    /** The 21 host descriptors the production `ToolSurface` advertises, in order. */
    private val hostToolNames = listOf(
        // atomic a11y
        "read_screen", "tap", "long_press", "scroll", "input_text",
        // atomic intent (no `dial`: the catalog owns it)
        "open_app", "open_url", "open_settings", "set_alarm", "compose_note",
        // atomic Shizuku
        "clear_data", "uninstall", "disable_app", "set_permission", "set_brightness",
        // atomic local directory / system keys (`launch_app` retired 2026-10-22, 24 → 23)
        "list_apps", "system_action",
        // umbrella
        "device", "shell", "open", "dial",
    )

    /** A rig whose host advertises the 21 production host tools (plus [extra] for the duplicate case). */
    private fun rigWithHostFace(extra: List<String> = emptyList()): SmartTestRig {
        val rig = SmartTestRig()
        (hostToolNames + extra).forEach { name -> rig.actions.descriptors += smartAction(name) }
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }
        return rig
    }

    /** Runs one complete turn and returns both the request's tool names and the `P4_TOOLFACE` lines it emitted. */
    private fun oneTurn(rig: SmartTestRig): Pair<List<String>, List<String>> {
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { line -> lines += line }
        try {
            rig.wakeAndFinal("打开设置")
        } finally {
            P3Log.sink = previous
        }
        val tools = rig.model.requests.single().tools.map { it.name }
        return tools to lines.filter { it.startsWith("P4_TOOLFACE") }
    }

    @Test
    fun `the default registry is the 21 host tools plus notify_user and load_skill`() {
        val (tools, _) = oneTurn(rigWithHostFace())

        assertEquals(23, tools.size)
        assertEquals(23, tools.toSet().size, "0 duplicate: 23 distinct names")
        assertEquals(
            (hostToolNames + NotifyUserTool.DESCRIPTOR.name + LoadSkillTool.NAME).toSet(),
            tools.toSet(),
        )
        assertTrue(tools.contains("notify_user"))
        assertTrue(tools.contains("load_skill"))
    }

    @Test
    fun `the default face never advertises the hint turn's propose_hint`() {
        val (tools, _) = oneTurn(rigWithHostFace())

        assertFalse(tools.contains("propose_hint"), "propose_hint belongs to the proactive hint turn only")
    }

    @Test
    fun `a registry build emits one count only P4_TOOLFACE line`() {
        val (_, faceLines) = oneTurn(rigWithHostFace())

        assertEquals(listOf("P4_TOOLFACE tools=23 duplicate=0"), faceLines)
        val line = faceLines.single()
        assertFalse(hostToolNames.any { line.contains(it) }, "no tool name may reach the line: $line")
        assertFalse(
            line.contains("notify_user") || line.contains("load_skill"),
            "no plugin tool name either: $line",
        )
    }

    @Test
    fun `two turns in the same session emit one P4_TOOLFACE line`() {
        val rig = rigWithHostFace()
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { line -> lines += line }
        try {
            rig.wakeAndFinal("打开设置", turnId = "asr-1")
            // The wake path has a 3 s cooldown; the second turn must be past it to build its own registry.
            rig.tick(4_000)
            rig.wakeAndFinal("再打开一次设置", turnId = "asr-2")
        } finally {
            P3Log.sink = previous
        }

        assertEquals(2, rig.model.requests.size, "the session must have run two turns, each building a registry")
        assertEquals(
            listOf("P4_TOOLFACE tools=23 duplicate=0"),
            lines.filter { it.startsWith("P4_TOOLFACE") },
            "the face is pinned for the session, so its count is a session fact, not a per-turn one",
        )
    }

    @Test
    fun `a host name colliding with a plugin tool is counted as one duplicate`() {
        val (tools, faceLines) = oneTurn(rigWithHostFace(extra = listOf("notify_user")))

        // ToolRegistry merges the colliding declaration, so the model-visible face stays 23 — and the line says the
        // face was reached with one declaration merged away.
        assertEquals(23, tools.size)
        assertEquals(23, tools.toSet().size)
        assertEquals(listOf("P4_TOOLFACE tools=23 duplicate=1"), faceLines)
    }
}
