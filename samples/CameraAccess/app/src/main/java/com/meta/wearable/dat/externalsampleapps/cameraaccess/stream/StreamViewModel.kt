package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.network.ElevenLabsTts
import com.meta.wearable.dat.externalsampleapps.cameraaccess.network.ImageUploader
import com.meta.wearable.dat.externalsampleapps.cameraaccess.network.VoiceMode
import okhttp3.Call

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.location.LocationManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
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
import kotlin.coroutines.resume
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private data class PersonResult(
    val name: String,
    val details: List<String>,
) {
    fun toDisplayText(): String = buildString {
        appendLine(name)
        details.forEach { appendLine(it) }
    }.trim()

    fun toSpeakText(): String = buildString {
        append(name)
        details.forEach { append(". $it") }
    }
}

@SuppressLint("AutoCloseableUse")
class StreamViewModel(
    application: Application,
    private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

    private enum class CaptureMode { IDENTIFY, EVIDENCE }

    companion object {
        private const val TAG = "StreamViewModel"
        private const val SERVER_URL = "http://192.168.1.57:8000/identify"
        private const val EVIDENCE_URL = "http://192.168.1.57:8000/evidence"

        // Time allowed for BT session + stream + first frame to arrive
        private const val STREAM_TIMEOUT_MS = 8_000L

        // Hard cap from first stabilized frame through upload completion
        private const val PIPELINE_TIMEOUT_MS = 30_000L

        // Skip this many frames so camera exposure/focus can settle before capture
        private const val CAPTURE_STABILIZE_FRAMES = 5

        // How many times to retry a failed capturePhoto() before giving up
        private const val CAPTURE_MAX_RETRIES = 3

        // Delay between capture retries
        private const val CAPTURE_RETRY_DELAY_MS = 2_000L
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

    // Guards stream startup (cancelled when first stabilized frame arrives)
    private var streamTimeoutJob: Job? = null

    // Guards the entire capture→upload pipeline (cancelled in returnToIdle)
    private var pipelineTimeoutJob: Job? = null

    // Active OkHttp upload call — cancelled in stopStream() so it doesn't linger
    private var activeUploadCall: Call? = null

    // Local frame counter — resets on each startStream(), not exposed to UI
    private var frameCount = 0

    @Volatile private var autoCapturePending = false
    private var captureMode = CaptureMode.IDENTIFY

    private val elevenLabs = ElevenLabsTts(application)

    // Called by WearablesViewModel before navigating to the stream screen.
    fun setAutoCaptureMode() {
        Log.d(TAG, "▶ setAutoCaptureMode()")
        captureMode = CaptureMode.IDENTIFY
        autoCapturePending = true
        _uiState.update { it.copy(isAutoCaptureMode = true) }
        wearablesViewModel.clearLastIdentificationResult()
    }

    fun setEvidenceCaptureMode() {
        Log.d(TAG, "▶ setEvidenceCaptureMode()")
        captureMode = CaptureMode.EVIDENCE
        autoCapturePending = true
        _uiState.update { it.copy(isAutoCaptureMode = true) }
    }

    // =========================
    // START / STOP STREAM
    // =========================

    fun startStream() {
        Log.d(TAG, "▶ startStream()")
        stopStream()

        // If no stabilized frame arrives within STREAM_TIMEOUT_MS, abort.
        streamTimeoutJob = viewModelScope.launch {
            delay(STREAM_TIMEOUT_MS)
            if (autoCapturePending) {
                Log.e(TAG, "Stream startup timed out after ${STREAM_TIMEOUT_MS}ms")
                speakText("Glasses not found, please try again")
                wearablesViewModel.setRecentError("Glasses not found – try again")
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

    fun cancelStream() {
        viewModelScope.launch { returnToIdle() }
    }

    fun setVoiceMode(mode: VoiceMode) {
        elevenLabs.setMode(mode)
    }

    fun stopStream() {
        streamTimeoutJob?.cancel();   streamTimeoutJob = null
        pipelineTimeoutJob?.cancel(); pipelineTimeoutJob = null
        videoJob?.cancel();           videoJob = null
        stateJob?.cancel();           stateJob = null
        errorJob?.cancel();           errorJob = null
        sessionStateJob?.cancel();    sessionStateJob = null
        activeUploadCall?.cancel();   activeUploadCall = null
        frameCount = 0

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
                    // Return to idle on any stream error during auto-capture, regardless of phase
                    if (_uiState.value.isAutoCaptureMode) {
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
            frameCount++
            _uiState.update { cur -> cur.copy(videoFrame = it, videoFrameCount = cur.videoFrameCount + 1) }

            // Wait for CAPTURE_STABILIZE_FRAMES frames so the camera has settled
            if (autoCapturePending && frameCount >= CAPTURE_STABILIZE_FRAMES) {
                autoCapturePending = false
                streamTimeoutJob?.cancel(); streamTimeoutJob = null
                Log.d(TAG, "▶ Stabilized — triggering capturePhoto()")

                // Pipeline timeout: if capture+upload doesn't finish within limit, force idle
                pipelineTimeoutJob?.cancel()
                pipelineTimeoutJob = viewModelScope.launch {
                    delay(PIPELINE_TIMEOUT_MS)
                    Log.e(TAG, "Pipeline timed out after ${PIPELINE_TIMEOUT_MS}ms, forcing idle")
                    returnToIdle()
                }

                capturePhoto()
            }
        }
    }

    fun capturePhoto() {
        if (uiState.value.isCapturing) return
        _uiState.update { it.copy(isCapturing = true) }
        viewModelScope.launch {
            captureWithRetry(CAPTURE_MAX_RETRIES)
        }
    }

    private suspend fun captureWithRetry(remainingAttempts: Int) {
        val result = stream?.capturePhoto()
        if (result == null) {
            Log.w(TAG, "capturePhoto: stream is null")
            _uiState.update { it.copy(isCapturing = false) }
            if (_uiState.value.isAutoCaptureMode) returnToIdle()
            return
        }
        result.onSuccess { photo ->
            _uiState.update { it.copy(isCapturing = false) }
            handlePhotoData(photo)
        }
        result.onFailure { error, _ ->
            val attemptsLeft = remainingAttempts - 1
            Log.w(TAG, "Capture failed ($attemptsLeft retries left): ${error.description}")
            if (attemptsLeft > 0 && stream != null) {
                delay(CAPTURE_RETRY_DELAY_MS)
                captureWithRetry(attemptsLeft)
            } else {
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
        if (bitmap != null) {
            when (captureMode) {
                CaptureMode.EVIDENCE -> uploadEvidence(bitmap)
                CaptureMode.IDENTIFY -> uploadToServer(bitmap)
            }
        } else {
            Log.e(TAG, "Photo decoded to null bitmap")
            if (_uiState.value.isAutoCaptureMode) viewModelScope.launch { returnToIdle() }
        }
    }

    private fun uploadToServer(bitmap: Bitmap) {
        Log.d(TAG, "▶ uploadToServer() — URL: $SERVER_URL")
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

        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val (latitude, longitude) = getLastKnownLocation()
        val timestamp = currentTimestamp()

        Log.d(TAG, "▶ Sending request — deviceId=$deviceId lat=$latitude lon=$longitude ts=$timestamp")
        activeUploadCall?.cancel()
        activeUploadCall = ImageUploader.uploadImage(
            file, SERVER_URL, deviceId, latitude, longitude, timestamp
        ) { result ->
            Log.d(TAG, "◀ Raw server result: $result")
            viewModelScope.launch(Dispatchers.Main) {
                activeUploadCall = null
                _uiState.update { it.copy(isIdentifying = false) }
                speakResultSequentially(result)
            }
        }
    }

    private fun uploadEvidence(bitmap: Bitmap) {
        val context = getApplication<Application>()
        val file = File(context.cacheDir.also { it.mkdirs() }, "evidence.jpg")

        try {
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save evidence image", e)
            if (_uiState.value.isAutoCaptureMode) viewModelScope.launch { returnToIdle() }
            return
        }

        _uiState.update { it.copy(isIdentifying = true) }

        val deviceId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        val (latitude, longitude) = getLastKnownLocation()
        val timestamp = currentTimestamp()

        activeUploadCall?.cancel()
        activeUploadCall = ImageUploader.uploadImage(
            file, EVIDENCE_URL, deviceId, latitude, longitude, timestamp
        ) { result ->
            Log.d(TAG, "Evidence upload result: $result")
            viewModelScope.launch(Dispatchers.Main) {
                activeUploadCall = null
                _uiState.update { it.copy(isIdentifying = false) }
                returnToIdle()
                speakText("Evidence Stored")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun getLastKnownLocation(): Pair<Double?, Double?> {
        val context = getApplication<Application>()
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return Pair(null, null)
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val loc = try {
            lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        } catch (e: Exception) { null }
        return Pair(loc?.latitude, loc?.longitude)
    }

    private fun currentTimestamp(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun parseNameFromJson(raw: String): String {
        return try {
            JSONObject(raw).getString("voice_text")
        } catch (e: JSONException) {
            if (raw.isNotBlank() && !raw.startsWith("ERROR")) raw else "Unknown"
        }
    }

    fun speak(text: String) {
        elevenLabs.speak(text)
    }

    private fun speakText(text: String) {
        elevenLabs.speak(text)
    }

    private suspend fun speakAndWait(text: String) = suspendCancellableCoroutine<Unit> { cont ->
        elevenLabs.speak(text) { if (cont.isActive) cont.resume(Unit) }
    }

    private suspend fun speakResultSequentially(rawJson: String) {
        val (facesDetected, persons) = parseStructuredResult(rawJson)

        // Navigate back to NonStreamScreen first — speaking continues there
        returnToIdle()

        if (persons.isEmpty()) {
            val voiceText = parseNameFromJson(rawJson)
            wearablesViewModel.setLastIdentificationResult(voiceText)
            speakAndWait(voiceText)
            wearablesViewModel.clearLastIdentificationResult()
            return
        }

        val countDisplay = if (facesDetected == 1) "Face Detected" else "Faces Detected: $facesDetected"
        val countSpeak  = if (facesDetected == 1) "Face detected" else "$facesDetected faces detected"
        wearablesViewModel.setLastIdentificationResult(countDisplay)
        speakAndWait(countSpeak)

        for (person in persons) {
            wearablesViewModel.setLastIdentificationResult(person.toDisplayText())
            speakAndWait(person.toSpeakText())
        }

        wearablesViewModel.clearLastIdentificationResult()
    }

    private fun parseStructuredResult(raw: String): Pair<Int, List<PersonResult>> {
        return try {
            val json = JSONObject(raw)
            val facesDetected = json.optInt("faces_detected", 0)
            val resultsArray: JSONArray? = json.optJSONArray("results")
            val persons = mutableListOf<PersonResult>()
            if (resultsArray != null) {
                for (i in 0 until resultsArray.length()) {
                    val r = resultsArray.getJSONObject(i)
                    val name = r.optString("name", "").trim()
                    if (name.isEmpty()) continue
                    val rawDetails = mutableListOf<Pair<Int, String>>()
                    r.keys().forEach { key ->
                        val kl = key.lowercase()
                        if (kl != "name") {
                            val v = r.optString(key, "").trim()
                            if (v.isNotEmpty() && v.toLongOrNull() == null && v.toDoubleOrNull() == null) {
                                val order = when (kl) {
                                    in setOf("designation", "title", "role", "position", "rank", "post") -> 0
                                    in setOf("department", "organization", "org", "unit", "agency",
                                             "division", "company", "bureau", "directorate")            -> 1
                                    else -> 2
                                }
                                rawDetails.add(Pair(order, v))
                            }
                        }
                    }
                    persons.add(PersonResult(name, rawDetails.sortedBy { it.first }.map { it.second }))
                }
            }
            Pair(facesDetected, persons)
        } catch (e: Exception) {
            Log.e(TAG, "parseStructuredResult failed: $e")
            Pair(0, emptyList())
        }
    }

    // =========================
    // RETURN TO IDLE
    // =========================

    private suspend fun returnToIdle() {
        streamTimeoutJob?.cancel();   streamTimeoutJob = null
        pipelineTimeoutJob?.cancel(); pipelineTimeoutJob = null
        autoCapturePending = false
        captureMode = CaptureMode.IDENTIFY
        _uiState.update {
            it.copy(
                isAutoCaptureMode = false,
                isCapturing = false,
                isIdentifying = false,
                capturedPhoto = null,
                videoFrame = null,
                videoFrameCount = 0,
                recognitionResult = null,
            )
        }
        stopStream()
        wearablesViewModel.navigateToDeviceSelection()
    }

    override fun onCleared() {
        super.onCleared()
        stopStream()
        elevenLabs.shutdown()
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
