---
name: evergrow-voice
description: How Evergrow's voice stack actually works — listen mode, call mode and SCO, echo windows, barge-in, realtime translate, the Qwen speech tools and the double-voice guard. Load when debugging listening that goes deaf or hears itself, when speech sounds wrong, when the user says they cannot hear the agent, when audio is quiet or routed to the wrong device, or before changing anything that touches ListenEngine, CallModeController, VoiceOutbox or the speak tools.
---

# Evergrow voice

The rule that explains most bugs here: **exactly one component may speak.** When two do,
the microphone hears the second one and the loop never ends.

## The pipeline as built

```
AudioRecord 16 kHz mono → UtteranceRecorder (ring archive + VAD)  → WAV
                       → ListenEngine pipe → SpeechRecognizer (on-device) → text
text → agent turn → py_speak_qwen (Qwen streaming omni, voice Serena, ~1.1 s
                    to first audio)  |  on-device py_speak as fallback
```

MediaRecorder `.m4a` output was rejected by the Qwen ASR endpoint above ~130 KB, so the
recorder writes PCM WAV.

## On-device ASR needs all three

`SpeechRecognizer` will transcribe **supplied audio** only with:

1. a `ParcelFileDescriptor` pipe fed **in real time** (a finished file yields nothing),
2. `EXTRA_SEGMENTED_SESSION = true`,
3. text read from `onPartialResults` (finals can be empty).

The phone has a working on-device recogniser
(`com.google.android.tts/GoogleTTSRecognitionService`), so transcription needs no cloud
key. Write-up: `docs/android-offline-asr-from-audio.md` in the repo.

## Echo: drop by timestamp, never by text

When the reply comes out of the speaker, the mic hears the agent. Two guards:

- **App speech**: `CallModeController.micOff/micOn` open a window; transcripts whose
  *time* falls inside `[start - LEAD_PAD, end + TAIL_PAD]` are dropped as echo.
- **Tool speech** (`py_speak*`): the tool writes `filesDir/speech_window.txt` with
  `"<startMs> <endMs>"` before playback and again after it finishes.

Text matching was tried and abandoned — the mic's rendering of the agent's own words
differs enough to slip through, and partials gave false positives that cut replies off.

**The failure this causes, and it looks exactly like "listen mode is broken":** one bad
duration wrote an end **12 hours** into the future; every transcript after it was dropped
as echo, and the log only said `dropped by time window`. Guards now sit on both sides —
the speak tool caps its predicted end at start + 60 s, and `insideToolWindow` rejects any
window longer than 120 s. If listening goes silently deaf, read `speech_window.txt` first.

## Call mode (#278, VoiceCallAudio.kt)

Listen mode runs as a call — `MODE_IN_COMMUNICATION`, voice-communication focus, SCO —
so the headset mic works with the phone pocketed and locked.

Two lessons that cost a day each:

1. **Without `android.permission.MODIFY_AUDIO_SETTINGS` in the manifest, every
   `setMode`/`setSpeakerphoneOn`/`startBluetoothSco` call is silently discarded.** Reads
   work, writes vanish — `getMode()` stayed 0 and the logs looked fine.
2. **Never set `isBluetoothScoOn` by hand.** It only lights the flag and leaves the
   recorder on a channel that does not exist: total silence plus an `error=7` storm.
   Wait for a real SCO/BLE-headset *input* device before entering call mode, otherwise
   stay NORMAL on the phone mic.

Kill switch without a rebuild: `call_audio=0` in `filesDir/voice_tuning.txt`.
Verified working: `AudioRecord.getRoutedDevice()` = type 7 (SCO).

## Routing and loudness

- Swapping the headset while listen mode runs leaves audio latched on the quiet,
  narrowband call route until a restart. `App.kt resetAudioRouteAtStartup()` resets the
  mode at process start (#285); the clean way is to stop listen mode before swapping.
- **"I can't hear you"** → check `AudioManager.getStreamVolume(3)` and the route *before*
  debugging speech. Observed twice: volume at 0/150 with synthesis and playback both
  reporting success, and SCO stuck on so audio went to the buds.
  `setStreamVolume(3, 90, 0)` + `stopBluetoothSco()` fixed it with no rebuild.

## Barge-in

Only with headphones connected. On the speaker the mic hears the speaker, so barge-in
would fire on the agent's own voice. The listening pill is the manual interrupt.

## Double-voice guard

`VoiceOutbox.INSTANCE.getEnabled()` is true whenever listen mode owns the reply speech;
`py_speak` and `py_speak_qwen` check it first and skip rather than speaking twice (the
echo also fed the recogniser and looped). **Any new speech path must check the same
flag.**

## Realtime translate (build #250)

- Mandarin two-person: one hop — realtime session audio in and out, `longan*` voices.
- Cantonese two-person: two hops. The realtime voice list has no Cantonese timbre
  (Rocky is rejected: "Unsupported voice"), so the session runs text-only and
  `qwen3-tts-flash` synthesises the Cantonese line with voice **Rocky** (Kiki is the
  female alternative), played locally with the mic muted.
- One-person modes are text-only subtitles with a caption bar.
- Pitfalls: `MediaPlayer.duration` can return garbage (44 739 241 ms) right after
  `prepare()` — never use it as a mute window; use `onCompletion` with a ceiling plus a
  force-unmute watchdog. Half-duplex stays closed until playback drains. State writes
  need a generation guard against a late `onClosed(1000)`. `translateMode` must be
  `rememberSaveable` and forwarded to `LiquidChatComposer`.
