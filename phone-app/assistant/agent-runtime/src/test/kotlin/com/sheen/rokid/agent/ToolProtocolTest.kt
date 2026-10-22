package com.sheen.rokid.agent

import kotlin.test.Test
import kotlin.test.assertFailsWith

class ToolProtocolTest {

    @Test fun `a blank item id is rejected at the port`() {
        // `ModelHistoryItem.ToolCall` also refuses a blank item id, but a gateway that supplies a blank claim must fail
        // here, where the failure names the tool port, instead of surfacing deep inside the transcript assembly.
        listOf("", " ", "\t").forEach { blank ->
            assertFailsWith<IllegalArgumentException>("accepted item id <$blank>") {
                ToolCall(id = "call-1", name = "lookup", argsJson = "{}", itemId = blank)
            }
        }
    }
}
