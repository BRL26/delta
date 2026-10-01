# What OpenCode did — the full record

This file is the complete, file-level account of the work done by **OpenCode** (an AI
coding agent) on this project across several development sessions. It covers everything
that went into **v1.0114.01** — including the wrong turns, because a faithful record is
more useful than a flattering one.

Sessions worked as a collaboration with the project owner: OpenCode wrote code, built it,
installed it on a physical phone, investigated behaviour with live logcat/dumpsys while
the owner tested, and iterate until each behaviour matched what was asked.

> **Note on §9.** Sections 1–8 cover v1.0114.01. Section 9 covers **v1.0114.02**, which
> began as uncommitted changes in an OpenCode session and was finished by a follow-up
> session with a different agent (Buffy, of Freebuff) working with the same owner. It is
> recorded here because this file is the project's development record, not a record of
> one tool — and because the state it was found in is part of the story.

---

## 1. Voice assistant changes (`VoiceInputController`, `AssistantChatScreen`)

### 1.1 Mic keep-alive (only in the big overlay)

**Ask:** *"keep the mic open instead of turning it off and on when a new message is
sent, but only when I'm using the big overlay, not the pill."*

Before, every message turn destroyed and re-created the speech recogniser session — a
tone, a re-tap on the mic icon, and a pause between turns. Now:

- The recogniser keeps listening across turns for as long as the assistant overlay is
  the active voice session.
- The behaviour is gated on the **large overlay** being open. When the assistant is
  collapsed into the pill, the mic is released (`LaunchedEffect(collapsed)` in
  `AssistantChatScreen.kt` calls `voice.stop()`), so the pill never holds the mic.

### 1.2 Auto-send after 3 seconds of silence

**Ask:** *"make the wait time before automatically sending a message from voice a bit
longer, like 3 seconds of inactivity."*

When the user stops speaking, the partially-transcribed text is automatically sent after
a **3-second inactivity debounce** instead of an immediate send on the recogniser's
final partial result.

### 1.3 Auto-quit after 3 hearings of nothing (9 seconds)

**Ask:** *"if it hears nothing 3 times (9s total), then it should quit the app."*

The recogniser's `ERROR_NO_MATCH` / `ERROR_SPEECH_TIMEOUT` results are counted
consecutively. Any real speech resets the counter. After **3 consecutive silent
listens** the assistant emits `gaveUp`, which:

1. Stops the conversation context (`AssistantInput.stop(context)`), and
2. Closes the assistant session (`SessionBridge.requestClose()`).

The overlay closes itself and the app returns to idle — no tap needed.

---

## 2. The listening-sound fix (`VoiceInputController`, `AndroidManifest.xml`)

**Ask:** *"make the listening and not listening not make sound."*

This one took the longest because the sounds were *not* produced by the app at all, and
they were actually **two different sounds from two different sources**:

