package com.notes6lite.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.notes6lite.app.domain.model.SheetStyle
import kotlin.math.floor

private data class SheetPalette(
    val background: Int,
    val lines: Int,
    val margin: Int,
    val border: Int,
    val workspace: Int
)

/**
 * Desenha exclusivamente o fundo da página (template + estilo de folha).
 *
 * Importante: nenhum traço da S-Pen é desenhado aqui. Assim a borracha
 * (que atua na camada de tinta) nunca apaga o fundo.
 */
class SheetPainter {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    private var palette = paletteFor(SheetStyle.BLANK)

    var style: SheetStyle = SheetStyle.BLANK
        set(value) {
            if (field == value) return
            field = value
            palette = paletteFor(value)
            applyPalette()
        }

    init {
        applyPalette()
    }

    val workspaceColor: Int
        get() = palette.workspace

    private fun applyPalette() {
        backgroundPaint.color = palette.background
        linePaint.color = palette.lines
        marginPaint.color = palette.margin
        borderPaint.color = palette.border
    }

    fun drawPage(canvas: Canvas, pageRect: RectF, backgroundBitmap: Bitmap?) {
        backgroundPaint.color = palette.background
        canvas.drawRect(pageRect, backgroundPaint)

        canvas.save()
        canvas.clipRect(pageRect)
        if (backgroundBitmap != null && !backgroundBitmap.isRecycled) {
            // PDF importado: o bitmap é a folha, mas continua isolado da tinta.
            canvas.drawBitmap(backgroundBitmap, null, pageRect, bitmapPaint)
        } else {
            drawPattern(canvas, pageRect)
        }
        canvas.restore()

        canvas.drawRect(pageRect, borderPaint)
    }

    fun drawInfinite(canvas: Canvas, visibleRect: RectF) {
        backgroundPaint.color = palette.background
        canvas.drawRect(visibleRect, backgroundPaint)
        drawPattern(canvas, visibleRect)
    }

    private fun drawPattern(canvas: Canvas, area: RectF) {
        when (style) {
            SheetStyle.BLANK -> Unit

            SheetStyle.RULED -> {
                var y = floor(area.top / STEP) * STEP
                while (y <= area.bottom) {
                    canvas.drawLine(area.left, y, area.right, y, linePaint)
                    y += STEP
                }
                // Margem de caderno (linha vertical suave).
                if (area.left <= MARGIN_X && area.right >= MARGIN_X) {
                    canvas.drawLine(MARGIN_X, area.top, MARGIN_X, area.bottom, marginPaint)
                }
            }

            SheetStyle.GRID -> {
                var y = floor(area.top / STEP) * STEP
                while (y <= area.bottom) {
                    canvas.drawLine(area.left, y, area.right, y, linePaint)
                    y += STEP
                }
                var x = floor(area.left / STEP) * STEP
                while (x <= area.right) {
                    canvas.drawLine(x, area.top, x, area.bottom, linePaint)
                    x += STEP
                }
            }

            SheetStyle.DARK -> {
                // Dark Mode com grade sutil, para não ofuscar a tinta clara.
                var y = floor(area.top / STEP) * STEP
                while (y <= area.bottom) {
                    canvas.drawLine(area.left, y, area.right, y, linePaint)
                    y += STEP
                }
                var x = floor(area.left / STEP) * STEP
                while (x <= area.right) {
                    canvas.drawLine(x, area.top, x, area.bottom, linePaint)
                    x += STEP
                }
            }
        }
    }

    companion object {
        const val STEP = 60f
        const val MARGIN_X = 120f

        private fun paletteFor(style: SheetStyle): SheetPalette = when (style) {
            SheetStyle.BLANK -> SheetPalette(
                background = Color.WHITE,
                lines = Color.TRANSPARENT,
                margin = Color.TRANSPARENT,
                border = Color.parseColor("#CBD5E1"),
                workspace = Color.parseColor("#E2E8F0")
            )

            SheetStyle.RULED -> SheetPalette(
                background = Color.parseColor("#FFFDF8"),
                lines = Color.parseColor("#CBD5E1"),
                margin = Color.parseColor("#FCA5A5"),
                border = Color.parseColor("#CBD5E1"),
                workspace = Color.parseColor("#E2E8F0")
            )

            SheetStyle.GRID -> SheetPalette(
                background = Color.WHITE,
                lines = Color.parseColor("#E2E8F0"),
                margin = Color.TRANSPARENT,
                border = Color.parseColor("#CBD5E1"),
                workspace = Color.parseColor("#E2E8F0")
            )

            SheetStyle.DARK -> SheetPalette(
                background = Color.parseColor("#0F172A"),
                lines = Color.parseColor("#1E293B"),
                margin = Color.parseColor("#334155"),
                border = Color.parseColor("#334155"),
                workspace = Color.parseColor("#020617")
            )
        }
    }
}
