"""Restore Evergrow's device side after a fresh install - config, skills, tools, keys.

Nothing secret lives in this file or in the repo it pulls from. The keys come from the
encrypted EVGR1 backup the user keeps in shared storage.

Run it from execute_python:

    import requests
    ns = {}
    exec(requests.get("https://raw.githubusercontent.com/donal100/Evergrow/device-config/device/restore.py").text, ns)
    print(ns["restore"](passphrase="<the passphrase>"))

Leaving passphrase empty restores settings, skills and tools only.
"""
import hashlib
import io
import json
import os
import struct
import tarfile

FILES = "/data/user/0/com.niki914.zafiro.evergrow.debug/files"
TARBALL = "https://codeload.github.com/donal100/Evergrow/tar.gz/refs/heads/device-config"
ENC_CANDIDATES = ["/sdcard/Download/evergrow-keys.enc", FILES + "/downloads/evergrow-keys.enc"]
KEY_DESTS = {
    "serpapi": "tool_output/serpapi/.token",
    "openrouter": "tool_output/openrouter/.token",
    "github": "tool_output/evergrow/.token",
    "google_maps": "google_maps_key.txt",
    "firestore": "tool_output/firestore/.token",
    "qwen_token_plan": "tool_output/qwen/token_plan.token",
    "qwen_payg": "tool_output/qwen/payg.token",
    "qwen_asr": "tool_output/qwen/asr.token",
}

RCON = [0x01, 0x02, 0x04, 0x08, 0x10, 0x20, 0x40, 0x80, 0x1b, 0x36]


def _sbox():
    sbox = [0] * 256
    p = q = 1
    while True:
        p = (p ^ (p << 1) ^ (0x1b if p & 0x80 else 0)) & 0xff
        q ^= q << 1
        q ^= q << 2
        q ^= q << 4
        q &= 0xff
        if q & 0x80:
            q ^= 0x09
        x = q ^ ((q << 1) | (q >> 7)) ^ ((q << 2) | (q >> 6)) ^ ((q << 3) | (q >> 5)) ^ ((q << 4) | (q >> 4))
        sbox[p] = (x ^ 0x63) & 0xff
        if p == 1:
            break
    sbox[0] = 0x63
    return sbox


SBOX = _sbox()


def _mul(a, b):
    r = 0
    while b:
        if b & 1:
            r ^= a
        a = ((a << 1) ^ 0x1b) & 0xff if a & 0x80 else (a << 1) & 0xff
        b >>= 1
    return r


