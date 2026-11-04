package com.sheen.rokid.phone.settings

import com.sheen.rokid.host.HostRuntime
import com.sheen.rokid.phone.app.HostCompositionRoot
import com.sheen.rokid.phone.plugin.smarthelper.host.DetachGate
import com.sheen.rokid.phone.plugin.smarthelper.host.ProactiveArming
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettings
import com.sheen.rokid.phone.plugin.smarthelper.host.SmartSettingsStore
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.DetachUi
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsFeedback
import com.sheen.rokid.phone.plugin.smarthelper.host.ui.SettingsSaveResult
import com.sheen.rokid.phone.storage.EncryptedBlobStore
import com.sheen.rokid.phone.storage.TestBlobs
import com.sheen.rokid.phone.ui.settings.RuntimeAppearanceUi
import com.sheen.rokid.plugin.CancellationHandle
import com.sheen.rokid.plugin.ModelClient
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.storage.MemoryProfileRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 0.52 design §3.4/§4.2: the failure feedback the runtime/appearance page owes the user.
 *
 * Task 9 introduced [SettingsSaveResult]/[SettingsFeedback] and made the proactive switch report persistence.
 * Task 10 extends the same shape to 「语音朗读」 and the two opacity sliders, and gives the page one place that turns
 * every result into the exact Chinese line it renders:
 *
 * - a speech write refusal is a **result** the page can show, not a log line: `setSpeechEnabled` now returns
 *   [SettingsSaveResult] and the page maps it to the same copy as every other settings failure;
 * - the proactive refusal stays visible through the same mapping (the T9 contract is pinned here so a later edit
 *   cannot quietly turn it back into a log-only path);
 * - `PhoneOnlyOpacityResult.error` carries the same stable codes, so the opacity line uses the existing mapping;
 * - the phone-only switch keeps [DetachUi]'s wording, including the "session was stopped first" side effect.
 *
 * [RuntimeAppearanceUi] is the host-free projection the composable renders; these assertions exercise the same
 * functions the page calls, not a re-typed copy of the strings in the test.
 */
class SettingsFeedbackTest {

    private class InertModel : ModelClient {
        override fun request(request: ModelRequest, listener: (ModelStreamEvent) -> Unit): CancellationHandle =
            object : CancellationHandle {
                override fun cancel() = Unit
            }
    }

    /**
     * A store that reads like an empty settings blob but refuses every write, like a full/blocked disk.
     *
     * [failing] is healable so the detach auto-on case can prove the next successful detach clears the reported
     * refusal; the default keeps every existing refusal test unchanged.
     */
    private class FailingWriteBlobs : EncryptedBlobStore {
        val delegate = TestBlobs()
        var failing = true

        override fun read(id: String): ByteArray? = delegate.read(id)
        override fun write(id: String, value: ByteArray) {
            if (failing) throw IOException("synthetic write failure")
            delegate.write(id, value)
        }
        override fun delete(id: String) = delegate.delete(id)
    }

    /** The smallest real composition that owns a speech switch; `store = null` is the hand-built no-store rig. */
    private fun composition(store: SmartSettingsStore?): HostCompositionRoot = HostCompositionRoot(
        host = HostRuntime(1),
        profiles = MemoryProfileRepository(),
        model = InertModel(),
        environment = null,
        smartSettingsStore = store,
    )

    // ---- speech ------------------------------------------------------------------------------------------------

    @Test
    fun `a speech write failure becomes a visible save failure`() {
        val root = composition(SmartSettingsStore(FailingWriteBlobs()))
        try {
            val result = root.setSpeechEnabled(true)

            assertFalse("a refused blob write is not a save", result.saved)
            assertEquals(SettingsSaveResult.ERROR_SAVE_FAILED, result.errorCode)
            assertEquals(
                "the page must render a refusal, never a success line",
                "保存失败：设置写入失败，未保存。",
                RuntimeAppearanceUi.speechText(result),
            )
            assertTrue("the live switch already moved before the write refused", root.speechEnabled.value)
        } finally {
            root.close()
        }
    }

    @Test
    fun `a missing settings store is a visible speech save failure`() {
        val root = composition(store = null)
        try {
            val result = root.setSpeechEnabled(false)

            assertFalse(result.saved)
            assertEquals(SettingsSaveResult.ERROR_STORE_UNAVAILABLE, result.errorCode)
            assertEquals(
                "保存失败：设置存储不可用，未保存。",
                RuntimeAppearanceUi.speechText(result),
            )
        } finally {
            root.close()
        }
    }

