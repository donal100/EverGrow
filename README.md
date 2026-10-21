> **Evergrow** is a personal fork of [Zafiro](https://github.com/niki914/zafiro) by [niki914](https://github.com/niki914),
> used under the MIT licence. The architecture, agent runtime and tool design are his work. Upstream credit and the
> original `LICENSE` are retained unchanged.
>
> What this fork adds on top of upstream:
> - **Voice in** — tap once, transcribe with Qwen ASR (`qwen3-asr-flash`) and send automatically
> - **Voice out** — replies spoken with the Qwen streaming omni voice (`qwen3.5-omni-plus`), on-device TTS as fallback
> - **Listen mode as a phone call** — continuous conversation that runs the audio stack in call mode, so the
>   *headset* microphone is used when one is connected and the phone microphone when it isn't
> - **Two-person interpretation** — English ⇄ Mandarin and English ⇄ Cantonese, plus one-way subtitles
> - **Context compaction with recall** — lossy summaries over a lossless archive (`py_history_search`, `py_history_show`)
> - **Raw GPS for the agent** — `py_gps` exposes position, accuracy, altitude, speed, bearing and satellites
> - **News briefing skill** — Hong Kong, technology, geopolitics and Premier League, routed by language
> - **CI baseline build** + `INJECT_EVENTS_MISSING` capability preflight + guided Android 13+ onboarding
>
> Design notes live in [`docs/`](docs/): [VOICE.md](docs/VOICE.md), [OFFLINE-ASR.md](docs/OFFLINE-ASR.md),
> [GOTCHAS.md](docs/GOTCHAS.md), [BUILD.md](docs/BUILD.md).
>
> **Build requirements** (undocumented upstream, found the hard way): JDK 17 **and Python 3.11** —
> Chaquopy's `installDebugPythonRequirements` task fails without a `python3.11` on PATH.

<div align="right">

**[中文](README_CN.md)** | English

</div>

<p align="center">
  <img src="https://socialify.git.ci/donal100/Evergrow/image?custom_description=Voice-first+Android+agent+%C2%B7+Speak%2C+listen%2C+act%0ASkills+%C2%B7+MCP+%C2%B7+Python+%2F+Phone-Use&description=1&font=Raleway&language=1&logo=data%3Aimage%2Fsvg%2Bxml%3Bbase64%2CPHN2ZyB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciIHZpZXdCb3g9IjAgMCAxMDggMTA4IiB3aWR0aD0iNTEyIiBoZWlnaHQ9IjUxMiI%2BPHJlY3QgeD0iMCIgeT0iMCIgd2lkdGg9IjEwOCIgaGVpZ2h0PSIxMDgiIGZpbGw9IiMwQjFCMkIiLz48cGF0aCBkPSJNNTQuMCwyMi4wIFEzNC4wLDY2LjUgODIuNiw3MS41IiBzdHJva2U9IiMyRTdERkYiIHN0cm9rZS13aWR0aD0iNyIgZmlsbD0ibm9uZSIgc3Ryb2tlLWxpbmVjYXA9InJvdW5kIi8%2BPHBhdGggZD0iTTgyLjYsNzEuNSBRNTQuMCwzMS45IDI1LjQsNzEuNSIgc3Ryb2tlPSIjMkVEM0M2IiBzdHJva2Utd2lkdGg9IjciIGZpbGw9Im5vbmUiIHN0cm9rZS1saW5lY2FwPSJyb3VuZCIvPjxwYXRoIGQ9Ik0yNS40LDcxLjUgUTc0LjAsNjYuNSA1NC4wLDIyLjAiIHN0cm9rZT0iI0RDRTdGNSIgc3Ryb2tlLXdpZHRoPSI3IiBmaWxsPSJub25lIiBzdHJva2UtbGluZWNhcD0icm91bmQiLz48L3N2Zz4%3D&name=1&owner=1&pattern=Diagonal+Stripes&theme=Auto" alt="evergrow"/>
</p>

<p align="center">
  <a href="https://github.com/donal100/Evergrow"><img src="https://img.shields.io/github/stars/donal100/Evergrow?label=stars" alt="stars"/></a>
  <a href="https://github.com/donal100/Evergrow/releases/tag/debug-latest"><img src="https://img.shields.io/github/v/release/donal100/Evergrow?include_prereleases&label=debug%20build" alt="release"/></a>
  <a href="https://github.com/donal100/Evergrow/releases"><img src="https://img.shields.io/github/downloads/donal100/Evergrow/total" alt="downloads"/></a>
  <img src="https://img.shields.io/badge/kotlin-86.4k-blue" alt="kotlin lines"/>
  <a href="https://github.com/niki914/zafiro"><img src="https://img.shields.io/badge/upstream-niki914%2Fzafiro-lightgrey" alt="upstream"/></a>
</p>

## What is EverGrow?

EverGrow is Zafiro with a voice. It still runs a full Android agent — screen understanding, device control,
Skills, MCP, memory, native Shell and Python 3 — but you can now hold a spoken conversation with it: tap the
microphone to talk, and its answers come back in a natural cloud voice instead of text alone.

The design goal is the boring-but-hard one: make the voice loop feel like a phone call rather than a walkie-talkie.
That means **one owner for speech** — the agent speaks, the app never does, so no reply is ever read twice — and echo
rejected by *timestamp* rather than by comparing text, because text comparison throws away legitimate speech whenever
you quote the agent back to itself.

Listen mode goes one step further and enters Android's call audio path (`MODE_IN_COMMUNICATION` + voice-communication
focus + SCO), which is what makes a Bluetooth headset's microphone usable at all. It only does so when a real SCO input
device appears; otherwise it stays on the phone microphone, because guessing wrong leaves the recorder pointed at a
channel that does not exist and the recogniser hears silence. There is a kill switch in `voice_tuning.txt`.

