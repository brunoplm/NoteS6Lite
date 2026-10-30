package com.notes6lite.app.render

import android.graphics.Path
import android.graphics.RectF
import com.notes6lite.app.domain.model.InkPoint
import com.notes6lite.app.domain.model.ToolMode
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Contorno preenchido de um traço. Usar um único Path preenchido por traço
 * mantém o custo de desenho baixo (1 drawPath por traço) mesmo com largura
 * variável por pressão.
 */
class StrokeOutline(
    val path: Path = Path(),
    val bounds: RectF = RectF()
)

object StrokeOutlineBuilder {

    /**
     * Constrói o contorno de um traço com largura variável.
     *
     * A linha central é suavizada com curvas quadráticas de Bézier
     * (mesma técnica do MVP) e o contorno é gerado deslocando os pontos
     * pela normal em função da pressão da S-Pen.
     */
    fun build(
        points: List<InkPoint>,
        baseWidth: Float,
        mode: ToolMode,
        pressureEnabled: Boolean,
        reuse: StrokeOutline? = null
    ): StrokeOutline {
        val outline = reuse ?: StrokeOutline()
        outline.path.reset()
        outline.bounds.setEmpty()

        if (points.isEmpty()) return outline

        // Remove pontos duplicados/ruído mínimo do digitalizador.
        val pts = ArrayList<InkPoint>(points.size)
        for (p in points) {
            if (pts.isEmpty()) {
                pts.add(p)
            } else {
                val last = pts[pts.size - 1]
                if (hypot(p.x - last.x, p.y - last.y) > 0.01f) pts.add(p)
            }
        }
        if (pts.isEmpty()) return outline

        val usePressure = pressureEnabled && mode == ToolMode.PEN

        if (pts.size == 1) {
            val p = pts[0]
            val radius = widthFor(p.pressure, baseWidth, usePressure) / 2f
            outline.path.addCircle(p.x, p.y, radius, Path.Direction.CW)
            outline.path.computeBounds(outline.bounds, true)
            return outline
        }

        val n = pts.size
        val widths = FloatArray(n)
        for (i in 0 until n) {
            widths[i] = widthFor(pts[i].pressure, baseWidth, usePressure)
        }

        // Suavização da largura para evitar "serrilhado" de pressão.
        if (usePressure) {
            for (i in 1 until n) {
                widths[i] = widths[i - 1] * 0.35f + widths[i] * 0.65f
            }
            for (i in n - 2 downTo 0) {
                widths[i] = widths[i + 1] * 0.35f + widths[i] * 0.65f
            }
        }

        val leftX = FloatArray(n)
        val leftY = FloatArray(n)
        val rightX = FloatArray(n)
        val rightY = FloatArray(n)

        for (i in 0 until n) {
            val prev = pts[if (i > 0) i - 1 else 0]
            val next = pts[if (i < n - 1) i + 1 else n - 1]
            var dx = next.x - prev.x
            var dy = next.y - prev.y
            val len = hypot(dx, dy)
            if (len > 0.0001f) {
                dx /= len
                dy /= len
            } else {
                dx = 0f
                dy = 0f
            }
            // Normal (girada 90° no sentido do desenho)
            val nx = -dy
            val ny = dx
            val half = widths[i] / 2f
            leftX[i] = pts[i].x + nx * half
            leftY[i] = pts[i].y + ny * half
            rightX[i] = pts[i].x - nx * half
            rightY[i] = pts[i].y - ny * half
        }

        val path = outline.path
        path.moveTo(leftX[0], leftY[0])

        // Lado esquerdo: Bézier quadrática passando pelos pontos médios.
        for (i in 1 until n) {
            val midX = (leftX[i - 1] + leftX[i]) * 0.5f
            val midY = (leftY[i - 1] + leftY[i]) * 0.5f
            path.quadTo(leftX[i - 1], leftY[i - 1], midX, midY)
        }
        path.lineTo(leftX[n - 1], leftY[n - 1])

        // Ponta final arredondada (curva quadrática no sentido do traço).
        val endDx = pts[n - 1].x - pts[n - 2].x
        val endDy = pts[n - 1].y - pts[n - 2].y
        val endLen = hypot(endDx, endDy).coerceAtLeast(0.0001f)
        val endHalf = widths[n - 1] / 2f
        path.quadTo(
            pts[n - 1].x + (endDx / endLen) * endHalf,
            pts[n - 1].y + (endDy / endLen) * endHalf,
            rightX[n - 1],
            rightY[n - 1]
        )

        // Lado direito de volta.
        for (i in n - 1 downTo 1) {
            val midX = (rightX[i] + rightX[i - 1]) * 0.5f
            val midY = (rightY[i] + rightY[i - 1]) * 0.5f
            path.quadTo(rightX[i], rightY[i], midX, midY)
        }
        path.lineTo(rightX[0], rightY[0])

        // Ponta inicial arredondada.
        val startDx = pts[0].x - pts[1].x
        val startDy = pts[0].y - pts[1].y
        val startLen = hypot(startDx, startDy).coerceAtLeast(0.0001f)
        val startHalf = widths[0] / 2f
        path.quadTo(
            pts[0].x + (startDx / startLen) * startHalf,
            pts[0].y + (startDy / startLen) * startHalf,
            leftX[0],
            leftY[0]
        )
        path.close()

        path.computeBounds(outline.bounds, true)
        outline.bounds.inset(-1f, -1f)
        return outline
    }

    private fun widthFor(pressure: Float, baseWidth: Float, usePressure: Boolean): Float {
        if (!usePressure) return baseWidth
        val p = pressure.coerceIn(0f, 1f)
        // Curva de resposta não linear: toque leve = traço fino,
        // pressão forte = traço cheio, sem ficar "duro" no início.
        val factor = 0.28f + 0.72f * sqrt(p)
        return (baseWidth * factor).coerceAtLeast(0.75f)
    }
}
