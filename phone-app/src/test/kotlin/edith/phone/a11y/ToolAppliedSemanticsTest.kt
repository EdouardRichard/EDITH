package edith.phone.a11y

import edith.phone.actions.ExecutionBackend
import edith.phone.actions.ExecutionRouter
import edith.plugin.ActionDescriptor
import edith.plugin.ActionOutcome
import edith.plugin.ActionResult
import edith.plugin.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * T1.4 — Applied semantics verification layer (AC-10).
 *
 * Every action-class tool must return `applied` information indicating whether the screen state **actually changed**.
 * The [AppliedSemanticsPostProcessor] wraps an [ExecutionRouter] and enriches every result with an explicit
 * `[applied=true]` or `[applied=false]` prefix in the detail, so the model never has to infer applied status from
 * outcome codes alone.
 *
 * Four cases are pinned:
 * 1. Backend returns Done but fingerprint unchanged → reclassified as Refused(NO_SCREEN_CHANGE) with applied=false
 * 2. Fingerprint changed → applied=true
 * 3. Channel unavailable → stays ACTION_CHANNEL_UNAVAILABLE, NOT mixed into applied=false
 * 4. Shizuku-style state check without screen change → reports its own outcome correctly; the post-processor
 *    only applies screen-fingerprint checking to accessibility actions
 */
class ToolAppliedSemanticsTest {

    // ── Fakes ────────────────────────────────────────────────────────────────

    /**
     * A fake [AccessibilityUi] whose fingerprint can be controlled per-call.
     * The [fingerprintSequence] is consumed in order; once exhausted, the last value repeats.
     */
    private class FakeUi(
        private val clickable: Boolean = true,
        private val fingerprintSequence: List<String> = listOf("screen-a", "screen-b"),
    ) : AccessibilityUi {
        val clicks = mutableListOf<String>()
        val typed = mutableListOf<String>()
        private var fingerprintCalls = 0

        override fun clickText(text: String): Boolean {
            clicks += text
            return clickable
        }

        override fun setText(text: String): Boolean {
            typed += text
            return clickable
        }

        override fun screenFingerprint(): String {
            val index = fingerprintCalls.coerceAtMost(fingerprintSequence.lastIndex)
            fingerprintCalls++
            return fingerprintSequence[index]
        }

        override fun snapshot(filter: ScreenFilter): ScreenSnapshot =
            throw UnsupportedOperationException("not needed for applied semantics tests")

        override fun clickByIndex(snapshotId: String, index: Int): Boolean = false
        override fun longPressByIndex(snapshotId: String, index: Int): Boolean = false
        override fun scroll(direction: ScrollDirection): Boolean = false
        override fun setTextByIndex(snapshotId: String, index: Int, text: String): Boolean = false
        override fun structuralFingerprint(): ScreenFingerprint =
            ScreenFingerprint(hash = "", nodeCount = 0, clickableCount = 0, truncated = false)
    }

