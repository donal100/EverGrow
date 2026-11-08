---
name: maps-and-places
description: Find places and get there — cafés, restaurants, shops, stations, "what's near me", "how long to walk to X", distances and opening hours. Use whenever the answer depends on a real-world location or on Google Maps data. Also load before reaching for OpenStreetMap or any free geo API — it explains which sources work here and which ones time out.
---

# Places and routes

Two good sources, one habit to avoid: reaching for free-by-default geo APIs before
checking what's already keyed on the device.

## Place search — SerpApi, engine=google_maps

Returns real Google Maps listings: name, address, rating, review count, open/closed
state. ~1 s. Token: `files/tool_output/serpapi/.token`.

```python
import requests
key = open("/data/user/0/<pkg>/files/tool_output/serpapi/.token").read().strip()
r = requests.get("https://serpapi.com/search", params={
    "engine": "google_maps", "q": "coffee", "ll": "@22.4667,114.0052,15z",
    "type": "search", "hl": "en", "gl": "hk", "api_key": key}, timeout=30)
for p in r.json().get("local_results", []):
    print(p["title"], p.get("address", "")[:60], p.get("rating"), p.get("reviews"),
          p.get("open_state"))
```

Notes that matter:

- `q` is free text. Geography comes from `ll` **and** from the words in `q` —
  `q="coffee"` with an `ll` in Tin Shui Wai returned *Yuen Long* results, while
  `q="coffee Tin Shui Wai"` returned the right neighbourhood. Put the district in `q`.
- There is no distance field: rank by the order returned, and say district rather
  than inventing metres.
- Google's `local_results` carry `rating` and `reviews` — quote them; they are the
  most useful thing to tell a person choosing between three cafés.

## Directions — Google Routes API

Walking and driving, turn-by-turn, with street names. Key:
`files/google_maps_key.txt`. Wrapped by the `py_navigate` tool:

```
py_navigate(action="start", destination="TEA & CORNER Wetland Seasons Bay")
py_navigate(action="status")     # next turn, metres, heading check
py_navigate(action="stop")
```

`py_navigate` geocodes the destination with Nominatim (OSM) and routes with Google.
That split is deliberate: geocoding without a key survives a Google quota failure.

## Reverse geocoding — Nominatim

Cheap and keyless for "where am I": `nominatim.openstreetmap.org/reverse`, send a
`User-Agent`, ≤1 req/s. Returns `濕地公園路, 天水圍北, 元朗區` style addresses.

## Why not Overpass

Overpass (OSM's query API) is the obvious free choice for "cafés within 900 m" and it
is the wrong one here: measured repeatedly timing out at 900 m and 2.5 km radii,
against ~1 s for the SerpApi Maps call. Use OSM for **geocoding**, not for finding
places, and only fall back to it when no SerpApi token exists.

## Reading a location request

- **No fix** — `py_gps` can return null even with permissions granted (indoors, no sky
  view). Say so and ask for a district; do not guess an area from a stale fix, and
  never present an old GPS point as current.
- The fix arrives with `age_s` and `accuracy_m`; quote accuracy when it is poor
  (>50 m) rather than implying precision.
- If the user says "near me" and no fix is available, one retry (~20 s) is reasonable,
  then ask.

## Answering well

Lead with the one place you'd actually send them and why (open now, closest, best
rated), then a short table of the rest. Include open/closed state and closing time —
a 4.6-star café that shut five minutes ago is not a recommendation.
