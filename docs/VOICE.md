# Voice pipeline

How Evergrow turns speech into a conversation, and why each piece is built the way it is.
Every claim here was measured on a Xiaomi 24129PN74G (Android 15) running `com.niki914.zafiro.evergrow.debug`.

## The shape of it

```
AudioRecord (16 kHz PCM16)
      │
      ├──► ring recorder ──► files/voice_ring ──► py_voice_archive (independent ASR check)
      │
      └──► queue ──► pipe (ParcelFileDescriptor) ──► SpeechRecognizer (segmented session)
                                                          │
                                                     onPartial/onResults
                                                          │
                                              CallModeController: echo filter by time window
                                                          │
                                                    agent turn ──► py_speak_qwen ──► ears
```

## One owner for speech

Two systems deciding who speaks produced overlap, a stolen microphone and finally silence. The rule
now: **the agent speaks, the app does not.** `VoiceOutbox.enabled` is `false` unconditionally, and
`py_speak_qwen` refuses to speak when the outbox is enabled so the guard survives future edits.

Do not reintroduce a "who owns this turn" flag file. Coordinate by deletion, not by flags: a stale
flag on disk once silenced the app for two hours (`skip speak: py tool owns this turn`).

## Echo is a timestamp problem, not a text problem

The microphone is never muted while the speaker is live. Instead the playback window is recorded
(`[start - 250 ms, end + 900 ms]`) and any transcript arriving inside it is dropped.

- Text similarity was tried and removed: it deletes legitimate speech whenever you quote the agent
  ("when you were reading this answer…"), which is exactly when understanding matters.
- The Python speech tool writes its own window to `filesDir/speech_window.txt` as `start end`.
  It must declare the **predicted end before playback starts** — writing the end after playback
  finishes loses a race with the recogniser, which delivers the final transcript a few hundred ms
  after the audio stops. Symptom: your own reply arrives back as a user message.
- A window with no end is trusted for 20 s only, so a killed process cannot mute the mic for hours.

## The reopen gap: keep the audio

A segmented session delivers one sentence and then has to be reopened (350 ms). That gap used to eat
the beginning of the next sentence — "Do you think I should try with the earphones" arrived as
"with the earphones now". Three separate bugs stacked:

1. `closeSession()` called `queue.clear()` — buffered audio deleted by design.
2. The writer polled the queue *before* checking whether a pipe was open — it drained the backlog
   and threw it away.
3. During the gap `out` still pointed at the previous, closed pipe, so audio was written into a dead
   stream (`writer: Stream Closed`) and lost.

All three are fixed; the log now prints `session open backlog=N`, and **N > 0 is the proof** that
gap audio is being carried into the new session.

## Listen mode is a call, not playback

Conversation needs a bidirectional low-latency channel and the microphone next to the mouth. That is
Android's call model: `MODE_IN_COMMUNICATION`, `AUDIOFOCUS_GAIN` with `USAGE_VOICE_COMMUNICATION`,
and SCO/HFP to the headset.

Two traps, both worth hours:

- **`MODIFY_AUDIO_SETTINGS`** must be in the manifest, or `setMode`, `setSpeakerphoneOn` and
  `startBluetoothSco` are silently discarded. Reads work, writes vanish: `setMode(MODE_IN_COMMUNICATION)`
  returned, `getMode()` stayed `0`. It is a *normal* permission — declaring it is enough, no prompt.
  `BLUETOOTH_CONNECT` (runtime) is also required on Android 12+ for SCO.
- **Never call `setBluetoothScoOn(true)` yourself.** It only lights a flag. If no SCO link exists the
  recorder is pointed at a channel that is not there, records silence, and the recogniser answers
  `error=7` (`NO_MATCH`) in a loop while the user hears nothing at all.

So: start SCO, wait up to ~2 s for an actual SCO/BLE-headset **input** device to appear, and only
then enter call mode. If it never appears, stop SCO, stay in `NORMAL`, keep the phone microphone.
Verified working with a Bluetooth headset and the phone in a pocket, screen locked.

The trade is explicit: while call mode is active, everything you hear is narrowband call audio.
That is why it is opt-in per situation rather than always on.

## Runtime switches (no rebuild)

`filesDir/voice_tuning.txt`, one `key=value` per line:

| key | effect |
|---|---|
| `call_audio=0` | disable call mode entirely; stay in `NORMAL`, phone mic |
| `listen_ondevice=0` | use the on-device recogniser instead of the network one |
| `listen_lang=zh-HK` | change the recognition language |

## Diagnostics

| file | what it holds |
|---|---|
| `files/call_mode.log` | `session open backlog=`, `sentence:`, `error=`, `[call]`, `dropped by time window:` |
| `files/voice_debug.log` | `submit`, `speak from outbox`, `first text … after send` |
| `files/voice_ring/` | rolling 5-minute audio, the ground truth for "what was actually said" |

When a transcript looks wrong — a number, a name, a translation — do not argue from memory: run the
window through `py_voice_archive`, which transcribes it independently of the phone's recogniser.

## Known limitation

After almost every sentence the recogniser is torn down and reopened, and the log shows
`error=11` (`SERVER_DISCONNECTED`). It is self-inflicted: we cancel a recogniser whose socket is still
live, and the error handler cannot tell that from a real failure. It recovers in ~600 ms and is
invisible in use. The fix is a single long-lived session with our own VAD over the ring recording;
that work is deliberately parked — see the note in the commit history before attempting it.
