package com.meta.wearable.dat.externalsampleapps.cameraaccess

import android.Manifest.permission.*
import android.os.Bundle
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
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine

class MainActivity : ComponentActivity() {

  companion object {
    val PERMISSIONS = arrayOf(BLUETOOTH, BLUETOOTH_CONNECT, CAMERA, INTERNET)
  }

  val viewModel: WearablesViewModel by viewModels()

  private lateinit var streamViewModel: StreamViewModel

  private val permissionCheckLauncher =
    registerForActivityResult(RequestMultiplePermissions()) { permissions ->
      viewModel.onPermissionsResult(permissions) {
        Wearables.initialize(this)
      }
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
    permissionCheckLauncher.launch(PERMISSIONS)
  }

  override fun onStop() {
    super.onStop()
    streamViewModel.stopStream()
    viewModel.navigateToDeviceSelection()
  }
}