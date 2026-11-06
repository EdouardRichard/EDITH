package com.sheen.rokid.phone.settings

import android.content.ClipboardManager
import android.content.Context
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.platform.app.InstrumentationRegistry
import com.sheen.rokid.phone.ForegroundTestActivity
import com.sheen.rokid.phone.model.catalog.ModelCatalog
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.phone.model.catalog.Provider
import com.sheen.rokid.phone.storage.EncryptedBlobStore
import com.sheen.rokid.phone.storage.LegacyModelConfig
import com.sheen.rokid.phone.storage.LegacyPromptStore
import com.sheen.rokid.phone.ui.FirstRunModelUpgrade
import com.sheen.rokid.phone.ui.settings.ModelCatalogActions
import com.sheen.rokid.phone.ui.settings.ModelCatalogUi
import com.sheen.rokid.phone.ui.settings.ModelCatalogUiState
import com.sheen.rokid.phone.ui.settings.ModelServiceScreen
import com.sheen.rokid.phone.ui.settings.rememberHelpState
import com.sheen.rokid.plugin.CapabilityVerification
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.VerificationState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * 0.52 design §§5.4/5.9/5.10: the model service page and the first-run upgrade notice, driven through real Compose
 * semantics on the K40-safe foreground harness.
 *
 * The state machine itself is covered by the JVM `ModelCatalogViewModelTest`; these cases prove what the wearer sees and
 * taps: the three empty states, the chip vocabulary, the elided-but-copyable long values, the key mask, the dismissible
 * refresh summary, the corrupt-catalog reset and the one-shot legacy prompt with its two exits.
 */
class ModelServiceUiTest {

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

    private fun entry(
        id: String,
        origin: ModelOrigin = ModelOrigin.GATEWAY,
        image: Boolean = true,
        missing: Boolean = false,
        textVerifiedAtRevision: Long? = null,
        window: Int = 131_072,
        output: Int = 8_192,
    ) = ModelEntry(
        id = id,
        displayName = "Model $id",
        contextWindowTokens = window,
        maxOutputTokens = output,
        inputs = if (image) setOf(InputModality.TEXT, InputModality.IMAGE) else setOf(InputModality.TEXT),
        outputs = setOf(OutputModality.TEXT),
        origin = origin,
        missing = missing,
        verifications = textVerifiedAtRevision?.let {
            listOf(CapabilityVerification(it, "TEXT_REQUEST", VerificationState.Verified, 1_700_000_000_000L))
        }.orEmpty(),
    )

    private fun provider(
        id: String = "p-1",
        name: String = "DeepSeek",
        baseUrl: String = "https://api.deepseek.com/v1",
        models: List<ModelEntry> = emptyList(),
        syncedAt: Long? = null,
    ) = Provider(
        id = id,
        name = name,
        baseUrl = baseUrl,
        secretHandleId = "credential-$id",
        revision = 1,
        lastSyncedAtEpochMillis = syncedAt,
        models = models,
    )

    private fun stateOf(
        provider: Provider?,
        selected: ModelEntry? = null,
        keyMask: Map<String, String> = emptyMap(),
        error: String? = null,
        refreshSummary: String? = null,
        corrupt: Boolean = false,
    ) = ModelCatalogUiState(
        catalog = provider?.let {
            ModelCatalog(activeProviderId = it.id, selectedModelId = selected?.id, providers = listOf(it))
        } ?: ModelCatalog(),
        activeProvider = provider,
        selectedModel = selected,
        keyMask = keyMask,
        error = error,
        refreshSummary = refreshSummary,
        corrupt = corrupt,
    )

    // ---- empty state -------------------------------------------------------------------------------------------

    @Test
    fun theEmptyStateOffersAddProviderAndExplainsTheThreeFields() {
        var added = 0
        setContent {
            ModelServiceScreen(
                state = stateOf(provider = null),
                actions = ModelCatalogActions(),
                help = rememberHelpState(),
                onAddProvider = { added++ },
            )
        }

        compose.onNodeWithText(ModelCatalogUi.EMPTY_TITLE).assertExists()
        compose.onNodeWithText(ModelCatalogUi.EMPTY_DETAIL).assertExists()
        compose.onNodeWithText("必填：名称、网关 Base URL、API Key。协议与超时在「高级」里。").assertExists()
        compose.onNodeWithText(ModelCatalogUi.ADD_PROVIDER).performClick()

        assertEquals(1, added)
    }

    // ---- model list vocabulary ---------------------------------------------------------------------------------

