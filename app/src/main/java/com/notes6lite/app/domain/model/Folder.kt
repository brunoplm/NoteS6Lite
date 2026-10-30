package com.notes6lite.app.domain.model

/**
 * Pasta da biblioteca. O aninhamento é infinito: parentFolderId nulo
 * significa "raiz"; qualquer outro id aponta para a pasta pai.
 */
data class Folder(
    val id: Long,
    val name: String,
    val parentFolderId: Long?,
    val createdAt: Long
)

/**
 * Conteúdo de uma pasta: subpastas + notas (documentos).
 */
data class LibraryContent(
    val folders: List<Folder> = emptyList(),
    val notes: List<Notebook> = emptyList()
) {
    val isEmpty: Boolean
        get() = folders.isEmpty() && notes.isEmpty()
}
