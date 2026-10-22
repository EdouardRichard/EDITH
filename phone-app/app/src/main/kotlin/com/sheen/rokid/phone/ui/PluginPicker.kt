package com.sheen.rokid.phone.ui
import com.sheen.rokid.phone.ui.PhoneScreen
import com.sheen.rokid.phone.app.HostCompositionRoot

/**
 * The plugins the session page offers, in order.
 *
 * Extracted from `PhoneScreen`'s inline `buildList` so that **what is offered** can be pinned by a test: `D57` shelved
 * the interview assistant for this version, and "a plugin that cannot activate must not be offered" is a property of
 * this list, not of the composable that renders it.
 *
 * The interview assistant stays **registered** (`HostCompositionRoot.registerPlugin`) because its module is kept so P4
 * can resume without re-creating anything — but it is **not offered**: it needs `Capability.Speaker`, which the
 * environment deliberately does not advertise, so picking it could only ever end in a capability refusal.
 */
internal fun pluginPickerOptions(debug: Boolean, diagnosticHintSenderPresent: Boolean): List<Pair<String, String>> =
    buildList {
        add("smart-assistant" to "智能助手")
        // `D57`: `interview-assistant`（面试助手 / P4）**不在此列出** —— 模块与注册都保留，但"能选却永远激活不了"
        // 的入口不留。恢复 P4 时：把这一行加回，并同时接上 `Capability.Speaker`（注册、阈值与能力宣告是它自己的任务）。
        if (debug) add("p2-diagnostic" to "诊断")
        if (debug && diagnosticHintSenderPresent) add("p2-hint" to "提示探针（调试）")
    }
