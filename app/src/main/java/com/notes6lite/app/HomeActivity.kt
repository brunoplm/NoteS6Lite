package com.notes6lite.app

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.addCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.notes6lite.app.data.local.NoteDatabase
import com.notes6lite.app.data.repository.NotebookRepository
import com.notes6lite.app.domain.model.Folder
import com.notes6lite.app.domain.model.LibraryContent
import com.notes6lite.app.domain.model.Notebook
import com.notes6lite.app.ui.HomeViewModel
import kotlinx.coroutines.launch

/**
 * Tela inicial: biblioteca com pastas hierárquicas (infinitas) e notas.
 *
 * Tocar numa pasta entra nela; tocar numa nota abre o editor (MainActivity).
 * Segurar um item abre renomear / mover / excluir.
 */
class HomeActivity : AppCompatActivity() {

    private val viewModel: HomeViewModel by viewModels {
        HomeViewModel.factory(
            NotebookRepository(NoteDatabase.getInstance(applicationContext))
        )
    }

    private lateinit var recycler: RecyclerView
    private lateinit var tvEmpty: TextView
    private lateinit var breadcrumbContainer: LinearLayout
    private lateinit var adapter: LibraryAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_home)

        recycler = findViewById(R.id.recyclerLibrary)
        tvEmpty = findViewById(R.id.tvEmpty)
        breadcrumbContainer = findViewById(R.id.breadcrumbContainer)

        adapter = LibraryAdapter(
            onClick = { row -> onItemClick(row) },
            onLongClick = { row -> showItemMenu(row) }
        )
        recycler.layoutManager = GridLayoutManager(
            this,
            if (resources.configuration.screenWidthDp >= 600) 4 else 2
        )
        recycler.adapter = adapter

        findViewById<Button>(R.id.btnNewFolder).setOnClickListener {
            showNewFolderDialog()
        }
        findViewById<Button>(R.id.btnNewNote).setOnClickListener {
            createNote()
        }

        setupBackNavigation()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.content.collect { content ->
                        adapter.submit(content)
                        tvEmpty.visibility =
                            if (content.isEmpty) View.VISIBLE else View.GONE
                    }
                }
                launch {
                    viewModel.currentFolderId.collect {
                        renderBreadcrumbs()
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Navegação
    // ---------------------------------------------------------------------

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this) {
            val current = viewModel.currentFolderId.value
            if (current == null) {
                isEnabled = false
                onBackPressedDispatcher.onBackPressed()
            } else {
                lifecycleScope.launch {
                    viewModel.openFolder(viewModel.parentOf(current))
                }
            }
        }
    }

    private suspend fun renderBreadcrumbs() {
        val path = viewModel.folderPath()
        breadcrumbContainer.removeAllViews()
        addCrumb("Início", null)
        for (folder in path) {
            addCrumb(folder.name, folder.id)
        }
    }

    private fun addCrumb(label: String, folderId: Long?) {
        if (breadcrumbContainer.childCount > 0) {
            val separator = TextView(this).apply {
                text = "›"
                setTextColor(Color.parseColor("#94A3B8"))
                textSize = 15f
                setPadding(dp(6), 0, dp(6), 0)
            }
            breadcrumbContainer.addView(separator)
        }

        val crumb = TextView(this).apply {
            text = label
            setTextColor(Color.parseColor("#F8FAFC"))
            textSize = 15f
            isClickable = true
            isFocusable = true
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { viewModel.openFolder(folderId) }
        }
        breadcrumbContainer.addView(crumb)
    }

    private fun openEditor(notebookId: Long) {
        startActivity(
            Intent(this, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_NOTEBOOK_ID, notebookId)
        )
    }

    // ---------------------------------------------------------------------
    // Ações dos itens
    // ---------------------------------------------------------------------

    private fun onItemClick(row: LibraryRow) {
        when (row) {
            is LibraryRow.FolderRow -> viewModel.openFolder(row.folder.id)
            is LibraryRow.NoteRow -> openEditor(row.note.id)
        }
    }

    private fun showItemMenu(row: LibraryRow) {
        val title = when (row) {
            is LibraryRow.FolderRow -> row.folder.name
            is LibraryRow.NoteRow -> row.note.title
        }
        val options = arrayOf("Renomear", "Mover para…", "Excluir")

        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> askRename(row)
                    1 -> showMoveDialog(row)
                    2 -> confirmDelete(row)
                }
            }
            .show()
    }

    private fun showNewFolderDialog() {
        askName("Nova pasta", "") { name ->
            lifecycleScope.launch { viewModel.createFolder(name) }
        }
    }

    private fun createNote() {
        lifecycleScope.launch {
            val notebookId = viewModel.createNote()
            openEditor(notebookId)
        }
    }

    private fun askRename(row: LibraryRow) {
        val initial = when (row) {
            is LibraryRow.FolderRow -> row.folder.name
            is LibraryRow.NoteRow -> row.note.title
        }
        askName("Renomear", initial) { newName ->
            lifecycleScope.launch {
                when (row) {
                    is LibraryRow.FolderRow -> viewModel.renameFolder(row.folder.id, newName)
                    is LibraryRow.NoteRow -> viewModel.renameNote(row.note.id, newName)
                }
            }
        }
    }

    private fun askName(title: String, initial: String, onConfirm: (String) -> Unit) {
        val input = EditText(this).apply {
            setText(initial)
            setSelection(text.length)
            setSingleLine()
            hint = "Nome"
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setView(input)
            .setPositiveButton("Salvar") { _, _ -> onConfirm(input.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showMoveDialog(row: LibraryRow) {
        lifecycleScope.launch {
            val excludeId = (row as? LibraryRow.FolderRow)?.folder?.id
            val targets = viewModel.moveTargets(excludeId)

            val labels = ArrayList<String>(targets.size + 1)
            labels.add("Início (raiz)")
            targets.forEach { labels.add(it.label) }

            AlertDialog.Builder(this@HomeActivity)
                .setTitle("Mover para…")
                .setItems(labels.toTypedArray()) { _, which ->
                    lifecycleScope.launch {
                        val targetFolderId = if (which == 0) null else targets[which - 1].id
                        when (row) {
                            is LibraryRow.FolderRow ->
                                viewModel.moveFolder(row.folder.id, targetFolderId)
                            is LibraryRow.NoteRow ->
                                viewModel.moveNote(row.note.id, targetFolderId)
                        }
                    }
                }
                .setNegativeButton("Cancelar", null)
                .show()
        }
    }

    private fun confirmDelete(row: LibraryRow) {
        val title = when (row) {
            is LibraryRow.FolderRow -> "Excluir pasta \"${row.folder.name}\"?"
            is LibraryRow.NoteRow -> "Excluir nota \"${row.note.title}\"?"
        }
        val message = when (row) {
            is LibraryRow.FolderRow ->
                "As subpastas e todas as notas dentro dela também serão excluídas."
            is LibraryRow.NoteRow ->
                "As páginas e as anotações serão removidas."
        }

        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Excluir") { _, _ ->
                lifecycleScope.launch {
                    when (row) {
                        is LibraryRow.FolderRow -> viewModel.deleteFolder(row.folder.id)
                        is LibraryRow.NoteRow -> viewModel.deleteNote(row.note.id)
                    }
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}

// -------------------------------------------------------------------------
// Itens da grade
// -------------------------------------------------------------------------

private sealed interface LibraryRow {
    data class FolderRow(val folder: Folder) : LibraryRow
    data class NoteRow(val note: Notebook) : LibraryRow
}

private class LibraryAdapter(
    private val onClick: (LibraryRow) -> Unit,
    private val onLongClick: (LibraryRow) -> Unit
) : RecyclerView.Adapter<LibraryAdapter.Holder>() {

    private val rows = ArrayList<LibraryRow>()

    @Suppress("NotifyDataSetChanged")
    fun submit(content: LibraryContent) {
        rows.clear()
        content.folders.forEach { rows.add(LibraryRow.FolderRow(it)) }
        content.notes.forEach { rows.add(LibraryRow.NoteRow(it)) }
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_library, parent, false)
        return Holder(view)
    }

    override fun getItemCount(): Int = rows.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(rows[position])
    }

    inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {

        private val tvIcon: TextView = itemView.findViewById(R.id.tvIcon)
        private val tvName: TextView = itemView.findViewById(R.id.tvName)
        private val tvSubtitle: TextView = itemView.findViewById(R.id.tvSubtitle)

        fun bind(row: LibraryRow) {
            when (row) {
                is LibraryRow.FolderRow -> {
                    tvIcon.text = "📁"
                    tvName.text = row.folder.name
                    tvSubtitle.text = "Pasta"
                }

                is LibraryRow.NoteRow -> {
                    tvIcon.text = "📄"
                    tvName.text = row.note.title
                    tvSubtitle.text = DateUtils.getRelativeTimeSpanString(
                        row.note.updatedAt,
                        System.currentTimeMillis(),
                        DateUtils.MINUTE_IN_MILLIS
                    )
                }
            }

            itemView.setOnClickListener { onClick(row) }
            itemView.setOnLongClickListener {
                onLongClick(row)
                true
            }
        }
    }
}
