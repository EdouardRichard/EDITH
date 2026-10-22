package com.sheen.rokid.phone.plugin.smarthelper.harness

import com.sheen.rokid.agent.PerceptionPolicy
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.MediaState
import com.sheen.rokid.plugin.NotificationItem
import com.sheen.rokid.plugin.PerceptionCoverage
import com.sheen.rokid.plugin.PerceptionElement
import com.sheen.rokid.plugin.PerceptionSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * edith-0.3 Phase 2 Task 9: the pure request-tail renderer and its count-only telemetry.
 *
 * The rendered block is model-facing text, so the synthetic title/text/clipboard values are expected in it. The
 * telemetry line is log-facing, so those values must never appear there: only counts, a snapshot prefix and the
 * coverage tokens.
 */
class SmartPerceptionRendererTest {

    private fun coverage(
        screen: String = "ok",
        notifications: String = "ok",
        media: String = "ok",
        calendar: String = "ok",
        acoustic: String = "ok",
        clipboard: String = "ok",
        vision: String = "ok",
        glasses: String = "ok",
        device: String = "ok",
    ) = PerceptionCoverage(
        screen = screen,
        notifications = notifications,
        media = media,
        calendar = calendar,
        acoustic = acoustic,
        clipboard = clipboard,
        vision = vision,
        glasses = glasses,
        device = device,
    )

    private fun snapshot(
        snapshotId: String = "snap-0042",
        foregroundPackage: String? = "com.miui.player",
        foregroundActivity: String? = ".PlayerActivity",
        elements: List<PerceptionElement> = listOf(
            PerceptionElement(
                ref = "#n12",
                role = "button",
                text = "搜索",
                centerX = 540,
                centerY = 320,
                clickable = true,
            ),
            PerceptionElement(
                ref = "#n13",
                role = "text",
                text = "稻香 - 周杰伦",
                centerX = 540,
                centerY = 180,
            ),
        ),
        notifications: List<NotificationItem> = listOf(
            NotificationItem(
                packageName = "com.tencent.mm",
                appLabel = "微信",
                title = "张三: 晚上一起吃饭吗？",
                text = "synthetic-notification-text",
                subText = "sub",
                bigText = "big",
                postedAtEpochMillis = 2_000L,
                actionLabels = listOf("回复", "标记已读"),
            ),
            NotificationItem(
                packageName = "com.android.mms",
                appLabel = "信息",
                title = "older-title",
                text = "older-text",
                postedAtEpochMillis = 1_000L,
            ),
        ),
        media: MediaState? = MediaState(
            state = "PLAYING",
            title = "稻香",
            artist = "周杰伦",
            album = "魔杰座",
            positionMs = 64_000L,
            durationMs = 223_000L,
        ),
        calendarTitles: List<String> = listOf("19:30 项目评审"),
        acoustic: String? = "speech",
        clipboardText: String? = "synthetic-clipboard-value",
        coverage: PerceptionCoverage = coverage(),
        truncated: Boolean = false,
    ) = PerceptionSnapshot(
        snapshotId = snapshotId,
        capturedAtEpochMillis = 1_000_000L,
        foregroundPackage = foregroundPackage,
        foregroundActivity = foregroundActivity,
        screenWidthPx = 1080,
        screenHeightPx = 2400,
        elements = elements,
        notifications = notifications,
        media = media,
        calendarTitles = calendarTitles,
        acoustic = acoustic,
        clipboardText = clipboardText,
        coverage = coverage,
        truncated = truncated,
    )

    private fun state(
        batteryPercent: Int? = 76,
        charging: Boolean? = true,
        network: String? = "wifi",
        screenOn: Boolean? = true,
        locked: Boolean? = false,
        brightness: Int? = 50,
        volumePercent: Int? = 40,
        imeVisible: Boolean? = false,
        foregroundPackage: String? = null,
        foregroundActivity: String? = null,
        panelVisible: Boolean? = true,
        glassesConnected: Boolean? = true,
        glassesWearing: Boolean? = true,
    ) = DeviceState(
        capturedAtEpochMillis = 1_000_000L,
        timeZone = "Asia/Shanghai",
        localDate = "2026-10-20",
        weekday = "星期二",
        localTime = "18:23",
        batteryPercent = batteryPercent,
        charging = charging,
        network = network,
        screenOn = screenOn,
        locked = locked,
        brightness = brightness,
        volumePercent = volumePercent,
        imeVisible = imeVisible,
        foregroundPackage = foregroundPackage,
        foregroundActivity = foregroundActivity,
        panelVisible = panelVisible,
        glassesConnected = glassesConnected,
        glassesWearing = glassesWearing,
    )

