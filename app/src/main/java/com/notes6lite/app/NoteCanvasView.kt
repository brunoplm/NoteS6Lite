package com.notes6lite.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.notes6lite.app.domain.model.InkPoint
import com.notes6lite.app.domain.model.PageTemplate
import com.notes6lite.app.domain.model.SheetStyle
import com.notes6lite.app.domain.model.Stroke
import com.notes6lite.app.domain.model.ToolMode
import com.notes6lite.app.render.InkPainter
import com.notes6lite.app.render.SheetPainter
import com.notes6lite.app.render.StrokeOutline
import com.notes6lite.app.render.StrokeOutlineBuilder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Canvas de tinta de baixa latência para o Galaxy Tab S6 Lite.
 *
 * Princípios preservados:
 * 1. Desenho direto em android.graphics.Canvas, com suavização por Bézier
 *    quadrática e uso de getHistoricalX/Y (zero lag).
 * 2. Rejeição de palma: TOOL_TYPE_STYLUS/ERASER tem prioridade absoluta;
 *    TOOL_TYPE_FINGER só faz pan/zoom.
 * 3. Camadas: fundo (template/PDF) e tinta são desenhados em camadas
 *    separadas; a borracha usa CLEAR apenas na camada de tinta.
 */
class NoteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    // ---------------------------------------------------------------------
    // Ferramenta atual
    // ---------------------------------------------------------------------

    var currentToolMode: ToolMode = ToolMode.PEN
        set(value) {
            field = value
            invalidate()
        }

    var currentColor: Int = DEFAULT_INK_COLOR

    var penStrokeWidth: Float = 6.5f
    var highlighterStrokeWidth: Float = 28f
    var eraserStrokeWidth: Float = 42f

    /**
     * Quando true, a largura do traço da caneta varia ponto a ponto
     * conforme event.getPressure() da S-Pen.
     */
    var pressureEnabled: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            rebuildAllOutlines()
            invalidate()
        }

    // ---------------------------------------------------------------------
    // Página / template / estilo
    // ---------------------------------------------------------------------

    var pageTemplate: PageTemplate = PageTemplate.A4
        private set

    var sheetStyle: SheetStyle = SheetStyle.GRID
        private set

    var pageWidth: Int = PageTemplate.A4.pageWidth
        private set

    var pageHeight: Int = PageTemplate.A4.pageHeight
        private set

    /** Chamado quando um traço é finalizado (para autosave). */
    var onStrokeCommitted: (() -> Unit)? = null

    private var backgroundBitmap: Bitmap? = null

    /** Fundo efetivamente escuro (Dark Mode sem PDF branco por cima). */
    val isDarkBackground: Boolean
        get() = sheetStyle.isDark && backgroundBitmap == null

    // ---------------------------------------------------------------------
    // Estado dos traços
    // ---------------------------------------------------------------------

    private data class PageStroke(
        val data: Stroke,
        val outline: StrokeOutline
    )

    private val strokes = ArrayList<PageStroke>()
    private val activePoints = ArrayList<InkPoint>(256)
    private var activeOutline: StrokeOutline? = null
    private var activeMode: ToolMode = ToolMode.PEN
    private var activeColor: Int = currentColor
    private var activeBaseWidth: Float = penStrokeWidth
    private var isStylusDrawing = false
    private var activeStylusPointerId = MotionEvent.INVALID_POINTER_ID
    private var lastPageX = 0f
    private var lastPageY = 0f
    private var smoothedPressure = 0.5f
    private var lastOutlineBuildTime = 0L
    private var hasEraserStrokes = false

    // ---------------------------------------------------------------------
    // Transformação Pan/Zoom
    // ---------------------------------------------------------------------

    private val viewMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var scaleFactor = 1f
    private var translateX = 0f
    private var translateY = 0f

    private var lastFingerX = 0f
    private var lastFingerY = 0f
    private var isFingerPanning = false
    private var activeFingerPointerId = MotionEvent.INVALID_POINTER_ID

    // ---------------------------------------------------------------------
    // Renderização
    // ---------------------------------------------------------------------

    private val sheetPainter = SheetPainter()
    private val inkPainter = InkPainter()

    private val pageRect = RectF()
    private val visibleRect = RectF()
    private val tempPoint = FloatArray(2)

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                if (isStylusDrawing) return false

                val minScale = if (pageTemplate.isInfinite) 0.2f else 0.4f
                val maxScale = if (pageTemplate.isInfinite) 6f else 4.5f

                val previousScale = scaleFactor
                scaleFactor = max(minScale, min(scaleFactor * detector.scaleFactor, maxScale))
                val factor = scaleFactor / previousScale

                translateX = detector.focusX - (detector.focusX - translateX) * factor
                translateY = detector.focusY - (detector.focusY - translateY) * factor
                updateMatrix()
                invalidate()
                return true
            }
        }
    )

    init {
        setLayerType(LAYER_TYPE_HARDWARE, null)
        sheetPainter.style = sheetStyle
        updatePageRect()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0 && w > 0 && h > 0) {
            fitPageToScreen()
        }
    }

    // ---------------------------------------------------------------------
    // API de página
    // ---------------------------------------------------------------------

    fun setPageTemplate(template: PageTemplate) {
        if (pageTemplate == template) return
        pageTemplate = template
        if (backgroundBitmap == null) {
            pageWidth = if (template.isInfinite) 0 else template.pageWidth
            pageHeight = if (template.isInfinite) 0 else template.pageHeight
        }
        updatePageRect()
        fitPageToScreen()
    }

    fun setSheetStyle(style: SheetStyle) {
        if (sheetStyle == style) return
        sheetStyle = style
        sheetPainter.style = style
        adjustInkColorFor(style)
        invalidate()
    }

    fun setBackgroundBitmap(bitmap: Bitmap?) {
        backgroundBitmap = bitmap
        if (bitmap != null) {
            pageWidth = bitmap.width
            pageHeight = bitmap.height
        } else if (pageTemplate.isInfinite) {
            pageWidth = 0
            pageHeight = 0
        } else {
            pageWidth = pageTemplate.pageWidth
            pageHeight = pageTemplate.pageHeight
        }
        adjustInkColorFor(sheetStyle)
        updatePageRect()
        fitPageToScreen()
    }

    /**
     * Carrega uma página completa (template + estilo + fundo + traços).
     * Usado pelo MainActivity ao trocar de página/caderno.
     */
    fun loadPage(
        template: PageTemplate,
        style: SheetStyle,
        background: Bitmap?,
        pageWidth: Int,
        pageHeight: Int,
        savedStrokes: List<Stroke>
    ) {
        pageTemplate = template
        sheetStyle = style
        sheetPainter.style = style

        backgroundBitmap = background
        adjustInkColorFor(style)

        when {
            // As dimensões persistidas são a fonte de verdade do espaço de
            // coordenadas; o bitmap pode ter sido decodificado com downsample.
            pageWidth > 0 && pageHeight > 0 -> {
                this.pageWidth = pageWidth
                this.pageHeight = pageHeight
            }

            background != null -> {
                this.pageWidth = background.width
                this.pageHeight = background.height
            }

            template.isInfinite -> {
                this.pageWidth = 0
                this.pageHeight = 0
            }

            else -> {
                this.pageWidth = template.pageWidth
                this.pageHeight = template.pageHeight
            }
        }

        updatePageRect()
        loadStrokes(savedStrokes)
        fitPageToScreen()
    }

    fun loadStrokes(savedStrokes: List<Stroke>) {
        // Um traço em andamento pertence à página anterior: descarta para não
        // vazar tinta (ou um CLEAR de borracha) para a página recém-carregada.
        isStylusDrawing = false
        activeStylusPointerId = MotionEvent.INVALID_POINTER_ID
        activePoints.clear()
        activeOutline = null
        isFingerPanning = false
        activeFingerPointerId = MotionEvent.INVALID_POINTER_ID

        strokes.clear()
        hasEraserStrokes = false
        for (stroke in savedStrokes) {
            if (stroke.mode == ToolMode.ERASER) hasEraserStrokes = true
            strokes.add(
                PageStroke(
                    data = stroke,
                    outline = StrokeOutlineBuilder.build(
                        points = stroke.points,
                        baseWidth = stroke.baseWidth,
                        mode = stroke.mode,
                        pressureEnabled = pressureEnabled
                    )
                )
            )
        }
        invalidate()
    }

    /**
     * Mantido para compatibilidade com o fluxo antigo de PDF.
     */
    fun setPageContent(bitmap: Bitmap?, savedStrokes: List<Stroke>?) {
        backgroundBitmap = bitmap
        if (bitmap != null) {
            pageWidth = bitmap.width
            pageHeight = bitmap.height
        } else if (pageTemplate.isInfinite) {
            pageWidth = 0
            pageHeight = 0
        } else {
            pageWidth = pageTemplate.pageWidth
            pageHeight = pageTemplate.pageHeight
        }
        updatePageRect()
        loadStrokes(savedStrokes ?: emptyList())
        fitPageToScreen()
    }

    fun fitPageToScreen() {
        if (width == 0 || height == 0) return

        if (pageTemplate.isInfinite) {
            // Canvas Infinito: enquadra o conteúdo quando existe; caso
            // contrário, coloca a origem no centro da tela.
            val content = getStrokesCopy()
            if (content.isNotEmpty()) {
                val bounds = computeContentBounds(content, 80f)
                val scaleX = width / bounds.width().coerceAtLeast(1f)
                val scaleY = height / bounds.height().coerceAtLeast(1f)
                scaleFactor = min(scaleX, scaleY).coerceIn(0.2f, 6f)
                translateX = width / 2f - bounds.centerX() * scaleFactor
                translateY = height / 2f - bounds.centerY() * scaleFactor
            } else {
                scaleFactor = 1f
                translateX = width / 2f
                translateY = height / 2f
            }
        } else {
            val scaleX = (width * 0.94f) / pageWidth.coerceAtLeast(1).toFloat()
            val scaleY = (height * 0.94f) / pageHeight.coerceAtLeast(1).toFloat()
            scaleFactor = min(scaleX, scaleY).coerceAtLeast(0.05f)
            translateX = (width - pageWidth * scaleFactor) / 2f
            translateY = max(24f, (height - pageHeight * scaleFactor) / 2f)
        }

        updateMatrix()
        invalidate()
    }

    private fun updatePageRect() {
        pageRect.set(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
    }

    private fun updateMatrix() {
        viewMatrix.reset()
        viewMatrix.postScale(scaleFactor, scaleFactor)
        viewMatrix.postTranslate(translateX, translateY)
        viewMatrix.invert(inverseMatrix)
    }

    private fun mapToPage(screenX: Float, screenY: Float, out: FloatArray) {
        out[0] = screenX
        out[1] = screenY
        inverseMatrix.mapPoints(out)
    }

    private fun adjustInkColorFor(style: SheetStyle) {
        val darkBackground = style.isDark && backgroundBitmap == null
        val luminance = Color.luminance(currentColor)
        if (darkBackground && luminance < 0.25f) {
            currentColor = DEFAULT_DARK_INK_COLOR
        } else if (!darkBackground && luminance > 0.75f) {
            currentColor = DEFAULT_INK_COLOR
        }
    }

    private fun rebuildAllOutlines() {
        if (strokes.isEmpty()) return
        val rebuilt = ArrayList<PageStroke>(strokes.size)
        for (pageStroke in strokes) {
            val stroke = pageStroke.data
            rebuilt.add(
                PageStroke(
                    data = stroke,
                    outline = StrokeOutlineBuilder.build(
                        points = stroke.points,
                        baseWidth = stroke.baseWidth,
                        mode = stroke.mode,
                        pressureEnabled = pressureEnabled
                    )
                )
            )
        }
        strokes.clear()
        strokes.addAll(rebuilt)
    }

    // ---------------------------------------------------------------------
    // Entrada: S-Pen (escrita) x dedo (pan/zoom)
    // ---------------------------------------------------------------------

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        // 1. Localiza qualquer ponteiro de S-Pen no evento.
        var stylusIndex = -1
        for (i in 0 until event.pointerCount) {
            val toolType = event.getToolType(i)
            if (toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
                stylusIndex = i
                break
            }
        }

        // 2. A S-Pen tem prioridade absoluta: dedos/palma são ignorados.
        if (stylusIndex != -1) {
            isFingerPanning = false
            activeFingerPointerId = MotionEvent.INVALID_POINTER_ID
            handleStylusEvent(event, action, stylusIndex)
            return true
        }

        // 3. Sem S-Pen, o dedo apenas move e dá zoom na página.
        if (event.getToolType(event.actionIndex) == MotionEvent.TOOL_TYPE_FINGER) {
            handleFingerPanAndZoom(event, action)
            return true
        }

        return super.onTouchEvent(event)
    }

    private fun handleStylusEvent(event: MotionEvent, action: Int, pointerIndex: Int) {
        val actionPointerId = event.getPointerId(event.actionIndex)

        when (action) {
            MotionEvent.ACTION_DOWN -> startStroke(event, pointerIndex)

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Só inicia se o ponteiro que desceu for realmente a S-Pen.
                val actionTool = event.getToolType(event.actionIndex)
                val actionIsStylus =
                    actionTool == MotionEvent.TOOL_TYPE_STYLUS ||
                        actionTool == MotionEvent.TOOL_TYPE_ERASER
                if (actionIsStylus && !isStylusDrawing) {
                    startStroke(event, pointerIndex)
                }
                // Dedos extras durante a escrita são ignorados (rejeição de palma).
            }

            MotionEvent.ACTION_MOVE -> {
                if (!isStylusDrawing) return
                val index = event.findPointerIndex(activeStylusPointerId)
                if (index == -1) return
                appendMove(event, index)
            }

            MotionEvent.ACTION_UP -> {
                if (isStylusDrawing && actionPointerId == activeStylusPointerId) {
                    commitStroke()
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Importante: compara o ponteiro que subiu (pode ser um dedo),
                // não o índice da S-Pen. Assim a palma não finaliza o traço.
                if (isStylusDrawing && actionPointerId == activeStylusPointerId) {
                    commitStroke()
                }
            }

            MotionEvent.ACTION_CANCEL -> {
                if (isStylusDrawing) commitStroke()
            }
        }
    }

    private fun startStroke(event: MotionEvent, pointerIndex: Int) {
        val toolType = event.getToolType(pointerIndex)
        val isStylusEraserButton =
            toolType == MotionEvent.TOOL_TYPE_ERASER ||
                (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

        activeMode = if (isStylusEraserButton) ToolMode.ERASER else currentToolMode
        activeColor = currentColor
        activeBaseWidth = when (activeMode) {
            ToolMode.PEN -> penStrokeWidth
            ToolMode.HIGHLIGHTER -> highlighterStrokeWidth
            ToolMode.ERASER -> eraserStrokeWidth
        }

        activePoints.clear()
        val rawPressure = event.getPressure(pointerIndex).coerceIn(0f, 1f)
        smoothedPressure = if (rawPressure < 0.05f) 0.05f else rawPressure

        mapToPage(event.getX(pointerIndex), event.getY(pointerIndex), tempPoint)
        lastPageX = tempPoint[0]
        lastPageY = tempPoint[1]
        activePoints.add(InkPoint(lastPageX, lastPageY, smoothedPressure))

        activeOutline = StrokeOutlineBuilder.build(
            points = activePoints,
            baseWidth = activeBaseWidth,
            mode = activeMode,
            pressureEnabled = pressureEnabled
        )
        lastOutlineBuildTime = event.eventTime

        isStylusDrawing = true
        activeStylusPointerId = event.getPointerId(pointerIndex)
        invalidate()
    }

    private fun appendMove(event: MotionEvent, pointerIndex: Int) {
        // Pontos históricos do digitalizador: base do zero lag da S-Pen.
        val historySize = event.historySize
        for (h in 0 until historySize) {
            mapToPage(
                event.getHistoricalX(pointerIndex, h),
                event.getHistoricalY(pointerIndex, h),
                tempPoint
            )
            appendPoint(
                tempPoint[0],
                tempPoint[1],
                event.getHistoricalPressure(pointerIndex, h)
            )
        }

        mapToPage(event.getX(pointerIndex), event.getY(pointerIndex), tempPoint)
        appendPoint(tempPoint[0], tempPoint[1], event.getPressure(pointerIndex))

        // Reconstruir o contorno é O(n). Limitar a ~125 Hz mantém o preview
        // fluido sem gastar CPU demais em traços longos.
        if (event.eventTime - lastOutlineBuildTime >= 8L) {
            activeOutline = StrokeOutlineBuilder.build(
                points = activePoints,
                baseWidth = activeBaseWidth,
                mode = activeMode,
                pressureEnabled = pressureEnabled,
                reuse = activeOutline
            )
            lastOutlineBuildTime = event.eventTime
        }
        invalidate()
    }

    private fun appendPoint(x: Float, y: Float, pressure: Float) {
        if (abs(x - lastPageX) < 0.05f && abs(y - lastPageY) < 0.05f) return

        val rawPressure = pressure.coerceIn(0f, 1f)
        // Suavização exponencial evita serrilhado na largura do traço.
        smoothedPressure = (smoothedPressure * 0.65f + rawPressure * 0.35f)
            .coerceIn(0.05f, 1f)

        activePoints.add(InkPoint(x, y, smoothedPressure))
        lastPageX = x
        lastPageY = y
    }

    private fun commitStroke() {
        if (!isStylusDrawing) return
        isStylusDrawing = false
        activeStylusPointerId = MotionEvent.INVALID_POINTER_ID

        if (activePoints.isNotEmpty()) {
            val data = Stroke(
                points = activePoints.toList(),
                color = activeColor,
                baseWidth = activeBaseWidth,
                mode = activeMode
            )
            val outline = StrokeOutlineBuilder.build(
                points = data.points,
                baseWidth = data.baseWidth,
                mode = data.mode,
                pressureEnabled = pressureEnabled
            )
            strokes.add(PageStroke(data, outline))
            if (activeMode == ToolMode.ERASER) hasEraserStrokes = true
        }

        activePoints.clear()
        activeOutline = null
        onStrokeCommitted?.invoke()
        invalidate()
    }

    private fun handleFingerPanAndZoom(event: MotionEvent, action: Int) {
        if (isStylusDrawing) return

        scaleGestureDetector.onTouchEvent(event)

        when (action) {
            MotionEvent.ACTION_DOWN -> {
                lastFingerX = event.x
                lastFingerY = event.y
                activeFingerPointerId = event.getPointerId(0)
                isFingerPanning = true
            }

            MotionEvent.ACTION_MOVE -> {
                if (!scaleGestureDetector.isInProgress && isFingerPanning) {
                    val index = event.findPointerIndex(activeFingerPointerId)
                    if (index != -1) {
                        val dx = event.getX(index) - lastFingerX
                        val dy = event.getY(index) - lastFingerY
                        // Filtro mínimo para ignorar vibração de palma apoiada.
                        if (abs(dx) > 2f || abs(dy) > 2f) {
                            translateX += dx
                            translateY += dy
                            updateMatrix()
                            invalidate()
                            lastFingerX = event.getX(index)
                            lastFingerY = event.getY(index)
                        }
                    }
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                val pointerIndex = event.actionIndex
                val pointerId = event.getPointerId(pointerIndex)
                if (pointerId == activeFingerPointerId) {
                    val newIndex = if (pointerIndex == 0) 1 else 0
                    if (newIndex < event.pointerCount) {
                        lastFingerX = event.getX(newIndex)
                        lastFingerY = event.getY(newIndex)
                        activeFingerPointerId = event.getPointerId(newIndex)
                    }
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                isFingerPanning = false
                activeFingerPointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
    }

    // ---------------------------------------------------------------------
    // Desenho
    // ---------------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Mesa de trabalho atrás da folha.
        canvas.drawColor(sheetPainter.workspaceColor)

        canvas.save()
        canvas.concat(viewMatrix)

        visibleRect.set(0f, 0f, width.toFloat(), height.toFloat())
        inverseMatrix.mapRect(visibleRect)

        // 1. Fundo: template/estilo ou PDF. Nunca recebe a borracha.
        if (pageTemplate.isInfinite) {
            sheetPainter.drawInfinite(canvas, visibleRect)
        } else {
            pageRect.set(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
            sheetPainter.drawPage(canvas, pageRect, backgroundBitmap)
        }

        // 2. Camada isolada de tinta: a borracha só apaga aqui.
        // Sem traços de borracha, desenhar direto evita um saveLayer por frame.
        val needsInkLayer = hasEraserStrokes ||
            (isStylusDrawing && activeMode == ToolMode.ERASER)
        val layerBounds: RectF? = if (pageTemplate.isInfinite) null else pageRect
        val layerId = if (needsInkLayer) canvas.saveLayer(layerBounds, null) else -1

        for (pageStroke in strokes) {
            if (RectF.intersects(pageStroke.outline.bounds, visibleRect)) {
                inkPainter.draw(
                    canvas,
                    pageStroke.outline,
                    pageStroke.data.color,
                    pageStroke.data.mode
                )
            }
        }

        activeOutline?.let { outline ->
            inkPainter.draw(canvas, outline, activeColor, activeMode)
        }

        if (needsInkLayer) {
            canvas.restoreToCount(layerId)
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------------
    // Ações
    // ---------------------------------------------------------------------

    fun undo() {
        if (strokes.isNotEmpty()) {
            val removed = strokes.removeAt(strokes.lastIndex)
            if (removed.data.mode == ToolMode.ERASER) {
                hasEraserStrokes = strokes.any { it.data.mode == ToolMode.ERASER }
            }
            onStrokeCommitted?.invoke()
            invalidate()
        }
    }

    fun clearPageStrokes() {
        strokes.clear()
        hasEraserStrokes = false
        activePoints.clear()
        activeOutline = null
        isStylusDrawing = false
        onStrokeCommitted?.invoke()
        invalidate()
    }

    fun getStrokesCopy(): List<Stroke> = strokes.map { it.data }

    // ---------------------------------------------------------------------
    // Exportação
    // ---------------------------------------------------------------------

    /**
     * Renderiza uma página finita (template/PDF + traços) em um Canvas de
     * PdfDocument, sempre com a tinta em uma camada separada do fundo.
     */
    fun renderPageForExport(
        targetCanvas: Canvas,
        bgBitmap: Bitmap?,
        pageStrokes: List<Stroke>,
        targetWidth: Int,
        targetHeight: Int,
        style: SheetStyle = sheetStyle,
        pressure: Boolean = pressureEnabled
    ) {
        val painter = SheetPainter().apply { this.style = style }
        val rect = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
        painter.drawPage(targetCanvas, rect, bgBitmap)

        if (pageStrokes.isEmpty()) return

        val overlay = Bitmap.createBitmap(
            targetWidth.coerceAtLeast(1),
            targetHeight.coerceAtLeast(1),
            Bitmap.Config.ARGB_8888
        )
        val inkCanvas = Canvas(overlay)
        val ink = InkPainter()
        for (stroke in pageStrokes) {
            val outline = StrokeOutlineBuilder.build(
                points = stroke.points,
                baseWidth = stroke.baseWidth,
                mode = stroke.mode,
                pressureEnabled = pressure
            )
            ink.draw(inkCanvas, outline, stroke.color, stroke.mode)
        }
        targetCanvas.drawBitmap(overlay, 0f, 0f, null)
        overlay.recycle()
    }

    /**
     * Exporta um Canvas Infinito recortado pela área de conteúdo dos traços.
     */
    fun renderInfinitePageForExport(
        targetCanvas: Canvas,
        pageStrokes: List<Stroke>,
        contentBounds: RectF,
        style: SheetStyle = sheetStyle,
        pressure: Boolean = pressureEnabled
    ) {
        val width = ceil(contentBounds.width()).toInt().coerceAtLeast(1)
        val height = ceil(contentBounds.height()).toInt().coerceAtLeast(1)

        val painter = SheetPainter().apply { this.style = style }
        painter.drawInfinite(targetCanvas, RectF(0f, 0f, width.toFloat(), height.toFloat()))

        if (pageStrokes.isEmpty()) return

        val overlay = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val inkCanvas = Canvas(overlay)
        inkCanvas.translate(-contentBounds.left, -contentBounds.top)
        val ink = InkPainter()
        for (stroke in pageStrokes) {
            val outline = StrokeOutlineBuilder.build(
                points = stroke.points,
                baseWidth = stroke.baseWidth,
                mode = stroke.mode,
                pressureEnabled = pressure
            )
            ink.draw(inkCanvas, outline, stroke.color, stroke.mode)
        }
        targetCanvas.drawBitmap(overlay, 0f, 0f, null)
        overlay.recycle()
    }

    companion object {
        val DEFAULT_INK_COLOR: Int = Color.parseColor("#111827")
        val DEFAULT_DARK_INK_COLOR: Int = Color.parseColor("#F8FAFC")

        /**
         * Bounding box dos traços para exportação do Canvas Infinito.
         */
        fun computeContentBounds(pageStrokes: List<Stroke>, margin: Float = 120f): RectF {
            val bounds = RectF()
            var hasPoint = false
            for (stroke in pageStrokes) {
                for (point in stroke.points) {
                    if (!hasPoint) {
                        bounds.set(point.x, point.y, point.x, point.y)
                        hasPoint = true
                    } else {
                        if (point.x < bounds.left) bounds.left = point.x
                        if (point.y < bounds.top) bounds.top = point.y
                        if (point.x > bounds.right) bounds.right = point.x
                        if (point.y > bounds.bottom) bounds.bottom = point.y
                    }
                }
            }
            if (!hasPoint) {
                bounds.set(0f, 0f, PageTemplate.A4.pageWidth.toFloat(), PageTemplate.A4.pageHeight.toFloat())
            }
            bounds.inset(-margin, -margin)
            return bounds
        }
    }
}
