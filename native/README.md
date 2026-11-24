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