    @Test
    fun `an unreadable settings blob is a visible speech save failure`() {
        val blobs = TestBlobs().apply {
            values[SmartSettingsStore.BLOB_ID] = """{"version":1,"speechEnabled":false}""".toByteArray()
            failReadId = SmartSettingsStore.BLOB_ID
        }
        val root = composition(SmartSettingsStore(blobs))
        try {
            val result = root.setSpeechEnabled(true)

            assertFalse(result.saved)
            assertEquals(SettingsSaveResult.ERROR_SETTINGS_UNREADABLE, result.errorCode)
            assertEquals(
                "保存失败：原设置无法读取，未覆盖原设置。",
                RuntimeAppearanceUi.speechText(result),
            )
            blobs.failReadId = null
            assertFalse("an unreadable blob must not be overwritten", SmartSettingsStore(blobs).load().speechEnabled)
        } finally {
            root.close()
        }
    }

    @Test
    fun `a successful speech write says the change is immediate`() {
        val root = composition(SmartSettingsStore(TestBlobs()))
        try {
            val result = root.setSpeechEnabled(true)

            assertTrue(result.saved)
            assertNull(result.errorCode)
            assertEquals("已保存，立即生效", RuntimeAppearanceUi.speechText(result))
        } finally {
            root.close()
        }
    }

    // ---- detach auto-on speech (fix round 1) -------------------------------------------------------------------

    /** The first detach with no explicit speech choice turns speech on; its write must not fail silently. */
    @Test
    fun `the detach auto-on speech refusal is an observable result, not only a log`() {
        val blobs = FailingWriteBlobs()
        val store = SmartSettingsStore(blobs)
        val root = composition(store)
        try {
            root.onDetachModeChanged(detached = true)

            assertTrue("the live switch still moves before the write", root.speechEnabled.value)
            val failure = root.speechAutoOnFailure.value
            assertNotNull(
                "the refusal must reach the settings page instead of ending in P5_TTS persisted=false",
                failure,
            )
            assertFalse("a refused write is not a save", failure!!.saved)
            assertEquals(SettingsSaveResult.ERROR_SAVE_FAILED, failure.errorCode)
            assertEquals(
                "the page renders the same central mapping as every other settings failure",
                "保存失败：设置写入失败，未保存。",
                RuntimeAppearanceUi.autoOnSpeechText(failure),
            )
            assertFalse("the refusal left nothing persisted", store.load().speechEnabled)
            assertFalse("the refusal did not record an explicit speech choice", store.load().speechConfigured)
        } finally {
            root.close()
        }
    }

    /** A later detach that can write clears the old refusal, so the page does not keep a stale failure line. */
    @Test
    fun `the detach auto-on speech refusal clears on the next successful detach`() {
        val blobs = FailingWriteBlobs()
        val root = composition(SmartSettingsStore(blobs))
        try {
            root.onDetachModeChanged(detached = true)
            assertNotNull("the first detach reports its refused auto-on write", root.speechAutoOnFailure.value)

            blobs.failing = false
            root.onDetachModeChanged(detached = true)

            assertNull("a successful auto-on leaves nothing to report", root.speechAutoOnFailure.value)
            assertTrue("the second attempt persisted the speech choice", SmartSettingsStore(blobs).load().speechConfigured)
        } finally {
            root.close()
        }
    }

    // ---- proactive arming (T9 pin) -----------------------------------------------------------------------------

    @Test
    fun `a proactive write failure is reported instead of only logged`() {
        val blobs = TestBlobs()
        val store = SmartSettingsStore(blobs)
        store.save(SmartSettings(proactiveEnabled = true))
        blobs.failId = SmartSettingsStore.BLOB_ID

        val result = ProactiveArming(store).set(false)

        assertFalse(result.saved)
        assertEquals(SettingsSaveResult.ERROR_SAVE_FAILED, result.errorCode)
        assertEquals(
            "the same mapping the assistant page renders",
            "保存失败：设置写入失败，未保存。",
            SettingsFeedback.saveFailedText(result),
        )
        assertTrue("the refused write left the stored switch on", store.load().proactiveEnabled)
    }

    // ---- opacity -----------------------------------------------------------------------------------------------

