# releases/

Official release artifacts.

| File | Version | Notes |
|---|---|---|
| `delta-1.0114.01-signed.apk` | 1.0114.01 (versionCode 114) | **Primary artifact — release-signed** (RSA-2048, self-signed 20,000-day cert, CN=Delta). Installs directly on Android. Unminified (R8 off). |
| `delta-1.0114.01.apk` | 1.0114.01 (versionCode 114) | Unsigned release build, kept for archive/re-signing. |
| `SHA256SUMS` | — | Checksums for every artifact in this folder. |

Verify with:

```bash
sha256sum -c SHA256SUMS
```

## About the signature

- `delta-1.0114.01-signed.apk` is signed with Delta's **release key**, generated at the
  1.0114.01 release. Install it with `adb install` or by tapping the APK on the device.
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