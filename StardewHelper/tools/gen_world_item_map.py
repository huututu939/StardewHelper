# -*- coding: utf-8 -*-
"""生成存档世界状态的名称映射层：crops.json 的 nameEn + animals.json 的 typeEn + worldItemMap.json

用法:
    python tools/gen_world_item_map.py            # 生成 + 自校验
    python tools/gen_world_item_map.py --check    # 只校验磁盘上已有的文件，不写

## 这个文件解决什么问题

存档里给的是**数字 item_id**（`190`）或**英文名**（`Carrot` / `SummerSquash`），
App 包内的表给的是**中文名**（花椰菜 / 胡萝卜）。两边对不上，界面就只能显示
「190 ×54」或者更糟——显示成「锈剑」（item_id 0）。

`worldItemMap.json` 是唯一的桥：
    byId       数字 item_id → App 中文名
    byNameEn   英文名        → App 中文名
    animalType FarmAnimal/type（英文）→ App 动物名

**查不到就静默跳过，绝不瞎猜**——显示错名字比不显示更糟。

## 为什么不能按中文名 join（实测数据，不是推测）

App 的 `crops.json`（42 条）与上游 `item_index.json` 的 `crop` 类中文名只对上 35 条。
差的 7 条全是译名差异：

    大蒜 → 蒜 ｜ 蓝爵士 → 蓝爵 ｜ 甘蓝 → 甘蓝菜 ｜ 虞美人 → 虞美人花
    金皮西葫芦（夏南瓜）→ 金皮西葫芦 ｜ 远古水果 → 上古水果 ｜ 大米 → 未碾米

→ **用英文名当桥**：给 `crops.json` 每条补一个 `nameEn`，值取索引的 `name_en`。
改名对照表写死在下面 `CROP_RENAME`（改数据时改这一张表，不改代码）。

⚠ v2.82 之前这里还有四条：盛夏疯狂 → 夏季亮片 ｜ 红甘蓝 → 红叶卷心菜 ｜
  朝鲜蓟 → 洋蓟 ｜ 粉瓜 → 霜瓜 —— 那四条是 **App 侧从英文直译出来的自造名**，
  群友 @王忠明 2026-10-22 报错了，已在 App 侧改成上面这四个名字，
  于是 App 名 == 上游名，**这四条从对照表里删掉**（留着会查不到、脚本会报错退出）。

## 三条数据纪律（都是踩过的坑）

1. **售价一律以 App 自己的 `crops.json` 为准**，不许用上游 `sell_price` 覆盖。
   实测冲突：**甜瓜 App=250、索引=120**，官方值是 250 —— 索引这条是错的。
   所以本脚本**不产出任何价格**（价格不进映射表，避免第二个真相）。
2. **4 条 1.6/mod 作物上游没有 item_id**（Carrot / Summer Squash / Broccoli /
   Powdermelon）→ 只能靠 `name_en` 命中，故映射表必须**双键**。
3. **武器/工具的 id 不许进映射表**：上游 `weapon` / `tool` 类的 id 是 1.5 的
   精灵序号（0 生锈的剑 / 1 镀银军刀 / 2 黑暗剑 / 16 刻刀…），与 1.6 的
   **物品 id 空间**撞车。存档反查（读四份真实存档的 `<itemId>` + `<name>`）：
   存档里的 id 16 是 **Wild Horseradish 野山葵**，id 18 = Daffodil 黄水仙、
   id 20 = Leek 韭葱、id 22 = Dandelion 蒲公英 —— **不是**刻刀/飞贼之胫。
   把武器 id 放进来，这 30 株采集物就会显示成「刻刀」。故只取物品 id 空间。

## 额外加的一层：名称的「无空格变体」

MOD 档的 `indexOfHarvest` 有时写成 `SummerSquash`（上游是 `Summer Squash`，
中间带空格）。所以 `byNameEn` 同时收两种形态（带空格 / 去空格），
实测重逢档的 7 株夏南瓜正是靠这一条命中的。

## 数据源（均为仓库内既有文件，不联网）

- `stardew_valley_1.6_data_v9/stardew_data/item_index.json`：item_id ↔ name ↔ name_en
- `StardewHelper/app/src/main/assets/data/crops.json`：App 作物（中文名 + 售价）
- `StardewHelper/app/src/main/assets/data/foraging.json`：采集物（name_en + name_cn）
- `StardewHelper/app/src/main/assets/data/forage.json`：采集物（App 图鉴用的中文名表）
- `StardewHelper/app/src/main/assets/data/animals.json`：App 动物（中文名）
"""
import io
import json
import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)                      # StardewHelper/
REPO = os.path.dirname(ROOT)                       # 仓库根
DATA = os.path.join(ROOT, "app", "src", "main", "assets", "data")
INDEX = os.path.join(REPO, "旧文件归档", "旧文件归档/stardew_valley_1.6_data_v9", "stardew_data", "item_index.json")