    @Test
    fun `opacity failure text keeps the existing code mapping`() {
        val codes = listOf(
            SettingsSaveResult.ERROR_STORE_UNAVAILABLE,
            SettingsSaveResult.ERROR_SETTINGS_UNREADABLE,
            SettingsSaveResult.ERROR_SAVE_FAILED,
        )
        codes.forEach { code ->
            val text = RuntimeAppearanceUi.opacityText(
                saved = false,
                error = code,
                ballPercent = 60,
                bubblePercent = 40,
                detached = false,
            )
            assertEquals(SettingsFeedback.saveFailedText(SettingsSaveResult(false, code)), text)
            assertFalse("$code must not look like success", text.contains("已保存"))
        }

        assertEquals(
            "an unknown code is still surfaced with its stable token",
            "保存失败：SYNTHETIC_CODE（未保存）。",
            RuntimeAppearanceUi.opacityText(false, "SYNTHETIC_CODE", 60, 40, false),
        )
    }

    @Test
    fun `opacity success names the values and the mode dependent effect`() {
        assertEquals(
            "已保存：悬浮球 60% / 气泡 40%，立即生效。",
            RuntimeAppearanceUi.opacityText(true, null, 60, 40, detached = true),
        )
        assertEquals(
            "已保存：悬浮球 60% / 气泡 40%，模式开启后生效。",
            RuntimeAppearanceUi.opacityText(true, null, 60, 40, detached = false),
        )
    }

    // ---- phone-only mode ---------------------------------------------------------------------------------------

    @Test
    fun `detach success and failure keep the DetachUi wording`() {
        assertEquals(DetachUi.successText(false), RuntimeAppearanceUi.detachText(true, false, null))
        assertEquals(DetachUi.successText(true), RuntimeAppearanceUi.detachText(true, true, null))

        val saveFailure = RuntimeAppearanceUi.detachText(false, false, DetachGate.ERROR_SAVE_FAILED)
        assertEquals(DetachUi.errorText(DetachGate.ERROR_SAVE_FAILED), saveFailure)
        assertFalse("a failure must not claim the mode was saved", saveFailure.contains("已保存"))

        val stopFailure = RuntimeAppearanceUi.detachText(false, true, DetachGate.ERROR_STOP_FAILED)
        assertEquals(
            DetachUi.errorText(DetachGate.ERROR_STOP_FAILED, stoppedSession = true),
            stopFailure,
        )
        assertTrue(
            "a stop that already happened must stay visible in the failure line",
            stopFailure.contains("会话已先停止"),
        )
    }

    // ---- running status ----------------------------------------------------------------------------------------

    @Test
    fun `the running status is hidden while the mode is off`() {
        assertNull(RuntimeAppearanceUi.runningText(detached = false, running = false, startFailure = null))
        assertNull(RuntimeAppearanceUi.runningText(detached = false, running = true, startFailure = null))
    }

    @Test
    fun `the running state and the start failure use the 0_5_1 wording`() {
        assertEquals(
            RuntimeAppearanceUi.RUNNING,
            RuntimeAppearanceUi.runningText(detached = true, running = true, startFailure = null),
        )
        assertEquals(
            RuntimeAppearanceUi.NOT_RUNNING,
            RuntimeAppearanceUi.runningText(detached = true, running = false, startFailure = null),
        )
        assertEquals(
            "悬浮球启动失败：悬浮球窗口未能显示（系统拒绝了悬浮窗）",
            RuntimeAppearanceUi.runningText(
                detached = true,
                running = false,
                startFailure = "悬浮球窗口未能显示（系统拒绝了悬浮窗）",
            ),
        )
        assertEquals(
            "a stale failure never masks a running overlay",
            RuntimeAppearanceUi.RUNNING,
            RuntimeAppearanceUi.runningText(detached = true, running = true, startFailure = "旧失败"),
        )
    }

    // ---- page copy ---------------------------------------------------------------------------------------------

    @Test
    fun `the page copy keeps the mode off note and no retired term`() {
        assertTrue(RuntimeAppearanceUi.MODE_OFF_NOTE.contains("模式开启后生效"))
        assertTrue(RuntimeAppearanceUi.MODE_OFF_NOTE.contains("只预览数值并保存设置"))
        assertFalse(RuntimeAppearanceUi.MODE_OFF_NOTE.contains("档案"))
        assertFalse(RuntimeAppearanceUi.RUNNING.contains("档案"))
        assertFalse(RuntimeAppearanceUi.NOT_RUNNING.contains("档案"))

        assertEquals(
            "the sliders keep the shipped defaults",
            82,
            SmartSettings.DEFAULT_BALL_OPACITY,
        )
        assertEquals(75, SmartSettings.DEFAULT_BUBBLE_OPACITY)
        assertEquals(20, SmartSettings.MIN_BALL_OPACITY)
        assertEquals(30, SmartSettings.MIN_BUBBLE_OPACITY)
        assertEquals(100, SmartSettings.MAX_OPACITY)
    }
}
