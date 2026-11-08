# device/ - the phone side of Evergrow, kept in git

Everything that lives on the device and is *not* a secret: custom Python tools, the skills
written for this fork, and the agent's settings. A reinstall wipes
`/data/user/0/com.niki914.zafiro.evergrow.debug/files`, so this folder is the source of
truth for all of it.

**No credentials are stored here.** Passwords and API keys stay in
`/sdcard/Download/evergrow-keys.enc` (format `EVGR1`, AES-256-GCM) and are restored with the
passphrase. `settings/llm/saved_configs.json` is committed with `api_key` blanked, and
`restore.py` refuses to overwrite a key that is already live on the device.

## What is in here

| path | what it is |
|---|---|
| `restore.py` | self-contained restore: pulls this folder, writes it into place, then optionally decrypts the key backup |
| `settings/tools/custom_py_tools.json` | every custom tool - `py_speak_qwen`, `py_speak`, `py_navigate`, `py_voice_archive`, `py_screen`, `py_restore_keys`, `py_web_search`, `py_web_read`, `py_download_file`, `py_install_apk`, `py_launch_wechat` |
| `settings/llm/` | model config (key blank), model facts, OpenRouter catalogue |
| `settings/agents/` | agent registry and memory |
| `settings/rules/execution_rules.json` | takeover / consent rules |
| `settings/app_state.json` | UI preferences (volatile fields stripped) |
| `skills/` | the non-APK skills: evergrow-dev, evergrow-voice, maps-and-places, news-briefing, paywalled-reading, screen-control |

The five skills that ship inside the APK (`phone-use`, `termux`, `zafiro-config`,
`skill-creator`, `install-skill-from-github`) are **not** duplicated here - the installer
seeds them.

## Branch

This lives on the `device-config` branch, not `main`, on purpose: `main` triggers a 4-minute
Android build on every push, and a settings change should not. `restore.py` reads this branch.
Merge it into `main` whenever a build is being pushed anyway.

## Restoring after a reinstall

See `RESTORE.md`.
