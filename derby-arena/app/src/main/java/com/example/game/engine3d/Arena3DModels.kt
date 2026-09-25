package com.example.game.engine3d

import androidx.compose.ui.graphics.Color
import com.example.game.ArenaConstants
import com.example.game.EnemyCar
import com.example.game.Obstacle
import com.example.game.PlayerCar
import com.example.game.QualityPreset
import com.example.game.RobotSpectator
import kotlin.math.*

/**
 * Arena geometry + vehicle meshes.
 * Phase C: colosseum arena with sand floor, columns, arches, banners, service areas.
 * Phase D: multi-material vehicle body (rust metal, glass, rubber, emissive).
 */
object Arena3DModels {

    // Sand / dirt palette
    private val SAND_A = Color(0xFFC4A574)
    private val SAND_B = Color(0xFFB8956A)
    private val SAND_C = Color(0xFFA8845C)
    private val SAND_DARK = Color(0xFF8B7355)
    private val SAND_TRACK = Color(0xFF9A8060)
    private val STONE = Color(0xFF6B6560)
    private val STONE_DARK = Color(0xFF4A4540)
    private val STONE_LIGHT = Color(0xFF8A8478)
    private val BANNER_RED = Color(0xFFB71C1C)
    private val BANNER_DARK = Color(0xFF6D1111)
    private val COLUMN = Color(0xFF7A7468)
    private val COLUMN_CAP = Color(0xFF9A9488)
    private val BARRIER_CONCRETE = Color(0xFF5C6B73)
    private val BARRIER_CONCRETE_DARK = Color(0xFF3E4A50)
    private val BARRIER_STRIPE = Color(0xFFFFD600)
    private val TIRE_COLOR = Color(0xFF1A1A1A)
    private val TIRE_RIM = Color(0xFF424242)
    private val RUST = Color(0xFF8D6E63)
    private val METAL_BARE = Color(0xFF90A4AE)
    private val SCRAP = Color(0xFF546E7A)

    /** Solid sand floor with track wear and tone variation — cached. */
    val staticFloorPolys: List<Poly3D> by lazy {
        val polys = ArrayList<Poly3D>(280)
        val radius = ArenaConstants.ARENA_RADIUS
        val segs = 28
        val rings = listOf(0f, 90f, 200f, 340f, 480f, 620f, radius)

        for (r in 0 until rings.size - 1) {
            val r0 = rings[r]
            val r1 = rings[r + 1]
            for (i in 0 until segs) {
                val a1 = i * (2 * PI / segs).toFloat()
                val a2 = (i + 1) * (2 * PI / segs).toFloat()
                val p1 = Vector3(cos(a1) * r0, sin(a1) * r0, 0f)
                val p2 = Vector3(cos(a2) * r0, sin(a2) * r0, 0f)
                val p3 = Vector3(cos(a2) * r1, sin(a2) * r1, 0f)
                val p4 = Vector3(cos(a1) * r1, sin(a1) * r1, 0f)
                val col = when {
                    r >= 4 -> SAND_DARK
                    (i + r) % 3 == 0 -> SAND_A
                    (i + r) % 3 == 1 -> SAND_B
                    else -> SAND_C
                }
                polys.add(Poly3D(p1, p2, p3, p4, col))
            }
        }

        // Worn track ring
        val laneR = 300f
        val laneSegs = 20
        for (i in 0 until laneSegs) {
            val a1 = i * (2 * PI / laneSegs).toFloat()
            val a2 = (i + 1) * (2 * PI / laneSegs).toFloat()
            val inner = laneR - 18f
            val outer = laneR + 18f
            polys.add(
                Poly3D(
                    Vector3(cos(a1) * inner, sin(a1) * inner, 0.3f),
                    Vector3(cos(a2) * inner, sin(a2) * inner, 0.3f),
                    Vector3(cos(a2) * outer, sin(a2) * outer, 0.3f),
                    Vector3(cos(a1) * outer, sin(a1) * outer, 0.3f),
                    SAND_TRACK
                )
            )
        }

        // Center arena seal
        val centerR = 70f
        val cSegs = 12
        for (i in 0 until cSegs) {
            val a1 = i * (2 * PI / cSegs).toFloat()
            val a2 = (i + 1) * (2 * PI / cSegs).toFloat()
            polys.add(
                Poly3D(
                    Vector3(0f, 0f, 0.5f),
                    Vector3(cos(a1) * centerR, sin(a1) * centerR, 0.5f),
                    Vector3(cos(a2) * centerR, sin(a2) * centerR, 0.5f),
                    null,
                    STONE_DARK
                )
            )
        }
        // Cross marks
        val ml = 40f
        val mw = 5f
        polys.add(Poly3D(Vector3(-ml, -mw, 0.6f), Vector3(ml, -mw, 0.6f), Vector3(ml, mw, 0.6f), Vector3(-ml, mw, 0.6f), STONE_LIGHT))
        polys.add(Poly3D(Vector3(-mw, -ml, 0.6f), Vector3(mw, -ml, 0.6f), Vector3(mw, ml, 0.6f), Vector3(-mw, ml, 0.6f), STONE_LIGHT))

        polys
    }

