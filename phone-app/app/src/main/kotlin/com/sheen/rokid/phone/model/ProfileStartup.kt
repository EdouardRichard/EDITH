package com.sheen.rokid.phone.model

import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile

/**
 * 0.25 Task 14 (R74): the session-start gate for the assistant.
 *
 * The product sends TEXT + IMAGE; a profile that does not declare `IMAGE` is refused with
 * `PROFILE_MODEL_NOT_MULTIMODAL` and must be replaced, never silently downgraded to text-only. An absent profile is a
 * different failure (the host's capability/config path), so it is not this check's answer.
 *
 * 0.52: the type is derived from the catalog projection, so this remains the one start gate regardless of how the
 * profile was configured (design §3.1 模型服务). It lives here rather than beside the deleted legacy profile form.
 */
internal fun profileStartupError(profile: ModelProfile?): String? =
    if (profile != null && InputModality.IMAGE !in profile.inputs) "PROFILE_MODEL_NOT_MULTIMODAL" else null
