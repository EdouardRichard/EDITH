package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.transport.CxrAuthorizationOutcome

/**
 * What the glasses-connection area of 会话 may say, and what the SDK's authorization result is allowed to say.
 *
 * Owner-reported defect (2026-10-10): every tap printed "已请求连接眼镜，请在 AI App 中确认授权" - after authorization had
 * long been granted. The sentence was a **guess dressed as a state**, so the wearer kept waiting for a prompt that never
 * came. The page reports only what it knows.
 *
 * `D53`③ (2026-10-11) went further: the standalone 「连接眼镜 / 重连眼镜」 entry is gone, and 开始/停止/暂停 own the link.
 * There is therefore no "the user asked and we are waiting" state any more, which is why the `requested` half of the old
 * signature was removed rather than kept unused - a state the page can no longer reach must not stay wordable.
 *
 * [linkReady] is `HostCompositionRoot.linkReady` (the SDK's live-session signal, delivered once) — a property of **this
 * process**, not a live heartbeat: no disconnect callback is wired (spec 不做/未测). The wording therefore never says
 * 在线, exactly as it never says 授权.
 */
internal fun cxrStatusLabel(transportMissing: Boolean, linkReady: Boolean): String = when {
    // The strongest fact wins: a live session outranks a stale "no transport" flag.
    linkReady -> "已连接眼镜"
    transportMissing -> "眼镜连接未装配（TRANSPORT_MISSING）"
    else -> "未连接"
}

/**
 * The transport's verdict on the SDK's authorization result, in the wearer's words. A cancellation is not an error: the
 * user declining a prompt is a legitimate answer.
 *
 * [CxrAuthorizationOutcome.UNEXPECTED] is any answer that did **not** come back as a usable token, so it carries both
 * an unrecognised code and a parse the SDK itself refused (review Minor 6) - the transport derives it from the parsed
 * `AuthResult`, never from the bare result code.
 */
internal fun cxrAuthorizationLabel(outcome: CxrAuthorizationOutcome): String = when (outcome) {
    CxrAuthorizationOutcome.CONFIRMED -> "眼镜授权确认已完成"
    CxrAuthorizationOutcome.CANCELLED -> "眼镜授权被取消"
    CxrAuthorizationOutcome.UNEXPECTED -> "眼镜授权结果异常"
}
