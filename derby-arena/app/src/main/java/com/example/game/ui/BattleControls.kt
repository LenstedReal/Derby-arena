package com.example.game.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.game.GameViewModel
import kotlin.math.hypot

/**
 * BattleControls: left steering joystick, right aim joystick, hold buttons (Throttle / Brake / Nitro /
 * Handbrake / Fire). Joysticks use a deadzone, normalized vectors, center return and smoothing.
 * Multi-touch: every control has its own pointerInput so all of them can be held simultaneously.
 */
@Composable
fun BattleControls(viewModel: GameViewModel, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
        // LEFT: steering / throttle stick
        Joystick(
            modifier = Modifier.align(Alignment.BottomStart).testTag("steer_joystick"),
            accent = Color(0xFFD84315),
            onChange = { nx, ny, active -> viewModel.updateInputs(if (active) -ny else 0f, if (active) nx else 0f, 0f, false, false) }
        )
        // RIGHT: aim stick + buttons
        Row(modifier = Modifier.align(Alignment.BottomEnd), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HoldButton("NITRO", Color(0xFFFF8F00), Modifier.testTag("nitro_button")) { viewModel.setNitro(it) }
                HoldButton("BRAKE", Color(0xFF8D6E63), Modifier.testTag("brake_button")) { viewModel.setBrake(it) }
                HoldButton("DRIFT", Color(0xFF607D8B), Modifier.testTag("handbrake_button")) { viewModel.setHandbrake(it) }
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                HoldButton("FIRE", Color(0xFFD32F2F), Modifier.size(88.dp).testTag("fire_button")) { viewModel.setFire(it) }
                Joystick(
                    modifier = Modifier.testTag("aim_joystick"),
                    accent = Color(0xFFFFB300),
                    size = 128.dp,
                    onChange = { nx, ny, active -> viewModel.updateAim(nx, ny, active) }
                )
            }
        }
    }
}

@Composable
fun Joystick(
    modifier: Modifier = Modifier,
    accent: Color,
    size: androidx.compose.ui.unit.Dp = 150.dp,
    deadzone: Float = 0.12f,
    onChange: (nx: Float, ny: Float, active: Boolean) -> Unit
) {
    var knob by remember { mutableStateOf(Offset.Zero) }
    var active by remember { mutableStateOf(false) }
    val radiusPx = with(androidx.compose.ui.platform.LocalDensity.current) { (size / 2).toPx() }
    val knobRadius = radiusPx * 0.42f
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color(0x5A101010))
            .border(2.dp, if (active) accent else Color(0x88555555), CircleShape)
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    active = true
                    var pos = down.position - Offset(radiusPx, radiusPx)
                    fun emit() {
                        val len = hypot(pos.x, pos.y)
                        val clamped = if (len > radiusPx) pos * (radiusPx / len) else pos
                        knob = clamped
                        var nx = clamped.x / radiusPx
                        var ny = clamped.y / radiusPx
                        val mag = hypot(nx, ny)
                        if (mag < deadzone) { nx = 0f; ny = 0f } else {
                            val scaled = (mag - deadzone) / (1f - deadzone)
                            nx = nx / mag * scaled; ny = ny / mag * scaled
                        }
                        onChange(nx, ny, true)
                    }
                    emit()
                    drag(down.id) { change ->
                        pos += change.positionChange()
                        change.consume()
                        emit()
                    }
                    active = false
                    knob = Offset.Zero
                    onChange(0f, 0f, false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(knob.x.toInt(), knob.y.toInt()) }
                .size(with(androidx.compose.ui.platform.LocalDensity.current) { (knobRadius * 2).toDp() })
                .clip(CircleShape)
                .background(if (active) accent.copy(alpha = 0.85f) else Color(0xAA2A2A2A))
                .border(2.dp, Color(0xFF1E1E1E), CircleShape)
        )
    }
}

@Composable
fun HoldButton(label: String, color: Color, modifier: Modifier = Modifier, onHold: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .defaultMinSize(minWidth = 70.dp, minHeight = 56.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(if (pressed) color else Color(0x99161616))
            .border(2.dp, if (pressed) Color.White else color.copy(alpha = 0.8f), RoundedCornerShape(4.dp))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pressed = true
                    onHold(true)
                    drag(down.id) { it.consume() }
                    pressed = false
                    onHold(false)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (pressed) Color.Black else Color(0xFFF5F5F5), fontSize = 13.sp, fontWeight = FontWeight.Black, letterSpacing = 1.5.sp)
    }
}
