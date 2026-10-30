package com.notes6lite.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.notes6lite.app.data.local.entity.NotebookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NotebookDao {

    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<NotebookEntity>>

    @Query("SELECT * FROM notebooks ORDER BY updatedAt DESC")
    suspend fun getAll(): List<NotebookEntity>

    @Query(
        "SELECT * FROM notebooks WHERE (:folderId IS NULL AND folderId IS NULL) " +
            "OR folderId = :folderId ORDER BY updatedAt DESC"
    )
    fun observeByFolder(folderId: Long?): Flow<List<NotebookEntity>>

    @Query(
        "SELECT * FROM notebooks WHERE (:folderId IS NULL AND folderId IS NULL) " +
            "OR folderId = :folderId ORDER BY updatedAt DESC"
    )
    suspend fun getByFolder(folderId: Long?): List<NotebookEntity>

    @Query("SELECT * FROM notebooks WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): NotebookEntity?

    @Insert
    suspend fun insert(notebook: NotebookEntity): Long

    @Update
    suspend fun update(notebook: NotebookEntity)

    @Query("UPDATE notebooks SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: Long, title: String, updatedAt: Long)

    @Query("UPDATE notebooks SET folderId = :folderId, updatedAt = :updatedAt WHERE id = :id")
    suspend fun moveToFolder(id: Long, folderId: Long?, updatedAt: Long)

    @Query("UPDATE notebooks SET template = :template, sheetStyle = :sheetStyle, updatedAt = :updatedAt WHERE id = :id")
    suspend fun updateDefaults(id: Long, template: String, sheetStyle: String, updatedAt: Long)

    @Query("UPDATE notebooks SET updatedAt = :updatedAt WHERE id = :id")
    suspend fun touch(id: Long, updatedAt: Long)

    @Query("SELECT id FROM notebooks WHERE folderId IN (:folderIds)")
    suspend fun getIdsInFolders(folderIds: List<Long>): List<Long>

    @Query("DELETE FROM notebooks WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Delete
    suspend fun delete(notebook: NotebookEntity)
}
