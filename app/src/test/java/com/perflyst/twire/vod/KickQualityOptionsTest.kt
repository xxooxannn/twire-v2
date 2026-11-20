package com.perflyst.twire.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KickQualityOptionsTest {
    @Test
    fun listsOnlyAvailableQualitiesInDescendingResolutionAndFrameRateOrder() {
        val qualities = KickQualityOptions.fromTracks(listOf(
            KickVideoTrack(0, 4, 360, 30f, 800_000),
            KickVideoTrack(1, 0, 720, 60f, 3_000_000),
            KickVideoTrack(0, 0, 1080, 59.94f, 5_000_000),
            KickVideoTrack(0, 3, 480, 30f, 1_200_000),
            KickVideoTrack(0, 1, 1080, 30f, 6_000_000),
            KickVideoTrack(0, 5, 160, 25f, 250_000)
        ))

        assertEquals(listOf("1080p60", "1080p", "720p60", "480p", "360p", "160p"), qualities.map { it.label })
        assertEquals("kick:1080p60", qualities.first().key)
        assertEquals(KickVideoQuality("kick:720p60", "720p60", 1, 0), qualities[2])
    }

    @Test
    fun duplicateLabelsPreferHigherFrameRateThenHigherBitrate() {
        val qualities = KickQualityOptions.fromTracks(listOf(
            KickVideoTrack(0, 0, 720, 59.94f, 6_000_000),
            KickVideoTrack(0, 1, 720, 60f, 3_000_000),
            KickVideoTrack(1, 0, 720, 60f, 4_000_000)
        ))

        assertEquals(listOf(KickVideoQuality("kick:720p60", "720p60", 1, 0)), qualities)
    }

    @Test
    fun treatsInvalidFrameRatesAndBitratesAsUnknown() {
        val qualities = KickQualityOptions.fromTracks(listOf(
            KickVideoTrack(0, 0, 1080, Float.NaN, 4_000_000),
            KickVideoTrack(0, 1, 1080, Float.POSITIVE_INFINITY, 5_000_000),
            KickVideoTrack(0, 2, 1080, 30f, -1),
            KickVideoTrack(0, 3, 720, -1f, -1),
            KickVideoTrack(0, 4, 720, 0f, 3_000_000),
            KickVideoTrack(0, 5, 480, 49.9f, 1_200_000),
            KickVideoTrack(0, 6, 360, 50f, 800_000)
        ))

        assertEquals(listOf("1080p", "720p", "480p", "360p50"), qualities.map { it.label })
        assertEquals(listOf(2, 4, 5, 6), qualities.map { it.trackIndex })
    }

    @Test
    fun excludesUnlabeledAndInvalidTrackReferences() {
        val invalidTracks = listOf(
            KickVideoTrack(0, 0, 0, 30f, 1_000_000),
            KickVideoTrack(0, 1, -1, 30f, 1_000_000),
            KickVideoTrack(-1, 0, 1080, 60f, 1_000_000),
            KickVideoTrack(0, -1, 720, 60f, 1_000_000)
        )

        assertTrue(KickQualityOptions.fromTracks(invalidTracks).isEmpty())
        assertTrue(KickQualityOptions.fromTracks(emptyList()).isEmpty())
    }

    @Test
    fun resolvesSavedResolutionOnlyWhileItIsAvailable() {
        val savedKey = "kick:720p60"
        val tracks = listOf(KickVideoTrack(2, 1, 720, 60f, 3_000_000))
        val available = KickQualityOptions.fromTracks(tracks)
        val other = KickQualityOptions.fromTracks(listOf(KickVideoTrack(0, 0, 720, 30f, 3_000_000)))

        assertEquals(savedKey, KickQualityOptions.resolvePreference(savedKey, available))
        assertEquals("auto", KickQualityOptions.resolvePreference("auto", available))
        assertEquals("auto", KickQualityOptions.resolvePreference(savedKey, other))
        assertEquals("auto", KickQualityOptions.resolvePreference(savedKey, emptyList()))
        assertEquals(savedKey, KickQualityOptions.resolvePreference(savedKey, available))
    }
}
