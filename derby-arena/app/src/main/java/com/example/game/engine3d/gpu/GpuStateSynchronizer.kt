package com.example.game.engine3d.gpu

import android.opengl.Matrix
import com.example.game.GameViewModel
import com.example.game.LootType
import com.google.android.filament.Engine
import com.google.android.filament.TransformManager
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import kotlin.math.*

/**
 * GpuSyncSystem: every frame copies gameplay state into Filament entity transforms.
 * PlayerState → player root/wheels/turret/hood, EnemyState → enemy pool, projectiles → tracer pool,
 * particles → VFX pools, loot → crate pools, zone → ring, crowd excitement → tribune groups.
 * No static transforms: everything below is driven by the simulation snapshot.
 */
class GpuStateSynchronizer(engine: Engine) {

    companion object {
        const val WORLD_SCALE = 0.04f // 750 game units -> 30 meters
        private const val HIDDEN_Y = -400f
    }

    private val tm: TransformManager = engine.transformManager
    private val m = FloatArray(16)
    private val t = FloatArray(16)

    class VehicleEntities(val root: Int, val wheelFl: Int, val wheelFr: Int, val wheelRl: Int, val wheelRr: Int, val turret: Int, val barrel: Int, val hood: Int) {
        var wheelRoll = 0f
        var hoodOpen = 0f
        var wreckSettle = 0f
    }

    fun cacheVehicleEntities(asset: FilamentAsset?): VehicleEntities? {
        if (asset == null || asset.root == 0) return null
        return VehicleEntities(
            asset.root,
            asset.getFirstEntityByName("Wheel_FL"), asset.getFirstEntityByName("Wheel_FR"),
            asset.getFirstEntityByName("Wheel_RL"), asset.getFirstEntityByName("Wheel_RR"),
            asset.getFirstEntityByName("Turret"), asset.getFirstEntityByName("Barrel"), asset.getFirstEntityByName("Hood")
        )
    }

    private fun instanceOf(entity: Int): Int {
        if (entity == 0) return 0
        var inst = tm.getInstance(entity)
        if (inst == 0) { tm.create(entity); inst = tm.getInstance(entity) }
        return inst
    }

