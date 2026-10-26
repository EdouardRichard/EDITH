package edith.phone.a11yprobe

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Task 13a: the **text arm** of the probe — `ACTION_SET_TEXT` with Unicode, and a read-back that never logs content.
 *
 * Why this activity exists: `adb shell input text` cannot type a non-ASCII character (`Virtual.kcm` has no CJK
 * mappings), and the project forbids using it as a substitute for the production accessibility path anyway. The
 * acceptance for 0.4's input row needs to put **Chinese** text into a real app field and then prove the field holds it.
 * That is exactly two platform capabilities — `ACTION_SET_TEXT` (S1 A05, works without focus) and reading `.text` back
 * — and this activity is their unattended runner.
 *
 * Two actions, one confirmation gate:
 *  - [ACTION_SET_TEXT] waits for an eligible node in the active window, focuses it, performs
 *    `ACTION_SET_TEXT` with `ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE`, and reads the field back.
 *  - [ACTION_READ_TEXT] waits for an eligible node, reads its text, and compares it with an optional `expected`
 *    value exactly.
 *
 * **Privacy is structural.** The only text value that leaves this process is [TextFingerprint] — UTF-16 length,
 * code-point count and SHA-256 over UTF-8. No log line this class writes contains the text, a prefix/suffix of it, or
 * an exception message that could embed it. The test fixture's own hint is a constant authored here.
 *
 * **Reaching the target window.** Starting any activity brings this probe's window to the front, and the target app's
 * field would no longer be the active window. The runner therefore moves its own task to the back after the confirmation
 * gate and then polls: whatever app was in front before `am start` is in front again, and its tree is what the
 * accessibility service reads. The self-test mode (`--ez selftest true`) skips that move and exercises this activity's
 * own probe-owned `EditText`, so the instrument can be verified without touching a production screen.
 *
 * Like A1 and click-now, this is **not** a launcher and does not auto-run: a stray intent cannot type into whatever
 * field happens to be focused. It is exported only so `am start` can reach it, and every run needs `--ez confirm true`.
 */
class A11yTextActivity : Activity() {

    private val service: A11yProbeService? get() = A11yProbeService.instance
    private val handler = Handler(Looper.getMainLooper())
    private val runSequence = AtomicInteger(0)

