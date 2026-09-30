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

## What makes this build different

This release (**v1.0114.01**) is the work of several long development sessions with
[OpenCode](https://opencode.ai), an AI coding agent. The full, file-by-file account is
in **[WHAT_OPENCODE_DID.md](WHAT_OPENCODE_DID.md)**. The headline changes:

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

A release APK is attached to each
[GitHub Release](../../releases) under `releases/`:

```
releases/delta-1.0114.01.apk
```

> ⚠️ **The release APK is shipped unsigned.** That was a deliberate choice for this
> release. An unsigned APK cannot be installed on a normal device. If you want to
> sideload it:
>
> 1. Open the app in Android Studio.
> 2. `Build → Generate Signed App Bundle / APK → APK`.
> 3. Use your own release key (or the debug key from `~/.android/debug.keystore` for a
>    quick test).
>
> The **debug** APK (`assembleDebug`) is signed with the debug key and can be installed
> directly with `adb install -r app/build/outputs/apk/debug/app-debug.apk`.

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

* 🧠 **Intelligent UI automation** — Delta reads the screen, understands the UI layout,
  and taps, swipes, and types to complete multi-step tasks across apps.
* 📢 **Voice-first conversation** — talk to it like an operator; it listens, acts, and
  speaks back. No tap-to-talk needed.
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
* `./gradlew :app:assembleRelease` — release APK (unsigned, unminified).
* `./gradlew :app:testDebugUnitTest` — unit tests (56 passing).

---

## License

[Personal Use License](LICENSE) — free for personal, educational, and non-commercial
use. Commercial use requires a separate license.