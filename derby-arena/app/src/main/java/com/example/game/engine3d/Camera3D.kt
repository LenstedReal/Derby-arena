package com.example.game.engine3d

import androidx.compose.ui.geometry.Size
import kotlin.math.*

enum class CameraMode3D(val label: String) {
    CHASE_3D("3D CHASE CAM"),
    HOOD_3D("3D HOOD CAM"),
    TACTICAL_3D("3D TACTICAL CAM")
}

/**
 * Cinematic third-person camera (Phase F).
 * Lower, tighter frame; lag-follow; FOV punch on boost; lateral lean on drift.
 */
class Camera3D {
    var position = Vector3(0f, 0f, 90f)
    var target = Vector3(0f, 0f, 15f)
    var up = Vector3(0f, 0f, 1f)
    var fov: Float = 580f
    var baseFov: Float = 580f

    var smoothYaw: Float = 0f
    var smoothPitch: Float = 0f

    private var forward = Vector3(0f, 1f, 0f)
    private var right = Vector3(1f, 0f, 0f)
    private var camUp = Vector3(0f, 0f, 1f)

    // Lateral drift offset for cinematic feel
    private var lateralOffset = 0f

    fun update(
        playerX: Float,
        playerY: Float,
        playerAngleDeg: Float,
        playerSpeed: Float,
        shakeAmount: Float,
        mode: CameraMode3D,
        isBoosting: Boolean,
        isHandbraking: Boolean = false,
        dt: Float = 0.016f
    ) {
        // Boost / speed FOV expansion
        val targetFov = when {
            isBoosting -> baseFov * 1.18f
            abs(playerSpeed) > 6f -> baseFov * (1f + (abs(playerSpeed) - 6f) * 0.015f)
            else -> baseFov
        }
        fov += (targetFov - fov) * 0.12f

        // Smooth yaw follow
        val angleDiff = (playerAngleDeg - smoothYaw + 540f) % 360f - 180f
        val followSpeed = when (mode) {
            CameraMode3D.HOOD_3D -> 0.38f
            CameraMode3D.CHASE_3D -> 0.16f
            CameraMode3D.TACTICAL_3D -> 0.07f
        }
        smoothYaw += angleDiff * followSpeed

        // Drift lateral camera lean
        val targetLateral = if (isHandbraking && abs(playerSpeed) > 2f) {
            // lean opposite to steer feel via angular residual
            -angleDiff.coerceIn(-25f, 25f) * 0.9f
        } else {
            0f
        }
        lateralOffset += (targetLateral - lateralOffset) * 0.08f

        val radYaw = Math.toRadians(smoothYaw.toDouble()).toFloat()
        val headingX = cos(radYaw)
        val headingY = sin(radYaw)
        val sideX = -sin(radYaw)
        val sideY = cos(radYaw)

        val shakeX = if (shakeAmount > 0.05f) (Math.random().toFloat() * 2f - 1f) * shakeAmount * 1.4f else 0f
        val shakeY = if (shakeAmount > 0.05f) (Math.random().toFloat() * 2f - 1f) * shakeAmount * 1.4f else 0f
        val shakeZ = if (shakeAmount > 0.05f) (Math.random().toFloat() * 2f - 1f) * shakeAmount * 0.9f else 0f

        when (mode) {
            CameraMode3D.CHASE_3D -> {
                // Closer, lower cinematic chase — vehicle in lower-middle frame
                val dist = 118f + abs(playerSpeed) * 2.2f
                val height = 48f + (if (playerSpeed < 0f) 10f else 0f)

                val targetPosX = playerX - headingX * dist + sideX * lateralOffset + shakeX
                val targetPosY = playerY - headingY * dist + sideY * lateralOffset + shakeY
                val targetPosZ = height + shakeZ

                position.x += (targetPosX - position.x) * 0.18f
                position.y += (targetPosY - position.y) * 0.18f
                position.z += (targetPosZ - position.z) * 0.18f

                val lookAhead = 55f + abs(playerSpeed) * 3f
                target = Vector3(
                    playerX + headingX * lookAhead,
                    playerY + headingY * lookAhead,
                    12f
                )
            }
            CameraMode3D.HOOD_3D -> {
                val hoodOffset = 16f
                position = Vector3(
                    playerX + headingX * hoodOffset + shakeX,
                    playerY + headingY * hoodOffset + shakeY,
                    22f + shakeZ
                )
                val lookAhead = 140f
                target = Vector3(
                    playerX + headingX * lookAhead,
                    playerY + headingY * lookAhead,
                    16f
                )
            }
            CameraMode3D.TACTICAL_3D -> {
                val dist = 280f
                val height = 210f
                val targetPosX = playerX - headingX * dist * 0.55f + shakeX
                val targetPosY = playerY - headingY * dist * 0.55f + shakeY
                val targetPosZ = height + shakeZ

                position.x += (targetPosX - position.x) * 0.12f
                position.y += (targetPosY - position.y) * 0.12f
                position.z += (targetPosZ - position.z) * 0.12f

                target = Vector3(playerX, playerY, 12f)
            }
        }

        forward = (target - position).normalized()
        val tempUp = if (abs(forward.dot(Vector3(0f, 0f, 1f))) > 0.99f) Vector3(0f, 1f, 0f) else Vector3(0f, 0f, 1f)
        right = forward.cross(tempUp).normalized()
        camUp = right.cross(forward).normalized()
    }

    fun project(point: Vector3, viewportSize: Size): ProjectedPoint {
        val rel = point - position
        val depth = rel.dot(forward)
        if (depth <= 2.0f) {
            return ProjectedPoint(0f, 0f, depth, false)
        }

        val camX = rel.dot(right)
        val camY = rel.dot(camUp)

        val halfW = viewportSize.width * 0.5f
        val halfH = viewportSize.height * 0.5f

        val sx = halfW + (camX / depth) * fov
        val sy = halfH - (camY / depth) * fov

        val margin = 200f
        val isVisible = sx >= -margin && sx <= viewportSize.width + margin &&
                sy >= -margin && sy <= viewportSize.height + margin

        return ProjectedPoint(sx, sy, depth, isVisible)
    }

    fun isSphereInFrustum(worldPos: Vector3, radius: Float): Boolean {
        val rx = worldPos.x - position.x
        val ry = worldPos.y - position.y
        val rz = worldPos.z - position.z

        val depth = rx * forward.x + ry * forward.y + rz * forward.z
        if (depth < -radius || depth > 1050f) return false

        val camX = rx * right.x + ry * right.y + rz * right.z
        val camY = rx * camUp.x + ry * camUp.y + rz * camUp.z

        val maxDist = depth * 1.4f + radius + 60f
        return abs(camX) <= maxDist && abs(camY) <= maxDist
    }

    fun getDepth(point: Vector3): Float {
        return (point - position).dot(forward)
    }
}
