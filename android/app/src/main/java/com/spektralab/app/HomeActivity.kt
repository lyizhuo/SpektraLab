package com.spektralab.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.spektralab.app.rolls.Roll
import com.spektralab.app.rolls.RollStore
import com.spektralab.app.ui.home.HomeScreen
import com.spektralab.app.ui.home.RecentFrame
import com.spektralab.app.ui.theme.Tokens
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * C1 — the app's door.
 *
 * It was the editor until now, which is why the prototype's home screen had no
 * implementation. Everything here reads state the app already keeps: the rolls
 * in `RollStore`, and the capture directory the camera writes into.
 *
 * The three exits are the frame's own: 从相册导入 opens *this* photograph in the
 * editor, 相机 goes to C3, and a roll tile continues that roll in C3.
 */
class HomeActivity : ComponentActivity() {

    /**
     * Constructed on first use, not in the constructor.
     *
     * It was a plain property initializer and that crashed on launch:
     * `RollStore(this)` runs while the Activity object is being instantiated,
     * before `attachBaseContext`, so `context.filesDir` is null and
     * `File(null, "rolls")` throws. The emulator never showed it because the
     * app was being killed by its system UI first.
     */
    private val rolls by lazy { RollStore(this) }

    private var rollList by mutableStateOf<List<Roll>>(emptyList())

    private var recent by mutableStateOf<List<RecentFrame>>(emptyList())

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openInEditor(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(background = Tokens.Ground)) {
                Surface(modifier = Modifier.fillMaxSize(), color = Tokens.Ground) {
                    Box(Modifier.fillMaxSize().background(Tokens.Ground)) {
                        HomeScreen(
                            rolls = rollList,
                            recent = recent,
                            onOpenRoll = { roll ->
                                // C3 continues whatever roll is current, so
                                // "open this roll" means "make it current" when
                                // it is not, and go shooting either way.
                                if (rolls.current()?.id != roll.id) rolls.save(roll)
                                startActivity(CameraActivity.intent(this@HomeActivity))
                            },
                            onPickAlbum = { pickImage.launch(arrayOf("image/*", "application/octet-stream")) },
                            onCamera = { startActivity(CameraActivity.intent(this@HomeActivity)) },
                            onOpenRecent = { frame -> openInEditor(Uri.fromFile(frame.file)) },
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Capture and import both land here, so the list is re-read on return
        // rather than watched: there is no change notification to subscribe to
        // and the directories are small.
        lifecycleScope.launch {
            rollList = withContext(Dispatchers.IO) { rolls.all() }
            recent = withContext(Dispatchers.IO) { recentFrames() }
        }
    }

    private fun openInEditor(uri: Uri) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_URI, uri)
                data = uri
            },
        )
    }

    /** The camera's own directory, newest first, with the extension badge. */
    private fun recentFrames(): List<RecentFrame> {
        val directory = File(cacheDir, "captures")
        val files = directory.listFiles { file -> file.isFile } ?: return emptyList()
        return files
            .sortedByDescending { it.lastModified() }
            .take(6)
            .map { RecentFrame(it, it.extension.uppercase().ifEmpty { "IMG" }) }
    }
}
