package com.sheen.rokid.model

import com.sheen.rokid.plugin.ModelStreamEvent
import com.sheen.rokid.plugin.ModelUsage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 0.3 design §5.3: the cache counts are read from the provider's own usage members — OpenAI's
 * `prompt_tokens_details.cached_tokens` and DeepSeek's `prompt_cache_hit_tokens`/`prompt_cache_miss_tokens` — and stay
 * absent when the provider reports none. A missing count is not a zero, and a miss count is never derived by
 * subtracting the hit count from the input count.
 */
class ChatCompletionsDecoderTest {

    private fun finish(usage: String) = SseFrame(null,
        """{"choices":[{"delta":{},"finish_reason":"stop"}],"usage":$usage}""")

    private fun usageOf(usage: String): ModelUsage? {
        val terminal = ChatCompletionsDecoder("q").accept(finish(usage)).single()
        return (terminal as ModelStreamEvent.Terminal).usage
    }

    @Test fun `usage exposes openai cached tokens from prompt token details`() {
        val usage = usageOf("""{"prompt_tokens":100,"completion_tokens":20,"prompt_tokens_details":{"cached_tokens":64}}""")
        assertEquals(64L, usage?.cachedInputTokens)
        assertNull(usage?.cacheMissInputTokens, "the provider reported no miss count; it must not be derived")
    }

    @Test fun `usage exposes deepseek prompt cache hit and miss tokens`() {
        val usage = usageOf("""{"prompt_tokens":100,"completion_tokens":20,"prompt_cache_hit_tokens":64,"prompt_cache_miss_tokens":36}""")
        assertEquals(64L, usage?.cachedInputTokens)
        assertEquals(36L, usage?.cacheMissInputTokens)
    }

    @Test fun `usage leaves cache fields null when the provider omits them`() {
        val usage = usageOf("""{"prompt_tokens":100,"completion_tokens":20}""")
        assertEquals(100L, usage?.inputTokens)
        assertEquals(20L, usage?.outputTokens)
        assertNull(usage?.cachedInputTokens)
        assertNull(usage?.cacheMissInputTokens)
    }
}
