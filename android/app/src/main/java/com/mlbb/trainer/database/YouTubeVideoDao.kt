package com.mlbb.trainer.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface YouTubeVideoDao {
    @Query("SELECT * FROM youtube_videos WHERE heroId = :heroId ORDER BY createdAt DESC")
    fun getVideosForHero(heroId: Long): Flow<List<YouTubeVideo>>

    @Query("SELECT * FROM youtube_videos WHERE heroId = :heroId ORDER BY createdAt DESC")
    suspend fun getVideosForHeroList(heroId: Long): List<YouTubeVideo>

    @Insert
    suspend fun insert(video: YouTubeVideo): Long

    @Delete
    suspend fun delete(video: YouTubeVideo)

    @Query("DELETE FROM youtube_videos WHERE heroId = :heroId")
    suspend fun deleteAllForHero(heroId: Long)
}
