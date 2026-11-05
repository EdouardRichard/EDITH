package com.sheen.rokid.phone.actions.media

import android.media.session.PlaybackState
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.plugin.ActionOutcome
import com.sheen.rokid.plugin.ActionResult
import com.sheen.rokid.plugin.RiskLevel
import com.sheen.rokid.plugin.ToolOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.5.4 Task 15 (spec §6.5): `media` lists the active sessions and controls exactly one of them.
 *
 * Two layers are pinned here because they answer different questions:
 * - the **tool** owns argument validation, target selection, the pre-send `PlaybackState.actions` bit check and the
 *   mapping of a port answer to the model-facing codes. Its tests use a fake [MediaControlPort], so "the port was not
 *   called" is a real assertion rather than an inference.
 * - the **production port** owns the Android session lifecycle: it selects a session, sends one transport command,
 *   polls `PlaybackState` for evidence inside the 1.5 s window and releases **every** controller it obtained. Its tests
 *   drive [AndroidMediaControlPort] over fake [MediaSessionHandle]s, so no JVM test sleeps and no Android session is
 *   needed.
 *
 * Nothing here may put a title, artist or album into a log line: the audit assertion plants distinctive metadata and
 * proves it never reaches [P3Log].
 */
class MediaToolTest {

    // ── list ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `list with no active sessions is Done with an empty array`() {
        val rig = ToolRig(sessions = emptyList())

