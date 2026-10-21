---
name: news-briefing
description: Produce a news briefing across Hong Kong news, technology news, geopolitics,
  and Premier League football. Use this skill whenever the user asks for news, headlines,
  "what's happening in Hong Kong", tech/AI news, world or geopolitical news, football or
  Premier League results and fixtures, a daily briefing, or "what's the latest" — even if
  they name only one category. Also use it whenever news is to be read aloud.
---

# News briefing

Categories in, engines out. The rule underneath: **DeepSeek is a Chinese-first index; Google
(via SerpApi) is the Western/default index.** Choose by *what the content is*, never by the
language of the question. Google through SerpApi returns Google's own ranking, top stories and
an AI overview, and it is not region-blocked here — it replaced Google News (SerpApi), which took 13–25 s per
query against 0.9–1.4 s for SerpApi.

## Default: two tiers, fast one first

Use the **`py_news` tool** rather than hand-rolling requests — it implements this whole section.

```python
py_news(mode="fast", sections="hk,world,tech,football")   # ~1 s, dated headlines, starts the deep fetch
py_news(mode="status")                                     # is the deep fetch done yet?
py_news(mode="deep")                                       # the Google News write-ups, already cached
```

How the trick works: `mode=fast` returns the RSS/DeepSeek headlines *and* kicks off the Google News
queries on a background Python thread that keeps running while the reply is being spoken. The
cache is rewritten after **each** section finishes, so `mode=deep` can hand back partial results
the moment the first one lands instead of waiting for all of them.

So the flow is: answer from the fast batch, let it be read aloud, then call `mode=deep` — by
then the write-ups are usually sitting in the cache (measured: world+tech+hk all done ≈ 50 s,
first section usually under 20 s).

### Tier 0 — always first, ~1 s total (run in parallel)

| Section | Route | Measured |
|---|---|---|
| World | Google News RSS `q=world news` | 0.6–1.0 s |
| Technology | Google News RSS `q=technology AI` | 0.6 s |
| Hong Kong | Google News RSS `q=Hong Kong` + SCMP RSS `rss/91` + DeepSeek | 0.1 / 0.3 / 0.5 s |
| Premier League | Google News RSS `q=Premier League` | ~0.5 s |

Answer from Tier 0 and stop there unless the user asks for depth. Say what you have, not what
you're still waiting for.

### Tier 1 — deep dive, never blocking

Google News (SerpApi) via OpenRouter `:online`, run in parallel across sections (10–25 s each). Use it for depth,
analysis, or when a category came back thin. Do **not** block the first answer on it. If you
escalate, say so plainly ("going deeper on that one").

## Routing notes (measured 2026-10-18)

- **Do not send Western queries to DeepSeek.** It answers in 0.35 s but returns *section front
  pages* with no dates — `bbc.com/?t=16325#1`, `nytimes.com/?res=9E0CE0DE…`, `qz.com/latest`.
  Unusable as headlines. It earns its place only for Hong Kong local coverage.
- **Google News RSS is genuinely Google** and free for any query — not just Hong Kong.
- Google News (SerpApi) without `include_domains` returns aggregator blogs; always pass the domain list.

## Hong Kong — three sources, merged

One engine alone is not enough here. DeepSeek is live but returns section front pages;
the RSS feeds are cleaner but lag. Merge all three and dedupe by title.

```python
# 1) DeepSeek — Chinese local outlets
POST https://api.deepseek.com/v1/tools/search
Authorization: Bearer <settings/llm/saved_configs.json, active config api_key>
{"query": "香港 新聞 今日 頭條", "count": 8, "need_content": False}

# 2) Google News RSS (English HK)
GET https://news.google.com/rss/search?q=Hong+Kong&hl=en-US&gl=US&ceid=US:en

# 3) SCMP RSS  — NOTE: rss/91 is the general feed, rss/4 is SCMP's *China* section, NOT Hong Kong
GET https://www.scmp.com/rss/91/feed
```

Parse RSS with `<item>` / `<title>` / `<pubDate>`. Filter SCMP's general feed for Hong Kong
keywords rather than trusting it wholesale — it mixes in world news.

## Technology / Geopolitics / Premier League — Google News (SerpApi), domain-curated

```python
POST https://openrouter.ai/api/v1/chat/completions
Authorization: Bearer <files/tool_output/openrouter/.token>
{
  "model": "deepseek/deepseek-v4-flash:online",
  "plugins": [{"id": "web", "engine": "exa",
               "include_domains": ["reuters.com","techcrunch.com","theverge.com",
                                   "arstechnica.com","bloomberg.com"]}],
  "messages": [{"role": "user", "content": "..."}]
}
```

`include_domains` is what makes this usable. Without it, "top technology news today" returns
**aggregator blogs** (`ai-news-online.com`, `aibriefs.news`, `terabrief.com`). With it, the
same query returned TechCrunch, Reuters and The Verge.

Sources arrive in `choices[0].message.annotations[].url_citation.url`. They are often
**section pages** (`techcrunch.com/2026/10/`) rather than article permalinks — cite the
domain, don't pretend it's a deep link.

For geopolitics add `aljazeera.com`, `apnews.com`, `axios.com`. For football add
`premierleague.com`, `bbc.com`, `espn.com`, `nbcsports.com`.

## Google is not available

Every sanctioned Google path is closed from Hong Kong: CSE JSON API is not open to new
customers (sunsets 1 Jan 2027); Gemini `google_search` grounding is region-blocked both
directly *and* through OpenRouter (403 "not available in your region"). Do not promise Google
results. Real Google links would need Serper — a scraper, not an official API. Google **News**
RSS is genuinely Google and free; use it.

## Output

- **3–5 items per category**, newest first, each with source and time.
- Markdown table for reading. **If voice mode is on**, speak a 2–3 sentence summary of the
  single most important item — never read a table aloud.
- Say plainly when a category has nothing new rather than padding it.
- Never invent a story. If an engine returns junk, name what failed.
- A result without a visible date is not "today's news".

## Known failure modes

| Symptom | Cause | Fix |
|---|---|---|
| All results are `/zh-cn/`, `/fa/`, `/pt-br/` of one URL | DeepSeek locale drift | re-run that query on Google News (SerpApi) |
| Results are `ai-news-online.com`, `aibriefs.news` | Google News (SerpApi) without domain filter | add `include_domains` |
| HK section is all section front pages | DeepSeek returned nav pages | lead with RSS items instead |
| 20–25 s before anything appears | Google News (SerpApi) latency | run it in parallel with a fast route |
