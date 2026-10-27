# Songify

A free, ad-free music player for **Android and Windows** with a clean, Spotify-style design. Search any song, stream it in full, download it for offline play, and keep your playlists on your own device. No account and no server.

**[Download the latest version](../../releases/latest)**: `Songify-<version>.apk` for Android 8.0+, `Songify-<version>.msi` for Windows 10/11.

## Features

- **Full songs, on demand.** Search as you type, play anything, skip as much as you like. Next always finds you another song.
- **Offline downloads.** Download songs or whole playlists and play them with no internet. Songs that can't be downloaded are marked so you can retry.
- **Plays in the background.** On Android: screen off, app closed, lock screen and headphone controls.
- **Your library, on your device.** Liked Songs, playlists and recently played are stored locally. Nothing is uploaded.
- **Bring your playlists.** Import from Spotify (via [Exportify](https://exportify.app)), Apple Music links, CSV or ZIP exports, M3U, Spotify's own data export, or a pasted song list.
- **Fix anything that didn't match.** Songs that couldn't be found are kept on the playlist; search for them or paste a link to the exact song.
- **Share playlists as files.** Friends open the file in Songify (phone or computer) and get the same playlist.
- **Synced lyrics, queue, sleep timer, autoplay and song radio.**
- **Audio quality you control.** Automatic, Low, Normal or High.
- **Updates itself.** Songify tells you when a new version is out and installs it.
- **Make it yours.** Pick any accent color, plus a pure-black mode.

## Moving your Spotify playlists

1. Open [exportify.app](https://exportify.app) and log in with Spotify.
2. Click **Export** next to a playlist, or **Export All** for a ZIP of every playlist.
3. In Songify, go to **Your Library → + → Import a file** and pick the download (on Android you can also just open the file with Songify).

The beginner's guide in the app walks through this. A Spotify playlist link also works, but Spotify only shares the first 100 songs that way.

## Install

**Android:** download the `.apk` from [Releases](../../releases), open it on your phone and allow installing from this source when asked. New versions install over the old one; your playlists are kept.

**Windows:** download the `.msi` from [Releases](../../releases) and run it. Songify installs for your user only (no admin needed) and adds a Start menu shortcut. Keyboard: Space play/pause, Ctrl+→ next, Ctrl+← previous; media keys work while Songify is focused.

## Build from source

Requirements: JDK 17 and, for Android, the Android SDK (platform 35).

```sh
./gradlew :app:assembleRelease           # Android APK → app/build/outputs/apk/release/
./gradlew :desktop:packageReleaseMsi     # Windows installer → desktop/build/compose/binaries/main-release/msi/
./gradlew :desktop:run                   # run the desktop app directly
```

If your editor holds files in `app/build` open (some language servers do), build Android into another folder with `-Psongify.buildDir=<path>`.

Optional live tests (they call real music services):

```sh
SONGIFY_LIVE_TESTS=1 ./gradlew :core:test
```

## Project layout

- `core/`: shared Kotlin code: search, song matching, playlist import, lyrics, library storage, update checks.
- `app/`: Android app (Jetpack Compose, Media3 ExoPlayer with background playback and offline downloads).
- `desktop/`: Windows app (Compose for Desktop; audio decoded in pure Java and played through the system output).

## Tech

Kotlin, Jetpack Compose and Compose for Desktop (Material 3), Media3 ExoPlayer, OkHttp, Coil, kotlinx.serialization, [NewPipe Extractor](https://github.com/TeamNewPipe/NewPipeExtractor), mp4parser, JAAD and JLayer. Lyrics come from [LRCLIB](https://lrclib.net).

## Disclaimer

Songify is a personal, non-commercial project. It does not host any music. You are responsible for how you use it and for following the terms of the services it connects to.
