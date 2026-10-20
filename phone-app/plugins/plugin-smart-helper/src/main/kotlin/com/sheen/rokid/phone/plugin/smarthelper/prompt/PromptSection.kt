package com.sheen.rokid.phone.plugin.smarthelper.prompt

/**
 * Where a prompt section is allowed to travel (spec §4.1).
 *
 * The kind is a placement contract, not a label:
 * - [STATIC] goes into the system prompt and must not contain any `{{slot}}`.
 * - [SESSION_STATIC] goes into the system prompt, is rendered once per session, and may only carry
 *   session-stable `DeviceProfile` values.
 * - [DYNAMIC] goes into the request tail and is re-rendered for every request.
 * - [PINNED] content is externally constrained (for example Laya's tuned question) and may only
 *   change after re-calibration.
 */
enum class PromptKind { STATIC, SESSION_STATIC, DYNAMIC, PINNED }

/**
 * One ordered, versioned piece of the prompt (spec §4.1).
 *
 * [id] is the manifest id, [kind] decides where the section may be placed, and [text] is already
 * loaded (and, for devices, already rendered) prompt content.
 */
data class PromptSection(
    val id: String,
    val kind: PromptKind,
    val text: String,
)
