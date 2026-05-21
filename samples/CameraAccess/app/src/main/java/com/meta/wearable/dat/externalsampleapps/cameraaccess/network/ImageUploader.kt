package com.meta.wearable.dat.externalsampleapps.cameraaccess.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object ImageUploader {

    // Fix 1: timeouts so an unreachable server doesn't hang the app indefinitely.
    // Fix 2: response.use {} ensures the connection is returned to the pool after each call.
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    fun uploadImage(file: File, serverUrl: String, onResult: (String) -> Unit) {

        val requestBody = file.asRequestBody("image/jpeg".toMediaType())

        val request = Request.Builder()
            .url(serverUrl)
            .post(
                MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", file.name, requestBody)
                    .build()
            )
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: java.io.IOException) {
                onResult("ERROR: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    onResult(r.body?.string() ?: "EMPTY RESPONSE")
                }
            }
        })
    }
}