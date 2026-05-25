package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.network.ImageUploader

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.camera.Stream
import com.meta.wearable.dat.camera.addStream
import com.meta.wearable.dat.camera.types.*
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.session.DeviceSession
import com.meta.wearable.dat.core.session.DeviceSessionState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONException
import org.json.JSONObject
import java.io.*
import java.util.Locale

@SuppressLint("AutoCloseableUse")
class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "StreamViewModel"
        private const val SERVER_URL = "http://192.168.1.169:8000/identify"
        private const val STREAM_TIMEOUT_MS = 10_000L
    }

    private val deviceSelector: DeviceSelector = wearablesViewModel.deviceSelector

    private var session: DeviceSession? = null
    private var stream: Stream? = null

    private val _uiState = MutableStateFlow(StreamUiState())
    val uiState: StateFlow<StreamUiState> = _uiState.asStateFlow()

    private var videoJob: Job? = null
    private var stateJob: Job? = null
    private var errorJob: Job? = null
    private var sessionStateJob: Job? = null
    private var timeoutJob: Job? = null

    @Volatile private var autoCapturePending = false

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(application) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
    }

    // Called by WearablesViewModel before navigating to the stream screen.
    fun setAutoCaptureMode() {
        autoCapturePending = true
        _uiState.update { it.copy(isAutoCaptureMode = true) }
    }

    // =========================
    // START / STOP STREAM
    // =========================

    fun startStream() {
        stopStream()

        // If no video frame arrives within timeout, abort and show error.
        timeoutJob = viewModelScope.launch {
            delay(STREAM_TIMEOUT_MS)
            if (autoCapturePending) {
                Log.e(TAG, "Stream startup timed out")
                wearablesViewModel.setRecentError("Camera timed out – try again")
                returnToIdle()
            }
        }

        Wearables.createSession(deviceSelector)
            .onSuccess { createdSession ->
                session = createdSession
                sessionStateJob = viewModelScope.launch {
                    createdSession.state.collect { state -> handleSessionState(state) }
                }
                session?.start()
            }
            .onFailure {
                Log.e(TAG, "Session creation failed: $it")
                wearablesViewModel.setRecentError("Could not connect to glasses")
                if (_uiState.value.isAutoCaptureMode) {
                    viewModelScope.launch { returnToIdle() }
                }
            }
    }

    fun stopStream() {
        timeoutJob?.cancel();      timeoutJob = null
        videoJob?.cancel();        videoJob = null
        stateJob?.cancel();        stateJob = null
        errorJob?.cancel();        errorJob = null
        sessionStateJob?.cancel(); sessionStateJob = null

        stream?.stop(); stream = null
        session?.stop(); session = null

        _uiState.update { it.copy(streamState = StreamState.STOPPED) }
    }

    // =========================
    // SESSION → STREAM SETUP
    // =========================

    private fun handleSessionState(state: DeviceSessionState) {
        if (state != DeviceSessionState.STARTED) return
        if (stream != null) return

        session?.addStream(
            StreamConfiguration(videoQuality = VideoQuality.MEDIUM, frameRate = 24)
        )?.onSuccess { addedStream ->
            stream = addedStream

            videoJob = viewModelScope.launch {
                stream?.videoStream?.collect { handleVideoFrame(it) }
            }
            stateJob = viewModelScope.launch {
                stream?.state?.collect { s -> _uiState.update { it.copy(streamState = s) } }
            }
            errorJob = viewModelScope.launch {
                stream?.errorStream?.collect { err ->
                    Log.e(TAG, "Stream error: ${err.description}")
                    if (_uiState.value.isAutoCaptureMode && _uiState.value.isIdentifying) {
                        viewModelScope.launch { returnToIdle() }
                    }
                }
            }

            stream?.start()
        }?.onFailure { error, _ ->
            Log.e(TAG, "addStream failed: ${error.description}")
            wearablesViewModel.setRecentError("Camera unavailable: ${error.description}")
            if (_uiState.value.isAutoCaptureMode) {
                viewModelScope.launch { returnToIdle() }
            }
        }
    }

    // =========================
    // VIDEO FRAME → CAPTURE
    // =========================

    private fun handleVideoFrame(videoFrame: VideoFrame) {
        val bitmap = YuvToBitmapConverter.convert(
            videoFrame.buffer, videoFrame.width, videoFrame.height
        )
        bitmap?.let {
            _uiState.update { cur -> cur.copy(videoFrame = it, videoFrameCount = cur.videoFrameCount + 1) }

            if (autoCapturePending) {
                autoCapturePending = false
                timeoutJob?.cancel(); timeoutJob = null
                capturePhoto()
            }
        }
    }

    fun capturePhoto() {
        if (uiState.value.isCapturing) return
        _uiState.update { it.copy(isCapturing = true) }

        viewModelScope.launch {
            val result = stream?.capturePhoto()
            if (result == null) {
                _uiState.update { it.copy(isCapturing = false) }
                if (_uiState.value.isAutoCaptureMode) returnToIdle()
                return@launch
            }
            result.onSuccess { photo ->
                _uiState.update { it.copy(isCapturing = false) }
                handlePhotoData(photo)
            }
            result.onFailure { error, _ ->
                Log.e(TAG, "Capture failed: ${error.description}")
                _uiState.update { it.copy(isCapturing = false) }
                if (_uiState.value.isAutoCaptureMode) returnToIdle()
            }
        }
    }

    // =========================
    // PHOTO → UPLOAD → SPEAK
    // =========================

    private fun handlePhotoData(photo: PhotoData) {
        val bitmap: Bitmap? = when (photo) {
            is PhotoData.Bitmap -> photo.bitmap
            is PhotoData.HEIC -> {
                val bytes = ByteArray(photo.data.remaining())
                photo.data.get(bytes)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
        }
        bitmap?.let { uploadToServer(it) }
    }

    private fun uploadToServer(bitmap: Bitmap) {
        val context = getApplication<Application>()
        val file = File(context.cacheDir.also { it.mkdirs() }, "capture.jpg")

        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save image", e)
            if (_uiState.value.isAutoCaptureMode) viewModelScope.launch { returnToIdle() }
            return
        }

        _uiState.update { it.copy(isIdentifying = true) }

        viewModelScope.launch(Dispatchers.IO) {
            ImageUploader.uploadImage(file, SERVER_URL) { result ->
                val name = parseNameFromJson(result)
                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.update { it.copy(isIdentifying = false) }
                    speakText(name)
                    delay(2000L)
                    returnToIdle()
                }
            }
        }
    }

    private fun parseNameFromJson(raw: String): String {
        return try {
            JSONObject(raw).getString("name")
        } catch (e: JSONException) {
            if (raw.isNotBlank() && !raw.startsWith("ERROR")) raw else "Unknown"
        }
    }

    fun speak(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tts")
    }

    private fun speakText(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "result")
    }

    // =========================
    // RETURN TO IDLE
    // =========================

    private suspend fun returnToIdle() {
        timeoutJob?.cancel(); timeoutJob = null
        autoCapturePending = false
        _uiState.update {
            it.copy(
                isAutoCaptureMode = false,
                isCapturing = false,
                isIdentifying = false,
                capturedPhoto = null,
            )
        }
        stopStream()
        wearablesViewModel.navigateToDeviceSelection()
    }

    override fun onCleared() {
        super.onCleared()
        stopStream()
        tts?.shutdown()
        tts = null
    }

    class Factory(
        private val application: Application,
        private val wearablesViewModel: WearablesViewModel,
    ) : ViewModelProvider.Factory {
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(StreamViewModel::class.java))
                return StreamViewModel(application, wearablesViewModel) as T
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
