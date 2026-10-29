package com.sheen.rokid.phone.overlay

import com.sheen.rokid.contracts.AssistantRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5: the display→bubble path's route filter, the auto thinking row and the halo state queries.
 *
 * The timing itself is [BubbleStackModelTest]'s job; here the injected clock proves the repository reads the same
 * monotonic source the overlay service uses.
 */
class PhoneBubbleRepositoryTest {

    private var now = 0L
    private val repository = PhoneBubbleRepository(nowMillis = { now })

    private fun user(text: String = "帮我看看这个页面", turn: String = "t1") =
        repository.onEvent(PhoneBubbleEvent.User(AssistantRoute.WAKE, turn, text))

    @Test
    fun aUserEventAddsTheUserLineAndTheThinkingRow() {
        user()
        now = 300

        val messages = repository.snapshotNow().messages

        assertEquals(2, messages.size)
        assertEquals(BubbleKind.USER, messages[0].kind)
        assertEquals(BubbleKind.THINKING, messages[1].kind)
        assertEquals(BubbleStackModel.THINKING_TEXT, messages[1].text)
        assertTrue("thinking is the ball's light-ring state", repository.isAgentWorking())
        assertFalse(repository.isAnswering())
    }

    @Test
    fun onlyTheWakeRouteReachesTheStack() {
        repository.onEvent(PhoneBubbleEvent.User(AssistantRoute.PROACTIVE, "p1", "主动提示"))
        now = 300

        assertTrue(repository.snapshotNow().messages.isEmpty())
        assertFalse(repository.isAgentWorking())
    }

    @Test
    fun theRepositoryUsesTheInjectedClock() {
        user()
        now = 2_401

        val messages = repository.snapshotNow().messages

        assertEquals("the old user line is gone", 1, messages.size)
        assertEquals(BubbleKind.THINKING, messages.single().kind)
    }

    @Test
    fun theAnswerIsNotAWorkingStateAndTheToolRowIs() {
        user()
        repository.onEvent(PhoneBubbleEvent.Tool(AssistantRoute.WAKE, "t1", "读取屏幕内容",
            com.sheen.rokid.contracts.ToolPhase.START))
        now = 300
        assertTrue(repository.isAgentWorking())

        repository.onEvent(PhoneBubbleEvent.Tool(AssistantRoute.WAKE, "t1", "读取屏幕内容",
            com.sheen.rokid.contracts.ToolPhase.END))
        repository.onEvent(PhoneBubbleEvent.AnswerFinal(AssistantRoute.WAKE, "t1", "h1", "回答"))
        now = 400

        assertFalse(repository.isAgentWorking())
        assertTrue(repository.isAnswering())
    }

    @Test
    fun clearDropsEverything() {
        user()
        now = 300
        repository.clear()

        assertTrue(repository.snapshotNow().messages.isEmpty())
        assertFalse(repository.isAgentWorking())
    }

    /**
     * 0.5.1 (`C10`): a local Notice is a short-lived overlay message, not a turn artifact. The repository's own clock
     * expires it, so no `TurnIdle` (and no second event) is needed for it to disappear.
     */
    @Test
    fun aLocalNoticeExpiresWithoutAnyFurtherEvent() {
        repository.onEvent(PhoneBubbleEvent.Notice(AssistantRoute.WAKE, "还没有开始会话，打开 App 开始"))
        now = 300
        assertTrue(repository.snapshotNow().messages.isNotEmpty())

        now = BubbleStackModel.NOTICE_DISMISS_AFTER_MILLIS + 1
        assertTrue(repository.snapshotNow().messages.isEmpty())
    }

    /**
     * 0.5.1 (`C10`): the published snapshot must contain the row at the same instant it arrives. The overlay's frame
     * loop starts from a non-empty snapshot, and a lone local Notice has no later event to restart it.
     */
    @Test
    fun aLocalNoticeIsPublishedInTheSameInstantSnapshot() {
        repository.onEvent(PhoneBubbleEvent.Notice(AssistantRoute.WAKE, "还没有开始会话，打开 App 开始"))

        val published = repository.snapshots.value.messages

        assertEquals(1, published.size)
        assertTrue(published.single().newest)
        assertEquals(BubbleKind.NOTICE, published.single().kind)
    }
}
