#!/usr/bin/env python3
"""从已缓存的 wiki 页提取刷级相关数据：

  weaponDamage.json   武器伤害阶梯（剑/匕首/锤/弹弓），伤害是区间取 min/max
  skillXpSources.json 鱼类 Base XP + 采矿经验（Rock Type|Experience）

数据真源：英文 stardewvalleywiki.com。

    python tools/build_skill_data.py
"""
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from parse_wiki_tables import load_tables  # noqa: E402

DATA = os.path.abspath(os.path.join(HERE, "..", "app", "src", "main", "assets", "data"))

# Sword 页里各属性表对应的武器大类
SWORD_TABLES = {1: "剑", 2: "匕首", 3: "锤/棍", 4: "弹弓", 6: "特殊武器"}


def rng(s):
    """'2-5' → (2,5)；'15-25' → (15,25)；'4' → (4,4)；取不到返回 (None,None)。"""
    if not s:
        return None, None
    t = str(s).replace("–", "-").replace("—", "-")
    m = re.search(r"(\d+)\s*-\s*(\d+)", t)
    if m:
        return int(m.group(1)), int(m.group(2))
    m = re.search(r"\d+", t)
    return (int(m.group()), int(m.group())) if m else (None, None)


def price(s):
    m = re.search(r"(\d[\d,]*)\s*g", str(s or ""))
    return int(m.group(1).replace(",", "")) if m else None


def build_weapons():
    tables = load_tables("Sword")
    out = []
    for ti, kind in SWORD_TABLES.items():
        if ti >= len(tables) or not tables[ti]:
            continue
        head = tables[ti][0]
        idx = {}
        for i, h in enumerate(head):
            hl = h.strip().lower()
            if hl == "name":
                idx["name"] = i
            elif hl.startswith("damage"):
                idx["damage"] = i
            elif hl == "level":
                idx["level"] = i
            elif hl.startswith("location"):
                idx["loc"] = i
            elif hl.startswith("purchase"):
                idx["buy"] = i
            elif hl.startswith("critical"):
                idx["crit"] = i
        if "damage" not in idx or "name" not in idx:
            continue
        for row in tables[ti][1:]:
            if not row or len(row) <= idx["damage"]:
                continue
            name = re.sub(r"\s+", " ", row[idx["name"]]).strip()
            lo, hi = rng(row[idx["damage"]])
            if not name or lo is None:
                continue
            out.append({
                "name_en": name,
                "kind": kind,
                "level": (row[idx["level"]] if "level" in idx and idx["level"] < len(row) else ""),
                "dmg_min": lo,
                "dmg_max": hi,
                "crit": (row[idx["crit"]] if "crit" in idx and idx["crit"] < len(row) else ""),
                "where": (row[idx["loc"]] if "loc" in idx and idx["loc"] < len(row) else ""),
                "buy_g": price(row[idx["buy"]] if "buy" in idx and idx["buy"] < len(row) else None),
            })
    # 去重（同名同大类只留一条）
    seen, uniq = set(), []
    for r in out:
        k = (r["name_en"], r["kind"])
        if k in seen:
            continue
        seen.add(k)
        uniq.append(r)
    uniq.sort(key=lambda r: (r["kind"], r["dmg_max"] or 0))
    return uniq


def build_fish():
    head = load_tables("Fish")[0]
    ix = {h.strip().lower(): i for i, h in enumerate(head[0])}
    out = []
    for row in head[1:]:
        if len(row) <= 9:
            continue
        name = re.sub(r"\s+", " ", row[ix.get("name", 1)]).strip()
        xp = re.search(r"\d+", row[ix["base xp"]]) if "base xp" in ix and ix["base xp"] < len(row) else None
        if not name or not xp:
            continue
        out.append({
            "name_en": name,
            "xp": int(xp.group()),
            "price": price(row[ix["price"]] if "price" in ix else ""),
            "location": row[ix["location"]] if "location" in ix else "",
            "difficulty": row[ix["difficulty & behavior"]] if "difficulty & behavior" in ix else "",
        })
    return out


def build_mining():
    t = load_tables("Mining")[1]
    out = []
    for row in t[1:]:
        if not row:
            continue
        rock = re.sub(r"\s+", " ", row[0]).strip()
        m = re.search(r"(\d+)", row[1]) if len(row) > 1 else None
        if rock and m:
            out.append({"rock": rock, "xp": int(m.group(1))})
    return out


def main():
    weapons = build_weapons()
    fish = build_fish()
    mining = build_mining()

    files = {
        "weaponDamage.json": weapons,
        "fishXp.json": fish,
        "miningXp.json": mining,
    }
    for fn, data in files.items():
        p = os.path.join(DATA, fn)
        with open(p, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
        print(f"{fn:<20} {len(data):>4} 条")

    print("\n武器伤害阶梯（前 10）：")
    for w in weapons[:10]:
        print(f"   {w['kind']:<6} {w['name_en']:<24} {w['dmg_min']}-{w['dmg_max']}  售价 {w['buy_g']}")
    print(f"\n采矿经验（前 6）：")
    for m in mining[:6]:
        print(f"   {m['rock'][:46]:<48} {m['xp']}")


if __name__ == "__main__":
    main()