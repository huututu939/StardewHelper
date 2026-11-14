# -*- coding: utf-8 -*-
"""
build_cheat_codes_v279.py —— 重建 assets/data/cheatCodes.json（小鸡代码 / 作弊码）

背景
----
v2.18 时小鸡代码只收了 28 条（popular_codes）。群友给了一份 134 条的补全清单
（F:\\Downloads\\小鸡代码补全清单.json），本脚本把清单落进 App，并把编号按
**本地可查的官方 1.6 物品 ID 索引**逐条核过一遍。

编号口径（重要，勿随手改）
--------------------------
1) 纯数字 = 物品在游戏里的 **object id**。本地权威源：v9 包的 sprite 导出
   `images/items/<id>_<官方英文名>.png`（文件名首段就是 id）。
   该索引已与 App 现有 28 条逐条对齐（74 五彩碎片 / 499 上古种子 / 337 铱金锭 …），
   26/26 全中 → 可当官方 id 表用。
2) 带前缀 = id 本身仍属 object id，只是**要按品类上下文解析**：
   (W) 武器 / (B) 靴子 / (BC) 大件 / (H) 帽子；普通物品 (O) 可省略。
   武器编号另有 App 自带的 data/weaponCompendium.json（每条自带 (W) 编号）可核。
3) **取名能不能刷出来，只看有没有前缀**：
   - 纯数字 → 给动物/角色取名输入这个数字就出物品（method=naming）
   - 带前缀 → 取名无效，得用 SMAPI / CJB 作弊菜单或聊天调试命令（method=chat）
   这是清单里【最重要】的一条，App 必须标出来，否则玩家照着取名刷不出来。

本脚本做什么
------------
- 合并现有 28 条 + 清单 134 条（去掉 1 条重复：(BC)645 铱金洒水器已在旧表里）
- 逐条核编号：sprite 索引能查到就用官方 id；查得到但与清单不同 → 记为**订正**
- 查不到官方 id 的（1.6 新物品、1.6 新靴子）→ 保留清单编号并标 note「编号待核对」
- 顺带把缺的像素图标从 v9 sprite 导进 assets/img/wiki/items/，并登记 image_map

用法：python tools/build_cheat_codes_v279.py [--apply]
"""
import io
import json
import os
import re
import shutil
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
DATA = os.path.join(ASSETS, "data")
IMGMAP = os.path.join(ASSETS, "image_map.json")
IMG_DIR = os.path.join(ASSETS, "img")
WIKI_ITEMS = os.path.join(IMG_DIR, "wiki", "items")
V9 = "_V9_DIR()"
V9_ITEMS = os.path.join(V9, "images", "items")

CC_PATH = os.path.join(DATA, "cheatCodes.json")

