package com.example.game.systems

import com.example.game.*
import org.json.JSONObject
import kotlin.math.hypot

/**
 * ArenaSystem: loads the collision layout baked by the asset pipeline
 * (assets/models/arena/arena01_layout.json) so gameplay colliders match the GPU geometry exactly.
 */
class ArenaSystem(private val assetReader: ((String) -> String?)? = null) {

    companion object {
        private const val TAG = "ArenaSystem"
        const val LAYOUT_PATH = "models/arena/arena01_layout.json"
    }

    fun spawnArena01Obstacles(): List<Obstacle> {
        val json = try { assetReader?.invoke(LAYOUT_PATH) } catch (t: Throwable) { android.util.Log.w(TAG, "layout read failed: ${t.message}"); null }
        if (json == null) {
            android.util.Log.w(TAG, "Layout JSON missing; using fallback ring layout")
            return fallbackLayout()
        }
        return try {
            val root = JSONObject(json)
            val arr = root.getJSONArray("obstacles")
            val list = ArrayList<Obstacle>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val kind = o.getString("type")
                val type = when (kind) {
                    "barrel" -> "fuel_barrel"
                    "concrete" -> "concrete"
                    "tires" -> "scrap_pile"
                    "wreck" -> "steel_box"
                    else -> "concrete"
                }
                val hp = when (kind) { "barrel" -> 30f; "tires" -> 60f; "rubble" -> 400f; else -> 9999f }
                list.add(
                    Obstacle(
                        id = i + 1,
                        x = o.getDouble("x").toFloat(),
                        y = o.getDouble("y").toFloat(),
                        radius = o.getDouble("radius").toFloat(),
                        type = type,
                        health = hp,
                        maxHealth = hp,
                        isExplosive = kind == "barrel",
                        scale = o.optDouble("height", 1.0).toFloat()
                    )
                )
            }
            android.util.Log.i(TAG, "Arena layout loaded: ${list.size} colliders")
            list
        } catch (t: Throwable) {
            android.util.Log.e(TAG, "Layout parse failed", t)
            fallbackLayout()
        }
    }

    private fun fallbackLayout(): List<Obstacle> {
        val list = mutableListOf<Obstacle>()
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            list.add(Obstacle(i + 1, (Math.cos(a) * 380).toFloat(), (Math.sin(a) * 380).toFloat(), 40f, "concrete", 9999f, 9999f))
        }
        return list
    }

    fun checkLossCondition(player: PlayerCar): Boolean = player.health <= 0f || player.isDead

    fun checkWinCondition(enemies: List<EnemyCar>): Boolean = enemies.isNotEmpty() && enemies.all { it.isDead || it.isWreckage }

    /** Distance from the barrier for HUD / camera framing. */
    fun distanceToWall(x: Float, y: Float): Float = ArenaConstants.PLAYABLE_RADIUS - hypot(x, y)
}
