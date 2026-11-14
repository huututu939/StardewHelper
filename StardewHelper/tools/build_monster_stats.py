#!/usr/bin/env python3
"""汇总怪物属性（HP / 伤害 / 防御 / 速度 / 经验）→ assets/data/monsterStats.json。

两路取数：
  A. 单怪页 infobox  `<table id="infoboxtable">` 的 Base HP / Base Damage / Base Def /
     Speed / XP / Spawns In / Floors —— 大多数怪物走这条（Bug、Mummy、Serpent …）
  B. 家族页属性表  表头同时含 HP 与 XP 的 wikitable —— 家族页 infobox 写的是
     "24, 36, 80" 这类逗号列表（Bats、Slimes），只能从 Variations 表逐变种取

数据真源：英文 stardewvalleywiki.com（biligame 对本机 IP 567 限流，只用于中译名）。

    python tools/build_monster_stats.py [--show]
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from parse_wiki_tables import load_tables, load_infobox  # noqa: E402

OUT = os.path.join(HERE, "..", "app", "src", "main", "assets", "data", "monsterStats.json")
RAW = os.path.join(HERE, "wiki_raw_v213")

# 家族页属性表：表头 → 字段
COLS = {
    "hp": ["hp", "health"],
    "damage": ["damage"],
    "defense": ["def", "defense"],
    "speed": ["speed"],
    "xp": ["xp", "experience"],
}
# 这些页面不是怪物，别当怪物抓
NOT_MONSTER = {
    "Crops", "Fish", "Forage", "Mining", "Monsters", "Skill", "Sword", "Bug",
}
# 页面 slug → 中文译名（后面由中译脚本补，这里先留空）
NOT_A_MONSTER_PAGE = re.compile(
    r"(Witch|Hut|Options|Shrine|Combat|Skills|Slime_Hutch|Truffle$|_Hut$)", re.I)


def num(s):
    if not s:
        return None
    m = re.search(r"-?\d+", str(s).replace(",", ""))
    return int(m.group()) if m else None


def match_cols(header):
    idx = {}
    for i, h in enumerate(header):
        hl = h.strip().lower()
        for f, keys in COLS.items():
            if f in idx:
                continue
            if any(hl == k or hl.startswith(k + " ") for k in keys):
                idx[f] = i
                break
    return idx if ("hp" in idx and "xp" in idx) else None


def clean_name(s):
    return re.sub(r"\s+", " ", s or "").strip()


def from_infobox(slug):
    name, ib = load_infobox(slug)
    hp = num(ib.get("Base HP"))
    xp = num(ib.get("XP"))
    if not name or hp is None or xp is None:
        return None
    return {
        "name_en": name,
        "dangerous": slug.endswith("_(dangerous)"),
        "hp": hp,
        "damage": num(ib.get("Base Damage")),
        "defense": num(ib.get("Base Def")),
        "speed": num(ib.get("Speed")),
        "xp": xp,
        "where": clean_name(ib.get("Spawns In", "")),
        "floors": clean_name(ib.get("Floors", "")),
        "source": "infobox",
        "page": slug,
    }


def from_tables(slug):
    out = []
    for t in load_tables(slug):
        if not t:
            continue
        idx = match_cols(t[0])
        if not idx:
            continue
        for row in t[1:]:
            if not row or len(row) <= max(idx.values()):
                continue
            nm = clean_name(row[0])
            hp, xp = num(row[idx["hp"]]), num(row[idx["xp"]])
            if not nm or hp is None or xp is None or nm.lower() == "varies":
                continue
            out.append({
                "name_en": nm,
                "dangerous": slug.endswith("_(dangerous)"),
                "hp": hp,
                "damage": num(row[idx["damage"]]) if "damage" in idx else None,
                "defense": num(row[idx["defense"]]) if "defense" in idx else None,
                "speed": num(row[idx["speed"]]) if "speed" in idx else None,
                "xp": xp,
                "where": clean_name(row[idx["where"]]) if "where" in idx else "",
                "source": "table",
                "page": slug,
            })
    return out


def main():
    show = "--show" in sys.argv
    slugs = sorted(f[:-5] for f in os.listdir(RAW) if f.endswith(".html"))
    out, seen = [], set()
    stats = {"infobox": 0, "table": 0, "skipped": 0}

    for slug in slugs:
        if slug in NOT_MONSTER or NOT_A_MONSTER_PAGE.search(slug):
            stats["skipped"] += 1
            continue
        rec = from_infobox(slug)
        if rec:
            key = (rec["name_en"], rec["dangerous"])
            if key not in seen:
                seen.add(key)
                out.append(rec)
                stats["infobox"] += 1
            continue
        rows = from_tables(slug)
        for rec in rows:
            key = (rec["name_en"], rec["dangerous"])
            if key in seen:
                continue
            seen.add(key)
            out.append(rec)
            stats["table"] += 1
        if not rows:
            stats["skipped"] += 1

    out.sort(key=lambda r: (not r["dangerous"], r["hp"]))
    dest = os.path.abspath(OUT)
    with open(dest, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)

    print(f"infobox 取到 {stats['infobox']} · 属性表取到 {stats['table']} · "
          f"跳过(非怪物/无属性) {stats['skipped']}")
    print(f"合计 {len(out)} 条 → {dest}\n")
    for r in out[:12]:
        tag = "危险" if r["dangerous"] else ""
        print(f"  {r['name_en']:<26} HP {str(r['hp']):>5}  伤害 {str(r['damage']):>4}"
              f"  经验 {str(r['xp']):>4}  {tag}")
    if show and len(out) > 12:
        print(f"  … 其余 {len(out) - 12} 条见 JSON")


if __name__ == "__main__":
    main()