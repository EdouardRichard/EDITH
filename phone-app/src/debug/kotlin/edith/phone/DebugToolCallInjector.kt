package edith.phone

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
 * adb shell am broadcast -a edith.DEBUG_TOOLCALL -n edith.phone/.DebugToolCallInjector \
 *     --es action uninstall --es package edith.phone.a11yprobe
 * adb shell am broadcast -a edith.DEBUG_TOOLCALL -n edith.phone/.DebugToolCallInjector \
 *     --es action set_permission --es package edith.phone.a11yprobe \
 *     --es permission android.permission.POST_NOTIFICATIONS --ez granted false
 * adb shell am broadcast -a edith.DEBUG_TOOLCALL -n edith.phone/.DebugToolCallInjector --ez off true
 * ```
 *
 * It lives in the **debug source set only** (see `src/debug/AndroidManifest.xml`) and is exported because the only
 * caller is `am broadcast` as the shell UID; a release APK contains no receiver. The log line carries the action name
 * and never the members - a package, a permission or a number are exactly what must not be logged.
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
        val action = intent.getStringExtra(EXTRA_ACTION)
        if (action.isNullOrBlank()) return report("refused reason=MISSING_ACTION")
        val args = scriptedToolCallArgs(
            packageName = intent.getStringExtra(EXTRA_PACKAGE),
            permission = intent.getStringExtra(EXTRA_PERMISSION),
            granted = if (intent.hasExtra(EXTRA_GRANTED)) intent.getBooleanExtra(EXTRA_GRANTED, false) else null,
            value = if (intent.hasExtra(EXTRA_VALUE)) intent.getIntExtra(EXTRA_VALUE, 0) else null,
        )
        if (!root.armScriptedModelAnswer(action, args, intent.getStringExtra(EXTRA_TEXT) ?: "")) {
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
        ).size
        report("armed action=$action members=$members")
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
    }
}
