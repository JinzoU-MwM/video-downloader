package com.jni.videodownloader.domain

object UrlExtractor {
    private val URL_REGEX = Regex("""https?://[^\s]+""")
    private val PATTERNS = listOf(
        Platform.TIKTOK to Regex("""(tiktok\.com|vm\.tiktok\.com|vt\.tiktok\.com)""", RegexOption.IGNORE_CASE),
        Platform.INSTAGRAM to Regex("""instagram\.com""", RegexOption.IGNORE_CASE),
        Platform.FACEBOOK to Regex("""(facebook\.com|fb\.watch|fb\.com)""", RegexOption.IGNORE_CASE),
    )

    fun firstUrl(text: String): String? =
        URL_REGEX.find(text)?.value?.trimEnd('.', ',', ')', '"', '\'')

    fun detectPlatform(url: String): Platform? =
        PATTERNS.firstOrNull { it.second.containsMatchIn(url) }?.first
}
