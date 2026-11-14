"""把 v9 采集数据里的英文地点/用途/季节换成中文。
英文→中文映射不手写：优先从 v9 自带的 name_en/name_cn 对照字段里提取
（recipes / crafting / buildings / item_index / secret_notes 等），
地点类专名和收集包少数几项才手写补，末尾会打印未覆盖项供人工确认。
脚本幂等：可重复运行，已中文化的值不会被映射成 None。
"""
import json, io, sys, os, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
V9 = _V9_DIR() + "/stardew_data/"
A = _REPO_ROOT() + "/StardewHelper/app/src/main/assets/data/"

pairs = {}

def add(en, cn):
    if isinstance(en, str) and isinstance(cn, str) and en.strip() and cn.strip():
        pairs.setdefault(en.strip(), cn.strip())

def walk(o):
    if isinstance(o, dict):
        add(o.get("name_en") or o.get("item_en") or o.get("en_name"),
            o.get("name") or o.get("name_cn") or o.get("cn"))
        for k, v in o.items():
            if isinstance(v, str) and isinstance(o.get(k + "_cn"), str):
                add(v, o[k + "_cn"])
            walk(v)
    elif isinstance(o, list):
        for v in o:
            walk(v)

for f in ("recipes.json", "crafting.json", "buildings.json", "item_index.json",
          "geodes.json", "crops_animals.json", "secret_notes.json", "foraging.json",
          "processing_profit.json", "weapon_compendium.json"):
    try:
        walk(json.load(open(V9 + f, encoding="utf-8")))
    except Exception:
        pass

# 地点类专名：采集地点是地图区域，不在物品表里，只能手写
PLACE = {
    "The Beach": "海滩", "Beach": "海滩", "Secret Woods": "秘密森林",
    "Forest Farm": "森林农场", "Farm Cave": "农场洞", "Cindersap Forest": "煤矿森林",
    "Mountain": "山地", "The Mines": "矿井", "Mines": "矿井",
    "Skull Cavern": "骷髅洞穴", "Pelican Town": "鹈鹕镇", "Bus Stop": "巴士站",
    "Railroad": "铁路", "Calico Desert": "卡利科沙漠", "Backwoods": "小树林",
    "Swamp": "沼泽", "Ginger Island": "姜岛", "Ginger Island West Beach": "姜岛西沙滩",
    "Jungle": "丛林", "Volcano Dungeon": "火山地牢", "Tunnel": "隧道",
    "Tapping Saplings": "砍树苗",
    "Tilling soil throughout Stardew Valley": "星露谷全境耕地",
    "Shaking Maple Trees": "摇晃枫树", "Shaking Palm Trees": "摇晃棕榈树",
    "Tapper on Oak Tree": "橡树树液采集器", "Tapper on Pine Tree": "松树树液采集器",
    "Tapper on Maple Tree": "枫树树液采集器", "Trees": "树木", "Any Tree": "任意树",
    "Bushes": "灌木丛", "Shrub": "灌木丛",
}
for en, cn in PLACE.items():
    pairs.setdefault(en, cn)

# 收集包 / 图腾 / 特殊用途：App 里没有对应条目，手写这批
EXTRA = {
    "Spring Foraging Bundle": "春季采集包", "Summer Foraging Bundle": "夏季采集包",
    "Fall Foraging Bundle": "秋季采集包", "Winter Foraging Bundle": "冬季采集包",
    "Exotic Foraging Bundle": "异国采集包", "Winter Star Bundle": "冬季之星收集包",
    "Field Research Bundle": "野外研究收集包", "Crab Pot Bundle": "蟹笼收集包",
    "Chef's Bundle": "大厨收集包", "Dye Bundle": "染料收集包",
    "Warp Totem: Mountain": "传送图腾：山丘", "The Mysterious Qi Quest": "神秘齐任务",
    "Base Fertilizer": "基础肥料", "Raisins": "葡萄干",
    "Cures Nauseated debuff": "解除恶心状态",
}
for en, cn in EXTRA.items():
    pairs.setdefault(en, cn)

# 村民：George(loved gift) 这类，拆出英文名再查 v9 的 npc_affection
en2cn = {}
def walk_npc(o):
    if isinstance(o, dict):
        en, cn = o.get("name_en"), o.get("name")
        if isinstance(en, str) and isinstance(cn, str):
            en2cn[en] = cn
        for v in o.values():
            walk_npc(v)
    elif isinstance(o, list):
        for v in o:
            walk_npc(v)

walk_npc(json.load(open(V9 + "npc_affection.json", encoding="utf-8")))

NPC_TAG = {"loved gift": "最爱礼物", "loved": "最爱"}
SEASON_CN = {"Spring": "春", "Summer": "夏", "Fall": "秋", "Winter": "冬", "All": "全季"}
BRACKET = {"Mushroom": "蘑菇区", "Fruit Bat": "果蝠区",
           "after Fall 14": "秋 14 日后", "especially West and North": "尤其西部与北部"}

def lookup(en):
    """只查英文原文。pairs 的 key 里混了中文（来自 name_cn），
    值也可能是没中文化的英文——两种都要挡掉，否则二次运行会把中文映射成 None。
    纯中文输入（例如原始数据里的「灌木丛」）直接原样返回。"""
    if not isinstance(en, str):
        return None
    s = en.strip()
    if not re.search(r"[A-Za-z]", s):
        return s
    v = pairs.get(s)
    if not v or re.search(r"[A-Za-z]{3,}", v):
        return None
    return v

