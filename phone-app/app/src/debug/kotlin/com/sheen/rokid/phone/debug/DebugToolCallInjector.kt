package com.sheen.rokid.phone.debug
import com.sheen.rokid.phone.model.ScriptedModelWire
import com.sheen.rokid.phone.platform.P3Log
import com.sheen.rokid.phone.model.scriptedToolCallArgs
import com.sheen.rokid.phone.app.EdithApplication

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Debug-only instrument that arms [ScriptedModelWire] from a host shell (P3.3 Stage C acceptance).
 *
 * The configured model would not emit the L3/L4 calls the acceptance needs, so the *transport* is scripted while
 * everything above it - consent, the capability gate, the budget, the policy, the confirmation gate with its phone
 * card, the privileged executor with its state re-check, and the audit - stays exactly as it ships. See
 * [ScriptedModelWire] for why that is the honest way to finish Stage C rather than declaring it unmeasurable.
 *
 * Driven from a host shell. The arguments travel as **separate** extras, never as one JSON literal: the device shell
 * strips the quotes out of `--es args '{"package":"…"}'`, which reaches the executor as `ARGUMENTS_INVALID` and makes a
 * working instrument look broken.
 *
 * ```
 * adb shell am broadcast -a edith.DEBUG_TOOLCALL -n com.sheen.rokid.phone/.DebugToolCallInjector \
 *     --es action uninstall --es package edith.phone.a11yprobe
 * adb shell am broadcast -a edith.DEBUG_TOOLCALL -n com.sheen.rokid.phone/.DebugToolCallInjector \
 *     --es action set_permission --es package edith.phone.a11yprobe \
 *     --es permission android.permission.POST_NOTIFICATIONS --ez granted false
 * adb shell am broadcast -a edith.DEBUG_TOOLCALL -n com.sheen.rokid.phone/.DebugToolCallInjector --ez off true
 * ```
 *
 * It lives in the **debug source set only** (see `src/debug/AndroidManifest.xml`) and is exported because the only
 * caller is `am broadcast` as the shell UID; a release APK contains no receiver. The log line carries the action name
 * and never the members - a package, a permission or a number are exactly what must not be logged.
 *
 * **B8 (§6.2/§6.3) reuses it.** The configured model never calls `notify_user`, so the fallback and the mid-step
 * downgrade cannot be driven by asking it nicely. `say` + `channel` script one notification; `times` scripts a turn
 * that notifies twice; `times 0` scripts a model that only answers (the fallback's own case) and `fail true` scripts a
 * provider failure (the "even a failed turn says something" invariant). What stays real is everything the scenarios are
 * about: `notify_user` itself, the per-turn ledger, the downgrade, the fallback and the output path.
 *
 * ```
 * adb shell am broadcast -a edith.DEBUG_TOOLCALL -n com.sheen.rokid.phone/.DebugToolCallInjector \
 *     --es action notify_user --es say 进度 --es channel both --ei times 2 --es text 好了。
 * adb shell am broadcast -a edith.DEBUG_TOOLCALL -n com.sheen.rokid.phone/.DebugToolCallInjector --ez fail true --ei times 0
 * ```
 */
class DebugToolCallInjector : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_SCRIPT) return
        val root = (context.applicationContext as? EdithApplication)?.composition
        if (root == null) return report("refused reason=NO_COMPOSITION")

        if (intent.getBooleanExtra(EXTRA_OFF, false)) {
            root.disarmScriptedModelAnswer()
            return
        }
        val action = intent.getStringExtra(EXTRA_ACTION) ?: ""
        val times = if (intent.hasExtra(EXTRA_TIMES)) intent.getIntExtra(EXTRA_TIMES, 1) else 1
        val fail = intent.getBooleanExtra(EXTRA_FAIL, false)
        val args = scriptedToolCallArgs(
            packageName = intent.getStringExtra(EXTRA_PACKAGE),
            permission = intent.getStringExtra(EXTRA_PERMISSION),
            granted = if (intent.hasExtra(EXTRA_GRANTED)) intent.getBooleanExtra(EXTRA_GRANTED, false) else null,
            value = if (intent.hasExtra(EXTRA_VALUE)) intent.getIntExtra(EXTRA_VALUE, 0) else null,
            notifyText = intent.getStringExtra(EXTRA_SAY),
            channel = intent.getStringExtra(EXTRA_CHANNEL),
            appName = intent.getStringExtra(EXTRA_NAME),
            command = intent.getStringExtra(EXTRA_COMMAND),
        )
        if (!root.armScriptedModelAnswer(action, args, intent.getStringExtra(EXTRA_TEXT) ?: "", times, fail)) {
            return report("refused reason=NOT_DEBUG")
        }
        // How many members the instrument actually received, never which ones: "members=0" is the difference between
        // "the action takes no arguments" and "the extras never arrived", and that difference cost a device round trip
        // to discover otherwise.
        val members = listOfNotNull(
            intent.getStringExtra(EXTRA_PACKAGE),
            intent.getStringExtra(EXTRA_PERMISSION),
            if (intent.hasExtra(EXTRA_GRANTED)) "granted" else null,
            if (intent.hasExtra(EXTRA_VALUE)) "value" else null,
            intent.getStringExtra(EXTRA_SAY),
            intent.getStringExtra(EXTRA_CHANNEL),
            intent.getStringExtra(EXTRA_COMMAND),
        ).size
        report("armed action=${action.ifBlank { "none" }} members=$members times=$times fail=$fail")
    }

    private fun report(state: String) = P3Log.line("P3_SCRIPT state=$state")

    companion object {
        const val ACTION_SCRIPT = "edith.DEBUG_TOOLCALL"
        const val EXTRA_ACTION = "action"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_PERMISSION = "permission"
        const val EXTRA_GRANTED = "granted"
        const val EXTRA_VALUE = "value"
        const val EXTRA_TEXT = "text"
        const val EXTRA_OFF = "off"
        /** The notification body `notify_user` carries (`--es say`), never the closing answer text. */
        const val EXTRA_SAY = "say"
        /** The `notify_user` channel (`--es channel screen|voice|both|silent`). */
        const val EXTRA_CHANNEL = "channel"
        /** How many tool calls the script serves before its closing answer (`--ei times`, default 1). */
        const val EXTRA_TIMES = "times"
        /** Serve a provider failure instead of the closing answer (`--ez fail true`). */
        const val EXTRA_FAIL = "fail"
        /** The app name for `open_app` (`--es name 音乐`). */
        const val EXTRA_NAME = "name"
        /** The command for `shell` (`--es command 'cmd package list packages'`); B8 classifier matrix. */
        const val EXTRA_COMMAND = "command"
    }
}
