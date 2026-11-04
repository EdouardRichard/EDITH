package com.sheen.rokid.phone.settings

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.phone.ForegroundTestActivity
import com.sheen.rokid.phone.app.MainActivity
import com.sheen.rokid.phone.ui.settings.HelpContent
import com.sheen.rokid.phone.ui.settings.HelpIcon
import com.sheen.rokid.phone.ui.settings.HelpState
import com.sheen.rokid.phone.ui.settings.HelpText
import com.sheen.rokid.phone.ui.settings.rememberHelpState
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 0.52 design §4.1 / §4.5: the `(?)` help popover.
 *
 * Six behaviours are the contract: the icon carries its own description and opens the popover; a tap outside closes
 * it; back closes it; only one popover is ever open; the risk limits and the impact line are in the body; and the
 * popover is capped at ~330dp wide / 60% of the screen tall and scrolls its body.
 *
 * Harness: `createEmptyComposeRule()` + [ForegroundTestActivity] (the K40 aborts the Compose rule's own background
 * Activity start; see `SettingsHomeTest`). Content is installed on the real foreground MainActivity.
 */
class HelpPopoverTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var foreground: ForegroundTestActivity

    /** Screen position of the Activity's window, used only to inject a real outside tap. */
    private val windowOrigin = IntArray(2)

    @Before
    fun launchForegroundHost() {
        foreground = ForegroundTestActivity.launch()
    }

    @After
    fun closeForegroundHost() {
        if (::foreground.isInitialized) foreground.close()
    }

    private fun setContent(content: @Composable () -> Unit) {
        foreground.onActivity { activity ->
            activity.setContent {
                MaterialTheme { content() }
            }
        }
    }

    /** Must be called before a focusable popup takes window focus, so the Activity is still RESUMED with focus. */
    private fun rememberWindowOrigin() {
        foreground.onActivity { activity ->
            activity.window.decorView.getLocationOnScreen(windowOrigin)
        }
    }

    /**
     * A real OS-level tap at [node]'s centre.
     *
     * `performClick()` cannot do this job for an outside tap: in Compose UI test 1.7.3 the injected touch is delivered
     * with `root.view.dispatchTouchEvent(...)` directly to the node's own root, so a tap on the page behind a Popup
     * never reaches the popup window that owns `dismissOnClickOutside`. The popup is a focusable, non-touch-modal
     * window, so the platform tap below is consumed by it and its own `onTouchEvent` turns it into a dismissal.
     */
    private fun tapScreenAt(node: SemanticsNodeInteraction) {
        // boundsInWindow is in window pixels; the injection API wants screen pixels.
        val bounds = node.fetchSemanticsNode().boundsInWindow
        val x = windowOrigin[0] + bounds.center.x
        val y = windowOrigin[1] + bounds.center.y
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val up = MotionEvent.obtain(downTime, downTime + 16, MotionEvent.ACTION_UP, x, y, 0).apply {
            source = InputDevice.SOURCE_TOUCHSCREEN
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        try {
            instrumentation.sendPointerSync(down)
            instrumentation.sendPointerSync(up)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    // the help icon exposes its own description and opens the popover
    @Test
    fun theHelpIconExposesItsOwnDescriptionAndOpensThePopover() {
        val text = HelpText(
            title = "唤醒词",
            body = "唤醒词仅在 App 启动时读取一次（组合为进程级单例），修改后需完全重启 App 才会生效。",
            impact = "影响：完全重启 App 后生效",
        )
        setContent { Column { HelpIcon(text, rememberHelpState()) } }

        compose.onNodeWithContentDescription("查看${text.title}说明").assertExists().performClick()

        compose.onNodeWithText(text.title).assertExists()
        compose.onNodeWithText(text.body).assertExists()
        compose.onNodeWithText(text.impact!!).assertExists()
    }

    // tapping outside closes the popover
    @Test
    fun tappingOutsideClosesThePopover() {
        val text = HelpText(
            title = "持续对话",
            body = "答案显示/播报结束后自动进入下一轮聆听；关闭后答案结束约 1 秒关闭面板，不发 status 5。",
            impact = "影响：完全重启 App 后生效",
        )
        setContent {
            Column(Modifier.fillMaxSize()) {
                Text("outside", Modifier.padding(16.dp))
                HelpIcon(text, rememberHelpState())
            }
        }
        rememberWindowOrigin()

        compose.onNodeWithContentDescription("查看${text.title}说明").performClick()
        compose.onNodeWithText(text.body).assertExists()

        tapScreenAt(compose.onNodeWithText("outside"))
        compose.waitForIdle()

        compose.onNodeWithText(text.body).assertDoesNotExist()
    }

    // the back gesture closes the popover
    @Test
    fun theBackGestureClosesThePopover() {
        val text = HelpText(
            title = "语音朗读",
            body = "关闭后不会自动打开；引擎缺失时提示照常显示、只是不出声。",
            impact = "影响：立即生效",
        )
        // Captured before the popover takes window focus: the focusable popup makes Activity.hasWindowFocus() false,
        // so the harness' onActivity readiness check cannot be used while it is open.
        var activity: MainActivity? = null
        foreground.onActivity { activity = it }
        setContent { Column { HelpIcon(text, rememberHelpState()) } }

        compose.onNodeWithContentDescription("查看${text.title}说明").performClick()
        compose.onNodeWithText(text.body).assertExists()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            requireNotNull(activity).onBackPressedDispatcher.onBackPressed()
        }
        compose.waitForIdle()

        compose.onNodeWithText(text.body).assertDoesNotExist()
    }

    // only one popover is open at a time
    @Test
    fun onlyOnePopoverIsOpenAtATime() {
        val first = HelpText("唤醒词", "第一条说明正文。")
        val second = HelpText("持续对话", "第二条说明正文。")
        setContent {
            val help: HelpState = rememberHelpState()
            Column {
                Row { Text("唤醒词"); HelpIcon(first, help) }
                Row { Text("持续对话"); HelpIcon(second, help) }
            }
        }

        compose.onNodeWithContentDescription("查看${first.title}说明").performClick()
        compose.onNodeWithText(first.body).assertExists()
        compose.onNodeWithText(second.body).assertDoesNotExist()

        compose.onNodeWithContentDescription("查看${second.title}说明").performClick()
        compose.waitForIdle()

        compose.onNodeWithText(first.body).assertDoesNotExist()
        compose.onNodeWithText(second.body).assertExists()
    }

    // the popover body keeps the risk limits and impact line
    @Test
    fun thePopoverBodyKeepsTheRiskLimitsAndImpactLine() {
        val text = HelpContent.NO_INTERCEPTION
        setContent { Column { Text("不拦截"); HelpIcon(text, rememberHelpState()) } }

        compose.onNodeWithContentDescription("查看${text.title}说明").performClick()

        compose.onNodeWithText(text.body).assertExists()
        compose.onNodeWithText(text.body).assert(hasText("可能无法撤销", substring = true))
        compose.onNodeWithText(text.impact!!).assertExists()
    }

    // the popover is width and height constrained and scrolls
    @Test
    fun thePopoverIsWidthAndHeightConstrainedAndScrolls() {
        // Long enough that an unconstrained popover would be wider than 330dp and much taller than 60% of the screen.
        val body = "这是一段很长的说明文字，用于验证浮层不会超过屏幕高度，并且正文可以滚动。".repeat(60)
        val text = HelpText(title = "长说明", body = body, impact = "影响：立即生效")
        var screenWidthDp = 0
        var screenHeightDp = 0
        setContent {
            val configuration = LocalConfiguration.current
            screenWidthDp = configuration.screenWidthDp
            screenHeightDp = configuration.screenHeightDp
            Column { HelpIcon(text, rememberHelpState()) }
        }

        compose.onNodeWithContentDescription("查看${text.title}说明").performClick()

        val title = compose.onNodeWithText(text.title)
        val bodyNode = compose.onNodeWithText(body)
        title.assertExists()
        bodyNode.assertExists().assert(hasScrollAction())

        val maxWidth = minOf(330.dp, (screenWidthDp - 32).dp)
        val maxHeight = screenHeightDp.dp * 0.6f
        val titleBounds = title.getUnclippedBoundsInRoot()
        val bodyBounds = bodyNode.getUnclippedBoundsInRoot()
        assertTrue("title width ${titleBounds.width} exceeds $maxWidth", titleBounds.width <= maxWidth)
        assertTrue("body width ${bodyBounds.width} exceeds $maxWidth", bodyBounds.width <= maxWidth)
        assertTrue("body height ${bodyBounds.height} exceeds $maxHeight", bodyBounds.height <= maxHeight + 1.dp)
    }
}