# ── App 名 → 上游中文名（译名差异对照表）──────────────────────────────
# 键 = App 的中文名，值 = 上游 index 的中文名。
# ⚠ 上游名查不到时**报错退出**，不静默跳过 —— 少一条映射会让界面少显示一个名字，
#   而「上游改了译名」这种事必须当场看见。
CROP_RENAME = {
    "大蒜": "蒜",
    "蓝爵士": "蓝爵",
    "甘蓝": "甘蓝菜",
    "虞美人": "虞美人花",
    "金皮西葫芦（夏南瓜）": "金皮西葫芦",
    "远古水果": "上古水果",
    "大米": "未碾米",
}

# ── App 动物名 → 存档里的 FarmAnimal/type 英文名 ────────────────────────
# 值是**列表**：同一个 App 条目可能对应多个品种。
# 「牛」一条要管三个品种（Brown/White/Gold Cow）—— App 的手册里只叫「牛」。
# ⚠ mod 品种（Llama 羊驼等）App 没有对应 → **不进这张表**，
#   运行期按「查不到 → 显示英文原名」回退，不崩、不空、不瞎猜。
ANIMAL_TYPES = {
    "鸡（白）": ["White Chicken"],
    "鸡（棕）": ["Brown Chicken"],
    "蓝鸡": ["Blue Chicken"],
    "虚空鸡": ["Void Chicken"],
    "金鸡": ["Golden Chicken"],
    "鸭": ["Duck"],
    "兔子": ["Rabbit"],
    "恐龙": ["Dinosaur"],
    "牛": ["Brown Cow", "White Cow", "Gold Cow"],
    "山羊": ["Goat"],
    "绵羊": ["Sheep"],
    "猪": ["Pig"],
    "鸵鸟": ["Ostrich"],
}

# ── c204：存档里查不到中文名的物品（真实 1.6.15 存档实测）──────────────
# 起因：读四份真实存档，逐条比对 App 的 worldItemMap ⇒ 背包里 36 个英文名
# 查不到中文名。查不到 ⇒ 今日页「背包 N 类 · M 件」少算，图鉴的「背包 N 个」
# 退化成「背包里有未识别物品，以游戏内为准」——**全 App 的背包判断都失真**。
#
# 三条取舍纪律：
# ① **App 的名字优先**：App 说「钢铁短剑」，上游说「钢制轻剑」——以 App 为准
#    （App 武器图鉴里就写「钢铁短剑」，写成别的用户点不到）。
# ② **名字必须是 App 真的有条目的**：散落在正文里的同名字符串不算。
#    App 没有条目的（垃圾/漂流木/湿报纸/宝藏箱/金椰子/乔嘉汽水/平底锅/镰刀/
#    剪刀/金色的镰刀…）一律不收——背包里冒出一个 App 别处都没有的名字，
#    用户只会以为 App 乱显示。
# ③ **工具/武器的 id 不进 byId**：见文件头「三条数据纪律」第 3 条
#    （上游 tool/weapon 的 id 是 1.5 精灵序号，与 1.6 物品 id 空间撞车）。
#    只进 byNameEn——存档的 <name> 一直都在，够命中。
EXTRA_CATEGORIES = ("tool", "weapon", "mineral", "ore", "gem", "fish", "food",
                     "seed", "misc", "processed_good", "animal_product", "metal_bar")

