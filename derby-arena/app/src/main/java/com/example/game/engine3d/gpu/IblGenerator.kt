package com.example.game.engine3d.gpu

import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.IndirectLight
import com.google.android.filament.Skybox
import com.google.android.filament.Texture
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Procedural image-based lighting: bakes a dusty post-apocalyptic sky (sun disc, warm horizon haze,
 * dark ground bounce) into a mip-mapped cubemap for specular reflections + spherical-harmonics
 * irradiance. Replaces the need for offline cmgen-generated KTX files.
 */
object IblGenerator {
    private const val TAG = "IblGenerator"

    class Result(val indirectLight: IndirectLight, val skybox: Skybox, val texture: Texture)

    /** Sky radiance (linear, roughly [0..~8]) for a world direction. */
    private fun sky(dx: Float, dy: Float, dz: Float, sunX: Float, sunY: Float, sunZ: Float, blur: Float, out: FloatArray) {
        val up = dy
        // horizon haze -> zenith gradient
        val t = ((up + 0.05f) / 1.05f).coerceIn(0f, 1f)
        val hazeR = 0.78f; val hazeG = 0.62f; val hazeB = 0.46f      // warm dusty horizon
        val zenR = 0.26f; val zenG = 0.30f; val zenB = 0.36f         // dim smoky zenith
        var r = hazeR * (1 - t) + zenR * t
        var g = hazeG * (1 - t) + zenG * t
        var b = hazeB * (1 - t) + zenB * t
        if (up < 0f) {
            // ground bounce: sand & scorched stone
            val g2 = (-up).coerceIn(0f, 1f)
            r = 0.34f * (1 - g2) + 0.22f * g2
            g = 0.28f * (1 - g2) + 0.18f * g2
            b = 0.20f * (1 - g2) + 0.13f * g2
        }
        // sun disc + corona (softened per mip level)
        val cos = (dx * sunX + dy * sunY + dz * sunZ).coerceIn(-1f, 1f)
        val disc = ((cos - (0.9975f - blur * 0.15f)) / (0.0025f + blur * 0.15f)).coerceIn(0f, 1f)
        val corona = max(0f, cos - 0.85f).pow(3f) * 2.2f
        r += disc * 6f + corona * 1.2f
        g += disc * 5.2f + corona * 0.9f
        b += disc * 3.6f + corona * 0.5f
        out[0] = r; out[1] = g; out[2] = b
    }

    /** Cubemap face direction for face index (Filament order: +X,-X,+Y,-Y,+Z,-Z). */
    private fun faceDir(face: Int, u: Float, v: Float, out: FloatArray) {
        val (x, y, z) = when (face) {
            0 -> Triple(1f, -v, -u)
            1 -> Triple(-1f, -v, u)
            2 -> Triple(u, 1f, v)
            3 -> Triple(u, -1f, -v)
            4 -> Triple(u, -v, 1f)
            else -> Triple(-u, -v, -1f)
        }
        val len = sqrt(x * x + y * y + z * z)
        out[0] = x / len; out[1] = y / len; out[2] = z / len
    }

