package edith.agent

import edith.plugin.ContextPart

/**
 * The long-term-memory retrieval boundary of the agent runtime (spec §6.5): turns a query into context parts that a
 * caller may fold into the next model request.
 *
 * P3.1 ships the **interface and its boundary only**. Task 14 owns the storage-backed implementation over the
 * encrypted `Capability.Storage` namespace, together with the view/delete affordances; **retrieval quality is
 * explicitly out of P3.1's scope** (spec §12), so nothing in this module ranks, scores or evaluates results and no
 * implementation lives here.
 */
interface MemoryRetrieval {
    /**
     * Returns up to [limit] context parts relevant to [query]. Never returns more than [limit]; ordering and quality
     * are the implementation's concern and are not specified by P3.1.
     */
    fun retrieve(query: String, limit: Int): List<ContextPart>
}