    /** Barriers + tire ring — cached. */
    val staticBarrierPolys: List<Poly3D> by lazy {
        val polys = ArrayList<Poly3D>(200)
        val outerR = ArenaConstants.ARENA_RADIUS
        val innerR = ArenaConstants.BARRIER_INNER_RADIUS
        val wallH = ArenaConstants.WALL_HEIGHT
        val segs = 28

        for (i in 0 until segs) {
            val a1 = i * (2 * PI / segs).toFloat()
            val a2 = (i + 1) * (2 * PI / segs).toFloat()
            val w1 = Vector3(cos(a1) * outerR, sin(a1) * outerR, 0f)
            val w2 = Vector3(cos(a2) * outerR, sin(a2) * outerR, 0f)
            val w3 = Vector3(cos(a2) * outerR, sin(a2) * outerR, wallH)
            val w4 = Vector3(cos(a1) * outerR, sin(a1) * outerR, wallH)
            polys.add(Poly3D(w1, w2, w3, w4, BARRIER_CONCRETE_DARK))
            val lipR = outerR + 10f
            polys.add(
                Poly3D(
                    w4, w3,
                    Vector3(cos(a2) * lipR, sin(a2) * lipR, wallH + 3f),
                    Vector3(cos(a1) * lipR, sin(a1) * lipR, wallH + 3f),
                    if (i % 2 == 0) BARRIER_STRIPE else Color(0xFF1E2124)
                )
            )
        }

        val tireH = 16f
        val tireSegs = 20
        for (i in 0 until tireSegs) {
            val a1 = i * (2 * PI / tireSegs).toFloat()
            val a2 = (i + 1) * (2 * PI / tireSegs).toFloat()
            val rIn = innerR - 12f
            val rOut = innerR + 6f
            val b1 = Vector3(cos(a1) * rIn, sin(a1) * rIn, 0f)
            val b2 = Vector3(cos(a2) * rIn, sin(a2) * rIn, 0f)
            val b3 = Vector3(cos(a2) * rOut, sin(a2) * rOut, 0f)
            val b4 = Vector3(cos(a1) * rOut, sin(a1) * rOut, 0f)
            val t1 = Vector3(cos(a1) * rIn, sin(a1) * rIn, tireH)
            val t2 = Vector3(cos(a2) * rIn, sin(a2) * rIn, tireH)
            val t3 = Vector3(cos(a2) * rOut, sin(a2) * rOut, tireH)
            val t4 = Vector3(cos(a1) * rOut, sin(a1) * rOut, tireH)
            polys.add(Poly3D(b1, b2, t2, t1, TIRE_COLOR))
            polys.add(Poly3D(b3, b4, t4, t3, TIRE_COLOR))
            polys.add(Poly3D(t1, t2, t3, t4, TIRE_RIM))
        }
        polys
    }

