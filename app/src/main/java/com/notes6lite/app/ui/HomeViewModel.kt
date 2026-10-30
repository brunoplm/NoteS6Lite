package com.notes6lite.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.notes6lite.app.data.repository.NotebookRepository
import com.notes6lite.app.domain.model.Folder
import com.notes6lite.app.domain.model.LibraryContent
import com.notes6lite.app.domain.model.PageTemplate
import com.notes6lite.app.domain.model.SheetStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Destino possível no diálogo "Mover para...". */
data class MoveTarget(
    val id: Long,
    val label: String
)

/**
 * Estado da tela inicial (biblioteca de pastas e notas).
 *
 * O conteúdo da pasta atual é um Flow do Room: qualquer criação, renomeação,
 * movimentação ou exclusão atualiza a grade automaticamente.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(private val repository: NotebookRepository) : ViewModel() {

    private val _currentFolderId = MutableStateFlow<Long?>(null)
    val currentFolderId: StateFlow<Long?> = _currentFolderId.asStateFlow()

    val content: StateFlow<LibraryContent> = _currentFolderId
        .flatMapLatest { folderId -> repository.observeFolderContent(folderId) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = LibraryContent()
        )

    private val writeMutex = Mutex()

    fun openFolder(folderId: Long?) {
        _currentFolderId.value = folderId
    }

    suspend fun folderPath(): List<Folder> = withContext(Dispatchers.IO) {
        repository.getFolderPath(_currentFolderId.value)
    }

    suspend fun parentOf(folderId: Long): Long? = withContext(Dispatchers.IO) {
        repository.getFolder(folderId)?.parentFolderId
    }

    suspend fun createFolder(name: String): Long = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            repository.createFolder(name, _currentFolderId.value)
        }
    }

    suspend fun createNote(): Long = writeMutex.withLock {
        withContext(Dispatchers.IO) {
            repository.createNotebook(
                title = "Nova nota",
                template = PageTemplate.A4,
                sheetStyle = SheetStyle.GRID,
                folderId = _currentFolderId.value
            )
        }
    }

    suspend fun renameFolder(id: Long, name: String) = writeMutex.withLock {
        withContext(Dispatchers.IO) { repository.renameFolder(id, name) }
    }

    suspend fun renameNote(id: Long, title: String) = writeMutex.withLock {
        withContext(Dispatchers.IO) { repository.renameNotebook(id, title) }
    }

    suspend fun moveFolder(id: Long, parentId: Long?) = writeMutex.withLock {
        withContext(Dispatchers.IO) { repository.moveFolder(id, parentId) }
    }

    suspend fun moveNote(id: Long, folderId: Long?) = writeMutex.withLock {
        withContext(Dispatchers.IO) { repository.moveNotebook(id, folderId) }
    }

    suspend fun deleteFolder(id: Long) = writeMutex.withLock {
        withContext(Dispatchers.IO) { repository.deleteFolderRecursive(id) }
    }

    suspend fun deleteNote(id: Long) = writeMutex.withLock {
        withContext(Dispatchers.IO) { repository.deleteNotebookWithFiles(id) }
    }

    /**
     * Pastas válidas como destino de movimentação. Exclui a própria pasta e
     * toda a sua subárvore, para não criar ciclos.
     */
    suspend fun moveTargets(excludeFolderId: Long?): List<MoveTarget> =
        withContext(Dispatchers.IO) {
            val all = repository.getAllFolders()
            val byId = all.associateBy { it.id }
            all.asSequence()
                .filter { folder ->
                    excludeFolderId == null || !isInSubtree(folder.id, excludeFolderId, byId)
                }
                .map { folder -> MoveTarget(folder.id, labelFor(folder, byId)) }
                .sortedBy { it.label.lowercase() }
                .toList()
        }

    private fun labelFor(folder: Folder, byId: Map<Long, Folder>): String {
        val parts = ArrayList<String>()
        var current: Folder? = folder
        var guard = 0
        while (current != null && guard < MAX_DEPTH) {
            parts.add(current.name)
            current = current.parentFolderId?.let { byId[it] }
            guard++
        }
        parts.reverse()
        return parts.joinToString(" / ")
    }

    private fun isInSubtree(
        candidateId: Long,
        ancestorId: Long,
        byId: Map<Long, Folder>
    ): Boolean {
        var current = byId[candidateId]
        var guard = 0
        while (current != null && guard < MAX_DEPTH) {
            if (current.id == ancestorId) return true
            current = current.parentFolderId?.let { byId[it] }
            guard++
        }
        return false
    }

    companion object {
        private const val MAX_DEPTH = 256

        fun factory(repository: NotebookRepository): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(repository) }
        }
    }
}