    @Test
    fun `the full block renders exactly the thirteen perception slots`() {
        val text = SmartPerceptionRenderer.render(snapshot(), state(), PerceptionPolicy(), full = true)

        assertEquals(13, text.lines().size, "the perception template has thirteen lines: $text")
        assertTrue(text.startsWith("【本轮运行上下文】"), text)
        // Time facts come from the device state, never from a clock the renderer invents.
        assertTrue("2026-10-20" in text, text)
        assertTrue("星期二" in text, text)
        assertTrue("18:23" in text, text)
        assertTrue("Asia/Shanghai" in text, text)
        // Device facts: only the non-null fields, in one line.
        assertTrue("battery=76%" in text, text)
        assertTrue("charging=yes" in text, text)
        assertTrue("network=wifi" in text, text)
        assertTrue("screenOn=yes" in text, text)
        assertTrue("locked=no" in text, text)
        assertTrue("brightness=50" in text, text)
        assertTrue("volume=40%" in text, text)
        assertTrue("ime=false" in text, text)
        // 0.3 Phase 2 Task 9 review I1: the glasses/panel facts were dropped from the device line.
        assertTrue("panel=visible" in text, text)
        assertTrue("glasses=connected" in text, text)
        assertTrue("wearing=yes" in text, text)
        assertTrue("com.miui.player/.PlayerActivity" in text, text)
        assertTrue("PLAYING" in text && "稻香" in text && "周杰伦" in text && "魔杰座" in text, text)
        assertTrue("progress=01:04/03:43" in text, text)
        // The compact screen renderer's own lines.
        assertTrue("#n12 button \"搜索\" center=(540,320) [clickable]" in text, text)
        assertTrue("#n13 text \"稻香 - 周杰伦\" center=(540,180)" in text, text)
        // Newest first, all listed notification fields present.
        assertTrue(text.indexOf("张三: 晚上一起吃饭吗？") < text.indexOf("older-title"), "newest notification first: $text")
        assertTrue("微信" in text && "synthetic-notification-text" in text && "sub" in text && "big" in text, text)
        assertTrue("actions=回复,标记已读" in text, text)
        assertTrue("19:30 项目评审" in text, text)
        assertTrue("speech" in text, text)
        assertTrue("synthetic-clipboard-value" in text, text)
        // The coverage line is the shared renderer's exact token line.
        assertTrue(
            "screen=ok notifications=ok media=ok calendar=ok acoustic=ok clipboard=ok vision=ok glasses=ok device=ok" in text,
            text,
        )
    }

    @Test
    fun `the panel and glasses facts are appended only when the host knows them`() {
        val known = SmartPerceptionRenderer.render(
            snapshot(),
            state(panelVisible = false, glassesConnected = false, glassesWearing = false),
            PerceptionPolicy(),
            full = true,
        )
        assertTrue("panel=hidden" in known, known)
        assertTrue("glasses=disconnected" in known, known)
        assertTrue("wearing=no" in known, known)

        // A null fact is absent, never a guessed false: the coverage line's own glasses token is unrelated.
        val unknown = SmartPerceptionRenderer.render(
            snapshot(),
            state(panelVisible = null, glassesConnected = null, glassesWearing = null),
            PerceptionPolicy(),
            full = true,
        )
        listOf("panel=visible", "panel=hidden", "glasses=connected", "glasses=disconnected", "wearing=")
            .forEach { token -> assertFalse(token in unknown, "omitted fact '$token' must not be rendered: $unknown") }
    }

    @Test
    fun `the foreground pair never mixes a snapshot package with a live activity`() {
        // The snapshot is the request's observation; mixing its package with the live state's activity would fabricate
        // a package/activity pair that has never coexisted (review I2).
        val conflicting = SmartPerceptionRenderer.render(
            snapshot(foregroundPackage = "com.snapshot.player", foregroundActivity = null),
            state(foregroundPackage = "com.live.player", foregroundActivity = ".LiveActivity"),
            PerceptionPolicy(),
            full = true,
        )
        assertTrue("前台：com.snapshot.player" in conflicting, conflicting)
        assertFalse("com.live.player" in conflicting, conflicting)
        assertFalse("/.LiveActivity" in conflicting, conflicting)

        // Only when the snapshot names no package at all does the live pair supply both halves together.
        val fallback = SmartPerceptionRenderer.render(
            snapshot(foregroundPackage = null, foregroundActivity = null),
            state(foregroundPackage = "com.live.player", foregroundActivity = ".LiveActivity"),
            PerceptionPolicy(),
            full = true,
        )
        assertTrue("前台：com.live.player/.LiveActivity" in fallback, fallback)
    }

