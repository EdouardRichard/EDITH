package com.sheen.rokid.phone.plugin.smarthelper.memory

/**
 * Phase 7 F4 (§3.1/§3.2): the memory text form the plugin renders, **measurement only**.
 *
 * The default is [PRODUCTION], which renders the Phase 4 `<reference_context>` block byte-for-byte. [LEGACY_RAW]
 * replays each memory's original utterance with no wrapper and no normalization, and is selected at runtime by the
 * debug-only `edith.DEBUG_MEMORY_FORM` receiver **before** a session activates. No production caller sets this, and a
 * release build contains no receiver that could.
 */
enum class MemoryMeasurementForm { PRODUCTION, LEGACY_RAW }
