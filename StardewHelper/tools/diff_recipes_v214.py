# -*- coding: utf-8 -*-
"""v2.14：菜谱全量比对 —— App 现有 vs 官方 81 道。

以 stardew_valley_1.6_data_v6/recipes.json（官方 81 道，源：EN+ZH Cooking 总表）为基准，
按材料集合相似度把 App 现有条目对齐到官方条目，输出三类问题：
  1) 缺失   —— 官方有、App 没有
  2) 改名   —— 是同一道菜，但名字不对
  3) 材料错 —— 对上了，但材料清单不一致
另外单列 App 多余条目（官方 81 道里根本没有的）。

用法：python tools/diff_recipes_v214.py
"""
import io
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = _REPO_ROOT() + '/stardew_valley_1.6_data_v6'
APP = os.path.join(ROOT, "app", "src", "main", "assets", "data", "recipes.json")
OUT = os.path.join(ROOT, "tools", "recipes_diff_v214.json")


def load(p):
    return json.load(io.open(p, encoding="utf-8"))


def sim(a, b):
    """材料集合 Jaccard 相似度"""
    A, B = set(a), set(b)
    if not A or not B:
        return 0.0
    return len(A & B) / float(len(A | B))


def main():
    official = load(os.path.join(PKG, "recipes.json"))["data"]["recipes"]
    app = load(APP)

    off = []
    for r in official:
        off.append({
            "name": r["name"],
            "name_en": r["name_en"],
            "ing": [i["item"] for i in r.get("ingredients", [])],
            "energy": r.get("energy"),
            "health": r.get("health"),
            "sell": r.get("sell_price"),
            "source": r.get("source"),
            "source_detail": r.get("source_detail"),
            "new16": r.get("is_new_1_6"),
        })

    appn = [a["name"] for a in app]
    exact = set(appn) & set(o["name"] for o in off)

    used = set()
    pairs = []       # (app_item, off_item, sim)
    for a in app:
        ai = [i["name"] for i in a.get("ingredients", [])]
        best, bs = None, 0.0
        for o in off:
            if o["name"] in used:
                continue
            s = sim(ai, o["ing"])
            if s > bs:
                best, bs = o, s
        if best is not None and bs >= 0.34:
            used.add(best["name"])
            pairs.append((a, best, bs))

    missing = [o for o in off if o["name"] not in used]
    extra = [a for a, o, s in pairs if s < 0.999 and a["name"] not in exact]
    renamed = [(a, o, s) for a, o, s in pairs
               if a["name"] != o["name"] and sim([i["name"] for i in a.get("ingredients", [])], o["ing"]) >= 0.5]
    ingdiff = []
    for a, o, s in pairs:
        ai = [i["name"] for i in a.get("ingredients", [])]
        if a["name"] == o["name"] and ai != o["ing"]:
            ingdiff.append((a, o))

    L = []
    L.append("=" * 74)
    L.append("App %d 条  vs  官方 %d 条  （官方少 %d / App 多 %d）"
             % (len(app), len(off), len(missing), len(extra)))
    L.append("=" * 74)

    L.append("\n【1】App 缺失 —— 官方有，App 没有（%d 道）" % len(missing))
    for o in missing:
        L.append("   %-14s %-20s %s  | %s/%s  %sg  [%s]"
                 % (o["name"], o["name_en"], "+".join(o["ing"]),
                    o["energy"], o["health"], o["sell"], o["source_detail"] or o["source"]))

    L.append("\n【2】名称需改 —— 是同一道菜，App 名字不对（%d 道）" % len(renamed))
    for a, o, s in renamed:
        L.append("   App「%s」 → 官方「%s」  (%s)" % (a["name"], o["name"], o["name_en"]))

    L.append("\n【3】材料需改 —— 名字对得上，但材料清单不一致（%d 道）" % len(ingdiff))
    for a, o in ingdiff:
        ai = [i["name"] for i in a.get("ingredients", [])]
        L.append("   %-14s App: %s" % (a["name"], " + ".join(ai)))
        L.append("   %-14s 官方: %s" % ("", " + ".join(o["ing"])))

    L.append("\n【4】App 多余 —— 官方 81 道里没有这个东西（%d 条）" % len(extra))
    for a in extra:
        ai = [i["name"] for i in a.get("ingredients", [])]
        L.append("   %-14s %s" % (a["name"], " + ".join(ai)))

    txt = "\n".join(L)
    print(txt)
    io.open(OUT, "w", encoding="utf-8").write(txt)
    print("\n已写出 %s" % OUT)


if __name__ == "__main__":
    main()