package edith.phone.a11yprobe

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Activity
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * S1 **(2026-10-16) the accessibility capability survey runner** — answers "what atomic operations can a non-root
 * `AccessibilityService` give an LLM agent on the K40", one scenario per run.
 *
 * Reachable only by an explicit `am start` carrying [ACTION] plus `--ez confirm true`, like A1 and click-now: this
 * activity reads another app's window and injects touches, so it must never run because someone opened the app.
 *
 * **Privacy.** Every string this class writes to logcat is either a constant authored here, a class name, a resource
 * id, a package name, a count or a boolean. Screen text is *read* only to be counted or compared against a constant,
 * and the comparison result is what gets logged (D53, spec §17.5). The one string it ever reasons about that it did not
 * author is the label of a *system* row it deliberately queried by name, and even that is only reported as a count.
 */
class A11ySurveyActivity : Activity() {

    private val service: A11ySurveyService? get() = A11ySurveyService.instance

    private lateinit var status: TextView
    private lateinit var hitsView: TextView
    private lateinit var edit: EditText
    private lateinit var rejectEdit: RejectingEditText
    private lateinit var check: CheckBox
    private lateinit var expandable: ExpandableProbeView
    private lateinit var disabled: Button
    private lateinit var scroll: ScrollView
    private lateinit var webView: WebView
    private var items: List<TextView> = emptyList()

    private val hits = AtomicInteger(0)
    private val longHits = AtomicInteger(0)
    private val overlayHits = AtomicInteger(0)
    private val webClicks = AtomicInteger(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scenario = intent?.getStringExtra(EXTRA_SCENARIO) ?: "actions"
        try {
            buildZoo(scenario)
        } catch (error: Throwable) {
            // A crash here takes the whole process down with both accessibility services in it, and the framework then
            // reports them as *crashed* rather than as unbound — which looks exactly like "the capability does not
            // exist". The first run died this way; the line below is what makes that diagnosable in one logcat read.
            Log.e(A11ySurveyService.TAG, "SURVEY_ZOO_FAILED where=${error.javaClass.simpleName}", error)
            finish()
            return
        }
        if (intent?.getBooleanExtra(EXTRA_CONFIRM, false) != true) {
            status.text = "EDITH S1 SURVEY\n\n未确认，不执行。\n\nadb shell am start -n $packageName/.A11ySurveyActivity " +
                "--es scenario actions --ez $EXTRA_CONFIRM true"
            Log.w(A11ySurveyService.TAG, "SURVEY_NOT_CONFIRMED")
            return
        }
        status.text = "EDITH S1 SURVEY\n\nscenario=$scenario\n\n进行中…"
        Thread { runScenario(scenario) }.start()
    }

    /**
     * A second `am start` while this activity is still the top instance delivers the intent here, **not** to
     * `onCreate` — and the first version of this runner silently did nothing for every scenario after the first
     * (`am` reports it as "intent has been delivered to currently running top-most instance"). Both paths run the
     * battery, so a scenario can be selected whether or not the previous one has finished tearing down.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_CONFIRM, false) != true) return
        val scenario = intent.getStringExtra(EXTRA_SCENARIO) ?: "actions"
        status.text = "EDITH S1 SURVEY\n\nscenario=$scenario\n\n进行中…"
        Thread { runScenario(scenario) }.start()
    }

    // ==========================================================================================
    // Scenario dispatch
    // ==========================================================================================

    private fun runScenario(scenario: String) {
        val s = service
        if (s == null) {
            log("SURVEY_ABORT reason=survey_service_not_enabled")
            return
        }
        log(
            "SURVEY_START scenario=$scenario sdk=${Build.VERSION.SDK_INT} release=${Build.VERSION.RELEASE}" +
                " model=${Build.MODEL} pkg=$packageName",
        )
        if (scenario == "actions" || scenario == "tree" || scenario == "events") warmUp(s)
        runCatching {
            when (scenario) {
                "actions" -> actions(s)
                "tree" -> tree(s)
                "events" -> events(s)
                "secure" -> secure()
                "webview" -> webview(s)
                "overlay" -> overlay(s)
                "globals" -> globals(s)
                "crossapp" -> crossapp(s)
                else -> log("SURVEY_UNKNOWN_SCENARIO scenario=$scenario")
            }
        }.onFailure { log("SURVEY_EXCEPTION scenario=$scenario where=${it.javaClass.simpleName}") }
        log("SURVEY_END scenario=$scenario")
        // Finishing frees the fixture before the next scenario: the next `am start` then builds its own zoo instead of
        // reusing one built for a different scenario (the WebView only exists in `webview`).
        onUi { runCatching { finish() } }
    }

    // ==========================================================================================
    // Scenario: actions
    // ==========================================================================================

    /**
     * Wait until this activity owns the active window **and** its own tree can be read back.
     *
     * The first run measured nothing at the very start: `rootInActiveWindow` still named the app that was in front
     * before this one (another work line had left Settings there), so the first click aimed at a screen that was not
     * there and reported "the text was not found" — a probe artefact that would have read as a platform limitation.
     * Waiting for the precondition is what separates the two.
     */
    private fun warmUp(s: A11ySurveyService, timeoutMs: Long = 10_000): Boolean {
        val started = SystemClock.uptimeMillis()
        var own = false
        var readable = false
        while (SystemClock.uptimeMillis() - started < timeoutMs) {
            own = s.activePackage() == packageName
            readable = own && s.textOfViewId("$packageName:id/survey_hits") != null
            if (readable) break
            sleep(100)
        }
        log(
            "SURVEY_WARMUP ownWindow=$own treeReadable=$readable waitedMs=${SystemClock.uptimeMillis() - started}",
        )
        return readable
    }

