# SKILLS - Nova Browser Go work log

## Done in this task
- Download failure "CertPathValidatorException: Trust anchor for certification path not found"
  - Cause: only ISRG Root X1 was added as an extra trust anchor; googlevideo.com uses Google Trust Services roots that old devices lack.
  - Fix: res/raw/extra_roots.pem (39 valid roots incl. GTS R1-R4, ISRG X1/X2, GlobalSign, DigiCert, USERTrust, Amazon, Sectigo).
  - core/NovaApp.kt: new `Tls` object builds a merged trust manager (system first, bundled roots as fallback).
  - `Tls.apply(builder)` now used by the OkHttp clients in yt/YtDownload.kt (YtFetch) and core/Suggest.kt (NetKit).
- Friendly TLS error text (res/values/strings.xml + values-ar/strings.xml) via `describe()` in download/Downloader.kt.
- Low-end tuning: progress tick 800ms and 64KB buffer when LowEnd.on; mux buffer sized from KEY_MAX_INPUT_SIZE instead of fixed 4MB.

## Pending
- MP3 conversion: Android has no MP3 encoder. AudioFormats.all in download/AudioConvert.kt is empty on purpose (FFmpeg removed).
  Needs a decision: native LAME wrapper (adds .so per ABI) or a pure-Java encoder.
- Once an encoder exists: fill AudioFormats.all, implement AudioConvert.run (MediaCodec decode -> PCM -> encoder), the sheet in yt/YtDownload.kt already lists AudioFmt entries.

## Notes
- Project had no way to compile here (no Gradle/kotlinc offline): build once in CI and check for compile errors.
- No README created (user preference). New code has no Arabic text; user-visible strings go in res/values*/strings.xml.

## Task 2: HTTP 403 mid-download (YouTube)
- googlevideo links are IP/session bound and throttle parallel ranges. YouTube tasks now use 1 connection (DlTask.connLimit) and on HTTP 403/410 fetch a fresh link via DlTask.refresher (max 4 times) and continue.
- Not verified on device. If 403 persists, check VPN/IPv6 mismatch between the extractor client and the downloader.

## Task 3: 403 persists, stuck at 0 B
- Likely cause: link came from a different InnerTube client (IOS/TV/web) than the UA used for the download. Now the UA is derived from the link's `c` param (ytUaFor) at start and after each refresh.
- 403 refresh events are logged via YtLog (c= old -> new). Check the log if it still fails; YouTube may require PO tokens for these clients.

## Task 4: 403 again + more audio formats
- Downloader now uses OkHttp (DlHttp) instead of HttpURLConnection; IPv4-first DNS (v4first) for both the extractor and the downloader so both hit googlevideo from the same IP (links are IP bound). No cookies/Referer sent to googlevideo.
- Empty error text now shows the exception class name.
- Audio list now shows every available stream (M4A AAC / WebM Opus at each bitrate).
- MP3 still pending (needs an encoder library).

## Task 5: MP3 conversion
- Dependency: com.github.NorthernCaptain:TAndroidLame:1.1 (JitPack, LAME JNI wrapper). Package assumed com.naman14.androidlame; used through reflection (Mp3Lame in download/AudioConvert.kt) so a mismatch cannot break compilation; MP3 options are hidden when the library or its native .so is missing.
- Flow: download the AAC m4a, decode with MediaCodec, encode with LAME (128/192/320 kbps), save as .mp3, delete the source. On failure the m4a is kept and a message is shown.
- ProGuard keeps com.naman14.androidlame.**.
- Risks to verify on first build: JitPack artifact resolves; arm64-v8a .so exists (and 16KB alignment on Android 15+ devices); wrapper license is GPLv3.

## Task 6: diagnostics for 403
- On a rejected range request the failure text now ends with: host, client (c=), Server header and first 120 chars of the response body (also in YtLog). Use it to identify the real cause (PO token, IP mismatch, expired link).

## Task 6: yt-dlp downloads stuck at 0% then "unable to download ... 403"
- Symptoms: card shows "Converting to MP3 • 0%" (wrong text for video downloads), hangs, then a Toast with HTTP 403 that disappears before it can be read.
- Causes found in yt/YtDlp.kt: no timeout on yt-dlp runs (a stalled client blocked the whole ladder); the yt-dlp self-update ran synchronously inside ensure() with no timeout (GitHub slow/blocked = sheet hangs on "fetching info"); error only shown as a 120-char Toast; the status text reused the MP3 string for every phase; generic format fallbacks could pick m3u8 (needs ffmpeg, absent).
- Fixes:
  - YtDlp.exec(): watchdog kills the yt-dlp process when no output arrives (120 s before first byte, 45 s after) or total time is exceeded; info() has an 80 s budget.
  - Update runs in a background thread (nightly channel found by reflection, falls back to stable); retries at most every 30 min on failure.
  - Download ladder: planned client+format, other clients with generic format, last try without --force-ipv4; generic formats exclude m3u8; --socket-timeout/--retries tuned.
  - Backup engine: if yt-dlp fails (typically 403 without PO token) the same option is re-run through YtFetch (links captured from the YouTube WebView player) and the old Downloader; info() also falls back to YtFetch when yt-dlp cannot extract.
  - Failures are now a persistent card (Downloader.addFailed) with the full error, "copy error details" (error + diagnostics log) and a working retry button, plus a failure notification.
  - Real phase text on the card: preparing / downloading video / downloading audio / merging / converting to MP3 with progress; indeterminate bar until the first byte.
  - MP3 conversion reports progress; if conversion fails the original audio is saved instead of being lost (AudioConvert.run now returns Boolean).
