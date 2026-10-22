package com.sheen.rokid.contracts

/**
 * 0.24 spec §5.4/§5.5: which half of a tool execution a tool row reports.
 *
 * The wire spells the phase lower-case (`start`/`end`); the plugin publishes the same value to the display. The type
 * lives in `core-contracts` so `plugin-api` (the agent callback) and `output-runtime` (the wire codec) cannot drift
 * into two enum copies that encode different names for the same phase.
 */
enum class ToolPhase { START, END }

/**
 * 0.24 spec §5.5: how a speech request finished.
 *
 * The output arbiter reports this to the plugin, which uses it to decide whether the next listening turn may start.
 * `core-contracts` owns it for the same reason as [ToolPhase]: one type across the plugin boundary and the runtime.
 */
enum class SpeechResult { FINISHED, FAILED }
