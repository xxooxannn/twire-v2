package com.perflyst.twire.vod

/** Keeps the saved position on Twitch's timeline even when video comes from Kick. */
data class VodTimeline(
    val videoOffsetMs: Long = 0,
    val chatAdjustmentMs: Long = 0
) {
    // Kick starts later than Twitch => a positive offset.
    fun twitchPosition(videoPositionMs: Long): Long =
        (videoPositionMs + videoOffsetMs).coerceAtLeast(0)

    fun videoPosition(twitchPositionMs: Long): Long =
        (twitchPositionMs - videoOffsetMs).coerceAtLeast(0)

    // Leave negative positions negative: there is no Twitch chat before Twitch went live.
    fun chatPosition(videoPositionMs: Long): Long =
        videoPositionMs + videoOffsetMs + chatAdjustmentMs

    fun hasChat(videoPositionMs: Long, twitchDurationMs: Long): Boolean {
        val position = chatPosition(videoPositionMs)
        return position >= 0 && (twitchDurationMs <= 0 || position < twitchDurationMs)
    }

    companion object {
        const val ARG_TWITCH_STARTED_AT_MS = "twitch_vod_started_at_ms"
        const val ARG_TWITCH_DURATION_MS = "twitch_vod_duration_ms"
        const val EXTRA_VIDEO_OFFSET_MS = "vod_video_offset_ms"
        const val EXTRA_KICK_VOD = "kick_vod"
        const val EXTRA_KICK_VOD_ID = "kick_vod_id"
    }
}
