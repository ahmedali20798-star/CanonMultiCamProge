package com.canon.multicam.protocol.ccapi

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.canon.multicam.domain.model.CameraDeviceProfile
import com.canon.multicam.domain.session.CameraSessionController
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
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

class CcapiAuthRequiredException(message: String) : IOException(message)

data class CcapiServiceMap(
    val endpointsBySuffix: Map<String, String>
) {
    fun resolveUrl(baseUrl: String, suffix: String): String? {
        val normalized = suffix.trimStart('/')
        val path = endpointsBySuffix[normalized] ?: return null
        return if (path.startsWith("http")) path else "$baseUrl$path"
    }

    fun hasEndpoint(suffix: String): Boolean =
        endpointsBySuffix.containsKey(suffix.trimStart('/'))

    companion object {
        private val VERSION_KEY_REGEX = Regex("""^ver\d+$""")

        fun parseFromRootJson(rootJson: JSONObject): CcapiServiceMap {
            val found = linkedMapOf<String, String>()
            val versionKeys = rootJson.keys().asSequence()
                .filter { VERSION_KEY_REGEX.matches(it) }
                .sorted()
                .toList()

            for (verKey in versionKeys) {
                val array = rootJson.optJSONArray(verKey) ?: continue
                for (i in 0 until array.length()) {
                    val entry = array.optJSONObject(i) ?: continue
                    val path = entry.optString("path", "")
                    if (path.isBlank()) continue
                    val suffix = path.substringAfter("/$verKey/", "")
                    if (suffix.isNotBlank()) {
                        found[suffix] = path
                    }
                }
            }
            return CcapiServiceMap(endpointsBySuffix = found)
        }
    }
}