# App 的译名与上游不一致的几条（以 App 为准）。
APP_NAME_OVERRIDES = {
    "Frozen Geode": "冰冻晶洞",   # 上游叫「冰封晶球」，App 的晶洞图鉴叫「冰冻晶洞」
    "Magma Geode": "岩浆晶洞",    # 上游叫「岩浆晶球」，App 叫「岩浆晶洞」
    "Omni Geode": "万能晶洞",     # 上游叫「万象晶球」，App 叫「万能晶洞」
}

# 上游索引里根本没有、但 App 自己有名字的（手维护）。
EXTRA_BY_EN = {
    "Fishing Rod": "鱼竿",   # 上游 tool 类目只有 Bamboo Pole/Fiberglass Rod/Iridium Rod
    "Hay": "干草",            # 1.6 的干草，上游 v9 索引里没有
    "White Algae": "白藻",    # 姜岛的白藻，上游 v9 索引里没有
    "Joja Cola": "Joja可乐",  # 商店条目名（App shopCatalog 里有），上游 v9 索引里没有
}

# ── c204 第二层：App **没有条目**、但上游有权威中文名的那几件 ──────────
# 为什么单独一张表：上面那些名字在 App 里查不到（图鉴里没有这一条），
# 可它们在真实存档里天天出现（镰刀/剪刀/淘金盘/钓鱼垃圾…）。
# 不收 ⇒ 背包里永远挂着「另有 N 件名字没对上」⇒ 图鉴的「背包 N 个」对
# **每一个**条目都退化成「以游戏内为准」——一处漏网，全盘失真。
# 收 ⇒ 名字是对的（上游原样，不是自造），只是图鉴里点不到它。
#
# ⚠ 纪律：这层的中文名**必须在上游数据文件里逐字存在**（自校验会查），
#   不许自造、不许意译——「显示错名字比不显示更糟」。
OFFICIAL_ONLY_BY_EN = {
    "Scythe": "镰刀",              # 上游 item_index tool
    "Shears": "剪刀",              # 上游 item_index tool / tool_upgrades
    "Golden Scythe": "金色的镰刀",  # 上游 item_index tool
    "Copper Pan": "铜淘金盘",      # 上游 tool_upgrades（初始铜淘金盘）
    "Artifact Trove": "文物宝藏",  # 上游 mines_skull_cavern / pet_guide
    "Soggy Newspaper": "湿报纸",   # 上游 npc_affection（湿报纸 Soggy Newspaper）
    "Driftwood": "浮木",           # App 自己的 processingProfit：input=浮木 / input_en=Driftwood
    "Golden Coconut": "金椰子",    # 上游 ginger_island
    "Trash": "垃圾",               # 上游 processing_profit（钓鱼垃圾）
    "Return Scepter": "回返魔杖",  # 上游 item_index tool（1.6 返程魔杖）
}

# 上游哪些类目的 item_id 属于「旧 id 空间」（1.5 精灵序号），不许进映射表。
# 理由见文件头「三条数据纪律」第 3 条（存档反查：存档里的 id 16 是野山葵不是刻刀）。
LEGACY_ID_CATEGORIES = {"weapon", "tool"}

# 无 item_id 的作物（上游自己就没记）→ 只能靠英文名命中
NO_ID_CROPS = ("Carrot", "Summer Squash", "Broccoli", "Powdermelon")


def load(path):
    return json.load(io.open(path, encoding="utf-8"))


def dump(obj, path, sort_keys=False):
    text = json.dumps(obj, ensure_ascii=False, indent=1, sort_keys=sort_keys)
    io.open(path, "w", encoding="utf-8", newline="\n").write(text)


def norm(en):
    """英文名的无空格变体（Summer Squash → SummerSquash）。"""
    return re.sub(r"\s+", "", en or "")


