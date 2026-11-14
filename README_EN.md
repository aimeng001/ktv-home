# Home KTV

[中文](README.md) | **English**

Home KTV is a local-network karaoke system for a NAS or Linux host. The server owns the song catalog, playback queue, favorites, history, lyrics, and settings. Android TV or Windows Player handles playback; phones can browse and order songs through the H5 client.

## Components

- **Server:** Spring Boot, PostgreSQL, FFmpeg/FFprobe, HTTP APIs, and WebSocket.
- **Phone and administration:** Vue 3 H5 songbook and admin console.
- **Android TV:** Media3/ExoPlayer playback client. The APK also provides controller and combined modes.
- **Windows Player:** Windows 11 WPF client using mpv and the shared server protocol.

The system is intended for a trusted home network. Do not expose the ordinary song-ordering and remote-control endpoints directly to the public internet.

## Library modes

Home KTV supports `MANAGED` and `EXTERNAL_READ_ONLY` deployments.

- **`EXTERNAL_READ_ONLY`:** Use [`docker-compose.nas.yml`](docker-compose.nas.yml) for an existing NAS library. The source mount is read-only. Home KTV indexes, probes, and streams existing files without copying, without moving, without renaming, and without deleting source media. It does not overwrite or transcode source files in place.
- **`MANAGED`:** Use the managed Compose configuration when Home KTV is responsible for importing and organizing source media. Keep the source and library directories separate and configure write access only for this mode.

For an existing NAS library, set `KTV_SOURCE_MUSIC_DIR` to its path and start the NAS configuration:

```bash
docker compose -f docker-compose.nas.yml up -d --wait
```

For the prebuilt managed deployment:

```bash
docker compose -f docker-compose.prebuilt.yml up -d --pull always --wait
```

The NAS configuration keeps `/source-music` mounted read-only. Do not use the managed import or cleanup flow against an `EXTERNAL_READ_ONLY` library. The Chinese [deployment instructions](README.md#快速开始) contain the full environment-variable and firewall setup.

## Main features

- Search by song title, artist, Chinese characters, full pinyin, or pinyin initials.
- Order, top, and remove queue entries; share queue state between clients.
- Control playback, volume, repeat, next song, and original/vocal audio layout.
- Use favorites, recently sung history, playlists, lyrics, and song metadata.
- Scan and inspect a library with FFprobe; supported media includes common video and audio formats.
- Discover the server from Android TV on the local network using UDP port `18888`.
- Manage library metadata, playback services, transcoding tasks, and settings through the admin console.

Library metadata does not make a media file playable by itself. Home KTV retains its own song IDs, availability rules, and playback APIs.

## Recent Updates & Android TV

- **Search & Playback Readiness (v1.0.34):** Fast B-Tree prefix index path for single-letter searches (`a`–`z`), on-demand FFprobe probing via `SongProbeService` (`POST /api/admin/songs/{id}/probe` and pre-order probe), zero-disk-cache `pipe:1` live fMP4 transcoding for legacy formats/decoder fallback, and read-only external artist avatar matching via `/avatar-library` (`KTV_AVATAR_LIBRARY_DIR`).
- **Android TV KTV UI (v1.0.34):** 10-tile Bento home dashboard + unified three-column 3×5 song browser with 5-mode soft keyboard, circular artist avatar/surname initial badges with display-title cleanup (`SongDisplayFormatter`), and purged legacy single-panel UI shells across clients.

## Android TV updates

Release images may include 32-bit (`armeabi-v7a`) and 64-bit (`arm64-v8a`) TV APKs. The server exposes release metadata through `GET /api/release`; a TV client checks for a newer version after connecting and lets the user download and install the matching package.

The default release notice announces the Android TV APK update and asks users to rescan the library after upgrading. `EXTERNAL_READ_ONLY` libraries must keep the source files unchanged. For `MANAGED` libraries, follow the applicable maintenance instructions shown in the admin UI.

Release APKs must keep the same signing certificate across upgrades. A debug APK has a different package/signature and cannot be upgraded directly to the release APK.

## Local development

- Backend: Java 21 and PostgreSQL.
- Android TV: JDK 17 and Android SDK.
- H5: Node.js and npm.
- Windows Player: .NET 10 SDK; a compatible `mpv.exe` is required at runtime.

Useful local validation commands and the current architecture are documented in [REQUIREMENTS.md](REQUIREMENTS.md), [ARCHITECTURE.md](ARCHITECTURE.md), and the Chinese [README](README.md). The local project instructions are not a substitute for deployment-specific database backups or NAS permissions.

## Documentation

- [Chinese project README and full deployment guide](README.md)
- [Requirements](REQUIREMENTS.md)
- [Architecture](ARCHITECTURE.md)
- [Local Docker and Android emulator test guide](docs/testing/local-docker-desktop-avd-guide.md)

