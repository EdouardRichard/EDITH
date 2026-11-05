package com.sheen.rokid.phone.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.sheen.rokid.phone.ForegroundTestActivity
import com.sheen.rokid.phone.ui.PhoneDetailPage
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 0.53 device check: the shared settings detail surface must scroll its body.
 *
 * Every secondary settings page (except the model service's own LazyColumn) is drawn inside [PhoneDetailPage]'s dialog,
 * so this is the one scroll container all of them rely on. The page composables' own instrumented tests wrap the page in
 * a *test-harness* scroll column, so they never proved this surface itself scrolls; this test does.
 */
@RunWith(AndroidJUnit4::class)
class PhoneDetailPageScrollTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var foreground: ForegroundTestActivity

    @Before
    fun launchForegroundHost() {
        foreground = ForegroundTestActivity.launch()
    }

    @After
    fun closeForegroundHost() {
        if (::foreground.isInitialized) foreground.close()
    }

    @Test
    fun theDetailSurfaceScrollsToContentBelowTheFold() {
        foreground.onActivity { activity ->
            activity.setContent {
                PhoneDetailPage(title = "scroll", onBack = {}) {
                    repeat(40) { index ->
                        Text("row-$index", modifier = Modifier.height(80.dp))
                    }
                }
            }
        }

        compose.onNodeWithText("row-39").performScrollTo().assertIsDisplayed()
    }
}
