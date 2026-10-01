# 🔺 Delta: Your AI Phone Operator

**You touch grass. I'll touch your glass.**

Delta is a proactive, on-device AI agent for Android. It understands natural-language
voice commands and operates your phone's UI to achieve them — tapping, swiping and
typing across apps the way a human operator would. It runs your assistant experience
as the system-level voice interaction service, so it can take over from the long-press
power gesture, a launcher shortcut, a widget, or a scheduled trigger.

Delta is a heavily-reworked fork of **Blurr** (formerly *Panda*), rebuilt around a
hands-free conversational assistant, a redesigned overlay, and — as of this release —
a clean **delta** identity.

> **Application id:** `com.brl.blurrmbtg` · **Namespace:** `com.blurr.voice` (kept from the
> upstream project so every manifest component stays source-compatible — see the note in
> `app/build.gradle.kts`).

---

## What's new in v1.0114.02

- **🔺 The Essential Key is a real remote.** All four press types — single, double,
  triple and long press — map to an action from one registry: Circle to Search, Google
  Lens, **flashlight**, **quick settings**, **play/pause and track skipping**, or *open
  any app* / *open any link* you pick. The picker is a themed sheet with a description
  under every action and a **Test it** button that runs the choice before you save it.
- **One front end for the assistant.** The popup is now the only owner of a
  conversation; the floating overlay system and the legacy assistant UI are deleted, so
  nothing keeps talking behind a window that has already closed.
- **Home and Settings rebuilt** in the assistant's own design language — the three
  surfaces finally read as one product.
- **Quieter failures.** A press that cannot run logs under `SideKey` instead of posting
  a notification on a screen you did not ask about.

---

## What makes this build different

**v1.0114.01** was the work of several long development sessions with
[OpenCode](https://opencode.ai), an AI coding agent, and **v1.0114.02** continues that
same record. The full, file-by-file account is in
**[WHAT_OPENCODE_DID.md](WHAT_OPENCODE_DID.md)**. The headline changes:

- **🕐 Mic stays open while you talk.** The voice assistant keeps the microphone
  recording across turns instead of re-prompting you to press the mic for every
  message. A message is sent automatically after **3 seconds of silence**, so a
  conversation sounds natural.
- **🔇 No more listening chimes.** Each recording session used to play a confirmation
  tone and the session close played another. Both are gone — the streams that carry
  them are muted for the whole mic-open window, then restored with a short delay so the
  closing chime is swallowed too.
- **⏹️ Auto-quit when no one is talking.** If the assistant hears nothing **three times
  in a row (≈9 seconds)** it stops listening and closes the overlay by itself.
- **🔺 Full rebrand to Delta.** New launcher icon (a delta triangle), app name, themes,
  and strings. The old **Panda** wake word (and its proprietary engine) was removed
  entirely.
- **🔕 Quiet assistant notification.** The foreground-service notification now reads
  *"Delta — Assistant is active"* with a silent, low-importance channel. It no longer
  claims to be listening for a command (an always-on assistant notification is required
  by the platform, but it no longer shouts).

---

## Installation

### The release APK

Release APKs are attached to each [GitHub Release](../../releases) under `releases/`:

```
releases/delta-1.0114.02-signed.apk   ← signed with Delta's release key — install this one
releases/delta-1.0114.02.apk          ← unsigned build, archived for re-signing
```

**`delta-1.0114.02-signed.apk` is the installable artifact.** It is signed with Delta's
release key (unminified, R8 off) and can be installed directly:

```bash
adb install -r delta-1.0114.02-signed.apk
```

The release key lives at `~/.android/delta-release.jks` (password in
`~/.android/delta-release.pass`), **outside** this repository. Back it up — without it,
future releases cannot be signed.

If you'd rather build and sign from source, the **debug** APK
(`./gradlew :app:assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`) is signed
with the debug key and installs the same way.

R8/minification is deliberately **disabled** for this release (see the build.gradle.kts
comment for why) — the trade-off is a larger APK in exchange for a release build that
doesn't secretly break reflection-based code paths.

### From source

1. Clone the repository.
2. Open the project in Android Studio and let Gradle sync.
3. Add any API keys you want to use (Gemini, Tavily, MEM0, Google TTS — see
   `local.properties.template`) in `local.properties`.
4. Build & run on your device:
   ```bash
   ./gradlew :app:installDebug
   ```
5. On first run, grant the app accessibility permission, then set it as your default
   **digital assistant app** (Settings → Apps → Default apps → Digital assistant) so
   the long-press power gesture opens Delta.

---

## Core capabilities

* 🧰 **Tools-first agent** — before touching the screen, Delta checks its tool kit: open
  any installed app, launch OS intents (timers, alarms, reminders, dialer, share, links,
  email), search the web, read/write its file system, view file listings, read your
  notifications, and report battery/screen/network state. Accessibility screen control is
  used only when no tool fits the task. The decision protocol is baked into the system
  prompt, and the tool catalog is the same single source of truth the LLM sees.
* 🧠 **Intelligent UI automation (fallback)** — only for UI that no tool can reach (in-app
  forms, third-party flows): Delta reads the screen, understands the UI layout, and taps,
  swipes, and types to complete multi-step tasks.
* 📢 **Voice-first conversation** — talk to it like an operator; it listens, acts, and
  speaks back. No tap-to-talk needed.
* 🔑 **Essential Key** — the Nothing side key becomes a configurable remote: four press
  types, each mapped to an action from one list (search the screen with Circle to Search
  or Lens, toggle the flashlight, open quick settings, control playback, or open a
  chosen app or link), with a Test button that runs the choice before you save it.
* ⏰ **Triggers** — start tasks from a scheduled time, an incoming notification, a
  launcher shortcut, a widget, or the assistant gesture.
* 💾 **Local memory** — ⚠️ currently disabled (see `docs/MEMORY_STATUS.md`).

---

## Documentation

Subsystem notes live in [`docs/`](docs/): the trigger system, speech coordination, STT
implementations, accessibility disclosure, direct app opening, and more. The complete
development record of this release is in
**[WHAT_OPENCODE_DID.md](WHAT_OPENCODE_DID.md)**.

---

## Building

* **Kotlin** 1.9.22, **AGP** 8.9.2, min SDK 24, target SDK 35.
* `./gradlew :app:assembleDebug` — debug APK (installable).
* `./gradlew :app:assembleRelease` — release APK, unminified, **signed automatically**
  when the `DELTA_RELEASE_*` keys are set in `local.properties` (see the template);
  falls back to unsigned with a warning otherwise.
* `./gradlew :app:testDebugUnitTest` — unit tests (97 passing).

---

## License

[Personal Use License](LICENSE) — free for personal, educational, and non-commercial
use. Commercial use requires a separate license.