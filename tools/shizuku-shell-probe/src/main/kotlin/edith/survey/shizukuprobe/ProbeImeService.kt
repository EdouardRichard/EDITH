package edith.survey.shizukuprobe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.inputmethodservice.InputMethodService
import android.os.Build
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.LinearLayout
import android.widget.TextView

/**
 * TEMPORARY survey probe (2026-10-16): the smallest possible "commit arbitrary Unicode into the focused field" IME.
 *
 * It exists to answer the one question `input text` cannot: Unicode is impossible through `input text` on this device
 * (the platform's virtual key character map is ASCII-only — measured), so the only way to type Chinese is for a
 * component with an active `InputConnection` to commit the string itself. This service is that component, and the
 * broadcast is the channel an agent would use to drive it.
 *
 * Reachable only by an explicit broadcast carrying the text, and only while this IME is the active one.
 */
class ProbeImeService : InputMethodService() {

    private var connection: InputConnection? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val text = intent?.getStringExtra(EXTRA_TEXT)
            if (text.isNullOrEmpty()) {
                Probe.log("IME_BROADCAST_EMPTY")
                return
            }
            val ic = connection
            if (ic == null) {
                Probe.log("IME_NO_INPUT_CONNECTION textLen=${text.length}")
                return
            }
            // The commit is what actually delivers the characters; the return value is the platform's own answer.
            val committed = runCatching { ic.commitText(text, 1) }.getOrDefault(false)
            Probe.log("IME_COMMIT textLen=${text.length} codePoints=${text.codePoints().count()} ok=$committed")
            if (intent.getBooleanExtra(EXTRA_ENTER, false)) editorAction()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(ACTION_INJECT).apply { addCategory(Intent.CATEGORY_DEFAULT) }
        // API 33 requires the flag spelling; the broadcast comes from adb shell, i.e. from outside this app.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
        Probe.log("IME_CREATED")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        super.onDestroy()
    }

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        connection = currentInputConnection
        Probe.log("IME_START_INPUT package=${attribute?.packageName} restarting=$restarting conn=${connection != null}")
    }

    override fun onFinishInput() {
        connection = null
        super.onFinishInput()
    }

    override fun onCreateInputView(): View =
        LinearLayout(this).apply {
            setPadding(24, 24, 24, 24)
            addView(TextView(this@ProbeImeService).apply {
                text = "EDITH survey IME — controlled by broadcast only"
                textSize = 14f
            })
        }

    private fun editorAction() {
        val ic = connection ?: return
        runCatching {
            ic.performEditorAction(EditorInfo.IME_ACTION_DONE)
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER))
            ic.sendKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER))
        }
    }

    companion object {
        const val ACTION_INJECT = "edith.survey.shizukuprobe.INJECT_TEXT"
        const val EXTRA_TEXT = "text"
        const val EXTRA_ENTER = "enter"
    }
}
