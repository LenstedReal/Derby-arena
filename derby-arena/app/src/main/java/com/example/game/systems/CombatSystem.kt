package com.example.game.systems

import com.example.game.*
import kotlin.math.*

/**
 * CombatSystem: projectile simulation for every combatant (player and AI-vs-AI), hit detection
 * against vehicles / obstacles / barrier, splash damage, EMP stun, and turret aiming for the player.
 * All hit-point changes go through DamageSystem; every effect is reported as a CombatEvent.
 */
class CombatSystem(
    private val weaponSystem: WeaponSystem,
    private val damageSystem: DamageSystem
) {

    private val bulletsList = ArrayList<Bullet>(256)
    var lastPlayerRecoil = 0f
        private set

    fun getBullets(): List<Bullet> = bulletsList

    fun clear() {
        bulletsList.clear()
        lastPlayerRecoil = 0f
    }

    /** Player aim + fire. */
    fun updatePlayerCombat(
        player: PlayerCar,
        enemies: List<EnemyCar>,
        input: PlayerInput,
        autoAim: Boolean,
        events: MutableList<CombatEvent>
    ) {
        lastPlayerRecoil *= 0.82f
        if (player.isDead) return
        weaponSystem.tick(player.weapon)
        if (player.stunFrames > 0) { player.stunFrames--; return }
        if (player.damageBoostFrames > 0) player.damageBoostFrames--

        val spec = WeaponSystem.spec(player.weapon.id)
        var hasTarget = false
        if (input.aimActive) {
            val diff = ((input.aimAngleDeg - player.turretAngle + 540f) % 360f) - 180f
            player.turretAngle += diff * 0.45f
        } else if (autoAim) {
            var nearest: EnemyCar? = null
            var minDist = spec.range
            for (e in enemies) {
                if (e.isDead || e.isWreckage) continue
                val d = hypot(e.x - player.x, e.y - player.y)
                if (d < minDist) { minDist = d; nearest = e }
            }
            if (nearest != null) {
                // lead the target slightly
                val lead = minDist / spec.projectileSpeed
                val tx = nearest.x + nearest.vx * lead * 0.6f
                val ty = nearest.y + nearest.vy * lead * 0.6f
                val target = Math.toDegrees(atan2((ty - player.y).toDouble(), (tx - player.x).toDouble())).toFloat()
                val diff = ((target - player.turretAngle + 540f) % 360f) - 180f
                player.turretAngle += diff * 0.22f
                hasTarget = abs(diff) < 6f
            } else {
                val diff = ((player.angle - player.turretAngle + 540f) % 360f) - 180f
                player.turretAngle += diff * 0.10f
            }
        } else {
            val diff = ((player.angle - player.turretAngle + 540f) % 360f) - 180f
            player.turretAngle += diff * 0.10f
        }

        if (input.fire || (autoAim && hasTarget && !input.aimActive)) {
            val mult = (1f + (player.turretLevel - 1) * 0.18f) * (if (player.damageBoostFrames > 0) 1.5f else 1f)
            val fired = weaponSystem.fire(
                player.weapon, "player", player.x, player.y, player.turretAngle, player.vx, player.vy, mult, bulletsList
            ) { recoil -> lastPlayerRecoil = recoil }
            if (fired) {
                val rad = Math.toRadians(player.turretAngle.toDouble()).toFloat()
                events.add(CombatEvent.Muzzle(player.x + cos(rad) * 30f, player.y + sin(rad) * 30f, rad, true, spec.projectileKind))
            }
        }
    }

    /** AI fire helper (aim already resolved by EnemyAISystem). */
    fun fireEnemyWeapon(e: EnemyCar, aimDeg: Float, damageMultiplier: Float, events: MutableList<CombatEvent>): Boolean {
        val fired = weaponSystem.fire(e.weapon, "enemy:${e.id}", e.x, e.y, aimDeg, e.vx, e.vy, damageMultiplier, bulletsList) { }
        if (fired) {
            val rad = Math.toRadians(aimDeg.toDouble()).toFloat()
            events.add(CombatEvent.Muzzle(e.x + cos(rad) * 28f, e.y + sin(rad) * 28f, rad, false, WeaponSystem.spec(e.weapon.id).projectileKind))
        }
        return fired
    }

    fun updateBullets(
        player: PlayerCar,
        enemies: List<EnemyCar>,
        obstacles: List<Obstacle>,
        frame: Long,
        events: MutableList<CombatEvent>
    ) {
        val iter = bulletsList.iterator()
        while (iter.hasNext()) {
            val b = iter.next()
            b.x += b.vx
            b.y += b.vy
            b.life--

            var consumed = false
            // Barrier / range
            if (b.life <= 0 || hypot(b.x, b.y) > ArenaConstants.PLAYABLE_RADIUS + 10f) {
                events.add(CombatEvent.Impact(b.x, b.y, b.vx, b.vy, heavy = b.kind == "rocket" || b.kind == "shell"))
                consumed = true
            }
            // Obstacles
            if (!consumed) {
                for (o in obstacles) {
                    if (o.isDestroyed) continue
                    if (hypot(b.x - o.x, b.y - o.y) < o.radius) {
                        if (o.health < 9000f) {
                            o.health -= b.damage
                            if (o.health <= 0f) o.isDestroyed = true
                        }
                        events.add(CombatEvent.Impact(b.x, b.y, b.vx, b.vy, heavy = b.splashRadius > 0f))
                        consumed = true
                        break
                    }
                }
            }
            // Enemies (any owner except the shooter itself)
            if (!consumed) {
                for (e in enemies) {
                    if (e.isDead || e.isWreckage) continue
                    if (b.ownerId == "enemy:${e.id}") continue
                    if (hypot(b.x - e.x, b.y - e.y) < ArenaConstants.CAR_RADIUS + 2f) {
                        damageSystem.damageEnemy(e, b.damage, b.ownerId, events)
                        damageSystem.applyStun(e, b.stunFrames)
                        events.add(CombatEvent.Impact(b.x, b.y, b.vx, b.vy, heavy = b.splashRadius > 0f))
                        consumed = true
                        break
                    }
                }
            }
            // Player
            if (!consumed && b.owner == "enemy" && !player.isDead) {
                if (hypot(b.x - player.x, b.y - player.y) < ArenaConstants.CAR_RADIUS + 2f) {
                    damageSystem.damagePlayer(player, b.damage, b.x - b.vx * 4f, b.y - b.vy * 4f, frame, events)
                    damageSystem.applyStunPlayer(player, b.stunFrames / 2)
                    events.add(CombatEvent.Impact(b.x, b.y, b.vx, b.vy, heavy = b.splashRadius > 0f))
                    consumed = true
                }
            }
            if (consumed) {
                if (b.splashRadius > 0f) applySplash(b, player, enemies, frame, events)
                iter.remove()
            }
        }
    }

    private fun applySplash(b: Bullet, player: PlayerCar, enemies: List<EnemyCar>, frame: Long, events: MutableList<CombatEvent>) {
        events.add(CombatEvent.Explosion(b.x, b.y, b.splashRadius))
        for (e in enemies) {
            if (e.isDead || e.isWreckage) continue
            val d = hypot(e.x - b.x, e.y - b.y)
            if (d < b.splashRadius) {
                val falloff = 1f - d / b.splashRadius
                damageSystem.damageEnemy(e, b.damage * 0.6f * falloff, b.ownerId, events)
                damageSystem.applyStun(e, (b.stunFrames * falloff).toInt())
                e.vx += (e.x - b.x) / max(d, 1f) * 6f * falloff
                e.vy += (e.y - b.y) / max(d, 1f) * 6f * falloff
            }
        }
        if (!player.isDead && b.owner == "enemy") {
            val d = hypot(player.x - b.x, player.y - b.y)
            if (d < b.splashRadius) {
                val falloff = 1f - d / b.splashRadius
                damageSystem.damagePlayer(player, b.damage * 0.5f * falloff, b.x, b.y, frame, events)
                player.vx += (player.x - b.x) / max(d, 1f) * 5f * falloff
                player.vy += (player.y - b.y) / max(d, 1f) * 5f * falloff
            }
        }
    }
}