    private fun actions(s: A11ySurveyService) {
        val self = packageName
        val rowId = "$self:id/survey_row_alpha"
        val buttonId = "$self:id/survey_button"
        val editId = "$self:id/survey_edit"
        val rejectId = "$self:id/survey_edit_reject"

        log("SURVEY_WINDOW pkg=${s.activePackage()} isOwn=${s.activePackage() == self}")

        // --- A01: click by text, aiming at the nearest clickable ancestor -----------------------
        val carried = s.activeWindowRoot()?.let { s.findByText(it, LABEL_ROW).firstOrNull() }
        val carrier = carried?.let { climbClickable(it) }
        val beforeA01 = hits.get()
        val retA01 = s.perform(carrier ?: carried, AccessibilityNodeInfo.ACTION_CLICK)
        val afterA01 = awaitHits(beforeA01 + 1, SETTLE_MS)
        log(
            "SURVEY_A01_TEXT_CLICK carried=${carried != null} carriedClickable=${carried?.isClickable}" +
                " ancestor=${carrier != null} ancestorClass=${carrier?.className}" +
                " ret=$retA01 hitsDelta=${afterA01 - beforeA01}",
        )

        // --- A02: click by view id -------------------------------------------------------------
        val byId = s.findNodeByViewId(buttonId)
        val beforeA02 = hits.get()
        val retA02 = s.perform(byId, AccessibilityNodeInfo.ACTION_CLICK)
        val afterA02 = awaitHits(beforeA02 + 1, SETTLE_MS)
        log(
            "SURVEY_A02_ID_CLICK found=${byId != null} resolvedId=${byId?.viewIdResourceName}" +
                " ret=$retA02 hitsDelta=${afterA02 - beforeA02}",
        )

        // --- A03: coordinate tap through dispatchGesture ----------------------------------------
        val rowNode = s.findNodeByViewId(rowId)
        val rect = Rect()
        rowNode?.getBoundsInScreen(rect)
        val beforeA03 = hits.get()
        val a03 = gestureTap(s, rect.centerX(), rect.centerY())
        val afterA03 = awaitHits(beforeA03 + 1, SETTLE_MS)
        log(
            "SURVEY_A03_COORD_TAP bounds=[${rect.left},${rect.top}][${rect.right},${rect.bottom}]" +
                " dispatched=${a03.first} completed=${a03.second} hitsDelta=${afterA03 - beforeA03}" +
                " gestureWindowOwn=${a03.third}",
        )

        // --- A04: long click ---------------------------------------------------------------------
        val beforeA04 = longHits.get()
        val retA04 = s.perform(s.findNodeByViewId(rowId), AccessibilityNodeInfo.ACTION_LONG_CLICK)
        val afterA04 = awaitLongHits(beforeA04 + 1, SETTLE_MS)
        log("SURVEY_A04_LONG_CLICK ret=$retA04 longHitsDelta=${afterA04 - beforeA04}")

        // --- A05/A06: ACTION_SET_TEXT on an unfocused standard EditText ---------------------------
        val focusedBefore = s.focusedEditable()?.viewIdResourceName
        val editNode = s.findNodeByViewId(editId)
        val retA05 = s.perform(editNode, AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs(PROBE_TEXT))
        val readA05 = s.textOfViewId(editId)
        log(
            "SURVEY_A05_SET_TEXT focusedBefore=$focusedBefore focusIsEdit=${focusedBefore == editId}" +
                " nodeEditable=${editNode?.isEditable} ret=$retA05 readLen=${readA05?.length ?: -1}" +
                " equalsProbe=${readA05 == PROBE_TEXT}",
        )
        val retA06 = s.perform(s.findNodeByViewId(editId), AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs(""))
        val clearedNode = s.findNodeByViewId(editId)
        val readA06 = clearedNode?.text
        val hintA06 = clearedNode?.hintText
        log(
            "SURVEY_A06_SET_TEXT_CLEAR ret=$retA06 readLen=${readA06?.length ?: -1} blank=${readA06?.isBlank()}" +
                // An empty EditText is not readable as "empty": on this device `.text` comes back as the *hint*, so a
                // reader that only looks at `text` cannot tell "the field is empty" from "the field holds the hint".
                " hintLen=${hintA06?.length ?: -1} textEqualsHint=${readA06 != null && readA06 == hintA06}" +
                " hintTextPresent=${hintA06 != null}",
        )

        // --- A07: ACTION_SET_TEXT on a node that is not editable ----------------------------------
        val staticNode = s.findNodeByViewId("$self:id/survey_static")
        val retA07 = s.perform(staticNode, AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs(PROBE_TEXT))
        val readA07 = s.textOfViewId("$self:id/survey_static")
        log(
            "SURVEY_A07_SET_TEXT_NONEDITABLE nodeEditable=${staticNode?.isEditable} ret=$retA07" +
                " stillEqualsConstant=${readA07 == LABEL_STATIC}",
        )

        // --- A08: ACTION_PASTE ---------------------------------------------------------------------
        setClipboard(PROBE_CLIP)
        s.perform(s.findNodeByViewId(editId), AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs(PROBE_TEXT))
        val focusedBeforePaste = s.focusedEditable()?.viewIdResourceName
        val retA08 = s.perform(s.findNodeByViewId(editId), AccessibilityNodeInfo.ACTION_PASTE)
        sleep(SETTLE_MS)
        val readA08 = s.textOfViewId(editId)
        log(
            "SURVEY_A08_PASTE focusedBefore=$focusedBeforePaste ret=$retA08 readLen=${readA08?.length ?: -1}" +
                " containsClip=${readA08?.contains(PROBE_CLIP)} unchanged=${readA08 == PROBE_TEXT}",
        )

        // --- A09/A10: a field that accepts the action and keeps its own text -----------------------
        val rejectNode = s.findNodeByViewId(rejectId)
        RejectingEditText.setTextAttempts.set(0)
        RejectingEditText.pasteAttempts.set(0)
        val retA09 = s.perform(rejectNode, AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs(PROBE_TEXT))
        val readA09 = s.textOfViewId(rejectId)
        log(
            "SURVEY_A09_SET_TEXT_REJECTING ret=$retA09 setTextCalls=${RejectingEditText.setTextAttempts.get()}" +
                " readLen=${readA09?.length ?: -1} equalsProbe=${readA09 == PROBE_TEXT}",
        )
        val retA10 = s.perform(s.findNodeByViewId(rejectId), AccessibilityNodeInfo.ACTION_PASTE)
        log(
            "SURVEY_A10_PASTE_REJECTING ret=$retA10 pasteCalls=${RejectingEditText.pasteAttempts.get()}",
        )

        // --- A11: click on a clickable but disabled Button ------------------------------------------
        val disabledNode = s.findNodeByViewId("$self:id/survey_disabled")
        val beforeA11 = hits.get()
        val retA11 = s.perform(disabledNode, AccessibilityNodeInfo.ACTION_CLICK)
        val afterA11 = awaitHits(beforeA11 + 1, SETTLE_MS)
        log(
            "SURVEY_A11_CLICK_DISABLED nodeClickable=${disabledNode?.isClickable}" +
                " nodeEnabled=${disabledNode?.isEnabled} ret=$retA11 hitsDelta=${afterA11 - beforeA11}",
        )

        // --- A12/A13: checkable, and selected ---------------------------------------------------------
        // The platform exposes **no** ACTION_CHECK/ACTION_UNCHECK on `AccessibilityNodeInfo`: a checkable node is
        // toggled by ACTION_CLICK and read back with `isChecked`. This measures exactly that, twice, to show the flip.
        val checkId = "$self:id/survey_check"
        val checkNode = s.findNodeByViewId(checkId)
        val checkedBefore = checkNode?.isChecked
        val retA12 = s.perform(checkNode, AccessibilityNodeInfo.ACTION_CLICK)
        sleep(SETTLE_MS)
        val afterFirst = s.findNodeByViewId(checkId)?.isChecked
        val retA13 = s.perform(s.findNodeByViewId(checkId), AccessibilityNodeInfo.ACTION_CLICK)
        sleep(SETTLE_MS)
        val afterSecond = s.findNodeByViewId(checkId)?.isChecked
        log(
            "SURVEY_A12_CHECKABLE checkable=${checkNode?.isCheckable} before=$checkedBefore" +
                " clickRet=$retA12 afterFirst=$afterFirst secondClickRet=$retA13 afterSecond=$afterSecond",
        )
        val retSelect = s.perform(s.findNodeByViewId(rowId), AccessibilityNodeInfo.ACTION_SELECT)
        sleep(SETTLE_MS)
        val selectedAfter = s.findNodeByViewId(rowId)?.isSelected
        val retClearSelect = s.perform(s.findNodeByViewId(rowId), AccessibilityNodeInfo.ACTION_CLEAR_SELECTION)
        sleep(SETTLE_MS)
        log(
            "SURVEY_A13_SELECT ret=$retSelect selectedAfter=$selectedAfter" +
                " clearRet=$retClearSelect selectedAfterClear=${s.findNodeByViewId(rowId)?.isSelected}",
        )

        // --- A15: ACTION_FOCUS -------------------------------------------------------------------------
        val retFocus = s.perform(s.findNodeByViewId(editId), AccessibilityNodeInfo.ACTION_FOCUS)
        sleep(SETTLE_MS)
        val focusedAfter = s.focusedEditable()?.viewIdResourceName
        log("SURVEY_A15_FOCUS ret=$retFocus focusedIsEdit=${focusedAfter == editId} focused=$focusedAfter")

        // --- A08b: ACTION_PASTE again, now that the field **is** focused --------------------------------
        // The first attempt ran while the field was unfocused and returned false; that alone cannot separate "paste
        // needs input focus" from "paste is not supported here", so both conditions are measured.
        setClipboard(PROBE_CLIP)
        s.perform(s.findNodeByViewId(editId), AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs(PROBE_TEXT))
        val retPasteFocused = s.perform(s.findNodeByViewId(editId), AccessibilityNodeInfo.ACTION_PASTE)
        sleep(SETTLE_MS)
        val readPaste = s.textOfViewId(editId)
        log(
            "SURVEY_A08B_PASTE_FOCUSED focusIsEdit=${s.focusedEditable()?.viewIdResourceName == editId}" +
                " ret=$retPasteFocused readLen=${readPaste?.length ?: -1}" +
                " containsClip=${readPaste?.contains(PROBE_CLIP)} unchanged=${readPaste == PROBE_TEXT}",
        )

        // --- A16/A17/A18: scrolling ----------------------------------------------------------------------
        scrollToTop(s)
        val top = visibleItemRange(s)
        val scrollId = "$self:id/survey_scroll"
        val scrollNode = s.findNodeByViewId(scrollId)
        val scrollRect = Rect()
        scrollNode?.getBoundsInScreen(scrollRect)
        log(
            "SURVEY_A15B_SCROLL_NODE found=${scrollNode != null} class=${scrollNode?.className}" +
                " scrollable=${scrollNode?.isScrollable} childCount=${scrollNode?.childCount}" +
                " bounds=[${scrollRect.left},${scrollRect.top}][${scrollRect.right},${scrollRect.bottom}]" +
                " visibleItems=$top",
        )
        val retFwd = s.perform(s.findNodeByViewId(scrollId), AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        sleep(SCROLL_SETTLE_MS)
        val afterFwd = visibleItemRange(s)
        log("SURVEY_A16_SCROLL_FORWARD ret=$retFwd before=$top after=$afterFwd moved=${top != afterFwd}")

        var scrolls = 0
        var lastRet = false
        while (scrolls < MAX_SCROLLS) {
            val from = visibleItemRange(s)
            lastRet = s.perform(s.findNodeByViewId(scrollId), AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            sleep(SCROLL_SETTLE_MS)
            scrolls += 1
            if (visibleItemRange(s) == from) break
        }
        val atEnd = visibleItemRange(s)
        // The action is performed again at the end on purpose: the return value here is the "accepted but nothing moved"
        // case the backend's NO_SCREEN_CHANGE refusal exists for.
        val retAtEnd = s.perform(s.findNodeByViewId(scrollId), AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
        sleep(SCROLL_SETTLE_MS)
        val stillEnd = visibleItemRange(s)
        log(
            "SURVEY_A17_SCROLL_TO_END scrolls=$scrolls atEnd=$atEnd retAtEnd=$retAtEnd" +
                " movedAtEnd=${stillEnd != atEnd} lastRet=$lastRet",
        )

        val retBack = s.perform(s.findNodeByViewId(scrollId), AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
        sleep(SCROLL_SETTLE_MS)
        log("SURVEY_A18_SCROLL_BACKWARD ret=$retBack from=$stillEnd to=${visibleItemRange(s)}")

        // --- A19: ACTION_SHOW_ON_SCREEN on a node that is laid out but off screen ---------------------
        val lastId = "$self:id/survey_item_last"
        val lastNode = s.findNodeByViewId(lastId)
        val visibleBefore = lastNode?.isVisibleToUser
        val retShow = s.perform(
            lastNode,
            AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id,
        )
        sleep(SCROLL_SETTLE_MS)
        val lastAfter = s.findNodeByViewId(lastId)
        log(
            "SURVEY_A19_SHOW_ON_SCREEN found=${lastNode != null} visibleBefore=$visibleBefore" +
                " ret=$retShow visibleAfter=${lastAfter?.isVisibleToUser}",
        )

        // --- A20: ACTION_EXPAND / ACTION_COLLAPSE on a view that advertises them ----------------------
        val expandId = "$self:id/survey_expand"
        val descBefore = s.findNodeByViewId(expandId)?.contentDescription
        val retExpand = s.perform(s.findNodeByViewId(expandId), AccessibilityNodeInfo.ACTION_EXPAND)
        sleep(SETTLE_MS)
        val descExpanded = s.findNodeByViewId(expandId)?.contentDescription
        val retCollapse = s.perform(s.findNodeByViewId(expandId), AccessibilityNodeInfo.ACTION_COLLAPSE)
        sleep(SETTLE_MS)
        val descCollapsed = s.findNodeByViewId(expandId)?.contentDescription
        log(
            "SURVEY_A20_EXPAND_COLLAPSE before=$descBefore expandRet=$retExpand expanded=$descExpanded" +
                " collapseRet=$retCollapse collapsed=$descCollapsed",
        )

        // --- A21: one gesture carrying two sequential taps (multi-step) --------------------------------
        val btnRect = Rect()
        s.findNodeByViewId(buttonId)?.getBoundsInScreen(btnRect)
        val beforeMulti = hits.get()
        val multi = gestureTapTwice(s, btnRect.centerX(), btnRect.centerY())
        val afterMulti = awaitHits(beforeMulti + 2, SETTLE_MS * 2)
        log(
            "SURVEY_A21_MULTI_STEP_TAPS dispatched=${multi.first} completed=${multi.second}" +
                " hitsDelta=${afterMulti - beforeMulti} ownWindow=${multi.third}",
        )

        // --- A22: a swipe gesture moves a scroll container -------------------------------------------------
        scrollToTop(s)
        val beforeSwipe = visibleItemRange(s)
        val swipeRect = Rect()
        s.findNodeByViewId(scrollId)?.getBoundsInScreen(swipeRect)
        val swipe = gestureSwipe(s, swipeRect)
        sleep(SCROLL_SETTLE_MS)
        log(
            "SURVEY_A22_SWIPE dispatched=${swipe.first} completed=${swipe.second}" +
                " ownWindow=${swipe.third} before=$beforeSwipe after=${visibleItemRange(s)}",
        )

        // --- A23: the IME window ------------------------------------------------------------------------
        val imeShown = showIme()
        sleep(1_200)
        val windows = s.windowSurvey()
        log(
            "SURVEY_A23_IME shown=$imeShown windows=${windows.size} hasImeWindow=" +
                "${windows.any { it.startsWith("INPUT_METHOD") }} windowList=${windows.joinToString("|")}",
        )
        hideIme()
        sleep(SETTLE_MS)

        // --- A24: a node captured before the screen changed -----------------------------------------------
        val stale = s.findNodeByViewId(buttonId)
        val beforeStale = hits.get()
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        sleep(1_500)
        val pkgAway = s.activePackage()
        val retStale = s.perform(stale, AccessibilityNodeInfo.ACTION_CLICK)
        sleep(SETTLE_MS)
        log(
            "SURVEY_A24_STALE_NODE captured=${stale != null} activeWhileAway=$pkgAway ret=$retStale" +
                " hitsDelta=${hits.get() - beforeStale} activeAfter=${s.activePackage()}",
        )
    }

    // ==========================================================================================
    // Scenario: tree
    // ==========================================================================================

    private fun tree(s: A11ySurveyService) {
        val self = packageName
        val root = s.activeWindowRoot()
        if (root == null) {
            log("SURVEY_TREE_NO_ROOT")
            return
        }
        val shape = s.shapeOf(
            root,
            deepestLabel = LABEL_DEEP,
            wideId = "$self:id/survey_wide",
        )
        log(
            "SURVEY_T01_SHAPE nodes=${shape.nodes} maxDepth=${shape.maxDepth} maxChildCount=${shape.maxChildCount}" +
                " truncated=${shape.truncated} nullChildren=${shape.nullChildren} visible=${shape.visible}" +
                " withText=${shape.withText}" +
                " withDesc=${shape.withContentDescription} withViewId=${shape.withViewId}" +
                " withClassName=${shape.withClassName} clickable=${shape.clickable} editable=${shape.editable}" +
                " scrollable=${shape.scrollable} checkable=${shape.checkable} checked=${shape.checked}" +
                " withBounds=${shape.withBounds} distinctActions=${shape.distinctActionCounts}",
        )
        log(
            "SURVEY_T02_LIMITS wideChildCount=${shape.wideChildCount} wideExpected=$WIDE_CHILDREN" +
                " deepLabelFound=${shape.deepestLabelFound} deepExpectedDepth=$DEEP_LEVELS",
        )
        val labels = s.visibleLabels(s.activeWindowRoot() ?: root)
        log(
            "SURVEY_T03_LABEL_COUNT visibleLabels=${labels.size} itemLabels=${labels.count { ITEM_REGEX.matches(it) }}",
        )

        // findAccessibilityNodeInfosByText: exact, substring, case-insensitive, contentDescription, miss.
        val r = s.activeWindowRoot() ?: root
        val exact = s.findByText(r, LABEL_ROW).size
        val substring = s.findByText(r, LABEL_ROW.substring(0, 3)).size
        val lower = s.findByText(r, LABEL_ROW.lowercase()).size
        val byDesc = s.findByText(r, LABEL_EXPAND_SHUT).size
        val miss = s.findByText(r, LABEL_ABSENT).size
        log(
            "SURVEY_T04_FIND_BY_TEXT exact=$exact substring=$substring lowercase=$lower" +
                " byContentDescription=$byDesc absent=$miss",
        )

        // findAccessibilityNodeInfosByViewId, and the A/B against the flag-less twin.
        val ownId = s.findByViewId(r, "$self:id/survey_button").size
        val unknownId = s.findByViewId(r, "$self:id/definitely_not_a_view").size
        val noIds = A11ySurveyService.noIdsInstance
        val noIdsRoot = noIds?.activeWindowRoot()
        val noIdsOwnId = if (noIdsRoot != null) noIds.findByViewId(noIdsRoot, "$self:id/survey_button").size else -1
        val noIdsHadId = noIdsRoot?.let { n -> s.shapeOf(n, maxNodes = 400).withViewId } ?: -1
        val withIdsHadId = s.shapeOf(r, maxNodes = 400).withViewId
        log(
            "SURVEY_A_B_VIEWIDS withFlag_ownIdMatches=$ownId withFlag_unknownId=$unknownId" +
                " withFlag_nodesWithId=$withIdsHadId" +
                " noFlag_ownIdMatches=$noIdsOwnId noFlag_nodesWithId=$noIdsHadId" +
                " twinEnabled=${noIds != null}",
        )

        // getWindows() with and without flagRetrieveInteractiveWindows.
        val mine = s.windowSurvey()
        val twin = noIds?.windowSurvey() ?: emptyList()
        log(
            "SURVEY_A_B_WINDOWS withFlag_windows=${mine.size} withFlag=${mine.joinToString("|")}" +
                " noFlag_windows=${twin.size} noFlag=${twin.joinToString("|")}",
        )

        // A container marked not-important: is its important descendant still reachable?
        val nested = s.findByText(r, LABEL_NESTED).size
        log("SURVEY_T05_NOT_IMPORTANT_CONTAINER nestedTextStillFound=$nested")
    }

    // ==========================================================================================
    // Scenario: events
    // ==========================================================================================

    private fun events(s: A11ySurveyService) {
        val self = packageName
        val expandId = "$self:id/survey_expand"
        A11ySurveyService.resetEventCounters()
        sleep(300)
        val baselineQuiet = A11ySurveyService.quietMillis()

        // One UI change, then: when did the first event arrive, and when did the tree first show the change?
        val t0 = SystemClock.uptimeMillis()
        s.perform(s.findNodeByViewId(expandId), AccessibilityNodeInfo.ACTION_EXPAND)
        var treeSeenAt = -1L
        val deadline = t0 + 3_000
        while (SystemClock.uptimeMillis() < deadline) {
            if (s.findNodeByViewId(expandId)?.contentDescription == LABEL_EXPAND_OPEN) {
                treeSeenAt = SystemClock.uptimeMillis()
                break
            }
            sleep(10)
        }
        val firstEvent = A11ySurveyService.firstEventAfter(t0)
        val firstContentChanged = A11ySurveyService.firstEventAfter(t0, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)
        log(
            "SURVEY_E01_EVENT_VS_TREE firstEventMs=${if (firstEvent < 0) -1 else firstEvent - t0}" +
                " firstContentChangedMs=${if (firstContentChanged < 0) -1 else firstContentChanged - t0}" +
                " treeVisibleMs=${if (treeSeenAt < 0) -1 else treeSeenAt - t0}" +
                " baselineQuietMs=$baselineQuiet",
        )

        // How long the screen stays noisy after one change — the "wait for idle" question.
        val settleStart = SystemClock.uptimeMillis()
        var quietAt = -1L
        while (SystemClock.uptimeMillis() < settleStart + 4_000) {
            if (A11ySurveyService.quietMillis() >= QUIET_THRESHOLD_MS) {
                quietAt = SystemClock.uptimeMillis()
                break
            }
            sleep(20)
        }
        log(
            "SURVEY_E02_SETTLE timeToQuietMs=${if (quietAt < 0) -1 else quietAt - settleStart}" +
                " quietThresholdMs=$QUIET_THRESHOLD_MS",
        )

        // A burst of changes, to see the histogram's shape.
        A11ySurveyService.resetEventCounters()
        repeat(5) {
            s.perform(s.findNodeByViewId("$self:id/survey_button"), AccessibilityNodeInfo.ACTION_CLICK)
            sleep(250)
        }
        sleep(500)
        log(
            "SURVEY_E03_HISTOGRAM total=${A11ySurveyService.totalEvents.get()}" +
                " distinct=${A11ySurveyService.eventCounts.size} counts=${A11ySurveyService.eventHistogram()}",
        )
        val eventTypes = A11ySurveyService.eventCounts.keys.sorted().joinToString(",")
        log("SURVEY_E04_EVENT_TYPES types=$eventTypes")
        log(
            "SURVEY_E05_EVENT_CONSTANTS windowStateChanged=${AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED}" +
                " windowContentChanged=${AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED}" +
                " viewFocused=${AccessibilityEvent.TYPE_VIEW_FOCUSED}" +
                " viewClicked=${AccessibilityEvent.TYPE_VIEW_CLICKED}",
        )
    }

    // ==========================================================================================
    // Scenario: secure (FLAG_SECURE)
    // ==========================================================================================

    private fun secure() {
        val s = service ?: return
        // BOTH dialogs are constructed **inside** `onUi`: `Dialog` creates a `Handler` in its constructor, so building
        // one on this worker thread throws "Can't create handler inside thread that has not called Looper.prepare()" —
        // which is how the first `secure` run ended as a bare `SURVEY_EXCEPTION where=RuntimeException`.
        var normalDialog: Dialog? = null
        onUi {
            normalDialog = Dialog(this).apply {
                setContentView(
                    labeledColumn(LABEL_NORMAL).also { it.getChildAt(0).id = R.id.survey_normal_text },
                )
                show()
            }
        }
        sleep(1_000)
        val normalRoot = s.activeWindowRoot()
        val normalFound = if (normalRoot != null) s.findByText(normalRoot, LABEL_NORMAL).size else -1
        log(
            "SURVEY_S01_NORMAL_DIALOG found=$normalFound windows=${s.windowSurvey().size}" +
                " activePackage=${s.activePackage()}",
        )
        onUi { runCatching { normalDialog?.dismiss() } }
        sleep(SETTLE_MS)

        var secureDialog: Dialog? = null
        onUi {
            secureDialog = Dialog(this).apply {
                window?.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
                setContentView(
                    labeledColumn(LABEL_SECURE).also { column ->
                        column.getChildAt(0).id = R.id.survey_secure_text
                        column.addView(
                            EditText(this@A11ySurveyActivity).apply {
                                id = R.id.survey_secure_edit
                                setText(LABEL_SECURE_VALUE)
                            },
                        )
                    },
                )
                show()
            }
        }
        sleep(1_000)
        val root = s.activeWindowRoot()
        val secureFound = root?.let { s.findByText(it, LABEL_SECURE).size } ?: -1
        val secureByIdFound = root?.let { s.findByViewId(it, "$packageName:id/survey_secure_edit").size } ?: -1
        val secureEditText = s.textOfViewId("$packageName:id/survey_secure_edit")
        val ret = s.perform(
            s.findNodeByViewId("$packageName:id/survey_secure_edit"),
            AccessibilityNodeInfo.ACTION_SET_TEXT,
            setTextArgs(PROBE_TEXT),
        )
        val afterWrite = s.textOfViewId("$packageName:id/survey_secure_edit")
        log(
            "SURVEY_S02_SECURE_WINDOW found=$secureFound byIdFound=$secureByIdFound" +
                " editReadLen=${secureEditText?.length ?: -1} setTextRet=$ret" +
                " wroteLen=${afterWrite?.length ?: -1} wroteEqualsProbe=${afterWrite == PROBE_TEXT}" +
                " windows=${s.windowSurvey().joinToString("|")}",
        )
        onUi { runCatching { secureDialog?.dismiss() } }
        sleep(SETTLE_MS)
        val afterDismiss = s.activeWindowRoot()
        log(
            "SURVEY_S03_SECURE_DISMISSED stillFound=" +
                "${if (afterDismiss != null) s.findByText(afterDismiss, LABEL_SECURE).size else -1}",
        )
    }

    // ==========================================================================================
    // Scenario: webview (a non-standard, virtual-node input)
    // ==========================================================================================

    private fun webview(s: A11ySurveyService) {
        if (!::webView.isInitialized) {
            log("SURVEY_W00_WEB_NOT_PRESENT reason=instance_reused_from_another_scenario")
            return
        }
        // Readiness is "the WebView node has an **editable** descendant or the link's text", not "the node's text
        // contains W..." — the first version looked for the input's HTML `id` as if it were its text, so it waited the
        // full budget and reported `not ready` for a page that had loaded fine. A WebView input carries no text while
        // it is empty; `isEditable` is the fact that is actually there.
        val deadline = SystemClock.uptimeMillis() + WEB_TIMEOUT_MS
        var candidate: AccessibilityNodeInfo? = null
        var sawNode = false
        while (SystemClock.uptimeMillis() < deadline) {
            val activeRoot = s.activeWindowRoot() ?: break
            val found = s.findByViewId(activeRoot, "$packageName:id/survey_webview").firstOrNull()
            if (found != null) {
                sawNode = true
                if (findEditable(found) != null || s.findByText(found, LABEL_WEB_LINK).isNotEmpty()) {
                    candidate = found
                    break
                }
            }
            sleep(150)
        }
        val webRoot = candidate
        if (webRoot == null) {
            log("SURVEY_W00_WEB_NOT_READY waitedMs=$WEB_TIMEOUT_MS sawWebViewNode=$sawNode")
            return
        }
        val shape = s.shapeOf(webRoot, maxNodes = 800, maxDepth = 40)
        log(
            "SURVEY_W01_WEB_SHAPE nodes=${shape.nodes} maxDepth=${shape.maxDepth} editable=${shape.editable}" +
                " clickable=${shape.clickable} withText=${shape.withText} withViewId=${shape.withViewId}" +
                " withClassName=${shape.withClassName} nullChildren=${shape.nullChildren}",
        )
        val node = findEditable(webRoot)
        val ret = s.perform(node, AccessibilityNodeInfo.ACTION_SET_TEXT, setTextArgs(PROBE_TEXT))
        sleep(600)
        log(
            "SURVEY_W02_WEB_SET_TEXT found=${node != null} nodeEditable=${node?.isEditable}" +
                " nodeClass=${node?.className} nodeViewId=${node?.viewIdResourceName} ret=$ret" +
                " value=${webValueProbe()}",
        )
        val inputClicks = webClicks.get()
        val link = s.findByText(webRoot, LABEL_WEB_LINK).firstOrNull()
        val walkLink = s.firstNodeWithText(webRoot, LABEL_WEB_LINK)
        val clicked = s.perform(climbClickable(walkLink ?: link ?: return) ?: walkLink ?: link, AccessibilityNodeInfo.ACTION_CLICK)
        sleep(600)
        log(
            "SURVEY_W03_WEB_CLICK byFindNode=${link != null} byManualWalk=${walkLink != null}" +
                " ret=$clicked jsClicksDelta=${webClicks.get() - inputClicks}",
        )
    }

    /** The first editable node in [node]'s subtree — how a virtual (WebView-provided) input is addressed. */
    private fun findEditable(node: AccessibilityNodeInfo, depth: Int = 0): AccessibilityNodeInfo? {
        if (node.isEditable) return node
        if (depth > 40) return null
        for (index in 0 until node.childCount) {
            val child = node.getChild(index) ?: continue
            findEditable(child, depth + 1)?.let { return it }
        }
        return null
    }

    /** Reads `document.getElementById('f').value` in the probe page and reports **length + equality only**. */
    private fun webValueProbe(): String {
        val latch = CountDownLatch(1)
        var len = -1
        var matches = false
        onUi {
            webView.evaluateJavascript("(function(){var v=document.getElementById('f').value;" +
                "return v.length + ':' + (v === '$PROBE_TEXT');})()") { value ->
                val parts = value?.trim('"')?.split(":") ?: emptyList()
                len = parts.getOrNull(0)?.toIntOrNull() ?: -1
                matches = parts.getOrNull(1) == "true"
                latch.countDown()
            }
        }
        runCatching { latch.await(2, TimeUnit.SECONDS) }
        return "len=$len equalsProbe=$matches"
    }

    // ==========================================================================================
    // Scenario: overlay (a window of ours above our own window)
    // ==========================================================================================

    private fun overlay(s: A11ySurveyService) {
        if (!Settings.canDrawOverlays(this)) {
            log("SURVEY_O00_OVERLAY_SKIPPED reason=no_overlay_permission")
            return
        }
        // The rect must be the button's **real** rect. The first overlay run measured it before this activity had been
        // laid out and got `[0,0][0,0]`, so the "overlay" was placed in the top-left corner over the status bar, the
        // coordinate tap was aimed at (0,0), and neither the target nor the overlay counted a hit — an occlusion test
        // that occluded nothing. Waiting for a non-empty rect is what makes the claim mean something.
        warmUp(s)
        val self = packageName
        val buttonId = "$self:id/survey_button"
        val buttonRect = Rect()
        val rectDeadline = SystemClock.uptimeMillis() + 5_000
        while (SystemClock.uptimeMillis() < rectDeadline) {
            s.findNodeByViewId(buttonId)?.getBoundsInScreen(buttonRect)
            if (buttonRect.width() > 0 && buttonRect.height() > 0) break
            sleep(100)
        }
        if (buttonRect.width() <= 0 || buttonRect.height() <= 0) {
            log("SURVEY_O00_OVERLAY_SKIPPED reason=button_rect_empty")
            return
        }

        val manager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val overlayView = TextView(this).apply {
            text = LABEL_OVERLAY
            setBackgroundColor(Color.argb(200, 255, 0, 0))
            isClickable = true
            setOnClickListener {
                overlayHits.incrementAndGet()
                text = "$LABEL_OVERLAY hits=${overlayHits.get()}"
            }
        }
        // **Full screen**, because a window placed at the button's own rectangle does not cover it: `WindowManager`
        // positions a `gravity=TOP` window in the *content* area, which on this device starts 80 px below the button's
        // screen rectangle (the status bar). The first two overlay runs therefore left the button's centre exposed and
        // the coordinate tap kept working. Covering everything removes that offset from the experiment.
        val params = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        try {
            onUi { manager.addView(overlayView, params) }
            sleep(1_200)
            val root = s.activeWindowRoot()
            val overlayFound = root?.let { s.findByText(it, LABEL_OVERLAY).size } ?: -1
            val overlayFoundByWalk = root?.let { s.findAccessibilityNodeInfosByTextOn(it, LABEL_OVERLAY) } ?: -1
            val overlayFoundInAnyWindow = s.findInAnyWindow(LABEL_OVERLAY)
            val windows = s.windowSurvey()
            log(
                "SURVEY_O00_OVERLAY_ADDED buttonBounds=[${buttonRect.left},${buttonRect.top}]" +
                    "[${buttonRect.right},${buttonRect.bottom}] overlayAt=(${params.x},${params.y})" +
                    " size=${params.width}x${params.height}",
            )
            log(
                "SURVEY_O01_OVERLAY visible=${overlayView.isShown} inActiveTree=$overlayFound" +
                    " inActiveTreeByWalk=$overlayFoundByWalk inAnyWindow=$overlayFoundInAnyWindow" +
                    " windows=${windows.joinToString("|")}",
            )
            val beforeNode = hits.get()
            val retNode = s.perform(s.findNodeByViewId(buttonId), AccessibilityNodeInfo.ACTION_CLICK)
            val afterNode = awaitHits(beforeNode + 1, SETTLE_MS)
            log("SURVEY_O02_OCCLUDED_NODE_CLICK ret=$retNode hitsDelta=${afterNode - beforeNode}")

            val beforeTap = hits.get()
            val beforeOverlay = overlayHits.get()
            val tap = gestureTap(s, buttonRect.centerX(), buttonRect.centerY())
            sleep(SETTLE_MS)
            log(
                "SURVEY_O03_OCCLUDED_COORD_TAP dispatched=${tap.first} completed=${tap.second}" +
                    " ownWindow=${tap.third} targetHitsDelta=${hits.get() - beforeTap}" +
                    " overlayHitsDelta=${overlayHits.get() - beforeOverlay}" +
                    " windowsAfter=${s.windowSurvey().joinToString("|")}",
            )
        } finally {
            onUi { runCatching { manager.removeView(overlayView) } }
            sleep(SETTLE_MS)
            log("SURVEY_O04_OVERLAY_REMOVED stillShown=${runCatching { overlayView.isShown }.getOrDefault(false)}")
        }
    }

    // ==========================================================================================
    // Scenario: globals
    // ==========================================================================================

    private fun globals(s: A11ySurveyService) {
        // Order matters and the first version had it wrong: HOME runs first sends *this* activity to the background,
        // and a background app's `startActivity` is refused on Android 10+, so the Settings step silently did nothing
        // (`G02 active=com.miui.home`). BACK is measured against a real app screen, so that comes first, while this
        // activity still owns the foreground.
        warmUp(s)
        log("SURVEY_G00_START active=${s.activePackage()}")

        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        sleep(1_800)
        log("SURVEY_G01_SETTINGS_ON_TOP active=${s.activePackage()} windows=${s.windowSurvey().size}")

        val back = runCatching { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }.getOrDefault(false)
        sleep(1_500)
        log("SURVEY_G02_BACK_LEAVES_APP ret=$back active=${s.activePackage()}")

        val home = runCatching { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME) }.getOrDefault(false)
        sleep(1_500)
        log("SURVEY_G03_HOME ret=$home active=${s.activePackage()} windows=${s.windowSurvey().size}")

        val recents = runCatching { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) }.getOrDefault(false)
        sleep(1_500)
        log("SURVEY_G04_RECENTS ret=$recents windows=${s.windowSurvey().joinToString("|")}")

        val backFromRecents = runCatching { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }.getOrDefault(false)
        sleep(1_200)
        log("SURVEY_G05_BACK_FROM_RECENTS ret=$backFromRecents active=${s.activePackage()}")

        val shade = runCatching { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS) }.getOrDefault(false)
        sleep(1_500)
        log("SURVEY_G06_NOTIFICATIONS ret=$shade windows=${s.windowSurvey().joinToString("|")}")

        val backFromShade = runCatching { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK) }.getOrDefault(false)
        sleep(1_200)
        log(
            "SURVEY_G07_BACK_FROM_SHADE ret=$backFromShade windows=${s.windowSurvey().joinToString("|")}" +
                " active=${s.activePackage()}",
        )

        // GLOBAL_ACTION_QUICK_SETTINGS and GLOBAL_ACTION_LOCK_SCREEN are deliberately NOT executed: the first leaves the
        // quick-settings shade open over the acceptance device, and the second locks a device another work line is using.
        log(
            "SURVEY_G08_NOT_EXECUTED quickSettings=${AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS}" +
                " lockScreen=${AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN}" +
                " powerDialog=${AccessibilityService.GLOBAL_ACTION_POWER_DIALOG}" +
                " reason=state_on_shared_acceptance_device",
        )
    }