    fun hide(entity: Int) {
        val inst = instanceOf(entity)
        if (inst == 0) return
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, 0f, HIDDEN_Y, 0f)
        tm.setTransform(inst, m)
    }

    fun hideInstance(inst: FilamentInstance?) { if (inst != null) hide(inst.root) }

    // ------------------------------------------------------------------ vehicles
    fun syncVehicle(ent: VehicleEntities?, pose: GameViewModel.GpuVehiclePose, scale: Float, dt: Float, recoil: Float) {
        if (ent == null) return
        val inst = instanceOf(ent.root)
        if (inst == 0) return
        val wx = pose.x * WORLD_SCALE
        val wz = pose.y * WORLD_SCALE
        val yaw = -(pose.angle + 90f)
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, wx, 0f, wz)
        Matrix.rotateM(m, 0, yaw, 0f, 1f, 0f)
        if (pose.isDead || pose.isWreckage) {
            ent.wreckSettle = min(1f, ent.wreckSettle + dt * 1.5f)
            Matrix.translateM(m, 0, 0f, -0.18f * ent.wreckSettle, 0f)
            Matrix.rotateM(m, 0, 16f * ent.wreckSettle, 0f, 0f, 1f)
            Matrix.rotateM(m, 0, 7f * ent.wreckSettle, 1f, 0f, 0f)
        } else {
            ent.wreckSettle = 0f
            val roll = (pose.steerAngle * 0.16f * (abs(pose.speed) / 6f).coerceIn(0.2f, 1f)).coerceIn(-6f, 6f)
            val pitch = (pose.speed * 0.35f).coerceIn(-3.5f, 3.5f) + (if (pose.handbrake) 1.5f else 0f)
            Matrix.rotateM(m, 0, roll, 0f, 0f, 1f)
            Matrix.rotateM(m, 0, -pitch, 1f, 0f, 0f)
            if (pose.stunned) Matrix.rotateM(m, 0, sin(ent.wheelRoll * 0.2f) * 2f, 0f, 0f, 1f)
        }
        Matrix.scaleM(m, 0, scale, scale, scale)
        tm.setTransform(inst, m)

        // wheels: roll with speed, front wheels steer
        ent.wheelRoll = (ent.wheelRoll + pose.speed * 30f * dt) % 360f
        val s = 1f // local (child) space already scaled by root
        wheel(ent.wheelFl, -0.98f * s, 0.43f * s, -1.45f * s, pose.steerAngle, ent.wheelRoll)
        wheel(ent.wheelFr, 0.98f * s, 0.43f * s, -1.45f * s, pose.steerAngle, ent.wheelRoll)
        wheel(ent.wheelRl, -0.98f * s, 0.43f * s, 1.45f * s, 0f, ent.wheelRoll)
        wheel(ent.wheelRr, 0.98f * s, 0.43f * s, 1.45f * s, 0f, ent.wheelRoll)

        // turret rotates independently of the hull; barrel kicks back with recoil
        val tInst = instanceOf(ent.turret)
        if (tInst != 0) {
            Matrix.setIdentityM(t, 0)
            Matrix.translateM(t, 0, 0f, 1.33f, 0.05f)
            Matrix.rotateM(t, 0, -(pose.turretAngle - pose.angle), 0f, 1f, 0f)
            tm.setTransform(tInst, t)
        }
        val bInst = instanceOf(ent.barrel)
        if (bInst != 0) {
            Matrix.setIdentityM(t, 0)
            Matrix.translateM(t, 0, 0f, 0.24f, -0.3f + recoil * 0.12f)
            Matrix.rotateM(t, 0, -recoil * 2.5f, 1f, 0f, 0f)
            tm.setTransform(bInst, t)
        }
        // hood: damage state 2+ pops the hood, wrecks tear it wide open
        val hInst = instanceOf(ent.hood)
        if (hInst != 0) {
            val target = when {
                pose.isDead || pose.isWreckage -> 58f
                pose.healthFrac < 0.25f -> 34f
                pose.healthFrac < 0.55f -> 14f
                else -> 0f
            }
            ent.hoodOpen += (target - ent.hoodOpen) * 0.1f
            Matrix.setIdentityM(t, 0)
            Matrix.translateM(t, 0, 0f, 0.78f, -0.5f)
            Matrix.rotateM(t, 0, ent.hoodOpen, 1f, 0f, 0f)
            tm.setTransform(hInst, t)
        }
    }

    private fun wheel(entity: Int, x: Float, y: Float, z: Float, steerDeg: Float, rollDeg: Float) {
        val inst = instanceOf(entity)
        if (inst == 0) return
        Matrix.setIdentityM(t, 0)
        Matrix.translateM(t, 0, x, y, z)
        if (abs(steerDeg) > 0.05f) Matrix.rotateM(t, 0, -steerDeg, 0f, 1f, 0f)
        Matrix.rotateM(t, 0, -rollDeg, 1f, 0f, 0f)
        tm.setTransform(inst, t)
    }

    // ------------------------------------------------------------------ projectiles
    fun syncProjectile(inst: FilamentInstance?, b: GameViewModel.GpuBulletPose) {
        if (inst == null) return
        val ti = instanceOf(inst.root)
        if (ti == 0) return
        val yaw = -(Math.toDegrees(atan2(b.vy.toDouble(), b.vx.toDouble())).toFloat() + 90f)
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, b.x * WORLD_SCALE, 1.5f, b.y * WORLD_SCALE)
        Matrix.rotateM(m, 0, yaw, 0f, 1f, 0f)
        val stretch = (hypot(b.vx, b.vy) / 16f).coerceIn(0.7f, 1.6f)
        Matrix.scaleM(m, 0, 1f, 1f, stretch)
        tm.setTransform(ti, m)
    }

    // ------------------------------------------------------------------ particles
    fun syncParticle(inst: FilamentInstance?, p: GameViewModel.GpuParticlePose, time: Float) {
        if (inst == null) return
        val ti = instanceOf(inst.root)
        if (ti == 0) return
        val life = p.life.coerceIn(0f, 1f)
        val rise = when (p.type) { "smoke", "fire" -> (1f - life) * 2.6f + 0.9f; "dust", "skid" -> 0.35f + (1f - life) * 0.6f; else -> 0.8f + (1f - life) * 0.4f }
        val grow = when (p.type) { "smoke", "dust", "skid" -> 0.6f + (1f - life) * 1.6f; "fire" -> 0.8f + life * 0.6f; else -> 0.4f + life * 0.8f }
        val sz = (p.size * 0.05f).coerceIn(0.15f, 1.6f) * grow * (if (p.type == "spark" || p.type == "debris") 1f else life.coerceAtLeast(0.15f))
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, p.x * WORLD_SCALE, rise, p.y * WORLD_SCALE)
        Matrix.rotateM(m, 0, time * 90f + p.x, 0.3f, 1f, 0.2f)
        Matrix.scaleM(m, 0, sz, sz, sz)
        tm.setTransform(ti, m)
    }

    // ------------------------------------------------------------------ loot
    fun syncLoot(inst: FilamentInstance?, l: GameViewModel.GpuLootPose, time: Float) {
        if (inst == null) return
        if (!l.active) { hide(inst.root); return }
        val ti = instanceOf(inst.root)
        if (ti == 0) return
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, l.x * WORLD_SCALE, 0.15f + sin(l.bob) * 0.12f, l.y * WORLD_SCALE)
        Matrix.rotateM(m, 0, time * 40f + l.bob * 20f, 0f, 1f, 0f)
        tm.setTransform(ti, m)
    }

    // ------------------------------------------------------------------ zone ring
    fun syncZone(rootEntity: Int, cx: Float, cy: Float, radius: Float, pulse: Float) {
        val ti = instanceOf(rootEntity)
        if (ti == 0) return
        val r = radius * WORLD_SCALE
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, cx * WORLD_SCALE, 0f, cy * WORLD_SCALE)
        Matrix.scaleM(m, 0, r, 1f + pulse * 0.15f, r)
        tm.setTransform(ti, m)
    }

    // ------------------------------------------------------------------ crowd
    fun syncCrowdGroup(entity: Int, index: Int, time: Float, excitement: Float, visible: Boolean) {
        val ti = instanceOf(entity)
        if (ti == 0) return
        if (!visible) { hide(entity); return }
        val amp = 0.05f + (excitement / 100f).coerceIn(0f, 1f) * 0.32f
        val bounce = abs(sin(time * (3.2f + (index % 5) * 0.4f) + index * 0.9f)) * amp
        Matrix.setIdentityM(m, 0)
        Matrix.translateM(m, 0, 0f, bounce, 0f)
        tm.setTransform(ti, m)
    }

    fun lootPoolIndex(type: LootType): Int = when (type) {
        LootType.AMMO -> 0; LootType.HEALTH -> 1; LootType.ARMOR -> 2; LootType.NITRO -> 3
        LootType.WEAPON, LootType.UPGRADE -> 4; LootType.REPAIR -> 5
    }
}