# ══════════════════════════════════════════════════════════════════
# 0) 基线：v2.18 已有的 28 条（取自 v9 数据包 popular_codes，字段一字未改）
#    固化在这里而不是读现文件 —— 否则脚本不幂等：跑第二遍会把上一遍的输出
#    当基线，订正就再也跑不出来了。
# ══════════════════════════════════════════════════════════════════
BASE = [
    ("五彩碎片", "Prismatic Shard", "74", "宝石", "最稀有宝石，沙漠石碑兑换银河剑"),
    ("传说之鱼", "Legend", "163", "鱼", "最值钱的鱼王，春夏雨天山区湖"),
    ("银河剑", "Galaxy Sword", "(W)4", "武器", "沙漠三块五彩石碑兑换的最强光剑"),
    ("幸运兔子的脚", "Rabbit's Foot", "446", "动物制品", "提升运气，万能礼物"),
    ("铱金洒水器", "Iridium Sprinkler", "(BC)645", "特殊物品", "覆盖24格，农场自动化核心"),
    ("上古种子", "Ancient Seeds", "499", "种子", "种植远古果实，可重复收获"),
    ("星之果实", "Stardrop", "434", "特殊物品", "永久增加34点能量上限"),
    ("铱金锭", "Iridium Bar", "337", "金属锭", "最高级加工金属，升级工具必需"),
    ("南瓜", "Pumpkin", "276", "作物", "万灵节高产秋季作物"),
    ("杨桃", "Starfruit", "268", "作物", "最值钱的普通作物，酿酒首选"),
    ("上古水果", "Ancient Fruit", "454", "作物", "最值钱果子，酿酒长线收益"),
    ("钻石", "Diamond", "72", "宝石", "万能礼物，合成坚固戒指素材"),
    ("红宝石", "Ruby", "64", "宝石", "火山锻造增加武器攻击"),
    ("绿宝石", "Emerald", "60", "宝石", "火山锻造提升武器速度"),
    ("紫水晶", "Amethyst", "66", "宝石", "早期最易获得的好感礼物"),
    ("草莓", "Strawberry", "400", "作物", "春季多次结果，回本快"),
    ("蔓越莓", "Cranberry", "282", "作物", "秋季多次结果，高产"),
    ("绯红鱼", "Crimsonfish", "159", "鱼", "四大鱼王之一，夏季海滩"),
    ("鮟鱇鱼", "Angler", "160", "鱼", "四大鱼王之一，秋季小镇河"),
    ("冰川鱼", "Glacierfish", "775", "鱼", "四大鱼王之一，冬季南岛尽头"),
    ("变异鲤鱼", "Mutant Carp", "682", "鱼", "四大鱼王之一，下水道"),
    ("虚空蛋", "Void Egg", "305", "动物制品", "孵化器孵出虚空鸡"),
    ("恐龙蛋", "Dinosaur Egg", "107", "古物", "孵化器孵出恐龙，产恐龙 Mayo"),
    ("虎斑史莱姆蛋", "Tiger Slime Egg", "857", "杂项", "1.6虎斑史莱姆巢穴蛋"),
    ("金南瓜", "Golden Pumpkin", "373", "特殊物品", "万灵节迷宫奖品，送礼用"),
    ("放射性矿石", "Radioactive Ore", "909", "矿石", "制作放射性锭与末日装置"),
    ("海蓝宝石", "Aquamarine", "62", "宝石", "火山锻造增加暴击率"),
    ("金锭", "Gold Bar", "336", "金属锭", "中期最常用高级金属"),
]

