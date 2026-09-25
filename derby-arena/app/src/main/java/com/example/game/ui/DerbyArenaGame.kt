package com.example.game.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.game.*
import com.example.game.data.DerbyDatabase
import com.example.game.data.DerbyRepository
import com.example.game.engine3d.CameraMode3D
import com.example.game.engine3d.gpu.GpuArenaViewport
import com.example.game.engine3d.gpu.RenderConfig
import com.example.game.engine3d.gpu.GpuBackendState
import java.util.*
import kotlin.math.*
import kotlin.random.Random

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DerbyArenaGame(
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val gameState by viewModel.gameState.collectAsStateWithLifecycle()
    val activeTheme by viewModel.theme.collectAsStateWithLifecycle()
    val fogDensity by viewModel.fogDensity.collectAsStateWithLifecycle()
    val controlType by viewModel.controlType.collectAsStateWithLifecycle()
    val steeringSensitivity by viewModel.steeringSensitivity.collectAsStateWithLifecycle()

    val pCar by viewModel.player.collectAsStateWithLifecycle()
    val listEnemies by viewModel.enemies.collectAsStateWithLifecycle()
    val listObs by viewModel.obstacles.collectAsStateWithLifecycle()
    val listBullets by viewModel.bullets.collectAsStateWithLifecycle()
    val listParticles by viewModel.particles.collectAsStateWithLifecycle()
    val listScrap by viewModel.scrapItems.collectAsStateWithLifecycle()
    val listSpectators by viewModel.spectators.collectAsStateWithLifecycle()

    val currentWave by viewModel.wave.collectAsStateWithLifecycle()
    val activeEnemiesCount by viewModel.waveActiveEnemies.collectAsStateWithLifecycle()
    val screenShake by viewModel.screenShake.collectAsStateWithLifecycle()
    val crowdExcitement by viewModel.crowdExcitement.collectAsStateWithLifecycle()

    val cameraMode by viewModel.cameraMode.collectAsStateWithLifecycle()
    val isBoosting by viewModel.isBoosting.collectAsStateWithLifecycle()
    val isHandbraking by viewModel.isHandbraking.collectAsStateWithLifecycle()
    val quality by viewModel.qualityPreset.collectAsStateWithLifecycle()
    val damageEvents by viewModel.lastDamageEvents.collectAsStateWithLifecycle()
    val deathCause by viewModel.lastDeathCause.collectAsStateWithLifecycle()
    var useGpuRenderer by remember { mutableStateOf(RenderConfig.USE_GPU_RENDERER) }
    var gpuFailMessage by remember { mutableStateOf<String?>(null) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF07080A))
    ) {
        when (gameState) {
            GameViewModel.GameState.MENU -> {
                Box(modifier = Modifier.fillMaxSize()) {
                    if (useGpuRenderer) {
                        GpuArenaViewport(
                            player = pCar,
                            enemies = listEnemies,
                            isBoosting = false,
                            isHandbraking = false,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                            onGpuFailed = { _ -> }
                        )
                    }
                    DerbyMenuScreen(
                        viewModel = viewModel,
                        activeTheme = activeTheme,
                        fogDensity = fogDensity,
                        controlType = controlType,
                        steeringSensitivity = steeringSensitivity,
                        pCar = pCar
                    )
                }
            }
            GameViewModel.GameState.PLAYING,
            GameViewModel.GameState.PAUSED -> {
                if (gameState == GameViewModel.GameState.PAUSED) {
                    BackHandler { viewModel.resumeGame() }
                }
                Box(modifier = Modifier.fillMaxSize()) {
                    if (useGpuRenderer) {
                        GpuArenaViewport(
                            player = pCar,
                            enemies = listEnemies,
                            isBoosting = isBoosting,
                            isHandbraking = isHandbraking,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                            onGpuFailed = { msg ->
                                gpuFailMessage = msg
                                useGpuRenderer = false
                            }
                        )
                        BattleHud(viewModel = viewModel)
                        if (gameState == GameViewModel.GameState.PLAYING) BattleControls(viewModel = viewModel)
                    } else {
                        Arena3DViewport(
                            player = pCar,
                            enemies = listEnemies,
                            obstacles = listObs,
                            bullets = listBullets,
                            particles = listParticles,
                            scrapItems = listScrap,
                            spectators = listSpectators,
                            theme = activeTheme,
                            fogDensity = fogDensity,
                            screenShake = screenShake,
                            cameraMode = cameraMode,
                            isBoosting = isBoosting,
                            isHandbraking = isHandbraking,
                            quality = quality,
                            viewModel = viewModel
                        )
                        DerbyDashboardHeader(
                            player = pCar,
                            wave = currentWave,
                            enemiesLeft = listEnemies.count { !it.isDead && !it.isWreckage },
                            crowdExcitement = crowdExcitement,
                            cameraMode = cameraMode,
                            viewModel = viewModel
                        )
                        DamageDebugOverlay(
                            events = damageEvents,
                            deathCause = deathCause,
                            hp = pCar.health,
                            maxHp = pCar.maxHealth
                        )
                        gpuFailMessage?.let { msg ->
                            Text(
                                text = "FALLBACK CANVAS · $msg",
                                color = Color.Black,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier
                                    .align(Alignment.TopCenter)
                                    .padding(top = 48.dp)
                                    .background(Color(0xFFFF9800), RoundedCornerShape(6.dp))
                                    .padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                    }

                    if (gameState == GameViewModel.GameState.PAUSED) {
                        PauseOverlay(viewModel = viewModel)
                    }
                }
            }
            GameViewModel.GameState.ARENA_01_COMPLETE -> {
                BackHandler { viewModel.goToMenu() }
                Box(modifier = Modifier.fillMaxSize()) {
                    if (useGpuRenderer) {
                        GpuArenaViewport(
                            player = pCar,
                            enemies = listEnemies,
                            isBoosting = false,
                            isHandbraking = false,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                            onGpuFailed = { _ -> }
                        )
                    } else {
                        Arena3DViewport(
                            player = pCar,
                            enemies = listEnemies,
                            obstacles = listObs,
                            bullets = listBullets,
                            particles = listParticles,
                            scrapItems = listScrap,
                            spectators = listSpectators,
                            theme = activeTheme,
                            fogDensity = fogDensity,
                            screenShake = 0f,
                            cameraMode = cameraMode,
                            isBoosting = false,
                            isHandbraking = false,
                            quality = quality,
                            viewModel = viewModel
                        )
                    }
                    ResultScreen(viewModel = viewModel, won = true)
                }
            }
            GameViewModel.GameState.LEVEL_COMPLETE -> {
                BackHandler { viewModel.goToMenu() }
                Box(modifier = Modifier.fillMaxSize()) {
                    if (useGpuRenderer) {
                        GpuArenaViewport(
                            player = pCar,
                            enemies = listEnemies,
                            isBoosting = false,
                            isHandbraking = false,
                            viewModel = viewModel,
                            modifier = Modifier.fillMaxSize(),
                            onGpuFailed = { _ -> }
                        )
                    }
                    ResultScreen(viewModel = viewModel, won = true)
                }
            }
            GameViewModel.GameState.LEVEL_SELECT -> {
                BackHandler { viewModel.goToMenu() }
                DerbyLevelSelectScreen(viewModel = viewModel)
            }
            GameViewModel.GameState.UPGRADES -> {
                BackHandler { viewModel.goToMenu() }
                DerbyUpgradesScreen(
                    player = pCar,
                    viewModel = viewModel,
                    wave = currentWave
                )
            }
            GameViewModel.GameState.GAME_OVER -> {
                BackHandler { viewModel.goToMenu() }
                Box(modifier = Modifier.fillMaxSize()) {
                    if (useGpuRenderer) {
                        GpuArenaViewport(player = pCar, enemies = listEnemies, isBoosting = false, isHandbraking = false, viewModel = viewModel, modifier = Modifier.fillMaxSize())
                    }
                    ResultScreen(viewModel = viewModel, won = false)
                }
            }
        }
    }
}

// ------------------- MAIN GAMEPLAY SIMULATION CANVAS -------------------
@Composable
fun ArenaSimulationCanvas(
    player: PlayerCar,
    enemies: List<EnemyCar>,
    obstacles: List<Obstacle>,
    bullets: List<Bullet>,
    particles: List<Particle>,
    scrapItems: List<ScrapItem>,
    spectators: List<RobotSpectator>,
    theme: DerbyTheme,
    fogDensity: Float,
    screenShake: Float,
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }

    // Driving input tracking inside canvas boundaries (for tap to shoot and steer wheel)
    var joystickOffset by remember { mutableStateOf(Offset.Zero) }
    var rightJoystickOffset by remember { mutableStateOf(Offset.Zero) }
    var isLeftActive by remember { mutableStateOf(false) }
    var isRightActive by remember { mutableStateOf(false) }

    // Steer & Throttle outputs
    val sensitivity = 100f

    LaunchedEffect(isLeftActive, joystickOffset) {
        if (!isLeftActive) {
            viewModel.updateInputs(0f, 0f, player.turretAngle, false, false)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // If manual shoot with touch, we can tap screen to point turret & shoot!
                detectDragGestures(
                    onDragStart = { offset ->
                        // Determine if drag was on right side of screen
                        if (offset.x > size.width / 2) {
                            isRightActive = true
                            rightJoystickOffset = Offset(
                                offset.x - size.width * 0.75f,
                                offset.y - size.height * 0.7f
                            )
                        } else {
                            isLeftActive = true
                            joystickOffset = Offset.Zero
                        }
                    },
                    onDragEnd = {
                        isLeftActive = false
                        isRightActive = false
                    },
                    onDragCancel = {
                        isLeftActive = false
                        isRightActive = false
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (isLeftActive && change.position.x < size.width / 2) {
                            joystickOffset += dragAmount
                            val tx = (joystickOffset.x / sensitivity).coerceIn(-1f, 1f)
                            val ty = -(joystickOffset.y / sensitivity).coerceIn(-1f, 1f) // invert Y for throttle
                            viewModel.updateInputs(ty, tx, player.turretAngle, false, false)
                        } else if (isRightActive || change.position.x > size.width / 2) {
                            isRightActive = true
                            val dx = change.position.x - (size.width * 0.75f)
                            val dy = change.position.y - (size.height * 0.7f)
                            val angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                            viewModel.updateInputs(
                                if (isLeftActive) -(joystickOffset.y / sensitivity).coerceIn(-1f, 1f) else 0f,
                                if (isLeftActive) (joystickOffset.x / sensitivity).coerceIn(-1f, 1f) else 0f,
                                angle,
                                true,
                                true
                            )
                        }
                    }
                )
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("arena_canvas")
        ) {
            canvasSize = size
            val width = size.width
            val height = size.height

            // SCREEN SHAKE: Apply random offset translation before anything else
            val shakeX = if (screenShake > 0f) (Random.nextFloat() * 2f - 1f) * screenShake else 0f
            val shakeY = if (screenShake > 0f) (Random.nextFloat() * 2f - 1f) * screenShake else 0f

            // CAMERA CENTERING: Translate camera to focus on Player Car
            val cameraX = width / 2f - player.x + shakeX
            val cameraY = height / 2f - player.y + shakeY

            withTransform({
                translate(left = cameraX, top = cameraY)
            }) {
                // 1. Draw Ground Base plate
                drawRect(
                    color = theme.groundColor,
                    topLeft = Offset(-1000f, -1000f),
                    size = Size(2000f, 2000f)
                )

                // 2. Draw Metallic Grid pattern
                val gridSpacing = 80f
                val gridLinesCount = 30
                for (i in -gridLinesCount..gridLinesCount) {
                    val pos = i * gridSpacing
                    // Vertical Lines
                    drawLine(
                        color = theme.gridColor,
                        start = Offset(pos, -1000f),
                        end = Offset(pos, 1000f),
                        strokeWidth = 1.5f
                    )
                    // Horizontal Lines
                    drawLine(
                        color = theme.gridColor,
                        start = Offset(-1000f, pos),
                        end = Offset(1000f, pos),
                        strokeWidth = 1.5f
                    )
                }

                // Draw tire marks/pavement scars
                drawPavementScars(this)

                // 3. Draw Persistent Skid marks particles (rendered first, below cars)
                particles.filter { it.type == "skid" }.forEach { p ->
                    drawCircle(
                        color = p.color,
                        radius = p.size,
                        center = Offset(p.x, p.y)
                    )
                }

                // 4. Draw Arena Boundary circular fence
                drawCircle(
                    color = Color.Black,
                    radius = 750f,
                    center = Offset.Zero,
                    style = Stroke(width = 24f)
                )
                // Draw yellow/black hazard strip around outer colosseum border
                drawCircle(
                    color = Color(0xFFFFC107),
                    radius = 746f,
                    center = Offset.Zero,
                    style = Stroke(width = 6f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 15f), 0f))
                )
                // Boundary spikes (visual decoration)
                drawCircularBoundarySpikes(this)

                // 5. Draw Robot Spectator Crowd (animated bouncing heads!)
                spectators.forEach { s ->
                    val rad = Math.toRadians(s.angle.toDouble()).toFloat()
                    val rx = cos(rad) * s.radius
                    val ry = sin(rad) * s.radius
                    drawRobotSpectatorHead(this, rx, ry, s)
                }

                // 6. Draw Scrap collectable parts
                scrapItems.forEach { s ->
                    // Outer glowing gear ring
                    drawCircle(
                        color = Color(0xFF00FFCC).copy(alpha = 0.35f),
                        radius = 12f + sin(System.currentTimeMillis() / 150f) * 3f,
                        center = Offset(s.x, s.y)
                    )
                    // Core metal nut
                    drawCircle(
                        color = Color(0xFFECEFF1),
                        radius = 5f,
                        center = Offset(s.x, s.y),
                        style = Stroke(width = 3.5f)
                    )
                }

                // 7. Draw Destructible Barriers / Obstacles
                obstacles.forEach { o ->
                    drawDestructibleBarrier(this, o)
                }

                // 8. Draw Bullets / Plasma trails
                bullets.forEach { b ->
                    val bulletColor = if (b.owner == "player") Color(0xFFFFEA00) else Color(0xFFFF1744)
                    // Bullet core
                    drawCircle(
                        color = Color.White,
                        radius = 3.5f,
                        center = Offset(b.x, b.y)
                    )
                    // Bullet light trail
                    drawLine(
                        color = bulletColor,
                        start = Offset(b.x, b.y),
                        end = Offset(b.x - b.vx * 1.5f, b.y - b.vy * 1.5f),
                        strokeWidth = 4.5f,
                        cap = StrokeCap.Round
                    )
                }

                // 9. Draw AI Enemies
                enemies.forEach { e ->
                    drawEnemyVehicle(this, e)
                }

                // 10. Draw Player Car (Rusty Post-Apocalyptic muscle car!)
                if (!player.isDead) {
                    drawPlayerVehicle(this, player)
                }

                // 11. Draw High-fidelity Explosion, Smoke, and Spark particles
                particles.filter { it.type != "skid" && it.type != "text" }.forEach { p ->
                    val alpha = p.life.coerceIn(0f, 1f)
                    drawCircle(
                        color = p.color.copy(alpha = alpha),
                        radius = p.size,
                        center = Offset(p.x, p.y)
                    )
                }

                // 12. Draw floating on-screen "CRUNCH!" or score texts in viewport coords
                particles.filter { it.type == "text" }.forEach { p ->
                    drawFloatingTextOnCanvas(this, p)
                }
            }

            // 13. CINEMATIC DYSTOPIAN FOG OVERLAY (Camera-independent drifting fog!)
            drawDystopianAtmosphericFog(this, theme, fogDensity)
        }
    }
}

