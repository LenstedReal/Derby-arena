package com.example.game.engine3d.gpu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.game.EnemyCar
import com.example.game.GameViewModel
import com.example.game.PlayerCar

/**
 * Filament GPU viewport. Pure rendering surface: HUD and touch controls are layered on top
 * by BattleHud / BattleControls. Handles pause/resume/destroy with the host lifecycle.
 */
@Composable
fun GpuArenaViewport(
    player: PlayerCar,
    enemies: List<EnemyCar>,
    isBoosting: Boolean,
    isHandbraking: Boolean,
    viewModel: GameViewModel,
    modifier: Modifier = Modifier,
    onGpuFailed: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val quality by viewModel.qualityPreset.collectAsStateWithLifecycle()
    val world = remember { FilamentWorld(context.applicationContext).apply { bindViewModel(viewModel) } }

    LaunchedEffect(quality) { world.setQuality(quality) }

    DisposableEffect(lifecycleOwner, world) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> world.start()
                Lifecycle.Event.ON_PAUSE -> world.stop()
                Lifecycle.Event.ON_DESTROY -> world.destroy()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            world.stop()
            world.destroy()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = {
                world.onStatusChanged = { st -> if (st.state == GpuBackendState.FAILED) onGpuFailed(st.message) }
                val tv = world.createTextureView()
                if (world.status.state == GpuBackendState.FAILED) onGpuFailed(world.status.message) else world.start()
                tv
            }
        )
    }
}
