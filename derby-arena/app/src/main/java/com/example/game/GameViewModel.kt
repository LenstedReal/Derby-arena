package com.example.game

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.game.data.DerbyRepository
import com.example.game.data.DerbySaveState
import com.example.game.engine3d.CameraMode3D
import com.example.game.systems.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.*

/**
 * GameViewModel: thin coordinator. Owns the fixed-step simulation loop and wires the systems:
 * INPUT → VehiclePhysics → Collision → EnemyAI → Combat → Damage → Loot → BattleRoyale → Vfx → Camera → GPU snapshot.
 * No gameplay rules live here; they live in the systems.
 */
class GameViewModel(
    private val repository: DerbyRepository,
    private val assetReader: ((String) -> String?)? = null
) : ViewModel() {

    // --- GPU frame snapshot types (immutable, produced once per simulation tick) ---
    data class GpuVehiclePose(
        val x: Float = 0f, val y: Float = 0f, val angle: Float = -90f, val steerAngle: Float = 0f,
        val turretAngle: Float = -90f, val speed: Float = 0f, val healthFrac: Float = 1f,
        val isDead: Boolean = false, val isWreckage: Boolean = false, val isBoosting: Boolean = false,
        val variant: Int = 0, val stunned: Boolean = false, val handbrake: Boolean = false
    )
    data class GpuBulletPose(val x: Float, val y: Float, val vx: Float, val vy: Float, val isPlayer: Boolean, val kind: String)
    data class GpuParticlePose(val x: Float, val y: Float, val size: Float, val life: Float, val type: String)
    data class GpuLootPose(val x: Float, val y: Float, val type: LootType, val active: Boolean, val bob: Float)
    data class GpuFrameData(
        val player: GpuVehiclePose = GpuVehiclePose(),
        val enemies: List<GpuVehiclePose> = emptyList(),
        val bullets: List<GpuBulletPose> = emptyList(),
        val particles: List<GpuParticlePose> = emptyList(),
        val loot: List<GpuLootPose> = emptyList(),
        val zone: ZoneState = ZoneState(),
        val screenShake: Float = 0f,
        val crowdExcitement: Float = 25f,
        val recoil: Float = 0f,
        val aimActive: Boolean = false,
        val matchActive: Boolean = false
    )

    enum class GameState { MENU, LEVEL_SELECT, PLAYING, PAUSED, LEVEL_COMPLETE, ARENA_01_COMPLETE, UPGRADES, GAME_OVER }

    private val _gameState = MutableStateFlow(GameState.MENU)
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    // Campaign
    val campaignSystem = CampaignSystem()
    val currentLevel = MutableStateFlow(1)
    val maxUnlockedLevel = MutableStateFlow(1)
    val totalStars = MutableStateFlow(0)
    val starsMap = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val currentLevelConfig = MutableStateFlow(campaignSystem.getLevelConfig(1))
    val stageTimeElapsed = MutableStateFlow(0f)
    val stageEarnedStars = MutableStateFlow(1)
    val stageScrapEarned = MutableStateFlow(0)
    val lastResult = MutableStateFlow<GameStateSystem.MatchResult?>(null)

    // Settings
    private val _theme = MutableStateFlow(DerbyTheme.TOXIC_SMOG)
    val theme: StateFlow<DerbyTheme> = _theme.asStateFlow()
    private val _fogDensity = MutableStateFlow(0.40f)
    val fogDensity: StateFlow<Float> = _fogDensity.asStateFlow()
    private val _controlScheme = MutableStateFlow("JOYSTICK")
    val controlScheme: StateFlow<String> = _controlScheme.asStateFlow()
    val controlType: StateFlow<String> = _controlScheme.asStateFlow()
    private val _steeringSensitivity = MutableStateFlow(1.0f)
    val steeringSensitivity: StateFlow<Float> = _steeringSensitivity.asStateFlow()
    private val _cameraMode = MutableStateFlow(CameraMode3D.CHASE_3D)
    val cameraMode: StateFlow<CameraMode3D> = _cameraMode.asStateFlow()
    private val _qualityPreset = MutableStateFlow(QualityPreset.HIGH)
    val qualityPreset: StateFlow<QualityPreset> = _qualityPreset.asStateFlow()
    val autoAim = MutableStateFlow(true)
    val isBoosting = MutableStateFlow(false)
    val isHandbraking = MutableStateFlow(false)

    // Reactive world state for Compose (HUD reads these; GPU reads the snapshot)
    val player = MutableStateFlow(PlayerCar())
    val enemies = MutableStateFlow<List<EnemyCar>>(emptyList())
    val obstacles = MutableStateFlow<List<Obstacle>>(emptyList())
    val bullets = MutableStateFlow<List<Bullet>>(emptyList())
    val particles = MutableStateFlow<List<Particle>>(emptyList())
    val scrapItems = MutableStateFlow<List<ScrapItem>>(emptyList())
    val spectators = MutableStateFlow<List<RobotSpectator>>(emptyList())
    val loot = MutableStateFlow<List<LootItem>>(emptyList())
    val zone = MutableStateFlow(ZoneState())
    val killFeed = MutableStateFlow<List<KillFeedEntry>>(emptyList())
    val countdown = MutableStateFlow(0f)
    val zoneAnnouncement = MutableStateFlow("")
    val wave = MutableStateFlow(1)
    val waveActiveEnemies = MutableStateFlow(5)
    val screenShake = MutableStateFlow(0f)
    val crowdExcitement = MutableStateFlow(25f)

    data class DamageEvent(val source: String, val amount: Float, val attacker: String, val collisionType: String, val hpAfter: Float, val frame: Long)
    private val damageLog = ArrayDeque<DamageEvent>(8)
    val lastDamageEvents = MutableStateFlow<List<DamageEvent>>(emptyList())
    val lastDeathCause = MutableStateFlow("none")

    private var dbHighScore = 0
    private var dbKills = 0
    private var dbGamesPlayed = 0

    // Systems
    private val weaponSystem = WeaponSystem()
    private val damageSystem = DamageSystem()
    private val physicsSystem = VehiclePhysicsSystem()
    private val collisionSystem = CollisionSystem()
    private val combatSystem = CombatSystem(weaponSystem, damageSystem)
    private val lootSystem = LootSystem(weaponSystem)
    private val battleRoyale = BattleRoyaleSystem()
    private val enemyAISystem = EnemyAISystem(combatSystem, lootSystem, battleRoyale)
    private val arenaSystem = ArenaSystem(assetReader)
    private val vfxSystem = VfxSystem()
    private val matchState = GameStateSystem()
    val audioSystem = AudioSystem()

    // Input (authoritative snapshot)
    private val input = PlayerInput()
    private var gameFrame: Long = 0
    private var gameJob: Job? = null
    private val events = ArrayList<CombatEvent>(64)
    private val killFeedBuffer = ArrayDeque<KillFeedEntry>()

    @Volatile private var gpuFrameSnapshot = GpuFrameData()
    fun getGpuFrameData(): GpuFrameData = gpuFrameSnapshot

    init {
        viewModelScope.launch {
            repository.saveState.collect { save ->
                save?.let {
                    dbHighScore = it.highScore
                    dbKills = it.totalKills
                    dbGamesPlayed = it.totalGamesPlayed
                    currentLevel.value = it.currentLevel.coerceAtLeast(1)
                    maxUnlockedLevel.value = it.maxUnlockedLevel.coerceAtLeast(1)
                    totalStars.value = it.totalStars
                    currentLevelConfig.value = campaignSystem.getLevelConfig(currentLevel.value)
                    if (it.starsMapJson.isNotBlank()) {
                        starsMap.value = it.starsMapJson.split(",").mapNotNull { entry ->
                            val parts = entry.split(":")
                            if (parts.size == 2) parts[0].toIntOrNull()?.let { l -> parts[1].toIntOrNull()?.let { s -> l to s } } else null
                        }.toMap()
                    }
                    player.value = player.value.copy(
                        engineLevel = it.engineLevel, armorLevel = it.armorLevel, turretLevel = it.turretLevel,
                        magnetLevel = it.magnetLevel, scrap = it.totalScrap
                    )
                }
            }
        }
        audioSystem.init()
    }

    // ---------------- settings API (used by menu / settings UI)
    fun setControlScheme(scheme: String) { _controlScheme.value = scheme }
    fun setControlType(type: String) { _controlScheme.value = type }
    fun setCameraMode(mode: CameraMode3D) { _cameraMode.value = mode }
    fun toggleCameraMode() { _cameraMode.value = if (_cameraMode.value == CameraMode3D.CHASE_3D) CameraMode3D.TACTICAL_3D else CameraMode3D.CHASE_3D }
    fun setTheme(newTheme: DerbyTheme) { _theme.value = newTheme }
    fun setFogDensity(density: Float) { _fogDensity.value = density.coerceIn(0f, 1f) }
    fun setSteeringSensitivity(sens: Float) { _steeringSensitivity.value = sens.coerceIn(0.5f, 2f) }
    fun setQuality(preset: QualityPreset) { _qualityPreset.value = preset }
    fun setAutoAim(enabled: Boolean) { autoAim.value = enabled }
    fun setNitro(active: Boolean) { isBoosting.value = active; input.nitro = active }
    fun setHandbrake(active: Boolean) { isHandbraking.value = active; input.handbrake = active }
    fun setBrake(active: Boolean) { input.brake = active }
    fun setFire(active: Boolean) { input.fire = active }

    /** Steering joystick / pedals. */
    fun updateInputs(throttle: Float, steer: Float, turretAngle: Float, isJoystickActive: Boolean, fire: Boolean) {
        input.throttle = throttle.coerceIn(-1f, 1f)
        input.steer = steer.coerceIn(-1f, 1f)
        if (isJoystickActive) input.aimAngleDeg = turretAngle
        input.aimActive = isJoystickActive
        if (fire) input.fire = true
    }

    /** Aim joystick (right stick): normalized vector in screen space (x right, y down). */
    fun updateAim(nx: Float, ny: Float, active: Boolean) {
        input.aimActive = active
        if (active && (abs(nx) > 0.05f || abs(ny) > 0.05f)) {
            // screen "up" is the car's forward direction
            val relDeg = Math.toDegrees(atan2(nx.toDouble(), -ny.toDouble())).toFloat()
            input.aimAngleDeg = player.value.angle + relDeg
        }
    }

    // ---------------- navigation
    fun pauseGame() {
        if (_gameState.value == GameState.PLAYING) {
            _gameState.value = GameState.PAUSED
            gameJob?.cancel()
            isBoosting.value = false; isHandbraking.value = false
            audioSystem.setEngine(0f, false, false)
        }
    }
    fun resumeGame() { if (_gameState.value == GameState.PAUSED) { _gameState.value = GameState.PLAYING; startGameLoop() } }
    fun goToMenu() { gameJob?.cancel(); _gameState.value = GameState.MENU; isBoosting.value = false; isHandbraking.value = false; matchState.toLobby(); audioSystem.setEngine(0f, false, false) }
    fun openLevelSelect() { gameJob?.cancel(); _gameState.value = GameState.LEVEL_SELECT }
    fun openGarage() { gameJob?.cancel(); _gameState.value = GameState.UPGRADES }
    fun resumeFromUpgrades() { _gameState.value = GameState.MENU }
    fun startCurrentLevel() = startLevel(currentLevel.value)
    fun nextLevel() = startLevel((currentLevel.value + 1).coerceAtMost(CampaignSystem.TOTAL_LEVELS))
    fun restartStage() = startLevel(currentLevel.value)
    fun restartArena() = startCurrentLevel()
    fun continueFromArenaComplete() = nextLevel()
    fun pauseForUpgrades() { pauseGame(); _gameState.value = GameState.UPGRADES }
    fun startGame() = startLevel(currentLevel.value)

    fun startLevel(levelNumber: Int) {
        val lvl = levelNumber.coerceIn(1, CampaignSystem.TOTAL_LEVELS)
        currentLevel.value = lvl
        val config = campaignSystem.getLevelConfig(lvl)
        currentLevelConfig.value = config
        _theme.value = config.theme
        viewModelScope.launch { dbGamesPlayed++; saveToDatabase() }

        wave.value = lvl
        waveActiveEnemies.value = config.enemyCount
        screenShake.value = 0f
        crowdExcitement.value = 25f
        isBoosting.value = false; isHandbraking.value = false
        input.nitro = false; input.handbrake = false; input.fire = false; input.aimActive = false
        gameFrame = 0
        stageTimeElapsed.value = 0f
        damageLog.clear(); lastDamageEvents.value = emptyList(); lastDeathCause.value = "none"
        killFeedBuffer.clear(); killFeed.value = emptyList()
        zoneAnnouncement.value = ""
        damageSystem.reset()

        val prev = player.value
        val maxHp = 100f + (prev.armorLevel - 1) * 30f
        player.value = PlayerCar(
            x = 0f, y = 180f, angle = -90f, health = maxHp, maxHealth = maxHp,
            armor = 10f + (prev.armorLevel - 1) * 8f, scrap = prev.scrap,
            engineLevel = prev.engineLevel, armorLevel = prev.armorLevel, turretLevel = prev.turretLevel, magnetLevel = prev.magnetLevel,
            armorPoints = 20f * (prev.armorLevel - 1), weapon = WeaponSystem.freshState(WeaponId.MACHINE_GUN)
        )
        combatSystem.clear(); vfxSystem.clear()
        bullets.value = emptyList(); particles.value = emptyList(); scrapItems.value = emptyList()
        obstacles.value = arenaSystem.spawnArena01Obstacles()
        enemies.value = enemyAISystem.spawnLevelEnemies(config)
        lootSystem.spawnInitial(18 + config.enemyCount * 2, obstacles.value, lvl)
        loot.value = lootSystem.items.map { it.copy() }
        battleRoyale.reset(1f + lvl / 450f)
        zone.value = battleRoyale.zone.copy()
        matchState.startCountdown(config.enemyCount)
        countdown.value = 3f
        updateGpuFrameDataSnapshot()
        _gameState.value = GameState.PLAYING
        startGameLoop()
    }

    private fun startGameLoop() {
        gameJob?.cancel()
        gameJob = viewModelScope.launch(Dispatchers.Default) {
            var last = System.nanoTime()
            var accumulator = 0.0
            val step = 1.0 / 60.0
            while (isActive && _gameState.value == GameState.PLAYING) {
                val now = System.nanoTime()
                accumulator += ((now - last) / 1_000_000_000.0).coerceAtMost(0.1)
                last = now
                var steps = 0
                while (accumulator >= step && steps < 4) {
                    updateGame(step.toFloat())
                    accumulator -= step
                    steps++
                }
                delay(4)
            }
        }
    }

    private fun updateGame(dt: Float) {
        gameFrame++
        events.clear()
        val p = player.value
        val enemyList = enemies.value
        val obstacleList = obstacles.value
        val preset = _qualityPreset.value
        val config = currentLevelConfig.value

        // 0. Countdown phase: world is live (camera / crowd animate) but nobody moves.
        if (matchState.phase == GameStateSystem.MatchPhase.COUNTDOWN) {
            if (matchState.tickCountdown(dt)) countdown.value = 0f else countdown.value = matchState.countdown
            vfxSystem.decayEffects(); vfxSystem.updateParticles(preset)
            publish(p, enemyList)
            return
        }
        matchState.tickMatch(dt)
        stageTimeElapsed.value = matchState.matchTime
        damageSystem.tick()
        collisionSystem.updateCooldowns()
        vfxSystem.decayEffects()

        // 1. Player physics (stunned vehicles lose throttle)
        val throttle = if (p.stunFrames > 0) 0f else (if (input.brake) -1f else input.throttle)
        physicsSystem.updatePlayer(p, throttle, input.steer, _steeringSensitivity.value, dt, input.nitro && p.stunFrames == 0, input.handbrake)
        p.inZone = battleRoyale.isInside(p.x, p.y)

        // 2. Enemy AI (drives, targets player + other enemies, loots, obeys zone)
        enemyAISystem.updateEnemies(enemyList, p, obstacleList, dt, config.enemySpeedMultiplier, config.enemyDamageMultiplier, events)

        // 3. Combat (aim / fire / projectiles / splash / stun)
        combatSystem.updatePlayerCombat(p, enemyList, input, autoAim.value, events)
        combatSystem.updateBullets(p, enemyList, obstacleList, gameFrame, events)

        // 4. Vehicle / obstacle collisions (impulses, crash damage, barrels)
        collisionSystem.checkCollisions(p, enemyList, obstacleList, spawnProtection = damageSystem.spawnProtectionFrames > 0, listener = collisionListener)

        // 5. Loot pickups (player + AI)
        lootSystem.update(obstacleList)
        lootSystem.collectForPlayer(p, events)
        for (e in enemyList) lootSystem.collectForEnemy(e, events)

        // 6. Battle royale zone
        if (battleRoyale.update(dt)) {
            zoneAnnouncement.value = if (battleRoyale.zone.finalCircle) "FINAL CIRCLE" else if (battleRoyale.zone.shrinking) "ZONE SHRINKING" else "ZONE LOCKED"
            audioSystem.play(AudioSystem.Sfx.ZONE_WARNING, gameFrame, 30)
        }
        val zd = battleRoyale.zoneDamage(p.x, p.y, dt)
        if (zd > 0f) {
            damageSystem.damagePlayer(p, zd, battleRoyale.zone.centerX, battleRoyale.zone.centerY, gameFrame, events, ignoreProtection = true)
            if (gameFrame % 30 == 0L) events.add(CombatEvent.ZoneDamage(p.x, p.y, zd))
            if (p.health <= 0f) lastDeathCause.value = "DEATH: outside safe zone"
        }
        for (e in enemyList) {
            val ezd = battleRoyale.zoneDamage(e.x, e.y, dt)
            if (ezd > 0f) damageSystem.damageEnemy(e, ezd, "zone", events)
        }

        // 7. Translate combat events into VFX / audio / HUD
        for (ev in events) handleEvent(ev, p, preset)
        vfxSystem.updateParticles(preset)
        if (abs(p.speed) > 3.5f && p.isHandbraking) vfxSystem.addDustBurst(p.x, p.y, 0f, 0f, 1, preset)

        // 8. Damage-state smoke / fire for every vehicle (stage 2+)
        if (gameFrame % 4 == 0L) {
            emitDamageSmoke(p.x, p.y, p.health / p.maxHealth, p.isDead, preset)
            for (e in enemyList) emitDamageSmoke(e.x, e.y, e.health / e.maxHealth, e.isDead, preset)
        }

        // 9. Win / loss
        when (matchState.evaluate(p, enemyList)) {
            GameStateSystem.MatchPhase.ELIMINATED -> finishMatch(false, p, enemyList, config)
            GameStateSystem.MatchPhase.WON -> finishMatch(true, p, enemyList, config)
            else -> Unit
        }

        // 10. Publish reactive state + GPU snapshot
        audioSystem.setEngine((abs(p.speed) / 14f).coerceIn(0f, 1f), input.nitro, true)
        publish(p, enemyList)
    }

    private fun emitDamageSmoke(x: Float, y: Float, hpFrac: Float, dead: Boolean, preset: QualityPreset) {
        val stage = damageSystem.stage(hpFrac, dead)
        if (stage >= 2) vfxSystem.addDustBurst(x, y, 0f, 0f, 0, preset)
        if (stage >= 3 || dead) vfxSystem.addMetalSparks(x, y, 0f, -1.5f, Color(0xFFFF6D00), preset)
    }

    private fun handleEvent(ev: CombatEvent, p: PlayerCar, preset: QualityPreset) {
        when (ev) {
            is CombatEvent.Muzzle -> {
                vfxSystem.addMuzzleFlash(ev.x, ev.y, ev.angleRad, if (ev.fromPlayer) Color(0xFFFFD54F) else Color(0xFFFF5252))
                if (ev.fromPlayer) audioSystem.play(if (ev.kind == "tracer" || ev.kind == "pellet") AudioSystem.Sfx.SHOT else AudioSystem.Sfx.HEAVY_SHOT, gameFrame, if (ev.kind == "tracer") 5 else 20)
            }
            is CombatEvent.Impact -> {
                vfxSystem.addMetalSparks(ev.x, ev.y, -ev.vx * 0.4f, -ev.vy * 0.4f, Color(0xFFFFD54F), preset)
                if (ev.heavy) audioSystem.play(AudioSystem.Sfx.IMPACT, gameFrame, 8)
            }
            is CombatEvent.Hit -> {
                vfxSystem.addFloatingText(ev.x, ev.y, "-${ev.amount.toInt()}", if (ev.fromPlayer) Color(0xFFFFEA00) else Color(0xFFFF8A65))
            }
            is CombatEvent.Kill -> {
                val victim = enemies.value.firstOrNull { "enemy:${it.id}" == ev.victimId }
                val victimName = victim?.name ?: "RIG"
                val killer = if (ev.killerId == "player") "YOU" else if (ev.killerId == "zone") "THE ZONE" else enemies.value.firstOrNull { "enemy:${it.id}" == ev.killerId }?.name ?: "RIG"
                if (ev.killerId == "player") {
                    p.kills++
                    p.score += 250
                    p.scrap += 30 + currentLevel.value / 4
                    dbKills++
                    vfxSystem.addFloatingText(ev.x, ev.y, "ELIMINATED +250", Color(0xFFFFEA00))
                }
                pushKillFeed("$killer  ▶  $victimName")
                vfxSystem.addExplosion(ev.x, ev.y, 48f, preset)
                vfxSystem.addCrowdExcitement(25f)
                vfxSystem.addScreenShake(6f)
                audioSystem.play(AudioSystem.Sfx.WRECK, gameFrame, 10)
            }
            is CombatEvent.Explosion -> { vfxSystem.addExplosion(ev.x, ev.y, ev.radius * 0.6f, preset); audioSystem.play(AudioSystem.Sfx.EXPLOSION, gameFrame, 12) }
            is CombatEvent.Pickup -> {
                if (ev.byPlayer) { vfxSystem.addFloatingText(ev.x, ev.y, "+${ev.type.name}", Color(0xFF69F0AE)); audioSystem.play(AudioSystem.Sfx.PICKUP, gameFrame, 6) }
            }
            is CombatEvent.PlayerDamaged -> {
                vfxSystem.addScreenShake((ev.amount * 0.4f).coerceIn(1f, 8f))
                recordDamage("projectile", ev.amount, "enemy", "combat", p.health)
            }
            is CombatEvent.ZoneDamage -> { vfxSystem.addFloatingText(ev.x, ev.y, "ZONE!", Color(0xFFFF1744)); recordDamage("zone", ev.amount, "zone", "zone", p.health) }
        }
    }

    private fun pushKillFeed(text: String) {
        if (killFeedBuffer.size >= 4) killFeedBuffer.removeFirst()
        killFeedBuffer.addLast(KillFeedEntry(text, gameFrame))
        killFeed.value = killFeedBuffer.toList()
    }

    private fun recordDamage(source: String, amount: Float, attacker: String, collisionType: String, hpAfter: Float) {
        if (damageLog.size >= 5) damageLog.removeFirst()
        damageLog.addLast(DamageEvent(source, amount, attacker, collisionType, hpAfter, gameFrame))
        lastDamageEvents.value = damageLog.toList()
        if (hpAfter <= 0f && lastDeathCause.value == "none") lastDeathCause.value = "DEATH: $source / $collisionType by $attacker (dmg=${amount.toInt()})"
    }

    private val collisionListener = object : CollisionSystem.CollisionListener {
        override fun onDamageApplied(source: String, amount: Float, attacker: String, collisionType: String, hpAfter: Float) = recordDamage(source, amount, attacker, collisionType, hpAfter)
        override fun onScreenShake(intensity: Float) = vfxSystem.addScreenShake(intensity)
        override fun onDustBurst(x: Float, y: Float, nx: Float, ny: Float, intensity: Int) = vfxSystem.addDustBurst(x, y, nx, ny, intensity, _qualityPreset.value)
        override fun onMetalSparks(x: Float, y: Float, vx: Float, vy: Float, color: Color) { vfxSystem.addMetalSparks(x, y, vx, vy, color, _qualityPreset.value); audioSystem.play(AudioSystem.Sfx.IMPACT, gameFrame, 6) }
        override fun onFloatingText(x: Float, y: Float, text: String, color: Color) = vfxSystem.addFloatingText(x, y, text, color)
        override fun onCheer(x: Float, y: Float, intensity: Int) = vfxSystem.addCrowdExcitement(intensity * 4f)
        override fun onFuelBarrelExplosion(x: Float, y: Float, radius: Float, damage: Float) {
            vfxSystem.addExplosion(x, y, 60f, _qualityPreset.value)
            audioSystem.play(AudioSystem.Sfx.EXPLOSION, gameFrame, 5)
            val p = player.value
            val pd = hypot(p.x - x, p.y - y)
            if (pd < radius) damageSystem.damagePlayer(p, damage * (radius - pd) / radius, x, y, gameFrame, events)
            for (e in enemies.value) {
                val ed = hypot(e.x - x, e.y - y)
                if (ed < radius) damageSystem.damageEnemy(e, damage * 1.6f * (radius - ed) / radius, "barrel", events)
            }
        }
    }

    private fun finishMatch(won: Boolean, p: PlayerCar, enemyList: List<EnemyCar>, config: CampaignSystem.LevelConfig) {
        gameJob?.cancel()
        audioSystem.setEngine(0f, false, false)
        val stars = if (won) campaignSystem.calculateStars(currentLevel.value, p.health / p.maxHealth, matchState.matchTime.toInt()) else 0
        val scrapEarned = if (won) config.scrapReward else 15 + p.kills * 10
        p.scrap += scrapEarned
        if (won) {
            p.score += 500 * stars
            stageEarnedStars.value = stars
            stageScrapEarned.value = scrapEarned
            maxUnlockedLevel.value = max(maxUnlockedLevel.value, (currentLevel.value + 1).coerceAtMost(CampaignSystem.TOTAL_LEVELS))
            val cur = starsMap.value.toMutableMap()
            if (stars > (cur[currentLevel.value] ?: 0)) { cur[currentLevel.value] = stars; starsMap.value = cur; totalStars.value = cur.values.sum() }
        } else {
            p.isDead = true
        }
        lastResult.value = GameStateSystem.MatchResult(
            won = won, placement = matchState.placement(enemyList), totalCombatants = matchState.totalCombatants,
            kills = p.kills, timeSeconds = matchState.matchTime.toInt(), damageTaken = damageSystem.playerDamageTakenThisMatch.toInt(),
            scrapEarned = scrapEarned, stars = stars
        )
        _gameState.value = if (won) (if (currentLevel.value == 1) GameState.ARENA_01_COMPLETE else GameState.LEVEL_COMPLETE) else GameState.GAME_OVER
        viewModelScope.launch { saveToDatabase() }
    }

    private fun publish(p: PlayerCar, enemyList: List<EnemyCar>) {
        player.value = p.copy()
        enemies.value = enemyList.map { it.copy() }
        bullets.value = combatSystem.getBullets().map { it.copy() }
        particles.value = vfxSystem.getParticles().map { it.copy() }
        loot.value = lootSystem.items.map { it.copy() }
        zone.value = battleRoyale.zone.copy()
        screenShake.value = vfxSystem.screenShake
        crowdExcitement.value = vfxSystem.crowdExcitement
        waveActiveEnemies.value = enemyList.count { !it.isDead && !it.isWreckage }
        updateGpuFrameDataSnapshot()
    }

    private fun updateGpuFrameDataSnapshot() {
        val p = player.value
        gpuFrameSnapshot = GpuFrameData(
            player = GpuVehiclePose(p.x, p.y, p.angle, p.steerAngle, p.turretAngle, p.speed, (p.health / p.maxHealth).coerceIn(0f, 1f),
                p.isDead || p.health <= 0f, p.isDead, isBoosting.value, 0, p.stunFrames > 0, p.isHandbraking),
            enemies = enemies.value.map { e ->
                GpuVehiclePose(e.x, e.y, e.angle, e.steerAngle, e.turretAngle, e.speed, (e.health / e.maxHealth).coerceIn(0f, 1f),
                    e.isDead, e.isWreckage, false, e.modelVariant, e.stunFrames > 0, false)
            },
            bullets = combatSystem.getBullets().map { GpuBulletPose(it.x, it.y, it.vx, it.vy, it.owner == "player", it.kind) },
            particles = vfxSystem.getParticles().map { GpuParticlePose(it.x, it.y, it.size, it.life, it.type) },
            loot = lootSystem.items.map { GpuLootPose(it.x, it.y, it.type, it.active, it.bobPhase) },
            zone = battleRoyale.zone.copy(),
            screenShake = vfxSystem.screenShake,
            crowdExcitement = vfxSystem.crowdExcitement,
            recoil = combatSystem.lastPlayerRecoil,
            aimActive = input.aimActive,
            matchActive = matchState.phase == GameStateSystem.MatchPhase.PLAYING
        )
    }

    // ---------------- garage
    private fun buy(cost: Int, level: Int, apply: (PlayerCar) -> Unit): Boolean {
        val p = player.value
        if (p.scrap < cost || level >= 5) return false
        p.scrap -= cost
        apply(p)
        player.value = p.copy()
        viewModelScope.launch { saveToDatabase() }
        return true
    }
    fun buyEngineUpgrade() = buy(player.value.engineLevel * 120, player.value.engineLevel) { it.engineLevel++ }
    fun buyArmorUpgrade() = buy(player.value.armorLevel * 120, player.value.armorLevel) { it.armorLevel++; it.maxHealth = 100f + (it.armorLevel - 1) * 30f; it.health = it.maxHealth; it.armor = 10f + (it.armorLevel - 1) * 8f }
    fun buyTurretUpgrade() = buy(player.value.turretLevel * 120, player.value.turretLevel) { it.turretLevel++ }
    fun buyMagnetUpgrade() = buy(player.value.magnetLevel * 120, player.value.magnetLevel) { it.magnetLevel++ }
    fun repairVehicle(): Boolean {
        val p = player.value
        if (p.scrap < 50 || p.health >= p.maxHealth) return false
        p.scrap -= 50; p.health = p.maxHealth
        player.value = p.copy()
        viewModelScope.launch { saveToDatabase() }
        return true
    }

    private suspend fun saveToDatabase() {
        val p = player.value
        repository.updateSaveState(
            DerbySaveState(
                highScore = max(dbHighScore, p.score), totalScrap = p.scrap,
                engineLevel = p.engineLevel, armorLevel = p.armorLevel, turretLevel = p.turretLevel, magnetLevel = p.magnetLevel,
                totalKills = dbKills, totalGamesPlayed = dbGamesPlayed,
                currentLevel = currentLevel.value, maxUnlockedLevel = maxUnlockedLevel.value, totalStars = totalStars.value,
                starsMapJson = starsMap.value.entries.joinToString(",") { "${it.key}:${it.value}" }
            )
        )
    }

    override fun onCleared() {
        gameJob?.cancel()
        audioSystem.release()
        super.onCleared()
    }
}

class GameViewModelFactory(
    private val application: android.app.Application,
    private val repository: DerbyRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(GameViewModel::class.java)) {
            val reader: (String) -> String? = { path ->
                try { application.assets.open(path).bufferedReader().use { it.readText() } } catch (_: Throwable) { null }
            }
            @Suppress("UNCHECKED_CAST")
            return GameViewModel(repository, reader) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
