# -*- coding: utf-8 -*-
"""生成「农场年度报告」要用的英文名 → 中文名对照表。

## 为什么需要它
存档里 NPC 好感度、怪物击杀的键都是**英文内部名**（`Lewis` / `Green Slime`），
报告要出中文，必须有一张对照表。手工维护 78 怪 + 40 NPC 的表必然漂移，
所以这里从项目既有的 **v9 真源数据**生成，生成物提交进 assets，构建不依赖 v9 目录。

## 真源（都是 v9 抓取整理过的官方 wiki 结构化数据）
  - stardew_data/monster_compendium.json → data.monsters[] 的 name / name_en
  - stardew_data/npc_affection.json      → data.npcs[] 的 name / name_en
  - stardew_data/new_npc_1_6.json       → data.new_npcs[] 的 name / name_en

## 输出
  app/src/main/assets/data/reportNames.json
    { "monsters": {"Green Slime": "绿色史莱姆", ...},
      "npcs":    {"Abigail": "阿比盖尔", ...},
      "favorite": {...} }          # 1.6「最喜欢的东西」内部值 → 中文

## 查不到怎么办
对照表查不到时**照原样显示英文**，不猜、不空着——「Dust Spirit」这种
游戏内部名和 wiki 译名对不上的情况是真实存在的，编一个中文名比显示英文更糟。
"""
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
V9 = os.path.join(ROOT, "..", "旧文件归档", "旧文件归档/stardew_valley_1.6_data_v9", "stardew_data")
OUT = os.path.join(ROOT, "app", "src", "main", "assets", "data", "reportNames.json")

# 1.6「最喜欢的东西」：**不是枚举，是玩家在建档时自己输入的自由文本**
# （官方 wiki 原文："in the starter menu you have the option to type your favorite thing"）。
# 所以这里**不维护译名表**——玩家打什么就显示什么，编一个中文名反而是撒谎。
# 唯一收录的是有官方含义的那一个：打了 "concerned ape"，吃星之花时猿猴会回一句谢谢。
FAVORITE = {
    "ConcernedApe": "关切的猿",
}


# 游戏**内部名** → 项目既有中文译名。
#
# 为什么需要：存档里 `specificMonstersKilled` 的键是**游戏内部名**，
# 而 v9 compendium 用的是**英文 wiki 的页面名**，两者有出入（真档实测）：
#   游戏 "Dust Spirit" ↔ wiki "Dust Sprite"（灰尘精灵）
#   游戏 "Fly"        ↔ wiki "Cave Fly"（苍蝇）
#   游戏 "Sludge"     ↔ wiki "Red Sludge"（红色泥浆）
#   游戏 "Big Slime"  ↔ wiki 按颜色分成 4 个（统一记「巨型史莱姆」）
#   游戏 "Frost Jelly" ↔ wiki 页面是英文，中文名见项目 wiki 原文「冰冻史莱姆」
# 这些不是猜的：真档里出现过的名字 + 项目既有译名（image_map / monsterDrops）能对上。
MONSTER_ALIAS = {
    "Dust Spirit": "灰尘精灵",
    "Fly": "苍蝇",
    "Sludge": "红色泥浆",
    "Big Slime": "巨型史莱姆",
    "Frost Jelly": "冰冻史莱姆",
}


def load(name):
    p = os.path.join(V9, name)
    if not os.path.exists(p):
        print("缺少真源：%s" % p)
        return None
    with open(p, encoding="utf-8") as f:
        return json.load(f)


def collect(obj, out, field="name"):
    """从 [{name, name_en, ...}] 里收 {name_en: name}。"""
    for m in obj or []:
        en = (m.get("name_en") or "").strip()
        cn = (m.get(field) or "").strip()
        if en and cn:
            out[en] = cn
    return out


def main():
    mon_doc = load("monster_compendium.json")
    npc_doc = load("npc_affection.json")
    new_doc = load("new_npc_1_6.json")
    if not mon_doc or not npc_doc:
        print("真源缺失，未生成")
        return 1

    monsters = collect(mon_doc["data"].get("monsters"), {})
    # 游戏内部名优先（真档里用的就是它），wiki 名作为补充
    for en, cn in MONSTER_ALIAS.items():
        monsters[en] = cn
    npcs = collect(npc_doc["data"].get("npcs"), {})
    collect((new_doc or {}).get("data", {}).get("new_npcs"), npcs)

    out = {
        "_note": "英文内部名 → 中文名。生成物：tools/gen_report_names.py（真源 v9 数据）",
        "monsters": dict(sorted(monsters.items())),
        "npcs": dict(sorted(npcs.items())),
        "favorite": dict(sorted(FAVORITE.items())),
    }
    with open(OUT, "w", encoding="utf-8", newline="") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("写入 %s：怪物 %d · NPC %d · favorite %d"
          % (OUT, len(monsters), len(npcs), len(FAVORITE)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
