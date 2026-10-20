package com.sheen.rokid.phone.perception

import com.sheen.rokid.agent.PerceptionPolicy
import com.sheen.rokid.device.Bounds
import com.sheen.rokid.device.DeviceNode
import com.sheen.rokid.device.Foreground
import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.ObservationWindow
import com.sheen.rokid.device.Ref
import com.sheen.rokid.device.RefRegistry
import com.sheen.rokid.output.CompactSnapshotRenderer
import com.sheen.rokid.output.PerceptionCoverageRenderer
import com.sheen.rokid.phone.context.CalendarSnapshot
import com.sheen.rokid.phone.context.ClipboardRead
import com.sheen.rokid.phone.context.GlassesPerception
import com.sheen.rokid.phone.context.NotificationContent
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.MediaState
import com.sheen.rokid.plugin.PerceptionCoverage
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionSource
import com.sheen.rokid.plugin.PerceptionSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * edith-0.3 Phase 2 Task 6: the pure snapshot builder.
 *
 * Every source is driven as a plain value, so the whole model-facing shape is pinned on the JVM: which nodes become
 * elements, which noise is dropped, the filter and cap semantics, and the coverage token of every source (including
 * the unavailable/empty distinction). No Android class is touched.
 *
 * Synthetic content only: these tests never carry a real screen text, notification body or clipboard value.
 */
class PerceptionSnapshotBuilderTest {

    // ── Inputs ──────────────────────────────────────────────────────────────

    private fun node(
        role: String = "text",
        text: String? = null,
        description: String? = null,
        bounds: Bounds = Bounds(0, 0, 100, 100),
        states: Set<String> = emptySet(),
        actions: List<String> = emptyList(),
        hint: String? = null,
        windowId: Int = 1,
        depth: Int = 0,
        visibleToUser: Boolean = true,
    ): DeviceNode = DeviceNode(
        role = role,
        text = text,
        contentDescription = description,
        viewId = null,
        bounds = bounds,
        states = states,
        actions = actions,
        hint = hint,
        paneTitle = null,
        windowId = windowId,
        depth = depth,
        visibleToUser = visibleToUser,
    )

    private fun window(
        id: Int = 1,
        type: String = "application",
        packageName: String? = "com.example.app",
    ): ObservationWindow = ObservationWindow(
        id = id,
        type = type,
        layer = 0,
        bounds = Bounds(0, 0, 1080, 2400),
        packageName = packageName,
        title = null,
    )

    private fun observation(
        nodes: List<DeviceNode>,
        windows: List<ObservationWindow> = listOf(window()),
        truncated: Boolean = false,
    ): Observation = Observation(
        revision = 0L,
        windows = windows,
        nodes = nodes,
        truncated = truncated,
    )

    private fun refsOf(observation: Observation): Map<String, Ref> = RefRegistry().assign(observation)

    private fun deviceState(
        imeVisible: Boolean? = false,
        foregroundPackage: String? = "com.example.app",
    ): DeviceState = DeviceState(
        capturedAtEpochMillis = 0L,
        timeZone = "Asia/Shanghai",
        localDate = "2026-10-20",
        weekday = "Tuesday",
        localTime = "18:23",
        imeVisible = imeVisible,
        foregroundPackage = foregroundPackage,
    )

    private fun profile(width: Int = 1080, height: Int = 2400): DeviceProfile = DeviceProfile(
        manufacturer = "Xiaomi",
        model = "K40",
        deviceName = null,
        androidRelease = "13",
        apiLevel = 33,
        screenWidthPx = width,
        screenHeightPx = height,
        densityDpi = 440,
        glassesModel = null,
        glassesFirmware = null,
        glassesWidthPx = null,
        glassesHeightPx = null,
        appVersion = "0.3",
        locale = "zh-CN",
        timeZone = "Asia/Shanghai",
        wakeWords = emptyList(),
        capabilities = emptySet(),
    )

