# PHASE A — Player Death Diagnosis (evidence-based)

## Root causes found in code (not guesses)

### 1. Enemy bullet spam (PRIMARY)
- 5 enemies all fire when `distPlayer < 450`
- Damage per hit: `6f + 1.5f = 7.5`, after armor ~6
- Fire cooldown was `40 + rand(35)` ≈ 0.67–1.25s
- Theoretical DPS from 5 AI: **~25–40 HP/s**
- Player max HP: 100 (+armor upgrades)
- **Time-to-death under focus fire: ~3–4 seconds** with no mistakes

### 2. No spawn protection
- Player spawned at (0, 180); enemies at ~480 range
- Enemies immediately CHASE + fire
- First bullets arrive before player can react

### 3. Collision damage can multi-hit same frame sequence
- Player↔enemy and player↔obstacle applied damage every physics tick while overlapping
- No per-source cooldown → sliding along a barrier or grinding an enemy melted HP

### 4. HP write race (CRITICAL BUG)
- `applyPlayerDamage` wrote `player.value`
- `checkCollisions` / `updatePlayerPhysics` ended with `player.value = p.copy()` using **stale local `p.health`**
- This could silently discard or restore HP inconsistently

### 5. Secondary sources
- Barrier bounce damage when speed > 3
- Fuel barrel explosion AOE (range 140, base 45)
- Obstacle crash damage

## Fixes applied (Phase A)

1. Central `applyPlayerDamage()` with log ring (last 5 events)
2. Logcat tag `DerbyDamage` for every hit / death
3. Spawn protection: **120 frames (~2s)** — blocks damage, not a permanent cheat
4. Collision damage cooldown: 18 frames; barrier cooldown: 24 frames
5. Enemy fire cooldown: **70–120 frames** (was 40–75)
6. Bullet hit radius 26 → 22 (slightly tighter)
7. HP sync after kinematics write (race fixed)
8. On-screen `DamageDebugOverlay` + Game Over shows death cause

## How to verify at runtime

1. Start Arena 01
2. Top-left overlay shows HP + last hits
3. `adb logcat -s DerbyDamage`
4. Die once → Game Over must show e.g. `DEATH: bullet / projectile by enemy_turret`
5. Confirm spawn: first ~2s no HP loss even if bullets land

## NOT done in Phase A (by design)

- Filament / GPU renderer (Phase B)
- Full arena rebuild, PBR materials, shadows
- Random damage number nerfs without logs
