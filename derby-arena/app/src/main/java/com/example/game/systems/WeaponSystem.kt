package com.example.game.systems

import com.example.game.*
import kotlin.math.*
import kotlin.random.Random

/**
 * WeaponSystem: data-driven weapon archetypes (damage / fire rate / range / spread / recoil /
 * ammo / reload / heat) shared by the player and AI. Produces Bullet projectiles and combat events.
 */
class WeaponSystem {

    companion object {
        val SPECS: Map<WeaponId, WeaponSpec> = listOf(
            WeaponSpec(WeaponId.MACHINE_GUN, "MACHINE GUN", damage = 9f, fireRateFrames = 6, range = 520f, spreadDeg = 2.2f,
                recoil = 0.35f, magazine = 80, reloadFrames = 110, heatPerShot = 0.035f, projectileSpeed = 17f),
            WeaponSpec(WeaponId.SHOTGUN, "SHOTGUN", damage = 14f, fireRateFrames = 42, range = 240f, spreadDeg = 11f,
                recoil = 1.4f, magazine = 8, reloadFrames = 150, heatPerShot = 0.12f, projectileSpeed = 15f, pellets = 6, projectileKind = "pellet"),
            WeaponSpec(WeaponId.ROCKET, "ROCKET", damage = 70f, fireRateFrames = 75, range = 650f, spreadDeg = 0.8f,
                recoil = 2.2f, magazine = 4, reloadFrames = 180, heatPerShot = 0.2f, projectileSpeed = 11f, splashRadius = 110f, projectileKind = "rocket"),
            WeaponSpec(WeaponId.EMP, "EMP", damage = 18f, fireRateFrames = 90, range = 420f, spreadDeg = 1.5f,
                recoil = 0.8f, magazine = 3, reloadFrames = 200, heatPerShot = 0.25f, projectileSpeed = 13f, splashRadius = 90f, stunFrames = 110, projectileKind = "emp"),
            WeaponSpec(WeaponId.HEAVY_CANNON, "HEAVY CANNON", damage = 55f, fireRateFrames = 48, range = 700f, spreadDeg = 0.5f,
                recoil = 2.6f, magazine = 6, reloadFrames = 170, heatPerShot = 0.18f, projectileSpeed = 22f, projectileKind = "shell")
        ).associateBy { it.id }

        fun spec(id: WeaponId): WeaponSpec = SPECS.getValue(id)

        fun freshState(id: WeaponId): WeaponState {
            val s = spec(id)
            return WeaponState(id = id, ammo = s.magazine, reserve = s.magazine * 3)
        }
    }

    /** Ticks cooldown / reload / heat. Call once per frame per weapon state. */
    fun tick(w: WeaponState) {
        if (w.cooldown > 0) w.cooldown--
        if (w.reloadTimer > 0) {
            w.reloadTimer--
            if (w.reloadTimer == 0) {
                val s = spec(w.id)
                val take = min(s.magazine, if (w.reserve >= 9000) s.magazine else w.reserve)
                w.ammo = take
                if (w.reserve < 9000) w.reserve -= take
            }
        }
        w.heat = (w.heat - 0.012f).coerceAtLeast(0f)
        if (w.overheated && w.heat < 0.35f) w.overheated = false
    }

    fun startReload(w: WeaponState) {
        if (w.reloadTimer > 0) return
        val s = spec(w.id)
        if (w.ammo >= s.magazine) return
        if (w.reserve <= 0) return
        w.reloadTimer = s.reloadFrames
    }

    fun canFire(w: WeaponState): Boolean = w.cooldown == 0 && w.reloadTimer == 0 && w.ammo > 0 && !w.overheated

    /**
     * Fires the weapon from (x,y) toward aimDeg. Returns spawned projectiles (empty if it could not fire).
     * The recoil value is returned via [onRecoil] so the camera/turret systems can react.
     */
    fun fire(
        w: WeaponState,
        ownerId: String,
        x: Float,
        y: Float,
        aimDeg: Float,
        ownerVx: Float,
        ownerVy: Float,
        damageMultiplier: Float,
        out: MutableList<Bullet>,
        onRecoil: (Float) -> Unit
    ): Boolean {
        if (!canFire(w)) {
            if (w.ammo == 0 && w.reloadTimer == 0) startReload(w)
            return false
        }
        val s = spec(w.id)
        val owner = if (ownerId == "player") "player" else "enemy"
        val lifeFrames = (s.range / s.projectileSpeed).toInt().coerceIn(10, 120)
        repeat(s.pellets) {
            val spread = (Random.nextFloat() - 0.5f) * 2f * s.spreadDeg
            val rad = Math.toRadians((aimDeg + spread).toDouble()).toFloat()
            val speedJitter = if (s.pellets > 1) 0.85f + Random.nextFloat() * 0.3f else 1f
            out.add(
                Bullet(
                    x = x + cos(rad) * 30f,
                    y = y + sin(rad) * 30f,
                    vx = cos(rad) * s.projectileSpeed * speedJitter + ownerVx * 0.25f,
                    vy = sin(rad) * s.projectileSpeed * speedJitter + ownerVy * 0.25f,
                    owner = owner,
                    damage = s.damage * damageMultiplier,
                    ownerId = ownerId,
                    kind = s.projectileKind,
                    life = lifeFrames,
                    splashRadius = s.splashRadius,
                    stunFrames = s.stunFrames
                )
            )
        }
        w.ammo--
        w.cooldown = s.fireRateFrames
        w.heat += s.heatPerShot
        if (w.heat >= 1f) {
            w.heat = 1f
            w.overheated = true
        }
        if (w.ammo == 0) startReload(w)
        onRecoil(s.recoil)
        return true
    }

    /** Ammo pickup: refills reserve proportionally to the magazine size. */
    fun addAmmo(w: WeaponState, magazines: Int = 2) {
        val s = spec(w.id)
        w.reserve = (w.reserve + s.magazine * magazines).coerceAtMost(s.magazine * 8)
        if (w.ammo == 0 && w.reloadTimer == 0) startReload(w)
    }
}
