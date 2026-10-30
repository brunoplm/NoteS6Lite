package com.notes6lite.app.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.notes6lite.app.data.local.entity.PageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PageDao {

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY pageIndex ASC")
    suspend fun getPages(notebookId: Long): List<PageEntity>

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId ORDER BY pageIndex ASC")
    fun observePages(notebookId: Long): Flow<List<PageEntity>>

    @Query("SELECT * FROM pages WHERE notebookId = :notebookId AND pageIndex = :pageIndex LIMIT 1")
    suspend fun getPage(notebookId: Long, pageIndex: Int): PageEntity?

    @Query("SELECT * FROM pages WHERE id = :id LIMIT 1")
    suspend fun getById(id: Long): PageEntity?

    @Query("SELECT COUNT(*) FROM pages WHERE notebookId = :notebookId")
    suspend fun count(notebookId: Long): Int

    @Query("SELECT MAX(pageIndex) FROM pages WHERE notebookId = :notebookId")
    suspend fun maxPageIndex(notebookId: Long): Int?

    @Query(
        "SELECT backgroundPath FROM pages WHERE notebookId IN (:notebookIds) " +
            "AND backgroundPath IS NOT NULL"
    )
    suspend fun getBackgroundPaths(notebookIds: List<Long>): List<String>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(page: PageEntity): Long

    @Update
    suspend fun update(page: PageEntity)

    @Delete
    suspend fun delete(page: PageEntity)
}
