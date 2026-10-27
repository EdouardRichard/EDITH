package edith.phone.a11yprobe

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Task 13a's **probe-owned test screen**: one real `EditText` in an ordinary, opaque activity, owned by the probe and
 * reachable only by an explicit action plus `confirm=true`.
 *
 * The text arm's self-test proves the platform actions inside the runner's own window. This fixture proves the other
 * half of the production shape on the K40: a **different activity** is the foreground app when the runner's task moves
 * to the back, its tree is read across the window boundary, and the text it holds is read back after the runner leaves.
 * That is the same path Task 13 uses against the WAKE input row; using a probe-owned screen keeps the measurement off
 * a production field until the acceptance itself is run.
 *
 * Privacy: the field's hint is a constant authored here, and nothing this activity displays is logged. The runner logs
 * only the field's text fingerprint (length/chars/SHA-256), never its value.
 */
class A11yTextFixtureActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var field: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (intent?.getBooleanExtra(A11yTextActivity.EXTRA_CONFIRM, false) != true) {
            status = TextView(this).apply {
                textSize = 16f
                setPadding(40, 120, 40, 40)
                text = "EDITH P3 TEXT FIXTURE\n\n未确认，不显示测试输入框。\n\n" +
                    "用 adb 显式传 --ez ${A11yTextActivity.EXTRA_CONFIRM} true 触发。"
            }
            setContentView(status)
            Log.w(A11yProbeService.TAG, "A1TEXT_FIXTURE_NOT_CONFIRMED")
            return
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 120, 40, 40)
            isFocusableInTouchMode = true
        }
        status = TextView(this).apply {
            textSize = 16f
            text = "EDITH P3 TEXT FIXTURE\n\n探针自有输入框，用于 SET_TEXT / READ_TEXT 跨窗口验证。"
        }
        field = EditText(this).apply {
            id = R.id.probe_text_fixture
            hint = A11yTextActivity.FIXTURE_HINT
            minLines = 3
            isSingleLine = false
        }
        root.addView(status)
        root.addView(
            field,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
        )
        setContentView(root)
        root.requestFocus()
        field.clearFocus()

        Log.i(
            A11yProbeService.TAG,
            "A1TEXT_FIXTURE shown id=$packageName:id/probe_text_fixture pkg=$packageName",
        )
    }

    /** Kept for the manifest's explicit action; the activity displays the field and has no other run mode. */
    companion object {
        const val ACTION = "edith.phone.a11yprobe.intent.TEXT_FIXTURE"
    }
}
