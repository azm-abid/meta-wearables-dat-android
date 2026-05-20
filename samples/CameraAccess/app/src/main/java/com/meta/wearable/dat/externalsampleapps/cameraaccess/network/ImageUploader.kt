package com.meta.wearable.dat.externalsampleapps.cameraaccess.network

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

object ImageUploader {

    private val client = OkHttpClient()

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
                onResult(response.body?.string() ?: "EMPTY RESPONSE")
            }
        })
    }
}