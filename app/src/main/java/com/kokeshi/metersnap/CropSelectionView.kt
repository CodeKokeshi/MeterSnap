package com.kokeshi.metersnap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class CropSelectionView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    interface Listener {
        fun onSelectionCommitted(selectionInView: RectF)
    }

    private enum class DragMode {
        NONE,
        DRAW,
        MOVE,
        RESIZE
    }

    private var listener: Listener? = null
    private var active = false

    private val overlayPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#77000000")
        style = Paint.Style.FILL
    }

    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#FFEB3B")
        style = Paint.Style.FILL
    }

    private val minSizePx = 80f
    private val handleRadius = 18f
    private val touchSlop = 24f

    private var mode = DragMode.NONE
    private val selectionRect = RectF()
    private var hasSelection = false

    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f

    fun setListener(listener: Listener?) {
        this.listener = listener
    }

    fun setActive(active: Boolean) {
        this.active = active
        if (!active) {
            clearSelection()
        }
        invalidate()
    }

    fun clearSelection() {
        selectionRect.setEmpty()
        hasSelection = false
        mode = DragMode.NONE
        invalidate()
    }

    fun setSelection(selectionInView: RectF) {
        val bounded = RectF(
            selectionInView.left.coerceIn(0f, width.toFloat()),
            selectionInView.top.coerceIn(0f, height.toFloat()),
            selectionInView.right.coerceIn(0f, width.toFloat()),
            selectionInView.bottom.coerceIn(0f, height.toFloat())
        )
        if (bounded.width() < minSizePx || bounded.height() < minSizePx) {
            clearSelection()
            return
        }
        selectionRect.set(bounded)
        hasSelection = true
        mode = DragMode.NONE
        invalidate()
    }

    fun getSelection(): RectF? {
        return if (hasSelection && selectionRect.width() >= minSizePx && selectionRect.height() >= minSizePx) {
            RectF(selectionRect)
        } else {
            null
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (!active) return

        val hasStartedSelection = hasSelection || mode != DragMode.NONE
        if (!hasStartedSelection && selectionRect.width() < 1f && selectionRect.height() < 1f) {
            return
        }

        if (hasStartedSelection) {
            // Cutout effect by overdrawing transparent-like center using background color.
            val clearPaint = Paint().apply {
                color = Color.TRANSPARENT
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.CLEAR)
            }
            val saveLayer = canvas.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), overlayPaint)
            if (selectionRect.width() >= 1f && selectionRect.height() >= 1f) {
                canvas.drawRect(selectionRect, clearPaint)
            }
            canvas.restoreToCount(saveLayer)
        }

        if (selectionRect.width() >= 1f && selectionRect.height() >= 1f) {
            canvas.drawRect(selectionRect, borderPaint)
            canvas.drawCircle(selectionRect.right, selectionRect.bottom, handleRadius, handlePaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!active) return false

        val x = event.x.coerceIn(0f, width.toFloat())
        val y = event.y.coerceIn(0f, height.toFloat())

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startY = y
                lastX = x
                lastY = y

                mode = when {
                    hasSelection && isNearResizeHandle(x, y) -> DragMode.RESIZE
                    hasSelection && selectionRect.contains(x, y) -> DragMode.MOVE
                    else -> {
                        hasSelection = false
                        selectionRect.set(x, y, x, y)
                        DragMode.DRAW
                    }
                }
                invalidate()
                return true
            }

            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    DragMode.DRAW -> {
                        updateDrawRect(startX, startY, x, y)
                    }
                    DragMode.MOVE -> {
                        val dx = x - lastX
                        val dy = y - lastY
                        moveRect(dx, dy)
                    }
                    DragMode.RESIZE -> {
                        resizeRectTo(x, y)
                    }
                    DragMode.NONE -> {
                        // no-op
                    }
                }
                lastX = x
                lastY = y
                invalidate()
                return true
            }

            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL -> {
                val wasDrawing = mode == DragMode.DRAW
                mode = DragMode.NONE

                if (wasDrawing) {
                    hasSelection = selectionRect.width() >= minSizePx && selectionRect.height() >= minSizePx
                }

                if (hasSelection && selectionRect.width() >= minSizePx && selectionRect.height() >= minSizePx) {
                    listener?.onSelectionCommitted(RectF(selectionRect))
                }
                invalidate()
                return true
            }
        }

        return super.onTouchEvent(event)
    }

    private fun updateDrawRect(x1: Float, y1: Float, x2: Float, y2: Float) {
        val rawLeft = min(x1, x2)
        val rawRight = max(x1, x2)
        val rawTop = min(y1, y2)
        val rawBottom = max(y1, y2)
        val squareSize = min(rawRight - rawLeft, rawBottom - rawTop)

        selectionRect.set(
            rawLeft,
            rawTop,
            rawLeft + squareSize,
            rawTop + squareSize
        )
        hasSelection = selectionRect.width() >= touchSlop && selectionRect.height() >= touchSlop
    }

    private fun moveRect(dx: Float, dy: Float) {
        val newLeft = (selectionRect.left + dx).coerceIn(0f, width.toFloat() - selectionRect.width())
        val newTop = (selectionRect.top + dy).coerceIn(0f, height.toFloat() - selectionRect.height())
        selectionRect.set(
            newLeft,
            newTop,
            newLeft + selectionRect.width(),
            newTop + selectionRect.height()
        )
    }

    private fun resizeRectTo(x: Float, y: Float) {
        val maxSize = min(width.toFloat() - selectionRect.left, height.toFloat() - selectionRect.top)
        val targetSize = max(x - selectionRect.left, y - selectionRect.top)
            .coerceIn(minSizePx, maxSize)
        selectionRect.right = selectionRect.left + targetSize
        selectionRect.bottom = selectionRect.top + targetSize
    }

    private fun isNearResizeHandle(x: Float, y: Float): Boolean {
        return abs(x - selectionRect.right) <= handleRadius * 2f &&
            abs(y - selectionRect.bottom) <= handleRadius * 2f
    }
}
