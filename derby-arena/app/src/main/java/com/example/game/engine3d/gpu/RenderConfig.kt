package com.example.game.engine3d.gpu

/**
 * Dual-renderer feature flags.
 * USE_GPU_RENDERER=true is the default path for premium 3D.
 * If GPU init fails at runtime, UI must fall back explicitly (never silent blank).
 */
object RenderConfig {
    const val USE_GPU_RENDERER: Boolean = true
}
