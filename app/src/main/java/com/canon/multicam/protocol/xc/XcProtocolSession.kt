package com.canon.multicam.protocol.xc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.canon.multicam.domain.model.CameraDeviceProfile
import com.canon.multicam.domain.session.CameraSessionController
import com.canon.multicam.protocol.ccapi.CcapiAuthRequiredException
import com.canon.multicam.protocol.ccapi.HttpDigestAuthHelper
import com.canon.multicam.protocol.discovery.CameraNetworkBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Canon XC Protocol controller over HTTP port 80 for EOS R5 C in Cinema mode.
 */
class XcProtocolSession(
    private val hostIp: String,
    private val profile: CameraDeviceProfile,
    username: String = "",
    password: String = "",
    private val onFrameReceived: (Bitmap) -> Unit,
    private val onSessionError: (String) -> Unit
) : CameraSessionController {

    private val baseUrl = "http://$hostIp:$XC_PORT"
    private val digestAuth = HttpDigestAuthHelper(username, password)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var liveViewJob: Job? = null

    @Volatile
    private var targetFps: Int = profile.capabilities.maxLiveViewFps

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val response = executeGet("$baseUrl/api/cam/getprop")
            if (response.isEmpty()) {
                error("Empty XC Protocol response from ${profile.displayName}")
            }
        }
    }

    override fun startLiveView() {
        if (liveViewJob?.isActive == true) return
        liveViewJob = sessionScope.launch {
            while (isActive) {
                val frameIntervalMs = (MILLIS_PER_SECOND / targetFps.coerceAtLeast(1)).toLong()
                val startTime = System.currentTimeMillis()
                runCatching {
                    val jpegBytes = executeGet("$baseUrl/api/cam/lv")
                    val opts = BitmapFactory.Options().apply {
                        inPreferredConfig = Bitmap.Config.RGB_565
                    }
                    BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size, opts)
                        ?.let(onFrameReceived)
                }.onFailure { err ->
                    onSessionError("XC LiveView warning: ${err.message}")
                }
                val waitMs = frameIntervalMs - (System.currentTimeMillis() - startTime)
                if (waitMs > 0) delay(waitMs)
            }
        }
    }

    override fun stopLiveView() {
        liveViewJob?.cancel()
        liveViewJob = null
    }

    override fun updateTargetFps(fps: Int) {
        targetFps = fps
    }

    override suspend fun triggerTouchAf(normX: Float, normY: Float): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val xPct = (normX.coerceIn(0f, 1f) * PERCENT_SCALE).toInt()
                val yPct = (normY.coerceIn(0f, 1f) * PERCENT_SCALE).toInt()
                executeGet("$baseUrl/api/cam/af?mode=spot&x=$xPct&y=$yPct")
                Unit
            }
        }

    override suspend fun setRecording(record: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val cmd = if (record) "start" else "stop"
                executeGet("$baseUrl/api/cam/rec?cmd=$cmd")
                Unit
            }
        }

    override suspend fun triggerShutter(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            executeGet("$baseUrl/api/cam/rec?cmd=photo")
            Unit
        }
    }

    override suspend fun setExposureSetting(
        iso: String?,
        shutterSpeed: String?,
        aperture: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            iso?.let { executeGet("$baseUrl/api/cam/setprop?gain=$it") }
            shutterSpeed?.let { executeGet("$baseUrl/api/cam/setprop?shutter=$it") }
            aperture?.let {
                val av = it.removePrefix("f/").removePrefix("F/")
                executeGet("$baseUrl/api/cam/setprop?iris=$av")
            }
            Unit
        }
    }

    private fun executeGet(urlStr: String, isRetry: Boolean = false): ByteArray {
        val parsedUrl = URL(urlStr)
        val conn = CameraNetworkBinder.openWifiBoundConnection(parsedUrl)
        conn.requestMethod = "GET"
        conn.connectTimeout = HTTP_TIMEOUT_MS
        conn.readTimeout = HTTP_TIMEOUT_MS

        digestAuth.buildAuthorizationHeader("GET", parsedUrl.path)?.let { authHdr ->
            conn.setRequestProperty("Authorization", authHdr)
        }

        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            val wwwAuth = conn.getHeaderField("WWW-Authenticate")
            conn.disconnect()
            if (!isRetry &&
                digestAuth.hasCredentials() &&
                digestAuth.updateChallengeFromHeader(wwwAuth)
            ) {
                return executeGet(urlStr, isRetry = true)
            }
            throw CcapiAuthRequiredException(
                "XC Protocol 401 Auth required on ${profile.displayName}"
            )
        }
        if (code !in HTTP_OK_START..HTTP_OK_END) {
            conn.disconnect()
            throw IOException("XC Protocol HTTP $code on ${parsedUrl.path}")
        }
        return conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
    }

    override fun release() {
        stopLiveView()
        sessionScope.cancel()
    }

    companion object {
        const val XC_PORT = 80
        private const val HTTP_TIMEOUT_MS = 3500
        private const val MILLIS_PER_SECOND = 1000
        private const val PERCENT_SCALE = 100
        private const val HTTP_OK_START = 200
        private const val HTTP_OK_END = 299
    }
}
