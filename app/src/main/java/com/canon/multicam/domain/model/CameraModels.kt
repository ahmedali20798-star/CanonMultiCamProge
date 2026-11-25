package com.canon.multicam.domain.model

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class ProtocolType(val displayName: String) {
    WIFI_CCAPI("Wi-Fi CCAPI"),
    WIFI_PTPIP("Wi-Fi PTP/IP"),
    USB_PTP("USB PTP"),
    XC_PROTOCOL("Canon XC Protocol")
}

enum class ConnectionState {
    DISCONNECTED,
    DISCOVERING,
    CONNECTING,
    CONNECTED,
    ERROR
}

enum class ThermalStatus {
    NORMAL,
    WARNING,
    CRITICAL
}

@Immutable
data class CameraCapabilities(
    val supportsLiveView: Boolean,
    val supportsTouchAf: Boolean,
    val supportsMovieRecord: Boolean,
    val supportsPhotoShutter: Boolean,
    val supportsIsoControl: Boolean,
    val supportsShutterControl: Boolean,
    val supportsApertureControl: Boolean,
    val supportsWbControl: Boolean,
    val maxLiveViewFps: Int,
    val sensorWidthUnits: Int = DEFAULT_SENSOR_WIDTH,
    val sensorHeightUnits: Int = DEFAULT_SENSOR_HEIGHT
) {
    companion object {
        const val DEFAULT_SENSOR_WIDTH = 6000
        const val DEFAULT_SENSOR_HEIGHT = 4000
    }
}

@Immutable
data class ThermalThrottlePolicy(
    val normalFps: Int = DEFAULT_NORMAL_FPS,
    val warningFps: Int = DEFAULT_WARNING_FPS,
    val criticalFps: Int = DEFAULT_CRITICAL_FPS,
    val pauseLiveViewOnCritical: Boolean = false
) {
    companion object {
        const val DEFAULT_NORMAL_FPS = 30
        const val DEFAULT_WARNING_FPS = 12
        const val DEFAULT_CRITICAL_FPS = 4
    }
}

@Immutable
data class CameraDeviceProfile(
    val modelId: String,
    val displayName: String,
    val supportedProtocols: ImmutableList<ProtocolType>,
    val capabilities: CameraCapabilities,
    val thermalPolicy: ThermalThrottlePolicy = ThermalThrottlePolicy(),
    val requiresCcapiPreActivation: Boolean = false,
    val minCcapiFirmware: String? = null
)

@Immutable
data class OverlayGuidesConfig(
    val showRuleOfThirds: Boolean = true,
    val showCenterCrosshair: Boolean = true,
    val showSafeArea16x9: Boolean = false,
    val showFocusPeakingHint: Boolean = false
)

@Immutable
data class CameraSlotUiState(
    val slotId: String,
    val profile: CameraDeviceProfile,
    val activeProtocol: ProtocolType,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val hostIp: String = "",
    val iso: String = "800",
    val shutterSpeed: String = "1/50",
    val aperture: String = "f/2.8",
    val whiteBalanceKelvin: Int = DEFAULT_WB_KELVIN,
    val isRecording: Boolean = false,
    val batteryPercent: Int = DEFAULT_BATTERY_PERCENT,
    val thermalStatus: ThermalStatus = ThermalStatus.NORMAL,
    val overlayGuides: OverlayGuidesConfig = OverlayGuidesConfig(),
    val touchAfNormalized: Pair<Float, Float>? = null,
    val authRequired: Boolean = false,
    val statusMessage: String = ""
) {
    companion object {
        const val DEFAULT_WB_KELVIN = 5600
        const val DEFAULT_BATTERY_PERCENT = 100
    }
}

@Immutable
data class MultiCamUiState(
    val slots: ImmutableList<CameraSlotUiState> = persistentListOf(),
    val primarySlotId: String? = null,
    val isScanning: Boolean = false,
    val statusBanner: String? = null
)

object CanonModelCatalog {
    private const val FPS_HIGH = 30
    private const val FPS_MID = 24
    private const val FPS_STANDARD = 20
    private const val R5C_WARNING_FPS = 10
    private const val R5C_CRITICAL_FPS = 3

    val supportedIsoValues: ImmutableList<String> = persistentListOf(
        "AUTO", "100", "200", "400", "800", "1600", "3200", "6400", "12800"
    )

    val supportedShutterValues: ImmutableList<String> = persistentListOf(
        "1/30", "1/50", "1/60", "1/100", "1/125", "1/250", "1/500", "1/1000"
    )

    val supportedApertureValues: ImmutableList<String> = persistentListOf(
        "f/1.4", "f/1.8", "f/2.0", "f/2.8", "f/4.0", "f/5.6", "f/8.0", "f/11"
    )

    private fun fullCcapiCapabilities(fps: Int, touchAf: Boolean = true): CameraCapabilities =
        CameraCapabilities(
            supportsLiveView = true,
            supportsTouchAf = touchAf,
            supportsMovieRecord = true,
            supportsPhotoShutter = true,
            supportsIsoControl = true,
            supportsShutterControl = true,
            supportsApertureControl = true,
            supportsWbControl = true,
            maxLiveViewFps = fps
        )

