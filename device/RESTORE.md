# Restoring Evergrow after a reinstall

Symptom: the app is freshly installed, chats work (the LLM key lives in settings, restored by
Android's own backup), but `tool_output/` is gone, so maps, news, OpenRouter, GitHub and Qwen
speech all fail, and the custom tools are missing from the registry.

One command does everything. In `execute_python`:

```python
import requests
ns = {}
exec(requests.get("https://raw.githubusercontent.com/donal100/Evergrow/device-config/device/restore.py").text, ns)
print(ns["restore"](passphrase="<passphrase>"))
```

That restores settings, all custom tools, the six fork skills, and then decrypts
`/sdcard/Download/evergrow-keys.enc` and writes every credential to the path the skills
expect. Dry run first if you want to see the file list: `ns["restore"](dry_run=True)`.

Then **restart the app** - the tool registry and settings are read at startup - and confirm:

```python
py_web_search(query="test")          # network + tools
py_restore_keys(passphrase="...", dry_run=True)   # key file map
py_speak(text="restored")            # on-device voice
py_speak_qwen(text="restored")       # cloud voice, needs the Qwen key
```

## If the key backup is missing

The `.enc` is the only copy of the credentials; it is not in the repo. Copy it back to
`/sdcard/Download/evergrow-keys.enc` first. Format: `EVGR1` magic, 16-byte salt, 12-byte
nonce, AES-256-GCM ciphertext, key = PBKDF2-HMAC-SHA256(passphrase, salt, 120000).

## Before the wipe

`settings/tools/custom_py_tools.json` is the part that changes most. Refresh the repo copy
after building or editing tools:

```python
# agent: run this after any py_meta_tools write/delete
```

The agent is expected to keep `device/` current: a tool that only exists on the phone is a
tool that will be lost again.
