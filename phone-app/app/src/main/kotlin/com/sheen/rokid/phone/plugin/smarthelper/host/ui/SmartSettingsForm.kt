package com.sheen.rokid.phone.plugin.smarthelper.host.ui
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings

/**
 * The settings screen's text ⇄ [SmartSettings] mapping (Task 14b).
 *
 * Pure by construction - no Android types - because it is the part of the settings entry point that must be
 * provable on the JVM: the Compose screen around it only holds the text fields and prints the errors this returns.
 *
 * Two rules shape the API:
 *
 * - **Every rejection is reported, and the valid fields still apply.** A user who mistypes one wake word must not
 *   lose the level they chose in the same edit, so [toSettings] returns a usable [SmartSettings] *and* the list of
 *   problems; nothing is silently coerced. The only field that keeps its previous value wholesale is the one whose
 *   entry was rejected (an unknown level, or a wake-word list where nothing survived validation).
 * - **The fields this form does not own survive.** `profileId` and `cooldownMillis` are not text fields here, so
 *   they are copied from `base` untouched.
 * - **The all-invalid asymmetry (deliberate, stated here so it is not mistaken for a bug).** When nothing in the
 *   wake-word field survives validation, the previous words are kept: wake words are not optional (the plugin
 *   refuses a configuration with an empty word list), so falling back is the only usable answer. When nothing in
 *   the whitelist field survives validation, the stored grants are replaced with **none**: every entry the user
 *   wrote was refused, and the safe direction for a permission list is fewer grants rather than the old ones the
 *   edit may have been trying to remove. Both cases report their errors.
 */
object SmartSettingsForm {

    /** The editable text, exactly as typed. */
    data class Draft(
        val wakeWords: String,
        val maxLevel: String,
        val whitelist: String,
        /** P3.2: the proactive cooldown in milliseconds, as typed; defaults to the shipped value like the level below. */
        val proactiveCooldown: String = "60000",
        /** P3.2: the sensitivity level; a set of three, so the screen edits it as a chooser. */
        val proactiveSensitivity: String = "LOW",
        /**
         * 0.24 S3 (spec §5.5): the 「持续对话」 switch. A boolean, not text, so there is nothing to parse and no
         * rejection to report - it is carried through exactly as the user set it.
         */
        val continueDialogue: Boolean = true,
        /**
         * 0.3 Phase 2 Task 10: the 「感知截图」 switch. A boolean like the switch above, carried through exactly as the
         * user set it; the host applies it to the live gate on a successful save.
         */
        val visionEnabled: Boolean = true,
        /**
         * 0.3 Phase 4B (`D189`): the 「不拦截」 switch. Like the switches above it is a boolean carried through exactly
         * as set; the screen owns the second confirmation that must happen before this can become `true`.
         */
        val noInterception: Boolean = false,
    )

    /**
     * The result of one edit.
     *
     * [settings] is always usable - the valid fields applied, the rejected ones taken from `base` - so a caller can
     * save it, while [errors] is what the UI must display.
     */
    data class Result(val settings: SmartSettings, val errors: List<String>)

    /** The longest a wake word may be; the minimum of 1 is what the non-blank check below already enforces. */
    private const val MAX_WORD_LENGTH = 16
    private val LEVELS = (0..4).map { "L$it" }

    /**
     * The proactive cooldown's bounds: 5 s is the shortest that is not a per-sentence retry, and 10 minutes is the
     * longest the wake path's own per-session horizon makes sense for.
     */
    private const val MIN_PROACTIVE_COOLDOWN_MILLIS = 5_000L
    private const val MAX_PROACTIVE_COOLDOWN_MILLIS = 600_000L

    fun fromSettings(settings: SmartSettings): Draft = Draft(
        wakeWords = settings.wakeWords.joinToString(","),
        maxLevel = settings.effectiveMaxLevel(),
        whitelist = settings.whitelist.joinToString(","),
        proactiveCooldown = settings.proactiveCooldownMillis.toString(),
        proactiveSensitivity = settings.effectiveProactiveSensitivity(),
        continueDialogue = settings.continueDialogue,
        visionEnabled = settings.visionEnabled,
        noInterception = settings.noInterception,
    )