class CcapiCameraSession(
    private val hostIp: String,
    private val profile: CameraDeviceProfile,
    username: String = "",
    password: String = "",
    private val onFrameReceived: (Bitmap) -> Unit,
    private val onSessionError: (String) -> Unit
) : CameraSessionController {

    private val baseUrl = "http://$hostIp:$CCAPI_PORT"
    private val digestAuth = HttpDigestAuthHelper(username, password)
    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var liveViewJob: Job? = null
    private var serviceMap: CcapiServiceMap = CcapiServiceMap(emptyMap())

    @Volatile
    private var targetFps: Int = profile.capabilities.maxLiveViewFps

    override suspend fun connect(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val discoveryResponse = executeRequest(
                urlStr = "$baseUrl/ccapi",
                method = "GET",
                jsonBody = null
            )
            val rootJson = JSONObject(discoveryResponse.decodeToString())
            serviceMap = CcapiServiceMap.parseFromRootJson(rootJson)
            if (serviceMap.endpointsBySuffix.isEmpty()) {
                error(buildActivationHintMessage())
            }
        }
    }

    override fun startLiveView() {
        if (liveViewJob?.isActive == true) return
        liveViewJob = sessionScope.launch {
            val lvUrl = resolveEndpointOrDefault(
                suffix = "shooting/liveview",
                fallbackPath = "/ccapi/ver100/shooting/liveview"
            )
            val startPayload = JSONObject()
                .put("liveviewsize", "medium")
                .put("cameradisplay", "on")
                .toString()
            runCatching { executeRequest(lvUrl, "POST", startPayload) }

            val flipUrl = resolveEndpointOrDefault(
                suffix = "shooting/liveview/flip",
                fallbackPath = "/ccapi/ver100/shooting/liveview/flip"
            )

            while (isActive) {
                val frameIntervalMs = (MILLIS_PER_SECOND / targetFps.coerceAtLeast(1)).toLong()
                val startTime = System.currentTimeMillis()
                runCatching {
                    val jpegBytes = executeRequest(flipUrl, "GET", null)
                    val bitmap = decodeJpegRgb565(jpegBytes)
                    if (bitmap != null) {
                        onFrameReceived(bitmap)
                    }
                }.onFailure { err ->
                    onSessionError("CCAPI LiveView interrupted: ${err.message}")
                }
                val waitMs = frameIntervalMs - (System.currentTimeMillis() - startTime)
                if (waitMs > 0) delay(waitMs)
            }
        }
    }

    override fun stopLiveView() {
        liveViewJob?.cancel()
        liveViewJob = null
        sessionScope.launch {
            val lvUrl = resolveEndpointOrDefault(
                suffix = "shooting/liveview",
                fallbackPath = "/ccapi/ver100/shooting/liveview"
            )
            val stopPayload = JSONObject()
                .put("liveviewsize", "off")
                .put("cameradisplay", "on")
                .toString()
            runCatching { executeRequest(lvUrl, "POST", stopPayload) }
        }
    }

    override fun updateTargetFps(fps: Int) {
        targetFps = fps
    }

    override suspend fun triggerTouchAf(normX: Float, normY: Float): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val touchAfUrl = serviceMap.resolveUrl(baseUrl, "shooting/control/touchaf")
                if (touchAfUrl != null) {
                    val sensorX = (normX.coerceIn(0f, 1f) * profile.capabilities.sensorWidthUnits)
                        .toInt()
                    val sensorY = (normY.coerceIn(0f, 1f) * profile.capabilities.sensorHeightUnits)
                        .toInt()
                    val body = JSONObject().put(
                        "position",
                        JSONObject().put("x", sensorX).put("y", sensorY)
                    ).toString()
                    executeRequest(touchAfUrl, "POST", body)
                } else {
                    // Fallback for CCAPI v1.0.0 bodies (EOS RP, EOS R6 Mark I) without touchaf
                    val afUrl = serviceMap.resolveUrl(baseUrl, "shooting/control/af")
                        ?: error("Neither touchaf nor standard AF endpoint advertised by camera")
                    executeRequest(afUrl, "POST", JSONObject().put("action", "start").toString())
                    delay(AF_PULSE_MS)
                    executeRequest(afUrl, "POST", JSONObject().put("action", "stop").toString())
                }
                Unit
            }
        }

    override suspend fun setRecording(record: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = resolveEndpointOrDefault(
                    suffix = "shooting/control/recbutton",
                    fallbackPath = "/ccapi/ver100/shooting/control/recbutton"
                )
                val action = if (record) "start" else "stop"
                val body = JSONObject().put("action", action).toString()
                executeRequest(url, "POST", body)
                Unit
            }
        }

    override suspend fun triggerShutter(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val url = resolveEndpointOrDefault(
                suffix = "shooting/control/shutterbutton",
                fallbackPath = "/ccapi/ver100/shooting/control/shutterbutton"
            )
            val body = JSONObject().put("af", true).toString()
            executeRequest(url, "POST", body)
            Unit
        }
    }

    override suspend fun setExposureSetting(
        iso: String?,
        shutterSpeed: String?,
        aperture: String?
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            iso?.let {
                val url = resolveEndpointOrDefault(
                    suffix = "shooting/settings/iso",
                    fallbackPath = "/ccapi/ver100/shooting/settings/iso"
                )
                executeRequest(url, "PUT", JSONObject().put("value", it.lowercase()).toString())
            }
            shutterSpeed?.let {
                val url = resolveEndpointOrDefault(
                    suffix = "shooting/settings/tv",
                    fallbackPath = "/ccapi/ver100/shooting/settings/tv"
                )
                executeRequest(url, "PUT", JSONObject().put("value", it).toString())
            }
            aperture?.let {
                val url = resolveEndpointOrDefault(
                    suffix = "shooting/settings/av",
                    fallbackPath = "/ccapi/ver100/shooting/settings/av"
                )
                val cleanAv = it.removePrefix("f/").removePrefix("F/")
                executeRequest(url, "PUT", JSONObject().put("value", cleanAv).toString())
            }
            Unit
        }
    }

    private fun resolveEndpointOrDefault(suffix: String, fallbackPath: String): String =
        serviceMap.resolveUrl(baseUrl, suffix) ?: "$baseUrl$fallbackPath"

    private fun buildActivationHintMessage(): String {
        val fwHint = profile.minCcapiFirmware?.let { " (Firmware $it+)" } ?: ""
        return "CCAPI unavailable on ${profile.displayName}$fwHint. " +
            "Enable Camera Control API on camera or switch to USB PTP."
    }

    private fun executeRequest(
        urlStr: String,
        method: String,
        jsonBody: String?,
        isDigestRetry: Boolean = false
    ): ByteArray {
        val parsedUrl = URL(urlStr)
        val conn = CameraNetworkBinder.openWifiBoundConnection(parsedUrl)
        conn.requestMethod = method
        conn.connectTimeout = HTTP_TIMEOUT_MS
        conn.readTimeout = HTTP_TIMEOUT_MS
        conn.setRequestProperty("Accept", "application/json, image/jpeg")

        digestAuth.buildAuthorizationHeader(method, parsedUrl.path)?.let { authHdr ->
            conn.setRequestProperty("Authorization", authHdr)
        }

        if (jsonBody != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }
        }

        val code = conn.responseCode
        if (code == HttpURLConnection.HTTP_UNAUTHORIZED) {
            val wwwAuth = conn.getHeaderField("WWW-Authenticate")
            conn.disconnect()
            if (!isDigestRetry &&
                digestAuth.hasCredentials() &&
                digestAuth.updateChallengeFromHeader(wwwAuth)
            ) {
                return executeRequest(urlStr, method, jsonBody, isDigestRetry = true)
            }
            throw CcapiAuthRequiredException(
                "HTTP 401 Digest Auth required by ${profile.displayName}. Enter CCAPI credentials."
            )
        }

        if (code !in HTTP_OK_START..HTTP_OK_END) {
            val errMsg = conn.errorStream?.bufferedReader()?.readText().orEmpty()
            conn.disconnect()
            throw IOException("CCAPI HTTP $code on ${parsedUrl.path}: $errMsg")
        }
        return conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
    }

    private fun decodeJpegRgb565(bytes: ByteArray): Bitmap? {
        val opts = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }

    override fun release() {
        stopLiveView()
        sessionScope.cancel()
    }

    companion object {
        const val CCAPI_PORT = 8080
        private const val HTTP_TIMEOUT_MS = 3500
        private const val MILLIS_PER_SECOND = 1000
        private const val AF_PULSE_MS = 180L
        private const val HTTP_OK_START = 200
        private const val HTTP_OK_END = 299
    }
}
