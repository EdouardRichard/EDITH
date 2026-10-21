package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.ToolResultEnvelope
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome

/**
 * The exact model-facing envelope a successful, observation-less tool result carries (0.3 Phase 2.2, Task 2).
 *
 * Consumer tests used to name the tool's raw detail (`notified:1`); the detail is now the envelope's `data` member, so
 * an assertion compares the whole envelope and keeps pinning the shape the plugin persists and replays instead of
 * decoding it away. `ToolResultEnvelopeTest`/`ToolRegistryTest` pin the wire spelling itself.
 */
internal fun doneToolOutput(data: String): String =
    ToolResultEnvelope.encode(ToolExecution(outcome = ToolOutcome.Done), observation = null, data = data)
