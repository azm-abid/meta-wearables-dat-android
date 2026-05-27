package com.meta.wearable.dat.externalsampleapps.cameraaccess.network

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

object ImageUploader {

    private const val TAG = "ImageUploader"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun uploadImage(
        file: File,
        serverUrl: String,
        deviceId: String,
        latitude: Double?,
        longitude: Double?,
        timestamp: String,
        onResult: (String) -> Unit,
    ) {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, file.asRequestBody("image/jpeg".toMediaType()))
            .addFormDataPart("device_id", deviceId)
            .addFormDataPart("timestamp", timestamp)

        if (latitude != null && longitude != null) {
            multipart.addFormDataPart("latitude", latitude.toString())
            multipart.addFormDataPart("longitude", longitude.toString())
        }

        val request = Request.Builder()
            .url(serverUrl)
            .post(multipart.build())
            .build()

        client.newCall(request).enqueue(object : Callback {

            override fun onFailure(call: Call, e: java.io.IOException) {
                Log.e(TAG, "Upload failed — URL: $serverUrl | Error: ${e.message}", e)
                onResult("ERROR: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { r ->
                    val body = r.body?.string() ?: "EMPTY RESPONSE"
                    if (!r.isSuccessful) Log.e(TAG, "Server error ${r.code}: $body")
                    else Log.d(TAG, "Server response: $body")
                    onResult(body)
                }
            }
        })
    }
}
