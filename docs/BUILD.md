# Building and shipping

## Requirements (undocumented upstream — this cost us a failed CI run)

- **JDK 17**
- **Python 3.11** on the build host. Chaquopy's `installDebugPythonRequirements` task fails with
  `Couldn't find Python 3.11` without it. `ubuntu-latest` ships 3.12, so `actions/setup-python@v5`
  with `python-version: '3.11'` is mandatory.
- Android SDK (preinstalled on GitHub-hosted runners). Gradle 9.3.1, Kotlin 2.2.0.

## CI

`.github/workflows/build.yml`, runs on push to `main` and `feat/**`.

Order matters: **assemble → upload APK → unit tests.** Tests take ~8 min and used to gate the
artifact; upload first so the APK is available ~2-3 min in.

## Signing identity — never let this drift

Debug builds sign with **`ci/debug.keystore`, committed to the repo**, wired explicitly in
`app/build.gradle.kts`:

```kotlin
create("forkDebug") {
    storeFile = rootProject.file("ci/debug.keystore")
    storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
}
```

and `debug { signingConfig = signingConfigs.getByName("forkDebug") }`.

Two approaches that **do not work**, both tried and both failed with
"App not installed as package conflicts with an existing package":

- `actions/cache` for `~/.android/debug.keystore` — caches evict, and a miss silently
  generates a new key.
- Relying on AGP's implicit debug signing via a copied `~/.android/debug.keystore` —
  three consecutive builds produced three different certificates.

Verify before shipping an update: compare `sha256` of the signer certificate of the new APK
with the installed one. Equal → in-place update. Different → Android will refuse, and only an
uninstall fixes it (losing app data and permission grants).

## Getting the APK onto the phone

1. Read the run's artifacts via the GitHub API, download the zip, extract `app-debug.apk`.
2. Copy to `/storage/emulated/0/Download/`.
3. **MediaStore scan is required** or the Files app will not list it:
   `MediaScannerConnection.scanFile(ctx, [path], ["application/vnd.android.package-archive"], null)`
4. Install: Files → Downloads → tap the APK. There is no silent install path — no root, and the
   app does not declare REQUEST_INSTALL_PACKAGES.

## Install gates on this device (all observed)

- "Install unknown apps" must be granted to the *file manager*, not to your app.
- MIUI shows a "Danger" dialog with a countdown and an acknowledgement checkbox.
- MIUI's final **Install** button does **not** accept accessibility clicks — only a real tap.
