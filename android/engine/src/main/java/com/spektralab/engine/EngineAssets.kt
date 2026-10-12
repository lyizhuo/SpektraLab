package com.spektralab.engine

import android.content.Context
import java.io.File
import java.io.InputStream

/**
 * Unpacks the `engine` asset tree into `filesDir/engine` and hands that path to
 * `spk_engine_create`.
 *
 * The engine reads its resources through `std::filesystem`, so they have to be
 * real files, not assets. The unpack is keyed by the installation's own
 * timestamp: an app update replaces the tree exactly once, and a launch that
 * did not update anything keeps it. A hard-coded version string would be the
 * other failure mode -- update the APK, keep the old
 * `spektrafilm_constants.bin`, and let the engine load resources that no
 * longer match the library beside them.
 */
object EngineAssets {
    private const val ASSET_ROOT = "engine"
    private const val STAMP = ".unpacked"

    /** The stamp an installation carries: changes on install and on update. */
    fun installationStamp(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionCode}-${info.lastUpdateTime}"
    }.getOrDefault("unknown")

    /**
     * The resources directory, unpacking it first if needed. `version`
     * overrides the installation stamp, which is what the device tests use to
     * get a fresh tree per run.
     */
    fun ensure(context: Context, version: String? = null): File {
        val stampValue = version ?: installationStamp(context)
        val target = File(context.filesDir, ASSET_ROOT)
        val stamp = File(target, STAMP)
        if (stamp.isFile && stamp.readText().trim() == stampValue) return target
        unpack(context, target)
        stamp.writeText(stampValue)
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
