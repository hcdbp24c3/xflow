# XFlow, native Android (Kotlin)

A rewrite of the Flutter app in Kotlin with Jetpack Compose and Media3 ExoPlayer. It lives next
to the Flutter project, which is unchanged, and uses the same application id
(`com.alchemistaloha.xflow`), so it can replace the Flutter app in place.

## Layout

- `core/` — pure Kotlin (JVM): models, the X response parser, the discovery engine and feed
  pipeline, SQL text, tap detection, paging rules, formatters. Everything here has unit tests.
- `app/` — the Android app: X client (OkHttp), database, settings, stores, player pool, media
  cache and all Compose screens.

## Build

    ./gradlew :core:test :app:testReleaseUnitTest
    ./gradlew :app:assembleRelease -Pabi=arm64-v8a

`-PciIdSuffix=.kt` builds a side-by-side package (`com.alchemistaloha.xflow.kt`) that installs
next to the released app and next to the Flutter CI build (`.ci`). Without `key.properties` the
release build is signed with the debug key.

`tools/check-core.sh` and `tools/check-app.sh` compile (and for core, test) the sources with a
standalone `kotlinc` for machines that cannot run the Android Gradle build.

## Data from the Flutter app

Installed over the Flutter app (same id, same signing key), the native app:

- opens the same database file (`xflow.db`, schema version 10) in place: accounts (the login),
  subscriptions, cached media, hashtags and bookmarks carry over;
- copies the Flutter settings (`FlutterSharedPreferences`, keys starting with `flutter.`) once
  into its own settings file; the Flutter file is only read;
- shares the WebView cookie store, which belongs to the application id.

Nothing from the Flutter app is deleted. If the database cannot be opened the app starts empty
and asks for a login.

## Signing of the CI builds

The CI workflows (`build-native.yml`, `build-apk.yml`) sign every APK with one fixed test key, so a
new CI build installs over the previous one. The key is held in the repository's Actions secrets
(`CI_KEYSTORE_BASE64`, `CI_KEYSTORE_PASSWORD`, `CI_KEY_ALIAS`); it is not in the repository and it
is not the release key (`release.yml` uses its own secrets). Each run prints the certificate of
the APK it built. SHA-256 of the certificate:

    2F:AF:2D:FE:04:9F:96:C2:C7:ED:BF:9E:AA:D3:88:0D:A6:38:49:C9:5F:E9:CD:D9:2F:E4:90:9D:92:02:5F:6B

Without the secrets (a fork) the build falls back to the runner's debug key and says so in the log.

## Links

Tapping a link in a caption goes through `LinkRouter` (`core/link`): a profile or hashtag link of
x.com / twitter.com opens that screen of the app, any other web page opens in the in-app browser
(`ui/browser/InAppBrowserScreen`), and a link that is not http(s) (`mailto:`, `market:`,
`intent:`, ...) is handed to the system. The in-app browser is a pushed screen, so the feed under
it stops playing while it is open. Its WebView shares the app's cookies (x.com pages are signed
in), gets no file or content access, allows no mixed content, and nothing is injected into pages.

## Talking to X (state of 2026-11-24)

`x-client-transaction-id`, in this order; the log line of every request names the source
(`txId=webview-module|kotlin|third-party|missing`), never the value:

1. X's own generator, found and called in the hidden x.com page (`TransactionIdScript`).
2. The same algorithm in Kotlin (`TransactionIdGenerator`), from the public x.com document. Which
   key bytes it reads is looked up in X's public scripts (`TransactionIdAssets`), with the values
   of 2026-11-24 as the fallback. The page is kept for an hour and read again after a 404.
3. The third-party service of the Flutter app. Its host no longer resolves; after a failure it is
   left alone for 30 minutes.

Search, when `SearchTimeline` answers 404 with every id:

1. the operation the x.com search page itself runs, read from that page (`PageOperations`);
2. for a query that names accounts (`from:a OR from:b`), the timelines of those accounts
   (`AccountTimelineFallback`, operation `UserTweets`). Differences from the search: a page of the
   feed covers a few accounts, not the whole query; only the newest ~20 posts of each account are
   seen; replies, reposts, media type and the minimum like count are filtered in the app; a query
   without accounts (hashtag, trending) has no replacement. Timelines are cached for 10 minutes and
   the route stops before X's rate limit for `UserTweets` is used up.

## Translation and subtitles

- **Translate** (under a caption, in the reply list, and in the "More" sheet) asks X itself:
  `POST /2/grok/translation.json` with `{"content_type":"POST","id":…,"dst_lang":…}` on
  `api.x.com`, then on `x.com/i/api`, then the older Strato `translateTweet` address. The
  first is what the x.com web client called on 2026-11-24 (read from its public bundle); it
  answers 401 without a session and has **not been called with a signed-in account** from
  this project yet. Every attempt is logged (`Translate: …` under the tag `XFlow`).
  It is offered only when the post's language differs from the target (Settings → Playback &
  Feed → Translate Posts Into; default is the device language). Translations are kept in
  memory for the run of the app.
- **Subtitles** come from the video's HLS master playlist (`#EXT-X-MEDIA:TYPE=SUBTITLES`),
  which lists a playlist of WebVTT files on video.twimg.com; no sign-in is needed for them.
  Playback stays on the mp4, the cues are drawn by Compose from the player position. The
  switch is in the "More" sheet and in Settings; the choice is remembered.
- The language and playlist of a post are kept in a table of their own (`tweet_extras`), so
  the tables shared with the Flutter app keep their layout.
