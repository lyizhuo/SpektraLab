package com.spektralab.app.ui.home

import android.content.ContentValues
import android.graphics.Bitmap
import android.os.Environment
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.spektralab.app.rolls.Roll
import com.spektralab.app.ui.theme.Tokens
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Renders C1 and writes the picture where `adb pull` can reach it.
 *
 * **Why not `adb shell screencap`.** This emulator runs SwiftShader, and the
 * system UI misses its deadline often enough that `screencap` catches an ANR
 * dialog over the app — or the app is killed by the freezer while the dialog is
 * up. That happened twice while comparing this screen against the frame. This
 * test renders the composable in-process instead, so the picture is of the
 * layout and nothing else.
 *
 * **Why Downloads.** App-private storage needs `run-as`, and `connectedAndroidTest`
 * uninstalls the app when the run ends, taking the file with it. A `MediaStore`
 * download survives both and `adb pull /sdcard/Download/...` just works.
 *
 * The picture is a comparison aid, not an assertion: what this test asserts is
 * that the frame renders and that the four landmarks the frame puts on screen
 * are present in the tree. Whether it *looks* like the design is a question for
 * a person, which is the whole reason the file exists.
 */
@RunWith(AndroidJUnit4::class)
class HomeScreenCaptureTest {

    @get:Rule
    val compose = createComposeRule()

    private val rolls = listOf(
        Roll(
            id = "capture-portra",
            filmStockId = "kodak_portra_400",
            paperStockId = "kodak_portra_endura",
            capacity = 36,
            createdAtMillis = System.currentTimeMillis(),
            frameIds = List(17) { "frame-$it" },
        ),
        Roll(
            id = "capture-xtra",
            filmStockId = "kodak_ultramax_400",
            paperStockId = "kodak_portra_endura",
            capacity = 36,
            createdAtMillis = System.currentTimeMillis() - 4L * 86_400_000L,
            frameIds = List(12) { "frame-$it" },
        ),
    )

    @Test
    fun theHomeScreenRendersAndIsCaptured() {
        compose.setContent {
            Box(Modifier.fillMaxSize().background(Tokens.Ground)) {
                HomeScreen(
                    rolls = rolls,
                    recent = emptyList(),
                    onOpenRoll = {},
                    onPickAlbum = {},
                    onCamera = {},
                    onOpenRecent = {},
                )
            }
        }
        compose.waitForIdle()

        // The four things the frame puts on screen, as text or tags.
        compose.onRoot().fetchSemanticsNode()
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        assertTrue("nothing was rendered", bitmap.width > 0 && bitmap.height > 0)

        val written = writeToDownloads(bitmap, "spk-home.png")
        assertTrue("the capture was not written to $written", written.exists() && written.length() > 0)
    }

    private fun writeToDownloads(bitmap: Bitmap, name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val uri = resolver.insert(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "image/png")
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            },
        ) ?: error("could not create a download for $name")
        resolver.openOutputStream(uri)?.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            ?: error("could not open $uri")
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        // The path MediaStore chose, for the pull; the API has no "give me the file".
        return File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), name)
    }
}
