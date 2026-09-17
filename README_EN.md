# Home KTV

[中文](README.md) | **English**

> Documentation sync: 2026-08-31. This README follows the current source and release layout; pending internal remediation is not presented as completed functionality.

Home KTV turns a NAS, Linux host, or Docker Desktop machine into a private,
LAN-only karaoke system. Use either the Android TV client or the Windows 11
Player for playback, and use a phone browser for song selection and remote
control. The server keeps the queue, lyrics, playback state, media processing,
and song metadata in sync in real time.

> Home KTV is designed for a trusted home LAN. It does not provide public-login
> or Internet-facing security controls. Do not expose it directly to the
> Internet.

## Screenshots

| Mobile songbook | TV playback | Library and service dashboard |
| --- | --- | --- |
| ![Mobile songbook](docs/images/mobile-songbook.png) | ![TV playback](docs/images/tv-player.png) | ![Admin dashboard](docs/images/admin-dashboard.png) |
| Browse, search, favorite, and queue songs from a phone. | Big-screen playback, animated lyrics, and QR-code song selection. | Track the library, transcoding work, and playback service from one place. |

## Highlights

- **One server and three user surfaces:** Spring Boot server, Vue 3 mobile/admin
  H5, Android TV based on Media3/ExoPlayer, and a Windows 11 Player built with
  .NET 10/WPF plus mpv JSON IPC.
- **Phone-first song selection:** no app installation or registration required;
  search by title, artist, Chinese name, full pinyin, or pinyin initials.
- **TV playback built for karaoke:** dual audio tracks, vocal/accompaniment
  switching without reloading, animated LRC lyrics, remote-control support,
  reconnect recovery, QR-code pairing, and burn-in protection.
- **Two library modes:** `MANAGED` can import, direct-copy, transcode, and safely
  clean processed source media; `EXTERNAL_READ_ONLY` indexes and plays an
  existing NAS library without modifying the source files.
- **Media-library workflow:** scan source media, inspect with FFprobe, dedupe where
  content verification is needed, direct-copy compatible files, transcode
  incompatible media, and import sidecar lyrics and cover art.
- **Real-time room control:** the queue, playback state, lyrics, volume, and
  controls synchronize through WebSocket.

## Current Release Highlights

- **Source pipeline:** every scan re-evaluates transcoding requirements. Compatible
  files move directly into the KTV library and leave the source-management list;
  incompatible files remain available for transcoding with progress and priority control.
- **KTV administration:** paginated queries and fixed action controls are joined by
  metadata scraping with resumable batches, live progress, confidence-based apply,
  manual review and editing, per-song rematching, and cover previews.
- **Artist library:** normalized artist names, gender status, batch AI analysis, and
  manual review with representative songs for ambiguous names.
- **Mobile songbook:** covers on every song row, first-class language and category
  browsing, male/female artist filters, and adding songs to existing playlists.
- **Theme playlists:** editable previews generated from curated library metadata,
  up to 100 songs per playlist, valid shorter results, and deletion of AI-generated lists.
- **Settings center:** categorized navigation, search, deep links, model capability
  checks, ingestion/transcoding controls, TV display settings, and data maintenance.
- **AI and fallback:** arbitrary OpenAI-compatible URLs and model IDs, optional
  bulk/reasoning models, model discovery, concurrency limits, and local-rule fallback
  for supported parsing tasks. AI-only operations ask for configuration instead of
  fabricating results.
- **Windows Player:** server discovery/manual connection, WebSocket reconnect,
  playback-state recovery, `DUAL_TRACK`/`DUAL_CHANNEL`, display selection and
  borderless fullscreen output.
- **Release delivery:** signed 32-bit and 64-bit Android Release APKs are bundled
  in the Docker image, and the Windows x64 Player is published as a separate
  release ZIP. TV clients can download and open the ABI-matched Android installer.
- **Migration safety:** Flyway V15 preserves source history, while a migration safety
  test rejects direct table deletion, truncation, and destructive drops.

## Quick Start

### Requirements

- A NAS, Linux host, or Docker Desktop installation with Docker Compose
- At least 1 GB of available memory is recommended
- Phone, Android TV, and server on the same LAN
- Android TV 8.0 (API 26) or later when using the Android player
- Windows 11 x64 when using the Windows Player; the release is self-contained but requires a separate `mpv.exe`

### 1. Configure storage and credentials

```bash
git clone <repository-url>
cd home-ktv
cp .env.example .env
```

Set separate host directories for source media and the playable library, then
replace the database password:

```dotenv
KTV_SOURCE_MUSIC_DIR=/volume1/home-ktv/source-music
KTV_MUSIC_DIR=/volume1/home-ktv/music
KTV_DB_PASSWORD=replace-with-a-strong-password
KTV_ADMIN_PASSWORD=replace-with-an-admin-password
```

