package com.meta.wearable.dat.externalsampleapps.cameraaccess

import android.Manifest.permission.*
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions
import androidx.activity.viewModels
import androidx.lifecycle.ViewModelProvider
import com.meta.wearable.dat.core.Wearables
import com.meta.wearable.dat.core.types.Permission
import com.meta.wearable.dat.core.types.PermissionStatus
import com.meta.wearable.dat.externalsampleapps.cameraaccess.stream.StreamViewModel
import com.meta.wearable.dat.externalsampleapps.cameraaccess.ui.CameraAccessScaffold
import com.meta.wearable.dat.externalsampleapps.cameraaccess.wearables.WearablesViewModel
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.math.sqrt
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : ComponentActivity(), SensorEventListener {

  companion object {
    val PERMISSIONS = arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, CAMERA, INTERNET, ACCESS_FINE_LOCATION)
    private const val SHAKE_THRESHOLD = 12f      // m/s², net force above gravity
    private const val SHAKE_WINDOW_MS = 800L     // reversals must happen within this window
    private const val SHAKE_MIN_REVERSALS = 4    // direction flips needed (~2 full back-and-forth cycles)
    private const val SHAKE_MIN_GAP_MS = 100L    // min time between counted direction flips
    private const val SHAKE_COOLDOWN_MS = 2000L
  }

  val viewModel: WearablesViewModel by viewModels()

  private lateinit var streamViewModel: StreamViewModel

  private lateinit var sensorManager: SensorManager
  private var accelerometer: Sensor? = null
  private var lastShakeTime = 0L
  private var shakeWindowStart = 0L
  private var shakeReversals = 0
  private var lastShakeDir = 0       // +1 or -1 on dominant axis
  private var lastReversalTime = 0L

  private val permissionCheckLauncher =
    registerForActivityResult(RequestMultiplePermissions()) { permissions ->
      viewModel.onPermissionsResult(permissions) {}
    }

  private var continuation: CancellableContinuation<PermissionStatus>? = null
  private val mutex = Mutex()

  private val permissionsResultLauncher =
    registerForActivityResult(Wearables.RequestPermissionContract()) { result ->
      val status = result.getOrDefault(PermissionStatus.Denied)
      continuation?.resume(status)
      continuation = null
    }

  suspend fun requestWearablesPermission(permission: Permission): PermissionStatus {
    return mutex.withLock {
      suspendCancellableCoroutine { cont ->
        continuation = cont
        cont.invokeOnCancellation { continuation = null }
        permissionsResultLauncher.launch(permission)
      }
    }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    // ✅ MUST initialize BEFORE ANY AutoDeviceSelector usage
    Wearables.initialize(this)

    streamViewModel =
      ViewModelProvider(
        this,
        StreamViewModel.Factory(application, viewModel)
      )[StreamViewModel::class.java]

    viewModel.attachStreamViewModel(streamViewModel)

    sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
    accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    setContent {
      CameraAccessScaffold(
        viewModel = viewModel,
        streamViewModel = streamViewModel,
        onRequestWearablesPermission = ::requestWearablesPermission
      )
    }
  }

  override fun onStart() {
    super.onStart()
    val allGranted = PERMISSIONS.all { perm ->
      checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
    }
    if (allGranted) {
      // Permissions already granted — notify viewModel directly, no dialog
      viewModel.onPermissionsResult(PERMISSIONS.associateWith { true }) {}
    } else {
      permissionCheckLauncher.launch(PERMISSIONS)
    }
    // If we were streaming before going to background, restart the stream now
    if (viewModel.uiState.value.isStreaming) {
      streamViewModel.startStream()
    }
    accelerometer?.let {
      sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
    }
  }

  // Volume-up → face identify; Volume-down → vehicle identify.
  // (The glasses button is inaccessible to 3rd-party apps — Meta firmware handles it internally.)
  override fun dispatchKeyEvent(event: KeyEvent): Boolean {
    if (event.action == KeyEvent.ACTION_DOWN &&
        viewModel.uiState.value.isRegistered && viewModel.uiState.value.hasActiveDevice) {
      when (event.keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP -> {
          viewModel.triggerFaceIdentify()
          return true
        }
        KeyEvent.KEYCODE_VOLUME_DOWN -> {
          viewModel.triggerVehicleIdentify()
          return true
        }
      }
    }
    return super.dispatchKeyEvent(event)
  }

  override fun onStop() {
    super.onStop()
    sensorManager.unregisterListener(this)
    streamViewModel.stopStream()
    // Only reset to idle screen when the activity is actually closing, not on every background.
    // Previously this fired on every screen-off/notification-check, wiping the whole app state.
    if (isFinishing) {
      viewModel.navigateToDeviceSelection()
    }
  }

  override fun onSensorChanged(event: SensorEvent) {
    if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
    val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
    val netForce = sqrt(ax * ax + ay * ay + az * az) - SensorManager.GRAVITY_EARTH
    if (netForce < SHAKE_THRESHOLD) return

    val now = System.currentTimeMillis()

    // dominant axis direction: which axis is moving hardest, and which way
    val dir = when {
      abs(ax) >= abs(ay) && abs(ax) >= abs(az) -> if (ax > 0) 1 else -1
      abs(ay) >= abs(az) -> if (ay > 0) 1 else -1
      else -> if (az > 0) 1 else -1
    }

    // reset window if it expired
    if (now - shakeWindowStart > SHAKE_WINDOW_MS) {
      shakeWindowStart = now
      shakeReversals = 0
      lastShakeDir = dir
      return
    }

    // count a reversal only when direction flips AND enough time since last flip
    if (dir != lastShakeDir && now - lastReversalTime >= SHAKE_MIN_GAP_MS) {
      shakeReversals++
      lastShakeDir = dir
      lastReversalTime = now
      if (shakeReversals >= SHAKE_MIN_REVERSALS && now - lastShakeTime > SHAKE_COOLDOWN_MS) {
        lastShakeTime = now
        shakeReversals = 0
        streamViewModel.triggerEmergency()
      }
    }
  }

  override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
}