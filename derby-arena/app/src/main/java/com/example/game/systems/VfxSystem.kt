package com.example.game.systems

import androidx.compose.ui.graphics.Color
import com.example.game.Particle
import com.example.game.QualityPreset
import kotlin.math.*
import kotlin.random.Random

/**
 * System 6: VfxSystem
 * Manages all visual effects: dust bursts, sparks, explosions, muzzle flashes,
 * smoke, screen shake, and crowd excitement dynamics.
 */
class VfxSystem {

    private val particlesList = ArrayList<Particle>(60)
    var screenShake: Float = 0f
        private set
    var crowdExcitement: Float = 25f
        private set

    fun getParticles(): List<Particle> = particlesList

    fun clear() {
        particlesList.clear()
        screenShake = 0f
    }

    fun addScreenShake(amount: Float) {
        screenShake = (screenShake + amount).coerceAtMost(25f)
    }

    fun addCrowdExcitement(amount: Float) {
        crowdExcitement = (crowdExcitement + amount).coerceIn(0f, 100f)
    }

    fun decayEffects() {
        if (screenShake > 0.1f) {
            screenShake *= 0.90f
        } else {
            screenShake = 0f
        }

        if (crowdExcitement > 20f) {
            crowdExcitement -= 0.08f
        }
    }

    fun addDustBurst(x: Float, y: Float, nx: Float, ny: Float, count: Int, quality: QualityPreset) {
        val maxParticles = quality.maxParticles
        val burstCount = count.coerceAtMost(if (quality == QualityPreset.HIGH) 8 else 4)
        for (i in 0 until burstCount) {
            if (particlesList.size >= maxParticles) break
            val spread = (Random.nextFloat() - 0.5f) * 1.5f
            val speed = 2.0f + Random.nextFloat() * 4.0f
            val vx = (nx + spread) * speed
            val vy = (ny + spread) * speed
            particlesList.add(
                Particle(
                    x = x,
                    y = y,
                    vx = vx,
                    vy = vy,
                    color = Color(0xFF8D6E63).copy(alpha = 0.65f),
                    size = 12f + Random.nextFloat() * 14f,
                    life = 1f,
                    decay = 0.06f,
                    type = "smoke"
                )
            )
        }
    }

    fun addMetalSparks(x: Float, y: Float, vx: Float, vy: Float, color: Color, quality: QualityPreset) {
        val maxParticles = quality.maxParticles
        val sparkCount = if (quality == QualityPreset.HIGH) 6 else 3
        for (i in 0 until sparkCount) {
            if (particlesList.size >= maxParticles) break
            val angle = Random.nextFloat() * 2 * PI.toFloat()
            val speed = 3.0f + Random.nextFloat() * 5.0f
            particlesList.add(
                Particle(
                    x = x,
                    y = y,
                    vx = cos(angle) * speed + vx * 0.2f,
                    vy = sin(angle) * speed + vy * 0.2f,
                    color = color,
                    size = 3.5f + Random.nextFloat() * 2.5f,
                    life = 1f,
                    decay = 0.08f,
                    type = "spark"
                )
            )
        }
    }

    fun addMuzzleFlash(x: Float, y: Float, angleRad: Float, color: Color) {
        particlesList.add(
            Particle(
                x = x,
                y = y,
                vx = cos(angleRad) * 2f,
                vy = sin(angleRad) * 2f,
                color = color,
                size = 18f,
                life = 1f,
                decay = 0.30f,
                type = "fire"
            )
        )
    }

    fun addExplosion(x: Float, y: Float, size: Float, quality: QualityPreset) {
        addScreenShake(size * 0.5f)
        addCrowdExcitement(15f)
        // Fireball center
        particlesList.add(
            Particle(x = x, y = y, vx = 0f, vy = 0f, color = Color.White, size = size, decay = 0.12f, type = "fire")
        )
        particlesList.add(
            Particle(x = x, y = y, vx = 0f, vy = 0f, color = Color(0xFFFFD54F), size = size * 1.3f, decay = 0.08f, type = "fire")
        )

        val rings = if (quality == QualityPreset.HIGH) 8 else 4
        for (i in 0 until rings) {
            val a = Random.nextFloat() * 2 * PI.toFloat()
            val spd = 2f + Random.nextFloat() * 5f
            particlesList.add(
                Particle(
                    x = x,
                    y = y,
                    vx = cos(a) * spd,
                    vy = sin(a) * spd,
                    color = Color(0xFFFF5722),
                    size = size * 0.6f,
                    decay = 0.06f,
                    type = "fire"
                )
            )
        }
    }

    fun addFloatingText(x: Float, y: Float, text: String, color: Color) {
        particlesList.add(
            Particle(
                x = x,
                y = y,
                vx = 0f,
                vy = -1.2f,
                color = color,
                size = 14f,
                life = 1f,
                decay = 0.035f,
                type = "text",
                customText = text
            )
        )
    }

    fun updateParticles(quality: QualityPreset) {
        val iter = particlesList.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            p.x += p.vx
            p.y += p.vy
            p.life -= p.decay
            if (p.type == "smoke" || p.type == "fire") {
                p.size *= 1.03f
                p.vx *= 0.94f
                p.vy *= 0.94f
            }
            if (p.life <= 0f) {
                iter.remove()
            }
        }
        while (particlesList.size > quality.maxParticles) {
            particlesList.removeAt(0)
        }
    }
}
