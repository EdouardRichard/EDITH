package com.sheen.rokid.glasses.display

import android.content.Context
import android.graphics.Color
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.sheen.rokid.output.OfficialConversation

/**
 * 0.24 S2 (Task 7): the official panel's view tree, extracted from `GlassesActivity.buildLayout()` unchanged.
 *
 * The official panel (0.23 P3, spec §5.1): a bottom-anchored 380 px black panel on the 480x640 canvas.
 *
 * Relative geometry follows `dialog_page_ai.xml`: the conversation window sits between two 30 px fades, the fixed
 * listening line and the 55x45 orb share the bottom state row, the two operating tips sit 70 px above the panel's
 * bottom edge, and the red compliance label is pinned to the bottom-right of the window.
 *
 * Built in code on purpose - a `TextView` needs no resources, and a resource file would add a build surface that
 * nothing here needs. This class owns construction only: the Activity still builds the dynamic conversation rows,
 * decides every visibility/copy value, and owns the key handling. The two setters below are how those rows are handed
 * back, so this view always refers to the row the Activity actually put on screen.
 */
class GlassesPanelView(context: Context) : FrameLayout(context) {

    val entriesView: LinearLayout
    val hintScroll: ScrollView
    val listeningView: TextView

    /** The official bottom cluster's voice orb: the PAG view (0.24 S4 Task 18), with its own VoiceOrbView fallback. */
    val orbView: PagOrbView

    val tipsView: TextView
    val pinTextView: TextView
    val pinIconView: PinIconView
    val complianceView: TextView

    /** The two official 30 px fades above/below the conversation window. */
    val topMask: GradientMaskView
    val bottomMask: GradientMaskView

    /** The thinking row currently in the list, so it can be hidden with the panel; null when not thinking. */
    var thinkingRow: View? = null
        private set

    /** The tool row currently in the list (0.24 S4 Task 17); null when the panel is not showing one. */
    var toolRow: View? = null
        private set

    /** The row the live hint occupies, so the layout report and the probe read the hint rather than the list. */
    var liveRow: TextView? = null
        private set

    init {
        setBackgroundColor(Color.TRANSPARENT)

        val panel = FrameLayout(context).apply {
            setBackgroundColor(GlassesPanelSpec.BLACK)
            setPadding(0, 0, 0, GlassesPanelSpec.PANEL_BOTTOM_PADDING_PX)
        }
        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        // P4: no route header. The official panel has no label, and the view follows `SHOW` alone (U8).
        entriesView = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            // Phase C C1: nothing may paint the panel except the text. A child view would otherwise sit on the
            // theme's window background, and on the waveguide that non-black fill is the green cast to remove.
            setBackgroundColor(Color.TRANSPARENT)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
        }
        hintScroll = ScrollView(context).apply {
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = true
            isFillViewport = false
            setBackgroundColor(Color.TRANSPARENT)
            isFocusable = false
            defaultFocusHighlightEnabled = false
            // 0.24 S4 Task 19: the official item insets (ASR margin 10 px, TTS padding 10 px) live on the rows now, so
            // the scroll view must not add another 10 px per side - that would double every row's official inset.
            setPadding(0, GlassesPanelSpec.LIST_PADDING_TOP_PX,
                0, GlassesPanelSpec.LIST_PADDING_BOTTOM_PX)
            addView(entriesView)
            // The bottom fade follows the scroll: visible while there is more below, gone at the bottom (official
            // `id_iv_recycler_bottom_mask` is gone by default for the same reason). The listener itself stays in
            // `GlassesActivity`: it is the render decision `updateScrollChrome`, not view construction.
        }
        topMask = GradientMaskView(context, MaskEdge.TOP)
        bottomMask = GradientMaskView(context, MaskEdge.BOTTOM)
        complianceView = TextView(context).apply {
            setTextColor(GlassesPanelSpec.ALERT_RED)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, GlassesPanelSpec.COMPLIANCE_TEXT_SIZE_PX)
            gravity = Gravity.END
            text = GlassesPanelSpec.complianceText()
        }
        val listContainer = FrameLayout(context).apply {
            addView(hintScroll, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(topMask, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, GlassesPanelSpec.MASK_HEIGHT_PX, Gravity.TOP))
            addView(bottomMask, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, GlassesPanelSpec.MASK_HEIGHT_PX, Gravity.BOTTOM))
            addView(complianceView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = GlassesPanelSpec.COMPLIANCE_MARGIN_END_PX
                bottomMargin = GlassesPanelSpec.COMPLIANCE_MARGIN_BOTTOM_PX
            })
        }

        listeningView = TextView(context).apply {
            setTextColor(GlassesPanelSpec.GREEN_NORMAL)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, GlassesPanelSpec.LISTENING_TEXT_SIZE_PX)
            gravity = Gravity.CENTER
            text = GlassesPanelSpec.LISTENING_TEXT
            visibility = View.GONE
        }
        orbView = PagOrbView(context)
        val stateRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(listeningView, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = GlassesPanelSpec.LISTENING_MARGIN_TOP_PX
            })
            addView(orbView, LinearLayout.LayoutParams(
                GlassesPanelSpec.VOICE_ORB_WIDTH_PX, GlassesPanelSpec.VOICE_ORB_HEIGHT_PX))
        }

        tipsView = TextView(context).apply {
            setTextColor(GlassesPanelSpec.GREEN_40)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, GlassesPanelSpec.TIPS_TEXT_SIZE_PX)
            gravity = Gravity.START
            text = OfficialConversation.TIPS_EXIT
        }
        pinTextView = TextView(context).apply {
            setTextColor(GlassesPanelSpec.GREEN_40)
            setTextSize(TypedValue.COMPLEX_UNIT_PX, GlassesPanelSpec.TIPS_TEXT_SIZE_PX)
            gravity = Gravity.END
            text = GlassesPanelSpec.PIN_TEXT
        }
        pinIconView = PinIconView(context).apply { visibility = View.GONE }
        val tipsRow = FrameLayout(context).apply {
            addView(tipsView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.START or Gravity.CENTER_VERTICAL).apply {
                marginStart = GlassesPanelSpec.TIPS_MARGIN_START_PX
            })
            addView(pinTextView, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = GlassesPanelSpec.TIPS_MARGIN_END_PX
            })
            addView(pinIconView, FrameLayout.LayoutParams(
                GlassesPanelSpec.PIN_ICON_PX, GlassesPanelSpec.PIN_ICON_PX,
                Gravity.END or Gravity.CENTER_VERTICAL).apply {
                marginEnd = GlassesPanelSpec.TIPS_MARGIN_END_PX
            })
        }

        column.addView(listContainer, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        column.addView(stateRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = GlassesPanelSpec.STATE_MARGIN_BOTTOM_PX
        })
        column.addView(tipsRow, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = GlassesPanelSpec.TIPS_BOTTOM_MARGIN_PX
        })
        panel.addView(column, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        addView(panel, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, GlassesPanelSpec.PANEL_HEIGHT_PX, Gravity.BOTTOM))
    }

    /** The thinking row currently in the list; null when the list has none. The same object the Activity added. */
    fun setThinkingRow(row: View?) {
        thinkingRow = row
    }

    /** The tool row currently in the list; null when the list has none. The same object the Activity added. */
    fun setToolRow(row: View?) {
        toolRow = row
    }

    /** The row the live hint occupies; null when nothing is live. The same object the Activity added. */
    fun setLiveRow(row: TextView?) {
        liveRow = row
    }
}
