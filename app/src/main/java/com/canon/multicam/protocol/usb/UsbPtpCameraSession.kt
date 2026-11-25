package com.canon.multicam.protocol.usb

import android.graphics.Bitmap
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.canon.multicam.domain.model.CameraDeviceProfile
import com.canon.multicam.domain.session.CameraSessionController
import com.canon.multicam.protocol.ptpip.LiveViewDatasetParser
import com.canon.multicam.protocol.ptpip.PtpIpConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class UsbPtpCameraSession(
    usbManager: UsbManager,
    device: UsbDevice,
    private val profile: CameraDeviceProfile,
    private val onFrameReceived: (Bitmap) -> Unit,
    private val onSessionError: (String) -> Unit
) : CameraSessionController {

    private val transport = UsbPtpTransport(usbManager = usbManager, device = device)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var liveViewJob: Job? = null

    @Volatile
    private var targetFps: Int = profile.capabilities.maxLiveViewFps

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            transport.open()
            val openResp = transport.sendCommand(
                opCode = PtpIpConstants.OP_OPEN_SESSION,
                params = intArrayOf(DEFAULT_SESSION_ID)
            )
            if (openResp != PtpIpConstants.RESP_OK &&
                openResp != PtpIpConstants.RESP_SESSION_NOT_OPEN
            ) {
                error("USB OpenSession failed: 0x${openResp.toString(HEX_RADIX)}")
            }
            transport.sendCommand(PtpIpConstants.OP_CANON_SET_REMOTE_MODE, intArrayOf(1))
            transport.sendCommand(PtpIpConstants.OP_CANON_SET_EVENT_MODE, intArrayOf(1))
            setCanonProperty(
                propCode = PtpIpConstants.PROP_CANON_CAPTURE_DESTINATION,
                value = PtpIpConstants.CAPTURE_TARGET_CARD
            )
        }
    }

    override fun startLiveView() {
        if (liveViewJob?.isActive == true) return
        liveViewJob = sessionScope.launch {
            setCanonProperty(PtpIpConstants.PROP_CANON_EVF_MODE, 1)
            setCanonProperty(
                propCode = PtpIpConstants.PROP_CANON_EVF_OUTPUT_DEVICE,
                value = PtpIpConstants.EVF_OUTPUT_PC
            )

            while (isActive) {
                val frameIntervalMs = (MILLIS_PER_SECOND / targetFps.coerceAtLeast(1)).toLong()
                val startMs = System.currentTimeMillis()
                val (resp, raw) = transport.sendCommandWithDataIn(
                    opCode = PtpIpConstants.OP_CANON_GET_VIEWFINDER_DATA,
                    params = intArrayOf(VIEWFINDER_JPEG_PARAM)
                )
                if (resp == PtpIpConstants.RESP_OK) {
                    LiveViewDatasetParser.extractJpegFrame(raw)?.let(onFrameReceived)
                } else if (resp != PtpIpConstants.RESP_DEVICE_BUSY) {
                    onSessionError("USB LiveView status: 0x${resp.toString(HEX_RADIX)}")
                }
                val waitMs = frameIntervalMs - (System.currentTimeMillis() - startMs)
                if (waitMs > 0) delay(waitMs)
            }
        }
    }

    override fun stopLiveView() {
        liveViewJob?.cancel()
        liveViewJob = null
        sessionScope.launch {
            runCatching {
                setCanonProperty(
                    propCode = PtpIpConstants.PROP_CANON_EVF_OUTPUT_DEVICE,
                    value = PtpIpConstants.EVF_OUTPUT_TFT
                )
            }
        }
    }

    override fun updateTargetFps(fps: Int) {
        targetFps = fps
    }

    override suspend fun triggerTouchAf(normX: Float, normY: Float): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val sensorX = (normX.coerceIn(0f, 1f) * profile.capabilities.sensorWidthUnits).toInt()
                val sensorY = (normY.coerceIn(0f, 1f) * profile.capabilities.sensorHeightUnits).toInt()
                val resp = transport.sendCommand(
                    opCode = PtpIpConstants.OP_CANON_TOUCH_AF_POSITION,
                    params = intArrayOf(TOUCH_AF_MODE_ON, sensorX, sensorY)
                )
                if (resp != PtpIpConstants.RESP_OK) {
                    error("USB TouchAF failed: 0x${resp.toString(HEX_RADIX)}")
                }
            }
        }

    override suspend fun setRecording(record: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val opCode = if (record) {
                    PtpIpConstants.OP_CANON_MOVIE_RECORD_START
                } else {
                    PtpIpConstants.OP_CANON_MOVIE_RECORD_STOP
                }
                val resp = transport.sendCommand(opCode)
                if (resp != PtpIpConstants.RESP_OK) {
                    error("USB Record toggle failed: 0x${resp.toString(HEX_RADIX)}")
                }
            }
        }

    override suspend fun triggerShutter(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val resp = transport.sendCommand(
                opCode = PtpIpConstants.OP_CANON_REMOTE_RELEASE_ON,
                params = intArrayOf(PtpIpConstants.REMOTE_RELEASE_FULL, 0)
            )
            if (resp != PtpIpConstants.RESP_OK) {
                error("USB Shutter failed: 0x${resp.toString(HEX_RADIX)}")
            }
            delay(SHUTTER_HOLD_MS)
            transport.sendCommand(
                opCode = PtpIpConstants.OP_CANON_REMOTE_RELEASE_OFF,
                params = intArrayOf(PtpIpConstants.REMOTE_RELEASE_FULL)
            )
            Unit
        }
    }

    override suspend fun setExposureSetting(
        iso: String?,
        shutterSpeed: String?,
        aperture: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            iso?.let { mapIsoToCanonHex(it) }?.let {
                setCanonProperty(PtpIpConstants.PROP_CANON_ISO, it)
            }
            shutterSpeed?.let { mapShutterToCanonHex(it) }?.let {
                setCanonProperty(PtpIpConstants.PROP_CANON_SHUTTER_SPEED, it)
            }
            aperture?.let { mapApertureToCanonHex(it) }?.let {
                setCanonProperty(PtpIpConstants.PROP_CANON_APERTURE, it)
            }
            Unit
        }
    }

    private fun setCanonProperty(propCode: Int, value: Int): Int {
        val payload = UsbPtpTransport.buildCanonPropertyPayload(propCode, value)
        return transport.sendCommandWithDataOut(
            opCode = PtpIpConstants.OP_CANON_SET_DEVICE_PROP_VALUE_EX,
            payloadData = payload
        )
    }

    override fun release() {
        stopLiveView()
        sessionScope.cancel()
        transport.close()
    }

    companion object {
        private const val DEFAULT_SESSION_ID = 1
        private const val VIEWFINDER_JPEG_PARAM = 0x00100000
        private const val TOUCH_AF_MODE_ON = 1
        private const val SHUTTER_HOLD_MS = 80L
        private const val MILLIS_PER_SECOND = 1000
        private const val HEX_RADIX = 16

        private fun mapIsoToCanonHex(iso: String): Int? = when (iso.uppercase()) {
            "AUTO" -> 0x00
            "100" -> 0x48
            "200" -> 0x50
            "400" -> 0x58
            "800" -> 0x60
            "1600" -> 0x68
            "3200" -> 0x70
            "6400" -> 0x78
            "12800" -> 0x80
            else -> null
        }

        private fun mapShutterToCanonHex(shutter: String): Int? = when (shutter) {
            "1/30" -> 0x50
            "1/50" -> 0x55
            "1/60" -> 0x58
            "1/100" -> 0x60
            "1/125" -> 0x63
            "1/250" -> 0x68
            "1/500" -> 0x70
            "1/1000" -> 0x78
            else -> null
        }

        private fun mapApertureToCanonHex(aperture: String): Int? = when (aperture.lowercase()) {
            "f/1.4" -> 0x10
            "f/1.8" -> 0x15
            "f/2.0" -> 0x18
            "f/2.8" -> 0x20
            "f/4.0" -> 0x28
            "f/5.6" -> 0x30
            "f/8.0" -> 0x38
            "f/11" -> 0x40
            else -> null
        }
    }
}
