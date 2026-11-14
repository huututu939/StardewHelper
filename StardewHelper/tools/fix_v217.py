# -*- coding: utf-8 -*-
"""v2.17 数据订正（幂等，可反复运行）—— tools/fix_v217.py

数值来源（2026-10 官方英文 wiki 实页核实，不凭记忆）：
  - Cheese_Press (rev 183811)：奶酪 230g / 金奶酪 345g / 山羊奶酪 400g / 大金 600g；耗时 200m≈3h；
    中文站名「压酪机」
  - Oil_Maker (rev 190486)：松露油 6h；玉米油 1000m≈16.7h；向日葵籽油 3200m=2 天；向日葵油 1h；
    中文站名「产油机」
  - Slime_Egg-Press (rev 190521)：100 粘液 → 20h（1200m）
  - Iron_Bar (rev 192802)：铁锭 120g / 冶炼 2h
  - Egg_Festival (rev 192059)：春 13 ✓（App 现值正确，不动）
  - Artifacts (rev 186104)：可捐赠古物 42 件（App 51 件中 9 件为非捐赠项）
其余（蓝莓 50/菠萝 300/远古 550）与自家 crops.json 交叉一致。

每一步都是「设为定值」或「仅当仍是旧值才改」，重跑输出 no-op。
"""
import json
import io
import os
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "app", "src", "main", "assets", "data")
IMAP = os.path.join(ROOT, "app", "src", "main", "assets", "image_map.json")

changes = []


