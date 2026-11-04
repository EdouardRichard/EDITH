package com.sheen.rokid.phone.ui.settings

import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.DetachUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsFeedback
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SmartSettingsForm

/*
 * 0.52 design §3.3/§4.2: the pure state machine behind the assistant behavior page.
 *
 * The composable is intentionally thin: it renders AssistantBehaviorState and forwards every user action to
 * AssistantBehaviorLogic. Keeping the transitions here has two payoffs that matter for this page:
 *
 * - the **proactive toggle is outside the form save** - AssistantBehaviorLogic.toggleProactive calls its own setter
 *   immediately and never touches the draft; the form's save is the separate AssistantBehaviorLogic.save;
 * - a **failed save keeps the draft** - the save only builds a temporary draft with the parsed cooldown and never
 *   commits it to the state unless the host reported `saved = true`.
 *
 * No Compose or Android types are imported, so the JVM test can drive the same transitions the UI runs.
 */

/** The user-visible copy that belongs to this page; the JVM test pins the exact strings. */
internal object AssistantBehaviorUi {

    /** The persistent note at the top of the page (0.52 design §3.3: 「需重启」 semantics in one place). */
    const val RESTART_NOTE = "带「需重启」的项会在完全关闭并重新打开 App 后生效。"

    /** The row badge on the four restart fields. */
    const val RESTART_CHIP = "需重启"

    /** The always-visible risk badge on the vision row. */
    const val SENSITIVE_BADGE = "敏感"

    /** The dirty-back confirmation's title and two answers (design §4.4). */
    const val DISCARD_TITLE = "放弃修改？"
    const val DISCARD_CONFIRM = "放弃"
    const val DISCARD_KEEP = "继续编辑"

    /** The form's explicit save button. */
    const val SAVE = "保存"

    /** A successful immediate write (the proactive switch) has nothing to restart. */
    const val PROACTIVE_SAVED = "已保存，立即生效"

    /**
     * The seconds-field error.
     *
     * The storage contract is milliseconds (5000-600000). The page edits seconds, so the rejection must talk about
     * the range in the unit the user typed while keeping the same bounds the form's parser enforces.
     */
    const val COOLDOWN_SECONDS_ERROR = "主动辅助冷却需为 5-600 秒的数字，未保存"
}

/**
 * One snapshot of the page.
 *
 * [base] is the last persisted snapshot the page knows about: the load at entry, updated after each successful
 * form save and after each successful proactive write. [draft] is what the text fields and switches show, and
 * [cooldownSeconds] is the one field whose UI unit differs from storage.
 */
internal data class AssistantBehaviorState(
    val base: SmartSettings,
    val draft: SmartSettingsForm.Draft,
    val cooldownSeconds: String,
    val proactiveChecked: Boolean,
    val validationErrors: List<String> = emptyList(),
    val result: SettingsSaveResult? = null,
    val restartCount: Int = 0,
    val proactiveResult: SettingsSaveResult? = null,
) {

    /** True while the form would write something different from [base] (also true for unparseable text). */
    val dirty: Boolean
        get() = draft != SmartSettingsForm.fromSettings(base) ||
            cooldownSeconds != AssistantBehaviorLogic.cooldownSecondsText(base.proactiveCooldownMillis)

    /** `D202`: the persisted switch stays visible but is not operable while the phone-only mode is on. */
    val proactiveOperable: Boolean get() = DetachUi.proactiveSwitchEnabled(base.glassesDetached)

    /** The form's result line, or null before a save attempt. */
    val saveMessage: String?
        get() = result?.let { result ->
            if (result.saved) SettingsFeedback.savedRestartText(restartCount) else SettingsFeedback.saveFailedText(result)
        }

    /** The immediate switch's result line; success is immediate by definition, failure is the mapped refusal. */
    val proactiveMessage: String?
        get() = proactiveResult?.let { result ->
            if (result.saved) AssistantBehaviorUi.PROACTIVE_SAVED else SettingsFeedback.saveFailedText(result)
        }
}

/** The pure transitions the page uses; see the file-level comment for why they are not inline in the composable. */
internal object AssistantBehaviorLogic {

    private const val MIN_COOLDOWN_MILLIS = 5_000L
    private const val MAX_COOLDOWN_MILLIS = 600_000L

    /** A fresh page: exactly one load at entry, normalized into the form's draft plus the seconds projection. */
    fun initial(base: SmartSettings): AssistantBehaviorState = AssistantBehaviorState(
        base = base,
        draft = SmartSettingsForm.fromSettings(base),
        cooldownSeconds = cooldownSecondsText(base.proactiveCooldownMillis),
        proactiveChecked = base.proactiveEnabled,
    )

