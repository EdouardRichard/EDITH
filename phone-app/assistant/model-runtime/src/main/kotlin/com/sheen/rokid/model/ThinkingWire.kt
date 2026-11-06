package com.sheen.rokid.model

import com.sheen.rokid.plugin.ModelProfile

/**
 * 0.5.4: whether an "off" switch must be put on the wire explicitly.
 *
 * 0.5.3 encoded `thinkingEnabled=false` as "omit the field". That is only a true off for providers whose default is
 * thinking off. DeepSeek V4.1 Flash defaults thinking on, so the omitted field left the model reasoning and the switch
 * was a no-op on device (`P3_THINKING active=false` with `reasoningChars > 0`).
 *
 * This predicate is about **wire encoding**, not the behaviour switch: [ModelProfile.thinkingEnabled] still decides
 * whether thinking is requested. It selects the providers that need `disabled`/`none` to honour that switch. The
 * capability bit is checked first because it is the catalog's explicit declaration; the name/base URL fallback covers
 * hand-created profiles whose 0.52-era record never set the bit.
 */
internal fun ModelProfile.needsExplicitThinkingDisable(): Boolean =
    thinkingSupported ||
        model.contains(DEEPSEEK, ignoreCase = true) ||
        baseUrl.contains(DEEPSEEK, ignoreCase = true)

private const val DEEPSEEK = "deepseek"
