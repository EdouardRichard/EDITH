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
