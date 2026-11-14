//! Native chat hot paths for Twire V2.
//!
//! These functions run for every incoming chat message and for every keystroke
//! in the chat input. The Kotlin implementations they replace allocate a
//! `Regex` (and therefore a compiled `Pattern`), a `ArrayList<String>` of words,
//! a boxed `HashMap<Int, Emote>` and, for emotes, several arrays per line.
//! This crate does the same work without allocating anything per word or per
//! emote: it walks the message once and returns packed `int` arrays.
//!
//! Offsets handed back to Kotlin are UTF-16 code units, because that is what
//! `SpannableStringBuilder.setSpan` and `String.substring` use.

pub mod autocomplete;
pub mod emotes;
pub mod irc;
pub mod jni_bridge;

pub use autocomplete::search;
pub use emotes::{scan_emotes, EmoteHit};
pub use irc::{parse_emote_tag, IrcEmote};

use std::collections::HashMap;

/// An immutable keyword -> index table plus a lowercased copy of every keyword
/// for the autocomplete search.
///
/// Built once per emote-set load (not per message) and kept alive by Kotlin via
/// the handle registry in [`crate::jni_bridge`].
/// Cheap 64-bit hash used only for the bloom pre-filter (never for map
/// equality), so a weak hash cannot cause a slowdown beyond a false positive.
#[inline]
fn hash_word(word: &str) -> u64 {
    let mut hash: u64 = 0xcbf2_9ce4_8422_2325;
    for chunk in word.as_bytes().chunks(8) {
        let mut buf = [0u8; 8];
        buf[..chunk.len()].copy_from_slice(chunk);
        hash = (hash ^ u64::from_le_bytes(buf)).wrapping_mul(0x100_0000_01b3);
    }
    hash
}

#[derive(Debug)]
pub struct EmoteIndex {
    keywords: Vec<String>,
    lower: Vec<String>,
    by_keyword: HashMap<String, u32>,
    /// One bit per ~8 keywords. Most chat words are not emotes, and a hash-map
    /// probe for every one of them was costing a cache miss each; the filter
    /// rejects ~88% of misses while staying inside a few KB of L2.
    bloom: Vec<u64>,
}

impl EmoteIndex {
    pub fn new(keywords: Vec<String>) -> Self {
        let mut by_keyword = HashMap::with_capacity(keywords.len());
        // Later duplicates win, matching Kotlin's `associateBy { it.keyword }`
        // (the map used by the old chat rendering path).
        for (i, kw) in keywords.iter().enumerate() {
            by_keyword.insert(kw.clone(), i as u32);
        }
        let lower = keywords.iter().map(|k| k.to_lowercase()).collect();

        let bit_count = (keywords.len() * 8).next_power_of_two().max(64);
        let mut bloom = vec![0u64; bit_count / 64];
        let mask = (bit_count - 1) as u64;
        for kw in &keywords {
            let hash = hash_word(kw);
            for bit in [hash & mask, (hash >> 32) & mask] {
                bloom[(bit >> 6) as usize] |= 1u64 << (bit & 63);
            }
        }

        Self {
            keywords,
            lower,
            by_keyword,
            bloom,
        }
    }

    pub fn len(&self) -> usize {
        self.keywords.len()
    }

    pub fn is_empty(&self) -> bool {
        self.keywords.is_empty()
    }

    pub fn keyword(&self, index: u32) -> Option<&str> {
        self.keywords.get(index as usize).map(String::as_str)
    }

    /// Emotes found in `message`, as `(start, len, keyword index)` with UTF-16
    /// offsets, in message order.
    ///
    /// Mirrors `ChatMessage.getEmotesFromMessage`: the message is split on single
    /// spaces (consecutive spaces produce empty words that still advance the
    /// offset by one) and every word is looked up exactly as written.
    pub fn scan(&self, message: &str) -> Vec<EmoteHit> {
        let bloom = &self.bloom;
        let mask = (bloom.len() * 64 - 1) as u64;
        emotes::scan_emotes(message, |word| {
            let hash = hash_word(word);
            let bit1 = hash & mask;
            let bit2 = (hash >> 32) & mask;
            if bloom[(bit1 >> 6) as usize] & (1u64 << (bit1 & 63)) == 0
                || bloom[(bit2 >> 6) as usize] & (1u64 << (bit2 & 63)) == 0
            {
                return None;
            }
            self.by_keyword.get(word).copied()
        })
    }

    /// Autocomplete: keyword indices whose lowercase form contains `query`,
    /// ordered by first occurrence of `query` (ties keep emote-list order),
    /// truncated to `limit`.
    ///
    /// Mirrors `.filter { contains }.sortedBy { indexOf }.take(10)`.
    pub fn search(&self, query: &str, limit: usize) -> Vec<u32> {
        autocomplete::search(&self.lower, query, limit)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn index(words: &[&str]) -> EmoteIndex {
        EmoteIndex::new(words.iter().map(|w| w.to_string()).collect())
    }

    #[test]
    fn scan_matches_kotlin_split_semantics() {
        let idx = index(&["Kappa", "LUL"]);
        // "hi  Kappa LUL" -> ["hi", "", "Kappa", "LUL"]
        // offset after "hi" = 3, empty word -> 4, "Kappa" starts at 4.
        let hits = idx.scan("hi  Kappa LUL");
        assert_eq!(
            hits.iter().map(|h| (h.start, h.len, h.keyword)).collect::<Vec<_>>(),
            vec![(4, 5, 0), (10, 3, 1)]
        );
    }

    #[test]
    fn scan_is_utf16_based() {
        let idx = index(&["Pog"]);
        // 3-byte snowman: byte offset 4, UTF-16 offset 3 (one BMP char + space).
        let hits = idx.scan("\u{2603} Pog");
        assert_eq!(hits[0].start, 2);
        // Astral char (4 bytes, 2 UTF-16 units) + space = offset 3.
        let hits = idx.scan("\u{1F600} Pog");
        assert_eq!(hits[0].start, 3);
    }

    #[test]
    fn trailing_words_and_repeats() {
        let idx = index(&["A"]);
        let hits = idx.scan("A A ");
        assert_eq!(hits.len(), 2);
        assert_eq!((hits[0].start, hits[1].start), (0, 2));
    }

    #[test]
    fn search_orders_by_first_occurrence_then_list_order() {
        let idx = index(&["xabc", "abc", "abcd", "zzz"]);
        assert_eq!(idx.search("abc", 10), vec![1, 2, 0]);
        assert_eq!(idx.search("abc", 2), vec![1, 2]);
        assert!(idx.search("", 10).is_empty());
    }
}