    fun create(engine: Engine, sunDir: FloatArray, intensity: Float, baseSize: Int = 64): Result? {
        return try {
            // Light direction points from the sun toward the scene: sky sun position is the negation.
            val sl = sqrt(sunDir[0] * sunDir[0] + sunDir[1] * sunDir[1] + sunDir[2] * sunDir[2])
            val sx = -sunDir[0] / sl; val sy = -sunDir[1] / sl; val sz = -sunDir[2] / sl
            var levels = 1
            var s = baseSize
            while (s > 1) { s /= 2; levels++ }
            val texture = Texture.Builder()
                .width(baseSize).height(baseSize).levels(levels)
                .sampler(Texture.Sampler.SAMPLER_CUBEMAP)
                .format(Texture.InternalFormat.RGBA8)
                .build(engine)

            val sh = FloatArray(27)
            val dir = FloatArray(3)
            val rgb = FloatArray(3)
            var size = baseSize
            for (level in 0 until levels) {
                val faceBytes = size * size * 4
                val buffer = ByteBuffer.allocateDirect(faceBytes * 6).order(ByteOrder.nativeOrder())
                val blur = level.toFloat() / (levels - 1).coerceAtLeast(1)
                val offsets = IntArray(6) { it * faceBytes }
                for (face in 0 until 6) {
                    for (py in 0 until size) {
                        for (px in 0 until size) {
                            val u = (px + 0.5f) / size * 2f - 1f
                            val v = (py + 0.5f) / size * 2f - 1f
                            faceDir(face, u, v, dir)
                            sky(dir[0], dir[1], dir[2], sx, sy, sz, blur, rgb)
                            // rougher mips: blend toward the average sky tone (pre-filter approximation)
                            val avgR = 0.5f; val avgG = 0.42f; val avgB = 0.34f
                            val r = rgb[0] * (1 - blur * 0.6f) + avgR * blur * 0.6f
                            val g = rgb[1] * (1 - blur * 0.6f) + avgG * blur * 0.6f
                            val b = rgb[2] * (1 - blur * 0.6f) + avgB * blur * 0.6f
                            // encode: linear / 4 into 8 bits (IBL intensity compensates)
                            buffer.put((r * 64f).coerceIn(0f, 255f).toInt().toByte())
                            buffer.put((g * 64f).coerceIn(0f, 255f).toInt().toByte())
                            buffer.put((b * 64f).coerceIn(0f, 255f).toInt().toByte())
                            buffer.put(255.toByte())
                            if (level == 0) {
                                // SH irradiance accumulation with solid-angle weights
                                val d = 1f + u * u + v * v
                                val dOmega = 4f / (sqrt(d) * d) / (size * size)
                                val x = dir[0]; val y = dir[1]; val z = dir[2]
                                val basis = floatArrayOf(
                                    1f, y, z, x, y * x, y * z, 3f * z * z - 1f, z * x, x * x - y * y
                                )
                                for (i in 0 until 9) {
                                    val w = basis[i] * dOmega
                                    sh[i * 3] += rgb[0] * w
                                    sh[i * 3 + 1] += rgb[1] * w
                                    sh[i * 3 + 2] += rgb[2] * w
                                }
                            }
                        }
                    }
                }
                buffer.flip()
                texture.setImage(engine, level, Texture.PixelBufferDescriptor(buffer, Texture.Format.RGBA, Texture.Type.UBYTE))
                size = max(1, size / 2)
            }
            // pre-scale SH by A_l * K_lm^2 (Filament's shader applies the raw polynomial only)
            val k = floatArrayOf(0.282095f, 0.488603f, 0.488603f, 0.488603f, 1.092548f, 1.092548f, 0.315392f, 1.092548f, 0.546274f)
            val a = floatArrayOf(PI.toFloat(), 2.0943951f, 2.0943951f, 2.0943951f, 0.7853982f, 0.7853982f, 0.7853982f, 0.7853982f, 0.7853982f)
            for (i in 0 until 9) {
                val scale = a[i] * k[i] * k[i] / PI.toFloat() // divide by pi: Filament irradiance is pre-divided
                sh[i * 3] *= scale; sh[i * 3 + 1] *= scale; sh[i * 3 + 2] *= scale
            }
            val ibl = IndirectLight.Builder()
                .reflections(texture)
                .irradiance(3, sh)
                .intensity(intensity)
                .build(engine)
            val skybox = Skybox.Builder().environment(texture).intensity(intensity * 0.9f).build(engine)
            Log.i(TAG, "Procedural IBL ready: ${baseSize}px cubemap, $levels mips, SH0=(${sh[0]}, ${sh[1]}, ${sh[2]})")
            Result(ibl, skybox, texture)
        } catch (t: Throwable) {
            Log.e(TAG, "IBL generation failed, falling back to flat ambient", t)
            null
        }
    }
}