    /**
     * Parses one edit against [base].
     *
     * Wake words are separated by half- or full-width commas, trimmed, and de-duplicated case-insensitively in the
     * user's order; each must be 1-16 characters and contain at least one letter or CJK character. The level is
     * normalised to upper case and must be `L0`..`L4`. A whitelist entry must be **exactly** `action|scope` with
     * both halves non-blank - the rule the plugin's own parser enforces ([parseWhitelist]) - and a rejected entry is
     * dropped while the well-formed ones are kept.
     */
    fun toSettings(draft: Draft, base: SmartSettings): Result = runCatching {
        val errors = mutableListOf<String>()

        val words = parseWakeWords(draft.wakeWords, errors)
        val level = parseLevel(draft.maxLevel, errors)
        val whitelist = parseWhitelist(draft.whitelist, errors)
        val proactiveCooldown = parseProactiveCooldown(draft.proactiveCooldown, errors)
        val proactiveSensitivity = parseProactiveSensitivity(draft.proactiveSensitivity, errors)

        Result(
            settings = base.copy(
                wakeWords = if (words.isEmpty()) base.wakeWords else words,
                maxLevel = level ?: base.effectiveMaxLevel(),
                whitelist = whitelist,
                proactiveCooldownMillis = proactiveCooldown ?: base.proactiveCooldownMillis,
                proactiveSensitivity = proactiveSensitivity ?: base.effectiveProactiveSensitivity(),
                continueDialogue = draft.continueDialogue,
                visionEnabled = draft.visionEnabled,
                noInterception = draft.noInterception,
                // 0.4 Task 5: host-only and owned by `DetachGate`, never edited by this form. It must be carried
                // through wholesale or a save of the form's own fields would silently change the audio/display mode.
                glassesDetached = base.glassesDetached,
            ),
            errors = errors,
        )
    }.getOrElse { failure ->
        // The parser is deliberately total over `String`, so this branch is unreachable in practice; it exists so a
        // future rule that does throw cannot take the settings screen down or save a half-parsed value.
        Result(base, listOf("设置解析失败：${failure.message ?: failure.javaClass.simpleName}"))
    }

    private fun parseWakeWords(text: String, errors: MutableList<String>): List<String> {
        val seen = mutableSetOf<String>()
        val accepted = mutableListOf<String>()
        text.split(',', '，').forEach { raw ->
            val word = raw.trim()
            if (word.isEmpty()) return@forEach
            if (word.length > MAX_WORD_LENGTH) {
                errors += "唤醒词「$word」超过 $MAX_WORD_LENGTH 个字符，未保存"
                return@forEach
            }
            if (!word.any { it.isLetter() }) {
                errors += "唤醒词「$word」至少需要包含一个字母或汉字，未保存"
                return@forEach
            }
            // Case-insensitive: "EDITH" and "edith" are the same wake word to every matcher downstream, and a
            // duplicate would make the plugin's word list disagree with its own lexicon.
            if (!seen.add(word.lowercase())) return@forEach
            accepted += word
        }
        if (accepted.isEmpty()) {
            errors += "唤醒词不能为空，至少填写一个（1-$MAX_WORD_LENGTH 个字符，需包含字母或汉字）"
        }
        return accepted
    }

    private fun parseLevel(text: String, errors: MutableList<String>): String? {
        val level = text.trim().uppercase()
        if (level !in LEVELS) {
            errors += "权限上限「${text.trim()}」不是有效级别，请选择 L0-L4"
            return null
        }
        return level
    }

    private fun parseWhitelist(text: String, errors: MutableList<String>): List<String> {
        val accepted = mutableListOf<String>()
        text.split(',', '，').forEach { raw ->
            val entry = raw.trim()
            if (entry.isEmpty()) return@forEach
            // The authority for this rule is `SmartAssistantPlugin.parseWhitelist` (plugins-smart): it requires
            // **exactly** two parts and throws, refusing the whole configuration, on anything else. The two modules
            // cannot share code, so this mirror must stay exact - accepting `a|b|c` here would let the screen store a
            // value the next activation rejects, taking the plugin's activation (and the passive loop) down with it.
            val parts = entry.split('|')
            if (parts.size != 2 || parts[0].isBlank() || parts[1].isBlank()) {
                errors += "白名单「$entry」格式应为 action|scope，未保存"
                return@forEach
            }
            accepted += entry
        }
        return accepted
    }

    /** The proactive cooldown must be a whole number of milliseconds inside the documented window. */
    private fun parseProactiveCooldown(text: String, errors: MutableList<String>): Long? {
        val millis = text.trim().toLongOrNull()
        if (millis == null || millis < MIN_PROACTIVE_COOLDOWN_MILLIS || millis > MAX_PROACTIVE_COOLDOWN_MILLIS) {
            errors += "主动辅助冷却需为 $MIN_PROACTIVE_COOLDOWN_MILLIS-$MAX_PROACTIVE_COOLDOWN_MILLIS 毫秒的整数，未保存"
            return null
        }
        return millis
    }

    /** The sensitivity is the plugin's three-level enum, normalised to its own spelling. */
    private fun parseProactiveSensitivity(text: String, errors: MutableList<String>): String? {
        val level = text.trim().uppercase()
        if (level !in SmartSettings.SENSITIVITIES) {
            errors += "敏感度「${text.trim()}」不是有效档位，请选择 LOW/MEDIUM/HIGH"
            return null
        }
        return level
    }
}