    // ==========================================================================================
    // Scenario: crossapp (a system app, and the clipboard while backgrounded)
    // ==========================================================================================

    private fun crossapp(s: A11ySurveyService) {
        // Foreground first: a background app's `startActivity` is refused on Android 10+, and the system app this
        // scenario reads has to actually come to the front.
        warmUp(s)
        setClipboard(PROBE_CLIP)

        // "Wait until the screen is stable" measured rather than assumed: how long after asking for another app does
        // its window-state event arrive, and how long until the tree actually names it.
        A11ySurveyService.resetEventCounters()
        val t0 = SystemClock.uptimeMillis()
        startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        var treeNamedAt = -1L
        val switchDeadline = t0 + 5_000
        while (SystemClock.uptimeMillis() < switchDeadline) {
            if (s.activePackage() == "com.android.settings") {
                treeNamedAt = SystemClock.uptimeMillis()
                break
            }
            sleep(20)
        }
        val firstEvent = A11ySurveyService.firstEventAfter(t0)
        val firstWindowState = A11ySurveyService.firstEventAfter(t0, AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        log(
            "SURVEY_X00_SCREEN_SWITCH firstEventMs=${if (firstEvent < 0) -1 else firstEvent - t0}" +
                " firstWindowStateMs=${if (firstWindowState < 0) -1 else firstWindowState - t0}" +
                " treeNamedNewAppMs=${if (treeNamedAt < 0) -1 else treeNamedAt - t0}",
        )
        sleep(1_200)

        val pkg = s.activePackage()
        val root = s.activeWindowRoot()
        log("SURVEY_X01_ACTIVE pkg=$pkg readable=${root != null} isOwn=${pkg == packageName}")
        if (root == null) return

        val shape = s.shapeOf(root, maxNodes = 4_000, maxDepth = 200)
        log(
            "SURVEY_X02_SYSTEM_APP_SHAPE nodes=${shape.nodes} maxDepth=${shape.maxDepth}" +
                " withText=${shape.withText} withDesc=${shape.withContentDescription}" +
                " withViewId=${shape.withViewId} withClassName=${shape.withClassName}" +
                " clickable=${shape.clickable} scrollable=${shape.scrollable} visible=${shape.visible}" +
                " truncated=${shape.truncated}",
        )

        val probe = s.findByText(root, SYSTEM_QUERY)
        val probeShorter = s.findByText(root, SYSTEM_QUERY_SHORTER)
        log(
            "SURVEY_X03_SYSTEM_FIND_BY_TEXT queryHits=${probe.size} shorterQueryHits=${probeShorter.size}" +
                " absent=${s.findByText(root, LABEL_ABSENT).size}",
        )
        val ids = s.distinctViewIds(root)
        log("SURVEY_X04_SYSTEM_VIEW_IDS count=${ids.size} sample=${ids.take(6).joinToString("|")}")

        val node = probe.firstOrNull()
        log(
            "SURVEY_X05_SYSTEM_TEXT_READ matched=${node != null} textLen=${node?.text?.length ?: -1}" +
                " clickable=${node?.isClickable} class=${node?.className}" +
                " clickableAncestor=${node?.let { climbClickable(it) }?.className}",
        )

        val windows = s.windowSurvey()
        log("SURVEY_X06_WINDOWS count=${windows.size} list=${windows.joinToString("|")}")

        // Clipboard from a backgrounded app: the value itself is never logged, only whether it was readable at all.
        val clip = runCatching {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val primary = manager.primaryClip
            "hasClip=${primary != null} items=${primary?.itemCount ?: -1}" +
                " matchesProbe=${primary?.getItemAt(0)?.text?.toString() == PROBE_CLIP}"
        }.getOrElse { "threw=${it.javaClass.simpleName}" }
        log("SURVEY_X07_CLIPBOARD_BACKGROUND $clip")

        val twin = A11ySurveyService.noIdsInstance
        val twinRoot = twin?.activeWindowRoot()
        val twinIds = twinRoot?.let { twin.distinctViewIds(it) } ?: emptyList()
        // The decisive half of the A/B: not "is the *name* readable" but "does a by-id **lookup** still resolve".
        val genericId = "android:id/title"
        val withFlagLookup = s.findByViewId(root, genericId).size
        val noFlagLookup = if (twinRoot != null) twin.findByViewId(twinRoot, genericId).size else -1
        log(
            "SURVEY_X08_A_B_SYSTEM_IDS withFlag_visibleIds=${ids.size} noFlag_visibleIds=${twinIds.size}" +
                " withFlag_lookupAndroidIdTitle=$withFlagLookup noFlag_lookupAndroidIdTitle=$noFlagLookup" +
                " twinEnabled=${twin != null}",
        )
    }

    // ==========================================================================================
    // The widget zoo
    // ==========================================================================================

    private fun buildZoo(scenario: String) {
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        /**
         * Every control gets an **explicit pixel height**.
         *
         * The first two runs put the scroll container at `bounds=[0,2356][1080,2356]` — zero pixels tall, pushed off
         * the bottom — because the widgets above it were measured with their theme defaults and consumed the whole
         * screen. The scroll, swipe and show-on-screen measurements then read `ret=false` and `visible=none`, which is
         * a fact about this fixture and not about the platform. Explicit heights make the viewport exist.
         */
        fun add(view: View, heightPx: Int) {
            column.addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, heightPx))
        }
        status = TextView(this).apply {
            textSize = 11f
            setPadding(16, 16, 16, 8)
        }
        add(status, 70)
        hitsView = TextView(this).apply {
            id = R.id.survey_hits
            text = "HITS=0 LC=0"
            textSize = 12f
        }
        add(hitsView, 50)

