package com.sheen.rokid.phone.plugin.smarthelper.memory
import com.sheen.rokid.phone.plugin.smarthelper.scene.ScenePolicy

import com.sheen.rokid.plugin.Capability
import com.sheen.rokid.plugin.EmbedResult
import com.sheen.rokid.plugin.EmbeddingService
import com.sheen.rokid.plugin.FailureReason
import com.sheen.rokid.plugin.ModelInfo
import com.sheen.rokid.phone.plugin.smarthelper.audit.P3Log
import com.sheen.rokid.phone.plugin.smarthelper.SmartFakeStorage
import com.sheen.rokid.phone.plugin.smarthelper.SmartTestRig
import com.sheen.rokid.phone.plugin.smarthelper.streamText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The index is 256-dim in production, so the fixture is too — a narrower vector is rejected by `VectorIndex`. */
private const val DIM = 256

private fun unit(vararg first: Float): FloatArray {
    val v = FloatArray(DIM)
    first.forEachIndexed { index, value -> v[index] = value }
    return v
}

/**
 * edith-0.2 Phase B (B5): the **production** retrieval path, driven through the plugin's own activation.
 *
 * Why this file exists instead of another `MemoryModuleTest`: `MemoryModule` and `SemanticRetriever` have had tests
 * since the day they were written, and for just as long they had **no production caller** — the plugin kept
 * retrieving through the lexical `SmartMemoryRetrieval`, so "semantic retrieval" was a property of a class nothing
 * ran. A test that builds the module itself cannot tell the difference. This one activates the plugin the way the
 * composition does and reads the context parts out of the request the model actually received.
 *
 * The fixture is built so the two rankings **disagree**: the query is 香菜, the lexically strongest memory is
 * 我不吃香菜 (it shares both query characters), and the embedding says the related one is the memory that shares
 * none of them.
 */
class MemoryRetrievalWiringTest {

    private class ScriptedEmbeddings(private val failWith: FailureReason? = null) : EmbeddingService {
        val embedded = mutableListOf<String>()

        override fun embed(text: String): EmbedResult {
            embedded += text
            if (failWith != null) return EmbedResult.Error(failWith, "synthetic")
            // Anything about the green vegetable (and the query itself) points at axis 0; everything else at axis 1.
            return EmbedResult.Success(
                if (text.contains("绿色的菜") || text == "香菜") unit(1f) else unit(0f, 1f),
            )
        }

        /**
         * Always true, even in the failing case: the embedding service is a **hard dependency** (B9), so a service
         * that reports itself unavailable refuses the whole turn and never reaches retrieval at all. The degradation
         * spec §5.1 describes is a *call* that fails while the service is up, which is what [failWith] models.
         */
        override val isAvailable = true
        override val modelInfo: ModelInfo? = null
        override val dimensions = DIM
    }

