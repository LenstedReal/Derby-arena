package com.example.game.systems

import com.example.game.*
import kotlin.math.*

/**
 * DamageSystem: the single authoritative place where hit points change.
 * Handles armor absorption, spawn protection, stun, kill attribution, wreck state and damage stages
 * (0 = pristine, 1 = scratched, 2 = hood popped + smoke, 3 = fire, 4 = wreck) used by GpuSync / VFX.
 */
class DamageSystem {

    var spawnProtectionFrames = 0
    var playerDamageTakenThisMatch = 0f
        private set

    fun reset() {
        spawnProtectionFrames = 120
        playerDamageTakenThisMatch = 0f
    }

    fun tick() {
        if (spawnProtectionFrames > 0) spawnProtectionFrames--
    }

    /** Returns actual damage applied (0 when blocked by spawn protection or already dead). */
    fun damagePlayer(p: PlayerCar, amount: Float, fromX: Float, fromY: Float, frame: Long, events: MutableList<CombatEvent>, ignoreProtection: Boolean = false): Float {
        if (p.isDead) return 0f
        if (spawnProtectionFrames > 0 && !ignoreProtection) return 0f
        var dmg = (amount - p.armor * 0.1f).coerceAtLeast(amount * 0.35f)
        if (p.armorPoints > 0f) {
            val absorbed = min(p.armorPoints, dmg * 0.65f)
            p.armorPoints -= absorbed
            dmg -= absorbed
        }
        p.health = (p.health - dmg).coerceAtLeast(0f)
        p.lastHitDirDeg = Math.toDegrees(atan2((fromY - p.y).toDouble(), (fromX - p.x).toDouble())).toFloat()
        p.lastHitFrame = frame
        playerDamageTakenThisMatch += dmg
        events.add(CombatEvent.PlayerDamaged(p.x, p.y, dmg, p.lastHitDirDeg))
        if (p.health <= 0f) p.isDead = true
        return dmg
    }

    /** Returns true if the enemy was killed by this hit. */
    fun damageEnemy(e: EnemyCar, amount: Float, attackerId: String, events: MutableList<CombatEvent>): Boolean {
        if (e.isDead || e.isWreckage) return false
        var dmg = amount
        if (e.armorPoints > 0f) {
            val absorbed = min(e.armorPoints, dmg * 0.6f)
            e.armorPoints -= absorbed
            dmg -= absorbed
        }
        e.health -= dmg
        e.recentDamage += dmg
        events.add(CombatEvent.Hit(e.x, e.y, "enemy:${e.id}", dmg, attackerId == "player"))
        if (e.health <= 0f) {
            e.health = 0f
            e.isDead = true
            e.isWreckage = true
            e.killerId = attackerId
            e.speed = 0f
            events.add(CombatEvent.Kill(e.x, e.y, "enemy:${e.id}", attackerId))
            events.add(CombatEvent.Explosion(e.x, e.y, 60f))
            return true
        }
        return false
    }

    fun applyStun(e: EnemyCar, frames: Int) {
        if (frames > 0) e.stunFrames = max(e.stunFrames, frames)
    }

    fun applyStunPlayer(p: PlayerCar, frames: Int) {
        if (frames > 0) p.stunFrames = max(p.stunFrames, frames)
    }

    /** Damage stage from health fraction. */
    fun stage(healthFrac: Float, dead: Boolean): Int = when {
        dead -> 4
        healthFrac < 0.25f -> 3
        healthFrac < 0.55f -> 2
        healthFrac < 0.85f -> 1
        else -> 0
    }
}
