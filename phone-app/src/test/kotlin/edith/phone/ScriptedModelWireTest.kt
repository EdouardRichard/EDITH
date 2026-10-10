package edith.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The arming state machine behind [ScriptedModelWire], pinned without OkHttp.
 *
 * Why this instrument exists: §17.8's Stage C acceptance needs an **L3/L4 tool call** to reach the real policy, the
 * real confirmation gate, the real privileged executor and the real audit. On the K40 the configured model refused to
 * emit one (four attempts, all `steps=0`, including a diagnostic whose argument transcribed cleanly), so the
 * destructive half of the phase could not be exercised at all. Scripting the **transport** removes the model's
 * willingness from the question while leaving everything else in the path untouched.
 *
 * The two properties that matter here:
 * - **the shape is the proven Green one** (`response.created` → `output_item.added` → arguments delta →
 *   `output_item.done` → `response.completed`, as `model-runtime`'s `responses/tool-call-completed.sse` carries it),
 *   because the decoder treats a missing terminal as a refusal rather than a tool call;
 * - **while it is armed it never falls through** to the real transport. A scripted run must not be able to leak a
 *   transcript to the network as a side effect of the script running out.
 */
class ScriptedModelWireTest {

    private fun wire() = ScriptedModelWire(log = { })

    @Test fun `an unarmed wire has nothing to serve, so the real transport runs`() {
        assertNull(wire().nextAnswer())
    }

    @Test fun `an armed wire serves the scripted tool call, then the closing text, and never falls through`() {
        val subject = wire()
        assertTrue(
            subject.arm(
                action = "uninstall",
                argsJson = """{"package":"edith.phone.a11yprobe"}""",
                text = "已处理",
            ),
        )

        val first = subject.nextAnswer()
        assertNotNull("the first request must be answered by the script", first)
        val toolCall = requireNotNull(first).body
        assertTrue("the tool call must be a complete stream", toolCall.contains("response.completed"))
        assertTrue("the terminal item must name the action", toolCall.contains(""""name":"uninstall""""))
        assertTrue("the arguments must carry the scripted target", toolCall.contains("edith.phone.a11yprobe"))
        assertTrue(
            "the item must be closed before the terminal, like the proven wire shape",
            toolCall.indexOf("response.output_item.done") < toolCall.indexOf("response.completed"),
        )

        val second = subject.nextAnswer()
        assertNotNull("the loop's second request must still be answered by the script", second)
        val text = requireNotNull(second).body
        assertTrue(text.contains("response.output_text.delta"))
        assertFalse("a text answer must not carry a tool call", text.contains("function_call"))

        val third = subject.nextAnswer()
        assertNotNull("while armed the wire must never fall through to the network", third)
        assertTrue(requireNotNull(third).body.contains("response.output_text.delta"))
    }

    @Test fun `re-arming replaces the pending script and starts the tool call over`() {
        val subject = wire()
        subject.arm("uninstall", """{"package":"edith.phone.a11yprobe"}""", "第一")
        assertTrue(requireNotNull(subject.nextAnswer()).body.contains("uninstall"))

        subject.arm("disable_app", """{"package":"edith.phone.a11yprobe"}""", "第二")
        val afterRearm = requireNotNull(subject.nextAnswer()).body
        assertTrue("a re-arm must serve its own tool call first again", afterRearm.contains("disable_app"))
        assertFalse("and must not serve the previous action", afterRearm.contains(""""name":"uninstall""""))
    }

    @Test fun `an empty action is refused rather than arming a wire that can answer nothing`() {
        val subject = wire()
        assertFalse(subject.arm("  ", "{}", "文本"))
        assertNull(subject.nextAnswer())
    }

    @Test fun `disarming hands the transport back`() {
        val subject = wire()
        subject.arm("uninstall", """{"package":"edith.phone.a11yprobe"}""", "文本")
        assertNotNull(subject.nextAnswer())
        subject.disarm()
        assertNull("a disarmed wire must let the real transport run again", subject.nextAnswer())
    }

    @Test fun `the served stream frames are separated by blank lines, which is what the decoder dispatches on`() {
        val subject = wire()
        subject.arm("clear_data", """{"package":"edith.phone.a11yprobe"}""", "文本")
        val body = requireNotNull(subject.nextAnswer()).body
        assertTrue("frames are terminated by a blank line", body.contains("\n\n"))
        assertTrue("each frame carries its event name", body.contains("event: response.output_item.done"))
        assertTrue("and its data line", body.contains("data: {"))
        assertEquals("the stream ends with a terminator, not a dangling frame", "\n\n", body.takeLast(2))
    }

    @Test fun `every arming gets its own call id, so a second answer is not de-duplicated away`() {
        val subject = wire()
        subject.arm("uninstall", """{"package":"edith.phone.a11yprobe"}""", "一")
        val first = requireNotNull(subject.nextAnswer()).body
        subject.arm("uninstall", """{"package":"edith.phone.a11yprobe"}""", "二")
        val second = requireNotNull(subject.nextAnswer()).body

        val firstId = Regex("\"call_id\":\"([^\"]+)\"").find(first)?.groupValues?.get(1)
        val secondId = Regex("\"call_id\":\"([^\"]+)\"").find(second)?.groupValues?.get(1)
        assertNotNull(firstId)
        assertNotNull(secondId)
        assertNotEquals(
            "the host de-duplicates on (session, generation, eventId) and the answer's eventId is the call id, " +
                "so a reused id makes every answer after the first look like a duplicate and be dropped",
            firstId,
            secondId,
        )
    }

    @Test fun `the arguments field decodes to exactly the arguments object`() {
        val subject = wire()
        subject.arm("set_permission", """{"package":"p","permission":"q","granted":false}""", "文本")
        val body = requireNotNull(subject.nextAnswer()).body
        val item = requireNotNull(
            Regex("event: response\\.output_item\\.done\\ndata: (\\{.*\\})").find(body)?.groupValues?.get(1),
        ) { "the terminal item frame must be present" }
        val arguments = Json.parseToJsonElement(item).jsonObject["item"]!!.jsonObject["arguments"]!!
            .jsonPrimitive.content
        assertEquals(
            "the wire must carry the arguments as a JSON string, not as an escaped blob",
            """{"package":"p","permission":"q","granted":false}""",
            arguments,
        )
    }

    @Test fun `the arguments are built from separate extras, so no shell quoting can break them`() {
        assertEquals(
            """{"package":"edith.phone.a11yprobe"}""",
            scriptedToolCallArgs(packageName = "edith.phone.a11yprobe", permission = null, granted = null, value = null),
        )
        assertEquals(
            """{"package":"edith.phone.a11yprobe","permission":"android.permission.POST_NOTIFICATIONS","granted":false}""",
            scriptedToolCallArgs(
                packageName = "edith.phone.a11yprobe",
                permission = "android.permission.POST_NOTIFICATIONS",
                granted = false,
                value = null,
            ),
        )
        assertEquals(
            """{"value":150}""",
            scriptedToolCallArgs(packageName = null, permission = null, granted = null, value = 150),
        )
        assertEquals(
            "an action with no declared members still needs a well-formed object",
            "{}",
            scriptedToolCallArgs(packageName = null, permission = null, granted = null, value = null),
        )
    }
}
