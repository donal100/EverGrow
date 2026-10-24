"""Full-text helpers for Hong Kong news: Ming Pao (free) and SCMP (cookie session).

    import sys; sys.path.insert(0, "<skills>/news-briefing/scripts")
    import hknews
    hknews.mingpao()                 # [(date, title, link)]  要聞
    hknews.mingpao("s00002")         # 港聞
    hknews.mingpao_body(link)        # full article text
    hknews.scmp()                    # [(date, title, link)] from rss/91
    hknews.scmp_body(link)           # full body via the saved cookie session
"""
import json
import re

UA = ("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) "
      "Chrome/124.0.0.0 Mobile Safari/537.36")
H = {"User-Agent": UA, "Accept-Language": "zh-HK,zh;q=0.9,en;q=0.8"}

MP_RSS = "https://news.mingpao.com/rss/pns/%s.xml"
SCMP_RSS = "https://www.scmp.com/rss/91/feed"


def _field(item, name):
    m = re.search(r"<%s>(?:<!\[CDATA\[)?(.*?)(?:\]\]>)?</%s>" % (name, name), item, re.S)
    return re.sub(r"<[^>]+>", "", m.group(1)).strip() if m else ""


def _items(xml):
    return re.findall(r"<item>(.*?)</item>", xml, re.S)


def mingpao(section="s00001"):
    import requests
    r = requests.get(MP_RSS % section, headers=H, timeout=25)
    r.encoding = "utf-8"          # the feed declares UTF-8; requests guesses wrong
    return [(_field(i, "pubDate"), _field(i, "title"), _field(i, "link"))
            for i in _items(r.text)]


def mingpao_body(link):
    import requests
    r = requests.get(link, headers=H, timeout=25)
    ps = re.findall(r"<p[^>]*>(.*?)</p>", r.text, re.S)
    out = []
    for p in ps:
        t = re.sub(r"<[^>]+>", "", p)
        t = re.sub(r"\s+", " ", t).strip()
        if len(t) > 20 and "outdated browser" not in t:
            out.append(t)
    return "\n\n".join(out)


def scmp():
    import requests
    r = requests.get(SCMP_RSS, headers=H, timeout=25)
    r.encoding = "utf-8"
    return [(_field(i, "pubDate"), _field(i, "title"), _field(i, "link"))
            for i in _items(r.text)]


def scmp_session():
    """Reuse the paywalled-reading helper so there is only one cookie parser."""
    import sys, os
    skills = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    sys.path.insert(0, os.path.join(skills, "paywalled-reading", "scripts"))
    import webread
    return webread.session_from("tool_output/scmp/cookies.txt", ".scmp.com")


def scmp_body(link):
    """Prefer the articleBody JSON SCMP embeds; fall back to <p> scraping."""
    s = scmp_session()
    html = s.get(link, timeout=30).text
    m = re.search(r'"articleBody"\s*:\s*("(?:[^"\\]|\\.)*")', html, re.S)
    if m:
        return re.sub(r"<[^>]+>", "", json.loads(m.group(1)))
    ps = re.findall(r"<p[^>]*>(.*?)</p>", html, re.S)
    return "\n\n".join(t for t in (re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", p)).strip()
                                    for p in ps) if len(t) > 60)


def paywalled(html):
    return ("Subscribe to continue" in html) or ("available to subscribers" in html)
