package com.example.game.engine3d.gpu

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.SurfaceView
import android.view.TextureView
import com.example.game.GameViewModel
import com.example.game.QualityPreset
import com.google.android.filament.*
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.utils.Utils

/**
 * GpuRenderSystem (FilamentWorld): owns Engine / Renderer / View / Scene / Camera / SwapChain lifecycle,
 * loads the baked GLB world, and runs the Choreographer frame loop:
 *   snapshot(GameViewModel) → GpuStateSynchronizer → Filament render.
 * Survives Activity recreation via destroy()/re-create; pause/resume stop the frame loop only.
 */
class FilamentWorld(private val context: Context) {

    companion object {
        private const val TAG = "FilamentWorld"
        const val WORLD_SCALE = GpuStateSynchronizer.WORLD_SCALE
        private const val ENEMY_POOL = 7
        private const val TRACER_POOL = 48
        private const val ROCKET_POOL = 12
        private const val LOOT_PER_TYPE = 10
        private val PARTICLE_TYPES = listOf("spark", "smoke", "dust", "fire", "debris", "tire_smoke")

        init {
            try { Filament.init() } catch (t: Throwable) { Log.w(TAG, "Filament.init: ${t.message}") }
            try { Utils.init() } catch (t: Throwable) { Log.w(TAG, "Utils.init: ${t.message}") }
        }
    }

    private var engine: Engine? = null
    private var renderer: Renderer? = null
    private var scene: Scene? = null
    private var view: View? = null
    private var camera: Camera? = null
    private var swapChain: SwapChain? = null
    private var uiHelper: UiHelper? = null
    private var displayHelper: DisplayHelper? = null
    private var choreographer: Choreographer? = null
    private val frameCallback = FrameCallback()
    private var running = false
    private var aspect = 16.0 / 9.0
    @Entity private var sunEntity = 0
    @Entity private var cameraEntity = 0
    private var ibl: IblGenerator.Result? = null

    private var gltfLoader: GltfAssetLoader? = null
    private var sync: GpuStateSynchronizer? = null
    private val cameraController = CameraController()

    private var playerEntities: GpuStateSynchronizer.VehicleEntities? = null
    private val enemyEntities = ArrayList<GpuStateSynchronizer.VehicleEntities?>()
    private val enemyVariant = ArrayList<Int>()
    private val enemySlotUsed = BooleanArray(ENEMY_POOL)
    private var tracers: Array<FilamentInstance?> = emptyArray()
    private var enemyTracers: Array<FilamentInstance?> = emptyArray()
    private var rockets: Array<FilamentInstance?> = emptyArray()
    private val particlePools = HashMap<String, Array<FilamentInstance?>>()
    private val lootPools = ArrayList<Array<FilamentInstance?>>()
    private var zoneRoot = 0
    private val crowdGroups = IntArray(24)
    private var crowdVisibleGroups = 24
    private var budget = DeviceTier.budget(QualityPreset.HIGH)
    private var startNanos = 0L
    private var lastFrameNanos = 0L
    private var frameCounter = 0L

    var status: GpuStatus = GpuStatus(GpuBackendState.UNINITIALIZED, "GPU renderer not initialized")
        private set
    var onStatusChanged: ((GpuStatus) -> Unit)? = null
    private var boundViewModel: GameViewModel? = null

    fun bindViewModel(vm: GameViewModel) {
        boundViewModel = vm
        budget = DeviceTier.budget(vm.qualityPreset.value)
    }

    fun setQuality(preset: QualityPreset) {
        budget = DeviceTier.budget(preset)
        crowdVisibleGroups = budget.crowdGroups
        applyViewOptions()
    }

    fun createTextureView(): TextureView {
        val tv = TextureView(context).apply { isOpaque = true }
        initialize { helperFor(tv) }
        return tv
    }

    fun createSurfaceView(): SurfaceView {
        val sv = SurfaceView(context)
        initialize { helperFor(sv) }
        return sv
    }

