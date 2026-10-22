package com.sheen.rokid.output

import kotlinx.serialization.json.*
import kotlin.test.*

class TraceRecorderTest {
    private fun recorder() = TraceRecorder()
    private fun record(t: TraceRecorder, session: String = "s-00000000-0000-0000-0000-000000000001", request: String? = null,
        time: Long = 0, code: String? = null, bytes: Int? = null, hash: String? = null) {
        t.record(TraceRecord(session, request, TraceMark.AUDIO_RECEIVED, time, code, bytes, hash))
    }
    private fun snapshot(t: TraceRecorder) = t.snapshot()
    @Test fun `bounded trace retains latest 4096 ordered independent records`() {
        val t = recorder(); repeat(4100) { record(t, time = it.toLong()) }
        val snap = snapshot(t); assertEquals(4096, snap.size)
        assertEquals(4L, snap.first().timeNanos)
        record(t, time = 4100); assertEquals(4099L, snap.last().timeNanos)
    }
    @Test fun `metadata rejects sensitive injection and JSON contains only whitelisted fields`() {
        val t = recorder(); val sentinel = "PRIVATE_SENTINEL"
        record(t, session = sentinel); record(t, request = sentinel); record(t, code = sentinel); record(t, hash = sentinel)
        record(t, time = -1); record(t, bytes = -1)
        assertTrue(snapshot(t).isEmpty())
        record(t, request = "r-00000000-0000-0000-0000-000000000002", code = "HINT_TOO_LONG", bytes = 0, hash = "a".repeat(64))
        val raw = t.toJson()
        assertFalse(raw.contains(sentinel))
        val parsed = Json.parseToJsonElement(raw).jsonArray.single().jsonObject
        assertEquals(setOf("sessionId", "requestId", "mark", "timeNanos", "safeCode", "queuedBytes", "versionHash"), parsed.keys)
        assertEquals(0, parsed.getValue("queuedBytes").jsonPrimitive.int)
    }
}
