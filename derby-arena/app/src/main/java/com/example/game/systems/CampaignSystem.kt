package com.example.game.systems

import com.example.game.DerbyTheme

/**
 * 450-Level Campaign System for Derby Arena.
 * Features 5 grand championships / chapters, exponential difficulty scaling,
 * elite mini-boss encounters every 10 levels, and grand Titan Boss battles every 50 levels.
 */
class CampaignSystem {

    companion object {
        const val TOTAL_LEVELS = 450
        const val LEVELS_PER_CHAPTER = 90
        const val TOTAL_CHAPTERS = 5
    }

    data class ChapterInfo(
        val chapterNumber: Int,
        val title: String,
        val subtitle: String,
        val startLevel: Int,
        val endLevel: Int,
        val theme: DerbyTheme
    )

    data class LevelConfig(
        val level: Int,
        val chapterNumber: Int,
        val chapterTitle: String,
        val levelTitle: String,
        val enemyCount: Int,
        val enemyHpMultiplier: Float,
        val enemySpeedMultiplier: Float,
        val enemyDamageMultiplier: Float,
        val targetTimeSeconds: Int,
        val isBossLevel: Boolean,
        val bossName: String?,
        val scrapReward: Int,
        val theme: DerbyTheme
    )

    private val chapters = listOf(
        ChapterInfo(
            chapterNumber = 1,
            title = "NOVICE COLOSSEUM",
            subtitle = "Learn the art of demolition in the ancient sand pits.",
            startLevel = 1,
            endLevel = 90,
            theme = DerbyTheme.TOXIC_SMOG
        ),
        ChapterInfo(
            chapterNumber = 2,
            title = "SCRAP WASTELAND",
            subtitle = "Battle ruthless raiders among industrial scrapyard ruins.",
            startLevel = 91,
            endLevel = 180,
            theme = DerbyTheme.CRIMSON_WASTE
        ),
        ChapterInfo(
            chapterNumber = 3,
            title = "TOXIC FOUNDRY",
            subtitle = "High-octane carnage over acid vats and explosive silos.",
            startLevel = 181,
            endLevel = 270,
            theme = DerbyTheme.ACID_RAIN
        ),
        ChapterInfo(
            chapterNumber = 4,
            title = "MIDNIGHT CYBER-RING",
            subtitle = "High-speed drift warfare under cold neon floodlights.",
            startLevel = 271,
            endLevel = 360,
            theme = DerbyTheme.MIDNIGHT_ASH
        ),
        ChapterInfo(
            chapterNumber = 5,
            title = "IRON APOCALYPSE",
            subtitle = "The final gauntlet. Only the ultimate destruction gladiator survives.",
            startLevel = 361,
            endLevel = 450,
            theme = DerbyTheme.CRIMSON_WASTE
        )
    )

    fun getChapters(): List<ChapterInfo> = chapters

    fun getChapterForLevel(level: Int): ChapterInfo {
        val clamped = level.coerceIn(1, TOTAL_LEVELS)
        val idx = ((clamped - 1) / LEVELS_PER_CHAPTER).coerceIn(0, chapters.size - 1)
        return chapters[idx]
    }

    fun getLevelConfig(level: Int): LevelConfig {
        val lvl = level.coerceIn(1, TOTAL_LEVELS)
        val chapter = getChapterForLevel(lvl)
        val isBoss = (lvl % 10 == 0)

        // Difficulty scaling formulas
        val enemyCount = when {
            lvl <= 5 -> 2
            lvl <= 20 -> 3
            lvl <= 60 -> 4
            lvl <= 150 -> 5
            lvl <= 300 -> 6
            else -> 7
        }

        val hpMult = 1.0f + (lvl - 1) * 0.0075f + (if (isBoss) 0.6f else 0f)
        val speedMult = (1.0f + ((lvl - 1) / 450f) * 0.32f).coerceAtMost(1.35f)
        val damageMult = 1.0f + (lvl - 1) * 0.0055f

        val bossName = if (isBoss) {
            when (lvl) {
                10 -> "CRUSHER ALPHA"
                20 -> "VIPER OVERLORD"
                50 -> "GLADIATOR TITAN"
                90 -> "COLOSSEUM EMPEROR"
                100 -> "SCRAPGUN PRIME"
                150 -> "JUGGERNAUT WARLORD"
                180 -> "RUST MONARCH"
                200 -> "ACID GOLIATH"
                250 -> "SLUDGE DREADNOUGHT"
                270 -> "FOUNDRY TYRANT"
                300 -> "PHANTOM REAPER"
                350 -> "CYBER WRECKER"
                360 -> "MIDNIGHT BERSERKER"
                400 -> "APOCALYPSE DREAD"
                450 -> "THE OMEGA WAR RIG"
                else -> "ELITE WAR RIG #${lvl / 10}"
            }
        } else null

        val levelTitle = if (isBoss) {
            "BOSS: $bossName"
        } else {
            "STAGE $lvl · ${chapter.title}"
        }

        val scrapReward = 50 + lvl * 12 + (if (isBoss) 150 else 0)
        val targetTime = if (isBoss) 95 else 75

        return LevelConfig(
            level = lvl,
            chapterNumber = chapter.chapterNumber,
            chapterTitle = chapter.title,
            levelTitle = levelTitle,
            enemyCount = enemyCount,
            enemyHpMultiplier = hpMult,
            enemySpeedMultiplier = speedMult,
            enemyDamageMultiplier = damageMult,
            targetTimeSeconds = targetTime,
            isBossLevel = isBoss,
            bossName = bossName,
            scrapReward = scrapReward,
            theme = chapter.theme
        )
    }

    /** Calculates 1 to 3 stars earned for finishing the level */
    fun calculateStars(
        level: Int,
        playerHealthFraction: Float,
        completionTimeSeconds: Int
    ): Int {
        val config = getLevelConfig(level)
        var stars = 1 // 1 star for clearing the stage
        if (playerHealthFraction >= 0.40f) stars++
        if (completionTimeSeconds <= config.targetTimeSeconds) stars++
        return stars.coerceIn(1, 3)
    }
}