    private fun notification(
        title: String,
        packageName: String = "com.tencent.mm",
        appLabel: String? = "微信",
        text: String? = "合成正文",
    ): NotificationContent = NotificationContent(
        packageName = packageName,
        appLabel = appLabel,
        title = title,
        text = text,
        subText = null,
        bigText = null,
        category = "msg",
        postedAtEpochMillis = 1_700_000_000_000L,
        ongoing = false,
        actionLabels = listOf("回复"),
    )

    private fun build(
        snapshotId: String = "snap-1",
        capturedAtEpochMillis: Long = 1_234L,
        observation: Observation? = observation(listOf(node(text = "hello"))),
        refs: Map<String, Ref>? = null,
        foreground: Foreground? = Foreground("com.example.app", ".MainActivity"),
        notifications: List<NotificationContent> = emptyList(),
        notificationsConnected: Boolean = true,
        media: MediaState? = null,
        calendar: CalendarSnapshot = CalendarSnapshot(available = true, titles = emptyList()),
        calendarGranted: Boolean = true,
        deviceState: DeviceState? = deviceState(),
        profile: DeviceProfile? = profile(),
        clipboard: ClipboardRead? = ClipboardRead.Unavailable("empty"),
        acoustic: String? = null,
        glasses: GlassesPerception? = GlassesPerception(connected = true, wearing = true, panelVisible = false),
        visionEnabled: Boolean = true,
        policy: PerceptionPolicy = PerceptionPolicy(),
        include: Set<PerceptionSource> = PerceptionSource.entries.toSet(),
        filter: PerceptionFilter = PerceptionFilter.ALL,
        limit: Int? = null,
    ): PerceptionSnapshot = PerceptionSnapshotBuilder.build(
        snapshotId = snapshotId,
        capturedAtEpochMillis = capturedAtEpochMillis,
        observation = observation,
        refs = refs ?: observation?.let { refsOf(it) } ?: emptyMap(),
        foreground = foreground,
        notifications = notifications,
        notificationsConnected = notificationsConnected,
        media = media,
        calendar = calendar,
        calendarGranted = calendarGranted,
        deviceState = deviceState,
        profile = profile,
        clipboard = clipboard,
        acoustic = acoustic,
        glasses = glasses,
        visionEnabled = visionEnabled,
        policy = policy,
        include = include,
        filter = filter,
        limit = limit,
    )

    // ── All sources present ─────────────────────────────────────────────────

    @Test
    fun allSourcesPresentFillTheSnapshotAndItsCoverage() {
        val bounds = Bounds(440, 280, 640, 360)
        val observation = observation(
            listOf(
                node(
                    role = "button",
                    text = "搜索",
                    bounds = bounds,
                    states = setOf("clickable", "enabled"),
                    actions = listOf("click"),
                ),
            ),
        )

        val result = build(
            observation = observation,
            notifications = listOf(notification("张三: 晚上一起吃饭吗？"), notification("李四: 文件收到了")),
            media = MediaState("PLAYING", "稻香", "周杰伦", "魔杰座", 64_000L, 223_000L),
            calendar = CalendarSnapshot(available = true, titles = listOf("19:30 项目评审")),
            clipboard = ClipboardRead.Text("合成剪贴板内容"),
            acoustic = "speech",
        )

        assertEquals("snap-1", result.snapshotId)
        assertEquals(1_234L, result.capturedAtEpochMillis)
        assertEquals("com.example.app", result.foregroundPackage)
        assertEquals(".MainActivity", result.foregroundActivity)
        assertEquals(1080, result.screenWidthPx)
        assertEquals(2400, result.screenHeightPx)

        val element = result.elements.single()
        assertEquals("button", element.role)
        assertEquals("搜索", element.text)
        assertEquals("com.example.app", element.packageName)
        assertEquals(540, element.centerX)
        assertEquals(320, element.centerY)
        assertEquals("440,280,640,360", element.bounds)
        assertTrue(element.clickable)
        assertEquals(true, element.enabled)
        assertFalse(element.editable)
        assertFalse(element.scrollable)
        assertNull(element.checked)

        assertEquals(2, result.notifications.size)
        assertEquals("com.tencent.mm", result.notifications[0].packageName)
        assertEquals("微信", result.notifications[0].appLabel)
        assertEquals("张三: 晚上一起吃饭吗？", result.notifications[0].title)
        assertEquals("合成正文", result.notifications[0].text)
        assertEquals(listOf("回复"), result.notifications[0].actionLabels)

        assertEquals("PLAYING", result.media?.state)
        assertEquals("稻香", result.media?.title)
        assertEquals(listOf("19:30 项目评审"), result.calendarTitles)
        assertEquals("合成剪贴板内容", result.clipboardText)
        assertEquals("speech", result.acoustic)
        assertEquals(
            PerceptionCoverage(
                screen = "ok",
                notifications = "ok",
                media = "ok",
                calendar = "ok",
                acoustic = "ok",
                clipboard = "ok",
                vision = "ok",
                glasses = "ok",
                device = "ok",
            ),
            result.coverage,
        )
        assertFalse(result.truncated)
    }

