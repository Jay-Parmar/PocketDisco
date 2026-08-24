package dev.pocketdisco.phase0

import java.util.UUID

enum class YouTubeItemType(val wireValue: String) {
    VIDEO("video"),
    PLAYLIST("playlist");

    fun validate(itemId: String): String {
        val normalized = when (this) {
            VIDEO -> ProbeInput.videoId(itemId)
            PLAYLIST -> ProbeInput.playlistId(itemId)
        }
        require(normalized == itemId) { "YouTube item ID must not contain surrounding whitespace" }
        return normalized
    }

    companion object {
        fun parse(value: String): YouTubeItemType = entries.firstOrNull { it.wireValue == value }
            ?: throw IllegalArgumentException("Unknown YouTube item type")
    }
}

data class YouTubeControlTrialRequest(
    val itemType: YouTubeItemType,
    val itemId: String,
    val requestedPositionMs: Long,
    val effectiveAtUnixMs: Long,
) {
    init {
        itemType.validate(itemId)
        require(requestedPositionMs in 0..MAX_POSITION_MS) { "YouTube position is out of range" }
        require(effectiveAtUnixMs >= 0) { "YouTube effective time must be non-negative" }
    }
}

data class YouTubeControlTrial(
    val id: String,
    val itemType: YouTubeItemType,
    val itemId: String,
    val requestedPositionMs: Long,
    val effectiveAtUnixMs: Long,
    val createdAtUnixMs: Long,
) {
    companion object {
        fun parse(
            id: String,
            itemType: String,
            itemId: String,
            requestedPositionMs: Long,
            effectiveAtUnixMs: Long,
            createdAtUnixMs: Long,
        ): YouTubeControlTrial {
            val normalizedId = UUID.fromString(id).toString()
            val normalizedType = YouTubeItemType.parse(itemType)
            normalizedType.validate(itemId)
            require(requestedPositionMs in 0..MAX_POSITION_MS) { "YouTube position is out of range" }
            require(createdAtUnixMs >= 0) { "YouTube creation time must be non-negative" }
            require(effectiveAtUnixMs >= createdAtUnixMs) { "YouTube effective time precedes creation" }
            return YouTubeControlTrial(
                id = normalizedId,
                itemType = normalizedType,
                itemId = itemId,
                requestedPositionMs = requestedPositionMs,
                effectiveAtUnixMs = effectiveAtUnixMs,
                createdAtUnixMs = createdAtUnixMs,
            )
        }
    }
}

private const val MAX_POSITION_MS = 86_400_000L
