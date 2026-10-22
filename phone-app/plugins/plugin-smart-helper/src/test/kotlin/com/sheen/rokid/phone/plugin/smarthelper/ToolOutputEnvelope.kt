package com.sheen.rokid.phone.plugin.smarthelper

import com.sheen.rokid.agent.ToolResultEnvelope
import com.sheen.rokid.plugin.ToolExecution
import com.sheen.rokid.plugin.ToolOutcome

/**
 * The exact model-facing envelope an **unmigrated** tool result carries (0.3 Phase 2.2, Task 2).
 *
 * Consumer tests used to name the tool's raw detail (`notified:1`); the detail is now the envelope's `data` member, so
 * an assertion compares the whole envelope and keeps pinning the shape the plugin persists and replays instead of
 * decoding it away. `ToolResultEnvelopeTest`/`ToolRegistryTest` pin the wire spelling itself.
 *
 * This helper models a tool that states only the outcome, so the registry projects `accepted` and the tri-state facts
 * as unknown. A migrated pure plugin tool (`notify_user`/`load_skill`, Task 7) states the facts it knows, so its tests
 * use [pureToolOutput].
 */
internal fun unmigratedToolOutput(data: String): String =
    ToolResultEnvelope.encode(ToolExecution(outcome = ToolOutcome.Done), observation = null, data = data)

/**
 * The envelope a migrated **pure plugin tool** returns on success (0.3 Phase 2.2, Task 7).
 *
 * `notify_user` and `load_skill` never look at the screen, so the observation stays `null`; the call was accepted and
 * completed, so `accepted = true`. `applied`/`verified`/`changed` stay unknown (`null`) - neither tool has independent
 * evidence about delivery or device state, and inventing it would be a fabricated fact.
 */
internal fun pureToolOutput(data: String): String =
    ToolResultEnvelope.encode(
        ToolExecution(outcome = ToolOutcome.Done, accepted = true),
        observation = null,
        data = data,
    )
