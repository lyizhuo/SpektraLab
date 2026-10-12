package com.spektralab.engine

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * Unpacks the `engine` asset tree into `filesDir/engine` and hands that path to
 * `spk_engine_create`.
 *
 * The engine reads its resources through `std::filesystem`, so they have to be
 * real files, not assets. The unpack is keyed by a stamp holding the asset
 * version (the `BuildConfig`-free stand-in is the packaging time written by the
 * build) so an app update replaces the tree once rather than on every launch.
 */
object EngineAssets {
    private const val ASSET_ROOT = "engine"
    private const val STAMP = ".unpacked"

    /** The resources directory, unpacking it first if needed. */
    fun ensure(context: Context, version: String): File {
        val target = File(context.filesDir, ASSET_ROOT)
        val stamp = File(target, STAMP)
        if (stamp.isFile && stamp.readText().trim() == version) return target
        unpack(context, target)
        stamp.writeText(version)
        return target
    }

    private fun unpack(context: Context, target: File) {
        val assets = context.assets
        val pending = File(target.parentFile, "${target.name}.partial")
        pending.deleteRecursively()
        copyTree(assets, ASSET_ROOT, pending)
        // The rename keeps a half-unpacked tree from ever being the one the
        // engine opens.
        target.deleteRecursively()
        if (!pending.renameTo(target)) {
            pending.copyRecursively(target, overwrite = true)
            pending.deleteRecursively()
        }
    }

    private fun copyTree(assets: android.content.res.AssetManager, assetDir: String, target: File) {
        val children = assets.list(assetDir) ?: emptyArray()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(assetDir).use { input -> target.outputStream().use { input.copyTo(it) } }
            return
        }
        target.mkdirs()
        for (child in children) {
            val childAsset = "$assetDir/$child"
            val childTarget = File(target, child)
            val grandchildren = assets.list(childAsset) ?: emptyArray()
            if (grandchildren.isEmpty()) {
                childTarget.parentFile?.mkdirs()
                openAsset(assets, childAsset).use { input -> childTarget.outputStream().use { input.copyTo(it) } }
            } else {
                copyTree(assets, childAsset, childTarget)
            }
        }
    }

    private fun openAsset(assets: android.content.res.AssetManager, path: String): InputStream =
        assets.open(path)
}
