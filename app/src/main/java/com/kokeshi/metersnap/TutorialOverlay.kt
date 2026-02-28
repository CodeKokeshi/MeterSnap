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

    private val tutorialText: TextView
    private var currentStep = 0
    private var onTutorialCompleted: (() -> Unit)? = null

    private val steps = listOf(
        TutorialStep(
            target = StepTarget.MANUAL_INPUT,
            text = "Manual Input lets you type current and previous readings directly when camera scan is not needed."
        ),
        TutorialStep(
            target = StepTarget.LOAD_IMAGE,
            text = "Load Image lets you pick a meter photo from your gallery for reading analysis."
        ),
        TutorialStep(
            target = StepTarget.SETTINGS,
            text = "Settings is where you manage previous reading, rates, history, and projections."
        ),
        TutorialStep(
            target = StepTarget.CAMERA,
            text = "Place your meter digits inside this guide box, keep it steady, and let MeterSnap read it."
        )
    )

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true
        setLayerType(LAYER_TYPE_SOFTWARE, null)

        tutorialText = TextView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(24)
                marginEnd = dp(24)
                gravity = android.view.Gravity.CENTER_HORIZONTAL
            }
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = context.getDrawable(android.R.drawable.dialog_holo_dark_frame)
            elevation = 12f
        }
        addView(tutorialText)

        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                nextStep()
            }
            true
        }
    }

    fun startTutorial(onCompleted: () -> Unit) {
        onTutorialCompleted = onCompleted
        currentStep = 0
        alpha = 0f
        visibility = View.VISIBLE

        animate()
            .alpha(1f)
            .setDuration(250)
            .start()

        post { showStep(0) }
    }

    private fun showStep(step: Int) {
        if (step >= steps.size) {
            completeTutorial()
            return
        }

        val tutorialStep = steps[step]
        tutorialText.text = tutorialStep.text

        when (tutorialStep.target) {
            StepTarget.MANUAL_INPUT -> showButtonStep(R.id.btn_manual_input)
            StepTarget.LOAD_IMAGE -> showButtonStep(R.id.btn_snap)
            StepTarget.SETTINGS -> showButtonStep(R.id.btn_settings)
            StepTarget.CAMERA -> showCameraStep()
        }

        tutorialText.alpha = 0f
        tutorialText.animate()
            .alpha(1f)
            .setDuration(200)
            .start()
    }

    private fun showButtonStep(targetButtonId: Int) {
        val parent = parent as? ViewGroup ?: return

        val settingsGroup = getBottomBarGroup(parent, 0)
        val loadGroup = getBottomBarGroup(parent, 1)
        val manualGroup = getBottomBarGroup(parent, 2)

        settingsGroup?.alpha = if (targetButtonId == R.id.btn_settings) 1f else 0.25f
        loadGroup?.alpha = if (targetButtonId == R.id.btn_snap) 1f else 0.25f
        manualGroup?.alpha = if (targetButtonId == R.id.btn_manual_input) 1f else 0.25f

        parent.findViewById<View>(R.id.top_bar)?.alpha = 0.25f
        parent.findViewById<View>(R.id.btn_torch)?.alpha = 0.25f
        parent.findViewById<View>(R.id.guide_box)?.alpha = 0.35f
        parent.findViewById<View>(R.id.scan_line)?.alpha = 0.25f
        parent.findViewById<View>(R.id.tv_instructions)?.alpha = 0.35f

        highlightRect = null
        invalidate()

        tutorialText.post {
            val params = tutorialText.layoutParams as LayoutParams
            params.gravity = android.view.Gravity.CENTER
            params.topMargin = 0
            tutorialText.layoutParams = params
        }
    }

    private fun showCameraStep() {
        val parent = parent as? ViewGroup ?: return
        val guideBox = parent.findViewById<View>(R.id.guide_box) ?: return

        getBottomBarGroup(parent, 0)?.alpha = 0.25f
        getBottomBarGroup(parent, 1)?.alpha = 0.25f
        getBottomBarGroup(parent, 2)?.alpha = 0.25f
        parent.findViewById<View>(R.id.top_bar)?.alpha = 0.25f
        parent.findViewById<View>(R.id.btn_torch)?.alpha = 0.25f
        parent.findViewById<View>(R.id.scan_line)?.alpha = 1f
        parent.findViewById<View>(R.id.tv_instructions)?.alpha = 0.25f

        val padding = dp(8).toFloat()
        highlightRect = RectF(
            guideBox.x - padding,
            guideBox.y - padding,
            guideBox.x + guideBox.width + padding,
            guideBox.y + guideBox.height + padding
        )
        highlightRadius = dp(12).toFloat()
        invalidate()

        tutorialText.post {
            val params = tutorialText.layoutParams as LayoutParams
            params.gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            params.topMargin = (guideBox.y + guideBox.height + dp(14)).toInt()
            tutorialText.layoutParams = params
        }
    }

    private fun getBottomBarGroup(parent: ViewGroup, index: Int): LinearLayout? {
        val bottomBar = parent.findViewById<LinearLayout>(R.id.bottom_bar) ?: return null
        if (index < 0 || index >= bottomBar.childCount) return null
        return bottomBar.getChildAt(index) as? LinearLayout
    }

    private fun nextStep() {
        currentStep++
        if (currentStep < steps.size) {
            showStep(currentStep)
        } else {
            completeTutorial()
        }
    }

    private fun completeTutorial() {
        restoreUiAlpha()

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

    private fun restoreUiAlpha() {
        val parent = parent as? ViewGroup ?: return

        getBottomBarGroup(parent, 0)?.alpha = 1f
        getBottomBarGroup(parent, 1)?.alpha = 1f
        getBottomBarGroup(parent, 2)?.alpha = 1f
        parent.findViewById<View>(R.id.top_bar)?.alpha = 1f
        parent.findViewById<View>(R.id.btn_torch)?.alpha = 1f
        parent.findViewById<View>(R.id.guide_box)?.alpha = 1f
        parent.findViewById<View>(R.id.scan_line)?.alpha = 1f
        parent.findViewById<View>(R.id.tv_instructions)?.alpha = 1f

        highlightRect = null
        invalidate()
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
        MANUAL_INPUT,
        LOAD_IMAGE,
        SETTINGS,
        CAMERA
    }
}
