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
import android.widget.Button
import android.widget.EditText
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
import com.notes6lite.app.domain.model.SheetStyle
import com.notes6lite.app.domain.model.Stroke
import com.notes6lite.app.domain.model.ToolMode
import com.notes6lite.app.render.InkPainter
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
    private lateinit var tvPageIndicator: TextView
    private lateinit var tvNotebookTitle: TextView

    private lateinit var btnToolPen: Button
    private lateinit var btnToolHighlighter: Button
    private lateinit var btnToolEraser: Button
    private lateinit var btnEraserMode: Button
    private lateinit var seekStroke: SeekBar
    private lateinit var tvStrokeValue: TextView
    private lateinit var presetButtons: List<Button>
    private lateinit var btnTemplate: Button
    private lateinit var btnStyle: Button
    private lateinit var btnPressure: Button
    private lateinit var btnPrevPage: Button
    private lateinit var btnNextPage: Button
    private lateinit var btnAddPage: Button
    private lateinit var btnViewMode: Button

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
        setupToolbarActions()
        loadPresets()
        updateStrokeControls()
        updateEraserModeButton()
        updateViewModeButton()

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

    private fun bindViews() {
        canvasView = findViewById(R.id.noteCanvasView)
        tvPageIndicator = findViewById(R.id.tvPageIndicator)
        tvNotebookTitle = findViewById(R.id.tvNotebookTitle)

        btnToolPen = findViewById(R.id.btnToolPen)
        btnToolHighlighter = findViewById(R.id.btnToolHighlighter)
        btnToolEraser = findViewById(R.id.btnToolEraser)
        btnEraserMode = findViewById(R.id.btnEraserMode)
        seekStroke = findViewById(R.id.seekStroke)
        tvStrokeValue = findViewById(R.id.tvStrokeValue)
        presetButtons = listOf(
            findViewById(R.id.btnPreset1),
            findViewById(R.id.btnPreset2),
            findViewById(R.id.btnPreset3)
        )
        btnTemplate = findViewById(R.id.btnTemplate)
        btnStyle = findViewById(R.id.btnStyle)
        btnPressure = findViewById(R.id.btnPressure)
        btnPrevPage = findViewById(R.id.btnPrevPage)
        btnNextPage = findViewById(R.id.btnNextPage)
        btnAddPage = findViewById(R.id.btnAddPage)
        btnViewMode = findViewById(R.id.btnViewMode)
    }

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
                    return target.first
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
            title = "Minha primeira nota",
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
        btnPressure.text = if (canvasView.pressureEnabled) "Pressão ON" else "Pressão OFF"

        updateEraserModeButton()
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

    // ---------------------------------------------------------------------
    // Barra de ferramentas
    // ---------------------------------------------------------------------

    private fun setupToolbarActions() {
        findViewById<Button>(R.id.btnOpenPdf).setOnClickListener {
            openPdfLauncher.launch(arrayOf("application/pdf"))
        }

        findViewById<Button>(R.id.btnSavePdf).setOnClickListener {
            savePdfLauncher.launch("Estudo_Anotado_${System.currentTimeMillis() / 1000}.pdf")
        }

        findViewById<Button>(R.id.btnHome).setOnClickListener {
            finish()
        }

        findViewById<Button>(R.id.btnNotebooks).setOnClickListener {
            showNotebooksDialog()
        }

        // Alterna entre Rolo Contínuo e Página Única
        btnViewMode.setOnClickListener {
            val nextMode = canvasView.viewMode.toggle()
            canvasView.viewMode = nextMode
            updateViewModeButton()

            if (nextMode == DocumentViewMode.CONTINUOUS) {
                canvasView.scrollToPage(currentPageIndex)
                Toast.makeText(this, "Modo Rolo Contínuo ativado (role para cima/baixo)", Toast.LENGTH_SHORT).show()
            } else {
                renderCurrentPage()
                Toast.makeText(this, "Modo Página Única ativado (use ◀ e ▶)", Toast.LENGTH_SHORT).show()
            }
        }

        // Navegação de páginas (funciona tanto no modo Página quanto rola no modo Contínuo)
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

        btnToolPen.setOnClickListener { selectTool(ToolMode.PEN) }
        btnToolHighlighter.setOnClickListener { selectTool(ToolMode.HIGHLIGHTER) }
        btnToolEraser.setOnClickListener { selectTool(ToolMode.ERASER) }

        btnEraserMode.setOnClickListener {
            canvasView.eraserMode = canvasView.eraserMode.toggle()
            updateEraserModeButton()
        }

        findViewById<Button>(R.id.btnUndo).setOnClickListener {
            canvasView.undo()
            if (!canvasView.isContinuousActive) {
                pageStrokesMap[currentPageIndex] = canvasView.getStrokesCopy()
            }
            scheduleAutosave()
        }

        findViewById<Button>(R.id.btnClearPage).setOnClickListener {
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

        setupStrokeControls()

        findViewById<Button>(R.id.btnColorBlack).setOnClickListener {
            setInkColor(Color.parseColor("#111827"))
        }
        findViewById<Button>(R.id.btnColorBlue).setOnClickListener {
            setInkColor(Color.parseColor("#1D4ED8"))
        }
        findViewById<Button>(R.id.btnColorRed).setOnClickListener {
            setInkColor(Color.parseColor("#DC2626"))
        }
        findViewById<Button>(R.id.btnColorGreen).setOnClickListener {
            setInkColor(Color.parseColor("#059669"))
        }
        findViewById<Button>(R.id.btnColorYellow).setOnClickListener {
            setInkColor(Color.parseColor("#FACC15"))
            selectTool(ToolMode.HIGHLIGHTER)
        }

        findViewById<Button>(R.id.btnResetZoom).setOnClickListener {
            canvasView.fitPageToScreen()
        }

        btnTemplate.setOnClickListener { showTemplateDialog() }
        btnStyle.setOnClickListener { showStyleDialog() }
        btnPressure.setOnClickListener {
            canvasView.pressureEnabled = !canvasView.pressureEnabled
            updateToolbarState()
        }
    }

    private fun selectTool(mode: ToolMode) {
        canvasView.currentToolMode = mode

        val activeTint = ColorStateList.valueOf(COLOR_ACCENT)
        val inactiveTint = ColorStateList.valueOf(COLOR_TOOL_IDLE)

        btnToolPen.backgroundTintList =
            if (mode == ToolMode.PEN) activeTint else inactiveTint
        btnToolHighlighter.backgroundTintList =
            if (mode == ToolMode.HIGHLIGHTER) activeTint else inactiveTint
        btnToolEraser.backgroundTintList =
            if (mode == ToolMode.ERASER) activeTint else inactiveTint

        updateStrokeControls()
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
            selectTool(ToolMode.PEN)
        }
    }

    // ---------------------------------------------------------------------
    // Espessura: slider contínuo + presets favoritos (P1..P3)
    // ---------------------------------------------------------------------

    private fun setupStrokeControls() {
        seekStroke.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                applyStrokeWidth(progress.toFloat())
            }

            override fun onStartTrackingTouch(bar: SeekBar) = Unit
            override fun onStopTrackingTouch(bar: SeekBar) = Unit
        })

        presetButtons.forEachIndexed { index, button ->
            button.setOnClickListener { applyPreset(index) }
            button.setOnLongClickListener {
                savePreset(index)
                true
            }
        }
    }

    private fun updateStrokeControls() {
        val eraser = canvasView.currentToolMode == ToolMode.ERASER
        val minValue = if (eraser) ERASER_WIDTH_MIN else PEN_WIDTH_MIN
        val maxValue = if (eraser) ERASER_WIDTH_MAX else PEN_WIDTH_MAX
        val width = currentToolWidth()

        if (seekStroke.min != minValue) seekStroke.min = minValue
        if (seekStroke.max != maxValue) seekStroke.max = maxValue
        seekStroke.progress = width.roundToInt().coerceIn(minValue, maxValue)
        tvStrokeValue.text = String.format(Locale.US, "%.1f", width)
    }

    private fun currentToolWidth(): Float = when (canvasView.currentToolMode) {
        ToolMode.PEN -> canvasView.penStrokeWidth
        ToolMode.HIGHLIGHTER -> canvasView.highlighterStrokeWidth
        ToolMode.ERASER -> canvasView.eraserStrokeWidth
    }

    private fun applyStrokeWidth(width: Float) {
        when (canvasView.currentToolMode) {
            ToolMode.PEN -> canvasView.penStrokeWidth = width
            ToolMode.HIGHLIGHTER -> canvasView.highlighterStrokeWidth = width
            ToolMode.ERASER -> canvasView.eraserStrokeWidth = width
        }
        tvStrokeValue.text = String.format(Locale.US, "%.1f", width)
    }

    private fun updateEraserModeButton() {
        val mode = canvasView.eraserMode
        btnEraserMode.text = "Borracha: ${mode.displayName}"
        btnEraserMode.backgroundTintList = ColorStateList.valueOf(
            if (mode == EraserMode.STROKE) COLOR_ACCENT else COLOR_TOOL_IDLE
        )
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
        seekStroke.progress = preset.width.roundToInt()
            .coerceIn(seekStroke.min, seekStroke.max)
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
    // Diálogos: Template, Estilo (Dark Mode) e Cadernos
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

    private fun showNotebooksDialog() {
        lifecycleScope.launch {
            val notebooks = viewModel.getNotebooks()
            if (notebooks.isEmpty()) {
                showNewNotebookDialog()
                return@launch
            }

            val labels = notebooks.map {
                "${it.title}   (${it.template.displayName})"
            }.toTypedArray()

            AlertDialog.Builder(this@MainActivity)
                .setTitle("Notas")
                .setItems(labels) { _, which ->
                    lifecycleScope.launch { openNotebook(notebooks[which].id) }
                }
                .setPositiveButton("Nova") { _, _ -> showNewNotebookDialog() }
                .setNeutralButton("Renomear") { _, _ -> showRenameNotebookDialog(notebooks) }
                .setNegativeButton("Excluir") { _, _ -> showDeleteNotebookDialog(notebooks) }
                .show()
        }
    }

    private fun showNewNotebookDialog() {
        val input = EditText(this).apply {
            hint = "Nome da nota"
            setSingleLine()
        }

        AlertDialog.Builder(this)
            .setTitle("Nova nota")
            .setView(input)
            .setPositiveButton("Criar") { _, _ ->
                val title = input.text.toString().ifBlank { "Nota ${System.currentTimeMillis() % 1000}" }
                lifecycleScope.launch {
                    val id = viewModel.createNotebook(
                        title = title,
                        template = PageTemplate.GRID,
                        sheetStyle = SheetStyle.GRID,
                        folderId = currentNotebook?.folderId
                    )
                    openNotebook(id)
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showRenameNotebookDialog(notebooks: List<Notebook>) {
        val labels = notebooks.map { it.title }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Renomear nota")
            .setItems(labels) { _, which ->
                val notebook = notebooks[which]
                val input = EditText(this).apply {
                    setText(notebook.title)
                    setSelection(text.length)
                }

                AlertDialog.Builder(this)
                    .setTitle("Novo nome")
                    .setView(input)
                    .setPositiveButton("Salvar") { _, _ ->
                        val title = input.text.toString().ifBlank { notebook.title }
                        lifecycleScope.launch {
                            viewModel.renameNotebook(notebook.id, title)
                            if (currentNotebook?.id == notebook.id) {
                                currentNotebook = notebook.copy(title = title)
                                updateToolbarState()
                            }
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
            }
            .show()
    }

    private fun showDeleteNotebookDialog(notebooks: List<Notebook>) {
        val labels = notebooks.map { it.title }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Excluir nota")
            .setItems(labels) { _, which ->
                val notebook = notebooks[which]

                AlertDialog.Builder(this)
                    .setTitle("Excluir \"${notebook.title}\"?")
                    .setMessage("Todas as páginas e anotações serão removidas.")
                    .setPositiveButton("Excluir") { _, _ ->
                        lifecycleScope.launch {
                            val deletingCurrent = currentNotebook?.id == notebook.id
                            if (deletingCurrent) {
                                autosaveJob?.cancelAndJoin()
                                autosaveJob = null
                                currentNotebook = null
                                currentPage = null
                            }

                            val pages = viewModel.getPages(notebook.id)
                            viewModel.deleteNotebook(notebook.id)
                            pages.forEach { page ->
                                page.backgroundPath?.let { path -> File(path).delete() }
                            }

                            if (deletingCurrent) {
                                val remaining = viewModel.getNotebooks()
                                if (remaining.isEmpty()) {
                                    createDefaultNotebook()
                                } else {
                                    openNotebook(remaining.first().id)
                                }
                            }
                        }
                    }
                    .setNegativeButton("Cancelar", null)
                    .show()
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

    /**
     * Renderiza a página correspondente no modo Página Única.
     */
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

    /**
     * Exportação de Todas as Páginas combinando o fundo do PDF com os traços.
     */
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
                                    val widthPt = pdfPage.width
                                    val heightPt = pdfPage.height
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

                                    val destRect = RectF(0f, 0f, widthPt.toFloat(), heightPt.toFloat())
                                    docPage.canvas.drawBitmap(pageBitmap, null, destRect, null)
                                    pageBitmap.recycle()

                                    if (strokes.isNotEmpty()) {
                                        docPage.canvas.save()
                                        val scaleX = widthPt.toFloat() / targetSize.first.toFloat()
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
                                    pressure = pressure
                                )
                                document.finishPage(docPage)
                            }
                        }

                        contentResolver.openOutputStream(uri)?.use { output ->
                            document.writeTo(output)
                        } ?: throw IllegalStateException("Não foi possível salvar o arquivo de saída.")
                    } finally {
                        document.close()
                    }
                }

                if (canvasView.isContinuousActive) {
                    canvasView.invalidate()
                } else {
                    renderCurrentPage()
                }
                Toast.makeText(this@MainActivity, "PDF exportado com sucesso ($totalPages páginas)!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Erro ao exportar PDF: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun pdfPageTargetSize(width: Int, height: Int): Pair<Int, Int> {
        val largest = max(width, height).coerceAtLeast(1)
        val scale = min(2.2f, 2400f / largest)
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return targetWidth to targetHeight
    }

    private fun pdfPageSize(widthPx: Int, heightPx: Int): Pair<Int, Int> {
        val safeWidth = widthPx.coerceAtLeast(1)
        val safeHeight = heightPx.coerceAtLeast(1)
        val pdfWidth = PDF_PAGE_WIDTH_PT
        val pdfHeight = (pdfWidth.toFloat() * safeHeight / safeWidth)
            .roundToInt()
            .coerceAtLeast(1)
        return pdfWidth to pdfHeight
    }

    private fun queryDisplayName(uri: Uri): String? {
        var name: String? = null
        contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) name = cursor.getString(index)
            }
        }
        return name
    }

    private fun decodeBackground(path: String?): Bitmap? {
        if (path == null) return null
        val file = File(path)
        if (!file.exists()) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)

        var sampleSize = 1
        val maxDimension = 2400
        while (bounds.outWidth / sampleSize > maxDimension ||
            bounds.outHeight / sampleSize > maxDimension
        ) {
            sampleSize *= 2
        }

        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return BitmapFactory.decodeFile(path, options)
    }

    private fun closeCurrentPdf() {
        runCatching { currentPdfPage?.close() }
        currentPdfPage = null
        runCatching { pdfRenderer?.close() }
        pdfRenderer = null
        runCatching { pdfDescriptor?.close() }
        pdfDescriptor = null
        currentPageBitmap?.recycle()
        currentPageBitmap = null
        pageBitmapCache.values.forEach { it.recycle() }
        pageBitmapCache.clear()
        currentPdfTempFile?.delete()
        currentPdfTempFile = null
    }

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch {
            withContext(NonCancellable) { saveCurrentPage() }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        closeCurrentPdf()
    }

    companion object {
        const val EXTRA_NOTEBOOK_ID = "com.notes6lite.app.extra.NOTEBOOK_ID"
        private const val PDF_PAGE_WIDTH_PT = 595

        private const val PEN_WIDTH_MIN = 1
        private const val PEN_WIDTH_MAX = 35
        private const val ERASER_WIDTH_MIN = 10
        private const val ERASER_WIDTH_MAX = 70

        private const val PRESET_COUNT = 3
        private const val PREFS_NAME = "note_toolbar"

        private val COLOR_ACCENT = Color.parseColor("#2563EB")
        private val COLOR_TOOL_IDLE = Color.parseColor("#334155")
    }
}
