package com.example.game.systems

import androidx.compose.ui.graphics.Color
import com.example.game.*
import kotlin.math.*
import kotlin.random.Random

/**
 * EnemyAISystem: utility-scored state machine per vehicle.
 * States: SEARCH, CHASE, ATTACK (orbit/strafe + fire), DODGE, RETREAT, LOOT, ZONE.
 * Enemies pick targets among the player AND other enemies, avoid obstacles, obey the safe zone,
 * and drive with the same steering / throttle model as the player (no teleport-steering).
 */
class EnemyAISystem(
    private val combatSystem: CombatSystem,
    private val lootSystem: LootSystem,
    private val battleRoyale: BattleRoyaleSystem
) {

    fun spawnLevelEnemies(config: CampaignSystem.LevelConfig): List<EnemyCar> {
        val list = mutableListOf<EnemyCar>()
        val count = config.enemyCount.coerceIn(2, 7)
        val names = listOf("CRUSHER", "VIPER", "TITAN", "GHOST", "CHAOS", "DREAD", "REAPER")
        val colors = listOf(Color(0xFFD0C040), Color(0xFF2E9E5A), Color(0xFF55606F), Color(0xFFD0C040), Color(0xFF2E9E5A), Color(0xFF55606F), Color(0xFFD0C040))
        val arcStart = Math.toRadians(-160.0)
        val arcEnd = Math.toRadians(-20.0)
        val step = if (count > 1) (arcEnd - arcStart) / (count - 1) else 0.0
        for (i in 0 until count) {
            val a = arcStart + i * step
            val r = 500f + (i % 2) * 60f
            val sx = (cos(a) * r).toFloat()
            val sy = (sin(a) * r).toFloat()
            val isBoss = config.isBossLevel && i == 0
            val typeName = if (isBoss) (config.bossName ?: "WAR RIG BOSS") else names[i % names.size]
            val baseHp = when {
                isBoss -> 280f * config.enemyHpMultiplier
                typeName == "TITAN" -> 140f * config.enemyHpMultiplier
                typeName == "CRUSHER" -> 110f * config.enemyHpMultiplier
                typeName == "GHOST" -> 75f * config.enemyHpMultiplier
                else -> 90f * config.enemyHpMultiplier
            }
            val weapon = when {
                isBoss -> WeaponId.HEAVY_CANNON
                typeName == "TITAN" -> WeaponId.SHOTGUN
                typeName == "REAPER" -> WeaponId.ROCKET
                typeName == "GHOST" -> WeaponId.EMP
                else -> WeaponId.MACHINE_GUN
            }
            list.add(
                EnemyCar(
                    id = i + 1, name = typeName, x = sx, y = sy,
                    angle = Math.toDegrees(atan2(-sy.toDouble(), -sx.toDouble())).toFloat(),
                    health = baseHp, maxHealth = baseHp,
                    color = if (isBoss) Color(0xFF8A1010) else colors[i % colors.size],
                    type = typeName, state = "SEARCH",
                    armorPoints = if (isBoss) 80f else if (typeName == "TITAN") 40f else 0f,
                    weapon = WeaponState(id = weapon, ammo = 999, reserve = 9999),
                    modelVariant = if (isBoss) 3 else i % 3,
                    isBoss = isBoss
                )
            )
        }
        return list
    }

    fun updateEnemies(
        enemies: List<EnemyCar>,
        player: PlayerCar,
        obstacles: List<Obstacle>,
        dt: Float,
        speedMultiplier: Float,
        damageMultiplier: Float,
        events: MutableList<CombatEvent>
    ) {
        for (e in enemies) {
            if (e.isDead || e.isWreckage) {
                e.vx *= 0.9f; e.vy *= 0.9f
                e.x += e.vx; e.y += e.vy
                e.speed *= 0.9f
                continue
            }
            WeaponSystem.SPECS // ensure loaded
            e.weapon.cooldown = (e.weapon.cooldown - 1).coerceAtLeast(0)
            e.weapon.heat = (e.weapon.heat - 0.012f).coerceAtLeast(0f)
            if (e.weapon.overheated && e.weapon.heat < 0.35f) e.weapon.overheated = false
            e.recentDamage *= 0.96f
            if (e.stunFrames > 0) {
                e.stunFrames--
                e.speed *= 0.93f
                integrate(e, 0f, 0f, speedMultiplier)
                continue
            }
            e.stateTimer--
            if (e.stateTimer <= 0) chooseState(e, enemies, player)

            // ---- resolve navigation target
            var tx = e.targetX
            var ty = e.targetY
            var desiredSpeed = 1f
            var strafe = 0f
            val targetVehicle = resolveTargetVehicle(e, enemies, player)
            when (e.state) {
                "CHASE", "ATTACK" -> {
                    if (targetVehicle == null) { e.stateTimer = 0 } else {
                        tx = targetVehicle.first; ty = targetVehicle.second
                        val d = hypot(tx - e.x, ty - e.y)
                        val range = WeaponSystem.spec(e.weapon.id).range
                        if (e.state == "ATTACK") {
                            // orbit / strafe around the target at ~55% weapon range
                            strafe = e.strafeDir
                            desiredSpeed = if (d < range * 0.35f) 0.55f else 0.85f
                        } else if (d < range * 0.7f) {
                            e.state = "ATTACK"; e.stateTimer = 90 + Random.nextInt(90)
                        }
                    }
                }
                "DODGE" -> { strafe = e.strafeDir * 1.5f; desiredSpeed = 1f; if (targetVehicle != null) { tx = targetVehicle.first; ty = targetVehicle.second } }
                "RETREAT" -> {
                    if (targetVehicle != null) { tx = e.x - (targetVehicle.first - e.x); ty = e.y - (targetVehicle.second - e.y) }
                    desiredSpeed = 1f
                }
                "LOOT" -> {
                    val loot = lootSystem.items.firstOrNull { it.id == e.targetId && it.active }
                    if (loot == null) e.stateTimer = 0 else { tx = loot.x; ty = loot.y }
                }
                "ZONE" -> { tx = battleRoyale.zone.centerX; ty = battleRoyale.zone.centerY; desiredSpeed = 1f }
                else -> { if (hypot(tx - e.x, ty - e.y) < 60f) e.stateTimer = 0 }
            }

            // ---- steering toward target with strafing and obstacle avoidance
            var desired = Math.toDegrees(atan2((ty - e.y).toDouble(), (tx - e.x).toDouble())).toFloat()
            if (strafe != 0f) desired += 62f * strafe
            desired += avoidance(e, obstacles, enemies)
            val angleDiff = ((desired - e.angle + 540f) % 360f) - 180f
            val steer = (angleDiff / 40f).coerceIn(-1f, 1f)
            // slow down for sharp turns, reverse when stuck against a wall
            val throttle = if (abs(angleDiff) > 120f && e.speed < 1.5f) -0.6f else desiredSpeed * (1f - abs(steer) * 0.35f)
            e.throttle = throttle
            integrate(e, steer, throttle, speedMultiplier)

            // ---- weapon: fire when the target is in range and roughly in front of the turret
            if (targetVehicle != null && (e.state == "ATTACK" || e.state == "CHASE" || e.state == "DODGE")) {
                val d = hypot(targetVehicle.first - e.x, targetVehicle.second - e.y)
                val spec = WeaponSystem.spec(e.weapon.id)
                val aim = Math.toDegrees(atan2((targetVehicle.second - e.y).toDouble(), (targetVehicle.first - e.x).toDouble())).toFloat()
                val diff = ((aim - e.turretAngle + 540f) % 360f) - 180f
                e.turretAngle += diff.coerceIn(-5f, 5f)
                if (d < spec.range * 0.95f && abs(diff) < 8f && e.weapon.cooldown == 0) {
                    combatSystem.fireEnemyWeapon(e, e.turretAngle + (Random.nextFloat() - 0.5f) * 5f, damageMultiplier * (if (e.isBoss) 1.3f else 1f), events)
                    // AI fire cadence is deliberately slower than the spec so 7 enemies can't melt the player
                    e.weapon.cooldown = (spec.fireRateFrames * (if (e.isBoss) 2.2f else 3.4f)).toInt() + Random.nextInt(20)
                }
            } else {
                val diff = ((e.angle - e.turretAngle + 540f) % 360f) - 180f
                e.turretAngle += diff * 0.1f
            }
            e.stateTimer = max(e.stateTimer, 1)
        }
    }

    /** Utility scoring: picks the next state for the enemy. */
    private fun chooseState(e: EnemyCar, enemies: List<EnemyCar>, player: PlayerCar) {
        val hpFrac = e.health / e.maxHealth
        val inZone = battleRoyale.isInside(e.x, e.y, 40f)
        if (!inZone) { e.state = "ZONE"; e.stateTimer = 120; return }
        if (e.recentDamage > e.maxHealth * 0.12f && Random.nextFloat() < 0.6f) {
            e.state = "DODGE"; e.strafeDir = if (Random.nextBoolean()) 1f else -1f; e.stateTimer = 45 + Random.nextInt(40); return
        }
        if (hpFrac < 0.32f) {
            val heal = lootSystem.nearestActive(e.x, e.y) { it.type == LootType.HEALTH || it.type == LootType.REPAIR || it.type == LootType.ARMOR }
            if (heal != null && hypot(heal.x - e.x, heal.y - e.y) < 420f) {
                e.state = "LOOT"; e.targetId = heal.id; e.stateTimer = 240; return
            }
            if (Random.nextFloat() < 0.5f) { e.state = "RETREAT"; e.stateTimer = 90 + Random.nextInt(60); return }
        }
        // target selection: score = proximity + weakness + aggression bias toward the player
        var bestScore = -1f
        var bestKind = "player"
        var bestId = -1
        if (!player.isDead) {
            val d = hypot(player.x - e.x, player.y - e.y)
            bestScore = 1000f / (d + 60f) + 0.6f + (1f - player.health / player.maxHealth) * 0.5f
        }
        for (o in enemies) {
            if (o === e || o.isDead || o.isWreckage) continue
            val d = hypot(o.x - e.x, o.y - e.y)
            val score = 1000f / (d + 60f) + (1f - o.health / o.maxHealth) * 0.8f + (if (o.isBoss) -0.4f else 0f) + Random.nextFloat() * 0.35f
            if (score > bestScore) { bestScore = score; bestKind = "enemy"; bestId = o.id }
        }
        if (bestScore < 0f) { e.state = "SEARCH"; randomPoint(e); e.stateTimer = 150; return }
        e.targetKind = bestKind
        e.targetId = bestId
        val weaponLoot = lootSystem.nearestActive(e.x, e.y) { it.type == LootType.WEAPON }
        if (e.weapon.id == WeaponId.MACHINE_GUN && weaponLoot != null && hypot(weaponLoot.x - e.x, weaponLoot.y - e.y) < 220f && Random.nextFloat() < 0.5f) {
            e.state = "LOOT"; e.targetId = weaponLoot.id; e.targetKind = "loot"; e.stateTimer = 200; return
        }
        e.state = "CHASE"
        e.strafeDir = if (Random.nextBoolean()) 1f else -1f
        e.stateTimer = 120 + Random.nextInt(120)
    }

    private fun randomPoint(e: EnemyCar) {
        val a = Random.nextFloat() * 6.2832f
        val r = Random.nextFloat() * (battleRoyale.zone.radius * 0.8f)
        e.targetX = battleRoyale.zone.centerX + cos(a) * r
        e.targetY = battleRoyale.zone.centerY + sin(a) * r
    }

    private fun resolveTargetVehicle(e: EnemyCar, enemies: List<EnemyCar>, player: PlayerCar): Pair<Float, Float>? {
        if (e.targetKind == "player") return if (player.isDead) null else Pair(player.x, player.y)
        if (e.targetKind == "enemy") {
            val o = enemies.firstOrNull { it.id == e.targetId && !it.isDead && !it.isWreckage } ?: return null
            return Pair(o.x, o.y)
        }
        return null
    }

    /** Steering offset (degrees) that pushes the heading away from nearby obstacles / vehicles / barrier. */
    private fun avoidance(e: EnemyCar, obstacles: List<Obstacle>, enemies: List<EnemyCar>): Float {
        val rad = Math.toRadians(e.angle.toDouble()).toFloat()
        val lookX = e.x + cos(rad) * 110f
        val lookY = e.y + sin(rad) * 110f
        var offset = 0f
        for (o in obstacles) {
            if (o.isDestroyed) continue
            val d = hypot(o.x - lookX, o.y - lookY)
            if (d < o.radius + 60f) {
                val side = (cos(rad) * (o.y - e.y) - sin(rad) * (o.x - e.x))
                offset += (if (side > 0) -1f else 1f) * (80f * (1f - d / (o.radius + 60f)))
            }
        }
        for (o in enemies) {
            if (o === e || o.isDead) continue
            val d = hypot(o.x - lookX, o.y - lookY)
            if (d < 70f) {
                val side = (cos(rad) * (o.y - e.y) - sin(rad) * (o.x - e.x))
                offset += (if (side > 0) -1f else 1f) * 35f
            }
        }
        val wall = hypot(lookX, lookY)
        if (wall > ArenaConstants.PLAYABLE_RADIUS - 80f) {
            val toCenter = Math.toDegrees(atan2(-e.y.toDouble(), -e.x.toDouble())).toFloat()
            val diff = ((toCenter - e.angle + 540f) % 360f) - 180f
            offset += diff.coerceIn(-70f, 70f)
        }
        return offset.coerceIn(-90f, 90f)
    }

    /** Car-like integration shared by every AI vehicle (accel / steer / lateral grip / barrier bounce). */
    private fun integrate(e: EnemyCar, steer: Float, throttle: Float, speedMultiplier: Float) {
        val maxSpeed = ((if (e.isBoss) 5.2f else if (e.type == "VIPER" || e.type == "GHOST") 7.2f else 6.2f) * speedMultiplier).coerceIn(4f, 9.5f)
        val accel = if (e.isBoss) 0.14f else 0.2f
        if (abs(throttle) > 0.02f) e.speed = (e.speed + throttle * accel).coerceIn(-maxSpeed * 0.5f, maxSpeed) else e.speed *= 0.975f
        val speedFactor = (abs(e.speed) / 3.5f).coerceIn(0.3f, 1f)
        e.angle += steer * 4.6f * speedFactor * (if (e.speed < 0) -1f else 1f)
        e.angle = ((e.angle + 540f) % 360f) - 180f
        e.steerAngle = steer * 32f
        val rad = Math.toRadians(e.angle.toDouble()).toFloat()
        val hx = cos(rad); val hy = sin(rad)
        val lx = -sin(rad); val ly = cos(rad)
        val fwd = e.vx * hx + e.vy * hy
        val lat = (e.vx * lx + e.vy * ly) * 0.8f
        val fwdCombined = fwd * 0.82f + e.speed * 0.18f
        e.vx = hx * fwdCombined + lx * lat
        e.vy = hy * fwdCombined + ly * lat
        e.x += e.vx
        e.y += e.vy
        val dist = hypot(e.x, e.y)
        val maxR = ArenaConstants.PLAYABLE_RADIUS - ArenaConstants.CAR_RADIUS
        if (dist > maxR && dist > 0.01f) {
            val nx = e.x / dist; val ny = e.y / dist
            e.x = nx * maxR; e.y = ny * maxR
            val dot = e.vx * nx + e.vy * ny
            if (dot > 0) { e.vx -= dot * nx * 1.4f; e.vy -= dot * ny * 1.4f }
            e.speed *= 0.5f
        }
    }
}