    /**
     * Colosseum architecture: columns, upper gallery, red banners, service sheds.
     * Cached once.
     */
    val staticColosseumPolys: List<Poly3D> by lazy {
        val polys = ArrayList<Poly3D>(320)
        val arenaR = ArenaConstants.ARENA_RADIUS
        val wallH = ArenaConstants.WALL_HEIGHT
        val galleryH = 95f
        val roofH = 130f
        val segs = 24

        // Outer wall + gallery tier
        for (i in 0 until segs) {
            val a1 = i * (2 * PI / segs).toFloat()
            val a2 = (i + 1) * (2 * PI / segs).toFloat()
            val r0 = arenaR + 8f
            val r1 = arenaR + 90f
            // Gallery floor slope
            polys.add(
                Poly3D(
                    Vector3(cos(a1) * r0, sin(a1) * r0, wallH),
                    Vector3(cos(a2) * r0, sin(a2) * r0, wallH),
                    Vector3(cos(a2) * r1, sin(a2) * r1, galleryH),
                    Vector3(cos(a1) * r1, sin(a1) * r1, galleryH),
                    STONE_DARK
                )
            )
            // Upper rim wall
            val r2 = arenaR + 100f
            polys.add(
                Poly3D(
                    Vector3(cos(a1) * r1, sin(a1) * r1, galleryH),
                    Vector3(cos(a2) * r1, sin(a2) * r1, galleryH),
                    Vector3(cos(a2) * r2, sin(a2) * r2, roofH),
                    Vector3(cos(a1) * r2, sin(a1) * r2, roofH),
                    STONE
                )
            )
        }

        // Columns every other segment
        val colCount = 12
        for (c in 0 until colCount) {
            val a = c * (2 * PI / colCount).toFloat()
            val cx = cos(a) * (arenaR + 35f)
            val cy = sin(a) * (arenaR + 35f)
            val colW = 8f
            val colH = galleryH + 5f
            // Column box
            val corners = listOf(
                Vector3(cx - colW, cy - colW, 0f),
                Vector3(cx + colW, cy - colW, 0f),
                Vector3(cx + colW, cy + colW, 0f),
                Vector3(cx - colW, cy + colW, 0f)
            )
            val top = corners.map { Vector3(it.x, it.y, colH) }
            polys.add(Poly3D(top[0], top[1], top[2], top[3], COLUMN_CAP))
            for (k in 0 until 4) {
                val n = (k + 1) % 4
                polys.add(Poly3D(corners[k], corners[n], top[n], top[k], COLUMN))
            }
            // Capital
            val capW = colW + 3f
            polys.add(
                Poly3D(
                    Vector3(cx - capW, cy - capW, colH),
                    Vector3(cx + capW, cy - capW, colH),
                    Vector3(cx + capW, cy + capW, colH + 6f),
                    Vector3(cx - capW, cy + capW, colH + 6f),
                    COLUMN_CAP
                )
            )
        }

        // Red banners hanging between some columns
        for (c in 0 until colCount step 2) {
            val a = c * (2 * PI / colCount).toFloat() + 0.12f
            val bx = cos(a) * (arenaR + 20f)
            val by = sin(a) * (arenaR + 20f)
            val side = Vector3(-sin(a), cos(a), 0f)
            val topZ = wallH + 28f
            val botZ = wallH + 4f
            val hw = 14f
            val p1 = Vector3(bx, by, topZ) + side * (-hw)
            val p2 = Vector3(bx, by, topZ) + side * hw
            val p3 = Vector3(bx, by, botZ) + side * hw
            val p4 = Vector3(bx, by, botZ) + side * (-hw)
            polys.add(Poly3D(p1, p2, p3, p4, if (c % 4 == 0) BANNER_RED else BANNER_DARK, isDoubleSided = true))
        }

        // Service / scrap sheds at four compass points outside playable but inside outer wall
        val shedPositions = listOf(
            Vector3(0f, -620f, 0f),
            Vector3(0f, 620f, 0f),
            Vector3(620f, 0f, 0f),
            Vector3(-620f, 0f, 0f)
        )
        for (sp in shedPositions) {
            val sw = 40f
            val sd = 28f
            val sh = 22f
            val b1 = sp + Vector3(-sw, -sd, 0f)
            val b2 = sp + Vector3(sw, -sd, 0f)
            val b3 = sp + Vector3(sw, sd, 0f)
            val b4 = sp + Vector3(-sw, sd, 0f)
            val t1 = sp + Vector3(-sw, -sd, sh)
            val t2 = sp + Vector3(sw, -sd, sh)
            val t3 = sp + Vector3(sw, sd, sh)
            val t4 = sp + Vector3(-sw, sd, sh)
            polys.add(Poly3D(t1, t2, t3, t4, SCRAP))
            polys.add(Poly3D(b1, b2, t2, t1, RUST))
            polys.add(Poly3D(b2, b3, t3, t2, RUST))
            polys.add(Poly3D(b3, b4, t4, t3, METAL_BARE))
            polys.add(Poly3D(b4, b1, t1, t4, RUST))
        }

        // Debris props near mid-ring (static visual anchors)
        val debris = listOf(
            Vector3(200f, 380f, 0f),
            Vector3(-350f, 150f, 0f),
            Vector3(380f, -200f, 0f),
            Vector3(-180f, -400f, 0f)
        )
        for (d in debris) {
            val s = 18f
            val h = 10f
            polys.add(
                Poly3D(
                    d + Vector3(-s, -s, 0f),
                    d + Vector3(s, -s, 0f),
                    d + Vector3(s, s, h),
                    d + Vector3(-s, s, h),
                    STONE
                )
            )
        }

        polys
    }

    // Legacy stadium alias used by older code paths
    val staticStadiumPolys: List<Poly3D> by lazy {
        staticColosseumPolys
    }