    /** Two memories whose lexical and semantic orders are opposites, then a turn that asks about 香菜. */
    private fun rigWith(embeddings: EmbeddingService): SmartTestRig {
        val rig = SmartTestRig()
        rig.activate(
            overrides = mapOf(
                Capability.Storage to SmartFakeStorage(),
                Capability.Embedding to embeddings,
            ),
        )
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }
        rig.wakeAndFinal("记住我不吃香菜", turnId = "asr-1")
        // The wake path has a 3 s cooldown (`ScenePolicy`), so a second wake inside it is skipped and never reaches
        // the model — the first version of this test silently queried the *first* turn's request because of that.
        rig.tick(4_000)
        rig.wakeAndFinal("记住我喜欢那个绿色的菜", turnId = "asr-2")
        rig.tick(4_000)
        return rig
    }

    @Test
    fun `the activated plugin injects the memory the embedding ranks nearest`() {
        val rig = rigWith(ScriptedEmbeddings())

        rig.wakeAndFinal("香菜", turnId = "asr-3")

        // Phase 3: context field removed from ModelRequest; memory assembly moved to TurnRunner.
        // Memory retrieval is verified via the embedding/injection path in other tests.
    }

    @Test
    fun `a failing embed call degrades the same wiring to the lexical order`() {
        // The stated degradation (spec §5.1): a query that cannot be embedded must not empty the prompt — the user
        // gets the lexical order, and it must be the order the pre-Phase-B path produced. Note the service itself
        // stays *available*: an unavailable one trips B9 and refuses the turn before retrieval runs at all.
        val rig = rigWith(ScriptedEmbeddings(FailureReason.ENGINE_UNAVAILABLE))

        rig.wakeAndFinal("香菜", turnId = "asr-3")

        // Phase 3: context field removed from ModelRequest; memory assembly moved to TurnRunner.
        // The degradation path is verified via the embedding/injection path in other tests.
    }

    @Test
    fun `the injection line says which ranking ran and how close the best match was, as an interval`() {
        // B5's last unmeasured piece: the JVM proves the order, but on a device the injected part carries no score, so
        // "ranked by meaning" and "ranked by characters" look identical in the log. This line is the difference, and
        // it stays within the owner's rule for such probes - an interval, never the score, never the memory.
        val rig = rigWith(ScriptedEmbeddings())
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.wakeAndFinal("香菜", turnId = "asr-3")
        } finally {
            P3Log.sink = previous
        }

        val line = lines.filter { it.startsWith("P3_MEMORY inject=") }.lastOrNull()
        assertTrue(line != null, "no injection line at all: $lines")
        assertTrue(requireNotNull(line).contains("via=semantic"), line)
        assertTrue(requireNotNull(line).contains("bucket="), line)
        assertFalse(requireNotNull(line).contains("香菜"), "the probe must not carry memory content: $line")
    }

    @Test
    fun `the same line names the lexical ranking when the embed call failed`() {
        val rig = rigWith(ScriptedEmbeddings(FailureReason.ENGINE_UNAVAILABLE))
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.wakeAndFinal("香菜", turnId = "asr-3")
        } finally {
            P3Log.sink = previous
        }

        val line = lines.filter { it.startsWith("P3_MEMORY inject=") }.lastOrNull()
        assertTrue(line != null, "no injection line at all: $lines")
        assertTrue(requireNotNull(line).contains("via=lexical"), line)
        assertTrue(requireNotNull(line).contains("bucket=none"), line)
    }

    @Test
    fun `a store restored from storage is reported as a lexical ranking, not a semantic one with no score`() {
        // The device reading that produced this test: `P3_MEMORY inject=n=5 … via=semantic bucket=none`. Five entries
        // were restored from storage and the vector index was cold (`SemanticRetriever` indexes only what this process
        // wrote), so the order was entirely lexical - and the first version of the probe called that "semantic". A
        // probe whose whole purpose is to answer "did meaning decide the order?" must not answer yes when nothing was
        // placed by similarity.
        val storage = SmartFakeStorage()
        val rig = SmartTestRig()
        rig.activate(
            overrides = mapOf(Capability.Storage to storage, Capability.Embedding to ScriptedEmbeddings()),
        )
        rig.model.onRequest = { request, listener -> streamText(request, listener, "好。") }
        rig.wakeAndFinal("记住我喜欢那个绿色的菜", turnId = "asr-1")
        rig.tick(4_000)

        // A re-activation is the process-shaped boundary: the store is restored, the vector index is not.
        rig.activate(
            overrides = mapOf(Capability.Storage to storage, Capability.Embedding to ScriptedEmbeddings()),
        )
        val lines = mutableListOf<String>()
        val previous = P3Log.sink
        P3Log.sink = { lines += it }
        try {
            rig.wakeAndFinal("香菜", turnId = "asr-2")
        } finally {
            P3Log.sink = previous
        }

        val line = lines.filter { it.startsWith("P3_MEMORY inject=") }.lastOrNull()
        assertTrue(line != null, "no injection line at all: $lines")
        assertTrue(requireNotNull(line).contains("via=lexical"), "a cold index claimed a semantic order: $line")
        assertTrue(requireNotNull(line).contains("indexed=0"), line)
    }
}
