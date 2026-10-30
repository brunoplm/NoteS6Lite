package com.notes6lite.app.render

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.notes6lite.app.domain.model.ToolMode

/**
 * Desenha traços em uma camada de tinta isolada.
 *
 * A borracha usa PorterDuff.CLEAR e só pode ser aplicada na camada de tinta,
 * nunca no fundo (template/PDF).
 */
class InkPainter {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val clearXfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    fun draw(canvas: Canvas, outline: StrokeOutline, color: Int, mode: ToolMode) {
        when (mode) {
            ToolMode.PEN -> {
                paint.xfermode = null
                paint.color = color
                paint.alpha = 255
            }

            ToolMode.HIGHLIGHTER -> {
                paint.xfermode = null
                paint.color = color
                paint.alpha = 95
            }

            ToolMode.ERASER -> {
                paint.xfermode = clearXfermode
                paint.color = Color.TRANSPARENT
                paint.alpha = 255
            }
        }
        canvas.drawPath(outline.path, paint)
        paint.xfermode = null
    }
}
