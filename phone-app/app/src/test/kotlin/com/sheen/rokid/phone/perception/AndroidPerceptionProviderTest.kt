package com.sheen.rokid.phone.perception

import android.content.ComponentName
import android.content.ContextWrapper
import com.sheen.rokid.agent.PerceptionPolicy
import com.sheen.rokid.device.Bounds
import com.sheen.rokid.device.DeviceNode
import com.sheen.rokid.device.Foreground
import com.sheen.rokid.device.Observation
import com.sheen.rokid.device.ObservationWindow
import com.sheen.rokid.device.Ref
import com.sheen.rokid.device.RefRegistry
import com.sheen.rokid.device.RefResult
import com.sheen.rokid.device.ScreenFilter
import com.sheen.rokid.phone.app.autoVisionProfileSupportsImage
import com.sheen.rokid.phone.context.AcousticSource
import com.sheen.rokid.phone.context.CalendarSource
import com.sheen.rokid.phone.context.ClipboardSource
import com.sheen.rokid.phone.context.GlassesStateSource
import com.sheen.rokid.phone.context.MediaSessionSource
import com.sheen.rokid.phone.context.NotificationContent
import com.sheen.rokid.phone.context.NotificationContentSource
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.DeviceProfile
import com.sheen.rokid.plugin.DeviceState
import com.sheen.rokid.plugin.DeviceStateProvider
import com.sheen.rokid.plugin.ImageRef
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.ModelProfile
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.PerceptionFilter
import com.sheen.rokid.plugin.PerceptionScreenshotResult
import com.sheen.rokid.plugin.PerceptionSource
import com.sheen.rokid.plugin.SecretHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * edith-0.3 Phase 2 Task 6: the turn lifecycle of [AndroidPerceptionProvider].
 *
 * The provider is driven with an injected observe lambda, foreground lambda and fake sources, so these tests pin the
 * load-bearing order (observe -> foreground -> beginSnapshot -> assign -> build), the re-bind after an action, the
 * cached rebuild of [AndroidPerceptionProvider.snapshot], and that a turn's refs die at [AndroidPerceptionProvider.endTurn].
 *
 * Synthetic content only: no real screen text, notification body or clipboard value.
 */
class AndroidPerceptionProviderTest {

    // ── Fixtures ────────────────────────────────────────────────────────────

    private fun node(
        role: String = "text",
        text: String? = null,
        description: String? = null,
        bounds: Bounds = Bounds(0, 0, 100, 100),
        states: Set<String> = emptySet(),
        actions: List<String> = emptyList(),
        windowId: Int = 1,
        depth: Int = 0,
    ): DeviceNode = DeviceNode(
        role = role,
        text = text,
        contentDescription = description,
        viewId = null,
        bounds = bounds,
        states = states,
        actions = actions,
        hint = null,
        paneTitle = null,
        windowId = windowId,
        depth = depth,
    )

    private fun window(): ObservationWindow = ObservationWindow(
        id = 1,
        type = "application",
        layer = 0,
        bounds = Bounds(0, 0, 1080, 2400),
        packageName = "com.example.app",
        title = null,
    )

    private fun observation(
        nodes: List<DeviceNode>,
        truncated: Boolean = false,
    ): Observation = Observation(
        revision = 0L,
        windows = listOf(window()),
        nodes = nodes,
        truncated = truncated,
    )

