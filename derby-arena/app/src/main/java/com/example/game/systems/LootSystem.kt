package com.example.game.systems

import com.example.game.*
import kotlin.math.*
import kotlin.random.Random

/**
 * LootSystem: world-space loot spawning / respawning and pickup resolution for player & AI.
 */
class LootSystem(private val weaponSystem: WeaponSystem) {

    val items = mutableListOf<LootItem>()
    private var nextId = 1

    fun clear() {
        items.clear()
        nextId = 1
    }

    fun spawnInitial(count: Int, obstacles: List<Obstacle>, level: Int) {
        clear()
        val weaponPool = listOf(WeaponId.SHOTGUN, WeaponId.ROCKET, WeaponId.HEAVY_CANNON, WeaponId.EMP)
        repeat(count) { i ->
            val type = when {
                i % 7 == 0 -> LootType.WEAPON
                i % 5 == 0 -> LootType.ARMOR
                i % 4 == 0 -> LootType.NITRO
                i % 3 == 0 -> LootType.HEALTH
                i % 11 == 0 -> LootType.REPAIR
                i % 13 == 0 -> LootType.UPGRADE
                else -> LootType.AMMO
            }
            val pos = findFreeSpot(obstacles)
            items.add(
                LootItem(
                    id = nextId++,
                    x = pos.x,
                    y = pos.y,
                    type = type,
                    weapon = if (type == LootType.WEAPON) weaponPool[(i / 7 + level) % weaponPool.size] else null,
                    bobPhase = Random.nextFloat() * 6.28f
                )
            )
        }
    }

    private fun findFreeSpot(obstacles: List<Obstacle>): Vec2 {
        repeat(40) {
            val a = Random.nextFloat() * 2f * PI.toFloat()
            val r = 80f + Random.nextFloat() * (ArenaConstants.PLAYABLE_RADIUS - 140f)
            val x = cos(a) * r
            val y = sin(a) * r
            val blocked = obstacles.any { hypot(it.x - x, it.y - y) < it.radius + 45f }
            if (!blocked && hypot(x, y - 180f) > 90f) return Vec2(x, y)
        }
        return Vec2(0f, -200f)
    }

    fun update(obstacles: List<Obstacle>) {
        for (it in items) {
            it.bobPhase += 0.04f
            if (!it.active) {
                it.respawnTimer--
                if (it.respawnTimer <= 0) {
                    val p = findFreeSpot(obstacles)
                    it.x = p.x
                    it.y = p.y
                    it.active = true
                }
            }
        }
    }

    /** Applies pickups for the player. Emits CombatEvent.Pickup for each. */
    fun collectForPlayer(p: PlayerCar, events: MutableList<CombatEvent>) {
        if (p.isDead) return
        val reach = ArenaConstants.CAR_RADIUS + 26f + (p.magnetLevel - 1) * 14f
        for (item in items) {
            if (!item.active) continue
            if (hypot(item.x - p.x, item.y - p.y) > reach) continue
            when (item.type) {
                LootType.AMMO -> weaponSystem.addAmmo(p.weapon, 2)
                LootType.HEALTH -> p.health = (p.health + p.maxHealth * 0.35f).coerceAtMost(p.maxHealth)
                LootType.ARMOR -> p.armorPoints = (p.armorPoints + 50f).coerceAtMost(p.maxArmorPoints)
                LootType.NITRO -> p.nitro = p.maxNitro
                LootType.REPAIR -> { p.health = p.maxHealth; p.armorPoints = (p.armorPoints + 20f).coerceAtMost(p.maxArmorPoints) }
                LootType.UPGRADE -> p.damageBoostFrames = 60 * 20
                LootType.WEAPON -> {
                    val w = item.weapon ?: WeaponId.SHOTGUN
                    if (p.weapon.id == w) weaponSystem.addAmmo(p.weapon, 2) else p.weapon = WeaponSystem.freshState(w)
                }
            }
            item.active = false
            item.respawnTimer = 60 * (25 + Random.nextInt(20))
            events.add(CombatEvent.Pickup(item.x, item.y, item.type, byPlayer = true))
        }
    }

    /** AI pickups: heals / re-arms enemies that drive over loot. */
    fun collectForEnemy(e: EnemyCar, events: MutableList<CombatEvent>) {
        if (e.isDead || e.isWreckage) return
        for (item in items) {
            if (!item.active) continue
            if (hypot(item.x - e.x, item.y - e.y) > ArenaConstants.CAR_RADIUS + 22f) continue
            when (item.type) {
                LootType.HEALTH, LootType.REPAIR -> e.health = (e.health + e.maxHealth * 0.35f).coerceAtMost(e.maxHealth)
                LootType.ARMOR -> e.armorPoints = (e.armorPoints + 40f).coerceAtMost(80f)
                LootType.WEAPON -> e.weapon = WeaponState(id = item.weapon ?: WeaponId.SHOTGUN, ammo = 999, reserve = 9999)
                else -> Unit
            }
            item.active = false
            item.respawnTimer = 60 * (30 + Random.nextInt(20))
            events.add(CombatEvent.Pickup(item.x, item.y, item.type, byPlayer = false))
        }
    }

    fun nearestActive(x: Float, y: Float, filter: (LootItem) -> Boolean = { true }): LootItem? {
        var best: LootItem? = null
        var bestD = Float.MAX_VALUE
        for (it in items) {
            if (!it.active || !filter(it)) continue
            val d = hypot(it.x - x, it.y - y)
            if (d < bestD) { bestD = d; best = it }
        }
        return best
    }
}
