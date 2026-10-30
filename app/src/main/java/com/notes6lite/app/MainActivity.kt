package com.notes6lite.app

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notes6lite.app.data.local.NoteDatabase
import com.notes6lite.app.data.repository.NotebookRepository
import com.notes6lite.app.domain.model.ColorScheme
import com.notes6lite.app.domain.model.DocumentViewMode
import com.notes6lite.app.domain.model.EraserMode
import com.notes6lite.app.domain.model.NotePage
import com.notes6lite.app.domain.model.Notebook
import com.notes6lite.app.domain.model.PageTemplate
import com.notes6lite.app.domain.model.PenType
import com.notes6lite.app.domain.model.SheetStyle
import com.notes6lite.app.domain.model.Stroke
import com.notes6lite.app.domain.model.TextElement
import com.notes6lite.app.domain.model.ToolMode
import com.notes6lite.app.render.InkPainter
import com.notes6lite.app.render.SheetPainter
import com.notes6lite.app.render.StrokeOutlineBuilder
import com.notes6lite.app.ui.NotesViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {

    private val viewModel: NotesViewModel by viewModels {
        NotesViewModel.factory(
            NotebookRepository(NoteDatabase.getInstance(applicationContext))
        )
    }

    private lateinit var canvasView: NoteCanvasView
    private lateinit var tvNotebookTitle: TextView

    // Top Category Tabs
    private lateinit var tabPen: Button
    private lateinit var tabEraser: Button
    private lateinit var tabText: Button
    private lateinit var tabBackground: Button
    private lateinit var tabPagePdf: Button
    private lateinit var subBarContainer: FrameLayout

    // Sub-Bars
    private lateinit var subBarPen: LinearLayout
    private lateinit var subBarEraser: LinearLayout
    private lateinit var subBarText: LinearLayout
    private lateinit var subBarBackground: LinearLayout
    private lateinit var subBarPagePdf: LinearLayout

    // Pen Sub-bar controls
    private lateinit var btnPenBallpoint: Button
    private lateinit var btnPenFountain: Button
    private lateinit var btnPenHighlighter: Button
    private lateinit var seekStroke: SeekBar
    private lateinit var tvStrokeValue: TextView
    private lateinit var presetButtons: List<Button>

    // Eraser Sub-bar controls
    private lateinit var btnEraserMode: Button
    private lateinit var seekEraserStroke: SeekBar
    private lateinit var tvEraserStrokeValue: TextView
    private lateinit var btnClearPage: Button

    // Text Sub-bar controls
    private lateinit var seekTextSize: SeekBar
    private lateinit var tvTextSizeValue: TextView
    private var currentTextFontSize = 20f
    private var currentTextColor = Color.parseColor("#111827")

    // Background Sub-bar controls
    private lateinit var btnTemplate: Button
    private lateinit var btnStyle: Button
    private lateinit var btnPressure: Button

    // Page / PDF Sub-bar controls
    private lateinit var btnPrevPage: Button
    private lateinit var tvPageIndicator: TextView
    private lateinit var btnNextPage: Button
    private lateinit var btnAddPage: Button
    private lateinit var btnViewMode: Button
    private lateinit var btnSideScratchpad: Button
    private lateinit var btnOpenPdf: Button
    private lateinit var btnSavePdf: Button

    // Floating Footnote / Summary Note controls
    private lateinit var btnSummaryPill: Button
    private lateinit var cardSummaryPanel: LinearLayout
    private lateinit var tvSummaryTitle: TextView
    private lateinit var btnSummarySizeToggle: Button
    private lateinit var btnSummaryMinimize: Button
    private lateinit var etSummaryContent: EditText
    private var summaryAutosaveJob: Job? = null
    private var isSummaryExpanded = false

    private enum class CategoryTab {
        PEN, ERASER, TEXT, BACKGROUND, PAGE_PDF
    }
    private var activeTab: CategoryTab = CategoryTab.PEN

    /** Espessura + cor guardadas em cada botão de preset (P1..P3). */
    private data class InkPreset(val width: Float, val color: Int)
    private val presets = arrayOfNulls<InkPreset>(PRESET_COUNT)
    private val presetPrefs by lazy {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
    }

    // ---------------------------------------------------------------------
    // Estado de navegação e PDF multi-páginas
    // ---------------------------------------------------------------------
    private var pdfRenderer: PdfRenderer? = null
    private var pdfDescriptor: ParcelFileDescriptor? = null
    private var currentPdfPage: PdfRenderer.Page? = null
    private var currentPdfTempFile: File? = null
    private val pageStrokesMap = HashMap<Int, List<Stroke>>()
    private val pageBitmapCache = HashMap<Int, Bitmap>()
    private var currentPageBitmap: Bitmap? = null

    private var currentNotebook: Notebook? = null
    private var currentPage: NotePage? = null
    private var currentPageIndex = 0
    private var totalPages = 1
    private var autosaveJob: Job? = null
    private var exportWithScratchpad: Boolean = false

    private val openPdfLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) importPdf(uri)
    }

    private val savePdfLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/pdf")
    ) { uri: Uri? ->
        if (uri != null) exportAnnotatedPdfToUri(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupCategoryTabs()
        setupSubBarControls()
        setupSummaryPanel()
        loadPresets()
        updateStrokeControls()
        updateEraserControls()
        updateViewModeButton()
        updateSideScratchpadButton()

        canvasView.onStrokeCommitted = {
            if (!canvasView.isContinuousActive) {
                pageStrokesMap[currentPageIndex] = canvasView.getStrokesCopy()
            }
            scheduleAutosave()
        }

        canvasView.onPageChangedListener = { newIndex ->
            if (currentPageIndex != newIndex) {
                currentPageIndex = newIndex
                updatePageIndicator()
            }
        }

        // Digitação no Canvas (ToolMode.TEXT)
        canvasView.onCanvasTapForText = { x, y ->
            showTextInputDialog(x, y, null)
        }
        canvasView.onTextElementTapped = { element ->
            showTextInputDialog(element.x, element.y, element)
        }

        setupContinuousProvider()

        lifecycleScope.launch {
            val requestedId = intent.getLongExtra(EXTRA_NOTEBOOK_ID, -1L)
            val requested = if (requestedId > 0) viewModel.getNotebook(requestedId) else null
            if (requested != null) {
                openNotebook(requested.id)
                return@launch
            }

            val notebooks = viewModel.getNotebooks()
            if (notebooks.isEmpty()) {
                createDefaultNotebook()
            } else {
                openNotebook(notebooks.first().id)
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (canvasView.onKeyDown(keyCode, event)) return true
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (canvasView.onKeyUp(keyCode, event)) return true
        return super.onKeyUp(keyCode, event)
    }

    private fun bindViews() {
        canvasView = findViewById(R.id.noteCanvasView)
        tvNotebookTitle = findViewById(R.id.tvNotebookTitle)

        tabPen = findViewById(R.id.tabPen)
        tabEraser = findViewById(R.id.tabEraser)
        tabText = findViewById(R.id.tabText)
        tabBackground = findViewById(R.id.tabBackground)
        tabPagePdf = findViewById(R.id.tabPagePdf)
        subBarContainer = findViewById(R.id.subBarContainer)

        subBarPen = findViewById(R.id.subBarPen)
        subBarEraser = findViewById(R.id.subBarEraser)
        subBarText = findViewById(R.id.subBarText)
        subBarBackground = findViewById(R.id.subBarBackground)
        subBarPagePdf = findViewById(R.id.subBarPagePdf)

        btnPenBallpoint = findViewById(R.id.btnPenBallpoint)
        btnPenFountain = findViewById(R.id.btnPenFountain)
        btnPenHighlighter = findViewById(R.id.btnPenHighlighter)
        seekStroke = findViewById(R.id.seekStroke)
        tvStrokeValue = findViewById(R.id.tvStrokeValue)
        presetButtons = listOf(
            findViewById(R.id.btnPreset1),
            findViewById(R.id.btnPreset2),
            findViewById(R.id.btnPreset3)
        )

        btnEraserMode = findViewById(R.id.btnEraserMode)
        seekEraserStroke = findViewById(R.id.seekEraserStroke)
        tvEraserStrokeValue = findViewById(R.id.tvEraserStrokeValue)
        btnClearPage = findViewById(R.id.btnClearPage)

        seekTextSize = findViewById(R.id.seekTextSize)
        tvTextSizeValue = findViewById(R.id.tvTextSizeValue)

        btnTemplate = findViewById(R.id.btnTemplate)
        btnStyle = findViewById(R.id.btnStyle)
        btnPressure = findViewById(R.id.btnPressure)

        btnPrevPage = findViewById(R.id.btnPrevPage)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        btnNextPage = findViewById(R.id.btnNextPage)
        btnAddPage = findViewById(R.id.btnAddPage)
        btnViewMode = findViewById(R.id.btnViewMode)
        btnSideScratchpad = findViewById(R.id.btnSideScratchpad)
        btnOpenPdf = findViewById(R.id.btnOpenPdf)
        btnSavePdf = findViewById(R.id.btnSavePdf)

        btnSummaryPill = findViewById(R.id.btnSummaryPill)
        cardSummaryPanel = findViewById(R.id.cardSummaryPanel)
        tvSummaryTitle = findViewById(R.id.tvSummaryTitle)
        btnSummarySizeToggle = findViewById(R.id.btnSummarySizeToggle)
        btnSummaryMinimize = findViewById(R.id.btnSummaryMinimize)
        etSummaryContent = findViewById(R.id.etSummaryContent)

        findViewById<Button>(R.id.btnHome).setOnClickListener { finish() }
        findViewById<Button>(R.id.btnUndo).setOnClickListener {
            canvasView.undo()
            if (!canvasView.isContinuousActive) {
                pageStrokesMap[currentPageIndex] = canvasView.getStrokesCopy()
            }
            scheduleAutosave()
        }
        findViewById<Button>(R.id.btnResetZoom).setOnClickListener {
            canvasView.fitPageToScreen()
        }
    }

    // ---------------------------------------------------------------------
    // Abas de Categorias & Sub-Barras
    // ---------------------------------------------------------------------

    private fun setupCategoryTabs() {
        tabPen.setOnClickListener { selectCategoryTab(CategoryTab.PEN) }
        tabEraser.setOnClickListener { selectCategoryTab(CategoryTab.ERASER) }
        tabText.setOnClickListener { selectCategoryTab(CategoryTab.TEXT) }
        tabBackground.setOnClickListener { selectCategoryTab(CategoryTab.BACKGROUND) }
        tabPagePdf.setOnClickListener { selectCategoryTab(CategoryTab.PAGE_PDF) }
    }

    private fun selectCategoryTab(tab: CategoryTab) {
        if (activeTab == tab && subBarContainer.visibility == View.VISIBLE) {
            subBarContainer.visibility = View.GONE
            return
        }
        activeTab = tab
        subBarContainer.visibility = View.VISIBLE

        subBarPen.visibility = if (tab == CategoryTab.PEN) View.VISIBLE else View.GONE
        subBarEraser.visibility = if (tab == CategoryTab.ERASER) View.VISIBLE else View.GONE
        subBarText.visibility = if (tab == CategoryTab.TEXT) View.VISIBLE else View.GONE
        subBarBackground.visibility = if (tab == CategoryTab.BACKGROUND) View.VISIBLE else View.GONE
        subBarPagePdf.visibility = if (tab == CategoryTab.PAGE_PDF) View.VISIBLE else View.GONE

        val activeTint = ColorStateList.valueOf(COLOR_ACCENT)
        val idleTint = ColorStateList.valueOf(COLOR_TOOL_IDLE)

        tabPen.backgroundTintList = if (tab == CategoryTab.PEN) activeTint else idleTint
        tabEraser.backgroundTintList = if (tab == CategoryTab.ERASER) activeTint else idleTint
        tabText.backgroundTintList = if (tab == CategoryTab.TEXT) activeTint else idleTint
        tabBackground.backgroundTintList = if (tab == CategoryTab.BACKGROUND) activeTint else idleTint
        tabPagePdf.backgroundTintList = if (tab == CategoryTab.PAGE_PDF) activeTint else idleTint

        when (tab) {
            CategoryTab.PEN -> {
                when (canvasView.penType) {
                    PenType.BALLPOINT, PenType.FOUNTAIN -> canvasView.currentToolMode = ToolMode.PEN
                    PenType.HIGHLIGHTER -> canvasView.currentToolMode = ToolMode.HIGHLIGHTER
                }
                updateStrokeControls()
            }
            CategoryTab.ERASER -> {
                canvasView.currentToolMode = ToolMode.ERASER
                updateEraserControls()
            }
            CategoryTab.TEXT -> {
                canvasView.currentToolMode = ToolMode.TEXT
            }
            else -> Unit
        }
    }

    private fun setupSubBarControls() {
        // Caneta: tipos
        btnPenBallpoint.setOnClickListener {
            canvasView.penType = PenType.BALLPOINT
            updatePenTypeButtons(PenType.BALLPOINT)
            updateStrokeControls()
        }
        btnPenFountain.setOnClickListener {
            canvasView.penType = PenType.FOUNTAIN
            updatePenTypeButtons(PenType.FOUNTAIN)
            updateStrokeControls()
        }
        btnPenHighlighter.setOnClickListener {
            canvasView.penType = PenType.HIGHLIGHTER
            updatePenTypeButtons(PenType.HIGHLIGHTER)
            updateStrokeControls()
        }

        // Caneta: espessura
        seekStroke.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                applyStrokeWidth(progress.toFloat())
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        // Presets favoritos P1..P3
        presetButtons.forEachIndexed { index, button ->
            button.setOnClickListener { applyPreset(index) }
            button.setOnLongClickListener {
                savePreset(index)
                true
            }
        }

        // Cores de caneta
        findViewById<Button>(R.id.btnColorBlack).setOnClickListener { setInkColor(Color.parseColor("#111827")) }
        findViewById<Button>(R.id.btnColorBlue).setOnClickListener { setInkColor(Color.parseColor("#1D4ED8")) }
        findViewById<Button>(R.id.btnColorRed).setOnClickListener { setInkColor(Color.parseColor("#DC2626")) }
        findViewById<Button>(R.id.btnColorGreen).setOnClickListener { setInkColor(Color.parseColor("#059669")) }
        findViewById<Button>(R.id.btnColorYellow).setOnClickListener {
            setInkColor(Color.parseColor("#FACC15"))
            canvasView.penType = PenType.HIGHLIGHTER
            updatePenTypeButtons(PenType.HIGHLIGHTER)
            updateStrokeControls()
        }

        // Borracha: modo e slider
        btnEraserMode.setOnClickListener {
            canvasView.eraserMode = canvasView.eraserMode.toggle()
            updateEraserControls()
        }
        seekEraserStroke.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                canvasView.eraserStrokeWidth = progress.toFloat()
                tvEraserStrokeValue.text = progress.toString()
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        btnClearPage.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Limpar página")
                .setMessage("Apagar todos os traços desta página? O fundo será preservado.")
                .setPositiveButton("Limpar") { _, _ ->
                    canvasView.clearPageStrokes()
                    pageStrokesMap[currentPageIndex] = emptyList()
                    scheduleAutosave()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

        // Texto: tamanho da fonte e cores
        seekTextSize.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                currentTextFontSize = (12 + progress).toFloat()
                tvTextSizeValue.text = "${currentTextFontSize.toInt()}sp"
            }
            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })
        findViewById<Button>(R.id.btnTextColorBlack).setOnClickListener { currentTextColor = Color.parseColor("#111827") }
        findViewById<Button>(R.id.btnTextColorBlue).setOnClickListener { currentTextColor = Color.parseColor("#1D4ED8") }
        findViewById<Button>(R.id.btnTextColorRed).setOnClickListener { currentTextColor = Color.parseColor("#DC2626") }
        findViewById<Button>(R.id.btnTextColorWhite).setOnClickListener { currentTextColor = Color.parseColor("#F8FAFC") }

        // Fundo
        btnTemplate.setOnClickListener { showTemplateDialog() }
        btnStyle.setOnClickListener { showStyleDialog() }
        btnPressure.setOnClickListener {
            canvasView.pressureEnabled = !canvasView.pressureEnabled
            updateToolbarState()
        }

        // Página / PDF
        btnViewMode.setOnClickListener {
            val nextMode = canvasView.viewMode.toggle()
            canvasView.viewMode = nextMode
            updateViewModeButton()
            if (nextMode == DocumentViewMode.CONTINUOUS) {
                canvasView.scrollToPage(currentPageIndex)
                Toast.makeText(this, "Modo Rolo Contínuo ativado (role a página)", Toast.LENGTH_SHORT).show()
            } else {
                renderCurrentPage()
                Toast.makeText(this, "Modo Página Única ativado", Toast.LENGTH_SHORT).show()
            }
        }
        btnSideScratchpad.setOnClickListener {
            canvasView.isSideScratchpadEnabled = !canvasView.isSideScratchpadEnabled
            updateSideScratchpadButton()
            if (canvasView.isContinuousActive) {
                canvasView.fitPageToScreen()
            }
            Toast.makeText(
                this,
                if (canvasView.isSideScratchpadEnabled) "Rascunho Lateral ativado ao lado do PDF!" else "Rascunho Lateral desativado.",
                Toast.LENGTH_SHORT
            ).show()
        }
        btnPrevPage.setOnClickListener {
            if (currentPageIndex > 0) {
                if (!canvasView.isContinuousActive) {
                    pageStrokesMap[currentPageIndex] = canvasView.getStrokesCopy()
                }
                currentPageIndex = (currentPageIndex - 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                if (canvasView.isContinuousActive) {
                    canvasView.scrollToPage(currentPageIndex)
                    updatePageIndicator()
                } else {
                    renderCurrentPage()
                }
            }
        }
        btnNextPage.setOnClickListener {
            if (currentPageIndex < totalPages - 1) {
                if (!canvasView.isContinuousActive) {
                    pageStrokesMap[currentPageIndex] = canvasView.getStrokesCopy()
                }
                currentPageIndex = (currentPageIndex + 1).coerceIn(0, (totalPages - 1).coerceAtLeast(0))
                if (canvasView.isContinuousActive) {
                    canvasView.scrollToPage(currentPageIndex)
                    updatePageIndicator()
                } else {
                    renderCurrentPage()
                }
            }
        }
        btnAddPage.setOnClickListener {
            if (!canvasView.isContinuousActive) {
                pageStrokesMap[currentPageIndex] = canvasView.getStrokesCopy()
            }
            if (pdfRenderer == null && currentNotebook != null) {
                lifecycleScope.launch {
                    val notebook = currentNotebook ?: return@launch
                    val nextIndex = totalPages
                    viewModel.ensurePage(
                        notebookId = notebook.id,
                        pageIndex = nextIndex,
                        template = canvasView.pageTemplate,
                        sheetStyle = canvasView.sheetStyle
                    )
                    totalPages = nextIndex + 1
                    currentPageIndex = nextIndex
                    loadNotebookPage(nextIndex)
                }
            } else {
                totalPages++
                currentPageIndex = totalPages - 1
                if (canvasView.isContinuousActive) {
                    canvasView.scrollToPage(currentPageIndex)
                    updatePageIndicator()
                } else {
                    renderCurrentPage()
                }
            }
        }
        btnOpenPdf.setOnClickListener {
            openPdfLauncher.launch(arrayOf("application/pdf"))
        }
        btnSavePdf.setOnClickListener {
            if (pdfRenderer != null) {
                AlertDialog.Builder(this)
                    .setTitle("Exportar PDF Anotado")
                    .setMessage("Como deseja exportar?")
                    .setPositiveButton("Com Rascunho Lateral") { _, _ ->
                        exportWithScratchpad = true
                        savePdfLauncher.launch("Estudo_Rascunho_${System.currentTimeMillis() / 1000}.pdf")
                    }
                    .setNegativeButton("Apenas PDF Original") { _, _ ->
                        exportWithScratchpad = false
                        savePdfLauncher.launch("Estudo_Original_${System.currentTimeMillis() / 1000}.pdf")
                    }
                    .setNeutralButton("Cancelar", null)
                    .show()
            } else {
                exportWithScratchpad = false
                savePdfLauncher.launch("Caderno_${System.currentTimeMillis() / 1000}.pdf")
            }
        }
    }

    private fun updatePenTypeButtons(type: PenType) {
        val activeTint = ColorStateList.valueOf(COLOR_ACCENT)
        val idleTint = ColorStateList.valueOf(COLOR_TOOL_IDLE)

        btnPenBallpoint.backgroundTintList = if (type == PenType.BALLPOINT) activeTint else idleTint
        btnPenFountain.backgroundTintList = if (type == PenType.FOUNTAIN) activeTint else idleTint
        btnPenHighlighter.backgroundTintList = if (type == PenType.HIGHLIGHTER) activeTint else idleTint
    }

    private fun updateEraserControls() {
        val mode = canvasView.eraserMode
        btnEraserMode.text = "Borracha: ${mode.displayName}"
        btnEraserMode.backgroundTintList = ColorStateList.valueOf(
            if (mode == EraserMode.STROKE) COLOR_ACCENT else COLOR_TOOL_IDLE
        )
        seekEraserStroke.progress = canvasView.eraserStrokeWidth.toInt().coerceIn(10, 70)
        tvEraserStrokeValue.text = "${canvasView.eraserStrokeWidth.toInt()}"
    }

    private fun updateSideScratchpadButton() {
        if (canvasView.isSideScratchpadEnabled) {
            btnSideScratchpad.text = "Rascunho Lateral: ON"
            btnSideScratchpad.backgroundTintList = ColorStateList.valueOf(COLOR_ACCENT)
        } else {
            btnSideScratchpad.text = "Rascunho Lateral: OFF"
            btnSideScratchpad.backgroundTintList = ColorStateList.valueOf(COLOR_TOOL_IDLE)
        }
    }

    // ---------------------------------------------------------------------
    // Diálogo de Inserção de Texto no Canvas
    // ---------------------------------------------------------------------

    private fun showTextInputDialog(x: Float, y: Float, existing: TextElement?) {
        val input = EditText(this).apply {
            setText(existing?.text ?: "")
            setSelection(text.length)
            hint = "Digite suas anotações aqui..."
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1E293B"))
            setPadding(32, 24, 32, 24)
        }

        val builder = AlertDialog.Builder(this)
            .setTitle(if (existing == null) "Inserir Texto na Folha" else "Editar Texto")
            .setView(input)
            .setPositiveButton("Salvar") { _, _ ->
                val newText = input.text.toString().trim()
                if (newText.isNotEmpty()) {
                    if (existing != null) {
                        existing.text = newText
                        existing.fontSize = currentTextFontSize
                        existing.color = currentTextColor
                    } else {
                        canvasView.textElements.add(
                            TextElement(
                                text = newText,
                                x = x,
                                y = y,
                                fontSize = currentTextFontSize,
                                color = currentTextColor
                            )
                        )
                    }
                    canvasView.invalidate()
                    scheduleAutosave()
                }
            }
            .setNegativeButton("Cancelar", null)

        if (existing != null) {
            builder.setNeutralButton("Excluir") { _, _ ->
                canvasView.textElements.remove(existing)
                canvasView.invalidate()
                scheduleAutosave()
            }
        }

        builder.show()
    }

    // ---------------------------------------------------------------------
    // Painel Flutuante / Footnote (Resumo Vinculado)
    // ---------------------------------------------------------------------

    private fun setupSummaryPanel() {
        btnSummaryPill.setOnClickListener {
            btnSummaryPill.visibility = View.GONE
            cardSummaryPanel.visibility = View.VISIBLE
            val params = cardSummaryPanel.layoutParams
            params.height = (resources.displayMetrics.heightPixels * 0.35f).toInt()
            cardSummaryPanel.layoutParams = params
            isSummaryExpanded = false
            btnSummarySizeToggle.text = "⤢ 70%"
        }

        btnSummaryMinimize.setOnClickListener {
            cardSummaryPanel.visibility = View.GONE
            btnSummaryPill.visibility = View.VISIBLE
        }

        btnSummarySizeToggle.setOnClickListener {
            val params = cardSummaryPanel.layoutParams
            if (isSummaryExpanded) {
                params.height = (resources.displayMetrics.heightPixels * 0.35f).toInt()
                isSummaryExpanded = false
                btnSummarySizeToggle.text = "⤢ 70%"
            } else {
                params.height = (resources.displayMetrics.heightPixels * 0.70f).toInt()
                isSummaryExpanded = true
                btnSummarySizeToggle.text = "⤡ 35%"
            }
            cardSummaryPanel.layoutParams = params
        }

        etSummaryContent.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scheduleSummarySave()
            }
            override fun afterTextChanged(s: android.text.Editable?) {}
        })
    }

    private fun loadSummaryForCurrentNotebook() {
        val nb = currentNotebook ?: return
        tvSummaryTitle.text = "📝 [Resumo] ${nb.title}"
        lifecycleScope.launch {
            val summaryNote = viewModel.getSummaryNote(nb.id)
            if (summaryNote != null) {
                etSummaryContent.setText(summaryNote.summaryText ?: "")
            } else {
                etSummaryContent.setText("")
            }
        }
    }

    private fun scheduleSummarySave() {
        summaryAutosaveJob?.cancel()
        summaryAutosaveJob = lifecycleScope.launch {
            delay(500)
            val nb = currentNotebook ?: return@launch
            val text = etSummaryContent.text.toString()
            viewModel.saveSummary(nb.id, nb.title, text, nb.folderId)
        }
    }

    // ---------------------------------------------------------------------
    // Provider Contínuo de Documento
    // ---------------------------------------------------------------------

    private fun setupContinuousProvider() {
        canvasView.continuousProvider = object : NoteCanvasView.ContinuousDocumentProvider {
            override fun getPageCount(): Int = totalPages

            override fun getPageWidth(index: Int): Int {
                val renderer = pdfRenderer
                if (renderer != null && index < renderer.pageCount) {
                    val page = runCatching { renderer.openPage(index) }.getOrNull()
                    val w = page?.width ?: PageTemplate.DEFAULT_WIDTH
                    val h = page?.height ?: PageTemplate.DEFAULT_HEIGHT
                    val target = pdfPageTargetSize(w, h)
                    page?.close()
                    val baseWidth = target.first
                    return if (canvasView.isSideScratchpadEnabled) (baseWidth * 1.55f).toInt() else baseWidth
                }
                return PageTemplate.DEFAULT_WIDTH
            }

            override fun getPageHeight(index: Int): Int {
                val renderer = pdfRenderer
                if (renderer != null && index < renderer.pageCount) {
                    val page = runCatching { renderer.openPage(index) }.getOrNull()
                    val w = page?.width ?: PageTemplate.DEFAULT_WIDTH
                    val h = page?.height ?: PageTemplate.DEFAULT_HEIGHT
                    val target = pdfPageTargetSize(w, h)
                    page?.close()
                    return target.second
                }
                return PageTemplate.DEFAULT_HEIGHT
            }

            override fun getPageBitmap(index: Int): Bitmap? {
                val cached = pageBitmapCache[index]
                if (cached != null && !cached.isRecycled) return cached

                val renderer = pdfRenderer ?: return null
                if (index >= renderer.pageCount) return null

                val page = runCatching { renderer.openPage(index) }.getOrNull() ?: return null
                return try {
                    val target = pdfPageTargetSize(page.width, page.height)
                    val bitmap = Bitmap.createBitmap(target.first, target.second, Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(Color.WHITE)
                    page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    pageBitmapCache[index] = bitmap
                    bitmap
                } finally {
                    page.close()
                }
            }

            override fun getPageStrokes(index: Int): List<Stroke> {
                return pageStrokesMap[index] ?: emptyList()
            }

            override fun onPageStrokesChanged(index: Int, strokes: List<Stroke>) {
                pageStrokesMap[index] = strokes
                scheduleAutosave()
                canvasView.invalidate()
            }
        }
    }

    // ---------------------------------------------------------------------
    // Cadernos locais
    // ---------------------------------------------------------------------

    private suspend fun createDefaultNotebook() {
        val id = viewModel.createNotebook(
            title = "Minhas Anotações",
            template = PageTemplate.GRID,
            sheetStyle = SheetStyle.GRID
        )
        openNotebook(id)
    }

    private suspend fun openNotebook(id: Long) {
        autosaveJob?.cancelAndJoin()
        autosaveJob = null
        saveCurrentPage()
        closeCurrentPdf()
        pageStrokesMap.clear()

        val notebook = viewModel.getNotebook(id) ?: return
        currentNotebook = notebook
        currentPage = null
        currentPageIndex = 0
        totalPages = viewModel.pageCount(id).coerceAtLeast(1)
        tvNotebookTitle.text = notebook.title

        loadSummaryForCurrentNotebook()
        loadNotebookPage(0)
    }

    private suspend fun loadNotebookPage(index: Int) {
        val notebook = currentNotebook ?: return
        val snapshot = viewModel.loadPage(
            notebookId = notebook.id,
            pageIndex = index,
            template = notebook.template,
            sheetStyle = notebook.sheetStyle
        )

        currentPageIndex = index
        totalPages = viewModel.pageCount(notebook.id).coerceAtLeast(index + 1)

        val background = withContext(Dispatchers.IO) {
            decodeBackground(snapshot.page.backgroundPath)
        }

        canvasView.loadPage(
            template = snapshot.page.template,
            style = snapshot.page.sheetStyle,
            background = background,
            pageWidth = snapshot.page.pageWidth,
            pageHeight = snapshot.page.pageHeight,
            savedStrokes = snapshot.strokes
        )

        currentPage = snapshot.page
        pageStrokesMap[index] = canvasView.getStrokesCopy()
        updateToolbarState()
    }

    private suspend fun persistCurrentPage() {
        val page = currentPage ?: return
        val strokes = if (canvasView.isContinuousActive) {
            pageStrokesMap[currentPageIndex] ?: canvasView.getStrokesCopy()
        } else {
            canvasView.getStrokesCopy()
        }
        pageStrokesMap[currentPageIndex] = strokes
        viewModel.savePage(page, strokes)
    }

    private suspend fun saveCurrentPage() {
        val pending = autosaveJob
        autosaveJob = null
        pending?.cancelAndJoin()
        persistCurrentPage()
    }

    private fun scheduleAutosave() {
        autosaveJob?.cancel()
        autosaveJob = lifecycleScope.launch {
            delay(700)
            persistCurrentPage()
        }
    }

    private fun updatePageIndicator() {
        tvPageIndicator.text = "Pág ${currentPageIndex + 1}/$totalPages"

        val hasPrevious = currentPageIndex > 0
        val hasNext = currentPageIndex < totalPages - 1
        btnPrevPage.isEnabled = hasPrevious
        btnPrevPage.alpha = if (hasPrevious) 1f else 0.35f
        btnNextPage.isEnabled = hasNext
        btnNextPage.alpha = if (hasNext) 1f else 0.35f
    }

    private fun updateToolbarState() {
        val notebook = currentNotebook
        tvNotebookTitle.text = notebook?.title ?: "NoteS6Lite"
        btnTemplate.text = "Template: ${canvasView.pageTemplate.displayName}"
        btnStyle.text = "Folha: ${canvasView.sheetStyle.displayName}"
        btnPressure.text = if (canvasView.pressureEnabled) "Pressão S-Pen: ON" else "Pressão S-Pen: OFF"

        updateSideScratchpadButton()
        updateEraserControls()
        updateViewModeButton()
        updateStrokeControls()
        updatePageIndicator()
    }

    private fun updateViewModeButton() {
        if (canvasView.viewMode == DocumentViewMode.CONTINUOUS) {
            btnViewMode.text = "Rolo 📜"
            btnViewMode.backgroundTintList = ColorStateList.valueOf(COLOR_ACCENT)
        } else {
            btnViewMode.text = "Página 📄"
            btnViewMode.backgroundTintList = ColorStateList.valueOf(COLOR_TOOL_IDLE)
        }
    }

    private fun setInkColor(color: Int) {
        val safeColor =
            if (canvasView.isDarkBackground && Color.luminance(color) < 0.25f) {
                NoteCanvasView.DEFAULT_DARK_INK_COLOR
            } else {
                color
            }
        canvasView.currentColor = safeColor
        if (canvasView.currentToolMode == ToolMode.ERASER) {
            selectCategoryTab(CategoryTab.PEN)
        }
    }

    private fun updateStrokeControls() {
        val width = currentToolWidth()
        seekStroke.progress = width.roundToInt().coerceIn(1, 35)
        tvStrokeValue.text = String.format(Locale.US, "%.1f", width)
    }

    private fun currentToolWidth(): Float = when (canvasView.currentToolMode) {
        ToolMode.PEN -> canvasView.penStrokeWidth
        ToolMode.HIGHLIGHTER -> canvasView.highlighterStrokeWidth
        ToolMode.ERASER -> canvasView.eraserStrokeWidth
        ToolMode.TEXT -> canvasView.penStrokeWidth
    }

    private fun applyStrokeWidth(width: Float) {
        when (canvasView.currentToolMode) {
            ToolMode.PEN -> canvasView.penStrokeWidth = width
            ToolMode.HIGHLIGHTER -> canvasView.highlighterStrokeWidth = width
            ToolMode.ERASER -> canvasView.eraserStrokeWidth = width
            ToolMode.TEXT -> canvasView.penStrokeWidth = width
        }
        tvStrokeValue.text = String.format(Locale.US, "%.1f", width)
    }

    private fun loadPresets() {
        val defaults = arrayOf(
            InkPreset(3.5f, Color.parseColor("#111827")),
            InkPreset(6.5f, Color.parseColor("#1D4ED8")),
            InkPreset(12.0f, Color.parseColor("#DC2626"))
        )
        for (index in 0 until PRESET_COUNT) {
            val width = presetPrefs.getFloat(keyWidth(index), defaults[index].width)
            val color = presetPrefs.getInt(keyColor(index), defaults[index].color)
            presets[index] = InkPreset(width, color)
        }
    }

    private fun applyPreset(index: Int) {
        val preset = presets.getOrNull(index) ?: return
        applyStrokeWidth(preset.width)
        setInkColor(preset.color)
        seekStroke.progress = preset.width.roundToInt().coerceIn(1, 35)
        Toast.makeText(this, "Preset P${index + 1} aplicado", Toast.LENGTH_SHORT).show()
    }

    private fun savePreset(index: Int) {
        val width = currentToolWidth()
        val color = canvasView.currentColor
        presets[index] = InkPreset(width, color)
        presetPrefs.edit()
            .putFloat(keyWidth(index), width)
            .putInt(keyColor(index), color)
            .apply()
        Toast.makeText(
            this,
            "Preset P${index + 1} salvo (${String.format(Locale.US, "%.1f", width)})",
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun keyWidth(index: Int) = "preset_${index}_width"
    private fun keyColor(index: Int) = "preset_${index}_color"

    // ---------------------------------------------------------------------
    // Diálogos: Template, Estilo (Dark Mode)
    // ---------------------------------------------------------------------

    private fun showTemplateDialog() {
        val templates = PageTemplate.values()
        val labels = templates.map { it.displayName }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Template da página")
            .setItems(labels) { _, which ->
                val template = templates[which]
                canvasView.setPageTemplate(template)
                updateToolbarState()
            }
            .show()
    }

    private fun showStyleDialog() {
        val schemes = ColorScheme.values()
        val labels = schemes.map { "Esquema: ${it.displayName}" }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Esquema de Cores da Folha")
            .setItems(labels) { _, which ->
                val scheme = schemes[which]
                val newStyle = SheetStyle.of(canvasView.pageTemplate, scheme)
                canvasView.setSheetStyle(newStyle)
                updateToolbarState()
            }
            .show()
    }

    // ---------------------------------------------------------------------
    // Importação e renderização de PDF Multi-Páginas
    // ---------------------------------------------------------------------

    private fun importPdf(uri: Uri) {
        lifecycleScope.launch {
            try {
                saveCurrentPage()
                closeCurrentPdf()
                pageStrokesMap.clear()
                pageBitmapCache.clear()

                val displayName = queryDisplayName(uri) ?: "PDF Importado"
                tvNotebookTitle.text = displayName.removeSuffix(".pdf")

                val tempFile = File(cacheDir, "doc_${System.currentTimeMillis()}.pdf")
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use { input ->
                        FileOutputStream(tempFile).use { output -> input.copyTo(output) }
                    } ?: throw IllegalStateException("Não foi possível ler o arquivo PDF.")
                }
                currentPdfTempFile = tempFile

                val pfd = ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
                pdfDescriptor = pfd
                val renderer = PdfRenderer(pfd)
                pdfRenderer = renderer

                totalPages = renderer.pageCount
                currentPageIndex = 0

                if (canvasView.viewMode == DocumentViewMode.CONTINUOUS) {
                    canvasView.fitPageToScreen()
                    canvasView.invalidate()
                    updatePageIndicator()
                } else {
                    renderCurrentPage()
                }

                Toast.makeText(this@MainActivity, "PDF aberto ($totalPages páginas)", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Erro ao carregar PDF: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun renderCurrentPage() {
        val renderer = pdfRenderer
        if (renderer != null && currentPageIndex < renderer.pageCount) {
            currentPdfPage?.close()
            currentPdfPage = null

            val page = renderer.openPage(currentPageIndex)
            currentPdfPage = page

            val size = pdfPageTargetSize(page.width, page.height)
            val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.WHITE)
            page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)

            currentPageBitmap?.recycle()
            currentPageBitmap = bitmap

            val strokes = pageStrokesMap[currentPageIndex] ?: emptyList()
            canvasView.setPageContent(bitmap, strokes)
        } else {
            val strokes = pageStrokesMap[currentPageIndex] ?: emptyList()
            canvasView.setPageContent(null, strokes)
        }

        tvPageIndicator.text = "Pág ${currentPageIndex + 1}/$totalPages"

        val hasPrevious = currentPageIndex > 0
        val hasNext = currentPageIndex < totalPages - 1
        btnPrevPage.isEnabled = hasPrevious
        btnPrevPage.alpha = if (hasPrevious) 1f else 0.35f
        btnNextPage.isEnabled = hasNext
        btnNextPage.alpha = if (hasNext) 1f else 0.35f
    }

    private fun exportAnnotatedPdfToUri(uri: Uri) {
        lifecycleScope.launch {
            try {
                if (!canvasView.isContinuousActive) {
                    pageStrokesMap[currentPageIndex] = canvasView.getStrokesCopy()
                }

                val renderer = pdfRenderer
                val pressure = canvasView.pressureEnabled

                Toast.makeText(this@MainActivity, "Exportando PDF ($totalPages páginas)...", Toast.LENGTH_SHORT).show()

                withContext(Dispatchers.IO) {
                    val document = PdfDocument()
                    try {
                        currentPdfPage?.close()
                        currentPdfPage = null

                        for (i in 0 until totalPages) {
                            val strokes = pageStrokesMap[i] ?: emptyList()

                            if (renderer != null && i < renderer.pageCount) {
                                val pdfPage = renderer.openPage(i)
                                try {
                                    val baseW = pdfPage.width
                                    val baseH = pdfPage.height
                                    val widthPt = if (exportWithScratchpad) (baseW * 1.55f).toInt() else baseW
                                    val heightPt = baseH

                                    val pageInfo = PdfDocument.PageInfo.Builder(widthPt, heightPt, i + 1).create()
                                    val docPage = document.startPage(pageInfo)

                                    val targetSize = pdfPageTargetSize(pdfPage.width, pdfPage.height)
                                    val pageBitmap = Bitmap.createBitmap(
                                        targetSize.first,
                                        targetSize.second,
                                        Bitmap.Config.ARGB_8888
                                    )
                                    pageBitmap.eraseColor(Color.WHITE)
                                    pdfPage.render(pageBitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_PRINT)

                                    if (exportWithScratchpad) {
                                        val painter = SheetPainter().apply { this.style = canvasView.sheetStyle }
                                        val pdfRect = RectF(0f, 0f, baseW.toFloat(), heightPt.toFloat())
                                        val scratchRect = RectF(baseW.toFloat(), 0f, widthPt.toFloat(), heightPt.toFloat())
                                        painter.drawScratchpadPage(docPage.canvas, pdfRect, scratchRect, pageBitmap)
                                    } else {
                                        val destRect = RectF(0f, 0f, widthPt.toFloat(), heightPt.toFloat())
                                        docPage.canvas.drawBitmap(pageBitmap, null, destRect, null)
                                    }
                                    pageBitmap.recycle()

                                    if (strokes.isNotEmpty()) {
                                        docPage.canvas.save()
                                        val scaleX = baseW.toFloat() / targetSize.first.toFloat()
                                        val scaleY = heightPt.toFloat() / targetSize.second.toFloat()
                                        docPage.canvas.scale(scaleX, scaleY)

                                        val ink = InkPainter()
                                        for (stroke in strokes) {
                                            val outline = StrokeOutlineBuilder.build(
                                                points = stroke.points,
                                                baseWidth = stroke.baseWidth,
                                                mode = stroke.mode,
                                                pressureEnabled = pressure
                                            )
                                            val colorToDraw = if (canvasView.sheetStyle.isDark && (stroke.color == Color.BLACK || Color.luminance(stroke.color) < 0.25f)) {
                                                NoteCanvasView.DEFAULT_DARK_INK_COLOR
                                            } else {
                                                stroke.color
                                            }
                                            ink.draw(docPage.canvas, outline, colorToDraw, stroke.mode)
                                        }
                                        docPage.canvas.restore()
                                    }

                                    document.finishPage(docPage)
                                } finally {
                                    pdfPage.close()
                                }
                            } else {
                                val width = PageTemplate.DEFAULT_WIDTH
                                val height = PageTemplate.DEFAULT_HEIGHT
                                val pdfSize = pdfPageSize(width, height)
                                val pageInfo = PdfDocument.PageInfo.Builder(pdfSize.first, pdfSize.second, i + 1).create()
                                val docPage = document.startPage(pageInfo)

                                docPage.canvas.scale(
                                    pdfSize.first / width.toFloat(),
                                    pdfSize.second / height.toFloat()
                                )
                                canvasView.renderPageForExport(
                                    targetCanvas = docPage.canvas,
                                    bgBitmap = null,
                                    pageStrokes = strokes,
                                    targetWidth = width,
                                    targetHeight = height,
                                    style = canvasView.sheetStyle,
                                    pressure = pressure,
                                    includeScratchpad = false,
                                    texts = canvasView.textElements
                                )
                                document.finishPage(docPage)
                            }
                        }

                        contentResolver.openOutputStream(uri)?.use { output ->
                            document.writeTo(output)
                        } ?: throw IllegalStateException("Não foi possível salvar o arquivo.")
                    } finally {
                        document.close()
                    }
                }

                Toast.makeText(this@MainActivity, "PDF exportado com sucesso!", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Erro ao exportar PDF: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (canvasView.viewMode != DocumentViewMode.CONTINUOUS) {
                    renderCurrentPage()
                }
            }
        }
    }

    private fun closeCurrentPdf() {
        currentPdfPage?.close()
        currentPdfPage = null
        pdfRenderer?.close()
        pdfRenderer = null
        pdfDescriptor?.close()
        pdfDescriptor = null
        currentPdfTempFile?.delete()
        currentPdfTempFile = null

        pageBitmapCache.values.forEach { if (!it.isRecycled) it.recycle() }
        pageBitmapCache.clear()

        currentPageBitmap?.recycle()
        currentPageBitmap = null
    }

    private fun pdfPageTargetSize(srcWidth: Int, srcHeight: Int): Pair<Int, Int> {
        val maxDim = 2048f
        val srcMax = max(srcWidth, srcHeight).toFloat()
        val scale = if (srcMax > maxDim) maxDim / srcMax else 1f
        val w = (srcWidth * scale).toInt().coerceAtLeast(1)
        val h = (srcHeight * scale).toInt().coerceAtLeast(1)
        return Pair(w, h)
    }

    private fun pdfPageSize(srcWidth: Int, srcHeight: Int): Pair<Int, Int> {
        val maxPt = 1200f
        val srcMax = max(srcWidth, srcHeight).toFloat()
        val scale = if (srcMax > maxPt) maxPt / srcMax else 1f
        val w = (srcWidth * scale).toInt().coerceAtLeast(1)
        val h = (srcHeight * scale).toInt().coerceAtLeast(1)
        return Pair(w, h)
    }

    private fun decodeBackground(path: String?): Bitmap? {
        if (path.isNullOrEmpty()) return null
        val file = File(path)
        if (!file.exists()) return null
        return runCatching { BitmapFactory.decodeFile(file.absolutePath) }.getOrNull()
    }

    private fun queryDisplayName(uri: Uri): String? {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex != -1 && cursor.moveToFirst()) {
                return cursor.getString(nameIndex)
            }
        }
        return uri.lastPathSegment
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch(NonCancellable) {
            saveCurrentPage()
            closeCurrentPdf()
        }
    }

    companion object {
        const val EXTRA_NOTEBOOK_ID = "notebook_id"
        private const val PREFS_NAME = "notes6lite_presets"
        private const val PRESET_COUNT = 3

        val COLOR_ACCENT: Int = Color.parseColor("#2563EB")
        val COLOR_TOOL_IDLE: Int = Color.parseColor("#334155")
    }
}
