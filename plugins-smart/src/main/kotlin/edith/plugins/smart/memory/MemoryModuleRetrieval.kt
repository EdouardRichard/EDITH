package edith.plugins.smart.memory

import edith.agent.MemoryRetrieval
import edith.plugin.ContextPart
import edith.plugins.smart.P3Log

/**
 * edith-0.2 Phase B (B5): makes the memory facade satisfy the agent runtime's retrieval port.
 *
 * The two interfaces do not line up on purpose — [MemoryModule] is the plugin's own storage-and-ranking facade, and
 * `edith.agent.MemoryRetrieval` is the boundary `ModelClientGateway` asks. This class is the single place where a
 * **production turn's** memory parts acquire their semantic ordering. Without it, building the module would change
 * nothing: the gateway would keep asking a port that the lexical retriever implements, which is exactly the state
 * B5 was in — a module with tests and no caller.
 *
 * It logs the same `P3_MEMORY inject=` line the lexical retriever used to, because that line is the device log's only
 * evidence that memory was injected at all. It carries **counts, a character total and ids — never content**
 * (spec §5.3). The lexical retriever also printed a `topScore`; a score is not part of this port's answer, so that
 * field is gone rather than recomputed from a ranking the caller cannot see.
 */
class MemoryModuleRetrieval(
    private val memory: MemoryModule,
    private val logger: (String) -> Unit = { P3Log.line(it) },
) : MemoryRetrieval {

    override fun retrieve(query: String, limit: Int): List<ContextPart> {
        val parts = memory.injectionFor(query, limit)
        // A blank query, an empty store or a namespace that refused answers nothing and logs nothing: saying
        // `inject=n=0` would look like a measurement of something that never happened.
        if (parts.isNotEmpty()) {
            logger(
                "P3_MEMORY inject=n=${parts.size} chars=${parts.sumOf { it.text.length }} " +
                    "ids=${parts.joinToString(",") { it.id }}",
            )
        }
        return parts
    }
}
