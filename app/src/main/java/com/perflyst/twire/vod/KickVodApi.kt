package com.perflyst.twire.vod

import com.perflyst.twire.service.Service
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.URI
import java.net.URISyntaxException
import java.time.DateTimeException
import java.time.Instant
import java.util.concurrent.TimeUnit

data class KickVod(
    val id: String,
    val title: String,
    val startedAtMs: Long,
    val durationMs: Long
)

/** Public, ended xqc recordings only. Network methods must run off the main thread. */
object KickVodApi {
    private const val CREATOR_ID = "668"
    private const val BASE_URL = "https://web.kick.com/api/v1"
    private const val MIN_OVERLAP_MS = 5 * 60 * 1000L
    private val videoIdPattern = Regex("[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}")
    private val client by lazy {
        Service.client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .callTimeout(20, TimeUnit.SECONDS)
            .followRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }

    @Throws(IOException::class)
    fun listXqcVods(): List<KickVod> {
        val request = Request.Builder()
            .url("$BASE_URL/channels/$CREATOR_ID/videos")
            .header("User-Agent", "Twire")
            .header("Accept", "application/json")
            .build()
        return parseVods(requestJson(request, "VOD list"))
    }

    @Throws(IOException::class)
    fun playbackUrl(vod: KickVod): String {
        requireVideoId(vod.id)
        val payload = """{"video_player":{"player":{}},"video_session":{},"user_session":{"non_personalised_ads":true}}"""
        val request = Request.Builder()
            .url("$BASE_URL/stream/${vod.id}/playback")
            .header("User-Agent", "Twire")
            .header("Accept", "application/json")
            .header("Origin", "https://kick.com")
            .header("Referer", "https://kick.com/")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()
        return parsePlaybackUrl(requestJson(request, "VOD playback"), vod.id)
    }

    /**
     * Compare actual recording intervals, including late starts and restarts. Require five
     * minutes of overlap, half of the shorter recording and a quarter of the longer one.
     * A runner-up with at least 80% of the best overlap is too ambiguous to select safely.
     */
    fun findMatch(
        vods: List<KickVod>,
        twitchStartedAtMs: Long,
        twitchDurationMs: Long
    ): KickVod? {
        val twitchEnd = endTime(twitchStartedAtMs, twitchDurationMs) ?: return null
        // Repeated copies of a recording are harmless; conflicting metadata is not.
        val unique = vods.distinct()
        if (unique.groupBy { it.id.lowercase() }.values.any { it.size > 1 }) return null
        val candidates = unique.mapNotNull { vod ->
            if (!videoIdPattern.matches(vod.id)) return@mapNotNull null
            val end = endTime(vod.startedAtMs, vod.durationMs) ?: return@mapNotNull null
            val overlap = minOf(twitchEnd, end) - maxOf(twitchStartedAtMs, vod.startedAtMs)
            val shorter = minOf(twitchDurationMs, vod.durationMs)
            val longer = maxOf(twitchDurationMs, vod.durationMs)
            if (overlap < MIN_OVERLAP_MS || overlap.toDouble() / shorter < 0.5 ||
                overlap.toDouble() / longer < 0.25
            ) return@mapNotNull null
            vod to overlap
        }.sortedByDescending { it.second }
        val best = candidates.firstOrNull() ?: return null
        if (candidates.size > 1 && candidates[1].second.toDouble() / best.second >= 0.8) {
            return null
        }
        return best.first
    }

