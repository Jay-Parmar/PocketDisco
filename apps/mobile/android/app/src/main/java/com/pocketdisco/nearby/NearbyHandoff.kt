package com.pocketdisco.nearby

class NearbyHandoff {
    private var owner: String? = null
    private var away = false

    fun open(roomId: String) {
        require(roomId.isNotBlank() && roomId.length <= 64) { "Join a room before opening nearby audio." }
        check(owner == null) { "Nearby audio is already open." }
        owner = roomId
        away = false
    }

    fun hostPaused() {
        if (owner != null) away = true
    }

    fun hostReturned(): Boolean = if (away) close() else false

    fun close(): Boolean {
        val open = owner != null
        owner = null
        away = false
        return open
    }
}

internal fun nearbyAudioSupported(enabled: Boolean, sdk: Int) = enabled && sdk >= 29
