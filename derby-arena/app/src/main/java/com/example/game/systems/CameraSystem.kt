package com.example.game.systems

import com.example.game.CameraState
import com.google.android.filament.Camera
import kotlin.math.*
import kotlin.random.Random

/**
 * CameraSystem: cinematic third-person vehicle camera → Filament camera.
 * Smooth follow, yaw smoothing with drift lag, speed-based distance, impact shake, recoil kick,
 * aim-camera blend toward the turret direction, nitro FOV stretch, countdown orbit.
 */
class CameraSystem {

    private var smoothX = 0.0
    private var smoothY = 3.2
    private var smoothZ = 7.0
    private var smoothYaw = 0.0
    private var smoothFov = 46.0
    val state = CameraState()

    fun reset(targetX: Float, targetZ: Float, initialYawDeg: Float) {
        val rad = Math.toRadians(-(initialYawDeg + 90.0))
        smoothYaw = -(initialYawDeg + 90.0)
        smoothX = targetX - sin(rad) * 6.5
        smoothY = 3.4
        smoothZ = targetZ + cos(rad) * 6.5
    }

    fun updateCamera(
        camera: Camera,
        targetX: Float,
        targetZ: Float,
        angleDeg: Float,
        turretAngleDeg: Float,
        speed: Float,
        isBoosting: Boolean,
        isHandbrake: Boolean,
        aimActive: Boolean,
        screenShake: Float,
        recoil: Float,
        aspect: Double,
        orbitTime: Double = -1.0
    ) {
        val carYaw = -(angleDeg.toDouble() + 90.0)
        val turretYaw = -(turretAngleDeg.toDouble() + 90.0)
        // aim camera: blend toward the turret heading while the aim stick is held
        state.aimBlend += ((if (aimActive) 0.55f else 0f) - state.aimBlend) * 0.08f
        val aimDiff = ((turretYaw - carYaw + 540.0) % 360.0) - 180.0
        val targetYaw = carYaw + aimDiff * state.aimBlend
        val diff = ((targetYaw - smoothYaw + 540.0) % 360.0) - 180.0
        // drift: slower yaw follow while handbraking so the car slides across the frame
        smoothYaw += diff * (if (isHandbrake) 0.07 else 0.14)
        state.driftYaw = diff.toFloat()

        val syRad = Math.toRadians(smoothYaw)
        val fx = sin(syRad)
        val fz = -cos(syRad)

        val baseDistance = 6.6 + (abs(speed) * 0.13).coerceAtMost(2.6) + (if (isBoosting) 1.3 else 0.0)
        val camHeight = 3.0 + (abs(speed) * 0.04).coerceAtMost(0.8)

        var desiredX: Double; var desiredZ: Double; var desiredY = camHeight
        if (orbitTime >= 0.0) {
            // countdown: slow cinematic orbit around the player
            val a = orbitTime * 0.35
            desiredX = targetX + sin(a) * 8.5
            desiredZ = targetZ + cos(a) * 8.5
            desiredY = 2.6
        } else {
            desiredX = targetX - fx * baseDistance
            desiredZ = targetZ - fz * baseDistance
        }
        val followRate = 0.16
        smoothX += (desiredX - smoothX) * followRate
        smoothY += (desiredY - smoothY) * followRate
        smoothZ += (desiredZ - smoothZ) * followRate

        val lookLead = 3.5 + (abs(speed) * 0.15).coerceAtMost(2.0)
        var lookX = targetX + fx * lookLead
        var lookY = 0.9
        var lookZ = targetZ + fz * lookLead

        // recoil kick (backwards + up) and impact shake
        state.recoil = max(state.recoil * 0.8f, recoil)
        state.shake = screenShake
        var camX = smoothX - fx * state.recoil * 0.12
        var camY = smoothY + state.recoil * 0.05
        var camZ = smoothZ - fz * state.recoil * 0.12
        if (screenShake > 0.05f) {
            val mag = (screenShake * 0.035).coerceAtMost(0.45)
            val rx = (Random.nextDouble() - 0.5) * mag
            val ry = (Random.nextDouble() - 0.5) * mag
            val rz = (Random.nextDouble() - 0.5) * mag
            camX += rx; camY += ry; camZ += rz
            lookX += rx * 0.5; lookY += ry * 0.5; lookZ += rz * 0.5
        }
        camera.lookAt(camX, camY, camZ, lookX, lookY, lookZ, 0.0, 1.0, 0.0)

        val targetFov = 46.0 + (abs(speed) * 0.8).coerceAtMost(6.0) + (if (isBoosting) 8.0 else 0.0) - state.aimBlend * 6.0
        smoothFov += (targetFov - smoothFov) * 0.1
        camera.setProjection(smoothFov, aspect, 0.15, 900.0, Camera.Fov.VERTICAL)
    }
}