    @Throws(IOException::class)
    internal fun parseVods(json: String): List<KickVod> {
        val root = parseObject(json, "VOD list")
        val entries = root.opt("data") as? JSONArray
            ?: throw IOException("Kick VOD list is missing its recordings array")
        val result = ArrayList<KickVod>()
        for (index in 0 until entries.length()) {
            val entry = entries.opt(index) as? JSONObject
                ?: throw IOException("Kick VOD list contains an invalid recording")
            val isLive = entry.opt("is_live") as? Boolean
                ?: throw IOException("Kick VOD list is missing recording live status")
            val status = entry.opt("status") as? String
                ?: throw IOException("Kick VOD list is missing recording visibility")
            if (isLive || status != "public") continue
            val channel = entry.optJSONObject("channel")
                ?: throw IOException("Kick VOD list is missing channel metadata")
            val channelId = when (val value = channel.opt("id")) {
                is Number, is String -> value.toString()
                else -> throw IOException("Kick VOD list is missing its channel ID")
            }
            val slug = channel.opt("slug") as? String
                ?: throw IOException("Kick VOD list is missing its channel slug")
            if (channelId != CREATOR_ID || !slug.equals("xqc", ignoreCase = true)) continue
            val id = entry.opt("id") as? String
                ?: throw IOException("Kick VOD list is missing a recording ID")
            requireVideoId(id)
            val title = entry.opt("title") as? String
                ?: throw IOException("Kick VOD list is missing a recording title")
            val start = entry.opt("start_time") as? String
                ?: throw IOException("Kick VOD list is missing a recording start time")
            val startedAtMs = try {
                Instant.parse(start).toEpochMilli()
            } catch (_: DateTimeException) {
                throw IOException("Kick VOD list contains an invalid start time")
            } catch (_: ArithmeticException) {
                throw IOException("Kick VOD list contains an out-of-range start time")
            }
            // The public API reports seconds, not milliseconds. Reject fractional/overflowing values.
            val seconds = (entry.opt("duration") as? Number)?.toString()?.toLongOrNull()
                ?: throw IOException("Kick VOD list contains an invalid duration")
            if (seconds <= 0 || seconds > Long.MAX_VALUE / 1000) {
                throw IOException("Kick VOD list contains an out-of-range duration")
            }
            val durationMs = seconds * 1000
            if (endTime(startedAtMs, durationMs) == null) {
                throw IOException("Kick VOD list contains invalid recording times")
            }
            result.add(KickVod(id, title, startedAtMs, durationMs))
        }
        return result.distinct()
    }

    @Throws(IOException::class)
    internal fun parsePlaybackUrl(json: String): String = parsePlaybackUrl(json, null)

    @Throws(IOException::class)
    internal fun parsePlaybackUrl(json: String, expectedVideoId: String?): String {
        if (expectedVideoId != null) requireVideoId(expectedVideoId)
        val root = parseObject(json, "VOD playback")
        if (root.has("data") && !root.isNull("data")) {
            throw IOException("Kick VOD playback is restricted or unavailable")
        }
        val session = root.optJSONObject("video_session")
            ?: throw IOException("Kick VOD playback is missing session metadata")
        if (session.opt("creator_id")?.toString() != CREATOR_ID) {
            throw IOException("Kick VOD playback is not for xqc")
        }
        val videoId = session.opt("video_id") as? String
            ?: throw IOException("Kick VOD playback is missing its recording ID")
        requireVideoId(videoId)
        if (expectedVideoId != null && !videoId.equals(expectedVideoId, ignoreCase = true)) {
            throw IOException("Kick VOD playback returned a different recording")
        }
        if (session.opt("video_stream_status") != "vod") {
            throw IOException("Kick VOD playback is not an ended recording")
        }
        if (session.opt("video_encryption_type") != "NONE") {
            throw IOException("Kick VOD playback is encrypted or unsupported")
        }
        val url = root.optJSONObject("playback_url")?.opt("vod") as? String
            ?: throw IOException("Kick VOD playback is unavailable")
        val parsed = try {
            URI(url)
        } catch (_: URISyntaxException) {
            // Do not include signed URLs or response bodies in errors.
            throw IOException("Kick VOD playback returned an invalid URL")
        }
        if (parsed.scheme != "https" || parsed.host.isNullOrBlank() ||
            parsed.rawUserInfo != null || parsed.rawFragment != null
        ) {
            throw IOException("Kick VOD playback requires a valid HTTPS URL")
        }
        return url
    }

    private fun requireVideoId(id: String) {
        if (!videoIdPattern.matches(id)) throw IOException("Kick VOD recording ID is invalid")
    }

    private fun endTime(start: Long, duration: Long): Long? =
        if (start <= 0 || duration <= 0 || duration > Long.MAX_VALUE - start) null
        else start + duration

    private fun parseObject(json: String, operation: String): JSONObject = try {
        JSONObject(json)
    } catch (_: JSONException) {
        throw IOException("Kick $operation returned invalid JSON")
    }

    private fun requestJson(request: Request, operation: String): String {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Kick $operation request failed (HTTP ${response.code})")
            }
            return response.body?.string()
                ?: throw IOException("Kick $operation returned an empty response")
        }
    }
}
