package com.sheen.rokid.phone.settings

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import com.sheen.rokid.phone.ForegroundTestActivity
import com.sheen.rokid.phone.model.catalog.ModelCatalog
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelField
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.phone.ui.settings.CatalogConfirmation
import com.sheen.rokid.phone.ui.settings.ModelCatalogActions
import com.sheen.rokid.phone.ui.settings.ModelCatalogUi
import com.sheen.rokid.phone.ui.settings.ModelCatalogUiState
import com.sheen.rokid.phone.ui.settings.ModelDetailScreen
import com.sheen.rokid.phone.ui.settings.ModelDetailUi
import com.sheen.rokid.phone.ui.settings.ModelTestResult
import com.sheen.rokid.phone.ui.settings.SettingsPageUi
import com.sheen.rokid.phone.ui.settings.rememberHelpState
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.ThinkingEffort
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 0.52 design §§5.6/5.7: the model detail page driven through real Compose semantics on the K40-safe foreground
 * harness.
 *
 * The host-free draft rules (K round-trip, budget, overrides, thinking defaults, audio reservation) are covered by the
 * JVM `ModelDetailStateTest`; these cases prove what the wearer sees and taps: per-field save errors, the confirmed
 * restore that clears the edit, the upload confirmation before a synthetic probe, the conditional image test, the
 * discard guard, the single always-visible thinking switch, and the delete affordance that returns to the model
 * service.
 */
class ModelDetailUiTest {

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

    /** The page is a form; the test host scrolls it so every control is reachable whatever the device height is. */
    private fun setDetail(content: @Composable () -> Unit) {
        setContent {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { content() }
        }
    }

    private fun model(
        id: String = "deepseek-flash",
        displayName: String = "DeepSeek Flash",
        image: Boolean = true,
        thinkingSupported: Boolean = true,
        window: Int = 131_072,
        output: Int = 8_192,
        overrides: Set<ModelField> = emptySet(),
    ) = ModelEntry(
        id = id,
        displayName = displayName,
        contextWindowTokens = window,
        maxOutputTokens = output,
        inputs = if (image) setOf(InputModality.TEXT, InputModality.IMAGE) else setOf(InputModality.TEXT),
        outputs = setOf(OutputModality.TEXT),
        thinkingSupported = thinkingSupported,
        thinkingEffort = ThinkingEffort.LOW,
        origin = ModelOrigin.GATEWAY,
        overrides = overrides,
    )

    private fun provider(model: ModelEntry) = Provider(
        id = "p-1",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        secretHandleId = "credential-1",
        revision = 1,
        models = listOf(model),
    )

    private fun stateFor(
        model: ModelEntry,
        confirmation: CatalogConfirmation? = null,
        lastTest: ModelTestResult? = null,
        savedModelId: String? = null,
        testAvailable: Boolean = true,
        modelEditEpoch: Int = 0,
    ): ModelCatalogUiState {
        val active = provider(model)
        return ModelCatalogUiState(
            catalog = ModelCatalog(
                activeProviderId = active.id,
                selectedModelId = model.id,
                providers = listOf(active),
            ),
            activeProvider = active,
            selectedModel = model,
            confirmation = confirmation,
            lastTest = lastTest,
            savedModelId = savedModelId,
            testAvailable = testAvailable,
            modelEditEpoch = modelEditEpoch,
        )
    }

    private fun restoreState(state: ModelCatalogUiState, restored: ModelEntry, epoch: Int): ModelCatalogUiState {
        val active = provider(restored)
        return state.copy(
            catalog = state.catalog?.copy(selectedModelId = restored.id, providers = listOf(active)),
            activeProvider = active,
            selectedModel = restored,
            modelEditEpoch = epoch,
        )
    }

    // ---- save --------------------------------------------------------------------------------------------------