// ----------------- DETAILED DRAWING HELPERS -----------------

private fun drawPavementScars(drawScope: DrawScope) {
    // Semi-permanent industrial scratches, tire tracks, rust stains
    val r = Random(42) // constant seed to keep tracks static!
    for (i in 0..25) {
        val sx = r.nextFloat() * 1200f - 600f
        val sy = r.nextFloat() * 1200f - 600f
        val ex = sx + r.nextFloat() * 200f - 100f
        val ey = sy + r.nextFloat() * 200f - 100f
        drawScope.drawLine(
            color = Color(0x0C000000),
            start = Offset(sx, sy),
            end = Offset(ex, ey),
            strokeWidth = 3f + r.nextFloat() * 8f
        )
        // oil spills
        drawScope.drawCircle(
            color = Color(0x13000000),
            radius = 10f + r.nextFloat() * 40f,
            center = Offset(sx + 50f, sy - 50f)
        )
    }
}

private fun drawCircularBoundarySpikes(drawScope: DrawScope) {
    // Boundary spikes along colosseum
    for (i in 0 until 60) {
        val angleRad = Math.toRadians(i * 360.0 / 60.0).toFloat()
        val sx = cos(angleRad) * 750f
        val sy = sin(angleRad) * 750f
        val ex = cos(angleRad) * 768f
        val ey = sin(angleRad) * 768f
        drawScope.drawLine(
            color = Color(0xFF37474F),
            start = Offset(sx, sy),
            end = Offset(ex, ey),
            strokeWidth = 6f
        )
    }
}