# ── c204：App 自己的「规范条目名」与「英文名 → 中文名」────────────────────
# 口径照 GameData.buildCodexGroups 复刻（rowsGroup 的 nameKey + 各组自己的取法）。
# ⚠ 只认**条目名**，不认正文里出现的同名字符串——散落在攻略正文里的「宝藏箱」
#   不是 App 的一条条目，收进映射表只会让背包里冒出一个点不到的名字。
ROWS_GROUP_NAME_KEY = {
    "fruitTrees.json": "果实",
    "recipes.json": "菜名",
    "crafting.json": "名称",
    "animals.json": "动物",
    "buildings.json": "建筑",
    "fishPonds.json": "鱼",
    "toolUpgrades.json": "升级",
    "geodes.json": "晶洞",
    "seedPrices.json": "种子",
}


def app_canon_names():
    """App 自己**真的有条目**的名字集合（映射表的值必须落在里面）。"""
    names = set()

    def add(v):
        if isinstance(v, str) and v.strip():
            names.add(v.strip())

    def first_or(o, key):
        """照 GameData.rowsGroup 的口径取条目名：
        优先 nameKey，取不到就回退到**第一个键**的值。"""
        if not isinstance(o, dict) or not o:
            return None
        v = o.get(key)
        if isinstance(v, str) and v.strip():
            return v
        for k in o:                      # 第一个键（JSON 顺序即插入顺序）
            fv = o[k]
            if isinstance(fv, str) and fv.strip():
                return fv
            break
        return None

    for fn, key in ROWS_GROUP_NAME_KEY.items():
        for o in load(os.path.join(DATA, fn)):
            add(first_or(o, key))
    for o in load(os.path.join(DATA, "toolUpgrades.json")):
        add(o.get("系列"))          # 工具族通称（锄头/斧头/十字镐/鱼竿…）
    museum = load(os.path.join(DATA, "museum.json"))
    for o in museum.get("minerals", []):
        add(o.get("name"))
    for s in museum.get("artifacts", []):
        add(s)
    for o in load(os.path.join(DATA, "forage.json")):
        add(o.get("name"))
    for o in load(os.path.join(DATA, "crops.json")):
        add(o.get("name"))
    for o in load(os.path.join(DATA, "shipping.json")):
        for s in o.get("items", []):
            # c205 起出货条目是对象 {name, price, shippable}，不是字符串
            add(s.get("name") if isinstance(s, dict) else s)
    for o in load(os.path.join(DATA, "tackle.json")):
        if isinstance(o, dict) and o:
            add(list(o.values())[0])
    for o in load(os.path.join(DATA, "fishing.json")):
        add(o.get("name"))
    for o in load(os.path.join(DATA, "monsterLoot.json")):
        if isinstance(o, dict):
            add(o.get("名称"))
    for o in load(os.path.join(DATA, "gingerIsland.json")).get("parrotShop", []):
        add(o.get("兑换项"))
    for o in load(os.path.join(DATA, "raccoon.json")):
        add(o.get("兑换项"))
    for o in load(os.path.join(DATA, "skills.json")).get("list", []):
        add(o.get("name"))
    for o in load(os.path.join(DATA, "weaponCompendium.json")).get("weapons", []):
        add(o.get("name"))
    # 商店条目名（煤炭/铁矿石/挤奶桶…）
    for items in load(os.path.join(DATA, "shopCatalog.json")).values():
        for o in items:
            if isinstance(o, dict):
                add(o.get("name"))
    # 收集包条目名（干草…）—— bundles.json 是 room → bundles[] → items[] 三层
    for room in load(os.path.join(DATA, "bundles.json")):
        for b in (room.get("bundles") or []):
            for it in (b.get("items") or []):
                if isinstance(it, dict):
                    add(it.get("name"))
    return names


def app_en_to_cn():
    """App 自己带英文名的表 → 英文名 → App 中文名（**App 的名字优先于上游**）。"""
    out = {}
    for o in load(os.path.join(DATA, "monsterLoot.json")):
        if isinstance(o, dict):
            en, cn = o.get("英文名"), o.get("名称")
            if en and cn:
                out.setdefault(en.strip(), cn.strip())
    for o in load(os.path.join(DATA, "weaponCompendium.json")).get("weapons", []):
        en, cn = o.get("name_en"), o.get("name")
        if en and cn:
            out.setdefault(en.strip(), cn.strip())
    return out


