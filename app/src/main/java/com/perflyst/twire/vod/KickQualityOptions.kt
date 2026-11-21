package com.perflyst.twire.vod

import kotlin.math.roundToInt

data class KickVideoTrack(
    val groupIndex: Int,
    val trackIndex: Int,
    val height: Int,
    val frameRate: Float,
    val bitrate: Int,
    val exceedsCapabilities: Boolean = false
)

data class KickVideoQuality(
    val key: String,
    val label: String,
    val groupIndex: Int,
    val trackIndex: Int,
    val exceedsCapabilities: Boolean = false
)

object KickQualityOptions {
    fun fromTracks(tracks: List<KickVideoTrack>): List<KickVideoQuality> = tracks
        .filter { it.height > 0 && it.groupIndex >= 0 && it.trackIndex >= 0 }
        .sortedWith(
            compareByDescending<KickVideoTrack> { it.height }
                .thenByDescending { knownFrameRate(it.frameRate) }
                // Prefer a decoder-advertised track when Kick has multiple codecs for one rung.
                .thenBy { it.exceedsCapabilities }
                .thenByDescending { it.bitrate.coerceAtLeast(0) }
        )
        .map { track ->
            val frameRate = knownFrameRate(track.frameRate)
            val suffix = if (frameRate >= 50f) frameRate.roundToInt().toString() else ""
            val label = "${track.height}p$suffix"
            KickVideoQuality(
                "kick:$label", label, track.groupIndex, track.trackIndex, track.exceedsCapabilities
            )
        }
        .distinctBy { it.key }

    fun resolvePreference(savedKey: String, qualities: List<KickVideoQuality>): String =
        if (qualities.any { it.key == savedKey }) savedKey else "auto"

    private fun knownFrameRate(frameRate: Float): Float =
        if (frameRate.isFinite() && frameRate > 0f) frameRate else 0f
}