    @Test
    fun `a value over the policy cap carries the shared truncation marker`() {
        val capPolicy = PerceptionPolicy(elementLimit = 5) // max(500, 5 * 120) = 600 code points
        val longPackage = "p".repeat(601)
        val long = snapshot(
            elements = emptyList(),
            notifications = listOf(NotificationItem(packageName = longPackage, postedAtEpochMillis = 1L)),
            media = null,
            calendarTitles = emptyList(),
            acoustic = null,
            clipboardText = null,
            coverage = coverage(screen = "empty", notifications = "ok"),
        )

        val text = SmartPerceptionRenderer.render(long, state(), capPolicy, full = true)

        assertTrue("… truncated" in text, "a deliberate cap must leave the shared marker: $text")
        assertFalse(longPackage in text, "the over-cap value must not be rendered whole: $text")
    }

    @Test
    fun `the policy cap boundary is exact and the telemetry reports the local clip`() {
        val capPolicy = PerceptionPolicy(elementLimit = 5) // max(500, 5 * 120) = 600 code points
        fun block(packageLength: Int) = snapshot(
            elements = emptyList(),
            notifications = listOf(
                NotificationItem(packageName = "p".repeat(packageLength), postedAtEpochMillis = 1L),
            ),
            media = null,
            calendarTitles = emptyList(),
            acoustic = null,
            clipboardText = null,
            coverage = coverage(screen = "empty", notifications = "ok"),
        )

        // Exactly at the cap: whole, no marker, telemetry keeps the snapshot's own truncated flag.
        val atCap = SmartPerceptionRenderer.renderDetailed(block(600), state(), capPolicy, full = true)
        assertFalse(atCap.truncated, "600 code points is not over the 600 code point cap")
        assertFalse("… truncated" in atCap.text, atCap.text)
        assertTrue(
            "truncated=false" in SmartPerceptionRenderer.telemetry(block(600), atCap.text, atCap.truncated, atCap.coverage),
            "an unclipped render keeps truncated=false",
        )

        // One code point over: the renderer itself truncates, says so, and the value still ends at the cap.
        val overCap = SmartPerceptionRenderer.renderDetailed(block(601), state(), capPolicy, full = true)
        assertTrue(overCap.truncated, "a locally clipped value must set the telemetry flag")
        assertTrue("… truncated" in overCap.text, overCap.text)
        assertFalse("p".repeat(601) in overCap.text, "the 601st code point must be the marker's budget, not content")
        val telemetry = SmartPerceptionRenderer.telemetry(block(601), overCap.text, overCap.truncated, overCap.coverage)
        assertTrue("truncated=true" in telemetry, telemetry)
    }

    @Test
    fun `a clipped screen marks the screen coverage and never splits a surrogate pair`() {
        val capPolicy = PerceptionPolicy(elementLimit = 5) // 600 code points
        val emoji = "\uD83D\uDE00" // one code point, two UTF-16 units
        val longScreen = snapshot(
            elements = listOf(
                PerceptionElement(ref = "#n1", role = "text", text = emoji.repeat(700), centerX = 1, centerY = 2),
            ),
            notifications = emptyList(),
            media = null,
            calendarTitles = emptyList(),
            acoustic = null,
            clipboardText = null,
            coverage = coverage(screen = "ok", notifications = "empty"),
        )

        val rendered = SmartPerceptionRenderer.renderDetailed(longScreen, state(), capPolicy, full = true)

        assertTrue(rendered.truncated, "the screen text was cut, so the snapshot is locally bounded")
        assertEquals("truncated", rendered.coverage.screen, "the local coverage token must name the cut")
        assertTrue("screen=truncated" in rendered.text, rendered.text)
        assertTrue("… truncated" in rendered.text, rendered.text)
        val telemetry = SmartPerceptionRenderer.telemetry(
            longScreen,
            rendered.text,
            rendered.truncated,
            rendered.coverage,
        )
        assertTrue("truncated=true" in telemetry, telemetry)
        assertTrue("screen=truncated" in telemetry, telemetry)
        // The cap counts code points: a cut between the two halves of an emoji would leave a lone surrogate.
        var index = 0
        while (index < rendered.text.length) {
            val character = rendered.text[index]
            if (Character.isHighSurrogate(character)) {
                assertTrue(
                    index + 1 < rendered.text.length && Character.isLowSurrogate(rendered.text[index + 1]),
                    "the truncated text ends on a lone high surrogate at $index",
                )
                index += 2
            } else {
                assertFalse(Character.isLowSurrogate(character), "the truncated text carries a lone low surrogate at $index")
                index += 1
            }
        }
    }

