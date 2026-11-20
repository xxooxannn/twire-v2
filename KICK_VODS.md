# xQc Kick VOD video with Twitch chat

Open a past broadcast from xQc's **Twitch Videos** tab as usual. For xQc archives,
Twire first looks for a matching completed, public Kick recording. Video and audio
come from Kick; chat, emotes and badges still come from the original Twitch VOD.
Live streams, clips, highlights and other channels keep their existing playback.

The player's overflow menu contains **VOD source** on xQc archives:

- **Use Twitch video** switches this VOD back to Twitch and remembers that choice.
- **Match xQc Kick VOD automatically** retries matching and re-enables Kick for this VOD.
- **Choose xQc Kick VOD** lists available recordings with local start dates, durations
  and titles. Use this if a stream restarted, matching is ambiguous, or the automatic
  choice is wrong.
- **Adjust Twitch chat timing** appears while Kick is selected. It applies an extra
  offset, in seconds, after automatic alignment. Positive values use later Twitch
  messages; negative values use earlier messages. For example, if chat appears ten
  seconds late, enter `10`. The adjustment is saved for this Twitch/Kick VOD pair.

Source switches, resume positions and forward/backward seeks use the same moment
on the original Twitch timeline. The player aligns recordings by their actual UTC
start times, not their titles or local calendar dates. Chat is suspended outside
Twitch's recorded interval; Kick-only portions cannot have Twitch chat replay.
Manual chat adjustments do not change the saved video position.

If no clear matching recording exists, or Kick cannot be reached, Twire explains
that it is falling back to Twitch. An expired playback URL can be refreshed with
the player's Retry action. Only recording IDs are saved, never signed playback
URLs. Kick's available adaptive qualities are selected by Media3 (`Auto (Kick)`).

## Limits

This supports public, completed, unencrypted xQc recordings. It does not recover
deleted/expired recordings, unlock subscriptions, or remove muting from Twitch.
Twitch chat replay must still be available for the Twitch VOD. Start-time matching
is an initial alignment, not an audio/content match: platform delays, stream edits
or restarts may need a manual choice or a new chat offset.

The Kick website API is not a stable public VOD API. This integration uses the
current website endpoints observed on November 20, 2026:

- `GET https://web.kick.com/api/v1/channels/668/videos`
- `POST https://web.kick.com/api/v1/stream/{recording-id}/playback`

The corresponding upstream extractor is
[yt-dlp's Kick extractor](https://github.com/yt-dlp/yt-dlp/blob/master/yt_dlp/extractor/kick.py).
Only `playback_url.vod` is used; a live playback URL is never substituted.
Separate HTTP headers and the standard HLS parser are used for Kick, preserving
Twitch's existing live low-latency parser and request headers.

## Checks and build

`KickVodApiTest` covers response validation, duration units, start times, matching,
ambiguity, unavailable recordings, and selecting VOD rather than live playback.
`VodTimelineTest` covers both offset signs, source-switch round trips, resume,
chat-only adjustments, and chat boundaries. The Android parcel test also checks
that the Twitch broadcast start time survives recreation.

The existing **CI** / **Build APK** GitHub Actions workflows run the Android build,
lint and JVM tests. CI also runs the native Rust tests and Android parcel tests on
an emulator. The APK artifact is named **TwireV2-debug-apk**. No Android Studio or
local SDK is required to build through Actions.
