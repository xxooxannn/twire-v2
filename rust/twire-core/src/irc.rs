//! Parsing of the Twitch IRC `emotes` tag.
//!
//! Tag layout: `25:0-4,6-10/1902:12-16` — one `id:ranges` group per emote,
//! groups separated by `/`, ranges by `,`, each range `start-end` inclusive.

/// One emote group from the tag.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct IrcEmote {
    /// Byte offset of the emote id inside the tag string.
    pub id_start: u32,
    /// Length of the emote id in bytes.
    pub id_len: u32,
    /// Inclusive `(start, end)` ranges, in message index space.
    pub ranges: Vec<(u32, u32)>,
}

#[inline]
fn is_word_byte(b: u8) -> bool {
    b.is_ascii_alphanumeric() || b == b'_'
}

/// Parses an `emotes` tag value into emote groups.
///
/// Malformed groups (empty id, non-word id, unparsable range) are skipped
/// instead of throwing, which is what the Kotlin regex +
/// `split(",".toRegex())` path effectively did for well-formed Twitch data.
pub fn parse_emote_tag(tag: &str) -> Vec<IrcEmote> {
    let mut out = Vec::new();
    let mut base: u32 = 0;

    for segment in tag.split('/') {
        let segment_len = segment.len() as u32;
        if let Some(colon) = segment.find(':') {
            let (id, rest) = segment.split_at(colon);
            if !id.is_empty() && id.bytes().all(is_word_byte) {
                let mut ranges = Vec::with_capacity(2);
                for part in rest[1..].split(',') {
                    if let Some(dash) = part.find('-') {
                        let (a, b) = part.split_at(dash);
                        if let (Ok(start), Ok(end)) =
                            (a.parse::<u32>(), b[1..].parse::<u32>())
                        {
                            ranges.push((start, end));
                        }
                    }
                }
                if !ranges.is_empty() {
                    out.push(IrcEmote {
                        id_start: base,
                        id_len: id.len() as u32,
                        ranges,
                    });
                }
            }
        }
        // `+ 1` for the '/' that `split` consumed.
        base += segment_len + 1;
    }

    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_multiple_groups_and_ranges() {
        let groups = parse_emote_tag("25:0-4,6-10/1902:12-16");
        assert_eq!(groups.len(), 2);
        assert_eq!(groups[0].id_start, 0);
        assert_eq!(groups[0].id_len, 2);
        assert_eq!(groups[0].ranges, [(0, 4), (6, 10)]);
        assert_eq!(groups[1].id_start, 12);
        assert_eq!(groups[1].id_len, 4);
        assert_eq!(groups[1].ranges, [(12, 16)]);
    }

    #[test]
    fn ignores_garbage() {
        assert!(parse_emote_tag("").is_empty());
        assert!(parse_emote_tag("nonsense").is_empty());
        assert!(parse_emote_tag("25:").is_empty());
        assert!(parse_emote_tag("25:x-y").is_empty());
        // Trailing separator must not eat the next id.
        let groups = parse_emote_tag("1:0-1//2:3-4/");
        assert_eq!(groups.len(), 2);
        assert_eq!(groups[1].id_start, 7);
    }
}
