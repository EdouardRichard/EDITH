package com.sheen.rokid.phone.ui.settings

import com.sheen.rokid.phone.model.catalog.CatalogValidation
import com.sheen.rokid.phone.model.catalog.ModelEntry
import com.sheen.rokid.phone.model.catalog.ModelField
import com.sheen.rokid.phone.model.catalog.ModelOrigin
import com.sheen.rokid.plugin.InputModality
import com.sheen.rokid.plugin.OutputModality
import com.sheen.rokid.plugin.ThinkingEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 0.52 design §5.6/§5.7: the model detail page's pure editing rules.
 *
 * The page is host-free, so the K-token conversion, the budget rule and the `overrides` bookkeeping are the draft's own
 * responsibility. These cases pin the rules the Composable only renders: a window round-trips through K tokens, the
 * safety reserve is enforced, an edit protects exactly the fields it changed, thinking is a DeepSeek-family capability
 * that defaults off/low, and audio is reserved rather than editable.
 */
class ModelDetailStateTest {

    private fun entry(
        id: String = "deepseek-flash",
        displayName: String = "DeepSeek Flash",
        window: Int = 131_072,
        output: Int = 8_192,
        inputs: Set<InputModality> = setOf(InputModality.TEXT, InputModality.IMAGE),
        thinkingSupported: Boolean = true,
        thinkingEnabled: Boolean = false,
        thinkingEffort: ThinkingEffort = ThinkingEffort.LOW,
        overrides: Set<ModelField> = emptySet(),
    ) = ModelEntry(
        id = id,
        displayName = displayName,
        contextWindowTokens = window,
        maxOutputTokens = output,
        inputs = inputs,
        outputs = setOf(OutputModality.TEXT),
        thinkingEnabled = thinkingEnabled,
        thinkingEffort = thinkingEffort,
        thinkingSupported = thinkingSupported,
        origin = ModelOrigin.GATEWAY,
        overrides = overrides,
    )

    @Test
    fun `k values round trip and reject zero negative and overflow`() {
        val previous = entry(window = 131_072, output = 8_192)
        val draft = ModelDetailDraft.from(previous)

        assertEquals("128", draft.maxInputK)
        assertEquals("8", draft.maxOutputK)
        assertEquals(
            "an untouched draft must rebuild exactly the stored row",
            previous,
            ModelDetailDraft.toEntry(draft, previous),
        )
        assertEquals(1_048_576, ModelDetailDraft.parseKTokens("1024"))
        assertEquals(131_072, ModelDetailDraft.parseKTokens("128"))
        // A gateway window is not necessarily a whole K: the K field still has to round-trip it exactly.
        val odd = entry(window = 100_000, output = 8_000)
        val oddDraft = ModelDetailDraft.from(odd)
        assertEquals("the K fields keep enough precision to round-trip metadata", odd, ModelDetailDraft.toEntry(oddDraft, odd))

        listOf("0", "0.0", "-1", "", "   ", "abc", "1e3", "2097152").forEach { invalid ->
            assertNull("K tokens must reject <$invalid>", ModelDetailDraft.parseKTokens(invalid))
            assertThrows("toEntry must refuse the invalid window <$invalid>", IllegalArgumentException::class.java) {
                ModelDetailDraft.toEntry(draft.copy(maxInputK = invalid), previous)
            }
        }
        assertEquals(
            "the largest storable window is still accepted",
            2_147_482_624,
            ModelDetailDraft.parseKTokens("2097151"),
        )
    }

    @Test
    fun `the output must leave room for the safety reserve`() {
        val previous = entry(window = 16_384, output = 8_192)
        val draft = ModelDetailDraft.from(previous)
        assertFalse("16K / 8K leaves well over the reserve", ModelDetailDraft.validate(draft).any)

        // Exactly on the boundary: 8192 - 7936 - 256 == 0 is not allowed.
        val noRoom = draft.copy(maxInputK = "8", maxOutputK = "7.75")
        val boundaryErrors = ModelDetailDraft.validate(noRoom)
        assertEquals(CatalogValidation.budgetError(8_192, 7_936), boundaryErrors.budget)
        assertTrue(boundaryErrors.any)
        assertThrows(IllegalArgumentException::class.java) {
            ModelDetailDraft.toEntry(noRoom, previous)
        }

        // One token above the reserve is legal: 8192 - 7935 - 256 == 1.
        val oneToken = draft.copy(maxInputK = "8", maxOutputK = "7.749")
        assertFalse(ModelDetailDraft.validate(oneToken).any)
        assertEquals(7_935, ModelDetailDraft.parseKTokens(oneToken.maxOutputK))
        assertEquals(8_192, ModelDetailDraft.toEntry(oneToken, previous).contextWindowTokens)
        assertEquals(256, CatalogValidation.SAFETY_TOKENS)

        val outputTooSmall = draft.copy(maxOutputK = "0")
        assertTrue(ModelDetailDraft.validate(outputTooSmall).output != null)
    }

