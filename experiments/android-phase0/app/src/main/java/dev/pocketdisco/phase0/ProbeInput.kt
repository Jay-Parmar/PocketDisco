package dev.pocketdisco.phase0

import java.net.URI
import java.util.Locale

object ProbeInput {
    private val videoIdPattern = Regex("^[A-Za-z0-9_-]{11}$")
    private val playlistIdPattern = Regex("^[A-Za-z0-9_-]{10,100}$")
    private val assetSha256Pattern = Regex("^[A-Fa-f0-9]{64}$")

    fun licensedAssetUrl(value: String): String {
        val uri = parseHttps(value, allowPath = true)
        require(uri.fragment == null) { "Asset URL must not contain a fragment" }
        val path = uri.path.lowercase(Locale.US)
        require(path.endsWith(".m4a") || path.endsWith(".mp4")) {
            "Use a seek-friendly .m4a or .mp4 asset"
        }
        return uri.toASCIIString()
    }

    fun webOrigin(value: String): String {
        val uri = parseHttps(value, allowPath = false)
        require(uri.userInfo == null) { "Origin must not contain user information" }
        require(uri.query == null && uri.fragment == null) { "Origin must not contain a query or fragment" }
        return buildString {
            append("https://")
            append(uri.host.lowercase(Locale.US))
            if (uri.port != -1) {
                append(':')
                append(uri.port)
            }
        }
    }

    fun videoId(value: String): String = value.trim().also {
        require(videoIdPattern.matches(it)) { "Enter an 11-character YouTube video ID" }
    }

    fun playlistId(value: String): String = value.trim().also {
        require(playlistIdPattern.matches(it)) { "Enter a valid YouTube playlist ID" }
    }

    fun assetSha256(value: String): String = value.trim().also {
        require(assetSha256Pattern.matches(it)) { "Enter the asset SHA-256 as 64 hexadecimal characters" }
    }.lowercase(Locale.US)

    fun coordinatorBaseUrl(value: String): String {
        val uri = try {
            URI(value.trim())
        } catch (_: Exception) {
            throw IllegalArgumentException("Enter a valid coordinator URL")
        }
        val scheme = uri.scheme?.lowercase(Locale.US)
        require(scheme == "http" || scheme == "https") { "Coordinator URL must use HTTP or HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Coordinator URL must include a host" }
        require(uri.userInfo == null && uri.query == null && uri.fragment == null) {
            "Coordinator URL must not contain credentials, a query, or a fragment"
        }
        require(uri.path.isNullOrEmpty() || uri.path == "/") { "Coordinator URL must not contain a path" }
        if (scheme == "http") {
            require(isPrivateLanHost(uri.host)) { "Plain HTTP is limited to a private LAN host" }
        }
        return buildString {
            append(scheme)
            append("://")
            append(uri.host.lowercase(Locale.US))
            if (uri.port != -1) {
                append(':')
                append(uri.port)
            }
        }
    }

    private fun parseHttps(value: String, allowPath: Boolean): URI {
        val uri = try {
            URI(value.trim())
        } catch (_: Exception) {
            throw IllegalArgumentException("Enter a valid HTTPS URL")
        }
        require(uri.scheme.equals("https", ignoreCase = true)) { "HTTPS is required" }
        require(!uri.host.isNullOrBlank()) { "URL must include a host" }
        require(uri.userInfo == null) { "URL must not contain user information" }
        if (!allowPath) {
            require(uri.path.isNullOrEmpty() || uri.path == "/") { "Origin must not contain a path" }
        }
        return uri
    }

    private fun isPrivateLanHost(host: String): Boolean {
        val normalized = host.lowercase(Locale.US).removePrefix("[").removeSuffix("]")
        if (normalized == "localhost" || normalized.endsWith(".local")) return true
        val octets = normalized.split('.')
        if (octets.size == 4) {
            val values = octets.map { it.toIntOrNull() ?: return false }
            if (values.any { it !in 0..255 }) return false
            if (values[0] == 10 || values[0] == 127) return true
            if (values[0] == 192 && values[1] == 168) return true
            if (values[0] == 172 && values[1] in 16..31) return true
        }
        if (!normalized.contains(':') || !normalized.matches(Regex("^[0-9a-f:]+$"))) return false
        if (normalized == "::1" || normalized.startsWith("fc") || normalized.startsWith("fd")) return true
        val firstHextet = normalized.substringBefore(':').toIntOrNull(16) ?: return false
        return firstHextet in 0xfe80..0xfebf
    }
}