    private fun initialize(attach: () -> Unit) {
        try {
            setupEngine()
            setupLighting()
            attach()
            loadAssets()
            status = GpuStatus(GpuBackendState.ACTIVE, "FILAMENT PBR · ${budget.preset.label}")
            onStatusChanged?.invoke(status)
            Log.i(TAG, "GPU world initialized (${budget.preset})")
        } catch (t: Throwable) {
            Log.e(TAG, "GPU world init failed", t)
            status = GpuStatus(GpuBackendState.FAILED, "GPU FAILED: ${t.message}")
            onStatusChanged?.invoke(status)
        }
    }

    fun start() {
        if (status.state != GpuBackendState.ACTIVE || running) return
        running = true
        startNanos = System.nanoTime()
        lastFrameNanos = startNanos
        choreographer = Choreographer.getInstance()
        choreographer?.postFrameCallback(frameCallback)
    }

    fun stop() {
        running = false
        choreographer?.removeFrameCallback(frameCallback)
    }

    fun destroy() {
        stop()
        try { uiHelper?.detach() } catch (_: Throwable) {}
        uiHelper = null
        val eng = engine ?: return
        try {
            swapChain?.let { eng.destroySwapChain(it) }
            swapChain = null
            eng.flushAndWait()
            displayHelper?.detach()
            gltfLoader?.destroy()
            gltfLoader = null
            ibl?.let { eng.destroyIndirectLight(it.indirectLight); eng.destroySkybox(it.skybox); eng.destroyTexture(it.texture) }
            ibl = null
            scene?.skybox?.let { if (ibl == null) runCatching { eng.destroySkybox(it) } }
            if (sunEntity != 0) { eng.destroyEntity(sunEntity); EntityManager.get().destroy(sunEntity); sunEntity = 0 }
            view?.let { eng.destroyView(it) }
            scene?.let { eng.destroyScene(it) }
            renderer?.let { eng.destroyRenderer(it) }
            if (cameraEntity != 0) { eng.destroyCameraComponent(cameraEntity); EntityManager.get().destroy(cameraEntity); cameraEntity = 0 }
            eng.destroy()
        } catch (t: Throwable) {
            Log.e(TAG, "destroy error", t)
        }
        engine = null; renderer = null; scene = null; view = null; camera = null
        status = GpuStatus(GpuBackendState.UNINITIALIZED, "Destroyed")
    }

    // ------------------------------------------------------------------ surface lifecycle
    private fun helperFor(target: android.view.View) {
        displayHelper = DisplayHelper(context)
        val helper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
        helper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                val eng = engine ?: return
                val rend = renderer ?: return
                swapChain?.let { eng.destroySwapChain(it) }
                swapChain = eng.createSwapChain(surface, helper.swapChainFlags)
                displayHelper?.attach(rend, target.display)
                Log.i(TAG, "SwapChain (re)created")
            }

            override fun onDetachedFromSurface() {
                displayHelper?.detach()
                val eng = engine
                val sc = swapChain
                if (eng != null && sc != null) {
                    eng.destroySwapChain(sc)
                    eng.flushAndWait()
                    swapChain = null
                }
            }

