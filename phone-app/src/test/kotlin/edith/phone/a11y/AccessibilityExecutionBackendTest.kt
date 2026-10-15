package edith.phone.a11y

import edith.phone.actions.ExecutionRouter
import edith.plugin.ActionOutcome
import edith.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The accessibility channel (spec §17.2/§17.3): cross-app semantic UI operations, whose success criterion comes from
 * P0's T3b probe.
 *
 * That probe's two hard-won rules are pinned here: a click must aim at the **nearest clickable ancestor** of the node
 * that carries the text (the label itself returns `false` from `ACTION_CLICK`), and success is **the screen changing**,
 * not the action being accepted. "Mock 或编译通过不得当作实机通过证据" - and the same rule cuts the other way: an action
 * that returns `true` while nothing moved is a failed action.
 */
class AccessibilityExecutionBackendTest {

    private class FakeUi(
        private val clickable: Boolean = true,
        private val fingerprintBefore: String = "screen-a",
        private val fingerprintAfter: String = "screen-b",
    ) : AccessibilityUi {
        val clicks = mutableListOf<String>()
        val typed = mutableListOf<String>()
        var fingerprints = 0
        override fun clickText(text: String): Boolean {
            clicks += text
            return clickable
        }
        override fun setText(text: String): Boolean {
            typed += text
            return clickable
        }
        override fun screenFingerprint(): String {
            fingerprints++
            return if (fingerprints == 1) fingerprintBefore else fingerprintAfter
        }
        // T1.3 extended methods — not exercised by legacy backend tests; stubs keep compilation green.
        override fun snapshot(filter: ScreenFilter): ScreenSnapshot =
            throw UnsupportedOperationException("legacy fake")
        override fun clickByIndex(snapshotId: String, index: Int): Boolean = false
        override fun longPressByIndex(snapshotId: String, index: Int): Boolean = false
        override fun scroll(direction: ScrollDirection): Boolean = false
        override fun setTextByIndex(snapshotId: String, index: Int, text: String): Boolean = false
        override fun structuralFingerprint(): ScreenFingerprint =
            ScreenFingerprint(hash = "", nodeCount = 0, clickableCount = 0, truncated = false)
    }

    private fun backend(ui: AccessibilityUi, connected: Boolean = true) =
        AccessibilityExecutionBackend(ui = ui, connected = { connected })

    @Test fun `tap text is a success only when the screen actually changed`() {
        val ui = FakeUi(fingerprintAfter = "screen-a")

        val result = backend(ui).execute("tap_text", """{"text":"WLAN"}""")

        assertEquals(listOf("WLAN"), ui.clicks)
        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NO_SCREEN_CHANGE", result.detail)
    }

    @Test fun `tap text succeeds when the screen changed`() {
        val ui = FakeUi()

        val result = backend(ui).execute("tap_text", """{"text":"WLAN"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals("tapped WLAN", result.detail)
    }

    @Test fun `type text is judged the same way`() {
        val stale = backend(FakeUi(fingerprintAfter = "screen-a")).execute("type_text", """{"text":"hello"}""")
        assertEquals(ActionOutcome.Refused, stale.outcome)
        assertEquals("NO_SCREEN_CHANGE", stale.detail)

        val ok = backend(FakeUi()).execute("type_text", """{"text":"hello"}""")
        assertEquals(ActionOutcome.Done, ok.outcome)
    }

    @Test fun `an unavailable service refuses instead of pretending`() {
        val ui = FakeUi()

        val result = backend(ui, connected = false).execute("tap_text", """{"text":"WLAN"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_CHANNEL_UNAVAILABLE", result.detail)
        assertTrue("a down service must not be driven", ui.clicks.isEmpty())
        assertTrue("a down channel must advertise nothing through the router",
            ExecutionRouter(listOf(backend(ui, connected = false))).descriptors().isEmpty())
    }

    @Test fun `neither action may record screen text in the audit trail`() {
        val byName = backend(FakeUi()).descriptors().associateBy { it.name }

        assertEquals(setOf("tap_text", "type_text"), byName.keys)
        byName.values.forEach { descriptor ->
            assertEquals("${descriptor.name} must be L2", RiskLevel.L2, descriptor.risk)
            assertEquals("${descriptor.name} scope", "app.ui", descriptor.scope)
            // The argument *is* screen text, so recording it would be recording what the user saw.
            assertEquals("${descriptor.name} audit target", null, descriptor.auditTargetArgument)
        }
    }

    @Test fun `a blank or multi line text is refused`() {
        val ui = FakeUi()
        val backend = backend(ui)

        listOf("""{"text":""}""", """{"text":"two\nlines"}""", """{"text":"   "}""", "{}").forEach { args ->
            val result = backend.execute("tap_text", args)
            assertEquals("refused: $args", ActionOutcome.Refused, result.outcome)
            assertEquals("refused: $args", "ARGUMENTS_INVALID", result.detail)
        }
        assertTrue(ui.clicks.isEmpty())
    }

    @Test fun `an unknown action name is refused rather than executed`() {
        val ui = FakeUi()

        assertEquals(ActionOutcome.Refused, backend(ui).execute("tap_anything", "{}").outcome)
        assertTrue(ui.clicks.isEmpty())
    }

    @Test fun `the click result alone is not the verdict`() {
        // `clickText` answered `false` (the node was not clickable): whatever the screen does afterwards, this is a
        // failure, and the code says why.
        val ui = FakeUi(clickable = false)

        val result = backend(ui).execute("tap_text", """{"text":"WLAN"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NODE_NOT_CLICKABLE", result.detail)
        assertFalse(result.detail == "NO_SCREEN_CHANGE")
    }
}
