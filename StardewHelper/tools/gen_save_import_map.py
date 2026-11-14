# -*- coding: utf-8 -*-
"""生成 assets/data/saveImportMap.json —— 导入存档用的「存档标识 → App 条目名」映射。

用法:
    python tools/gen_save_import_map.py            # 生成 + 自校验
    python tools/gen_save_import_map.py --check    # 只校验磁盘上已有的文件，不写

## 为什么要有这个文件

App 包内 49 个 data JSON **全是中文名，没有任何一个带 item_id 或英文名**；
而游戏存档给的全是数字 ID（`(O)131`）或英文名（`Fried Egg`）。
两边对不上，导入功能就无从下手。这个文件是唯一的桥。

**值必须是 App 自己 JSON 里逐字存在的条目名**——打勾键
（`codexCheckKey` → `"<前缀>:<名字>"`）就是拿这个名字拼的，差一个字就勾不上。
所以本脚本自校验时对 App 侧做的是**集合成员检查**，不是模糊匹配。

## 数据源（均为仓库内既有文件，不联网）

- `stardew_valley_1.6_data_v9/stardew_data/item_index.json`：item_id ↔ name ↔ name_en
- `stardew_valley_1.6_data_v9/stardew_data/crafting.json`：制作配方 name_en ↔ name

## 两处「主动放弃」是有意的，不是漏了

1. **5 条传送图腾**：源译名「传送图腾：山岭 / 海滩」，App 叫「传送图腾（山丘 / 海洋）」。
   英文名 `Warp Totem: Mountains / Beach` 与 App 的「山丘 / 海洋」**语义不对齐**
   （Beach≠海洋、Mountains≠山丘），映射错了就是勾错配方，按「宁可不勾」砍掉。
2. **成就**：v9 用字符串 id（`greenhorn`）无数字字段，且与 App 中文名**只交集 16/49**
   （App「千万富翁」vs v9「传奇」）。双重障碍，要建两张表，性价比最低。

## 矿物表为什么是「整表写死」而不是从 item_index 推导

`item_index.json` 的 mineral/gem 类只有 19 条（石英、火水晶这类著名矿物），
App 的 53 条里另外 38 条（黑方石/大理石/沙岩/花岗岩…）**上游压根没收**。
这批的 item_id 是 **539~578 连续段**，只能从外部拿。

v2.66.0 用**两个互相独立的来源**交叉验证后整表写死（见 `MINERAL_OVERRIDE`）：

1. **存档反查**：四份真实存档的 `Item` 节点形如
   `<name>Bixite</name><parentSheetIndex>539</parentSheetIndex>`，
   正则扫全文得到 539~578 **40 个 ID 的英文名，一个不缺**。
2. **中英双语 wiki 逐格对齐**：`stardewvalleywiki.com/Minerals` 与
   `zh.stardewvalleywiki.com/矿物` 的「收集」表**排列顺序**与上面反查到的
   ID 顺序完全吻合（60…86, 538…578），逐格对译即得中文名。
   三个已知锚点互证：538=Alamite=透闪石、570=Basalt=玄武岩、572=Soapstone=皂石。
   两条 wiki 另载「完结一共可以捐赠 53 种矿石」，与 App 的 53 条一致。

⚠ **单源不可信**——项目此前已被外部文档坑过（《存档XML字段解析文档 v1.2》
五处定性错误，见「与 Kotlin 侧的对应关系」）。所以这里是双源，且
`OLD_MINERAL_15` 把改动前的 15 条钉死，任何一条被"顺手改掉"都会当场报错。

verify 第 58 节会钉死这些「必须缺席」，防后人好心补回来。

## 精灵珠宝 / 手部骨骼为什么是手工补的两条（v2.66.1）

古物其余 40 条都从 item_index 的「App 中文名 → item_id」自动推导，这两条按
App 的叫法查不到上游的门：

- **精灵珠宝**：上游名叫「精灵**首饰**」（name_en `Elvish Jewelry`，item_id 104），
  与 App 一字之差，按名字查自然落空。
- **手部骨骼**：上游名叫「骨骼手掌」（`Skeletal Hand`），且 `item_id` 是 **null**
  ——上游自己就没记 ID，不是我们的查法问题。

ID 仍按「单源不可信」做双源交叉（群友反馈精灵珠宝勾不上后实测）：

1. **存档反查**：KKK 存档真实存在
   `<name>Elvish Jewelry</name><parentSheetIndex>104</parentSheetIndex>` 与
   `<name>Skeletal Hand</name><parentSheetIndex>582</parentSheetIndex>`，
   且 104、582 两个值都出现在该档 museumPieces 里（修好后 KKK 古物 40→42 全勾）。
2. **独立佐证**：上游 `image_path` 分别为 `104_Elvish_Jewelry.png`、
   `582_Skeletal_Hand.png`；序号连续性也对——104 恰夹在已映射的 103/105 之间，
   582 恰落在化石段 579~585 的缺口（579/580/581/583/584/585 都已映射）。

之所以**不**把古物整表写死（矿物那套办法）：40 条能自动推导的留着自动推导，
上游若改了名或挪了 ID，覆盖数会掉出账目当场红；只有这 2 条按 App 口径手工补。
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets", "data")
V9 = os.path.join(ROOT, "..", "旧文件归档", "stardew_valley_1.6_data_v9", "stardew_data")
OUT = os.path.join(ASSETS, "saveImportMap.json")

# ── 计划账目（verify 第 58 节钉死这些数字）──────────────────────────────
# 钉的是**覆盖了多少条 App 条目**，不是字典有几个键。
# 键数会因「同一物品两种英文名形态」（Cherry Bomb / CherryBomb）而膨胀，
# 覆盖数才直接等于「导入后能勾上几条」，也是用户看得懂的量。
EXPECT_COVER = {
    "fish": 74,        # 70 数字 ID + 4 字符串 ID
    "recipe": 81,      # 烹饪
    "craft": 118,      # 制作 = c210 前 88 + c210 补官方配方后自动覆盖 114 + 手工 4（核对：见 58 节）
    "mineral": 53,     # 博物馆矿物（App 53 条已全量覆盖，见 MINERAL_OVERRIDE）
    "artifact": 42,    # 博物馆古物（App 42 条已全量覆盖，2 条手工补，见 ARTIFACT_OVERRIDE）
}
EXPECT_TOTAL = 368
APP_TOTAL = 560          # 14 个可勾分类的条目总数（覆盖率分母）

# ── 改动前（v2.65.x）的 15 条矿物映射，用来钉死「不许被顺手改掉」────────────
# 这 15 条在 v2.66.0 的双源对齐里 ID 一条都没变，证明新表对旧数据零回归。
# verify 58.23 也会独立断言这一条——脚本自校验与 verify 是两道闸门。
OLD_MINERAL_15 = {
    "60": "绿宝石", "62": "海蓝宝石", "64": "红宝石", "66": "紫水晶", "68": "黄水晶",
    "70": "翡翠", "72": "钻石", "74": "五彩碎片", "80": "石英", "82": "火水晶",
    "84": "泪晶", "86": "地晶", "538": "透闪石", "570": "玄武岩", "572": "皂石",
}

# ── 博物馆矿物 53 条整表（v2.66.0 新增）────────────────────────────────────
# 来源与验证方式见文件头「矿物表为什么是整表写死」。键是 item_id，值**必须**
# 是 App `museum.json` 里逐字存在的条目名——打勾键 `museum:<名字>` 靠它拼。
#
# ⚠ 578 在两个中文 wiki 上都译作「陶瓷碎片」，但 App 清单写的是「**星碎片**」。
#   这里必须用 App 的口径（值只能来自 App 清单，否则拼不出打勾键）；
#   译名准确性与数据迁移是另一件事，见 plan §6.1，本轮不动。
MINERAL_OVERRIDE = {
    "60": "绿宝石", "62": "海蓝宝石", "64": "红宝石", "66": "紫水晶", "68": "黄水晶",
    "70": "翡翠", "72": "钻石", "74": "五彩碎片", "80": "石英", "82": "火水晶",
    "84": "泪晶", "86": "地晶",
    "538": "透闪石", "539": "黑方石", "540": "重晶石", "541": "青泥石",
    "542": "方解石", "543": "白云石", "544": "硅钙石", "545": "氟磷灰石",
    "546": "杰明石", "547": "日光榴石", "548": "绿水镍矿", "549": "铁铅矿",
    "550": "蓝晶石", "551": "酸性月岩", "552": "孔雀石", "553": "柱星叶石",
    "554": "柠檬石", "555": "新硅钙石", "556": "雌黄", "557": "石化史莱姆",
    "558": "雷公蛋", "559": "黄铁矿", "560": "海洋石", "561": "幽灵水晶",
    "562": "虎眼石", "563": "碧玉", "564": "猫眼石", "565": "赤红猫眼石",
    "566": "天青石", "567": "大理石", "568": "沙岩", "569": "花岗岩",
    "570": "玄武岩", "571": "石灰石", "572": "皂石", "573": "赤铁矿",
    "574": "泥石", "575": "黑曜石", "576": "板岩", "577": "精灵石",
    "578": "星碎片",
}

# ── 手工补的 2 条古物（v2.66.1）──────────────────────────────────────────
# 来源与双源验证见文件头「精灵珠宝 / 手部骨骼为什么是手工补的两条」。
# 值必须是 App `museum.json` artifacts 里逐字存在的名字；其余 40 条仍走
# item_index 自动推导——上游若漂了会掉出覆盖账目当场红。
ARTIFACT_OVERRIDE = {
    "104": "精灵珠宝",
    "582": "手部骨骼",
}

# ── 4 条 1.6 新鱼：存档里是字符串 ID，源数据里英文名带空格 ───────────────
# 实测 KKK 存档 fishCaught 的非数字 key：['SeaJelly','RiverJelly','CaveJelly','Goby']
# 而 item_index.json 的 name_en 是 'Sea Jelly'（有空格）。
# 归一化：取 (O) 后缀 → 去掉全部空格 → 比对 name_en 去空格。
STRING_FISH = {
    "SeaJelly": "Sea Jelly",
    "RiverJelly": "River Jelly",
    "CaveJelly": "Cave Jelly",
    "Goby": "Goby",
}

# ── 4 条野生种子：源译名与 App 叫法不同（季节对应无歧义，手工补）──────────
# 源：Wild Seeds (Sp) -> 春季野生种子 ；App：野生种子（春）
# 季节一一对应，没有歧义，所以补。
MANUAL_CRAFT = {
    "Wild Seeds (Sp)": "野生种子（春）",
    "Wild Seeds (Su)": "野生种子（夏）",
    "Wild Seeds (Fa)": "野生种子（秋）",
    "Wild Seeds (Wi)": "野生种子（冬）",
}

# ── 明确不做的 5 条传送图腾：写在这里是为了让「为什么没有」可追溯 ────────
SKIPPED_CRAFT = {
    "Warp Totem: Mountains": "App 作「传送图腾（山丘）」，源作「山岭」，语义不对齐",
    "Warp Totem: Beach": "App 作「传送图腾（海洋）」，源作「海滩」，语义不对齐",
    "Warp Totem: Farm": "App 作「传送图腾（农场）」，同名但未纳入，保守起见一并放弃",
    "Warp Totem: Desert": "App 作「传送图腾（沙漠）」，同名但未纳入，保守起论一并放弃",
    "Warp Totem: Island": "App 作「传送图腾（岛屿）」，源作「姜岛」，命名不一致",
}


def load(path):
    with io.open(path, encoding="utf-8") as f:
        return json.load(f)


def names_of(fname, group=None):
    """取 App 侧某个 data JSON 的条目名全集。

    `group` 是「dict 里的第几个数组」（museum.json 的 minerals / artifacts）；
    剩下的情况整个文件就是那个数组。
    条目可能是 {"name": ...} 也可能直接是字符串（museum 的 artifacts 就是裸字符串），
    两种都处理。
    """
    data = load(os.path.join(ASSETS, fname))
    if group:
        data = data[group]
    if isinstance(data, dict):
        data = list(data.values())
    out = []
    for x in data:
        out.append(x["name"] if isinstance(x, dict) else x)
    return out


def build():
    idx = load(os.path.join(V9, "item_index.json"))["data"]["categories"]

    # name -> item_id
    name2id = {}
    # name_en **原样** -> name。键绝不能去空格：存档里两种形态都真实存在
    # （实测 KKK 档 craftingRecipes 134 条带空格 + 27 条不带，
    #   如 `Cobblestone Path` 与 `Campfire`），键一旦归一化就会漏查。
    en2name_raw = {}
    # name_en 去空格 -> name。**只**用于下面 4 条字符串鱼
    # （存档 key 是 `SeaJelly`，源 name_en 是 `Sea Jelly`）。
    en2name_squeezed = {}
    en_id = {}  # 英文名 -> item_id，用于判断「同名不同物」
    for _cat, body in idx.items():
        for it in body.get("items", []):
            nm, en, i = it.get("name"), it.get("name_en"), it.get("item_id")
            if nm:
                name2id.setdefault(nm, i)
            if en and nm:
                en2name_raw.setdefault(en, nm)
                en2name_squeezed.setdefault(en.replace(" ", ""), nm)
                if i is not None:
                    en_id.setdefault(en, i)

    m = {}

    # ── 钓鱼：数字 ID 70 条 + 字符串 ID 4 条 ──
    app_fish = names_of("fishing.json")
    m["fishById"] = {str(name2id[n]): n for n in app_fish
                     if name2id.get(n) is not None and isinstance(name2id[n], int)}
    fish_name_set = set(app_fish)
    m["fishByName"] = {}
    for save_id, en in STRING_FISH.items():
        cn = en2name_squeezed.get(en.replace(" ", ""))
        if cn is None or cn not in fish_name_set:
            raise SystemExit("字符串鱼 ID %s 映射失败：源里查不到 %s" % (save_id, en))
        m["fishByName"][save_id] = cn

    # ── 烹饪：存档给的是**数字 item_id**，不是英文名 ──
    # ⚠ 这一点最初被写错过。`存档条目整理.md` 与外部文档都写「菜谱键是英文名
    # `Fried Egg`」，实测四档全是数字（ff 用 <int>、KKK 用 <string> 装数字）。
    # 宿主文件里那份 htt 存档的 recipesCooked 恰好是空的，所以错误一直没被撞出来。
    # 教训与成就 24 / 鱼 131 / 怪物路径同源：**文档示例不能替代实测**。
    app_rec = names_of("recipes.json")
    app_rec_set = set(app_rec)
    m["recipeById"] = {str(name2id[n]): n for n in app_rec
                       if isinstance(name2id.get(n), int)}
    # 1.6 里少数食物用字符串内部名（实测苔藓汤的存档键是 `MossSoup`，
    # 而 name_en 是 "Moss Soup"）——去空格比对，和钓鱼的 4 条新鱼同一套办法。
    m["recipeByName"] = {}
    for cn in app_rec:
        if isinstance(name2id.get(cn), int):
            continue
        for en, src_cn in en2name_squeezed.items():
            if src_cn == cn:
                m["recipeByName"][en] = cn
                break

    # ── 制作：v9 crafting.json 为主，item_index 补缺口，手工补 4 条野生种子 ──
    # 补缺口时用「中文名是否已被覆盖」判断，而不是「英文名是否已存在」——
    # 否则 `Cherry Bomb` 与 `CherryBomb` 两种形态会各占一个键，
    # 同一个 App 条目被数成两条，账目对不上。
    app_craft = names_of("crafting.json")
    app_craft_set = set(app_craft)
    craft_en2cn = {}
    for it in load(os.path.join(V9, "crafting.json"))["data"]:
        ne, nm = it.get("name_en"), it.get("name")
        if ne and nm:
            craft_en2cn.setdefault(ne, nm)
    m["craftByEn"] = {en: cn for en, cn in craft_en2cn.items() if cn in app_craft_set}
    covered = set(m["craftByEn"].values())
    for en, nm in en2name_raw.items():
        if nm in app_craft_set and nm not in covered:
            m["craftByEn"][en] = nm
            covered.add(nm)
    for en, cn in MANUAL_CRAFT.items():
        if cn not in app_craft_set:
            raise SystemExit("手工制作映射 %s 指向的 App 条目不存在：%s" % (en, cn))
        m["craftByEn"][en] = cn

    # ── 博物馆 ──
    app_min = names_of("museum.json", "minerals")
    app_art = names_of("museum.json", "artifacts")
    # 矿物改用整表（上游 item_index 缺 38 条，见文件头）。这里仍做两道本地校验：
    #   ① 表里每条都要在 App 清单内，否则拼不出打勾键
    #   ② item_index 若恰好收录了同一 id，中文名必须一致（上游与我们同源时互为背书）
    m["mineralById"] = {}
    for sid, cn in MINERAL_OVERRIDE.items():
        if cn not in app_min:
            raise SystemExit("矿物映射 %s 指向的 App 条目不存在：%s" % (sid, cn))
        upstream = name2id.get(cn)
        if isinstance(upstream, int) and upstream != int(sid):
            raise SystemExit("矿物 %s 上游 id 是 %s，表里写的是 %s" % (cn, upstream, sid))
        m["mineralById"][sid] = cn
    m["artifactById"] = {str(name2id[n]): n for n in app_art
                         if name2id.get(n) is not None and isinstance(name2id[n], int)}
    # 手工补 2 条。合并前查重：若上游将来把同名条目修好并给出同一 id，自动推导
    # 会先产出它——名字一致就放行（手工表冗余但无害），名字不一致必须当场红，
    # 绝不能让人工条目悄悄盖掉自动条目。
    for sid, cn in sorted(ARTIFACT_OVERRIDE.items()):
        if cn not in app_art:
            raise SystemExit("古物映射 %s 指向的 App 条目不存在：%s" % (sid, cn))
        prev = m["artifactById"].get(sid)
        if prev is not None and prev != cn:
            raise SystemExit("古物 id %s 自动推导为 %r，与手工表 %r 冲突" % (sid, prev, cn))
        m["artifactById"][sid] = cn
    return m, en_id


def validate(m, en_id):
    """自校验。任何一条不过就抛错——宁可生成失败，也别落一份错的映射进 APK。"""
    errs = []
    print("== 覆盖 App 条目 ==")
    cover = {
        "fish": set(m["fishById"].values()) | set(m["fishByName"].values()),
        "recipe": set(m["recipeById"].values()) | set(m["recipeByName"].values()),
        "craft": set(m["craftByEn"].values()),
        "mineral": set(m["mineralById"].values()),
        "artifact": set(m["artifactById"].values()),
    }
    for k, want in EXPECT_COVER.items():
        got = len(cover[k])
        flag = "OK " if got == want else "!! "
        print("  %s%-9s %3d / 账目 %3d" % (flag, k, got, want))
        if got != want:
            errs.append("  [%s] 覆盖 %d 条，账目要求 %d" % (k, got, want))

    total = sum(len(v) for v in cover.values())
    print("  合计 %d / %d = %.1f%%" % (total, EXPECT_TOTAL,
                                      100.0 * total / EXPECT_TOTAL))
    if total != EXPECT_TOTAL:
        errs.append("  合计 %d，账目要求 %d" % (total, EXPECT_TOTAL))

    # 覆盖数不得超过该组 App 条目数（防止映射里混进别的分类的名字）
    for k, fname, jkey in (("fish", "fishing.json", None),
                           ("recipe", "recipes.json", None),
                           ("craft", "crafting.json", None),
                           ("mineral", "museum.json", "minerals"),
                           ("artifact", "museum.json", "artifacts")):
        universe = set(names_of(fname, jkey))
        bad = cover[k] - universe
        if bad:
            errs.append("  [%s] 覆盖里有不属于 %s 的名字：%s" % (k, fname, sorted(bad)))
        if len(cover[k]) > len(universe):
            errs.append("  [%s] 覆盖数超过 %s 的条目数" % (k, fname))

    # 主动放弃的传送图腾必须真的缺席
    for en in SKIPPED_CRAFT:
        if en in m["craftByEn"]:
            errs.append("  传送图腾 %s 不该出现在映射里（主动放弃）" % en)
    for en in MANUAL_CRAFT:
        if en not in m["craftByEn"]:
            errs.append("  野生种子 %s 应在映射里" % en)

    # v2.66.0 回归闸门：改动前的 15 条矿物映射必须一字未变。
    # 双源对齐表若把某条 id 挪了位或改了名，这里当场红——而不是等到用户
    # 导入时勾错矿物才发现。
    got = m["mineralById"]
    for sid, cn in sorted(OLD_MINERAL_15.items()):
        if sid not in got:
            errs.append("  回归：旧矿物 %s(%s) 在新表里消失了" % (sid, cn))
        elif got[sid] != cn:
            errs.append("  回归：旧矿物 %s 被改成 %s（原为 %s）" % (sid, got[sid], cn))
    if len(got) != len(MINERAL_OVERRIDE):
        errs.append("  矿物表 %d 条，与 MINERAL_OVERRIDE 的 %d 条不符"
                    % (len(got), len(MINERAL_OVERRIDE)))

    # v2.66.1 回归闸门：两条手工古物必须逐字落位。它们是靠 App 名字拼打勾键的，
    # 名字改了、id 挪了，用户端表现都是「捐了但永远勾不上」——静默失败。
    for sid, cn in sorted(ARTIFACT_OVERRIDE.items()):
        if m["artifactById"].get(sid) != cn:
            errs.append("  手工古物 %s=%s 未落位（实际 %s）"
                        % (sid, cn, m["artifactById"].get(sid, "缺失")))

    # 矿物/古物共用 museum: 前缀，靠中文名区分，撞名就会互相覆盖
    overlap = set(m["mineralById"].values()) & set(m["artifactById"].values())
    if overlap:
        errs.append("  矿物与古物条目名重叠（共用 museum: 前缀会撞键）：%s" % sorted(overlap))

    # 同一中文名被多个英文名指向时，只有「确实是不同物品」才是问题。
    # `Cherry Bomb` / `CherryBomb` 这种同物异形是正常的（存档两种都可能出现），
    # 但 `Life Elixir` 若同中文名却对应不同 item_id，那是真错译，要报出来。
    for key in ("craftByEn",):
        rev = {}
        for en, cn in m[key].items():
            rev.setdefault(cn, []).append(en)
        for cn, ens in rev.items():
            if len(ens) < 2:
                continue
            ids = set(en_id[e] for e in ens if e in en_id)
            if len(ids) > 1:
                errs.append("  [%s] 中文名 %r 对应不同物品的英文名 %s" % (key, cn, sorted(ens)))

    return errs, total


def serialize(m):
    """字节级可复现：sort_keys + indent=0（与 image_map.json 同款惯例）。

    ⚠ 不要改成 indent=1/2 —— verify 会跑「生成两次比对 md5」断言，
    换缩进等于换格式，会把那条断言变成假红。
    """
    return json.dumps(m, ensure_ascii=False, sort_keys=True, indent=0)


def main():
    check_only = "--check" in sys.argv
    m, en_id = build()
    errs, total = validate(m, en_id)

    blob = serialize(m)
    nbytes = len(blob.encode("utf-8"))
    print("\n体积: %d 字节 (%.1f KB)" % (nbytes, nbytes / 1024.0))
    print("键数: %s" % {k: len(v) for k, v in m.items()})

    if errs:
        print("\n自校验未通过：")
        for e in errs:
            print(e)
        return 1

    print("\n覆盖率: %d / %d = %.1f%%" % (total, APP_TOTAL, 100.0 * total / APP_TOTAL))
    if check_only:
        if not os.path.exists(OUT):
            print("\n%s 不存在" % OUT)
            return 1
        cur = io.open(OUT, encoding="utf-8").read()
        if cur != blob:
            print("\n磁盘上的文件与重新生成的结果不一致，请重跑不带 --check")
            return 1
        print("\n--check 通过：磁盘文件与生成结果逐字节一致")
        return 0

    with io.open(OUT, "w", encoding="utf-8", newline="") as f:
        f.write(blob)
    print("\n已写入 %s" % OUT)
    print("传送图腾 5 条为**主动放弃**（理由见本文件头部 SKIPPED_CRAFT），verify 会断言它们缺席。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
