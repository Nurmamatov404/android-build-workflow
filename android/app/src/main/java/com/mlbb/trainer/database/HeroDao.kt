package com.mlbb.trainer.database

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface HeroDao {
    @Query("SELECT * FROM heroes ORDER BY createdAt DESC")
    fun getAllHeroes(): Flow<List<Hero>>

    @Query("SELECT * FROM heroes ORDER BY createdAt DESC")
    suspend fun getAllHeroesList(): List<Hero>

    @Insert
    suspend fun insert(hero: Hero): Long

    @Update
    suspend fun update(hero: Hero)

    @Delete
    suspend fun delete(hero: Hero)

    @Query("SELECT * FROM heroes WHERE id = :id")
    suspend fun getHeroById(id: Long): Hero?
}
