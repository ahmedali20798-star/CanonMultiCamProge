package com.canon.multicam.protocol.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import com.canon.multicam.protocol.ptpip.PtpIpConstants
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

class UsbPtpTransport(
    private val usbManager: UsbManager,
    private val device: UsbDevice
) {
    private var connection: UsbDeviceConnection? = null
    private var ptpInterface: UsbInterface? = null
    private var bulkIn: UsbEndpoint? = null
    private var bulkOut: UsbEndpoint? = null
    private val txIdCounter = AtomicInteger(1)

    fun open() {
        val iface = findPtpInterface(device)
            ?: throw IOException("No PTP Still Image interface found on ${device.deviceName}")
        val conn = usbManager.openDevice(device)
            ?: throw IOException("USB permission denied or device disconnected")
        if (!conn.claimInterface(iface, true)) {
            conn.close()
            throw IOException("Failed to claim USB PTP interface")
        }

        for (i in 0 until iface.endpointCount) {
            val ep = iface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                if (ep.direction == UsbConstants.USB_DIR_IN) bulkIn = ep
                if (ep.direction == UsbConstants.USB_DIR_OUT) bulkOut = ep
            }
        }
        if (bulkIn == null || bulkOut == null) {
            conn.releaseInterface(iface)
            conn.close()
            throw IOException("Missing Bulk IN/OUT endpoints")
        }
        ptpInterface = iface
        connection = conn
    }

    @Synchronized
    fun sendCommand(opCode: Int, params: IntArray = intArrayOf()): Int {
        val txId = txIdCounter.getAndIncrement()
        writePtpContainer(
            containerType = TYPE_COMMAND,
            code = opCode,
            txId = txId,
            params = params,
            rawPayload = null
        )
        return readResponseContainer(txId)
    }

    @Synchronized
    fun sendCommandWithDataOut(
        opCode: Int,
        payloadData: ByteArray,
        params: IntArray = intArrayOf()
    ): Int {
        val txId = txIdCounter.getAndIncrement()
        // 1. PIMA 15740 Command Container (0x0001)
        writePtpContainer(
            containerType = TYPE_COMMAND,
            code = opCode,
            txId = txId,
            params = params,
            rawPayload = null
        )
        // 2. PIMA 15740 Data Container (0x0002) carrying the property bytes
        writePtpContainer(
            containerType = TYPE_DATA,
            code = opCode,
            txId = txId,
            params = intArrayOf(),
            rawPayload = payloadData
        )
        // 3. PIMA 15740 Response Container (0x0003)
        return readResponseContainer(txId)
    }

    @Synchronized
    fun sendCommandWithDataIn(opCode: Int, params: IntArray = intArrayOf()): Pair<Int, ByteArray> {
        val txId = txIdCounter.getAndIncrement()
        writePtpContainer(
            containerType = TYPE_COMMAND,
            code = opCode,
            txId = txId,
            params = params,
            rawPayload = null
        )
        return readDataAndResponseContainers(txId)
    }

    private fun writePtpContainer(
        containerType: Short,
        code: Int,
        txId: Int,
        params: IntArray,
        rawPayload: ByteArray?
    ) {
        val conn = connection ?: throw IOException("USB connection closed")
        val outEp = bulkOut ?: throw IOException("USB Bulk OUT missing")
        val payloadBytes = rawPayload?.size ?: (params.size * INT_BYTES)
        val totalLen = PTP_HEADER_BYTES + payloadBytes

        val container = ByteBuffer.allocate(totalLen).order(ByteOrder.LITTLE_ENDIAN)
        container.putInt(totalLen)
        container.putShort(containerType)
        container.putShort(code.toShort())
        container.putInt(txId)
        if (rawPayload != null) {
            container.put(rawPayload)
        } else {
            for (p in params) container.putInt(p)
        }

        val packet = container.array()
        val sent = conn.bulkTransfer(outEp, packet, packet.size, USB_TIMEOUT_MS)
        if (sent < 0) throw IOException("USB Bulk OUT failed for 0x${code.toString(HEX_RADIX)}")

        // Send Zero-Length Packet (ZLP) if transfer size is an exact multiple of maxPacketSize
        if (packet.isNotEmpty() && packet.size % outEp.maxPacketSize == 0) {
            conn.bulkTransfer(outEp, ByteArray(0), 0, USB_TIMEOUT_MS)
        }
    }

    private fun readResponseContainer(expectedTxId: Int): Int {
        val (type, code, txId, _) = readSingleContainer()
        if (type != TYPE_RESPONSE) {
            throw IOException("Expected USB PTP Response (0x0003), got 0x${type.toString(HEX_RADIX)}")
        }
        if (txId != expectedTxId) {
            throw IOException("USB PTP TxID mismatch: expected $expectedTxId got $txId")
        }
        return code
    }

    private fun readDataAndResponseContainers(expectedTxId: Int): Pair<Int, ByteArray> {
        val (firstType, firstCode, firstTxId, firstPayload) = readSingleContainer()
        if (firstTxId != expectedTxId) {
            throw IOException("USB PTP TxID mismatch: expected $expectedTxId got $firstTxId")
        }
        if (firstType == TYPE_RESPONSE) {
            return firstCode to ByteArray(0)
        }
        if (firstType != TYPE_DATA) {
            throw IOException("Unexpected USB PTP container: 0x${firstType.toString(HEX_RADIX)}")
        }
        val respCode = readResponseContainer(expectedTxId)
        return respCode to firstPayload
    }

    private fun readSingleContainer(): ContainerFrame {
        val conn = connection ?: throw IOException("USB connection closed")
        val inEp = bulkIn ?: throw IOException("USB Bulk IN missing")
        val buffer = ByteArray(USB_READ_BUFFER_BYTES)
        val firstRead = conn.bulkTransfer(inEp, buffer, buffer.size, USB_TIMEOUT_MS)
        if (firstRead < PTP_HEADER_BYTES) {
            throw IOException("Incomplete USB PTP header ($firstRead bytes)")
        }
        val hdr = ByteBuffer.wrap(buffer, 0, PTP_HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        val totalLen = hdr.int
        val type = hdr.short
        val code = hdr.short.toInt() and SHORT_MASK
        val txId = hdr.int

        val collector = ByteArrayOutputStream()
        collector.write(buffer, PTP_HEADER_BYTES, firstRead - PTP_HEADER_BYTES)
        var totalReceived = firstRead
        while (totalReceived < totalLen) {
            val chunk = conn.bulkTransfer(inEp, buffer, buffer.size, USB_TIMEOUT_MS)
            if (chunk <= 0) break
            collector.write(buffer, 0, chunk)
            totalReceived += chunk
        }
        return ContainerFrame(type, code, txId, collector.toByteArray())
    }

    fun close() {
        ptpInterface?.let { iface -> runCatching { connection?.releaseInterface(iface) } }
        runCatching { connection?.close() }
        connection = null
    }

    private data class ContainerFrame(
        val type: Short,
        val code: Int,
        val txId: Int,
        val payload: ByteArray
    )

    companion object {
        const val TYPE_COMMAND: Short = 0x0001
        const val TYPE_DATA: Short = 0x0002
        const val TYPE_RESPONSE: Short = 0x0003
        private const val PTP_HEADER_BYTES = 12
        private const val INT_BYTES = 4
        private const val SHORT_MASK = 0xFFFF
        private const val HEX_RADIX = 16
        private const val USB_TIMEOUT_MS = 4000
        private const val USB_READ_BUFFER_BYTES = 65536
        private const val STILL_IMAGE_CLASS = 6

        fun findPtpInterface(device: UsbDevice): UsbInterface? {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == STILL_IMAGE_CLASS) return iface
            }
            return if (device.interfaceCount > 0) device.getInterface(0) else null
        }

        fun buildCanonPropertyPayload(propCode: Int, value: Int): ByteArray =
            ByteBuffer.allocate(PTP_HEADER_BYTES)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(PTP_HEADER_BYTES)
                .putInt(propCode)
                .putInt(value)
                .array()

        fun buildCaptureToCardPayload(): ByteArray =
            buildCanonPropertyPayload(
                propCode = PtpIpConstants.PROP_CANON_CAPTURE_DESTINATION,
                value = PtpIpConstants.CAPTURE_TARGET_CARD
            )
    }
}
