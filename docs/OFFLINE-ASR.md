# Transcribing a supplied audio file with Android's on-device recogniser

Android's `SpeechRecognizer` normally listens to the microphone. Feeding it *our own* audio file is
possible, but only with three things together — any one missing produces silence, `error=5`, `error=11`
or `error=13`, none of which mention the real cause.

Measured on Android 15 with Google's `GoogleTTSRecognitionService`.

## The recipe

```kotlin
val pipe = ParcelFileDescriptor.createPipe()          // [0] = read end, [1] = write end
val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
recognizer.setRecognitionListener(listener)
recognizer.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
    putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
    putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, true)        // 1. required
    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])          // 2. the read end
    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, 16_000)
    putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
})
val out = FileOutputStream(pipe[1].fileDescriptor)
// 3. write PCM in real time, not in one burst
```

Then read text from **`onPartialResults`** as well as `onResults` — the partials are where the
recogniser commits early, and with a pipe the final result can arrive late or empty.

## The three requirements, and what each failure looks like

| missing | what you see |
|---|---|
| `EXTRA_SEGMENTED_SESSION=true` | the service treats the pipe as a live mic and never finalises: nothing at all |
| a `ParcelFileDescriptor` pipe (vs. writing a finished file) | zero results; the recogniser consumes the stream as it is written |
| writing in real time | a burst of PCM can be consumed before the service is ready and is discarded |
| reading `onPartialResults` | text appears and then vanishes — only the last partial survives |

Feeding a completed `.wav` file straight in does **not** work, no matter the flags. Verified: `0` results.

## Sanity check that cost the most time

`list(javaArrayList)` inside a Java→Python callback raises `TypeError`, which propagates to the main
thread and kills the process. The symptom looks like *"Python worker is not connected"* and sends you
looking for a network or Chaquopy problem. Iterate with `.iterator()`/`hasNext()`, wrap every callback
body in `try/except`, and check the callback before blaming the platform.

## When to prefer a cloud ASR

The on-device recogniser is free and offline, which is why it is the default for continuous listening.
It is weaker at: numbers spoken as ranges ("four or five hundred" → "4500"), names, code-switching, and
Cantonese. For those, send the same audio to `qwen3-asr-flash` (`asr_options.language` must be a real
code — `auto` fails) and compare. Keeping the recording is what makes that possible after the fact.
