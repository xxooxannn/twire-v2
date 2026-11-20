package com.perflyst.twire

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.TrackGroup
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.perflyst.twire.vod.KickTrackSelection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

@androidx.annotation.OptIn(UnstableApi::class)
class KickTrackSelectionTest {
    private val video = TrackGroup("video",
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1920).setHeight(1080).setFrameRate(60f).build(),
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(1280).setHeight(720).setFrameRate(60f).build(),
        Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264).setWidth(852).setHeight(480).setFrameRate(30f).build()
    )
    private val audio = TrackGroup("audio",
        Format.Builder().setSampleMimeType(MimeTypes.AUDIO_AAC).setSampleRate(48000).setChannelCount(2).build()
    )

    private fun tracks(highestSupported: Boolean = true): Tracks = Tracks(listOf(
        Tracks.Group(video, true,
            intArrayOf(if (highestSupported) C.FORMAT_HANDLED else C.FORMAT_EXCEEDS_CAPABILITIES,
                C.FORMAT_HANDLED, C.FORMAT_HANDLED),
            booleanArrayOf(false, true, false)),
        Tracks.Group(audio, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(true))
    ))

    @Test
    fun fixedResolutionSelectsOneVideoTrackAndPreservesAudio() {
        val audioOverride = TrackSelectionOverride(audio, 0)
        val original = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon()
            .addOverride(audioOverride).build()
        val selected = KickTrackSelection.parameters(original, tracks(), "kick:1080p60")
        assertEquals(listOf(0), selected.overrides[video]!!.trackIndices)
        assertEquals(audioOverride, selected.overrides[audio])
        assertEquals(listOf("1080p60", "720p60", "480p"), KickTrackSelection.qualities(tracks()).map { it.label })
    }

    @Test
    fun autoClearsVideoOverrideAndPreservesAudio() {
        val audioOverride = TrackSelectionOverride(audio, 0)
        val fixed = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT.buildUpon()
            .addOverride(audioOverride).addOverride(TrackSelectionOverride(video, 1)).build()
        // Auto can recover a stalled rendition even with no fresh track snapshot.
        val auto = KickTrackSelection.parameters(fixed, Tracks.EMPTY, "auto")
        assertFalse(auto.overrides.containsKey(video))
        assertEquals(audioOverride, auto.overrides[audio])
    }

    @Test
    fun unsupportedResolutionFallsBackToAuto() {
        val original = TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT
        val available = tracks(highestSupported = false)
        assertEquals(listOf("720p60", "480p"), KickTrackSelection.qualities(available).map { it.label })
        val selected = KickTrackSelection.parameters(original, available, "kick:1080p60")
        assertFalse(selected.overrides.containsKey(video))
    }

    @Test
    fun trickPlayTracksAreNotOfferedAsVideoQualities() {
        val trickPlay = TrackGroup("thumbnails",
            Format.Builder().setSampleMimeType(MimeTypes.VIDEO_H264)
                .setWidth(3840).setHeight(2160).setFrameRate(60f)
                .setRoleFlags(C.ROLE_FLAG_TRICK_PLAY).build()
        )
        val available = Tracks(listOf(
            Tracks.Group(trickPlay, false, intArrayOf(C.FORMAT_HANDLED), booleanArrayOf(false))
        ) + tracks().groups)
        assertEquals(listOf("1080p60", "720p60", "480p"),
            KickTrackSelection.qualities(available).map { it.label })
    }

    @Test
    fun fixedResolutionSurvivesMediaSessionBundleSerialization() {
        val selected = KickTrackSelection.parameters(
            TrackSelectionParameters.DEFAULT_WITHOUT_CONTEXT, tracks(), "kick:720p60"
        )
        val restored = TrackSelectionParameters.fromBundle(selected.toBundle())
        assertEquals(selected, restored)
        assertEquals(listOf(1), restored.overrides[video]!!.trackIndices)
    }
}
