package com.perflyst.twire.vod

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Instant

class KickVodApiTest {
    private val start = Instant.parse("2026-11-19T21:03:03Z").toEpochMilli()
    private val hour = 60 * 60 * 1000L
    private val id = "01a0251f-ef48-702d-9289-d66922da0e84"
    private val otherId = "01a0251f-ef48-702d-9289-d66922da0e85"

    @Test
    fun parsesCurrentListEnvelopeAndConvertsSecondsToMilliseconds() {
        val result = KickVodApi.parseVods(listJson(recording().put("duration", 48979)))

        assertEquals(listOf(KickVod(id, "A recording", start, 48_979_000L)), result)
        assertEquals(7, result.single().id[14].digitToInt())
    }

    @Test
    fun listIncludesOnlyEndedPublicXqcRecordings() {
        val result = KickVodApi.parseVods(listJson(
            recording(),
            recording().put("is_live", true),
            recording().put("status", "private"),
            recording().put("status", "sub_only"),
            recording().put("status", "unlisted"),
            recording().put("channel", JSONObject().put("id", 669).put("slug", "xqc")),
            recording().put("channel", JSONObject().put("id", 668).put("slug", "another-channel"))
        ))

        assertEquals(listOf(id), result.map { it.id })
    }

    @Test
    fun acceptsEmptyListAndDoesNotRequireMetadataForExcludedRecordings() {
        assertTrue(KickVodApi.parseVods("""{"data":[],"message":"Success"}""").isEmpty())
        assertTrue(KickVodApi.parseVods(listJson(
            JSONObject().put("is_live", true).put("status", "public"),
            JSONObject().put("is_live", false).put("status", "private")
        )).isEmpty())
    }

    @Test
    fun parsesOffsetTimestampsAsInstants() {
        for (timestamp in listOf("2026-11-20T02:48:03+05:45", "2026-11-19T17:03:03-04:00")) {
            val result = KickVodApi.parseVods(listJson(recording().put("start_time", timestamp)))
            assertEquals(start, result.single().startedAtMs)
        }
    }

    @Test
    fun malformedAndRestrictedListsThrowUsefulIoExceptions() {
        for (json in listOf("", "not json", "[]", "{}", """{"data":{}}""",
            """{"data":{"type":"restricted","details":"unavailable"}}""", """{"data":[null]}""")) {
            assertIo { KickVodApi.parseVods(json) }
        }
    }

    @Test
    fun rejectsInvalidEligibleRecordingMetadata() {
        val invalidChanges: List<(JSONObject) -> Unit> = listOf(
            { it.remove("id") },
            { it.put("id", "../other/playback?token=secret") },
            { it.put("id", "1-1-1-1-1") },
            { it.put("id", "https://kick.com/xqc/videos/$id") },
            { it.put("title", JSONObject.NULL) },
            { it.put("start_time", "yesterday") },
            { it.put("start_time", "2026-02-30T21:03:03Z") },
            { it.put("start_time", "1970-01-01T00:00:00Z") },
            { it.remove("start_time") },
            { it.put("duration", -1) },
            { it.put("duration", 0) },
            { it.put("duration", 4.5) },
            { it.put("duration", "3600") },
            { it.put("duration", Long.MAX_VALUE) },
            { it.remove("duration") },
            { it.put("is_live", "false") },
            { it.remove("status") },
            { it.remove("channel") },
            { it.getJSONObject("channel").remove("id") },
            { it.getJSONObject("channel").remove("slug") },
            { it.put("start_time", Instant.ofEpochMilli(Long.MAX_VALUE - hour).toString()) }
        )
        for (change in invalidChanges) {
            val entry = recording()
            change(entry)
            assertIo { KickVodApi.parseVods(listJson(entry)) }
        }
    }

    @Test
    fun playbackReturnsOnlyVodUrlEvenWhenLiveUrlIsPresent() {
        val response = playback()
        assertEquals(vodUrl, KickVodApi.parsePlaybackUrl(response.toString()))
        assertEquals(vodUrl, KickVodApi.parsePlaybackUrl(response.toString(), id.uppercase()))
    }

