package com.canon.multicam.protocol.discovery

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.canon.multicam.domain.model.CameraDeviceProfile
import com.canon.multicam.domain.model.CanonModelCatalog
import com.canon.multicam.domain.model.ProtocolType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

data class DiscoveredCamera(
    val uniqueId: String,
    val displayName: String,
    val profile: CameraDeviceProfile,
    val protocol: ProtocolType,
    val hostIp: String? = null,
    val usbDevice: UsbDevice? = null
)

class CameraDiscoveryManager(private val context: Context) {

    init {
        CameraNetworkBinder.initialize(context)
    }

    suspend fun discoverAllCameras(): List<DiscoveredCamera> = withContext(Dispatchers.IO) {
        val results = mutableListOf<DiscoveredCamera>()
        results.addAll(scanUsbCanonCameras())
        results.addAll(scanSsdpCanonCameras())
        results.distinctBy { it.uniqueId }
    }

    fun scanUsbCanonCameras(): List<DiscoveredCamera> {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
            ?: return emptyList()
        return usbManager.deviceList.values
            .filter { it.vendorId == CANON_USB_VENDOR_ID }
            .map { device ->
                val rawName = device.productName ?: "Canon EOS R"
                val profile = CanonModelCatalog.resolveByName(rawName)
                DiscoveredCamera(
                    uniqueId = "usb_${device.deviceId}",
                    displayName = profile.displayName,
                    profile = profile,
                    protocol = ProtocolType.USB_PTP,
                    usbDevice = device
                )
            }
    }

    private fun scanSsdpCanonCameras(): List<DiscoveredCamera> {
        val found = mutableListOf<DiscoveredCamera>()
        val socket = DatagramSocket()
        CameraNetworkBinder.bindDatagramSocket(socket)
        socket.soTimeout = SSDP_READ_TIMEOUT_MS
        socket.broadcast = true

        try {
            val group = InetAddress.getByName(SSDP_MULTICAST_IP)
            val payload = SSDP_MSEARCH_QUERY.toByteArray(Charsets.UTF_8)
            val packet = DatagramPacket(payload, payload.size, group, SSDP_PORT)
            socket.send(packet)

            val deadline = System.currentTimeMillis() + SSDP_TOTAL_WINDOW_MS
            val recvBuf = ByteArray(SSDP_BUFFER_BYTES)
            while (System.currentTimeMillis() < deadline) {
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                try {
                    socket.receive(recvPacket)
                    val msg = String(recvPacket.data, 0, recvPacket.length, Charsets.UTF_8)
                    val ip = recvPacket.address?.hostAddress ?: continue
                    if (isCanonSsdpResponse(msg)) {
                        val profile = CanonModelCatalog.resolveByName(extractModelHint(msg))
                        found.add(
                            DiscoveredCamera(
                                uniqueId = "wifi_$ip",
                                displayName = "${profile.displayName} ($ip)",
                                profile = profile,
                                protocol = profile.supportedProtocols.first(),
                                hostIp = ip
                            )
                        )
                    }
                } catch (_: SocketTimeoutException) {
                    // Continue waiting until deadline
                }
            }
        } catch (_: Exception) {
            // Network interface unavailable
        } finally {
            socket.close()
        }
        return found
    }

    private fun isCanonSsdpResponse(msg: String): Boolean {
        val upper = msg.uppercase()
        return upper.contains("CANON") ||
            upper.contains("CCAPI") ||
            upper.contains("PTP-IP") ||
            upper.contains("15740")
    }

    private fun extractModelHint(msg: String): String {
        val serverLine = msg.lines().firstOrNull {
            it.startsWith("SERVER:", ignoreCase = true) ||
                it.startsWith("USN:", ignoreCase = true)
        }
        return serverLine ?: "Canon EOS R"
    }

    companion object {
        const val CANON_USB_VENDOR_ID = 0x04A9
        private const val SSDP_MULTICAST_IP = "239.255.255.250"
        private const val SSDP_PORT = 1900
        private const val SSDP_READ_TIMEOUT_MS = 450
        private const val SSDP_TOTAL_WINDOW_MS = 1800L
        private const val SSDP_BUFFER_BYTES = 2048

        private val SSDP_MSEARCH_QUERY = buildString {
            append("M-SEARCH * HTTP/1.1
")
            append("HOST: 239.255.255.250:1900
")
            append("MAN: "ssdp:discover"
")
            append("MX: 2
")
            append("ST: ssdp:all

")
        }
    }
}
