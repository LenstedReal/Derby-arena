package com.example.game.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.*
import com.example.game.engine3d.CameraMode3D
import com.example.game.engine3d.Renderer3D
import kotlin.math.*

/**
 * Full 3D Colosseum Arena Viewport with perspective rendering, dynamic chase camera,
 * tactical colosseum minimap radar, 3D speedometer, and high-octane competitive controls.
 */
@Composable
fun Arena3DViewport(
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
    cameraMode: CameraMode3D,
    isBoosting: Boolean,
    isHandbraking: Boolean,
    quality: QualityPreset = QualityPreset.HIGH,
    viewModel: GameViewModel,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val renderer3D = remember { Renderer3D() }

    // Driving input tracking
    var joystickOffset by remember { mutableStateOf(Offset.Zero) }
    var isLeftActive by remember { mutableStateOf(false) }
    var isRightAimActive by remember { mutableStateOf(false) }

    val sensitivity = 100f
    val deadzone = 12f

    // Return joystick to center when released
    LaunchedEffect(isLeftActive) {
        if (!isLeftActive) {
            joystickOffset = Offset.Zero
            viewModel.updateInputs(0f, 0f, player.turretAngle, false, false)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (offset.x < size.width * 0.45f) {
                            isLeftActive = true
                            joystickOffset = Offset.Zero
                        } else if (offset.x > size.width * 0.6f && offset.y < size.height * 0.7f) {
                            isRightAimActive = true
                        }
                    },
                    onDragEnd = {
                        isLeftActive = false
                        isRightAimActive = false
                        joystickOffset = Offset.Zero
                        viewModel.updateInputs(0f, 0f, player.turretAngle, false, false)
                    },
                    onDragCancel = {
                        isLeftActive = false
                        isRightAimActive = false
                        joystickOffset = Offset.Zero
                        viewModel.updateInputs(0f, 0f, player.turretAngle, false, false)
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        if (isLeftActive && change.position.x < size.width * 0.5f) {
                            joystickOffset += dragAmount
                            // Clamp max travel
                            val maxTravel = 80f
                            val clampedX = joystickOffset.x.coerceIn(-maxTravel, maxTravel)
                            val clampedY = joystickOffset.y.coerceIn(-maxTravel, maxTravel)
                            joystickOffset = Offset(clampedX, clampedY)

                            // Apply deadzone
                            val mag = hypot(clampedX, clampedY)
                            val tx: Float
                            val ty: Float
                            if (mag < deadzone) {
                                tx = 0f
                                ty = 0f
                            } else {
                                val scale = ((mag - deadzone) / (maxTravel - deadzone)).coerceIn(0f, 1f)
                                tx = (clampedX / mag) * scale
                                ty = -(clampedY / mag) * scale
                            }
                            viewModel.updateInputs(ty, tx, player.turretAngle, false, false)
                        } else if (isRightAimActive || change.position.x > size.width * 0.6f) {
                            val dx = change.position.x - (size.width * 0.8f)
                            val dy = change.position.y - (size.height * 0.5f)
                            val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                            val mag = hypot(joystickOffset.x, joystickOffset.y)
                            val tx: Float
                            val ty: Float
                            if (!isLeftActive || mag < deadzone) {
                                tx = 0f
                                ty = 0f
                            } else {
                                val scale = ((mag - deadzone) / (80f - deadzone)).coerceIn(0f, 1f)
                                tx = (joystickOffset.x / mag) * scale
                                ty = -(joystickOffset.y / mag) * scale
                            }
                            viewModel.updateInputs(ty, tx, angle, true, true)
                        }
                    }
                )
            }
    ) {
        // 1. The 3D Engine Perspective Viewport
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .testTag("arena_canvas_3d")
        ) {
            renderer3D.render(
                drawScope = this,
                textMeasurer = textMeasurer,
                player = player,
                enemies = enemies,
                obstacles = obstacles,
                bullets = bullets,
                particles = particles,
                scrapItems = scrapItems,
                spectators = spectators,
                theme = theme,
                fogDensity = fogDensity,
                screenShake = screenShake,
                cameraMode = cameraMode,
                isBoosting = isBoosting,
                isHandbraking = isHandbraking,
                quality = quality
            )
        }

        // 2. Tactical 360-Degree Colosseum Radar Minimap (Top Right)
        TacticalRadarMinimap(
            player = player,
            enemies = enemies,
            obstacles = obstacles,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 110.dp, end = 16.dp)
        )

        // 3. Competitive 3D Speedometer & Nitro Boost Gauge (Bottom Center)
        SpeedometerHUD(
            player = player,
            isBoosting = isBoosting,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 24.dp)
        )

        // 4. On-Screen Driving Controls
        ArenaControlOverlay(
            viewModel = viewModel,
            player = player,
            isBoosting = isBoosting,
            isHandbraking = isHandbraking,
            joystickOffset = joystickOffset,
            isLeftActive = isLeftActive,
            modifier = Modifier.fillMaxSize()
        )
    }
}

