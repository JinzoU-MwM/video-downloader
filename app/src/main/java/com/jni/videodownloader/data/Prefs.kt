package com.jni.videodownloader.data

import android.content.Context
import com.jni.videodownloader.domain.DownloadMode
import com.jni.videodownloader.domain.DownloadOptions
import com.jni.videodownloader.domain.VideoQuality
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class Prefs @Inject constructor(@ApplicationContext ctx: Context) {

    private val sp = ctx.getSharedPreferences("dl_prefs", Context.MODE_PRIVATE)

    fun lastOptions(): DownloadOptions = DownloadOptions(
        quality = VideoQuality.fromApiValue(sp.getString(KEY_QUALITY, null)),
        mode = if (sp.getString(KEY_MODE, "video") == "audio") DownloadMode.AUDIO else DownloadMode.VIDEO,
        whatsapp = sp.getBoolean(KEY_WA, false),
    )

    fun save(options: DownloadOptions) {
        sp.edit()
            .putString(KEY_QUALITY, options.quality.apiValue)
            .putString(KEY_MODE, if (options.mode == DownloadMode.AUDIO) "audio" else "video")
            .putBoolean(KEY_WA, options.whatsapp)
            .apply()
    }

    private companion object {
        const val KEY_QUALITY = "quality"
        const val KEY_MODE = "mode"
        const val KEY_WA = "wa"
    }
}
