package com.notes6lite.app.domain.model

data class NotePage(
    val id: Long,
    val notebookId: Long,
    val pageIndex: Int,
    val template: PageTemplate,
    val sheetStyle: SheetStyle,
    val pageWidth: Int,
    val pageHeight: Int,
    val backgroundPath: String?,
    val createdAt: Long
)

data class PageSnapshot(
    val page: NotePage,
    val strokes: List<Stroke>
)