    /**
     * A fake [ExecutionBackend] that returns a fixed [ActionResult] for any action.
     * Used to simulate Shizuku-style backends that use state checks instead of screen fingerprints.
     */
    private class FixedResultBackend(
        override val id: String,
        private val result: ActionResult,
        private val actionNames: List<String> = listOf("clear_data"),
        private val isAvailable: Boolean = true,
    ) : ExecutionBackend {
        override fun available(): Boolean = isAvailable

        override fun descriptors(): List<ActionDescriptor> = actionNames.map { name ->
            ActionDescriptor(
                name = name,
                title = "Test action $name",
                parametersJson = """{"type":"object","properties":{"package":{"type":"string"}},"required":["package"],"additionalProperties":false}""",
                risk = RiskLevel.L3,
                requiresConfirmation = false,
                idempotent = false,
                reversible = false,
                scope = "app",
                auditTargetArgument = "package",
            )
        }

        override fun execute(name: String, argsJson: String): ActionResult = result
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun accessibilityBackend(
        ui: AccessibilityUi,
        connected: Boolean = true,
    ) = AccessibilityExecutionBackend(ui = ui, connected = { connected })

    private fun router(vararg backends: ExecutionBackend) = ExecutionRouter(backends.toList())

    private fun postProcessor(router: ExecutionRouter) =
        AppliedSemanticsPostProcessor(router)

    // ── Test Case 1: Backend returns Done ⇒ applied=true (trust the backend) ──

    @Test
    fun `backend Done result gets applied=true enrichment`() {
        // The accessibility backend already does fingerprint verification internally.
        // When it returns Done, the screen DID change. The post-processor trusts this
        // and adds the explicit applied=true prefix.
        val ui = FakeUi(fingerprintSequence = listOf("screen-a", "screen-b"))
        val backend = accessibilityBackend(ui)
        val r = router(backend)
        val processor = postProcessor(r)

        val result = processor.execute("tap_text", """{"text":"WLAN"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue(
            "detail must contain applied=true: ${result.detail}",
            result.detail.contains("[applied=true]"),
        )
    }

    @Test
    fun `non-accessibility backend Done gets applied=true without fingerprint check`() {
        // A backend that returns Done for a non-accessibility action (e.g., Shizuku-style)
        // should get applied=true without any screen fingerprint verification.
        val alwaysDoneBackend = object : ExecutionBackend {
            override val id = "fake-state-backend"
            override fun available() = true
            override fun descriptors() = listOf(
                ActionDescriptor(
                    name = "clear_data",
                    title = "Clear data",
                    parametersJson = """{"type":"object","properties":{"package":{"type":"string"}},"required":["package"],"additionalProperties":false}""",
                    risk = RiskLevel.L3,
                    requiresConfirmation = false,
                    idempotent = false,
                    reversible = false,
                    scope = "app",
                    auditTargetArgument = "package",
                )
            )
            override fun execute(name: String, argsJson: String) =
                ActionResult(ActionOutcome.Done, "cleared com.example")
        }

        val r = router(alwaysDoneBackend)
        val processor = postProcessor(r)

        val result = processor.execute("clear_data", """{"package":"com.example"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue(
            "detail must contain applied=true: ${result.detail}",
            result.detail.contains("[applied=true]"),
        )
    }

    // ── Test Case 2: Fingerprint changed ⇒ applied=true ──

    @Test
    fun `fingerprint changed means applied=true`() {
        val ui = FakeUi(fingerprintSequence = listOf("screen-before", "screen-after"))
        val backend = accessibilityBackend(ui)
        val r = router(backend)
        val processor = postProcessor(r)

        val result = processor.execute("tap_text", """{"text":"WLAN"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue(
            "detail must contain applied=true: ${result.detail}",
            result.detail.contains("[applied=true]"),
        )
    }

    @Test
    fun `type text with screen change reports applied=true`() {
        val ui = FakeUi(fingerprintSequence = listOf("before", "after"))
        val backend = accessibilityBackend(ui)
        val r = router(backend)
        val processor = postProcessor(r)

        val result = processor.execute("type_text", """{"text":"hello"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue(
            "detail must contain applied=true: ${result.detail}",
            result.detail.contains("[applied=true]"),
        )
    }

    // ── Test Case 3: Channel unavailable ⇒ still ACTION_CHANNEL_UNAVAILABLE ──

    @Test
    fun `channel unavailable stays ACTION_CHANNEL_UNAVAILABLE not applied=false`() {
        val ui = FakeUi()
        val backend = accessibilityBackend(ui, connected = false)
        val r = router(backend)
        val processor = postProcessor(r)

        val result = processor.execute("tap_text", """{"text":"WLAN"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue(
            "must remain ACTION_CHANNEL_UNAVAILABLE: ${result.detail}",
            result.detail.contains("ACTION_CHANNEL_UNAVAILABLE"),
        )
        assertFalse(
            "must NOT be reclassified to NO_SCREEN_CHANGE: ${result.detail}",
            result.detail.contains("NO_SCREEN_CHANGE"),
        )
    }

    @Test
    fun `shizuku channel unavailable stays ACTION_CHANNEL_UNAVAILABLE`() {
        val shizukuLike = FixedResultBackend(
            id = "shizuku",
            result = ActionResult(ActionOutcome.Refused, "ACTION_CHANNEL_UNAVAILABLE"),
            isAvailable = false,
        )
        val r = router(shizukuLike)
        val processor = postProcessor(r)

        val result = processor.execute("clear_data", """{"package":"com.example"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue(
            "must remain ACTION_CHANNEL_UNAVAILABLE: ${result.detail}",
            result.detail.contains("ACTION_CHANNEL_UNAVAILABLE"),
        )
    }

    // ── Test Case 4: Shizuku-style state check without screen change ──

    @Test
    fun `shizuku Done via state check reports applied=true without screen fingerprint`() {
        // Shizuku actions use state checks (pm clear succeeded, package is gone, etc.)
        // not screen fingerprints. When they report Done, that IS the applied truth.
        val shizukuLike = FixedResultBackend(
            id = "shizuku",
            result = ActionResult(ActionOutcome.Done, "cleared com.example"),
        )
        val r = router(shizukuLike)
        // No accessibility UI available — post-processor should pass through
        val processor = postProcessor(r)

        val result = processor.execute("clear_data", """{"package":"com.example"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue(
            "Shizuku Done must carry applied=true: ${result.detail}",
            result.detail.contains("[applied=true]"),
        )
    }

    @Test
    fun `shizuku STATE_UNCHANGED reports applied=false`() {
        val shizukuLike = FixedResultBackend(
            id = "shizuku",
            result = ActionResult(ActionOutcome.Failed, "STATE_UNCHANGED"),
        )
        val r = router(shizukuLike)
        val processor = postProcessor(r)

        val result = processor.execute("clear_data", """{"package":"com.example"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertTrue(
            "STATE_UNCHANGED must carry applied=false: ${result.detail}",
            result.detail.contains("[applied=false]"),
        )
    }

    // ── Edge cases ───────────────────────────────────────────────────────────

    @Test
    fun `node not clickable stays NODE_NOT_CLICKABLE with applied=false`() {
        val ui = FakeUi(clickable = false)
        val backend = accessibilityBackend(ui)
        val r = router(backend)
        val processor = postProcessor(r)

        val result = processor.execute("tap_text", """{"text":"WLAN"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue(
            "must remain NODE_NOT_CLICKABLE: ${result.detail}",
            result.detail.contains("NODE_NOT_CLICKABLE"),
        )
        assertTrue(
            "must carry applied=false: ${result.detail}",
            result.detail.contains("[applied=false]"),
        )
    }

    @Test
    fun `unknown action stays UNKNOWN_ACTION with applied=false`() {
        val ui = FakeUi()
        val backend = accessibilityBackend(ui)
        val r = router(backend)
        val processor = postProcessor(r)

        val result = processor.execute("nonexistent_action", "{}")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue(
            "must carry applied=false: ${result.detail}",
            result.detail.contains("[applied=false]"),
        )
    }

    @Test
    fun `arguments invalid stays ARGUMENTS_INVALID with applied=false`() {
        val ui = FakeUi()
        val backend = accessibilityBackend(ui)
        val r = router(backend)
        val processor = postProcessor(r)

        val result = processor.execute("tap_text", """{"text":""}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue(
            "must remain ARGUMENTS_INVALID: ${result.detail}",
            result.detail.contains("ARGUMENTS_INVALID"),
        )
        assertTrue(
            "must carry applied=false: ${result.detail}",
            result.detail.contains("[applied=false]"),
        )
    }

    @Test
    fun `post-processor preserves descriptors from underlying router`() {
        val ui = FakeUi()
        val backend = accessibilityBackend(ui)
        val r = router(backend)
        val processor = postProcessor(r)

        val descriptors = processor.descriptors()

        assertEquals(2, descriptors.size)
        assertEquals(setOf("tap_text", "type_text"), descriptors.map { d -> d.name }.toSet())
    }

    @Test
    fun `post-processor delegates cancel to underlying router`() {
        var cancelled = false
        val cancelTrackingBackend = object : ExecutionBackend {
            override val id = "tracking"
            override fun available() = true
            override fun descriptors() = emptyList<ActionDescriptor>()
            override fun execute(name: String, argsJson: String) =
                ActionResult(ActionOutcome.Done, "ok")
            override fun cancel() { cancelled = true }
        }
        val r = router(cancelTrackingBackend)
        val processor = postProcessor(r)

        processor.cancel()

        assertTrue("cancel must be delegated", cancelled)
    }

    @Test
    fun `accessibility backend Done is trusted as applied=true`() {
        // The post-processor trusts the backend's verdict. The accessibility backend
        // already verified via fingerprint that the screen changed before returning Done.
        val ui = FakeUi(fingerprintSequence = listOf("a", "b"))
        val backend = accessibilityBackend(ui)
        val r = router(backend)
        val processor = postProcessor(r)

        val result = processor.execute("tap_text", """{"text":"WLAN"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertTrue(
            "Backend Done must carry applied=true: ${result.detail}",
            result.detail.contains("[applied=true]"),
        )
    }

    @Test
    fun `Refused outcome always gets applied=false`() {
        val refusedBackend = FixedResultBackend(
            id = "test",
            result = ActionResult(ActionOutcome.Refused, "SOME_REFUSAL"),
        )
        val r = router(refusedBackend)
        val processor = postProcessor(r)

        val result = processor.execute("clear_data", """{"package":"com.example"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue(
            "Refused must carry applied=false: ${result.detail}",
            result.detail.contains("[applied=false]"),
        )
    }

    @Test
    fun `Failed outcome always gets applied=false`() {
        val failedBackend = FixedResultBackend(
            id = "test",
            result = ActionResult(ActionOutcome.Failed, "SOME_FAILURE"),
        )
        val r = router(failedBackend)
        val processor = postProcessor(r)

        val result = processor.execute("clear_data", """{"package":"com.example"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        assertTrue(
            "Failed must carry applied=false: ${result.detail}",
            result.detail.contains("[applied=false]"),
        )
    }

    @Test
    fun `accessibility backend already returning NO_SCREEN_CHANGE gets applied=false prefix`() {
        // The accessibility backend already returns Refused(NO_SCREEN_CHANGE) when fingerprint
        // doesn't change. The post-processor must add the applied=false prefix without
        // double-refusing or losing the original code.
        val ui = FakeUi(fingerprintSequence = listOf("same", "same"))
        val backend = accessibilityBackend(ui)
        val r = router(backend)
        val processor = postProcessor(r)

        val result = processor.execute("tap_text", """{"text":"WLAN"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertTrue(
            "must contain NO_SCREEN_CHANGE: ${result.detail}",
            result.detail.contains("NO_SCREEN_CHANGE"),
        )
        assertTrue(
            "must contain applied=false: ${result.detail}",
            result.detail.contains("[applied=false]"),
        )
    }

    @Test
    fun `multiple backends coexist with correct applied semantics`() {
        // Accessibility backend with screen change + Shizuku backend with state check
        val ui = FakeUi(fingerprintSequence = listOf("before", "after"))
        val a11yBackend = accessibilityBackend(ui)
        val shizukuBackend = FixedResultBackend(
            id = "shizuku",
            result = ActionResult(ActionOutcome.Done, "cleared com.test"),
            actionNames = listOf("clear_data"),
        )
        val r = router(a11yBackend, shizukuBackend)
        val processor = postProcessor(r)

        // Accessibility action
        val tapResult = processor.execute("tap_text", """{"text":"Settings"}""")
        assertEquals(ActionOutcome.Done, tapResult.outcome)
        assertTrue("a11y applied=true: ${tapResult.detail}", tapResult.detail.contains("[applied=true]"))

        // Shizuku action
        val clearResult = processor.execute("clear_data", """{"package":"com.test"}""")
        assertEquals(ActionOutcome.Done, clearResult.outcome)
        assertTrue("shizuku applied=true: ${clearResult.detail}", clearResult.detail.contains("[applied=true]"))
    }
}