    fun buildPlayerCarPolys(
        player: PlayerCar,
        wheelRotation: Float = 0f,
        isBoosting: Boolean = false
    ): List<Poly3D> {
        val polys = mutableListOf<Poly3D>()
        val worldPos = Vector3(player.x, player.y, 0f)
        val yaw = player.angle
        val steer = player.steerAngle
        val roll = (-player.angularVelocity * 1.8f).coerceIn(-12f, 12f)
        val pitch = (-player.speed * 0.4f).coerceIn(-6f, 6f)

        fun transform(localPt: Vector3): Vector3 {
            return localPt.rotateX(pitch).rotateY(roll).rotateZ(yaw) + worldPos
        }

        // Phase D materials — rusted dark muscle, not flat single color
        val bodyPrimary = Color(0xFF2A3036)
        val bodyRust = Color(0xFF3E342E)
        val bodyPanel = Color(0xFF1E2428)
        val hoodCarbon = Color(0xFF151A1E)
        val roofMetal = Color(0xFF37474F)
        val glass = Color(0xFF0D1B22).copy(alpha = 0.92f)
        val chrome = Color(0xFFB0BEC5)
        val rubber = Color(0xFF121212)
        val rim = Color(0xFFCFD8DC)
        val hazardY = Color(0xFFFFD600)
        val emissiveHead = Color(0xFFFFF59D)
        val emissiveTail = Color(0xFFFF1744)

        val l = 26f
        val w = 13f
        val zBase = 3.5f
        val zMid = 11.5f

        val cFL = transform(Vector3(l, -w, zBase))
        val cFR = transform(Vector3(l, w, zBase))
        val cBL = transform(Vector3(-l, -w, zBase))
        val cBR = transform(Vector3(-l, w, zBase))
        val cFLT = transform(Vector3(l, -w, zMid))
        val cFRT = transform(Vector3(l, w, zMid))
        val cBLT = transform(Vector3(-l, -w, zMid))
        val cBRT = transform(Vector3(-l, w, zMid))

        polys.add(Poly3D(cFLT, cFRT, cBRT, cBLT, bodyPrimary))
        polys.add(Poly3D(cFL, cFR, cFRT, cFLT, bodyPanel))
        polys.add(Poly3D(cBR, cBL, cBLT, cBRT, bodyRust))
        polys.add(Poly3D(cBL, cFL, cFLT, cBLT, bodyRust))
        polys.add(Poly3D(cFR, cBR, cBRT, cFRT, bodyPrimary))

        // Cabin
        val cabBack = -18f
        val cabFront = 6f
        val cabW = 10f
        val zRoof = 19.5f
        val cabFL = transform(Vector3(cabFront, -cabW, zMid))
        val cabFR = transform(Vector3(cabFront, cabW, zMid))
        val cabBL = transform(Vector3(cabBack, -cabW, zMid))
        val cabBR = transform(Vector3(cabBack, cabW, zMid))
        val rFL = transform(Vector3(cabFront - 4f, -cabW * 0.82f, zRoof))
        val rFR = transform(Vector3(cabFront - 4f, cabW * 0.82f, zRoof))
        val rBL = transform(Vector3(cabBack + 4f, -cabW * 0.82f, zRoof))
        val rBR = transform(Vector3(cabBack + 4f, cabW * 0.82f, zRoof))
        polys.add(Poly3D(cabFL, cabFR, rFR, rFL, glass))
        polys.add(Poly3D(rBL, rBR, cabBR, cabBL, glass))
        polys.add(Poly3D(rFL, rFR, rBR, rBL, roofMetal))
        polys.add(Poly3D(cabBL, cabFL, rFL, rBL, glass))
        polys.add(Poly3D(cabFR, cabBR, rBR, rFR, glass))

        // Supercharger
        val blowerX = 14f
        val bW = 4.5f
        val bL = 6.5f
        val bH = 6f
        polys.add(
            Poly3D(
                transform(Vector3(blowerX + bL, -bW, zMid + bH)),
                transform(Vector3(blowerX + bL, bW, zMid + bH)),
                transform(Vector3(blowerX - bL, bW, zMid + bH * 0.8f)),
                transform(Vector3(blowerX - bL, -bW, zMid + bH * 0.8f)),
                chrome
            )
        )
        polys.add(
            Poly3D(
                transform(Vector3(blowerX + bL, -bW, zMid)),
                transform(Vector3(blowerX + bL, bW, zMid)),
                transform(Vector3(blowerX + bL, bW, zMid + bH)),
                transform(Vector3(blowerX + bL, -bW, zMid + bH)),
                Color(0xFFFF1744)
            )
        )

        // Bull bar
        val ramFrontX = l + 4f
        val ramW = w + 1f
        val ramTopZ = zMid + 2.5f
        polys.add(
            Poly3D(
                transform(Vector3(ramFrontX, -ramW, zBase)),
                transform(Vector3(ramFrontX, ramW, zBase)),
                transform(Vector3(ramFrontX, ramW, ramTopZ)),
                transform(Vector3(ramFrontX, -ramW, ramTopZ)),
                Color(0xFF455A64)
            )
        )
        polys.add(
            Poly3D(
                transform(Vector3(ramFrontX + 0.1f, -6f, zBase + 1f)),
                transform(Vector3(ramFrontX + 0.1f, -2f, zBase + 1f)),
                transform(Vector3(ramFrontX + 0.1f, -2f, ramTopZ - 1f)),
                transform(Vector3(ramFrontX + 0.1f, -6f, ramTopZ - 1f)),
                hazardY
            )
        )
        polys.add(
            Poly3D(
                transform(Vector3(ramFrontX + 0.1f, 2f, zBase + 1f)),
                transform(Vector3(ramFrontX + 0.1f, 6f, zBase + 1f)),
                transform(Vector3(ramFrontX + 0.1f, 6f, ramTopZ - 1f)),
                transform(Vector3(ramFrontX + 0.1f, 2f, ramTopZ - 1f)),
                hazardY
            )
        )

        // Headlights (emissive)
        polys.add(
            Poly3D(
                transform(Vector3(l + 0.5f, -7f, zMid - 2f)),
                transform(Vector3(l + 0.5f, -3f, zMid - 2f)),
                transform(Vector3(l + 0.5f, -3f, zMid + 1f)),
                transform(Vector3(l + 0.5f, -7f, zMid + 1f)),
                emissiveHead, emissive = true
            )
        )
        polys.add(
            Poly3D(
                transform(Vector3(l + 0.5f, 3f, zMid - 2f)),
                transform(Vector3(l + 0.5f, 7f, zMid - 2f)),
                transform(Vector3(l + 0.5f, 7f, zMid + 1f)),
                transform(Vector3(l + 0.5f, 3f, zMid + 1f)),
                emissiveHead, emissive = true
            )
        )
        // Tail lights
        polys.add(
            Poly3D(
                transform(Vector3(-l - 0.3f, -8f, zMid - 1f)),
                transform(Vector3(-l - 0.3f, -3f, zMid - 1f)),
                transform(Vector3(-l - 0.3f, -3f, zMid + 2f)),
                transform(Vector3(-l - 0.3f, -8f, zMid + 2f)),
                emissiveTail, emissive = true
            )
        )
        polys.add(
            Poly3D(
                transform(Vector3(-l - 0.3f, 3f, zMid - 1f)),
                transform(Vector3(-l - 0.3f, 8f, zMid - 1f)),
                transform(Vector3(-l - 0.3f, 8f, zMid + 2f)),
                transform(Vector3(-l - 0.3f, 3f, zMid + 2f)),
                emissiveTail, emissive = true
            )
        )

        // Exhausts + nitro flame
        val exFrontX = 8f
        val exBackX = -12f
        val exLeftY = -w - 2f
        val exRightY = w + 2f
        val exZ = zBase + 2.5f
        polys.add(
            Poly3D(
                transform(Vector3(exFrontX, exLeftY, exZ)),
                transform(Vector3(exBackX, exLeftY, exZ)),
                transform(Vector3(exBackX, exLeftY, exZ + 2f)),
                transform(Vector3(exFrontX, exLeftY, exZ + 2f)),
                METAL_BARE
            )
        )
        polys.add(
            Poly3D(
                transform(Vector3(exFrontX, exRightY, exZ)),
                transform(Vector3(exBackX, exRightY, exZ)),
                transform(Vector3(exBackX, exRightY, exZ + 2f)),
                transform(Vector3(exFrontX, exRightY, exZ + 2f)),
                METAL_BARE
            )
        )
        if (isBoosting) {
            val fBack = exBackX - 12f
            polys.add(
                Poly3D(
                    transform(Vector3(exBackX, exLeftY, exZ)),
                    transform(Vector3(fBack, exLeftY - 1f, exZ)),
                    transform(Vector3(fBack, exLeftY - 1f, exZ + 3f)),
                    transform(Vector3(exBackX, exLeftY, exZ + 3f)),
                    Color(0xFF00E5FF), isDoubleSided = true, emissive = true
                )
            )
            polys.add(
                Poly3D(
                    transform(Vector3(exBackX, exRightY, exZ)),
                    transform(Vector3(fBack, exRightY + 1f, exZ)),
                    transform(Vector3(fBack, exRightY + 1f, exZ + 3f)),
                    transform(Vector3(exBackX, exRightY, exZ + 3f)),
                    Color(0xFF00E5FF), isDoubleSided = true, emissive = true
                )
            )
        }

        // Turret
        val turretCenter = transform(Vector3(-6f, 0f, zRoof))
        val turretRad = Math.toRadians(player.turretAngle.toDouble()).toFloat()
        val tFwd = Vector3(cos(turretRad), sin(turretRad), 0f)
        val tRight = Vector3(-sin(turretRad), cos(turretRad), 0f)
        val tRadius = 5.5f
        val tHeight = 4f
        for (i in 0 until 6) {
            val a1 = i * (2 * PI / 6).toFloat()
            val a2 = (i + 1) * (2 * PI / 6).toFloat()
            polys.add(
                Poly3D(
                    turretCenter + Vector3(cos(a1) * tRadius, sin(a1) * tRadius, 0f),
                    turretCenter + Vector3(cos(a2) * tRadius, sin(a2) * tRadius, 0f),
                    turretCenter + Vector3(cos(a2) * tRadius * 0.85f, sin(a2) * tRadius * 0.85f, tHeight),
                    turretCenter + Vector3(cos(a1) * tRadius * 0.85f, sin(a1) * tRadius * 0.85f, tHeight),
                    Color(0xFF37474F)
                )
            )
        }
        val recoil = if (player.shootCooldown > 0) 2.5f else 0f
        val barrelStart = turretCenter + Vector3(0f, 0f, tHeight * 0.7f) - tFwd * recoil
        val barrelLen = 14f
        for (side in listOf(-1.8f, 1.8f)) {
            val b1 = barrelStart + tRight * side
            val b2 = b1 + tFwd * barrelLen
            polys.add(Poly3D(b1, b2, b2 + Vector3(0f, 0f, 1.2f), b1 + Vector3(0f, 0f, 1.2f), Color(0xFF212121), isDoubleSided = true))
        }

        // Wheels — rubber + rim
        val wheelPositions = listOf(
            Triple(16f, -w - 1.2f, steer),
            Triple(16f, w + 1.2f, steer),
            Triple(-16f, -w - 1.2f, 0f),
            Triple(-16f, w + 1.2f, 0f)
        )
        val tireRadius = 6.2f
        val tireWidth = 4.2f
        for ((wx, wy, steerAngle) in wheelPositions) {
            val wCenter = transform(Vector3(wx, wy, tireRadius))
            val isLeft = wy < 0
            val wYaw = yaw + steerAngle
            val wRad = Math.toRadians(wYaw.toDouble()).toFloat()
            val wFwd = Vector3(cos(wRad), sin(wRad), 0f)
            val wSide = Vector3(-sin(wRad), cos(wRad), 0f) * (if (isLeft) -1f else 1f)
            for (i in 0 until 6) {
                val th1 = i * (2 * PI / 6).toFloat() + wheelRotation
                val th2 = (i + 1) * (2 * PI / 6).toFloat() + wheelRotation
                val r1 = wFwd * (cos(th1) * tireRadius) + Vector3(0f, 0f, sin(th1) * tireRadius)
                val r2 = wFwd * (cos(th2) * tireRadius) + Vector3(0f, 0f, sin(th2) * tireRadius)
                val pt1 = wCenter + r1
                val pt2 = wCenter + r2
                val pt3 = wCenter + r2 + wSide * tireWidth
                val pt4 = wCenter + r1 + wSide * tireWidth
                polys.add(Poly3D(pt1, pt2, pt3, pt4, rubber))
                polys.add(Poly3D(wCenter + wSide * tireWidth, pt3, pt4, null, rim))
            }
        }

        // Damage visual stages (Phase H-lite): smoke marker via darker panels when low HP
        val hpPct = (player.health / player.maxHealth).coerceIn(0f, 1f)
        if (hpPct < 0.5f) {
            // scorched hood patch
            polys.add(
                Poly3D(
                    transform(Vector3(8f, -6f, zMid + 0.2f)),
                    transform(Vector3(18f, -6f, zMid + 0.2f)),
                    transform(Vector3(18f, 6f, zMid + 0.2f)),
                    transform(Vector3(8f, 6f, zMid + 0.2f)),
                    Color(0xFF1A120E)
                )
            )
        }
        if (hpPct < 0.25f) {
            // missing side panel hint
            polys.add(
                Poly3D(
                    transform(Vector3(-5f, -w - 0.5f, zBase + 2f)),
                    transform(Vector3(5f, -w - 0.5f, zBase + 2f)),
                    transform(Vector3(5f, -w - 0.5f, zMid)),
                    transform(Vector3(-5f, -w - 0.5f, zMid)),
                    RUST
                )
            )
        }

        return polys
    }

