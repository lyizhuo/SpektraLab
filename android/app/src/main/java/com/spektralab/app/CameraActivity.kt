package com.spektralab.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.spektralab.app.rolls.Roll
import com.spektralab.app.rolls.RollStore
import com.spektralab.app.rolls.stockLabel
import com.spektralab.app.ui.screens.camera.CameraScreen
import java.io.File

/**
 * C3, the camera. A separate activity rather than a screen inside the editor.
 *
 * The reason is the lifecycle, not the layout: a capture session owns the
 * camera, and the editor owns the engine's GPU memory. Putting both in one
 * activity means a viewfinder running while a 12 MP session holds a few hundred
 * megabytes of planes — `MemoryBudget` would start refusing exports while the
 * user is just looking through the camera. Two activities make the handoff
 * explicit: the camera closes the device in `onStop`, then the editor opens the
 * file it was given.
 *
 * The result is the DNG's `content://` URI (through [FileProvider], because the
 * editor opens it by file descriptor) plus the exposure the frame was taken at,
 * which the date back and the recipe both want.
 */
class CameraActivity : ComponentActivity() {

    /**
     * The roll this camera is filling.
     *
     * C3 prints `PORTRA 400` and `17 / 36`, so the screen needs a roll before a
     * frame exists — the app opens a new one when there is none, or continues
     * the newest one that is not full. That is the whole point of the model:
     * a counter that restarts at 1 every time the camera is opened is not a
     * film roll, it is a decoration.
     *
     * The stock is a default for now. C1 (the roll library) is what loads a
     * roll with a chosen film and paper; until it exists, the camera inherits
     * whatever roll is current and invents one with the catalogue's default
     * pair when there is none. `RollStore.appendToCurrent` starts a new roll
     * when the current one has a different film, so the two cannot drift.
     */
    private val rolls by lazy { RollStore(this) }

    private var roll: Roll? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val captureDir = File(cacheDir, "captures").apply { mkdirs() }
        roll = rolls.current() ?: Roll.newRoll(
            id = "roll-${System.currentTimeMillis()}",
            filmStockId = DEFAULT_FILM,
            paperStockId = DEFAULT_PAPER,
        ).also { rolls.save(it) }

        val current = roll!!

        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    CameraScreen(
                        hasPermission = ContextCompat.checkSelfPermission(
                            this, Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED,
                        captureDirectory = captureDir,
                        onCaptured = { file, readout -> finishWith(file, readout) },
                        onClose = { finish() },
                        filmLabel = stockLabel(current.filmStockId),
                        frameIndex = current.count,
                        frameCapacity = current.capacity,
                    )
                }
            }
        }
    }

    private fun finishWith(file: File, readout: String) {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.captures", file)

        // The frame joins the roll before the editor is asked to open it: a
        // capture that reached the disk but not the roll would come back as an
        // untitled photograph the next time the camera opens, and the counter
        // would silently forget it.
        roll?.let { r ->
            runCatching {
                rolls.appendToCurrent(
                    frameId = uri.toString(),
                    filmStockId = r.filmStockId,
                    paperStockId = r.paperStockId,
                    capacity = r.capacity,
                    idFor = { "roll-$it" },
                )
            }
        }

        setResult(
            RESULT_OK,
            Intent().apply {
                data = uri
                putExtra(EXTRA_READOUT, readout)
            },
        )
        finish()
    }

    companion object {
        /** Start C3 and get back a DNG URI. */
        fun intent(context: android.content.Context): Intent =
            Intent(context, CameraActivity::class.java)

        const val EXTRA_READOUT = "com.spektralab.app.extra.READOUT"

        /** Result code the editor checks before opening [Intent.getData]. */
        const val RESULT_CAPTURED = RESULT_OK

        /** The catalogue's default pair until C1 can load a roll explicitly. */
        private const val DEFAULT_FILM = "kodak_portra_400"
        private const val DEFAULT_PAPER = "kodak_portra_endura"
    }
}
