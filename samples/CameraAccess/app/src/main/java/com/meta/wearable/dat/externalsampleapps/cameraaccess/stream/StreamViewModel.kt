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
        private const val SERVER_URL = "http://192.168.1.146:8000/identify"
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

    // Set true by setAutoCaptureMode(); cleared on first frame so we only capture once per command
    @Volatile private var autoCapturePending = false

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(application) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
            }
        }
    }

    // =========================
    // AUTO-CAPTURE MODE
    // =========================

    // Called by WearablesViewModel when voice triggers "take picture" from idle state.
    // The flag survives the stopStream() call inside startStream() so the first video frame
    // automatically fires capturePhoto().
    fun setAutoCaptureMode() {
        autoCapturePending = true
        _uiState.update { it.copy(isAutoCaptureMode = true) }
    }

    // =========================
    // START STREAM
    // =========================
    fun startStream() {
        stopStream()

        Wearables.createSession(deviceSelector)
            .onSuccess { createdSession ->
                session = createdSession
                sessionStateJob = viewModelScope.launch {
                    createdSession.state.collect { state ->
                        handleSessionState(state)
                    }
                }
                session?.start()
            }
            .onFailure {
                Log.e(TAG, "Session error: ${it.javaClass.simpleName}: $it")
                if (_uiState.value.isAutoCaptureMode) {
                    viewModelScope.launch { returnToIdle() }
                }
            }
    }

    // =========================
    // SESSION HANDLING
    // =========================
    private fun handleSessionState(state: DeviceSessionState) {
        if (state != DeviceSessionState.STARTED) return
        if (stream != null) return

        session?.addStream(
            StreamConfiguration(
                videoQuality = VideoQuality.MEDIUM,
                frameRate = 24
            )
        )?.onSuccess { addedStream ->
            stream = addedStream

            // SDK videoStream has main-thread affinity — must stay on Dispatchers.Main.
            videoJob = viewModelScope.launch {
                stream?.videoStream?.collect { handleVideoFrame(it) }
            }

            stateJob = viewModelScope.launch {
                stream?.state?.collect { s ->
                    _uiState.update { it.copy(streamState = s) }
                }
            }

            // Only recover to idle when a stream error occurs while we're actively
            // identifying (server upload in progress). Errors during stream init are
            // non-fatal SDK events and must be ignored. Launch a new coroutine so
            // returnToIdle() → stopStream() doesn't self-cancel this collector.
            errorJob = viewModelScope.launch {
                stream?.errorStream?.collect { err ->
                    Log.e(TAG, "Stream error: ${err.description}")
                    if (_uiState.value.isAutoCaptureMode && _uiState.value.isIdentifying) {
                        viewModelScope.launch { returnToIdle() }
                    }
                }
            }

            stream?.start()
        }
    }

    fun hideShareDialog() {
        _uiState.update { it.copy(isShareDialogVisible = false) }
    }

    fun sharePhoto(bitmap: Bitmap) {
        val context = getApplication<Application>()
        val imagesFolder = File(context.cacheDir, "images")
        try {
            imagesFolder.mkdirs()
            val file = File(imagesFolder, "shared_image.jpg")
            FileOutputStream(file).use { stream ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                context, "${context.packageName}.fileprovider", file
            )
            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(android.content.Intent.EXTRA_STREAM, uri)
                addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(
                android.content.Intent.createChooser(intent, "Share Photo").apply {
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            Log.e(TAG, "Share failed", e)
        }
    }

    // =========================
    // VIDEO FRAME
    // =========================
    private fun handleVideoFrame(videoFrame: VideoFrame) {
        val bitmap = YuvToBitmapConverter.convert(
            videoFrame.buffer,
            videoFrame.width,
            videoFrame.height
        )

        bitmap?.let {
            _uiState.update { current ->
                current.copy(
                    videoFrame = it,
                    videoFrameCount = current.videoFrameCount + 1
                )
            }

            // First frame = stream is live; fire the auto-capture
            if (autoCapturePending) {
                autoCapturePending = false
                capturePhoto()
            }
        }
    }

    // =========================
    // CAPTURE PHOTO
    // =========================
    fun capturePhoto() {
        if (uiState.value.isCapturing) return

        _uiState.update { it.copy(isCapturing = true) }

        viewModelScope.launch {
            // Fix 5: stream may have been nulled between the isCapturing check and here;
            // treat a null result as a failure so isCapturing never stays stuck true.
            val result = stream?.capturePhoto()
            if (result == null) {
                _uiState.update { it.copy(isCapturing = false) }
                if (_uiState.value.isAutoCaptureMode) returnToIdle()
                return@launch
            }
            result.onSuccess { photo ->
                handlePhotoData(photo)
                _uiState.update { it.copy(isCapturing = false) }
            }
            result.onFailure { error, _ ->
                Log.e(TAG, "Capture failed: ${error.description}")
                _uiState.update { it.copy(isCapturing = false) }
                if (_uiState.value.isAutoCaptureMode) returnToIdle()
            }
        }
    }

    // =========================
    // PHOTO HANDLING
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

        bitmap?.let {
            _uiState.update { state -> state.copy(capturedPhoto = bitmap) }
            uploadToServer(it)
        }
    }

    // =========================
    // UPLOAD & IDENTIFICATION
    // =========================
    private fun uploadToServer(bitmap: Bitmap) {
        val context = getApplication<Application>()
        val dir = File(context.cacheDir, "upload")
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, "capture.jpg")

        try {
            FileOutputStream(file).use {
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save capture for upload", e)
            if (_uiState.value.isAutoCaptureMode) {
                viewModelScope.launch { returnToIdle() }
            }
            return
        }

        val isAutoMode = _uiState.value.isAutoCaptureMode
        _uiState.update { it.copy(isIdentifying = true) }

        viewModelScope.launch(Dispatchers.IO) {
            ImageUploader.uploadImage(file, SERVER_URL) { result ->
                Log.d(TAG, "Server response: $result")
                val name = parseNameFromJson(result)

                viewModelScope.launch(Dispatchers.Main) {
                    _uiState.update { it.copy(isIdentifying = false) }
                    speakText(name)
                    if (isAutoMode) {
                        delay(1500L)
                        returnToIdle()
                    }
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

    private fun speakText(text: String) {
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "result")
    }

    private suspend fun returnToIdle() {
        autoCapturePending = false
        _uiState.update {
            it.copy(
                isAutoCaptureMode = false,
                recognitionResult = null,
                capturedPhoto = null,
                isIdentifying = false,
                isCapturing = false,
            )
        }
        stopStream()
        wearablesViewModel.navigateToDeviceSelection()
    }

    // =========================
    // STOP STREAM
    // =========================
    fun stopStream() {
        videoJob?.cancel(); videoJob = null
        stateJob?.cancel(); stateJob = null
        errorJob?.cancel(); errorJob = null
        sessionStateJob?.cancel(); sessionStateJob = null

        stream?.stop()
        stream = null

        session?.stop()
        session = null

        // Keep isAutoCaptureMode / autoCapturePending intact — startStream() may follow immediately
        _uiState.update { it.copy(streamState = StreamState.STOPPED) }
    }

    override fun onCleared() {
        super.onCleared()
        stopStream()
        tts?.shutdown()
        tts = null
    }

    // =========================
    // FACTORY
    // =========================
    class Factory(
        private val application: Application,
        private val wearablesViewModel: WearablesViewModel
    ) : ViewModelProvider.Factory {

        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            if (modelClass.isAssignableFrom(StreamViewModel::class.java)) {
                return StreamViewModel(application, wearablesViewModel) as T
            }
            throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}