    @Test
    fun savingAnInvalidWindowShowsTheFieldErrorAndKeepsTheDraft() {
        val original = model()
        var state by mutableStateOf(stateFor(original))
        var saved: Pair<ModelEntry, ModelEntry>? = null
        var popped = 0
        setDetail {
            ModelDetailScreen(
                state = state,
                actions = ModelCatalogActions(updateModel = { _, previous, changed -> saved = previous to changed }),
                help = rememberHelpState(),
                modelId = original.id,
                requestPop = { popped++ },
            )
        }

        compose.onNodeWithContentDescription("模型上下文窗口输入").performTextReplacement("-1")
        compose.onNodeWithText(SettingsPageUi.SAVE).performScrollTo().performClick()

        compose.onNodeWithText(ModelDetailUi.WINDOW_ERROR).assertExists()
        compose.onNodeWithContentDescription("模型上下文窗口输入").assertTextContains("-1")
        assertNull("an invalid window is never submitted", saved)

        compose.onNodeWithContentDescription("模型上下文窗口输入").performTextReplacement("16")
        compose.onNodeWithText(SettingsPageUi.SAVE).performScrollTo().performClick()

        val (previous, changed) = requireNotNull(saved) { "a valid window is submitted" }
        assertEquals(original, previous)
        assertEquals(16 * 1024, changed.contextWindowTokens)
        assertEquals(original.maxOutputTokens, changed.maxOutputTokens)

        // The view model publishes the saved row; the detail page consumes the event and returns to the model service.
        state = state.copy(savedModelId = original.id)
        compose.waitForIdle()
        assertEquals("a successful save returns to the model service page", 1, popped)
    }

    // ---- restore ------------------------------------------------------------------------------------------------

    @Test
    fun restoreDefaultsAsksForConfirmationAndClearsOnlyThatModel() {
        val original = model(
            overrides = setOf(ModelField.CONTEXT_WINDOW_TOKENS, ModelField.MAX_OUTPUT_TOKENS),
        )
        var state by mutableStateOf(stateFor(original))
        val restoreCalls = mutableListOf<String>()
        setDetail {
            ModelDetailScreen(
                state = state,
                actions = ModelCatalogActions(restoreDefaults = { modelId ->
                    restoreCalls += modelId
                    // The page consumes the catalog the view model re-reads; only the addressed row may be re-seeded.
                    state = restoreState(
                        state,
                        original.copy(
                            contextWindowTokens = 65_536,
                            maxOutputTokens = 4_096,
                            overrides = emptySet(),
                        ),
                        epoch = 1,
                    )
                }),
                help = rememberHelpState(),
                modelId = original.id,
                registerBackGuard = {},
            )
        }

        compose.onNodeWithText(ModelDetailUi.RESTORE).performScrollTo().performClick()
        compose.onNodeWithText(ModelDetailUi.RESTORE_TITLE).assertExists()
        assertTrue("nothing is restored before the confirmation", restoreCalls.isEmpty())

        compose.onNodeWithText(ModelDetailUi.RESTORE_ACTION).performClick()

        assertEquals("only the model the page is showing is restored", listOf(original.id), restoreCalls)
        compose.waitForIdle()
        compose.onNodeWithContentDescription("模型上下文窗口输入").assertTextContains("64")
        compose.onNodeWithContentDescription("模型最大输出输入").assertTextContains("4")
    }

    // ---- probes -------------------------------------------------------------------------------------------------

