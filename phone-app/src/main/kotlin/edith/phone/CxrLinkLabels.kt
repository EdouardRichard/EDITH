package edith.phone

/**
 * What the glasses-connection area of 会话 may say, and what the SDK's authorization result is allowed to say.
 *
 * Owner-reported defect (2026-10-10): every tap printed "已请求连接眼镜，请在 AI App 中确认授权" - after authorization had
 * long been granted. The sentence was a **guess dressed as a state**, so the wearer kept waiting for a prompt that never
 * came. The page now reports only what it knows: whether this build has a transport, whether the user asked to connect,
 * and whether the transport has reported a live session.
 *
 * [linkReady] is `HostCompositionRoot.linkReady` (the SDK's live-session signal, delivered once) — a property of **this
 * process**, not a live heartbeat: no disconnect callback is wired (spec 不做/未测). The wording therefore never says
 * 在线, exactly as it never says 授权.
 */
internal fun cxrStatusLabel(transportMissing: Boolean, requested: Boolean, linkReady: Boolean): String = when {
    // The strongest fact wins: a live session outranks a stale "no transport" flag.
    linkReady -> "已连接眼镜"
    transportMissing && requested -> "眼镜连接未装配（TRANSPORT_MISSING）"
    requested -> "已请求，等待就绪…"
    else -> "未连接"
}

/**
 * The SDK's authorization result in the wearer's words. A cancellation is not an error: the user declining a prompt is a
 * legitimate answer, and only an unrecognised code is reported as unexpected.
 */
internal fun cxrAuthorizationLabel(outcome: CxrAuthorizationOutcome): String = when (outcome) {
    CxrAuthorizationOutcome.CONFIRMED -> "眼镜授权确认已完成"
    CxrAuthorizationOutcome.CANCELLED -> "眼镜授权被取消"
    CxrAuthorizationOutcome.UNEXPECTED -> "眼镜授权结果异常"
}

/** The three shapes the SDK's authorization result can take, as far as the phone can tell. */
enum class CxrAuthorizationOutcome { CONFIRMED, CANCELLED, UNEXPECTED }
