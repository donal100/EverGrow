---
name: paywalled-reading
description: Read articles and pages the agent cannot fetch with plain HTTP — JavaScript single-page sites, and anything behind a login such as an SCMP subscription. Use this whenever a fetch returns a teaser, an empty shell, or a "subscribe to continue" wall, whenever the user asks for a story from South China Morning Post or another subscriber site, or whenever a page needs JavaScript to render. Covers the in-app login window, harvesting the WebView cookie jar, and replaying those cookies from Python.
---

# Reading pages that need JavaScript or a login

Plain `requests` handles static HTML. It fails in two distinct ways, and they need
different tools:

| Symptom | Cause | Tool |
|---|---|---|
| Empty HTML, `<div id="root">` only | page rendered by JavaScript | in-app WebView (`fetch`) |
| Headline and a teaser, no body | paywall — no session cookie | log in once in the app's WebView, then replay the cookie jar |

Both paths go through the same hidden WebView, which lives in the main process
because Python (`:python`) cannot touch it.

## Talking to the bridge

Commands travel as file + broadcast, exactly like the screen bridge:

```python
import sys; sys.path.insert(0, "<skills>/paywalled-reading/scripts")
import webread
webread.send("fetch", url="https://www.scmp.com/", delay_ms=2500)
```

`send()` writes `filesDir/webview_cmd.json`, broadcasts to
`com.niki914.zafiro.app.web.WebViewCmdReceiver` **by explicit component** (the
receiver has no intent-filter, so an action-only broadcast does nothing at all),
then polls `webview_result.json`.

| op | args | returns |
|---|---|---|
| `fetch` | `url`, `js`?, `delay_ms`?, `timeout_ms`? | page text (or the JS result) |
| `js` | `js` | runs on the **already loaded** page, no navigation |
| `cookies` | `url` | the cookie header for that URL |
| `savecookies` | `url`, `path` | writes the jar to `filesDir/<path>` |
| `setcookie` | `url`, `cookie` | injects cookies into the WebView jar |
| `open` | `url` | opens the visible login window (an Activity) |

**Always pass `delay_ms` for a single-page app.** `onPageFinished` fires before
React paints; SCMP's login page returned literally `""` with no delay and 362
characters of real UI with `delay_ms=3500`.

## Logging in once

Scripted clicking does **not** work on these apps. Measured on
`account.scmp.com/login`: dispatching `pointerdown`, `mousedown`, `pointerup`,
`mouseup` and `click` at the "Sign in with Email" element left the DOM byte-for-byte
unchanged, and the only real `<button>` on the page is the cookie `ACCEPT`. Do not
burn time on `dispatchEvent`.

The working method is a visible window:

1. `webread.send("open", url="https://account.scmp.com/login")` — a real Activity
   with a WebView appears on the user's screen.
2. The **user** taps and types. The agent never handles the password.
3. They press back; the activity flushes the cookie jar.
4. `savecookies` for the site's domain → `filesDir/tool_output/<site>/cookies.txt`.
5. From then on, Python fetches with `webread.session_from(path, domain)`.

This is a one-time step. The WebView jar lives in the app's private storage and
survives restarts and updates; a copy in `tool_output` is what Python replays.

## Reading an article

```python
s = webread.session_from("tool_output/scmp/cookies.txt", ".scmp.com")
html = s.get(url, timeout=30).text
body = webread.article_text(html)
```

SCMP RSS gives the URLs without any session: `https://www.scmp.com/rss/91/feed`
(general), `rss/4` (China). Pull article links from there rather than guessing.

## Detecting an expired session

Two cheap checks, in order:

- Fetch the homepage in the WebView; if the rendered text contains `SIGN IN`, the
  session is gone.
- Or fetch a known subscriber article with the cookies and look for
  `Subscribe to continue` / `available to subscribers` in the body.

When it has expired, say so and offer the login window again — do not silently
report a teaser as the article.

## Rules

- Only fetch what the user asked about. Replaying a subscription cookie to mirror a
  site in bulk is a different activity from reading one article, and it is the
  user's account at stake.
- Cookie dumps and credentials are secrets: `files/tool_output/` with mode `0600`,
  never shared storage, never echoed into chat.
- Report paywalls honestly. "I only got the teaser" is a useful answer; inventing
  the rest of the article is not.

## Limits

- The hidden WebView cannot be tapped, only scripted; anything requiring a real
  touch needs the visible window.
- No headless Chromium exists here (no Playwright, no binaries via Chaquopy), so
  the WebView is the only browser engine available.
- `article_text()` is deliberately crude — `<p>` blocks with tags stripped. If a
  site nests its body differently, inspect the HTML before trusting the output.
