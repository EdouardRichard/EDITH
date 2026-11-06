package com.sheen.rokid.phone.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.sheen.rokid.phone.ForegroundTestActivity
import com.sheen.rokid.phone.model.catalog.ModelCatalog
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.phone.ui.settings.ModelCatalogActions
import com.sheen.rokid.phone.ui.settings.ModelCatalogUi
import com.sheen.rokid.phone.ui.settings.ModelCatalogUiState
import com.sheen.rokid.phone.ui.settings.ProviderDraft
import com.sheen.rokid.phone.ui.settings.ProviderEditorScreen
import com.sheen.rokid.phone.ui.settings.rememberHelpState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 0.52 design §§5.2/5.10: the provider form.
 *
 * The form is host-free, so these cases drive the real field validation and the real draft handed to the action; the
 * view model's own save (staging, zeroing, auto-activation, first fetch) is pinned by `ModelCatalogViewModelTest`.
 */
class ProviderEditorUiTest {

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

    private fun setContent(content: @Composable () -> Unit) {
        foreground.onActivity { activity -> activity.setContent { content() } }
    }

    /** The test host scrolls the form so the save button can be reached without relying on the device height. */
    private fun setEditor(content: @Composable () -> Unit) {
        setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
        }
    }

    private fun provider() = Provider(
        id = "p-1",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        secretHandleId = "credential-1",
        revision = 1,
    )

    private fun editingState() = ModelCatalogUiState(
        catalog = ModelCatalog(activeProviderId = "p-1", providers = listOf(provider())),
        activeProvider = provider(),
        keyMask = mapOf("p-1" to "sk-••••4321"),
    )

    @Test
    fun theProviderFormSavesAnyKeyAndValidatesTheBaseUrlWithoutEchoingIt() {
        var saved: ProviderDraft? = null
        var savedKey: String? = null
        setEditor {
            ProviderEditorScreen(
                state = ModelCatalogUiState(),
                actions = ModelCatalogActions(saveProvider = { draft ->
                    saved = draft
                    savedKey = draft.apiKey?.concatToString()
                }),
                help = rememberHelpState(),
            )
        }

        compose.onNodeWithContentDescription("提供商名称输入").performTextReplacement("DeepSeek")
        compose.onNodeWithContentDescription("提供商 Base URL 输入").performTextReplacement("http://insecure.example/v1")
        compose.onNodeWithContentDescription("提供商 API Key 输入").performTextReplacement("sk-any-value-1234")

        compose.onNodeWithText(ModelCatalogUi.SAVE_AND_FETCH).performScrollTo().performClick()

        // The refusal is visible and is the fixed sentence - it never repeats the URL or the key.
        val error = ModelCatalogUi.providerBaseUrlError("http://insecure.example/v1", allowLocalHttp = true)
        requireNotNull(error)
        compose.onNodeWithText(error).assertExists()
        assertTrue("the rejection text must not echo the key", !error.contains("sk-"))
        assertTrue("the rejection text must not echo the url", !error.contains("insecure.example"))
        assertNull("an invalid form is never submitted", saved)

        compose.onNodeWithContentDescription("提供商 Base URL 输入")
            .performTextReplacement("https://api.deepseek.com/v1")
        compose.onNodeWithText(ModelCatalogUi.SAVE_AND_FETCH).performScrollTo().performClick()

        val draft = requireNotNull(saved) { "a valid form is submitted" }
        assertEquals("DeepSeek", draft.name)
        assertEquals("https://api.deepseek.com/v1", draft.baseUrl)
        assertEquals("any key content is accepted verbatim", "sk-any-value-1234", savedKey)
        assertNull("a new provider has no id yet; the view model generates one", draft.providerId)
    }

    @Test
    fun editingAProviderPreFillsStoredFieldsAndBlankKeyKeepsTheCredential() {
        var saved: ProviderDraft? = null
        setEditor {
            ProviderEditorScreen(
                state = editingState(),
                actions = ModelCatalogActions(saveProvider = { draft -> saved = draft }),
                help = rememberHelpState(),
                providerId = "p-1",
            )
        }

        compose.onNodeWithContentDescription("提供商名称输入").assertTextContains("DeepSeek")
        compose.onNodeWithContentDescription("提供商 Base URL 输入").assertTextContains("https://api.deepseek.com/v1")
        compose.onNodeWithText("sk-••••4321 · ${ModelCatalogUi.KEY_KEEP}").assertExists()

        compose.onNodeWithText(ModelCatalogUi.SAVE_AND_FETCH).performScrollTo().performClick()

        val draft = requireNotNull(saved)
        assertEquals("p-1", draft.providerId)
        assertNull("a blank key keeps the stored credential", draft.apiKey)
    }

    @Test
    fun aWhitespaceOnlyKeyOnEditKeepsTheCredentialAndAnOverLongKeyIsAFieldError() {
        var saved: ProviderDraft? = null
        setEditor {
            ProviderEditorScreen(
                state = editingState(),
                actions = ModelCatalogActions(saveProvider = { draft -> saved = draft }),
                help = rememberHelpState(),
                providerId = "p-1",
            )
        }

        compose.onNodeWithContentDescription("提供商 API Key 输入").performTextReplacement("   ")
        compose.onNodeWithText(ModelCatalogUi.SAVE_AND_FETCH).performScrollTo().performClick()

        assertNull("whitespace-only on edit means keep the stored credential", requireNotNull(saved).apiKey)

        saved = null
        compose.onNodeWithContentDescription("提供商 API Key 输入").performTextReplacement("x".repeat(9000))
        compose.onNodeWithText(ModelCatalogUi.SAVE_AND_FETCH).performScrollTo().performClick()

        compose.onNodeWithText("API Key 过长").assertExists()
        assertNull("an over-length key is never submitted", saved)
    }

    @Test
    fun aFailedSaveIsShownInlineAndTheFormStaysEditable() {
        setEditor {
            ProviderEditorScreen(
                state = ModelCatalogUiState(error = "保存失败，请重试"),
                actions = ModelCatalogActions(saveProvider = { }),
                help = rememberHelpState(),
            )
        }

        compose.onNodeWithContentDescription("提供商名称输入").performTextReplacement("DeepSeek")
        compose.onNodeWithText("保存失败，请重试").assertExists()
        compose.onNodeWithContentDescription("提供商名称输入").assertTextContains("DeepSeek")
    }
}
