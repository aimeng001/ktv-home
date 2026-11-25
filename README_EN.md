# Home KTV

[中文](README.md) | **English**

Home KTV is a local-network karaoke system for a NAS or Linux host. The server owns the song catalog, playback queue, favorites, history, lyrics, and settings. Android TV handles big-screen playback, while the H5 web client provides both a TV remote console when a TV is connected and a standalone web KTV player when no TV is online.

## Components

- **Server:** Spring Boot, PostgreSQL, FFmpeg/FFprobe, HTTP APIs, and WebSocket.
- **Web KTV and administration:** Vue 3 H5 kiosk player, remote console, and admin dashboard.
- **Android TV:** Media3/ExoPlayer playback and kiosk client replicating IKTV commercial TV interfaces.

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

## Core Capabilities & Android TV

- **Dual-Mode Web KTV & Admin Console:** Vue 3 H5 acts as a TV remote console (`TV_CONSOLE`) when an Android TV is online, or as a standalone Web KTV player (`WEB_HOST`) with Range streaming, `pipe:1` live fMP4 transcode fallback, Web Audio channel routing/pitch shifting, 16:9 PiP preview, and fullscreen lyrics.
- **Zero-Disk-Cache Streaming & Fast Search:** Mainstream formats stream via HTTP Range; legacy containers (e.g., RM/RMVB) or decoder fallbacks transcode in-memory via FFmpeg `pipe:1` without disk caching. Supports on-demand FFprobe probing (`SongProbeService`), single-letter B-Tree prefix index fast paths, and read-only external artist avatars (`/avatar-library`).
- **Android TV KTV UI (IKTV 50.0.4 Parity):** Landscape-locked TV client featuring a 10-tile Bento home dashboard, three-column 3×5 song browser, 5-mode D-pad soft keyboard, 11-control bottom dock with 16:9 PiP preview, and hardware-accelerated Media3 playback.

## Android TV updates

Release images may include 32-bit (`armeabi-v7a`) and 64-bit (`arm64-v8a`) TV APKs. The server exposes release metadata through `GET /api/release`; a TV client checks for a newer version after connecting and lets the user download and install the matching package.

The default release notice announces the Android TV APK update and asks users to rescan the library after upgrading. `EXTERNAL_READ_ONLY` libraries must keep the source files unchanged. For `MANAGED` libraries, follow the applicable maintenance instructions shown in the admin UI.

Release APKs must keep the same signing certificate across upgrades. A debug APK has a different package/signature and cannot be upgraded directly to the release APK.

## Local development

- Backend: Java 21 and PostgreSQL.
- Android TV: JDK 17 and Android SDK.
- H5: Node.js and npm.

Please see the Chinese [README](README.md) for full deployment, architecture, and local validation instructions.

## Documentation

- [Chinese project README and full deployment guide](README.md)

