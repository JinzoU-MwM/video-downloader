package com.jni.videodownloader.data

import com.jni.videodownloader.data.net.ExtractResponse
import com.jni.videodownloader.data.net.VideoDto
import com.jni.videodownloader.domain.Platform
import org.junit.Assert.assertEquals
import org.junit.Test

class MappersTest {
    @Test fun maps_response_to_videoinfo_with_proxy_url() {
        val resp = ExtractResponse(
            platform = "TIKTOK",
            title = "t",
            thumbnail = "th",
            duration = 3.0,
            video = VideoDto("https://cdn/x.mp4", "mp4", 1000, mapOf("User-Agent" to "ua")),
            proxyToken = "TOK",
        )
        val info = resp.toVideoInfo("https://dl.jni.my.id/")
        assertEquals(Platform.TIKTOK, info.platform)
        assertEquals("https://cdn/x.mp4", info.directUrl)
        assertEquals("https://dl.jni.my.id/proxy?token=TOK", info.proxyUrl)
        assertEquals("ua", info.headers["User-Agent"])
    }
}