    private fun buildR8Profile(): CameraDeviceProfile = CameraDeviceProfile(
        modelId = "eos_r8",
        displayName = "Canon EOS R8",
        supportedProtocols = persistentListOf(ProtocolType.WIFI_CCAPI, ProtocolType.USB_PTP),
        capabilities = fullCcapiCapabilities(FPS_HIGH, touchAf = true),
        requiresCcapiPreActivation = true,
        minCcapiFirmware = "1.5.0"
    )

    private fun buildR7Profile(): CameraDeviceProfile = CameraDeviceProfile(
        modelId = "eos_r7",
        displayName = "Canon EOS R7",
        supportedProtocols = persistentListOf(ProtocolType.WIFI_CCAPI, ProtocolType.USB_PTP),
        capabilities = fullCcapiCapabilities(FPS_HIGH, touchAf = true),
        requiresCcapiPreActivation = true
    )

    private fun buildEosRProfile(): CameraDeviceProfile = CameraDeviceProfile(
        modelId = "eos_r",
        displayName = "Canon EOS R",
        supportedProtocols = persistentListOf(ProtocolType.WIFI_PTPIP, ProtocolType.USB_PTP),
        capabilities = fullCcapiCapabilities(FPS_STANDARD, touchAf = true)
    )

    private fun buildEosRpProfile(): CameraDeviceProfile = CameraDeviceProfile(
        modelId = "eos_rp",
        displayName = "Canon EOS RP",
        supportedProtocols = persistentListOf(ProtocolType.WIFI_CCAPI, ProtocolType.USB_PTP),
        // CCAPI v1.0.0 on EOS RP supports standard AF, not touchaf coordinates
        capabilities = fullCcapiCapabilities(FPS_STANDARD, touchAf = false)
    )

    private fun buildR6Profile(): CameraDeviceProfile = CameraDeviceProfile(
        modelId = "eos_r6",
        displayName = "Canon EOS R6",
        supportedProtocols = persistentListOf(ProtocolType.WIFI_CCAPI, ProtocolType.USB_PTP),
        capabilities = fullCcapiCapabilities(FPS_MID, touchAf = false)
    )

    private fun buildR6MarkIiProfile(): CameraDeviceProfile = CameraDeviceProfile(
        modelId = "eos_r6_mark_ii",
        displayName = "Canon EOS R6 Mark II",
        supportedProtocols = persistentListOf(ProtocolType.WIFI_CCAPI, ProtocolType.USB_PTP),
        capabilities = fullCcapiCapabilities(FPS_HIGH, touchAf = true),
        requiresCcapiPreActivation = true,
        minCcapiFirmware = "1.7.0"
    )

    private fun buildR6MarkIiiProfile(): CameraDeviceProfile = CameraDeviceProfile(
        modelId = "eos_r6_mark_iii",
        displayName = "Canon EOS R6 Mark III",
        supportedProtocols = persistentListOf(ProtocolType.WIFI_CCAPI, ProtocolType.USB_PTP),
        capabilities = fullCcapiCapabilities(FPS_HIGH, touchAf = true),
        requiresCcapiPreActivation = false
    )

    private fun buildR5CProfile(): CameraDeviceProfile = CameraDeviceProfile(
        modelId = "eos_r5c",
        displayName = "Canon EOS R5 C",
        // Cinema mode uses Canon XC Protocol; Photo mode uses USB PTP / CCAPI
        supportedProtocols = persistentListOf(
            ProtocolType.XC_PROTOCOL,
            ProtocolType.USB_PTP,
            ProtocolType.WIFI_CCAPI
        ),
        capabilities = fullCcapiCapabilities(FPS_HIGH, touchAf = true),
        thermalPolicy = ThermalThrottlePolicy(
            normalFps = FPS_HIGH,
            warningFps = R5C_WARNING_FPS,
            criticalFps = R5C_CRITICAL_FPS,
            pauseLiveViewOnCritical = false
        )
    )

    val allProfiles: ImmutableList<CameraDeviceProfile> = persistentListOf(
        buildR8Profile(),
        buildR7Profile(),
        buildEosRProfile(),
        buildEosRpProfile(),
        buildR6Profile(),
        buildR6MarkIiProfile(),
        buildR6MarkIiiProfile(),
        buildR5CProfile()
    )

    fun resolveByName(rawModelName: String): CameraDeviceProfile {
        val norm = rawModelName.uppercase().trim()
        return when {
            norm.contains("R5 C") || norm.contains("R5C") -> buildR5CProfile()
            norm.contains("R6 MARK III") || norm.contains("R6M3") -> buildR6MarkIiiProfile()
            norm.contains("R6 MARK II") || norm.contains("R6M2") -> buildR6MarkIiProfile()
            norm.contains("R6") -> buildR6Profile()
            norm.contains("R8") -> buildR8Profile()
            norm.contains("R7") -> buildR7Profile()
            norm.contains("RP") -> buildEosRpProfile()
            else -> buildEosRProfile()
        }
    }
}
