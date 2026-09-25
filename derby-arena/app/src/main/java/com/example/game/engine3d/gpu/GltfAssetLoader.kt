package com.example.game.engine3d.gpu

import android.content.Context
import android.util.Log
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.Scene
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.FilamentInstance
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import java.nio.ByteBuffer

/**
 * Loads .glb from Android assets into a Filament scene (single or instanced) and owns their lifecycle.
 */
class GltfAssetLoader(
    private val context: Context,
    private val engine: Engine
) {
    companion object {
        private const val TAG = "GltfAssetLoader"
    }

    private val materialProvider = UbershaderProvider(engine)
    private val assetLoader = AssetLoader(engine, materialProvider, EntityManager.get())
    private val resourceLoader = ResourceLoader(engine)
    private val loaded = mutableListOf<FilamentAsset>()
    private val bufferCache = HashMap<String, ByteBuffer>()

    private fun readBuffer(assetPath: String): ByteBuffer {
        bufferCache[assetPath]?.let { return it.duplicate() }
        val bytes = context.assets.open(assetPath).use { it.readBytes() }
        val buffer = ByteBuffer.allocateDirect(bytes.size).apply { put(bytes); flip() }
        bufferCache[assetPath] = buffer
        return buffer.duplicate()
    }

    fun loadFromAssets(assetPath: String, scene: Scene): FilamentAsset? {
        return try {
            val asset = assetLoader.createAsset(readBuffer(assetPath))
                ?: throw IllegalStateException("createAsset returned null for $assetPath")
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            scene.addEntities(asset.entities)
            loaded.add(asset)
            Log.i(TAG, "Loaded GLB $assetPath entities=${asset.entities.size}")
            asset
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load $assetPath", t)
            null
        }
    }

    /** Loads one asset with [count] GPU instances (shared geometry, individual transforms). */
    fun loadInstanced(assetPath: String, count: Int, scene: Scene): Array<FilamentInstance?> {
        val instances = arrayOfNulls<FilamentInstance>(count)
        try {
            val asset = assetLoader.createInstancedAsset(readBuffer(assetPath), instances)
                ?: throw IllegalStateException("createInstancedAsset returned null for $assetPath")
            resourceLoader.loadResources(asset)
            asset.releaseSourceData()
            for (inst in instances) {
                if (inst != null) scene.addEntities(inst.entities)
            }
            loaded.add(asset)
            Log.i(TAG, "Loaded instanced GLB $assetPath x$count")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to load instanced $assetPath", t)
        }
        return instances
    }

    fun destroy() {
        for (asset in loaded) {
            try { assetLoader.destroyAsset(asset) } catch (t: Throwable) { Log.w(TAG, "destroyAsset: ${t.message}") }
        }
        loaded.clear()
        bufferCache.clear()
        resourceLoader.destroy()
        assetLoader.destroy()
        materialProvider.destroyMaterials()
        materialProvider.destroy()
    }
}
