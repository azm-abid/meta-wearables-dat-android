package com.meta.wearable.dat.externalsampleapps.cameraaccess.network

import android.content.Context
import com.meta.wearable.dat.externalsampleapps.cameraaccess.network.VoiceMode
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ElevenLabsTts(private val context: Context) {

    companion object {
        private const val TAG = "ElevenLabsTts"
        private const val API_KEY = "sk_0f802a907b29491bcf08a7a07ab658f28b06e623475c592b"
        private const val BASE_URL = "https://api.elevenlabs.io/v1/text-to-speech"
        const val VOICE_BELLA = "EXAVITQu4vr4xnSDxMaL"
        const val VOICE_ADAM  = "pNInz6obpgDQGcFmaJgB"
    }

    @Volatile private var voiceId: String = VOICE_BELLA
    @Volatile private var activeMode: VoiceMode = VoiceMode.BELLA

    fun setMode(newMode: VoiceMode) {
        activeMode = newMode
        when (newMode) {
            VoiceMode.BELLA -> voiceId = VOICE_BELLA
            VoiceMode.ADAM  -> voiceId = VOICE_ADAM
            VoiceMode.TTS   -> Unit
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val mainHandler = Handler(Looper.getMainLooper())
    private val fileCounter = AtomicInteger(0)

    @Volatile private var mediaPlayer: MediaPlayer? = null
    @Volatile private var pendingOnComplete: (() -> Unit)? = null

    // Android TTS fallback — used when ElevenLabs is unavailable or quota exhausted
    private var fallbackTts: TextToSpeech? = null
    private var fallbackReady = false

    init {
        fallbackTts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                fallbackTts?.language = Locale.US
                fallbackReady = true
                Log.d(TAG, "Fallback TTS ready")
            }
        }
    }

    fun speak(text: String, onComplete: () -> Unit = {}) {
        Log.d(TAG, "speak() called: \"$text\" [mode=$activeMode]")
        stopCurrentPlayback()
        pendingOnComplete = onComplete
        if (activeMode == VoiceMode.TTS) {
            mainHandler.post { speakFallback(text) }
            return
        }

        val bodyJson = JSONObject().apply {
            put("text", text)
            put("model_id", "eleven_flash_v2_5")
            put("voice_settings", JSONObject().apply {
                put("stability", 0.5)
                put("similarity_boost", 0.75)
                put("style", 0.0)
                put("use_speaker_boost", true)
            })
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("$BASE_URL/$voiceId")
            .addHeader("xi-api-key", API_KEY)
            .addHeader("Accept", "audio/mpeg")
            .post(bodyJson)
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                Log.e(TAG, "API request failed: ${e.message} — falling back to Android TTS")
                mainHandler.post { speakFallback(text) }
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                Log.d(TAG, "API response code: ${response.code}")
                if (!response.isSuccessful) {
                    Log.e(TAG, "API error ${response.code} — falling back to Android TTS")
                    mainHandler.post { speakFallback(text) }
                    return
                }
                val bytes = response.body?.bytes()
                if (bytes == null || bytes.isEmpty()) {
                    Log.e(TAG, "Empty response body — falling back to Android TTS")
                    mainHandler.post { speakFallback(text) }
                    return
                }
                Log.d(TAG, "Got ${bytes.size} audio bytes")
                mainHandler.post { playBytes(bytes) }
            }
        })
    }

    private fun speakFallback(text: String) {
        if (fallbackReady) {
            Log.d(TAG, "Speaking via Android TTS fallback")
            fallbackTts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { firePendingOnComplete() }
                override fun onError(utteranceId: String?) { firePendingOnComplete() }
            })
            fallbackTts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts_${System.currentTimeMillis()}")
        } else {
            Log.e(TAG, "Fallback TTS not ready — firing onComplete immediately")
            firePendingOnComplete()
        }
    }

    private fun firePendingOnComplete() {
        mainHandler.post {
            val cb = pendingOnComplete
            pendingOnComplete = null
            cb?.invoke()
        }
    }

    private fun playBytes(bytes: ByteArray) {
        try {
            val file = File(context.cacheDir, "tts_${fileCounter.incrementAndGet()}.mp3")
            file.writeBytes(bytes)

            val mp = MediaPlayer.create(context, Uri.fromFile(file))
            if (mp == null) {
                Log.e(TAG, "MediaPlayer.create() returned null")
                firePendingOnComplete()
                return
            }
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra")
                true
            }
            mp.setOnCompletionListener {
                Log.d(TAG, "Playback complete")
                it.release()
                firePendingOnComplete()
            }
            mediaPlayer = mp
            mp.start()
            Log.d(TAG, "MediaPlayer started")
        } catch (e: Exception) {
            Log.e(TAG, "playBytes failed: $e", e)
        }
    }

    private fun stopCurrentPlayback() {
        pendingOnComplete = null
        mediaPlayer?.runCatching { stop(); release() }
        mediaPlayer = null
    }

    fun shutdown() {
        mainHandler.post {
            stopCurrentPlayback()
            fallbackTts?.shutdown()
            fallbackTts = null
        }
    }
}
