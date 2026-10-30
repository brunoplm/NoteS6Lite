package com.notes6lite.app.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.notes6lite.app.data.local.entity.StrokeEntity

@Dao
interface StrokeDao {

    @Query("SELECT * FROM strokes WHERE pageId = :pageId ORDER BY orderIndex ASC")
    suspend fun getForPage(pageId: Long): List<StrokeEntity>

    @Insert
    suspend fun insertAll(strokes: List<StrokeEntity>)

    @Query("DELETE FROM strokes WHERE pageId = :pageId")
    suspend fun deleteForPage(pageId: Long)
}
