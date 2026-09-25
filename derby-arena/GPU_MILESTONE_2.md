# GPU Milestone 2 — Build Fix + GLB World + Gameplay Bind

## Build fix (permanent)
- compileSdk **37** (required by Filament 1.77.1 AAR metadata)
- targetSdk **36** (unchanged runtime policy)
- minSdk **24** (unchanged)
- Filament version stays **1.77.1** (not downgraded)
- google-services missing strategy still WARN

## Version
6.2-gpu2 (versionCode 62)

## GPU path (main)
- FilamentWorld loads real .glb from assets
- player_muscle.glb, enemy_raider.glb x5, arena01_base.glb
- Sun light with castShadows
- Chase camera (CameraController) follows player pose
- GameViewModel poses → Filament transforms each frame
- Controls overlay (joystick deadzone + center return, nitro/handbrake HOLD)
- Dashboard + damage debug still on GPU path

## Fallback
USE_GPU_RENDERER default true; on init failure → Canvas + orange badge

## Assets
assets/models/vehicles/player_muscle.glb
assets/models/vehicles/enemy_raider.glb
assets/models/arena/arena01_base.glb

## Honest status
- GLB: YES (low-poly generated proxies — not art-directed muscle-car assets)
- Arena mesh: YES (disk + wall segments proxy)
- Real Filament shadows: YES (engine shadow maps when device supports FL1)
- PBR ubershader: YES via gltfio UbershaderProvider (no custom painted textures yet)
- Full Unity-trailer quality: NO — next need art assets + materials + VFX
