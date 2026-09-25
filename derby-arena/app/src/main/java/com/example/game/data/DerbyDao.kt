package com.example.game.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface DerbyDao {
    @Query("SELECT * FROM derby_save_state WHERE id = 1 LIMIT 1")
    fun getSaveState(): Flow<DerbySaveState?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveState(state: DerbySaveState)

    @Query("DELETE FROM derby_save_state")
    suspend fun clearSave()
}
