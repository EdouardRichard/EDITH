package edith.phone.a11y

import edith.phone.actions.ExecutionBackend
import edith.plugin.ActionDescriptor
import edith.plugin.ActionOutcome
import edith.plugin.ActionResult
import edith.plugin.RiskLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

private const val TAP_TEXT = "tap_text"
private const val TYPE_TEXT = "type_text"

private const val ARGUMENTS_INVALID = "ARGUMENTS_INVALID"
private const val UNKNOWN_ACTION = "UNKNOWN_ACTION"
private const val NODE_NOT_CLICKABLE = "NODE_NOT_CLICKABLE"
private const val NO_SCREEN_CHANGE = "NO_SCREEN_CHANGE"
private const val ACTION_CHANNEL_UNAVAILABLE = "ACTION_CHANNEL_UNAVAILABLE"

private const val TEXT = "text"

/** Bounded on purpose: this moves another app's UI, not a document-typing service. */
private const val MAX_TEXT_CHARS = 100

private val argumentsJson = Json { isLenient = true }

private val DESCRIPTORS: List<ActionDescriptor> = listOf(
    ActionDescriptor(
        name = TAP_TEXT,
        title = "点击界面上的文字",
        parametersJson = """{"type":"object","properties":{"text":{"type":"string"}},""" +
            """"required":["text"],"additionalProperties":false}""",
        risk = RiskLevel.L2,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "app.ui",
        // The argument *is* screen text; recording it would be recording what the user saw (spec §17.5).
        auditTargetArgument = null,
    ),
    ActionDescriptor(
        name = TYPE_TEXT,
        title = "在当前输入框输入文字",
        parametersJson = """{"type":"object","properties":{"text":{"type":"string"}},""" +
            """"required":["text"],"additionalProperties":false}""",
        risk = RiskLevel.L2,
        requiresConfirmation = false,
        idempotent = false,
        reversible = false,
        scope = "app.ui",
        auditTargetArgument = null,
    ),
)

/**
 * What an accessibility service can do here, as a port.
 *
 * The port exists because P0's T3b probe learned two things that must stay testable without a device: the node that
 * carries a text is usually **not** the clickable one (its ancestor is), and "the action returned true" is not success.
 * The Android implementation walks the real node tree; the JVM tests answer from a fake.
 */
interface AccessibilityUi {
    /** Clicks the nearest clickable ancestor of the first node carrying [text]. `false` when there is none. */
    fun clickText(text: String): Boolean

    /** Sets the text of the focused editable node. `false` when nothing focusable/editable is there. */
    fun setText(text: String): Boolean

    /** A digest of what is currently on screen, used only to compare before and after - never logged, never stored. */
    fun screenFingerprint(): String
}

/**
 * The accessibility channel: cross-app semantic UI operations (spec §17.2/§17.3).
 *
 * Success is **the screen changing**, not the action being accepted. T3b's first probe version judged success by
 * "the target text disappeared", which was false for a navigation whose destination page carried the same title; 20/20
 * real clicks were reported as failures. The fingerprint comparison is that correction.
 *
 * Two refusals are distinguished on purpose, because they mean different things to whoever reads the audit line:
 * [NODE_NOT_CLICKABLE] (the UI did not offer the action) and [NO_SCREEN_CHANGE] (the action was accepted and nothing
 * happened). Neither is a success, and neither is an exception.
 */
class AccessibilityExecutionBackend(
    private val ui: AccessibilityUi,
    private val connected: () -> Boolean,
) : ExecutionBackend {

    override val id = "accessibility"

    override fun available(): Boolean = runCatching { connected() }.getOrDefault(false)

    override fun descriptors(): List<ActionDescriptor> = DESCRIPTORS

    override fun execute(name: String, argsJson: String): ActionResult {
        if (!available()) return refused(ACTION_CHANNEL_UNAVAILABLE)
        val text = textArgument(argsJson) ?: return refused(ARGUMENTS_INVALID)
        val before = runCatching { ui.screenFingerprint() }.getOrDefault("")
        val accepted = when (name) {
            TAP_TEXT -> ui.clickText(text)
            TYPE_TEXT -> ui.setText(text)
            else -> return refused(UNKNOWN_ACTION)
        }
        if (!accepted) return refused(NODE_NOT_CLICKABLE)
        val after = runCatching { ui.screenFingerprint() }.getOrDefault("")
        return if (after != before) done(if (name == TAP_TEXT) "tapped $text" else "typed into the field")
        else refused(NO_SCREEN_CHANGE)
    }

    private fun textArgument(argsJson: String): String? {
        val args = runCatching { argumentsJson.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
            ?: return null
        val text = (args[TEXT] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull ?: return null
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TEXT_CHARS) return null
        if (trimmed.any { it == '\n' || it == '\r' }) return null
        return trimmed
    }

    private fun done(detail: String) = ActionResult(ActionOutcome.Done, detail)
    private fun refused(code: String) = ActionResult(ActionOutcome.Refused, code)
}
