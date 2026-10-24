"""Helper for talking to the app's screen bridge from the ":python" process.

Python runs in its own process, so it cannot call ScreenBridge directly. Commands
travel as: JSON command file -> broadcast to ScreenCmdReceiver (main process) ->
JSON result file.

Usage:
    import sys; sys.path.insert(0, "<skills>/taobao-shopping/scripts")
    import screen
    print(screen.send("status"))
    print(screen.send("read", max=150))
    print(screen.send("tap", token=12))
    print(screen.send("type", token=3, text="蓝牙耳机"))
"""
import json
import os
import time

_RECEIVER = "com.niki914.zafiro.app.screen.ScreenCmdReceiver"
_ACTION = "com.niki914.zafiro.evergrow.screen.CMD"


def send(op, **kw):
    from com.chaquo.python import Python
    from android.content import Intent, ComponentName

    ctx = Python.getPlatform().getApplication()
    files = str(ctx.getFilesDir())
    cmd_path = os.path.join(files, "screen_cmd.json")
    res_path = os.path.join(files, "screen_result.json")
    cid = int(time.time() * 1000)
    payload = {"id": cid, "op": op}
    payload.update(kw)
    with open(cmd_path, "w") as f:
        json.dump(payload, f)
    try:
        os.remove(res_path)
    except OSError:
        pass
    intent = Intent(_ACTION)
    intent.setComponent(ComponentName(str(ctx.getPackageName()), _RECEIVER))
    ctx.sendBroadcast(intent)
    end = time.time() + 8
    while time.time() < end:
        try:
            with open(res_path) as f:
                res = json.load(f)
            if res.get("id") == cid:
                return res.get("out", "")
        except Exception:
            pass
        time.sleep(0.12)
    return "ERR: no response from the main process (is the app alive?)"


def find(tree_text, needle, clickable_only=False):
    """Return [(token, line)] for tree lines containing needle."""
    hits = []
    for line in tree_text.splitlines():
        if needle not in line:
            continue
        if clickable_only and "*" not in line.split("(")[0]:
            continue
        try:
            tok = int(line.split("]")[0].strip("["))
        except ValueError:
            continue
        hits.append((tok, line.strip()))
    return hits
