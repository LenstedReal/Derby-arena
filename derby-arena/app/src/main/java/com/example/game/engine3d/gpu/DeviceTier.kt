package com.example.game.engine3d.gpu

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.game.QualityPreset

/**
 * Device tier detection (LOW / MEDIUM / HIGH / ULTRA) from RAM, CPU cores and API level.
 * Drives shadow map size, crowd density, particle budget and render resolution scale.
 */
object DeviceTier {
    private const val TAG = "DeviceTier"

    data class RenderBudget(
        val preset: QualityPreset,
        val shadowMapSize: Int,
        val crowdGroups: Int,          // out of 24 baked groups
        val maxParticles: Int,
        val resolutionScale: Float,
        val ssao: Boolean,
        val bloom: Boolean,
        val softShadows: Boolean
    )

    fun detect(context: Context): QualityPreset {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val ramGb = mi.totalMem / (1024.0 * 1024.0 * 1024.0)
            val cores = Runtime.getRuntime().availableProcessors()
            val preset = when {
                ramGb >= 7.5 && cores >= 8 && Build.VERSION.SDK_INT >= 31 -> QualityPreset.ULTRA
                ramGb >= 5.5 && cores >= 8 -> QualityPreset.HIGH
                ramGb >= 3.5 -> QualityPreset.MEDIUM
                else -> QualityPreset.LOW
            }
            Log.i(TAG, "Detected tier $preset (ram=${"%.1f".format(ramGb)}GB cores=$cores sdk=${Build.VERSION.SDK_INT})")
            preset
        } catch (t: Throwable) {
            Log.w(TAG, "Tier detection failed: ${t.message}")
            QualityPreset.MEDIUM
        }
    }

    fun budget(preset: QualityPreset): RenderBudget = when (preset) {
        QualityPreset.LOW -> RenderBudget(preset, 1024, 8, 48, 0.75f, ssao = false, bloom = false, softShadows = false)
        QualityPreset.MEDIUM -> RenderBudget(preset, 1536, 16, 96, 0.85f, ssao = false, bloom = true, softShadows = false)
        QualityPreset.HIGH -> RenderBudget(preset, 2048, 24, 160, 1.0f, ssao = true, bloom = true, softShadows = true)
        QualityPreset.ULTRA -> RenderBudget(preset, 4096, 24, 220, 1.0f, ssao = true, bloom = true, softShadows = true)
    }
}
