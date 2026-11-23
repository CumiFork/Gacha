package com.arkana.cppoverlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View

class EspOverlayView(context: Context) : View(context) {

    data class Line(val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 255, 70, 70)
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    @Volatile
    private var lines: List<Line> = emptyList()

    fun updateLines(newLines: List<Line>) {
        lines = newLines
        postInvalidate()
    }

    fun clearLines() {
        lines = emptyList()
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val snapshot = lines
        for (l in snapshot) {
            canvas.drawLine(l.x1, l.y1, l.x2, l.y2, paint)
        }
    }
}
