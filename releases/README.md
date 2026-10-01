# releases/

Official release artifacts.

| File | Version | Notes |
|---|---|---|
| `delta-1.0114.03-signed.apk` | 1.0114.03 (versionCode 116) | **Primary artifact — release-signed** (RSA-2048, self-signed 20,000-day cert, CN=Delta). Installs directly on Android, and over 1.0114.02 without data loss. Unminified (R8 off). |
| `delta-1.0114.03.apk` | 1.0114.03 (versionCode 116) | Unsigned release build, kept for archive/re-signing. |
| `delta-1.0114.02-signed.apk` | 1.0114.02 (versionCode 115) | Previous release, signed. |
| `delta-1.0114.02.apk` | 1.0114.02 (versionCode 115) | Previous release, unsigned. |
| `delta-1.0114.01-signed.apk` | 1.0114.01 (versionCode 114) | Older release, signed. |
| `delta-1.0114.01.apk` | 1.0114.01 (versionCode 114) | Older release, unsigned. |
| `SHA256SUMS` | — | Checksums for every artifact in this folder. |

Verify with:

```bash
sha256sum -c SHA256SUMS
```

## About the signature

- `delta-1.0114.03-signed.apk` is signed with Delta's **release key**, the same key
  generated at the 1.0114.01 release — which is why it installs as an update rather than
  asking to be uninstalled first. Install it with `adb install` or by tapping the APK on
  the device.
- The release key itself **must be backed up**: `~/.android/delta-release.jks` and its
  companion password file `~/.android/delta-release.pass`. Lose the key and every future
  update of this app becomes impossible to sign. Keep both in a safe, private place —
  they live **outside** this repository and are never committed.
- `assembleRelease` signs automatically: it reads `DELTA_RELEASE_KEYSTORE` /
  `DELTA_RELEASE_KEYSTORE_PASSWORD` (etc.) from the gitignored `local.properties`
  (see `local.properties.template`). With those unset, the build degrades to unsigned
  and prints a warning.
- The unsigned APK is kept for archival and re-signing by anyone who wants their own key.

Every GitHub Release also attaches the APK(s) as release assets, so they can be
downloaded without cloning the repository.