The server writes processed files into `KTV_MUSIC_DIR`, so ensure the container
has write access. Never point both directory variables at the same path.

### Existing NAS library: read-only mode

Use `docker-compose.nas.yml` when the NAS already contains the karaoke files.
For example:

```dotenv
KTV_SOURCE_MUSIC_DIR=/volume1/KTV
```

This uses `EXTERNAL_READ_ONLY` and mounts the existing source directory
read-only. Home KTV indexes and plays the existing files without copying,
without moving, without renaming, without deleting, without overwriting, and
without transcoding or automatically cleaning the source files.

Use the Managed workflow only when Home KTV is allowed to import and organize
source media.

### 2. Start the stack

The recommended deployment pulls the multi-architecture image published by
GitHub Actions and does not compile anything on the NAS or host:

```bash
docker compose -f docker-compose.prebuilt.yml up -d --pull always --wait
```

It uses `ghcr.io/aimeng001/ktv-home:latest` by default. For production, set
`KTV_RELEASE_IMAGE` in `.env` to a specific release tag so upgrades are
explicit.

The production Compose defaults give the application a 4 GB container limit
and a 2 GB JVM heap. On an out-of-memory error the JVM writes a diagnostic
heap dump under `/data` and exits so Docker can restart it. If an existing
`.env` still contains the old `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=70 -Xmx512m`,
remove it or replace it with the value from `.env.example`; an explicit old
value overrides the safer Compose default. After administrator login,
`/api/admin/diagnostics/memory` reports heap usage and scan progress.

To build from source instead, run:

```bash
docker compose up -d --build --wait
docker compose ps
curl http://127.0.0.1:${KTV_HTTP_PORT:-8080}/api/ready
```

| Service | Default port | Purpose |
| --- | --- | --- |
| TCP | `8080` | Mobile UI, administration UI, API, WebSocket, and media streaming |
| UDP | `18888` | Android TV LAN discovery |

Allow both ports through the NAS or host firewall. The UDP discovery protocol
always uses port `18888`; only the HTTP service port can be customized in `.env`.

### 3. Add songs and connect the TV (Managed mode)

1. In `MANAGED` mode, copy source media to `KTV_SOURCE_MUSIC_DIR`.
2. Open `http://<host-ip>:8080/m/admin` and choose **Scan source path**.
3. Review files in **Source Library** and start transcoding where needed.
4. Install the Android TV APK, then let it discover the server or enter
   `<host-ip>:8080` manually.
5. Scan the QR code shown on TV and start picking songs at
   `http://<host-ip>:8080/m`.

After verifying imported files, **Auto cleanup** in **Source Library** removes
only safely imported originals whose valid library output still exists. Pending,
failed, duplicate, unrecognized, or unverifiable files are retained. When cleanup
finishes, return to the dashboard and run **Scan source path** again to synchronize
the latest contents of the source directory.

Each published server image contains signed 32-bit (`armeabi-v7a`) and 64-bit
(`arm64-v8a`) Release APKs with the same release version. The release tag becomes
`versionName`, while the monotonically increasing GitHub Actions run number becomes
`versionCode`; the same values are embedded in the server image and both APKs.

`GET /api/release` exposes the version, announcement, and ABI-specific package
metadata. The announcement ID defaults to the release version. **Remind later**
hides it for the current browser session, while **Mark as read** stores that ID in
the current browser until the ID or version changes. The administration UI only
opens the notice when announcements are enabled and the image contains at least
one APK, so source-built development images do not present dead download links.

The default release notice announces the Android TV APK update and asks users to
rescan the library after upgrading.

`EXTERNAL_READ_ONLY` libraries must keep the source files unchanged. `MANAGED`
deployments should follow the maintenance instructions shown in the
administration UI.
The notice ships in the image's `application.yml`; it does not depend on users
updating `docker-compose.yml` or `.env`. Pulling the new image is sufficient to
receive its version and announcement.

After connecting, the TV compares its `versionCode` with the server. When they
differ, it selects the package matching the device ABI. Download verifies the
reported size, requests unknown-source installation permission when necessary,
and opens the system installer. Update checks and download failures never block
playback, and failed downloads can be retried. Direct download endpoints are:

```text
http://<host-ip>:8080/api/release/tv/apk/armeabi-v7a
http://<host-ip>:8080/api/release/tv/apk/arm64-v8a
```

The download filenames are `home-ktv-tv-<version>-armeabi-v7a.apk` and
`home-ktv-tv-<version>-arm64-v8a.apk`.

All Release APK updates must keep the same signing certificate. Existing Debug
installs use a `.debug` application ID and a different signature, so they need
one initial uninstall before the first Release APK can be installed. This only
clears the TV app's saved server address; server-side songs and data are unchanged.

