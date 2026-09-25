package com.example.game.systems

import androidx.compose.ui.graphics.Color
import com.example.game.*
import kotlin.math.*
import kotlin.random.Random

/**
 * System 2: CollisionSystem
 * High-precision vehicle collision physics with elastic/inelastic impulse response,
 * barrier rebound, wreckage displacement, obstacle crushing, and damage telemetry.
 */
class CollisionSystem {

    interface CollisionListener {
        fun onDamageApplied(source: String, amount: Float, attacker: String, collisionType: String, hpAfter: Float)
        fun onScreenShake(intensity: Float)
        fun onDustBurst(x: Float, y: Float, nx: Float, ny: Float, intensity: Int)
        fun onMetalSparks(x: Float, y: Float, vx: Float, vy: Float, color: Color)
        fun onFloatingText(x: Float, y: Float, text: String, color: Color)
        fun onCheer(x: Float, y: Float, intensity: Int)
        fun onFuelBarrelExplosion(x: Float, y: Float, radius: Float, damage: Float)
    }

    private var collisionCooldown = 0
    private var barrierCooldown = 0

    fun updateCooldowns() {
        if (collisionCooldown > 0) collisionCooldown--
        if (barrierCooldown > 0) barrierCooldown--
    }

    fun checkCollisions(
        player: PlayerCar,
        enemies: List<EnemyCar>,
        obstacles: List<Obstacle>,
        spawnProtection: Boolean,
        listener: CollisionListener
    ) {
        // 1. Player <-> Enemy Vehicles
        for (e in enemies) {
            if (e.isDead || e.isWreckage) continue
            val dx = e.x - player.x
            val dy = e.y - player.y
            val dist = hypot(dx, dy)
            val combinedRadius = ArenaConstants.CAR_RADIUS * 2f

            if (dist < combinedRadius && dist > 0.01f) {
                val overlap = combinedRadius - dist
                val nx = dx / dist
                val ny = dy / dist

                // Push apart
                player.x -= nx * overlap * 0.5f
                player.y -= ny * overlap * 0.5f
                e.x += nx * overlap * 0.5f
                e.y += ny * overlap * 0.5f

                // Relative velocity along normal
                val rvx = player.vx - e.vx
                val rvy = player.vy - e.vy
                val velAlongNormal = rvx * nx + rvy * ny

                if (velAlongNormal > 0f) {
                    val restitution = 0.55f
                    val impulse = -(1 + restitution) * velAlongNormal * 0.5f

                    player.vx += impulse * nx
                    player.vy += impulse * ny
                    e.vx -= impulse * nx
                    e.vy -= impulse * ny

                    val crashSpeed = abs(velAlongNormal)
                    if (crashSpeed > 1.2f) {
                        // Impact effects
                        val cx = (player.x + e.x) * 0.5f
                        val cy = (player.y + e.y) * 0.5f
                        listener.onScreenShake(crashSpeed * 3.5f)
                        listener.onMetalSparks(cx, cy, -nx * 4f, -ny * 4f, Color(0xFFFFD54F))
                        listener.onDustBurst(cx, cy, nx, ny, (crashSpeed * 3).toInt())

                        if (collisionCooldown <= 0) {
                            collisionCooldown = 12

                            // Angle checks for T-bone vs Head-on
                            val pRad = Math.toRadians(player.angle.toDouble()).toFloat()
                            val pHx = cos(pRad)
                            val pHy = sin(pRad)
                            val pHeadingDot = pHx * nx + pHy * ny

                            val isPlayerRamming = pHeadingDot > 0.45f && player.speed > 1.0f
                            val isNitroActive = player.isNitroActive

                            if (isPlayerRamming) {
                                // Player rams enemy
                                val boostBonus = if (isNitroActive) 2.2f else 1.0f
                                val dmgToEnemy = (crashSpeed * 10f * boostBonus + (player.armorLevel - 1) * 6f).coerceAtLeast(15f)
                                e.health -= dmgToEnemy
                                player.score += 50
                                listener.onFloatingText(e.x, e.y, "CRUSH! -${dmgToEnemy.toInt()} HP", Color(0xFFFFEA00))
                                listener.onCheer(e.x, e.y, 4)

                                val recoil = if (isNitroActive) 0.5f else 2.5f
                                if (!spawnProtection) {
                                    player.health = (player.health - recoil).coerceAtLeast(0f)
                                    listener.onDamageApplied("RAM_RECOIL", recoil, e.name, "CAR_VS_CAR", player.health)
                                }
                            } else {
                                // Enemy hits player or mutual side swipe
                                val baseDmg = (crashSpeed * 7.5f - player.armor * 0.15f).coerceIn(4f, 28f)
                                if (!spawnProtection) {
                                    player.health = (player.health - baseDmg).coerceAtLeast(0f)
                                    listener.onDamageApplied("CRASH", baseDmg, e.name, "CAR_VS_CAR", player.health)
                                    listener.onFloatingText(player.x, player.y, "-${baseDmg.toInt()} HP", Color(0xFFFF1744))
                                }
                                e.health -= baseDmg * 0.8f
                            }
                        }
                    }
                }
            }
        }

        // 2. Enemy <-> Enemy Collisions
        for (i in enemies.indices) {
            val e1 = enemies[i]
            if (e1.isDead) continue
            for (j in i + 1 until enemies.size) {
                val e2 = enemies[j]
                if (e2.isDead) continue
                val dx = e2.x - e1.x
                val dy = e2.y - e1.y
                val dist = hypot(dx, dy)
                val comb = ArenaConstants.CAR_RADIUS * 2f

                if (dist < comb && dist > 0.01f) {
                    val overlap = comb - dist
                    val nx = dx / dist
                    val ny = dy / dist
                    e1.x -= nx * overlap * 0.5f
                    e1.y -= ny * overlap * 0.5f
                    e2.x += nx * overlap * 0.5f
                    e2.y += ny * overlap * 0.5f

                    val rvx = e1.vx - e2.vx
                    val rvy = e1.vy - e2.vy
                    val vn = rvx * nx + rvy * ny
                    if (vn > 0f) {
                        val imp = -1.4f * vn * 0.5f
                        e1.vx += imp * nx
                        e1.vy += imp * ny
                        e2.vx -= imp * nx
                        e2.vy -= imp * ny
                        if (abs(vn) > 1.8f) {
                            e1.health -= abs(vn) * 4f
                            e2.health -= abs(vn) * 4f
                            listener.onMetalSparks((e1.x + e2.x) * 0.5f, (e1.y + e2.y) * 0.5f, -nx * 3f, -ny * 3f, Color(0xFFFF9800))
                        }
                    }
                }
            }
        }

        // 3. Vehicle <-> Wreckage
        for (w in enemies) {
            if (!w.isWreckage) continue
            val dx = player.x - w.x
            val dy = player.y - w.y
            val dist = hypot(dx, dy)
            val comb = ArenaConstants.CAR_RADIUS * 1.8f
            if (dist < comb && dist > 0.01f) {
                val overlap = comb - dist
                val nx = dx / dist
                val ny = dy / dist
                player.x += nx * overlap * 0.4f
                player.y += ny * overlap * 0.4f
                w.x -= nx * overlap * 0.6f
                w.y -= ny * overlap * 0.6f
                w.vx = -nx * player.speed * 0.5f
                w.vy = -ny * player.speed * 0.5f
                player.speed *= 0.85f
            }
        }

        // 4. Vehicle <-> Obstacles (Barrels, Concrete Barriers)
        for (o in obstacles) {
            if (o.isDestroyed) continue
            val dx = player.x - o.x
            val dy = player.y - o.y
            val dist = hypot(dx, dy)
            val comb = ArenaConstants.CAR_RADIUS + o.radius
            if (dist < comb && dist > 0.01f) {
                val overlap = comb - dist
                val nx = dx / dist
                val ny = dy / dist
                player.x += nx * overlap
                player.y += ny * overlap

                val speedMag = abs(player.speed)
                if (speedMag > 1.5f) {
                    listener.onScreenShake(speedMag * 2f)
                    listener.onDustBurst(o.x, o.y, nx, ny, 6)

                    if (o.isExplosive) {
                        o.isDestroyed = true
                        listener.onFuelBarrelExplosion(o.x, o.y, 160f, 40f)
                    } else {
                        o.health -= speedMag * 15f
                        if (o.health <= 0f) {
                            o.isDestroyed = true
                            listener.onMetalSparks(o.x, o.y, nx * 3f, ny * 3f, Color(0xFF90A4AE))
                            listener.onFloatingText(o.x, o.y, "SMASH!", Color(0xFF00FFCC))
                        }
                    }
                    player.speed *= 0.3f
                }
            }
        }
    }
}
