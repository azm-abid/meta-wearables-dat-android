package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.app.Activity
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.BuildConfig
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.voice.VoiceRecognitionManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraAccessScaffold(
    viewModel: WearablesViewModel,
    streamViewModel: StreamViewModel?,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier,
) {

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val bottomSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val context = LocalContext.current
    val activity = context as? Activity

    // -------------------------
    // attach stream VM
    // -------------------------
    LaunchedEffect(streamViewModel) {
        streamViewModel?.let {
            viewModel.attachStreamViewModel(it)
        }
    }

    // -------------------------
    // voice system
    // -------------------------
    val voiceManager = remember(activity) {
        activity?.let {
            VoiceRecognitionManager(it) { text ->
                viewModel.onVoiceCommand(text)
            }
        }
    }

    LaunchedEffect(voiceManager) {
        voiceManager?.startListening()
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceManager?.stop()
        }
    }

    // -------------------------
    // errors
    // -------------------------
    LaunchedEffect(uiState.recentError) {
        uiState.recentError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearRecentError()
        }
    }

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {

        Box(modifier = Modifier.fillMaxSize()) {

            when {
                uiState.isStreaming ->
                    StreamScreen(wearablesViewModel = viewModel)

                uiState.isRegistered ->
                    NonStreamScreen(
                        viewModel = viewModel,
                        onRequestWearablesPermission = onRequestWearablesPermission,
                    )

                else ->
                    HomeScreen(viewModel = viewModel)
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
            )

            if (BuildConfig.DEBUG) {
                FloatingActionButton(
                    onClick = { viewModel.showDebugMenu() },
                    modifier = Modifier.align(Alignment.CenterEnd),
                ) {
                    Icon(Icons.Default.BugReport, contentDescription = "Debug Menu")
                }

                if (uiState.isDebugMenuVisible) {
                    ModalBottomSheet(
                        onDismissRequest = { viewModel.hideDebugMenu() },
                        sheetState = bottomSheetState,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        MockDeviceKitScreen(modifier = Modifier.fillMaxSize())
                    }
                }
            }
        }
    }
}