        val result = rig.tool.execute("""{"action":"list"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val data = body(result)
        assertEquals(0, data["count"]!!.jsonPrimitive.int)
        assertTrue("no session is a real list answer", data["sessions"]!!.jsonArray.isEmpty())
        assertEquals("list is a read, not a write", false, result.stateChanging)
        assertTrue("list must not send a transport command", rig.port.controlCalls.isEmpty())
    }

    @Test
    fun `list reports one session with package state metadata timings actions current and controllable`() {
        val session = session(
            pkg = "com.example.music",
            state = "PLAYING",
            title = "Song",
            artist = "Artist",
            album = "Album",
            positionMs = 42_000L,
            durationMs = 180_000L,
            actions = ALL_ACTIONS,
            current = true,
            controllable = true,
        )
        val rig = ToolRig(sessions = listOf(session))

        val result = rig.tool.execute("""{"action":"list"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val data = body(result)
        assertEquals(1, data["count"]!!.jsonPrimitive.int)
        val entry = data["sessions"]!!.jsonArray.single().jsonObject
        assertEquals("com.example.music", entry["package"]!!.jsonPrimitive.content)
        assertEquals("PLAYING", entry["state"]!!.jsonPrimitive.content)
        assertEquals("Song", entry["title"]!!.jsonPrimitive.content)
        assertEquals("Artist", entry["artist"]!!.jsonPrimitive.content)
        assertEquals("Album", entry["album"]!!.jsonPrimitive.content)
        assertEquals(42_000L, entry["positionMs"]!!.jsonPrimitive.long)
        assertEquals(180_000L, entry["durationMs"]!!.jsonPrimitive.long)
        assertEquals(ALL_ACTIONS, entry["actions"]!!.jsonPrimitive.long)
        assertEquals(true, entry["current"]!!.jsonPrimitive.boolean)
        assertEquals(true, entry["controllable"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `list reports several sessions in the order the platform returned them`() {
        val rig = ToolRig(
            sessions = listOf(
                session(pkg = "com.example.a", current = true),
                session(pkg = "com.example.b"),
                session(pkg = "com.example.c"),
            ),
        )

        val result = rig.tool.execute("""{"action":"list"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val data = body(result)
        assertEquals(3, data["count"]!!.jsonPrimitive.int)
        assertEquals(
            listOf("com.example.a", "com.example.b", "com.example.c"),
            data["sessions"]!!.jsonArray.map { it.jsonObject["package"]!!.jsonPrimitive.content },
        )
        assertTrue(rig.port.controlCalls.isEmpty())
    }

    // ── access vs session ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `list without notification access is NO_NOTIFICATION_ACCESS rather than an empty list`() {
        val rig = ToolRig(sessions = emptyList(), noAccess = true)

        val result = rig.tool.execute("""{"action":"list"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals("NO_NOTIFICATION_ACCESS", execution.code)
        assertEquals(false, execution.accepted)
        assertEquals(false, execution.applied)
        assertTrue("no session list may be fabricated", rig.port.controlCalls.isEmpty())
    }

    @Test
    fun `control without notification access is NO_NOTIFICATION_ACCESS, not NO_SESSION`() {
        val rig = ToolRig(sessions = emptyList(), noAccess = true)

        val result = rig.tool.execute("""{"action":"play"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NO_NOTIFICATION_ACCESS", requireNotNull(result.execution).code)
        assertTrue(rig.port.controlCalls.isEmpty())
    }

    @Test
    fun `control with no active session is NO_SESSION and the port is not called`() {
        val rig = ToolRig(sessions = emptyList())

        val result = rig.tool.execute("""{"action":"pause"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NO_SESSION", requireNotNull(result.execution).code)
        assertTrue(rig.port.controlCalls.isEmpty())
    }

    @Test
    fun `a requested package with no session of its own is NO_SESSION`() {
        val rig = ToolRig(sessions = listOf(session(pkg = "com.example.music")))

        val result = rig.tool.execute("""{"action":"play","package":"com.other.player"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NO_SESSION", requireNotNull(result.execution).code)
        assertTrue(rig.port.controlCalls.isEmpty())
    }

    @Test
    fun `several sessions without a package are AMBIGUOUS_SESSION with the candidate packages`() {
        val rig = ToolRig(
            sessions = listOf(
                session(pkg = "com.example.a"),
                session(pkg = "com.example.b"),
            ),
        )

        val result = rig.tool.execute("""{"action":"play"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals("AMBIGUOUS_SESSION", execution.code)
        assertEquals(
            setOf("com.example.a", "com.example.b"),
            body(result)["candidates"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertTrue("an ambiguous call must not pick a session silently", rig.port.controlCalls.isEmpty())
    }

    @Test
    fun `a package selects its own session even when several are active`() {
        val rig = ToolRig(
            sessions = listOf(
                session(pkg = "com.example.a"),
                session(pkg = "com.example.b"),
            ),
            result = MediaControlResult.Done(
                before = session(pkg = "com.example.b", state = "PAUSED"),
                after = session(pkg = "com.example.b", state = "PLAYING"),
            ),
        )

        val result = rig.tool.execute("""{"action":"play","package":"com.example.b"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        assertEquals(
            listOf(Triple("play", "com.example.b", null)),
            rig.port.controlCalls,
        )
    }

    // ── the pre-send action-bit gate ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `a missing transport action bit is ACTION_UNSUPPORTED before the port is asked to control`() {
        val rig = ToolRig(
            sessions = listOf(session(actions = PlaybackState.ACTION_PAUSE)),
            result = MediaControlResult.Done(session(state = "PAUSED"), session(state = "PLAYING")),
        )

        val result = rig.tool.execute("""{"action":"play"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_UNSUPPORTED", requireNotNull(result.execution).code)
        assertTrue("a session without the play bit must not receive play", rig.port.controlCalls.isEmpty())
    }

    @Test
    fun `each action requires its own transport bit`() {
        val requiredBits = listOf(
            // A play/pause session may advertise only ACTION_PLAY_PAUSE, so play and pause accept that bit beside theirs.
            "play" to (PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PLAY_PAUSE),
            "pause" to (PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE),
            "toggle" to PlaybackState.ACTION_PLAY_PAUSE,
            "next" to PlaybackState.ACTION_SKIP_TO_NEXT,
            "previous" to PlaybackState.ACTION_SKIP_TO_PREVIOUS,
            "stop" to PlaybackState.ACTION_STOP,
            "seek" to PlaybackState.ACTION_SEEK_TO,
        )
        requiredBits.forEach { (action, bit) ->
            val args = if (action == "seek") {
                """{"action":"seek","position_ms":1000}"""
            } else {
                """{"action":"$action"}"""
            }
            val unsupported = ToolRig(
                sessions = listOf(session(actions = ALL_ACTIONS and bit.inv())),
                result = MediaControlResult.Done(session(), session()),
            ).tool.execute(args)
            assertEquals("$action must be unsupported without its bit", "ACTION_UNSUPPORTED",
                requireNotNull(unsupported.execution).code)

            val supported = ToolRig(
                sessions = listOf(session(actions = bit)),
                result = MediaControlResult.Done(
                    before = session(actions = bit),
                    after = session(actions = bit),
                ),
            ).tool.execute(args)
            assertEquals("$action must reach the port with its bit", ActionOutcome.Done, supported.outcome)
        }
    }

    // ── successful control ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `play is Done only with the port's before and after and the verified execution facts`() {
        val before = session(state = "PAUSED", positionMs = 10_000L)
        val after = session(state = "PLAYING", positionMs = 10_400L)
        val rig = ToolRig(sessions = listOf(before), result = MediaControlResult.Done(before, after))

        val result = rig.tool.execute("""{"action":"play","package":"com.example.music"}""")

        assertEquals(ActionOutcome.Done, result.outcome)
        val data = body(result)
        assertEquals("play", data["action"]!!.jsonPrimitive.content)
        assertEquals("com.example.music", data["package"]!!.jsonPrimitive.content)
        assertEquals("PAUSED", data["before"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        assertEquals("PLAYING", data["after"]!!.jsonObject["state"]!!.jsonPrimitive.content)
        val execution = requireNotNull(result.execution)
        assertEquals(ToolOutcome.Done, execution.outcome)
        assertEquals(true, execution.accepted)
        assertEquals(true, execution.applied)
        assertEquals(true, execution.verified)
        assertEquals(true, execution.changed)
        assertEquals(listOf(Triple("play", "com.example.music", null)), rig.port.controlCalls)
    }

    @Test
    fun `pause next previous stop and seek all reach the port with the selected package`() {
        listOf(
            Triple("pause", null, null),
            Triple("next", null, null),
            Triple("previous", null, null),
            Triple("stop", null, null),
            Triple("seek", 30_000L, """{"action":"seek","package":"com.example.music","position_ms":30000}"""),
        ).forEach { (action, position, args) ->
            val rig = ToolRig(
                sessions = listOf(session()),
                result = MediaControlResult.Done(session(), session()),
            )
            val result = rig.tool.execute(
                args ?: """{"action":"$action","package":"com.example.music"}""",
            )

            assertEquals("$action must be accepted", ActionOutcome.Done, result.outcome)
            assertEquals(
                "$action must carry the selected package",
                listOf(Triple(action, "com.example.music", position)),
                rig.port.controlCalls,
            )
        }
    }

    @Test
    fun `seek requires a non-negative position_ms`() {
        listOf(
            """{"action":"seek","package":"com.example.music"}""",
            """{"action":"seek","package":"com.example.music","position_ms":-1}""",
            """{"action":"seek","package":"com.example.music","position_ms":"1000"}""",
        ).forEach { args ->
            val rig = ToolRig(sessions = listOf(session()))
            val result = rig.tool.execute(args)

            assertEquals("$args must be refused", ActionOutcome.Refused, result.outcome)
            assertEquals("ARGUMENTS_INVALID", requireNotNull(result.execution).code)
            assertTrue(rig.port.controlCalls.isEmpty())
        }
    }

    // ── post-send failures ───────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `a port answer with no state change is NO_STATE_CHANGE after an accepted send`() {
        val rig = ToolRig(sessions = listOf(session()), result = MediaControlResult.NoChange)

        val result = rig.tool.execute("""{"action":"play"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals("NO_STATE_CHANGE", execution.code)
        assertEquals(ToolOutcome.Failed, execution.outcome)
        assertEquals("the command was dispatched", true, execution.accepted)
        assertNull("the state did not prove it applied", execution.applied)
        assertEquals(false, execution.verified)
        assertEquals(listOf(Triple("play", "com.example.music", null)), rig.port.controlCalls)
    }

    @Test
    fun `a vanished session is SESSION_GONE after an accepted send`() {
        val rig = ToolRig(sessions = listOf(session()), result = MediaControlResult.SessionGone)

        val result = rig.tool.execute("""{"action":"pause"}""")

        assertEquals(ActionOutcome.Failed, result.outcome)
        val execution = requireNotNull(result.execution)
        assertEquals("SESSION_GONE", execution.code)
        assertEquals(true, execution.accepted)
        assertNull(execution.applied)
        assertEquals(false, execution.verified)
    }

    @Test
    fun `a port-side unsupported answer is ACTION_UNSUPPORTED`() {
        val rig = ToolRig(sessions = listOf(session()), result = MediaControlResult.Unsupported)

        val result = rig.tool.execute("""{"action":"stop"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("ACTION_UNSUPPORTED", requireNotNull(result.execution).code)
    }

    @Test
    fun `access lost between list and send is NO_NOTIFICATION_ACCESS`() {
        val rig = ToolRig(sessions = listOf(session()), result = MediaControlResult.NoPermission)

        val result = rig.tool.execute("""{"action":"play"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NO_NOTIFICATION_ACCESS", requireNotNull(result.execution).code)
    }

    @Test
    fun `a session lost between list and send is NO_SESSION`() {
        val rig = ToolRig(sessions = listOf(session()), result = MediaControlResult.NoSession)

        val result = rig.tool.execute("""{"action":"play"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("NO_SESSION", requireNotNull(result.execution).code)
    }

    // ── argument validation ─────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `an unknown action name is UNKNOWN_ACTION and reaches no port`() {
        val rig = ToolRig(sessions = listOf(session()))

        val result = rig.tool.execute("""{"action":"record"}""")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("UNKNOWN_ACTION", requireNotNull(result.execution).code)
        assertTrue(rig.port.controlCalls.isEmpty())
    }

    @Test
    fun `malformed arguments are ARGUMENTS_INVALID before any port read`() {
        listOf(
            "{}",
            """{"action":123}""",
            """{"action":""}""",
            """{"action":"play","package":123}""",
        ).forEach { args ->
            val rig = ToolRig(sessions = listOf(session()))

            val result = rig.tool.execute(args)

            assertEquals("$args must be refused", ActionOutcome.Refused, result.outcome)
            assertEquals("ARGUMENTS_INVALID", requireNotNull(result.execution).code)
            assertTrue(rig.port.controlCalls.isEmpty())
        }
    }

    @Test
    fun `an unknown tool name is UNKNOWN_ACTION`() {
        val rig = ToolRig(sessions = listOf(session()))

        val result = rig.tool.execute("teleport", "{}")

        assertEquals(ActionOutcome.Refused, result.outcome)
        assertEquals("UNKNOWN_ACTION", requireNotNull(result.execution).code)
    }

    // ── audit ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the audit line carries action package and counts but never title artist or album`() {
        val rig = ToolRig(
            sessions = listOf(session(title = "Top Secret Song", artist = "Secret Artist", album = "Secret Album")),
            result = MediaControlResult.Done(session(), session()),
        )
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { line -> lines += line }
        try {
            rig.tool.execute("""{"action":"play","package":"com.example.music"}""")
        } finally {
            P3Log.sink = previous
        }

        val audit = lines.filter { it.startsWith("P3_MEDIA") }
        assertEquals("one audit line per call", 1, audit.size)
        val line = audit.single()
        assertTrue("the action must be recorded: $line", line.contains("action=play"))
        assertTrue("the target package must be recorded: $line", line.contains("package=com.example.music"))
        assertTrue("the session count must be recorded: $line", line.contains("sessions=1"))
        assertFalse("a title is user content: $line", line.contains("Top Secret"))
        assertFalse("an artist is user content: $line", line.contains("Secret Artist"))
        assertFalse("an album is user content: $line", line.contains("Secret Album"))
    }

    // ── descriptor ───────────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `the descriptor declares exactly the action package and seek position at L1`() {
        val descriptor = MediaTool.DESCRIPTOR

        assertEquals("media", descriptor.name)
        assertEquals(RiskLevel.L1, descriptor.risk)
        assertFalse(descriptor.requiresConfirmation)
        assertEquals("action", descriptor.auditTargetArgument)

        val schema = Json.parseToJsonElement(descriptor.parametersJson).jsonObject
        assertEquals(
            "no volume/search/playFromSearch argument is offered",
            setOf("action", "package", "position_ms"),
            schema["properties"]!!.jsonObject.keys,
        )
        assertEquals(
            listOf("action"),
            schema["required"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        assertEquals(false, schema["additionalProperties"]!!.jsonPrimitive.boolean)
        assertEquals(
            setOf("list", "play", "pause", "toggle", "next", "previous", "stop", "seek"),
            schema["properties"]!!.jsonObject["action"]!!.jsonObject["enum"]!!
                .jsonArray.map { it.jsonPrimitive.content }.toSet(),
        )
        assertTrue(descriptor.description.contains("package"))
        assertTrue(descriptor.description.contains("position_ms"))
    }

    // ── production port: state machine over fake handles ────────────────────────────────────────────────────

    @Test
    fun `the port sends play and reports Done only after the state becomes PLAYING`() {
        val clock = FakeClock()
        val handle = FakeHandle(session(state = "PAUSED"))
        handle.afterSend = { session(state = "PLAYING") }
        val port = port(handle, clock)

        val result = port.control("play", "com.example.music", null)

        val done = result as MediaControlResult.Done
        assertEquals("PAUSED", done.before.state)
        assertEquals("PLAYING", done.after.state)
        assertEquals(listOf("play"), handle.sent)
        assertEquals("the first poll already proves the state, so nothing had to sleep", emptyList<Long>(), clock.sleeps)
        assertTrue(handle.released)
    }

    @Test
    fun `the port accepts pause, next and seek evidence`() {
        val pause = FakeHandle(session(state = "PLAYING"))
        pause.afterSend = { session(state = "PAUSED") }
        assertTrue(port(pause, FakeClock()).control("pause", "com.example.music", null) is MediaControlResult.Done)

        val next = FakeHandle(session(state = "PLAYING", title = "Song A"))
        next.afterSend = { session(state = "PLAYING", title = "Song B") }
        assertTrue(
            "a next must be proven by the metadata identity changing",
            port(next, FakeClock()).control("next", "com.example.music", null) is MediaControlResult.Done,
        )
        assertEquals(listOf("next"), next.sent)

        val seek = FakeHandle(session(positionMs = 10_000L, durationMs = 180_000L))
        seek.afterSend = { session(positionMs = 31_500L, durationMs = 180_000L) }
        assertTrue(
            "a seek within two seconds of the target is verified",
            port(seek, FakeClock()).control("seek", "com.example.music", 30_000L) is MediaControlResult.Done,
        )
        assertEquals(listOf("seek@30000"), seek.sent)
    }

    @Test
    fun `the port reports NO_STATE_CHANGE when the evidence never appears in the window`() {
        val clock = FakeClock()
        val handle = FakeHandle(session(state = "PAUSED"))
        val port = port(handle, clock)

        val result = port.control("play", "com.example.music", null)

        assertEquals(MediaControlResult.NoChange, result)
        assertEquals(listOf("play"), handle.sent)
        assertEquals("the poll must use the 1.5 s window", VERIFY_TIMEOUT_MILLIS, clock.value)
        assertTrue("a failed verification must still release", handle.released)
    }

    @Test
    fun `the port reports SESSION_GONE when the controller disappears after the send`() {
        val handle = FakeHandle(session(state = "PAUSED"))
        handle.afterSend = { null }
        val port = port(handle, FakeClock())

        val result = port.control("play", "com.example.music", null)

        assertEquals(MediaControlResult.SessionGone, result)
        assertTrue(handle.released)
    }

    @Test
    fun `the port refuses an unsupported action before sending anything`() {
        val handle = FakeHandle(session(actions = PlaybackState.ACTION_PAUSE))
        val port = port(handle, FakeClock())

        val result = port.control("play", "com.example.music", null)

        assertEquals(MediaControlResult.Unsupported, result)
        assertTrue("no transport command may be sent without its bit", handle.sent.isEmpty())
        assertTrue(handle.released)
    }

    @Test
    fun `the port releases every controller it obtained`() {
        val first = FakeHandle(session(pkg = "com.example.a"))
        val second = FakeHandle(session(pkg = "com.example.b"))
        val clock = FakeClock()
        val platform = FakePlatform(listOf(first, second))
        val port = AndroidMediaControlPort(platform, clock::now, clock::sleep)

        port.control("play", "com.example.a", null)

        assertTrue("the selected controller is released", first.released)
        assertTrue("an unselected controller is released too", second.released)
    }

    @Test
    fun `list copies the session facts and releases every controller`() {
        val first = FakeHandle(session(pkg = "com.example.a", current = true, actions = ALL_ACTIONS))
        val second = FakeHandle(session(pkg = "com.example.b", current = false, actions = PlaybackState.ACTION_PAUSE))
        val clock = FakeClock()
        val port = AndroidMediaControlPort(FakePlatform(listOf(first, second)), clock::now, clock::sleep)

        val sessions = port.list()

        assertEquals(2, sessions.size)
        assertEquals("com.example.a", sessions[0].packageName)
        assertEquals(true, sessions[0].current)
        assertEquals(true, sessions[0].controllable)
        assertEquals("com.example.b", sessions[1].packageName)
        assertEquals(false, sessions[1].current)
        assertTrue(first.released)
        assertTrue(second.released)
    }

    @Test
    fun `the port never selects one of several sessions without a package`() {
        val first = FakeHandle(session(pkg = "com.example.a"))
        val second = FakeHandle(session(pkg = "com.example.b"))
        val clock = FakeClock()
        val port = AndroidMediaControlPort(FakePlatform(listOf(first, second)), clock::now, clock::sleep)

        val result = port.control("play", null, null)

        assertEquals(
            MediaControlResult.Ambiguous(listOf("com.example.a", "com.example.b")),
            result,
        )
        assertTrue(first.sent.isEmpty())
        assertTrue(second.sent.isEmpty())
        assertTrue(first.released)
        assertTrue(second.released)
    }

    @Test
    fun `the production port maps a refused session binder to NoPermission`() {
        val clock = FakeClock()
        val port = AndroidMediaControlPort(FakePlatform(emptyList(), denied = true), clock::now, clock::sleep)

        assertEquals(MediaControlResult.NoPermission, port.control("play", null, null))
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────────────────

    private class ToolRig(
        sessions: List<MediaSessionInfo>,
        noAccess: Boolean = false,
        result: MediaControlResult? = null,
    ) {
        val port = FakePort(sessions, noAccess, result)
        val tool = MediaTool(port)
    }

    private class FakePort(
        private val sessions: List<MediaSessionInfo>,
        private val noAccess: Boolean,
        private val result: MediaControlResult?,
    ) : MediaControlPort {
        val controlCalls = mutableListOf<Triple<String, String?, Long?>>()

        override fun list(): List<MediaSessionInfo> {
            if (noAccess) throw MediaNotificationAccessException()
            return sessions
        }

        override fun control(action: String, packageName: String?, positionMs: Long?): MediaControlResult {
            controlCalls += Triple(action, packageName, positionMs)
            return result ?: MediaControlResult.NoSession
        }
    }

    private class FakePlatform(
        private val sessions: List<FakeHandle>,
        private val denied: Boolean = false,
    ) : MediaSessionHandles {
        override fun activeSessions(): List<MediaSessionHandle> {
            if (denied) throw MediaNotificationAccessException()
            return sessions
        }
    }

    private class FakeHandle(
        private var current: MediaSessionInfo?,
    ) : MediaSessionHandle {
        /** The session's answer on the poll after the send; `null` models a session that went away. */
        var afterSend: (() -> MediaSessionInfo?)? = null
        val sent = mutableListOf<String>()
        var released = false

        override val packageName: String = current!!.packageName

        override fun snapshot(): MediaSessionInfo? = current

        override fun send(action: String, positionMs: Long?): Boolean {
            sent += if (positionMs == null) action else "$action@$positionMs"
            afterSend?.let { current = it() }
            return true
        }

        override fun release() {
            released = true
        }
    }

    private class FakeClock {
        var value = 0L
        val sleeps = mutableListOf<Long>()

        fun now(): Long = value

        fun sleep(millis: Long) {
            sleeps += millis
            value += millis
        }
    }

    private fun port(handle: FakeHandle, clock: FakeClock): AndroidMediaControlPort =
        AndroidMediaControlPort(FakePlatform(listOf(handle)), clock::now, clock::sleep)

    private companion object {
        const val VERIFY_TIMEOUT_MILLIS = 1_500L
        const val ALL_ACTIONS = PlaybackState.ACTION_PLAY or
            PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or
            PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or
            PlaybackState.ACTION_STOP or
            PlaybackState.ACTION_SEEK_TO

        fun session(
            pkg: String = "com.example.music",
            state: String = "PLAYING",
            title: String? = "Song",
            artist: String? = "Artist",
            album: String? = "Album",
            positionMs: Long? = 10_000L,
            durationMs: Long? = 180_000L,
            actions: Long = ALL_ACTIONS,
            current: Boolean = false,
            controllable: Boolean = true,
        ) = MediaSessionInfo(
            packageName = pkg,
            state = state,
            title = title,
            artist = artist,
            album = album,
            positionMs = positionMs,
            durationMs = durationMs,
            actions = actions,
            current = current,
            controllable = controllable,
        )

        fun body(result: ActionResult): JsonObject = Json.parseToJsonElement(result.detail).jsonObject
    }
}