    @Test
    fun theTextTestAsksForUploadConfirmationFirst() {
        val original = model()
        var state by mutableStateOf(stateFor(original, testAvailable = false))
        val tests = mutableListOf<Boolean>()
        setDetail {
            ModelDetailScreen(
                state = state,
                actions = ModelCatalogActions(testModel = { _, image -> tests += image }),
                help = rememberHelpState(),
                modelId = original.id,
                registerBackGuard = {},
            )
        }

        // No tester wired: the action is disabled and the reason is visible instead of pretending a probe could run.
        compose.onNodeWithText(ModelDetailUi.TEST_UNAVAILABLE_REASON).assertExists()
        compose.onNodeWithText(ModelDetailUi.TEST_TEXT).performScrollTo().assertIsNotEnabled()

        state = state.copy(testAvailable = true)
        compose.waitForIdle()
        compose.onNodeWithText(ModelDetailUi.TEST_TEXT).performScrollTo().performClick()

        assertTrue("no probe runs before the upload confirmation", tests.isEmpty())
        compose.onNodeWithText(ModelDetailUi.TEST_CONFIRM_TEXT).assertExists()
        compose.onNodeWithText(ModelDetailUi.TEST_CONFIRM_ACTION).performClick()
        assertEquals(listOf(false), tests)

        compose.onNodeWithText(ModelDetailUi.TEST_TEXT).performScrollTo().performClick()
        compose.onNodeWithText(ModelCatalogUi.RESET_CANCEL).performClick()
        assertEquals("cancelling the confirmation sends nothing", listOf(false), tests)

        state = state.copy(
            lastTest = ModelTestResult(
                modelId = original.id,
                image = false,
                passed = true,
                message = ModelCatalogUi.TEST_PASSED,
                testedAtEpochMillis = 1_700_000_000_000L,
            ),
        )
        compose.waitForIdle()
        compose.onNodeWithText(ModelDetailUi.TEST_RESULT_PREFIX, substring = true).assertExists()
    }

    @Test
    fun theImageTestRowOnlyAppearsWhenImageInputIsDeclared() {
        val original = model(image = true)
        var state by mutableStateOf(stateFor(original))
        setDetail {
            ModelDetailScreen(
                state = state,
                actions = ModelCatalogActions(),
                help = rememberHelpState(),
                modelId = original.id,
                registerBackGuard = {},
            )
        }

        compose.onNodeWithText(ModelDetailUi.TEST_IMAGE).performScrollTo().assertExists()
        compose.onNodeWithText(ModelDetailUi.TEST_TEXT).assertExists()

        compose.onNodeWithContentDescription(ModelDetailUi.IMAGE_SWITCH).performScrollTo().performClick()
        compose.onNodeWithText(ModelDetailUi.TEST_IMAGE).assertDoesNotExist()
        compose.onNodeWithText(ModelDetailUi.TEST_TEXT).assertExists()

        compose.onNodeWithContentDescription(ModelDetailUi.IMAGE_SWITCH).performClick()
        compose.onNodeWithText(ModelDetailUi.TEST_IMAGE).assertExists()
    }

    // ---- dirty guard --------------------------------------------------------------------------------------------

    @Test
    fun anUnsavedModelDraftAsksBeforeDiscarding() {
        val original = model()
        var state by mutableStateOf(stateFor(original))
        var guard: (() -> Unit)? = null
        var popped = 0
        setDetail {
            ModelDetailScreen(
                state = state,
                actions = ModelCatalogActions(),
                help = rememberHelpState(),
                modelId = original.id,
                requestPop = { popped++ },
                registerBackGuard = { registered -> guard = registered },
            )
        }

        compose.onNodeWithContentDescription("模型显示名输入").performTextReplacement("Edited")

        val leave = requireNotNull(guard) { "the detail page must register its back guard" }
        // The shell invokes its registered guard on the main thread; run it there and let the state write settle so
        // the assertion sees the same recomposition the device would.
        compose.runOnIdle { leave() }
        compose.onNodeWithText(SettingsPageUi.DISCARD_TITLE).assertExists()
        assertEquals("the route does not close while the draft is unsaved", 0, popped)

        compose.onNodeWithText(SettingsPageUi.DISCARD_KEEP).performClick()
        compose.onNodeWithText(SettingsPageUi.DISCARD_TITLE).assertDoesNotExist()
        assertEquals(0, popped)

        compose.runOnIdle { leave() }
        compose.onNodeWithText(SettingsPageUi.DISCARD_CONFIRM).performClick()
        assertEquals("discarding the draft leaves the route", 1, popped)
    }

    // ---- delete -------------------------------------------------------------------------------------------------

