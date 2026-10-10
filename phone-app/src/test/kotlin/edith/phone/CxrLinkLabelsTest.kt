package edith.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Owner-reported defect (2026-10-10): the connect button printed "已请求连接眼镜，请在 AI App 中确认授权" on **every** tap,
 * long after authorization had been granted - a guess presented as a state, which is what kept the wearer waiting for a
 * prompt that never came. The page now says only what it knows:
 *
 * - whether this build has a glasses transport at all,
 * - whether the user asked to connect,
 * - whether the transport has reported a live session ([HostCompositionRoot.linkReady], surfaced as
 *   `SessionUiState.cxrLinkReady`) - which is **not** a live heartbeat, and may not be worded as one,
 * - and, on its own line, the SDK's authorization **result** once it really asked ([cxrAuthorizationLabel]).
 */
class CxrLinkLabelsTest {

    @Test
    fun theStatusLineSaysOnlyWhatIsKnown() {
        assertEquals("未连接", cxrStatusLabel(transportMissing = false, requested = false, linkReady = false))
        assertEquals("已请求，等待就绪…", cxrStatusLabel(transportMissing = false, requested = true, linkReady = false))
        assertEquals("已连接眼镜", cxrStatusLabel(transportMissing = false, requested = true, linkReady = true))
        assertEquals(
            "眼镜连接未装配（TRANSPORT_MISSING）",
            cxrStatusLabel(transportMissing = true, requested = true, linkReady = false),
        )
    }

    @Test
    fun aLiveSessionOutranksAStaleMissingFlag() {
        // The two flags cannot both be true in practice; if they ever are, the fact that outranks the other is the
        // transport's own "session is live" - claiming 未装配 on a live link would be the same kind of wrong as the old
        // authorization sentence.
        assertEquals("已连接眼镜", cxrStatusLabel(transportMissing = true, requested = true, linkReady = true))
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
        listOf(false, true).flatMap { requested ->
            listOf(false, true).map { ready -> cxrStatusLabel(missing, requested, ready) }
        }
    }
}
