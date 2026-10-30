package com.notes6lite.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "pages",
    foreignKeys = [
        ForeignKey(
            entity = NotebookEntity::class,
            parentColumns = ["id"],
            childColumns = ["notebookId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index("notebookId"),
        Index(value = ["notebookId", "pageIndex"], unique = true)
    ]
)
data class PageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val notebookId: Long,
    val pageIndex: Int,
    val template: String,
    val sheetStyle: String,
    val pageWidth: Int,
    val pageHeight: Int,
    val backgroundPath: String?,
    val createdAt: Long
)
