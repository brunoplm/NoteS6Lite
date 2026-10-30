package com.notes6lite.app.data.repository

import androidx.room.withTransaction
import com.notes6lite.app.data.local.NoteDatabase
import com.notes6lite.app.data.local.StrokeCodec
import com.notes6lite.app.data.local.entity.FolderEntity
import com.notes6lite.app.data.local.entity.NotebookEntity
import com.notes6lite.app.data.local.entity.PageEntity
import com.notes6lite.app.data.local.entity.StrokeEntity
import com.notes6lite.app.domain.model.Folder
import com.notes6lite.app.domain.model.LibraryContent
import com.notes6lite.app.domain.model.NotePage
import com.notes6lite.app.domain.model.Notebook
import com.notes6lite.app.domain.model.PageSnapshot
import com.notes6lite.app.domain.model.PageTemplate
import com.notes6lite.app.domain.model.SheetStyle
import com.notes6lite.app.domain.model.Stroke
import com.notes6lite.app.domain.model.ToolMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import java.io.File

/**
 * Fonte única de verdade para pastas, notas (cadernos), páginas e traços.
 * Mantém o domínio livre de anotações do Room.
 */
class NotebookRepository(private val database: NoteDatabase) {

    private val notebookDao = database.notebookDao()
    private val pageDao = database.pageDao()
    private val strokeDao = database.strokeDao()
    private val folderDao = database.folderDao()

    // ---------------------------------------------------------------------
    // Biblioteca: pastas + notas
    // ---------------------------------------------------------------------

    fun observeFolderContent(parentFolderId: Long?): Flow<LibraryContent> =
        combine(
            folderDao.observeChildren(parentFolderId),
            notebookDao.observeByFolder(parentFolderId)
        ) { folders, notebooks ->
            LibraryContent(
                folders = folders.map { it.toDomain() },
                notes = notebooks.map { it.toDomain() }
            )
        }

    suspend fun getFolder(id: Long): Folder? = folderDao.getById(id)?.toDomain()

    suspend fun getAllFolders(): List<Folder> = folderDao.getAll().map { it.toDomain() }

    /**
     * Caminho da raiz até a pasta informada (para o breadcrumb).
     * Uma lista vazia significa "raiz".
     */
    suspend fun getFolderPath(folderId: Long?): List<Folder> {
        if (folderId == null) return emptyList()
        val byId = folderDao.getAll().associateBy { it.id }
        val path = ArrayList<Folder>()
        var current = byId[folderId]
        var guard = 0
        while (current != null && guard < MAX_FOLDER_DEPTH) {
            path.add(current.toDomain())
            current = current.parentFolderId?.let { byId[it] }
            guard++
        }
        path.reverse()
        return path
    }

    suspend fun createFolder(name: String, parentFolderId: Long?): Long =
        folderDao.insert(
            FolderEntity(
                name = name.trim().ifBlank { "Nova pasta" },
                parentFolderId = parentFolderId,
                createdAt = System.currentTimeMillis()
            )
        )

    suspend fun renameFolder(id: Long, name: String) {
        folderDao.rename(id, name.trim().ifBlank { "Nova pasta" })
    }

    /**
     * Move uma pasta, impedindo ciclos (mover para dentro da própria
     * subárvore criaria uma pasta sem raiz).
     */
    suspend fun moveFolder(id: Long, newParentId: Long?) {
        if (id == newParentId) return
        val all = folderDao.getAll()
        if (newParentId != null && isInSubtree(newParentId, id, all)) return
        folderDao.move(id, newParentId)
    }

    suspend fun moveNotebook(id: Long, folderId: Long?) {
        notebookDao.moveToFolder(id, folderId, System.currentTimeMillis())
    }

    /**
     * Exclui a pasta com todas as subpastas e notas, e apaga os PNGs de PDF
     * dessas notas. A remoção no banco é transacional; os arquivos são
     * apagados depois, para não segurar a transação em I/O.
     */
    suspend fun deleteFolderRecursive(folderId: Long) {
        val all = folderDao.getAll()
        val subtree = collectSubtreeIds(folderId, all)

        val notebookIds = if (subtree.isEmpty()) {
            emptyList()
        } else {
            notebookDao.getIdsInFolders(subtree)
        }
        val backgroundPaths = if (notebookIds.isEmpty()) {
            emptyList()
        } else {
            pageDao.getBackgroundPaths(notebookIds)
        }

        database.withTransaction {
            if (notebookIds.isNotEmpty()) notebookDao.deleteByIds(notebookIds)
            folderDao.deleteById(folderId)
        }

        backgroundPaths.forEach { path -> runCatching { File(path).delete() } }
    }

    suspend fun deleteNotebookWithFiles(id: Long) {
        val backgroundPaths = pageDao.getBackgroundPaths(listOf(id))
        deleteNotebook(id)
        backgroundPaths.forEach { path -> runCatching { File(path).delete() } }
    }

    // ---------------------------------------------------------------------
    // Notas (cadernos)
    // ---------------------------------------------------------------------

