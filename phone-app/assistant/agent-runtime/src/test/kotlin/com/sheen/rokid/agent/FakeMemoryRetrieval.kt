package com.sheen.rokid.agent

/**
 * Test-only [MemoryRetrieval]: hands back the injected segments and honours [retrieve]'s limit.
 */
internal class FakeMemoryRetrieval(private val parts: List<Segment>) : MemoryRetrieval {
    override fun retrieve(query: String, limit: Int): List<Segment> = parts.take(limit.coerceAtLeast(0))
}
