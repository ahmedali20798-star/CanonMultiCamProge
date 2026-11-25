package com.canon.multicam

import android.Manifest
import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import com.canon.multicam.domain.model.CanonModelCatalog
import com.canon.multicam.protocol.discovery.CameraDiscoveryManager
import com.canon.multicam.protocol.discovery.CameraNetworkBinder
import com.canon.multicam.ui.CameraStudioScreen
import com.canon.multicam.ui.MultiCamViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: MultiCamViewModel by viewModels()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        viewModel.scanForCameras()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CameraNetworkBinder.initialize(this)
        requestDiscoveryPermissions()
        handleUsbAttachIntent(intent)

        setContent {
            CameraStudioScreen(viewModel = viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbAttachIntent(intent)
    }

    private fun requestDiscoveryPermissions() {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(required)
    }

    @Suppress("DEPRECATION")
    private fun handleUsbAttachIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        if (device != null && device.vendorId == CameraDiscoveryManager.CANON_USB_VENDOR_ID) {
            val profile = CanonModelCatalog.resolveByName(device.productName ?: "Canon EOS R")
            viewModel.connectUsbCamera(device, profile)
        }
    }
}