def fix_paren(inner):
    """括号里可能是纯英文（Fall 50% / Spring 15-18 / Mushroom），
    也可能是已中文化的部分，逐个查表；查不到就按「季节 + 数字」规则兜底。"""
    v = BRACKET.get(inner) or lookup(inner)
    if v:
        return v
    # "Fall 50%" / "Spring 15-18" / "Fall 8-11" -> "秋季 50%" / "春季 15-18 日"
    mm = re.match(r"^(Spring|Summer|Fall|Winter)\s+(\d+)(%|-\d+)$", inner)
    if mm:
        return "%s季 %s%s" % (SEASON_CN[mm.group(1)], mm.group(2), mm.group(3))
    return None

def fix_npc(u):
    mm = re.match(r"^([A-Za-z]+)\s*\((.+)\)$", u)
    if not mm:
        return None
    cn = en2cn.get(mm.group(1))
    return "%s（%s）" % (cn, NPC_TAG.get(mm.group(2), mm.group(2))) if cn else None

fg = json.load(open(A + "foraging.json", encoding="utf-8"))
hit = miss = 0
missing_season, missing_loc, missing_use, missing_paren = set(), set(), set(), set()

for it in fg["items"]:
    new_se = []
    for s in it.get("season", []):
        cn = SEASON_CN.get(s) or lookup(s)
        if cn:
            new_se.append(cn); hit += 1
        else:
            if isinstance(s, str) and re.search(r"[A-Za-z]{3,}", s):
                missing_season.add(s); miss += 1
            new_se.append(s)
    it["season"] = new_se

    new_loc = []
    for l in it.get("locations", []):
        if not isinstance(l, str):
            new_loc.append(l); continue
        # 半角 ( ) 与全角 （ ） 都要匹配：一次运行后有些已变成全角
        mm = re.match(r"^(.*?)\s*[(（](.*)[)）]\s*$", l)
        if mm:
            core, prob = mm.group(1).strip(), mm.group(2).strip()
            cn = lookup(core)
            if cn:
                prob_cn = fix_paren(prob)
                if prob_cn is None:
                    if re.search(r"[A-Za-z]{3,}", prob):
                        missing_paren.add(prob); miss += 1
                    prob_cn = prob
                new_loc.append("%s（%s）" % (cn, prob_cn)); hit += 1; continue
            if re.search(r"[A-Za-z]{3,}", l):
                missing_loc.add(l); miss += 1
            new_loc.append(l)
        else:
            cn = lookup(l)
            if cn:
                new_loc.append(cn); hit += 1
            else:
                if re.search(r"[A-Za-z]{3,}", l):
                    missing_loc.add(l); miss += 1
                new_loc.append(l)
    it["locations"] = new_loc

    new_use = []
    for u in it.get("uses", []):
        if not isinstance(u, str):
            new_use.append(u); continue
        cn = fix_npc(u) if re.match(r"^[A-Za-z]+\s*\(.*\)$", u) else lookup(u)
        if cn:
            new_use.append(cn); hit += 1
        else:
            if re.search(r"[A-Za-z]{3,}", u):
                missing_use.add(u); miss += 1
            new_use.append(u)
    it["uses"] = new_use

json.dump(fg, open(A + "foraging.json", "w", encoding="utf-8"),
          ensure_ascii=False, separators=(",", ":"))

print("映射表 %d 条；可翻译 %d / 未覆盖 %d" % (len(pairs), hit, miss))
print("未覆盖季节 %d 种: %s" % (len(missing_season), sorted(missing_season) or "无"))
print("未覆盖地点 %d 种: %s" % (len(missing_loc), sorted(missing_loc) or "无"))
print("未覆盖括号 %d 种: %s" % (len(missing_paren), sorted(missing_paren) or "无"))
print("未覆盖用途 %d 种: %s" % (len(missing_use), sorted(missing_use) or "无"))

residue = set()
for it in fg["items"]:
    for s in it.get("locations", []) + it.get("uses", []) + it.get("season", []):
        if isinstance(s, str):
            residue.update(re.findall(r"[A-Za-z]{3,}", s))
print("残留英文单词:", sorted(residue) or "无")
print("样例:", json.dumps(fg["items"][1], ensure_ascii=False)[:260])


# ── 路径解析（2026-11-10 开源清理）────────────────────────────────
# 原先这里写死本机绝对路径，公开仓里必然跑不通，
# 而且会把本机目录结构一起公开。改成基于脚本位置推导 + 环境变量覆盖。
def _REPO_ROOT():
    """仓根（本仓根 = StardewHelper/ 的上一级）。可用 SDH_REPO_ROOT 覆盖。"""
    _env = os.environ.get("SDH_REPO_ROOT")
    if _env:
        return _env
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _V9_DIR():
    """1.6 官方数据包目录。可用 SDH_V9_DIR 覆盖（公开仓不含该数据）。"""
    _env = os.environ.get("SDH_V9_DIR")
    if _env:
        return _env
    return os.path.join(_REPO_ROOT(), "旧文件归档", "stardew_valley_1.6_data_v9")
