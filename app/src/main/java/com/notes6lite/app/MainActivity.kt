package com.notes6lite.app

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.notes6lite.app.data.local.NoteDatabase
import com.notes6lite.app.data.repository.NotebookRepository
import com.notes6lite.app.domain.model.NotePage
import com.notes6lite.app.domain.model.Notebook
import com.notes6lite.app.domain.model.PageTemplate
import com.notes6lite.app.domain.model.SheetStyle
import com.notes6lite.app.domain.model.ToolMode
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
import kotlin.math.ceil
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
    private lateinit var btnStrokeThin: Button
    private lateinit var btnStrokeMedium: Button
    private lateinit var btnStrokeThick: Button
    private lateinit var btnTemplate: Button
    private lateinit var btnStyle: Button
    private lateinit var btnPressure: Button

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
        if (uri != null) exportPdf(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        bindViews()
        setupToolbarActions()

        canvasView.onStrokeCommitted = { scheduleAutosave() }

        lifecycleScope.launch {
            // Veio da biblioteca de pastas? Abre exatamente a nota escolhida.
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
        btnStrokeThin = findViewById(R.id.btnStrokeThin)
        btnStrokeMedium = findViewById(R.id.btnStrokeMedium)
        btnStrokeThick = findViewById(R.id.btnStrokeThick)
        btnTemplate = findViewById(R.id.btnTemplate)
        btnStyle = findViewById(R.id.btnStyle)
        btnPressure = findViewById(R.id.btnPressure)
    }

    // ---------------------------------------------------------------------
    // Cadernos e páginas
    // ---------------------------------------------------------------------

    private suspend fun createDefaultNotebook() {
        val id = viewModel.createNotebook(
            title = "Minha primeira nota",
            template = PageTemplate.A4,
            sheetStyle = SheetStyle.GRID
        )
        openNotebook(id)
    }

    private suspend fun openNotebook(id: Long) {
        autosaveJob?.cancelAndJoin()
        autosaveJob = null
        saveCurrentPage()

        val notebook = viewModel.getNotebook(id) ?: return
        currentNotebook = notebook
        currentPage = null
        currentPageIndex = 0
        totalPages = viewModel.pageCount(id).coerceAtLeast(1)
        tvNotebookTitle.text = notebook.title

        loadPage(0)
    }

    private suspend fun loadPage(index: Int) {
        val notebook = currentNotebook ?: return
        val snapshot = viewModel.loadPage(
            notebookId = notebook.id,
            pageIndex = index,
            template = notebook.template,
            sheetStyle = notebook.sheetStyle
        )

        currentPageIndex = index
        totalPages = max(totalPages, viewModel.pageCount(notebook.id))

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

        // Só aponta para a página nova depois que o canvas já foi trocado,
        // para que um autosave tardio nunca grave traços antigos nela.
        currentPage = snapshot.page

        updateToolbarState()
    }

    private suspend fun persistCurrentPage() {
        val page = currentPage ?: return
        viewModel.savePage(page, canvasView.getStrokesCopy())
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
    }

    private fun updateToolbarState() {
        val notebook = currentNotebook
        val page = currentPage

        tvNotebookTitle.text = notebook?.title ?: "NoteS6Lite"
        btnTemplate.text = "Template: ${page?.template?.displayName ?: "—"}"
        btnStyle.text = "Folha: ${page?.sheetStyle?.displayName ?: "—"}"
        btnPressure.text = if (canvasView.pressureEnabled) "Pressão ON" else "Pressão OFF"

        updatePageIndicator()
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

        findViewById<Button>(R.id.btnPrevPage).setOnClickListener {
            if (currentPageIndex <= 0) return@setOnClickListener
            lifecycleScope.launch {
                saveCurrentPage()
                loadPage(currentPageIndex - 1)
            }
        }

        findViewById<Button>(R.id.btnNextPage).setOnClickListener {
            lifecycleScope.launch {
                saveCurrentPage()
                val notebook = currentNotebook ?: return@launch
                val nextIndex = currentPageIndex + 1
                if (nextIndex >= totalPages) {
                    viewModel.ensurePage(
                        notebookId = notebook.id,
                        pageIndex = nextIndex,
                        template = notebook.template,
                        sheetStyle = notebook.sheetStyle
                    )
                    totalPages = nextIndex + 1
                }
                loadPage(nextIndex)
            }
        }

        btnToolPen.setOnClickListener { selectTool(ToolMode.PEN) }
        btnToolHighlighter.setOnClickListener { selectTool(ToolMode.HIGHLIGHTER) }
        btnToolEraser.setOnClickListener { selectTool(ToolMode.ERASER) }

        findViewById<Button>(R.id.btnUndo).setOnClickListener {
            canvasView.undo()
            scheduleAutosave()
        }

        findViewById<Button>(R.id.btnClearPage).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Limpar página")
                .setMessage("Apagar todos os traços desta página? O fundo será preservado.")
                .setPositiveButton("Limpar") { _, _ ->
                    canvasView.clearPageStrokes()
                    scheduleAutosave()
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }

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

        val activeTint = ColorStateList.valueOf(Color.parseColor("#2563EB"))
        val inactiveTint = ColorStateList.valueOf(Color.parseColor("#334155"))

        btnToolPen.backgroundTintList =
            if (mode == ToolMode.PEN) activeTint else inactiveTint
        btnToolHighlighter.backgroundTintList =
            if (mode == ToolMode.HIGHLIGHTER) activeTint else inactiveTint
        btnToolEraser.backgroundTintList =
            if (mode == ToolMode.ERASER) activeTint else inactiveTint
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

    private fun highlightStrokeButton(selected: Button) {
        val activeTint = ColorStateList.valueOf(Color.parseColor("#2563EB"))
        val inactiveTint = ColorStateList.valueOf(Color.parseColor("#334155"))
        btnStrokeThin.backgroundTintList = if (selected == btnStrokeThin) activeTint else inactiveTint
        btnStrokeMedium.backgroundTintList = if (selected == btnStrokeMedium) activeTint else inactiveTint
        btnStrokeThick.backgroundTintList = if (selected == btnStrokeThick) activeTint else inactiveTint
    }

    // ---------------------------------------------------------------------
    // Diálogos: template, estilo e cadernos
    // ---------------------------------------------------------------------

    private fun showTemplateDialog() {
        val notebook = currentNotebook ?: return
        val page = currentPage ?: return

        if (page.backgroundPath != null) {
            Toast.makeText(
                this,
                "Esta página veio de um PDF e mantém o tamanho original.",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        val templates = PageTemplate.values()
        val labels = templates.map { it.displayName }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Template da página")
            .setItems(labels) { _, which ->
                val template = templates[which]
                lifecycleScope.launch {
                    val updatedPage = page.copy(
                        template = template,
                        pageWidth = if (template.isInfinite) 0 else template.pageWidth,
                        pageHeight = if (template.isInfinite) 0 else template.pageHeight
                    )
                    currentPage = updatedPage
                    canvasView.setPageTemplate(template)
                    viewModel.savePage(updatedPage, canvasView.getStrokesCopy())
                    viewModel.updateNotebookDefaults(notebook.id, template, page.sheetStyle)
                    currentNotebook = notebook.copy(
                        template = template,
                        sheetStyle = page.sheetStyle
                    )
                    updateToolbarState()
                }
            }
            .show()
    }

    private fun showStyleDialog() {
        val notebook = currentNotebook ?: return
        val page = currentPage ?: return

        val styles = SheetStyle.values()
        val labels = styles.map { it.displayName }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Estilo da folha")
            .setItems(labels) { _, which ->
                val style = styles[which]
                lifecycleScope.launch {
                    val updatedPage = page.copy(sheetStyle = style)
                    currentPage = updatedPage
                    canvasView.setSheetStyle(style)
                    viewModel.savePage(updatedPage, canvasView.getStrokesCopy())
                    viewModel.updateNotebookDefaults(notebook.id, page.template, style)
                    currentNotebook = notebook.copy(
                        template = page.template,
                        sheetStyle = style
                    )
                    updateToolbarState()
                }
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
                "${it.title}   (${it.template.displayName} • ${it.sheetStyle.displayName})"
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
                        template = PageTemplate.A4,
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
    // Importação de PDF -> caderno com páginas em bitmap
    // ---------------------------------------------------------------------

    private fun importPdf(uri: Uri) {
        Toast.makeText(this, "Importando PDF…", Toast.LENGTH_SHORT).show()
        val targetFolderId = currentNotebook?.folderId
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    importPdfToNotebook(uri, targetFolderId)
                }
                openNotebook(result.first)
                Toast.makeText(
                    this@MainActivity,
                    "PDF importado (${result.second} páginas)",
                    Toast.LENGTH_SHORT
                ).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Erro ao abrir PDF: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * Executa toda a parte pesada (PdfRenderer + bitmaps + arquivos) fora da
     * main thread. Retorna (notebookId, pageCount).
     */
    private suspend fun importPdfToNotebook(
        uri: Uri,
        folderId: Long?
    ): Pair<Long, Int> {
        val displayName = queryDisplayName(uri) ?: "PDF importado"
        val tempFile = File(cacheDir, "import_${System.currentTimeMillis()}.pdf")
        val directory = File(filesDir, "pdf_pages").apply { mkdirs() }
        var descriptor: ParcelFileDescriptor? = null
        var renderer: PdfRenderer? = null
        var createdNotebookId: Long? = null

        try {
            val input = contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Não foi possível ler o arquivo PDF")
            input.use { stream ->
                FileOutputStream(tempFile).use { output -> stream.copyTo(output) }
            }

            // Locais não-nulos evitam depender de smart cast em variáveis
            // capturadas pelos blocos de limpeza do finally.
            val pfd = ParcelFileDescriptor.open(
                tempFile,
                ParcelFileDescriptor.MODE_READ_ONLY
            )
            descriptor = pfd
            val pdfRenderer = PdfRenderer(pfd)
            renderer = pdfRenderer
            val pageCount = pdfRenderer.pageCount
            if (pageCount <= 0) throw IllegalStateException("PDF sem páginas")

            val notebookId = viewModel.createNotebook(
                title = displayName.removeSuffix(".pdf"),
                template = PageTemplate.A4,
                sheetStyle = SheetStyle.BLANK,
                createFirstPage = false,
                folderId = folderId
            )
            createdNotebookId = notebookId

            for (index in 0 until pageCount) {
                val pdfPage = pdfRenderer.openPage(index)
                val size = pdfPageTargetSize(pdfPage.width, pdfPage.height)
                val bitmap = Bitmap.createBitmap(size.first, size.second, Bitmap.Config.ARGB_8888)
                try {
                    bitmap.eraseColor(Color.WHITE)
                    pdfPage.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                } finally {
                    pdfPage.close()
                }

                val file = File(directory, "nb_${notebookId}_p${index}.png")
                try {
                    FileOutputStream(file).use { output ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                    }
                } finally {
                    bitmap.recycle()
                }

                viewModel.createPage(
                    notebookId = notebookId,
                    pageIndex = index,
                    template = PageTemplate.A4,
                    sheetStyle = SheetStyle.BLANK,
                    pageWidth = size.first,
                    pageHeight = size.second,
                    backgroundPath = file.absolutePath
                )
            }

            return notebookId to pageCount
        } catch (e: Exception) {
            createdNotebookId?.let { id ->
                runCatching { viewModel.deleteNotebook(id) }
                directory.listFiles()
                    ?.filter { it.name.startsWith("nb_${id}_p") }
                    ?.forEach { it.delete() }
            }
            throw e
        } finally {
            runCatching { renderer?.close() }
            runCatching { descriptor?.close() }
            tempFile.delete()
        }
    }

    private fun pdfPageTargetSize(width: Int, height: Int): Pair<Int, Int> {
        val largest = max(width, height).coerceAtLeast(1)
        val scale = min(2.2f, 2400f / largest)
        val targetWidth = (width * scale).roundToInt().coerceAtLeast(1)
        val targetHeight = (height * scale).roundToInt().coerceAtLeast(1)
        return targetWidth to targetHeight
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

    // ---------------------------------------------------------------------
    // Exportação para PDF
    // ---------------------------------------------------------------------

    private fun exportPdf(uri: Uri) {
        lifecycleScope.launch {
            try {
                saveCurrentPage()
                val notebook = currentNotebook ?: return@launch
                val pages = viewModel.getPages(notebook.id)

                if (pages.isEmpty()) {
                    Toast.makeText(this@MainActivity, "Nenhuma página para exportar.", Toast.LENGTH_SHORT).show()
                    return@launch
                }

                // Lido na main thread antes de ir para IO.
                val pressure = canvasView.pressureEnabled

                withContext(Dispatchers.IO) {
                    val document = PdfDocument()
                    try {
                        for (page in pages) {
                            val strokes = viewModel.getStrokes(page.id)

                            if (page.template.isInfinite) {
                                val bounds = NoteCanvasView.computeContentBounds(strokes)
                                val width = ceil(bounds.width()).toInt().coerceAtLeast(1)
                                val height = ceil(bounds.height()).toInt().coerceAtLeast(1)
                                val pdfSize = pdfPageSize(width, height)
                                val pdfPage = document.startPage(
                                    PdfDocument.PageInfo
                                        .Builder(pdfSize.first, pdfSize.second, page.pageIndex + 1)
                                        .create()
                                )
                                // O canvas do PdfDocument usa pontos (1/72"):
                                // desenhamos em pixels de página e escalamos.
                                pdfPage.canvas.scale(
                                    pdfSize.first / width.toFloat(),
                                    pdfSize.second / height.toFloat()
                                )
                                canvasView.renderInfinitePageForExport(
                                    targetCanvas = pdfPage.canvas,
                                    pageStrokes = strokes,
                                    contentBounds = bounds,
                                    style = page.sheetStyle,
                                    pressure = pressure
                                )
                                document.finishPage(pdfPage)
                            } else {
                                val background = decodeBackground(page.backgroundPath)
                                val width = page.pageWidth.coerceAtLeast(1)
                                val height = page.pageHeight.coerceAtLeast(1)
                                val pdfSize = pdfPageSize(width, height)
                                val pdfPage = document.startPage(
                                    PdfDocument.PageInfo
                                        .Builder(pdfSize.first, pdfSize.second, page.pageIndex + 1)
                                        .create()
                                )
                                pdfPage.canvas.scale(
                                    pdfSize.first / width.toFloat(),
                                    pdfSize.second / height.toFloat()
                                )
                                canvasView.renderPageForExport(
                                    targetCanvas = pdfPage.canvas,
                                    bgBitmap = background,
                                    pageStrokes = strokes,
                                    targetWidth = width,
                                    targetHeight = height,
                                    style = page.sheetStyle,
                                    pressure = pressure
                                )
                                document.finishPage(pdfPage)
                                background?.recycle()
                            }
                        }

                        val output = contentResolver.openOutputStream(uri)
                            ?: throw IllegalStateException("Destino do PDF indisponível")
                        output.use { document.writeTo(it) }
                    } finally {
                        document.close()
                    }
                }

                Toast.makeText(this@MainActivity, "PDF salvo com sucesso!", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(
                    this@MainActivity,
                    "Erro ao salvar PDF: ${e.message}",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    /**
     * O PdfDocument mede páginas em pontos (1/72"). Convertemos o espaço de
     * pixels da página para uma largura A4 (595 pt) mantendo a proporção,
     * para que o PDF exportado tenha tamanho imprimível.
     */
    private fun pdfPageSize(widthPx: Int, heightPx: Int): Pair<Int, Int> {
        val safeWidth = widthPx.coerceAtLeast(1)
        val safeHeight = heightPx.coerceAtLeast(1)
        val pdfWidth = PDF_PAGE_WIDTH_PT
        val pdfHeight = (pdfWidth.toFloat() * safeHeight / safeWidth)
            .roundToInt()
            .coerceAtLeast(1)
        return pdfWidth to pdfHeight
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

    // ---------------------------------------------------------------------
    // Ciclo de vida
    // ---------------------------------------------------------------------

    override fun onPause() {
        super.onPause()
        lifecycleScope.launch {
            withContext(NonCancellable) { saveCurrentPage() }
        }
    }

    companion object {
        /** Chave do Intent para abrir direto uma nota vinda da biblioteca. */
        const val EXTRA_NOTEBOOK_ID = "com.notes6lite.app.extra.NOTEBOOK_ID"

        /** Largura de uma folha A4 em pontos (1/72"), usada na exportação. */
        private const val PDF_PAGE_WIDTH_PT = 595
    }
}
