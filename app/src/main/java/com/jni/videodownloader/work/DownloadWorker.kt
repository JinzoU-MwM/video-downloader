package com.jni.videodownloader.work

import android.app.Notification
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.jni.videodownloader.data.DownloadRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repo: DownloadRepository,
    private val client: OkHttpClient,
) : CoroutineWorker(appContext, params) {

    private val downloadId: Long get() = inputData.getLong(KEY_ID, -1L)
    private val notifId: Int get() = (downloadId % 90000L).toInt() + 1000

    override suspend fun getForegroundInfo(): ForegroundInfo {
        NotificationHelper.ensureChannel(applicationContext)
        val title = inputData.getString(KEY_TITLE) ?: "Video"
        val n = NotificationHelper.progress(applicationContext, title, 0, true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(notifId, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notifId, n)
        }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = downloadId
        val direct = inputData.getString(KEY_DIRECT) ?: return@withContext fail(id)
        val proxy = inputData.getString(KEY_PROXY)
        val title = inputData.getString(KEY_TITLE) ?: "video"
        val headers = inputData.getString(KEY_HEADERS)?.let { runCatching { JSONObject(it) }.getOrNull() }

        NotificationHelper.ensureChannel(applicationContext)
        setForeground(getForegroundInfo())

        val tmp = File(applicationContext.cacheDir, "dl_$id.mp4")
        val ok = tryDownload(direct, headers, tmp, id, title) ||
            (proxy != null && tryDownload(proxy, null, tmp, id, title))

        if (!ok) {
            repo.setFailed(id)
            return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
        }

        val saved = saveToMediaStore(tmp, "rdl_${id}_${createdStamp()}.mp4")
        tmp.delete()
        repo.setCompleted(id, saved)
        notify(NotificationHelper.done(applicationContext, title))
        Result.success()
    }

    private fun tryDownload(
        url: String,
        headers: JSONObject?,
        out: File,
        id: Long,
        title: String,
    ): Boolean {
        return try {
            var start = if (out.exists()) out.length() else 0L
            val rb = Request.Builder().url(url)
            headers?.keys()?.forEach { k -> rb.header(k, headers.getString(k)) }
            if (start > 0) rb.header("Range", "bytes=$start-")

            client.newCall(rb.build()).execute().use { resp ->
                if (!resp.isSuccessful) return false
                // Server ignored our Range and is sending the whole file: restart from 0.
                if (start > 0 && resp.code == 200) {
                    out.delete(); start = 0
                }
                val body = resp.body ?: return false
                val total = if (body.contentLength() >= 0) body.contentLength() + start else -1L
                var done = start
                var lastPercent = -1
                body.byteStream().use { input ->
                    RandomAccessFile(out, "rw").use { raf ->
                        raf.seek(start)
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        while (input.read(buf).also { read = it } != -1) {
                            raf.write(buf, 0, read)
                            done += read
                            if (total > 0) {
                                val p = ((done * 100) / total).toInt()
                                if (p != lastPercent) {
                                    lastPercent = p
                                    repo.updateProgress(id, p)
                                    notify(NotificationHelper.progress(applicationContext, title, p, false))
                                }
                            }
                        }
                    }
                }
            }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun saveToMediaStore(file: File, name: String): String {
        val resolver = applicationContext.contentResolver
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_MOVIES + "/RDownloader",
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection =
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)!!
            resolver.openOutputStream(uri).use { os -> file.inputStream().use { it.copyTo(os!!) } }
            values.clear()
            values.put(MediaStore.Video.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                "RDownloader",
            )
            dir.mkdirs()
            val dest = File(dir, name)
            file.copyTo(dest, overwrite = true)
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, name)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                @Suppress("DEPRECATION")
                put(MediaStore.Video.Media.DATA, dest.absolutePath)
            }
            val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            uri?.toString() ?: dest.absolutePath
        }
    }

    private fun notify(n: Notification) {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(
                applicationContext,
                android.Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            runCatching { NotificationManagerCompat.from(applicationContext).notify(notifId, n) }
        }
    }

    private fun createdStamp(): Long = inputData.getLong(KEY_STAMP, downloadId)

    private fun fail(id: Long): Result {
        if (id > 0) repo.setFailed(id)
        return Result.failure()
    }

    companion object {
        const val KEY_ID = "id"
        const val KEY_DIRECT = "direct"
        const val KEY_PROXY = "proxy"
        const val KEY_HEADERS = "headers"
        const val KEY_TITLE = "title"
        const val KEY_STAMP = "stamp"
    }
}
