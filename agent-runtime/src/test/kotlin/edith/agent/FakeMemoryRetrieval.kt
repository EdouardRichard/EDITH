package edith.agent

import edith.plugin.ContextPart

/**
 * Test-only [MemoryRetrieval]: hands back the injected parts and honours [retrieve]'s limit. P3.1 ships no production
 * implementation — Task 14 owns the storage-backed one over the encrypted `Capability.Storage` namespace (spec §6.5).
 */
internal class FakeMemoryRetrieval(private val parts: List<ContextPart>) : MemoryRetrieval {
    override fun retrieve(query: String, limit: Int): List<ContextPart> = parts.take(limit.coerceAtLeast(0))
}
