# -*- coding: utf-8 -*-
"""v2.14：核对 App 81 道菜谱的中文名与官方中文维基是否一致。

背景：v2.13.1 的菜名来自 v6 数据包，而 v6 的中文名已被证实不可靠
（寿司卷 实为 生鱼寿司；鱼名 三文鱼/白斑狗鱼/木 skip鱼 全是机器直译）。
本脚本抓 zh.stardewvalleywiki.com/烹饪 的「图鉴」小节，取出官方全部菜名，
与 App 的 81 条逐条比对，产出「需改名 / 仅 App 有 / 仅维基有」三张清单。

只出报告，不改文件——改名必须人工过一遍再落。
用法：python tools/audit_recipe_names_v214.py
"""
import io
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "app", "src", "main", "assets", "data")
OUT = os.path.join(ROOT, "tools", "recipe_names_audit_v214.json")
URL = "https://zh.stardewvalleywiki.com/%E7%83%B9%E9%A5%AA"
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) StardewHelper-audit/2.14"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read().decode("utf-8", "replace")


def wiki_dish_names(html):
    """「图鉴」小节里每个菜是一个 <a href="/菜名" title="菜名">。取 title 即可。

    注意定位锚点必须用 id="图鉴"：目录里的 <a href="#图鉴">图鉴</a> 会让
    `>图鉴<` 先命中目录而不是正文标题。
    """
    i = html.find('id="图鉴"')
    if i < 0:
        print("!! 页面里找不到 图鉴 标题")
        return []
    j = html.find("<h2", i + 10)
    seg = html[i:j if j > 0 else len(html)]
    seen, out = set(), []
    for m in re.finditer(r'<a href="/([^"#:]+)" title="([^"]+)"', seg):
        name = m.group(2).strip()
        if name and name not in seen:
            seen.add(name)
            out.append(name)
    return out


def wiki_recipe_table(html):
    """解析「食谱」总表：{英文名: {zh, 英文, 材料[], 能量, 血, 售价}}。

    列序：图片 / 名称 / 描述 / 所需原料 / 能量·生命值 / 增益效果 / 增益时长 / 食谱来源 / 售出价格
    第 0 列 img 的 alt 就是英文内部名（如 "Fried Egg.png"）。
    """
    i = html.find('id="食谱"')
    if i < 0:
        return {}
    j = html.find("<h2", i + 10)
    seg = html[i:j if j > 0 else len(html)]
    out = {}
    for tr in re.findall(r"<tr[^>]*>(.*?)</tr>", seg, flags=re.S):
        tds = re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", tr, flags=re.S)
        if len(tds) < 9:
            continue
        en = ""
        m0 = re.search(r'alt="([^"]+)\.png"', tds[0])
        if m0:
            en = m0.group(1)
        zh = re.sub(r"<[^>]+>", " ", tds[1])
        zh = re.sub(r"\s+", " ", zh).strip()
        if not zh or zh in ("名称", "Name"):
            continue
        # 材料的 img alt 是英文（Egg/Milk），中文在 <a title="蛋"> 上——必须取 title
        ings = [m.group(1) for m in re.finditer(r'<a[^>]*title="([^"]+)"', tds[3])]
        nums = re.findall(r"\d+", re.sub(r"<[^>]+>", " ", tds[4]))
        pm = re.findall(r"\d+", re.sub(r"<[^>]+>", " ", tds[8]))
        out[zh] = {
            "en": en,
            "ingredients": ings,
            "energy": int(nums[0]) if len(nums) > 0 else None,
            "health": int(nums[1]) if len(nums) > 1 else None,
            "price": int(pm[0]) if pm else None,
        }
    return out


def verify_rename(app_item, wiki_item):
    """按 能量/血/材料重合度 给改名配对打分。"""
    a, w = app_item, wiki_item
    score = 0
    if a.get("effect", "").startswith("%s体/%s血" % (w.get("energy"), w.get("health"))):
        score += 40
    ai = set(i["name"] for i in a.get("ingredients", []))
    wi = set(w.get("ingredients", []))
    if ai and wi:
        score += int(60 * len(ai & wi) / float(len(ai | wi)))
    return score


def main():
    app = json.load(io.open(os.path.join(DATA, "recipes.json"), encoding="utf-8"))
    html = fetch(URL)
    gallery = wiki_dish_names(html)
    table = wiki_recipe_table(html)
    print("官方中文维基：图鉴小节 %d 个菜名，食谱总表 %d 行" % (len(gallery), len(table)))
    if not table:
        print("!! 食谱表解析失败，页面结构可能变了")
        return 1

    table_set = set(table)
    app_set = set(x["name"] for x in app)
    ok = sorted(app_set & table_set)
    unmatched = [x for x in app if x["name"] not in table_set]

    print("App 81 条中直接命中官方名 %d 条" % len(ok))
    print()
    print("=== 待改名 %d 条（已按材料/数值配对打分）===" % len(unmatched))
    rename = {}
    for a in unmatched:
        cands = sorted(((verify_rename(a, table[w]), w) for w in table_set),
                       key=lambda t: -t[0])
        best = cands[0]
        flag = "OK " if best[0] >= 70 else ("低 " if best[0] >= 40 else "✗  ")
        rename[a["name"]] = {"to": best[1], "score": best[0]}
        print("  %s %-14s -> %-14s 分%-4d  材料 %s"
              % (flag, a["name"], best[1], best[0],
                 "+".join(i["name"] for i in a["ingredients"])))

    rep = {"wiki_table_count": len(table), "app_count": len(app),
           "matched": ok, "rename": rename}
    with io.open(OUT, "w", encoding="utf-8") as f:
        json.dump(rep, f, ensure_ascii=False, indent=1)
    print("\n报告已写出 %s" % OUT)
    return 0


if __name__ == "__main__":
    sys.exit(main())