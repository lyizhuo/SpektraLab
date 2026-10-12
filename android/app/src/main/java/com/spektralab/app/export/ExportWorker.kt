package com.spektralab.app.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.spektralab.app.SpektraLabApp
import org.json.JSONObject

/**
 * A long export, kept alive by WorkManager (plan S6): a 25 MP render is
 * minutes of GPU work, and it must survive the user switching apps or the
 * activity being recreated.
 *
 * The engine is the process-wide one, so a worker never creates a second GPU
 * device; cancelling the work calls `spk_cancel` (safe from any thread) and
 * the render unwinds at the next node boundary, which is what leaves no
 * pending MediaStore row and no temporary file behind.
 */
class ExportWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? SpektraLabApp
            ?: return Result.failure(message("no SpektraLab application"))
        val source = inputData.getString(KEY_SOURCE) ?: return Result.failure(message("no source uri"))
        val displayName = inputData.getString(KEY_NAME) ?: "photo"
        val params = inputData.getString(KEY_PARAMS)?.let { JSONObject(it) } ?: JSONObject()
        val spec = specFrom(inputData)
        val target = if (inputData.getString(KEY_TARGET) == ExportTarget.Album.name) {
            ExportTarget.Album
        } else {
            ExportTarget.Directory
        }
        val directory = inputData.getString(KEY_DIRECTORY)?.let { Uri.parse(it) }

        setForeground(foregroundInfo("preparing"))
        val runner = ExportRunner(applicationContext, app.engine)
        val outcome = runner.run(Uri.parse(source), displayName, params, spec, target, directory) { stage ->
            setProgressAsync(Data.Builder().putString(KEY_STAGE, stage).build())
        }
        return when (outcome) {
            is ExportOutcome.Success -> Result.success(
                Data.Builder()
                    .putString(KEY_URI, outcome.uri)
                    .putInt(KEY_WIDTH, outcome.width)
                    .putInt(KEY_HEIGHT, outcome.height)
                    .putLong(KEY_BYTES, outcome.bytes)
                    .build(),
            )
            is ExportOutcome.Failed -> Result.failure(message(outcome.message))
            ExportOutcome.Cancelled -> Result.failure(message("cancelled"))
            is ExportOutcome.Refused -> Result.failure(message(outcome.reason))
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo("exporting")

    private fun foregroundInfo(stage: String): ForegroundInfo {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL, "Exports", NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
        val notification: Notification = NotificationCompat.Builder(context, CHANNEL)
            .setContentTitle("Exporting photograph")
            .setContentText(stage)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun specFrom(data: Data): ExportSpec {
        val format = ExportFormat.entries.firstOrNull { it.wire == data.getString(KEY_FORMAT) } ?: ExportFormat.Tiff16
        val space = ExportColorSpace.entries.firstOrNull { it.wire == data.getString(KEY_SPACE) } ?: ExportColorSpace.Srgb
        return ExportSpec(
            format = format,
            colorSpace = space,
            jpegQuality = data.getInt(KEY_QUALITY, 92),
            longEdge = data.getInt(KEY_LONG_EDGE, 0),
        )
    }

    private fun message(text: String) = Data.Builder().putString(KEY_ERROR, text).build()

    companion object {
        const val KEY_SOURCE = "source"
        const val KEY_NAME = "name"
        const val KEY_PARAMS = "params"
        const val KEY_FORMAT = "format"
        const val KEY_SPACE = "space"
        const val KEY_QUALITY = "quality"
        const val KEY_LONG_EDGE = "long_edge"
        const val KEY_TARGET = "target"
        const val KEY_DIRECTORY = "directory"
        const val KEY_STAGE = "stage"
        const val KEY_URI = "uri"
        const val KEY_WIDTH = "width"
        const val KEY_HEIGHT = "height"
        const val KEY_BYTES = "bytes"
        const val KEY_ERROR = "error"
        const val UNIQUE_WORK = "spektralab-export"
        private const val CHANNEL = "spektralab-exports"
        private const val NOTIFICATION_ID = 4101
    }
}
