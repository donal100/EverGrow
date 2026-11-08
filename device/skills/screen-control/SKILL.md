---
name: screen-control
description: Read and drive any app on this phone through the accessibility bridge — tap, type, scroll, open apps, read prices, titles, reviews and messages. Use this whenever the user wants something done inside another app's UI, including shopping on Taobao or HKTVmall, searching or playing something on YouTube, or any request that needs seeing or touching the screen ("open X and search for Y", "play the first video", "how much is this on Taobao", "check the reviews"). Also load it before claiming the screen cannot be automated — the bridge exists and the traps are written down here.
---

# Screen control

The agent can see and drive other apps by borrowing Android's accessibility service. This
skill carries the whole mechanism plus what each app needs, so none of it has to be
rediscovered. Build 283 is the reference implementation.

## The permission is borrowed, not owned

Banking apps refuse to run while any accessibility service is enabled, and they check the
global list rather than our package filter. So accessibility goes on for a job and off
immediately after.

1. `py_screen(operation="enable")` opens settings. On HyperOS the first attempt lands on the
   Accessibility dashboard (the `:settings:show_fragment` extra is accepted but ignored);
   once the service has been enabled before, the same call goes straight to **Downloaded apps**.
   Either way, say the path plainly: **Accessibility → Downloaded apps → Evergrow → on**.
2. If the toggle is greyed out, Android is blocking a sideloaded app:
   **Settings → Apps → Evergrow → ⋮ → Allow restricted settings**, then retry.
3. `py_screen(operation="status")` must report `ready=true`. If the user says it is on but
   `ready` is false, read `filesDir/screen.log`: a `connected;` line means the service is alive
   and the problem is elsewhere (see the process note below).
4. It switches itself off. The service arms a timer on connect — `screen_ttl_minutes.txt`,
   default **60**, `0` = never — and calls `disableSelf()` when it expires. The enable time is
   stamped to `screen_enabled_at.txt`, so if the process is killed or the phone reboots and the
   timer never fires, the next connect past the TTL disables it immediately. You do **not** need
   to call `operation="off"` after a job; leave it to the timer (user instruction), unless they are about to open a banking app, and check
   `status` if the user is about to open a banking app.
   Turning it back on always needs the user, unless Shizuku or root is present (neither is, here).

## Why the bridge looks odd: two processes

Chaquopy runs Python in its own process — `:python` — while the accessibility service lives in
the main process. Static state cannot cross that line, so calling `ScreenBridge` from Python
returns `ready=false` even while the service is connected. Confirm with
`/proc/self/cmdline` and `ps` if in doubt.

Commands therefore travel as files plus a broadcast:

```
Python: write filesDir/screen_cmd.json  {"id":…, "op":"read", "max":150}
        broadcast the intent to class ScreenCmdReceiver
Main:   ScreenCmdReceiver runs it, writes screen_result.json
Python: polls screen_result.json for the matching id (8 s timeout)
```

The receiver declares **no intent-filter**, so the broadcast must name its component:

```python
intent.setComponent(ComponentName(pkg, "com.niki914.zafiro.app.screen.ScreenCmdReceiver"))
```

Sending with only an action does nothing at all — no error, no result. That mistake costs ten
minutes every time it is repeated.

Use `scripts/screen.py`: `send(op, **kw)` plus `find(tree_text, needle)`. Import it with
`sys.path.insert(0, "<skills>/screen-control/scripts")`.

## Operations

| op | notes |
|---|---|
| `status` | `ready`, foreground package, whitelist |
| `enable` / `off` | the permission dance |
| `read` | numbered nodes: `[token] Class* (l,t,r,b) "label"`, `*` = clickable |
| `tap` | by token; falls back to an ancestor click, then a gesture at the centre |
| `tapxy` | raw coordinates, for self-drawn controls with no usable node |
| `type` | sets text via `ACTION_SET_TEXT` — the node must be an `EditText*` |
| `down` / `up` / `swipe` | scroll and drag |
| `back` / `home` | global actions |
| `packages` | whitelist, one package per line in `filesDir/screen_packages.txt` |

Tokens are only valid until the **next read** — re-read after anything changes the page.

### Screenshots, and when you need them

`py_screen(operation="shot")` writes `filesDir/screen_shot.png` (1200×2670 on this phone) via
`AccessibilityService.takeScreenshot` — no shell, no MediaProjection dialog. Read it back with
`view_image`.

Use it when the tree is empty: Taobao's spec sheet, cart and search pages return a 2-node tree,
so the only way to know what is on screen is to look. Screenshot → decide coordinates →
`tapxy` → screenshot again to verify. Never batch blind taps.

**Coordinate space:** the screenshot is the *real* display (1200×**2670**) while the app window
metrics are 1200×**2397**; gestures use display space, so scale from the screenshot only — do not
mix in `getDisplayMetrics()`.

**Taps can land on the wrong app.** If Evergrow comes to the foreground between tool calls, a
coordinate tap hits the chat instead of the target. Launch the app and do the taps **inside the
same tool call**, so the target stays front for the whole sequence.


## Working method

1. Scope first: `packages="com.taobao.taobao,com.hktv.android.hktvmall,com.google.android.youtube"`.
   An empty file means "all apps", which is the wrong default for a job.
2. Launch with `getLaunchIntentForPackage` + `FLAG_ACTIVITY_NEW_TASK`. Deep links into another
   app are denied from the background (`Permission Denial`), so do not rely on them.
