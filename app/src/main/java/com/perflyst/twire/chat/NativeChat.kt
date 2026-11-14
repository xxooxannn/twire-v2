package com.perflyst.twire.chat

/**
 * JNI bridge to the Rust chat core in `rust/twire-core`.
 *
 * The library is optional on purpose: a build without the NDK/Rust toolchain
 * (or a device whose ABI we did not ship) still runs, because callers fall back
 * to the Kotlin implementations in [EmoteKeyIndex] and [ChatEmoteManager].
 */
internal object NativeChat {
    /** False when `libtwire_core.so` is not packaged or fails to load. */
    val isAvailable: Boolean = try {
        System.loadLibrary("twire_core")
        true
    } catch (_: Throwable) {
        false
    }

    /** Builds an index from the emote-list order. Returns 0 on failure. */
    @JvmStatic
    external fun nativeNewEmoteIndex(keywords: Array<String>): Long

    @JvmStatic
    external fun nativeFreeEmoteIndex(handle: Long)

    /**
     * Packed emote occurrences `[utf16Start, utf16Length, keywordIndex]`,
     * in message order. See `EmoteHit` in the Rust crate.
     */
    @JvmStatic
    external fun nativeScanEmotes(handle: Long, message: String): IntArray

    /** Emote indices whose keyword contains `query`, best match first. */
    @JvmStatic
    external fun nativeSearchEmotes(handle: Long, query: String, limit: Int): IntArray

    /**
     * Packed `emotes` tag groups:
     * `[idByteStart, idByteLength, rangeCount, start, end, ...]`.
     */
    @JvmStatic
    external fun nativeParseEmoteTag(tag: String): IntArray
}
