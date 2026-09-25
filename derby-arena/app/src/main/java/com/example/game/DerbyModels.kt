package com.example.game

import androidx.compose.ui.graphics.Color
import kotlin.math.*

// Vector 2D helper
data class Vec2(val x: Float, val y: Float) {
    operator fun plus(other: Vec2) = Vec2(x + other.x, y + other.y)
    operator fun minus(other: Vec2) = Vec2(x - other.x, y - other.y)
    operator fun times(scalar: Float) = Vec2(x * scalar, y * scalar)
    operator fun div(scalar: Float) = Vec2(x / scalar, y / scalar)
    fun length() = sqrt(x * x + y * y)
    fun normalized(): Vec2 {
        val len = length()
        return if (len == 0f) Vec2(0f, 0f) else this / len
    }
    fun dot(other: Vec2) = x * other.x + y * other.y
}

/** Arena 01 physical constants — keep in sync with 3D geometry and collision. */
object ArenaConstants {
    const val ARENA_RADIUS = 750f
    const val BARRIER_INNER_RADIUS = 720f
    const val PLAYABLE_RADIUS = 700f
    const val WALL_HEIGHT = 45f
    const val CAR_RADIUS = 26f
}

/** Performance / visual quality tiers (Phase K). */
enum class QualityPreset(
    val label: String,
    val maxParticles: Int,
    val maxCrowdRobots: Int,
    val floorDetail: Boolean,
    val shadows: Boolean,
    val fogQuality: Float,
    val polyBudgetScale: Float
) {
    LOW("LOW", maxParticles = 18, maxCrowdRobots = 6, floorDetail = false, shadows = false, fogQuality = 0.35f, polyBudgetScale = 0.55f),
    MEDIUM("MEDIUM", maxParticles = 28, maxCrowdRobots = 12, floorDetail = true, shadows = true, fogQuality = 0.55f, polyBudgetScale = 0.8f),
    HIGH("HIGH", maxParticles = 40, maxCrowdRobots = 18, floorDetail = true, shadows = true, fogQuality = 0.75f, polyBudgetScale = 1f),
    ULTRA("ULTRA", maxParticles = 64, maxCrowdRobots = 24, floorDetail = true, shadows = true, fogQuality = 1f, polyBudgetScale = 1.2f)
}

// ===================== BATTLE ROYALE / COMBAT DATA MODELS (network-friendly) =====================

/** Weapon archetypes. New weapons only need a WeaponSpec entry in WeaponSystem. */
enum class WeaponId { MACHINE_GUN, SHOTGUN, ROCKET, EMP, HEAVY_CANNON }

/** Static tuning data for a weapon. */
data class WeaponSpec(
    val id: WeaponId,
    val label: String,
    val damage: Float,
    val fireRateFrames: Int,       // frames between shots (60 fps)
    val range: Float,              // game units
    val spreadDeg: Float,
    val recoil: Float,
    val magazine: Int,
    val reloadFrames: Int,
    val heatPerShot: Float,
    val projectileSpeed: Float,
    val pellets: Int = 1,
    val splashRadius: Float = 0f,
    val stunFrames: Int = 0,
    val projectileKind: String = "tracer" // tracer | rocket | shell | emp
)

/** Mutable per-vehicle weapon state (ammo/reload/heat). */
data class WeaponState(
    var id: WeaponId = WeaponId.MACHINE_GUN,
    var ammo: Int = 80,
    var reserve: Int = 240,
    var cooldown: Int = 0,
    var reloadTimer: Int = 0,
    var heat: Float = 0f,
    var overheated: Boolean = false
)

enum class LootType { AMMO, HEALTH, ARMOR, NITRO, WEAPON, REPAIR, UPGRADE }

data class LootItem(
    val id: Int,
    var x: Float,
    var y: Float,
    val type: LootType,
    val weapon: WeaponId? = null,
    var active: Boolean = true,
    var respawnTimer: Int = 0,
    var bobPhase: Float = 0f
)

/** Shrinking safe-zone state. */
data class ZoneState(
    var centerX: Float = 0f,
    var centerY: Float = 0f,
    var radius: Float = 700f,
    var targetRadius: Float = 700f,
    var targetCenterX: Float = 0f,
    var targetCenterY: Float = 0f,
    var phase: Int = 0,
    var phaseTimer: Float = 30f,
    var shrinking: Boolean = false,
    var damagePerSecond: Float = 4f,
    var finalCircle: Boolean = false
)

