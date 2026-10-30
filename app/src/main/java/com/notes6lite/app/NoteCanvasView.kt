package com.notes6lite.app

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class NoteCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class ToolMode {
        PEN, HIGHLIGHTER, ERASER
    }

    data class Stroke(
        val path: Path,
        val color: Int,
        val strokeWidth: Float,
        val mode: ToolMode
    )

    // Lista de traços da página atual
    private val strokes = mutableListOf<Stroke>()

    // Estado da ferramenta atual
    var currentToolMode: ToolMode = ToolMode.PEN
    var currentColor: Int = Color.parseColor("#111827") // Preto estudo
    var penStrokeWidth: Float = 6f
    var highlighterStrokeWidth: Float = 28f
    var eraserStrokeWidth: Float = 42f

    // PDF de fundo (ou folha em branco A4 padrão: 1654 x 2339 px em ~200 DPI)
    private var backgroundBitmap: Bitmap? = null
    var pageWidth: Int = 1654
        private set
    var pageHeight: Int = 2339
        private set

    // Matriz de transformação (Pan e Zoom com o dedo)
    private val viewMatrix = Matrix()
    private val inverseMatrix = Matrix()
    private var scaleFactor = 1.0f
    private var translateX = 0f
    private var translateY = 0f

    // Controle de traço ativo da S-Pen
    private var activePath: Path? = null
    private var activeMode: ToolMode = ToolMode.PEN
    private var lastPageX = 0f
    private var lastPageY = 0f
    private var isStylusDrawing = false

    // Controle de Pan/Zoom com os dedos (TOOL_TYPE_FINGER)
    private var lastFingerX = 0f
    private var lastFingerY = 0f
    private var isFingerPanning = false
    private var activeFingerPointerId = MotionEvent.INVALID_POINTER_ID

    // Objetos Paint pré-alocados para zero Garbage Collection durante o onDraw
    private val pageBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }
    private val pageShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#CBD5E1")
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E2E8F0")
        style = Paint.Style.STROKE
        strokeWidth = 1.5f
    }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val clearXfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)

    private val scaleGestureDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                // Só permite zoom se a S-Pen não estiver riscando no momento
                if (isStylusDrawing) return false
                val prevScale = scaleFactor
                scaleFactor = max(0.4f, min(scaleFactor * detector.scaleFactor, 4.5f))
                val factor = scaleFactor / prevScale

                // Mantém o foco do zoom no centro dos dedos
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
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (oldw == 0 && oldh == 0 && w > 0 && h > 0) {
            fitPageToScreen()
        }
    }

    fun fitPageToScreen() {
        if (width == 0 || height == 0) return
        val scaleX = (width * 0.94f) / pageWidth.toFloat()
        val scaleY = (height * 0.94f) / pageHeight.toFloat()
        scaleFactor = min(scaleX, scaleY)
        translateX = (width - pageWidth * scaleFactor) / 2f
        translateY = max(24f, (height - pageHeight * scaleFactor) / 2f)
        updateMatrix()
        invalidate()
    }

    private fun updateMatrix() {
        viewMatrix.reset()
        viewMatrix.postScale(scaleFactor, scaleFactor)
        viewMatrix.postTranslate(translateX, translateY)
        viewMatrix.invert(inverseMatrix)
    }

    private fun screenToPageCoords(screenX: Float, screenY: Float): FloatArray {
        val pts = floatArrayOf(screenX, screenY)
        inverseMatrix.mapPoints(pts)
        return pts
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val action = event.actionMasked
        val actionIndex = event.actionIndex
        val toolType = event.getToolType(actionIndex)

        // 1. Verifica se existe algum ponteiro de S-Pen (STYLUS ou ERASER) no evento
        var stylusIndex = -1
        for (i in 0 until event.pointerCount) {
            val t = event.getToolType(i)
            if (t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER) {
                stylusIndex = i
                break
            }
        }

        // 2. Se a S-Pen estiver na tela, ela tem prioridade absoluta (Rejeição de Palma Total)
        if (stylusIndex != -1 || toolType == MotionEvent.TOOL_TYPE_STYLUS || toolType == MotionEvent.TOOL_TYPE_ERASER) {
            isFingerPanning = false
            activeFingerPointerId = MotionEvent.INVALID_POINTER_ID
            val idx = if (stylusIndex != -1) stylusIndex else actionIndex
            handleStylusEvent(event, action, idx)
            return true
        }

        // 3. Caso contrário, se for toque de dedo (TOOL_TYPE_FINGER), faz apenas Pan e Zoom!
        if (toolType == MotionEvent.TOOL_TYPE_FINGER) {
            handleFingerPanAndZoom(event, action)
            return true
        }

        return super.onTouchEvent(event)
    }

    private fun handleStylusEvent(event: MotionEvent, action: Int, pointerIndex: Int) {
        val pts = screenToPageCoords(event.getX(pointerIndex), event.getY(pointerIndex))
        val pageX = pts[0]
        val pageY = pts[1]

        // Verifica se a ponta borracha ou o botão físico da S-Pen está pressionado
        val isStylusEraserButton =
            event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_ERASER ||
            (event.buttonState and MotionEvent.BUTTON_STYLUS_PRIMARY) != 0

        when (action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                activeMode = if (isStylusEraserButton) ToolMode.ERASER else currentToolMode
                activePath = Path().apply {
                    moveTo(pageX, pageY)
                    // Pequeno deslocamento inicial para permitir desenhar pontos isolados (dots)
                    lineTo(pageX + 0.1f, pageY + 0.1f)
                }
                lastPageX = pageX
                lastPageY = pageY
                isStylusDrawing = true
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                val path = activePath ?: return
                // Processa todos os pontos históricos capturados pelo digitalizador da S-Pen (Zero Lag)
                val historySize = event.historySize
                for (h in 0 until historySize) {
                    val histPts = screenToPageCoords(
                        event.getHistoricalX(pointerIndex, h),
                        event.getHistoricalY(pointerIndex, h)
                    )
                    val hx = histPts[0]
                    val hy = histPts[1]
                    val midX = (lastPageX + hx) / 2f
                    val midY = (lastPageY + hy) / 2f
                    path.quadTo(lastPageX, lastPageY, midX, midY)
                    lastPageX = hx
                    lastPageY = hy
                }

                // Ponto atual
                val midX = (lastPageX + pageX) / 2f
                val midY = (lastPageY + pageY) / 2f
                path.quadTo(lastPageX, lastPageY, midX, midY)
                lastPageX = pageX
                lastPageY = pageY
                invalidate()
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL -> {
                val path = activePath
                if (path != null) {
                    path.lineTo(lastPageX, lastPageY)
                    val width = when (activeMode) {
                        ToolMode.PEN -> penStrokeWidth
                        ToolMode.HIGHLIGHTER -> highlighterStrokeWidth
                        ToolMode.ERASER -> eraserStrokeWidth
                    }
                    strokes.add(
                        Stroke(
                            path = path,
                            color = currentColor,
                            strokeWidth = width,
                            mode = activeMode
                        )
                    )
                }
                activePath = null
                isStylusDrawing = false
                invalidate()
            }
        }
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
                    val idx = event.findPointerIndex(activeFingerPointerId)
                    if (idx != -1) {
                        val dx = event.getX(idx) - lastFingerX
                        val dy = event.getY(idx) - lastFingerY
                        // Filtro mínimo de 2px para ignorar vibração de palma apoiada
                        if (abs(dx) > 2f || abs(dy) > 2f) {
                            translateX += dx
                            translateY += dy
                            updateMatrix()
                            invalidate()
                            lastFingerX = event.getX(idx)
                            lastFingerY = event.getY(idx)
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

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Fundo da mesa de estudo (cinza suave)
        canvas.drawColor(Color.parseColor("#E2E8F0"))

        canvas.save()
        canvas.concat(viewMatrix)

        // 1. Desenha a folha ou o PDF de fundo
        val pageRect = RectF(0f, 0f, pageWidth.toFloat(), pageHeight.toFloat())
        canvas.drawRect(pageRect, pageBgPaint)

        val bg = backgroundBitmap
        if (bg != null && !bg.isRecycled) {
            canvas.drawBitmap(bg, null, pageRect, null)
        } else {
            // Se não houver PDF carregado, desenha pauta pontilhada/quadriculada suave para estudo
            val step = 60f
            var y = step
            while (y < pageHeight) {
                canvas.drawLine(0f, y, pageWidth.toFloat(), y, gridPaint)
                y += step
            }
            var x = step
            while (x < pageWidth) {
                canvas.drawLine(x, 0f, x, pageHeight.toFloat(), gridPaint)
                x += step
            }
        }

        // Borda da folha
        canvas.drawRect(pageRect, pageShadowPaint)

        // 2. Abre camada isolada para os traços da S-Pen (permite que a borracha apague só a tinta!)
        val layerId = canvas.saveLayer(pageRect, null)

        for (stroke in strokes) {
            drawSingleStroke(canvas, stroke.path, stroke.color, stroke.strokeWidth, stroke.mode)
        }

        val currentPath = activePath
        if (currentPath != null) {
            val activeWidth = when (activeMode) {
                ToolMode.PEN -> penStrokeWidth
                ToolMode.HIGHLIGHTER -> highlighterStrokeWidth
                ToolMode.ERASER -> eraserStrokeWidth
            }
            drawSingleStroke(canvas, currentPath, currentColor, activeWidth, activeMode)
        }

        canvas.restoreToCount(layerId)
        canvas.restore()
    }

    private fun drawSingleStroke(
        canvas: Canvas,
        path: Path,
        color: Int,
        width: Float,
        mode: ToolMode
    ) {
        strokePaint.strokeWidth = width
        when (mode) {
            ToolMode.PEN -> {
                strokePaint.xfermode = null
                strokePaint.color = color
                strokePaint.alpha = 255
            }
            ToolMode.HIGHLIGHTER -> {
                strokePaint.xfermode = null
                strokePaint.color = color
                strokePaint.alpha = 95 // Translúcido para grifar textos do PDF
            }
            ToolMode.ERASER -> {
                strokePaint.xfermode = clearXfermode
                strokePaint.color = Color.TRANSPARENT
                strokePaint.alpha = 255
            }
        }
        canvas.drawPath(path, strokePaint)
        strokePaint.xfermode = null
    }

    // --- Ações de Ferramentas e Gerenciamento de Página ---

    fun undo() {
        if (strokes.isNotEmpty()) {
            strokes.removeAt(strokes.lastIndex)
            invalidate()
        }
    }

    fun clearPageStrokes() {
        strokes.clear()
        invalidate()
    }

    fun getStrokesCopy(): List<Stroke> = strokes.toList()

    fun setPageContent(bitmap: Bitmap?, savedStrokes: List<Stroke>?) {
        backgroundBitmap = bitmap
        if (bitmap != null) {
            pageWidth = bitmap.width
            pageHeight = bitmap.height
        } else {
            pageWidth = 1654
            pageHeight = 2339
        }
        strokes.clear()
        if (savedStrokes != null) {
            strokes.addAll(savedStrokes)
        }
        fitPageToScreen()
    }

    /**
     * Renderiza qualquer página (fundo + anotações) diretamente no Canvas de um PdfDocument
     * para exportação em alta qualidade.
     */
    fun renderPageForExport(
        targetCanvas: Canvas,
        bgBitmap: Bitmap?,
        pageStrokes: List<Stroke>,
        targetWidth: Int,
        targetHeight: Int
    ) {
        val rect = RectF(0f, 0f, targetWidth.toFloat(), targetHeight.toFloat())
        targetCanvas.drawRect(rect, pageBgPaint)
        if (bgBitmap != null && !bgBitmap.isRecycled) {
            targetCanvas.drawBitmap(bgBitmap, null, rect, null)
        }

        val bitmapOverlay = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
        val inkCanvas = Canvas(bitmapOverlay)
        for (s in pageStrokes) {
            drawSingleStroke(inkCanvas, s.path, s.color, s.strokeWidth, s.mode)
        }
        targetCanvas.drawBitmap(bitmapOverlay, 0f, 0f, null)
        bitmapOverlay.recycle()
    }
}