    @Test
    fun `a non-image profile names the missing capability instead of reporting vision ok`() {
        // The host gate is on (the snapshot says `vision=ok`), but the selected profile declares no IMAGE input, so the
        // images cannot reach the model. Saying `ok` would be the silent text guess spec §7.7 forbids.
        val rendered = SmartPerceptionRenderer.renderDetailed(
            snapshot(coverage = coverage(vision = "ok")),
            state(),
            PerceptionPolicy(),
            full = true,
            visionSupported = false,
        )

        assertEquals("unavailable(no_image_profile)", rendered.coverage.vision)
        assertTrue("vision=unavailable(no_image_profile)" in rendered.text, rendered.text)
        val telemetry = SmartPerceptionRenderer.telemetry(
            snapshot(coverage = coverage(vision = "ok")),
            rendered.text,
            rendered.truncated,
            rendered.coverage,
        )
        assertTrue(
            "coverage=screen=ok notifications=ok media=ok calendar=ok acoustic=ok clipboard=ok " +
                "vision=unavailable(no_image_profile) glasses=ok device=ok" in telemetry,
            telemetry,
        )
    }

    @Test
    fun `a disabled host gate keeps saying disabled even for an image-capable profile`() {
        val rendered = SmartPerceptionRenderer.renderDetailed(
            snapshot(coverage = coverage(vision = "disabled")),
            state(),
            PerceptionPolicy(),
            full = true,
            visionSupported = true,
        )

        assertEquals("disabled", rendered.coverage.vision,
            "the host's off switch wins over the profile capability")
        assertTrue("vision=disabled" in rendered.text, rendered.text)
        assertTrue(
            "vision=disabled" in SmartPerceptionRenderer.telemetry(
                snapshot(coverage = coverage(vision = "disabled")),
                rendered.text,
                rendered.truncated,
                rendered.coverage,
            ),
            "the telemetry coverage must agree with the rendered block",
        )
    }

    @Test
    fun `an image-capable profile with the gate on keeps vision ok and no profile leaves the host token alone`() {
        val ok = SmartPerceptionRenderer.renderDetailed(
            snapshot(coverage = coverage(vision = "ok")),
            state(),
            PerceptionPolicy(),
            full = true,
            visionSupported = true,
        )
        assertEquals("ok", ok.coverage.vision)
        assertTrue("vision=ok" in ok.text, ok.text)

        // No profile is not evidence that images are unsupported: the host's own token stays the answer.
        val unknown = SmartPerceptionRenderer.renderDetailed(
            snapshot(coverage = coverage(vision = "ok")),
            state(),
            PerceptionPolicy(),
            full = true,
            visionSupported = null,
        )
        assertEquals("ok", unknown.coverage.vision)
        assertTrue("vision=ok" in unknown.text, unknown.text)
    }

    @Test
    fun `the telemetry is count-only and carries no content`() {
        val text = SmartPerceptionRenderer.render(snapshot(), state(), PerceptionPolicy(), full = true)

        val telemetry = SmartPerceptionRenderer.telemetry(snapshot(), text)

        assertTrue(telemetry.startsWith("P3_PERCEPTION "), telemetry)
        assertTrue("snap=snap-004" in telemetry, telemetry)
        assertTrue("elements=2" in telemetry, telemetry)
        assertTrue("notifications=2" in telemetry, telemetry)
        assertTrue("calendar=1" in telemetry, telemetry)
        assertTrue("images=0" in telemetry, telemetry)
        assertTrue("truncated=false" in telemetry, telemetry)
        val screenChars = Regex("""screenChars=(\d+)""").find(telemetry)!!.groupValues[1].toInt()
        assertEquals(text.length, screenChars, "screenChars is the rendered block's char count")
        assertTrue(
            "coverage=screen=ok notifications=ok media=ok calendar=ok acoustic=ok clipboard=ok " +
                "vision=ok glasses=ok device=ok" in telemetry,
            telemetry,
        )
        // Nothing that was content may leak: not a title, body, screen text, app label, package, calendar title or
        // clipboard value.
        listOf(
            "搜索", "稻香", "周杰伦", "魔杰座", "张三", "晚上一起吃饭吗？", "synthetic-notification-text",
            "synthetic-clipboard-value", "项目评审", "com.tencent.mm", "com.miui.player", "#n12", "微信",
        ).forEach { sensitive ->
            assertFalse(sensitive in telemetry, "telemetry leaked '$sensitive': $telemetry")
        }
        assertTrue(telemetry.all { it.code in 32..126 }, "the telemetry line must be ASCII only: $telemetry")
    }

