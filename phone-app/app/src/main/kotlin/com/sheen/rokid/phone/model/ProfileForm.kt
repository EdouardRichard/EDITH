package com.sheen.rokid.phone.model
import com.sheen.rokid.phone.ui.profile.domain

import com.sheen.rokid.phone.storage.opaqueSecret
import com.sheen.rokid.plugin.*
import java.math.BigDecimal

internal enum class ProfileField { NAME, SERVICE, BASE_URL, MODEL, PROTOCOL, API_KEY, MAX_INPUT, MAX_OUTPUT, TIMEOUT, INPUTS, OUTPUTS }
internal class ProfileFormException(val field: ProfileField, val code: String) : IllegalArgumentException(code)

data class ProfileForm(
    val name: String, val service: String, val baseUrl: String, val model: String, val protocolId: String,
    val maxInputK: String, val maxOutputK: String, val timeoutMillis: String,
    val inputs: Set<InputModality>, val outputs: Set<OutputModality>,
) {
    fun draft(id: String, previous: ModelProfile?, hasNewSecret: Boolean): ModelProfile {
        fun required(value: String, field: ProfileField, code: String): String = value.trim().also {
            if (it.isEmpty()) throw ProfileFormException(field, code)
        }
        val cleanName = required(name, ProfileField.NAME, "NAME_REQUIRED")
        val cleanService = required(service, ProfileField.SERVICE, "SERVICE_REQUIRED")
        val cleanUrl = required(baseUrl, ProfileField.BASE_URL, "BASE_URL_REQUIRED")
        try { domain(cleanUrl) } catch (_: Exception) { throw ProfileFormException(ProfileField.BASE_URL, "BASE_URL_INVALID") }
        val cleanModel = required(model, ProfileField.MODEL, "MODEL_REQUIRED")
        val cleanProtocol = required(protocolId, ProfileField.PROTOCOL, "PROTOCOL_REQUIRED")
        if (previous == null && !hasNewSecret) throw ProfileFormException(ProfileField.API_KEY, "API_KEY_REQUIRED")
        if (inputs.isEmpty()) throw ProfileFormException(ProfileField.INPUTS, "INPUTS_REQUIRED")
        if (outputs.isEmpty()) throw ProfileFormException(ProfileField.OUTPUTS, "OUTPUTS_REQUIRED")
        val capacity = parseKTokens(maxInputK, ProfileField.MAX_INPUT)
        val output = parseKTokens(maxOutputK, ProfileField.MAX_OUTPUT)
        val safety = previous?.safetyTokens ?: 256
        val allowance = capacity.toLong() - output.toLong() - safety.toLong()
        if (allowance <= 0) throw ProfileFormException(ProfileField.MAX_INPUT, "CAPACITY_OUTPUT_CONFLICT")
        val timeout = timeoutMillis.trim().toLongOrNull()?.takeIf { it > 0 }
            ?: throw ProfileFormException(ProfileField.TIMEOUT, "TIMEOUT_INVALID")
        // Changing only other fields must not silently relax a previously stricter allowance.
        val unchangedLimits = previous != null && capacity == previous.usableWindowTokens && output == previous.maxOutputTokens
        val budget = if (unchangedLimits) minOf(requireNotNull(previous).inputBudgetTokens, allowance.toInt()) else allowance.toInt()
        return ModelProfile(id, previous?.revision ?: 0, cleanName, cleanService, cleanUrl, cleanProtocol,
            cleanModel, previous?.secret ?: opaqueSecret("draft-placeholder"), capacity, budget, output, safety,
            timeout, inputs.toSet(), outputs.toSet(), emptyList())
    }

    companion object {
        /**
         * A new profile defaults to a **cloud-sized** window: 128 K in, 8 K out.
         *
         * The assistant's model is a cloud service; the on-device models (Laya, embedding, ASR) never answer the
         * assistant turn. The old 8 K/1 K defaults made the prompt budget so tight that a one-line hint summarised
         * skill bodies (2026-10-22 K70 acceptance), which is a configuration defect rather than a model limit. The
         * provider's real limit stays editable per profile.
         */
        const val DEFAULT_WINDOW_TOKENS = 131_072
        const val DEFAULT_OUTPUT_TOKENS = 8_192

        fun from(profile: ModelProfile?) = ProfileForm(
            profile?.name ?: "", profile?.service ?: "", profile?.baseUrl ?: "",
            profile?.model ?: "", profile?.protocolId ?: "DEEPSEEK_RESPONSES",
            formatKTokens(profile?.usableWindowTokens ?: DEFAULT_WINDOW_TOKENS),
            formatKTokens(profile?.maxOutputTokens ?: DEFAULT_OUTPUT_TOKENS),
            (profile?.timeoutMillis ?: 30000).toString(),
            profile?.inputs ?: setOf(InputModality.TEXT), profile?.outputs ?: setOf(OutputModality.TEXT))
    }
}