/** Authoritative player input snapshot (what a client would send to a server). */
data class PlayerInput(
    var throttle: Float = 0f,
    var steer: Float = 0f,
    var brake: Boolean = false,
    var nitro: Boolean = false,
    var handbrake: Boolean = false,
    var fire: Boolean = false,
    var aimAngleDeg: Float = -90f,
    var aimActive: Boolean = false
)

/** Combat events emitted by the simulation; consumed by VFX, HUD, audio and (later) network replication. */
sealed class CombatEvent {
    abstract val x: Float
    abstract val y: Float
    data class Hit(override val x: Float, override val y: Float, val targetId: String, val amount: Float, val fromPlayer: Boolean) : CombatEvent()
    data class Kill(override val x: Float, override val y: Float, val victimId: String, val killerId: String) : CombatEvent()
    data class Explosion(override val x: Float, override val y: Float, val radius: Float) : CombatEvent()
    data class Pickup(override val x: Float, override val y: Float, val type: LootType, val byPlayer: Boolean) : CombatEvent()
    data class Muzzle(override val x: Float, override val y: Float, val angleRad: Float, val fromPlayer: Boolean, val kind: String) : CombatEvent()
    data class Impact(override val x: Float, override val y: Float, val vx: Float, val vy: Float, val heavy: Boolean) : CombatEvent()
    data class ZoneDamage(override val x: Float, override val y: Float, val amount: Float) : CombatEvent()
    data class PlayerDamaged(override val x: Float, override val y: Float, val amount: Float, val dirDeg: Float) : CombatEvent()
}

/** HUD kill-feed line. */
data class KillFeedEntry(val text: String, val frame: Long)

/** Camera state produced by CameraSystem and consumed by GpuSync (kept separate for replay/network). */
data class CameraState(
    var shake: Float = 0f,
    var recoil: Float = 0f,
    var aimBlend: Float = 0f,
    var driftYaw: Float = 0f
)


data class PlayerCar(
    var x: Float = 0f,
    var y: Float = 0f,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var angle: Float = -90f, // heading in degrees
    var angularVelocity: Float = 0f,
    var speed: Float = 0f,
    var steerAngle: Float = 0f,
    var health: Float = 100f,
    var maxHealth: Float = 100f,
    var armor: Float = 10f, // damage reduction
    var score: Int = 0,
    var scrap: Int = 0,
    var turretAngle: Float = -90f,
    var shootCooldown: Int = 0,
    var isDead: Boolean = false,

    // 3D Dynamics & Boost
    var nitro: Float = 100f,
    var maxNitro: Float = 100f,
    var isNitroActive: Boolean = false,
    var isHandbraking: Boolean = false,

    // Upgrades
    var engineLevel: Int = 1,
    var armorLevel: Int = 1,
    var turretLevel: Int = 1,
    var magnetLevel: Int = 1,

    // Battle Royale additions
    var armorPoints: Float = 0f,
    var maxArmorPoints: Float = 100f,
    var weapon: WeaponState = WeaponState(),
    var kills: Int = 0,
    var stunFrames: Int = 0,
    var damageBoostFrames: Int = 0,
    var lastHitDirDeg: Float = 0f,
    var lastHitFrame: Long = -1000L,
    var inZone: Boolean = true
)

data class EnemyCar(
    val id: Int,
    var name: String,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var angle: Float = 0f,
    var speed: Float = 0f,
    var steerAngle: Float = 0f,
    var health: Float = 80f,
    var maxHealth: Float = 80f,
    var turretAngle: Float = 0f,
    var shootCooldown: Int = 0,
    var color: Color = Color(0xFFE57373),
    var isDead: Boolean = false,
    var isWreckage: Boolean = false, // remains on floor as scrap obstacle
    var type: String = "Scrap Raider", // "Scrap Raider", "Armored Juggernaut", "Acid Spitter"
    var state: String = "CHASE", // SEARCH, CHASE, ATTACK, DODGE, RETREAT, LOOT, ZONE
    var changeStateCooldown: Int = 0,

    // Battle Royale additions
    var armorPoints: Float = 0f,
    var weapon: WeaponState = WeaponState(id = WeaponId.MACHINE_GUN, ammo = 999, reserve = 9999),
    var targetKind: String = "player", // player | enemy | loot | point
    var targetId: Int = -1,
    var targetX: Float = 0f,
    var targetY: Float = 0f,
    var strafeDir: Float = 1f,
    var stateTimer: Int = 0,
    var stunFrames: Int = 0,
    var recentDamage: Float = 0f,
    var killerId: String = "",
    var modelVariant: Int = 0,
    var isBoss: Boolean = false,
    var throttle: Float = 0f
)