    @Test
    fun `editing a field adds exactly that field to overrides`() {
        val previous = entry(overrides = setOf(ModelField.DISPLAY_NAME))
        val draft = ModelDetailDraft.from(previous)

        val untouched = ModelDetailDraft.toEntry(draft, previous)
        assertEquals("an untouched save must not invent an override", setOf(ModelField.DISPLAY_NAME), untouched.overrides)

        val window = ModelDetailDraft.toEntry(draft.copy(maxInputK = "256"), previous)
        assertEquals(
            setOf(ModelField.DISPLAY_NAME, ModelField.CONTEXT_WINDOW_TOKENS),
            window.overrides,
        )
        assertEquals("the untouched fields keep their stored values", previous.maxOutputTokens, window.maxOutputTokens)
        assertEquals(previous.inputs, window.inputs)

        val output = ModelDetailDraft.toEntry(draft.copy(maxOutputK = "16"), previous)
        assertEquals(setOf(ModelField.DISPLAY_NAME, ModelField.MAX_OUTPUT_TOKENS), output.overrides)
        assertEquals(previous.contextWindowTokens, output.contextWindowTokens)

        val inputs = ModelDetailDraft.toEntry(draft.copy(inputs = setOf(InputModality.TEXT)), previous)
        assertEquals(setOf(ModelField.DISPLAY_NAME, ModelField.INPUTS), inputs.overrides)
        assertEquals(setOf(InputModality.TEXT), inputs.inputs)

        val name = ModelDetailDraft.toEntry(draft.copy(displayName = "Renamed"), previous)
        assertEquals("only the edited field is added", setOf(ModelField.DISPLAY_NAME), name.overrides)
        assertEquals("Renamed", name.displayName)
    }

    @Test
    fun `the thinking block is always visible but only editable for supported models`() {
        val custom = entry(id = "custom-vl", thinkingSupported = false)
        val customDraft = ModelDetailDraft.from(custom)
        assertTrue("0.53 keeps the block visible for every model", ModelDetailUi.thinkingVisible(customDraft))
        assertFalse("a non-DeepSeek model cannot edit thinking", ModelDetailUi.thinkingEditable(customDraft))
        assertFalse(customDraft.thinkingSupported)
        assertFalse("thinking defaults off", customDraft.thinkingEnabled)
        assertEquals("the effort defaults to low", ThinkingEffort.LOW, customDraft.thinkingEffort)

        // A model that is not a thinking model can never store an enabled thinking flag.
        val forced = ModelDetailDraft.toEntry(customDraft.copy(thinkingEnabled = true), custom)
        assertFalse(forced.thinkingEnabled)
        assertFalse(forced.thinkingSupported)

        // The advanced marker stays the metadata-missing escape hatch for a non-DeepSeek model.
        val marked = ModelDetailDraft.toEntry(customDraft.copy(thinkingSupported = true), custom)
        assertTrue(marked.thinkingSupported)
        assertTrue(ModelDetailUi.thinkingEditable(ModelDetailDraft.from(marked)))

        // 0.53: a DeepSeek-family id derives the capability even when the stored row says unsupported.
        val deepseek = entry(id = "deepseek-flash", thinkingSupported = false)
        val derived = ModelDetailDraft.from(deepseek)
        assertTrue("a derived DeepSeek model is thinking-capable", derived.thinkingSupported)
        assertTrue(ModelDetailUi.thinkingEditable(derived))
        assertFalse("a DeepSeek model still defaults to thinking off", derived.thinkingEnabled)
        assertEquals(ThinkingEffort.LOW, derived.thinkingEffort)

        val persisted = ModelDetailDraft.toEntry(derived, deepseek)
        assertTrue("saving a derived model persists the capability", persisted.thinkingSupported)
        assertTrue(
            "a derived DeepSeek model cannot be un-marked by the advanced switch",
            ModelDetailDraft.toEntry(derived.copy(thinkingSupported = false), deepseek).thinkingSupported,
        )

        val enabled = ModelDetailDraft.toEntry(
            derived.copy(thinkingEnabled = true, thinkingEffort = ThinkingEffort.MAX),
            deepseek,
        )
        assertTrue(enabled.thinkingEnabled)
        assertTrue(enabled.thinkingSupported)
        assertEquals(ThinkingEffort.MAX, enabled.thinkingEffort)
    }

    @Test
    fun `the thinking copy names the DeepSeek-only restriction`() {
        assertEquals("思考模式仅支持 DeepSeek 模型；当前模型按通用 OpenAI 协议请求。", ModelDetailUi.THINKING_ONLY_DEEPSEEK)
        assertTrue(ModelDetailUi.THINKING_SUPPORTED_HINT.contains("仅支持 DeepSeek 模型"))
        assertTrue(ModelDetailUi.THINKING_SUPPORTED_HINT.contains("工具回合保留思考"))
        assertTrue(ModelDetailUi.THINKING_SUPPORTED_HINT.contains("思考历史随请求回传"))
        assertTrue(ModelDetailUi.THINKING_MARK_DERIVED_DETAIL.contains("DeepSeek"))
        assertTrue(ModelDetailUi.THINKING_MARK_DERIVED_DETAIL.contains("不能关闭"))
    }

    // Brief case: `audio is always disabled and labelled for 0.53` (0.53 contains a dot, illegal in a JVM method name).
    @Test
    fun `audio is always disabled and labelled for the next release`() {
        assertEquals("0.52 未接入，0.53 开放", ModelDetailUi.AUDIO_DISABLED)
        assertFalse("0.52 has no audio input", ModelDetailUi.AUDIO_ENABLED)

        // An audio declaration that predates 0.52 is preserved, never editable, and never added by an edit.
        val previous = entry(inputs = setOf(InputModality.TEXT, InputModality.IMAGE, InputModality.AUDIO))
        val draft = ModelDetailDraft.from(previous)
        assertTrue(InputModality.AUDIO in draft.inputs)
        val withoutImage = ModelDetailDraft.toEntry(draft.copy(inputs = draft.inputs - InputModality.IMAGE), previous)
        assertEquals(setOf(InputModality.TEXT, InputModality.AUDIO), withoutImage.inputs)

        val textOnly = entry(inputs = setOf(InputModality.TEXT))
        val edited = ModelDetailDraft.toEntry(ModelDetailDraft.from(textOnly).copy(displayName = "Renamed"), textOnly)
        assertFalse("an edit never turns audio on", InputModality.AUDIO in edited.inputs)
        assertTrue("text stays locked on", InputModality.TEXT in edited.inputs)
    }
}
