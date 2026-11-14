# -*- coding: utf-8 -*-
"""v2.14：菜谱数据全量审计 + 重建。

背景：群友反馈 App 菜谱「名字大量翻译错误 + 没抓完（官方 81 道）」。
做法：以英文 wiki Cooking 总表为「有哪些菜」的唯一依据（中英行序一致，逐行对齐取官方中文名），
      再用官方 材料/能量/血/售价 重建 assets/data/recipes.json。

纯 stdlib。用法：python tools/audit_recipes_v214.py [--fix]
输出：控制台报告 + tools/recipes_audit_v214.json；加 --fix 时覆写 assets/data/recipes.json
"""
import json
import os
import re
import sys
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APPSRC = os.path.join(ROOT, "app", "src", "main", "assets")
DATA = os.path.join(APPSRC, "data")
OUT = os.path.join(ROOT, "tools", "recipes_audit_v214.json")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) StardewHelper-audit/2.14"


def fetch(url):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=90) as r:
        return r.read().decode("utf-8", "replace")


def cell_text(s):
    s = re.sub(r"<style[^>]*>.*?</style>", " ", s, flags=re.S | re.I)
    s = re.sub(r"<script[^>]*>.*?</script>", " ", s, flags=re.S | re.I)
    s = re.sub(r"<img[^>]*>", " ", s)
    s = re.sub(r"<sup[^>]*>.*?</sup>", " ", s, flags=re.S | re.I)
    s = re.sub(r"<[^>]+>", " ", s)
    for a, b in (("&#160;", " "), ("&nbsp;", " "), ("&amp;", "&"), ("&#39;", "'"),
                 ("&quot;", '"'), ("&#34;", '"'), ("&times;", "x")):
        s = s.replace(a, b)
    return re.sub(r"\s+", " ", s).strip()


def parse_table(html, anchor):
    """从 anchor 标题到下一个 <h2> 之间，抽 wikitable 的每一行。"""
    start = html.find(anchor)
    if start < 0:
        return []
    nxt = html.find("<h2>", start + len(anchor))
    seg = html[start:nxt if nxt > 0 else len(html)]
    rows = []
    for tr in re.findall(r"<tr>(.*?)</tr>", seg, flags=re.S | re.I):
        tds = re.findall(r"<t[dh][^>]*>(.*?)</t[dh]>", tr, flags=re.S | re.I)
        if len(tds) < 5:
            continue
        name = cell_text(tds[1])
        if not name or name in ("Name", "名称", "名字"):
            continue
        # 材料：图标 alt 去掉 .png
        ings = []
        for a in re.findall(r"<img[^>]*alt=\"([^\"]+)\"", tds[3]):
            n = a[:-4] if a.lower().endswith(".png") else a
            if n in ("Energy", "Health", "Gold", "HeartIconLarge"):
                continue
            ings.append(n)
        # 能量/血：文本里按出现顺序取前两个数字
        eh = cell_text(tds[4])
        nums = re.findall(r"\d+", eh)
        energy = int(nums[0]) if len(nums) >= 1 else None
        health = int(nums[1]) if len(nums) >= 2 else None
        price_txt = cell_text(tds[-1])
        pm = re.search(r"(\d+)\s*g", price_txt)
        src_txt = cell_text(tds[-2]) if len(tds) >= 9 else ""
        rows.append({
            "name": name,
            "ingredients": ings,
            "energy": energy,
            "health": health,
            "price": int(pm.group(1)) if pm else None,
            "source": src_txt[:80],
        })
    return rows


def zh_source(desc_zh, ing_zh):
    """把「配方来源」粗略归一成 App 现有的 source 口径。"""
    s = desc_zh
    if "酒馆" in s or "酒吧" in s:
        return "酒吧购买"
    if "餐后" in s:
        return "皇后食谱"
    if "邮箱" in s or "邮件" in s:
        return "村民信件"
    if "电视" in s or "节目" in s:
        return "电视节目"
    if "矿工" in s and "矿工" in s:
        return "矿工联盟会员"
    if "吉丁" in s or "姜岛" in s:
        return "姜岛齐先生"
    if "厨柜" in s or "食谱" in s:
        return "皇后食谱"
    return "其他"


