package com.jni.videodownloader.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jni.videodownloader.domain.VideoInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class DownloadController @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    fun enqueue(downloadId: Long, info: VideoInfo, title: String) {
        val headersJson = JSONObject(info.headers as Map<*, *>).toString()
        val data = workDataOf(
            DownloadWorker.KEY_ID to downloadId,
            DownloadWorker.KEY_DIRECT to info.directUrl,
            DownloadWorker.KEY_PROXY to info.proxyUrl,
            DownloadWorker.KEY_HEADERS to headersJson,
            DownloadWorker.KEY_TITLE to title,
            DownloadWorker.KEY_STAMP to System.currentTimeMillis(),
        )
        val req = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(data)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS)
            .addTag("download_$downloadId")
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniqueWork("dl_$downloadId", ExistingWorkPolicy.KEEP, req)
    }
}