    fun buildEnemyCarPolys(enemy: EnemyCar): List<Poly3D> {
        val polys = mutableListOf<Poly3D>()
        val worldPos = Vector3(enemy.x, enemy.y, 0f)
        val yaw = enemy.angle
        fun transform(localPt: Vector3) = localPt.rotateZ(yaw) + worldPos

        val primary = enemy.color
        val metalDark = Color(0xFF263238)
        val rustSpikes = Color(0xFF8D6E63)
        val glassColor = Color(0xFF1A237E)
        val l = 24f
        val w = 12f
        val zBase = 3f
        val zMid = 11f
        val zRoof = 18f

        val cFL = transform(Vector3(l, -w, zBase))
        val cFR = transform(Vector3(l, w, zBase))
        val cBL = transform(Vector3(-l, -w, zBase))
        val cBR = transform(Vector3(-l, w, zBase))
        val cFLT = transform(Vector3(l, -w, zMid))
        val cFRT = transform(Vector3(l, w, zMid))
        val cBLT = transform(Vector3(-l, -w, zMid))
        val cBRT = transform(Vector3(-l, w, zMid))
        polys.add(Poly3D(cFLT, cFRT, cBRT, cBLT, primary))
        polys.add(Poly3D(cFL, cFR, cFRT, cFLT, metalDark))
        polys.add(Poly3D(cBR, cBL, cBLT, cBRT, metalDark))
        polys.add(Poly3D(cBL, cFL, cFLT, cBLT, primary))
        polys.add(Poly3D(cFR, cBR, cBRT, cFRT, primary))

        val cabFL = transform(Vector3(4f, -w * 0.8f, zMid))
        val cabFR = transform(Vector3(4f, w * 0.8f, zMid))
        val cabBL = transform(Vector3(-14f, -w * 0.8f, zMid))
        val cabBR = transform(Vector3(-14f, w * 0.8f, zMid))
        val rFL = transform(Vector3(0f, -w * 0.7f, zRoof))
        val rFR = transform(Vector3(0f, w * 0.7f, zRoof))
        val rBL = transform(Vector3(-10f, -w * 0.7f, zRoof))
        val rBR = transform(Vector3(-10f, w * 0.7f, zRoof))
        polys.add(Poly3D(cabFL, cabFR, rFR, rFL, glassColor))
        polys.add(Poly3D(rBL, rBR, cabBR, cabBL, glassColor))
        polys.add(Poly3D(rFL, rFR, rBR, rBL, metalDark))

        val ramX = l + 4f
        polys.add(
            Poly3D(
                transform(Vector3(ramX, -w, zBase)),
                transform(Vector3(ramX, w, zBase)),
                transform(Vector3(ramX, w, zMid)),
                transform(Vector3(ramX, -w, zMid)),
                rustSpikes
            )
        )
        for (wp in listOf(Vector3(15f, -w - 2f, 5f), Vector3(15f, w + 2f, 5f), Vector3(-15f, -w - 2f, 5f), Vector3(-15f, w + 2f, 5f))) {
            val wc = transform(wp)
            polys.add(
                Poly3D(
                    wc + Vector3(-4f, 0f, -5f),
                    wc + Vector3(4f, 0f, -5f),
                    wc + Vector3(4f, 0f, 5f),
                    wc + Vector3(-4f, 0f, 5f),
                    Color(0xFF1E1E1E), isDoubleSided = true
                )
            )
        }
        return polys
    }

