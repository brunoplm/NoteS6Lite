package com.notes6lite.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import com.notes6lite.app.domain.model.DocumentViewMode
import com.notes6lite.app.domain.model.EraserMode
import com.notes6lite.app.domain.model.InkPoint
import com.notes6lite.app.domain.model.PageTemplate
import com.notes6lite.app.domain.model.PenType
import com.notes6lite.app.domain.model.SheetStyle
import com.notes6lite.app.domain.model.Stroke
import com.notes6lite.app.domain.model.TextElement
import com.notes6lite.app.domain.model.ToolMode
import com.notes6lite.app.render.InkPainter
import com.notes6lite.app.render.SheetPainter
import com.notes6lite.app.render.StrokeOutline
import com.notes6lite.app.render.StrokeOutlineBuilder
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
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

    var penType: PenType = PenType.BALLPOINT
        set(value) {
            field = value
            when (value) {
                PenType.BALLPOINT -> currentToolMode = ToolMode.PEN
                PenType.FOUNTAIN -> currentToolMode = ToolMode.PEN
                PenType.HIGHLIGHTER -> currentToolMode = ToolMode.HIGHLIGHTER
            }
            invalidate()
        }

    var currentColor: Int = DEFAULT_INK_COLOR

    var penStrokeWidth: Float = 6.5f
    var highlighterStrokeWidth: Float = 28f
    var eraserStrokeWidth: Float = 42f
    var textFontSize: Float = 20f

    /**
     * Como a borracha apaga: objeto (remove o traço inteiro) ou precisa
     * (PorterDuff.CLEAR na camada de tinta).
     */
    var eraserMode: EraserMode = EraserMode.STROKE
        set(value) {
            if (field == value) return
            field = value
            invalidate()
        }

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
    // Modo Folha de Rascunho Lateral (Ao lado do PDF)
    // ---------------------------------------------------------------------

    var isSideScratchpadEnabled: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            updatePageRect()
            fitPageToScreen()
            invalidate()
        }

    val isScratchpadActive: Boolean
        get() = isSideScratchpadEnabled && backgroundBitmap != null

    val effectivePageWidth: Int
        get() = if (isScratchpadActive) (pageWidth * 1.55f).toInt() else pageWidth

    // ---------------------------------------------------------------------
    // Elementos de Texto (Digitação no Canvas)
    // ---------------------------------------------------------------------

    val textElements = ArrayList<TextElement>()
    var onCanvasTapForText: ((x: Float, y: Float) -> Unit)? = null
    var onTextElementTapped: ((TextElement) -> Unit)? = null

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.SANS_SERIF
    }

    // ---------------------------------------------------------------------
    // Botão Físico da Caneta (Compatibilidade Ampla)
    // ---------------------------------------------------------------------

    private var isHardwareStylusButtonPressed: Boolean = false

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY ||
            keyCode == KeyEvent.KEYCODE_BUTTON_1 ||
            keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY ||
            keyCode == KeyEvent.KEYCODE_BUTTON_2) {
            isHardwareStylusButtonPressed = true
            invalidate()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_PRIMARY ||
            keyCode == KeyEvent.KEYCODE_BUTTON_1 ||
            keyCode == KeyEvent.KEYCODE_STYLUS_BUTTON_SECONDARY ||
            keyCode == KeyEvent.KEYCODE_BUTTON_2) {
            isHardwareStylusButtonPressed = false
            invalidate()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    fun isStylusEraserEvent(event: MotionEvent, pointerIndex: Int): Boolean {
        val toolType = event.getToolType(pointerIndex)
        val buttonState = event.buttonState
        return (buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0 ||
               (buttonState and MotionEvent.BUTTON_SECONDARY) != 0 ||
               (buttonState and MotionEvent.BUTTON_STYLUS_SECONDARY) != 0 ||
               (buttonState and MotionEvent.BUTTON_TERTIARY) != 0 ||
               toolType == MotionEvent.TOOL_TYPE_ERASER ||
               isHardwareStylusButtonPressed
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
    // Borracha: velocidade, hit-test de objeto e undo de exclusões
    // ---------------------------------------------------------------------

    /** Último ponto em coordenadas de tela, usado no cálculo de velocidade. */
    private var lastEraserScreenX = 0f
    private var lastEraserScreenY = 0f
    private var lastEraserEventTime = 0L

    /** Multiplicador suavizado do raio da borracha (1.0x .. 2.5x). */
    private var eraserSpeedScale = 1f

    /** Raio efetivo do último toque da borracha (para o preview na tela). */
    private var eraserPreviewRadius = 0f
    private var eraserPreviewX = 0f
    private var eraserPreviewY = 0f

    private val eraserHitPath = Path()
    private val eraserScratchPath = Path()
    private val eraserHitBounds = RectF()

    /** Traços removidos pela borracha de objeto, para desfazer em lote. */
    private val erasedBatches = ArrayDeque<List<PageStroke>>()

    private val eraserPreviewPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.parseColor("#94A3B8")
    }

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
        isFocusable = true
        isFocusableInTouchMode = true
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
        erasedBatches.clear()
        eraserPreviewRadius = 0f
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
            val effWidth = effectivePageWidth.coerceAtLeast(1).toFloat()
            val scaleX = (width * 0.94f) / effWidth
            val scaleY = (height * 0.94f) / pageHeight.coerceAtLeast(1).toFloat()
            scaleFactor = min(scaleX, scaleY).coerceAtLeast(0.05f)
            translateX = (width - effWidth * scaleFactor) / 2f
            if (isContinuousActive) {
                val currentTop = getContinuousPageTop(activeContinuousPageIndex)
                translateY = 24f - currentTop * scaleFactor
            } else {
                translateY = max(24f, (height - pageHeight * scaleFactor) / 2f)
            }
        }

        updateMatrix()
        invalidate()
    }

    private fun updatePageRect() {
        pageRect.set(0f, 0f, effectivePageWidth.toFloat(), pageHeight.toFloat())
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

    fun getEffectiveInkColor(color: Int): Int {
        if (isDarkBackground) {
            if (color == Color.BLACK || color == DEFAULT_INK_COLOR || Color.luminance(color) < 0.25f) {
                return DEFAULT_DARK_INK_COLOR
            }
        }
        return color
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

    private var textDownX = 0f
    private var textDownY = 0f
    private var isPotentialTextTap = false

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked

        // Modo Texto: toque rápido na folha aciona digitação ou edição
        if (currentToolMode == ToolMode.TEXT) {
            when (action) {
                MotionEvent.ACTION_DOWN -> {
                    textDownX = event.x
                    textDownY = event.y
                    isPotentialTextTap = true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (hypot(event.x - textDownX, event.y - textDownY) > 20f) {
                        isPotentialTextTap = false
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isPotentialTextTap && hypot(event.x - textDownX, event.y - textDownY) < 20f) {
                        mapToPage(event.x, event.y, tempPoint)
                        val pageX = tempPoint[0]
                        val pageY = tempPoint[1]
                        val hit = textElements.findLast { el ->
                            val textBoundsW = max(el.fontSize * el.text.length * 0.6f, 60f)
                            val textBoundsH = max(el.fontSize * 1.5f, 40f)
                            pageX >= el.x && pageX <= el.x + textBoundsW &&
                                pageY >= el.y - 10f && pageY <= el.y + textBoundsH
                        }
                        if (hit != null) {
                            onTextElementTapped?.invoke(hit)
                        } else {
                            onCanvasTapForText?.invoke(pageX, pageY)
                        }
                        return true
                    }
                }
            }
        }

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
        val isStylusEraserButton = isStylusEraserEvent(event, pointerIndex)

        if (currentToolMode == ToolMode.TEXT && !isStylusEraserButton) {
            return
        }

        activeMode = if (isStylusEraserButton) ToolMode.ERASER else currentToolMode
        activeColor = getEffectiveInkColor(currentColor)
        activeBaseWidth = when (activeMode) {
            ToolMode.PEN -> penStrokeWidth
            ToolMode.HIGHLIGHTER -> highlighterStrokeWidth
            ToolMode.ERASER -> eraserStrokeWidth
            ToolMode.TEXT -> penStrokeWidth
        }

        val rawPressure = event.getPressure(pointerIndex).coerceIn(0f, 1f)
        smoothedPressure = if (rawPressure < 0.05f) 0.05f else rawPressure

        mapToPage(event.getX(pointerIndex), event.getY(pointerIndex), tempPoint)
        val docX = tempPoint[0]
        val docY = tempPoint[1]

        val targetPage = if (isContinuousActive) getContinuousPageIndexAt(docY) else 0
        activeContinuousPageIndex = targetPage
        val pageTop = if (isContinuousActive) getContinuousPageTop(targetPage) else 0f

        lastPageX = docX
        lastPageY = docY - pageTop

        // Reinicia a medição de velocidade da borracha.
        lastEraserScreenX = event.getX(pointerIndex)
        lastEraserScreenY = event.getY(pointerIndex)
        lastEraserEventTime = event.eventTime
        eraserSpeedScale = 1f
        eraserPreviewRadius = eraserRadiusFor(1f)
        eraserPreviewX = lastPageX
        eraserPreviewY = lastPageY

        activePoints.clear()

        val objectEraser = activeMode == ToolMode.ERASER &&
            eraserMode == EraserMode.STROKE

        if (objectEraser) {
            // Borracha de objeto não grava tinta: remove os traços tocados.
            if (isContinuousActive) {
                eraseStrokesOnContinuousPage(activeContinuousPageIndex, lastPageX, lastPageY, eraserPreviewRadius, lastPageX, lastPageY)
            } else {
                eraseStrokesAt(lastPageX, lastPageY, eraserPreviewRadius, lastPageX, lastPageY)
            }
            activeOutline = null
        } else {
            activePoints.add(InkPoint(lastPageX, lastPageY, smoothedPressure))
            activeOutline = StrokeOutlineBuilder.build(
                points = activePoints,
                baseWidth = activeBaseWidth,
                mode = activeMode,
                pressureEnabled = pressureEnabled
            )
        }
        lastOutlineBuildTime = event.eventTime

        isStylusDrawing = true
        activeStylusPointerId = event.getPointerId(pointerIndex)
        invalidate()
    }

    private fun appendMove(event: MotionEvent, pointerIndex: Int) {
        // Monitora o botão lateral da S-Pen durante o movimento (troca imediata para borracha e retorno ao soltar)
        val isStylusEraser = isStylusEraserEvent(event, pointerIndex)
        val intendedMode = if (isStylusEraser) ToolMode.ERASER else currentToolMode

        if (intendedMode != activeMode) {
            commitStroke()
            startStroke(event, pointerIndex)
            return
        }

        if (activeMode == ToolMode.ERASER && eraserMode == EraserMode.STROKE) {
            appendObjectErase(event, pointerIndex)
            return
        }

        val pageTop = if (isContinuousActive) getContinuousPageTop(activeContinuousPageIndex) else 0f
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
                tempPoint[1] - pageTop,
                event.getHistoricalPressure(pointerIndex, h)
            )
        }

        mapToPage(event.getX(pointerIndex), event.getY(pointerIndex), tempPoint)
        appendPoint(tempPoint[0], tempPoint[1] - pageTop, event.getPressure(pointerIndex))

        if (activeMode == ToolMode.ERASER) {
            // A borracha precisa usa a velocidade do gesto para engordar.
            val scale = updateEraserSpeed(
                event.eventTime,
                event.getX(pointerIndex),
                event.getY(pointerIndex)
            )
            eraserSpeedScale = scale
            eraserPreviewRadius = eraserRadiusFor(scale)
            eraserPreviewX = tempPoint[0]
            eraserPreviewY = tempPoint[1]
        }

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

    /**
     * Borracha de objeto: apaga tudo o que a ponta (ou o segmento percorrido)
     * intersectar e remove os traços inteiros da lista de uma só vez.
     */
    private fun appendObjectErase(event: MotionEvent, pointerIndex: Int) {
        val historySize = event.historySize
        var previousX = lastPageX
        var previousY = lastPageY

        val pageTop = if (isContinuousActive) getContinuousPageTop(activeContinuousPageIndex) else 0f

        for (h in 0 until historySize) {
            mapToPage(
                event.getHistoricalX(pointerIndex, h),
                event.getHistoricalY(pointerIndex, h),
                tempPoint
            )
            val curX = tempPoint[0]
            val curY = tempPoint[1] - pageTop
            val scale = updateEraserSpeed(
                event.eventTime,
                event.getHistoricalX(pointerIndex, h),
                event.getHistoricalY(pointerIndex, h)
            )
            eraserSpeedScale = scale
            eraserPreviewRadius = eraserRadiusFor(scale)
            if (isContinuousActive) {
                eraseStrokesOnContinuousPage(
                    activeContinuousPageIndex,
                    curX,
                    curY,
                    eraserPreviewRadius,
                    previousX,
                    previousY
                )
            } else {
                eraseStrokesAt(
                    curX,
                    curY,
                    eraserPreviewRadius,
                    previousX,
                    previousY
                )
            }
            previousX = curX
            previousY = curY
        }

        mapToPage(event.getX(pointerIndex), event.getY(pointerIndex), tempPoint)
        val curX = tempPoint[0]
        val curY = tempPoint[1] - pageTop
        val scale = updateEraserSpeed(
            event.eventTime,
            event.getX(pointerIndex),
            event.getY(pointerIndex)
        )
        eraserSpeedScale = scale
        eraserPreviewRadius = eraserRadiusFor(scale)
        eraserPreviewX = curX
        eraserPreviewY = curY
        if (isContinuousActive) {
            eraseStrokesOnContinuousPage(activeContinuousPageIndex, curX, curY, eraserPreviewRadius, previousX, previousY)
        } else {
            eraseStrokesAt(curX, curY, eraserPreviewRadius, previousX, previousY)
        }

        lastPageX = curX
        lastPageY = curY
        invalidate()
    }

    /**
     * Velocidade do gesto em pixels de tela por milissegundo (independe do
     * zoom). Abaixo de [ERASER_SPEED_SLOW] o raio fica em 1.0x; acima de
     * [ERASER_SPEED_FAST] chega a 2.5x. A transição é contínua para o traço
     * não "pular" de tamanho.
     */
    private fun updateEraserSpeed(eventTime: Long, screenX: Float, screenY: Float): Float {
        val dt = (eventTime - lastEraserEventTime).coerceAtLeast(1L)
        val distance = hypot(screenX - lastEraserScreenX, screenY - lastEraserScreenY)
        val speed = distance / dt

        lastEraserScreenX = screenX
        lastEraserScreenY = screenY
        lastEraserEventTime = eventTime

        val target = when {
            speed <= ERASER_SPEED_SLOW -> 1f
            speed <= ERASER_SPEED_FAST -> {
                val t = (speed - ERASER_SPEED_SLOW) /
                    (ERASER_SPEED_FAST - ERASER_SPEED_SLOW)
                1f + t * 0.5f
            }

            else -> {
                val t = ((speed - ERASER_SPEED_FAST) / (ERASER_SPEED_FAST * 1.6f))
                    .coerceIn(0f, 1f)
                1.5f + t * 1.0f
            }
        }

        // Suavização: o raio acompanha a mão, sem tremer a cada evento.
        eraserSpeedScale = (eraserSpeedScale * 0.55f + target * 0.45f)
            .coerceIn(1f, StrokeOutlineBuilder.MAX_ERASER_SCALE)
        return eraserSpeedScale
    }

    private fun eraserRadiusFor(scale: Float): Float =
        (eraserStrokeWidth * scale.coerceIn(1f, StrokeOutlineBuilder.MAX_ERASER_SCALE)) / 2f

    /**
     * Remove da página os traços que colidem com a ponta da borracha.
     * Primeiro descarta por caixa delimitadora (barato) e só então faz a
     * interseção exata de Path, que é a parte cara.
     */
    private fun eraseStrokesAt(
        x: Float,
        y: Float,
        radius: Float,
        fromX: Float,
        fromY: Float
    ): Boolean {
        if (strokes.isEmpty()) return false

        eraserHitPath.reset()
        eraserHitPath.addCircle(x, y, radius, Path.Direction.CW)
        if (abs(fromX - x) > 0.01f || abs(fromY - y) > 0.01f) {
            // Segmento percorrido: cápsula aproximada para não deixar buracos
            // entre dois eventos da S-Pen.
            eraserHitPath.addRoundRect(
                min(fromX, x) - radius,
                min(fromY, y) - radius,
                max(fromX, x) + radius,
                max(fromY, y) + radius,
                radius,
                radius,
                Path.Direction.CW
            )
        }
        eraserHitPath.computeBounds(eraserHitBounds, true)

        var removedAny = false
        val removed = ArrayList<PageStroke>()
        val iterator = strokes.iterator()
        while (iterator.hasNext()) {
            val pageStroke = iterator.next()
            if (!RectF.intersects(pageStroke.outline.bounds, eraserHitBounds)) continue

            eraserScratchPath.reset()
            val intersects = eraserScratchPath.op(
                pageStroke.outline.path,
                eraserHitPath,
                Path.Op.INTERSECT
            )
            if (intersects && !eraserScratchPath.isEmpty) {
                removed.add(pageStroke)
                iterator.remove()
                removedAny = true
            }
        }

        if (removed.isNotEmpty()) {
            erasedBatches.addLast(removed)
            if (erasedBatches.size > MAX_ERASED_BATCHES) erasedBatches.removeFirst()
            hasEraserStrokes = strokes.any { it.data.mode == ToolMode.ERASER }
        }
        return removedAny
    }

    private fun appendPoint(x: Float, y: Float, pressure: Float) {
        if (abs(x - lastPageX) < 0.05f && abs(y - lastPageY) < 0.05f) return

        if (activeMode == ToolMode.ERASER) {
            // Em traços de borracha o campo `pressure` carrega o multiplicador
            // de raio (1.0x .. 2.5x) usado pelo StrokeOutlineBuilder.
            activePoints.add(
                InkPoint(x, y, eraserSpeedScale.coerceIn(1f, StrokeOutlineBuilder.MAX_ERASER_SCALE))
            )
        } else {
            val rawPressure = pressure.coerceIn(0f, 1f)
            // Suavização exponencial evita serrilhado na largura do traço.
            smoothedPressure = (smoothedPressure * 0.65f + rawPressure * 0.35f)
                .coerceIn(0.05f, 1f)
            activePoints.add(InkPoint(x, y, smoothedPressure))
        }

        lastPageX = x
        lastPageY = y
    }

    private fun commitStroke() {
        if (!isStylusDrawing) return
        isStylusDrawing = false
        activeStylusPointerId = MotionEvent.INVALID_POINTER_ID

        val objectEraser = activeMode == ToolMode.ERASER &&
            eraserMode == EraserMode.STROKE

        if (!objectEraser && activePoints.isNotEmpty()) {
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
            if (isContinuousActive) {
                addStrokeToContinuousPage(activeContinuousPageIndex, PageStroke(data, outline))
            } else {
                strokes.add(PageStroke(data, outline))
                if (activeMode == ToolMode.ERASER) hasEraserStrokes = true
            }
        }

        activePoints.clear()
        activeOutline = null
        eraserPreviewRadius = 0f
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
        if (isContinuousActive) {
            drawContinuousContent(canvas, visibleRect)
        } else if (pageTemplate.isInfinite) {
            sheetPainter.drawInfinite(canvas, visibleRect)
        } else if (isScratchpadActive) {
            val pdfRect = RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
            val scratchRect = RectF(pageWidth.toFloat(), 0f, effectivePageWidth.toFloat(), pageHeight.toFloat())
            sheetPainter.drawScratchpadPage(canvas, pdfRect, scratchRect, backgroundBitmap)
        } else {
            pageRect.set(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
            sheetPainter.drawPage(canvas, pageRect, backgroundBitmap)
        }

        // 2. Camada isolada de tinta: a borracha só apaga aqui.
        // Sem traços de borracha, desenhar direto evita um saveLayer por frame.
        if (!isContinuousActive) {
            val needsInkLayer = hasEraserStrokes ||
                (isStylusDrawing && activeMode == ToolMode.ERASER)
            val layerBounds: RectF? = if (pageTemplate.isInfinite) null else pageRect
            val layerId = if (needsInkLayer) canvas.saveLayer(layerBounds, null) else -1

            for (pageStroke in strokes) {
                if (RectF.intersects(pageStroke.outline.bounds, visibleRect)) {
                    inkPainter.draw(
                        canvas,
                        pageStroke.outline,
                        getEffectiveInkColor(pageStroke.data.color),
                        pageStroke.data.mode
                    )
                }
            }

            activeOutline?.let { outline ->
                inkPainter.draw(canvas, outline, getEffectiveInkColor(activeColor), activeMode)
            }

            // Preview do raio da borracha
            if (isStylusDrawing && activeMode == ToolMode.ERASER && eraserPreviewRadius > 0f) {
                canvas.drawCircle(
                    eraserPreviewX,
                    eraserPreviewY,
                    eraserPreviewRadius,
                    eraserPreviewPaint
                )
            }

            if (needsInkLayer) {
                canvas.restoreToCount(layerId)
            }

            // 3. Desenho de elementos de texto na página
            for (el in textElements) {
                textPaint.textSize = el.fontSize
                textPaint.color = getEffectiveInkColor(el.color)
                val lines = el.text.split("\n")
                var lineY = el.y + el.fontSize
                for (line in lines) {
                    canvas.drawText(line, el.x, lineY, textPaint)
                    lineY += el.fontSize * 1.3f
                }
            }
        } else {
            // Desenho do traço ativo durante a escrita na página correspondente
            activeOutline?.let { outline ->
                val pageTop = getContinuousPageTop(activeContinuousPageIndex)
                canvas.save()
                canvas.translate(0f, pageTop)
                inkPainter.draw(canvas, outline, getEffectiveInkColor(activeColor), activeMode)
                if (isStylusDrawing && activeMode == ToolMode.ERASER && eraserPreviewRadius > 0f) {
                    canvas.drawCircle(
                        eraserPreviewX,
                        eraserPreviewY,
                        eraserPreviewRadius,
                        eraserPreviewPaint
                    )
                }
                canvas.restore()
            }
        }
        canvas.restore()
    }

    // ---------------------------------------------------------------------
    // Ações
    // ---------------------------------------------------------------------

    fun undo() {
        // A borracha de objeto remove traços inteiros: desfazer devolve o
        // último lote apagado antes de voltar a remover o último traço.
        val erasedBatch = erasedBatches.removeLastOrNull()
        if (erasedBatch != null) {
            strokes.addAll(erasedBatch)
            hasEraserStrokes = strokes.any { it.data.mode == ToolMode.ERASER }
            onStrokeCommitted?.invoke()
            invalidate()
            return
        }

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
        textElements.clear()
        erasedBatches.clear()
        hasEraserStrokes = false
        activePoints.clear()
        activeOutline = null
        isStylusDrawing = false
        eraserPreviewRadius = 0f
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
        pressure: Boolean = pressureEnabled,
        includeScratchpad: Boolean = false,
        texts: List<TextElement> = emptyList()
    ) {
        val painter = SheetPainter().apply { this.style = style }
        if (includeScratchpad && bgBitmap != null && targetWidth > bgBitmap.width) {
            val pdfRect = RectF(0f, 0f, bgBitmap.width.toFloat(), targetHeight.toFloat())
            val scratchRect = RectF(bgBitmap.width.toFloat(), 0f, targetWidth.toFloat(), targetHeight.toFloat())
            painter.drawScratchpadPage(targetCanvas, pdfRect, scratchRect, bgBitmap)
        } else {
            val rect = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
            painter.drawPage(targetCanvas, rect, bgBitmap)
        }

        // Renderiza textos inseridos
        val expPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = android.graphics.Typeface.SANS_SERIF
        }
        for (el in texts) {
            expPaint.textSize = el.fontSize
            expPaint.color = el.color
            val lines = el.text.split("\n")
            var lineY = el.y + el.fontSize
            for (line in lines) {
                targetCanvas.drawText(line, el.x, lineY, expPaint)
                lineY += el.fontSize * 1.3f
            }
        }

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
            val colorToDraw = if (style.isDark && bgBitmap == null && (stroke.color == Color.BLACK || stroke.color == DEFAULT_INK_COLOR || Color.luminance(stroke.color) < 0.25f)) {
                DEFAULT_DARK_INK_COLOR
            } else {
                stroke.color
            }
            ink.draw(inkCanvas, outline, colorToDraw, stroke.mode)
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
            val colorToDraw = if (style.isDark && (stroke.color == Color.BLACK || stroke.color == DEFAULT_INK_COLOR || Color.luminance(stroke.color) < 0.25f)) {
                DEFAULT_DARK_INK_COLOR
            } else {
                stroke.color
            }
            ink.draw(inkCanvas, outline, colorToDraw, stroke.mode)
        }
        targetCanvas.drawBitmap(overlay, 0f, 0f, null)
        overlay.recycle()
    }

    // ---------------------------------------------------------------------
    // Métodos Auxiliares para o Modo de Rolagem Contínua (DocumentViewMode.CONTINUOUS)
    // ---------------------------------------------------------------------

    fun scrollToPage(pageIndex: Int) {
        if (!isContinuousActive) return
        val top = getContinuousPageTop(pageIndex)
        translateY = 24f - top * scaleFactor
        updateMatrix()
        invalidate()
    }

    private fun getContinuousPageTop(pageIndex: Int): Float {
        val provider = continuousProvider ?: return 0f
        var currentY = 0f
        val count = provider.getPageCount()
        for (i in 0 until pageIndex.coerceAtMost(count)) {
            currentY += provider.getPageHeight(i) + CONTINUOUS_PAGE_SPACING
        }
        return currentY
    }

    private fun getContinuousPageIndexAt(docY: Float): Int {
        val provider = continuousProvider ?: return 0
        val count = provider.getPageCount()
        var currentY = 0f
        for (i in 0 until count) {
            val h = provider.getPageHeight(i)
            if (docY <= currentY + h + CONTINUOUS_PAGE_SPACING / 2f) {
                return i
            }
            currentY += h + CONTINUOUS_PAGE_SPACING
        }
        return (count - 1).coerceAtLeast(0)
    }

    private fun addStrokeToContinuousPage(pageIndex: Int, stroke: PageStroke) {
        val provider = continuousProvider ?: return
        val existing = provider.getPageStrokes(pageIndex).toMutableList()
        existing.add(stroke.data)
        provider.onPageStrokesChanged(pageIndex, existing)
    }

    private fun eraseStrokesOnContinuousPage(
        pageIndex: Int,
        x: Float,
        y: Float,
        radius: Float,
        fromX: Float,
        fromY: Float
    ) {
        val provider = continuousProvider ?: return
        val currentStrokes = provider.getPageStrokes(pageIndex)
        if (currentStrokes.isEmpty()) return

        eraserHitPath.reset()
        eraserHitPath.addCircle(x, y, radius, Path.Direction.CW)
        if (abs(fromX - x) > 0.01f || abs(fromY - y) > 0.01f) {
            eraserHitPath.addRoundRect(
                min(fromX, x) - radius,
                min(fromY, y) - radius,
                max(fromX, x) + radius,
                max(fromY, y) + radius,
                radius,
                radius,
                Path.Direction.CW
            )
        }
        eraserHitPath.computeBounds(eraserHitBounds, true)

        val updated = ArrayList<Stroke>()
        var removedAny = false
        for (stroke in currentStrokes) {
            val outline = StrokeOutlineBuilder.build(
                stroke.points,
                stroke.baseWidth,
                stroke.mode,
                pressureEnabled
            )
            if (!RectF.intersects(outline.bounds, eraserHitBounds)) {
                updated.add(stroke)
                continue
            }
            eraserScratchPath.reset()
            val intersects = eraserScratchPath.op(outline.path, eraserHitPath, Path.Op.INTERSECT)
            if (intersects && !eraserScratchPath.isEmpty) {
                removedAny = true
            } else {
                updated.add(stroke)
            }
        }
        if (removedAny) {
            provider.onPageStrokesChanged(pageIndex, updated)
        }
    }

    private fun drawContinuousContent(canvas: Canvas, visible: RectF) {
        val provider = continuousProvider ?: return
        val count = provider.getPageCount()
        var currentY = 0f

        val tempPageRect = RectF()
        var mostVisiblePage = 0
        var maxVisibleArea = -1f

        for (i in 0 until count) {
            val pw = provider.getPageWidth(i).toFloat()
            val ph = provider.getPageHeight(i).toFloat()
            tempPageRect.set(0f, currentY, pw, currentY + ph)

            if (RectF.intersects(tempPageRect, visible)) {
                // Cálculo de qual página está mais visível no centro da tela
                val overlapTop = max(tempPageRect.top, visible.top)
                val overlapBottom = min(tempPageRect.bottom, visible.bottom)
                val visibleH = max(0f, overlapBottom - overlapTop)
                if (visibleH > maxVisibleArea) {
                    maxVisibleArea = visibleH
                    mostVisiblePage = i
                }

                // Desenha o fundo da página
                val pageBmp = provider.getPageBitmap(i)
                canvas.save()
                canvas.translate(0f, currentY)
                if (isSideScratchpadEnabled && pageBmp != null) {
                    val pdfRect = RectF(0f, 0f, pageBmp.width.toFloat(), ph)
                    val scratchRect = RectF(pageBmp.width.toFloat(), 0f, pw, ph)
                    sheetPainter.drawScratchpadPage(canvas, pdfRect, scratchRect, pageBmp)
                } else {
                    val localRect = RectF(0f, 0f, pw, ph)
                    sheetPainter.drawPage(canvas, localRect, pageBmp)
                }

                // Desenha os traços já existentes desta página
                val pageStrokes = provider.getPageStrokes(i)
                for (st in pageStrokes) {
                    val outline = StrokeOutlineBuilder.build(
                        st.points,
                        st.baseWidth,
                        st.mode,
                        pressureEnabled
                    )
                    inkPainter.draw(canvas, outline, getEffectiveInkColor(st.color), st.mode)
                }
                canvas.restore()
            }
            currentY += ph + CONTINUOUS_PAGE_SPACING
        }

        if (mostVisiblePage != activeContinuousPageIndex) {
            activeContinuousPageIndex = mostVisiblePage
            onPageChangedListener?.invoke(mostVisiblePage)
        }
    }

    var viewMode: DocumentViewMode = DocumentViewMode.CONTINUOUS
        set(value) {
            if (field == value) return
            field = value
            fitPageToScreen()
            invalidate()
        }

    /**
     * Provedor de dados para páginas no modo de rolagem contínua.
     */
    interface ContinuousDocumentProvider {
        fun getPageCount(): Int
        fun getPageWidth(index: Int): Int
        fun getPageHeight(index: Int): Int
        fun getPageBitmap(index: Int): Bitmap?
        fun getPageStrokes(index: Int): List<Stroke>
        fun onPageStrokesChanged(index: Int, strokes: List<Stroke>)
    }

    var continuousProvider: ContinuousDocumentProvider? = null

    val isContinuousActive: Boolean
        get() = viewMode == DocumentViewMode.CONTINUOUS && (continuousProvider?.getPageCount() ?: 0) > 1

    var onPageChangedListener: ((Int) -> Unit)? = null
    var activeContinuousPageIndex: Int = 0
        private set

    companion object {
        const val CONTINUOUS_PAGE_SPACING = 36f
        val DEFAULT_INK_COLOR: Int = Color.parseColor("#111827")
        val DEFAULT_DARK_INK_COLOR: Int = Color.parseColor("#F8FAFC")

        /** Velocidade (px de tela por ms) abaixo da qual a borracha não engorda. */
        private const val ERASER_SPEED_SLOW = 0.8f

        /** Acima desta velocidade a borracha já está no crescimento máximo. */
        private const val ERASER_SPEED_FAST = 3.0f

        /** Quantos lotes de traços apagados podem ser desfeitos. */
        private const val MAX_ERASED_BATCHES = 24

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
