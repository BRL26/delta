# releases/

Official release artifacts.

| File | Version | Notes |
|---|---|---|
| `delta-1.0114.01.apk` | 1.0114.01 (versionCode 114) | Release build — **unsigned**, unminified (R8 off). See `README.md` → Installation. |
| `SHA256SUMS` | — | Checksums for every artifact in this folder. |

Verify with:

```bash
sha256sum -c SHA256SUMS
```

## About the signature

The release APK is deliberately **unsigned**. An unsigned APK cannot be installed on a
stock device; to sideload it, either open the project in Android Studio and sign it
(`Build → Generate Signed App Bundle / APK`), or use the debug APK
(`app/build/outputs/apk/debug/app-debug.apk`) which is signed with the debug key.

Every GitHub Release also attaches the APK as a release asset, so it can be downloaded
without cloning the repository.