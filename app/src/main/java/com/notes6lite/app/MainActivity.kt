package com.notes6lite.app

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    private lateinit var canvasView: NoteCanvasView
    private lateinit var tvPageIndicator: TextView

    private lateinit var btnToolPen: Button
    private lateinit var btnToolHighlighter: Button
    private lateinit var btnToolEraser: Button
    private lateinit var btnStrokeThin: Button
    private lateinit var btnStrokeMedium: Button
    private lateinit var btnStrokeThick: Button

    // Gerenciamento de PDF Nativo (PdfRenderer)
    private var pdfFileDescriptor: ParcelFileDescriptor? = null
    private var pdfRenderer: PdfRenderer? = null
    private var currentPageIndex: Int = 0
    private var totalPages: Int = 1

    // Guarda os traços de cada página (índice da página -> lista de traços)
    private val pageStrokesMap = mutableMapOf<Int, List<NoteCanvasView.Stroke>>()

    // Seletor nativo do Android para abrir um arquivo PDF do tablet
    private val openPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            loadPdfFromUri(uri)
        }
    }

    // Seletor nativo do Android para salvar o PDF anotado no tablet
    private val savePdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) {
            exportAnnotatedPdfToUri(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        canvasView = findViewById(R.id.noteCanvasView)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)

        btnToolPen = findViewById(R.id.btnToolPen)
        btnToolHighlighter = findViewById(R.id.btnToolHighlighter)
        btnToolEraser = findViewById(R.id.btnToolEraser)
        btnStrokeThin = findViewById(R.id.btnStrokeThin)
        btnStrokeMedium = findViewById(R.id.btnStrokeMedium)
        btnStrokeThick = findViewById(R.id.btnStrokeThick)

        setupToolbarActions()
        updatePageIndicator()
    }

    private fun setupToolbarActions() {
        // Abrir e Salvar PDF
        findViewById<Button>(R.id.btnOpenPdf).setOnClickListener {
            openPdfLauncher.launch(arrayOf("application/pdf"))
        }

        findViewById<Button>(R.id.btnSavePdf).setOnClickListener {
            val suggestedName = "Estudo_Anotado_${System.currentTimeMillis() / 1000}.pdf"
            savePdfLauncher.launch(suggestedName)
        }

        // Navegação entre páginas (funciona tanto para PDF quanto para caderno em branco)
        findViewById<Button>(R.id.btnPrevPage).setOnClickListener {
            if (currentPageIndex > 0) {
                saveCurrentPageStrokes()
                currentPageIndex--
                renderCurrentPage()
            }
        }

        findViewById<Button>(R.id.btnNextPage).setOnClickListener {
            saveCurrentPageStrokes()
            if (pdfRenderer == null && currentPageIndex + 1 >= totalPages) {
                // No modo caderno livre, avançar cria uma nova folha automaticamente
                totalPages++
            }
            if (currentPageIndex + 1 < totalPages) {
                currentPageIndex++
                renderCurrentPage()
            }
        }

        // Seleção de Ferramentas
        btnToolPen.setOnClickListener {
            selectTool(NoteCanvasView.ToolMode.PEN)
        }
        btnToolHighlighter.setOnClickListener {
            selectTool(NoteCanvasView.ToolMode.HIGHLIGHTER)
        }
        btnToolEraser.setOnClickListener {
            selectTool(NoteCanvasView.ToolMode.ERASER)
        }

        // Botão Desfazer (Undo)
        findViewById<Button>(R.id.btnUndo).setOnClickListener {
            canvasView.undo()
        }

        // Ajuste de Espessura
        btnStrokeThin.setOnClickListener {
            canvasView.penStrokeWidth = 3.5f
            canvasView.highlighterStrokeWidth = 18f
            canvasView.eraserStrokeWidth = 24f
            highlightStrokeButton(btnStrokeThin)
        }
        btnStrokeMedium.setOnClickListener {
            canvasView.penStrokeWidth = 6.5f
            canvasView.highlighterStrokeWidth = 28f
            canvasView.eraserStrokeWidth = 42f
            highlightStrokeButton(btnStrokeMedium)
        }
        btnStrokeThick.setOnClickListener {
            canvasView.penStrokeWidth = 12f
            canvasView.highlighterStrokeWidth = 42f
            canvasView.eraserStrokeWidth = 70f
            highlightStrokeButton(btnStrokeThick)
        }

        // Cores de Estudo
        findViewById<Button>(R.id.btnColorBlack).setOnClickListener {
            canvasView.currentColor = Color.parseColor("#111827")
            if (canvasView.currentToolMode == NoteCanvasView.ToolMode.ERASER) {
                selectTool(NoteCanvasView.ToolMode.PEN)
            }
        }
        findViewById<Button>(R.id.btnColorBlue).setOnClickListener {
            canvasView.currentColor = Color.parseColor("#1D4ED8")
            if (canvasView.currentToolMode == NoteCanvasView.ToolMode.ERASER) {
                selectTool(NoteCanvasView.ToolMode.PEN)
            }
        }
        findViewById<Button>(R.id.btnColorRed).setOnClickListener {
            canvasView.currentColor = Color.parseColor("#DC2626")
            if (canvasView.currentToolMode == NoteCanvasView.ToolMode.ERASER) {
                selectTool(NoteCanvasView.ToolMode.PEN)
            }
        }
        findViewById<Button>(R.id.btnColorGreen).setOnClickListener {
            canvasView.currentColor = Color.parseColor("#059669")
            if (canvasView.currentToolMode == NoteCanvasView.ToolMode.ERASER) {
                selectTool(NoteCanvasView.ToolMode.PEN)
            }
        }
        findViewById<Button>(R.id.btnColorYellow).setOnClickListener {
            canvasView.currentColor = Color.parseColor("#FACC15")
            selectTool(NoteCanvasView.ToolMode.HIGHLIGHTER)
        }

        // Ajustar Zoom na Tela
        findViewById<Button>(R.id.btnResetZoom).setOnClickListener {
            canvasView.fitPageToScreen()
        }
    }

    private fun selectTool(mode: NoteCanvasView.ToolMode) {
        canvasView.currentToolMode = mode
        val activeTint = ColorStateList.valueOf(Color.parseColor("#2563EB"))
        val inactiveTint = ColorStateList.valueOf(Color.parseColor("#334155"))

        btnToolPen.backgroundTintList =
            if (mode == NoteCanvasView.ToolMode.PEN) activeTint else inactiveTint
        btnToolHighlighter.backgroundTintList =
            if (mode == NoteCanvasView.ToolMode.HIGHLIGHTER) activeTint else inactiveTint
        btnToolEraser.backgroundTintList =
            if (mode == NoteCanvasView.ToolMode.ERASER) activeTint else inactiveTint
    }

    private fun highlightStrokeButton(selected: Button) {
        val activeTint = ColorStateList.valueOf(Color.parseColor("#2563EB"))
        val inactiveTint = ColorStateList.valueOf(Color.parseColor("#334155"))
        btnStrokeThin.backgroundTintList = if (selected == btnStrokeThin) activeTint else inactiveTint
        btnStrokeMedium.backgroundTintList = if (selected == btnStrokeMedium) activeTint else inactiveTint
        btnStrokeThick.backgroundTintList = if (selected == btnStrokeThick) activeTint else inactiveTint
    }

    private fun saveCurrentPageStrokes() {
        pageStrokesMap[currentPageIndex] = canvasView.getStrokesCopy()
    }

    private fun loadPdfFromUri(uri: Uri) {
        try {
            closePdfRenderer()
            pageStrokesMap.clear()

            // Copia o PDF selecionado para um arquivo temporário local para acesso seguro via PdfRenderer
            val tempFile = File(cacheDir, "active_study_pdf.pdf")
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output)
                }
            }

            pdfFileDescriptor = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
            pdfRenderer = PdfRenderer(pdfFileDescriptor!!)
            totalPages = pdfRenderer!!.pageCount.coerceAtLeast(1)
            currentPageIndex = 0

            renderCurrentPage()
            Toast.makeText(this, "PDF carregado ($totalPages páginas)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao abrir PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun renderCurrentPage() {
        val renderer = pdfRenderer
        val savedStrokes = pageStrokesMap[currentPageIndex]

        if (renderer != null && currentPageIndex in 0 until renderer.pageCount) {
            val bitmap = renderPdfPageToBitmap(renderer, currentPageIndex)
            canvasView.setPageContent(bitmap, savedStrokes)
        } else {
            canvasView.setPageContent(null, savedStrokes)
        }
        updatePageIndicator()
    }

    private fun renderPdfPageToBitmap(renderer: PdfRenderer, pageIndex: Int): Bitmap {
        val page = renderer.openPage(pageIndex)
        // Escala 2.2x para nitidez em alta resolução na tela do Galaxy Tab S6 Lite
        val width = (page.width * 2.2f).toInt().coerceAtLeast(1200)
        val height = (page.height * 2.2f).toInt().coerceAtLeast(1600)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        page.close()
        return bitmap
    }

    private fun updatePageIndicator() {
        tvPageIndicator.text = "Pág ${currentPageIndex + 1}/$totalPages"
    }

    private fun exportAnnotatedPdfToUri(uri: Uri) {
        try {
            saveCurrentPageStrokes()
            val pdfDocument = PdfDocument()
            val renderer = pdfRenderer

            for (i in 0 until totalPages) {
                val bgBitmap = if (renderer != null && i < renderer.pageCount) {
                    renderPdfPageToBitmap(renderer, i)
                } else {
                    null
                }
                val w = bgBitmap?.width ?: 1654
                val h = bgBitmap?.height ?: 2339

                val pageInfo = PdfDocument.PageInfo.Builder(w, h, i + 1).create()
                val page = pdfDocument.startPage(pageInfo)

                val strokesForPage = pageStrokesMap[i] ?: emptyList()
                canvasView.renderPageForExport(page.canvas, bgBitmap, strokesForPage, w, h)

                pdfDocument.finishPage(page)
                bgBitmap?.recycle()
            }

            contentResolver.openOutputStream(uri)?.use { outStream ->
                pdfDocument.writeTo(outStream)
            }
            pdfDocument.close()
            Toast.makeText(this, "PDF salvo com sucesso no tablet!", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Erro ao salvar PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun closePdfRenderer() {
        try {
            pdfRenderer?.close()
            pdfFileDescriptor?.close()
        } catch (_: Exception) {
        }
        pdfRenderer = null
        pdfFileDescriptor = null
    }

    override fun onDestroy() {
        super.onDestroy()
        closePdfRenderer()
    }
}