    fun observeNotebooks(): Flow<List<Notebook>> =
        notebookDao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun getNotebooks(): List<Notebook> =
        notebookDao.getAll().map { it.toDomain() }

    suspend fun getSummaryNoteForParent(parentNoteId: Long): Notebook? =
        notebookDao.getSummaryNoteForParent(parentNoteId)?.toDomain()

    suspend fun saveSummaryText(parentNoteId: Long, parentTitle: String, text: String, folderId: Long?): Long {
        val now = System.currentTimeMillis()
        val existing = notebookDao.getSummaryNoteForParent(parentNoteId)
        return if (existing != null) {
            notebookDao.updateSummaryText(existing.id, text, now)
            existing.id
        } else {
            notebookDao.insert(
                NotebookEntity(
                    title = "[Resumo] $parentTitle",
                    template = PageTemplate.RULED.name,
                    sheetStyle = SheetStyle.RULED.name,
                    createdAt = now,
                    updatedAt = now,
                    coverColor = DEFAULT_COVER_COLOR,
                    folderId = folderId,
                    linkedNoteId = parentNoteId,
                    summaryText = text
                )
            )
        }
    }

    suspend fun getNotebook(id: Long): Notebook? =
        notebookDao.getById(id)?.toDomain()

    suspend fun createNotebook(
        title: String,
        template: PageTemplate,
        sheetStyle: SheetStyle,
        coverColor: Int = DEFAULT_COVER_COLOR,
        createFirstPage: Boolean = true,
        folderId: Long? = null
    ): Long {
        val now = System.currentTimeMillis()
        val notebookId = notebookDao.insert(
            NotebookEntity(
                title = title.ifBlank { "Nota sem título" },
                template = template.name,
                sheetStyle = sheetStyle.name,
                createdAt = now,
                updatedAt = now,
                coverColor = coverColor,
                folderId = folderId
            )
        )
        if (createFirstPage) {
            pageDao.insert(
                PageEntity(
                    notebookId = notebookId,
                    pageIndex = 0,
                    template = template.name,
                    sheetStyle = sheetStyle.name,
                    pageWidth = if (template.isInfinite) 0 else template.pageWidth,
                    pageHeight = if (template.isInfinite) 0 else template.pageHeight,
                    backgroundPath = null,
                    createdAt = now
                )
            )
        }
        return notebookId
    }

    // ---------------------------------------------------------------------
    // Páginas
    // ---------------------------------------------------------------------

    suspend fun ensurePage(
        notebookId: Long,
        pageIndex: Int,
        template: PageTemplate,
        sheetStyle: SheetStyle
    ): NotePage {
        pageDao.getPage(notebookId, pageIndex)?.let { return it.toDomain() }

        val now = System.currentTimeMillis()
        val entity = PageEntity(
            notebookId = notebookId,
            pageIndex = pageIndex,
            template = template.name,
            sheetStyle = sheetStyle.name,
            pageWidth = if (template.isInfinite) 0 else template.pageWidth,
            pageHeight = if (template.isInfinite) 0 else template.pageHeight,
            backgroundPath = null,
            createdAt = now
        )
        val insertedId = pageDao.insert(entity)
        if (insertedId == -1L) {
            // Corrida com outra criação da mesma página: relê o registro vencedor.
            return pageDao.getPage(notebookId, pageIndex)?.toDomain()
                ?: entity.copy(id = 0).toDomain()
        }
        return entity.copy(id = insertedId).toDomain()
    }

    suspend fun createPage(
        notebookId: Long,
        pageIndex: Int,
        template: PageTemplate,
        sheetStyle: SheetStyle,
        pageWidth: Int,
        pageHeight: Int,
        backgroundPath: String?
    ): NotePage {
        val now = System.currentTimeMillis()
        val entity = PageEntity(
            notebookId = notebookId,
            pageIndex = pageIndex,
            template = template.name,
            sheetStyle = sheetStyle.name,
            pageWidth = pageWidth,
            pageHeight = pageHeight,
            backgroundPath = backgroundPath,
            createdAt = now
        )
        val insertedId = pageDao.insert(entity)
        if (insertedId == -1L) {
            return pageDao.getPage(notebookId, pageIndex)?.toDomain()
                ?: entity.copy(id = 0).toDomain()
        }
        return entity.copy(id = insertedId).toDomain()
    }

    suspend fun loadPage(
        notebookId: Long,
        pageIndex: Int,
        template: PageTemplate,
        sheetStyle: SheetStyle
    ): PageSnapshot {
        val page = ensurePage(notebookId, pageIndex, template, sheetStyle)
        val strokes = strokeDao.getForPage(page.id).map { it.toDomain() }
        return PageSnapshot(page = page, strokes = strokes)
    }