    @Test
    fun playbackAcceptsNumericCreatorIdToo() {
        val response = playback()
        response.getJSONObject("video_session").put("creator_id", 668)
        assertEquals(vodUrl, KickVodApi.parsePlaybackUrl(response.toString()))
    }

    @Test
    fun playbackRejectsRestrictedEmptyOrLiveOnlyResponses() {
        for (json in listOf(
            "", "bad json", "{}",
            """{"data":{"type":"subscriber_only","details":"Sign in"}}""",
            playback().put("data", JSONObject().put("type", "restricted")).toString(),
            playback().put("playback_url", JSONObject().put("live", liveUrl)).toString(),
            playback().apply { getJSONObject("playback_url").put("vod", "") }.toString(),
            playback().apply { getJSONObject("playback_url").put("vod", JSONObject.NULL) }.toString()
        )) {
            assertIo { KickVodApi.parsePlaybackUrl(json) }
        }
    }

    @Test
    fun playbackRejectsWrongCreatorLiveEncryptedOrMissingSessionMetadata() {
        val invalidChanges: List<(JSONObject) -> Unit> = listOf(
            { it.put("creator_id", "669") },
            { it.remove("creator_id") },
            { it.put("video_stream_status", "live") },
            { it.remove("video_stream_status") },
            { it.put("video_encryption_type", "AES-128") },
            { it.remove("video_encryption_type") },
            { it.put("video_id", "invalid") },
            { it.remove("video_id") }
        )
        for (change in invalidChanges) {
            val response = playback()
            change(response.getJSONObject("video_session"))
            assertIo { KickVodApi.parsePlaybackUrl(response.toString()) }
        }
        assertIo { KickVodApi.parsePlaybackUrl(playback().put("video_session", JSONObject.NULL).toString()) }
        assertIo { KickVodApi.parsePlaybackUrl(playback().toString(), otherId) }
    }

    @Test
    fun playbackRejectsUnsafeUrlsWithoutExposingSignedTokens() {
        for (url in listOf(
            "http://example.com/vod.m3u8?init=secret",
            "//example.com/vod.m3u8?init=secret",
            "https://user:secret@example.com/vod.m3u8",
            "https://example.com/vod.m3u8#secret",
            "https:///vod.m3u8?init=secret",
            "https://example.com/has space?init=secret"
        )) {
            val response = playback()
            response.getJSONObject("playback_url").put("vod", url)
            val error = assertIo { KickVodApi.parsePlaybackUrl(response.toString()) }
            assertFalse(error.toString().contains("secret"))
            assertFalse(error.toString().contains(url))
        }
    }

    @Test
    fun invalidRequestIdsFailBeforeAnyNetworkCall() {
        for (invalid in listOf("", "../$id", "$id?token=secret", "https://example.com/$id")) {
            assertIo { KickVodApi.playbackUrl(vod().copy(id = invalid)) }
        }
    }

    @Test
    fun exactMatchAndBothOffsetSignsMatch() {
        for (offset in listOf(0L, -hour / 2, hour / 2)) {
            val candidate = vod().copy(startedAtMs = start + offset)
            assertEquals(candidate, KickVodApi.findMatch(listOf(candidate), start, 4 * hour))
        }
    }

    @Test
    fun permitsSubstantialLateStartsAndRestarts() {
        val late = vod().copy(startedAtMs = start + hour, durationMs = 3 * hour)
        val restart = vod().copy(startedAtMs = start + 2 * hour, durationMs = hour)
        assertEquals(late, KickVodApi.findMatch(listOf(late), start, 4 * hour))
        assertEquals(restart, KickVodApi.findMatch(listOf(restart), start, 4 * hour))
    }