    @Test
    fun theDetailPageDeletesTheModelAfterConfirmationAndReturnsToTheModelService() {
        val original = model()
        var state by mutableStateOf(stateFor(original))
        val deleted = mutableListOf<String>()
        val confirmed = mutableListOf<String?>()
        var popped = 0
        setDetail {
            ModelDetailScreen(
                state = state,
                actions = ModelCatalogActions(
                    deleteModel = { modelId -> deleted += modelId },
                    confirmPending = { newActiveId -> confirmed += newActiveId },
                ),
                help = rememberHelpState(),
                modelId = original.id,
                requestPop = { popped++ },
                registerBackGuard = {},
            )
        }

        compose.onNodeWithText(ModelDetailUi.DELETE_MODEL).performScrollTo().performClick()
        assertEquals("the page asks the view model to open the confirmation", listOf(original.id), deleted)

        // The view model opens the provider-scoped confirmation; the dialog is surfaced on the detail page.
        state = state.copy(
            confirmation = CatalogConfirmation.DeleteModel("p-1", original.id, original.displayName),
        )
        compose.waitForIdle()
        compose.onNodeWithText(ModelCatalogUi.DELETE_MODEL_TITLE).assertExists()
        compose.onNodeWithText(ModelCatalogUi.DELETE).performClick()
        assertEquals(listOf<String?>(null), confirmed)

        // The delete succeeded: the row is gone, so the page returns to the model service.
        val emptyProvider = provider(original).copy(models = emptyList())
        state = state.copy(
            confirmation = null,
            catalog = state.catalog?.copy(selectedModelId = null, providers = listOf(emptyProvider)),
            activeProvider = emptyProvider,
            selectedModel = null,
        )
        compose.waitForIdle()
        assertEquals("after a confirmed delete the page returns to the model service", 1, popped)
    }

    // ---- thinking / inputs --------------------------------------------------------------------------------------

    @Test
    fun theThinkingSwitchIsOperableForEveryModel() {
        val deepseek = model(thinkingSupported = false)
        var current by mutableStateOf(deepseek)
        setDetail {
            ModelDetailScreen(
                state = stateFor(current),
                actions = ModelCatalogActions(),
                help = rememberHelpState(),
                modelId = current.id,
                registerBackGuard = {},
            )
        }

        // 0.53 ruling A: the block and its switch are always present and operable; every notice is shown.
        compose.onNodeWithText(ModelDetailUi.THINKING).performScrollTo().assertExists()
        compose.onNodeWithContentDescription(ModelDetailUi.THINKING_SWITCH).assertIsOff().assertIsEnabled()
        compose.onNodeWithText(ModelDetailUi.THINKING_ONLY_DEEPSEEK).assertExists()
        compose.onNodeWithText(ModelDetailUi.THINKING_DEFAULT_BUDGET_HINT).assertExists()
        compose.onNodeWithText(ModelDetailUi.THINKING_WINDOW_HINT).assertExists()
        compose.onNodeWithText(ModelDetailUi.EFFORT_LOW).assertIsSelected()

        compose.onNodeWithContentDescription(ModelDetailUi.THINKING_SWITCH).performClick()
        compose.onNodeWithContentDescription(ModelDetailUi.THINKING_SWITCH).assertIsOn()
        compose.onNodeWithText(ModelDetailUi.EFFORT_MAX).performClick().assertIsSelected()

        // A non-DeepSeek model is not disabled and gets the same notice instead of losing the block.
        current = model(id = "custom-vl", thinkingSupported = false)
        compose.waitForIdle()
        compose.onNodeWithText(ModelDetailUi.THINKING).assertExists()
        compose.onNodeWithContentDescription(ModelDetailUi.THINKING_SWITCH).assertIsOff().assertIsEnabled()
        compose.onNodeWithText(ModelDetailUi.THINKING_ONLY_DEEPSEEK).assertExists()

        // The 0.52 advanced marker row is gone.
        compose.onNodeWithText("标记为 DeepSeek 思考模型").assertDoesNotExist()

        compose.onNodeWithContentDescription(ModelDetailUi.AUDIO_SWITCH).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText(ModelDetailUi.AUDIO_DISABLED).assertExists()
    }
}