# ══════════════════════════════════════════════════════════════════
# 1) 清单 134 条（分类, 名称, 编号, method）
#    分类名 = App 图鉴已有的 category_cn 词表
# ══════════════════════════════════════════════════════════════════
NEW = [
    # ── 宝石 ──
    ("宝石", "黄水晶", "68", "naming"),
    ("宝石", "翡翠", "70", "naming"),
    ("宝石", "石英", "80", "naming"),
    ("宝石", "火水晶", "82", "naming"),
    ("宝石", "冰泪石", "84", "naming"),
    ("宝石", "地晶", "86", "naming"),
    # ── 采集物 ──
    ("采集物", "山洞萝卜", "78", "naming"),
    ("采集物", "香蕉", "91", "naming"),
    ("采集物", "椰子", "88", "naming"),
    ("采集物", "仙人掌果", "90", "naming"),
    ("采集物", "甜宝石果", "417", "naming"),
    # ── 矿石 / 金属锭 ──
    ("矿石", "铜矿石", "378", "naming"),
    ("矿石", "铁矿石", "380", "naming"),
    ("矿石", "煤炭", "382", "naming"),
    ("矿石", "金矿石", "384", "naming"),
    ("矿石", "铱矿石", "386", "naming"),
    ("矿石", "石头", "390", "naming"),
    ("金属锭", "铜锭", "334", "naming"),
    ("金属锭", "铁锭", "335", "naming"),
    ("金属锭", "精炼石英", "338", "naming"),
    ("金属锭", "放射性矿锭", "910", "naming"),
    # ── 特殊物品 ──
    ("特殊物品", "金核桃", "73", "naming"),
    ("特殊物品", "金色椰子", "791", "naming"),
    ("特殊物品", "晶洞", "535", "naming"),
    ("特殊物品", "冰封晶洞", "536", "naming"),
    ("特殊物品", "岩浆晶洞", "537", "naming"),
    ("特殊物品", "万象晶球", "749", "naming"),
    ("特殊物品", "稀有种子", "347", "naming"),
    ("特殊物品", "松露", "430", "naming"),
    ("特殊物品", "银河之魂", "896", "naming"),
    ("特殊物品", "龙牙", "852", "naming"),
    ("特殊物品", "珍珠", "797", "naming"),
    ("特殊物品", "马笛", "911", "naming"),
    ("特殊物品", "魔法糖冰棍", "279", "naming"),
    ("特殊物品", "齐钻", "858", "naming"),
    ("特殊物品", "魔法墨水", "856", "naming"),
    ("特殊物品", "金蛋", "928", "naming"),
    ("特殊物品", "电影票", "809", "naming"),
    ("特殊物品", "虚空幽灵吊坠", "808", "naming"),
    ("特殊物品", "皮埃尔缺货清单", "897", "naming"),
    ("特殊物品", "矮人语翻译指南", "326", "naming"),
    ("特殊物品", "加压喷头", "915", "naming"),
    # ── 动物制品 ──
    ("动物制品", "蓝色史莱姆蛋", "413", "naming"),
    ("动物制品", "红色史莱姆蛋", "437", "naming"),
    ("动物制品", "紫色史莱姆蛋", "439", "naming"),
    ("动物制品", "鸵鸟蛋", "289", "naming"),
    ("动物制品", "鸭蛋", "442", "naming"),
    ("动物制品", "鸭毛", "444", "naming"),
    ("动物制品", "羊毛", "440", "naming"),
    ("动物制品", "鱼籽", "812", "naming"),
    ("动物制品", "墨汁", "814", "naming"),
    # ── 戒指 ──
    ("戒指", "小光辉戒指", "516", "naming"),
    ("戒指", "光辉戒指", "518", "naming"),
    ("戒指", "磁铁戒指", "519", "naming"),
    ("戒指", "史莱姆克星戒指", "520", "naming"),
    ("戒指", "战士戒指", "521", "naming"),
    ("戒指", "吸血戒指", "522", "naming"),
    ("戒指", "骷髅戒指", "523", "naming"),
    ("戒指", "保护戒指", "524", "naming"),
    ("戒指", "坚固戒指", "525", "naming"),
    ("戒指", "幸运戒指", "526", "naming"),
    ("戒指", "盗贼戒指", "527", "naming"),
    ("戒指", "黄水晶戒指", "528", "naming"),
    ("戒指", "紫水晶戒指", "529", "naming"),
    ("戒指", "铱戒指", "530", "naming"),
    ("戒指", "海蓝宝石戒指", "531", "naming"),
    ("戒指", "翡翠戒指", "532", "naming"),
    ("戒指", "红宝石戒指", "534", "naming"),
    ("戒指", "蟹壳戒指", "810", "naming"),
    ("戒指", "烧夷戒指", "811", "naming"),
    ("戒指", "荆棘戒指", "839", "naming"),
    ("戒指", "热咖啡戒指", "860", "naming"),
    ("戒指", "凤凰戒指", "863", "naming"),
    # ── 炸弹 ──
    ("炸弹", "樱桃炸弹", "286", "naming"),
    ("炸弹", "炸弹", "287", "naming"),
    ("炸弹", "超级炸弹", "288", "naming"),
    ("炸弹", "爆炸弹药", "441", "naming"),
    # ── 怪物材料 ──
    ("怪物材料", "史莱姆", "766", "naming"),
    ("怪物材料", "蝙蝠翅膀", "767", "naming"),
    ("怪物材料", "太阳精华", "768", "naming"),
    ("怪物材料", "虚空精华", "769", "naming"),
    ("怪物材料", "虫肉", "784", "naming"),
    # ── 药剂 / 食物 ──
    ("药剂", "生命药水", "773", "naming"),
    ("药剂", "能量滋补水", "349", "naming"),
    ("食物", "三倍浓缩咖啡", "253", "naming"),
    # ── 种子 ──
    ("种子", "混合种子", "770", "naming"),
    # ── 古物 ──
    ("古物", "矮人卷轴I", "96", "naming"),
    ("古物", "矮人卷轴II", "97", "naming"),
    ("古物", "矮人卷轴III", "98", "naming"),
    ("古物", "矮人卷轴IV", "99", "naming"),
    ("古物", "箭头", "101", "naming"),
    ("古物", "精灵珠宝", "104", "naming"),
    ("古物", "古剑", "109", "naming"),
    ("古物", "生锈齿轮", "112", "naming"),
    ("古物", "鹦鹉螺化石", "392", "naming"),
    # ── 武器 (W) ──
    ("武器", "黑暗剑", "(W)2", "chat"),
    ("武器", "圣刃", "(W)3", "chat"),
    ("武器", "黑曜石之刃", "(W)8", "chat"),
    ("武器", "熔岩武士刀", "(W)9", "chat"),
    ("武器", "双手大剑", "(W)10", "chat"),
    ("武器", "银河匕首", "(W)23", "chat"),
    ("武器", "银河锤", "(W)29", "chat"),
    ("武器", "弯刀", "(W)44", "chat"),
    ("武器", "雪人牙", "(W)48", "chat"),
    ("武器", "金色镰刀", "(W)53", "chat"),
    ("武器", "铱针", "(W)61", "chat"),
    ("武器", "无限之刃", "(W)62", "chat"),
    ("武器", "无限之锤", "(W)63", "chat"),
    ("武器", "无限匕首", "(W)64", "chat"),
    # ── 靴子 (B) ──
    ("靴子", "蟹壳靴", "(B)515", "chat"),
    ("靴子", "太空靴", "(B)519", "chat"),
    ("靴子", "战地靴", "(B)800", "chat"),
    ("靴子", "火焰靴", "(B)804", "chat"),
    ("靴子", "岩石靴", "(B)806", "chat"),
    ("靴子", "精灵靴", "(B)854", "chat"),
    ("靴子", "恐龙靴", "(B)855", "chat"),
    ("靴子", "防火靴", "(B)878", "chat"),
    ("靴子", "冷却靴", "(B)879", "chat"),
    # ── 大件 (BC) ──
    ("设备", "洒水器", "(BC)599", "chat"),
    ("设备", "优质洒水器", "(BC)621", "chat"),
    ("设备", "铱金洒水器", "(BC)645", "chat"),
    ("设备", "熔炉", "(BC)13", "chat"),
    ("设备", "蜂房", "(BC)10", "chat"),
    ("设备", "酒桶", "(BC)12", "chat"),
    ("设备", "罐头瓶", "(BC)15", "chat"),
    ("设备", "压酪机", "(BC)16", "chat"),
    ("设备", "织布机", "(BC)17", "chat"),
    ("设备", "榨油机", "(BC)19", "chat"),
    ("设备", "回收机", "(BC)20", "chat"),
    ("设备", "水晶球", "(BC)21", "chat"),
    ("设备", "蛋黄酱机", "(BC)24", "chat"),
    ("设备", "种子生产器", "(BC)25", "chat"),
    ("设备", "自动采集器", "(BC)165", "chat"),
    ("设备", "自动抚摸器", "(BC)272", "chat"),
    ("设备", "太阳能板", "(BC)231", "chat"),
]

