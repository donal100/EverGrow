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

## Audio mode writes are silently discarded without MODIFY_AUDIO_SETTINGS

Without `<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />` in the manifest,
every audio-routing call fails *quietly*:

```kotlin
am.setMode(AudioManager.MODE_IN_COMMUNICATION)
am.getMode()            // 0 — not 3
am.setSpeakerphoneOn(true)
am.isSpeakerphoneOn()   // false
```

Reads work, writes vanish, no exception, no log line. It is a *normal* permission, so declaring it is
enough — no runtime prompt. `BLUETOOTH_CONNECT` is separately required (runtime, Android 12+) before
`startBluetoothSco()` will do anything.

## Never set isBluetoothScoOn by hand

`setBluetoothScoOn(true)` only lights a flag; it does not create a link. If no SCO device exists, an
`AudioRecord` created afterwards is routed to a channel that is not there and yields pure silence,
while the recogniser reports `error=7` (`NO_MATCH`) in a loop. The user experience is "it hears
nothing at all", which sounds like a broken microphone.

Wait for a real `TYPE_BLUETOOTH_SCO` / BLE-headset **input** device in
`getDevices(GET_DEVICES_INPUTS)` and only then enter `MODE_IN_COMMUNICATION`; otherwise stay in
`NORMAL` and keep the phone microphone.

## Log the value you read back, not the intent

A log line written as `log("mode=IN_COMMUNICATION")` after `setMode(...)` is a lie waiting to happen —
it reports what the code asked for, not what Android did. Two hours were spent trusting that line while
the mode was still `0`. Always log `getMode()`, `isBluetoothScoOn()` and the device list.