    @Test
    fun theModelListShowsGatewayManualMissingAndVerifiedChips() {
        val manual = entry("m-manual", origin = ModelOrigin.MANUAL, textVerifiedAtRevision = 1)
        val missing = entry("m-missing", missing = true)
        val verified = entry("m-text", image = false, textVerifiedAtRevision = 1)
        val provider = provider(models = listOf(manual, missing, verified))

        setContent {
            ModelServiceScreen(
                state = stateOf(provider, selected = manual),
                actions = ModelCatalogActions(),
                help = rememberHelpState(),
            )
        }

        compose.onAllNodesWithText(ModelCatalogUi.MANUAL_CHIP).assertCountEquals(1)
        compose.onAllNodesWithText(ModelCatalogUi.MISSING_CHIP).assertCountEquals(1)
        compose.onAllNodesWithText(ModelCatalogUi.VERIFIED_CHIP).assertCountEquals(2)
        compose.onAllNodesWithText(ModelCatalogUi.IMAGE_UNVERIFIED).assertCountEquals(1)
        compose.onAllNodesWithText("128K 输入 · 8K 输出").assertCountEquals(3)
        // A text-only row cannot run the assistant (design §5.6): the reason is visible, not just a disabled radio.
        compose.onAllNodesWithText(ModelCatalogUi.TEXT_ONLY_DISABLED).assertCountEquals(1)
    }

    // ---- selection rule (I1) ------------------------------------------------------------------------------------

    @Test
    fun onlyTestedImageCapableRowsCanBeSelected() {
        val missing = entry("m-missing", missing = true, textVerifiedAtRevision = 1)
        val untested = entry("m-untested")
        val tested = entry("m-tested", textVerifiedAtRevision = 1)
        val provider = provider(models = listOf(missing, untested, tested))

        setContent {
            ModelServiceScreen(
                state = stateOf(provider, selected = null),
                actions = ModelCatalogActions(),
                help = rememberHelpState(),
            )
        }

        compose.onNodeWithContentDescription("选择模型 Model m-missing").assertIsNotEnabled()
        compose.onNodeWithContentDescription("选择模型 Model m-untested").assertIsNotEnabled()
        compose.onNodeWithContentDescription("选择模型 Model m-tested").assertIsEnabled()
        compose.onNodeWithText(ModelCatalogUi.SELECT_MISSING_ROW).assertExists()
        compose.onNodeWithText(ModelCatalogUi.SELECT_NEEDS_TEST).assertExists()
        // I1's no-candidate copy is visible instead of a silent, unusable selection.
        compose.onNodeWithText(ModelCatalogUi.NO_MODEL_SELECTED).assertExists()
    }

    // ---- 500-row laziness (I5) ----------------------------------------------------------------------------------

    @Test
    fun theFiveHundredRowModelListIsLazyAndStillReachesTheLastRow() {
        val models = (1..500).map { entry("m-$it", textVerifiedAtRevision = 1) }
        val provider = provider(models = models)

        setContent {
            ModelServiceScreen(
                state = stateOf(provider, selected = models.first()),
                actions = ModelCatalogActions(),
                help = rememberHelpState(),
            )
        }

        compose.waitForIdle()
        val composed = compose.onAllNodesWithContentDescription("选择模型", substring = true)
            .fetchSemanticsNodes().size
        assertTrue("a LazyColumn composes only the visible window, found $composed of 500 rows", composed < 500)

        compose.onNode(hasScrollAction()).performScrollToIndex(models.lastIndex)
        compose.onNodeWithContentDescription("选择模型 Model m-500").assertExists()
    }

    // ---- stale selection warning (I6/R28) ----------------------------------------------------------------------

    @Test
    fun aStaleSelectionShowsTheFreshTestWarningAndADisabledRadio() {
        val stale = entry("m-stale", textVerifiedAtRevision = 0)
        val provider = provider(models = listOf(stale))

        setContent {
            ModelServiceScreen(
                state = stateOf(provider, selected = stale).copy(warning = ModelCatalogUi.SELECTION_NEEDS_TEST),
                actions = ModelCatalogActions(),
                help = rememberHelpState(),
            )
        }

        compose.onNodeWithText(ModelCatalogUi.SELECTION_NEEDS_TEST).assertExists()
        compose.onNodeWithContentDescription("选择模型 Model m-stale").assertIsNotEnabled()
    }

    // ---- long values -------------------------------------------------------------------------------------------

    @Test
    fun aLongBaseUrlCanBeExpandedAndCopied() {
        val longUrl = "https://gateway.example.com/" + "a".repeat(120) + "/v1"
        val provider = provider(baseUrl = longUrl)

        setContent {
            ModelServiceScreen(
                state = stateOf(provider),
                actions = ModelCatalogActions(),
                help = rememberHelpState(),
            )
        }

        compose.onNodeWithContentDescription("查看完整 Base URL").performClick()
        compose.onNodeWithText(ModelCatalogUi.FULL_VALUE_TITLE).assertExists()
        compose.onNodeWithText(ModelCatalogUi.COPY).performClick()

        val clipboard = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(longUrl, clipboard.primaryClip?.getItemAt(0)?.text?.toString())
    }

    // ---- key mask ----------------------------------------------------------------------------------------------

