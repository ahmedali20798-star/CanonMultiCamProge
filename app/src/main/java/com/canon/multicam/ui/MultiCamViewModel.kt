package com.canon.multicam.ui

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.canon.multicam.domain.model.CameraDeviceProfile
import com.canon.multicam.domain.model.CameraSlotUiState
import com.canon.multicam.domain.model.CanonModelCatalog
import com.canon.multicam.domain.model.ConnectionState
import com.canon.multicam.domain.model.MultiCamUiState
import com.canon.multicam.domain.model.ProtocolType
import com.canon.multicam.domain.model.ThermalStatus
import com.canon.multicam.domain.session.CameraSessionController
import com.canon.multicam.protocol.ccapi.CcapiAuthRequiredException
import com.canon.multicam.protocol.ccapi.CcapiCameraSession
import com.canon.multicam.protocol.discovery.CameraDiscoveryManager
import com.canon.multicam.protocol.ptpip.PtpIpCameraSession
import com.canon.multicam.protocol.usb.UsbPtpCameraSession
import com.canon.multicam.protocol.xc.XcProtocolSession
import kotlinx.collections.immutable.ImmutableMap
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.toImmutableMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class MultiCamViewModel(application: Application) : AndroidViewModel(application) {

    private val discoveryManager = CameraDiscoveryManager(application)

    private val _uiState = MutableStateFlow(MultiCamUiState())
    val uiState: StateFlow<MultiCamUiState> = _uiState.asStateFlow()

    // Dedicated high-frequency 30 FPS LiveView frame map decoupled from control UI state
    private val _liveViewFrames = MutableStateFlow<ImmutableMap<String, Bitmap>>(persistentMapOf())
    val liveViewFrames: StateFlow<ImmutableMap<String, Bitmap>> = _liveViewFrames.asStateFlow()

    private val activeSessions = ConcurrentHashMap<String, CameraSessionController>()

    fun scanForCameras() {
        viewModelScope.launch {
            _uiState.update { it.copy(isScanning = true, statusBanner = "Scanning Wi-Fi & USB...") }
            val discovered = discoveryManager.discoverAllCameras()
            for (cam in discovered) {
                val alreadyAdded = _uiState.value.slots.any {
                    (cam.hostIp != null && it.hostIp == cam.hostIp) ||
                        it.slotId == cam.uniqueId
                }
                if (!alreadyAdded) {
                    if (cam.protocol == ProtocolType.USB_PTP && cam.usbDevice != null) {
                        connectUsbCamera(cam.usbDevice, cam.profile)
                    } else if (cam.hostIp != null) {
                        connectWifiCamera(cam.hostIp, cam.profile)
                    }
                }
            }
            _uiState.update {
                it.copy(
                    isScanning = false,
                    statusBanner = if (discovered.isEmpty()) {
                        "No new cameras found. Connect via IP or USB."
                    } else {
                        "Discovered ${discovered.size} camera(s)"
                    }
                )
            }
        }
    }

    fun connectWifiCamera(
        hostIp: String,
        preferredProfile: CameraDeviceProfile = CanonModelCatalog.allProfiles.first(),
        username: String = "",
        password: String = ""
    ) {
        val cleanIp = hostIp.trim()
        if (cleanIp.isEmpty()) return

        val existingSlot = _uiState.value.slots.firstOrNull { it.hostIp == cleanIp }
        val slotId = existingSlot?.slotId ?: "cam_${UUID.randomUUID().toString().take(ID_LEN)}"
        val orderedProtocols = resolveProbeOrder(preferredProfile)

        if (existingSlot == null) {
            val newSlot = CameraSlotUiState(
                slotId = slotId,
                profile = preferredProfile,
                activeProtocol = orderedProtocols.first(),
                connectionState = ConnectionState.CONNECTING,
                hostIp = cleanIp
            )
            addOrReplaceSlot(newSlot)
        } else {
            updateSlot(slotId) {
                it.copy(
                    profile = preferredProfile,
                    connectionState = ConnectionState.CONNECTING,
                    authRequired = false,
                    statusMessage = "Authenticating..."
                )
            }
        }

        viewModelScope.launch {
            attemptMultiProtocolConnect(
                slotId = slotId,
                cleanIp = cleanIp,
                profile = preferredProfile,
                protocols = orderedProtocols,
                username = username,
                password = password
            )
        }
    }

    private suspend fun attemptMultiProtocolConnect(
        slotId: String,
        cleanIp: String,
        profile: CameraDeviceProfile,
        protocols: List<ProtocolType>,
        username: String,
        password: String
    ) {
        var lastError: Throwable? = null
        for (protocol in protocols) {
            val session = createWifiSession(
                slotId = slotId,
                cleanIp = cleanIp,
                profile = profile,
                protocol = protocol,
                username = username,
                password = password
            ) ?: continue

            val result = session.connect()
            if (result.isSuccess) {
                activeSessions[slotId]?.release()
                activeSessions[slotId] = session
                updateSlot(slotId) {
                    it.copy(
                        activeProtocol = protocol,
                        connectionState = ConnectionState.CONNECTED,
                        authRequired = false,
                        statusMessage = "Connected (${protocol.displayName})"
                    )
                }
                session.startLiveView()
                return
            } else {
                session.release()
                val failure = result.exceptionOrNull()
                lastError = failure
                if (failure is CcapiAuthRequiredException) {
                    updateSlot(slotId) {
                        it.copy(
                            activeProtocol = protocol,
                            connectionState = ConnectionState.ERROR,
                            authRequired = true,
                            statusMessage = failure.message ?: "Digest Auth required"
                        )
                    }
                    return
                }
            }
        }

        updateSlot(slotId) {
            it.copy(
                connectionState = ConnectionState.ERROR,
                statusMessage = lastError?.message ?: "Connection failed on $cleanIp"
            )
        }
    }

    private fun createWifiSession(
        slotId: String,
        cleanIp: String,
        profile: CameraDeviceProfile,
        protocol: ProtocolType,
        username: String,
        password: String
    ): CameraSessionController? = when (protocol) {
        ProtocolType.WIFI_CCAPI -> CcapiCameraSession(
            hostIp = cleanIp,
            profile = profile,
            username = username,
            password = password,
            onFrameReceived = { bmp -> publishFrame(slotId, bmp) },
            onSessionError = { msg -> updateSlot(slotId) { it.copy(statusMessage = msg) } }
        )
        ProtocolType.XC_PROTOCOL -> XcProtocolSession(
            hostIp = cleanIp,
            profile = profile,
            username = username,
            password = password,
            onFrameReceived = { bmp -> publishFrame(slotId, bmp) },
            onSessionError = { msg -> updateSlot(slotId) { it.copy(statusMessage = msg) } }
        )
        ProtocolType.WIFI_PTPIP -> PtpIpCameraSession(
            hostIp = cleanIp,
            profile = profile,
            onFrameReceived = { bmp -> publishFrame(slotId, bmp) },
            onSessionError = { msg -> updateSlot(slotId) { it.copy(statusMessage = msg) } }
        )
        ProtocolType.USB_PTP -> null
    }

    fun connectUsbCamera(device: UsbDevice, profile: CameraDeviceProfile) {
        val usbManager = getApplication<Application>()
            .getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val slotId = "usb_${device.deviceId}"
        val slot = CameraSlotUiState(
            slotId = slotId,
            profile = profile,
            activeProtocol = ProtocolType.USB_PTP,
            connectionState = ConnectionState.CONNECTING
        )
        addOrReplaceSlot(slot)

        viewModelScope.launch {
            val session = UsbPtpCameraSession(
                usbManager = usbManager,
                device = device,
                profile = profile,
                onFrameReceived = { bmp -> publishFrame(slotId, bmp) },
                onSessionError = { msg -> updateSlot(slotId) { it.copy(statusMessage = msg) } }
            )
            val result = session.connect()
            if (result.isSuccess) {
                activeSessions[slotId]?.release()
                activeSessions[slotId] = session
                updateSlot(slotId) {
                    it.copy(
                        connectionState = ConnectionState.CONNECTED,
                        statusMessage = "Connected (USB PTP)"
                    )
                }
                session.startLiveView()
            } else {
                session.release()
                updateSlot(slotId) {
                    it.copy(
                        connectionState = ConnectionState.ERROR,
                        statusMessage = result.exceptionOrNull()?.message ?: "USB PTP error"
                    )
                }
            }
        }
    }

    fun setPrimarySlot(slotId: String) {
        _uiState.update { it.copy(primarySlotId = slotId) }
    }

    fun triggerTouchAf(slotId: String, normX: Float, normY: Float) {
        updateSlot(slotId) { it.copy(touchAfNormalized = normX to normY) }
        viewModelScope.launch {
            activeSessions[slotId]?.triggerTouchAf(normX, normY)?.onFailure { err ->
                updateSlot(slotId) { it.copy(statusMessage = err.message ?: "AF failed") }
            }
        }
    }

    fun toggleRecording(slotId: String) {
        val current = _uiState.value.slots.firstOrNull { it.slotId == slotId } ?: return
        val nextRecord = !current.isRecording
        viewModelScope.launch {
            activeSessions[slotId]?.setRecording(nextRecord)?.onSuccess {
                updateSlot(slotId) { it.copy(isRecording = nextRecord) }
            }?.onFailure { err ->
                updateSlot(slotId) { it.copy(statusMessage = err.message ?: "Record failed") }
            }
        }
    }

    fun toggleAllRecording() {
        val anyNotRecording = _uiState.value.slots.any {
            it.connectionState == ConnectionState.CONNECTED && !it.isRecording
        }
        for (slot in _uiState.value.slots) {
            if (slot.connectionState == ConnectionState.CONNECTED) {
                viewModelScope.launch {
                    activeSessions[slot.slotId]?.setRecording(anyNotRecording)?.onSuccess {
                        updateSlot(slot.slotId) { it.copy(isRecording = anyNotRecording) }
                    }
                }
            }
        }
    }

    fun triggerAllShutters() {
        for (slot in _uiState.value.slots) {
            if (slot.connectionState == ConnectionState.CONNECTED) {
                viewModelScope.launch {
                    activeSessions[slot.slotId]?.triggerShutter()
                }
            }
        }
    }

    fun updateExposure(
        slotId: String,
        iso: String? = null,
        shutterSpeed: String? = null,
        aperture: String? = null
    ) {
        viewModelScope.launch {
            activeSessions[slotId]?.setExposureSetting(iso, shutterSpeed, aperture)?.onSuccess {
                updateSlot(slotId) {
                    it.copy(
                        iso = iso ?: it.iso,
                        shutterSpeed = shutterSpeed ?: it.shutterSpeed,
                        aperture = aperture ?: it.aperture
                    )
                }
            }
        }
    }

    fun updateThermalState(slotId: String, status: ThermalStatus) {
        val slot = _uiState.value.slots.firstOrNull { it.slotId == slotId } ?: return
        val policy = slot.profile.thermalPolicy
        val nextFps = when (status) {
            ThermalStatus.NORMAL -> policy.normalFps
            ThermalStatus.WARNING -> policy.warningFps
            ThermalStatus.CRITICAL -> policy.criticalFps
        }
        activeSessions[slotId]?.updateTargetFps(nextFps)
        updateSlot(slotId) { it.copy(thermalStatus = status) }
    }

    fun removeSlot(slotId: String) {
        activeSessions.remove(slotId)?.release()
        _liveViewFrames.update { current ->
            current.toMutableMap().apply { remove(slotId) }.toImmutableMap()
        }
        _uiState.update { state ->
            val remaining = state.slots.filterNot { it.slotId == slotId }.toImmutableList()
            val nextPrimary = if (state.primarySlotId == slotId) {
                remaining.firstOrNull()?.slotId
            } else {
                state.primarySlotId
            }
            state.copy(slots = remaining, primarySlotId = nextPrimary)
        }
    }

    private fun publishFrame(slotId: String, bitmap: Bitmap) {
        _liveViewFrames.update { current ->
            current.toMutableMap().apply { put(slotId, bitmap) }.toImmutableMap()
        }
    }

    private fun addOrReplaceSlot(newSlot: CameraSlotUiState) {
        _uiState.update { state ->
            val filtered = state.slots.filterNot { it.slotId == newSlot.slotId }
            val updated = (filtered + newSlot).toImmutableList()
            state.copy(
                slots = updated,
                primarySlotId = state.primarySlotId ?: newSlot.slotId
            )
        }
    }

    private fun updateSlot(
        slotId: String,
        transform: (CameraSlotUiState) -> CameraSlotUiState
    ) {
        _uiState.update { state ->
            val updated = state.slots.map { slot ->
                if (slot.slotId == slotId) transform(slot) else slot
            }.toImmutableList()
            state.copy(slots = updated)
        }
    }

    override fun onCleared() {
        super.onCleared()
        activeSessions.values.forEach { it.release() }
        activeSessions.clear()
    }

    companion object {
        private const val ID_LEN = 8

        fun resolveProbeOrder(profile: CameraDeviceProfile): List<ProtocolType> =
            when (profile.modelId) {
                "eos_r5c" -> listOf(
                    ProtocolType.XC_PROTOCOL,
                    ProtocolType.WIFI_CCAPI,
                    ProtocolType.WIFI_PTPIP
                )
                "eos_r" -> listOf(
                    ProtocolType.WIFI_PTPIP,
                    ProtocolType.WIFI_CCAPI
                )
                else -> listOf(
                    ProtocolType.WIFI_CCAPI,
                    ProtocolType.WIFI_PTPIP
                )
            }
    }
}
