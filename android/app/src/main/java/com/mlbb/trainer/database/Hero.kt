package com.mlbb.trainer.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "heroes")
data class Hero(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val modelPath: String = "",
    val modelStatus: String = "none",  // none, importing, ready, error
    val createdAt: Long = System.currentTimeMillis()
)
