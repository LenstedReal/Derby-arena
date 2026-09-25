package com.example.game.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [DerbySaveState::class], version = 2, exportSchema = false)
abstract class DerbyDatabase : RoomDatabase() {
    abstract fun derbyDao(): DerbyDao

    companion object {
        @Volatile
        private var INSTANCE: DerbyDatabase? = null

        fun getDatabase(context: Context): DerbyDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DerbyDatabase::class.java,
                    "derby_database"
                )
                .fallbackToDestructiveMigration(true)
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
