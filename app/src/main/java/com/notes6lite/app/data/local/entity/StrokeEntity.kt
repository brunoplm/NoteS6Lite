package com.notes6lite.app.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "strokes",
    foreignKeys = [
        ForeignKey(
            entity = PageEntity::class,
            parentColumns = ["id"],
            childColumns = ["pageId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("pageId")]
)
data class StrokeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val pageId: Long,
    val orderIndex: Int,
    val color: Int,
    val baseWidth: Float,
    val mode: String,
    /**
     * Pontos empacotados em binário (count + x,y,pressure por ponto).
     * Formato compacto e rápido, sem JSON no caminho crítico.
     */
    val points: ByteArray
)
