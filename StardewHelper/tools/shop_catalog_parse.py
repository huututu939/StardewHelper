# -*- coding: utf-8 -*-
# 商店目录数据管线（v2.56.0）第 2 步/4：解析商品表 -> 草稿 shop_catalog_draft.json + 未命中清单
# 全链顺序：shop_catalog_fetch -> shop_catalog_parse -> shop_catalog_translate -> shop_catalog_merge
# 中间产物都在 %TEMP%（开发期缓存，不入库）；最终产物 app/src/main/assets/data/shopCatalog.json 入库。
# ⚠ 本组脚本是「数据出处」的记录：wiki 表结构或项目译名口径变了，按序重跑并人工复核 merge 输出。
# -*- coding: utf-8 -*-
"""
商店目录构建器（v2.56.0 数据源）：解析 15 家商店的 wiki wikitext → shopCatalog 草稿。

输入：%TEMP%/shop_wikitext/*.txt（parse API 抓的 wikitext 缓存，开发期产物）
输出：--out 指定草稿 JSON；stderr 打印每家条数 + 字典未命中清单（等 langlinks 补）。

解析规则（对 15 页实测归纳）：
  - 章节：白名单命中（Stock/Menu/Shop/Concessions/Livestock/...），层级 2 或 3；
    正文 = 本标题到下一个**同级或更高级**标题。
  - 行：`|-` 分段；行内同时支持「每行一个 |cell」和「|A||B 同行多格」。
  - 名称：非 File: 的 [[link]] / {{Name|X}} / {{Recipe|X}}（记 qual=配方）/
    纯文本单元格（哈维诊所式）。
  - 价格：data-sort-value="N" / {{Price|N[|unit]}} / {{tprice|N}}（旅行货车）；
    {{Name|货币|N}} 视为换购（沙漠商人：N 个某物）。
  - 条件：{{Description}} 单元格里的 ''(斜体)'' 备注。
"""
import io
import json
import os
import re
import sys

SRC = os.path.join(os.environ["TEMP"], "shop_wikitext")

# 项目店铺名 -> (缓存文件名, 商品章节白名单正则, 额外排除)
STORES = [
    ("皮埃尔的杂货店", "Pierres_General_Store.txt",
     r"Year-Round Stock|Spring Stock|Summer Stock|Fall Stock|Player-Derived Stock"),
    ("Joja超市", "JojaMart.txt", r"Permanent Stock|Spring Stock|Summer Stock|Fall Stock"),
    ("铁匠铺", "Blacksmith.txt", r"^Stock$"),
    ("星之果实酒吧", "The_Stardrop_Saloon.txt", r"Permanent Stock|Temporary Stock|Rotating Stock"),
    ("哈维的诊所", "Harveys_Clinic.txt", r"Medical Supplies"),
    ("冰淇淋摊", "Ice_Cream_Stand.txt", r"^Menu$"),
    ("电影院", "Movie_Theater.txt", r"^Concessions$"),
    ("木匠的商店", "Carpenters_Shop.txt", r"Permanent Stock"),  # 壁纸/地板图案 260 行压缩为一条汇总（main 里手工加）
    ("玛妮的牧场", "Marnies_Ranch.txt", r"Shop Inventory|^Livestock$"),
    ("法师塔", "Wizards_Tower.txt", r"$NOMATCH$"),  # 只卖特殊建筑（composite 价），归建筑体系
    ("旅行货车", "Traveling_Cart.txt",
     r"Special Stock|^Standard Stock$|Cooked Dishes|Crops & Foraging|Seeds & Fertilizer|Fish & Beach Forage|Artisan Goods"),
    ("冒险家公会", "Adventurers_Guild.txt", r"^Shop$"),
    ("鱼店", "Fish_Shop.txt", r"^Stock$|Targeted Bait|Player-Derived Stock"),
    ("绿洲", "Oasis.txt", r"Fixed Stock|Daily Stock"),
    ("沙漠商人", "Desert_Trader.txt", r"Permanent Stock|Rotating Stock"),
]

# 章节标题（小写）→ 供货窗口中文
AVAIL_RULES = [
    ("year-round", "全年"), ("permanent", "全年"), ("fixed", "全年"),
    ("shop inventory", "全年"), ("medical supplies", "全年"), ("menu", "全年"),
    ("concessions", "全年"), ("basement", "全年"), ("^stock", "全年"), ("^shop", "全年"),
    ("spring", "春"), ("summer", "夏"), ("fall", "秋"), ("winter", "冬"),
    ("livestock", "牲畜"),
    ("rotating", "轮换"), ("daily", "每日轮换"), ("temporary", "限时"),
    ("player-derived", "玩家供货"),
    ("standard stock", "每周随机"), ("special", "特殊"),
    ("cooked dishes", "每周随机"), ("crops & foraging", "每周随机"),
    ("seeds & fertilizer", "每周随机"), ("fish & beach forage", "每周随机"),
    ("artisan goods", "每周随机"),
]

LINK_RE = re.compile(r"\[\[([^\]|]+)(?:\|[^\]]*)?\]\]")
NAME_T_RE = re.compile(r"\{\{Name\|([^}|]+)")
PRICE_RE = re.compile(r'data-sort-value="0*(\d+)"')
PRICE_T_RE = re.compile(r"\{\{(?:Price|tprice)\|(\d+)(?:\|([^}]*))?\}\}", re.I)
BARTER_RE = re.compile(r"\{\{Name\|([^|}]+)\|(\d+)\}\}")
ITALIC_RE = re.compile(r"''\((.*?)\)''")
PLAIN_NAME_RE = re.compile(r"[A-Z][A-Za-z0-9 &'()\-]{2,40}")


def avail_of(title):
    tl = title.lower()
    for pat, zh in AVAIL_RULES:
        if re.search(pat, tl):
            return zh
    return title


