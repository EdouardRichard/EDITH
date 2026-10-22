package com.sheen.rokid.phone.plugin.smarthelper.context

/**
 * Which of the two conversation routes a context belongs to (spec §4.2).
 *
 * The two routes are the two ways the assistant is entered — a wake word (`WAKE`) and the host's proactive
 * perception (`PROACTIVE`) — and spec §1.3 C3 makes their LLM contexts independent: separate histories, separate
 * token accounting, separate compression state. They share the SKILL library, the memory store and the local model
 * services, and nothing else.
 */
enum class ContextRoute { WAKE, PROACTIVE }
