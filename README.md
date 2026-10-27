# Songify

A free, ad-free Android music player with a clean, Spotify-style design. Search any song, stream it in full, and keep your playlists on your own phone. No account and no server.

**[Download the latest APK](../../releases/latest)** (Android 8.0 or newer)

## Features

- **Full songs, on demand.** Search as you type, play anything, skip as much as you like.
- **Plays in the background.** Screen off, app closed, lock screen and headphone controls.
- **Your library, on your phone.** Liked Songs, playlists and recently played are stored locally in one file. Nothing is uploaded.
- **Bring your playlists.** Import from Spotify (via [Exportify](https://exportify.app)), Apple Music links, CSV, M3U, Spotify's own data export, or a pasted song list.
- **Fix anything that didn't match.** Songs that couldn't be found are kept on the playlist; search for them or paste a link to the exact song.
- **Share playlists as files.** Friends open the file in Songify and get the same playlist.
- **Synced lyrics, queue, sleep timer, autoplay and song radio.**
- **Audio quality you control.** Automatic, Low, Normal or High; Automatic drops quality on slow connections.
- **Make it yours.** Pick any accent color, plus a pure-black mode for OLED screens.

## Moving your Spotify playlists

1. Open [exportify.app](https://exportify.app) and log in with Spotify.
2. Tap **Export** next to a playlist, or **Export All** for a ZIP of every playlist.
3. Open the file with Songify, or go to **Your Library → + → Import a file**.

The beginner's guide in the app (Settings → Beginner's guide) walks through this. A Spotify playlist link also works, but Spotify only shares the first 100 songs that way.

## Install

1. Download `Songify-<version>.apk` from [Releases](../../releases).
2. Open it on your phone and allow installing from this source when Android asks.
3. New versions install over the old one; your playlists are kept.

## Build from source

Requirements: JDK 17 and the Android SDK (platform 35).

```sh
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/`. If your editor holds files in `app/build` open (some language servers do), build into another folder with `-Psongify.buildDir=<path>`.

Optional live tests (they call real music services):

```sh
SONGIFY_LIVE_TESTS=1 ./gradlew testDebugUnitTest
```

## Tech

Kotlin, Jetpack Compose (Material 3), Media3 ExoPlayer with a MediaSessionService for background playback, OkHttp, Coil, kotlinx.serialization, and [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor). Lyrics come from [LRCLIB](https://lrclib.net).

## Disclaimer

Songify is a personal, non-commercial project. It does not host any music. You are responsible for how you use it and for following the terms of the services it connects to.