        // A clickable row whose *child label* carries the text: the T3b lesson, kept as a fixture.
        val row = LinearLayout(this).apply {
            id = R.id.survey_row_alpha
            isClickable = true
            isLongClickable = true
            setBackgroundColor(Color.LTGRAY)
            setOnClickListener {
                hits.incrementAndGet()
                hitsView.text = "HITS=${hits.get()} LC=${longHits.get()}"
            }
            setOnLongClickListener {
                longHits.incrementAndGet()
                hitsView.text = "HITS=${hits.get()} LC=${longHits.get()}"
                true
            }
        }
        row.addView(TextView(this).apply { text = LABEL_ROW })
        add(row, 110)

        add(
            Button(this).apply {
                id = R.id.survey_button
                text = LABEL_BUTTON
                setOnClickListener {
                    hits.incrementAndGet()
                    hitsView.text = "HITS=${hits.get()} LC=${longHits.get()}"
                }
            },
            110,
        )
        add(TextView(this).apply { id = R.id.survey_static; text = LABEL_STATIC }, 60)
        add(
            Button(this).apply {
                id = R.id.survey_disabled
                text = LABEL_DISABLED
                isClickable = true
                isEnabled = false
            },
            110,
        )
        edit = EditText(this).apply {
            id = R.id.survey_edit
            hint = LABEL_EDIT
            isFocusableInTouchMode = true
        }
        add(edit, 120)
        rejectEdit = RejectingEditText(this).apply {
            id = R.id.survey_edit_reject
            hint = LABEL_REJECT
        }
        add(rejectEdit, 120)
        check = CheckBox(this).apply { id = R.id.survey_check; text = LABEL_CHECK }
        add(check, 110)
        expandable = ExpandableProbeView(this).apply { id = R.id.survey_expand }
        add(expandable, 110)