    @Test
    fun `a truncated screen keeps its marker and an unavailable source keeps its token`() {
        val cut = snapshot(
            elements = listOf(
                PerceptionElement(ref = "#n1", role = "text", text = "只有一行", centerX = 1, centerY = 2),
            ),
            notifications = emptyList(),
            calendarTitles = emptyList(),
            acoustic = null,
            clipboardText = null,
            coverage = coverage(
                screen = "truncated",
                notifications = "unavailable(no_access)",
                calendar = "empty",
                acoustic = "unavailable(no_event)",
                clipboard = "unavailable(disabled)",
            ),
            truncated = true,
        )

        val text = SmartPerceptionRenderer.render(cut, state(), PerceptionPolicy(), full = true)
        val telemetry = SmartPerceptionRenderer.telemetry(cut, text)

        assertTrue("… truncated" in text, "the shared renderer's screen truncation marker must survive: $text")
        assertTrue("unavailable(no_access)" in text, text)
        assertTrue("empty" in text, text)
        assertTrue("unavailable(no_event)" in text, text)
        assertTrue("unavailable(disabled)" in text, text)
        assertTrue("truncated=true" in telemetry, telemetry)
        assertTrue("screen=truncated" in telemetry, telemetry)
    }

    @Test
    fun `a missing device state and missing sources render honest empties and tokens`() {
        val empty = snapshot(
            foregroundPackage = null,
            foregroundActivity = null,
            elements = emptyList(),
            notifications = emptyList(),
            media = null,
            calendarTitles = emptyList(),
            acoustic = null,
            clipboardText = null,
            coverage = coverage(
                screen = "empty",
                notifications = "empty",
                media = "unavailable(no_session)",
                calendar = "empty",
                acoustic = "unavailable(no_event)",
                clipboard = "unavailable(disabled)",
            ),
        )

        val text = SmartPerceptionRenderer.render(empty, null, PerceptionPolicy(), full = true)

        assertTrue("当前时间：  （）" in text, "no clock is invented when the state is absent: $text")
        assertTrue("前台：unavailable" in text, text)
        assertTrue("媒体：unavailable(no_session)" in text, text)
        assertTrue("unavailable(no_event)" in text, text)
        assertTrue("unavailable(disabled)" in text, text)
        assertTrue("empty" in text, text)
        // The device line is empty (no field to render), never a fabricated zero.
        assertFalse("battery=" in text, text)
        assertFalse("brightness=" in text, text)
    }

    @Test
    fun `the ambient block omits the full snapshot content but keeps device and media`() {
        val text = SmartPerceptionRenderer.render(snapshot(), state(), PerceptionPolicy(), full = false)

        assertFalse("#n12" in text, "an ambient block must not repeat the screen: $text")
        assertFalse("张三: 晚上一起吃饭吗？" in text, text)
        assertFalse("19:30 项目评审" in text, text)
        assertFalse("synthetic-clipboard-value" in text, text)
        assertTrue("not_requested" in text, text)
        assertTrue("com.miui.player/.PlayerActivity" in text, text)
        assertTrue("PLAYING" in text && "稻香" in text, text)
        assertTrue("battery=76%" in text, text)
    }

    @Test
    fun `the notification cap is the policy's`() {
        val policy = PerceptionPolicy(notificationLimit = 1)

        val text = SmartPerceptionRenderer.render(snapshot(), state(), policy, full = true)

        assertTrue("张三: 晚上一起吃饭吗？" in text, text)
        assertFalse("older-title" in text, "the policy cap must bound the notification list: $text")
    }

    @Test
    fun `the value cap scales with the element limit instead of the renderer default`() {
        val elements = (1..8).map { index ->
            PerceptionElement(
                ref = "#n$index",
                role = "text",
                text = "x".repeat(200),
                centerX = index,
                centerY = index,
            )
        }

        val text = SmartPerceptionRenderer.render(snapshot(elements = elements), state(), PerceptionPolicy(), full = true)

        assertTrue("#n8" in text, "the perception slot must not be cut at the 500-code-point renderer default: $text")
        assertTrue(text.length > 500, "the block is longer than the default cap: length=${text.length}")
    }
}
