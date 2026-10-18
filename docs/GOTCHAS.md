# Gotchas — learned the hard way

## Package identity is load-bearing

`store/.../XRes.kt`:

```kotlin
fun getAppTypeOf(context: Context): AppType = when {
    context.packageName in appList -> AppType.Host
    context.packageName == BuildConfig.APPLICATION_ID ||
    context.packageName.startsWith("${BuildConfig.APPLICATION_ID}.") -> AppType.Me
    else -> AppType.Unknown
}
```

`Unknown` → `Transport.Unreachable` → every settings write fails →
`IllegalStateException: Failed to write settings store: tools.py` and the app dies at ~1.2 s.

**Rule: keep `applicationId` inside `com.niki914.zafiro.*`.** This fork uses
`com.niki914.zafiro.evergrow` so debug builds become `com.niki914.zafiro.evergrow.debug`.
A fully foreign package (e.g. `com.donal100.evergrow`) installs fine but **crashes on launch**.

## App label needs every locale

`app_name` is defined in `values/`, `values-en/`, `values-es/`, `values-ja/`, `values-ru/`,
`values-b+zh+Hant/`. Editing only `values/` shows no change on an English device — `values-en`
wins. English is done; the others still say Zafiro.

## Input injection is blocked

The shell runs as an unprivileged app UID: `input tap` fails with
`SecurityException: Injecting input events requires INJECT_EVENTS`. Use accessibility node taps.
Key events are inconsistent — BACK worked once, `keyevent 66` (Enter) was rejected later.
Never assume a keyevent will land.

## Tools steal focus

Running a Python tool brings your own activity to the foreground, so a target app must be
re-launched before the next UI step. This is why multi-step GUI flows break mid-way.

## Accessibility can't click everything

MIUI installer confirmation buttons and some vendor dialogs ignore accessibility clicks and fall
back to shell injection, which then fails. Those need a human tap.

## File writes need indexing

Anything written straight to `/storage/emulated/0/` is invisible to Files/WhatsApp pickers until
MediaStore is told about it.

## Signing identity is the most expensive thing to get wrong

A signing key is not configuration, it is identity. If two builds of the same package are
signed differently, Android refuses the update with *"App not installed as package conflicts
with an existing package"* and the only recovery is uninstalling — which silently destroys the
app's private data (skills, memory) and its permission grants.

Observed in one session: **four different certificates** across five builds, for the same
package, from the same workflow. Causes, in order of subtlety:

1. `actions/cache` evicting and regenerating `~/.android/debug.keystore`
2. AGP's implicit debug signing ignoring a copied keystore and minting its own
3. A `getPackageArchiveInfo` check that looked right but was never actually run before asking
   a human to install

Always compare the signer cert of the APK you are about to hand over with the installed app's
signer, *before* asking for the tap. That check is cheap and it is the difference between one
install and three.
