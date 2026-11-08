---
name: evergrow-dev
description: Build, compile and ship the Evergrow fork of Zafiro — Gradle/Chaquopy build,
  GitHub Actions CI, signing keystore, and getting the APK onto the phone. Use this skill
  whenever the task involves Evergrow, building or compiling an APK, running/fixing CI,
  Gradle or Chaquopy errors, the debug keystore, release/install steps, or anything about
  maintaining this Evergrow fork of Zafiro — even if the user only says "build it" or
  "why did CI fail".
---

# Evergrow dev

Evergrow is a personally maintained fork of [Zafiro](https://github.com/niki914/zafiro) by
niki914 (MIT licence). Repo `github.com/donal100/Evergrow`, package
`com.niki914.zafiro.evergrow.debug`, docs at `/storage/emulated/0/Evergrow/docs/`.

The upstream docs are the source of truth. Read `docs/BUILD.md` and `docs/GOTCHAS.md` from the
phone when a detail here looks stale — this skill is their summary, not a replacement.

## Build requirements

- **JDK 17** — the Gradle plugin chain does not accept 11 or 21.
- **Python 3.11** on the build host. Chaquopy's `installDebugPythonRequirements` task fails with
  `Couldn't find Python 3.11`. `ubuntu-latest` ships 3.12, so CI must pin
  `actions/setup-python@v5` with `python-version: '3.11'`. This cost a failed run once; don't skip it.
- Android SDK, Gradle 9.3.1, Kotlin 2.2.0.

## CI

`.github/workflows/build.yml` runs on push to `main` and `feat/**`.

Step order is deliberate: **assemble → upload APK → unit tests**. Tests take ~8 minutes; uploading
first puts the artifact up ~2-3 minutes in instead of gating it behind a long test run.

Debug builds sign with **`ci/debug.keystore`, committed to the repo**, wired explicitly in
`app/build.gradle.kts`:

```kotlin
create("forkDebug") {
    storeFile = rootProject.file("ci/debug.keystore")
    storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
}
```

and `debug { signingConfig = signingConfigs.getByName("forkDebug") }`.

A committed file cannot evict or silently regenerate, which is exactly why it replaced the
cached-keystore approach.

Copy that block character for character. AGP's own default alias is `AndroidDebugKey` with a capital
A and capital D and K, so a hand-written config that "looks normal" tends to capitalise it — and then
it no longer matches the key inside the committed store.

## Shipping the APK to the phone

1. Read the run's artifacts via the GitHub API, download the zip, extract `app-debug.apk`.
2. Copy it to `/storage/emulated/0/Download/`.
3. Trigger a MediaStore scan with
   `MediaScannerConnection.scanFile(ctx, [path], ["application/vnd.android.package-archive"], null)`.
   Skip this and the Files app simply won't list the APK — not a permission problem, an index problem.
4. **Verify the signer before handing the APK over.** Compare the `sha256` of the new APK's signer
   certificate with the installed app's. Equal → in-place update. Different → Android refuses with
   *"App not installed as package conflicts with an existing package"*, and the only fix is an
   uninstall, which destroys the app's private data (skills, memory) and permission grants. Do this
   check before asking a human to tap, not after a failure.
5. Install by tapping the APK in Files → Downloads. There is no silent path: no root and the app
   does not declare `REQUEST_INSTALL_PACKAGES`.

Install gates observed on this device: "Install unknown apps" must be granted to the *file manager*
(not to Evergrow); MIUI shows a "Danger" countdown with an acknowledgement checkbox; and MIUI's
final **Install** button ignores accessibility clicks — it needs a real human tap. Stop and ask the
user at that button instead of retrying.

## Hard rules

**Never move `applicationId` outside `com.niki914.zafiro.*`.** `store/.../XRes.kt` classifies the
running package: anything under `BuildConfig.APPLICATION_ID` is `AppType.Me`, a known list is `Host`,
everything else is `Unknown` → `Transport.Unreachable` → every settings write fails → the app dies
about 1.2 s after launch with `IllegalStateException: Failed to write settings store: tools.py`.
The app installs fine, which is what makes this trap expensive. This fork uses
`com.niki914.zafiro.evergrow`, so debug builds land at `com.niki914.zafiro.evergrow.debug`.

**Keep niki914 credited** in README, LICENSE and the About screen — MIT requires retaining the notice.

**Never let the signing identity drift.** A signing key is not configuration, it is identity. If two
builds of the same package are signed differently, Android refuses the update with *"App not installed
as package conflicts with an existing package"* and the only recovery is uninstalling.

Observed in one session: **four different certificates** across five builds, for the same package,
from the same workflow. Causes, in order of subtlety:

1. `actions/cache` evicting and regenerating `~/.android/debug.keystore`
2. AGP's implicit debug signing ignoring a copied keystore and minting its own
3. A `getPackageArchiveInfo` check that looked right but was never actually run before asking
   a human to install

Cause 3 is the one that gets repeated, because the check exists in the code and reads as correct —
nothing in a passing build tells you it never executed. The tell is a human being asked for an
install tap while no signer comparison was ever printed. The committed `ci/debug.keystore` closes
causes 1 and 2; only running the comparison closes 3, so make it emit its result, not just do it.

## Local gotchas that shape how you work here

- Shell input injection is blocked: the shell runs as an unprivileged app UID, so `input tap` fails
  with `SecurityException: Injecting input events requires INJECT_EVENTS`. Use accessibility node
  taps for GUI work. Key events are inconsistent — BACK worked once, `keyevent 66` was later
  rejected. Never assume a keyevent landed; verify.
- Apps must be re-launched between UI steps, because running a Python tool brings Evergrow's own
  activity to the foreground.
- App label: `app_name` exists in `values/`, `values-en/`, `values-es/`, `values-ja/`, `values-ru/`
  and `values+b+zh+Hant/`. Editing only `values/` changes nothing on an English device because
  `values-en` wins. English is renamed; the other locales still say Zafiro.
- Anything written straight to `/storage/emulated/0/` is invisible to Files and WhatsApp pickers
  until MediaStore is told about it.

## Branch state

`main` carries the fork work merged from `feat/inject-events-preflight`: label, CI plus committed
keystore, the `INJECT_EVENTS_MISSING` handling, docs, and locale labels. `feat/*` branches may be
ahead of `main` — check before assuming what main contains.

## Shipping the APK without breaking it

The rolling release asset is
`github.com/donal100/EverGrow/releases/download/debug-latest/evergrow-debug.apk`
(~62 MB). Release-asset downloads **truncate at roughly 10 MB** here — fetch with
`Range` requests in ~8 MB chunks, then verify `os.path.getsize` against the
`Content-Range` total and `zipfile.testzip()` before staging. Stage to
`/storage/emulated/0/Download/evergrow-debug.apk` and trigger a MediaStore scan.

Verify the signer before asking for an install tap: compare
`getPackageArchiveInfo(apk, GET_SIGNING_CERTIFICATES).signingInfo` against the
installed package's. Equal → in-place update; different → Android refuses with
"package conflicts" and the only fix is an uninstall, which destroys the app's data.

## Working on the code from the phone

- The tree at `files/work/donal100-Evergrow-3184eba` is a snapshot of an **old** commit.
  Pushing a file from it verbatim reverts newer upstream changes. Fetch the current file
  from the branch HEAD via the Contents API, apply only the delta, then push.
- Push with the Git Data API: blob → tree (`base_tree`) → commit → `PATCH ref`. One
  commit per change; CI runs on push to `main`.
- Chaquopy: an exception raised inside a Python→Java callback propagates to the JVM main
  thread and kills the app (looks like "Python worker is not connected"). Wrap every
  callback body in `try/except`.
- Chaquopy: Java `ArrayList`/`HashSet` are not Python-iterable — `list(javaList)` raises
  inside a callback. Use `.iterator()`/`hasNext()` or `.toArray()`.
- Editing a custom Python tool through `py_meta_tools write` does not always take effect
  immediately: a stale module keeps running until the app process restarts. Re-run and
  check for new output to confirm the reload. `py_` tools must **print** their result —
  return values are not surfaced.

## UI constraint worth remembering

The composer's text field gets only the width left after the buttons, so a fourth button
in the compact row squeezes it to zero. Keep it `attach | text | mic | send`.
