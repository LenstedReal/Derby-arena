package com.example.game.systems

import com.example.game.*
import kotlin.math.*
import kotlin.random.Random

/**
 * BattleRoyaleSystem: shrinking safe zone with timed phases, out-of-zone damage and the final circle.
 * Zone center drifts between phases so the fight moves across the colosseum floor.
 */
class BattleRoyaleSystem {

    private data class Phase(val waitSeconds: Float, val shrinkSeconds: Float, val radius: Float, val dps: Float)

    private val phases = listOf(
        Phase(28f, 22f, 520f, 4f),
        Phase(20f, 20f, 360f, 6f),
        Phase(16f, 16f, 230f, 9f),
        Phase(12f, 14f, 130f, 14f),
        Phase(10f, 12f, 70f, 22f)
    )

    val zone = ZoneState()
    private var shrinkDuration = 1f
    private var shrinkFrom = 700f
    private var shrinkFromCx = 0f
    private var shrinkFromCy = 0f

    fun reset(levelScale: Float) {
        zone.centerX = 0f
        zone.centerY = 0f
        zone.radius = ArenaConstants.PLAYABLE_RADIUS
        zone.targetRadius = zone.radius
        zone.targetCenterX = 0f
        zone.targetCenterY = 0f
        zone.phase = 0
        zone.shrinking = false
        zone.finalCircle = false
        zone.damagePerSecond = 4f
        zone.phaseTimer = phases[0].waitSeconds / levelScale.coerceIn(0.8f, 1.6f)
    }

    /** Advances the zone timeline. Returns true when the phase changed (HUD announcement). */
    fun update(dt: Float): Boolean {
        var changed = false
        zone.phaseTimer -= dt
        if (zone.shrinking) {
            val t = (1f - (zone.phaseTimer / shrinkDuration)).coerceIn(0f, 1f)
            val eased = t * t * (3f - 2f * t)
            zone.radius = shrinkFrom + (zone.targetRadius - shrinkFrom) * eased
            zone.centerX = shrinkFromCx + (zone.targetCenterX - shrinkFromCx) * eased
            zone.centerY = shrinkFromCy + (zone.targetCenterY - shrinkFromCy) * eased
            if (zone.phaseTimer <= 0f) {
                zone.radius = zone.targetRadius
                zone.centerX = zone.targetCenterX
                zone.centerY = zone.targetCenterY
                zone.shrinking = false
                zone.phase++
                if (zone.phase < phases.size) {
                    zone.phaseTimer = phases[zone.phase].waitSeconds
                } else {
                    zone.finalCircle = true
                    zone.phaseTimer = 9999f
                }
                changed = true
            }
        } else if (zone.phaseTimer <= 0f && zone.phase < phases.size) {
            val p = phases[zone.phase]
            shrinkFrom = zone.radius
            shrinkFromCx = zone.centerX
            shrinkFromCy = zone.centerY
            shrinkDuration = p.shrinkSeconds
            zone.targetRadius = p.radius
            // drift the center but keep the target circle inside the current one
            val maxDrift = (zone.radius - p.radius) * 0.55f
            val a = Random.nextFloat() * 2f * PI.toFloat()
            val d = Random.nextFloat() * maxDrift
            zone.targetCenterX = (zone.centerX + cos(a) * d).coerceIn(-300f, 300f)
            zone.targetCenterY = (zone.centerY + sin(a) * d).coerceIn(-300f, 300f)
            zone.damagePerSecond = p.dps
            zone.phaseTimer = p.shrinkSeconds
            zone.shrinking = true
            changed = true
        }
        return changed
    }

    fun isInside(x: Float, y: Float, margin: Float = 0f): Boolean =
        hypot(x - zone.centerX, y - zone.centerY) <= zone.radius - margin

    /** Damage to apply this frame to something standing at (x,y); 0 if inside the zone. */
    fun zoneDamage(x: Float, y: Float, dt: Float): Float {
        if (isInside(x, y)) return 0f
        val outside = hypot(x - zone.centerX, y - zone.centerY) - zone.radius
        return (zone.damagePerSecond + outside * 0.01f) * dt
    }

    fun timeToNextPhaseSeconds(): Int = zone.phaseTimer.coerceAtLeast(0f).toInt()
}
