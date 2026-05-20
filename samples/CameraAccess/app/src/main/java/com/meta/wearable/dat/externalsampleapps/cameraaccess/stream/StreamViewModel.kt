package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

import android.annotation.SuppressLint
import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
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
import com.meta.wearable.dat.core.types.DeviceSessionError
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*

import com.meta.wearable.dat.externalsampleapps.cameraaccess.network.ImageUploader

@SuppressLint("AutoCloseableUse")
class StreamViewModel(
  application: Application,
  private val wearablesViewModel: WearablesViewModel,
) : AndroidViewModel(application) {

  companion object {
    private const val TAG = "StreamViewModel"
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

      videoJob = viewModelScope.launch {
        stream?.videoStream?.collect {
          handleVideoFrame(it)
        }
      }

      stateJob = viewModelScope.launch {
        stream?.state?.collect { s ->
          _uiState.update { it.copy(streamState = s) }
        }
      }

      errorJob = viewModelScope.launch {
        stream?.errorStream?.collect {
          Log.e(TAG, "Stream error: ${it.description}")
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
    val dir = File(context.cacheDir, "share")
    if (!dir.exists()) dir.mkdirs()

    val file = File(dir, "shared.jpg")

    FileOutputStream(file).use {
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
    }

    Log.d(TAG, "Photo saved for sharing: ${file.absolutePath}")
  }

  // =========================
  // CAPTURE PHOTO
  // =========================
  fun capturePhoto() {

    if (uiState.value.isCapturing) return

    _uiState.update { it.copy(isCapturing = true) }

    viewModelScope.launch {

      stream?.capturePhoto()
        ?.onSuccess { photo ->
          handlePhotoData(photo)
          _uiState.update { it.copy(isCapturing = false) }
        }
        ?.onFailure { error, _ ->
          Log.e(TAG, "Capture failed: ${error.description}")
          _uiState.update { it.copy(isCapturing = false) }
        }
    }
  }

  // =========================
  // VIDEO FRAME (optional)
  // =========================
  private fun handleVideoFrame(videoFrame: VideoFrame) {

    val bitmap = YuvToBitmapConverter.convert(
      videoFrame.buffer,
      videoFrame.width,
      videoFrame.height
    )

    bitmap?.let {
      Log.d(TAG, "Frame received")
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

      _uiState.update {
        it.copy(capturedPhoto = bitmap)
      }

      uploadToServer(it)
    }
  }

  // =========================
  // UPLOAD TO BACKEND (FIXED)
  // =========================
  private fun uploadToServer(bitmap: Bitmap) {

    val context = getApplication<Application>()

    val dir = File(context.cacheDir, "upload")
    if (!dir.exists()) dir.mkdirs()

    val file = File(dir, "capture.jpg")

    FileOutputStream(file).use {
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it)
    }

    viewModelScope.launch(Dispatchers.IO) {

      ImageUploader.uploadImage(
        file,
        "http://192.168.1.146:8000/identify"
      ) { result ->
        Log.d(TAG, "UPLOAD RESULT: $result")
      }
    }
  }

  // =========================
  // STOP STREAM
  // =========================
  fun stopStream() {

    videoJob?.cancel()
    stateJob?.cancel()
    errorJob?.cancel()
    sessionStateJob?.cancel()

    stream?.stop()
    stream = null

    session?.stop()
    session = null
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