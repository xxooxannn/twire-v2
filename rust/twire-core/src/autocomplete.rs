//! Emote autocomplete search.
//!
//! Replaces `.filter { it.first.contains(query) }.sortedBy { it.first.indexOf(query) }.take(10)`
//! over the merged emote list, which runs on every keystroke and allocated a
//! filtered list plus a decorated sort over ~5-10k entries each time.

use std::collections::BinaryHeap;

/// Byte search tuned for many short haystacks.
///
/// `str::find` sets up a two-way searcher per call; across ~10k keywords of a
/// dozen bytes that setup dominated the whole search. A naive byte scan over
/// such short strings is faster, and a single-byte needle (what happens on the
/// first keystroke) degenerates to one linear scan.
#[inline]
fn find_position(hay: &str, needle: &str) -> Option<usize> {
    let hay = hay.as_bytes();
    let needle = needle.as_bytes();
    if needle.is_empty() || needle.len() > hay.len() {
        return None;
    }
    if needle.len() == 1 {
        return hay.iter().position(|&b| b == needle[0]);
    }
    let first = needle[0];
    let last = hay.len() - needle.len();
    let mut i = 0;
    while i <= last {
        if hay[i] == first && &hay[i..i + needle.len()] == needle {
            return Some(i);
        }
        i += 1;
    }
    None
}

/// Indices into `lower` of keywords containing `query`, ordered by the first
/// occurrence of `query` and then by list order (stable, like `sortedBy`).
///
/// `lower` must already be lowercased and in emote-list order. `query` is
/// lowercased here so callers can pass the raw trailing word.
pub fn search(lower: &[String], query: &str, limit: usize) -> Vec<u32> {
    if limit == 0 || query.is_empty() || lower.is_empty() {
        return Vec::new();
    }

    let query = query.to_lowercase();
    let mut best: BinaryHeap<(u32, u32)> = BinaryHeap::with_capacity(limit + 1);

    for (index, keyword) in lower.iter().enumerate() {
        let Some(position) = find_position(keyword, &query) else {
            continue;
        };
        // Kotlin's `indexOf` counts UTF-16 units; `str::find` counts bytes.
        // They only diverge for non-ASCII keywords, so pay for the recount
        // there and keep ASCII (the overwhelming majority) a byte compare.
        let position = if keyword.is_ascii() {
            position
        } else {
            keyword[..position].encode_utf16().count()
        };
        let candidate = (position as u32, index as u32);
        if best.len() < limit {
            best.push(candidate);
        } else if let Some(worst) = best.peek() {
            // Max-heap: keep the smallest (position, index) pairs.
            if candidate < *worst {
                best.pop();
                best.push(candidate);
            }
        }
    }

    let mut ranked = best.into_vec();
    ranked.sort_unstable();
    ranked.into_iter().map(|(_, index)| index).collect()
}


#[cfg(test)]
mod tests {
    use super::*;

    fn idx(words: &[&str]) -> Vec<String> {
        words.iter().map(|w| w.to_lowercase()).collect()
    }

    #[test]
    fn ranks_by_first_occurrence() {
        let lower = idx(&["xpepe", "pepe", "pepeclap"]);
        assert_eq!(search(&lower, "pepe", 10), vec![1, 2, 0]);
    }

    #[test]
    fn stable_for_equal_positions() {
        let lower = idx(&["aaa", "aab", "aac"]);
        assert_eq!(search(&lower, "aa", 10), vec![0, 1, 2]);
    }

    #[test]
    fn limit_and_case_insensitivity() {
        let lower = idx(&["Kappa", "KAPPACLAP", "kappahd"]);
        assert_eq!(search(&lower, "KAPPA", 2), vec![0, 1]);
    }

    #[test]
    fn no_match_and_empty_query() {
        let lower = idx(&["a", "b"]);
        assert!(search(&lower, "zzz", 10).is_empty());
        assert!(search(&lower, "", 10).is_empty());
        assert!(search(&lower, "a", 0).is_empty());
    }
}
