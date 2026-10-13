package edith.plugins.smart.memory

import edith.plugin.ContextPart
import edith.plugin.EmbeddingService

/**
 * The memory facade: one object for writing a memory, finding one, and handing the result to a prompt (spec §5.1).
 *
 * It composes three things that used to be wired directly by the plugin — [MemoryLog] (the store),
 * [SemanticRetriever] (the vectors) and [SmartMemoryRetrieval]'s lexical ranking (the degradation) — so the turn
 * pipeline asks one question instead of three.
 *
 * **Retrieval costs one forward pass, always.** The query is embedded; the store is not. Entries become searchable
 * when they are *written*, so a memory learned this session is found by meaning, while one restored from storage
 * keeps the lexical order until something writes it again. That is a deliberate bound, not an oversight: see
 * [SemanticRetriever] for the arithmetic.
 *
 * **Degradation, in order.** No embedding service → lexical. The query cannot be embedded → lexical. A namespace
 * that refuses to be read → nothing at all, because inventing a memory to fill the gap would be worse than answering
 * from nothing. Writing never throws: a store that refuses answers `null` and the turn carries on (spec §5.5).
 */
class MemoryModule(
    private val log: MemoryLog,
    private val embeddings: EmbeddingService?,
    private val lexicon: SmartMemoryRetrieval,
    private val retriever: SemanticRetriever = SemanticRetriever(embeddings),
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** Writes one raw message and indexes it (spec §5.5: the original text, never a summary). */
    fun remember(text: String, kind: MemoryKind, source: MemorySource): MemoryEntry? {
        val entry = log.record(kind, text, source) ?: return null
        retriever.index(entry)
        return entry
    }

    /**
     * Up to [limit] memories related to [query], most relevant first, marking them used so §5.6's eviction can see
     * that they earned their place.
     */
    fun retrieve(query: String, limit: Int = INJECT_LIMIT): List<MemoryEntry> {
        val asked = query.trim()
        if (asked.isEmpty()) return emptyList()
        val snapshot = log.snapshot()
        if (!snapshot.readOk) return emptyList()
        val entries = snapshot.entries
        if (entries.isEmpty()) return emptyList()

        val semantic = retriever.rerank(asked, entries)
        val ranked = if (semantic == null) {
            lexical(entries, asked, limit)
        } else {
            // Entries the index has never seen keep the lexical order and follow the semantically ranked ones.
            val rankedIds = semantic.mapTo(mutableSetOf()) { it.id }
            (semantic + lexical(entries.filterNot { it.id in rankedIds }, asked, limit)).take(limit)
        }
        if (ranked.isNotEmpty()) log.markUsed(ranked.map { it.id })
        return ranked
    }

    /** The form `buildPrompt` injects: ids, texts and an importance derived from how well each one matched (§5.7). */
    fun injectionFor(query: String, limit: Int = INJECT_LIMIT): List<ContextPart> =
        retrieve(query, limit).map { entry ->
            ContextPart(
                id = ID_PREFIX + entry.id,
                text = entry.text,
                importance = lexicalScore(query, entry.text).coerceIn(0, MAX_IMPORTANCE),
            )
        }

    fun snapshot(): MemorySnapshot = log.snapshot()

    fun entries(): List<MemoryEntry> = log.entries()

    /** Best effort, matching the lexical retriever's own contract: a namespace that refuses yields no memories. */
    private fun lexical(entries: List<MemoryEntry>, query: String, limit: Int): List<MemoryEntry> {
        if (entries.isEmpty()) return emptyList()
        return rank(entries, query, now(), limit, CHAR_BUDGET)
    }

    companion object {
        /** Spec §5.7: the top-5 related memories are injected. */
        const val INJECT_LIMIT = 5

        /** Matches `SmartMemoryRetrieval.CHAR_BUDGET`, so the two orders are bounded by the same number. */
        const val CHAR_BUDGET = 300

        private const val ID_PREFIX = "memory:"
        private const val MAX_IMPORTANCE = 10
    }
}
