package com.kokeshi.metersnap

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

class TutorialOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val dimPaint = Paint().apply {
        color = 0xCC000000.toInt()
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private var highlightRect: RectF? = null
    private var highlightRadius = 0f
    private var currentStep = 0
    private var onTutorialCompleted: (() -> Unit)? = null

    private val tvCounter: TextView
    private val tvDescription: TextView
    private val btnNext: TextView
    private val card: LinearLayout
    private val cardLayoutParams: LayoutParams

    // ── Step order ──────────────────────────────────────────────────────────
    // 1. Guide box  — show the camera live, explain framing
    // 2. Snap       — explain how to capture
    // 3. Auto-calc  — explain what happens after snap (no specific highlight)
    // 4. Manual     — fallback for unclear reads
    // 5. Load Image — gallery alternative
    // 6. Settings   — rate table config
    // ────────────────────────────────────────────────────────────────────────
    private val steps = listOf(
        TutorialStep(
            target = StepTarget.GUIDE_BOX,
            text = "Place the meter inside the guide box. Get up close so the digits are sharp and clear."
        ),
        TutorialStep(
            target = StepTarget.SNAP,
            text = "Once the meter is in frame, tap Snap to capture and read the digits."
        ),
        TutorialStep(
            target = StepTarget.AUTO_CALC,
            text = "After snapping, the app automatically calculates your water bill for you."
        ),
        TutorialStep(
            target = StepTarget.MANUAL_INPUT,
            text = "Camera can't get a clear read? Use Manual Input to type the digits in yourself."
        ),
        TutorialStep(
            target = StepTarget.LOAD_IMAGE,
            text = "Prefer using a saved photo? Tap Load Image to scan a picture from your gallery."
        ),
        TutorialStep(
            target = StepTarget.SETTINGS,
            text = "Open Settings to update your billing rates and rate table configuration."
        )
    )