# ══════════════════════════════════════════════════════════════════
# 2) 编号订正表：清单值 → 本地官方 ID 索引核出来的真值
#    每条都注明证据来源（sprite = v9 sprite 文件名首段；wp = App 武器图鉴自带 (W) 编号）
# ══════════════════════════════════════════════════════════════════
CORRECT = {
    # —— 普通物品（object id）——
    "石头":        ("40",   "sprite 40_Stone（清单的 390 在 1.5+ 编号表里不是石头）"),
    "虫肉":        ("684",  "sprite 684_Bug_Meat"),
    "鹦鹉螺化石":  ("586",  "sprite 586_Nautilus_Fossil"),
    # —— 戒指：object id 516~534 连续 19 个，其中 17 个已被 sprite 证实；
    #    剩下的 527 = Iridium Band、528 = Skull Ring（区间排除法，与 wiki 戒指表吻合）——
    "光辉戒指":    ("517",  "sprite 517_Glow_Ring"),
    "骷髅戒指":    ("528",  "516~534 区间排除（sprite 缺 527/528，527=Iridium Band）"),
    "保护戒指":    ("861",  "sprite 861_Protection_Ring（1.6 戒指在 859~863 段）"),
    "幸运戒指":    ("859",  "sprite 859_Lucky_Ring"),
    "盗贼戒指":    ("526",  "sprite 526_Burglar's_Ring"),
    "黄水晶戒指":  ("530",  "sprite 530_Topaz_Ring"),
    "铱戒指":      ("527",  "sprite 缺 527，区间排除 = Iridium Band"),
    # —— 靴子：object id 505~515 段（Combat 508 / Space 514 / Cowboy 515 已证）——
    "太空靴":      ("(B)514", "sprite 514_Space_Boots"),
    "战地靴":      ("(B)508", "sprite 508_Combat_Boots"),
    # —— 黄金镰刀是**工具**不是武器：53 号位在武器表里不存在（weaponCompendium 无 53），
    #    sprite 53 = Golden_Scythe（工具）→ 用纯数字，取名可用 ——
    "金色镰刀":    ("53",   "sprite 53_Golden_Scythe；武器图鉴无 (W)53 → 属工具，取纯数字"),
}