def split_sections(text):
    """扫所有 2/3 级标题 -> [(level, title, body)]，body 到下一个同级或更高级标题。"""
    heads = [(m.start(), len(m.group(1)), m.group(2).strip())
             for m in re.finditer(r"^(={2,3})([^=\n]+?)\1\s*$", text, re.M)]
    out = []
    for i, (pos, lvl, title) in enumerate(heads):
        end = len(text)
        if i + 1 < len(heads):
            end = heads[i + 1][0]
        out.append((lvl, title, text[pos:end]))
    return out


def row_cells(chunk):
    cells = []
    for line in chunk.splitlines():
        line = line.strip()
        if line.startswith("|") and not line.startswith("|}"):
            cells += [c.strip() for c in line[1:].split("||")]
    return [c for c in cells if c]


def is_price_cell(c):
    return bool(PRICE_RE.search(c) or PRICE_T_RE.search(c) or BARTER_RE.search(c))


def extract(cells):
    en = qual = None
    price = None
    price2 = None
    unit = ""
    note = ""
    for c in cells:
        if en is None:
            m = re.search(r"\{\{Recipe\|([^|}]+)", c)
            if m:
                en, qual = m.group(1).strip(), "配方"
                continue
            for lm in LINK_RE.finditer(c):
                t = lm.group(1).strip()
                if ":" in t or re.search(r"\.(png|jpg|gif|svg)$", t, re.I):
                    continue
                en = t
                break
            if en is None:
                m = NAME_T_RE.search(c)
                if m and not is_price_cell(c):
                    en = m.group(1).strip()
                elif en is None and PLAIN_NAME_RE.fullmatch(c) and not is_price_cell(c) \
                        and "{{Description" not in c:
                    en = c
                if en is not None:
                    m2 = re.search(r"(?:\[\[[^\]]+\]\]|(?<=\}\}))\s*(\([^)]*\))", c)
                    if m2:
                        qual = m2.group(1).strip("() ")
            continue
        # 第二格起：找价格 / 备注（同一行多个价格格 = Year1/Year2 两列）
        if is_price_cell(c) and (price is None or price2 is None):
            m = PRICE_RE.search(c) or PRICE_T_RE.search(c)
            tgt = "price2" if price is not None else "price"
            if m:
                v = int(m.group(1))
                if price is None:
                    price = v
                else:
                    price2 = v
                if len(m.groups()) > 1 and m.group(2):
                    u = m.group(2).strip()
                    if u and "class" not in u and "size" not in u and price is None:
                        unit = u
            else:
                mb = BARTER_RE.search(c)
                if price is None:
                    unit, price = mb.group(1).strip(), int(mb.group(2))
                else:
                    price2 = int(mb.group(2))
        if not note and "{{Description" in c:
            mi = ITALIC_RE.search(c)
            if mi:
                note = mi.group(1).strip()
    return en, qual, price, price2, unit, note


def parse_page(path, secs_re):
    rows = []
    text = io.open(path, encoding="utf-8").read()
    for lvl, title, body in split_sections(text):
        if not re.search(secs_re, title, re.I):
            continue
        avail = avail_of(title)
        for chunk in body.split("|-"):
            cells = row_cells(chunk)
            if len(cells) < 2:
                continue
            en, qual, price, price2, unit, note = extract(cells)
            if en and price is not None:
                r = {"item_en": en, "qualifier": qual or "", "price": price,
                     "unit": unit, "avail": avail, "note": note, "section": title}
                if price2 is not None:
                    r["price2"] = price2
                rows.append(r)
    return rows


def dict_en2cn():
    en2cn = {}
    data = _REPO_ROOT() + '/StardewHelper' + '/app/src/main/assets/data'
    for f in os.listdir(data):
        if not f.endswith(".json") or "wiki" in f:
            continue
        try:
            j = json.load(io.open(os.path.join(data, f), encoding="utf-8"))
        except Exception:
            continue
        def walk(o):
            if isinstance(o, dict):
                if "name_en" in o:
                    cn = o.get("name_cn") or o.get("name")
                    if cn:
                        en2cn.setdefault(str(o["name_en"]).strip().lower(), str(cn).strip())
                for v in o.values():
                    walk(v)
            elif isinstance(o, list):
                for v in o:
                    walk(v)
        walk(j)
    return en2cn


def main():
    result = {}
    all_en = set()
    for store, fn, secs in STORES:
        p = os.path.join(SRC, fn)
        if not os.path.exists(p):
            print("缺页：%s" % fn, file=sys.stderr)
            continue
        rows = parse_page(p, secs)
        result[store] = rows
        for r in rows:
            all_en.add(r["item_en"])
        print("%-10s %3d 条" % (store, len(rows)), file=sys.stderr)
    print("总计 %d 条商品行，%d 个不同英文物品名"
          % (sum(len(v) for v in result.values()), len(all_en)), file=sys.stderr)
    out = sys.argv[sys.argv.index("--out") + 1] if "--out" in sys.argv else None
    js = json.dumps(result, ensure_ascii=False, indent=1)
    (io.open(out, "w", encoding="utf-8") if out else sys.stdout).write(js)
    if out:
        print("已写 %s" % out, file=sys.stderr)

    en2cn = dict_en2cn()
    miss = sorted(e for e in all_en if e.lower() not in en2cn)
    print("\n== 字典未命中 %d / %d ==" % (len(miss), len(all_en)), file=sys.stderr)
    io.open(os.path.join(os.environ["TEMP"], "shop_miss_en.txt"), "w", encoding="utf-8") \
        .write("\n".join(miss))
    print("（清单已写 %%TEMP%%\\shop_miss_en.txt）", file=sys.stderr)


if __name__ == "__main__":
    main()
