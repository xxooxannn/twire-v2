package com.perflyst.twire.chat

import com.perflyst.twire.model.Emote
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import timber.log.Timber
import java.util.concurrent.Callable
import java.util.concurrent.CompletionService
import java.util.concurrent.ExecutorCompletionService
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future

/** Loads each provider/set independently so a failed request cannot hide other emotes. */
internal class CustomEmoteLoader(private val fetchJson: (String) -> String) {
    data class Result(val global: List<Emote>, val channel: List<Emote>)

    fun load(
        channelId: String,
        login: String,
        bttv: Boolean,
        ffz: Boolean,
        sevenTv: Boolean,
        onSourceLoaded: ((Result) -> Unit)? = null
    ): Result {
        val executor = Executors.newFixedThreadPool(6)
        val completionService = ExecutorCompletionService<IndexedSource>(executor)
        val pending = ArrayList<Future<IndexedSource>>()
        val completed = ArrayList<SourceEmotes?>()

        if (bttv) {
            completed.add(null)
            pending += submitSource(completionService, pending.size, "BTTV global") { result ->
                append(JSONArray(fetchJson("https://api.betterttv.net/3/cached/emotes/global")), result.global, ::toBTTV)
            }
            completed.add(null)
            pending += submitSource(completionService, pending.size, "BTTV channel") { result ->
                val user = JSONObject(fetchJson("https://api.betterttv.net/3/cached/users/twitch/$channelId"))
                append(user.optJSONArray("channelEmotes"), result.channel, ::toBTTV)
                append(user.optJSONArray("sharedEmotes"), result.channel, ::toBTTV)
            }
        }

        if (ffz) {
            completed.add(null)
            pending += submitSource(completionService, pending.size, "FFZ global") { result ->
                val response = JSONObject(fetchJson("https://api.frankerfacez.com/v1/set/global"))
                val sets = response.getJSONObject("sets")
                val defaults = response.getJSONArray("default_sets")
                for (i in 0 until defaults.length()) {
                    append(sets.getJSONObject(defaults.get(i).toString()).optJSONArray("emoticons"), result.global, ::toFFZ)
                }
            }
            completed.add(null)
            pending += submitSource(completionService, pending.size, "FFZ channel") { result ->
                val response = JSONObject(fetchJson("https://api.frankerfacez.com/v1/room/$login"))
                val sets = response.optJSONObject("sets")
                if (sets != null) {
                    val keys = sets.keys()
                    while (keys.hasNext()) {
                        append(sets.getJSONObject(keys.next()).optJSONArray("emoticons"), result.channel, ::toFFZ)
                    }
                }
            }
        }

        if (sevenTv) {
            completed.add(null)
            pending += submitSource(completionService, pending.size, "7TV global") { result ->
                append(fetchSevenTv("emote-sets/global") { it.getJSONArray("emotes") }, result.global, ::to7TV)
            }
            completed.add(null)
            pending += submitSource(completionService, pending.size, "7TV channel") { result ->
                val emotes = fetchSevenTv("users/twitch/$channelId") { user ->
                    // A registered user can legitimately have no active set.
                    if (!user.has("emote_set")) throw JSONException("Missing 7TV emote_set")
                    if (user.isNull("emote_set")) null else user.getJSONObject("emote_set").getJSONArray("emotes")
                }
                append(emotes, result.channel, ::to7TV)
            }
        }

        val global = ArrayList<Emote>()
        val channel = ArrayList<Emote>()
        try {
            // Publish each completed source immediately; the chat need not wait
            // for a slow provider before already-loaded emotes become usable.
            var remainingSources = pending.size
            while (remainingSources > 0) {
                remainingSources--
                try {
                    val completedSource = completionService.take().get()
                    completed[completedSource.index] = completedSource.emotes
                    val oldSize = global.size + channel.size
                    global.clear()
                    channel.clear()
                    completed.filterNotNull().forEach { source ->
                        global.addAll(source.global)
                        channel.addAll(source.channel)
                    }
                    if (global.size + channel.size != oldSize) {
                        channel.forEach { it.isCustomChannelEmote = true }
                        runCatching {
                            onSourceLoaded?.invoke(Result(global.toList(), channel.toList()))
                        }.onFailure { Timber.w(it, "Failed to publish custom emotes") }
                    }
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    pending.forEach { it.cancel(true) }
                    break
                } catch (e: ExecutionException) {
                    Timber.w(e.cause ?: e, "Custom emote source task failed")
                }
            }
        } finally {
            executor.shutdownNow()
        }

        channel.forEach { it.isCustomChannelEmote = true }
        return Result(global, channel)
    }

