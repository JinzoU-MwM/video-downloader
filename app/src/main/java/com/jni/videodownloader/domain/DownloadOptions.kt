package com.jni.videodownloader.domain

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class DownloadMode { VIDEO, AUDIO }

enum class VideoQuality(val apiValue: String, val label: String) {
    MAX("max", "Maksimal"),
    Q1080("1080", "1080p"),
    Q720("720", "720p"),
    Q480("480", "480p");

    companion object {
        fun fromApiValue(v: String?): VideoQuality =
            entries.firstOrNull { it.apiValue == v } ?: Q1080
    }
}

data class DownloadOptions(
    val quality: VideoQuality = VideoQuality.Q1080,
    val mode: DownloadMode = DownloadMode.VIDEO,
    val whatsapp: Boolean = false,
) {
    /** base is the backend /media URL that already carries `?token=…`. */
    fun mediaUrl(base: String): String {
        val sep = if (base.contains("?")) "&" else "?"
        val m = if (mode == DownloadMode.AUDIO) "audio" else "video"
        val wa = if (whatsapp && mode == DownloadMode.VIDEO) "&wa=1" else ""
        return "$base${sep}quality=${quality.apiValue}&mode=$m$wa"
    }

    val fileExt: String get() = if (mode == DownloadMode.AUDIO) "mp3" else "mp4"
}

fun defaultTitle(platform: Platform, createdAt: Long): String {
    val sdf = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())
    return "${platform.name} • ${sdf.format(Date(createdAt))}"
}
