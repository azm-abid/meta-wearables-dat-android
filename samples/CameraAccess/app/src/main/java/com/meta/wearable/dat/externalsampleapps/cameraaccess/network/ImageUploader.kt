package com.meta.wearable.dat.externalsampleapps.cameraaccess.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory
import java.util.concurrent.TimeUnit

object ImageUploader {

    private const val TAG = "ImageUploader"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // Returns an OkHttpClient bound to the active WiFi network, falling back to the default client.
    // This prevents Android from routing requests through mobile data when WiFi has no internet.
    private fun clientForContext(context: Context): OkHttpClient {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val wifiNetwork = cm.allNetworks.firstOrNull { network ->
            val caps = cm.getNetworkCapabilities(network) ?: return@firstOrNull false
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        } ?: return client
        val socketFactory = object : SocketFactory() {
            override fun createSocket(): Socket = wifiNetwork.socketFactory.createSocket()
            override fun createSocket(host: String, port: Int): Socket = wifiNetwork.socketFactory.createSocket(host, port)
            override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket = wifiNetwork.socketFactory.createSocket(host, port, localHost, localPort)
            override fun createSocket(host: InetAddress, port: Int): Socket = wifiNetwork.socketFactory.createSocket(host, port)
            override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket = wifiNetwork.socketFactory.createSocket(address, port, localAddress, localPort)
        }
        return client.newBuilder().socketFactory(socketFactory).build()
    }

    fun postLocationData(
        serverUrl: String,
        deviceId: String,
        latitude: Double?,
        longitude: Double?,
        timestamp: String,
        context: Context,
        onResult: (String) -> Unit,
    ): Call {
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
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

        val call = clientForContext(context).newCall(request)
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (call.isCanceled()) return
                Log.e(TAG, "Request failed — URL: $serverUrl | Error: ${e.message}", e)
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
        return call
    }

    fun uploadImage(
        file: File,
        serverUrl: String,
        deviceId: String,
        latitude: Double?,
        longitude: Double?,
        timestamp: String,
        context: Context,
        onResult: (String) -> Unit,
    ): Call {
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

        val call = clientForContext(context).newCall(request)
        call.enqueue(object : Callback {

            override fun onFailure(call: Call, e: java.io.IOException) {
                if (call.isCanceled()) return
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
        return call
    }
}
