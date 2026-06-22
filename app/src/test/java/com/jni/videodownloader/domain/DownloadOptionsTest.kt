package com.jni.videodownloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DownloadOptionsTest {

    @Test
    fun video_url_appends_quality_and_mode() {
        val o = DownloadOptions(VideoQuality.Q720, DownloadMode.VIDEO)
        assertEquals(
            "https://h/media?token=X&quality=720&mode=video",
            o.mediaUrl("https://h/media?token=X"),
        )
        assertEquals("mp4", o.fileExt)
    }

    @Test
    fun audio_url_uses_audio_mode_and_mp3_ext() {
        val o = DownloadOptions(VideoQuality.MAX, DownloadMode.AUDIO)
        assertEquals(
            "https://h/media?token=X&quality=max&mode=audio",
            o.mediaUrl("https://h/media?token=X"),
        )
        assertEquals("mp3", o.fileExt)
    }

    @Test
    fun fromApiValue_falls_back_to_1080() {
        assertEquals(VideoQuality.Q1080, VideoQuality.fromApiValue("weird"))
        assertEquals(VideoQuality.Q1080, VideoQuality.fromApiValue(null))
        assertEquals(VideoQuality.Q480, VideoQuality.fromApiValue("480"))
    }

    @Test
    fun defaultTitle_contains_platform_name() {
        assertTrue(defaultTitle(Platform.TIKTOK, 0L).contains("TIKTOK"))
    }

    @Test
    fun whatsapp_video_appends_wa_flag() {
        val o = DownloadOptions(VideoQuality.Q720, DownloadMode.VIDEO, whatsapp = true)
        assertEquals(
            "https://h/media?token=X&quality=720&mode=video&wa=1",
            o.mediaUrl("https://h/media?token=X"),
        )
    }

    @Test
    fun whatsapp_ignored_for_audio() {
        val o = DownloadOptions(VideoQuality.Q720, DownloadMode.AUDIO, whatsapp = true)
        assertEquals(
            "https://h/media?token=X&quality=720&mode=audio",
            o.mediaUrl("https://h/media?token=X"),
        )
    }
}
