package com.kokeshi.metersnap

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class OverlayView(context: Context, attrs: AttributeSet?) : View(context, attrs) {

    private val rects = ArrayList<RectF>()
    private val paintRed = Paint().apply {
        color = Color.RED
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    private val paintGreen = Paint().apply {
        color = Color.GREEN
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }
    
    // We can distinguish between "all detected" (red) and "accepted digits" (green)
    // For now, let's just draw what is passed to us.
    // If we want two colors, we need two lists.
    private val acceptedRects = ArrayList<RectF>()
    private val allRects = ArrayList<RectF>()

    fun setRects(all: List<RectF>, accepted: List<RectF>) {
        allRects.clear()
        allRects.addAll(all)
        acceptedRects.clear()
        acceptedRects.addAll(accepted)
        invalidate() // Trigger redraw
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        for (rect in allRects) {
            canvas.drawRect(rect, paintRed)
        }
        
        for (rect in acceptedRects) {
            canvas.drawRect(rect, paintGreen)
        }
    }
}
