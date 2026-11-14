# -*- coding: utf-8 -*-
"""v2.18 数据加工（幂等，可反复运行）—— tools/import_v218.py

从 v9 数据包 stardew_valley_1.6_data_v9/stardew_data/ 加工两份 assets：
  1. monsterFarming.json —— 战斗刷级用的区域经验聚合 + 冒险家公会 12 条目标 + 78 怪明细
     （只取刷级计算所需字段，不整表照搬，避免 94KB 全进 APK）
  2. cheatCodes.json     —— popular_codes 28 条小鸡代码

口径原则（与项目既有惯例一致）：
  - 区域名/描述已是中文，原样保留；未收录的英文枚举值回退原文，不猜译
  - 不伪造没有权威依据的数字：击杀耗时无权威数据 → 只给「XP/只」，不给「XP/分钟」
  - 写回保持 assets JSON 风格：indent=1 + CRLF + 无结尾换行（v2.17 已踩过格式坑）
"""
import json
import io
import os
import re
import sys

sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "app", "src", "main", "assets", "data")
V9 = _V9_DIR() + "/stardew_data"

changes = []


def note(msg):
    changes.append(msg)
    print("  * " + msg)


def load(p):
    with io.open(p, encoding="utf-8") as f:
        return json.load(f)


def save(path, obj):
    with io.open(path, "w", encoding="utf-8", newline="\r\n") as f:
        json.dump(obj, f, ensure_ascii=False, indent=1)


def setv(o, key, val, tag):
    if o.get(key) != val:
        o[key] = val
        note("%s: %s" % (tag, key))


# ══════════════════════════════════════════════════════════════
# 1) monsterFarming.json
# ══════════════════════════════════════════════════════════════
def parse_floors(text):
    """'1-29' -> (1,29)；'81+' -> (81,None)；'全部' -> None（解析不出就交原文）"""
    if not text:
        return None
    t = str(text).strip()
    m = re.match(r"^(\d+)\s*[-–~]\s*(\d+)$", t)
    if m:
        return [int(m.group(1)), int(m.group(2))]
    m = re.match(r"^(\d+)\s*\+$", t)
    if m:
        return [int(m.group(1)), None]
    m = re.match(r"^(\d+)$", t)
    if m:
        return [int(m.group(1)), int(m.group(1))]
    return None


