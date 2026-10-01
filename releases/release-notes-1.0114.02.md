## Delta 1.0114.02 — the Essential Key becomes a remote

Everything that landed since 1.0114.01: the side key turned into a real, configurable
remote, and the assistant's UI collapsed into a single front end. The full development
record is in
[WHAT_OPENCODE_DID.md](https://github.com/BRL26/delta/blob/main/WHAT_OPENCODE_DID.md).

### Essential Key (the side key)

- **One registry of actions.** Each of the four press types — single, double, triple and
  long press — maps to an action from one list: *Do nothing, Circle to Search, Google
  Lens, Flashlight, Quick settings, Play/pause, Next track, Previous track*. The list is
  the single source of truth: it renders the picker, labels the settings row, and is what
  the key executes against.
- **Open an app… / Open a link…** — point a press at any installed app (opened at its own
  home screen) or at any link: a place, a search, a thread. The target rides inside the
  stored id (`open_app:…`, `open_link:…`), so the prefs, the settings row and the executor
  all read the same one string instead of keeping a second table in sync.
- **A picker that matches the app.** The action dialog is a themed Compose sheet with a
  description under every action, replacing the old system `AlertDialog` list. Three
  steps — the catalogue, *Which app?*, *Which link?* — and the system back key now walks
  up a step instead of closing the dialog and throwing the choice away.
- **Test it** runs the highlighted action without pressing the key. Chosen app and link
  mappings are testable too, and flashlight / quick settings / media keys run through the
  very executor the key uses, so a test can't drift from what the press would do.
- **Snap-and-schedule** on a single press, with a Snaps tab. Circle to Search is driven
  through AKS' own trigger window; Google Lens (capture the frame, hand it to Lens) stays
  as the fallback that needs nothing but the Google app.
- **A press that fails stays silent.** Failures are logged under `SideKey` rather than
  posted as a "Side key did not run" notification on a screen you didn't ask about.

### Assistant and UI

- **The popup is the single owner of a conversation.** The floating overlay system —
  status pill, TTS captions — is deleted, along with the legacy assistant UI. One front
  end, not two: nothing keeps talking behind a popup that has already closed.
- **Home and Settings rebuilt** in the assistant's own design language, so they read as
  the same product as the pill.
- Assistant chat **scrolls to the newest message**; the assistant stops hearing its own
  replies and no longer survives the screen it was started on; camera-open routing and
  TTS echo fixed; three layouts nothing referenced deleted.

### Release engineering

- `versionCode 115 / versionName 1.0114.02`, produced by the project's auto-increment
  task. The task now writes the patch with its leading zero (`1.0114.02`, not
  `1.0114.2`), so every version in the list looks like the one before it.
- **Primary APK is release-signed**: `delta-1.0114.02-signed.apk` — the same RSA-2048
  `CN=Delta` key as 1.0114.01, so it installs straight over the previous release. The key
  lives outside the repo at `~/.android/delta-release.jks` — back it up, losing it makes
  the app un-updatable.
- The unsigned build (`delta-1.0114.02.apk`) is archived beside it for anyone who wants
  to re-sign with their own key.
- R8/minify still disabled — the standing decision documented in `app/build.gradle.kts`.
- **97 unit tests, 0 failures** (59 at 1.0114.01). The new `SideKeyTargetsTest` pins the
  encoded-id contract that the prefs, the registry and the executor all depend on.