def load(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def save_data(path, obj):
    """assets/data/* 维持原文件风格：indent=1 + CRLF + 无结尾换行（否则全文件 diff 爆炸）"""
    with io.open(path, "w", encoding="utf-8", newline="\r\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)


def note(msg):
    changes.append(msg)
    print("  * " + msg)


def setv(o, key, val, tag):
    if o.get(key) != val:
        o[key] = val
        note(f"{tag}: {key} -> {val}")


# ──────────────────────────────────────────────────────────────
# B1 crops.json：远古水果 550 + 再生 7；菠萝 300（生长 14 已对）
# ──────────────────────────────────────────────────────────────
def fix_crops():
    p = os.path.join(DATA, "crops.json")
    arr = load(p)
    for c in arr:
        if c["name"] == "远古水果":
            setv(c, "sellPrice", 550, "crops/远古水果")
            setv(c, "regrow", 7, "crops/远古水果")
        elif c["name"] == "菠萝":
            setv(c, "sellPrice", 300, "crops/菠萝")
            setv(c, "regrow", 7, "crops/菠萝")
    save_data(p, arr)


# ──────────────────────────────────────────────────────────────
# B2+B3 processingProfit.json：机器名统一 + 价格/耗时订正 + 派生值重算
# ──────────────────────────────────────────────────────────────
MACHINE_RENAME = {
    "酿酒桶": "小桶",       # 与 processing.json / crafting.json 统一
    "奶酪机": "压酪机",     # 官方中文站名「压酪机」
    "脱水机": "烘干机",     # crafting.json / jojaGoods.json 用「烘干机」
    "水晶球": "宝石复制机", # 「水晶球」为 Crystalarium 误译
    "榨油机": "产油机",     # 官方中文站名「产油机」
}

# (机器, 产出) -> {字段: 新值} ；只放官方核实过的
RECIPE_FIX = {
    ("压酪机", "奶酪"): {"output_price": 230},
    ("压酪机", "大奶酪(金)"): {"output_price": 345},   # 官方金品质奶酪 345
    ("蛋黄酱机", "金色蛋黄酱"): {"output_price": 285},  # 190×1.5
    ("小桶", "蓝莓酒"): {"input_base_price": 50},      # 蓝莓售价 50（crops.json 同）
    ("小桶", "番茄汁"): {"input_base_price": 60},      # 番茄售价 60
    ("罐头瓶", "腌番茄"): {"input_base_price": 60},
    ("熔炉", "铜锭"): {"output_price": 50},
    ("熔炉", "铁锭"): {"output_price": 120, "processing_time_hours": 2.0},
    ("熔炉", "金锭"): {"output_price": 250},
    ("产油机", "松露油"): {"processing_time_hours": 6.0, "note": "官方 6 小时"},
    ("产油机", "向日葵油"): {},                      # 1h 本就正确
    ("产油机", "油"): {},                            # 玉米/向日葵籽两行单独按下标处理
    ("史莱姆蛋压机", "史莱姆蛋"): {"processing_time_hours": 20.0, "note": "100粘液→1史莱姆蛋(绿500g) · 官方 20 小时"},
}


def fix_processing_profit():
    p = os.path.join(DATA, "processingProfit.json")
    root = load(p)
    for m in root.get("machines", []):
        old = m.get("name")
        if old in MACHINE_RENAME:
            m["name"] = MACHINE_RENAME[old]
            note(f"processingProfit: 机器名 {old} -> {MACHINE_RENAME[old]}")
        mname = m["name"]
        for r in m.get("recipes", []):
            key = (mname, r.get("output"))
            fx = RECIPE_FIX.get(key)
            if fx:
                for k, v in fx.items():
                    if v != {} and r.get(k) != v:
                        note(f"processingProfit/{mname}/{r.get('output')}: {k} {r.get(k)} -> {v}")
                        r[k] = v
            # 产油机「油」两行按原料区分耗时（官方：玉米 1000m≈16.7h、向日葵籽 3200m=48h）
            if mname == "产油机" and r.get("output") == "油":
                if r.get("input") == "玉米" and r.get("processing_time_hours") != 16.67:
                    note(f"processingProfit/产油机/油(玉米): 1.0 -> 16.67")
                    r["processing_time_hours"] = 16.67
                if r.get("input") == "向日葵种子":
                    if r.get("processing_time_hours") != 48.0:
                        note(f"processingProfit/产油机/油(向日葵种子): 0.17 -> 48.0")
                        r["processing_time_hours"] = 48.0
                    if r.get("note") != "官方 3200 分钟 = 2 天":
                        r["note"] = "官方 3200 分钟 = 2 天"
            # 派生值全量重算（幂等）
            h = float(r.get("processing_time_hours") or 0)
            profit = int(r.get("output_price", 0)) - int(r.get("input_base_price", 0))
            if r.get("profit") != profit:
                note(f"processingProfit/{mname}/{r.get('output')}: profit {r.get('profit')} -> {profit}")
                r["profit"] = profit
            ph = round(profit / h, 2) if h > 0 else 0.0
            if r.get("profit_per_hour") != ph:
                note(f"processingProfit/{mname}/{r.get('output')}: perHour {r.get('profit_per_hour')} -> {ph}")
                r["profit_per_hour"] = ph
    # D4：删掉 41 条无消费方、且与 UI 口径(62 条)自相矛盾的预生成排行
    if "profit_ranking" in root:
        del root["profit_ranking"]
        note("processingProfit: 删除死字段 profit_ranking（41 条）")
    save_data(p, root)


# ──────────────────────────────────────────────────────────────
# B2b processing.json：压酪机耗时 6h → 官方 200m≈3h（两视图统一到官方）
# ──────────────────────────────────────────────────────────────
def fix_processing():
    p = os.path.join(DATA, "processing.json")
    arr = load(p)
    for r in arr:
        if r.get("机器") == "压酪机" and r.get("耗时") == "约 6 小时":
            r["耗时"] = "约 3 小时"
            note(f"processing/压酪机/{r.get('成品')}: 耗时 约6小时 -> 约3小时（官方 200m）")
    save_data(p, arr)


# ──────────────────────────────────────────────────────────────
# B5 museum.json：删 9 件非捐赠项（官方 Artifacts 页 42 件清单）
# ──────────────────────────────────────────────────────────────
NON_DONATION_ARTIFACTS = [
    "肋骨化石", "腿骨化石", "脊椎化石", "颅骨化石",
    "蛇头骨", "蛇脊椎骨", "树懒骨架", "蝙蝠木乃伊", "青蛙木乃伊",
]


def fix_museum():
    p = os.path.join(DATA, "museum.json")
    m = load(p)
    before = len(m["artifacts"])
    m["artifacts"] = [a for a in m["artifacts"] if a not in NON_DONATION_ARTIFACTS]
    if len(m["artifacts"]) != before:
        note(f"museum: 古物 {before} -> {len(m['artifacts'])}（移除非捐赠项，官方 42 件）")
    save_data(p, m)


# ──────────────────────────────────────────────────────────────
# B6 bundles.json：建筑包去掉重复「木材」（打勾键 cc:0:4:木材 撞车源）
# ──────────────────────────────────────────────────────────────
def fix_bundles():
    p = os.path.join(DATA, "bundles.json")
    rooms = load(p)
    for ri, room in enumerate(rooms):
        for bi, b in enumerate(room.get("bundles", [])):
            seen, kept = set(), []
            for it in b.get("items", []):
                nm = it["name"]
                if nm in seen:
                    note(f"bundles: {room['room']}·{b['name']} 去重复项「{nm}」")
                    continue
                seen.add(nm)
                kept.append(it)
            b["items"] = kept
    save_data(p, rooms)


# ──────────────────────────────────────────────────────────────
# B9 gifts.json：莱纳斯生日冬26→冬3（wiki 快照三源一致），最爱补全
# ──────────────────────────────────────────────────────────────
def fix_gifts():
    p = os.path.join(DATA, "gifts.json")
    arr = load(p)
    for g in arr:
        if g.get("name") == "莱纳斯":
            setv(g, "birthdayDay", 3, "gifts/莱纳斯")
            loves = g.setdefault("loves", [])
            for extra in ["海之菜肴", "蓝莓千层酥", "小巷自助餐"]:
                if extra not in loves:
                    loves.append(extra)
                    note(f"gifts/莱纳斯: loves + {extra}（wiki 快照核对）")
    save_data(p, arr)


# ──────────────────────────────────────────────────────────────
# B8 shopHours.json：补结构化 closedDays（A2 闭店判定唯一数据源）
# ──────────────────────────────────────────────────────────────
# 周几语义从 closed 展示文本人工核对；非周几（日期限定/条件限定）留空并在文本中说明
CLOSED_DAYS = {
    "皮埃尔的杂货店": ["周三"],
    "铁匠铺": ["周五"],
    "冰淇淋摊": ["周三"],           # 「周三和下雨天」的周几部分
    "木匠的商店": ["周二"],
    "玛妮的牧场": ["周一", "周二"],
    "旅行货车": ["周一", "周二", "周三", "周四", "周六"],  # 「周五、周日之外」
    "鱼店": ["周六"],               # 「不下雨的周六」的周几部分
    "绿洲": [],                     # 日期限定（秋季 15 日 13:00 后）
    "沙漠商人": [],                 # 日期限定（冬季 15–17 日）
}


def fix_shop_hours():
    p = os.path.join(DATA, "shopHours.json")
    arr = load(p)
    for s in arr:
        want = CLOSED_DAYS.get(s.get("name", ""), [])
        if s.get("closedDays") != want:
            s["closedDays"] = want
            note(f"shopHours/{s.get('name')}: closedDays -> {want}")
    save_data(p, arr)


# ──────────────────────────────────────────────────────────────
# B6b unlocks.json：浮标文案不写死 76；沙漠卡注明金库暂未收录
# ──────────────────────────────────────────────────────────────
def fix_unlocks():
    p = os.path.join(DATA, "unlocks.json")
    arr = load(p)
    for u in arr:
        if u.get("id") == "buoys":
            setv(u, "after", "全部浮标（除默认外按图鉴内全部鱼种解锁）", "unlocks/buoys")
        if u.get("id") == "desert":
            setv(u, "req", "完成金库收集包（暂未收录，可手动勾选） 或 Joja 买「公共汽车」",
                 "unlocks/desert")
    save_data(p, arr)


# ──────────────────────────────────────────────────────────────
# B7 image_map.json：21 个显示名补图（20 条；精灵石无可靠对应图，留占位不猜）
# ──────────────────────────────────────────────────────────────
IMAGE_ALIASES = {
    "咀嚼洁齿棒": "咀嚼棒.png",
    "矮人小工具": "矮人装置.png",
    "装饰用扇子": "装饰扇.png",
    "生锈的汤匙": "生锈的勺子.png",
    "生锈的靴刺": "生锈的马刺.png",
    "诡异玩偶（绿）": "古怪娃娃（绿）.png",
    "诡异玩偶（黄）": "古怪娃娃（黄）.png",
    "肋骨化石": "化石化的肋骨.png",
    "腿骨化石": "化石化的腿骨.png",
    "脊椎化石": "化石化的脊椎.png",
    "颅骨化石": "化石化的头骨.png",
    "蛇脊椎骨": "蛇椎骨.png",
    "蝙蝠木乃伊": "木乃伊蝙蝠.png",
    "青蛙木乃伊": "木乃伊青蛙.png",
    "绿宝石": "翡翠.png",
    "黄水晶": "黄水晶.png",
    "沙岩": "砂岩.png",
    "石灰石": "石灰岩.png",
    "泥石": "青泥石.png",
    "蚌": "蚌.png",
}


def fix_image_map():
    with io.open(IMAP, encoding="utf-8") as f:
        raw = f.read()
    obj = json.loads(raw)
    img_dir = os.path.join(ROOT, "app", "src", "main", "assets", "img")
    added = 0
    for k, v in IMAGE_ALIASES.items():
        if k in obj:
            continue
        # 目标文件必须真实存在，否则宁可不加（不猜图）
        found = False
        for root_dir, _d, files in os.walk(img_dir):
            if os.path.basename(v) in files:
                found = True
                break
        if not found:
            note(f"image_map: 目标图缺失，跳过 {k} -> {v}")
            continue
        obj[k] = v
        added += 1
    if added:
        note(f"image_map: +{added} 条别名")
    # 格式检查：原风格是「{ 独占首行 + 每条目一行 + 无结尾换行」，破坏了就整体归位（幂等）
    if added or not raw.startswith("{\r\n"):
        lines = ",\r\n".join(
            f"{json.dumps(k, ensure_ascii=False)}: {json.dumps(v, ensure_ascii=False)}"
            for k, v in obj.items()
        )
        with io.open(IMAP, "w", encoding="utf-8", newline="") as f:
            f.write("{\r\n" + lines + "\r\n}")


# ──────────────────────────────────────────────────────────────
# D4 删除无消费方的死 JSON（先 grep 复核过：全项目零引用）
# ──────────────────────────────────────────────────────────────
DEAD_FILES = [
    "cropXp.json", "fishXp.json", "miningXp.json", "skillXpCurve.json",
    "weaponDamage.json", "monsterStats.json", "jojaGoods.json",
    "smelting.json",  # 键全 mismatch 的死数据（raw/product 中文键 vs 数据英文键），加载已移除
]


def delete_dead():
    for fn in DEAD_FILES:
        fp = os.path.join(DATA, fn)
        if os.path.exists(fp):
            os.remove(fp)
            note(f"删除死数据文件: data/{fn}")


def main():
    print("== fix_v217 数据订正 ==")
    fix_crops()
    fix_processing_profit()
    fix_processing()
    fix_museum()
    fix_bundles()
    fix_gifts()
    fix_shop_hours()
    fix_unlocks()
    fix_image_map()
    delete_dead()
    print(f"== 完成，{len(changes)} 处改动（重复运行时应为 0）==")


if __name__ == "__main__":
    main()
