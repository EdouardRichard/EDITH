package com.sheen.rokid.phone.plugin.smarthelper
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryModule
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryMeasurementFormTest
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryModuleRetrieval

import com.sheen.rokid.agent.ConversationStore
import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.InputPart
import com.sheen.rokid.plugin.ModelHistoryItem
import com.sheen.rokid.plugin.ModelRequest
import com.sheen.rokid.phone.plugin.smarthelper.context.ContextRoute
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryKind
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryLog
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemoryMeasurementForm
import com.sheen.rokid.phone.plugin.smarthelper.memory.MemorySource
import com.sheen.rokid.phone.plugin.smarthelper.memory.normalize
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Phase 7 F4: the **call site** of the memory measurement switch, not just the field behind the setter.
 *
 * `MemoryMeasurementFormTest` pins that the setter stores a value; this class pins that [SmartAssistantPlugin.activate]
 * actually reads that value and hands the matching renderer to the session's `MemoryModule`. Without it, deleting the
 * `renderer = …` argument (or inverting its branches) would leave the whole JVM suite green while the debug receiver
 * silently stopped changing the F4 form.
 *
 * The fixture is the preregistered one (§2.1/§2.2), written through the production dual-storage path
 * (`MemoryLog` + `normalize`) so the entry really carries both the raw utterance and the normalized statement. The
 * turn is a production store/frame turn: a `ConversationStore` per route, a real `MemoryModuleRetrieval`, the real
 * gateway, and only the transport is the rig's fake.
 */
class MemoryFormWiringTest {

    /** One fresh store per route, exactly as `SmartWiring` builds them (the store/frame path). */
    private fun stores(): Map<ContextRoute, ConversationStore> =
        ContextRoute.entries.associateWith { ConversationStore() }

    /** Writes the preregistered memory into [storage] through the production record + normalizer path. */
    private fun seed(storage: SmartFakeStorage) {
        val entry = MemoryLog(storage, normalizer = ::normalize)
            .record(MemoryKind.FACT, MEMORY_RAW, MemorySource.EXPLICIT)
        requireNotNull(entry) { "the fixture must be able to write its one memory" }
        assertTrue(entry.normalizedText.isNotBlank(), "the seeded entry must carry the normalized form")
    }

    /**
     * The request a wake turn produces under [form], or the default `PRODUCTION` form when `form` is `null`.
     *
     * [MemoryMeasurementForm.LEGACY_RAW] is set **before** `activate`, which is the only time the plugin reads it;
     * the control path never calls the setter, so it exercises the production default.
     */
    private fun requestFor(form: MemoryMeasurementForm?): ModelRequest {
        val storage = SmartFakeStorage()
        seed(storage)
        val rig = SmartTestRig(conversationStores = stores())
        if (form != null) rig.plugin.setMemoryMeasurementForm(form)
        rig.activate(overrides = mapOf(Capability.Storage to storage))
        // A completed stream keeps the turn from waiting on a deadline the fake clock never reaches; the request the
        // gateway builds is recorded before the reply is read, so the reply's content is irrelevant here.
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }
        rig.wakeAndFinal(QUESTION)
        return rig.model.requests.single()
    }

    /** Everything the transport would put in front of the model as text: the input and the history. */
    private fun textOf(request: ModelRequest): String = buildList {
        addAll(request.input.filterIsInstance<InputPart.Text>().map { it.text })
        addAll(request.history.filterIsInstance<ModelHistoryItem.Text>().map { it.text })
    }.joinToString("\n")

    @Test
    fun `LEGACY_RAW selected before activation makes the request carry the bare raw memory`() {
        val text = textOf(requestFor(MemoryMeasurementForm.LEGACY_RAW))

        assertTrue(text.contains(MEMORY_RAW), "legacy raw must replay the original utterance: $text")
        assertFalse(text.contains("<reference_context>"), "legacy raw must not gain a boundary tag: $text")
        assertFalse(text.contains("用户曾告知"), "legacy raw must not gain the third-person prefix: $text")
    }

    @Test
    fun `the production default keeps the normalized reference block in the request`() {
        // No setter call at all: this is the form every production activation uses.
        val text = textOf(requestFor(null))

        assertTrue(text.contains("<reference_context>"), "the production default must carry the boundary tag: $text")
        assertTrue(
            text.contains("用户曾告知$MEMORY_RAW"),
            "the production default must render the normalized statement: $text",
        )
    }

    private companion object {
        /** Design §2.1: the one preregistered memory, verbatim. */
        const val MEMORY_RAW = "播放周杰伦的稻香"

        /** Design §2.2: the one fixed wake-turn question. */
        const val QUESTION = "打开音乐，播放李克勤的月半小夜曲"
    }
}
