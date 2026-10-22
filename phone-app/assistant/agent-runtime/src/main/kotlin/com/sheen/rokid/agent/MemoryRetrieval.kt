package com.sheen.rokid.agent

/**
 * The long-term-memory retrieval boundary of the agent runtime (spec §6.5): turns a query into segments that a
 * caller may fold into the next request frame.
 *
 * Phase 3 changed the return type from `List<ContextPart>` to `List<Segment>` as part of the request assembly
 * convergence. The interface and its boundary remain; implementations produce `MEMORY_EVIDENCE` segments.
 */
interface MemoryRetrieval {
    /**
     * Returns up to [limit] memory evidence segments relevant to [query]. Never returns more than [limit]; ordering
     * and quality are the implementation's concern.
     */
    fun retrieve(query: String, limit: Int): List<Segment>
}
