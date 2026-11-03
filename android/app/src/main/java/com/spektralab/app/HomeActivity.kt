package com.spektralab.app

import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import android.content.ContentUris
import android.os.Bundle
import androidx.core.content.ContextCompat
import android.os.Build
import android.content.pm.PackageManager
import android.Manifest
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.spektralab.app.rolls.Roll
import com.spektralab.app.rolls.RollStore
import com.spektralab.app.ui.screens.home.HomeScreen
import com.spektralab.app.ui.screens.home.RecentFrame
import com.spektralab.app.ui.screens.picker.PhotoPickerScreen
import com.spektralab.app.ui.theme.SpektraLabTheme
import com.spektralab.app.ui.theme.SpektraLabTheme
import com.spektralab.app.ui.theme.SpektraLabTheme
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

    /** C1's 多选 state; empty means the grid opens instead of selecting. */
    private var selection by mutableStateOf<Set<Int>>(emptySet())

    /** B2, the picker the prototype draws; the system picker stays for files. */
    private var pickerOpen by mutableStateOf(false)

    /** API 33 renamed the gallery permission; below it the old one applies. */
    private val mediaPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* either way the grid re-reads in onResume */ }

    /**
     * The camera hands back a DNG and the editor opens it.
     *
     * `startActivity` was enough while the result had nowhere to go, which
     * is why a capture landed back on the library and looked like nothing
     * had happened. The prototype's path is 拍摄 → A1, so the result is
     * read and the frame opens.
     */
    private val launchCamera =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != CameraActivity.RESULT_CAPTURED) return@registerForActivityResult
            val uri = result.data?.data ?: return@registerForActivityResult
            openInEditor(uri)
        }

    private val pickImage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) openInEditor(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        askForGallery()
        setContent {
            SpektraLabTheme {
                BackHandler(enabled = pickerOpen) { pickerOpen = false }

                if (pickerOpen) {
                    Box(Modifier.fillMaxSize().background(Tokens.Ground)) {
                        PhotoPickerScreen(
                            onCancel = { pickerOpen = false },
                            onImport = { frames ->
                                pickerOpen = false
                                if (frames.isNotEmpty()) {
                                    startActivity(
                                        Intent(this@HomeActivity, MainActivity::class.java).apply {
                                            putStringArrayListExtra(
                                                MainActivity.EXTRA_IMPORT,
                                                ArrayList(frames.map { it.uri.toString() }),
                                            )
                                            putExtra(MainActivity.EXTRA_OPEN_URI, frames.first().uri)
                                            data = frames.first().uri
                                        },
                                    )
                                }
                            },
                        )
                    }
                    return@SpektraLabTheme
                }
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
                                launchCamera.launch(CameraActivity.intent(this@HomeActivity))
                            },
                            // 继续编辑 is the editor, not the shutter: open the
                            // frame the roll is actually on, and fall back to the
                            // album when the roll is empty.
                            onContinueEditing = {
                                val last = rolls.current()?.frameIds?.lastOrNull()
                                if (last != null) {
                                    openInEditor(Uri.parse(last))
                                } else {
                                    pickerOpen = true
                                }
                            },
                            onPickAlbum = { pickerOpen = true },
                            onCamera = { launchCamera.launch(CameraActivity.intent(this@HomeActivity)) },
                            onOpenRecent = { frame -> openInEditor(frame.uri) },
                            selection = selection,
                            onToggleSelection = { index ->
                                selection = if (index in selection) selection - index else selection + index
                            },
                            onExitSelection = { selection = emptySet() },
                            onEditSelected = {
                                val uris = selection.sorted()
                                    .mapNotNull { recent.getOrNull(it)?.uri }
                                if (uris.isNotEmpty()) openBatch(uris)
                            },
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

    private fun askForGallery() {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            mediaPermission.launch(permission)
        }
    }

    /** B1: the same editor, entered with a set of frames already picked. */
    private fun openBatch(uris: List<Uri>) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putStringArrayListExtra(MainActivity.EXTRA_BATCH, ArrayList(uris.map { it.toString() }))
                putExtra(MainActivity.EXTRA_OPEN_URI, uris.first())
                data = uris.first()
            },
        )
    }

    private fun openInEditor(uri: Uri) {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_OPEN_URI, uri)
                data = uri
            },
        )
    }

    /**
     * The device's own gallery, newest first.
     *
     * Reading this app's capture directory was wrong: the phone is full of
     * photographs before this app has taken any, and "最近照片" that shows none
     * of them is not a home screen. `MediaStore` needs the READ_MEDIA_IMAGES
     * grant on API 33+ and READ_EXTERNAL_STORAGE below it; without it the grid
     * says so rather than pretending the phone is empty.
     */
    private fun recentFrames(): List<RecentFrame> {
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
        )
        val found = mutableListOf<RecentFrame>()
        contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            null,
            null,
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            while (cursor.moveToNext() && found.size < 6) {
                val id = cursor.getLong(idColumn)
                val name = cursor.getString(nameColumn) ?: continue
                val uri = ContentUris.withAppendedId(
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id,
                )
                found += RecentFrame(uri, name, name.substringAfterLast('.', "IMG").uppercase())
            }
        }
        return found
    }
}
