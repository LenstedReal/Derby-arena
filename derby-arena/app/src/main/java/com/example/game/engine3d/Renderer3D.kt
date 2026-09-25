package com.example.game.engine3d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.example.game.*
import kotlin.math.*

/**
 * Software 3D pipeline — Phase E lighting, Phase K quality tiers.
 * Not a final GPU renderer; Filament migration remains Phase B.
 */
class Renderer3D {

    private val camera = Camera3D()
    private var wheelRotation = 0f
    private val path = Path()
    private val polyBuffer = ArrayList<Poly3D>(800)

    fun render(
        drawScope: DrawScope,
        textMeasurer: TextMeasurer,
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
        quality: QualityPreset = QualityPreset.HIGH
    ) {
        val size = drawScope.size
        wheelRotation += player.speed * 0.12f

        camera.update(
            playerX = player.x,
            playerY = player.y,
            playerAngleDeg = player.angle,
            playerSpeed = player.speed,
            shakeAmount = screenShake,
            mode = cameraMode,
            isBoosting = isBoosting,
            isHandbraking = isHandbraking
        )

        renderSkybox(drawScope, size, theme, camera.smoothYaw)

        polyBuffer.clear()
        Arena3DModels.appendStadiumPolys(spectators, camera, polyBuffer, quality)

        for (obs in obstacles) {
            if (!obs.isDestroyed) {
                if (camera.isSphereInFrustum(Vector3(obs.x, obs.y, 10f), obs.radius * 1.5f)) {
                    polyBuffer.addAll(Arena3DModels.buildObstaclePolys(obs))
                }
            }
        }

        for (enemy in enemies) {
            if (!enemy.isDead || enemy.isWreckage) {
                if (camera.isSphereInFrustum(Vector3(enemy.x, enemy.y, 10f), 55f)) {
                    polyBuffer.addAll(Arena3DModels.buildEnemyCarPolys(enemy))
                }
            }
        }

        if (cameraMode != CameraMode3D.HOOD_3D) {
            polyBuffer.addAll(
                Arena3DModels.buildPlayerCarPolys(player, wheelRotation, isBoosting)
            )
        }

        // Depth
        for (poly in polyBuffer) {
            val p4 = poly.p4
            val cx: Float
            val cy: Float
            val cz: Float
            if (p4 != null) {
                cx = (poly.p1.x + poly.p2.x + poly.p3.x + p4.x) * 0.25f
                cy = (poly.p1.y + poly.p2.y + poly.p3.y + p4.y) * 0.25f
                cz = (poly.p1.z + poly.p2.z + poly.p3.z + p4.z) * 0.25f
            } else {
                cx = (poly.p1.x + poly.p2.x + poly.p3.x) * 0.3333f
                cy = (poly.p1.y + poly.p2.y + poly.p3.y) * 0.3333f
                cz = (poly.p1.z + poly.p2.z + poly.p3.z) * 0.3333f
            }
            poly.depth = (cx - camera.position.x) * (camera.target.x - camera.position.x) +
                    (cy - camera.position.y) * (camera.target.y - camera.position.y) +
                    (cz - camera.position.z) * (camera.target.z - camera.position.z)
        }
        polyBuffer.sortByDescending { it.depth }

        // Phase E: warm directional sunlight
        val lightDir = Vector3(0.55f, 0.35f, 0.75f).normalized()

        // Soft ground shadow blobs under vehicles (Phase E)
        if (quality.shadows) {
            drawVehicleShadow(drawScope, size, player.x, player.y, 28f)
            for (e in enemies) {
                if (!e.isDead) drawVehicleShadow(drawScope, size, e.x, e.y, 24f)
            }
        }

        for (poly in polyBuffer) {
            if (poly.depth < 2f) continue
            val pt1 = camera.project(poly.p1, size)
            val pt2 = camera.project(poly.p2, size)
            val pt3 = camera.project(poly.p3, size)
            val pt4 = poly.p4?.let { camera.project(it, size) }
            if (!pt1.isVisible && !pt2.isVisible && !pt3.isVisible && (pt4 == null || !pt4.isVisible)) continue

            val vA = poly.p2 - poly.p1
            val vB = poly.p3 - poly.p1
            val normal = vA.cross(vB).normalized()
            if (!poly.isDoubleSided && !poly.emissive) {
                val toCam = (camera.position - poly.p1).normalized()
                if (normal.dot(toCam) < -0.1f) continue
            }

            var shadedColor: Color
            if (poly.emissive) {
                shadedColor = poly.color
            } else {
                val NdotL = normal.dot(lightDir).coerceIn(0f, 1f)
                // Warm sun: higher ambient on sand-facing, stronger key light
                val ambient = 0.38f
                val diffuse = NdotL * 0.62f
                val toCam = (camera.position - poly.p1).normalized()
                val halfVec = (lightDir + toCam).normalized()
                val NdotH = normal.dot(halfVec).coerceIn(0f, 1f)
                val specular = NdotH.pow(20) * 0.35f
                val NdotV = normal.dot(toCam).coerceIn(0f, 1f)
                val rim = (1f - NdotV).pow(3) * 0.18f
                val lit = ambient + diffuse
                shadedColor = Color(
                    red = (poly.color.red * lit + specular * 1.1f + rim * 0.35f).coerceIn(0f, 1f),
                    green = (poly.color.green * lit + specular * 0.95f + rim * 0.25f).coerceIn(0f, 1f),
                    blue = (poly.color.blue * lit + specular * 0.75f + rim * 0.15f).coerceIn(0f, 1f),
                    alpha = poly.color.alpha
                )
            }

            val fogStart = 220f
            val fogEnd = 980f
            val fogFactor = ((poly.depth - fogStart) / (fogEnd - fogStart)).coerceIn(0f, 1f) *
                    fogDensity * quality.fogQuality
            if (fogFactor > 0.01f && !poly.emissive) {
                shadedColor = lerpColor(shadedColor, theme.fogColor, fogFactor * 0.85f)
            }

            path.reset()
            path.moveTo(pt1.sx, pt1.sy)
            path.lineTo(pt2.sx, pt2.sy)
            path.lineTo(pt3.sx, pt3.sy)
            if (pt4 != null) path.lineTo(pt4.sx, pt4.sy)
            path.close()
            drawScope.drawPath(path = path, color = shadedColor, style = Fill)
        }

        // Scrap / bullets / particles / HP bars (capped by quality)
        for (scrap in scrapItems) {
            if (scrap.isCollected) continue
            val proj = camera.project(Vector3(scrap.x, scrap.y, 4f), size)
            if (proj.isVisible && proj.depth > 2f) {
                val scSize = (260f / proj.depth).coerceIn(3f, 22f)
                drawScope.drawCircle(Color(0xFF00FFCC), scSize, Offset(proj.sx, proj.sy))
                drawScope.drawCircle(Color.White, scSize * 0.4f, Offset(proj.sx, proj.sy))
            }
        }

        for (bullet in bullets) {
            if (bullet.isDead) continue
            val bPos = Vector3(bullet.x, bullet.y, 16f)
            val bProj = camera.project(bPos, size)
            if (bProj.isVisible && bProj.depth > 2f) {
                val tailPos = bPos - Vector3(bullet.vx * 1.5f, bullet.vy * 1.5f, 0f)
                val tProj = camera.project(tailPos, size)
                val bColor = if (bullet.owner == "player") Color(0xFFFFEA00) else Color(0xFFFF1744)
                drawScope.drawLine(
                    bColor,
                    Offset(tProj.sx, tProj.sy),
                    Offset(bProj.sx, bProj.sy),
                    strokeWidth = (280f / bProj.depth).coerceIn(2f, 6f),
                    cap = StrokeCap.Round
                )
            }
        }

        var drawnParticles = 0
        for (p in particles) {
            if (p.life <= 0f) continue
            if (drawnParticles >= quality.maxParticles) break
            val pPos = Vector3(p.x, p.y, (1f - p.life) * 20f + 2f)
            val proj = camera.project(pPos, size)
            if (!proj.isVisible || proj.depth <= 2f) continue
            drawnParticles++
            val pSize = (p.size * (240f / proj.depth)).coerceIn(1.5f, 40f)
            if (p.customText.isNotEmpty()) {
                val textX = proj.sx - 30f
                val textY = proj.sy - 20f
                if (textX >= 10f && textX <= size.width - 80f && textY >= 10f && textY <= size.height - 40f) {
                    try {
                        drawScope.drawText(
                            textMeasurer, p.customText, Offset(textX, textY),
                            TextStyle(
                                color = p.color.copy(alpha = p.life),
                                fontSize = (12.sp.value * (300f / proj.depth).coerceIn(0.7f, 2.2f)).sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    } catch (_: Exception) {}
                }
            } else {
                drawScope.drawCircle(
                    p.color.copy(alpha = (p.life * p.color.alpha).coerceIn(0f, 1f)),
                    pSize,
                    Offset(proj.sx, proj.sy)
                )
            }
        }

        for (enemy in enemies) {
            if (enemy.isDead) continue
            val proj = camera.project(Vector3(enemy.x, enemy.y, 32f), size)
            if (!proj.isVisible || proj.depth <= 2f) continue
            val barW = (4000f / proj.depth).coerceIn(20f, 80f)
            val hpPct = (enemy.health / enemy.maxHealth).coerceIn(0f, 1f)
            drawScope.drawRect(Color(0xAA000000), Offset(proj.sx - barW / 2, proj.sy), Size(barW, 5f))
            drawScope.drawRect(
                if (hpPct > 0.4f) Color(0xFFE53935) else Color(0xFFFF1744),
                Offset(proj.sx - barW / 2, proj.sy),
                Size(barW * hpPct, 5f)
            )
        }

        // Laser sight
        val turretRad = Math.toRadians(player.turretAngle.toDouble()).toFloat()
        val laserStart = Vector3(player.x, player.y, 22f)
        val laserEnd = laserStart + Vector3(cos(turretRad) * 450f, sin(turretRad) * 450f, -20f)
        val l1 = camera.project(laserStart, size)
        val l2 = camera.project(laserEnd, size)
        if (l1.isVisible && l2.isVisible) {
            drawScope.drawLine(Color(0x6600E5FF), Offset(l1.sx, l1.sy), Offset(l2.sx, l2.sy), strokeWidth = 1.8f)
        }
    }

    private fun drawVehicleShadow(drawScope: DrawScope, size: Size, x: Float, y: Float, radius: Float) {
        // Project a flat ellipse under the vehicle
        val segs = 8
        val pts = ArrayList<Offset>(segs)
        for (i in 0 until segs) {
            val a = i * (2 * PI / segs).toFloat()
            val wx = x + cos(a) * radius
            val wy = y + sin(a) * radius * 0.65f
            val pr = camera.project(Vector3(wx, wy, 0.4f), size)
            if (pr.depth > 2f) pts.add(Offset(pr.sx, pr.sy))
        }
        if (pts.size < 3) return
        path.reset()
        path.moveTo(pts[0].x, pts[0].y)
        for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
        path.close()
        drawScope.drawPath(path, Color(0x55000000), style = Fill)
    }

    private fun renderSkybox(drawScope: DrawScope, size: Size, theme: DerbyTheme, cameraYaw: Float) {
        val horizonY = size.height * 0.42f
        // Warmer dramatic sky
        drawScope.drawRect(
            brush = Brush.verticalGradient(
                colors = listOf(
                    Color(0xFF1A1520),
                    theme.skyColor,
                    theme.fogColor.copy(alpha = 0.9f),
                    Color(0xFFC4A574).copy(alpha = 0.35f),
                    theme.groundColor
                ),
                startY = 0f,
                endY = size.height
            ),
            size = size
        )
        val yawOffset = (cameraYaw * 3.5f) % size.width
        val skylineColor = Color(0xFF0F1215).copy(alpha = 0.8f)
        for (i in -1..2) {
            val baseX = i * size.width + yawOffset
            drawScope.drawRect(skylineColor, Offset(baseX + 40f, horizonY - 120f), Size(50f, 120f))
            drawScope.drawRect(skylineColor, Offset(baseX + 140f, horizonY - 80f), Size(90f, 80f))
            drawScope.drawRect(skylineColor, Offset(baseX + 280f, horizonY - 150f), Size(40f, 150f))
            drawScope.drawCircle(Color(0xFFFF1744), 2.5f, Offset(baseX + 300f, horizonY - 152f))
            drawScope.drawRect(skylineColor, Offset(baseX + 380f, horizonY - 100f), Size(70f, 100f))
        }
        // Sun disc
        drawScope.drawCircle(
            Color(0xFFFFE082).copy(alpha = 0.55f),
            28f,
            Offset(size.width * 0.72f, size.height * 0.18f)
        )
    }

    private fun lerpColor(c1: Color, c2: Color, t: Float): Color {
        val tt = t.coerceIn(0f, 1f)
        return Color(
            c1.red + (c2.red - c1.red) * tt,
            c1.green + (c2.green - c1.green) * tt,
            c1.blue + (c2.blue - c1.blue) * tt,
            c1.alpha
        )
    }
}
