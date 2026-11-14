#!/usr/bin/env python3
"""批量抓怪物/家族页（v2.14刷级数据）。

英文 wiki 的 Monsters 索引页只有「出没矩阵」，HP/Damage/XP 分散在各怪物家族页的
属性表里（表头形如 Color|HP|Damage|Defense|Speed|XP|...）。本脚本先按索引抓回所有
家族页，再逐页扫描含HP+XP 的表。

    python tools/fetch_monsters_v214.py
"""
import os
import re
import time
import urllib.request
import urllib.error

HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.join(HERE, "wiki_raw_v213")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) StardewHelperDataBot/1.0 (offline game helper data sync)"
BASE = "https://stardewvalleywiki.com/"

# 从 Monsters 索引页抽到的链接里，去掉纯地点/导航页，保留怪物与家族
SKIP = {
    "The_Farm", "The_Mines", "Skull_Cavern", "Quarry_Mine", "Mutant_Bug_Lair",
    "Volcano_Dungeon", "Combat", "Skills", "Slime_Hutch", "Truffle",
    "Options", "Dark_Shrine_of_Night_Terrors", "Witch's_Hut",
    "Wilderness_Golem",
}


def read_cached(slug):
    p = os.path.join(RAW, slug + ".html")
    return open(p, "rb").read().decode("utf-8", "replace") if os.path.exists(p) else None


def fetch(slug, tries=3):
    cached = read_cached(slug)
    if cached and len(cached) > 2000:
        return cached
    for i in range(tries):
        try:
            req = urllib.request.Request(BASE + slug, headers={"User-Agent": UA})
            with urllib.request.urlopen(req, timeout=30) as r:
                raw = r.read()
            open(os.path.join(RAW, slug + ".html"), "wb").write(raw)
            return raw.decode("utf-8", "replace")
        except Exception as e:
            print(f"    [retry {i + 1}] {slug}: {type(e).__name__}")
            time.sleep(2.5 * (i + 1))
    return None


def monster_slugs():
    h = read_cached("Monsters")
    body = h[h.find("Monsters List"): h.find('id="History"')]
    seen, out = set(), []
    for slug, title in re.findall(r'<a href="/([^"#:]+)" title="([^"]+)">', body):
        if slug in seen or slug in SKIP:
            continue
        seen.add(slug)
        out.append(slug)
    return out


def main():
    slugs = monster_slugs()
    print(f"待抓 {len(slugs)} 个怪物/家族页\n")
    ok, fail = [], []
    for n, slug in enumerate(slugs, 1):
        h = fetch(slug)
        (ok if h else fail).append(slug)
        print(f"  [{n:>2}/{len(slugs)}] {slug:<28} {'OK' if h else 'FAIL'}")
        time.sleep(1.2)
    print(f"\n成功 {len(ok)}，失败 {len(fail)}")
    if fail:
        print("失败列表：" + ", ".join(fail))


if __name__ == "__main__":
    main()