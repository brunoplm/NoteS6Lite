package com.notes6lite.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.notes6lite.app.data.repository.NotebookRepository
import com.notes6lite.app.domain.model.NotePage
import com.notes6lite.app.domain.model.Notebook
import com.notes6lite.app.domain.model.PageSnapshot
import com.notes6lite.app.domain.model.PageTemplate
import com.notes6lite.app.domain.model.SheetStyle
import com.notes6lite.app.domain.model.Stroke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * ViewModel responsável por orquestrar o repositório Room.
 *
 * Escritas são serializadas por um Mutex para impedir que um autosave
 * atrasado sobrescreva o estado de uma página recém-trocada.
 */
class NotesViewModel(private val repository: NotebookRepository) : ViewModel() {

    val notebooks: StateFlow<List<Notebook>> = repository.observeNotebooks()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyList()
        )

    private val writeMutex = Mutex()

    suspend fun getNotebooks(): List<Notebook> = withContext(Dispatchers.IO) {
        repository.getNotebooks()
    }

    suspend fun getNotebook(id: Long): Notebook? = withContext(Dispatchers.IO) {
        repository.getNotebook(id)
    }

    suspend fun getSummaryNote(parentNoteId: Long): Notebook? = withContext(Dispatchers.IO) {
        repository.getSummaryNoteForParent(parentNoteId)
    }

    suspend fun saveSummary(parentNoteId: Long, parentTitle: String, text: String, folderId: Long?): Long =
        writeMutex.withLock {
            withContext(Dispatchers.IO) {
                repository.saveSummaryText(parentNoteId, parentTitle, text, folderId)
            }
        }

    suspend fun createNotebook(
        title: String,
        template: PageTemplate,
        sheetStyle: SheetStyle,
        createFirstPage: Boolean = true,
        folderId: Long? = null
    ): Long = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            repository.createNotebook(
                title = title,
                template = template,
                sheetStyle = sheetStyle,
                createFirstPage = createFirstPage,
                folderId = folderId
            )
        }
    }

    suspend fun ensurePage(
        notebookId: Long,
        pageIndex: Int,
        template: PageTemplate,
        sheetStyle: SheetStyle
    ): NotePage = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            repository.ensurePage(notebookId, pageIndex, template, sheetStyle)
        }
    }

    suspend fun createPage(
        notebookId: Long,
        pageIndex: Int,
        template: PageTemplate,
        sheetStyle: SheetStyle,
        pageWidth: Int,
        pageHeight: Int,
        backgroundPath: String?
    ): NotePage = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            repository.createPage(
                notebookId = notebookId,
                pageIndex = pageIndex,
                template = template,
                sheetStyle = sheetStyle,
                pageWidth = pageWidth,
                pageHeight = pageHeight,
                backgroundPath = backgroundPath
            )
        }
    }

    suspend fun loadPage(
        notebookId: Long,
        pageIndex: Int,
        template: PageTemplate,
        sheetStyle: SheetStyle
    ): PageSnapshot = withContext(Dispatchers.IO) {
        repository.loadPage(notebookId, pageIndex, template, sheetStyle)
    }

    suspend fun savePage(page: NotePage, strokes: List<Stroke>) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            repository.savePage(page, strokes)
        }
    }

    suspend fun getStrokes(pageId: Long): List<Stroke> = withContext(Dispatchers.IO) {
        repository.getStrokes(pageId)
    }

    suspend fun getPages(notebookId: Long): List<NotePage> = withContext(Dispatchers.IO) {
        repository.getPages(notebookId)
    }

    suspend fun pageCount(notebookId: Long): Int = withContext(Dispatchers.IO) {
        repository.pageCount(notebookId)
    }

    suspend fun renameNotebook(id: Long, title: String) = writeMutex.withLock {
        withContext(Dispatchers.IO) { repository.renameNotebook(id, title) }
    }

    suspend fun updateNotebookDefaults(
        notebookId: Long,
        template: PageTemplate,
        sheetStyle: SheetStyle
    ) = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            repository.updateNotebookDefaults(notebookId, template, sheetStyle)
        }
    }

    suspend fun deleteNotebook(id: Long) = writeMutex.withLock {
        withContext(Dispatchers.IO) { repository.deleteNotebook(id) }
    }

    companion object {
        fun factory(repository: NotebookRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { NotesViewModel(repository) }
        }
    }
}