    @Test
    fun theKeyIsMaskedToTheLastFourCharacters() {
        val provider = provider()
        setContent {
            ModelServiceScreen(
                state = stateOf(provider, keyMask = mapOf(provider.id to "sk-••••4321")),
                actions = ModelCatalogActions(),
                help = rememberHelpState(),
            )
        }

        compose.onNodeWithText("sk-••••4321").assertExists()
        compose.onAllNodesWithText("sk-fixture-secret", substring = true).assertCountEquals(0)
    }

    // ---- errors and the retained summary ------------------------------------------------------------------------

    @Test
    fun aFailedFetchShowsTheMappedErrorAndTheSummaryCanBeDismissed() {
        var dismissed = 0
        val provider = provider()
        setContent {
            ModelServiceScreen(
                state = stateOf(
                    provider,
                    error = ModelCatalogUi.refreshError(com.sheen.rokid.model.ModelListError.AUTH),
                    refreshSummary = "新增 1 · 更新 0 · 未变 0 · 网关已移除 0 · 仅显示前 500 个模型",
                ),
                actions = ModelCatalogActions(dismissRefreshSummary = { dismissed++ }),
                help = rememberHelpState(),
            )
        }

        compose.onNodeWithText("API Key 无效或无权限").assertExists()
        compose.onNodeWithText("新增 1 · 更新 0 · 未变 0 · 网关已移除 0 · 仅显示前 500 个模型").assertExists()
        compose.onNodeWithContentDescription(ModelCatalogUi.SUMMARY_DISMISS).performClick()

        assertEquals(1, dismissed)
    }

    @Test
    fun aCorruptCatalogOffersResetAndNamesTheDamage() {
        var resets = 0
        setContent {
            ModelServiceScreen(
                state = stateOf(provider = null, corrupt = true),
                actions = ModelCatalogActions(resetCatalog = { resets++ }),
                help = rememberHelpState(),
            )
        }

        compose.onNodeWithText(ModelCatalogUi.RESET_TITLE).assertExists()
        compose.onNodeWithText(ModelCatalogUi.RESET_DETAIL).assertExists()
        compose.onNodeWithText(ModelCatalogUi.RESET).performClick()
        compose.onNodeWithText("重置模型目录？").assertExists()
        compose.onNodeWithText(ModelCatalogUi.RESET_CONFIRM).performClick()

        assertEquals(1, resets)
    }

    // ---- first run ----------------------------------------------------------------------------------------------

    private class MemoryBlobs : EncryptedBlobStore {
        val values = linkedMapOf<String, ByteArray>()
        override fun read(id: String): ByteArray? = values[id]?.copyOf()
        override fun write(id: String, value: ByteArray) {
            values[id] = value.copyOf()
        }

        override fun delete(id: String) {
            values.remove(id)
        }
    }

    private class Prompts : LegacyPromptStore {
        var shown = false
        override fun shown(): Boolean = shown
        override fun markShown() {
            shown = true
        }
    }

    private fun legacy(blobs: MemoryBlobs, prompts: Prompts): LegacyModelConfig {
        blobs.values["profiles-manifest"] =
            """{"version":1,"profiles":{"p-1":"profile-1"}}""".toByteArray(Charsets.UTF_8)
        blobs.values["profile-1"] = "old-profile-bytes".toByteArray(Charsets.UTF_8)
        return LegacyModelConfig(blobs, prompts)
    }

    @Test
    fun theFirstRunDialogCleansLegacyDataAndOpensTheModelService() {
        val blobs = MemoryBlobs()
        val prompts = Prompts()
        // Built once outside the composition: a new LegacyModelConfig per recomposition would reset the dialog's
        // remembered visibility, and production passes the composition's one stable instance.
        val legacyConfig = legacy(blobs, prompts)
        var configured = 0
        setContent {
            FirstRunModelUpgrade(legacyConfig, onConfigured = { configured++ })
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("模型配置已升级").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("模型配置方式已升级，需要重新填写服务提供商与 API Key；旧档案不会被读取。").assertExists()
        compose.onNodeWithText("去配置").performClick()

        compose.waitUntil(timeoutMillis = 10_000) { configured == 1 }
        assertFalse("the legacy manifest is deleted", blobs.values.containsKey("profiles-manifest"))
        assertFalse("the referenced legacy payload is deleted", blobs.values.containsKey("profile-1"))
        assertTrue("the prompt is recorded so it never returns", prompts.shown)
    }

    @Test
    fun theLaterButtonRecordsThePromptAndKeepsTheLegacyPayloads() {
        val blobs = MemoryBlobs()
        val prompts = Prompts()
        val legacyConfig = legacy(blobs, prompts)
        var configured = 0
        setContent {
            FirstRunModelUpgrade(legacyConfig, onConfigured = { configured++ })
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithText("模型配置已升级").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("稍后").performClick()

        compose.waitUntil(timeoutMillis = 10_000) { prompts.shown }
        assertEquals(0, configured)
        assertNotNull("the old payloads are kept but never read", blobs.values["profiles-manifest"])
        assertNotNull(blobs.values["profile-1"])
        compose.onAllNodesWithText("模型配置已升级").assertCountEquals(0)
    }
}
