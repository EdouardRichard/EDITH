package edith.plugins.smart

import edith.plugin.Capability
import edith.plugin.StorageAccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The closure of the review's C3, first half: the SKILL library must actually reach the prompt.
 *
 * The finding was that `SkillManager` had no production caller at all, so "SKILL 全量注入" reached no prompt in any
 * build — and no test could fail on it, because nothing asserted the instructions the model receives.
 */
class SkillInjectionWiringTest {

    private class FakeSkills(private val body: String?) : StorageAccess {
        override fun listFiles(dirPath: String, suffix: String): List<String> =
            if (body == null) emptyList() else listOf("$dirPath/a.md")

        override fun readFile(path: String): String? = body
        override fun deleteFile(path: String): Boolean = false
    }

    private fun doc(name: String, body: String) =
        "---\nname: $name\nversion: 1.0.0\ndescription: d\n---\n$body\n"

    private fun rig(skills: StorageAccess): SmartTestRig {
        val rig = SmartTestRig()
        rig.activate(overrides = mapOf(Capability.StorageAccess to skills))
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }
        return rig
    }

    @Test
    fun `an enabled skill is injected whole into the request instructions`() {
        val marker = "正文标记-8821"
        val rig = rig(FakeSkills(doc("reply-wechat-message", marker)))

        rig.wakeAndFinal("帮我回微信")

        val instructions = rig.model.requests.single().instructions
        assertTrue(instructions.contains("SKILL: reply-wechat-message"), instructions.take(300))
        assertTrue(instructions.contains(marker), "the body must be injected whole, not summarised")
    }

    @Test
    fun `the proactive route is given the skills as well`() {
        // Both routes share the library (C3): the proactive path builds its own instructions, and it had the same
        // missing injection before this change.
        val rig = rig(FakeSkills(doc("brake-check", "正文标记-3312")))
        rig.plugin.handle(edith.contracts.AcousticEvent(rig.envelope(), edith.contracts.AcousticLabel.Speech))
        rig.plugin.handle(
            edith.contracts.TranscriptEvent(rig.transcript("这个页面怎么一直转圈")),
        )

        assertTrue(
            rig.model.requests.single().instructions.contains("正文标记-3312"),
            rig.model.requests.single().instructions.take(300),
        )
    }

    @Test
    fun `a host with no skill directory answers normally`() {
        // Spec §3.7: no SKILLs installed is a normal state, not a degraded one - the turn still runs.
        val rig = rig(FakeSkills(null))

        rig.wakeAndFinal("你好")

        assertEquals(1, rig.model.requests.size)
        assertTrue("SKILL:" !in rig.model.requests.single().instructions)
    }

    @Test
    fun `a broken skill file does not break the turn`() {
        val rig = rig(FakeSkills("no front matter at all"))

        rig.wakeAndFinal("你好")

        assertEquals(1, rig.model.requests.size)
        assertTrue("SKILL:" !in rig.model.requests.single().instructions)
    }

    @Test
    fun `a disabled skill is not injected`() {
        val rig = rig(
            FakeSkills("---\nname: off\nversion: 1.0.0\ndescription: d\nenabled: false\n---\n不该出现\n"),
        )

        rig.wakeAndFinal("你好")

        assertTrue("SKILL:" !in rig.model.requests.single().instructions)
    }

    @Test
    fun `a directory the host refuses leaves the system prompt alone`() {
        // The grant being refused and the directory being empty end in the same place, which is the honest place:
        // the assistant answers from its system prompt rather than failing the turn.
        val rig = SmartTestRig()
        rig.activate()
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }

        rig.wakeAndFinal("你好")

        assertEquals(1, rig.model.requests.size)
    }
}
