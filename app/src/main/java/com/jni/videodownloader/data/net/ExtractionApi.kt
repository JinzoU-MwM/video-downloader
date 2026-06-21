package com.jni.videodownloader.data.net

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

interface ExtractionApi {
    @POST("extract")
    suspend fun extract(
        @Header("X-API-Key") apiKey: String,
        @Body req: ExtractRequest,
    ): ExtractResponse
}
