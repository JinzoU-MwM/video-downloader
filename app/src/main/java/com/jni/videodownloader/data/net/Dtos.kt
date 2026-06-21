package com.jni.videodownloader.data.net

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ExtractRequest(val url: String)

@JsonClass(generateAdapter = true)
data class VideoDto(
    val url: String,
    val ext: String?,
    val filesize: Long?,
    @Json(name = "http_headers") val httpHeaders: Map<String, String>?,
)

@JsonClass(generateAdapter = true)
data class ExtractResponse(
    val platform: String,
    val title: String?,
    val thumbnail: String?,
    val duration: Double?,
    val video: VideoDto,
    @Json(name = "proxy_token") val proxyToken: String?,
)
