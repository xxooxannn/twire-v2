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
                    if (!group.isTrackSupported(trackIndex)) continue
                    val format = group.getTrackFormat(trackIndex)
                    available += KickVideoTrack(
                        groupIndex, trackIndex, format.height, format.frameRate, format.bitrate
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
