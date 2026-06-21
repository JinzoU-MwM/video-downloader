package com.jni.videodownloader.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlExtractorTest {
    @Test fun extracts_url_from_messy_share_text() {
        val t = "Check this out https://vm.tiktok.com/ZMabc123/ amazing!"
        assertEquals("https://vm.tiktok.com/ZMabc123/", UrlExtractor.firstUrl(t))
    }

    @Test fun returns_null_when_no_url() {
        assertNull(UrlExtractor.firstUrl("no links here"))
    }

    @Test fun detects_tiktok() {
        assertEquals(Platform.TIKTOK, UrlExtractor.detectPlatform("https://www.tiktok.com/@u/video/1"))
        assertEquals(Platform.TIKTOK, UrlExtractor.detectPlatform("https://vm.tiktok.com/abc/"))
    }

    @Test fun detects_instagram() {
        assertEquals(Platform.INSTAGRAM, UrlExtractor.detectPlatform("https://instagram.com/reel/Cx/"))
    }

    @Test fun detects_facebook() {
        assertEquals(Platform.FACEBOOK, UrlExtractor.detectPlatform("https://fb.watch/abc/"))
        assertEquals(Platform.FACEBOOK, UrlExtractor.detectPlatform("https://www.facebook.com/watch?v=1"))
    }

    @Test fun unknown_platform_is_null() {
        assertNull(UrlExtractor.detectPlatform("https://youtube.com/watch?v=1"))
    }
}
