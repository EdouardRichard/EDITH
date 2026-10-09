package edith.phone

/**
 * Why a capability row on 会话 reads 尚不可用, in the wearer's own words.
 *
 * The panel used to print a bare `尚不可用` for every entry and throw the reason code away, so a capability that is
 * **genuinely unwired by design** - `眼镜音频`, which the environment deliberately never advertises because the
 * delivery path carries transcripts rather than an audio subscription - looked exactly like a capability that had
 * simply not come up yet. Printing the reason is what tells those two apart without either advertising something the
 * App cannot answer or hiding a code from the person reading it.
 *
 * An unrecognised code is returned unchanged on purpose: a future reason must stay visible instead of being replaced
 * by a label that would quietly mean something else.
 */
internal fun capabilityReasonLabel(reason: String): String = when (reason) {
    "TRANSPORT_MISSING" -> "眼镜未连接"
    "ENGINE_MISSING" -> "本机引擎未装配"
    "MODEL_OPERATION_UNAVAILABLE" -> "模型档案不可用"
    "DISPLAY_MISSING" -> "眼镜显示未就绪"
    "SPEECH_OUTPUT_MISSING" -> "语音输出未装配"
    "HOST_STORAGE_PROVIDER_MISSING" -> "持久存储未装配"
    "ACTION_PROVIDER_MISSING" -> "指令执行未装配"
    "CONTEXT_PROVIDER_MISSING" -> "上下文感知未装配"
    "WAKE_PROVIDER_MISSING" -> "唤醒词未装配"
    else -> reason
}
