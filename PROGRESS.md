# Twire V2 — Build Log

> A Twire fork with Frosty-grade chat. Twire's native ExoPlayer video engine kept,
> chat rebuilt toward Frosty parity. Android only.

- Upstream: https://github.com/twireapp/Twire
- Inspiration: https://github.com/tommyxchow/frosty
- This repo: https://github.com/xxooxannn/twire-v2
- App ID: `com.perflyst.twire.v2` (debug builds: `+ .debug`, install side-by-side with stock Twire)

## Why fork Twire, not Frosty

| | Twire (native Android + Media3 ExoPlayer) | Frosty (Flutter + Twitch web player in WebView) |
|---|---|---|
| Video | Direct HLS via Usher, full Source/1080p60/1440p, LL-HLS, background playback, PiP | Stuck on Auto, broken quality picker (#448), freezes on quality switch (#264) |
| Adblock | HLS proxy support built in (`$proxy/playlist/...`) | None |
| Chat | Dated: 7TV off by default, text-only autocomplete, login-gated input | Excellent: GIF emotes, image autocomplete, tap-to-inspect, history |

Verdict: keep Twire's video, port Frosty's chat. Rebuilding native video in
Flutter is unbounded work; fixing chat in Kotlin is bounded work.

## Done so far

### Video & adblock (kept, tuned)
- Media3 ExoPlayer kept (`PlayerFragment` + `PlaybackService`): true quality
  selection, background playback, PiP, VOD resume, VOD chat replay.
- `app/build.gradle.kts`: Media3 `1.3.0` → `1.4.1` (conservative bump: HLS +
  buffering fixes, same public API; CI proved it compiles).
- Proxy default `""` → `https://lb-as.cdn-perfprod.com` (Asia = closest to Nepal;
  fallback `https://as.luminous.dev`). Change anytime in Settings → Stream Player → Proxy.

### Chat overhauls
- **7TV on by default** (`Settings.chatEmoteSEVENTV false → true`) — was hiding
  most emotes on modern channels.
- **7TV parser fix** (`ChatEmoteManager.To7TV`): was webp-only, now accepts
  webp/gif/avif 1x–4x, prefers animated.
- **FFZ animated fix** (`ToFFZ`): old code ignored the `animated` object entirely;
  now prefers animated renditions like Frosty.
- **Tap any message → inspect** (`ChatAdapter` passes full `ChatMessage`;
  `ChatFragment` bottom sheet): chatter name (readable color, see theming),
  full message, **emote breakdown with image + keyword** (tap to paste into
  input — fastest way to learn new 7TV/BTTV/FFZ names), recent messages from
  that chatter, mention / duplicate actions.
- **Autocomplete with images** (`MentionAdapter` + `SuggestionItem`): `:pepe`
  shows matching emotes with previews, `@` mentions users; tap inserts correctly
  (old code ran @-logic for emote taps — broken).
- **Anon chat read**: input no longer hidden when logged out; reading, typing and
  autocomplete work for everyone, only *sending* needs login (+ live stream).
- **Chatter list**: 👥 icon in chat status bar → dialog of everyone seen talking,
  tap to mention. Zero network: the old `tmi.twitch.tv` endpoint is dead and
  Helix needs mod auth, so it's built from messages in memory.
- **Chat history on join**: same `recent-messages.robotty.de` source Frosty uses,
  last 50 messages backfilled as raw IRC (badges/color/emotes intact), best-effort.
  Connects first, fetches second — a slow archive can't delay chat anymore; the
  backfill is skipped if live messages already arrived (no out-of-order lines).
- **Deletes/timeouts fade** with a "message deleted" label instead of vanishing.
  Timeouts/bans (previously entirely unhandled — twitch4j delivers them as
  `UserTimeoutEvent`/`UserBanEvent`, not `ClearChatEvent`) now fade that user's
  messages too.
- **Live + history render the same name**: live messages now use the
  `display-name` tag (was lowercase login), so "Cyr" isn't "cyr" at the
  history/live boundary.
- **Tap-sheet actually taps on first tap**: removed `textIsSelectable` (it stole
  the first tap for text focus — upstream #383).

### Full audit round (bugs + perf)
- **Autocomplete now triggers mid-sentence**: the old full-string regex only
  matched when `@…`/`:…` was the *entire* input; now the last trigger word
  anywhere fires suggestions (`lol :ke` works). Greedy-prefix regex with an
  explicit `[@:]` class — a naive `.*(.)(\S+)$` backtracks g1 onto the wrong
  character, so the trigger is matched explicitly.
- **`:` autocomplete cost**: merged/deduped emote list (~5-10k emotes) was
  rebuilt on every keystroke; now cached, rebuilt only when an emote source
  loads. Mention autocomplete compiles one quoted regex per query instead of
  150 per keystroke (also removes a `PatternSyntaxException` crash path).
- **Sheet leak**: `onDestroyView` dismisses the tap sheet (rotation used to
  leak the Activity context).
- **Buffer trims** use one `notifyItemRangeRemoved` per burst, not per message.
- **Dead code**: `GetStreamChattersTask` + never-started poller in
  `PlayerFragment` deleted (endpoint died upstream long ago).

### Low-end / stability work (tested on Celeron laptop + Redmi, Android 16)
- Chat buffer stays at 150, autocomplete capped at 10, history capped at 50.
- No new background polling/services; dialogs and history are on-demand only.
- `ChatRecyclerView`: only real drag/fling pauses chat — animated-emote
  re-layouts can no longer trip the "chat paused" banner; reaching bottom always
  resumes; auto-scroll follows pause intent; buffer trims even while paused.
- `StartUpActivity`: `adjustResize` so the keyboard no longer paints over chat input.
- Crash fixes from on-device logs (`adb shell dumpsys dropbox`):
  - backspace-to-empty crash (`lateinit defaultBackgroundColor` →
    nullable + self-heal),
  - rotation + typing crash (mention views always rebound in `LiveStreamFragment`),
  - pre-connect send race (`::chatManager.isInitialized` guard),
  - dead-connection send NPE (`ChatManager.sendMessage` null-guard + try/catch —
    same crash class stock Twire hits).
- True Night fixes: all new chat UI uses theme colors (was black-on-black);
  pitch-dark username colors fall back to readable theme text.

### Repo / branding / CI
- Renamed Chautari → **Twire V2** (`com.perflyst.twire.v2`, launcher + setup +
  router strings, `TwireV2-*.apk`, version `2.0.0`).
- Upstream remote kept as `upstream`; origin is this repo.
- `.github/workflows/nightly.yml` → `.disabled` (needs Twire's private signing
  keys). **`ci.yml` is the build**: push to `master` → cloud `assembleDebug` →
  APK in the run's Artifacts. Laptop never builds.

## How to install

1. Phone browser → this repo → **Actions** → latest green **CI** run → **Artifacts** → download `app`.
2. Uninstall any previous Twire V2 debug first (CI debug keys rotate per build;
   Android rejects reinstalls on signature mismatch).
3. Install the APK (allow unknown apps once).

Or via USB: `adb install TwireV2-*.apk`.

## Known issues / next up

- Crash debugging = `adb shell "dumpsys dropbox --print data_app_crash"`.
- Deliberately deferred (battery/complexity): zero-width overlay emotes, 7TV
  live-update websocket, reply threads, sub/raid notices, mod tools (needs OAuth
  scopes flow), Media3 beyond 1.4.1.
- `GetStreamChattersTask` is a dead stub (upstream `tmi.twitch.tv` endpoint gone);
  chatter list is memory-based until Helix chatters API + auth is wired.