    @Test
    fun aDeadAccessibilityChannelIsUnavailableWhileOtherSourcesStillReport() {
        val result = build(
            observation = null,
            notifications = listOf(notification("新消息")),
            media = MediaState("PAUSED", "歌"),
            calendar = CalendarSnapshot(available = true, titles = listOf("会议")),
            clipboard = ClipboardRead.Text("合成剪贴板"),
            acoustic = "speech",
        )

        assertTrue(result.elements.isEmpty())
        assertEquals("unavailable(no_accessibility)", result.coverage.screen)
        assertEquals("ok", result.coverage.notifications)
        assertEquals("ok", result.coverage.media)
        assertEquals("ok", result.coverage.calendar)
        assertEquals("ok", result.coverage.clipboard)
        assertEquals("ok", result.coverage.acoustic)
    }

    // ── Per-source coverage ─────────────────────────────────────────────────

    @Test
    fun withoutNotificationAccessNotificationsAreUnavailableNotEmpty() {
        val result = build(
            notifications = listOf(notification("通知")),
            notificationsConnected = false,
        )

        assertEquals("unavailable(no_access)", result.coverage.notifications)
        assertTrue("a refused source must not fabricate content", result.notifications.isEmpty())
    }

    @Test
    fun aConnectedListenerWithNothingRecentIsEmpty() {
        val result = build(notifications = emptyList(), notificationsConnected = true)

        assertEquals("empty", result.coverage.notifications)
    }

    @Test
    fun noMediaSessionIsUnavailable() {
        assertEquals("unavailable(no_session)", build(media = null).coverage.media)
    }

    @Test
    fun calendarKeepsNoPermissionProviderFailureAndEmptyDayApart() {
        val noPermission = build(
            calendarGranted = false,
            calendar = CalendarSnapshot(available = false, titles = emptyList()),
        )
        val providerFailure = build(
            calendarGranted = true,
            calendar = CalendarSnapshot(available = false, titles = emptyList()),
        )
        val emptyDay = build(
            calendarGranted = true,
            calendar = CalendarSnapshot(available = true, titles = emptyList()),
        )

        assertEquals("unavailable(no_permission)", noPermission.coverage.calendar)
        assertEquals("unavailable(provider)", providerFailure.coverage.calendar)
        assertEquals("empty", emptyDay.coverage.calendar)
    }

