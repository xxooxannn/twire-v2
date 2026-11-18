package com.perflyst.twire.chat

import com.perflyst.twire.model.Emote
import com.perflyst.twire.model.UserInfo
import com.perflyst.twire.service.Service
import com.perflyst.twire.service.Settings.chatEmoteBTTV
import com.perflyst.twire.service.Settings.chatEmoteFFZ
import com.perflyst.twire.service.Settings.chatEmoteSEVENTV
import okhttp3.Request
import timber.log.Timber
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Created by sebastian on 26/07/2017.
 */
internal class ChatEmoteManager(private val channel: UserInfo) {
    val globalCustomEmotes: MutableList<Emote> = ArrayList()
    val channelCustomEmotes: MutableList<Emote> = ArrayList()

    private val emotePattern: Pattern = Pattern.compile("(\\w+):((?:\\d+-\\d+,?)+)")

    // Per-instance, not static: the old static map was shared by every
    // ChatManager, so switching channels mid-load let stale emotes from the
    // previous channel leak into the new one (channel 2 could render channel
    // 1's BTTV emotes), and reconnects re-populated an already-populated map.
    //
    // Backed by the Rust core when available (see EmoteKeyIndex): the keyword
    // table is built once per load instead of being walked with a boxed
    // HashMap for every incoming message.
    private var emoteKeywordToEmote: MutableMap<String, Emote> = HashMap()
    private var customEmoteIndex: EmoteKeyIndex = EmoteKeyIndex.EMPTY

    /**
     * Twitch emote objects are immutable per emote id, and the same handful of
     * emotes appear in almost every message. Reusing them keeps the renderer
     * from allocating a new Emote + URL array for every occurrence.
     */
    private val twitchEmoteCache = HashMap<String, Emote>()


    /**
     * Connects to custom emote APIs.
     * Fetches and maps the emote keywords and id's
     * This must not be called on main UI thread
     */
    fun loadCustomEmotes(callback: EmoteFetchCallback) {
        val result = CustomEmoteLoader(::fetchEmoteJson).load(
            channel.userId, channel.login, chatEmoteBTTV, chatEmoteFFZ, chatEmoteSEVENTV
        )
        globalCustomEmotes.clear()
        channelCustomEmotes.clear()
        globalCustomEmotes.addAll(result.global)
        channelCustomEmotes.addAll(result.channel)
        emoteKeywordToEmote = HashMap()
        // Channel aliases take precedence over global keywords.
        for (emote in result.global + result.channel) {
            emoteKeywordToEmote[emote.keyword] = emote
        }

        customEmoteIndex.close()
        customEmoteIndex = EmoteKeyIndex.of(emoteKeywordToEmote.values.toList())

        try {
            callback.onEmoteFetched()
        } catch (e: Exception) {
            Timber.e(e)
        }
    }

    private fun fetchEmoteJson(url: String): String {
        // Third-party APIs expect JSON, not Twitch's legacy v5 Accept/Client-ID
        // headers. Large 7TV sets also need more than Service's 5s timeout.
        val request = Request.Builder().url(url).header("Accept", "application/json").build()
        val client = if (url.startsWith("https://api.7tv.app/") || url.startsWith("https://7tv.io/")) {
            sevenTvClient
        } else {
            Service.client
        }
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Emote request returned HTTP ${response.code}: $url")
            return response.body?.string().orEmpty()
        }
    }

    /**
     * Finds and creates custom emotes in a message and returns them.
     *
     * @param message The message to find emotes in
     * @return The List of emotes in the message
     */
    fun findCustomEmotes(message: String): Map<Int, Emote> {
        return customEmoteIndex.findInMessage(message)
    }

    /** Releases the native keyword index; chat is done with this channel. */
    fun close() {
        customEmoteIndex.close()
        customEmoteIndex = EmoteKeyIndex.EMPTY
        twitchEmoteCache.clear()
    }

    /**
     * Finds and creates Twitch emotes in an unsplit irc line.
     *
     * @param line The line to find emotes in
     * @return The list of emotes from the line
     */
    fun findTwitchEmotes(line: String?, message: String): MutableMap<Int, Emote> {
        if (line == null || line.isEmpty()) return mutableMapOf()
        if (NativeChat.isAvailable) return findTwitchEmotesNative(line, message)

        val emotes: MutableMap<Int, Emote> = HashMap()
        val emoteMatcher = emotePattern.matcher(line)

        while (emoteMatcher.find()) {
            val emoteId = emoteMatcher.group(1)
            val stringPositions =
                emoteMatcher.group(2)!!.split(",".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()
            val positions = IntArray(stringPositions.size)
            var keyword = ""
            for (i in stringPositions.indices) {
                val stringPosition = stringPositions[i]
                val range = stringPosition.split("-".toRegex()).dropLastWhile { it.isEmpty() }
                    .toTypedArray()
                val start = range[0].toInt()

                positions[i] = start

                if (i == 0) {
                    val end = range[1].toInt()
                    keyword = message.substring(start, end + 1)
                }
            }

            for (position in positions) {
                emotes[position] = Emote.Twitch(keyword, emoteId!!)
            }
        }

        return emotes
    }

    /**
     * Same result as the regex path above, parsed by the Rust core: the tag is
     * walked once and returned as packed `[idStart, idLength, rangeCount,
     * start, end, ...]` groups. No `Regex`, no `split` arrays.
     */
    private fun findTwitchEmotesNative(tag: String, message: String): MutableMap<Int, Emote> {
        val packed = NativeChat.nativeParseEmoteTag(tag)
        if (packed.isEmpty()) return mutableMapOf()

        val emotes: MutableMap<Int, Emote> = HashMap()
        var cursor = 0
        while (cursor < packed.size) {
            val idStart = packed[cursor]
            val idLength = packed[cursor + 1]
            val rangeCount = packed[cursor + 2]
            cursor += 3
            if (idLength <= 0 || idStart < 0 || idStart + idLength > tag.length ||
                rangeCount <= 0 || cursor + rangeCount * 2 > packed.size
            ) {
                continue
            }

            val rangeBase = cursor
            cursor += rangeCount * 2
            val firstStart = packed[rangeBase]
            val firstEnd = packed[rangeBase + 1]
            if (firstStart < 0 || firstEnd < firstStart || firstEnd >= message.length) {
                // The Kotlin path would have thrown on substring() here; skip
                // instead so a malformed tag can never kill the chat thread.
                continue
            }

            val emote = twitchEmote(
                tag.substring(idStart, idStart + idLength),
                message.substring(firstStart, firstEnd + 1)
            )
            for (i in 0 until rangeCount) {
                val start = packed[rangeBase + i * 2]
                if (start >= 0) emotes[start] = emote
            }
        }
        return emotes
    }

    private fun twitchEmote(id: String, keyword: String): Emote {
        twitchEmoteCache[id]?.let { return it }
        val emote = Emote.Twitch(keyword, id)
        if (twitchEmoteCache.size >= 1024) twitchEmoteCache.clear()
        twitchEmoteCache[id] = emote
        return emote
    }

    private companion object {
        val sevenTvClient = Service.client.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .callTimeout(75, TimeUnit.SECONDS)
            .build()
    }

    fun interface EmoteFetchCallback {
        fun onEmoteFetched()
    }
}
