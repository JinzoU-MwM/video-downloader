package com.jni.videodownloader.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.jni.videodownloader.domain.DownloadMode
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class DownloadController @Inject constructor(
    @ApplicationContext private val ctx: Context,
) {
    fun enqueue(downloadId: Long, url: String, mode: DownloadMode, title: String) {
        val data = workDataOf(
            DownloadWorker.KEY_ID to downloadId,
            DownloadWorker.KEY_URL to url,
            DownloadWorker.KEY_AUDIO to (mode == DownloadMode.AUDIO),
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