    @Test
    fun clipboardReportsBackgroundRestrictionEmptyAndDisabledApart() {
        val restricted = build(clipboard = ClipboardRead.Unavailable("background_restricted"))
        val empty = build(clipboard = ClipboardRead.Unavailable("empty"))
        val disabled = build(
            clipboard = ClipboardRead.Text("合成剪贴板"),
            policy = PerceptionPolicy(includeClipboard = false),
        )

        assertEquals("unavailable(background_restricted)", restricted.coverage.clipboard)
        assertNull(restricted.clipboardText)
        assertEquals("empty", empty.coverage.clipboard)
        assertNull(empty.clipboardText)
        assertEquals("unavailable(disabled)", disabled.coverage.clipboard)
        assertNull("a disabled clipboard never carries text", disabled.clipboardText)
    }

    @Test
    fun acousticAndGlassesReportUnavailableWhenNothingWasEverDelivered() {
        val silent = build(acoustic = null)
        val noGlasses = build(glasses = null)
        val glassesWithoutFacts = build(glasses = GlassesPerception())

        assertEquals("unavailable(no_event)", silent.coverage.acoustic)
        assertNull(silent.acoustic)
        assertEquals("unavailable(no_state)", noGlasses.coverage.glasses)
        assertEquals("unavailable(no_state)", glassesWithoutFacts.coverage.glasses)
    }

    @Test
    fun visionIsDisabledWhenThePolicyExcludesItOrTheGateIsOff() {
        assertEquals(
            "disabled",
            build(policy = PerceptionPolicy(includeVision = false), visionEnabled = true).coverage.vision,
        )
        assertEquals("disabled", build(visionEnabled = false).coverage.vision)
        assertEquals("ok", build(visionEnabled = true).coverage.vision)
    }

    @Test
    fun deviceIsPartialUntilForegroundAndImeAreBothKnown() {
        assertEquals("partial", build(foreground = null).coverage.device)
        assertEquals("partial", build(deviceState = deviceState(imeVisible = null)).coverage.device)
        assertEquals("partial", build(deviceState = null).coverage.device)
        assertEquals("ok", build().coverage.device)
    }

    // ── Caps and truncation ─────────────────────────────────────────────────

    @Test
    fun theElementCapCutsTheScreenAndMarksItTruncated() {
        val observation = observation(
            (0 until 5).map { index ->
                node(text = "节点$index", bounds = Bounds(0, index * 100, 100, index * 100 + 80))
            },
        )

        val result = build(observation = observation, limit = 2)

        assertEquals(listOf("节点0", "节点1"), result.elements.map { it.text })
        assertEquals("truncated", result.coverage.screen)
        assertTrue(result.truncated)
    }

    @Test
    fun theEffectiveElementLimitIsTheSmallerOfTheCallerLimitAndThePolicy() {
        val observation = observation(
            (0 until 5).map { index ->
                node(text = "节点$index", bounds = Bounds(0, index * 100, 100, index * 100 + 80))
            },
        )
        val policy = PerceptionPolicy(elementLimit = 2)

        assertEquals(2, build(observation = observation, policy = policy, limit = 99).elements.size)
        assertEquals(2, build(observation = observation, policy = policy, limit = null).elements.size)
        assertEquals(1, build(observation = observation, policy = policy, limit = 1).elements.size)
    }

    @Test
    fun aProducerTruncatedObservationMarksTheScreenTruncatedEvenWhenElementsFit() {
        val result = build(observation = observation(listOf(node(text = "唯一")), truncated = true))

        assertEquals("truncated", result.coverage.screen)
        assertTrue(result.truncated)
        assertEquals(1, result.elements.size)
    }

    @Test
    fun theNotificationPolicyCapCutsTheListAndMarksItTruncated() {
        val four = (0 until 4).map { notification("标题$it") }
        val three = four.take(3)
        val policy = PerceptionPolicy(notificationLimit = 3)

        val cut = build(notifications = four, policy = policy)
        val fits = build(notifications = three, policy = policy)

        assertEquals(3, cut.notifications.size)
        assertEquals("truncated", cut.coverage.notifications)
        assertEquals("the screen itself was complete", "ok", cut.coverage.screen)
        assertTrue(cut.truncated)
        assertEquals(3, fits.notifications.size)
        assertEquals("ok", fits.coverage.notifications)
        assertFalse(fits.truncated)
    }

