## Delta 1.0114.01 — first public release

The assistant's voice experience, polished end to end. Full record of the work: [WHAT_OPENCODE_DID.md](https://github.com/BRL26/delta/blob/main/WHAT_OPENCODE_DID.md).

### Voice assistant
- **Mic stays open** while you talk in the big overlay — no tapping the mic between messages.
- **Auto-send** after 3 seconds of silence.
- **Auto-quit**: hearing nothing 3 times in a row (~9 s) stops listening and closes the overlay by itself.
- Mic is released when the assistant collapses into the pill.

### Sound
- **No more listening/not-listening chimes.** The start (recogniser) and close (OEM) tones are both silenced for the whole mic-open window, with a delayed restore that swallows the closing chime.

### Branding
- Rebranded to **Delta**: new delta-triangle launcher icon, app name, themes, constants.
- Wake word (and its proprietary engine) removed entirely.

### Notifications
- Assistant notification now reads *"Delta — Assistant is active"* on a silent, low-importance channel.

### Release notes
- **Primary APK is release-signed**: `delta-1.0114.01-signed.apk` (RSA-2048, `CN=Delta`) installs directly. The key lives outside the repo at `~/.android/delta-release.jks` — back it up!
- R8/minify disabled for this release (see the comment in `app/build.gradle.kts`).
- Pinned version: `versionCode 114 / versionName 1.0114.01`. The build's auto-increment task is bypassed (`-x incrementVersion`) so this build keeps that exact version.