private fun drawRobotSpectatorHead(drawScope: DrawScope, rx: Float, ry: Float, s: RobotSpectator) {
    val size = 15f
    val bounceY = s.bounceOffset

    // Translate coordinates
    drawScope.withTransform({
        translate(left = rx, top = ry + bounceY)
    }) {
        // Antenna
        drawLine(
            color = Color(0xFF78909C),
            start = Offset(0f, -size),
            end = Offset(0f, -size - 8f),
            strokeWidth = 2.5f
        )
        drawCircle(
            color = s.eyeColor,
            radius = 3f,
            center = Offset(0f, -size - 10f)
        )

        // Metal Head dome
        drawRoundRect(
            color = Color(0xFF546E7A),
            topLeft = Offset(-size, -size),
            size = Size(size * 2, size * 2),
            cornerRadius = CornerRadius(5.dp.toPx(), 5.dp.toPx())
        )
        // Rusty collar
        drawRoundRect(
            color = Color(0xFF8D6E63),
            topLeft = Offset(-size * 1.1f, size * 0.8f),
            size = Size(size * 2.2f, 6f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )

        // Glowing visor / Neon Eyes
        drawRoundRect(
            color = Color(0xFF1A237E),
            topLeft = Offset(-size * 0.75f, -size * 0.4f),
            size = Size(size * 1.5f, size * 0.6f),
            cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
        )
        // Visor glowing pupils
        drawCircle(
            color = s.eyeColor,
            radius = 2f,
            center = Offset(-size * 0.35f, -size * 0.1f)
        )
        drawCircle(
            color = s.eyeColor,
            radius = 2f,
            center = Offset(size * 0.35f, -size * 0.1f)
        )

        // Little text bubble emote when excited
        if (s.bounceOffset < -5f && s.cheerEmoji.isNotEmpty()) {
            // Drawn small white cloud
            drawRoundRect(
                color = Color.White.copy(alpha = 0.9f),
                topLeft = Offset(-18f, -42f),
                size = Size(36f, 22f),
                cornerRadius = CornerRadius(6f, 6f)
            )
            // Draw tiny triangular spike to head
            val path = Path().apply {
                moveTo(-4f, -20f)
                lineTo(4f, -20f)
                lineTo(0f, -12f)
                close()
            }
            drawPath(path, color = Color.White.copy(alpha = 0.9f))
        }
    }
}

private fun drawDestructibleBarrier(drawScope: DrawScope, o: Obstacle) {
    when (o.type) {
        "concrete" -> {
            // Draw reinforced square slab
            val sizeVal = o.radius * 2f
            val rectOffset = Offset(o.x - o.radius, o.y - o.radius)
            // Base shadow
            drawScope.drawRoundRect(
                color = Color(0xFF212121),
                topLeft = rectOffset + Offset(4f, 4f),
                size = Size(sizeVal, sizeVal),
                cornerRadius = CornerRadius(4f, 4f)
            )
            // Concrete body
            drawScope.drawRoundRect(
                color = Color(0xFF78909C),
                topLeft = rectOffset,
                size = Size(sizeVal, sizeVal),
                cornerRadius = CornerRadius(4f, 4f)
            )
            // Inner cracked detail
            drawScope.drawRoundRect(
                color = Color(0xFF455A64),
                topLeft = rectOffset,
                size = Size(sizeVal, sizeVal),
                cornerRadius = CornerRadius(4f, 4f),
                style = Stroke(width = 4f)
            )
            // Fracture lines if damaged
            if (o.health < o.maxHealth * 0.8f) {
                drawScope.drawLine(
                    color = Color(0xFF263238),
                    start = Offset(o.x - o.radius * 0.6f, o.y - o.radius * 0.5f),
                    end = Offset(o.x + o.radius * 0.4f, o.y + o.radius * 0.4f),
                    strokeWidth = 3f
                )
            }
            if (o.health < o.maxHealth * 0.4f) {
                drawScope.drawLine(
                    color = Color(0xFF263238),
                    start = Offset(o.x + o.radius * 0.5f, o.y - o.radius * 0.3f),
                    end = Offset(o.x - o.radius * 0.5f, o.y + o.radius * 0.7f),
                    strokeWidth = 3f
                )
            }
        }
        "fuel_barrel" -> {
            // Steel circular fuel drum with hazard warnings
            drawScope.drawCircle(
                color = Color(0xFFBF360C), // rusty hazard orange-red
                radius = o.radius,
                center = Offset(o.x, o.y)
            )
            drawScope.drawCircle(
                color = Color(0xFFE64A19),
                radius = o.radius * 0.85f,
                center = Offset(o.x, o.y)
            )
            // Steel rim
            drawScope.drawCircle(
                color = Color(0xFF3E2723),
                radius = o.radius,
                center = Offset(o.x, o.y),
                style = Stroke(width = 3.5f)
            )
            // Center skull/fire hazard logo (represented as nuclear black circle & dots)
            drawScope.drawCircle(
                color = Color.Black,
                radius = 6f,
                center = Offset(o.x, o.y)
            )
            drawScope.drawCircle(
                color = Color.Black,
                radius = 3f,
                center = Offset(o.x - 10f, o.y)
            )
            drawScope.drawCircle(
                color = Color.Black,
                radius = 3f,
                center = Offset(o.x + 10f, o.y)
            )
        }
        "steel_box" -> {
            // Steel container crate
            val sizeVal = o.radius * 2f
            val rectOffset = Offset(o.x - o.radius, o.y - o.radius)
            drawScope.drawRoundRect(
                color = Color(0xFF37474F),
                topLeft = rectOffset,
                size = Size(sizeVal, sizeVal),
                cornerRadius = CornerRadius(5f, 5f)
            )
            // Metallic borders
            drawScope.drawRoundRect(
                color = Color(0xFF90A4AE),
                topLeft = rectOffset,
                size = Size(sizeVal, sizeVal),
                cornerRadius = CornerRadius(5f, 5f),
                style = Stroke(width = 5f)
            )
            // Diagonals
            drawScope.drawLine(
                color = Color(0xFF1C313A),
                start = rectOffset,
                end = rectOffset + Offset(sizeVal, sizeVal),
                strokeWidth = 4f
            )
            drawScope.drawLine(
                color = Color(0xFF1C313A),
                start = rectOffset + Offset(0f, sizeVal),
                end = rectOffset + Offset(sizeVal, 0f),
                strokeWidth = 4f
            )
        }
        else -> {
            // General rusty junk heap
            drawScope.drawCircle(
                color = Color(0xFF8D6E63),
                radius = o.radius,
                center = Offset(o.x, o.y)
            )
            drawScope.drawCircle(
                color = Color(0xFF4E342E),
                radius = o.radius * 0.9f,
                center = Offset(o.x, o.y),
                style = Stroke(width = 4f)
            )
        }
    }
}

private fun drawPlayerVehicle(drawScope: DrawScope, car: PlayerCar) {
    val px = car.x
    val py = car.y
    val headingRad = Math.toRadians(car.angle.toDouble()).toFloat()

    drawScope.withTransform({
        // Rotate entire coordinates system to match car angle
        rotate(degrees = car.angle, pivot = Offset(px, py))
    }) {
        // 1. DUAL HEAVY EXHAUST MANIFOLDS (Slowing along sides, matching reference photo!)
        val exhaustCol = Color(0xFF8D6E63) // rusted steel copper
        // Exhaust pipes on left side
        drawRoundRect(
            color = exhaustCol,
            topLeft = Offset(px - 28f, py - 15f),
            size = Size(6f, 40f),
            cornerRadius = CornerRadius(2f, 2f)
        )
        // Exhaust pipes on right side
        drawRoundRect(
            color = exhaustCol,
            topLeft = Offset(px + 22f, py - 15f),
            size = Size(6f, 40f),
            cornerRadius = CornerRadius(2f, 2f)
        )

        // 2. TREADED TIRES (Four corners, front tires steer dynamically!)
        val tireWidth = 9f
        val tireHeight = 22f
        val tireColor = Color(0xFF111215)
        
        // Rear left tire
        drawRoundRect(
            color = tireColor,
            topLeft = Offset(px - 24f, py + 18f),
            size = Size(tireWidth, tireHeight),
            cornerRadius = CornerRadius(3f, 3f)
        )
        // Rear right tire
        drawRoundRect(
            color = tireColor,
            topLeft = Offset(px + 15f, py + 18f),
            size = Size(tireWidth, tireHeight),
            cornerRadius = CornerRadius(3f, 3f)
        )

        // Front left tire (steer-rotated!)
        withTransform({
            rotate(degrees = car.steerAngle, pivot = Offset(px - 19.5f, py - 23f))
        }) {
            drawRoundRect(
                color = tireColor,
                topLeft = Offset(px - 24f, py - 34f),
                size = Size(tireWidth, tireHeight),
                cornerRadius = CornerRadius(3f, 3f)
            )
        }
        // Front right tire (steer-rotated!)
        withTransform({
            rotate(degrees = car.steerAngle, pivot = Offset(px + 19.5f, py - 23f))
        }) {
            drawRoundRect(
                color = tireColor,
                topLeft = Offset(px + 15f, py - 34f),
                size = Size(tireWidth, tireHeight),
                cornerRadius = CornerRadius(3f, 3f)
            )
        }

        // 3. FRONT REINFORCED BULL BARS (Heavy rusted steel teeth/cages, matching reference!)
        drawRoundRect(
            color = Color(0xFF263238),
            topLeft = Offset(px - 20f, py - 46f),
            size = Size(40f, 7f),
            cornerRadius = CornerRadius(2f, 2f)
        )
        // Prongs / Ram Teeth
        drawLine(color = Color(0xFF546E7A), start = Offset(px - 12f, py - 45f), end = Offset(px - 12f, py - 52f), strokeWidth = 3.5f)
        drawLine(color = Color(0xFF546E7A), start = Offset(px - 4f, py - 45f), end = Offset(px - 4f, py - 52f), strokeWidth = 3.5f)
        drawLine(color = Color(0xFF546E7A), start = Offset(px + 4f, py - 45f), end = Offset(px + 4f, py - 52f), strokeWidth = 3.5f)
        drawLine(color = Color(0xFF546E7A), start = Offset(px + 12f, py - 45f), end = Offset(px + 12f, py - 52f), strokeWidth = 3.5f)

        // 4. MAIN ARMORED MUSCLE CHASSIS (Rusted weathered white/grey body, matching reference!)
        drawRoundRect(
            color = Color(0xFFB0BEC5), // weathered grey body
            topLeft = Offset(px - 18f, py - 40f),
            size = Size(36f, 75f),
            cornerRadius = CornerRadius(7f, 7f)
        )
        // Rust spots/splotches overlay
        drawCircle(color = Color(0xFF8D6E63), radius = 2.5f, center = Offset(px - 12f, py - 18f))
        drawCircle(color = Color(0xFF8D6E63), radius = 4f, center = Offset(px + 10f, py + 12f))
        drawCircle(color = Color(0xFF795548), radius = 2f, center = Offset(px + 11f, py + 13f))
        drawCircle(color = Color(0xFF5D4037), radius = 1.8f, center = Offset(px - 14f, py + 22f))

        // Decal racing stripes on hood
        drawRoundRect(
            color = Color(0xFFD84315),
            topLeft = Offset(px - 10f, py - 38f),
            size = Size(4f, 18f)
        )
        drawRoundRect(
            color = Color(0xFFD84315),
            topLeft = Offset(px + 6f, py - 38f),
            size = Size(4f, 18f)
        )

        // 5. ENGINE BLOWER / INTAKE TUBES ON HOOD (Triple black cylinder nostrils, matching reference!)
        drawRoundRect(
            color = Color(0xFF212121),
            topLeft = Offset(px - 7f, py - 32f),
            size = Size(14f, 12f),
            cornerRadius = CornerRadius(2f, 2f)
        )
        // Triple chrome intake circles
        drawCircle(color = Color(0xFFECEFF1), radius = 2.2f, center = Offset(px - 4f, py - 30f))
        drawCircle(color = Color(0xFFECEFF1), radius = 2.2f, center = Offset(px, py - 30f))
        drawCircle(color = Color(0xFFECEFF1), radius = 2.2f, center = Offset(px + 4f, py - 30f))

        // 6. ARMORED WINDSHIELD GRATES
        drawRoundRect(
            color = Color(0xFF1A237E), // deep blue armored slot windows
            topLeft = Offset(px - 14f, py - 14f),
            size = Size(28f, 7f),
            cornerRadius = CornerRadius(1f, 1f)
        )
        // Steel window protection bars
        drawLine(color = Color.Black, start = Offset(px - 7f, py - 14f), end = Offset(px - 7f, py - 7f), strokeWidth = 2f)
        drawLine(color = Color.Black, start = Offset(px, py - 14f), end = Offset(px, py - 7f), strokeWidth = 2f)
        drawLine(color = Color.Black, start = Offset(px + 7f, py - 14f), end = Offset(px + 7f, py - 7f), strokeWidth = 2f)
    }

    // 7. ROOFTOP TURRET (Rotates independently of the chassis!)
    drawScope.withTransform({
        rotate(degrees = car.turretAngle, pivot = Offset(px, py))
    }) {
        // Red laser searchlight sight beam (premium visual!)
        drawScope.drawLine(
            color = Color(0xFFFF1744).copy(alpha = 0.45f),
            start = Offset(px, py),
            end = Offset(px + cos(Math.toRadians(0.0)).toFloat() * 180f, py + sin(Math.toRadians(0.0)).toFloat() * 180f),
            strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
        )

        // Turret metal mount
        drawScope.drawCircle(
            color = Color(0xFF455A64),
            radius = 11f,
            center = Offset(px, py)
        )
        // Gun barrel housing (dark rusted iron rect)
        drawScope.drawRoundRect(
            color = Color(0xFF263238),
            topLeft = Offset(px - 3f, py - 5f),
            size = Size(26f, 10f),
            cornerRadius = CornerRadius(2f, 2f)
        )
        // Barrel nozzle
        drawScope.drawRoundRect(
            color = Color(0xFF212121),
            topLeft = Offset(px + 23f, py - 2.5f),
            size = Size(5f, 5f),
            cornerRadius = CornerRadius(1f, 1f)
        )
    }
}

private fun drawEnemyVehicle(drawScope: DrawScope, e: EnemyCar) {
    val px = e.x
    val py = e.y

    drawScope.withTransform({
        rotate(degrees = e.angle, pivot = Offset(px, py))
    }) {
        // If wreckage, draw a black charcoaled split chassis
        if (e.isWreckage) {
            // Tires blacked out
            drawScope.drawRoundRect(color = Color.Black, topLeft = Offset(px - 22f, py - 32f), size = Size(8f, 18f))
            drawScope.drawRoundRect(color = Color.Black, topLeft = Offset(px + 14f, py - 32f), size = Size(8f, 18f))
            drawScope.drawRoundRect(color = Color.Black, topLeft = Offset(px - 22f, py + 14f), size = Size(8f, 18f))
            drawScope.drawRoundRect(color = Color.Black, topLeft = Offset(px + 14f, py + 14f), size = Size(8f, 18f))

            // Body charcoaled grey/black
            drawScope.drawRoundRect(
                color = Color(0xFF212121),
                topLeft = Offset(px - 16f, py - 35f),
                size = Size(32f, 70f),
                cornerRadius = CornerRadius(6f, 6f)
            )
            // Fire sparks floating on top of wreckage
            if (Random.nextInt(5) == 0) {
                drawScope.drawCircle(
                    color = Color(0xFFFF9800),
                    radius = 2f + Random.nextFloat() * 2f,
                    center = Offset(px + (Random.nextFloat() * 20f - 10f), py + (Random.nextFloat() * 40f - 20f))
                )
            }
        } else {
            // Active Enemy drawing
            val enemyTireCol = Color(0xFF1E2124)
            // Tires
            drawScope.drawRoundRect(color = enemyTireCol, topLeft = Offset(px - 22f, py - 32f), size = Size(8f, 18f))
            drawScope.drawRoundRect(color = enemyTireCol, topLeft = Offset(px + 14f, py - 32f), size = Size(8f, 18f))
            drawScope.drawRoundRect(color = enemyTireCol, topLeft = Offset(px - 22f, py + 14f), size = Size(8f, 18f))
            drawScope.drawRoundRect(color = enemyTireCol, topLeft = Offset(px + 14f, py + 14f), size = Size(8f, 18f))

            // Steel blade horns on front of heavy raiders!
            if (e.type == "Armored Juggernaut") {
                // Front dual horns
                val path = Path().apply {
                    moveTo(px - 12f, py - 35f)
                    lineTo(px - 18f, py - 48f)
                    lineTo(px - 6f, py - 35f)
                    close()
                    moveTo(px + 6f, py - 35f)
                    lineTo(px + 18f, py - 48f)
                    lineTo(px + 12f, py - 35f)
                    close()
                }
                drawScope.drawPath(path, color = Color(0xFF546E7A))
            }

            // Chassis
            drawScope.drawRoundRect(
                color = e.color,
                topLeft = Offset(px - 15f, py - 32f),
                size = Size(30f, 64f),
                cornerRadius = CornerRadius(5f, 5f)
            )
            // Rusty patch overlays
            drawScope.drawCircle(color = Color(0xFF5D4037), radius = 3f, center = Offset(px - 8f, py + 10f))
            drawScope.drawCircle(color = Color(0xFF5D4037), radius = 2.5f, center = Offset(px + 7f, py - 12f))

            // Cabin windows
            drawScope.drawRoundRect(
                color = Color(0xFF263238),
                topLeft = Offset(px - 11f, py - 10f),
                size = Size(22f, 12f),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
    }

    // Gun Turret for living enemies
    if (!e.isWreckage) {
        drawScope.withTransform({
            rotate(degrees = e.turretAngle, pivot = Offset(px, py))
        }) {
            // turret dome
            drawScope.drawCircle(
                color = Color(0xFF37474F),
                radius = 9f,
                center = Offset(px, py)
            )
            // barrel
            drawScope.drawRoundRect(
                color = Color.Black,
                topLeft = Offset(px - 2f, py - 4f),
                size = Size(20f, 8f),
                cornerRadius = CornerRadius(1f, 1f)
            )
        }
    }
}

private fun drawFloatingTextOnCanvas(drawScope: DrawScope, p: Particle) {
    // Canvas Text API is complex, we render as custom visual boxes (retro plates)
    // with glowing texts borders or shapes to guarantee 100% stable execution!
    val bgCol = Color.Black.copy(alpha = 0.85f)
    val textLen = p.customText.length * 7f
    val boxW = textLen.coerceAtLeast(40f)
    val boxH = 16f
    
    // Draw small rustic metal box framing the numeric text value
    drawScope.drawRoundRect(
        color = bgCol,
        topLeft = Offset(p.x - boxW/2, p.y - boxH/2),
        size = Size(boxW, boxH),
        cornerRadius = CornerRadius(3f, 3f)
    )
    drawScope.drawRoundRect(
        color = p.color,
        topLeft = Offset(p.x - boxW/2, p.y - boxH/2),
        size = Size(boxW, boxH),
        cornerRadius = CornerRadius(3f, 3f),
        style = Stroke(width = 1f)
    )
    
    // Draw miniature digital tick dashes inside to represent characters
    val seed = p.customText.hashCode()
    val r = Random(seed)
    val numDashes = (p.customText.length * 1.5f).toInt()
    for (i in 0 until numDashes) {
        val dx = p.x - boxW/2 + 4f + (i * (boxW - 8f) / numDashes)
        val dy = p.y + (r.nextFloat() * 6f - 3f)
        drawScope.drawCircle(
            color = p.color,
            radius = 1.2f,
            center = Offset(dx, dy)
        )
    }
}

private fun drawDystopianAtmosphericFog(drawScope: DrawScope, theme: DerbyTheme, density: Float) {
    if (density <= 0.05f) return
    val w = drawScope.size.width
    val h = drawScope.size.height

    // Simulating drifting toxic fog overlays via alpha-blended overlay bands
    val timeSeed = System.currentTimeMillis() / 4000f
    
    // Band 1
    val offset1 = (timeSeed * 25f) % w
    drawScope.drawRect(
        brush = Brush.horizontalGradient(
            colors = listOf(
                Color.Transparent,
                theme.fogColor.copy(alpha = 0.15f * density),
                theme.fogColor.copy(alpha = 0.25f * density),
                theme.fogColor.copy(alpha = 0.15f * density),
                Color.Transparent
            ),
            startX = offset1,
            endX = offset1 + w
        )
    )
    // Band 2
    val offset2 = ((timeSeed * -15f) % w) + w
    drawScope.drawRect(
        brush = Brush.verticalGradient(
            colors = listOf(
                theme.fogColor.copy(alpha = 0.08f * density),
                theme.fogColor.copy(alpha = 0.18f * density),
                theme.fogColor.copy(alpha = 0.08f * density)
            ),
            startY = (offset2) % h,
            endY = ((offset2) % h) + h * 0.5f
        )
    )

    // Toxic Ash embers floating
    val r = Random(123)
    for (i in 0..12) {
        val ex = (r.nextFloat() * w + timeSeed * 80f) % w
        val ey = r.nextFloat() * h
        drawScope.drawCircle(
            color = theme.ambientParticlesColor.copy(alpha = 0.45f),
            radius = 1.5f + r.nextFloat() * 2f,
            center = Offset(ex, ey)
        )
    }
}

// ------------------- HUD DASHBOARD HEADER LAYER -------------------
@Composable
fun DerbyDashboardHeader(
    player: PlayerCar,
    wave: Int,
    enemiesLeft: Int,
    crowdExcitement: Float,
    cameraMode: CameraMode3D = CameraMode3D.CHASE_3D,
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .background(Color(0xE60A0C10), shape = RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF37474F), shape = RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left: Hull Health Integrity
            Column(modifier = Modifier.weight(1.3f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Build,
                        contentDescription = "Hull integrity",
                        tint = Color(0xFFFF1744),
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "HULL INTEGRITY",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        text = "${player.health.toInt()}/${player.maxHealth.toInt()} HP",
                        color = if (player.health < player.maxHealth * 0.3f) Color(0xFFFF1744) else Color(0xFF00E676),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { (player.health / player.maxHealth).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp)),
                    color = if (player.health < player.maxHealth * 0.3f) Color(0xFFFF1744) else Color(0xFF00FFCC),
                    trackColor = Color(0xFF1E2124)
                )
            }

            Spacer(modifier = Modifier.width(20.dp))

            // Center: Wave counter & Enemies remaining
            Column(
                modifier = Modifier.weight(1.2f),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val isBoss = (wave % 10 == 0)
                Text(
                    text = if (isBoss) "💀 BOSS BÖLÜM $wave" else "BÖLÜM $wave",
                    color = if (isBoss) Color(0xFFFF1744) else Color(0xFFFFEA00),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "DÜŞMAN: $enemiesLeft / 5",
                    color = Color(0xFF00FFCC),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.width(20.dp))

            // Right: Collected Scrap & Action buttons
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = "🔩",
                        fontSize = 15.sp
                    )
                    Text(
                        text = "${player.scrap}",
                        color = Color(0xFF00FFCC),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.testTag("scrap_counter")
                    )
                }
                Text(
                    text = "SCORE: ${player.score}",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Bottom row: Crowd Excitement meter & Upgrades toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.weight(2f)
            ) {
                Text(
                    text = "🤖 CROWD ENERGY:",
                    color = Color(0xFF00E5FF),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(8.dp)
                        .background(Color(0xFF1E2124), shape = RoundedCornerShape(4.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(fraction = (crowdExcitement / 100f).coerceIn(0f, 1f))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF00E5FF), Color(0xFFFF1744))
                                ),
                                shape = RoundedCornerShape(4.dp)
                            )
                    )
                }
                Text(
                    text = "${crowdExcitement.toInt()}%",
                    color = Color.White,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Pause button
            Button(
                onClick = { viewModel.pauseGame() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF37474F),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                modifier = Modifier.height(36.dp)
            ) {
                Text("II", fontWeight = FontWeight.Black, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 3D Camera Toggle Button
            Button(
                onClick = { viewModel.toggleCameraMode() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF1E272C),
                    contentColor = Color(0xFF00E5FF)
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                modifier = Modifier
                    .height(28.dp)
                    .testTag("camera_mode_button")
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Switch Camera Mode",
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(3.dp))
                Text(
                    text = cameraMode.label,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { viewModel.pauseForUpgrades() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF37474F),
                    contentColor = Color.White
                ),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
                modifier = Modifier
                    .height(28.dp)
                    .testTag("upgrades_button")
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "Upgrades",
                    modifier = Modifier.size(12.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "UPGRADES",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ------------------- ON-SCREEN ADAPTIVE TOUCH CONTROLS -------------------
@Composable
fun DerbyTouchControls(
    controlType: String,
    viewModel: GameViewModel,
    player: PlayerCar,
    modifier: Modifier = Modifier
) {
    // We render driving joysticks overlaying the canvas at the bottom corners
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // LEFT CORNER: D-Pad / Driving inputs
            Box(
                modifier = Modifier
                    .size(150.dp)
                    .background(Color(0x330A0C10), shape = CircleShape)
                    .border(2.dp, Color(0x6637474F), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // Inside: virtual driving instructions
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Accelerate",
                        tint = Color.White.copy(alpha = 0.6f)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(30.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Steer Left",
                            tint = Color.White.copy(alpha = 0.6f)
                        )
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(Color(0x9900FFCC), shape = CircleShape)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Steer Right",
                            tint = Color.White.copy(alpha = 0.6f)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Reverse",
                        tint = Color.White.copy(alpha = 0.6f)
                    )
                }
                
                Text(
                    text = "DRIVE STICK",
                    fontSize = 8.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 14.dp)
                )
            }

            // RIGHT CORNER: Turret / Shoot trigger
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Auto targeting guide text
                Box(
                    modifier = Modifier
                        .background(Color(0xBF000000), shape = RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "TURRET: AUTO-TARGETING",
                        color = Color(0xFF00FFCC),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                // Drag right side of screen to manually point gun or press manual buttons
                Box(
                    modifier = Modifier
                        .size(130.dp)
                        .background(Color(0x330A0C10), shape = CircleShape)
                        .border(2.dp, Color(0x6637474F), shape = CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "MANUAL AIM\nDRAG HERE\nOR PRESS",
                        textAlign = TextAlign.Center,
                        fontSize = 9.sp,
                        color = Color.White.copy(alpha = 0.5f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// ------------------- UPGRADES / ROUND REPAIR PANEL -------------------
@Composable
fun DerbyUpgradesScreen(
    player: PlayerCar,
    viewModel: GameViewModel,
    wave: Int,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFA0A0D14)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .fillMaxHeight(0.85f)
                .background(Color(0xFF10141D), shape = RoundedCornerShape(16.dp))
                .border(2.dp, Color(0xFF37474F), shape = RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Header
            Text(
                text = "COLYSSEUM PIT STOP",
                color = Color(0xFFFFEA00),
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "UPGRADE YOUR WASTELAND DESTROYER BEFORE WAVE $wave",
                color = Color(0xFF90A4AE),
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(bottom = 20.dp)
            )

            // Current wallet
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .background(Color(0xFF1E2638), shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "AVAILABLE SCRAP:",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "🔩 ${player.scrap}",
                    color = Color(0xFF00FFCC),
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // List of Upgrades
            Box(modifier = Modifier.weight(1f)) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    item {
                        UpgradeItemRow(
                            title = "V8 Supercharged Engine",
                            desc = "Increases top speed & tire torque",
                            level = player.engineLevel,
                            maxLevel = 5,
                            cost = player.engineLevel * 100,
                            walletScrap = player.scrap,
                            onBuy = { viewModel.buyEngineUpgrade() },
                            testTag = "engine_upgrade"
                        )
                    }
                    item {
                        UpgradeItemRow(
                            title = "Reinforced Steel Armor",
                            desc = "Increases max hull integrity & reduces hits",
                            level = player.armorLevel,
                            maxLevel = 5,
                            cost = player.armorLevel * 100,
                            walletScrap = player.scrap,
                            onBuy = { viewModel.buyArmorUpgrade() },
                            testTag = "armor_upgrade"
                        )
                    }
                    item {
                        UpgradeItemRow(
                            title = "Dual Rooftop Gatling Gun",
                            desc = "Faster shooting and double projectile damage",
                            level = player.turretLevel,
                            maxLevel = 5,
                            cost = player.turretLevel * 100,
                            walletScrap = player.scrap,
                            onBuy = { viewModel.buyTurretUpgrade() },
                            testTag = "turret_upgrade"
                        )
                    }
                    item {
                        UpgradeItemRow(
                            title = "Scrap Magnetic Harvester",
                            desc = "Pulls scrap metals from further distances",
                            level = player.magnetLevel,
                            maxLevel = 5,
                            cost = player.magnetLevel * 100,
                            walletScrap = player.scrap,
                            onBuy = { viewModel.buyMagnetUpgrade() },
                            testTag = "magnet_upgrade"
                        )
                    }
                    item {
                        // Hull repair
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1E2124), shape = RoundedCornerShape(10.dp))
                                .border(1.dp, Color(0xFF37474F), shape = RoundedCornerShape(10.dp))
                                .padding(14.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Repair Hull damage",
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    text = "Heal structural dents back to 100% health",
                                    color = Color(0xFFB0BEC5),
                                    fontSize = 11.sp
                                )
                            }
                            Button(
                                onClick = { viewModel.repairVehicle() },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFFF1744)
                                ),
                                enabled = player.health < player.maxHealth && player.scrap >= 50,
                                modifier = Modifier.testTag("repair_upgrade")
                            ) {
                                Text(
                                    text = if (player.health >= player.maxHealth) "MAX HULL" else "HEAL (50)",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Launch Button
            Button(
                onClick = { viewModel.resumeFromUpgrades() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00FFCC),
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("resume_button")
            ) {
                Text(
                    text = "RE-ENTER THE ARENA",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

@Composable
fun UpgradeItemRow(
    title: String,
    desc: String,
    level: Int,
    maxLevel: Int,
    cost: Int,
    walletScrap: Int,
    onBuy: () -> Boolean,
    testTag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF161921), shape = RoundedCornerShape(10.dp))
            .border(1.dp, Color(0xFF263238), shape = RoundedCornerShape(10.dp))
            .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = desc,
                color = Color(0xFF90A4AE),
                fontSize = 11.sp,
                modifier = Modifier.padding(bottom = 6.dp)
            )

            // Tech level nodes indicator
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (i in 1..maxLevel) {
                    Box(
                        modifier = Modifier
                            .size(width = 16.dp, height = 6.dp)
                            .background(
                                color = if (i <= level) Color(0xFF00FFCC) else Color(0xFF37474F),
                                shape = RoundedCornerShape(1.dp)
                            )
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(16.dp))

        if (level >= maxLevel) {
            Text(
                text = "MAX LEVEL",
                color = Color(0xFF90A4AE),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
        } else {
            Button(
                onClick = { onBuy() },
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF00FFCC),
                    contentColor = Color.Black
                ),
                enabled = walletScrap >= cost,
                modifier = Modifier.testTag(testTag)
            ) {
                Text(
                    text = "🔩 $cost",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
    }
}

// ------------------- COLYSSEUM MAIN MENU SCREEN -------------------
@Composable
fun DerbyMenuScreen(
    viewModel: GameViewModel,
    activeTheme: DerbyTheme,
    fogDensity: Float,
    controlType: String,
    steeringSensitivity: Float,
    pCar: PlayerCar,
    modifier: Modifier = Modifier
) {
    val currentLevel by viewModel.currentLevel.collectAsStateWithLifecycle()
    val maxUnlocked by viewModel.maxUnlockedLevel.collectAsStateWithLifecycle()
    val totalStars by viewModel.totalStars.collectAsStateWithLifecycle()
    val controlScheme by viewModel.controlScheme.collectAsStateWithLifecycle()
    val quality by viewModel.qualityPreset.collectAsStateWithLifecycle()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xB307090E),
                        Color(0xCC10141E),
                        Color(0xD907080D)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.96f)
                .padding(vertical = 12.dp, horizontal = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // 1. AAA Dystopian Game Header
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 10.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "⚡",
                        fontSize = 18.sp
                    )
                    Text(
                        text = "FILAMENT 3D PBR ENGINE",
                        color = Color(0xFF00FFCC),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "⚡",
                        fontSize = 18.sp
                    )
                }

                Text(
                    text = "DERBY ARENA",
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.ExtraBold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 3.sp
                )

                Text(
                    text = "450 BÖLÜM DEMOLITION ŞAMPİYONASI",
                    color = Color(0xFFFF1744),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.sp
                )
            }

            // 2. Career Quick Stats Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF121722).copy(alpha = 0.85f), RoundedCornerShape(10.dp))
                    .border(1.dp, Color(0xFF263238), RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "BÖLÜM", color = Color(0xFF90A4AE), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text(text = "$currentLevel / 450", color = Color(0xFFFFEA00), fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "YILDIZ", color = Color(0xFF90A4AE), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text(text = "★ $totalStars", color = Color(0xFFFFD700), fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "HURDA", color = Color(0xFF90A4AE), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text(text = "🔩 ${pCar.scrap}", color = Color(0xFF00FFCC), fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "SKOR", color = Color(0xFF90A4AE), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
                    Text(text = "${pCar.score}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
                }
            }

            // 3. Central: Live 3D Arena Viewport Showcase Badge
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(80.dp)
                    .background(Color(0x8810141D), shape = RoundedCornerShape(12.dp))
                    .border(1.5.dp, Color(0xFF00FFCC).copy(alpha = 0.7f), shape = RoundedCornerShape(12.dp))
                    .padding(10.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "🏟️ ROMA KOLEZYUMU DERBY ARENASI",
                        color = Color(0xFF00FFCC),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "V8 RUSTY MUSCLE CAR · 5 DÜŞMAN HURDA AKINCI · CANLI PBR IŞIK & GÖLGE",
                        color = Color(0xFFFFEA00),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        textAlign = TextAlign.Center
                    )
                }
            }

            // 4. Primary Play & Championship Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // PLAY CURRENT LEVEL BUTTON
                Button(
                    onClick = { viewModel.startCurrentLevel() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF1744),
                        contentColor = Color.White
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("start_game_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "Play",
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "OYNA · BÖLÜM $currentLevel",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 450 LEVELS BUTTON
                    Button(
                        onClick = { viewModel.openLevelSelect() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF00FFCC),
                            contentColor = Color.Black
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("level_select_button")
                    ) {
                        Text(
                            text = "🏆 450 BÖLÜM",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // GARAGE / UPGRADES BUTTON
                    Button(
                        onClick = { viewModel.openGarage() },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Color(0xFF263238),
                            contentColor = Color.White
                        ),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .testTag("garage_button")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Garage",
                            modifier = Modifier.size(16.dp),
                            tint = Color(0xFFFFEA00)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "GARAJ & GELİŞTİR",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            // 5. Controls & Environment Settings
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF10141D), shape = RoundedCornerShape(12.dp))
                    .border(1.dp, Color(0xFF263238), shape = RoundedCornerShape(12.dp))
                    .padding(12.dp)
            ) {
                // Control Type Switcher
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "KONTROL SİSTEMİ:",
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFF1E2638))
                            .border(1.dp, Color(0xFF00FFCC), RoundedCornerShape(6.dp))
                            .clickable {
                                val next = if (controlScheme == "PEDALS_ARROWS") "JOYSTICK" else "PEDALS_ARROWS"
                                viewModel.setControlScheme(next)
                            }
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (controlScheme == "PEDALS_ARROWS") "🎮 PEDALLAR & OKLAR" else "🕹️ ANALOG JOYSTICK",
                            color = Color(0xFF00FFCC),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Arena Theme Selector
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    DerbyTheme.values().forEach { t ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(32.dp)
                                .background(
                                    color = if (activeTheme == t) Color(0xFF1E2638) else Color(0xFF151821),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .border(
                                    width = if (activeTheme == t) 1.5.dp else 1.dp,
                                    color = if (activeTheme == t) Color(0xFF00FFCC) else Color(0xFF37474F),
                                    shape = RoundedCornerShape(6.dp)
                                )
                                .clickable { viewModel.setTheme(t) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = t.title,
                                color = if (activeTheme == t) Color(0xFF00FFCC) else Color.White,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }
        }
    }
}

// ------------------- GAME OVER SCREEN -------------------
@Composable
fun DerbyGameOverScreen(
    player: PlayerCar,
    viewModel: GameViewModel,
    deathCause: String = "none",
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xEE050508)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.85f)
                .background(Color(0xFF10141D), shape = RoundedCornerShape(16.dp))
                .border(2.dp, Color(0xFFFF1744), shape = RoundedCornerShape(16.dp))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Destroyed",
                tint = Color(0xFFFF1744),
                modifier = Modifier.size(64.dp)
            )

            Text(
                text = "VEHICLE DESTROYED",
                color = Color(0xFFFF1744),
                fontSize = 24.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            // deathCause collected at top of DerbyArenaGame — pass via player score screen context
            

            Text(
                text = "Your muscle car was pulverized to scrap metal. The spectator robots cheered at your spectacular explosion!",
                color = Color(0xFF90A4AE),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )
            if (deathCause != "none") {
                Text(
                    text = deathCause,
                    color = Color(0xFFFFEA00),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .padding(top = 8.dp)
                        .background(Color(0xFF1A0000), RoundedCornerShape(6.dp))
                        .padding(8.dp)
                )
            }

            // High scores table
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF161921), shape = RoundedCornerShape(8.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "FINAL SCORE:", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(text = "${player.score}", color = Color(0xFFFFEA00), fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(text = "SCRAP EARNED:", color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                    Text(text = "🔩 ${player.scrap}", color = Color(0xFF00FFCC), fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = { viewModel.startGame() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFFF1744)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("restart_button")
                ) {
                    Text(
                        text = "REDEPLOY CAR",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }

                Button(
                    onClick = { viewModel.goToMenu() }, // simple reset trigger to go back
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF37474F)
                    ),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("menu_button")
                ) {
                    Text(
                        text = "RETURN PIT",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}


@Composable
fun PauseOverlay(viewModel: GameViewModel) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xAA000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(Color(0xFF12151A), shape = RoundedCornerShape(16.dp))
                .border(1.dp, Color(0xFF00FFCC), shape = RoundedCornerShape(16.dp))
                .padding(28.dp)
        ) {
            Text(
                text = "PAUSED",
                color = Color.White,
                fontSize = 28.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(20.dp))
            Button(
                onClick = { viewModel.resumeGame() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
            ) {
                Text("RESUME", color = Color.Black, fontWeight = FontWeight.Black)
            }
            Spacer(modifier = Modifier.height(10.dp))
            Button(
                onClick = { viewModel.restartArena() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF455A64))
            ) {
                Text("RESTART", color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(onClick = { viewModel.goToMenu() }) {
                Text("MENU", color = Color(0xFF90A4AE))
            }
        }
    }
}

@Composable
fun Arena01CompleteOverlay(
    player: PlayerCar,
    viewModel: GameViewModel
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC000000)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .background(Color(0xFF0D1117), shape = RoundedCornerShape(16.dp))
                .border(2.dp, Color(0xFF00FFCC), shape = RoundedCornerShape(16.dp))
                .padding(32.dp)
        ) {
            Text(
                text = "ARENA 01",
                color = Color(0xFF00FFCC),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "CLEARED",
                color = Color.White,
                fontSize = 32.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "SCORE  ${player.score}",
                color = Color(0xFFFFEA00),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "SCRAP  ${player.scrap}",
                color = Color(0xFF00FFCC),
                fontSize = 14.sp,
                fontFamily = FontFamily.Monospace
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = { viewModel.continueFromArenaComplete() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E5FF))
            ) {
                Text("UPGRADES", color = Color.Black, fontWeight = FontWeight.Black)
            }
            Spacer(modifier = Modifier.height(10.dp))
            TextButton(onClick = { viewModel.goToMenu() }) {
                Text("MENU", color = Color(0xFF90A4AE))
            }
        }
    }
}


@Composable
fun DamageDebugOverlay(
    events: List<GameViewModel.DamageEvent>,
    deathCause: String,
    hp: Float,
    maxHp: Float,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 100.dp, start = 8.dp, end = 8.dp),
        horizontalAlignment = Alignment.Start
    ) {
        Text(
            text = "HP ${hp.toInt()}/${maxHp.toInt()}",
            color = if (hp < maxHp * 0.3f) Color(0xFFFF1744) else Color(0xFF00E676),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .background(Color(0x99000000), RoundedCornerShape(4.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        )
        events.takeLast(5).forEach { e ->
            Text(
                text = "${e.source}/${e.collisionType} -${e.amount.toInt()} by ${e.attacker} →${e.hpAfter.toInt()}",
                color = Color(0xFFFFEA00),
                fontSize = 8.sp,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .background(Color(0x88000000), RoundedCornerShape(2.dp))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            )
        }
        if (deathCause != "none") {
            Text(
                text = deathCause,
                color = Color(0xFFFF1744),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .background(Color(0xCC000000), RoundedCornerShape(4.dp))
                    .padding(4.dp)
            )
        }
    }
}
