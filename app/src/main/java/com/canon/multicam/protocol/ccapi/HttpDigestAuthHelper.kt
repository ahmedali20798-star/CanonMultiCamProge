package com.canon.multicam.protocol.ccapi

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicInteger

class HttpDigestAuthHelper(
    private val username: String,
    private val password: String
) {
    private val nonceCounter = AtomicInteger(0)
    private var cachedChallenge: DigestChallenge? = null

    fun hasCredentials(): Boolean = username.isNotBlank() && password.isNotBlank()

    fun updateChallengeFromHeader(wwwAuthenticateHeader: String?): Boolean {
        if (wwwAuthenticateHeader.isNullOrBlank()) return false
        if (!wwwAuthenticateHeader.trim().startsWith("Digest", ignoreCase = true)) return false
        val params = parseDigestParams(wwwAuthenticateHeader)
        val realm = params["realm"] ?: return false
        val nonce = params["nonce"] ?: return false
        cachedChallenge = DigestChallenge(
            realm = realm,
            nonce = nonce,
            qop = params["qop"],
            opaque = params["opaque"],
            algorithm = params["algorithm"] ?: "MD5"
        )
        nonceCounter.set(0)
        return true
    }

    fun buildAuthorizationHeader(method: String, requestUri: String): String? {
        val challenge = cachedChallenge ?: return null
        if (!hasCredentials()) return null

        val algoName = when {
            challenge.algorithm.contains("256", ignoreCase = true) -> "SHA-256"
            else -> "MD5"
        }
        val ha1 = hashHex("$username:${challenge.realm}:$password", algoName)
        val ha2 = hashHex("${method.uppercase()}:$requestUri", algoName)

        val selectedQop = selectQop(challenge.qop)
        val sb = StringBuilder("Digest ")
        sb.append("username="$username", ")
        sb.append("realm="${challenge.realm}", ")
        sb.append("nonce="${challenge.nonce}", ")
        sb.append("uri="$requestUri", ")

        if (selectedQop != null) {
            val ncVal = String.format("%08x", nonceCounter.incrementAndGet())
            val cnonce = generateCnonce()
            val responseHash = hashHex(
                "$ha1:${challenge.nonce}:$ncVal:$cnonce:$selectedQop:$ha2",
                algoName
            )
            sb.append("qop=$selectedQop, ")
            sb.append("nc=$ncVal, ")
            sb.append("cnonce="$cnonce", ")
            sb.append("response="$responseHash"")
        } else {
            val responseHash = hashHex("$ha1:${challenge.nonce}:$ha2", algoName)
            sb.append("response="$responseHash"")
        }

        challenge.opaque?.let { sb.append(", opaque="$it"") }
        return sb.toString()
    }

    private fun selectQop(rawQop: String?): String? {
        if (rawQop.isNullOrBlank()) return null
        val tokens = rawQop.split(",").map { it.trim() }
        return if (tokens.contains("auth")) "auth" else tokens.firstOrNull()
    }

    private fun generateCnonce(): String {
        val bytes = ByteArray(CNONCE_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun hashHex(input: String, algorithm: String): String {
        val digest = MessageDigest.getInstance(algorithm).digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    data class DigestChallenge(
        val realm: String,
        val nonce: String,
        val qop: String?,
        val opaque: String?,
        val algorithm: String
    )

    companion object {
        private const val CNONCE_BYTES = 8
        private val PARAM_REGEX = Regex("""(\w+)=("([^"]*)"|([^,\s]*))""")

        fun parseDigestParams(header: String): Map<String, String> {
            val body = header.substringAfter("Digest", "").trim()
            val result = mutableMapOf<String, String>()
            for (match in PARAM_REGEX.findAll(body)) {
                val key = match.groupValues[1].lowercase()
                val value = match.groupValues[3].ifEmpty { match.groupValues[4] }
                result[key] = value
            }
            return result
        }
    }
}