| | Source (proven from logcat/dumpsys, not guessed) |
|---|---|
| **Start tone** | The Google speech engine (`com.google.android.tts`, the phone's recognition service) plays a confirmation tone ~300 ms after every `onStartListening`. The tone's `AudioTrack` declares legacy `streamType = 5` (`STREAM_NOTIFICATION`, usage `USAGE_ASSISTANCE_SONIFICATION`). No `RecognizerIntent` extra disables it. |
| **End tone** | A closing chime played *after* the session ends — invisible to AudioTrack logs, produced via an OEM `SoundPool` path (system_server / SystemUI). It plays out of the session window, into the "gap" after teardown. |

Because the mic keep-alive re-asked the engine every ~3 seconds, the start tone would
have fired repeatedly for the whole conversation — the fix had to cover the whole
mic-open window, not once per session start.

### Wrong turns (recorded for honesty)

1. **The notification-channel theory.** The notification's `mSound` still resolved to
   `content://settings/system/notification_sound` even after `deleteNotificationChannel`
   + recreate with `setSound(null)` — Android restores the default sound on recreate and
   duplicate names are ignored anyway. Dead end, and moot: the sound is not a
   notification.
2. **`STREAM_SYSTEM` from the attribute table.** `dumpsys audio` showed a
   `forVolume:true stream: STREAM_SYSTEM(1)` line that looked like the live player. It
   was actually a static attribute→stream *mapping cache* (`mDevicesForAttrCache`), not
   an active player. Muting `STREAM_SYSTEM` alone changed nothing audible.

### The fix

- Mute **both `STREAM_NOTIFICATION` and `STREAM_SYSTEM` for the whole mic-open window**
  (`silenceRecognitionTone` runs on `start()`).
- A **1,500 ms-delayed unmute** (`ToneRestoreDelayMillis`) that also swallows the
  closing chime, which plays *after* `onStopListening`. A brand-new session cancels the
  pending restore, so back-to-back turns never leave a beep gap.
- Every path that can close the mic funnels through one new `endKeepAlive()` function
  (start/stop/cancel/release/give-up/error) — previously four near-identical preambles,
  the exact shape where one path forgets a line and strands the mute. `endKeepAlive()`
  restores exactly the streams that were muted.
- `ConversationalAgentService.onDestroy` is a backstop: if the process is torn down
  with the mic open, the streams still get restored.
- `MODIFY_AUDIO_SETTINGS` was added to the manifest (it was missing).

### Known trade-off

`STREAM_NOTIFICATION` and `STREAM_SYSTEM` also carry notification sounds, key presses,
lock-screen and volume feedback. Android has no per-usage volume, so those go quiet
while the assistant mic is open (a few seconds at a time — see the 3× silence auto-quit
above). This was accepted explicitly; the alternative (a different recognition path)
was deliberately not pursued for this release.

Residual risk: no persisted "was muted" flag on disk. If the process is killed outright
instead of going through cleanup paths, a mute could survive — a stored flag agreeing
with reality is worse, so it was left out.

---

## 3. Delta rebrand

**Ask:** *"change the name and icon into delta."*

### 3.1 Sweep

`panda` → `delta` renamed repo-wide: file names, classes, strings, launcher labels,
drawables. The app now presents as **Delta**.

### 3.2 Wake word removed entirely

The wake word detector was **deleted**, not renamed: the Picovoice `porcupine`/`panda`
engine is a compiled `.ppn` binary that cannot be relabelled safely, and running it would
keep handing out the dead name. Removed: the wake-word source, the dependency, the
manifest service, proguard rules, the `panda` asset, and its SDK key plumbing.

### 3.3 Launcher icon

- `ic_launcher_foreground.xml` rewritten as the **delta triangle** vector — a hollow
  equilateral triangle whose geometry was verified by measuring rendered pixels
  (horizontally exact; nudged **up 4 units** for optical centring, because a hollow
  triangle's visual mass sits ~6% below its bounding box).
- Legacy `mipmap-*/ic_launcher*.webp` for API 24–25 regenerated from
  `tools/render_delta_icon.py` so all densities match the adaptive icon.
- Onboarding illustraton swapped from the panda PNG to the vector
  `drawable/delta_logo.xml`; the old `delta_logo.png` / `delta_logo_v1_512.png` deleted.

### 3.4 Open-source / old-repo leak removal

Every mention of the old repo (GitHub URLs, DeepWiki badge, star-history charts, Discord
links) and of the project being "open source" was hunted down and removed — including
one leak in a **spoken** string (`accessibility_permission_needed_for_task`, TTS output
that said "my code is open source"), which a text-only grep initially missed.

### 3.5 Final branding sweep (this release)

The last stragglers were caught just before publishing:

- `Theme.Blurr` → `Theme.Delta` (themes.xml ×2, manifest ×17 refs, `OverlayManager`,
  `VisualFeedbackManager`).
- `WAKE_UP_PANDA` → `WAKE_UP_DELTA` (shortcuts.xml, `MainActivity`, `DeltaWidgetProvider`).
- `microphone_permission_desc` no longer promises a "wake-word" feature the app no
  longer has; stale `wake word` references in comments and `docs/` removed.
- Stale docs deleted: `docs/PORCUPINE_SETUP.md`, `docs/WAKE_WORD_BUTTON_RELOCATION.md`,
  the wake-word UI screenshot, and `PandaNotificationListenerService` references updated
  to the real class `DeltaNotificationListenerService`.

---

## 4. Notification behaviour

**Ask:** *"don't show a persistent notification that it's listening for my command."*

- The foreground-service notification now reads **"Delta — Assistant is active"** and
  no longer claims to be listening for a command.
- Channel importance lowered (`IMPORTANCE_LOW`, silent) — the channel that governs it.
  The second/default channel was silenced the same way.
- Android requires a foreground-service notification whenever the service runs; that
  cannot be removed without losing the service, so the requirement was stated to the
  owner and the notification was made as quiet as the platform allows.

---

## 5. Release engineering (v1.0114.01)

- `version.properties`: `VERSION_CODE=114`, `VERSION_NAME=1.0114.01`.
- **R8/minify disabled for release** — a standing project decision ("never R8 builds").
  The release build type keeps a commented explanation in `app/build.gradle.kts`: with
  reflection-heavy paths (action dispatchers, the session UI, Firebase), an unverifiable
  release build is the one artifact handed to other people, so a bigger APK was chosen
  over a subtly-broken one.
- The release APK initially shipped **unsigned by choice** (owner's decision), and the
  owner then asked for a signed artifact. A **release signing key** was generated
  (RSA-2048, self-signed 20,000-day certificate, `CN=Delta`) and the final APK signed
  with it — `releases/delta-1.0114.01-signed.apk`. The key
  (`~/.android/delta-release.jks`) and its password file live **outside** this
  repository and must be backed up; losing them would make the app un-updatable.
- Release build completed successfully (`:app:assembleRelease`, version pinned with
  `-x incrementVersion`); the APK was verified against the manifest:
  `versionCode=114`, `versionName=1.0114.01`, label `delta`,
  package `com.brl.blurrmbtg`.
- Growing out of that, release signing was later **wired into Gradle**: `assembleRelease`
  now signs automatically when `DELTA_RELEASE_*` properties are set in the gitignored
  `local.properties` (keystore path + passwords, never committed), and degrades to an
  unsigned build with a loud warning when they are absent.
- Debug build installed and accepted on the physical device; assistant role held by
  `com.brl.blurrmbtg`.

---

## 6. Tests

`./gradlew :app:testDebugUnitTest` — **59 tests / 0 failures** across
`ActionParsingTest`, `ConversationalAgentServiceTest`, `ExampleUnitTest`, `PlainTextTest`,
`ReminderTimeParserTest`, `SessionCollapseModeTest`, `VersionManagementTest`.

Two pre-existing test files do **not** compile on the project's `main` branch, broken
*before* this work and unrelated to it: `SystemPromptTest.kt` (imports a
`com.blurr.voice.prompts` package that no longer exists) and `DeltaStateManagerTest.kt`
(uses Mockito, which is not a dependency). They were temporarily stashed to run the
suite, then restored untouched.

---

## 7. Repository history

The new repository **(`delta`)** was created as a **fresh, single-commit history** —
deliberately. The old repository's URL (`Ayush0Chaudhary/blurr`) and every
pre-rebrand "Panda" commit are baked into the old git history, and pushing that history
would permanently relink this project to the branding this release exists to distance
itself from. No trace of the old repo remains in the new history.

---

## 8. Tools-first agent (follow-up feature)

Highlights:
- **System prompt rewritten** (`assets/prompts/system_prompt.md`): the agent now follows
  a mandatory `<tool_selection_protocol>` — pick an OS-level tool first, and only fall
  back to accessibility screen control when no tool fits. Screen rules are retitled
  `<screen_control_rules>` and explicitly gated behind the protocol. `<android_state>`
  is now described as fallback context, and the output examples show tool-only turns.
- **Three new tools**, added to the single source of truth in `Action.kt` and wired into
  `ActionExecutor`:
  - `notifications` — reads the active notification shade from a live snapshot kept by
    `DeltaNotificationListenerService` (new companion `current`, populated in
    `onListenerConnected` / `onNotificationPosted` / `onNotificationRemoved`). No screen,
    no app opening. Requires notification access, which is requested from the Triggers
    screen.
  - `list_files` — lists the agent workspace before `read_file`, so the model stops
    guessing filenames.
  - `device_state` — battery level/charging, screen on/off, connectivity read through
    system services (needs the new `ACCESS_NETWORK_STATE` normal permission).
- **File system** widened from `(md|txt)` to `(md|txt|json|csv|log)` so saved reports can
  be anything the agent can read back.
- Also: the status pill says "Working with tools" instead of "Looking at the screen",
  and `ActionParsingTest.kt` pins the empty-`{}` argument parsing the tool actions rely
  on (59 tests total, all green).

---

## 9. Essential Key: the action registry, chosen targets, and the Compose picker (v1.0114.02)

**State it was found in:** uncommitted changes across six files plus three new files,
and a build that did not compile (`SettingsActivity` called `SideKeyActionRegistry` with
no import). The intent was clear from the code -- the picker had been rewired to Compose,
three actions had been added to the registry, and app/link targets had been encoded into
the ids -- but none of it had ever been compiled, tested, or run on a phone.

### What it became

- **Five new actions** in the registry: `toggle_flashlight`, `open_quick_settings`,
  `media_play_pause`, `media_next`, `media_previous`, grouped after the screen-reading
  ones the way a hand reaches for them. They live as data, so the press rows, the picker
  and the prefs pick them up without knowing they exist; only
  [`SideKeyActionExecutor`](app/src/main/java/com/blurr/voice/sidekey/SideKeyActionExecutor.kt)
  gained arms, plus [`SideKeyShortcuts`](app/src/main/java/com/blurr/voice/sidekey/SideKeyShortcuts.kt)
  for the platform work each one actually needs (torch state has no getter on Android, so
  the last known state is kept; media keys must be sent as full down/up pairs or the
  player stops responding).
- **Chosen targets as ids.** `open_app:com.example` / `open_link:geo:0,0?q=coffee` keep
  the target inside the one string prefs already store, so there is no second table to
  keep in sync: [`SideKeyTargets`](app/src/main/java/com/blurr/voice/sidekey/SideKeyTargets.kt)
  parses, labels and launches it, the registry resolves it to a spec instead of degrading
  it to "do nothing", and the executor matches it by shape before anything else.
- **A three-step Compose picker** (`ui/sidekey/SideKeyActionDialog.kt`): the catalogue,
  *Which app?*, *Which link?* -- replacing an `AlertDialog` over a `ListView`, the one
  screen in the app still wearing the system's look.
- **Silent failures.** The "Side key did not run" notification is gone with its channel:
  a press that fails now writes one `Log.w` line.

### Bugs found while finishing it, and what was done

1. **It did not compile.** Missing `SideKeyActionRegistry` import in `SettingsActivity.kt`.
2. **"Test it" claimed it could not test what it could test.** The button was enabled for
   every row, but only knew *Do nothing*, *Circle to Search* and *Lens* -- so testing an
   existing app mapping toasted *"No test available for Open app."* It now takes the
   stored id: chosen targets are launched directly (they need no accessibility service),
   and the new fixed actions run through `SideKeyActionExecutor`, so a test cannot drift
   from what the key would do.
3. **`isKnown` answered `false` for "do nothing"** -- `specFor(id).id != NONE_ID` makes
   the question answer itself, contradicting the doc above it. It now reads the catalogue.
   Pinned by a test, which is how it was caught.
4. **The system back key threw the choice away.** From *Which app?* or *Which link?*, back
   dismissed the whole dialog instead of returning to the list -- and the window sees the
   back key before anything in Compose does, so no `BackHandler` could have helped. The
   step now lives outside the composition (`SideKeyPickerNav`) and the dialog's own key
   listener answers it: go back a step when there is one, close when there isn't.
5. **"Looking for apps…" could show forever.** An empty list was indistinguishable from
   one still being fetched. `null` now means *not asked for yet*.
6. **`incrementVersion` dropped the patch's leading zero**, which would have made this
   release `1.0114.2` next to `1.0114.01`. The patch is now written with two digits.
7. Dead code and rough edges: `SideKeyNotifications.kt` deleted (its last caller had gone
   earlier in the session), `Sheet`'s unused `onDismiss` parameter removed, three missing
   end-of-file newlines restored.

### Verification

- `:app:compileDebugKotlin`, `:app:assembleDebug`, `:app:assembleRelease` all green.
- `:app:testDebugUnitTest` -- **97 tests, 0 failures** (59 at v1.0114.01). New
  `SideKeyTargetsTest` pins the encoded-id contract that prefs, registry and executor
  all depend on.
- Debug build installed and run on the physical device, accessibility service bound.

### Release (v1.0114.02)

- `version.properties`: `VERSION_CODE=115`, `VERSION_NAME=1.0114.02`, produced by the
  project's own `incrementVersion` task (now zero-padding the patch) rather than pinned
  by hand as 1.0114.01 was.
- `releases/delta-1.0114.02-signed.apk` signed with the **same release key** as
  1.0114.01 -- verified by certificate digest -- so it installs as an update. The
  unsigned build is archived beside it, `SHA256SUMS` covers all four artifacts, and
  `releases/release-notes-1.0114.02.md` is what the GitHub Release ships as its notes.
- Signed build installed on the device: `versionCode=115`, `versionName=1.0114.02`,
  accessibility service re-enabled and bound, no crashes in logcat.

---

*Written by OpenCode and the project owner, September 2026. Section 9 added by a
follow-up session with Buffy (Freebuff) and the project owner.*