3. Read, then act on one node, then re-read. Do not batch taps against a stale tree.
4. Verify by state, not by return code: `ok: click` means the event was injected, not that the
   app reacted. A player's `SeekBar` description, a search field's text, a results count —
   those are proof.
5. When a tree comes back as a few bare `ViewGroup`s right after typing, read again; it settles
   in a second or two.

## App notes

### Taobao (com.taobao.taobao)

- Search: bottom `TabHost` → 首页 → the search bar is a **non-clickable** `FrameLayout`
  labelled **搜索栏** (~y 390–527); tapping it falls back to a gesture, which works. The
  clickable neighbour labelled 搜索 runs whatever is already in the box — usually not what
  you want. Then `EditText*`, type **simplified** characters, re-read, tap `Button*` 搜索.
- Results are self-drawn: titles and prices appear inside `View*` text as
  `"<title> <price>元"`; parse the text, ignore the structure. Many bounds are inverted
  (right edge smaller than left).
- Scroll with `down`; one screen per call, collecting title/price pairs across pages.
- A listing carries what matters: 已售 count, `宝贝质量 4.9` / `88VIP好评率 96%`,
  `物流速度 5.0 次日达`, review text nodes, and 问/答 pairs. 包郵到港 appears as a badge.
- **Adding to cart:** tree-tap `加入购物车` on the product page (the node exists there) → the
  spec sheet opens with the right variant. Then a **coordinate tap on the big bottom button**
  (≈ 601,2403) works. The sheet's small `+` / `−` stepper **ignores injected taps entirely** —
  measured with both a 60 ms tap and a 250 ms press, while the sheet's ✕ closed fine. So add
  one at a time and adjust quantity elsewhere, or ask the user to nudge the stepper.
- **Never order or pay without an explicit instruction in that turn.** Variant, address and
  payment are the user's call; a mis-tap costs real money.

### YouTube (com.google.android.youtube)

- Clean, labelled tree — no coordinate taps needed.
- `ImageView* "Search"` → `EditText* "Search YouTube"` → type → **tap the first suggestion
  row**: there is no submit button, the suggestion list is the submit.
- Results: the channel card comes first; the first *video* is a `Button*` whose label is
  `"<title> – <runtime>"`, with `"<title>"` also repeated as a `ViewGroup`.
- Proof of playback: a `SeekBar*` described as `"0 minutes 7 seconds of 1 hour 54 minutes…"`.

### HKTVmall (com.hktv.android.hktvmall)

Untested. Likely a WebView hybrid, so expect a sparse tree and reach for `tapxy`.

## Known limits

- No screenshots: the bridge exposes the tree and gestures only, so purely graphical controls
  cannot be interpreted. Ask the user when a control has neither text nor sane bounds.
- Shell injection is unavailable here (`input`, `am start`, `screencap` are all denied for an
  unprivileged UID) — which is exactly why the accessibility bridge exists.
- If `py_screen` reports `ERR: no response from the main process`, the app process may have
  been killed, or the tool's own module is stale after an edit; a restart clears the latter.

## Why `input` cannot be used

The app's shell runs as its own unprivileged UID. `input tap/keyevent` fails with
`SecurityException: Injecting input events requires INJECT_EVENTS`; `settings`, `wm`,
`dumpsys` and `screencap` are permission-denied. Read state through the accessibility
tree and screenshots, and drive the UI through the bridge — never through shell input.
Key events are inconsistent even when they appear to work: verify each step landed.

## Screenshots are the fallback, and often the primary tool

`read` can come back as **one or two nodes** while the app is plainly on screen. Two
different causes, same symptom:

- the page is **self-drawn** (Taobao's search page, order tracking, many 活动 pages), or
- the service reports `ERR: no active window` even though `status` says
  `foreground=com.taobao.taobao` — the window root is not reachable.

Neither means the bridge is broken. `py_screen(operation="shot")` still works, and you can
read the page by eye. Take the screenshot, decide coordinates, tap, screenshot again.
Never claim "the screen cannot be read" without trying `shot` first.

Because a screenshot is a still, do the decision-making yourself: read the Chinese text in
the image, work out what the page is, then tap.

## You cannot type into a self-drawn field

`type` needs an `EditText` token. On Taobao's search page there is none — the whole page is
two nodes. So **avoid any flow that needs typing**: reach a product through 足迹 (browsing
history), 我的订单 → 待发货, or a 店铺 you already opened, and navigate by taps only.

## Every launch resets the app

`getLaunchIntentForPackage` brings Taobao back to its **home** page, not where you left it.
Since Evergrow tends to reclaim the foreground between tool calls, each Python call must
re-launch and re-navigate. Put the whole sequence — launch, sleeps, taps, `shot` — inside
**one** call, and use generous sleeps (5–8 s after launch).

Verified Taobao path (1200×2670 display):

```
我的淘宝 tab      (1090, 2420)
待发货            ( 405,  888)
评价 tab          ( 440,  317)
查看全部          (1053,  442)
```

Order-list layouts shift between Taobao builds, so screenshot after each new page rather
than trusting a coordinate twice.

## What a Taobao product page actually tells you

- The 评价 tab shows 好评/中差评 counts and the shop's 宝贝质量 / 物流速度 / 服务保障 scores.
- Beware **默认好评**: often nearly all "good" reviews are blank defaults. `50 好评` with
  one written review means one usable opinion, not fifty.
- 物流速度 is frequently the only low score; that's a delivery complaint, not a product one.
