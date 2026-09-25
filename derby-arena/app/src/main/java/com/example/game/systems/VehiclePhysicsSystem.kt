package com.example.game.systems

import com.example.game.ArenaConstants
import com.example.game.PlayerCar
import kotlin.math.*

/**
 * System 1: VehiclePhysicsSystem
 * Authentic demolition derby vehicle dynamics:
 * - High-torque forward acceleration with nitrous boost surge
 * - Active power braking with screeching deceleration before reverse gear engagement
 * - Speed-sensitive steering dampening to prevent spinouts at top velocity
 * - Handbrake drift lock for wide power slides and counter-steering recovery
 * - Dynamic suspension roll and pitch feedback
 */
class VehiclePhysicsSystem {

    fun updatePlayer(
        player: PlayerCar,
        throttleInput: Float,
        steerInput: Float,
        sensitivity: Float,
        dt: Float,
        isBoosting: Boolean,
        isHandbraking: Boolean
    ) {
        if (player.isDead) {
            player.speed *= 0.94f
            player.vx *= 0.94f
            player.vy *= 0.94f
            player.x += player.vx
            player.y += player.vy
            return
        }

        player.isHandbraking = isHandbraking

        // 1. Nitrous Oxide Boost Injection
        if (isBoosting && player.nitro > 0f) {
            player.isNitroActive = true
            player.nitro = (player.nitro - 24f * dt).coerceAtLeast(0f)
        } else {
            player.isNitroActive = false
            player.nitro = (player.nitro + 14f * dt).coerceAtMost(player.maxNitro)
        }

        // 2. High-Response Steering & Wheel Angle
        val isDrifting = isHandbraking
        // Speed-dependent steering curve: quick at low speeds, stable at high speeds
        val speedFactor = (abs(player.speed) / (if (isDrifting) 2.2f else 3.8f)).coerceIn(0.25f, 1.0f)
        val maxSteerSpeed = (5.6f + (player.engineLevel - 1) * 0.5f) * sensitivity * (if (isDrifting) 1.6f else 1.0f)
        
        // Steering wheel / front tire angle (-42 to +42 degrees)
        val targetSteerAngle = (steerInput * 42f * sensitivity).coerceIn(-42f, 42f)
        player.steerAngle += (targetSteerAngle - player.steerAngle) * 0.45f

        val reverseFactor = if (player.speed < -0.1f) -1f else 1f
        player.angularVelocity = steerInput * maxSteerSpeed * speedFactor * reverseFactor
        player.angle = (player.angle + player.angularVelocity) % 360f
        if (player.angle < -180f) player.angle += 360f
        if (player.angle > 180f) player.angle -= 360f

        // 3. Throttle, Active Power Braking & Reverse
        val baseMaxSpeed = 7.5f + (player.engineLevel - 1) * 0.9f
        val maxSpeed = if (player.isNitroActive) baseMaxSpeed * 1.55f else baseMaxSpeed
        val accelPower = (0.26f + (player.engineLevel - 1) * 0.05f) * (if (player.isNitroActive) 2.4f else 1f)
        val normalFriction = if (isDrifting) 0.952f else 0.980f

        if (throttleInput > 0.05f) {
            // Forward acceleration
            player.speed += throttleInput * accelPower
            player.speed = player.speed.coerceAtMost(maxSpeed)
        } else if (throttleInput < -0.05f) {
            if (player.speed > 0.3f) {
                // Active hard braking: strong stopping power
                player.speed += throttleInput * accelPower * 2.8f
            } else {
                // Reverse gear
                player.speed += throttleInput * accelPower * 0.85f
                player.speed = player.speed.coerceAtLeast(-maxSpeed * 0.55f)
            }
        } else {
            // Engine rolling friction
            player.speed *= normalFriction
            if (abs(player.speed) < 0.04f) player.speed = 0f
        }

        // 4. Directional Velocity & Lateral Drift Mechanics
        val rad = Math.toRadians(player.angle.toDouble()).toFloat()
        val headingX = cos(rad)
        val headingY = sin(rad)

        val targetVx = headingX * player.speed
        val targetVy = headingY * player.speed

        // Lateral grip: low during handbrake drift, tight when gripping pavement
        val gripFactor = if (isDrifting) 0.09f else 0.32f
        player.vx += (targetVx - player.vx) * gripFactor
        player.vy += (targetVy - player.vy) * gripFactor

        player.x += player.vx
        player.y += player.vy

        // 5. Arena Outer Barrier Confinement & Rebound
        val dist = hypot(player.x, player.y)
        val maxR = ArenaConstants.PLAYABLE_RADIUS
        if (dist > maxR && dist > 0.01f) {
            val nx = player.x / dist
            val ny = player.y / dist
            player.x = nx * maxR
            player.y = ny * maxR
            val dot = player.vx * nx + player.vy * ny
            if (dot > 0f) {
                player.vx -= nx * dot * 1.4f
                player.vy -= ny * dot * 1.4f
                player.speed *= -0.35f
            }
        }
    }
}
