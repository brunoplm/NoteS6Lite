package com.notes6lite.app.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A "nota" da biblioteca é o mesmo documento que o editor abre: um caderno
 * com páginas e traços. `folderId` nulo significa que a nota está na raiz.
 *
 * Não usamos FK para `folders` aqui porque o SQLite não permite adicionar
 * chave estrangeira via ALTER TABLE (migração 1 -> 2). A exclusão em cascata
 * é feita de forma explícita e transacional no NotebookRepository.
 */
@Entity(
    tableName = "notebooks",
    indices = [Index("folderId")]
)
data class NotebookEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val template: String,
    val sheetStyle: String,
    val createdAt: Long,
    val updatedAt: Long,
    val coverColor: Int,
    val folderId: Long? = null
)
