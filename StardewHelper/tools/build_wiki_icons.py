import json, io, sys, os, re, shutil
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")

V9 = _V9_DIR() + "/"
ROOT = _REPO_ROOT() + "/StardewHelper/"
APP = ROOT + "app/src/main/assets/"
OUT = APP + "img/wiki/"

def norm(s):
    return re.sub(r"[^a-z0-9]", "", str(s).lower())

def walk_pairs(o, acc):
    """递归收集 {中文名: 英文名} 对"""
    if isinstance(o, dict):
        cn, en = o.get("name"), o.get("name_en")
        if isinstance(cn, str) and isinstance(en, str):
            acc[cn] = en
        for v in o.values():
            walk_pairs(v, acc)
    elif isinstance(o, list):
        for v in o:
            walk_pairs(v, acc)

def load(cat, strip=lambda s: s):
    idx = {}
    for f in sorted(os.listdir(V9 + "images/" + cat)):
        if f.endswith(".png"):
            idx.setdefault(norm(strip(f[:-4])), f)
    return idx

items_idx = load("items", lambda s: s.split("_", 1)[1] if re.match(r"^\d+_", s) else s)
fish_idx = load("fish")
npc_idx = load("npcs")

# monsters：slug_English_Name_normal|dangerous.png
# 排序让 normal 先入表，配合 setdefault 不覆盖 → 裸名「甲虫」拿到 normal 版
mon_idx = {}
for f in sorted(os.listdir(V9 + "images/monsters"), key=lambda s: ("dangerous" in s, s)):
    if not f.endswith(".png"):
        continue
    tk = f[:-4].split("_")
    start = next((i for i, t in enumerate(tk) if t[:1].isupper()), 1)
    title = "_".join(tk[start:])
    base = title.rsplit("_", 1)[0] if title.endswith(("_normal", "_dangerous")) else title
    mon_idx.setdefault(norm(title), f)
    mon_idx.setdefault(norm(base), f)

def mon_pick(en):
    return mon_idx.get(norm(en or "")) or mon_idx.get(
        norm(re.sub(r"\s*\((normal|dangerous)\)", "", en or "", flags=re.I)))

m = json.load(open(APP + "image_map.json", encoding="utf-8"))
need = {"items": {}, "monsters": {}, "npcs": {}, "fish": {}}

for w in json.load(open(APP + "data/weaponCompendium.json", encoding="utf-8"))["weapons"]:
    if w["name"] not in m:
        f = items_idx.get(norm(w["name_en"]))
        if f:
            need["items"][f] = w["name"]

mc = json.load(open(V9 + "stardew_data/monster_compendium.json", encoding="utf-8"))["data"]["monsters"]
by_en = {}
for o in mc:
    by_en.setdefault(norm(o["name_en"]), o["name"])
# 2) 怪物：图鉴「怪物」分类实际渲染的是 monsterDrops.json 的 18 个中文名
#    （GameData.kt:582 CodexGroup("monsters", ..., monsterDrops.map { CodexItem(it.first, it.first) })
#    —— 第二参数是 iconKey，也传中文名）。
#    monsterStats.json 那 59 条没有中文名（只有 name_en），UI 不用它取图。
md = json.load(open(APP + "data/monsterDrops.json", encoding="utf-8"))
md_names = [o["name"] for o in md]
# v9 compendium 提供中文名 → 英文名，用来定位图片
mon_cn2en = {o["name"]: o["name_en"] for o in mc}
for cn in md_names:
    if cn not in m:
        en = mon_cn2en.get(cn)
        f = mon_pick(en) if en else None
        if f:
            need["monsters"][f] = cn
# monsterStats 里的英文名直接兜底（万一同名实体已在别处出现）
for o in json.load(open(APP + "data/monsterStats.json", encoding="utf-8")):
    f = mon_pick(o.get("name_en", ""))
    if f:
        cn = by_en.get(norm(o.get("name_en", "")))
        if cn and cn not in m:
            need["monsters"][f] = cn

vg = json.load(open(APP + "data/gifts.json", encoding="utf-8"))
npairs = {}
walk_pairs(json.load(open(V9 + "stardew_data/npc_affection.json", encoding="utf-8")), npairs)
for n in sorted({g["name"] for g in (vg if isinstance(vg, list) else [])}):
    if n not in m and norm(npairs.get(n, "")) in npc_idx:
        need["npcs"][npc_idx[norm(npairs[n])]] = n

fp = {}
walk_pairs(json.load(open(V9 + "stardew_data/fishing_map.json", encoding="utf-8")), fp)
fish = json.load(open(APP + "data/fishing.json", encoding="utf-8"))
for f in (fish if isinstance(fish, list) else fish.get("fish", [])):
    if f["name"] not in m and norm(fp.get(f["name"], "")) in fish_idx:
        need["fish"][fish_idx[norm(fp[f["name"]])]] = f["name"]

# ── 只把「App 真正用得到」的拷进 assets（其余留在 v9 包里，不进 APK）──
# 图片必须先经 tools/build_wiki_images.ps1 缩到 48x48（numpy/Pillow 本机都没有），
# 暂存目录由该脚本产出；没暂存就直接拷原图——原图多数已是 48x48，NPC 是 120x120。
STAGE = os.environ.get("WIKI_STAGE", "").replace("\\", "/")
if STAGE and not STAGE.endswith("/"):
    STAGE += "/"
if STAGE:
    src_root = STAGE
else:
    src_root = V9 + "images/"

if os.path.isdir(OUT):
    shutil.rmtree(OUT)
for cat in need:
    os.makedirs(OUT + cat, exist_ok=True)
    for fn in need[cat]:
        src = src_root + cat + "/" + fn
        if not os.path.exists(src):
            src = V9 + "images/" + cat + "/" + fn
        shutil.copy2(src, OUT + cat + "/" + fn)

# ── image_map 增量 ──
# 只增不改：已有条目一律不覆盖（补缺为主，674 张 AI 像素图保持原样）
before = len(m)
for cat in need:
    for fn, cn in need[cat].items():
        m.setdefault(cn, "wiki/%s/%s" % (cat, fn))

json.dump(m, open(APP + "image_map.json", "w", encoding="utf-8"),
          ensure_ascii=False, sort_keys=True, indent=0)

print("== 拷贝进 assets ==")
tot = 0
for cat in need:
    n = len(need[cat])
    b = sum(os.path.getsize(OUT + cat + "/" + f) for f in os.listdir(OUT + cat))
    print("  %-9s %3d 张 %7.1f KB" % (cat, n, b / 1024))
    tot += n
print("  合计 %d 张" % tot)
print("image_map: %d -> %d 条（新增 %d）" % (before, len(m), len(m) - before))


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
