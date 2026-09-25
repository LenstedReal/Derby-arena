package com.example.game.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.game.ArenaConstants
import com.example.game.GameViewModel
import com.example.game.LootType
import com.example.game.systems.WeaponSystem
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private val Panel = Color(0xB3121212)
private val Rust = Color(0xFFD84315)
private val Amber = Color(0xFFFFB300)
private val Red = Color(0xFFD32F2F)
private val Text1 = Color(0xFFF5F5F5)
private val Muted = Color(0xFF888888)

/** BattleHud: HP / armor / nitro / ammo / weapon / minimap / zone timer / kills / speed / crosshair / damage indicator / kill feed. */
@Composable
fun BattleHud(viewModel: GameViewModel, modifier: Modifier = Modifier) {
    val p by viewModel.player.collectAsStateWithLifecycle()
    val enemies by viewModel.enemies.collectAsStateWithLifecycle()
    val loot by viewModel.loot.collectAsStateWithLifecycle()
    val zone by viewModel.zone.collectAsStateWithLifecycle()
    val killFeed by viewModel.killFeed.collectAsStateWithLifecycle()
    val countdown by viewModel.countdown.collectAsStateWithLifecycle()
    val announcement by viewModel.zoneAnnouncement.collectAsStateWithLifecycle()
    val level by viewModel.currentLevel.collectAsStateWithLifecycle()
    val time by viewModel.stageTimeElapsed.collectAsStateWithLifecycle()
    val alive = enemies.count { !it.isDead && !it.isWreckage }
    val spec = WeaponSystem.spec(p.weapon.id)

    Box(modifier = modifier.fillMaxSize()) {
        // TOP-LEFT: minimap + zone timer
        Column(Modifier.align(Alignment.TopStart).padding(12.dp)) {
            Minimap(p.x, p.y, p.angle, enemies.filter { !it.isDead }.map { Offset(it.x, it.y) }, loot.filter { it.active }.map { Offset(it.x, it.y) to it.type }, Offset(zone.centerX, zone.centerY), zone.radius)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.background(Panel).padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(if (zone.finalCircle) "FINAL CIRCLE" else if (zone.shrinking) "SHRINKING" else "ZONE", color = if (zone.shrinking || !p.inZone) Red else Amber, fontSize = 11.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                Spacer(Modifier.width(8.dp))
                Text(if (zone.finalCircle) "--" else "${zone.phaseTimer.coerceAtLeast(0f).toInt()}s", color = Text1, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("zone_timer"))
            }
        }

        // TOP-CENTER: stage + match clock + pause
        Row(Modifier.align(Alignment.TopCenter).padding(top = 10.dp).background(Panel).padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("STAGE $level", color = Rust, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
            Spacer(Modifier.width(12.dp))
            Text("%02d:%02d".format(time.toInt() / 60, time.toInt() % 60), color = Text1, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(12.dp))
            Text("II", color = Text1, fontSize = 14.sp, fontWeight = FontWeight.Black, modifier = Modifier.clickable { viewModel.pauseGame() }.padding(horizontal = 6.dp).testTag("pause_button"))
        }

        // TOP-RIGHT: kills / remaining / kill feed
        Column(Modifier.align(Alignment.TopEnd).padding(12.dp), horizontalAlignment = Alignment.End) {
            Row(Modifier.background(Panel).padding(horizontal = 10.dp, vertical = 4.dp)) {
                Stat("KILLS", p.kills.toString(), Amber, "kills_value")
                Spacer(Modifier.width(14.dp))
                Stat("ALIVE", "${alive + (if (p.isDead) 0 else 1)}", Text1, "alive_value")
            }
            Spacer(Modifier.height(4.dp))
            for (k in killFeed.takeLast(3)) Text(k.text, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        }

        // CENTER: crosshair + damage direction + countdown / announcements
        Crosshair(Modifier.align(Alignment.Center), p.weapon.overheated, p.lastHitDirDeg - p.angle, recentHit = (p.health < p.maxHealth) && abs(p.lastHitFrame) > 0)
        if (countdown > 0f) {
            Text(if (countdown > 1f) countdown.toInt().toString() else "GO!", color = Rust, fontSize = 72.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center).offset(y = (-70).dp).testTag("countdown_text"))
        } else if (announcement.isNotEmpty()) {
            Text(announcement, color = Red, fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp, modifier = Modifier.align(Alignment.Center).offset(y = (-90).dp).background(Panel).padding(horizontal = 14.dp, vertical = 4.dp))
        }
        if (!p.inZone && !p.isDead) Text("OUTSIDE SAFE ZONE — TAKING DAMAGE", color = Red, fontSize = 13.sp, fontWeight = FontWeight.Black, modifier = Modifier.align(Alignment.Center).offset(y = 70.dp))

        // BOTTOM-CENTER: vitals + weapon
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp).background(Panel).border(1.dp, Color(0xFF333333)).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column {
                SegBar("HP", p.health / p.maxHealth, if (p.health / p.maxHealth < 0.3f) Red else Color(0xFF4CAF50), "hp_bar")
                Spacer(Modifier.height(4.dp))
                SegBar("ARMOR", p.armorPoints / p.maxArmorPoints, Color(0xFF90A4AE), "armor_bar")
                Spacer(Modifier.height(4.dp))
                SegBar("NITRO", p.nitro / p.maxNitro, Amber, "nitro_bar")
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(spec.label, color = Rust, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 2.sp, modifier = Modifier.testTag("weapon_label"))
                Text(if (p.weapon.reloadTimer > 0) "RELOADING" else "${p.weapon.ammo} / ${if (p.weapon.reserve >= 9000) "∞" else p.weapon.reserve}", color = if (p.weapon.ammo == 0) Red else Text1, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("ammo_value"))
                SegBar("HEAT", p.weapon.heat, if (p.weapon.overheated) Red else Color(0xFFFF7043), "heat_bar", width = 90.dp)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("${(abs(p.speed) * 11f).toInt()}", color = Text1, fontSize = 28.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag("speed_value"))
                Text("KM/H", color = Muted, fontSize = 10.sp, letterSpacing = 2.sp)
            }
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color, tag: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 2.sp)
        Text(value, color = color, fontSize = 18.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun SegBar(label: String, frac: Float, color: Color, tag: String, width: androidx.compose.ui.unit.Dp = 150.dp, segments: Int = 12) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Muted, fontSize = 9.sp, letterSpacing = 1.5.sp, modifier = Modifier.width(38.dp))
        Row(Modifier.width(width).testTag(tag), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            val filled = (frac.coerceIn(0f, 1f) * segments + 0.5f).toInt()
            for (i in 0 until segments) Box(Modifier.weight(1f).height(9.dp).background(if (i < filled) color else Color(0xFF292929)))
        }
    }
}