def main():
    fix = "--fix" in sys.argv

    print("== 抓英文 Cooking 总表 ==")
    en_html = fetch("https://stardewvalleywiki.com/Cooking")
    en = parse_table(en_html, '<span class="mw-headline" id="Recipes">')
    print("英文官方菜谱 %d 条" % len(en))

    print("== 抓中文站 烹饪 总表 ==")
    zh_html = ""
    try:
        zh_html = fetch("https://zh.stardewvalleywiki.com/%E7%83%B9%E9%A5%AA")
        zh = parse_table(zh_html, 'id="Recipes"')
    except Exception as e:
        print("中文站抓取失败：%s" % e)
        zh = []
    print("中文官方菜谱 %d 条" % len(zh))

    # 中英逐行对齐
    merged = []
    n = min(len(en), len(zh))
    for i in range(n):
        e, z = en[i], zh[i]
        merged.append({
            "idx": i,
            "name_en": e["name"],
            "name_zh": z["name"],
            "ingredients_en": e["ingredients"],
            "ingredients_zh": z["ingredients"],
            "energy": e["energy"],
            "health": e["health"],
            "price": e["price"],
            "source_zh": z["source"],
        })
    if len(en) != len(zh):
        print("!! 中英行数不一致：EN=%d ZH=%d，只对齐前 %d 行" % (len(en), len(zh), n))

    app_path = os.path.join(DATA, "recipes.json")
    with open(app_path, encoding="utf-8") as f:
        app = json.load(f)
    app_names = set(a["name"] for a in app)

    print("\n" + "=" * 72)
    print("App 现有 %d 条 / 官方 %d 条" % (len(app), len(merged)))
    print("=" * 72)

    missing, renamed, ok = [], [], []
    for m in merged:
        zh_n = m["name_zh"]
        if zh_n in app_names:
            ok.append(m)
            continue
        # 名字对不上：找 App 里是否有「材料集合相同」的条目 → 视为改名
        app_ing = {}
        for a in app:
            app_ing.setdefault(
                tuple(sorted(i["name"] for i in a.get("ingredients", []))), []).append(a["name"])
        sig = tuple(sorted(m["ingredients_zh"]))
        cand = app_ing.get(sig)
        if cand:
            renamed.append((m, cand))
        else:
            missing.append(m)

    print("\n【已对上】%d 条" % len(ok))
    print("\n【名称需改】%d 条（材料相同、名字不同）" % len(renamed))
    for m, cand in renamed:
        print("  App「%s」 -> 官方「%s」   (EN %s)" % (cand[0], m["name_zh"], m["name_en"]))

    print("\n【App 缺失】%d 条" % len(missing))
    for m in missing:
        print("  %-14s %-24s %s/%s  %sg" % (
            m["name_zh"], "+".join(m["ingredients_zh"]), m["energy"], m["health"], m["price"]))

    # App 里既不是官方名、也匹配不上任何官方条目的 → 多余
    official_zh = set(m["name_zh"] for m in merged)
    extra = [a for a in app if a["name"] not in official_zh]
    print("\n【App 多余/存疑】%d 条" % len(extra))
    for a in extra:
        used_as_ing = any(
            a["name"] in m["ingredients_zh"] or a["name"] in m["ingredients_en"]
            for m in merged)
        print("  %-14s %-30s %s" % (
            a["name"],
            "+".join("%s x%d" % (i["name"], i["count"]) for i in a.get("ingredients", [])),
            "(仍是官方材料名，勿删)" if used_as_ing else ""))

    report = {
        "en_count": len(en), "zh_count": len(zh), "app_count": len(app),
        "matched": [m["name_zh"] for m in ok],
        "renamed": [{"app": c[0], "official": m["name_zh"], "en": m["name_en"]} for m, c in renamed],
        "missing": missing,
        "extra": [{"name": a["name"], "ing": a.get("ingredients", [])} for a in extra],
    }
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(report, f, ensure_ascii=False, indent=1)
    print("\n报告已写出：%s" % OUT)

    if fix:
        build(app, merged, renamed, missing)
    return 0


def build(app, merged, renamed, missing):
    """按官方数据重建 recipes.json：官方名 + 官方材料 + 官方能量/血 + 官方来源。"""
    old_by_name = set(a["name"] for a in app)
    rename_map = dict((c[0], m["name_zh"]) for m, c in renamed)

    # 官方材料里出现、App 里当主条目的，要一并改名（保材料名对齐）
    ing_names = set()
    for m in merged:
        ing_names.update(m["ingredients_zh"])
    for a in app:
        ing_names.update(i["name"] for i in a.get("ingredients", []))

    out = []
    for m in merged:
        ings = []
        for zh_ing in m["ingredients_zh"]:
            cnt = 1
            mm = re.search(r"[x×]\s*(\d+)", zh_ing)
            if mm:
                cnt = int(mm.group(1))
            ings.append({"name": rename_map.get(zh_ing, zh_ing), "count": cnt})
        out.append({
            "name": m["name_zh"],
            "ingredients": ings,
            "source": zh_source(m["source_zh"], m["ingredients_zh"]),
            "effect": "%s体/%s血" % (m["energy"], m["health"]),
        })
    path = os.path.join(DATA, "recipes.json")
    with open(path, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("\n[--fix] 已重建 recipes.json：%d 条" % len(out))


if __name__ == "__main__":
    sys.exit(main())