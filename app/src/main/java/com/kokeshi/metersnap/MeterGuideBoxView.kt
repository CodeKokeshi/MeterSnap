package com.kokeshi.metersnap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Draws a square guide box centered in the camera preview.
 *
 * The guide box is a static framing aid for the active YOLO-based snap flow.
 */
class MeterGuideBoxView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        /** Box side as a fraction of the view's shorter dimension. */
        const val FRACTION = 0.80f

        private val COLOR_IDLE = Color.WHITE
    }

    private val vignettePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(140, 0, 0, 0)
        style = Paint.Style.FILL
    }

    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = COLOR_IDLE
        style = Paint.Style.STROKE
        strokeWidth = 5f * context.resources.displayMetrics.density
        strokeCap = Paint.Cap.SQUARE
    }

    private val boxRect = RectF()

    fun getGuideBoxRect(): RectF = RectF(boxRect)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        val boxSize = FRACTION * min(w, h)
        val left = (w - boxSize) / 2f
        val top = (h - boxSize) / 2f
        boxRect.set(left, top, left + boxSize, top + boxSize)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()

        canvas.drawRect(0f, 0f, w, boxRect.top, vignettePaint)
        canvas.drawRect(0f, boxRect.bottom, w, h, vignettePaint)
        canvas.drawRect(0f, boxRect.top, boxRect.left, boxRect.bottom, vignettePaint)
        canvas.drawRect(boxRect.right, boxRect.top, w, boxRect.bottom, vignettePaint)

        cornerPaint.color = COLOR_IDLE
        val arm = boxRect.width() * 0.11f

        canvas.drawLine(boxRect.left, boxRect.top + arm, boxRect.left, boxRect.top, cornerPaint)
        canvas.drawLine(boxRect.left, boxRect.top, boxRect.left + arm, boxRect.top, cornerPaint)

        canvas.drawLine(boxRect.right - arm, boxRect.top, boxRect.right, boxRect.top, cornerPaint)
        canvas.drawLine(boxRect.right, boxRect.top, boxRect.right, boxRect.top + arm, cornerPaint)

        canvas.drawLine(boxRect.left, boxRect.bottom - arm, boxRect.left, boxRect.bottom, cornerPaint)
        canvas.drawLine(boxRect.left, boxRect.bottom, boxRect.left + arm, boxRect.bottom, cornerPaint)

        canvas.drawLine(boxRect.right - arm, boxRect.bottom, boxRect.right, boxRect.bottom, cornerPaint)
        canvas.drawLine(boxRect.right, boxRect.bottom - arm, boxRect.right, boxRect.bottom, cornerPaint)
    }
}
