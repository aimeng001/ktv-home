# Home KTV Windows Player

This package contains the self-contained .NET player. It does not include
`mpv.exe`, which is a separate runtime prerequisite.

## First-time setup

1. Install an x64 Windows build of mpv from the [mpv installation page](https://mpv.io/installation/).
2. Put `mpv.exe` next to `HomeKtv.Windows.exe`, or set `MpvExecutablePath`
   in `%LOCALAPPDATA%\HomeKtv.Windows\settings.json` to an absolute path.
3. Start `HomeKtv.Windows.exe` and enter the Home KTV server address, or use
   the LAN discovery button.

The player checks for `mpv.exe` beside its own executable first, then falls
back to the configured name so a normal Windows `PATH` installation remains
supported. If mpv cannot be started, the player shows the expected path and
the installation/configuration remedy.

The release workflow intentionally does not download an unpinned third-party
binary into the package. A future bundled-mpv release must pin the exact
Windows build, verify its checksum, and include the applicable license and
notice files.
