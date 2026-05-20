package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.meta.wearable.dat.camera.types.StreamState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel

@Composable
fun StreamScreen(
    wearablesViewModel: WearablesViewModel,
    modifier: Modifier = Modifier,
    streamViewModel: StreamViewModel =
        viewModel(
            factory = StreamViewModel.Factory(
                application = (LocalActivity.current as ComponentActivity).application,
                wearablesViewModel = wearablesViewModel,
            ),
        ),
) {
    val streamUiState by streamViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { streamViewModel.startStream() }

    Box(modifier = modifier.fillMaxSize()) {

        // Live video feed
        streamUiState.videoFrame?.let { videoFrame ->
            key(streamUiState.videoFrameCount) {
                Image(
                    bitmap = videoFrame.asImageBitmap(),
                    contentDescription = stringResource(R.string.live_stream),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        // Spinner while stream is establishing
        if (streamUiState.streamState == StreamState.STARTING) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        // Top status bar — shows current operation
        val statusText = when {
            streamUiState.isIdentifying -> "Identifying..."
            streamUiState.isCapturing -> "Capturing photo..."
            streamUiState.streamState == StreamState.STARTING -> "Connecting to glasses..."
            streamUiState.isAutoCaptureMode -> "Preparing camera..."
            else -> null
        }
        statusText?.let {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.55f))
                    .systemBarsPadding()
                    .padding(vertical = 10.dp)
                    .align(Alignment.TopCenter),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = it, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }

        // Full-screen result overlay — fades over the video while the name is announced
        streamUiState.recognitionResult?.let { name ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f)),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = name,
                        color = Color.White,
                        style = MaterialTheme.typography.displayMedium,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 32.dp),
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "Returning to idle...",
                        color = Color.White.copy(alpha = 0.55f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }

        // Manual controls — hidden during auto-capture so the user can't interfere with the flow
        if (!streamUiState.isAutoCaptureMode) {
            Box(modifier = Modifier
                .fillMaxSize()
                .padding(all = 24.dp)) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .navigationBarsPadding()
                        .fillMaxWidth()
                        .height(56.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    SwitchButton(
                        label = stringResource(R.string.stop_stream_button_title),
                        onClick = {
                            streamViewModel.stopStream()
                            wearablesViewModel.navigateToDeviceSelection()
                        },
                        isDestructive = true,
                        modifier = Modifier.weight(1f),
                    )

                    CaptureButton(onClick = { streamViewModel.capturePhoto() })
                }
            }
        }
    }

    // Share dialog only in manual mode (auto-capture handles display via result overlay)
    if (!streamUiState.isAutoCaptureMode) {
        streamUiState.capturedPhoto?.let { photo ->
            if (streamUiState.isShareDialogVisible) {
                SharePhotoDialog(
                    photo = photo,
                    onDismiss = { streamViewModel.hideShareDialog() },
                    onShare = { bitmap ->
                        streamViewModel.sharePhoto(bitmap)
                        streamViewModel.hideShareDialog()
                    },
                )
            }
        }
    }
}