    @Test
    fun aNotificationOnlyCutLeavesTheCompactScreenTextComplete() {
        // Task 7 review I3 end to end through the real builder: the snapshot-wide flag is true (notifications were
        // cut), but the screen is intact, so the compact text must carry no truncation marker.
        val cut = build(
            observation = observation(listOf(node(text = "完整屏幕"))),
            notifications = (0 until 4).map { notification("标题$it") },
            policy = PerceptionPolicy(notificationLimit = 3),
        )

        assertTrue("the snapshot as a whole was bounded", cut.truncated)
        assertEquals("ok", cut.coverage.screen)
        val text = CompactSnapshotRenderer.render(cut)
        assertTrue("the screen's own content is rendered", text.contains("完整屏幕"))
        assertFalse(
            "a notification cut must not append the screen marker: $text",
            text.contains(CompactSnapshotRenderer.TRUNCATION_MARKER),
        )
    }

    // ── Filters ─────────────────────────────────────────────────────────────

    private fun mixedObservation(): Observation = observation(
        listOf(
            node(text = "普通文本"),
            node(role = "button", text = "按钮", states = setOf("clickable")),
            node(role = "edittext", text = "输入框", states = setOf("editable")),
            node(role = "container", states = setOf("scrollable")),
            node(role = "checkbox", text = "勾选", states = setOf("checkable", "checked")),
            node(text = "聚焦文本", states = setOf("focused")),
            node(text = "长按文本", states = setOf("long_clickable")),
            node(hint = "只有提示"),
        ),
    )

    @Test
    fun interactiveFilterKeepsOnlyNodesWithActionsOrInteractionStates() {
        val result = build(observation = mixedObservation(), filter = PerceptionFilter.INTERACTIVE)

        assertEquals(
            listOf("按钮", "输入框", null, "勾选", "聚焦文本", "长按文本"),
            result.elements.map { it.text },
        )
    }

    @Test
    fun textFilterKeepsNodesCarryingTextDescriptionOrHint() {
        val result = build(observation = mixedObservation(), filter = PerceptionFilter.TEXT)

        assertEquals(
            listOf("普通文本", "按钮", "输入框", "勾选", "聚焦文本", "长按文本", null),
            result.elements.map { it.text },
        )
    }

    @Test
    fun allFilterKeepsEveryVisibleNode() {
        val result = build(observation = mixedObservation(), filter = PerceptionFilter.ALL)

        assertEquals(8, result.elements.size)
    }

    // ── Noise filtering ─────────────────────────────────────────────────────

    @Test
    fun invisibleNodesAreNeverModelFacing() {
        val result = build(
            observation = observation(
                listOf(
                    node(text = "可见"),
                    node(text = "不可见", visibleToUser = false),
                ),
            ),
        )

        assertEquals(listOf("可见"), result.elements.map { it.text })
    }

    @Test
    fun zeroSizedNodesAreDropped() {
        val result = build(
            observation = observation(
                listOf(
                    node(text = "正常", bounds = Bounds(0, 0, 100, 100)),
                    node(text = "零宽", bounds = Bounds(10, 10, 10, 100)),
                    node(text = "零高", bounds = Bounds(10, 10, 100, 10)),
                ),
            ),
        )

        assertEquals(listOf("正常"), result.elements.map { it.text })
    }

    @Test
    fun duplicateNodesAreKeptOnce() {
        val bounds = Bounds(0, 0, 200, 100)
        val result = build(
            observation = observation(
                listOf(
                    node(role = "button", text = "重复", bounds = bounds, depth = 0),
                    node(role = "button", text = "重复", bounds = bounds, depth = 1),
                ),
            ),
        )

        assertEquals(1, result.elements.size)
        assertEquals("重复", result.elements.single().text)
    }

