package com.example.game.systems

import com.example.game.EnemyCar
import com.example.game.PlayerCar

/**
 * GameStateSystem: match phase machine (LOBBY → COUNTDOWN → PLAYING → WON / ELIMINATED),
 * placement, match clock and result summary. Pure Kotlin, deterministic, network-friendly.
 */
class GameStateSystem {

    enum class MatchPhase { LOBBY, COUNTDOWN, PLAYING, WON, ELIMINATED }

    data class MatchResult(
        val won: Boolean,
        val placement: Int,
        val totalCombatants: Int,
        val kills: Int,
        val timeSeconds: Int,
        val damageTaken: Int,
        val scrapEarned: Int,
        val stars: Int
    )

    var phase = MatchPhase.LOBBY
        private set
    var countdown = 3f
        private set
    var matchTime = 0f
        private set
    var totalCombatants = 1
        private set

    fun startCountdown(enemyCount: Int) {
        phase = MatchPhase.COUNTDOWN
        countdown = 3.4f
        matchTime = 0f
        totalCombatants = enemyCount + 1
    }

    /** Returns true on the frame the countdown finishes. */
    fun tickCountdown(dt: Float): Boolean {
        if (phase != MatchPhase.COUNTDOWN) return false
        countdown -= dt
        if (countdown <= 0f) {
            phase = MatchPhase.PLAYING
            return true
        }
        return false
    }

    fun tickMatch(dt: Float) {
        if (phase == MatchPhase.PLAYING) matchTime += dt
    }

    fun aliveEnemies(enemies: List<EnemyCar>): Int = enemies.count { !it.isDead && !it.isWreckage }

    /** Evaluates win / loss. Returns the new phase if the match ended, else null. */
    fun evaluate(player: PlayerCar, enemies: List<EnemyCar>): MatchPhase? {
        if (phase != MatchPhase.PLAYING) return null
        if (player.isDead || player.health <= 0f) {
            phase = MatchPhase.ELIMINATED
            return phase
        }
        if (aliveEnemies(enemies) == 0) {
            phase = MatchPhase.WON
            return phase
        }
        return null
    }

    fun placement(enemies: List<EnemyCar>): Int = aliveEnemies(enemies) + 1

    fun toLobby() {
        phase = MatchPhase.LOBBY
    }
}
