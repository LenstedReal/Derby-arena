package com.example

import com.example.game.systems.CampaignSystem
import org.junit.Assert.*
import org.junit.Test

class CampaignSystemTest {

    @Test
    fun testCampaignHas450LevelsAnd5Chapters() {
        val campaign = CampaignSystem()
        val chapters = campaign.getChapters()
        assertEquals(5, chapters.size)
        assertEquals(1, chapters.first().startLevel)
        assertEquals(450, chapters.last().endLevel)
    }

    @Test
    fun testLevelProgressionScaling() {
        val campaign = CampaignSystem()
        val lvl1 = campaign.getLevelConfig(1)
        val lvl50 = campaign.getLevelConfig(50)
        val lvl450 = campaign.getLevelConfig(450)

        // Difficulty increases progressively
        assertTrue(lvl50.enemyHpMultiplier > lvl1.enemyHpMultiplier)
        assertTrue(lvl450.enemyHpMultiplier > lvl50.enemyHpMultiplier)
        assertTrue(lvl450.scrapReward > lvl1.scrapReward)
        assertTrue(lvl50.isBossLevel) // Every 10th level is boss
    }

    @Test
    fun testCalculateStars() {
        val campaign = CampaignSystem()
        // High HP and fast time gives 3 stars
        val stars3 = campaign.calculateStars(level = 1, playerHealthFraction = 0.85f, completionTimeSeconds = 25)
        assertEquals(3, stars3)

        // Low HP and slow time gives 1 star minimum
        val stars1 = campaign.calculateStars(level = 1, playerHealthFraction = 0.20f, completionTimeSeconds = 80)
        assertEquals(1, stars1)
    }
}