def _expand(key):
    nk = len(key) // 4
    nr = nk + 6
    w = [list(key[4 * i:4 * i + 4]) for i in range(nk)]
    for i in range(nk, 4 * (nr + 1)):
        t = w[i - 1][:]
        if i % nk == 0:
            t = [SBOX[b] for b in t[1:] + t[:1]]
            t[0] ^= RCON[i // nk - 1]
        elif nk > 6 and i % nk == 4:
            t = [SBOX[b] for b in t]
        w.append([a ^ b for a, b in zip(w[i - nk], t)])
    return w, nr


def _enc_block(w, nr, block):
    s = [[block[r + 4 * c] for c in range(4)] for r in range(4)]

    def addrk(rnd):
        for c in range(4):
            for r in range(4):
                s[r][c] ^= w[rnd * 4 + c][r]

    addrk(0)
    for rnd in range(1, nr + 1):
        for r in range(4):
            for c in range(4):
                s[r][c] = SBOX[s[r][c]]
        for r in range(1, 4):
            s[r] = s[r][r:] + s[r][:r]
        if rnd != nr:
            for c in range(4):
                a = [s[r][c] for r in range(4)]
                s[0][c] = _mul(a[0], 2) ^ _mul(a[1], 3) ^ a[2] ^ a[3]
                s[1][c] = a[0] ^ _mul(a[1], 2) ^ _mul(a[2], 3) ^ a[3]
                s[2][c] = a[0] ^ a[1] ^ _mul(a[2], 2) ^ _mul(a[3], 3)
                s[3][c] = _mul(a[0], 3) ^ a[1] ^ a[2] ^ _mul(a[3], 2)
        addrk(rnd)
    return bytes(s[r][c] for c in range(4) for r in range(4))


def _gmult(x, y):
    z = 0
    v = y
    for i in range(128):
        if (x >> (127 - i)) & 1:
            z ^= v
        v = (v >> 1) ^ (0xe1000000000000000000000000000000 if v & 1 else 0)
    return z


def _pad(b):
    return b"\x00" * ((16 - len(b) % 16) % 16)


def aes_gcm_decrypt(key, iv, blob):
    body, tag = blob[:-16], blob[-16:]
    w, nr = _expand(key)
    h = int.from_bytes(_enc_block(w, nr, b"\x00" * 16), "big")
    acc = 0
    for i in range(0, len(body), 16):
        acc = _gmult(acc ^ int.from_bytes(body[i:i + 16].ljust(16, b"\x00"), "big"), h)
    acc = _gmult(acc ^ int.from_bytes(struct.pack(">QQ", 0, len(body) * 8), "big"), h)
    mask = int.from_bytes(_enc_block(w, nr, iv + b"\x00\x00\x00\x01"), "big")
    ok = (acc ^ mask).to_bytes(16, "big") == tag
    out = bytearray()
    for i in range(0, len(body), 16):
        ks = _enc_block(w, nr, iv + struct.pack(">I", 2 + i // 16))
        out += bytes(a ^ b for a, b in zip(body[i:i + 16], ks))
    return ok, bytes(out)


def _fetch():
    import requests

    r = requests.get(TARBALL, timeout=60)
    r.raise_for_status()
    files = {}
    with tarfile.open(fileobj=io.BytesIO(r.content), mode="r:gz") as tf:
        for m in tf.getmembers():
            if not m.isfile() or "/device/" not in m.name:
                continue
            rel = m.name.split("/device/", 1)[1]
            if rel in ("restore.py",) or rel.startswith("."):
                continue
            files[rel] = tf.extractfile(m).read()
    return files


def _write(rel, data):
    dest = os.path.join(FILES, rel)
    os.makedirs(os.path.dirname(dest), exist_ok=True)
    if isinstance(data, str):
        data = data.encode()
    with open(dest, "wb") as f:
        f.write(data)
    os.chmod(dest, 0o600 if ("token" in rel or "_key" in rel or rel.endswith(".token")) else 0o644)
    return dest


def restore(passphrase="", dry_run=False):
    files = _fetch()
    lines = ["pulled %d config files from the device-config branch" % len(files)]
    # never overwrite a live LLM key with the blank one from the repo
    live = os.path.join(FILES, "settings/llm/saved_configs.json")
    old_keys = {}
    if os.path.exists(live):
        try:
            old = json.load(open(live))
            old_keys = {c.get("id"): c.get("api_key", "") for c in old.get("configs", [])}
        except Exception:
            old_keys = {}
    for rel, data in sorted(files.items()):
        if rel.endswith("saved_configs.json") and old_keys:
            try:
                new = json.loads(data.decode())
                for c in new.get("configs", []):
                    if old_keys.get(c.get("id")):
                        c["api_key"] = old_keys[c["id"]]
                data = json.dumps(new, indent=2).encode()
            except Exception:
                pass
        if not dry_run:
            _write(rel, data)
        lines.append(("would write " if dry_run else "wrote ") + rel)
    if passphrase:
        enc = next((p for p in ENC_CANDIDATES if os.path.exists(p)), None)
        if not enc:
            lines.append("keys: no encrypted backup found in Downloads")
        else:
            d = open(enc, "rb").read()
            if d[:5] != b"EVGR1":
                lines.append("keys: unexpected magic " + repr(d[:8]))
            else:
                salt, nonce, blob = d[5:21], d[21:33], d[33:]
                key = hashlib.pbkdf2_hmac("sha256", passphrase.encode(), salt, 120000, 32)
                ok, plain = aes_gcm_decrypt(key, nonce, blob)
                if not ok:
                    lines.append("keys: GCM tag mismatch - wrong passphrase")
                else:
                    keys = json.loads(plain)["keys"]
                    for name, rel in KEY_DESTS.items():
                        if name in keys and not dry_run:
                            _write(rel, keys[name].strip() + "\n")
                    lines.append("keys: %d credentials restored%s" % (
                        len([k for k in keys if k in KEY_DESTS]), " (dry run)" if dry_run else ""))
                    if not dry_run and "qwen_asr" in keys:
                        _write("qwen_asr_key.txt", keys["qwen_asr"].strip() + "\n")
    lines.append("next: restart Evergrow so the tool registry and settings reload, then load_skill('evergrow-dev')")
    out = "\n".join(lines)
    print(out)
    return out