    suspend fun savePage(page: NotePage, strokes: List<Stroke>) {
        val now = System.currentTimeMillis()
        database.withTransaction {
            // A nota pode ter sido excluída pela biblioteca enquanto o editor
            // estava em segundo plano: evita violar a FK de strokes.pageId.
            if (pageDao.getById(page.id) == null) return@withTransaction

            pageDao.update(page.toEntity())
            strokeDao.deleteForPage(page.id)
            if (strokes.isNotEmpty()) {
                strokeDao.insertAll(
                    strokes.mapIndexed { index, stroke ->
                        StrokeEntity(
                            pageId = page.id,
                            orderIndex = index,
                            color = stroke.color,
                            baseWidth = stroke.baseWidth,
                            mode = stroke.mode.name,
                            points = StrokeCodec.encode(stroke.points)
                        )
                    }
                )
            }
            notebookDao.touch(page.notebookId, now)
        }
    }

    suspend fun getStrokes(pageId: Long): List<Stroke> =
        strokeDao.getForPage(pageId).map { it.toDomain() }

    suspend fun getPages(notebookId: Long): List<NotePage> =
        pageDao.getPages(notebookId).map { it.toDomain() }

    suspend fun pageCount(notebookId: Long): Int =
        pageDao.count(notebookId)

    // ---------------------------------------------------------------------
    // Notas: renomear / excluir / padrões
    // ---------------------------------------------------------------------

    suspend fun renameNotebook(id: Long, title: String) {
        notebookDao.rename(id, title.ifBlank { "Nota sem título" }, System.currentTimeMillis())
    }

    suspend fun updateNotebookDefaults(
        notebookId: Long,
        template: PageTemplate,
        sheetStyle: SheetStyle
    ) {
        notebookDao.updateDefaults(
            id = notebookId,
            template = template.name,
            sheetStyle = sheetStyle.name,
            updatedAt = System.currentTimeMillis()
        )
    }

    suspend fun deleteNotebook(id: Long) {
        val notebook = notebookDao.getById(id) ?: return
        notebookDao.delete(notebook)
    }

    // ---------------------------------------------------------------------
    // Auxiliares de hierarquia
    // ---------------------------------------------------------------------

    private fun collectSubtreeIds(rootId: Long, all: List<FolderEntity>): List<Long> {
        val childrenByParent = all.groupBy { it.parentFolderId }
        val result = ArrayList<Long>()
        val queue = ArrayDeque<Long>()
        queue.add(rootId)
        while (queue.isNotEmpty() && result.size < MAX_FOLDER_DEPTH) {
            val current = queue.removeFirst()
            if (result.contains(current)) continue
            result.add(current)
            childrenByParent[current]?.forEach { child -> queue.add(child.id) }
        }
        return result
    }

    private fun isInSubtree(
        candidateId: Long,
        ancestorId: Long,
        all: List<FolderEntity>
    ): Boolean {
        val byId = all.associateBy { it.id }
        var current = byId[candidateId]
        var guard = 0
        while (current != null && guard < MAX_FOLDER_DEPTH) {
            if (current.id == ancestorId) return true
            current = current.parentFolderId?.let { byId[it] }
            guard++
        }
        return false
    }

    // ---------------------------------------------------------------------
    // Mapeamento Entity <-> Domínio
    // ---------------------------------------------------------------------

    private fun FolderEntity.toDomain() = Folder(
        id = id,
        name = name,
        parentFolderId = parentFolderId,
        createdAt = createdAt
    )

    private fun NotebookEntity.toDomain() = Notebook(
        id = id,
        title = title,
        template = PageTemplate.fromName(template),
        sheetStyle = SheetStyle.fromName(sheetStyle),
        createdAt = createdAt,
        updatedAt = updatedAt,
        coverColor = coverColor,
        folderId = folderId,
        linkedNoteId = linkedNoteId,
        summaryText = summaryText
    )

    private fun PageEntity.toDomain() = NotePage(
        id = id,
        notebookId = notebookId,
        pageIndex = pageIndex,
        template = PageTemplate.fromName(template),
        sheetStyle = SheetStyle.fromName(sheetStyle),
        pageWidth = pageWidth,
        pageHeight = pageHeight,
        backgroundPath = backgroundPath,
        createdAt = createdAt,
        textElementsJson = textElementsJson
    )

    private fun NotePage.toEntity() = PageEntity(
        id = id,
        notebookId = notebookId,
        pageIndex = pageIndex,
        template = template.name,
        sheetStyle = sheetStyle.name,
        pageWidth = pageWidth,
        pageHeight = pageHeight,
        backgroundPath = backgroundPath,
        createdAt = createdAt,
        textElementsJson = textElementsJson
    )

    private fun StrokeEntity.toDomain() = Stroke(
        points = StrokeCodec.decode(points),
        color = color,
        baseWidth = baseWidth,
        mode = ToolMode.fromName(mode)
    )

    companion object {
        const val DEFAULT_COVER_COLOR: Int = 0xFF2563EB.toInt()

        /** Limite de segurança para laços de hierarquia corrompidos. */
        private const val MAX_FOLDER_DEPTH = 256
    }
}
