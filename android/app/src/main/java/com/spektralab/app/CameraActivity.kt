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
import com.spektralab.app.ui.CameraScreen
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val captureDir = File(cacheDir, "captures").apply { mkdirs() }

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
                    )
                }
            }
        }
    }

    private fun finishWith(file: File, readout: String) {
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.captures", file)
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
    }
}