def build_monster_farming():
    src = load(os.path.join(V9, "monster_compendium.json"))["data"]
    monsters = src.get("monsters", [])

    # 按 area 聚合（v2.15 踩过的坑：normal 必须先于 dangerous，按原顺序遍历即可）
    areas = {}
    for m in monsters:
        xp = m.get("xp") or 0
        for loc in (m.get("locations") or []):
            area = (loc.get("area") or "").strip()
            if not area:
                continue
            a = areas.setdefault(area, {"area": area, "monsters": {}})
            a["monsters"].setdefault(m["name"], {
                "name": m["name"], "xp": xp, "type": m.get("type", ""),
                "floors": loc.get("floors"), "conditions": loc.get("conditions", ""),
            })

    xp_by_area = []
    for area in areas.values():
        ms = sorted(area["monsters"].values(), key=lambda x: -x["xp"])
        total = sum(x["xp"] for x in ms)
        entries = []
        for x in ms:
            e = {"name": x["name"], "xp": x["xp"]}
            if x["type"]:
                e["type"] = x["type"]
            if x["floors"]:
                e["floors_text"] = x["floors"]
                rng = parse_floors(x["floors"])
                if rng:
                    e["floors"] = rng
            if x["conditions"]:
                e["conditions"] = x["conditions"]
            entries.append(e)
        xp_by_area.append({
            "area": area["area"],
            "monster_count": len(ms),
            "total_xp": total,
            # 口径：每只怪的经验均值（不是每分钟）
            "avg_xp": round(total / len(ms), 1) if ms else 0,
            "max_xp": ms[0]["xp"] if ms else 0,
            "monsters": entries,
        })
    # 排序：平均经验高的排前面（同值按怪物数多者优先）
    xp_by_area.sort(key=lambda a: (-a["avg_xp"], -a["monster_count"]))

    goals = []
    for g in (src.get("adventurer_guild_goals") or []):
        goals.append({
            "monster": g.get("monster", ""),
            "kill_goal": g.get("kill_goal"),
            "reward": g.get("reward", ""),
            "reward_cn_desc": g.get("reward_cn_desc", ""),
            "repurchase_price_g": g.get("repurchase_price_g"),
        })

    # 怪物明细：只取刷级相关字段
    details = []
    for m in monsters:
        locs = []
        for loc in (m.get("locations") or []):
            lo = {"area": loc.get("area", "")}
            if loc.get("floors"):
                lo["floors_text"] = loc["floors"]
                rng = parse_floors(loc["floors"])
                if rng:
                    lo["floors"] = rng
            if loc.get("conditions"):
                lo["conditions"] = loc["conditions"]
            locs.append(lo)
        details.append({
            "name": m.get("name", ""),
            "type": m.get("type", ""),
            "hp": m.get("hp"),
            "damage": m.get("damage"),
            "defense": m.get("defense"),
            "speed": m.get("speed"),
            "xp": m.get("xp"),
            "variant": m.get("variant", "normal"),
            "locations": locs,
        })
    details.sort(key=lambda x: (-(x.get("xp") or 0), x["name"]))

    notes_out = []
    cn = src.get("coverage_notes", {})
    if cn.get("farm_spawn_explanation"):
        notes_out.append({
            "topic": "农场夜间刷怪",
            "text": "农场夜晚按战斗等级刷怪：0-3 级绿史莱姆；4-7 级蓝史莱姆/霜蝙蝠；"
                    "8-9 级红史莱姆/熔岩蝙蝠/暗影野蛮人；10 级铱蝙蝠/紫史莱姆/飞蛇/铱傀儡。"
                    "⚠ 农场夜间怪只给 1/3 战斗经验（比下矿差很多）。",
        })
    if cn.get("armored_bug_caveat"):
        notes_out.append({
            "topic": "装甲虫经验陷阱",
            "text": "普通装甲虫 Wiki 家族页继承 Bug 的 HP=1（模板陷阱），实际数值无意义；"
                    "危险版装甲虫为 HP250 / 伤害 20-27 / 防御 0 / 速度 2 / XP 1。",
        })
    if cn.get("no_boss_monsters"):
        notes_out.append({
            "topic": "没有 Boss 怪",
            "text": "原版没有 Boss 级怪物（史莱姆王/蝙蝠王并不存在）。"
                    "最大体型是大史莱姆，已按绿/蓝/红/紫 4 色收录。",
        })

    out = {
        "_note": "XP 为「每只怪」的经验，不是每分钟；击杀耗时无权威数据故不估算速率",
        "xp_by_area": xp_by_area,
        "guild_goals": goals,
        "notes": notes_out,
        "monsters": details,
    }
    path = os.path.join(DATA, "monsterFarming.json")
    if not os.path.exists(path) or load(path) != out:
        save(path, out)
        note("写入 monsterFarming.json（区域 %d · 公会 %d · 怪物 %d）"
             % (len(xp_by_area), len(goals), len(details)))
    return out


# ══════════════════════════════════════════════════════════════
# 2) cheatCodes.json
# ══════════════════════════════════════════════════════════════
CATEGORY_CN = {
    "gem": "宝石", "fish": "鱼", "weapon": "武器", "crop": "作物",
    "seed": "种子", "ore": "矿石", "metal_bar": "金属锭",
    "animal_product": "动物制品", "artifact": "古物", "special": "特殊物品",
    "misc": "杂项",
}


def build_cheat_codes():
    src = load(os.path.join(V9, "item_index.json"))["data"]
    rows = []
    for c in (src.get("popular_codes") or []):
        cat = c.get("category", "")
        rows.append({
            "name": c.get("name", ""),
            "name_en": c.get("name_en", ""),
            "spawn_code": c.get("spawn_code", ""),
            # 未收录的类别回退原文（项目既定原则：宁可露英文也不猜译）
            "category_cn": CATEGORY_CN.get(cat, cat),
            "description": c.get("description", ""),
        })
    # ⚠ 顶层必须是「数组」：buildCodexGroups 的 arr() 直接 JSONArray(text)，
    #   对象顶层会抛 JSONException 导致整个图鉴为空（v2.18 图鉴闪退的根因，已修）。
    #   故 _note 不放这里，放进每条的 note 字段会污染 UI——改为文件级注释。
    out = rows
    path = os.path.join(DATA, "cheatCodes.json")
    if not os.path.exists(path) or load(path) != out:
        save(path, out)
        note("写入 cheatCodes.json（%d 条，顶层数组）" % len(rows))
    return out


def main():
    print("== import_v218 数据加工 ==")
    build_monster_farming()
    build_cheat_codes()
    print("== 完成，%d 处改动（重复运行时应为 0）==" % len(changes))


if __name__ == "__main__":
    main()