    /**
     * One action at a time. Two `am start`s can arrive before the first run's verdict (the operator should wait, but an
     * unattended script may not); letting them overlap would put two pollers against one window and could focus/set a
     * field the second command never named. The second run is refused as `busy` instead.
     */
    private val running = AtomicBoolean(false)
    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private var fixture: EditText? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureUi(selftest = intent?.getBooleanExtra(EXTRA_SELFTEST, false) == true)
        // A configuration change (or a process restart with state) recreates this activity with the **last** intent.
        // Re-running that intent would replay a write the operator never asked for again; the command is single-shot, so
        // a recreated instance waits for a fresh `am start` instead.
        if (savedInstanceState != null) {
            status.text = "EDITH P3 TEXT\n\n界面已重建，未重放上一次命令。请重新发起。"
            Log.i(A11yProbeService.TAG, "A1TEXT_REPLAY_SKIPPED")
            return
        }
        handleIntent(intent)
    }

    /**
     * A second `am start` reaches the existing instance (the activity is `singleTop`) rather than a fresh one. That is
     * what lets the read arm re-read the **same** fixture after the set arm ran: a new instance would build an empty
     * field and every read would be a false `match=false`.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        ensureUi(selftest = intent.getBooleanExtra(EXTRA_SELFTEST, false) == true)
        handleIntent(intent)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // ==========================================================================================
    // UI
    // ==========================================================================================

    /**
     * The status line, plus the probe-owned fixture when the run is a self-test. The fixture has a **resource id**, not
     * `generateViewId()`: `findAccessibilityNodeInfosByViewId` only sees `package:id/name`, so a generated id would make
     * the by-id selector untestable on the device.
     */
    private fun ensureUi(selftest: Boolean) {
        if (!::root.isInitialized) {
            root = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(40, 120, 40, 40)
                isFocusableInTouchMode = true
            }
            status = TextView(this).apply {
                textSize = 16f
                text = "EDITH P3 TEXT\n\n等待运行…"
            }
            root.addView(status)
            setContentView(root)
            // Keep the fixture from taking focus on launch: the arm's first action is ACTION_FOCUS, and a field that
            // was already focused would hide whether that action was the thing that made the difference.
            root.requestFocus()
        }
        if (selftest && fixture == null) {
            fixture = EditText(this).apply {
                id = R.id.probe_text_fixture
                hint = FIXTURE_HINT
                minLines = 2
                isSingleLine = false
            }
            root.addView(
                fixture,
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
            fixture?.clearFocus()
            root.requestFocus()
        }
    }

    // ==========================================================================================
    // Intent dispatch
    // ==========================================================================================

    private fun handleIntent(intent: Intent?) {
        val op = when (intent?.action) {
            ACTION_SET_TEXT -> OP_SET
            ACTION_READ_TEXT -> OP_READ
            else -> null
        }
        ensureUi(selftest = intent?.getBooleanExtra(EXTRA_SELFTEST, false) == true)

        // Same rule as A1 and click-now: an exported component that writes into a field must not act because a stray
        // intent reached it. Read only the confirmation bit before refusing.
        if (intent?.getBooleanExtra(EXTRA_CONFIRM, false) != true) {
            emit(
                TextVerdict(op = op ?: "none", outcome = "refused", reason = "not_confirmed"),
                hint = "未确认，不执行。用 adb 显式传 --ez $EXTRA_CONFIRM true 触发。",
            )
            Log.w(A11yProbeService.TAG, "A1TEXT_NOT_CONFIRMED")
            return
        }

        if (op == null) {
            emit(
                TextVerdict(op = "none", outcome = "refused", reason = "unknown_action"),
                hint = "未知 action，不执行。",
            )
            return
        }

        if (service == null) {
            emit(
                TextVerdict(op = op, outcome = "refused", reason = "service_not_enabled"),
                hint = "无障碍服务未启用。请在 设置 → 无障碍 中启用 \"EDITH P3 T3b probe\" 后重试。",
            )
            Log.w(A11yProbeService.TAG, "A1TEXT_SERVICE_NOT_ENABLED")
            return
        }

        status.text = "EDITH P3 TEXT\n\n${op} 进行中…"
        if (!running.compareAndSet(false, true)) {
            emit(
                TextVerdict(op = op, outcome = "refused", reason = "busy"),
                hint = "上一次运行尚未结束，本次未执行。",
            )
            return
        }
        val run = runSequence.incrementAndGet()
        val selftest = intent.getBooleanExtra(EXTRA_SELFTEST, false)
        Thread {
            // The run id is only a diagnostic marker: a late result from an earlier run must not be mistaken for the
            // current one in the start line. Both verdicts are still emitted honestly.
            Log.i(A11yProbeService.TAG, "A1TEXT_RUN op=$op run=$run")
            try {
                when (op) {
                    OP_SET -> runSet(intent, selftest, run)
                    OP_READ -> runRead(intent, selftest, run)
                }
            } finally {
                running.set(false)
            }
        }.start()
    }

    // ==========================================================================================
    // SET_TEXT
    // ==========================================================================================

    /**
     * Wait for an eligible node, focus it, write the text, read it back, and emit one verdict.
     *
     * `outcome=set` is the platform **accepting** the action. The acceptance's actual question is "the field holds the
     * text", which is answered by the read-back (`readLen/readSha/match`) and, independently, by a subsequent
     * [ACTION_READ_TEXT] run. S1 A09 measured a field that returns true and keeps its own text; calling that a set
     * without the read-back is what this arm refuses to do.
     */
    private fun runSet(intent: Intent, selftest: Boolean, run: Int) {
        val activeService = service ?: return
        val text = decodeText(intent.rawExtra(EXTRA_TEXT), intent.rawExtra(EXTRA_TEXT_B64))
        val waitMillis = intent.longExtraOr(EXTRA_WAIT_MILLIS, DEFAULT_WAIT_MILLIS)
            .coerceIn(MIN_WAIT_MILLIS, MAX_WAIT_MILLIS)
        val request = requestFrom(intent)
        // Self-test must never act on the app behind the runner: until this activity's own window is active, the first
        // visible editable node belongs to whatever was in front. Pinning the package makes the poll wait for our
        // fixture instead (the runner's window is the probe package, and the fixture is the only editable node in it).
        val targetPackage = if (selftest) packageName else targetPackageFrom(intent)
        val fingerprint = text?.let { TextFingerprint.of(it) }

        Log.i(
            A11yProbeService.TAG,
            "A1TEXT_START op=set run=$run selector=${selectorToken(request)} targetPackage=${targetPackage ?: "any"} " +
                "waitMillis=$waitMillis textProvided=${text != null} selftest=$selftest",
        )

        if (text == null) {
            emit(
                TextVerdict(
                    op = OP_SET, outcome = "refused", reason = "missing_text",
                    target = selectorToken(request), polls = 0, waitMillis = waitMillis,
                ),
                hint = "缺少 text（--es text 或 --es textB64），未执行。",
            )
            return
        }

        if (!selftest) leaveForeground()

        val poll = waitForTarget(activeService, request, TextMode.SET, waitMillis, targetPackage)
        val found = poll.target
        if (found == null) {
            logTreeShape(activeService, OP_SET, poll.pkg)
            emit(
                TextVerdict(
                    op = OP_SET, outcome = "no_field", reason = "wait_timeout",
                    pkg = poll.pkg, target = selectorToken(request), polls = poll.polls, waitMillis = waitMillis,
                    text = fingerprint,
                ),
                hint = "等待输入框超时。",
            )
            return
        }

        val facts = found.candidate.facts
        val targetClass = simpleClass(facts.className)
        if (!facts.editable) {
            // Explicitly selected a non-editable node: refuse rather than redirect the write to some other field.
            emit(
                TextVerdict(
                    op = OP_SET, outcome = "refused", reason = "target_not_editable",
                    pkg = poll.pkg, target = found.selection.kind.token(), targetClass = targetClass,
                    polls = poll.polls, waitMillis = waitMillis, text = fingerprint,
                ),
                hint = "目标节点不可编辑，未写入。",
            )
            return
        }

        val focusRet = activeService.focusNode(found.candidate.node)
        sleep(FOCUS_SETTLE_MILLIS)
        val setRet = activeService.setText(found.candidate.node, text)
        val readBackRequest = pinnedRequest(request, facts)
        val readBack = awaitReadBack(
            service = activeService,
            request = readBackRequest,
            expected = text,
            targetPackage = poll.pkg,
            timeoutMillis = READ_BACK_TIMEOUT_MILLIS,
        )
        val decision = setTextDecision(
            serviceEnabled = true,
            textProvided = true,
            targetFound = true,
            targetEditable = true,
            setRet = setRet,
        )
        val readText = readBack?.facts?.text
        emit(
            TextVerdict(
                op = OP_SET,
                outcome = decision.outcome,
                reason = decision.reason,
                pkg = poll.pkg,
                target = found.selection.kind.token(),
                targetClass = targetClass,
                focusRet = focusRet,
                setRet = setRet,
                polls = poll.polls,
                waitMillis = waitMillis,
                text = fingerprint,
                read = readText?.let { TextFingerprint.of(it) },
                // `none` when the field never became readable again: that is a missing measurement, not a mismatch.
                match = readBack?.let { exactMatch(readText, text) },
                isHint = isHintEcho(readText, readBack?.facts?.hint),
            ),
        )
    }

    // ==========================================================================================
    // READ_TEXT
    // ==========================================================================================

    /**
     * Wait for an eligible node (editable first, a visible `TextView` as the read arm's fallback), read its text, and
     * emit length/chars/hash plus an exact-match verdict against the optional `expected` value.
     */
    private fun runRead(intent: Intent, selftest: Boolean, run: Int) {
        val activeService = service ?: return
        val expected = decodeText(intent.rawExtra(EXTRA_EXPECTED), intent.rawExtra(EXTRA_EXPECTED_B64))
        val waitMillis = intent.longExtraOr(EXTRA_WAIT_MILLIS, DEFAULT_WAIT_MILLIS)
            .coerceIn(MIN_WAIT_MILLIS, MAX_WAIT_MILLIS)
        val request = requestFrom(intent)
        val targetPackage = if (selftest) packageName else targetPackageFrom(intent)

        Log.i(
            A11yProbeService.TAG,
            "A1TEXT_START op=read run=$run selector=${selectorToken(request)} targetPackage=${targetPackage ?: "any"} " +
                "waitMillis=$waitMillis expectedProvided=${expected != null} selftest=$selftest",
        )

        if (!selftest) leaveForeground()

        val poll = waitForTarget(activeService, request, TextMode.READ, waitMillis, targetPackage)
        val found = poll.target
        if (found == null) {
            logTreeShape(activeService, OP_READ, poll.pkg)
            emit(
                TextVerdict(
                    op = OP_READ, outcome = "no_field", reason = "wait_timeout",
                    pkg = poll.pkg, target = selectorToken(request), polls = poll.polls, waitMillis = waitMillis,
                    expected = expected?.let { TextFingerprint.of(it) },
                ),
                hint = "等待可读节点超时。",
            )
            return
        }

        val facts = found.candidate.facts
        val actual = facts.text
        emit(
            TextVerdict(
                op = OP_READ,
                outcome = "read",
                reason = "none",
                pkg = poll.pkg,
                target = found.selection.kind.token(),
                targetClass = simpleClass(facts.className),
                polls = poll.polls,
                waitMillis = waitMillis,
                text = actual?.let { TextFingerprint.of(it) },
                expected = expected?.let { TextFingerprint.of(it) },
                match = exactMatch(actual, expected),
                isHint = isHintEcho(actual, facts.hint),
            ),
        )
    }

    // ==========================================================================================
    // Target discovery and actions
    // ==========================================================================================

    private data class FoundTarget(val selection: TextTargetSelection, val candidate: TextNodeCandidate)

    /** The result of waiting for a target: the target if one appeared, otherwise how long the probe tried and where. */
    private data class TargetPoll(val target: FoundTarget?, val polls: Int, val pkg: String)

    /**
     * Poll the **active** window until the selector matches an eligible node or the wait expires.
     *
     * The active root is read once per tick and supplies both the package and the candidates. When the caller names the
     * production package, a probe whose own task is still moving to the back must not read its own window and report a
     * field it is about to hide. `pkg` in the returned value is the package of that same root, so a timeout says *where*
     * it was looking.
     */
    private fun waitForTarget(
        service: A11yProbeService,
        request: TextTargetRequest,
        mode: TextMode,
        waitMillis: Long,
        targetPackage: String?,
    ): TargetPoll {
        var polls = 0
        var pkg = "unknown"
        val deadline = System.currentTimeMillis() + waitMillis
        while (true) {
            // One root read supplies both the package and the nodes: two reads race a window transition and can pair a
            // target from one window with the package name of another (see ActiveTextTree).
            val tree = service.activeTextTree()
            pkg = tree.pkg
            if (targetPackage == null || pkg == targetPackage) {
                polls += 1
                val selection = chooseTextTarget(tree.candidates.map { it.facts }, request, mode)
                val candidate = selection?.let { tree.candidates.getOrNull(it.key) }
                if (selection != null && candidate != null) {
                    return TargetPoll(FoundTarget(selection, candidate), polls, pkg)
                }
            }
            if (System.currentTimeMillis() >= deadline) return TargetPoll(null, polls, pkg)
            sleep(POLL_MILLIS)
        }
    }

    /**
     * Read the field back after a write, polling because the accessibility tree can lag the text change by a frame.
     * Pins the target by its view id when the device reported one, so a default "first editable" request cannot slide
     * to a different field between the write and the read. A read from a different package is skipped: the readback is
     * evidence about the field that was just written, not about whatever came to the front next.
     */
    private fun awaitReadBack(
        service: A11yProbeService,
        request: TextTargetRequest,
        expected: String,
        targetPackage: String?,
        timeoutMillis: Long,
    ): TextNodeCandidate? {
        val deadline = System.currentTimeMillis() + timeoutMillis
        var last: TextNodeCandidate? = null
        while (true) {
            val tree = service.activeTextTree()
            if (targetPackage == null || tree.pkg == targetPackage) {
                val selection = chooseTextTarget(tree.candidates.map { it.facts }, request, TextMode.SET)
                selection?.let { tree.candidates.getOrNull(it.key) }?.let { last = it }
                if (last?.facts?.text == expected) return last
            }
            if (System.currentTimeMillis() >= deadline) return last
            sleep(POLL_MILLIS)
        }
    }

    private fun pinnedRequest(request: TextTargetRequest, facts: TextCandidateFacts): TextTargetRequest =
        if (facts.viewId != null) TextTargetRequest(id = facts.viewId) else request

    /**
     * Move this activity's task behind the caller's app, then give the window manager a moment to make that app the
     * active window before the first read.
     *
     * Without this the probe would celebrate finding **its own** window: the activity is what `am start` just brought
     * to the front. In self-test mode the target *is* this window, so the move is skipped.
     */
    private fun leaveForeground() {
        handler.post {
            val moved = runCatching { moveTaskToBack(true) }.getOrDefault(false)
            Log.i(A11yProbeService.TAG, "A1TEXT_TASK_TO_BACK moved=$moved")
        }
        sleep(TASK_MOVE_SETTLE_MILLIS)
    }

    // ==========================================================================================
    // Extras and logging
    // ==========================================================================================

    private fun requestFrom(intent: Intent): TextTargetRequest = TextTargetRequest(
        id = decodeText(intent.rawExtra(EXTRA_ID), null)?.trim()?.takeIf { it.isNotEmpty() },
        label = decodeText(intent.rawExtra(EXTRA_LABEL), intent.rawExtra(EXTRA_LABEL_B64))
            ?.takeIf { it.isNotEmpty() },
        hint = decodeText(intent.rawExtra(EXTRA_HINT), intent.rawExtra(EXTRA_HINT_B64))
            ?.takeIf { it.isNotEmpty() },
        focusFirst = intent.getBooleanExtra(EXTRA_FOCUS_FIRST, false),
    )

    private fun targetPackageFrom(intent: Intent): String? =
        decodeText(intent.rawExtra(EXTRA_PACKAGE), null)?.trim()?.takeIf { it.isNotEmpty() }

    /** The selector the caller asked for, for start/no-field lines where no node was chosen. Never a node's text. */
    private fun selectorToken(request: TextTargetRequest): String = when {
        request.id != null -> "id"
        request.label != null -> "label"
        request.hint != null -> "hint"
        request.focusFirst -> "focus"
        else -> "first"
    }

    private fun simpleClass(className: String?): String =
        className?.substringAfterLast('.')?.takeIf { it.isNotEmpty() } ?: "none"

    /**
     * The timeout's structural evidence: counts and class names, never text/hint/content. This is what separates "the
     * window was not readable" (walked/visible near zero) from "the selector matched nothing" (a populated tree).
     */
    private fun logTreeShape(service: A11yProbeService, op: String, pkg: String) {
        val shape = service.textTreeShape()
        Log.i(
            A11yProbeService.TAG,
            "A1TEXT_TREE op=$op pkg=$pkg walked=${shape.walked} visible=${shape.visible} " +
                "editable=${shape.editable} editableClasses=${shape.editableClasses.joinToString("|").ifEmpty { "none" }}",
        )
    }

    /**
     * The one deliverable line, plus a UI echo. The verdict goes to logcat **before** the UI update so an unattended
     * run's evidence does not depend on the main thread having drawn anything.
     */
    private fun emit(verdict: TextVerdict, hint: String? = null) {
        val line = textVerdictLine(verdict)
        Log.i(A11yProbeService.TAG, line)
        handler.post {
            status.text = if (hint != null) {
                "EDITH P3 TEXT\n\n$hint\n\n$line"
            } else {
                "EDITH P3 TEXT\n\n$line\n\n结果已写入 logcat。"
            }
        }
    }

    private fun sleep(millis: Long) = runCatching { Thread.sleep(millis) }

    companion object {
        /** The write arm's action; its own action keeps this mode opt-in and greppable. */
        const val ACTION_SET_TEXT = "edith.phone.a11yprobe.intent.SET_TEXT"

        /** The read-back arm's action. */
        const val ACTION_READ_TEXT = "edith.phone.a11yprobe.intent.READ_TEXT"

        const val EXTRA_CONFIRM = "confirm"

        /** The UTF-8 text to write (`--es text '你好'`). Empty string is a valid clear. */
        const val EXTRA_TEXT = "text"

        /**
         * The same text as strict base64 of its UTF-8 bytes (`--es textB64 5Li...`).
         *
         * Added because the transport is part of the measurement: a Windows console or a shell layer that re-encodes the
         * command line turns Chinese into replacement characters, and the probe would then honestly report a set of a
         * different string. The plain [EXTRA_TEXT] wins when both are present.
         */
        const val EXTRA_TEXT_B64 = "textB64"

        /** The exact value the read arm compares against. Never logged; only its fingerprint and the boolean are. */
        const val EXTRA_EXPECTED = "expected"

        /** Base64 spelling of [EXTRA_EXPECTED], for the same transport reason as [EXTRA_TEXT_B64]. */
        const val EXTRA_EXPECTED_B64 = "expectedB64"

        /** Target selectors: a resource id, an exact label, an exact hint, or the currently focused field. */
        const val EXTRA_ID = "id"
        const val EXTRA_LABEL = "label"
        const val EXTRA_LABEL_B64 = "labelB64"
        const val EXTRA_HINT = "hint"
        const val EXTRA_HINT_B64 = "hintB64"
        const val EXTRA_FOCUS_FIRST = "focusFirst"

        /**
         * Optional package the active window must belong to before the probe acts (`--es package com.sheen.rokid.phone`).
         * Without it a mis-timed run could type into whatever app is in front; with it a timeout names the package it
         * actually read. This is a package name, not user content.
         */
        const val EXTRA_PACKAGE = "package"

        /**
         * Self-test: add a probe-owned `EditText` to this window and act on it instead of moving to the back. This is
         * how the instrument is verified on the K40 without touching a production screen.
         */
        const val EXTRA_SELFTEST = "selftest"

        /** How long to wait for the target node (milliseconds): `--el waitMillis 15000`. */
        const val EXTRA_WAIT_MILLIS = "waitMillis"

        private const val OP_SET = "set"
        private const val OP_READ = "read"

        /** Long enough for a slow app screen to appear, short enough to keep an unattended run bounded. */
        private const val DEFAULT_WAIT_MILLIS = 8_000L
        private const val MIN_WAIT_MILLIS = 250L
        private const val MAX_WAIT_MILLIS = 60_000L

        /** Poll cadence: one tree read per tick, matching A1's dialog poll so a verdict means the same thing. */
        private const val POLL_MILLIS = 150L

        /** Let `moveTaskToBack` finish before the first read, or the probe reads its own window. */
        private const val TASK_MOVE_SETTLE_MILLIS = 400L

        /** A focus change is a frame's worth of work; the write follows it, not a race with it. */
        private const val FOCUS_SETTLE_MILLIS = 200L

        /** The accessibility tree can lag a text change; poll rather than reading one stale frame. */
        private const val READ_BACK_TIMEOUT_MILLIS = 2_000L

        /** Probe-owned fixture hint; a constant authored here, never read from the target app. */
        internal const val FIXTURE_HINT = "探针测试输入框"
    }
}