    private data class SourceEmotes(
        val global: MutableList<Emote> = ArrayList(),
        val channel: MutableList<Emote> = ArrayList()
    )

    private data class IndexedSource(val index: Int, val emotes: SourceEmotes)

    private fun submitSource(
        executor: CompletionService<IndexedSource>,
        index: Int,
        source: String,
        load: (SourceEmotes) -> Unit
    ): Future<IndexedSource> = executor.submit(Callable {
        val result = SourceEmotes()
        try {
            load(result)
        } catch (e: Exception) {
            Timber.w(e, "%s emote fetch failed", source)
        }
        IndexedSource(index, result)
    })

    private fun fetchSevenTv(path: String, select: (JSONObject) -> JSONArray?): JSONArray? {
        var failure: Exception? = null
        for (host in listOf("https://api.7tv.app/v3", "https://7tv.io/v3")) {
            try {
                return select(JSONObject(fetchJson("$host/$path")))
            } catch (e: Exception) {
                failure = e
                Timber.w(e, "7TV request failed: %s/%s", host, path)
            }
        }
        throw failure!!
    }

    private fun append(emotes: JSONArray?, target: MutableList<Emote>, parse: (JSONObject) -> Emote) {
        if (emotes == null) return
        for (i in 0 until emotes.length()) {
            try {
                // Deleted/unavailable emotes may have missing data; keep the rest of the set.
                target.add(parse(emotes.getJSONObject(i)))
            } catch (e: JSONException) {
                Timber.w(e, "Skipping malformed custom emote at index %s", i)
            }
        }
    }

    private fun toBTTV(emote: JSONObject): Emote = Emote.BTTV(emote.getString("code"), emote.getString("id"))

    private fun toFFZ(emote: JSONObject): Emote {
        val urls = HashMap<Int, String>()
        for (source in listOfNotNull(emote.optJSONObject("animated"), emote.optJSONObject("urls"))) {
            val keys = source.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val size = key.toIntOrNull() ?: continue
                val url = source.getString(key)
                urls[size] = if (url.startsWith("//")) "https:$url" else url
            }
            if (urls.isNotEmpty()) break
        }
        if (urls.isEmpty()) throw JSONException("FFZ emote has no image URLs")
        return Emote(emote.getString("name"), urls)
    }

    private fun to7TV(emote: JSONObject): Emote {
        val host = emote.getJSONObject("data").getJSONObject("host")
        val hostUrl = host.getString("url")
        val baseUrl = (if (hostUrl.startsWith("//")) "https:$hostUrl" else hostUrl).trimEnd('/')
        val files = host.getJSONArray("files")
        val urls = HashMap<Int, String>()
        val priorities = HashMap<Int, Int>()
        for (i in 0 until files.length()) {
            val name = files.getJSONObject(i).getString("name")
            val match = SEVEN_TV_FILE.matchEntire(name) ?: continue
            val size = match.groupValues[1].toInt()
            val priority = when (match.groupValues[2]) {
                "webp" -> 4
                "gif" -> 3
                "png" -> 2
                else -> 1
            }
            if (priority > (priorities[size] ?: 0)) {
                urls[size] = "$baseUrl/$name"
                priorities[size] = priority
            }
        }
        if (urls.isEmpty()) throw JSONException("7TV emote has no supported image URLs")
        // The set's name is the channel alias; data.name may be different.
        return Emote(emote.getString("name"), urls)
    }

    private companion object {
        val SEVEN_TV_FILE = Regex("([1-4])x\\.(webp|gif|png|avif)")
    }
}
