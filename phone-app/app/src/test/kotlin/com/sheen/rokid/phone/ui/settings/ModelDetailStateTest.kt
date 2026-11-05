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
 * safety reserve is enforced, an edit protects exactly the fields it changed, thinking is a single user switch that
 * defaults off/low for every model (0.53 ruling A: no family judgement), and audio is reserved rather than editable.
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
    fun `every model can switch thinking on and the save path keeps it`() {
        val custom = entry(id = "custom-vl", thinkingSupported = false)
        val customDraft = ModelDetailDraft.from(custom)
        assertFalse("thinking defaults off", customDraft.thinkingEnabled)
        assertEquals("the effort defaults to low", ThinkingEffort.LOW, customDraft.thinkingEffort)

        // 0.53 ruling A: no family judgement. Any model may store the switch, and the switch never writes the legacy
        // capability bit.
        val on = ModelDetailDraft.toEntry(
            customDraft.copy(thinkingEnabled = true, thinkingEffort = ThinkingEffort.MAX),
            custom,
        )
        assertTrue("a non-DeepSeek model can turn thinking on", on.thinkingEnabled)
        assertFalse("the switch never writes the legacy capability bit", on.thinkingSupported)
        assertEquals(ThinkingEffort.MAX, on.thinkingEffort)

        // A DeepSeek id is not special-cased either: the stored capability is passed through, never derived.
        val deepseek = entry(id = "deepseek-flash", thinkingSupported = false)
        val deepseekDraft = ModelDetailDraft.from(deepseek)
        assertFalse("the id no longer derives the capability", deepseekDraft.thinkingSupported)
        assertEquals("an untouched draft rebuilds the same row", deepseek, ModelDetailDraft.toEntry(deepseekDraft, deepseek))
    }

    @Test
    fun `an unrelated edit keeps a non-DeepSeek row's thinking switch`() {
        // No Compose needed: the detail page renders the block unconditionally and only the draft/save pair carries
        // the switch, so a round-trip proves the block's state survives every model.
        val previous = entry(
            id = "custom-vl",
            thinkingSupported = false,
            thinkingEnabled = true,
            thinkingEffort = ThinkingEffort.HIGH,
        )
        val saved = ModelDetailDraft.toEntry(
            ModelDetailDraft.from(previous).copy(displayName = "Renamed"),
            previous,
        )
        assertTrue("the switch survives an unrelated edit", saved.thinkingEnabled)
        assertEquals("the effort survives an unrelated edit", ThinkingEffort.HIGH, saved.thinkingEffort)
        assertFalse("the compatibility bit is neither invented nor derived", saved.thinkingSupported)
        assertEquals(
            "only the edited field is protected from the next refresh",
            setOf(ModelField.DISPLAY_NAME),
            saved.overrides,
        )
    }

    @Test
    fun `the thinking copy states the DeepSeek-only support and the budget default`() {
        assertEquals(
            "思考模式仅支持 DeepSeek 模型（官方 thinking 协议）；其他模型开启后可能无效或报错。",
            ModelDetailUi.THINKING_ONLY_DEEPSEEK,
        )
        assertEquals(
            "开启后工具回合保留思考，输出预算按官方默认 64K/128K。关闭表示不主动请求深度思考；" +
                "DeepSeek 服务端可能仍有默认思考，应用会保留并回传其思考内容。",
            ModelDetailUi.THINKING_DEFAULT_BUDGET_HINT,
        )
        assertEquals(
            "若模型的上下文窗口小于「官方默认输出（64K/128K）+ 安全余量（256 tokens）」，开启思考后请求会因预算不足被拒绝；" +
                "DeepSeek V4.1 Flash 的窗口为 1M。",
            ModelDetailUi.THINKING_WINDOW_HINT,
        )
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