internal fun saveProfileDraft(draft: ModelProfile, enteredSecret: CharArray, save: (ModelProfile, CharArray?) -> Boolean): Boolean {
    val scoped = enteredSecret.takeIf { it.any { char -> !char.isWhitespace() } }?.copyOf()
    return try { save(draft, scoped).also { if (it) enteredSecret.fill('\u0000') } }
    catch (_: Exception) { false }
    finally { scoped?.fill('\u0000') }
}

internal fun formatKTokens(tokens: Int): String = BigDecimal(tokens).divide(BigDecimal(1024)).stripTrailingZeros().toPlainString()

private fun parseKTokens(value: String, field: ProfileField): Int {
    val text = value.trim()
    val code = if (field == ProfileField.MAX_INPUT) "MAX_INPUT_INVALID" else "MAX_OUTPUT_INVALID"
    if (text.length > 64 || !text.matches(Regex("(?:[0-9]+(?:\\.[0-9]+)?|\\.[0-9]+)"))) throw ProfileFormException(field, code)
    val tokens = try { BigDecimal(text).multiply(BigDecimal(1024)).intValueExact() }
    catch (_: ArithmeticException) { throw ProfileFormException(field, code) }
    if (tokens <= 0) throw ProfileFormException(field, code)
    return tokens
}

internal fun profileFormErrorMessage(code: String): String = when (code) {
    "NAME_REQUIRED" -> "名称：必填"
    "SERVICE_REQUIRED" -> "服务：必填"
    "BASE_URL_REQUIRED" -> "基础 URL：必填"
    "BASE_URL_INVALID" -> "基础 URL：须为 HTTPS 地址，不含用户名、密码或片段"
    "MODEL_REQUIRED" -> "模型：必填"
    "PROTOCOL_REQUIRED" -> "自定义协议 ID：必填"
    "API_KEY_REQUIRED" -> "API Key：新建档案必填"
    "INPUTS_REQUIRED" -> "声明输入：至少选择一项"
    "OUTPUTS_REQUIRED" -> "声明输出：至少选择一项"
    "PROFILE_MODEL_NOT_MULTIMODAL" -> "该档案未声明图片输入（IMAGE）；本版助手要求 IMAGE+TEXT，请更换支持图片的模型/档案"
    "MAX_INPUT_INVALID" -> "最大输入：须为正数 K tokens，精确对应整数 token 且不超过 2097151.9990234375 K tokens"
    "MAX_OUTPUT_INVALID" -> "最大输出：须为正数 K tokens，精确对应整数 token 且不超过 2097151.9990234375 K tokens"
    "CAPACITY_OUTPUT_CONFLICT" -> "最大输入容量不足：须大于最大输出与内部安全余量之和"
    "TIMEOUT_INVALID" -> "超时：须为正整数毫秒且不超过 9223372036854775807"
    else -> "保存未完成，请重试"
}

/**
 * 0.25 Task 14 (R74): the session-start gate for the assistant.
 *
 * The product sends TEXT + IMAGE; a profile that does not declare `IMAGE` is refused with
 * `PROFILE_MODEL_NOT_MULTIMODAL` and must be replaced, never silently downgraded to text-only. An absent profile is a
 * different failure (the host's capability/config path), so it is not this check's answer.
 */
internal fun profileStartupError(profile: ModelProfile?): String? =
    if (profile != null && InputModality.IMAGE !in profile.inputs) "PROFILE_MODEL_NOT_MULTIMODAL" else null