    @Test
    fun systemUiStatusAndNavigationWindowsAreNotModelFacing() {
        val result = build(
            observation = observation(
                nodes = listOf(
                    node(text = "应用正文"),
                    node(text = "状态栏文本", windowId = 2),
                    node(text = "导航栏按钮", windowId = 3),
                ),
                windows = listOf(
                    window(id = 1, type = "application", packageName = "com.example.app"),
                    window(id = 2, type = "system", packageName = "com.android.systemui"),
                    window(id = 3, type = "application", packageName = "com.android.systemui"),
                ),
            ),
        )

        assertEquals(listOf("应用正文"), result.elements.map { it.text })
    }

    @Test
    fun messageMailWebAndInputTextAreNeverDroppedAsNoise() {
        val result = build(
            observation = observation(
                nodes = listOf(
                    node(role = "textview", text = "张三: 晚上一起吃饭吗？"),
                    node(role = "webview", text = "网页正文内容"),
                    node(role = "textview", text = "邮件正文内容"),
                    node(role = "edittext", text = "输入框里已输入的内容", windowId = 2),
                ),
                windows = listOf(
                    window(id = 1, type = "application", packageName = "com.tencent.mm"),
                    window(id = 2, type = "ime", packageName = "com.android.inputmethod.latin"),
                ),
            ),
        )

        assertEquals(
            listOf("张三: 晚上一起吃饭吗？", "网页正文内容", "邮件正文内容", "输入框里已输入的内容"),
            result.elements.map { it.text },
        )
    }

    @Test
    fun boundsStayInTheContractFieldAndAreNotLeakedIntoModelText() {
        val result = build(
            observation = observation(listOf(node(text = "原始文本", bounds = Bounds(1, 2, 3, 4)))),
        )

        val element = result.elements.single()
        assertEquals("原始文本", element.text)
        assertEquals("1,2,3,4", element.bounds)
        assertEquals(2, element.centerX)
        assertEquals(3, element.centerY)
    }

    @Test
    fun coverageRendererRendersTheExactTokenLineAndNoBounds() {
        val coverage = PerceptionCoverage(
            screen = "ok",
            notifications = "truncated",
            media = "unavailable(no_session)",
            calendar = "empty",
            acoustic = "unavailable(no_event)",
            clipboard = "unavailable(background_restricted)",
            vision = "disabled",
            glasses = "unavailable(no_state)",
            device = "partial",
        )

        val rendered = PerceptionCoverageRenderer.render(coverage)

        assertEquals(
            "screen=ok notifications=truncated media=unavailable(no_session) calendar=empty " +
                "acoustic=unavailable(no_event) clipboard=unavailable(background_restricted) " +
                "vision=disabled glasses=unavailable(no_state) device=partial",
            rendered,
        )
        assertFalse("coverage is model-facing text and must never carry bounds", rendered.contains("bounds"))
    }

    // ── include projection ─────────────────────────────────────────────────

    @Test
    fun includeLimitsContentToTheRequestedSourcesWhileCoverageStaysHonest() {
        val result = build(
            observation = observation(listOf(node(text = "屏幕文本"))),
            notifications = listOf(notification("通知")),
            media = MediaState("PLAYING", "歌"),
            calendar = CalendarSnapshot(available = true, titles = listOf("会议")),
            clipboard = ClipboardRead.Text("合成剪贴板"),
            acoustic = "speech",
            include = setOf(PerceptionSource.MEDIA),
        )

        assertTrue(result.elements.isEmpty())
        assertTrue(result.notifications.isEmpty())
        assertTrue(result.calendarTitles.isEmpty())
        assertNull(result.clipboardText)
        assertNull(result.acoustic)
        assertEquals("PLAYING", result.media?.state)
        assertEquals("ok", result.coverage.screen)
        assertEquals("ok", result.coverage.notifications)
        assertEquals("ok", result.coverage.clipboard)
    }
}
