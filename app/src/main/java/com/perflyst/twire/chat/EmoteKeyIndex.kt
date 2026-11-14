package com.perflyst.twire.chat

import com.perflyst.twire.model.Emote
import java.util.Locale

/**
 * Keyword lookup + autocomplete over one emote set.
 *
 * Both operations are resolved by the Rust core when `libtwire_core.so` is
 * available; otherwise the Kotlin implementations below produce the same
 * results. The native path avoids the per-message `Regex`, the `split` arrays
 * and the boxed `HashMap<Int, Emote>` that made this the hottest allocation
 * site in chat.
 *
 * Instances are cheap to look at but hold a native index; call [close] when
 * replacing one (rebuilds are rare — they happen when an emote source loads).
 */
internal class EmoteKeyIndex private constructor(
    private val emotes: List<Emote>,
    private val byKeyword: Map<String, Emote>,
    // Volatile: the index is closed from the UI thread while the chat thread may
    // still be parsing a message; a stale non-zero read can only produce an
    // empty result, never a use-after-free (the native side rejects bad handles).
    @Volatile
    private var handle: Long
) {
    /**
     * Emotes found in [message], keyed by UTF-16 start offset — the shape
     * `ChatAdapter` renders.
     */
    fun findInMessage(message: String): Map<Int, Emote> {
        if (handle != 0L) {
            val packed = NativeChat.nativeScanEmotes(handle, message)
            if (packed.isEmpty()) return emptyMap()
            val found = HashMap<Int, Emote>(packed.size / 3)
            var i = 0
            while (i < packed.size) {
                found[packed[i]] = emotes[packed[i + 2]]
                i += 3
            }
            return found
        }

        // Fallback: identical semantics to the old
        // ChatMessage.getEmotesFromMessage — split on single spaces, offsets
        // advance by word length + 1 (so runs of spaces stay aligned).
        var position = 0
        var found: MutableMap<Int, Emote>? = null
        for (word in message.split(" ".toRegex()).dropLastWhile { it.isEmpty() }) {
            val emote = byKeyword[word]
            if (emote != null) {
                if (found == null) found = HashMap()
                found[position] = emote
            }
            position += word.length + 1
        }
        return found ?: emptyMap()
    }

    /**
     * Emotes whose keyword contains [lowerQuery], ordered by the first
     * occurrence of the query, ties in emote-list order.
     */
    fun autocomplete(lowerQuery: String, limit: Int): List<Emote> {
        if (lowerQuery.isEmpty() || limit <= 0) return emptyList()

        if (handle != 0L) {
            val indices = NativeChat.nativeSearchEmotes(handle, lowerQuery, limit)
            if (indices.isEmpty()) return emptyList()
            return indices.map { emotes[it] }
        }

        val matches = ArrayList<Emote>(limit * 2)
        for (emote in emotes) {
            if (emote.keyword.lowercase(Locale.getDefault()).contains(lowerQuery)) {
                matches.add(emote)
            }
        }
        matches.sortBy { it.keyword.lowercase(Locale.getDefault()).indexOf(lowerQuery) }
        return if (matches.size > limit) matches.subList(0, limit) else matches
    }

    fun close() {
        if (handle != 0L) {
            NativeChat.nativeFreeEmoteIndex(handle)
            handle = 0L
        }
    }

    companion object {
        val EMPTY = EmoteKeyIndex(emptyList(), emptyMap(), 0L)

        fun of(emotes: List<Emote>): EmoteKeyIndex {
            if (emotes.isEmpty()) return EMPTY

            var handle = 0L
            if (NativeChat.isAvailable) {
                handle = NativeChat.nativeNewEmoteIndex(Array(emotes.size) { emotes[it].keyword })
            }

            // The Kotlin keyword map is only the fallback. Skipping it when the
            // native index exists removes a ~10k-entry HashMap (and its entry
            // objects) from every emote set — the old code always held it.
            val byKeyword = if (handle != 0L) {
                emptyMap()
            } else {
                HashMap<String, Emote>(emotes.size * 2).apply {
                    for (emote in emotes) put(emote.keyword, emote)
                }
            }
            return EmoteKeyIndex(emotes, byKeyword, handle)
        }
    }
}
