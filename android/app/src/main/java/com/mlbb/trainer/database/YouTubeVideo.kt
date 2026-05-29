package com.mlbb.trainer.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "youtube_videos")
data class YouTubeVideo(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val heroId: Long,
    val url: String,
    val title: String = "",
    val status: String = "added",
    val createdAt: Long = System.currentTimeMillis()
)