    init {
        setWillNotDraw(false)
        elevation = 200f
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = context.getDrawable(android.R.drawable.dialog_holo_dark_frame)
            isClickable = true
            isFocusable = true
            setPadding(dp(20), dp(16), dp(20), dp(14))
        }

        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        headerRow.addView(TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
            text = "METEREAD Guide"
        })
        tvCounter = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            textSize = 13f
            setTextColor(0xFFAAAAAA.toInt())
        }
        headerRow.addView(tvCounter)
        card.addView(headerRow)

        card.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(1)
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(12)
            }
            setBackgroundColor(0x33FFFFFF)
        })

        tvDescription = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(14) }
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setLineSpacing(0f, 1.3f)
        }
        card.addView(tvDescription)

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            gravity = Gravity.CENTER_VERTICAL
        }
        val btnSkip = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = "Skip"
            textSize = 14f
            setTextColor(0x99FFFFFF.toInt())
            setPadding(0, dp(8), dp(12), dp(8))
            isClickable = true
            isFocusable = true
            setOnClickListener { completeTutorial() }
        }
        btnNext = TextView(context).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            textSize = 15f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = context.getDrawable(android.R.drawable.dialog_holo_dark_frame)
            isClickable = true
            isFocusable = true
            setOnClickListener { advanceStep() }
        }
        btnRow.addView(btnSkip)
        btnRow.addView(View(context).apply {
            layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
        })
        btnRow.addView(btnNext)
        card.addView(btnRow)

        // Card always anchored to bottom — distance varies per step (see positionCardForStep)
        cardLayoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(148)
        }
        addView(card, cardLayoutParams)
    }

    fun startTutorial(onCompleted: () -> Unit) {
        onTutorialCompleted = onCompleted
        currentStep = 0
        alpha = 0f
        visibility = View.VISIBLE
        bringToFront()
        animate().alpha(1f).setDuration(250).start()
        post { showStep(0) }
    }

    private fun showStep(index: Int) {
        if (index >= steps.size) {
            completeTutorial()
            return
        }

        val step = steps[index]
        val isLast = index == steps.size - 1

        tvCounter.text = "${index + 1} / ${steps.size}"
        tvDescription.text = step.text
        btnNext.text = if (isLast) "Got it" else "Next"
        positionCardForStep(step.target)

        val parentView = parent as? ViewGroup
        dimAll(parentView)

        when (step.target) {
            StepTarget.GUIDE_BOX    -> showGuideBoxHighlight(parentView)
            StepTarget.SNAP         -> highlightBottomBarGroup(parentView, 1)
            StepTarget.AUTO_CALC    -> { highlightRect = null; invalidate() }
            StepTarget.MANUAL_INPUT -> highlightBottomBarGroup(parentView, 2)
            StepTarget.LOAD_IMAGE   -> highlightViewById(parentView, R.id.btn_load_image)
            StepTarget.SETTINGS     -> highlightBottomBarGroup(parentView, 0)
        }

        tvDescription.alpha = 0f
        tvDescription.animate().alpha(1f).setDuration(200).start()
    }

    private fun advanceStep() {
        currentStep++
        if (currentStep < steps.size) showStep(currentStep) else completeTutorial()
    }

    /**
     * Card is always at the bottom.
     *
     * • Bottom-bar buttons (Snap, Manual, Settings): card sits just above the bar (148 dp).
     * • Load Image floats ~142–182 dp from screen bottom: push card higher (200 dp) so it
     *   doesn't cover the button.
     * • Guide box / auto-calc: same default (148 dp) — card lives below the camera area.
     */
    private fun positionCardForStep(target: StepTarget) {
        cardLayoutParams.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        cardLayoutParams.topMargin = 0
        cardLayoutParams.bottomMargin = when (target) {
            StepTarget.GUIDE_BOX  -> dp(0)   // flush to bottom — guide box stays fully clear
            StepTarget.LOAD_IMAGE -> dp(200) // above the floating Load Image button
            else                  -> dp(148) // just above the bottom bar
        }
        card.layoutParams = cardLayoutParams
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP) {
            val hl = highlightRect
            if (hl != null && hl.contains(event.x, event.y)) {
                advanceStep()
            }
        }
        return true
    }

    private fun dimAll(parent: ViewGroup?) {
        parent ?: return
        val bottomBar = parent.findViewById<LinearLayout>(R.id.bottom_bar)
        bottomBar?.let { for (i in 0 until it.childCount) it.getChildAt(i)?.alpha = 0.25f }
        parent.findViewById<View>(R.id.top_bar)?.alpha = 0.25f
        parent.findViewById<View>(R.id.tv_instructions)?.alpha = 0.25f
        parent.findViewById<View>(R.id.btn_load_image)?.alpha = 0.25f
        parent.findViewById<View>(R.id.zoom_toggle)?.alpha = 0.25f
    }

    private fun restoreAll(parent: ViewGroup?) {
        parent ?: return
        val bottomBar = parent.findViewById<LinearLayout>(R.id.bottom_bar)
        bottomBar?.let { for (i in 0 until it.childCount) it.getChildAt(i)?.alpha = 1f }
        parent.findViewById<View>(R.id.top_bar)?.alpha = 1f
        parent.findViewById<View>(R.id.tv_instructions)?.alpha = 1f
        parent.findViewById<View>(R.id.btn_load_image)?.alpha = 1f
        parent.findViewById<View>(R.id.zoom_toggle)?.alpha = 1f
        highlightRect = null
        invalidate()
    }

    private fun highlightBottomBarGroup(parent: ViewGroup?, index: Int) {
        parent ?: return
        val bottomBar = parent.findViewById<LinearLayout>(R.id.bottom_bar) ?: return
        if (index !in 0 until bottomBar.childCount) return
        val group = bottomBar.getChildAt(index) ?: return
        group.alpha = 1f

        post {
            val groupLoc = IntArray(2)
            group.getLocationOnScreen(groupLoc)
            val overlayLoc = IntArray(2)
            getLocationOnScreen(overlayLoc)
            val pad = dp(10).toFloat()
            highlightRect = RectF(
                groupLoc[0] - overlayLoc[0] - pad,
                groupLoc[1] - overlayLoc[1] - pad,
                groupLoc[0] - overlayLoc[0] + group.width + pad,
                groupLoc[1] - overlayLoc[1] + group.height + pad
            )
            highlightRadius = dp(16).toFloat()
            invalidate()
        }
    }

    private fun highlightViewById(parent: ViewGroup?, viewId: Int) {
        parent ?: return
        val target = parent.findViewById<View>(viewId) ?: return
        target.alpha = 1f

        post {
            val targetLoc = IntArray(2)
            target.getLocationOnScreen(targetLoc)
            val overlayLoc = IntArray(2)
            getLocationOnScreen(overlayLoc)
            val pad = dp(12).toFloat()
            highlightRect = RectF(
                targetLoc[0] - overlayLoc[0] - pad,
                targetLoc[1] - overlayLoc[1] - pad,
                targetLoc[0] - overlayLoc[0] + target.width + pad,
                targetLoc[1] - overlayLoc[1] + target.height + pad
            )
            highlightRadius = dp(20).toFloat()
            invalidate()
        }
    }

    private fun showGuideBoxHighlight(parent: ViewGroup?) {
        parent ?: return
        val guideBox = parent.findViewById<MeterGuideBoxView>(R.id.meter_guide_box) ?: return
        // Also light up the zoom toggle so it's visible alongside the guide box
        parent.findViewById<View>(R.id.zoom_toggle)?.alpha = 1f

        post {
            val guideRect = guideBox.getGuideBoxRect()
            val guideLoc = IntArray(2)
            guideBox.getLocationOnScreen(guideLoc)
            val overlayLoc = IntArray(2)
            getLocationOnScreen(overlayLoc)
            val pad = dp(12).toFloat()
            highlightRect = RectF(
                guideLoc[0] - overlayLoc[0] + guideRect.left - pad,
                guideLoc[1] - overlayLoc[1] + guideRect.top - pad,
                guideLoc[0] - overlayLoc[0] + guideRect.right + pad,
                guideLoc[1] - overlayLoc[1] + guideRect.bottom + pad
            )
            highlightRadius = dp(12).toFloat()
            invalidate()
        }
    }

    private fun completeTutorial() {
        restoreAll(parent as? ViewGroup)
        animate()
            .alpha(0f)
            .setDuration(250)
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    visibility = View.GONE
                    onTutorialCompleted?.invoke()
                }
            })
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        highlightRect?.let { rect ->
            canvas.drawRoundRect(rect, highlightRadius, highlightRadius, clearPaint)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class TutorialStep(
        val target: StepTarget,
        val text: String
    )

    private enum class StepTarget {
        GUIDE_BOX,
        SNAP,
        AUTO_CALC,
        MANUAL_INPUT,
        LOAD_IMAGE,
        SETTINGS
    }
}
