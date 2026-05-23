package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.widget.Toast
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import kotlinx.coroutines.launch

private val UpdateRequiredBackground = Color(0xFFFFF4D6)
private val UpdateRequiredForeground = Color(0xFF8A4B00)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NonStreamScreen(
    viewModel: WearablesViewModel,
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus,
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val gettingStartedSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    var dropdownExpanded by remember { mutableStateOf(false) }
    val isDisconnectEnabled = uiState.registrationState == RegistrationState.REGISTERED
    val isUpdateRequired = uiState.isFirmwareUpdateRequired || uiState.isDatAppUpdateRequired
    val activity = LocalActivity.current
    val context = LocalContext.current

    MaterialTheme(colorScheme = darkColorScheme()) {
        Box(
            modifier = modifier.fillMaxSize().background(Color.Black).padding(24.dp),
            contentAlignment = Alignment.Center,
        ) {

            // Top-right disconnect menu
            Box(modifier = Modifier.align(Alignment.TopEnd).systemBarsPadding()) {
                IconButton(onClick = { dropdownExpanded = true }) {
                    Icon(
                        imageVector = Icons.Default.LinkOff,
                        contentDescription = "Disconnect",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }
                DropdownMenu(
                    expanded = dropdownExpanded,
                    onDismissRequest = { dropdownExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.unregister_button_title),
                                color = if (isDisconnectEnabled) AppColor.Red else Color.Gray,
                            )
                        },
                        enabled = isDisconnectEnabled,
                        onClick = {
                            activity?.let { viewModel.startUnregistration(it) }
                                ?: Toast.makeText(context, "Activity not available", Toast.LENGTH_SHORT).show()
                            dropdownExpanded = false
                        },
                        modifier = Modifier.height(30.dp),
                    )
                }
            }

            // Centre content — app icon + title
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.camera_access_icon),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(80.dp * LocalDensity.current.density),
                )
                Text(
                    text = stringResource(R.string.non_stream_screen_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    color = Color.White,
                )
                Text(
                    text = stringResource(R.string.non_stream_screen_description),
                    textAlign = TextAlign.Center,
                    color = Color.White,
                )
            }

            // Bottom status area
            Column(
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {

                if (isUpdateRequired) {
                    UpdateRequiredBanner(
                        showFirmwareUpdate = uiState.isFirmwareUpdateRequired,
                        showDatAppUpdate = uiState.isDatAppUpdateRequired,
                    )
                }

                if (uiState.isFirmwareUpdateRequired) {
                    SwitchButton(
                        label = stringResource(R.string.update_firmware_button_title),
                        onClick = {
                            activity?.let { viewModel.openFirmwareUpdate(it) }
                                ?: Toast.makeText(context, "Activity not available", Toast.LENGTH_SHORT).show()
                        },
                    )
                }

                if (uiState.isDatAppUpdateRequired) {
                    SwitchButton(
                        label = stringResource(R.string.update_dat_app_button_title),
                        onClick = {
                            activity?.let { viewModel.openDATGlassesAppUpdate(it) }
                                ?: Toast.makeText(context, "Activity not available", Toast.LENGTH_SHORT).show()
                        },
                    )
                }

                // Status indicator
                if (!uiState.hasActiveDevice) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.hourglass_icon),
                            contentDescription = null,
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp),
                        )
                        Text(
                            text = stringResource(R.string.waiting_for_active_device),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.7f),
                        )
                    }
                } else if (!isUpdateRequired) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Listening...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        Text(
                            text = "Say \"Identify\" to capture and identify",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                        )
                    }
                }
            }

            // Getting Started sheet
            if (uiState.isGettingStartedSheetVisible) {
                ModalBottomSheet(
                    onDismissRequest = { viewModel.hideGettingStartedSheet() },
                    sheetState = gettingStartedSheetState,
                ) {
                    GettingStartedSheetContent(
                        onContinue = {
                            scope.launch {
                                gettingStartedSheetState.hide()
                                viewModel.hideGettingStartedSheet()
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun UpdateRequiredBanner(
    showFirmwareUpdate: Boolean,
    showDatAppUpdate: Boolean,
    modifier: Modifier = Modifier,
) {
    val message = when {
        showFirmwareUpdate && showDatAppUpdate -> stringResource(R.string.update_required_both_message)
        showFirmwareUpdate -> stringResource(R.string.update_required_firmware_message)
        else -> stringResource(R.string.update_required_dat_app_message)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(UpdateRequiredBackground)
            .padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(Icons.Default.Warning, contentDescription = null, tint = UpdateRequiredForeground, modifier = Modifier.size(24.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.update_required_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = UpdateRequiredForeground)
            Text(message, style = MaterialTheme.typography.bodyMedium, color = UpdateRequiredForeground)
        }
    }
}

@Composable
private fun GettingStartedSheetContent(onContinue: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = stringResource(R.string.getting_started_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.Center,
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().padding(8.dp).padding(bottom = 16.dp),
        ) {
            TipItem(iconResId = R.drawable.video_icon,         text = stringResource(R.string.getting_started_tip_permission))
            TipItem(iconResId = R.drawable.tap_icon,           text = stringResource(R.string.getting_started_tip_photo))
            TipItem(iconResId = R.drawable.smart_glasses_icon, text = stringResource(R.string.getting_started_tip_led))
        }
        SwitchButton(
            label = stringResource(R.string.getting_started_continue),
            onClick = onContinue,
            modifier = Modifier.navigationBarsPadding(),
        )
    }
}

@Composable
private fun TipItem(iconResId: Int, text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Icon(
            painter = painterResource(id = iconResId),
            contentDescription = null,
            modifier = Modifier.padding(start = 4.dp, top = 4.dp).width(24.dp),
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(text = text)
    }
}
