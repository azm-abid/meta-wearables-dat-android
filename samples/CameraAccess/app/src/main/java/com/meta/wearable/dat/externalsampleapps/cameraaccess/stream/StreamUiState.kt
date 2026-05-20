package com.meta.wearable.dat.externalsampleapps.cameraaccess.stream

import android.graphics.Bitmap
import com.meta.wearable.dat.camera.types.StreamState

data class StreamUiState(
    val streamState: StreamState = StreamState.STOPPED,
    val videoFrame: Bitmap? = null,
    val videoFrameCount: Int = 0,
    val capturedPhoto: Bitmap? = null,
    val isShareDialogVisible: Boolean = false,
    val isCapturing: Boolean = false,
    // Auto-capture: triggered by voice, automatically starts stream, captures, returns to idle
    val isAutoCaptureMode: Boolean = false,
    val isIdentifying: Boolean = false,
    val recognitionResult: String? = null,
)
