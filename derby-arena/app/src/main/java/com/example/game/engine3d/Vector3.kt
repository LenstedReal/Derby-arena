package com.example.game.engine3d

import androidx.compose.ui.graphics.Color
import kotlin.math.*

/**
 * High performance 3D Vector for real-time mobile 3D rendering and physics.
 */
data class Vector3(
    var x: Float = 0f,
    var y: Float = 0f,
    var z: Float = 0f
) {
    operator fun plus(o: Vector3) = Vector3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vector3) = Vector3(x - o.x, y - o.y, z - o.z)
    operator fun times(s: Float) = Vector3(x * s, y * s, z * s)
    operator fun div(s: Float) = Vector3(x / s, y / s, z / s)

    fun dot(o: Vector3): Float = x * o.x + y * o.y + z * o.z

    fun cross(o: Vector3): Vector3 = Vector3(
        y * o.z - z * o.y,
        z * o.x - x * o.z,
        x * o.y - y * o.x
    )

    fun lengthSquared(): Float = x * x + y * y + z * z
    fun length(): Float = sqrt(lengthSquared())

    fun normalized(): Vector3 {
        val len = length()
        return if (len > 0.0001f) this / len else Vector3(0f, 0f, 1f)
    }

    fun rotateZ(deg: Float): Vector3 {
        val rad = Math.toRadians(deg.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        return Vector3(x * c - y * s, x * s + y * c, z)
    }

    fun rotateX(deg: Float): Vector3 {
        val rad = Math.toRadians(deg.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        return Vector3(x, y * c - z * s, y * s + z * c)
    }

    fun rotateY(deg: Float): Vector3 {
        val rad = Math.toRadians(deg.toDouble()).toFloat()
        val c = cos(rad)
        val s = sin(rad)
        return Vector3(x * c + z * s, y, -x * s + z * c)
    }
}

/**
 * Screen projected 2D coordinates with depth for perspective rendering and depth sorting.
 */
data class ProjectedPoint(
    val sx: Float,
    val sy: Float,
    val depth: Float,
    val isVisible: Boolean
)

/**
 * 3D Polygon face (quad or triangle) with depth sorting, normal-based lighting, and color.
 */
data class Poly3D(
    val p1: Vector3,
    val p2: Vector3,
    val p3: Vector3,
    val p4: Vector3? = null,
    val color: Color,
    val isDoubleSided: Boolean = false,
    val emissive: Boolean = false,
    var depth: Float = 0f
)

/**
 * 3D Billboard / Sprite for particles, text, health bars, and indicators.
 */
data class Billboard3D(
    val position: Vector3,
    val size: Float,
    val color: Color,
    val type: String, // "particle", "smoke", "spark", "flame", "text", "health_bar", "target"
    val text: String = "",
    val extraData: Float = 0f,
    var depth: Float = 0f
)
