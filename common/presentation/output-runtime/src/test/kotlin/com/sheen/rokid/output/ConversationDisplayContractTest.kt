package com.sheen.rokid.output

import com.sheen.rokid.contracts.AssistantRoute
import com.sheen.rokid.contracts.EventEnvelope
import com.sheen.rokid.contracts.Generation
import com.sheen.rokid.contracts.HostEvent
import com.sheen.rokid.contracts.PinChanged
import com.sheen.rokid.contracts.SessionId
import com.sheen.rokid.contracts.SpeechFinished
import com.sheen.rokid.contracts.SpeechResult
import com.sheen.rokid.contracts.ToolPhase
import com.sheen.rokid.plugin.ConversationDisplay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 0.24 spec §5.5: the conversation port grows the official conversation's calls, and the two host facts the phone has
 * to deliver (a glasses PIN inward, a speech playback ending internally) become host events.
 *
 * The reason these are pinned here rather than only used by the phone: [ConversationDisplay] is implemented by hosts
 * and faked by plugins. The 0.24 calls must be additive defaults, never a new abstract surface — a pre-0.24
 * implementation has to keep compiling, and every new default has to stay inert instead of quietly reusing a legacy
 * call (e.g. `showIdle` must not turn into `idle`, which is the busy level, not the official idle status).
 */
class ConversationDisplayContractTest {

    private val envelope = EventEnvelope(
        sessionId = SessionId("session-1"),
        generation = Generation(2),
        eventId = "event-1",
        startNanos = 1_000L,
        endNanos = 2_000L,
    )

    /**
     * What a host implementation written before 0.24 looks like: the four original calls and nothing else. If any
     * 0.24 addition were abstract, this class would stop compiling.
     */
    private class LegacyDisplay : ConversationDisplay {
        val calls = mutableListOf<String>()

        override fun userMessage(route: AssistantRoute, text: String) {
            calls += "userMessage:$route:$text"
        }

        override fun assistantMessage(route: AssistantRoute, text: String) {
            calls += "assistantMessage:$route:$text"
        }

        override fun working(route: AssistantRoute) {
            calls += "working:$route"
        }

        override fun idle(route: AssistantRoute) {
            calls += "idle:$route"
        }
    }

    @Test
    fun `a host written before the official conversation still implements the port`() {
        val display = LegacyDisplay()
        display.userMessage(AssistantRoute.WAKE, "打开客厅的灯")
        display.assistantMessage(AssistantRoute.WAKE, "已经打开了")
        display.working(AssistantRoute.WAKE)
        display.idle(AssistantRoute.WAKE)

        assertEquals(
            listOf(
                "userMessage:WAKE:打开客厅的灯",
                "assistantMessage:WAKE:已经打开了",
                "working:WAKE",
                "idle:WAKE",
            ),
            display.calls,
            "the four original calls keep their meaning; the new defaults must not replace them",
        )
    }

    @Test
    fun `the official conversation calls are inert no-ops on a host written before them`() {
        val display = LegacyDisplay()

        display.state(
            route = AssistantRoute.WAKE,
            turnId = "turn-1",
            status = OfficialStatus.ANSWERING.wire,
            sequence = 3L,
        )
        display.showIdle(route = AssistantRoute.WAKE, sequence = 4L)
        display.userPartial(route = AssistantRoute.WAKE, turnId = "turn-1", revision = 1L, text = "北京")
        display.assistantPartial(
            route = AssistantRoute.WAKE,
            turnId = "turn-1",
            hintId = "hint-1",
            revision = 2L,
            text = "今天晴",
        )
        display.toolStatus(
            route = AssistantRoute.WAKE,
            turnId = "turn-1",
            phase = ToolPhase.START,
            label = "get_weather",
        )
        display.panelOpen(route = AssistantRoute.WAKE)
        display.closePanel(route = AssistantRoute.WAKE)

        assertTrue(
            display.calls.isEmpty(),
            "a default call must do nothing at all, not fall back to a legacy call: ${display.calls}",
        )
    }

