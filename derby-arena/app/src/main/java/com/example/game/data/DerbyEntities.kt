package com.example.game.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "derby_save_state")
data class DerbySaveState(
    @PrimaryKey val id: Int = 1,
    val highScore: Int = 0,
    val totalScrap: Int = 0,
    val engineLevel: Int = 1,
    val armorLevel: Int = 1,
    val turretLevel: Int = 1,
    val magnetLevel: Int = 1,
    val totalKills: Int = 0,
    val totalGamesPlayed: Int = 0,
    val currentLevel: Int = 1,
    val maxUnlockedLevel: Int = 1,
    val totalStars: Int = 0,
    val starsMapJson: String = ""
)
