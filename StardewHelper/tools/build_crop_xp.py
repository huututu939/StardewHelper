#!/usr/bin/env python3
"""从作物页 infobox 提取 Farming XP → assets/data/cropXp.json。

作物经验只写在单个作物页（Ancient Fruit → "XP 38 Farming XP"），
Crops 总页只有生长阶段图、没有 XP 列。判定是不是作物页：infobox 里有「Growth Time」。

    python tools/build_crop_xp.py
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from parse_wiki_tables import load_infobox  # noqa: E402

RAW = os.path.join(HERE, "wiki_raw_v213_crops")
OUT = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets", "data", "cropXp.json"))


def main():
    if not os.path.isdir(RAW):
        sys.exit("缺缓存目录，先跑 fetch_crops_v214.py")
    files = [f[:-5] for f in os.listdir(RAW) if f.endswith(".html")]
    out, skipped = [], []
    for slug in sorted(files):
        name, ib = load_infobox(slug, raw_dir=RAW)
        if not name or "Growth Time" not in ib:
            skipped.append(slug)
            continue
        xp_raw = ib.get("XP", "")
        m = re.search(r"(\d+)", xp_raw)
        if not m:
            skipped.append(slug)
            continue
        growth = ib.get("Growth Time", "")
        out.append({
            "name_en": name,
            "xp": int(m.group()),
            # wiki 对再生作物写「XP 14 Farming XP per harvest」，单次收获经验相同、
            # 但按「经验/天」算时要用重复收获次数，必须单独标出来
            "per_harvest": "per harvest" in xp_raw.lower(),
            "growth_days": growth,
            "season": re.sub(r"\s*•\s*", "/", ib.get("Season", "")).replace(" ", " ").strip(),
            "sell": re.sub(r"\s+", " ", ib.get("Sell Prices", "")).strip(),
        })

    out.sort(key=lambda r: r["name_en"])
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print(f"作物 {len(out)} 条 → {OUT}\n（跳过非作物页 {len(skipped)} 个）\n")
    for r in out[:15]:
        flag = "  ⟳再生" if r["per_harvest"] else ""
        print(f"  {r['name_en']:<20} XP {r['xp']:>4}   生长 {r['growth_days'][:18]:<20}"
              f" 季节 {r['season'][:20]}{flag}")


if __name__ == "__main__":
    main()