## Delta 1.0114.03 — the key opens the assistant, and the phone keeps its sound

Three changes since 1.0114.02, each found by using the app on the phone: a new action
for the Essential Key, two crashes that were taking the key's accessibility service down
with them, and an end to the app muting your notifications while it listened.

### Essential Key

- **New action: Delta assistant.** Any of the four press types can now open the
  assistant popup — through the same platform entry point the power-button gesture
  uses, so the key lands in the same conversation window. When Delta is not the phone's
  assistant yet, the press opens the screen that asks for the role instead of silently
  doing nothing.
- **Opening a press row in Settings no longer crashes.** The picker's sheet lives in a
  plain `Dialog`, which supplies none of the lifecycle owners a `ComposeView` resolves
  when it attaches — so tapping a row threw `ViewTreeLifecycleOwner not found` and took
  the app down. That is the "freeze" users saw, and it had a second face: everything
  runs in one process, so every crash left the accessibility service unbound, which is
  why the key then did nothing at all. The dialog now hands the view its owners before
  it attaches, the way the assistant popup already did.
- **A destroyed assistant session can no longer crash on a late restore.** A restore
  posted from the session's hide path could move an already-destroyed lifecycle to
  `STARTED` — the same process-wide crash, the same dead key.

### Sound

- **The app never mutes your phone again.** To hide the recogniser's "listening" beep,
  the app muted the notification and system streams for as long as the microphone was
  open — silencing notifications, key clicks and lock sounds with them, and sometimes
  outliving the app entirely if the process was killed mid-session. All of that code is
  deleted, along with the `MODIFY_AUDIO_SETTINGS` permission it needed. The recogniser's
  beep is back; your sound, vibrate and mute settings are yours.

### Release engineering

- `versionCode 116 / versionName 1.0114.03`, produced by the project's own
  `incrementVersion` task.
- **Primary APK is release-signed**: `delta-1.0114.03-signed.apk` — the same RSA-2048
  `CN=Delta` key as every previous release, so it installs straight over 1.0114.02. The
  key lives outside the repo at `~/.android/delta-release.jks` — back it up, losing it
  makes the app un-updatable.
- The unsigned build (`delta-1.0114.03.apk`) is archived beside it for anyone who wants
  to re-sign with their own key.
- R8/minify still disabled — the standing decision documented in `app/build.gradle.kts`.
- **97 unit tests, 0 failures.** The registry tests cover the new action's id and
  round-trip without needing a new case.