    private fun deviceProfile(): DeviceProfile = DeviceProfile(
        manufacturer = "Xiaomi",
        model = "K40",
        deviceName = null,
        androidRelease = "13",
        apiLevel = 33,
        screenWidthPx = 1080,
        screenHeightPx = 2400,
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

    private fun deviceState(): DeviceState = DeviceState(
        capturedAtEpochMillis = 0L,
        timeZone = "Asia/Shanghai",
        localDate = "2026-10-20",
        weekday = "Tuesday",
        localTime = "18:23",
        imeVisible = false,
        foregroundPackage = "com.example.app",
    )

    /**
     * A profile that declares `IMAGE` but carries only the verifications the caller scripts. The production resolver
     * (`autoVisionProfileSupportsImage`) is what decides the provider's second gate, so tests can pin the
     * declared-vs-verified boundary end to end through [AndroidPerceptionProvider.screenshot].
     */
    private fun declaredImageProfile(
        verifications: List<CapabilityVerification> = emptyList(),
    ): ModelProfile = ModelProfile(
        id = "declared-image-profile",
        revision = 1,
        name = "Synthetic declared IMAGE",
        service = "Synthetic",
        baseUrl = "https://synthetic.example/v1",
        protocolId = "DEEPSEEK_RESPONSES",
        model = "synthetic-model",
        secret = object : SecretHandle { override val id = "synthetic-secret" },
        usableWindowTokens = 4096,
        inputBudgetTokens = 2048,
        maxOutputTokens = 1024,
        safetyTokens = 128,
        timeoutMillis = 5000,
        inputs = setOf(InputModality.TEXT, InputModality.IMAGE),
        outputs = setOf(OutputModality.TEXT),
        verifications = verifications,
    )

    private fun notification(title: String): NotificationContent = NotificationContent(
        packageName = "com.tencent.mm",
        appLabel = "微信",
        title = title,
        text = "合成正文",
        subText = null,
        bigText = null,
        category = "msg",
        postedAtEpochMillis = 1_700_000_000_000L,
        ongoing = false,
        actionLabels = listOf("回复"),
    )

    /**
     * One provider instance over scripted fakes.
     *
     * [observe] also refreshes [latest], like the production kernel-backed lambda must: the provider itself never
     * calls [latestObservation] at the start of a turn, so a test of that rule has to see the cache move underneath.
     */
    private inner class Harness {
        val registry = RefRegistry()
        var observationResult: Observation? = observation(listOf(node(text = "屏幕文本")))
        var observeFailure: Throwable? = null
        var observeCalls = 0
        val observeFilters = mutableListOf<ScreenFilter>()
        var latest: Observation? = null
        var foregroundValue: Foreground? = Foreground("com.example.app", ".MainActivity")
        var foregroundThrows = false
        var now = 1_000L
        var policyValue = PerceptionPolicy()
        var vision = true
        var profileSupportsImage: () -> Boolean = { true }
        var screenshotCalls = 0
        var screenshotThrows = false
        var screenshotResult: PerceptionScreenshotResult =
            PerceptionScreenshotResult.Unavailable("NO_ACCESSIBILITY")
        var clipboardReads = 0
        var clipboardValue: CharSequence? = null
        var notificationsConnected = true
        var notificationsThrow = false
        var notifications = listOf<NotificationContent>()
        var calendarGranted = true
        var calendarTitles = listOf<String>()
        var calendarThrows = false
        var acousticLabel: String? = null
        var stateValue: DeviceState = deviceState()

        private val deviceStateProvider = object : DeviceStateProvider {
            override fun profile(): DeviceProfile = deviceProfile()

            override fun state(): DeviceState = stateValue
        }

        val provider = AndroidPerceptionProvider(
            observe = { filter ->
                observeCalls++
                observeFilters += filter
                observeFailure?.let { throw it }
                val result = observationResult ?: error("no observation scripted")
                latest = result
                result
            },
            foreground = {
                if (foregroundThrows) error("foreground unavailable") else foregroundValue
            },
            registry = registry,
            notifications = NotificationContentSource(
                connected = { notificationsConnected },
                recent = { limit ->
                    if (notificationsThrow) error("notification binder died") else notifications.take(limit)
                },
                category = { null },
            ),
            calendar = CalendarSource(
                granted = { calendarGranted },
                today = { if (calendarThrows) error("calendar provider died") else calendarTitles },
            ),
            media = MediaSessionSource(
                ContextWrapper(null),
                ComponentName("com.example.app", ".MediaService"),
            ),
            deviceState = deviceStateProvider,
            clipboard = ClipboardSource(
                read = {
                    clipboardReads++
                    clipboardValue
                },
                foreground = { true },
            ),
            acoustic = AcousticSource { acousticLabel },
            glasses = GlassesStateSource(
                connected = { true },
                wearing = { true },
                panelVisible = { false },
            ),
            policy = { policyValue },
            visionEnabled = { vision },
            // Read through the property on every call so a test can re-script the gate after construction.
            profileSupportsImage = { profileSupportsImage() },
            screenshot = {
                screenshotCalls++
                if (screenshotThrows) error("capture died") else screenshotResult
            },
            latestObservation = { latest },
            clock = { now },
        )
    }

    // ── beginTurn ───────────────────────────────────────────────────────────

    @Test
    fun beginTurnObservesTheWholeScreenAndStampsANewSnapshot() {
        val harness = Harness()
        val observation = observation(
            listOf(
                node(text = "A"),
                node(role = "button", text = "B", states = setOf("clickable"), actions = listOf("click")),
            ),
        )
        harness.observationResult = observation

        val snapshot = harness.provider.beginTurn()

        assertEquals(1, harness.observeCalls)
        assertEquals(listOf(ScreenFilter.ALL), harness.observeFilters)
        val current = harness.registry.currentSnapshot()
        assertNotNull(current)
        assertEquals(snapshot.snapshotId, current?.snapshotId)
        assertEquals(1_000L, current?.issuedAtEpochMillis)
        assertEquals("com.example.app", current?.foregroundPackage)
        assertEquals(".MainActivity", current?.foregroundActivity)
        assertEquals(listOf("A", "B"), snapshot.elements.map { it.text })
        assertEquals(listOf("#n0", "#n1"), snapshot.elements.map { it.ref })
    }

    @Test
    fun beginTurnRefreshesTheObservationInsteadOfUsingAPreTurnKernelCache() {
        val harness = Harness()
        harness.latest = observation(listOf(node(text = "回合前的旧屏幕")))
        val fresh = observation(listOf(node(text = "刚观察到的屏幕")))
        harness.observationResult = fresh

        val snapshot = harness.provider.beginTurn()

        assertEquals(listOf("刚观察到的屏幕"), snapshot.elements.map { it.text })
        assertSame("the injected observe lambda is what refreshes the kernel cache", fresh, harness.latest)
        assertEquals(1, harness.observeCalls)
    }

    @Test
    fun beginTurnUsesTheInjectedClockAndProfileDimensions() {
        val harness = Harness()
        harness.now = 777L

        val snapshot = harness.provider.beginTurn()

        assertEquals(777L, snapshot.capturedAtEpochMillis)
        assertEquals(777L, harness.registry.currentSnapshot()?.issuedAtEpochMillis)
        assertEquals(1080, snapshot.screenWidthPx)
        assertEquals(2400, snapshot.screenHeightPx)
    }

    @Test
    fun refreshObservesTheScreenAndOpensANewSnapshotWhileSnapshotStaysCached() {
        // 0.3 Phase 2 final review C1: an explicit `device.observe` is a real read, not the cached turn snapshot. `refresh`
        // mirrors `beginTurn`'s load-bearing order (observe -> foreground -> beginSnapshot -> assign -> build) without
        // starting a turn; `snapshot` keeps its cache-only contract.
        val harness = Harness()
        val first = harness.provider.beginTurn()
        harness.provider.snapshot(setOf(PerceptionSource.SCREEN), PerceptionFilter.ALL, null)
        assertEquals("snapshot must never observe", 1L, harness.observeCalls.toLong())

        harness.observationResult = observation(listOf(node(text = "手动切屏后的新内容")))
        val refreshed = harness.provider.refresh()

        assertEquals(2, harness.observeCalls)
        assertEquals(listOf(ScreenFilter.ALL, ScreenFilter.ALL), harness.observeFilters)
        assertNotEquals(first.snapshotId, refreshed.snapshotId)
        assertEquals(listOf("手动切屏后的新内容"), refreshed.elements.map { it.text })
        assertEquals(refreshed.snapshotId, harness.registry.currentSnapshot()?.snapshotId)

        val projected = harness.provider.snapshot(setOf(PerceptionSource.SCREEN), PerceptionFilter.ALL, null)
        assertEquals("snapshot after refresh stays cache-only", 2L, harness.observeCalls.toLong())
        assertEquals(refreshed.snapshotId, projected.snapshotId)
        assertEquals(listOf("手动切屏后的新内容"), projected.elements.map { it.text })
    }

    @Test
    fun anUnknownForegroundKeepsTheSnapshotButMarksTheDevicePartial() {
        val harness = Harness()
        harness.foregroundValue = null

        val snapshot = harness.provider.beginTurn()

        assertNull(snapshot.foregroundPackage)
        assertNull(snapshot.foregroundActivity)
        assertEquals("partial", snapshot.coverage.device)
        assertNull(harness.registry.currentSnapshot()?.foregroundPackage)
    }

    @Test
    fun aForegroundFailureLeavesTheSnapshotHonestAndPartial() {
        val harness = Harness()
        harness.foregroundThrows = true

        val snapshot = harness.provider.beginTurn()

        assertNull(snapshot.foregroundPackage)
        assertEquals("partial", snapshot.coverage.device)
        assertNull(harness.registry.currentSnapshot()?.foregroundPackage)
    }

    @Test
    fun beginTurnDegradesOnlyTheScreenWhenTheAccessibilityReadFails() {
        val harness = Harness()
        harness.observeFailure = IllegalStateException("accessibility died")
        harness.notifications = listOf(notification("新消息"))
        harness.clipboardValue = "合成剪贴板"
        harness.acousticLabel = "speech"
        harness.calendarTitles = listOf("会议")

        val snapshot = harness.provider.beginTurn()

        assertEquals("an unknown failure is not blamed on the permission", "unavailable(observe_failed)",
            snapshot.coverage.screen)
        assertTrue(snapshot.elements.isEmpty())
        assertEquals("ok", snapshot.coverage.notifications)
        assertEquals("ok", snapshot.coverage.clipboard)
        assertEquals("ok", snapshot.coverage.acoustic)
        assertEquals("ok", snapshot.coverage.calendar)
        assertNotNull(harness.registry.currentSnapshot())
    }

    @Test
    fun aLockedPhoneIsReportedAsLockedNotAsAMissingPermission() {
        val harness = Harness()
        harness.observeFailure = ScreenReadUnavailable("DEVICE_LOCKED")

        val snapshot = harness.provider.beginTurn()

        assertEquals("unavailable(locked)", snapshot.coverage.screen)
        assertTrue(snapshot.elements.isEmpty())
    }

    @Test
    fun aDozingPhoneIsReportedAsScreenOff() {
        val harness = Harness()
        harness.observeFailure = ScreenReadUnavailable("DEVICE_SCREEN_OFF")

        val snapshot = harness.provider.beginTurn()

        assertEquals("unavailable(screen_off)", snapshot.coverage.screen)
    }

    @Test
    fun anUnboundAccessibilityChannelStillReportsNoAccessibility() {
        val harness = Harness()
        harness.observeFailure = ScreenReadUnavailable("CHANNEL_UNAVAILABLE")

        val snapshot = harness.provider.beginTurn()

        assertEquals("unavailable(no_accessibility)", snapshot.coverage.screen)
    }

    @Test
    fun aThrowingCalendarProviderDegradesOnlyTheCalendarSource() {
        val harness = Harness()
        harness.calendarThrows = true

        val snapshot = harness.provider.beginTurn()

        assertEquals("unavailable(provider)", snapshot.coverage.calendar)
        assertEquals("ok", snapshot.coverage.screen)
        assertEquals("ok", snapshot.coverage.device)
    }

    @Test
    fun aThrowingNotificationProducerReportsUnavailableProviderNotEmpty() {
        // Final review I3: a binder failure is not "no notifications". The coverage token must say the source could not
        // answer, while a genuinely empty list stays `empty`.
        val harness = Harness()
        harness.notifications = listOf(notification("合成消息"))
        harness.notificationsThrow = true

        val snapshot = harness.provider.beginTurn()

        assertEquals("unavailable(provider)", snapshot.coverage.notifications)
        assertTrue(snapshot.notifications.isEmpty())
        assertEquals("only the notification source degrades", "ok", snapshot.coverage.screen)
    }

    @Test
    fun aDisconnectedNotificationListenerStillReportsNoAccess() {
        val harness = Harness()
        harness.notificationsConnected = false

        val snapshot = harness.provider.beginTurn()

        assertEquals("unavailable(no_access)", snapshot.coverage.notifications)
        assertTrue(snapshot.notifications.isEmpty())
    }

    // ── afterAction ─────────────────────────────────────────────────────────

    @Test
    fun afterActionRebindsANewSnapshotAndAnOldRefIsCrossSnapshot() {
        val harness = Harness()
        val before = observation(
            listOf(
                node(
                    role = "button",
                    text = "旧节点",
                    states = setOf("clickable"),
                    actions = listOf("click"),
                ),
                node(text = "留下"),
            ),
        )
        harness.observationResult = before
        val first = harness.provider.beginTurn()
        val oldRef = Ref(first.elements.first { it.text == "旧节点" }.ref)

        val after = observation(listOf(node(text = "留下")))
        harness.latest = after
        harness.now = 2_000L
        val second = harness.provider.afterAction()

        assertNotEquals(first.snapshotId, second.snapshotId)
        assertEquals("the post-action read comes from the kernel cache, not a new observe", 1, harness.observeCalls)
        val resolution = harness.registry.resolveForAction(
            after,
            oldRef,
            2_000L,
            "com.example.app",
            ".MainActivity",
            20_000L,
        )
        assertTrue(resolution is RefResult.Stale)
        assertEquals("CROSS_SNAPSHOT", (resolution as RefResult.Stale).code)
        assertEquals(listOf("留下"), second.elements.map { it.text })
    }

    @Test
    fun afterActionRestampsARefWhoseNodeSurvived() {
        val harness = Harness()
        val observation = observation(
            listOf(
                node(role = "button", text = "按钮", states = setOf("clickable"), actions = listOf("click")),
            ),
        )
        harness.observationResult = observation
        val first = harness.provider.beginTurn()

        harness.latest = observation
        harness.now = 2_000L
        val second = harness.provider.afterAction()

        assertNotEquals(first.snapshotId, second.snapshotId)
        val ref = Ref(first.elements.single().ref)
        val resolution = harness.registry.resolveForAction(
            observation,
            ref,
            2_000L,
            "com.example.app",
            ".MainActivity",
            20_000L,
        )
        assertTrue("a surviving node is re-stamped for the new snapshot", resolution is RefResult.Ok)
    }

    @Test
    fun afterActionRebindsTheForegroundFactsOfTheNewSnapshot() {
        val harness = Harness()
        val observation = observation(listOf(node(text = "屏幕")))
        harness.observationResult = observation
        harness.provider.beginTurn()

        harness.latest = observation
        harness.foregroundValue = Foreground("com.example.other", ".OtherActivity")
        harness.now = 3_000L
        val snapshot = harness.provider.afterAction()

        assertEquals("com.example.other", snapshot.foregroundPackage)
        assertEquals(".OtherActivity", snapshot.foregroundActivity)
        assertEquals("com.example.other", harness.registry.currentSnapshot()?.foregroundPackage)
    }

    @Test
    fun afterActionReObservesWhenTheKernelCacheIsEmpty() {
        val harness = Harness()
        harness.latest = null
        harness.observationResult = observation(listOf(node(text = "动作后")))

        val snapshot = harness.provider.afterAction()

        assertEquals(1, harness.observeCalls)
        assertEquals(listOf(ScreenFilter.ALL), harness.observeFilters)
        assertEquals(listOf("动作后"), snapshot.elements.map { it.text })
    }

    // ── snapshot ────────────────────────────────────────────────────────────

    @Test
    fun snapshotRebuildsFromTheCacheWithoutObservingOrRebinding() {
        val harness = Harness()
        harness.observationResult = observation(listOf(node(text = "屏幕文本")))
        val first = harness.provider.beginTurn()
        val current = harness.registry.currentSnapshot()
        harness.clipboardValue = "新的剪贴板"

        val second = harness.provider.snapshot(
            PerceptionSource.entries.toSet(),
            PerceptionFilter.ALL,
            null,
        )

        assertEquals(1, harness.observeCalls)
        assertEquals(current, harness.registry.currentSnapshot())
        assertEquals(first.snapshotId, second.snapshotId)
        assertEquals(first.capturedAtEpochMillis, second.capturedAtEpochMillis)
        assertEquals(first.elements.map { it.ref }, second.elements.map { it.ref })
        assertEquals("the live sources are re-read on every rebuild", "新的剪贴板", second.clipboardText)
        assertEquals("ok", second.coverage.clipboard)
    }

    @Test
    fun snapshotPassesTheCallerFilterAndLimitThrough() {
        val harness = Harness()
        harness.observationResult = observation(
            listOf(node(text = "一"), node(text = "二"), node(text = "三")),
        )
        harness.provider.beginTurn()

        val filtered = harness.provider.snapshot(
            PerceptionSource.entries.toSet(),
            PerceptionFilter.TEXT,
            2,
        )

        assertEquals(listOf("一", "二"), filtered.elements.map { it.text })
        assertEquals("truncated", filtered.coverage.screen)
        assertTrue(filtered.truncated)
    }

    @Test
    fun aDisabledClipboardPolicyNeverReadsThePlatformClipboard() {
        val harness = Harness()
        harness.policyValue = PerceptionPolicy(includeClipboard = false)
        harness.clipboardValue = "不应该被读取"

        val snapshot = harness.provider.beginTurn()

        assertEquals("unavailable(disabled)", snapshot.coverage.clipboard)
        assertNull(snapshot.clipboardText)
        assertEquals(0, harness.clipboardReads)
    }

    // ── screenshot ──────────────────────────────────────────────────────────

    @Test
    fun screenshotIsDisabledWithoutAskingTheKernelWhenVisionIsOff() {
        val harness = Harness()
        harness.vision = false

        assertEquals(PerceptionScreenshotResult.Disabled, harness.provider.screenshot())
        assertEquals(0, harness.screenshotCalls)
    }

    @Test
    fun screenshotReturnsTheInjectedImageWhenVisionIsOn() {
        val harness = Harness()
        val image = PerceptionScreenshotResult.Image(
            image = ImageRef("artifact-1", "image/jpeg", 10, 20, "sha256"),
            mime = "image/jpeg",
            width = 10,
            height = 20,
        )
        harness.screenshotResult = image

        assertSame(image, harness.provider.screenshot())
    }

    @Test
    fun screenshotReturnsTheInjectedUnavailableWhenVisionIsOn() {
        val harness = Harness()
        harness.screenshotResult = PerceptionScreenshotResult.Unavailable("NO_ACCESSIBILITY")

        assertEquals(
            PerceptionScreenshotResult.Unavailable("NO_ACCESSIBILITY"),
            harness.provider.screenshot(),
        )
    }

    @Test
    fun screenshotFailureIsAnUnavailableResultNeverAnException() {
        val harness = Harness()
        harness.screenshotThrows = true

        assertEquals(
            PerceptionScreenshotResult.Unavailable("SCREENSHOT_FAILED"),
            harness.provider.screenshot(),
        )
    }

    // ── screenshot: profile-aware gate (Phase 2 Task 2) ─────────────────────

    @Test
    fun screenshotIsDisabledWhenUserVisionOff() {
        // The user switch wins over the profile gate: a profile that does carry IMAGE still gets `Disabled`, and the
        // injected capture is never asked for an image it would have to throw away.
        val harness = Harness()
        harness.vision = false
        harness.profileSupportsImage = { true }

        assertEquals(PerceptionScreenshotResult.Disabled, harness.provider.screenshot())
        assertEquals(0, harness.screenshotCalls)
    }

    @Test
    fun screenshotIsUnavailableWhenProfileHasNoImage() {
        // The second gate: a profile without the IMAGE input must not attach an image, or the plugin's CapabilityGate
        // rejects the whole request. This is an explicit absence, not a capture attempt.
        val harness = Harness()
        harness.vision = true
        harness.profileSupportsImage = { false }

        assertEquals(
            PerceptionScreenshotResult.Unavailable("no_image_profile"),
            harness.provider.screenshot(),
        )
        assertEquals(0, harness.screenshotCalls)
    }

    @Test
    fun screenshotIsUnavailableWhenProfileDeclaresImageButIsNotVerified() {
        // Final whole-branch fix: the production second gate is autoVisionProfileSupportsImage. A profile that only
        // declares IMAGE would let the first auto image reach CapabilityGate, which requires the current revision's
        // latest IMAGE_REQUEST to be Verified and otherwise throws CapabilityMissing for the whole turn. The provider
        // must read the gate as false and never touch the capture channel.
        val harness = Harness()
        harness.vision = true
        val declared = declaredImageProfile(verifications = emptyList())
        harness.profileSupportsImage = {
            autoVisionProfileSupportsImage(
                selection = { declared.id },
                load = { id -> declared.takeIf { it.id == id } },
                fallback = { null },
            )
        }

        assertEquals(
            PerceptionScreenshotResult.Unavailable("no_image_profile"),
            harness.provider.screenshot(),
        )
        assertEquals(
            "a declared-but-unverified image profile must not capture",
            0,
            harness.screenshotCalls,
        )
    }

    @Test
    fun screenshotUsesCaptureWhenUserAndProfileAllowIt() {
        val harness = Harness()
        harness.vision = true
        harness.profileSupportsImage = { true }
        val image = PerceptionScreenshotResult.Image(
            image = ImageRef("artifact-2", "image/jpeg", 10, 20, "sha256"),
            mime = "image/jpeg",
            width = 10,
            height = 20,
        )
        harness.screenshotResult = image

        assertSame(image, harness.provider.screenshot())
        assertEquals(1, harness.screenshotCalls)
    }

    @Test
    fun screenshotCaptureFailureIsUnavailableWithCode() {
        val harness = Harness()
        harness.vision = true
        harness.profileSupportsImage = { true }
        harness.screenshotThrows = true

        assertEquals(
            PerceptionScreenshotResult.Unavailable("SCREENSHOT_FAILED"),
            harness.provider.screenshot(),
        )
    }

    // ── endTurn ─────────────────────────────────────────────────────────────

    @Test
    fun endTurnClearsTheSnapshotAndEveryRef() {
        val harness = Harness()
        val observation = observation(listOf(node(text = "屏幕")))
        harness.observationResult = observation
        val snapshot = harness.provider.beginTurn()
        val ref = Ref(snapshot.elements.single().ref)

        harness.provider.endTurn()

        assertNull(harness.registry.currentSnapshot())
        assertTrue(
            "no ref survives a turn",
            harness.provider.snapshot(PerceptionSource.entries.toSet(), PerceptionFilter.ALL, null)
                .elements.isEmpty(),
        )
        val resolution = harness.registry.resolveForAction(
            observation,
            ref,
            1_000L,
            "com.example.app",
            ".MainActivity",
            20_000L,
        )
        assertTrue(resolution is RefResult.Stale)
        assertEquals("STALE_REF", (resolution as RefResult.Stale).code)
    }
}
