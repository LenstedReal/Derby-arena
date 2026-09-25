package com.example.game.engine3d.gpu

enum class GpuBackendState {
    UNINITIALIZED,
    ACTIVE,
    FAILED,
    FALLBACK_CANVAS
}

data class GpuStatus(
    val state: GpuBackendState = GpuBackendState.UNINITIALIZED,
    val message: String = "GPU not started",
    val filamentVersionHint: String = "1.77.1"
)