@Composable
private fun Crosshair(modifier: Modifier, overheated: Boolean, hitDirRel: Float, recentHit: Boolean) {
    Canvas(modifier.size(120.dp).testTag("crosshair")) {
        val c = center
        val col = if (overheated) Red else Amber
        drawCircle(col, radius = 16f, center = c, style = Stroke(2f))
        for (a in 0 until 4) {
            val r = Math.toRadians(a * 90.0)
            drawLine(col, c + Offset(cos(r).toFloat() * 22f, sin(r).toFloat() * 22f), c + Offset(cos(r).toFloat() * 36f, sin(r).toFloat() * 36f), 2.5f)
        }
        if (recentHit) {
            val r = Math.toRadians(hitDirRel.toDouble() - 90.0)
            val dir = Offset(cos(r).toFloat(), sin(r).toFloat())
            drawLine(Red.copy(alpha = 0.8f), c + dir * 48f, c + dir * 58f, 6f)
        }
    }
}

@Composable
private fun Minimap(px: Float, py: Float, heading: Float, enemies: List<Offset>, loot: List<Pair<Offset, LootType>>, zoneC: Offset, zoneR: Float) {
    Canvas(Modifier.size(120.dp).background(Panel).border(1.dp, Color(0xFF333333)).testTag("minimap")) {
        val s = size.minDimension / 2f / (ArenaConstants.ARENA_RADIUS + 20f)
        val c = center
        fun map(x: Float, y: Float) = Offset(c.x + x * s, c.y + y * s)
        drawCircle(Color(0xFF3A3A3A), ArenaConstants.PLAYABLE_RADIUS * s, c, style = Stroke(1.5f))
        drawCircle(Red.copy(alpha = 0.9f), zoneR * s, map(zoneC.x, zoneC.y), style = Stroke(2f))
        for ((pos, type) in loot) drawCircle(when (type) { LootType.HEALTH, LootType.REPAIR -> Color(0xFF4CAF50); LootType.WEAPON -> Color(0xFFFF4081); else -> Amber }, 2.2f, map(pos.x, pos.y))
        for (e in enemies) drawCircle(Red, 3.5f, map(e.x, e.y))
        val r = Math.toRadians(heading.toDouble())
        val pp = map(px, py)
        val f = Offset(cos(r).toFloat(), sin(r).toFloat())
        drawLine(Text1, pp, pp + f * 9f, 3f)
        drawCircle(Text1, 3.5f, pp)
    }
}