# 清单用词 → App / item_index 用词（只为取官方英文名 + 说明 + 已有图标）
ALIAS = {
    "圣刃": "圣光剑", "双手大剑": "大剑", "雪人牙": "雪怪之牙", "金色镰刀": "黄金镰刀",
    "战地靴": "战斗靴", "太空靴": "太空靴", "精灵靴": "精灵靴",
    "小光辉戒指": "小发光戒指", "光辉戒指": "发光戒指", "磁铁戒指": "磁吸戒指",
    "史莱姆克星戒指": "史莱姆安抚戒指", "吸血戒指": "吸血鬼戒指", "铱戒指": "铱金戒指",
    "黄水晶戒指": "黄玉戒指", "翡翠戒指": "玉石戒指", "烧夷戒指": "凝固汽油戒指",
    "地晶": "大地水晶", "冰泪石": "冰冻泪滴", "火水晶": "火石英", "黄水晶": "黄玉",
    "山洞萝卜": "洞穴萝卜", "金核桃": "黄金椰子", "冰封晶洞": "冰冻晶洞",
    "玉米": "玉米", "齐钻": "齐钻", "矮人卷轴I": "矮人卷轴I",
    "无限匕首": "无限匕首", "精灵靴": "精灵靴", "恐龙靴": "恐龙靴",
    "火焰靴": "火焰靴", "岩石靴": "岩石靴", "防火靴": "防火靴", "冷却靴": "冷却靴",
    "蟹壳靴": "蟹壳靴",
}

# 这些条目本地查不到官方 id（1.6 新物品 / 1.6 新靴子）→ 保留清单编号 + 标注待核对
NEED_CHECK = {
    "金核桃", "金色椰子", "银河之魂", "龙牙", "马笛", "齐钻", "电影票", "虚空幽灵吊坠",
    "皮埃尔缺货清单", "鱼籽", "爆炸弹药", "加压喷头",
    "蟹壳靴", "火焰靴", "岩石靴", "精灵靴", "恐龙靴", "防火靴", "冷却靴",
}


def load(p):
    with io.open(p, encoding="utf-8") as f:
        return json.load(f)


def sprite_index():
    """英文名(小写) → (id, 文件名)；id → [英文名...]"""
    by_en, by_id = {}, {}
    if not os.path.isdir(V9_ITEMS):
        return by_en, by_id
    for fn in sorted(os.listdir(V9_ITEMS)):
        m = re.match(r"^(\d+)_(.+)\.png$", fn)
        if not m:
            continue
        i, en = int(m.group(1)), m.group(2).replace("_", " ")
        by_en.setdefault(en.strip().lower(), (i, fn))
        by_id.setdefault(i, []).append((en, fn))
    return by_en, by_id


