#!/usr/bin/env bash
# Builds rust/twire-core for every Android ABI into app/src/main/jniLibs.
#
# Called by the `buildRustCore` Gradle task before `preBuild`. It is deliberately
# tolerant: when the Rust toolchain, cargo-ndk or the NDK is missing (a plain
# checkout, or a CI job without them), it skips and the app still builds and
# runs — NativeChat.isAvailable is false and the Kotlin fallbacks take over.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CRATE="$ROOT/rust/twire-core"
OUT="$ROOT/app/src/main/jniLibs"
ABIS=(arm64-v8a armeabi-v7a x86 x86_64)

skip() {
    echo "[rust] $1 — skipping native chat core (Kotlin fallback will be used)"
    exit 0
}

command -v cargo >/dev/null 2>&1 || skip "cargo not found"
cargo ndk --version >/dev/null 2>&1 || skip "cargo-ndk not installed (cargo install cargo-ndk)"
if [ -z "${ANDROID_NDK_HOME:-}" ] && [ -z "${ANDROID_NDK_ROOT:-}" ]; then
    skip "ANDROID_NDK_HOME is not set"
fi

for target in aarch64-linux-android armv7-linux-androideabi i686-linux-android x86_64-linux-android; do
    if ! rustup target list --installed | grep -qx "$target"; then
        skip "rust target $target is not installed (rustup target add $target)"
    fi
done

mkdir -p "$OUT"
args=()
for abi in "${ABIS[@]}"; do
    args+=(-t "$abi")
done

# cargo-ndk shells out to `cargo metadata` without forwarding --manifest-path,
# so it has to run from inside the crate directory.
cd "$CRATE"
cargo ndk "${args[@]}" -o "$OUT" build --release

for abi in "${ABIS[@]}"; do
    test -f "$OUT/$abi/libtwire_core.so" || { echo "[rust] missing $OUT/$abi/libtwire_core.so"; exit 1; }
done

echo "[rust] built libtwire_core.so for: ${ABIS[*]}"