    fun buildObstaclePolys(obs: Obstacle): List<Poly3D> {
        val polys = mutableListOf<Poly3D>()
        val pos = Vector3(obs.x, obs.y, 0f)
        when (obs.type) {
            "concrete" -> {
                val l = obs.radius * 0.9f
                val w = obs.radius * 0.45f
                val h = obs.radius * 0.7f
                val cColor = if (obs.health < obs.maxHealth * 0.5f) Color(0xFF78909C) else Color(0xFFB0BEC5)
                val b1 = pos + Vector3(-l, -w, 0f)
                val b2 = pos + Vector3(l, -w, 0f)
                val b3 = pos + Vector3(l, w, 0f)
                val b4 = pos + Vector3(-l, w, 0f)
                val t1 = pos + Vector3(-l * 0.85f, -w * 0.6f, h)
                val t2 = pos + Vector3(l * 0.85f, -w * 0.6f, h)
                val t3 = pos + Vector3(l * 0.85f, w * 0.6f, h)
                val t4 = pos + Vector3(-l * 0.85f, w * 0.6f, h)
                polys.add(Poly3D(t1, t2, t3, t4, cColor))
                polys.add(Poly3D(b1, b2, t2, t1, cColor))
                polys.add(Poly3D(b3, b4, t4, t3, cColor))
                polys.add(Poly3D(b4, b1, t1, t4, cColor))
                polys.add(Poly3D(b2, b3, t3, t2, cColor))
                polys.add(
                    Poly3D(
                        pos + Vector3(-l * 0.4f, -w - 0.1f, h * 0.2f),
                        pos + Vector3(l * 0.4f, -w - 0.1f, h * 0.2f),
                        pos + Vector3(l * 0.35f, -w - 0.1f, h * 0.8f),
                        pos + Vector3(-l * 0.35f, -w - 0.1f, h * 0.8f),
                        Color(0xFFFFD600)
                    )
                )
            }
            "fuel_barrel" -> {
                val r = obs.radius * 0.7f
                val h = obs.radius * 1.2f
                for (i in 0 until 6) {
                    val a1 = i * (2 * PI / 6).toFloat()
                    val a2 = (i + 1) * (2 * PI / 6).toFloat()
                    val p1 = pos + Vector3(cos(a1) * r, sin(a1) * r, 0f)
                    val p2 = pos + Vector3(cos(a2) * r, sin(a2) * r, 0f)
                    val p3 = pos + Vector3(cos(a2) * r, sin(a2) * r, h)
                    val p4 = pos + Vector3(cos(a1) * r, sin(a1) * r, h)
                    polys.add(Poly3D(p1, p2, p3, p4, Color(0xFFD32F2F)))
                    polys.add(Poly3D(pos + Vector3(0f, 0f, h), p3, p4, null, Color(0xFFFFEA00)))
                }
            }
            else -> {
                val s = obs.radius * 0.75f
                val h = obs.radius * 0.8f
                val crateColor = Color(0xFF455A64)
                val b1 = pos + Vector3(-s, -s, 0f)
                val b2 = pos + Vector3(s, -s, 0f)
                val b3 = pos + Vector3(s, s, 0f)
                val b4 = pos + Vector3(-s, s, 0f)
                val t1 = pos + Vector3(-s, -s, h)
                val t2 = pos + Vector3(s, -s, h)
                val t3 = pos + Vector3(s, s, h)
                val t4 = pos + Vector3(-s, s, h)
                polys.add(Poly3D(t1, t2, t3, t4, Color(0xFF546E7A)))
                polys.add(Poly3D(b1, b2, t2, t1, crateColor))
                polys.add(Poly3D(b2, b3, t3, t2, crateColor))
                polys.add(Poly3D(b3, b4, t4, t3, crateColor))
                polys.add(Poly3D(b4, b1, t1, t4, crateColor))
            }
        }
        return polys
    }