def main():
    apply = "--apply" in sys.argv
    old = load(CC_PATH)
    imgmap = load(IMGMAP)
    iidx = load(os.path.join(ROOT, "tools", "game_data_v16", "item_index.json"))
    wc = load(os.path.join(DATA, "weaponCompendium.json"))
    sp_en, sp_id = sprite_index()

    # item_index：中文名 → 条目
    ii_by_cn = {}
    for node in iidx["data"]["categories"].values():
        for it in node.get("items", []):
            if it.get("name") and it["name"] not in ii_by_cn:
                ii_by_cn[it["name"]] = it
    # v9 配方表：中文名 → 条目（大件的官方英文名主要靠它，item_index 没有大件分类）
    cf_by_cn = {}
    cf_path = os.path.join(V9, "stardew_data", "crafting.json")
    if os.path.exists(cf_path):
        for it in load(cf_path).get("data", []):
            if it.get("name") and it["name"] not in cf_by_cn:
                cf_by_cn[it["name"]] = it
    # 武器图鉴：中文名 → 条目（自带 (W) 编号）
    wp_by_cn = {w.get("name"): w for w in wc["weapons"]}

    entries = []
    corrections = []
    notes = []

    # —— 基线 28 条原样保留（字段一字不改），只补 method ——
    for name, en, code, cat, desc in BASE:
        ne = {"name": name, "name_en": en, "spawn_code": code,
              "category_cn": cat, "description": desc,
              "method": "naming" if code.isdigit() else "chat"}
        entries.append(ne)

    seen = {e["spawn_code"] for e in entries}
    seen_name = {e["name"] for e in entries}

    for cat, name, code, method in NEW:
        if name in seen_name:
            notes.append("跳过重名：%s" % name)
            continue

        final_code = code
        note = ""
        src = "list"

        # ① 订正表优先（每条都有本地证据）
        if name in CORRECT:
            final_code, why = CORRECT[name]
            src = "index"
            if final_code != code:
                corrections.append((name, code, final_code, why))

        # ⚠ 去重必须在**订正之后**：骷髅戒指 523→528、黄水晶戒指 528→530 这对
        #   在订正前看是重复编号，订正后是两件不同的物品。
        if final_code in seen:
            notes.append("跳过重复编号：%s %s" % (name, final_code))
            continue

        # ② 取官方英文名：武器图鉴 → item_index → v9 配方表
        en = ""
        alias = ALIAS.get(name, name)
        ii = ii_by_cn.get(alias)
        wp = wp_by_cn.get(alias)
        cf = cf_by_cn.get(alias)
        if wp:
            # 武器图鉴自带 (W) 编号 —— 与清单不一致说明清单错了
            wcode = "(W)%s" % wp.get("id")
            en = wp.get("name_en") or en
            if final_code.startswith("(W)") and final_code != wcode and name not in CORRECT:
                corrections.append((name, final_code, wcode, "App 武器图鉴 weaponCompendium.json 自带编号"))
                final_code = wcode
                src = "index"
        if not en and ii:
            en = ii.get("name_en") or ""
        if not en and cf:
            en = cf.get("name_en") or ""

        # ③ sprite 索引复核编号（sprite 首段 = 官方 object id）
        #    纯数字与带前缀都查：前缀只改解析上下文，数字仍是 object id。
        num = final_code.strip("()").lstrip("(WBC)")
        num = "".join(ch for ch in num if ch.isdigit())
        hit = sp_en.get((en or "").lower())
        if hit:
            sid, sfn = hit
            if str(sid) != num:
                newcode = final_code if final_code.isdigit() else "(%s)%s" % (final_code[1], sid)
                corrections.append((name, final_code, newcode, "sprite %s（官方 object id）" % sfn))
                final_code = newcode
                num = str(sid)
                src = "index"
            else:
                src = "index"
            if not en:
                en = hit[0]
        elif final_code.isdigit() and not en:
            cands = sp_id.get(int(final_code), [])
            if len(cands) == 1:
                en = cands[0][0]
                src = "index"
        elif en:
            src = "domain"

        # ④ 本地查不到官方 id → 标待核对
        if name in NEED_CHECK and src != "index":
            note = "编号未经本地官方 ID 索引核对，以游戏内为准"
            src = "unverified"

        desc = ""
        if ii and ii.get("description"):
            desc = ii["description"]
        if not desc and cf and cf.get("source_detail"):
            desc = cf["source_detail"]

        e = {"name": name, "spawn_code": final_code, "category_cn": cat}
        # method 由**最终编号形态**推导，不照抄清单：
        #   纯数字 = object id → 给动物/角色取名输入这个数字就出物品；
        #   带 (W)/(B)/(BC) 前缀 = 取名无效，得用 SMAPI/CJB 作弊菜单或聊天调试命令。
        # （金色镰刀就是这一档的活例子：清单写 (W)53，但 53 在武器编号表里不存在，
        #   它是工具 → 订正成纯数字 53 → 取名可用。）
        e["method"] = "naming" if final_code.isdigit() else "chat"
        if en:
            e["name_en"] = en
        if desc:
            e["description"] = desc
        if note:
            e["note"] = note
        entries.append(e)
        seen.add(final_code)
        seen_name.add(name)

    # 排序：先取名可用（naming），再需作弊菜单（chat）；组内保持录入顺序
    entries.sort(key=lambda e: 0 if e["method"] == "naming" else 1)

    # 统一字段顺序（老条目是 name/name_en/spawn_code/category_cn/description，method 追加在尾）
    ORDER = ("name", "name_en", "spawn_code", "category_cn", "description", "method", "note")
    entries = [dict((k, e[k]) for k in ORDER if e.get(k)) for e in entries]

    # ── 图标 ──────────────────────────────────────────────
    icon_added, icon_alias, icon_none = [], [], []
    img_files = set(os.listdir(IMG_DIR)) if os.path.isdir(IMG_DIR) else set()
    for e in entries:
        name = e["name"]
        if name in imgmap:
            continue
        alias = ALIAS.get(name, name)
        en = (e.get("name_en") or "").lower()
        hit = sp_en.get(en)
        if hit:
            sid, sfn = hit
            src = os.path.join(V9_ITEMS, sfn)
            dst = os.path.join(WIKI_ITEMS, sfn)
            if apply and os.path.exists(src) and not os.path.exists(dst):
                os.makedirs(WIKI_ITEMS, exist_ok=True)
                shutil.copyfile(src, dst)
            imgmap[name] = "wiki/items/%s" % sfn
            icon_added.append((name, sfn))
        elif alias != name and alias in imgmap:
            imgmap[name] = imgmap[alias]
            icon_alias.append((name, alias, imgmap[alias]))
        elif (name + ".png") in img_files or (name + ".webp") in img_files:
            # App 里早就有这张图，只是没登记键（verify 37.5 专门盯这种孤儿图）
            imgmap[name] = name + (".webp" if (name + ".webp") in img_files else ".png")
            icon_alias.append((name, "（同名图已在 img/）", imgmap[name]))
        else:
            icon_none.append(name)

    # ── 报告 ──────────────────────────────────────────────
    n_naming = sum(1 for e in entries if e["method"] == "naming")
    n_chat = sum(1 for e in entries if e["method"] == "chat")
    print("== 条目 %d 条（取名可用 %d / 需作弊菜单 %d）==" % (len(entries), n_naming, n_chat))
    print("\n== 编号订正 %d 处（清单值 → 官方 ID）==" % len(corrections))
    for n, a, b, why in corrections:
        print("   %-12s %-9s -> %-9s  %s" % (n, a, b, why))
    print("\n== 编号待核对 %d 条 ==" % len([e for e in entries if e.get("note")]))
    for e in entries:
        if e.get("note"):
            print("   %-12s %s" % (e["name"], e["spawn_code"]))
    print("\n== 图标：新增 %d / 别名 %d / 无图 %d ==" % (len(icon_added), len(icon_alias), len(icon_none)))
    if icon_none:
        print("   无图：", "、".join(icon_none))
    if notes:
        print("\n== 提示 ==")
        for n in notes:
            print("   ", n)

    if not apply:
        print("\n（未 --apply，未写文件）")
        return

    with io.open(CC_PATH, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(entries, ensure_ascii=False, indent=1))
    print("\n已写 %s（%d 条）" % (CC_PATH, len(entries)))
    with io.open(IMGMAP, "w", encoding="utf-8", newline="\n") as f:
        f.write(json.dumps(imgmap, ensure_ascii=False, sort_keys=True, indent=0))
    print("已写 %s（%d 条，新增 %d / 别名 %d）" % (IMGMAP, len(imgmap), len(icon_added), len(icon_alias)))


if __name__ == "__main__":
    main()
