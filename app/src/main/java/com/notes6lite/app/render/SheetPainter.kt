package com.notes6lite.app.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.notes6lite.app.domain.model.ColorScheme
import com.notes6lite.app.domain.model.PageTemplate
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
 * Desenha o fundo da página (template + esquema de cores).
 *
 * Importante: nenhum traço da S-Pen é desenhado aqui. Assim a borracha
 * (que atua na camada de tinta) nunca apaga o fundo.
 *
 * O template ([PageTemplate]) e o esquema ([ColorScheme]) são desacoplados:
 * qualquer um dos 4 templates (BLANK, RULED, GRID, DOTTED) pode ser
 * renderizado em modo Claro (#FFFFFF / #E2E8F0) ou Escuro (#18181B / #27272A).
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
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

    private var palette = paletteFor(ColorScheme.LIGHT)

    var style: SheetStyle = SheetStyle.GRID
        set(value) {
            if (field == value) return
            field = value
            palette = paletteFor(value.scheme)
            applyPalette()
        }

    var template: PageTemplate
        get() = style.template
        set(value) {
            style = SheetStyle.of(value, style.scheme)
        }

    var colorScheme: ColorScheme
        get() = style.scheme
        set(value) {
            style = SheetStyle.of(style.template, value)
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
        dotPaint.color = palette.lines
    }

    fun drawPage(canvas: Canvas, pageRect: RectF, backgroundBitmap: Bitmap?) {
        backgroundPaint.color = palette.background
        canvas.drawRect(pageRect, backgroundPaint)

        canvas.save()
        canvas.clipRect(pageRect)
        if (backgroundBitmap != null && !backgroundBitmap.isRecycled) {
            // PDF importado: o bitmap é a folha, isolado da camada de tinta
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
        when (style.template) {
            PageTemplate.BLANK -> Unit

            PageTemplate.RULED -> {
                var y = floor(area.top / STEP) * STEP
                while (y <= area.bottom) {
                    canvas.drawLine(area.left, y, area.right, y, linePaint)
                    y += STEP
                }
                // Margem vertical
                if (area.left <= MARGIN_X && area.right >= MARGIN_X) {
                    canvas.drawLine(MARGIN_X, area.top, MARGIN_X, area.bottom, marginPaint)
                }
            }

            PageTemplate.GRID -> {
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

            PageTemplate.DOTTED -> {
                var y = floor(area.top / STEP) * STEP
                while (y <= area.bottom) {
                    var x = floor(area.left / STEP) * STEP
                    while (x <= area.right) {
                        canvas.drawCircle(x, y, DOT_RADIUS, dotPaint)
                        x += STEP
                    }
                    y += STEP
                }
            }
        }
    }

    companion object {
        const val STEP = 60f
        const val MARGIN_X = 120f
        private const val DOT_RADIUS = 2.2f

        private fun paletteFor(scheme: ColorScheme): SheetPalette =
            if (scheme.isDark) {
                SheetPalette(
                    background = Color.parseColor("#18181B"),
                    lines = Color.parseColor("#27272A"),
                    margin = Color.parseColor("#3F3F46"),
                    border = Color.parseColor("#3F3F46"),
                    workspace = Color.parseColor("#0F172A")
                )
            } else {
                SheetPalette(
                    background = Color.parseColor("#FFFFFF"),
                    lines = Color.parseColor("#E2E8F0"),
                    margin = Color.parseColor("#FCA5A5"),
                    border = Color.parseColor("#CBD5E1"),
                    workspace = Color.parseColor("#0F172A")
                )
            }
    }
}
