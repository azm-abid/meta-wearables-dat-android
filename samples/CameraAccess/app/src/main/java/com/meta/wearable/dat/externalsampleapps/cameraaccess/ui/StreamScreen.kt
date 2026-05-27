package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
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
    streamViewModel: StreamViewModel = viewModel(
        factory = StreamViewModel.Factory(
            application = (LocalActivity.current as ComponentActivity).application,
            wearablesViewModel = wearablesViewModel,
        ),
    ),
) {
    val state by streamViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { streamViewModel.startStream() }

    Box(modifier = modifier.fillMaxSize().background(Color.Black)) {

        // Live camera feed (shows once frames arrive)
        state.videoFrame?.let { frame ->
            key(state.videoFrameCount) {
                Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = stringResource(R.string.live_stream),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }

        // Spinner while stream is negotiating
        if (state.streamState == StreamState.STARTING) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
            )
        }

        // Status overlay
        val statusText = when {
            state.isIdentifying                        -> "Identifying..."
            state.isCapturing                          -> "Capturing photo..."
            state.streamState == StreamState.STARTING  -> "Connecting to glasses..."
            state.isAutoCaptureMode                    -> "Preparing camera..."
            else                                       -> null
        }
        statusText?.let { text ->
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.6f))
                    .systemBarsPadding()
                    .padding(vertical = 12.dp)
                    .align(Alignment.TopCenter),
                contentAlignment = Alignment.Center,
            ) {
                Text(text = text, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            }
        }

        // Cancel button — visible while connecting/preparing (before any video frame arrives)
        if (state.videoFrame == null && state.isAutoCaptureMode) {
            OutlinedButton(
                onClick = { streamViewModel.cancelStream() },
                colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.7f)),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 40.dp),
            ) {
                Text("Cancel", color = Color.White)
            }
        }
    }
}