/**
 * 360-degree tactical Colosseum Radar showing real-time enemy positions and barriers.
 */
@Composable
fun TacticalRadarMinimap(
    player: PlayerCar,
    enemies: List<EnemyCar>,
    obstacles: List<Obstacle>,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(90.dp)
            .background(Color(0xCC0A0D14), shape = CircleShape)
            .border(2.dp, Color(0xFF00FFCC).copy(alpha = 0.6f), shape = CircleShape)
            .testTag("tactical_radar")
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val radarRadius = size.width / 2f - 4f
            val worldScale = radarRadius / ArenaConstants.ARENA_RADIUS

            // Sweep ring
            drawCircle(
                color = Color(0x3300FFCC),
                radius = radarRadius * 0.5f,
                center = center,
                style = Stroke(width = 1f)
            )

            // Obstacles
            for (obs in obstacles) {
                if (!obs.isDestroyed) {
                    val ox = center.x + obs.x * worldScale
                    val oy = center.y + obs.y * worldScale
                    val col = if (obs.isExplosive) Color(0xFFFF9800) else Color(0xFF78909C)
                    drawCircle(color = col, radius = 2f, center = Offset(ox, oy))
                }
            }

            // Enemies
            for (enemy in enemies) {
                if (!enemy.isDead) {
                    val ex = center.x + enemy.x * worldScale
                    val ey = center.y + enemy.y * worldScale
                    drawCircle(color = Color(0xFFFF1744), radius = 3.5f, center = Offset(ex, ey))
                }
            }

            // Player dot + heading indicator
            val px = center.x + player.x * worldScale
            val py = center.y + player.y * worldScale
            val rad = Math.toRadians(player.angle.toDouble()).toFloat()
            val hx = px + cos(rad) * 8f
            val hy = py + sin(rad) * 8f

            drawCircle(color = Color(0xFF00FFCC), radius = 4f, center = Offset(px, py))
            drawLine(
                color = Color.White,
                start = Offset(px, py),
                end = Offset(hx, hy),
                strokeWidth = 2.5f
            )
        }
    }
}

/**
 * Competitive Analog & Digital Speedometer with Nitro Fuel Gauge
 */
@Composable
fun SpeedometerHUD(
    player: PlayerCar,
    isBoosting: Boolean,
    modifier: Modifier = Modifier
) {
    val speedKmh = (abs(player.speed) * 12.5f).toInt()

    Column(
        modifier = modifier
            .background(Color(0xCC0D1117), shape = RoundedCornerShape(12.dp))
            .border(1.dp, Color(0xFF37474F), shape = RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "$speedKmh",
                color = if (isBoosting) Color(0xFF00E5FF) else Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Black,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = "KM/H",
                color = Color(0xFF90A4AE),
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            if (player.isHandbraking) {
                Box(
                    modifier = Modifier
                        .background(Color(0xFFFF9800), shape = RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = "DRIFT",
                        color = Color.Black,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Nitro Fuel Bar
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "⚡ NITRO",
                color = Color(0xFF00E5FF),
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace
            )
            Box(
                modifier = Modifier
                    .width(80.dp)
                    .height(6.dp)
                    .background(Color(0xFF1E2124), shape = RoundedCornerShape(3.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction = (player.nitro / player.maxNitro).coerceIn(0f, 1f))
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(0xFF00B0FF), Color(0xFF00E5FF))
                            ),
                            shape = RoundedCornerShape(3.dp)
                        )
                )
            }
        }
    }
}

