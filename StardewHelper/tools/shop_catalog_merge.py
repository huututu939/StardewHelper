# -*- coding: utf-8 -*-
# 商店目录数据管线（v2.56.0）第 4 步/4：合并去重 + 口径修正 -> shopCatalog.json 定稿（人工复核后拷入 assets）
# 全链顺序：shop_catalog_fetch -> shop_catalog_parse -> shop_catalog_translate -> shop_catalog_merge
# 中间产物都在 %TEMP%（开发期缓存，不入库）；最终产物 app/src/main/assets/data/shopCatalog.json 入库。
# ⚠ 本组脚本是「数据出处」的记录：wiki 表结构或项目译名口径变了，按序重跑并人工复核 merge 输出。
# -*- coding: utf-8 -*-
"""商店目录定稿 v4：修 note 英文、公会点数列、沙漠商人货币、面粉口径。"""
import io
import json
import os
import re

TMP = os.environ["TEMP"]
A = _REPO_ROOT() + '/StardewHelper' + '/app/src/main/assets'
D = os.path.join(A, "data")

# ---- 项目中文名全集 ----
cn_set = set()
cn_set.update(json.load(io.open(os.path.join(A, "image_map.json"), encoding="utf-8")).keys())
for f in os.listdir(D):
    if not f.endswith(".json") or "wiki" in f:
        continue
    try:
        j = json.load(io.open(os.path.join(D, f), encoding="utf-8"))
    except Exception:
        continue
    def walk(o):
        if isinstance(o, dict):
            for k in ("name", "name_cn", "item", "product", "raw", "villager", "crop"):
                v = o.get(k)
                if isinstance(v, str):
                    cn_set.add(v.strip())
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(j)

# ---- 四层命中合并 ----
en2cn = {}
for k, (cn, how) in json.load(io.open(os.path.join(TMP, "shop_hit.json"), encoding="utf-8")).items():
    en2cn[k] = cn
for src in ("shop_langlinks.json",):
    d1 = json.load(io.open(os.path.join(TMP, src), encoding="utf-8"))
    for k, v in d1["map"].items():
        if v:
            en2cn.setdefault(k, v)
d2 = json.load(io.open(os.path.join(TMP, "shop_langlinks2.json"), encoding="utf-8"))
for k, v in d2["extra"].items():
    en2cn.setdefault(k, v)
d4 = json.load(io.open(os.path.join(TMP, "shop_sha1.json"), encoding="utf-8"))
for k, v in d4["sha1_hits"].items():
    en2cn.setdefault(k, v["cn"])

# ---- 第 5 层：v9 像素哈希映射（最高优先级：直接就是项目图的英文名）----
pix = json.load(io.open(os.path.join(TMP, "pix_en2cn.json"), encoding="utf-8"))
for en, cns in pix.items():
    # 多候选取与英文名同序第一个；像素同源 = 最强证据
    if cns:
        en2cn[en] = cns[0]

# ---- 手工映射（逐条有出处：项目既有词优先，其次 zh wiki 页面名）----
# 面粉口径按项目 recipes.json（小麦粉），不用 zh wiki 的「大麦粉」
en2cn["Wheat Flour"] = "小麦粉" if "小麦粉" in cn_set else en2cn.get("Wheat Flour")
# 沙漠商人货币（全部取项目既有词，保证图鉴可达）
for en, cn in (("Omni Geode", "万象晶球"), ("Iridium Bar", "铱棒"), ("Iridium Ore", "铱矿石"),
               ("Prismatic Shard", "五彩碎片"), ("Void Essence", "虚空精华"), ("Sunstone", "阳石")):
    if cn in cn_set:
        en2cn[en] = cn
SPECIAL = {"Bait (item)": "鱼饵",
           "Spring Seeds": "春季种子", "Summer Seeds": "夏季种子",
           "Fall Seeds": "秋季种子", "Winter Seeds": "冬季种子",
           "Ancient Seeds": "古代种子", "Ancient Seed": "古代种子"}
