package com.sheen.rokid.phone.actions.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * M1 (0.26): a tool call's shape may be logged, its content must not.
 */
class ToolArgsShapeTest {

    @Test
    fun `device logs action kind filter and flags but never the ref or text`() {
        val line = ToolArgsShape.describe(
            "device",
            """{"action":"act","kind":"tap","ref":"r7","text":"我的密码是 hunter2","filter":"all"}""",
        )

        assertEquals(
            "P3_TOOLARG tool=device action=act kind=tap filter=all hasRef=true textChars=13",
            line,
        )
        assertFalse(line.contains("r7"))
        assertFalse(line.contains("hunter2"))
    }

    @Test
    fun `shell logs a bounded command class and size but never the command`() {
        val line = ToolArgsShape.describe(
            "shell",
            """{"command":"input text secret-token","timeout_ms":5000}""",
        )

        assertEquals("P3_TOOLARG tool=shell commandClass=input chars=23 hasStdin=false timeoutMs=5000", line)
        assertFalse(line.contains("secret-token"))
        assertFalse(line.contains("input text"))
    }

    @Test
    fun `open logs target class and size but never the URL or package`() {
        val https = ToolArgsShape.describe("open", """{"target":"https://example.com/private?q=1"}""")
        val packageTarget = ToolArgsShape.describe("open", """{"target":"com.example.bank"}""")

        assertEquals("P3_TOOLARG tool=open targetClass=https chars=31 relaunch=false", https)
        assertEquals("P3_TOOLARG tool=open targetClass=package chars=16 relaunch=false", packageTarget)
        assertFalse(https.contains("example.com"))
        assertFalse(packageTarget.contains("com.example.bank"))
    }

    @Test
    fun `open list is a shape of its own`() {
        assertEquals("P3_TOOLARG tool=open shape=list", ToolArgsShape.describe("open", """{"list":true}"""))
    }

    @Test
    fun `dial logs only whether a number exists and its digit count`() {
        val line = ToolArgsShape.describe("dial", """{"number":"+86 138-0013-8000"}""")

        assertEquals("P3_TOOLARG tool=dial hasNumber=true digits=13", line)
        assertFalse(line.contains("138"))
    }

    @Test
    fun `malformed and unknown calls still produce a bounded shape`() {
        assertEquals("P3_TOOLARG tool=unknown", ToolArgsShape.describe("mystery", "{}"))
        assertEquals("P3_TOOLARG tool=device shape=invalid", ToolArgsShape.describe("device", "not-json"))
        assertEquals("P3_TOOLARG tool=shell shape=invalid", ToolArgsShape.describe("shell", """{"x":1}"""))
    }

    @Test
    fun `a command class is never an arbitrary first token`() {
        val line = ToolArgsShape.describe("shell", """{"command":"curl https://secret.example"}""")

        assertTrue(line.contains("commandClass=other"))
        assertFalse(line.contains("curl"))
        assertFalse(line.contains("secret.example"))
    }
}
