package com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables

import com.meta.wearable.dat.externalsampleapps.cameraaccess.network.ElevenLabsTts
import com.meta.wearable.dat.externalsampleapps.cameraaccess.network.VoiceMode
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

    private val elevenLabs = ElevenLabsTts(application)

    val deviceSelector: DeviceSelector by lazy { AutoDeviceSelector() }
    private var deviceSelectorJob: Job? = null

    private var monitoringStarted = false
    private val deviceMonitoringJobs = mutableMapOf<DeviceIdentifier, Job>()
    private val deviceCompatibility = mutableMapOf<DeviceIdentifier, DeviceCompatibility>()

    private var streamViewModel: StreamViewModel? = null

    // Real permission handler provided by the UI — used so voice commands
    // can trigger the actual Wearables camera permission dialog when needed.
    private var cameraPermissionHandler: (suspend (Permission) -> PermissionStatus)? = null

    fun attachStreamViewModel(vm: StreamViewModel) { streamViewModel = vm }
    fun detachStreamViewModel() { streamViewModel = null }

    fun registerPermissionHandler(handler: suspend (Permission) -> PermissionStatus) {
        cameraPermissionHandler = handler
    }

    // =========================
    // VOICE COMMAND — keyword: "Face Identify"
    // =========================

    fun onVoiceCommand(rawText: String) {
        val command = rawText.lowercase()

        when {
            command.contains("command") && (command.contains("center") || command.contains("centre")) -> {
                elevenLabs.speak("Listening")
            }

            command.contains("switch to bella") || command.contains("switch to female") -> {
                if (uiState.value.voiceMode == VoiceMode.BELLA) {
                    elevenLabs.speak("Already using Bella")
                } else {
                    elevenLabs.speak("Switching to Bella")
                    setVoiceMode(VoiceMode.BELLA)
                }
            }

            command.contains("switch to adam") || command.contains("switch to male") -> {
                if (uiState.value.voiceMode == VoiceMode.ADAM) {
                    elevenLabs.speak("Already using Adam")
                } else {
                    elevenLabs.speak("Switching to Adam")
                    setVoiceMode(VoiceMode.ADAM)
                }
            }

            command.contains("switch to tts") || command.contains("switch to native") -> {
                if (uiState.value.voiceMode == VoiceMode.TTS) {
                    elevenLabs.speak("Already using built-in voice")
                } else {
                    elevenLabs.speak("Switching to T T S")
                    setVoiceMode(VoiceMode.TTS)
                }
            }

            command.contains("face identify") -> {
                when {
                    uiState.value.isStreaming -> {
                        // Already streaming — capture immediately
                        streamViewModel?.capturePhoto()
                    }
                    uiState.value.isRegistered && uiState.value.hasActiveDevice -> {
                        // Idle with glasses connected — start full auto-capture flow
                        streamViewModel?.setAutoCaptureMode()
                        navigateToStreaming(cameraPermissionHandler ?: { PermissionStatus.Granted })
                    }
                    uiState.value.isRegistered -> {
                        setRecentError("Glasses not connected")
                    }
                }
            }
        }
    }

    // =========================
    // NAVIGATION
    // =========================

    fun navigateToStreaming(
        onRequestWearablesPermission: suspend (Permission) -> PermissionStatus
    ) {
        viewModelScope.launch {
            val permission = Permission.CAMERA
            val result = Wearables.checkPermissionStatus(permission)

            result.onFailure { error -> setRecentError(error.toString()) }

            val status = result.getOrNull()
            if (status == PermissionStatus.Granted) {
                _uiState.update { it.copy(isStreaming = true) }
                return@launch
            }

            when (onRequestWearablesPermission(permission)) {
                PermissionStatus.Granted -> _uiState.update { it.copy(isStreaming = true) }
                PermissionStatus.Denied  -> setRecentError("Camera permission denied")
            }
        }
    }

    fun navigateToDeviceSelection() {
        _uiState.update { it.copy(isStreaming = false) }
    }

    // =========================
    // DEVICE / REGISTRATION MONITORING
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
                val showSheet = value == RegistrationState.REGISTERED &&
                        previousState == RegistrationState.REGISTERING
                _uiState.update {
                    it.copy(registrationState = value, isGettingStartedSheetVisible = showSheet)
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
        (deviceMonitoringJobs.keys - devices).forEach { id ->
            deviceMonitoringJobs.remove(id)?.cancel()
            deviceCompatibility.remove(id)
        }
        updateFirmwareUpdateRequired()

        (devices - deviceMonitoringJobs.keys).forEach { id ->
            deviceMonitoringJobs[id] = viewModelScope.launch {
                Wearables.devicesMetadata[id]?.collect { metadata ->
                    deviceCompatibility[id] = metadata.compatibility
                    updateFirmwareUpdateRequired()
                    if (metadata.compatibility == DeviceCompatibility.DEVICE_UPDATE_REQUIRED) {
                        setRecentError("Device '${metadata.name.ifEmpty { id }}' requires update")
                    }
                }
            }
        }
    }

    private fun updateFirmwareUpdateRequired() {
        _uiState.update {
            it.copy(isFirmwareUpdateRequired = deviceCompatibility.values.any {
                it == DeviceCompatibility.DEVICE_UPDATE_REQUIRED
            })
        }
    }

    // =========================
    // PERMISSIONS / REGISTRATION
    // =========================

    fun onPermissionsResult(
        permissionsResult: Map<String, Boolean>,
        onAllGranted: () -> Unit,
    ) {
        val granted = permissionsResult.entries.all { it.value }
        _uiState.update { it.copy(canRegister = granted) }
        if (granted) {
            onAllGranted()
            startMonitoring()
        } else {
            setRecentError("Allow Bluetooth, Connect, Internet permissions")
        }
    }

    fun startRegistration(activity: Activity) { Wearables.startRegistration(activity) }
    fun startUnregistration(activity: Activity) { Wearables.startUnregistration(activity) }

    fun openFirmwareUpdate(activity: Activity) {
        Wearables.openFirmwareUpdate(activity).onFailure { error, _ -> setRecentError(error.description) }
    }

    fun openDATGlassesAppUpdate(activity: Activity) {
        Wearables.openDATGlassesAppUpdate(activity).onFailure { error, _ -> setRecentError(error.description) }
    }

    // =========================
    // UI HELPERS
    // =========================

    fun triggerFaceIdentify() {
        when {
            uiState.value.isStreaming -> streamViewModel?.capturePhoto()
            uiState.value.isRegistered && uiState.value.hasActiveDevice -> {
                streamViewModel?.setAutoCaptureMode()
                navigateToStreaming(cameraPermissionHandler ?: { PermissionStatus.Granted })
            }
            uiState.value.isRegistered -> setRecentError("Glasses not connected")
        }
    }

    fun setVoiceMode(mode: VoiceMode) {
        elevenLabs.setMode(mode)
        streamViewModel?.setVoiceMode(mode)
        _uiState.update { it.copy(voiceMode = mode) }
    }

    fun simulateVoiceInput(text: String) { onVoiceCommand(text) }
    fun showDebugMenu() { _uiState.update { it.copy(isDebugMenuVisible = true) } }
    fun hideDebugMenu() { _uiState.update { it.copy(isDebugMenuVisible = false) } }
    fun showGettingStartedSheet() { _uiState.update { it.copy(isGettingStartedSheetVisible = true) } }
    fun hideGettingStartedSheet() { _uiState.update { it.copy(isGettingStartedSheetVisible = false) } }
    fun setLastIdentificationResult(result: String) { _uiState.update { it.copy(lastIdentificationResult = result) } }
    fun clearLastIdentificationResult() { _uiState.update { it.copy(lastIdentificationResult = null) } }
    fun clearRecentError() { _uiState.update { it.copy(recentError = null) } }
    internal fun setRecentError(error: String) { _uiState.update { it.copy(recentError = error) } }
    internal fun setDatAppUpdateRequired(required: Boolean) { _uiState.update { it.copy(isDatAppUpdateRequired = required) } }

    override fun onCleared() {
        super.onCleared()
        deviceMonitoringJobs.values.forEach { it.cancel() }
        deviceMonitoringJobs.clear()
        deviceSelectorJob?.cancel()
        streamViewModel = null
        elevenLabs.shutdown()
    }
}