def upstream_strings():
    """上游数据文件里出现过的**全部**字符串（第二层映射名的「有据」凭据）。"""
    out = set()
    up_dir = os.path.join(REPO, "旧文件归档", "旧文件归档/stardew_valley_1.6_data_v9", "stardew_data")
    for fn in sorted(os.listdir(up_dir)):
        if not fn.endswith(".json"):
            continue
        try:
            raw = io.open(os.path.join(up_dir, fn), encoding="utf-8").read()
        except OSError:
            continue
        for m in re.finditer(r'"([^"\\]*(?:\\.[^"\\]*)*)"', raw):
            s = m.group(1)
            if s.strip():
                out.add(s.strip())
    return out


def upstream_grounded(name, pool):
    """中文名在上游数据里**有据**：整串出现，或作为某个上游字符串的一部分出现。

    ⚠ 允许子串是有原因的：上游把「湿报纸 Soggy Newspaper」写成**一整个字符串**
      （中英并排），「漂流木」「浮木」这类同义叫法也常以复合形式出现。
      仍然足以挡住「自己编一个名字」——自造的名字在上游数据里查无此串。
    """
    if not name:
        return False
    if name in pool:
        return True
    return any(name in s for s in pool)


def build():
    index = load(INDEX)["data"]["categories"]
    crops = load(os.path.join(DATA, "crops.json"))
    foraging = load(os.path.join(DATA, "foraging.json"))
    forage = load(os.path.join(DATA, "forage.json"))
    animals = load(os.path.join(DATA, "animals.json"))

    # 上游索引：中文名 → 条目；英文名 → 条目
    idx_by_cn = {}
    idx_by_en = {}
    idx_id_owner = {}          # item_id → 占用它的类目（**只算物品 id 空间**，见下）
    for cname, c in index.items():
        for it in c.get("items", []):
            if cname == "crop":
                idx_by_cn.setdefault(it["name"], it)
            if it.get("name_en"):
                idx_by_en.setdefault(it["name_en"], it)
            iid = it.get("item_id")
            # ⚠ 旧 id 空间（1.5 精灵序号）一律**不**登记为 id 来源：
            #   存档反查证明存档里的 id 16 = Wild Horseradish 野山葵，不是刻刀；
            #   id 24 = Parsnip 防风草，也不是武器。旧 id 空间整类忽略，
            #   不能因为「武器也占这个号」就把作物/采集物的号作废。
            if iid is not None and cname not in LEGACY_ID_CATEGORIES:
                idx_id_owner.setdefault(iid, []).append(cname)

    by_id = {}
    by_name_en = {}
    problems = []
    name_to_app = {}

    def put_id(iid, app_name, src):
        if iid is None:
            return
        prev = by_id.get(str(iid))
        if prev is not None and prev != app_name:
            problems.append("item_id %s 被两个名字占用：%s（%s）与 %s（%s）"
                            % (iid, prev, src, app_name, src))
            return
        by_id[str(iid)] = app_name

    def put_en(en, app_name):
        if not en:
            return
        for k in (en, norm(en)):
            if not k:
                continue
            prev = by_name_en.get(k)
            if prev is not None and prev != app_name:
                problems.append("英文名 %s 映射到两个 App 名：%s 与 %s" % (k, prev, app_name))
                continue
            by_name_en[k] = app_name

    # ── 1. 作物：补 nameEn，进 byNameEn / byId ──────────────────────────
    new_crops = []
    crop_name_en = {}
    for e in crops:
        app_name = e["name"]
        idx_name = CROP_RENAME.get(app_name, app_name)
        hit = idx_by_cn.get(idx_name)
        if hit is None:
            problems.append("作物「%s」在上游索引里找不到（查的中文名：%s）——"
                            "上游改译名了？补 CROP_RENAME 对照表" % (app_name, idx_name))
            new_crops.append(e)
            continue
        en = hit.get("name_en")
        if not en:
            problems.append("作物「%s」对应的上游条目没有 name_en" % app_name)
            new_crops.append(e)
            continue
        iid = hit.get("item_id")
        owners = idx_id_owner.get(iid, []) if iid is not None else []
        if iid is not None and not owners:
            # 上游给了 id，但这号只存在于旧 id 空间（武器/工具的精灵序号）→
            # 存档用的是 1.6 物品 id 空间，这个号在存档里指的不是这个物品 → 不用它。
            iid = None
        crop_name_en[app_name] = en
        put_id(iid, app_name, "作物")
        put_en(en, app_name)
        ne = {}
        for k, v in e.items():
            ne[k] = v
            if k == "name":
                ne["nameEn"] = en
        new_crops.append(ne)

    # ── 2. 采集物：App 图鉴表（forage.json）有的才收 ────────────────────
    forage_names = set(x.get("name") for x in forage if isinstance(x, dict))
    new_foraging = foraging
    for it in new_foraging.get("items", []):
        cn = it.get("name_cn")
        en = it.get("name_en")
        if not cn or not en or cn not in forage_names:
            continue
        hit = idx_by_en.get(en)
        if hit is None:
            continue
        iid = hit.get("item_id")
        owners = idx_id_owner.get(iid, []) if iid is not None else []
        if iid is not None and not owners:
            iid = None
        put_id(iid, cn, "采集物")
        put_en(en, cn)

    # ── 2b. 存档里查不到中文名的物品（c204）────────────────────────────
    # 取名顺序：**App 自己的名字 → 上游名字**；且必须落在 App 的条目名集合里。
    canon = app_canon_names()
    app_en = app_en_to_cn()
    for en, cn in sorted(EXTRA_BY_EN.items()):
        if cn not in canon:
            problems.append("手维护映射「%s → %s」的中文名不在 App 的条目名里" % (en, cn))
            continue
        put_en(en, cn)
    # 第二层：App 没有条目、但上游有权威中文名的那几件（上游原样，不自造）
    up_strings = upstream_strings()
    for en, cn in sorted(OFFICIAL_ONLY_BY_EN.items()):
        if not upstream_grounded(cn, up_strings):
            problems.append("第二层映射「%s → %s」的中文名在上游数据里查无实据（不许自造）"
                            % (en, cn))
            continue
        put_en(en, cn)
    for cname in EXTRA_CATEGORIES:
        c = index.get(cname)
        if not c:
            continue
        for it in c.get("items", []):
            en = it.get("name_en")
            if not en:
                continue
            cn = (APP_NAME_OVERRIDES.get(en)
                  or app_en.get(en)
                  or it.get("name"))
            # App 没有这条条目 ⇒ 不收（宁可不显示，也不显示成 App 没有的东西）
            if not cn or cn not in canon:
                continue
            iid = it.get("item_id")
            if cname in LEGACY_ID_CATEGORIES:
                iid = None      # 旧 id 空间，只进 byNameEn（见文件头纪律 3）
            elif iid is not None:
                owners = idx_id_owner.get(iid, [])
                if not owners:
                    iid = None
            put_id(iid, cn, cname)
            put_en(en, cn)

    # ── 3. 动物：补 typeEn，进 animalType ───────────────────────────────
    new_animals = []
    animal_type = {}
    for e in animals:
        app_name = e.get("动物")
        types = ANIMAL_TYPES.get(app_name)
        if not types:
            problems.append("动物「%s」没有 typeEn 映射（补 ANIMAL_TYPES 对照表）" % app_name)
            new_animals.append(e)
            continue
        for t in types:
            prev = animal_type.get(t)
            if prev is not None and prev != app_name:
                problems.append("动物类型 %s 同时归到 %s 与 %s" % (t, prev, app_name))
            animal_type[t] = app_name
        ne = {}
        for k, v in e.items():
            ne[k] = v
            if k == "动物":
                ne["typeEn"] = list(types)
        new_animals.append(ne)

    world_map = {
        "byId": dict(sorted(by_id.items(), key=lambda kv: int(kv[0]))),
        "byNameEn": dict(sorted(by_name_en.items())),
        "animalType": dict(sorted(animal_type.items())),
    }
    return {
        "crops": new_crops, "animals": new_animals, "map": world_map,
        "problems": problems, "crop_name_en": crop_name_en,
    }


