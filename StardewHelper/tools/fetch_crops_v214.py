#!/usr/bin/env python3
"""抓取所有作物页（拿经验值）——作物经验只写在单个作物页 infobox 里，
Crops 总页只有生长阶段图，没有 XP 列。

    python tools/fetch_crops_v214.py
"""
import os
import re
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "wiki_raw_v213_crops")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) StardewHelperDataBot/1.0 (offline game helper data sync)"
BASE = "https://stardewvalleywiki.com/"

SKIP = {"Crops", "Fruit_Trees", "Trellis_Crops", "Wild_Horse", "Special_Crops",
        "Stages", "Crops#Stages", "Golden_Walnut", "Crop"}


def slugs():
    h = open(os.path.join(HERE, "wiki_raw_v213", "Crops.html"), encoding="utf-8", errors="replace").read()
    seen, out = set(), []
    for slug in re.findall(r'<a href="/([A-Za-z0-9_%\'\-\.]+)"', h):
        slug = urllib.request.quote(slug)
        if slug in seen or slug in SKIP:
            continue
        seen.add(slug)
        out.append(slug)
    return out


def main():
    os.makedirs(RAW, exist_ok=True)
    ss = slugs()
    print(f"待抓 {len(ss)} 个作物页 → {RAW}\n", flush=True)
    ok, fail = 0, []
    for n, slug in enumerate(ss, 1):
        dest = os.path.join(RAW, urllib.request.quote(slug, safe="") + ".html")
        if os.path.exists(dest) and os.path.getsize(dest) > 2000:
            ok += 1
            continue
        try:
            req = urllib.request.Request(BASE + urllib.request.quote(slug), headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=25) as r:
                raw = r.read()
            if len(raw) > 2000:
                open(dest, "wb").write(raw)
                ok += 1
            else:
                fail.append(slug)
        except Exception as e:
            fail.append(slug)
        if n % 10 == 0:
            print(f"  {n}/{len(ss)} 成功 {ok}", flush=True)
        time.sleep(1.0)
    print(f"\n完成：成功 {ok} / 共 {len(ss)}", flush=True)
    if fail:
        print("失败：" + ", ".join(fail[:20]), flush=True)


if __name__ == "__main__":
    main()