data class Obstacle(
    val id: Int,
    var x: Float,
    var y: Float,
    var radius: Float,
    var type: String, // "concrete", "fuel_barrel", "scrap_pile", "steel_box"
    var health: Float,
    var maxHealth: Float,
    var isDestroyed: Boolean = false,
    var isExplosive: Boolean = false,
    var scale: Float = 1f
)

data class Bullet(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val owner: String, // "player", "enemy"
    val damage: Float,
    var isDead: Boolean = false,
    val ownerId: String = owner,          // "player" or "enemy:<id>" for AI-vs-AI attribution
    val kind: String = "tracer",          // tracer | rocket | shell | emp | pellet
    var life: Int = 90,                   // frames remaining
    val splashRadius: Float = 0f,
    val stunFrames: Int = 0
)

data class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val color: Color,
    var size: Float,
    var life: Float = 1f,
    val decay: Float = 0.05f,
    val type: String = "spark", // "spark", "smoke", "fire", "debris", "skid", "laser_impact", "fog"
    var angle: Float = 0f,
    var angularVelocity: Float = 0f,
    var customText: String = "" // For floating "CRUNCH!" or "+10 Scrap" text
)

data class ScrapItem(
    val id: Int,
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    val value: Int = 10,
    var isCollected: Boolean = false
)

data class RobotSpectator(
    val id: Int,
    val angle: Float, // polar coordinate angle around center
    val radius: Float, // radius from center
    var bounceOffset: Float = 0f,
    var eyeColor: Color = Color(0xFF00E5FF),
    var cheerCooldown: Int = 0,
    var cheerEmoji: String = "",
    var isJumping: Boolean = false
)

// Different Dystopian Themes
enum class DerbyTheme(
    val title: String,
    val groundColor: Color,
    val gridColor: Color,
    val skyColor: Color,
    val fogColor: Color,
    val ambientParticlesColor: Color,
    val desc: String
) {
    TOXIC_SMOG(
        title = "Toxic Smog",
        groundColor = Color(0xFF1E1F1A),
        gridColor = Color(0xFF2E3324),
        skyColor = Color(0xFF0D0E0B),
        fogColor = Color(0xFF3B442B), // Greenish smog
        ambientParticlesColor = Color(0xFF7F9E58),
        desc = "A chemical mist blankets the arena floor with floating green isotopes."
    ),
    MIDNIGHT_ASH(
        title = "Midnight Ash",
        groundColor = Color(0xFF111115),
        gridColor = Color(0xFF22222A),
        skyColor = Color(0xFF050508),
        fogColor = Color(0xFF222228), // Dark grey mist
        ambientParticlesColor = Color(0xFFB0BEC5),
        desc = "Ashen fog and glowing embers drift across a freezing steel colosseum."
    ),
    ACID_RAIN(
        title = "Acid Rain",
        groundColor = Color(0xFF161C1D),
        gridColor = Color(0xFF2A3D3E),
        skyColor = Color(0xFF090D0E),
        fogColor = Color(0xFF263D3E), // Teal acidic fog
        ambientParticlesColor = Color(0xFF00E5FF),
        desc = "Slick metal plates under static teal sky and highly corrosive mist."
    ),
    CRIMSON_WASTE(
        title = "Crimson Waste",
        groundColor = Color(0xFF201212),
        gridColor = Color(0xFF3B1F1F),
        skyColor = Color(0xFF0E0505),
        fogColor = Color(0xFF4A1F1F), // Blood red dust
        ambientParticlesColor = Color(0xFFFF5252),
        desc = "Rusty orange scrap metal floor swept by crimson fallout winds."
    )
}