    fun appendStadiumPolys(
        spectators: List<RobotSpectator>,
        camera: Camera3D,
        out: MutableList<Poly3D>,
        quality: QualityPreset = QualityPreset.HIGH,
        arenaRadius: Float = ArenaConstants.ARENA_RADIUS,
        wallHeight: Float = ArenaConstants.WALL_HEIGHT
    ) {
        out.addAll(staticFloorPolys)
        out.addAll(staticBarrierPolys)
        out.addAll(staticColosseumPolys)

        var addedRobots = 0
        val maxRobots = quality.maxCrowdRobots
        for (robot in spectators) {
            if (addedRobots >= maxRobots) break
            val rad = Math.toRadians(robot.angle.toDouble()).toFloat()
            val dist = robot.radius
            val baseZ = wallHeight + 10f + (dist - arenaRadius) * 0.55f + robot.bounceOffset * 1.5f
            val rPos = Vector3(cos(rad) * dist, sin(rad) * dist, baseZ)
            if (!camera.isSphereInFrustum(rPos, 20f)) continue
            addedRobots++
            val bSize = 6f
            val hSize = 10f
            val torso = Color(0xFF455A64)
            out.add(
                Poly3D(
                    rPos + Vector3(-bSize, -bSize, hSize),
                    rPos + Vector3(bSize, -bSize, hSize),
                    rPos + Vector3(bSize, bSize, hSize),
                    rPos + Vector3(-bSize, bSize, hSize),
                    torso
                )
            )
            out.add(
                Poly3D(
                    rPos + Vector3(-bSize, -bSize, 0f),
                    rPos + Vector3(bSize, -bSize, 0f),
                    rPos + Vector3(bSize, -bSize, hSize),
                    rPos + Vector3(-bSize, -bSize, hSize),
                    torso
                )
            )
            out.add(
                Poly3D(
                    rPos + Vector3(-bSize * 0.7f, -bSize - 0.2f, hSize * 0.5f),
                    rPos + Vector3(bSize * 0.7f, -bSize - 0.2f, hSize * 0.5f),
                    rPos + Vector3(bSize * 0.7f, -bSize - 0.2f, hSize * 0.8f),
                    rPos + Vector3(-bSize * 0.7f, -bSize - 0.2f, hSize * 0.8f),
                    robot.eyeColor, emissive = true
                )
            )
        }
    }

    fun buildStadiumPolys(
        spectators: List<RobotSpectator>,
        arenaRadius: Float = ArenaConstants.ARENA_RADIUS,
        wallHeight: Float = ArenaConstants.WALL_HEIGHT,
        grandstandHeight: Float = 140f
    ): List<Poly3D> {
        val polys = mutableListOf<Poly3D>()
        polys.addAll(staticFloorPolys)
        polys.addAll(staticBarrierPolys)
        polys.addAll(staticColosseumPolys)
        return polys
    }
}
