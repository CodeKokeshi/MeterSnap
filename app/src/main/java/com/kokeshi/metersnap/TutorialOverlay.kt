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
import android.widget.ImageView
import android.widget.TextView
import androidx.core.content.ContextCompat

class TutorialOverlay @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val dimPaint = Paint().apply {
        color = 0xCC000000.toInt() // Semi-transparent black
        style = Paint.Style.FILL
    }

    private val clearPaint = Paint().apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }

    private var highlightRect: RectF? = null
    private var highlightRadius = 0f

    private val tutorialText: TextView
    private val arrowImage: ImageView
    private var currentStep = 0
    private var onTutorialCompleted: (() -> Unit)? = null

    private val steps = listOf(
        TutorialStep(
            text = "Tap the manual input button to enter your meter reading directly, without using the camera scanner.",
            arrowDirection = ArrowDirection.DOWN_RIGHT
        ),
        TutorialStep(
            text = "Access settings to configure water rates, calculation methods, and customize your billing preferences.",
            arrowDirection = ArrowDirection.DOWN_LEFT
        ),
        TutorialStep(
            text = "Point your camera at the water meter and tap the capture button. The app will automatically read the numbers.",
            arrowDirection = ArrowDirection.UP
        )
    )

    init {
        setWillNotDraw(false)
        isClickable = true
        isFocusable = true

        // Tutorial text
        tutorialText = TextView(context).apply {
            layoutParams = LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 48
                marginEnd = 48
            }
            textSize = 18f
            setTextColor(0xFFFFFFFF.toInt())
            textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            setPadding(32, 24, 32, 24)
            background = ContextCompat.getDrawable(context, android.R.drawable.dialog_holo_dark_frame)
            elevation = 8f
        }
        addView(tutorialText)

        // Arrow indicator
        arrowImage = ImageView(context).apply {
            layoutParams = LayoutParams(120, 120)
            scaleType = ImageView.ScaleType.FIT_CENTER
            elevation = 8f
        }
        addView(arrowImage)

        // Start with first step - intercept all touch events
        setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                nextStep()
            }
            true  // Always consume the event to prevent buttons from being clicked
        }
    }

    fun startTutorial(onCompleted: () -> Unit) {
        this.onTutorialCompleted = onCompleted
        currentStep = 0
        alpha = 0f
        visibility = View.VISIBLE
        
        // Re-enable buttons before starting (in case they were disabled)
        val parent = parent as? ViewGroup
        parent?.findViewById<View>(R.id.btn_manual_input)?.apply {
            isClickable = true
            isFocusable = true
        }
        parent?.findViewById<View>(R.id.btn_settings)?.apply {
            isClickable = true
            isFocusable = true
        }
        
        animate()
            .alpha(1f)
            .setDuration(300)
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

        // Position text and arrow based on step
        when (step) {
            0 -> highlightManualInput()
            1 -> highlightSettings()
            2 -> highlightScanner()
        }

        // Animate text appearance
        tutorialText.alpha = 0f
        tutorialText.animate()
            .alpha(1f)
            .setDuration(300)
            .start()

        arrowImage.alpha = 0f
        arrowImage.animate()
            .alpha(1f)
            .setDuration(300)
            .start()
    }

    private fun highlightManualInput() {
        val parent = parent as? ViewGroup ?: return
        val button = parent.findViewById<View>(R.id.btn_manual_input) ?: return
        
        // Bring button to front so it's visible but disable it
        button.bringToFront()
        button.isClickable = false
        button.isFocusable = false
        
        highlightView(button)
        
        // Position arrow pointing to the button
        arrowImage.setImageResource(R.drawable.ic_arrow_down_right)
        arrowImage.post {
            val x = button.x - arrowImage.width - 20
            val y = button.y - arrowImage.height - 40
            arrowImage.x = x
            arrowImage.y = y
        }

        // Position text above the highlighted area (higher)
        tutorialText.post {
            val params = tutorialText.layoutParams as LayoutParams
            params.topMargin = (button.y - tutorialText.height - 250).toInt()
            tutorialText.layoutParams = params
        }
    }

    private fun highlightSettings() {
        val parent = parent as? ViewGroup ?: return
        val button = parent.findViewById<View>(R.id.btn_settings) ?: return
        
        // Bring button to front so it's visible but disable it
        button.bringToFront()
        button.isClickable = false
        button.isFocusable = false
        
        highlightView(button)
        
        // Position arrow pointing to the button
        arrowImage.setImageResource(R.drawable.ic_arrow_down_left)
        arrowImage.post {
            val x = button.x + button.width + 20
            val y = button.y - arrowImage.height - 80
            arrowImage.x = x
            arrowImage.y = y
        }

        // Position text above the highlighted area (higher)
        tutorialText.post {
            val params = tutorialText.layoutParams as LayoutParams
            params.topMargin = (button.y - tutorialText.height - 250).toInt()
            tutorialText.layoutParams = params
        }
    }

    private fun highlightScanner() {
        val parent = parent as? ViewGroup ?: return
        val guideBox = parent.findViewById<View>(R.id.guide_box) ?: return
        val scanLine = parent.findViewById<View>(R.id.scan_line) ?: return
        
        // Bring guide box and scan line to front so they're visible
        guideBox.bringToFront()
        scanLine.bringToFront()
        
        highlightView(guideBox)
        
        // Position arrow pointing down to the guide box
        arrowImage.setImageResource(R.drawable.ic_arrow_up)
        arrowImage.post {
            val x = guideBox.x + (guideBox.width - arrowImage.width) / 2
            val y = guideBox.y - arrowImage.height - 40
            arrowImage.x = x
            arrowImage.y = y
        }

        // Position text above the arrow (higher)
        tutorialText.post {
            val params = tutorialText.layoutParams as LayoutParams
            params.topMargin = (arrowImage.y - tutorialText.height - 40).toInt()
            tutorialText.layoutParams = params
        }
    }

    private fun highlightView(view: View) {
        val location = IntArray(2)
        view.getLocationInWindow(location)
        
        val padding = 40f
        val verticalOffset = -80f  // Move highlight upward
        highlightRect = RectF(
            view.x - padding,
            view.y - padding + verticalOffset,
            view.x + view.width + padding,
            view.y + view.height + padding + verticalOffset
        )
        highlightRadius = Math.max(view.width, view.height) / 2f + padding
        
        invalidate()
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
        // Re-enable buttons and reset their animations
        val parent = parent as? ViewGroup
        parent?.findViewById<View>(R.id.btn_manual_input)?.apply {
            isClickable = true
            isFocusable = true
            animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f).setDuration(200).start()
        }
        parent?.findViewById<View>(R.id.btn_settings)?.apply {
            isClickable = true
            isFocusable = true
            animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f).setDuration(200).start()
        }
        parent?.findViewById<View>(R.id.btn_snap)?.apply {
            animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f).setDuration(200).start()
        }
        parent?.findViewById<View>(R.id.btn_torch)?.apply {
            animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f).setDuration(200).start()
        }
        parent?.findViewById<View>(R.id.btn_help)?.apply {
            animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f).setDuration(200).start()
        }
        
        animate()
            .alpha(0f)
            .setDuration(300)
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
        
        // Draw dim overlay
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        
        // Cut out highlighted area
        highlightRect?.let { rect ->
            canvas.drawRoundRect(rect, highlightRadius, highlightRadius, clearPaint)
        }
    }

    private data class TutorialStep(
        val text: String,
        val arrowDirection: ArrowDirection
    )

    private enum class ArrowDirection {
        UP, DOWN_LEFT, DOWN_RIGHT
    }
}
