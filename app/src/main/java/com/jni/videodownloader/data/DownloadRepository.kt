package com.jni.videodownloader.data

import com.jni.videodownloader.data.db.DownloadDao
import com.jni.videodownloader.data.db.DownloadEntity
import com.jni.videodownloader.data.net.ExtractRequest
import com.jni.videodownloader.data.net.ExtractResponse
import com.jni.videodownloader.data.net.ExtractionApi
import com.jni.videodownloader.domain.Download
import com.jni.videodownloader.domain.DownloadStatus
import com.jni.videodownloader.domain.Platform
import com.jni.videodownloader.domain.VideoInfo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

fun ExtractResponse.toVideoInfo(baseUrl: String): VideoInfo = VideoInfo(
    platform = Platform.valueOf(platform),
    title = title,
    thumbnail = thumbnail,
    directUrl = video.url,
    proxyUrl = proxyToken?.let { "${baseUrl.trimEnd('/')}/proxy?token=$it" },
    headers = video.httpHeaders ?: emptyMap(),
    ext = video.ext ?: "mp4",
    filesize = video.filesize,
)

private fun DownloadEntity.toModel() = Download(
    id = id,
    url = url,
    platform = Platform.valueOf(platform),
    title = title,
    thumbnail = thumbnail,
    filePath = filePath,
    status = DownloadStatus.valueOf(status),
    progress = progress,
    createdAt = createdAt,
)

@Singleton
class DownloadRepository @Inject constructor(
    private val dao: DownloadDao,
    private val api: ExtractionApi,
    @Named("apiKey") private val apiKey: String,
    @Named("baseUrl") private val baseUrl: String,
) {
    suspend fun resolve(url: String): VideoInfo =
        api.extract(apiKey, ExtractRequest(url)).toVideoInfo(baseUrl)

    /** Returns the latest published app version if it's newer than [currentCode], else null. */
    suspend fun latestUpdate(currentCode: Int): com.jni.videodownloader.data.net.AppLatest? {
        val latest = api.appLatest()
        return if (latest.versionCode > currentCode) latest else null
    }

    fun observeAll(): Flow<List<Download>> =
        dao.observeAll().map { list -> list.map { it.toModel() } }

    fun create(
        url: String,
        platform: Platform,
        title: String?,
        thumbnail: String?,
        createdAt: Long,
    ): Long = dao.insert(
        DownloadEntity(
            url = url,
            platform = platform.name,
            title = title,
            thumbnail = thumbnail,
            filePath = null,
            status = DownloadStatus.QUEUED.name,
            progress = 0,
            createdAt = createdAt,
        )
    )

    fun getById(id: Long) = dao.getById(id)

    fun updateProgress(id: Long, progress: Int) =
        dao.updateProgress(id, progress, DownloadStatus.DOWNLOADING.name)

    fun setCompleted(id: Long, filePath: String) =
        dao.setCompleted(id, DownloadStatus.COMPLETED.name, filePath)

    fun setFailed(id: Long) = dao.setStatus(id, DownloadStatus.FAILED.name)

    fun delete(id: Long) = dao.delete(id)
}