/**
 * On-Screen Driving Controller: Steering stick, Turbo Nitro HOLD, Handbrake HOLD.
 */
@Composable
fun ArenaControlOverlay(
    viewModel: GameViewModel,
    player: PlayerCar,
    isBoosting: Boolean,
    isHandbraking: Boolean,
    joystickOffset: Offset = Offset.Zero,
    isLeftActive: Boolean = false,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
        ) {
            // LEFT: Virtual Driving Joystick Pad
            val knobOffsetX = if (isLeftActive) (joystickOffset.x * 0.4f).coerceIn(-40f, 40f) else 0f
            val knobOffsetY = if (isLeftActive) (joystickOffset.y * 0.4f).coerceIn(-40f, 40f) else 0f

            Box(
                modifier = Modifier
                    .size(130.dp)
                    .background(Color(0x440A0C10), shape = CircleShape)
                    .border(2.dp, if (isLeftActive) Color(0xFF00FFCC) else Color(0x6637474F), shape = CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowUp,
                        contentDescription = "Accelerate",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(26.dp)
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(36.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Steer Left",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(26.dp)
                        )
                        Box(
                            modifier = Modifier
                                .offset(x = knobOffsetX.dp, y = knobOffsetY.dp)
                                .size(32.dp)
                                .background(
                                    if (isLeftActive) Color(0xFF00FFCC) else Color(0x9900FFCC),
                                    shape = CircleShape
                                )
                                .border(1.5.dp, Color.White, shape = CircleShape)
                        )
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = "Steer Right",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = "Reverse",
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(26.dp)
                    )
                }

                Text(
                    text = "DRIVE STICK",
                    fontSize = 8.sp,
                    color = Color.White.copy(alpha = 0.4f),
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                )
            }

            // RIGHT: HOLD action buttons (NITRO, HANDBRAKE)
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // NITRO BOOST — press and hold
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .background(
                            if (isBoosting) Color(0xFF00E5FF) else Color(0xDD0091EA),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { viewModel.setNitro(true) },
                                onDragEnd = { viewModel.setNitro(false) },
                                onDragCancel = { viewModel.setNitro(false) },
                                onDrag = { _, _ -> }
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("nitro_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "⚡", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isBoosting) "NITRO!" else "BOOST",
                            color = if (isBoosting) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // HANDBRAKE — press and hold
                Box(
                    modifier = Modifier
                        .height(48.dp)
                        .background(
                            if (isHandbraking) Color(0xFFFF9800) else Color(0xDD455A64),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { viewModel.setHandbrake(true) },
                                onDragEnd = { viewModel.setHandbrake(false) },
                                onDragCancel = { viewModel.setHandbrake(false) },
                                onDrag = { _, _ -> }
                            )
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                        .testTag("handbrake_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(text = "💨", fontSize = 16.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (isHandbraking) "DRIFT!" else "BRAKE",
                            color = if (isHandbraking) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }

                // Turret Auto-Targeting Status Box
                Box(
                    modifier = Modifier
                        .background(Color(0xCC000000), shape = RoundedCornerShape(8.dp))
                        .border(1.dp, Color(0xFF00FFCC).copy(alpha = 0.5f), shape = RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = "TURRET: AUTO-LOCK [ON]",
                        color = Color(0xFF00FFCC),
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}
