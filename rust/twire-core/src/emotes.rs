//! Word-level emote scanning for chat messages.

/// One emote occurrence in a message.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EmoteHit {
    /// Start of the word in UTF-16 code units (Java `String` index space).
    pub start: u32,
    /// Length of the word in UTF-16 code units.
    pub len: u32,
    /// Index of the matched keyword in the caller's keyword list.
    pub keyword: u32,
}

#[inline]
fn utf16_len(word: &str, ascii: bool) -> usize {
    if ascii {
        word.len()
    } else {
        word.encode_utf16().count()
    }
}

/// Finds every space-separated word of `message` that `lookup` resolves, in
/// message order.
///
/// This is a drop-in replacement for Kotlin's
/// `message.split(" ".toRegex()).dropLastWhile { it.isEmpty() }` loop: only
/// single spaces split words, so runs of spaces produce empty words that still
/// advance the offset by one — which keeps the returned offsets identical to
/// `position += word.length + 1` in the Kotlin version.
pub fn scan_emotes<F>(message: &str, mut lookup: F) -> Vec<EmoteHit>
where
    F: FnMut(&str) -> Option<u32>,
{
    // Most chat messages are ASCII; skipping the UTF-16 walk keeps the common
    // case a pure byte loop.
    let ascii = message.is_ascii();
    let mut hits = Vec::new();
    let mut position: u32 = 0;

    for word in message.split(' ') {
        let len = utf16_len(word, ascii);
        // A run of spaces yields empty words; no emote has an empty keyword, so
        // the lookup is skipped while the offset still advances by one below.
        if !word.is_empty() {
            if let Some(keyword) = lookup(word) {
                hits.push(EmoteHit {
                    start: position,
                    len: len as u32,
                    keyword,
                });
            }
        }
        position = position.saturating_add(len as u32 + 1);
    }

    hits
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn empty_message_has_no_hits() {
        let hits = scan_emotes("", |_| Some(0));
        assert!(hits.is_empty());
    }

    #[test]
    fn every_word_is_looked_up() {
        let mut seen = Vec::new();
        scan_emotes("a bb ccc", |w| {
            seen.push(w.to_string());
            None
        });
        assert_eq!(seen, ["a", "bb", "ccc"]);
    }

    #[test]
    fn offsets_follow_single_space_splitting() {
        let hits = scan_emotes("Lead  mid end", |w| if w == "end" { Some(9) } else { None });
        assert_eq!(hits.len(), 1);
        assert_eq!(hits[0].start, 10);
    }
}
