package com.perflyst.twire.vod

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VodTimelineTest {
    @Test
    fun twitchPlaybackKeepsItsOwnTimeline() {
        val timeline = VodTimeline()
        assertEquals(125678L, timeline.twitchPosition(125678))
        assertEquals(125678L, timeline.videoPosition(125678))
        assertEquals(125678L, timeline.chatPosition(125678))
    }

    @Test
    fun kickStartingLaterAdvancesTwitchChat() {
        val timeline = VodTimeline(videoOffsetMs = 90000)
        assertEquals(100000L, timeline.chatPosition(10000))
        assertEquals(10000L, timeline.videoPosition(100000))
        assertEquals(100000L, timeline.twitchPosition(10000))
    }

    @Test
    fun kickStartingEarlierSkipsItsIntroWhenResumingTwitch() {
        val timeline = VodTimeline(videoOffsetMs = -90000)
        assertEquals(190000L, timeline.videoPosition(100000))
        assertEquals(100000L, timeline.twitchPosition(190000))
        assertEquals(-80000L, timeline.chatPosition(10000))
        assertFalse(timeline.hasChat(10000, 3600000))
        assertTrue(timeline.hasChat(90000, 3600000))
    }

    @Test
    fun sourceSwitchRoundTripsAtSameBroadcastMoment() {
        val earlyKick = VodTimeline(videoOffsetMs = -87321)
        val lateKick = VodTimeline(videoOffsetMs = 96543)
        val twitchPosition = 7200123L
        assertEquals(twitchPosition, earlyKick.twitchPosition(earlyKick.videoPosition(twitchPosition)))
        assertEquals(twitchPosition, lateKick.twitchPosition(lateKick.videoPosition(twitchPosition)))
        assertEquals(twitchPosition, lateKick.chatPosition(lateKick.videoPosition(twitchPosition)))
    }

    @Test
    fun unavailablePositionsClampForPlaybackAndSavedProgress() {
        val timeline = VodTimeline(videoOffsetMs = 60000)
        assertEquals(0L, timeline.videoPosition(30000))
        assertEquals(60000L, timeline.twitchPosition(0))
        assertEquals(0L, VodTimeline(-60000).twitchPosition(30000))
    }

    @Test
    fun manualChatAdjustmentDoesNotMoveVideoOrSavedProgress() {
        val timeline = VodTimeline(videoOffsetMs = 30000, chatAdjustmentMs = 10000)
        assertEquals(50000L, timeline.chatPosition(10000))
        assertEquals(40000L, timeline.twitchPosition(10000))
        assertEquals(10000L, timeline.videoPosition(40000))
        assertEquals(30000L, timeline.copy(chatAdjustmentMs = -10000).chatPosition(10000))
    }

    @Test
    fun chatStopsOutsideTwitchRecordingIncludingSeekAcrossBothEnds() {
        val timeline = VodTimeline(videoOffsetMs = -30000)
        assertFalse(timeline.hasChat(29999, 60000))
        assertTrue(timeline.hasChat(30000, 60000))
        assertTrue(timeline.hasChat(89999, 60000))
        assertFalse(timeline.hasChat(90000, 60000))
        assertTrue(timeline.hasChat(45000, 60000))
        assertFalse(timeline.hasChat(0, 60000))
    }

    @Test
    fun manualOffsetAlsoRespectsTwitchChatBoundaries() {
        val timeline = VodTimeline(chatAdjustmentMs = -10000)
        assertFalse(timeline.hasChat(5000, 60000))
        assertTrue(timeline.hasChat(10000, 60000))
        assertFalse(timeline.hasChat(70000, 60000))
    }
}