Build a debug TV APK with JDK 17 and the Android SDK:

```bash
cd android-tv
./gradlew testDebugUnitTest assembleDebug
```

The resulting APK is at
`android-tv/app/build/outputs/apk/debug/app-debug.apk`.

### 4. Windows Player

GitHub Releases also publish `home-ktv-windows-player-<version>-win-x64.zip`.
Extract it and run `HomeKtv.Windows.exe`. The .NET application is self-contained,
but the package intentionally does **not** bundle a third-party `mpv.exe`. Install
an x64 Windows build of mpv and either place `mpv.exe` next to the Home KTV
executable, make it available on `PATH`, or configure an absolute
`MpvExecutablePath` in `%LOCALAPPDATA%\HomeKtv.Windows\settings.json`.

The player can discover the server on the LAN or connect to `<host-ip>:8080`
manually. It supports display selection, borderless fullscreen, reconnect/recovery,
and both dual-track and left/right-channel karaoke audio layouts.

## Common URLs

| Purpose | URL |
| --- | --- |
| Mobile songbook | `http://<host-ip>:8080/m` |
| Administration | `http://<host-ip>:8080/m/admin` |
| Discovery health check | `http://<host-ip>:8080/api/health` |
| Container readiness (including database) | `http://<host-ip>:8080/api/ready` |

## Local Development

Development requires Node.js 20+, JDK 21, JDK 17, Docker, and the Android SDK. Windows Player development additionally requires the .NET 10 SDK.

```bash
# PostgreSQL
docker compose -f docker-compose.dev.yml up -d

# Backend (JDK 21)
cd backend && ./mvnw spring-boot:run

# Mobile web app
cd h5 && npm install && npm run dev

# Android TV (JDK 17)
cd android-tv && ./gradlew testDebugUnitTest assembleDebug

# Windows Player (.NET 10)
cd windows-player
dotnet test HomeKtv.Windows.sln -c Release
dotnet build HomeKtv.Windows.sln -c Release
```

Run the full module tests with `cd backend && ./mvnw test`, `cd h5 && npm test`,
`cd android-tv && ./gradlew testDebugUnitTest`, and
`cd windows-player && dotnet test HomeKtv.Windows.sln -c Release`.

## Media Notes

Home KTV prioritizes embedded media metadata and falls back to filenames such
as `Artist - Title.mp4`. Put same-named `.lrc` files beside media files for
line-by-line or enhanced word-timed lyrics. For dual-track karaoke videos, use
the order **vocal first, accompaniment second** and preferably label the tracks
`vocal` / `accompaniment` (or `原唱` / `伴奏`).

## AI Configuration and Fallback

AI is disabled by default and never blocks scanning, transcoding, importing,
song selection, or playback. Configure any OpenAI-compatible Chat Completions
service from **Administration → Settings → AI Models**. Model IDs are free-form;
the optional reasoning model falls back to the bulk model when left empty. The
settings page can discover models where supported and test authentication, chat,
and JSON capabilities.

Public providers require a non-empty API key. Private or local Ollama/LM Studio/
OpenAI-compatible endpoints may omit the key only when the explicit
`KTV_AI_ALLOW_PRIVATE_NETWORK=true` opt-in is enabled. With Docker deployments,
`localhost` means the Home KTV container itself, not the host running Ollama; use
an address that the Home KTV server can actually reach.

Stored API keys are encrypted with AES-256-GCM. Keep the generated
`data/secrets/config.key` file with the application data backup. When AI is not
configured or a request fails, embedded tags, filenames, lyric tags, and directory
rules remain available. Features without an equivalent local implementation,
such as natural-language playlist planning or artist-gender inference, request AI
configuration and do not invent a result.

## Hardware Transcoding

CPU transcoding is the default. Hardware encoding has currently been verified
only on Intel GPUs using the VAAPI `iHD` driver for H.264 and HEVC. AMD VAAPI
and Rockchip RK MPP have not been verified on physical hardware; their Compose
files provide device passthrough but do not guarantee that the prebuilt image
can enable hardware encoding out of the box. See the
[Chinese hardware-transcoding section](README.md#硬件转码) for the exact commands
and host-device requirements.

## Known Limitations

- Home KTV targets a trusted home LAN and must not be exposed directly to the Internet.
- The Windows release currently requires a separately installed/configured `mpv.exe`.
- Fully unauthenticated local OpenAI-compatible AI endpoints are not yet supported by the current request path.
- Android TV auto-start/background behavior can depend on vendor-specific permissions.
- Hardware encoding has only been physically verified on the Intel VAAPI path described above.

## License and Media Responsibility

The code is released under the [MIT License](LICENSE). Only import and play
media that you are authorized to use. Home KTV neither provides nor distributes
music, videos, or accompaniment tracks.
