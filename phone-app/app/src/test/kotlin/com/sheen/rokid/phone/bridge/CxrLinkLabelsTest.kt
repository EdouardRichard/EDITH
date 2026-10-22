package com.sheen.rokid.phone.bridge
import com.sheen.rokid.phone.session.SessionUiState
import com.sheen.rokid.phone.app.HostCompositionRoot

import com.sheen.rokid.transport.CxrAuthorizationOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The page's glasses-connection area, after `D53`③ removed the standalone connect entry.
 *
 * Two owner-reported defects meet here. (1) The old connect button printed "已请求连接眼镜，请在 AI App 中确认授权" on
 * **every** tap, long after authorization had been granted - a guess presented as a state. (2) Since `D53`③ there is no
 * connect entry at all: 开始/停止/暂停 own the link, so there is no "the user asked and we are waiting" state to word.
 *
 * What is left is what the page actually knows:
 *
 * - whether this build has a glasses transport at all ([SessionUiState.cxrTransportMissing]),
 * - whether the transport has reported a live session ([HostCompositionRoot.linkReady], surfaced as
 *   `SessionUiState.cxrLinkReady`) - which is **not** a live heartbeat, and may not be worded as one,
 * - and, on its own line, the SDK's authorization **result** once it really asked ([cxrAuthorizationLabel]).
 */
class CxrLinkLabelsTest {

    @Test
    fun theStatusLineSaysOnlyWhatIsKnown() {
        assertEquals("未连接", cxrStatusLabel(transportMissing = false, linkReady = false))
        assertEquals("已连接眼镜", cxrStatusLabel(transportMissing = false, linkReady = true))
        assertEquals(
            "眼镜连接未装配（TRANSPORT_MISSING）",
            cxrStatusLabel(transportMissing = true, linkReady = false),
        )
    }

    @Test
    fun aLiveSessionOutranksAStaleMissingFlag() {
        // The two flags cannot both be true in practice; if they ever are, the fact that outranks the other is the
        // transport's own "session is live" - claiming 未装配 on a live link would be the same kind of wrong as the old
        // authorization sentence.
        assertEquals("已连接眼镜", cxrStatusLabel(transportMissing = true, linkReady = true))
    }

    @Test
    fun thereIsNoUserTriggeredConnectStateAnyMore() {
        // D53-③: 开始 = 确保连接. Every wording the page can produce must be a **fact about the link**, never a state
        // that implies the user still has a connect step to take: those are the strings the removed button showed.
        everyStatusLine().forEach { line ->
            assertFalse("the status line may not ask for a connect tap: '$line'", line.contains("已请求"))
            assertFalse("the status line may not offer a reconnect: '$line'", line.contains("重连"))
        }
    }

    @Test
    fun theStatusLineNeverClaimsThatAnAuthorizationIsNeeded() {
        everyStatusLine().forEach { line ->
            assertFalse("the status line may not claim an authorization step: '$line'", line.contains("授权"))
        }
    }

    @Test
    fun theStatusLineNeverClaimsALiveHeartbeat() {
        // `linkReady` means "this process has seen the SDK's live-session signal" - it is not re-read, and the link can
        // drop without telling us (no disconnect callback is wired; see the spec's 不做/未测). Saying 在线 would be a
        // guess of exactly the kind this defect was about.
        everyStatusLine().forEach { line ->
            assertFalse("the status line may not claim a live heartbeat: '$line'", line.contains("在线") || line.contains("心跳"))
        }
    }

    @Test
    fun anAuthorizationResultIsReportedAsTheFactItIs() {
        assertEquals("眼镜授权确认已完成", cxrAuthorizationLabel(CxrAuthorizationOutcome.CONFIRMED))
        assertEquals("眼镜授权被取消", cxrAuthorizationLabel(CxrAuthorizationOutcome.CANCELLED))
        assertEquals("眼镜授权结果异常", cxrAuthorizationLabel(CxrAuthorizationOutcome.UNEXPECTED))
    }

    private fun everyStatusLine(): List<String> = listOf(false, true).flatMap { missing ->
        listOf(false, true).map { ready -> cxrStatusLabel(missing, ready) }
    }
}