    @Test
    fun unrelatedDayTouchingIntervalsAndTinyOverlapDoNotMatch() {
        for (candidate in listOf(
            vod().copy(startedAtMs = start - 24 * hour),
            vod().copy(startedAtMs = start + 24 * hour),
            vod().copy(startedAtMs = start - 4 * hour),
            vod().copy(startedAtMs = start + 4 * hour),
            vod().copy(startedAtMs = start + 4 * hour - 60_000),
            vod().copy(durationMs = 3 * 60_000),
            vod().copy(durationMs = hour / 2),
            vod().copy(startedAtMs = start - 3 * hour)
        )) {
            assertNull(KickVodApi.findMatch(listOf(candidate), start, 4 * hour))
        }
    }

    @Test
    fun equalAndSimilarlyPlausibleMatchesAreAmbiguousRegardlessOfOrder() {
        val exact = vod()
        for (alternative in listOf(
            exact.copy(id = otherId),
            exact.copy(id = otherId, startedAtMs = start + hour / 2)
        )) {
            assertNull(KickVodApi.findMatch(listOf(exact, alternative), start, 4 * hour))
            assertNull(KickVodApi.findMatch(listOf(alternative, exact), start, 4 * hour))
        }
    }

    @Test
    fun picksUniqueMaximumCoverageRatherThanFirstEntry() {
        val exact = vod()
        val partial = vod().copy(id = otherId, startedAtMs = start + 2 * hour)
        assertEquals(exact, KickVodApi.findMatch(listOf(partial, exact), start, 4 * hour))
        assertEquals(exact, KickVodApi.findMatch(listOf(exact, partial), start, 4 * hour))
        assertEquals(exact, KickVodApi.findMatch(listOf(exact, exact), start, 4 * hour))
    }

    @Test
    fun invalidMetadataAndConflictingDuplicateIdsNeverProduceMatches() {
        for ((badStart, badDuration) in listOf(
            0L to hour, -1L to hour, start to 0L, start to -1L,
            start to Long.MAX_VALUE, Long.MAX_VALUE - hour to 2 * hour
        )) {
            assertNull(KickVodApi.findMatch(listOf(vod()), badStart, badDuration))
            assertNull(KickVodApi.findMatch(
                listOf(vod().copy(startedAtMs = badStart, durationMs = badDuration)), start, 4 * hour
            ))
        }
        assertNull(KickVodApi.findMatch(emptyList(), start, 4 * hour))
        assertNull(KickVodApi.findMatch(listOf(vod().copy(id = "not-an-id")), start, 4 * hour))
        assertNull(KickVodApi.findMatch(listOf(vod(), vod().copy(startedAtMs = start + hour)), start, 4 * hour))
    }

    private fun vod() = KickVod(id, "A recording", start, 4 * hour)

    private fun recording() = JSONObject()
        .put("id", id)
        .put("title", "A recording")
        .put("start_time", "2026-11-19T21:03:03Z")
        .put("duration", 14_400)
        .put("is_live", false)
        .put("status", "public")
        .put("channel", JSONObject().put("id", 668).put("slug", "xqc").put("username", "xQc"))

    private fun listJson(vararg recordings: JSONObject): String =
        JSONObject().put("data", JSONArray(recordings.toList())).put("message", "Success").toString()

    private val vodUrl = "https://web.kick.com/api/v1/stream/manifest.m3u8?init=test-token"
    private val liveUrl = "https://example.com/live.m3u8"

    private fun playback() = JSONObject()
        .put("playback_url", JSONObject().put("vod", vodUrl).put("live", liveUrl))
        .put("video_session", JSONObject()
            .put("creator_id", "668")
            .put("video_id", id)
            .put("video_stream_status", "vod")
            .put("video_encryption_type", "NONE"))

    private fun assertIo(block: () -> Any?): IOException {
        try {
            block()
        } catch (error: IOException) {
            assertTrue("IOException should explain the failure", !error.message.isNullOrBlank())
            return error
        }
        fail("Expected IOException")
        throw AssertionError("unreachable")
    }
}