    /**
     * Edits another text field or switch in the form.
     *
     * The previous validation/save result is cleared: a stale error must not outlive the input it was about (the same
     * rule the old settings section followed).
     */
    fun editDraft(state: AssistantBehaviorState, draft: SmartSettingsForm.Draft): AssistantBehaviorState =
        state.copy(draft = draft, validationErrors = emptyList(), result = null, restartCount = 0)

    /** Edits the seconds field; the conversion to storage happens only in [save], never on every keystroke. */
    fun editCooldownSeconds(state: AssistantBehaviorState, text: String): AssistantBehaviorState =
        state.copy(cooldownSeconds = text, validationErrors = emptyList(), result = null, restartCount = 0)

    /**
     * The immediate 「主动辅助」 switch.
     *
     * It is deliberately **not** routed through [save]: the arming gate takes effect at once and is persisted by
     * `ProactiveArming` on its own. A successful write updates [AssistantBehaviorState.base] so a later form save
     * carries the new persisted value instead of writing the old one back; a failed write leaves `base` alone (the
     * store still holds the old value) while the live switch follows the requested value, exactly like the gate.
     */
    fun toggleProactive(
        state: AssistantBehaviorState,
        enabled: Boolean,
        setProactive: (Boolean) -> SettingsSaveResult,
    ): AssistantBehaviorState {
        if (!state.proactiveOperable) return state
        val result = setProactive(enabled)
        return state.copy(
            base = if (result.saved) state.base.copy(proactiveEnabled = enabled) else state.base,
            proactiveChecked = enabled,
            proactiveResult = result,
        )
    }

    /**
     * The explicit form save.
     *
     * The seconds field is parsed first and converted to milliseconds before the form sees it, so the form's
     * millisecond contract and its 5000-600000 range remain the authority for every stored value. Validation errors
     * (ours or the form's) block the write: a half-valid form must not produce a "saved" line while an error is still
     * shown. The host's refusal is mapped through [SettingsFeedback] and, like a validation error, leaves the draft
     * untouched.
     */
    fun save(
        state: AssistantBehaviorState,
        save: (SmartSettings) -> SettingsSaveResult,
    ): AssistantBehaviorState {
        val millis = parseCooldownSeconds(state.cooldownSeconds)
            ?: return state.copy(
                validationErrors = listOf(AssistantBehaviorUi.COOLDOWN_SECONDS_ERROR),
                result = null,
                restartCount = 0,
            )

        val form = SmartSettingsForm.toSettings(
            state.draft.copy(proactiveCooldown = millis.toString()),
            state.base,
        )
        if (form.errors.isNotEmpty()) {
            return state.copy(validationErrors = form.errors, result = null, restartCount = 0)
        }

        val result = save(form.settings)
        if (!result.saved) {
            return state.copy(validationErrors = emptyList(), result = result, restartCount = 0)
        }

        val saved = form.settings
        return state.copy(
            base = saved,
            draft = SmartSettingsForm.fromSettings(saved),
            cooldownSeconds = cooldownSecondsText(saved.proactiveCooldownMillis),
            validationErrors = emptyList(),
            result = result,
            restartCount = SmartSettingsForm.needsRestartCount(state.base, saved),
        )
    }

    /**
     * Parses the seconds field into the stored milliseconds.
     *
     * Whole and fractional seconds are accepted (up to millisecond precision) so an existing stored value that is not
     * a whole second round-trips without silently changing; the stored range is exactly the form's
     * `5000..600000` ms. Anything else (blank, non-numeric, a negative value, or more than three decimals) is refused.
     */
    fun parseCooldownSeconds(text: String): Long? {
        val value = text.trim().toBigDecimalOrNull() ?: return null
        val millis = value.movePointRight(3)
        if (millis.stripTrailingZeros().scale() > 0) return null
        val exact = try {
            millis.longValueExact()
        } catch (_: ArithmeticException) {
            return null
        }
        return exact.takeIf { it in MIN_COOLDOWN_MILLIS..MAX_COOLDOWN_MILLIS }
    }

    /** The seconds projection of a stored millisecond value; whole seconds stay whole, fractions keep up to 3 digits. */
    fun cooldownSecondsText(millis: Long): String {
        val wholeSeconds = millis / 1_000L
        val remainder = millis % 1_000L
        if (remainder == 0L) return wholeSeconds.toString()
        val fraction = remainder.toString().padStart(3, '0').trimEnd('0')
        return if (fraction.isEmpty()) wholeSeconds.toString() else "$wholeSeconds.$fraction"
    }
}