        // The WebView is the most expensive thing in this fixture (its own process, its own a11y provider, its own
        // first paint). It is created **only** for the scenario that measures it: when it was created for every run,
        // this activity's main thread was still busy while the action battery read its own tree back, and the reads
        // returned partial hierarchies that looked like missing capability.
        if (scenario == "webview") {
            webView = WebView(this).apply {
                id = R.id.survey_webview
                settings.javaScriptEnabled = true
                addJavascriptInterface(WebProbeBridge(), "AndroidProbe")
                loadDataWithBaseURL(null, WEB_HTML, "text/html", "utf-8", null)
            }
            add(webView, 260)
        }

        // A container the platform is told to ignore, holding an important descendant.
        val notImportant = LinearLayout(this).apply {
            id = R.id.survey_noimportant
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        notImportant.addView(TextView(this).apply { text = LABEL_NESTED })
        add(notImportant, 60)

        // The scrollable list, the deep chain and the wide container.
        scroll = ScrollView(this).apply { id = R.id.survey_scroll }
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items = (0 until ITEM_COUNT).map { index ->
            TextView(this).apply {
                text = itemLabel(index)
                textSize = 16f
                if (index == 0) id = R.id.survey_item_first
                if (index == ITEM_COUNT - 1) id = R.id.survey_item_last
            }
        }
        items.forEach { content.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ITEM_HEIGHT_PX)) }

        var deep: ViewGroup = content
        repeat(DEEP_LEVELS) {
            val next = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            }
            deep.addView(next, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            deep = next
        }
        deep.addView(TextView(this).apply { id = R.id.survey_deep_label; text = LABEL_DEEP })

        val wide = LinearLayout(this).apply {
            id = R.id.survey_wide
            orientation = LinearLayout.VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        repeat(WIDE_CHILDREN) { index ->
            wide.addView(
                TextView(this).apply { text = "W%03d".format(index) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1),
            )
        }
        content.addView(wide, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, WIDE_CHILDREN))

        scroll.addView(content)
        column.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(column)
        // The survey measures SET_TEXT on an *unfocused* field, so nothing may be focused when it starts.
        column.isFocusableInTouchMode = true
        column.requestFocus()
        edit.clearFocus()
    }

    private fun labeledColumn(label: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(40, 40, 40, 40)
        addView(TextView(this@A11ySurveyActivity).apply { text = label })
    }

    // ==========================================================================================
    // Helpers
    // ==========================================================================================

    private fun log(line: String) = Log.i(A11ySurveyService.TAG, line)

    private fun sleep(millis: Long) = runCatching { Thread.sleep(millis) }

    private fun onUi(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
            return
        }
        val latch = CountDownLatch(1)
        runOnUiThread { runCatching { block() }; latch.countDown() }
        runCatching { latch.await(3, TimeUnit.SECONDS) }
    }

    private fun climbClickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        var current: AccessibilityNodeInfo? = node
        var depth = 0
        while (current != null && depth < 15) {
            if (current.isClickable) return current
            val parent = runCatching { current?.parent }.getOrNull()
            current = parent
            depth += 1
        }
        return null
    }

    private fun setTextArgs(text: String) = Bundle().apply {
        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
    }

    private fun setClipboard(text: String) {
        onUi {
            val manager = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            manager.setPrimaryClip(ClipData.newPlainText("probe", text))
        }
    }

    private fun showIme(): Boolean = runCatching {
        onUi {
            edit.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
        }
        true
    }.getOrDefault(false)

    private fun hideIme() = runCatching {
        onUi {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(edit.windowToken, 0)
        }
    }

    private fun hitCounts(): IntArray {
        val text = service?.textOfViewId("$packageName:id/survey_hits") ?: return intArrayOf(-1, -1)
        val match = HITS_REGEX.find(text) ?: return intArrayOf(-1, -1)
        return intArrayOf(match.groupValues[1].toIntOrNull() ?: -1, match.groupValues[2].toIntOrNull() ?: -1)
    }

    private fun awaitHits(minimum: Int, timeoutMs: Long): Int {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var current = hitCounts()[0]
        while (current < minimum && SystemClock.uptimeMillis() < deadline) {
            sleep(POLL_MS)
            current = hitCounts()[0]
        }
        return current
    }

    private fun awaitLongHits(minimum: Int, timeoutMs: Long): Int {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var current = hitCounts()[1]
        while (current < minimum && SystemClock.uptimeMillis() < deadline) {
            sleep(POLL_MS)
            current = hitCounts()[1]
        }
        return current
    }

    /** `first-last` of the item labels currently visible, or `none` — indexes only, never the labels themselves. */
    private fun visibleItemRange(s: A11ySurveyService): String {
        val root = s.activeWindowRoot() ?: return "none"
        val indexes = s.visibleLabels(root).mapNotNull { label ->
            ITEM_REGEX.find(label)?.groupValues?.get(1)?.toIntOrNull()
        }
        if (indexes.isEmpty()) return "none"
        return "${indexes.min()}-${indexes.max()}"
    }

    private fun scrollToTop(s: A11ySurveyService) {
        var guard = 0
        while (guard < MAX_SCROLLS) {
            val from = visibleItemRange(s)
            s.perform(s.findNodeByViewId("$packageName:id/survey_scroll"), AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)
            sleep(SCROLL_SETTLE_MS)
            guard += 1
            if (visibleItemRange(s) == from) break
        }
    }

    /**
     * One tap, dispatched only while this app owns the foreground window.
     *
     * A dispatched gesture goes to whatever is on top at that point, and this is the shared acceptance device: refusing
     * to inject when another app is in front is what keeps a survey run from touching somebody else's run. The third
     * element of the returned triple is that guard's answer.
     */
    private fun gestureTap(s: A11ySurveyService, x: Int, y: Int): Triple<Boolean, Boolean, Boolean> {
        val own = s.activePackage() == packageName
        if (!own) return Triple(false, false, false)
        val path = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, TAP_MILLIS))
            .build()
        val completed = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(1); latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                completed.set(0); latch.countDown()
            }
        }
        val dispatched = runCatching { s.dispatchGesture(gesture, callback, null) }.getOrDefault(false)
        if (!dispatched) return Triple(false, false, true)
        runCatching { latch.await(2, TimeUnit.SECONDS) }
        return Triple(true, completed.get() == 1, true)
    }

    /** Two sequential taps inside **one** gesture description: the multi-step case. */
    private fun gestureTapTwice(s: A11ySurveyService, x: Int, y: Int): Triple<Boolean, Boolean, Boolean> {
        val own = s.activePackage() == packageName
        if (!own) return Triple(false, false, false)
        val first = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 0L, TAP_MILLIS,
        )
        val second = GestureDescription.StrokeDescription(
            Path().apply { moveTo(x.toFloat(), y.toFloat()) }, 200L, TAP_MILLIS,
        )
        val gesture = GestureDescription.Builder().addStroke(first).addStroke(second).build()
        val completed = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(1); latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                completed.set(0); latch.countDown()
            }
        }
        val dispatched = runCatching { s.dispatchGesture(gesture, callback, null) }.getOrDefault(false)
        if (!dispatched) return Triple(false, false, true)
        runCatching { latch.await(3, TimeUnit.SECONDS) }
        return Triple(true, completed.get() == 1, true)
    }

    private fun gestureSwipe(s: A11ySurveyService, rect: Rect): Triple<Boolean, Boolean, Boolean> {
        val own = s.activePackage() == packageName
        if (!own) return Triple(false, false, false)
        val fromY = rect.top + (rect.height() * 0.8f)
        val toY = rect.top + (rect.height() * 0.2f)
        val path = Path().apply {
            moveTo(rect.exactCenterX(), fromY)
            lineTo(rect.exactCenterX(), toY)
        }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0L, SWIPE_MILLIS))
            .build()
        val completed = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val callback = object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) {
                completed.set(1); latch.countDown()
            }

            override fun onCancelled(gestureDescription: GestureDescription?) {
                completed.set(0); latch.countDown()
            }
        }
        val dispatched = runCatching { s.dispatchGesture(gesture, callback, null) }.getOrDefault(false)
        if (!dispatched) return Triple(false, false, true)
        runCatching { latch.await(3, TimeUnit.SECONDS) }
        return Triple(true, completed.get() == 1, true)
    }

    /** A bridge the page calls, so a WebView click is verified in the page and reported as a count. */
    inner class WebProbeBridge {
        @JavascriptInterface
        fun onClicked() {
            webClicks.incrementAndGet()
        }
    }

    // ==========================================================================================
    // The two "non-standard" fixtures
    // ==========================================================================================

    /**
     * A field that **accepts** `ACTION_SET_TEXT` and keeps its own text.
     *
     * This is the survey's controlled model of a chat composer that owns its state (WeChat's is the one the survey was
     * asked about and is not installed on this device): the platform hands the text over, the view chooses what to do
     * with it, and the accessibility action still reports success. It exists to make "the action returned true" and
     * "the text changed" separable *on this device*, which is the difference the backend's `NO_SCREEN_CHANGE` refusal
     * exists for. Its counters are read through the accessibility tree, never logged as text.
     *
     * The interception is on `performAccessibilityAction`, **not** on `setText`: the first version overrode `setText`
     * and the probe process died on startup, because `TextView`'s own constructor calls the overridable `setText`
     * before any Kotlin property initializer has run and the counter was still null. The counters are static for the
     * same reason — a field of an instance that does not exist yet cannot be incremented.
     */
    class RejectingEditText(context: Context) : EditText(context) {

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
            if (action == AccessibilityNodeInfo.ACTION_SET_TEXT) {
                setTextAttempts.incrementAndGet()
                return true
            }
            return super.performAccessibilityAction(action, arguments)
        }

        override fun onTextContextMenuItem(id: Int): Boolean {
            if (id == android.R.id.paste) {
                pasteAttempts.incrementAndGet()
                return false
            }
            return super.onTextContextMenuItem(id)
        }

        companion object {
            val setTextAttempts = AtomicInteger(0)
            val pasteAttempts = AtomicInteger(0)
        }
    }

    /**
     * A view that advertises `ACTION_EXPAND`/`ACTION_COLLAPSE` and reflects its state in its
     * `contentDescription`, so the state can be read back **through the accessibility tree** rather than from the view.
     */
    class ExpandableProbeView(context: Context) : View(context) {
        private var expanded = false

        init {
            isClickable = true
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
            setBackgroundColor(Color.CYAN)
            updateDescription()
        }

        override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
            super.onInitializeAccessibilityNodeInfo(info)
            info.className = "android.widget.Button"
            info.isClickable = true
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND)
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE)
        }

        override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean = when (action) {
            AccessibilityNodeInfo.ACTION_EXPAND -> {
                expanded = true; updateDescription(); true
            }
            AccessibilityNodeInfo.ACTION_COLLAPSE -> {
                expanded = false; updateDescription(); true
            }
            else -> super.performAccessibilityAction(action, arguments)
        }

        private fun updateDescription() {
            contentDescription = if (expanded) LABEL_EXPAND_OPEN else LABEL_EXPAND_SHUT
        }
    }

    companion object {
        const val ACTION = "edith.phone.a11yprobe.intent.SURVEY"
        const val EXTRA_CONFIRM = "confirm"
        const val EXTRA_SCENARIO = "scenario"

        /** Probe-authored labels. Nothing in this list comes from the device or from the user. */
        const val LABEL_ROW = "ROWALPHA"
        const val LABEL_BUTTON = "BTNPROBE"
        const val LABEL_STATIC = "STATICPROBE"
        const val LABEL_DISABLED = "DISABLEDPROBE"
        const val LABEL_EDIT = "EDITPROBE"
        const val LABEL_REJECT = "REJECTPROBE"
        const val LABEL_CHECK = "CHECKPROBE"
        const val LABEL_EXPAND_SHUT = "EXPANDPROBE_SHUT"
        const val LABEL_EXPAND_OPEN = "EXPANDPROBE_OPEN"
        const val LABEL_DEEP = "DEEPLABEL"
        const val LABEL_NESTED = "NESTEDPROBE"
        const val LABEL_OVERLAY = "OVERLAYTEXT"
        const val LABEL_SECURE = "SECURETEXT"
        const val LABEL_SECURE_VALUE = "SECUREVALUE"
        const val LABEL_NORMAL = "NORMALTEXT"
        const val LABEL_ABSENT = "ZZZNOSUCHLABEL"
        const val LABEL_WEB = "WEBFIELD"
        const val LABEL_WEB_LINK = "WEBLINK"

        const val PROBE_TEXT = "probe-alpha"
        const val PROBE_CLIP = "probe-clip-1"

        /**
         * A label of the **system** app the cross-app read targets. It is a probe constant — a string this package
         * chose to look for — and the survey reports only how many nodes matched it, never what they said.
         */
        const val SYSTEM_QUERY = "WLAN"
        const val SYSTEM_QUERY_SHORTER = "WLA"

        const val ITEM_COUNT = 60
        const val ITEM_HEIGHT_PX = 90
        const val WIDE_CHILDREN = 120
        const val DEEP_LEVELS = 50

        private const val TAP_MILLIS = 60L
        private const val SWIPE_MILLIS = 300L
        private const val SETTLE_MS = 600L
        private const val SCROLL_SETTLE_MS = 320L
        private const val MAX_SCROLLS = 40
        private const val QUIET_THRESHOLD_MS = 250L
        private const val WEB_TIMEOUT_MS = 8_000L

        /**
         * How often a state read-back is polled.
         *
         * Every read travels to the target app's main thread, and when the target is this same app that main thread is
         * also the one laying the fixture out: a tight poll starves it and the read reports a partial tree. 100 ms is
         * slow enough to leave the main thread alone and fast enough that a 600 ms budget still gets several samples.
         */
        private const val POLL_MS = 100L

        private val HITS_REGEX = Regex("HITS=(-?\\d+) LC=(-?\\d+)")
        private val ITEM_REGEX = Regex("ITEM(\\d{2})")

        fun itemLabel(index: Int) = "ITEM%02d".format(index)

        /** A page whose input is a **virtual** node the app does not own, plus a link that calls back into the probe. */
        val WEB_HTML = """
            <html><body style="font-size:28px">
            <input id="f" type="text" value="" />
            <a href="#" id="l" onclick="AndroidProbe.onClicked();return false;">$LABEL_WEB_LINK</a>
            </body></html>
        """.trimIndent()
    }
}
