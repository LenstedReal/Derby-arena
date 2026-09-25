# GPU Milestone 1 — Filament Proof

## Goal
Prove real GPU 3D on device: Filament + cube + ground + sun + shadow + orbit camera + Compose HUD.

## Dependencies
- com.google.android.filament:filament-android:1.77.1
- filament-utils-android:1.77.1
- filamat-android:1.77.1
- gltfio-android:1.77.1

## Feature flag
RenderConfig.USE_GPU_RENDERER = true (default)

On GPU init failure -> explicit Canvas fallback + orange badge (never silent blank).

## New files
- engine3d/gpu/RenderConfig.kt
- engine3d/gpu/GpuRenderStatus.kt
- engine3d/gpu/FilamentGpuDemo.kt
- engine3d/gpu/GpuArenaViewport.kt

## Runtime expectation
Start game -> full-screen GPU view, rotating metallic-red cube on sand plane, orbiting camera, green badge:
GPU RENDERER ACTIVE

## Not in this milestone
Player car GLB, arena, crowd, full gameplay migration.

## Build
./gradlew assembleDebug --no-configuration-cache

## Known risks
- MaterialBuilder / FogOptions API drift across Filament patch versions
- First frame may flash until material compiles
- Shadows require device GLES capability (Feature Level 1)
