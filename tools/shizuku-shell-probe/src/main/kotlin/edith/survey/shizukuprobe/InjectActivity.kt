package edith.survey.shizukuprobe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.TextView

/**
 * TEMPORARY survey probe (2026-10-16). Sends the "commit this Unicode string" broadcast to [ProbeImeService].
 *
 * The text arrives as **code points**, never as literal CJK in this file: this host has already corrupted a CJK source
 * file once (Windows/ANSI round trip, recorded in the project's own evidence), so the request format is ASCII-only by
 * construction and the CJK is built by `StringBuilder.appendCodePoint` inside the app.
 */
class InjectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = TextView(this).apply { textSize = 14f; setPadding(48, 96, 48, 48) }
        setContentView(view)

        val codePoints = intent.getIntArrayExtra("codepoints") ?: IntArray(0)
        if (codePoints.isEmpty()) {
            view.text = "no codepoints extra"
            Probe.log("INJECT_NO_CODEPOINTS")
            finish()
            return
        }
        val text = StringBuilder().apply { codePoints.forEach { appendCodePoint(it) } }.toString()
        val reply = StringBuilder("injecting ${codePoints.size} code points, length=${text.length}\n")
        codePoints.forEach { reply.append("U+%04X ".format(it)) }
        view.text = reply.toString()

        // Log the *shape* of the string (lengths and code points), not the characters: this probe has no business
        // writing text content into a log that the survey then quotes.
        Probe.log("INJECT_SEND codePoints=${codePoints.size} length=${text.length} first=U+%04X".format(codePoints[0]))

        sendBroadcast(
            Intent(ProbeImeService.ACTION_INJECT)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .setPackage(packageName)
                .putExtra(ProbeImeService.EXTRA_TEXT, text)
                .putExtra(ProbeImeService.EXTRA_ENTER, intent.getBooleanExtra("enter", false)),
        )
        finish()
    }
}