NOTE_CN = {
    "Available from spring 15 of year 1 onward": "第 1 年春 15 起出售",
    "Available from year 2 onward": "第 2 年起出售",
    "Available in year 2+": "第 2 年起出售",
}
DERIVE = [(r"^(.+?) Seeds$", "种子"), (r"^(.+?) Sapling$", "树苗"),
          (r"^(.+?) Starter$", "秧苗"), (r"^(.+?) Bulb$", "球茎"),
          (r"^(.+?) Recipe$", "配方"), (r"^(.+?) Ring$", "戒指")]

def lookup(en):
    e = (en or "").strip()
    if not e:
        return None
    if e in SPECIAL:
        return SPECIAL[e]
    if e in en2cn and en2cn[e]:
        return en2cn[e]
    for pat, suf in DERIVE:
        m = re.match(pat, e, re.I)
        if m:
            base = lookup(m.group(1).strip())
            if base:
                return base + suf
    return None

# ---- 定稿 ----
draft = json.load(io.open(os.path.join(TMP, "shop_catalog_draft.json"), encoding="utf-8"))
for drop in ("电影院", "法师塔"):
    draft.pop(drop, None)

sp = json.load(io.open(os.path.join(D, "seedPrices.json"), encoding="utf-8"))
crops = json.load(io.open(os.path.join(D, "crops.json"), encoding="utf-8"))
cl = crops["crops"] if isinstance(crops, dict) and "crops" in crops else crops
season_of = {x["name"] + "种子": "、".join(x.get("seasons") or []) for x in cl}

cat = {}
# 1) 种子（seedPrices 权威）
for x in sp:
    n = x["name"]
    av = season_of.get(n, "全年")
    if x.get("pierre"):
        it = {"name": n, "price": x["pierre"], "avail": av, "codex": n in cn_set}
        notes = []
        if x.get("year2"):
            notes.append("第 2 年起出售")
        if x.get("pierreOff"):
            notes.append("春季展销会价 %s" % x["pierreOff"])
        if notes:
            it["note"] = "；".join(notes)
        cat.setdefault("皮埃尔的杂货店", []).append(it)
    if x.get("joja"):
        it = {"name": n, "price": x["joja"], "avail": av, "codex": n in cn_set}
        if x.get("jojaMember"):
            it["note"] = "会员价 %s" % x["jojaMember"]
        cat.setdefault("Joja超市", []).append(it)

# 2) wiki 行
lost = set()
for store, rows in draft.items():
    for r in rows:
        cn = lookup(r["item_en"])
        if not cn:
            lost.add((store, r["item_en"]))
            continue
        if any(e["name"] == cn for e in cat.get(store, [])):
            continue
        it = {"name": cn, "price": r["price"], "avail": r["avail"]}
        if store == "冒险家公会" and r.get("price2"):
            it["note"] = "需公会点数 %d" % r["price2"]
        elif r.get("price2"):
            it["price2"] = r["price2"]
        if r.get("unit"):
            uc = lookup(r["unit"])
            if uc:
                it["unit"] = uc          # 换购：price 个 unit
            else:
                lost.add((store, r["item_en"] + " [货币:" + r["unit"] + "]"))
                continue
        if r.get("qualifier"):
            it["qual"] = r["qualifier"]
        note = NOTE_CN.get(r.get("note", ""), r.get("note", ""))
        if note:
            it["note"] = (it.get("note", "") + "；" + note).lstrip("；")
        if cn in cn_set:
            it["codex"] = True
        cat.setdefault(store, []).append(it)

for store in cat:
    cat[store].sort(key=lambda x: (x["avail"] != "全年", x["avail"], x["name"]))

total = sum(len(v) for v in cat.values())
for s in sorted(cat, key=lambda x: -len(cat[x])):
    print("%-10s %3d 行" % (s, len(cat[s])))
print("总计 %d 行 / %d 店；仍剔除 %d 行" % (total, len(cat), len(lost)))
print("\n仍剔除明细（前 30）：")
for s, e in sorted(lost)[:30]:
    print("   %-10s %s" % (s, e))
io.open(os.path.join(TMP, "shop_catalog_v6.json"), "w", encoding="utf-8").write(
    json.dumps(cat, ensure_ascii=False, indent=1))
print("\n已写 shop_catalog_v6.json")
