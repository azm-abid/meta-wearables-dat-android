package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.voice

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.activity.ComponentActivity

class VoiceRecognitionManager(
    private val activity: ComponentActivity,
    private val onResult: (String) -> Unit
) {
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListening = false
    private var isStopped = false
    private val handler = Handler(Looper.getMainLooper())

    fun startListening() {
        if (isStopped) return
        if (isListening) return

        if (!SpeechRecognizer.isRecognitionAvailable(activity)) {
            Log.e(TAG, "Speech recognition not available")
            return
        }

        // Always destroy and recreate to avoid "recognizer busy" state issues
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(activity)
        speechRecognizer?.setRecognitionListener(recognitionListener)
        speechRecognizer?.startListening(buildIntent())
        isListening = true
        Log.d(TAG, "Started listening")
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            Log.w(TAG, "Recognition error code: $error")
            isListening = false
            // Use longer delays for busy/network errors, short for silence/no-match
            val delayMs = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 2000L
                SpeechRecognizer.ERROR_NETWORK,
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> 1500L
                SpeechRecognizer.ERROR_CLIENT -> 800L
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 200L
                else -> 500L
            }
            scheduleRestart(delayMs)
        }

        override fun onResults(results: Bundle?) {
            isListening = false
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.lowercase()
            if (text != null) {
                Log.d(TAG, "Recognized: $text")
                onResult(text)
            }
            // Short delay before restarting to let system settle
            scheduleRestart(150L)
        }

        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun scheduleRestart(delayMs: Long) {
        if (isStopped) return
        handler.removeCallbacksAndMessages(RESTART_TOKEN)
        handler.postDelayed({ startListening() }, RESTART_TOKEN, delayMs)
    }

    private fun buildIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
    }

    fun stop() {
        isStopped = true
        handler.removeCallbacksAndMessages(null)
        isListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        Log.d(TAG, "Stopped")
    }

    companion object {
        private const val TAG = "VoiceRecognitionManager"
        private val RESTART_TOKEN = Any()
    }
}
