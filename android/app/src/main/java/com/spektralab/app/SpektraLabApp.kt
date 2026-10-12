package com.spektralab.app

import android.app.Application
import com.spektralab.engine.EngineAssets
import com.spektralab.engine.EngineClient
import java.io.File

/**
 * One engine per process, held above the UI (plan S4: "engine 在 Application
 * 级单例持有，Activity 重建不重建引擎").
 *
 * Why it matters rather than being tidiness: the engine owns the GPU device,
 * the pipeline's baked tables and every session's cached negatives, and
 * creating a second one while the first still holds a 12 MP frame is how a
 * phone runs out of memory. Activity recreation, configuration changes and
 * WorkManager's workers (which outlive activities) all share this instance.
 */
class SpektraLabApp : Application() {

    /** Null until [engine] was first asked for: the unpack is not free. */
    private var client: EngineClient? = null

    /** The process's engine, created on first use. */
    val engine: EngineClient
        get() = client ?: synchronized(this) {
            client ?: buildEngine().also { client = it }
        }

    private fun buildEngine(): EngineClient {
        val resources: File = EngineAssets.ensure(this, version = "v1")
        return EngineClient(resources.absolutePath)
    }

    /**
     * System memory pressure, before the OS starts killing. The engine cannot
     * be asked to shrink — Vulkan has no pressure callback and no disk-backed
     * planes on this path — so the app drops what it can: the export
     * temporaries and the cache, and (through the UI's own hook) the canvas
     * resolution for the next open.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_RUNNING_LOW) return
        val tempExports = File(cacheDir, "export")
        tempExports.listFiles()?.forEach { it.delete() }
    }
}
