package com.jni.videodownloader.domain

data class VideoInfo(
    val platform: Platform,
    val title: String?,
    val thumbnail: String?,
    val directUrl: String,
    val proxyUrl: String?,
    val headers: Map<String, String>,
    val ext: String,
    val filesize: Long?,
)

data class Download(
    val id: Long,
    val url: String,
    val platform: Platform,
    val title: String?,
    val thumbnail: String?,
    val filePath: String?,
    val status: DownloadStatus,
    val progress: Int,
    val createdAt: Long,
)
