package com.example.game.engine3d.gpu

import com.example.game.systems.CameraSystem
import com.google.android.filament.Camera

/** Adapter between the GPU frame loop and CameraSystem. */
class CameraController {
    private val cameraSystem = CameraSystem()

    fun reset(targetX: Float, targetZ: Float, initialYawDeg: Float) = cameraSystem.reset(targetX, targetZ, initialYawDeg)

    fun update(
        camera: Camera, targetX: Float, targetZ: Float, angleDeg: Float, turretAngleDeg: Float, speed: Float,
        isBoosting: Boolean, isHandbrake: Boolean, aimActive: Boolean, screenShake: Float, recoil: Float,
        aspect: Double, orbitTime: Double
    ) = cameraSystem.updateCamera(camera, targetX, targetZ, angleDeg, turretAngleDeg, speed, isBoosting, isHandbrake, aimActive, screenShake, recoil, aspect, orbitTime)
}
