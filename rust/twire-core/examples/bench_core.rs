//! Pure-Rust timing for the three hot paths, without any JNI overhead.
//! `cargo run --release --example bench_core`

use std::time::Instant;

use twire_core::{parse_emote_tag, EmoteIndex};

fn main() {
    let keywords: Vec<String> = (0..10_000)
        .map(|i| {
            if i < 200 {
                format!("Kappa{}", i / 20)
            } else {
                format!("emote{}", i)
            }
        })
        .collect();
    let index = EmoteIndex::new(keywords.clone());

    let messages: Vec<String> = (0..20_000)
        .map(|i| {
            let mut s = String::new();
            for w in 0..(6 + i % 14) {
                if w > 0 {
                    s.push(' ');
                }
                if w % 4 == 0 {
                    s.push_str(&keywords[(i * 7 + w) % 200]);
                } else {
                    s.push_str(["nice", "that", "was", "so", "funny", "gg", "chat"][(i + w) % 7]);
                }
            }
            s
        })
        .collect();

    let tags: Vec<String> = (0..2000)
        .map(|i| format!("{}:0-3,5-8/{}:10-14", 25 + i % 900, 1900 + i % 90))
        .collect();

    const ROUNDS: u32 = 50;

    let mut bytes = 0usize;
    let start = Instant::now();
    for _ in 0..ROUNDS {
        for m in &messages {
            bytes += index.scan(m).len();
        }
    }
    let per = start.elapsed().as_nanos() as f64 / (ROUNDS as f64 * messages.len() as f64);
    println!("scan        {:8.0} ns/message  ({} hits)", per, bytes);

    let mut groups = 0usize;
    let start = Instant::now();
    for _ in 0..ROUNDS {
        for t in &tags {
            groups += parse_emote_tag(t).len();
        }
    }
    let per = start.elapsed().as_nanos() as f64 / (ROUNDS as f64 * tags.len() as f64);
    println!("irc tag     {:8.0} ns/tag      ({} groups)", per, groups);

    for q in ["ka", "lol", "pe", "emote1", "zzz", "a"] {
        let start = Instant::now();
        let mut found = 0usize;
        for _ in 0..ROUNDS {
            found += index.search(q, 10).len();
        }
        let per = start.elapsed().as_nanos() as f64 / ROUNDS as f64;
        println!("search {:6} {:8.0} ns/query   ({} results)", q, per, found / ROUNDS as usize);
    }
}