- Strings added to values/ and values-ar/ (dl_phase_*, dl_err_403, dl_err_stalled, dl_saved_original, dl_copy_error, dl_failed_title).
- Not compiled/tested here (no Gradle or network offline): build in CI and check for compile errors. Risky API names to check first: YoutubeDL.destroyProcessById, YoutubeDL.UpdateChannel (looked up by reflection).
- If 403 still shows after both engines: copy the error details from the card (includes the diagnostics log) and send it.

## Task 7: error details copy
- "Copy error details" now copies only the yt-dlp / innertube / http 4xx log lines (last 2500 chars) so the primary yt-dlp failure is not pushed out by the backup engine's log spam.
- Open question: 403 persists with VPN off; waiting for the yt-dlp lines to see why the primary engine fails (bot check vs PO token).

## Task 8: "Sign in to confirm you're not a bot" (log confirmed it is the primary failure)
- Log: android_vr = bot check, tv = "page needs to be reloaded", ios = format unavailable, default = 403. The backup engine then 403s too (IOS link without PO token, WebView sniff finds 0 links).
- Fix: on the first bot-check error yt-dlp is retried with the browser session cookies (YtDlp.cookieFile: Netscape file built from CookieManager for youtube/google, written to cacheDir/ytdlp/cookies.txt). Cookies are used only after a bot check, never by default. Applies to info() and to downloads.
- If there are no cookies (not signed in), the card shows dl_err_login telling the user to sign in inside the YouTube page.
- Risk: using a Google account session with yt-dlp can get the account flagged; exported live-session cookies may also rotate and stop working. Using a secondary account is safer.

## Task 9: local PO token (BotGuard) for yt-dlp
- New yt/PoToken.kt + assets/potoken.html: hidden WebView (base URL https://www.youtube.com) fetches the BotGuard challenge (jnn/v1/Create), runs the interpreter, calls GenerateIT, then mints two tokens with the web minter: one bound to visitorData (GVS) and one bound to the video id (player). Cached 20 min per video. 40 s timeout, logs "po-token: ..." in the diagnostics log.
- visitorData order: YtSniff.visitor -> ytcfg VISITOR_DATA of the YouTube WebView -> youtubei visitor_id request.
- yt/YtDlp.kt: when a token exists, clients mweb and web_safari are tried first with `--extractor-args youtube:player_client=X;po_token=X.gvs+..,X.player+..;visitor_data=..`; otherwise the old client list. Cookies retry (Task 8) still applies on top.
- Written from memory of the BotGuard protocol (as used by NewPipe/BgUtils) and NOT tested: if the log shows "po-token: failed ..." the message says which step broke (Create parse, vm missing, no minter...). Send those lines.
- ProGuard keeps PoToken.Bridge (JavascriptInterface).

## Task 10: yt-dlp never updated (GitHub blocked) + outdated client list
- Cause 1: the bundled yt-dlp (youtubedl-android 0.18.1) was updated only through github.com, which is blocked on the user's network, so it stayed old and its client names/behaviour no longer match YouTube.
- Cause 2: client list android_vr/tv/ios/default; in yt-dlp 2026.10.26 android_vr and ios require a PO token, and the new defaults are tv_samsung, visionos, web (no token needed for tv_samsung/visionos/tv).
- Fix: new yt/YtDlpUpdate.kt downloads the latest yt-dlp zipapp (nightly repo first, then stable) through the Worker (/gh/... and /dl?u=, already in worker.js; direct GitHub as fallback), verifies SHA2-256SUMS (or the asset digest), checks it is a zipapp, keeps yt-dlp.bak, replaces the binary found under noBackupFilesDir. Version/signature kept in prefs "ytdlp" (tag, sig); if the lib re-extracts its old binary the signature differs and an update is forced.
- yt/YtDlp.kt: runUpdate tries the Worker updater first, then the old library updater; update checked daily (or immediately when stale); baseClients = default, tv, tv_downgraded, android_vr, ios; first extraction waits up to 25 s for the update.
- Not compiled/tested here (no Gradle/network). If the log shows "yt-dlp-update: binary not found; noBackupFilesDir: ..." the path to the lib's yt-dlp file differs: send that line.
- Not changed: worker.js (no redeploy needed). yt-ai (needs a Python server) and YoutubeDownloader (C#, YoutubeExplode) were reviewed: nothing usable on-device.
