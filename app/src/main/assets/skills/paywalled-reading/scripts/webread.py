"""Helper for the in-app WebView bridge and for replaying its cookies in Python.

Two halves:
  send(op, **kw)                  talk to WebViewCmdReceiver (main process)
  session_from(path, domain)      build a requests.Session from a saved cookie jar

Typical use:
    import sys; sys.path.insert(0, "<skills>/paywalled-reading/scripts")
    import webread
    webread.send("open", url="https://account.scmp.com/login")   # user logs in
    webread.send("savecookies", url="https://www.scmp.com/", path="tool_output/scmp/cookies.txt")
    s = webread.session_from("tool_output/scmp/cookies.txt", ".scmp.com")
    html = s.get(url, timeout=30).text
"""
import json
import os
import time

_RECEIVER = "com.niki914.zafiro.app.web.WebViewCmdReceiver"
_ACTION = "com.niki914.zafiro.evergrow.webview.CMD"


def _files():
    from com.chaquo.python import Python
    return str(Python.getPlatform().getApplication().getFilesDir())


def send(op, **kw):
    from com.chaquo.python import Python
    from android.content import Intent, ComponentName

    ctx = Python.getPlatform().getApplication()
    files = str(ctx.getFilesDir())
    cmd = os.path.join(files, "webview_cmd.json")
    res = os.path.join(files, "webview_result.json")
    cid = int(time.time() * 1000)
    payload = {"id": cid, "op": op}
    payload.update(kw)
    with open(cmd, "w") as f:
        json.dump(payload, f)
    try:
        os.remove(res)
    except OSError:
        pass
    intent = Intent(_ACTION)
    intent.setComponent(ComponentName(str(ctx.getPackageName()), _RECEIVER))
    ctx.sendBroadcast(intent)
    timeout = kw.get("timeout_ms", 30000) / 1000.0
    end = time.time() + timeout + 8
    while time.time() < end:
        try:
            with open(res) as f:
                r = json.load(f)
            if r.get("id") == cid:
                return r.get("out", "")
        except Exception:
            pass
        time.sleep(0.15)
    return "ERR: no response"


def session_from(path, domain):
    """Rebuild a requests.Session from a WebView cookie dump."""
    import requests
    p = path if os.path.isabs(path) else os.path.join(_files(), path)
    cookie = open(p).read()
    s = requests.Session()
    s.headers.update({
        "User-Agent": ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 "
                       "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"),
        "Accept-Language": "en-HK,en;q=0.9",
    })
    for pair in cookie.split(";"):
        if "=" in pair:
            k, v = pair.strip().split("=", 1)
            s.cookies.set(k, v, domain=domain)
    return s


def article_text(html, min_chars=60):
    """Crude but usable article extraction: <p> blocks, tags stripped."""
    import re
    ps = re.findall(r"<p[^>]*>(.*?)</p>", html, re.S)
    out = []
    for p in ps:
        t = re.sub(r"<[^>]+>", "", p)
        t = re.sub(r"\s+", " ", t).strip()
        if len(t) >= min_chars and not t.startswith("."):
            out.append(t)
    return "\n\n".join(out)
