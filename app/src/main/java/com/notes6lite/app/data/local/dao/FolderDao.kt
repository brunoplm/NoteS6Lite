package com.notes6lite.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.notes6lite.app.data.local.entity.FolderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FolderDao {

    /**
     * Filhos diretos de uma pasta. O parâmetro nulo representa a raiz:
     * `parentFolderId IS NULL`.
     */
    @Query(
        "SELECT * FROM folders WHERE (:parentId IS NULL AND parentFolderId IS NULL) " +
            "OR parentFolderId = :parentId ORDER BY name COLLATE NOCASE ASC"
    )
    fun observeChildren(parentId: Long?): Flow<List<FolderEntity>>

    @Query(
        "SELECT * FROM folders WHERE (:parentId IS NULL AND parentFolderId IS NULL) " +
            "OR parentFolderId = :parentId ORDER BY name COLLATE NOCASE ASC"
    )
    suspend fun getChildren(parentId: Long?): List<FolderEntity>

    @Query("SELECT * FROM folders ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAll(): List<FolderEntity>

    @Query("SELECT * FROM folders WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): FolderEntity?

    @Insert
    suspend fun insert(folder: FolderEntity): Long

    @Query("UPDATE folders SET name = :name WHERE id = :id")
    suspend fun rename(id: Long, name: String)

    @Query("UPDATE folders SET parentFolderId = :parentId WHERE id = :id")
    suspend fun move(id: Long, parentId: Long?)

    @Query("DELETE FROM folders WHERE id = :id")
    suspend fun deleteById(id: Long)
}