    /**
     * A full 0.24 implementation. It exists to pin the exact signatures a producer will call: `status` is the plain
     * wire int (the phone passes `OfficialStatus.wire`), the two sequence/revision fields are `Long`, and the tool
     * phase is the shared `core-contracts` `ToolPhase` rather than a port-local copy.
     */
    private class RecordingDisplay : ConversationDisplay {
        val calls = mutableListOf<String>()

        override fun userMessage(route: AssistantRoute, text: String) {
            calls += "userMessage:$route:$text"
        }

        override fun assistantMessage(route: AssistantRoute, text: String) {
            calls += "assistantMessage:$route:$text"
        }

        override fun working(route: AssistantRoute) {
            calls += "working:$route"
        }

        override fun idle(route: AssistantRoute) {
            calls += "idle:$route"
        }

        override fun state(route: AssistantRoute, turnId: String, status: Int, sequence: Long) {
            calls += "state:$route:$turnId:$status:$sequence"
        }

        override fun showIdle(route: AssistantRoute, sequence: Long) {
            calls += "showIdle:$route:$sequence"
        }

        override fun userPartial(route: AssistantRoute, turnId: String, revision: Long, text: String) {
            calls += "userPartial:$route:$turnId:$revision:$text"
        }

        override fun assistantPartial(
            route: AssistantRoute,
            turnId: String,
            hintId: String,
            revision: Long,
            text: String,
        ) {
            calls += "assistantPartial:$route:$turnId:$hintId:$revision:$text"
        }

        override fun toolStatus(route: AssistantRoute, turnId: String, phase: ToolPhase, label: String) {
            calls += "toolStatus:$route:$turnId:${phase.name}:$label"
        }

        override fun panelOpen(route: AssistantRoute) {
            calls += "panelOpen:$route"
        }

        override fun closePanel(route: AssistantRoute) {
            calls += "closePanel:$route"
        }
    }

    @Test
    fun `a host receives every new call with the official wire fields`() {
        val display: ConversationDisplay = RecordingDisplay()
        display.state(
            route = AssistantRoute.PROACTIVE,
            turnId = "turn-9",
            status = OfficialStatus.TOOL.wire,
            sequence = 42L,
        )
        display.showIdle(route = AssistantRoute.PROACTIVE, sequence = 43L)
        display.userPartial(
            route = AssistantRoute.PROACTIVE,
            turnId = "turn-9",
            revision = 7L,
            text = "附近有",
        )
        display.assistantPartial(
            route = AssistantRoute.PROACTIVE,
            turnId = "turn-9",
            hintId = "hint-9",
            revision = 8L,
            text = "附近有一家咖啡店",
        )
        display.toolStatus(
            route = AssistantRoute.PROACTIVE,
            turnId = "turn-9",
            phase = ToolPhase.END,
            label = "get_location",
        )
        display.panelOpen(route = AssistantRoute.PROACTIVE)
        display.closePanel(route = AssistantRoute.PROACTIVE)

        assertEquals(
            listOf(
                "state:PROACTIVE:turn-9:7:42",
                "showIdle:PROACTIVE:43",
                "userPartial:PROACTIVE:turn-9:7:附近有",
                "assistantPartial:PROACTIVE:turn-9:hint-9:8:附近有一家咖啡店",
                "toolStatus:PROACTIVE:turn-9:END:get_location",
                "panelOpen:PROACTIVE",
                "closePanel:PROACTIVE",
            ),
            (display as RecordingDisplay).calls,
        )
    }

    @Test
    fun `PinChanged is a host event naming the route the wearer pinned`() {
        val changed = PinChanged(envelope = envelope, route = AssistantRoute.WAKE)
        val hostEvent: HostEvent = changed

        assertEquals(envelope, hostEvent.envelope)
        assertEquals(AssistantRoute.WAKE, changed.route)
        assertEquals(PinChanged(envelope, AssistantRoute.WAKE), changed)
    }

    @Test
    fun `SpeechFinished is a host event carrying the hint and how speech ended`() {
        SpeechResult.entries.forEach { result ->
            val finished = SpeechFinished(envelope = envelope, hintId = "hint-9", result = result)
            val hostEvent: HostEvent = finished

            assertEquals(envelope, hostEvent.envelope)
            assertEquals("hint-9", finished.hintId)
            assertEquals(result, finished.result)
        }
    }
}
