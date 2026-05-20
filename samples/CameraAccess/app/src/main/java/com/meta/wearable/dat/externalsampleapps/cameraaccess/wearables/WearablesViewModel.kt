package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import android.app.Activity
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.selectors.AutoDeviceSelector
import com.meta.wearable.dat.core.selectors.DeviceSelector
import com.meta.wearable.dat.core.types.DeviceCompatibility
import com.meta.wearable.dat.core.types.DeviceIdentifier
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.core.types.RegistrationState
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class WearablesViewModel(application: Application) : AndroidViewModel(application) {

  private val _uiState = MutableStateFlow(WearablesUiState())
  val uiState: StateFlow<WearablesUiState> = _uiState.asStateFlow()

  // =========================
  // DEVICE CONTROL
  // =========================
  val deviceSelector: DeviceSelector by lazy { AutoDeviceSelector() }
  private var deviceSelectorJob: Job? = null

  private var monitoringStarted = false
  private val deviceMonitoringJobs = mutableMapOf<DeviceIdentifier, Job>()
  private val deviceCompatibility = mutableMapOf<DeviceIdentifier, DeviceCompatibility>()

  // =========================
  // STREAM BRIDGE (NEW)
  // =========================
  private var streamViewModel: StreamViewModel? = null

  fun attachStreamViewModel(vm: StreamViewModel) {
    streamViewModel = vm
  }

  fun detachStreamViewModel() {
    streamViewModel = null
  }

  // =========================
  // VOICE COMMAND SYSTEM
  // =========================
  fun onVoiceCommand(rawText: String) {
    val command = rawText.lowercase()

    when {

      // 🔥 more natural speech coverage
      command.contains("photo") ||
              command.contains("picture") ||
              command.contains("capture") ||
              command.contains("snap") -> {

        if (uiState.value.isStreaming) {
          streamViewModel?.capturePhoto()
          setRecentError("Voice: Capture triggered")
        }
      }

      command.contains("start stream") -> {
        navigateToStreaming { PermissionStatus.Granted }
      }

      command.contains("stop stream") -> {
        navigateToDeviceSelection()
      }
    }
  }

  fun simulateVoiceInput(text: String) {
    onVoiceCommand(text)
  }

  fun openFirmwareUpdate(activity: Activity) {
    Wearables.openFirmwareUpdate(activity).onFailure { error, _ ->
      setRecentError(error.description)
    }
  }

  fun openDATGlassesAppUpdate(activity: Activity) {
    Wearables.openDATGlassesAppUpdate(activity).onFailure { error, _ ->
      setRecentError(error.description)
    }
  }

  // =========================
  // ORIGINAL LOGIC
  // =========================

  private fun startMonitoring() {
    if (monitoringStarted) return
    monitoringStarted = true

    deviceSelectorJob = viewModelScope.launch {
      deviceSelector.activeDeviceFlow().collect { device ->
        _uiState.update { it.copy(hasActiveDevice = device != null) }
      }
    }

    viewModelScope.launch {
      Wearables.registrationState.collect { value ->
        val previousState = _uiState.value.registrationState
        val showGettingStartedSheet =
          value == RegistrationState.REGISTERED &&
                  previousState == RegistrationState.REGISTERING

        _uiState.update {
          it.copy(
            registrationState = value,
            isGettingStartedSheetVisible = showGettingStartedSheet
          )
        }
      }
    }

    viewModelScope.launch {
      Wearables.devices.collect { value ->
        _uiState.update { it.copy(devices = value.toList().toImmutableList()) }
        monitorDeviceCompatibility(value)
      }
    }
  }

  private fun monitorDeviceCompatibility(devices: Set<DeviceIdentifier>) {
    val removedDevices = deviceMonitoringJobs.keys - devices
    removedDevices.forEach { id ->
      deviceMonitoringJobs[id]?.cancel()
      deviceMonitoringJobs.remove(id)
      deviceCompatibility.remove(id)
    }

    updateFirmwareUpdateRequired()

    val newDevices = devices - deviceMonitoringJobs.keys
    newDevices.forEach { id ->
      val job = viewModelScope.launch {
        Wearables.devicesMetadata[id]?.collect { metadata ->
          deviceCompatibility[id] = metadata.compatibility
          updateFirmwareUpdateRequired()

          if (metadata.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED) {
            val name = metadata.name.ifEmpty { id }
            setRecentError("Device '$name' requires update")
          }
        }
      }
      deviceMonitoringJobs[id] = job
    }
  }

  fun startRegistration(activity: Activity) {
    Wearables.startRegistration(activity)
  }

  fun startUnregistration(activity: Activity) {
    Wearables.startUnregistration(activity)
  }

  fun navigateToStreaming(
    onRequestWearablesPermission: suspend (Permission) -> PermissionStatus
  ) {
    viewModelScope.launch {
      val permission = Permission.CAMERA
      val result = Wearables.checkPermissionStatus(permission)

      result.onFailure { error ->
        setRecentError(error.toString())
      }

      val status = result.getOrNull()
      if (status == PermissionStatus.Granted) {
        _uiState.update { it.copy(isStreaming = true) }
        return@launch
      }

      when (onRequestWearablesPermission(permission)) {
        PermissionStatus.Granted -> {
          _uiState.update { it.copy(isStreaming = true) }
        }
        PermissionStatus.Denied -> {
          setRecentError("Permission denied")
        }
      }
    }
  }

  fun navigateToDeviceSelection() {
    _uiState.update { it.copy(isStreaming = false) }
  }

  fun showDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = true) }
  }

  fun hideDebugMenu() {
    _uiState.update { it.copy(isDebugMenuVisible = false) }
  }

  fun clearRecentError() {
    _uiState.update { it.copy(recentError = null) }
  }

  internal fun setRecentError(error: String) {
    _uiState.update { it.copy(recentError = error) }
  }

  internal fun setDatAppUpdateRequired(required: Boolean) {
    _uiState.update { it.copy(isDatAppUpdateRequired = required) }
  }

  fun onPermissionsResult(
    permissionsResult: Map<String, Boolean>,
    onAllGranted: () -> Unit
  ) {
    val granted = permissionsResult.entries.all { it.value }
    _uiState.update { it.copy(canRegister = granted) }

    if (granted) {
      onAllGranted()
      startMonitoring()
    } else {
      _uiState.update {
        it.copy(
          recentError = "Allow Bluetooth, Connect, Internet permissions"
        )
      }
    }
  }

  fun showGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = true) }
  }

  fun hideGettingStartedSheet() {
    _uiState.update { it.copy(isGettingStartedSheetVisible = false) }
  }

  override fun onCleared() {
    super.onCleared()
    deviceMonitoringJobs.values.forEach { it.cancel() }
    deviceMonitoringJobs.clear()
    deviceSelectorJob?.cancel()
    streamViewModel = null
  }

  private fun updateFirmwareUpdateRequired() {
    val required = deviceCompatibility.values.any {
      it == DeviceCompatibility.DEVICE_UPDATE_REQUIRED
    }

    _uiState.update { it.copy(isFirmwareUpdateRequired = required) }
  }
}