            override fun onResized(width: Int, height: Int) {
                val v = view ?: return
                aspect = width.toDouble() / height.coerceAtLeast(1).toDouble()
                v.viewport = Viewport(0, 0, width, height)
            }
        }
        if (target is TextureView) helper.attachTo(target) else helper.attachTo(target as SurfaceView)
        uiHelper = helper
    }

    // ------------------------------------------------------------------ engine / view
    private fun setupEngine() {
        val eng = Engine.Builder().featureLevel(Engine.FeatureLevel.FEATURE_LEVEL_1).build()
        engine = eng
        renderer = eng.createRenderer()
        scene = eng.createScene()
        view = eng.createView()
        cameraEntity = EntityManager.get().create()
        camera = eng.createCamera(cameraEntity)
        sync = GpuStateSynchronizer(eng)
        view!!.scene = scene
        view!!.camera = camera
        camera!!.setExposure(16f, 1f / 125f, 100f)
        applyViewOptions()
        try {
            view!!.colorGrading = ColorGrading.Builder().toneMapping(ColorGrading.ToneMapping.ACES).build(eng)
        } catch (t: Throwable) { Log.w(TAG, "Color grading: ${t.message}") }
        try {
            view!!.setFogOptions(View.FogOptions().apply {
                enabled = true
                distance = 38.0f
                density = 0.028f
                height = 0f
                heightFalloff = 0.22f
                maximumOpacity = 0.62f
                color = floatArrayOf(0.52f, 0.44f, 0.35f)
                inScatteringStart = 60f
                inScatteringSize = 20f
            })
        } catch (t: Throwable) { Log.w(TAG, "Fog: ${t.message}") }
        gltfLoader = GltfAssetLoader(context, eng)
    }

    private fun applyViewOptions() {
        val v = view ?: return
        try { v.antiAliasing = View.AntiAliasing.FXAA } catch (t: Throwable) { Log.w(TAG, "AA: ${t.message}") }
        try {
            v.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply { enabled = budget.ssao; radius = 0.6f; bias = 0.005f; power = 1.3f }
        } catch (t: Throwable) { Log.w(TAG, "SSAO: ${t.message}") }
        try {
            v.bloomOptions = View.BloomOptions().apply { enabled = budget.bloom; strength = 0.22f; highlight = 1000f }
        } catch (t: Throwable) { Log.w(TAG, "Bloom: ${t.message}") }
        try {
            v.dynamicResolutionOptions = View.DynamicResolutionOptions().apply {
                enabled = budget.resolutionScale < 1f
                minScale = floatArrayOf(budget.resolutionScale, budget.resolutionScale)
                maxScale = floatArrayOf(1f, 1f)
                quality = View.QualityLevel.MEDIUM
            }
        } catch (t: Throwable) { Log.w(TAG, "DynRes: ${t.message}") }
        try {
            v.shadowType = if (budget.softShadows) View.ShadowType.PCSS else View.ShadowType.PCF
        } catch (t: Throwable) { Log.w(TAG, "ShadowType: ${t.message}") }
    }

    private fun setupLighting() {
        val eng = engine ?: return
        val sc = scene ?: return
        val sunDir = floatArrayOf(0.42f, -0.78f, -0.46f)
        sunEntity = EntityManager.get().create()
        LightManager.Builder(LightManager.Type.SUN)
            .color(1.0f, 0.91f, 0.78f)
            .intensity(118_000.0f)
            .direction(sunDir[0], sunDir[1], sunDir[2])
            .castShadows(true)
            .sunAngularRadius(1.9f)
            .sunHaloSize(12f)
            .sunHaloFalloff(80f)
            .shadowOptions(LightManager.ShadowOptions().apply {
                mapSize = budget.shadowMapSize
                shadowCascades = if (budget.preset == QualityPreset.LOW) 1 else 3
                shadowFar = 90f
                constantBias = 0.0018f
                normalBias = 0.8f
            })
            .build(eng, sunEntity)
        sc.addEntity(sunEntity)

        val generated = IblGenerator.create(eng, sunDir, 24_000f, if (budget.preset == QualityPreset.LOW) 32 else 64)
        if (generated != null) {
            ibl = generated
            sc.indirectLight = generated.indirectLight
            sc.skybox = generated.skybox
        } else {
            sc.indirectLight = IndirectLight.Builder().intensity(28_000f).build(eng)
            sc.skybox = Skybox.Builder().color(0.42f, 0.38f, 0.32f, 1f).build(eng)
        }
    }

    // ------------------------------------------------------------------ assets
    private fun loadAssets() {
        val loader = gltfLoader ?: return
        val sc = scene ?: return
        val sy = sync ?: return

        val arena = loader.loadFromAssets("models/arena/arena01_colosseum.glb", sc)
        if (arena == null) Log.e(TAG, "ASSET MISSING: arena01_colosseum.glb (arena will be empty)")

        val crowd = loader.loadFromAssets("models/crowd/crowd_tiers.glb", sc)
        for (g in 0 until 24) crowdGroups[g] = crowd?.getFirstEntityByName("CrowdGroup_%02d".format(g)) ?: 0
        crowdVisibleGroups = budget.crowdGroups

        val playerAsset = loader.loadFromAssets("models/vehicles/player_muscle.glb", sc)
        playerEntities = sy.cacheVehicleEntities(playerAsset)
        if (playerEntities == null) Log.e(TAG, "ASSET MISSING: player_muscle.glb")

        // enemy pool: 2×A, 2×B, 2×C, 1×boss (variant 3)
        val files = listOf("enemy_raider_a", "enemy_raider_a", "enemy_raider_b", "enemy_raider_b", "enemy_raider_c", "enemy_raider_c", "enemy_boss_warrig")
        val variants = listOf(0, 0, 1, 1, 2, 2, 3)
        enemyEntities.clear(); enemyVariant.clear()
        for (i in files.indices) {
            val a = loader.loadFromAssets("models/vehicles/${files[i]}.glb", sc)
            enemyEntities.add(sy.cacheVehicleEntities(a))
            enemyVariant.add(variants[i])
            enemyEntities.last()?.let { sy.hide(it.root) }
        }

        tracers = loader.loadInstanced("models/vfx/tracer.glb", TRACER_POOL, sc)
        enemyTracers = loader.loadInstanced("models/vfx/enemy_tracer.glb", TRACER_POOL, sc)
        rockets = loader.loadInstanced("models/vfx/rocket.glb", ROCKET_POOL, sc)
        val perType = (budget.maxParticles / PARTICLE_TYPES.size).coerceAtLeast(8)
        for (type in PARTICLE_TYPES) particlePools[type] = loader.loadInstanced("models/vfx/$type.glb", perType, sc)
        for (kind in listOf("ammo", "health", "armor", "nitro", "weapon", "repair")) lootPools.add(loader.loadInstanced("models/props/loot_$kind.glb", LOOT_PER_TYPE, sc))
        zoneRoot = loader.loadFromAssets("models/vfx/zone_ring.glb", sc)?.root ?: 0

        // park every pooled instance below ground until the simulation claims it
        (tracers + enemyTracers + rockets).forEach { sy.hideInstance(it) }
        particlePools.values.forEach { pool -> pool.forEach { sy.hideInstance(it) } }
        lootPools.forEach { pool -> pool.forEach { sy.hideInstance(it) } }
        val vm = boundViewModel
        cameraController.reset(0f, 180f * WORLD_SCALE, vm?.player?.value?.angle ?: -90f)
        Log.i(TAG, "World loaded: arena=${arena != null} crowd=${crowd != null} enemies=${enemyEntities.count { it != null }} particles/type=$perType")
    }

    // ------------------------------------------------------------------ frame loop
    private inner class FrameCallback : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            choreographer?.postFrameCallback(this)
            val helper = uiHelper ?: return
            val rend = renderer ?: return
            val sc = swapChain ?: return
            val cam = camera ?: return
            val sy = sync ?: return
            val v = view ?: return
            if (!helper.isReadyToRender) return

            val dt = ((frameTimeNanos - lastFrameNanos) / 1_000_000_000.0).toFloat().coerceIn(0.001f, 0.05f)
            lastFrameNanos = frameTimeNanos
            val time = ((frameTimeNanos - startNanos) / 1_000_000_000.0).toFloat()
            frameCounter++
            val vm = boundViewModel
            val f = vm?.getGpuFrameData() ?: GameViewModel.GpuFrameData()

            try {
                // 1. Player vehicle (hull, wheels, turret, barrel recoil, hood damage)
                sy.syncVehicle(playerEntities, f.player, 1f, dt, f.recoil)

                // 2. Enemies: assign pool slots by model variant, hide unused slots
                java.util.Arrays.fill(enemySlotUsed, false)
                for (e in f.enemies) {
                    var slot = -1
                    for (i in 0 until enemyEntities.size) if (!enemySlotUsed[i] && enemyVariant[i] == e.variant) { slot = i; break }
                    if (slot < 0) for (i in 0 until enemyEntities.size) if (!enemySlotUsed[i] && enemyVariant[i] != 3) { slot = i; break }
                    if (slot < 0) break
                    enemySlotUsed[slot] = true
                    sy.syncVehicle(enemyEntities[slot], e, if (e.variant == 3) 1f else 0.96f, dt, 0f)
                }
                for (i in 0 until enemyEntities.size) if (!enemySlotUsed[i]) enemyEntities[i]?.let { sy.hide(it.root) }

                // 3. Projectiles
                var ti = 0; var ei = 0; var ri = 0
                for (b in f.bullets) {
                    when {
                        b.kind == "rocket" || b.kind == "shell" || b.kind == "emp" -> if (ri < rockets.size) sy.syncProjectile(rockets[ri++], b)
                        b.isPlayer -> if (ti < tracers.size) sy.syncProjectile(tracers[ti++], b)
                        else -> if (ei < enemyTracers.size) sy.syncProjectile(enemyTracers[ei++], b)
                    }
                }
                for (i in ti until tracers.size) sy.hideInstance(tracers[i])
                for (i in ei until enemyTracers.size) sy.hideInstance(enemyTracers[i])
                for (i in ri until rockets.size) sy.hideInstance(rockets[i])

                // 4. Particles → typed pools
                val used = HashMap<String, Int>(8)
                for (p in f.particles) {
                    val poolName = when (p.type) { "spark", "laser_impact" -> "spark"; "smoke" -> "smoke"; "fire" -> "fire"; "debris" -> "debris"; "skid" -> "tire_smoke"; else -> "dust" }
                    val pool = particlePools[poolName] ?: continue
                    val idx = used[poolName] ?: 0
                    if (idx >= pool.size) continue
                    sy.syncParticle(pool[idx], p, time)
                    used[poolName] = idx + 1
                }
                for ((name, pool) in particlePools) for (i in (used[name] ?: 0) until pool.size) sy.hideInstance(pool[i])

                // 5. Loot crates
                val lootUsed = IntArray(lootPools.size)
                for (l in f.loot) {
                    val pi = sy.lootPoolIndex(l.type)
                    val pool = lootPools.getOrNull(pi) ?: continue
                    if (lootUsed[pi] >= pool.size) continue
                    sy.syncLoot(pool[lootUsed[pi]++], l, time)
                }
                for (pi in lootPools.indices) for (i in lootUsed[pi] until lootPools[pi].size) sy.hideInstance(lootPools[pi][i])

                // 6. Safe zone ring
                if (zoneRoot != 0) {
                    if (f.matchActive || f.zone.radius < 699f) sy.syncZone(zoneRoot, f.zone.centerX, f.zone.centerY, f.zone.radius, kotlin.math.sin(time * 3f))
                    else sy.hide(zoneRoot)
                }

                // 7. Crowd: bounce with excitement, density by tier
                if (frameCounter % 2 == 0L || budget.preset.ordinal >= QualityPreset.HIGH.ordinal) {
                    for (g in 0 until 24) if (crowdGroups[g] != 0) sy.syncCrowdGroup(crowdGroups[g], g, time, f.crowdExcitement, g % 24 < crowdVisibleGroups || g % (24 / crowdVisibleGroups.coerceAtLeast(1)) == 0)
                }

                // 8. Camera
                cameraController.update(
                    camera = cam, targetX = f.player.x * WORLD_SCALE, targetZ = f.player.y * WORLD_SCALE,
                    angleDeg = f.player.angle, turretAngleDeg = f.player.turretAngle, speed = f.player.speed,
                    isBoosting = f.player.isBoosting, isHandbrake = f.player.handbrake, aimActive = f.aimActive,
                    screenShake = f.screenShake, recoil = f.recoil, aspect = aspect,
                    orbitTime = if (vm != null && vm.countdown.value > 0f && !f.matchActive) time.toDouble() else -1.0
                )

                // 9. Render
                if (rend.beginFrame(sc, frameTimeNanos)) {
                    rend.render(v)
                    rend.endFrame()
                }
            } catch (t: Throwable) {
                if (frameCounter % 300 == 0L) Log.w(TAG, "Frame failure (GpuSync/Render): ${t.message}")
            }
        }
    }
}
