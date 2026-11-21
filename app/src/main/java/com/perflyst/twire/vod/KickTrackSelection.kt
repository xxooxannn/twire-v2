package com.perflyst.twire.vod

import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi

/** Fixed video selection on the master playlist keeps its audio and playback timeline intact. */
@UnstableApi
object KickTrackSelection {
    fun qualities(tracks: Tracks): List<KickVideoQuality> {
        val available = ArrayList<KickVideoTrack>()
        tracks.groups.forEachIndexed { groupIndex, group ->
            if (group.type == C.TRACK_TYPE_VIDEO) {
                for (trackIndex in 0 until group.length) {
                    // A MIME-compatible track above the decoder's advertised size/rate limit
                    // may still play; let the user force it instead of hiding the quality.
                    if (!group.isTrackSupported(trackIndex, /* allowExceedsCapabilities= */ true)) continue
                    val format = group.getTrackFormat(trackIndex)
                    if ((format.roleFlags and C.ROLE_FLAG_TRICK_PLAY) != 0) continue
                    available += KickVideoTrack(
                        groupIndex,
                        trackIndex,
                        format.height,
                        format.frameRate,
                        format.bitrate,
                        group.getTrackSupport(trackIndex) == C.FORMAT_EXCEEDS_CAPABILITIES
                    )
                }
            }
        }
        return KickQualityOptions.fromTracks(available)
    }

    fun parameters(
        current: TrackSelectionParameters,
        tracks: Tracks,
        qualityKey: String
    ): TrackSelectionParameters {
        val builder = current.buildUpon().clearOverridesOfType(C.TRACK_TYPE_VIDEO)
        val quality = qualities(tracks).firstOrNull { it.key == qualityKey }
        if (quality != null) {
            val group = tracks.groups[quality.groupIndex]
            builder.addOverride(TrackSelectionOverride(group.mediaTrackGroup, quality.trackIndex))
        }
        return builder.build()
    }
}
