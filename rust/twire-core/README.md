# twire-core

Native hot paths for Twire V2 chat, exposed to Kotlin over JNI as
`libtwire_core.so`. Pure CPU, no I/O, no threads, no globals except a handle
registry.

| Module | What it does |
|---|---|
| `emotes.rs` | Finds emote words in a message (`EmoteIndex::scan`) |
| `irc.rs` | Parses the IRC `emotes` tag into id + UTF-16 ranges |
| `autocomplete.rs` | Top-k substring search over the emote keywords |
| `jni_bridge.rs` | JNI entry points, handle registry, `catch_unwind` |

Offsets are UTF-16 code units so they line up with `SpannableStringBuilder`
and `String.substring` — a Rust byte offset would be wrong for any message
containing an emoji or CJK text.

The library is optional. `NativeChat.isAvailable` is false when the `.so` is
missing or fails to load, and `chat/EmoteKeyIndex.kt` +
`chat/ChatEmoteManager.kt` then use the original Kotlin implementations.

## Build for Android

```sh
cargo install cargo-ndk
rustup target add aarch64-linux-android armv7-linux-androideabi \
    i686-linux-android x86_64-linux-android
export ANDROID_NDK_HOME=/path/to/ndk
bash rust/build-android.sh     # from the repo root
```

`./gradlew assembleDebug` runs the same script through the `buildRustCore`
task and packages `libtwire_core.so` for all four ABIs (~270–420 KB each). The
script exits 0 without building when the toolchain or NDK is absent, so a plain
checkout still builds.

## Tests and benchmark

```sh
cargo test                                    # semantics + edge cases
cargo run --release --example bench_core      # pure-Rust timings
```

`bench/EmoteBench.java` is the number that matters: it compares the Rust core
against a faithful Java port of the old Kotlin code on the same JVM, including
Java-heap bytes allocated per operation.

```sh
javac -d bench/out bench/java/com/perflyst/twire/chat/NativeChat.java bench/EmoteBench.java
java -cp bench/out -Djava.library.path=target/release EmoteBench
```

Target the host for that (a plain `cargo build --release` produces
`target/release/libtwire_core.so`).