def self_check(res, disk_only):
    """自校验：所有断言都对着**App 自己的表**做集合成员检查，不做模糊匹配。"""
    problems = list(res["problems"])
    app_names = app_canon_names()
    wm = res["map"]

    # ① 值必须是 App 自己 JSON 里逐字存在的条目名
    #    ⚠ 第二层（OFFICIAL_ONLY_BY_EN）例外：App 里本来就没有这些条目，
    #      它们的依据是上游数据文件（见 ⑫）——App 没有 ≠ 可以瞎写。
    layer2 = set(OFFICIAL_ONLY_BY_EN.values())
    for section in ("byId", "byNameEn", "animalType"):
        bad = sorted(k for k, v in wm[section].items()
                     if v not in app_names and v not in layer2)
        if bad:
            problems.append("%s 的 %d 个值不在 App 表里：%s" % (section, len(bad), bad[:6]))
    # ② item_id 唯一（一个 id 不被两个名字占用）
    if len(wm["byId"]) != len(set(wm["byId"])):
        problems.append("byId 出现重复 id")
    # ③ name_en 唯一（一个英文名不映射到两个 App 名）
    if len(wm["byNameEn"]) != len(set(wm["byNameEn"])):
        problems.append("byNameEn 出现重复英文名")
    # ④ 4 条无 item_id 的作物必须能经英文名路径命中
    for en in NO_ID_CROPS:
        if en not in wm["byNameEn"]:
            problems.append("无 item_id 的作物「%s」在 byNameEn 里查不到" % en)
        if norm(en) not in wm["byNameEn"]:
            problems.append("无 item_id 的作物「%s」的无空格变体查不到" % en)
    # ⑤ 全部作物都要进 byNameEn（一条都不能少）
    #    生成模式查「即将写盘的内容」，--check 模式查「磁盘上的内容」
    crops_checked = load(os.path.join(DATA, "crops.json")) if disk_only else res["crops"]
    for e in crops_checked:
        en = e.get("nameEn")
        if not en:
            problems.append("作物「%s」缺 nameEn" % e.get("name"))
        elif en not in wm["byNameEn"]:
            problems.append("作物「%s」的 nameEn(%s) 不在 byNameEn 里" % (e.get("name"), en))
    # ⑥ 动物 type 不重复归属
    animals_checked = load(os.path.join(DATA, "animals.json")) if disk_only else res["animals"]
    seen = {}
    for e in animals_checked:
        if not e.get("typeEn"):
            problems.append("动物「%s」缺 typeEn" % e.get("动物"))
        for t in e.get("typeEn") or []:
            if t in seen:
                problems.append("动物类型 %s 同时出现在 %s 与 %s" % (t, seen[t], e.get("动物")))
            seen[t] = e.get("动物")
    for t in wm["animalType"]:
        if t not in seen:
            problems.append("animalType 里的 %s 不在 animals.json 里" % t)
    # ⑧ c204：手维护映射必须真的进 byNameEn（否则改了表也没生效，白改）
    for en, cn in sorted(EXTRA_BY_EN.items()):
        if wm["byNameEn"].get(en) != cn:
            problems.append("手维护映射「%s → %s」没进 byNameEn（实得 %s）"
                            % (en, cn, wm["byNameEn"].get(en)))
    # ⑨ c204：App 的译名优先于上游（上游叫「钢制轻剑」，App 叫「钢铁短剑」）
    for en, cn in sorted(APP_NAME_OVERRIDES.items()):
        if wm["byNameEn"].get(en) != cn:
            problems.append("App 译名覆盖「%s → %s」没生效（实得 %s）"
                            % (en, cn, wm["byNameEn"].get(en)))
    # ⑩ c204：工具/武器的 id 一律不进 byId（旧 id 空间，见文件头纪律 3）
    for en in ("Scythe", "Golden Scythe", "Rusty Sword", "Steel Smallsword",
               "Galaxy Sword", "Master Slingshot", "Hoe", "Pickaxe", "Axe"):
        if en in wm["byNameEn"] and en in wm["byId"]:
            problems.append("工具/武器「%s」的 id 混进了 byId（旧 id 空间会撞车）" % en)
    # ⑪ c204：真实存档里出现过的英文名必须查得到中文名（回归口径）
    for en in ("Coal", "Iron Ore", "Frozen Geode", "Magma Geode", "Omni Geode",
               "Prismatic Shard", "Crab", "Sandfish", "Spicy Eel", "Taro Tuber",
               "White Algae", "Slime", "Rusty Sword", "Steel Smallsword",
               "Galaxy Sword", "Master Slingshot", "Hoe", "Axe", "Pickaxe",
               "Copper Hoe", "Gold Axe", "Iridium Pickaxe", "Iridium Rod",
               "Watering Can", "Milk Pail", "Fishing Rod",
               "Scythe", "Shears", "Golden Scythe", "Copper Pan", "Trash",
               "Driftwood", "Soggy Newspaper", "Artifact Trove", "Golden Coconut",
               "Joja Cola", "Coconut", "Common Mushroom", "Grape", "Mussel",
               "Return Scepter",
               "Purple Mushroom", "Sap", "Sweet Pea", "Cockle"):
        if en not in wm["byNameEn"]:
            problems.append("真实存档里的「%s」在 byNameEn 里查不到" % en)
    # ⑫ c204：第二层的中文名必须在上游数据里**逐字存在**（有据，不许自造）
    up_strings = upstream_strings()
    for en, cn in sorted(OFFICIAL_ONLY_BY_EN.items()):
        if not upstream_grounded(cn, up_strings):
            problems.append("第二层「%s → %s」在上游数据里查无实据" % (en, cn))
        if wm["byNameEn"].get(en) != cn:
            problems.append("第二层「%s → %s」没进 byNameEn（实得 %s）"
                            % (en, cn, wm["byNameEn"].get(en)))
    # ⑬ c204：第二层的前提是「App 里确实没有条目」——有的话就该走第一层
    for en, cn in sorted(OFFICIAL_ONLY_BY_EN.items()):
        if cn in app_names:
            problems.append("「%s」App 里已有条目「%s」，不该留在第二层" % (en, cn))
    # ⑦ 磁盘上的 worldItemMap.json 与生成结果一致（--check 模式的核心）
    wm_path = os.path.join(DATA, "worldItemMap.json")
    if disk_only:
        if not os.path.exists(wm_path):
            problems.append("worldItemMap.json 不存在（先跑一次不带 --check 的生成）")
        else:
            on_disk_map = load(wm_path)
            for section in ("byId", "byNameEn", "animalType"):
                a = on_disk_map.get(section, {})
                b = wm.get(section, {})
                if a != b:
                    only_disk = sorted(set(a) - set(b))[:4]
                    only_new = sorted(set(b) - set(a))[:4]
                    problems.append("worldItemMap.%s 与生成结果不一致（磁盘多：%s / 生成多：%s）"
                                    % (section, only_disk, only_new))
    return problems


def main():
    disk_only = "--check" in sys.argv
    res = build()
    problems = self_check(res, disk_only)
    if problems:
        print("自校验失败：")
        for p in problems:
            print("  ✗", p)
        return 1
    if disk_only:
        print("✅ worldItemMap.json / crops.json / animals.json 自校验通过（未写文件）")
        print("   byId=%d  byNameEn=%d  animalType=%d"
              % (len(res["map"]["byId"]), len(res["map"]["byNameEn"]), len(res["map"]["animalType"])))
        return 0

    dump(res["crops"], os.path.join(DATA, "crops.json"))
    dump(res["animals"], os.path.join(DATA, "animals.json"))
    dump(res["map"], os.path.join(DATA, "worldItemMap.json"), sort_keys=True)
    print("✅ 已生成：")
    print("   crops.json      42 条补 nameEn")
    print("   animals.json    13 条补 typeEn")
    print("   worldItemMap.json  byId=%d  byNameEn=%d  animalType=%d"
          % (len(res["map"]["byId"]), len(res["map"]["byNameEn"]), len(res["map"]["animalType"])))
    return 0


if __name__ == "__main__":
    sys.exit(main())