### Underlying agent: Zafiro


Zafiro is an open-source intelligent Agent running on Android devices, with [BYOK](https://www.ibm.com/think/topics/byok) support. It understands your screen, controls your device, and carries out operations across apps — with full support for Skills, MCP, and memory. It can also run Shell and Python 3 natively and wrap tools on its own to perform tasks such as web search and file downloads, or connect to a remote dev machine via SSH.

<table align="center">
<tr>
<tr>
<td align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/zafiro__phone_use.gif?raw=true" alt="Zafiro picks and plays music in Spotify via Phone-Use" width="135" height="300"/></td>
<td align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/zafiro__net_research.gif?raw=true" alt="Zafiro completes web research with headless web search and article reading via Python scripts" width="135" height="300"/></td>
<td align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/zafiro__native.gif?raw=true" alt="Zafiro downloads an APK from the web via Python and installs it with Root" width="135" height="300"/></td>
<td align="center"><img src="https://github.com/niki914/zafiro/blob/main/res/zafiro__settings_screen.png?raw=true" alt="Zafiro settings screen" width="142" height="300"/></td>
</tr>
<tr>
<td align="center">Device Control</td>
<td align="center">Research</td>
<td align="center">Install Apps from the Web</td>
<td align="center">Settings</td>
</tr>
</tr>
</table>

> [!IMPORTANT]
> Zafiro runs via [Shizuku](https://github.com/RikkaApps/Shizuku) — no Root required; Root users get the full experience.
>
> You can download a release from [Releases](https://github.com/niki914/zafiro/releases/latest), or choose to [build from source](#from-source).

## Core Capabilities

### Modern UI Design Language

- **[Material 3 Expressive](https://m3.material.io/) & Apple Liquid Glass** - a modern, polished interface
- **Personalized Theming** - multiple theme colors with dark mode and dynamic color extraction
- **Multilingual Support** - English, 中文, 日本語, Español, Русский
- **Background Interaction** - sleek floating ball and notification tray UI to follow and interact with the Agent in real time

### Device Control

- **Screen Interaction** - open apps, pick music, order food — all done for you
- **Fully Visible** - an on-screen mouse shows every step the Agent takes

### Agent System

- **Out-of-the-Box** - built-in support for OpenAI, Anthropic, Google Gemini, DeepSeek, OpenRouter, Kimi, Alibaba Bailian, SiliconFlow, and more
- **Extend as Needed** - full support for Skills and MCP, plus memory and a workspace — extend it your way
- **Permission Management** - with configurable rules, every command and piece of code is under your control
- **Conversation Control** - rewind, edit, and regenerate any turn at will; all conversation data stays strictly local, and models can be switched on the fly within the same session

### Python Tools

- **Run Code Directly** - run Python 3 natively on the device
- **Meta-Tooling** - wrap your own Python tools; the Agent reuses and maintains the tools it creates
- **Built-in Scenarios** - web search, web page reading, and APK installation work out of the box

### Remote Environments

- **[Termux](https://github.com/termux/termux-app)** - use Linux commands and tools on your Android device
- **SSH** - connect to a dev machine or server to execute remote tasks
- **[Claude Code](https://github.com/anthropics/claude-code)** - issue dev tasks from your phone, executed by a remote Coding Agent

## Voice Assistant Takeover

Through the [LSPosed](https://github.com/lsposed/lsposed) framework, Zafiro can take over your system voice assistant — wake Breeno or XiaoAi, and the one actually answering is your own Agent. You can decide, based on keywords, which requests go to Zafiro and which pass through to the native assistant. After takeover, the assistant retains full Agent capabilities including device control.

> [!NOTE]
> Taking over the system voice assistant requires **Root + LSPosed**, and currently supports: OPPO - Breeno
>
> Voice takeover availability may be affected by phone model, system version, voice assistant version, and vendor system restrictions. When your device does not yet support system assistant takeover, you can still use Zafiro's chat interface with all Agent capabilities.

<details>
<summary>Demos</summary>

<table align="center">
<tr>
<td align="center" valign="middle"><img src="https://github.com/niki914/zafiro/blob/main/res/breeno__github_mcp.gif?raw=true" alt="Zafiro voice assistant takeover demo" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/zafiro/blob/main/res/hyper__intro.gif?raw=true" alt="Zafiro voice assistant takeover demo" width="200"/></td>
<td align="center" valign="middle"><img src="https://github.com/niki914/zafiro/blob/main/res/breeno__magisk.gif?raw=true" alt="Zafiro voice assistant takeover demo" width="200"/></td>
</tr>
</table>

</details>

## Tech Stack

| Category         | Technology                                                                   |
|------------|----------------------------------------------------------------------|
| Language         | [Kotlin](https://kotlinlang.org/)                                    |
| UI Framework      | [Jetpack Compose](https://developer.android.com/jetpack/compose)     |
| Design Language       | [Material Design 3 Expressive](https://m3.material.io/)              |
| Liquid Glass       | [Android Liquid Glass](https://github.com/Kyant0/AndroidLiquidGlass) |
| Agent Runtime  | [Okia](https://github.com/niki914/okia)                              |
| Python Runtime | [Chaquopy](https://chaquo.com/chaquopy/)                             |
| System Takeover       | [LSPosed](https://github.com/lsposed/lsposed) + Xposed API           |
| Terminal / SSH   | [libterm](https://github.com/niki914/libterm)                        |
| SVG Rendering      | [coil-resvg](https://github.com/hash-sequence/coil-resvg) (resvg)    |
| G2 Rounded Corners | [Capsule](https://github.com/Kyant0/Capsule)                         |

## Getting Started

### Build from Source

```bash
./gradlew assembleDebug
```

### Requirements

- Android Studio (or Android SDK + JDK 17)
- An Android 11 or above device

<details>
<summary>Release Signing</summary>

To build a Release version yourself, use your own signing key:

```bash
keytool -genkeypair -v -keystore my-release.jks \
  -keyalg RSA -keysize 2048 -validity 10000 -alias my_key

./gradlew assembleRelease \
  -PRELEASE_STORE_FILE=/absolute/path/to/my-release.jks \
  -PRELEASE_STORE_PASSWORD=yourStorePassword \
  -PRELEASE_KEY_ALIAS=my_key \
  -PRELEASE_KEY_PASSWORD=yourKeyPassword
```

</details>

## Project Structure

```
./
├── app/                 # Main app: Compose UI, AgentRuntimeService, Xposed hooks
├── business/            # Core business layer (separation of contracts and implementations)
│   ├── api/             # Public business contract interfaces
│   ├── agent/           # Agent core logic, conversation orchestration & state flow
│   ├── files/           # File attachments, document path resolution
│   ├── notification/    # Resident notification, foreground service & channel management
│   ├── permission/      # Unified permission manager (Root / Shizuku auto-granting, tool approval)
│   └── application/     # Application management & lifecycle
├── agent-runtime/       # Agent runtime: LLM invocation, Tool/Skill/MCP execution, Python runtime
├── xsettings/           # Lightweight configuration module (depended on by modules as needed)
├── store/               # Store persistence, IPC bridge (XIpcBridge)
├── ui-kit/              # Shared Compose components, LiquidScreen shell, navigation
├── remote-view/         # Cross-process / floating RemoteView components
├── xposed-api/          # Xposed event types, shared constants (shared by main app and host process)
├── xposed-runtime/      # Xposed runtime, hook base classes
└── libs/
    ├── logging/         # Logging library
    ├── okia/            # Okia Agent runtime base library
    └── libterm/         # Terminal library (multiple backends: libsu, shizuku, ssh, etc.)
```

## Roadmap

- System voice assistant integration (non-Xposed takeover)
- File import / sharing from outside the app
- Built-in skills like Termux, `/skill` hints, and cloud skill imports

## Contributing

Pull requests are welcome!

1. Fork this project
2. Create a feature branch (`git checkout -b feat/your-feature`)
3. Commit your changes (`git commit -m 'feat: add your feature'`, following [Conventional Commits](https://www.conventionalcommits.org/en/))
4. Push to the branch (`git push origin feat/your-feature`)
5. Open a Pull Request

## Community

- [Telegram](https://t.me/+ZPX2xtSl6RwyZGNl) — discuss, ask questions, give feedback
- [GitHub Issues](https://github.com/niki914/zafiro/issues) — report bugs or request features
- [Afdian](https://afdian.com/a/niki914) — support the developer

When reporting an issue, please include as much detail as possible: phone model and Android version, system voice assistant and its version, Zafiro version, steps to reproduce, and screenshots or screen recordings.

## License

MIT — see [LICENSE](LICENSE).

<p align="center">
  Made with ✨️ by <a href="https://github.com/niki914">niki914</a>
</p>
