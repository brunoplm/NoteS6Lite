package com.notes6lite.app.domain.model

data class Notebook(
    val id: Long,
    val title: String,
    val template: PageTemplate,
    val sheetStyle: SheetStyle,
    val createdAt: Long,
    val updatedAt: Long,
    val coverColor: Int,
    /** Pasta onde a nota vive. Nulo = raiz da biblioteca. */
    val folderId: Long? = null
)
