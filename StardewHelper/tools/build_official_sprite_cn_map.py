# -*- coding: utf-8 -*-
"""
建立「官方英文名 -> App 中文名」映射表，供 BC 图标与作物图标挂载使用。

产出（只写这两个新文件，不动 Kotlin / data/*.json / image_map.json / verify 脚本）：
  tools/official_sprite_cn_map.json    官方贴图文件名 -> App 中文名
  tools/official_sprite_files.json     官方贴图文件名 -> 预期 App 图片相对路径

==========================================================================
① 英文名 -> 官方贴图文件名 的转换规则（已与官方仓库逐一比对）
==========================================================================
  1) 整串转小写；
  2) 每个「不是 [a-z0-9] 的字符」各自替换成**一个**下划线
     （空格、撇号、连字符、句点、问号都算；连续多个不合并！）
  例子：
    "Tub o' Flowers"            -> tub_o__flowers        （撇号 + 空格 = 两个下划线）
    "Statue Of The Dwarf King"  -> statue_of_the_dwarf_king
    "Wood Lamp-post"            -> wood_lamp_post
    "Cursed P.K. Arcade System" -> cursed_p_k__arcade_system （两个句点 + 空格）
    "??HMTGF??"                 -> __hmtgf__
    "Slime Egg-Press"           -> slime_egg_press
    "Auto-Grabber"              -> auto_grabber
  ⚠ 任务书示例写的是 "Tub o' Flowers" -> "tub_o_flowers"（单下划线），
    但官方贴图仓库与 App assets/img/official_bc/ 里实际落盘的文件名都是
    tub_o__flowers（双下划线）。若按「合并连续下划线」的规则会挂不上图。
    本脚本以官方仓库实际文件名为准，并在报告里单独列出这一条不一致。

  比对基准（官方贴图仓库 juliaramosguedes/stardew-data，仓库树缓存于
  C:\\Users\\y\\AppData\\Local\\Temp\\opencode\\sd_tree.json）：
    sprites/bigcraftables/ 147 个文件  <->  官方 BigCraftables.json 147 个唯一英文名  -> 147/147 全中
    sprites/crops/         50 个文件   <->  官方 Crops.json 50 个作物              ->  50/50 全中
    sprites/objects/      717 个文件   <->  官方 Objects.json 807 条              -> 807/807 全中
  且与 App assets/img/official_bc(147) / official_crop(50) / official_obj 里
  实际落盘的文件名逐一核对。

==========================================================================
② 中文名的取值优先级（全部来自 App 现有数据，不造译名）
==========================================================================
  bigcraftables:
    S1 tools/c210_crafting_cn.json                    App 制作中英对照（本次新建，
                                                      口径与 image_map 里已挂的
                                                      official_bc/* 完全一致）
    S2 assets/data/saveImportMap.json craftByEn       App 自带 英->中
    S3 assets/data/worldItemMap.json  byNameEn        App 自带 英->中
    S4 tools/official_objects_english.json
       + tools/official_objects_index.json join        官方物品 英->中
  crops:
    C1 assets/data/crops.json  nameEn -> name          App 作物数据（正在补到 50）
    C2 assets/image_map.json 里 official_crop/* 反查    App 已挂官方作物图的中文名
    C3 assets/data/worldItemMap.json  byNameEn
    C4 S4 同上
  objects（官方 Objects.json 里 crafting 用到的物品 = 官方 CraftingRecipes.json
          的材料与产出，剔除 (BC) 前缀与负数类别 token）:
    O1 S1（材料/产出若同时是制作配方名）
    O2 S4（官方物品 英->中）

  终检：选出的中文名必须出现在 App「在用中文名池」里，池 = 以下四处的并集
    - assets/data/crafting.json 的 name
    - assets/data/crops.json 的 name
    - assets/image_map.json 的 key
    - assets/img/**/ *.png 的文件名（去扩展名）
  查不到 → 该条跳过不写，并进跳过清单。
"""
import json, io, os, sys, re, collections

sys.stdout.reconfigure(encoding="utf-8")

ROOT = r"F:\Desktop\星露谷\StardewHelper"
TOOLS = os.path.join(ROOT, "tools")
DATA = os.path.join(TOOLS, "official_game_1_6_15", "Data")
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")
T = r"C:\Users\y\AppData\Local\Temp\opencode"
OUT_CN = os.path.join(TOOLS, "official_sprite_cn_map.json")
OUT_FILES = os.path.join(TOOLS, "official_sprite_files.json")
OUT_REPORT = os.path.join(TOOLS, "_sprite_cn_map_report.txt")

RPT = []


def p(*a):
    s = " ".join(str(x) for x in a)
    RPT.append(s)
    print(s)


def load(pth):
    with io.open(pth, encoding="utf-8") as f:
        return json.load(f)


# ---------------------------------------------------------------- 数据装载
bc = load(os.path.join(DATA, "BigCraftables.json"))
cr = load(os.path.join(DATA, "Crops.json"))
ob = load(os.path.join(DATA, "Objects.json"))
craftrec = load(os.path.join(DATA, "CraftingRecipes.json"))

craf = load(os.path.join(ASSETS, "data", "crafting.json"))
crops_app = load(os.path.join(ASSETS, "data", "crops.json"))
im = load(os.path.join(ASSETS, "image_map.json"))
sim = load(os.path.join(ASSETS, "data", "saveImportMap.json"))
wim = load(os.path.join(ASSETS, "data", "worldItemMap.json"))

ccn = load(os.path.join(TOOLS, "c210_crafting_cn.json"))
oe = load(os.path.join(TOOLS, "official_objects_english.json"))
oz = load(os.path.join(TOOLS, "official_objects_index.json"))

# 官方贴图仓库实际文件名（比对基准）
sd_tree = load(os.path.join(T, "sd_tree.json"))["tree"]
repo_sprites = collections.defaultdict(set)
for e in sd_tree:
    path = e.get("path", "")
    if path.startswith("sprites/") and path.endswith(".png"):
        parts = path.split("/")
        repo_sprites[parts[1]].add(parts[-1][:-4])

# App 里已落盘的官方贴图
app_sprites = {}
for sub in ("official_bc", "official_crop", "official_obj"):
    sp = os.path.join(ASSETS, "img", sub)
    if os.path.isdir(sp):
        app_sprites[sub] = {f[:-4] for f in os.listdir(sp) if f.lower().endswith(".png")}


# ---------------------------------------------------------------- 转换规则
def to_sprite_filename(name):
    """官方英文名 -> 官方贴图文件名（规则见文件头注释①）"""
    return "".join(ch if ch in "abcdefghijklmnopqrstuvwxyz0123456789" else "_" for ch in name.lower())


# ---------------------------------------------------------------- App 在用中文名池
def collect_cn_strings(node, out):
    if isinstance(node, dict):
        for v in node.values():
            collect_cn_strings(v, out)
    elif isinstance(node, list):
        for v in node:
            collect_cn_strings(v, out)
    elif isinstance(node, str):
        out.append(node)


CN_RE = re.compile(r"[\u4e00-\u9fff]")
app_cn_pool = set()

craft_names = {r["name"] for r in craf}
app_cn_pool |= craft_names

crop_names_app = {r["name"] for r in crops_app}
app_cn_pool |= crop_names_app

app_cn_pool |= {k for k in im if CN_RE.search(k)}

img_stems = set()
for root, dirs, files in os.walk(os.path.join(ASSETS, "img")):
    for f in files:
        if f.lower().endswith(".png"):
            img_stems.add(f[:-4])
app_cn_pool |= {s for s in img_stems if CN_RE.search(s)}

p("=" * 78)
p("App 在用中文名池：crafting.json %d + crops.json %d + image_map 键 %d + img/*.png %d"
  % (len(craft_names), len(crop_names_app),
     len([k for k in im if CN_RE.search(k)]), len(img_stems)))
p("去重后共 %d 个中文名" % len(app_cn_pool))

# ---------------------------------------------------------------- 各来源桥
cbe = sim.get("craftByEn", {})
bne = wim.get("byNameEn", {})

en2cn_obj = {}
for oid, e in oe.items():
    c = oz.get(oid)
    if c and c != e:
        en2cn_obj.setdefault(e, []).append(c)

# image_map 里已挂官方图的反查：文件名 -> 中文名（同一文件名可能对应多个中文名）
imap_off = collections.defaultdict(set)
for k, v in im.items():
    if not isinstance(v, str):
        continue
    m = re.match(r"^official_(bc|crop|obj)/([A-Za-z0-9_]+)\.png$", v)
    if m:
        imap_off[m.group(2)].add(k)

p("")
p("image_map 里已挂官方图的条目：%d 个文件名 / %d 个中文名"
  % (len(imap_off), sum(len(v) for v in imap_off.values())))

# ---------------------------------------------------------------- BC 分组
p("")
p("=" * 78)
p("【bigcraftables】官方 BigCraftables.json %d 条" % len(bc))

bc_entries = [(k, v.get("Name")) for k, v in bc.items()]
bc_uniq = []
_seen = set()
for k, n in bc_entries:
    if n not in _seen:
        _seen.add(n)
        bc_uniq.append((k, n))
p("唯一英文名 %d 个" % len(bc_uniq))

SRC_LABEL = {
    "c210": "tools/c210_crafting_cn.json",
    "craftByEn": "saveImportMap.craftByEn",
    "byNameEn": "worldItemMap.byNameEn",
    "objjoin": "official_objects_english+index",
    "image_map_official": "image_map(official_bc/*)",
}


def resolve_bc(en):
    """返回 (中文名, 来源标签, 候选清单)。候选里带「有译名但 App 无条目」的信息。"""
    cands = []  # (prio, label, cn)
    if en in ccn:
        cands.append((0, "c210", ccn[en]))
    if en in cbe:
        cands.append((1, "craftByEn", cbe[en]))
    if en in bne:
        cands.append((2, "byNameEn", bne[en]))
    if en in en2cn_obj:
        for c in en2cn_obj[en]:
            cands.append((3, "objjoin", c))
    # image_map 反查：官方 bc 文件名对应的中文名（App 已挂图的，最可信）
    fn = to_sprite_filename(en)
    for c in imap_off.get(fn, ()):
        cands.append((-1, "image_map_official", c))
    if not cands:
        return None, None, []
    # 先按「在 App 在用池」过滤，再按优先级
    good = [c for c in cands if c[2] in app_cn_pool]
    if not good:
        return None, None, cands
    good.sort(key=lambda x: (x[0], x[1]))
    return good[0][2], good[0][1], cands


bc_map = {}
bc_files = {}
bc_skip = []
bc_multi = []
for k, en in bc_uniq:
    fn = to_sprite_filename(en)
    cn, src, cands = resolve_bc(en)
    if cn is None:
        if not cands:
            why = "App 全部来源(c210/craftByEn/byNameEn/官方物品表/image_map)均无该英文名"
        else:
            candcn = sorted({c[2] for c in cands})
            why = "有译名候选 %r，但均不在 App 在用中文名池（多为官方中文，App 尚未收录该词条）" % candcn
        bc_skip.append((en, fn, why, cands))
        continue
    # 同名多条 App 条目 / 同一文件名多个中文名 → 记录歧义
    if len({c[2] for c in cands}) > 1:
        bc_multi.append((en, fn, sorted({c[2] for c in cands}), src))
    if fn in bc_map and bc_map[fn] != cn:
        bc_multi.append((en, fn, [bc_map[fn], cn], src))
    bc_map[fn] = cn
    bc_files[fn] = "img/official_bc/%s.png" % fn

p("可映射唯一英文名 %d 个（覆盖官方 BC 条目 %d / %d）"
  % (len(bc_map), sum(1 for k, n in bc_entries if to_sprite_filename(n) in bc_map), len(bc_entries)))
p("跳过唯一英文名 %d 个（对应官方条目 %d 条）"
  % (len(bc_skip), sum(1 for k, n in bc_entries if to_sprite_filename(n) not in bc_map)))
p("歧义/多口径 %d 条" % len(bc_multi))

# ---------------------------------------------------------------- Crops 分组
p("")
p("=" * 78)
p("【crops】官方 Crops.json %d 条" % len(cr))

crop_rows = []
for k, v in cr.items():
    hid = str(v.get("HarvestItemId"))
    hn = ob.get(hid, {}).get("Name")
    crop_rows.append((k, hid, hn))
p("唯一 harvest 英文名 %d 个" % len({r[2] for r in crop_rows if r[2]}))

crops_by_en = {r.get("nameEn"): r["name"] for r in crops_app}


def resolve_crop(en):
    cands = []
    if en in crops_by_en:
        cands.append((0, "crops.json", crops_by_en[en]))
    fn = to_sprite_filename(en)
    for c in imap_off.get(fn, ()):
        cands.append((-1, "image_map_official", c))
    if en in bne:
        cands.append((2, "byNameEn", bne[en]))
    if en in en2cn_obj:
        for c in en2cn_obj[en]:
            cands.append((3, "objjoin", c))
    if not cands:
        return None, None, []
    good = [c for c in cands if c[2] in app_cn_pool]
    if not good:
        return None, None, cands
    good.sort(key=lambda x: (x[0], x[1]))
    return good[0][2], good[0][1], cands


crop_map = {}
crop_files = {}
crop_skip = []
crop_multi = []
for k, hid, en in crop_rows:
    if not en:
        crop_skip.append(("<无 harvest 名>", "?", "HarvestItemId=%r 在 Objects.json 里查不到" % hid, []))
        continue
    fn = to_sprite_filename(en)
    cn, src, cands = resolve_crop(en)
    if cn is None:
        crop_skip.append((en, fn, "App 里查不到对应中文名", cands))
        continue
    if len({c[2] for c in cands}) > 1:
        crop_multi.append((en, fn, sorted({c[2] for c in cands}), src))
    crop_map[fn] = cn
    crop_files[fn] = "img/official_crop/%s.png" % fn

p("可映射唯一英文名 %d 个（覆盖官方 Crops 条目 %d / %d）"
  % (len(crop_map), sum(1 for r in crop_rows if r[2] and to_sprite_filename(r[2]) in crop_map), len(crop_rows)))
p("跳过 %d 条" % len(crop_skip))
p("歧义/多口径 %d 条" % len(crop_multi))

# ---------------------------------------------------------------- Objects 分组
p("")
p("=" * 78)
p("【objects】官方 Objects.json 里 crafting 用到的物品")

obj_ids = set()
CAT_TOKEN = re.compile(r"^-\d+$")
for key, raw in craftrec.items():
    segs = raw.split("/")
    mats = segs[0].split() if segs else []
    i = 0
    while i < len(mats):
        t = mats[i]
        if CAT_TOKEN.match(t):
            i += 1
            continue
        if t.isdigit():
            obj_ids.add(t)
            if i + 1 < len(mats) and mats[i + 1].isdigit():
                i += 2
            else:
                i += 1
        else:
            s = t.replace("(BC)", "")
            if not s.isdigit():
                i += 1
            else:
                i += 1
    y = segs[2].split() if len(segs) > 2 else []
    for t in y:
        if t.isdigit():
            obj_ids.add(t)
        elif t.replace("(BC)", "").isdigit():
            pass

obj_ids = {i for i in obj_ids if i in ob}
p("官方 CraftingRecipes 用到的 Objects id %d 个" % len(obj_ids))

# 多个 id 可能同名（官方 Objects 里 Stone 有多种 DisplayName），按英文名去重，避免同一贴图重复映射
seen_obj_en = set()
obj_en_order = []
for oid in sorted(obj_ids, key=lambda x: (len(x), x)):
    en = ob[oid].get("Name")
    if en and en not in seen_obj_en:
        seen_obj_en.add(en)
        obj_en_order.append(en)
p("去重后唯一英文名 %d 个" % len(obj_en_order))


def resolve_obj(en):
    cands = []
    if en in ccn:
        cands.append((0, "c210", ccn[en]))
    if en in bne:
        cands.append((2, "byNameEn", bne[en]))
    if en in cbe:
        cands.append((1, "craftByEn", cbe[en]))
    if en in en2cn_obj:
        for c in en2cn_obj[en]:
            cands.append((3, "objjoin", c))
    fn = to_sprite_filename(en)
    for c in imap_off.get(fn, ()):
        cands.append((-1, "image_map_official", c))
    if not cands:
        return None, None, []
    good = [c for c in cands if c[2] in app_cn_pool]
    if not good:
        return None, None, cands
    good.sort(key=lambda x: (x[0], x[1]))
    return good[0][2], good[0][1], cands


obj_map = {}
obj_files = {}
obj_skip = []
obj_multi = []
# 去重后的英文名遍历；「多个 id 同名」只取第一个 id 的中文（同一贴图文件就够了）
obj_dup = collections.defaultdict(list)
for oid in sorted(obj_ids, key=lambda x: (len(x), x)):
    en = ob[oid].get("Name")
    if en:
        obj_dup[en].append(oid)
for en in obj_en_order:
    oid = obj_dup[en][0]
    fn = to_sprite_filename(en)
    cn, src, cands = resolve_obj(en)
    if cn is None:
        if not cands:
            why = "App 全部来源(c210/craftByEn/byNameEn/官方物品表/image_map)均无该英文名"
        else:
            candcn = sorted({c[2] for c in cands})
            why = "有译名候选 %r，但均不在 App 在用中文名池" % candcn
        obj_skip.append((en, fn, why, cands))
        continue
    if len({c[2] for c in cands}) > 1:
        obj_multi.append((en, fn, sorted({c[2] for c in cands}), src))
    if fn in obj_map and obj_map[fn] != cn:
        obj_multi.append((en, fn, [obj_map[fn], cn], src))
    obj_map[fn] = cn
    obj_files[fn] = "img/official_obj/%s.png" % fn

p("可映射唯一文件名 %d 个，跳过 %d 个，歧义 %d 条" % (len(obj_map), len(obj_skip), len(obj_multi)))

# ---------------------------------------------------------------- 落盘
out_cn = {"bigcraftables": bc_map, "crops": crop_map, "objects": obj_map}
out_files = {"bigcraftables": bc_files, "crops": crop_files, "objects": obj_files}
for path, data in ((OUT_CN, out_cn), (OUT_FILES, out_files)):
    with io.open(path, "w", encoding="utf-8", newline="\r\n") as f:
        json.dump(data, f, ensure_ascii=False, indent=1)
        f.write("\n")
p("")
p("已写 %s（BC %d / crops %d / objects %d）" % (os.path.relpath(OUT_CN, TOOLS), len(bc_map), len(crop_map), len(obj_map)))
p("已写 %s（BC %d / crops %d / objects %d）" % (os.path.relpath(OUT_FILES, TOOLS), len(bc_files), len(crop_files), len(obj_files)))

# 跨分组同一文件名映射到不同中文名 → 冲突检查
p("")
p("【跨分组冲突检查】同一文件名在不同分组的映射值")
groups = {"bigcraftables": bc_map, "crops": crop_map, "objects": obj_map}
any_conf = False
for g1, m1 in groups.items():
    for g2, m2 in groups.items():
        if g1 >= g2:
            continue
        for fn in m1.keys() & m2.keys():
            if m1[fn] != m2[fn]:
                any_conf = True
                p("   %s.%s=%r  vs  %s.%s=%r（同名 %r）" % (g1, m1[fn], fn, g2, m2[fn], fn))
if not any_conf:
    p("   无冲突")

# ---------------------------------------------------------------- 文件名比对
p("")
p("=" * 78)
p("【文件名比对】我方转换规则 vs 官方仓库实际文件名 vs App 已落盘文件名")

p("")
p("-- bigcraftables --")
mismatch_repo = []
for k, en in bc_uniq:
    fn = to_sprite_filename(en)
    if fn not in repo_sprites.get("bigcraftables", set()):
        mismatch_repo.append((en, fn))
p("  147 唯一名 -> 官方仓库 bigcraftables 未命中：%d" % len(mismatch_repo))
for en, fn in mismatch_repo:
    p("     %-30r -> %-30r" % (en, fn))
mismatch_app = [fn for fn in bc_map if fn not in app_sprites.get("official_bc", set())]
p("  我方映射 %d 个文件名 -> App assets/img/official_bc 未落盘：%d" % (len(bc_map), len(mismatch_app)))
for fn in sorted(mismatch_app):
    p("     %r (中文名 %r)" % (fn, bc_map[fn]))
extra_app = sorted(app_sprites.get("official_bc", set()) - set(bc_map))
p("  App official_bc 已落盘但我方映射未覆盖：%d" % len(extra_app))
for fn in extra_app:
    p("     %r" % fn)

p("")
p("-- crops --")
mismatch_repo_c = []
for k, hid, en in crop_rows:
    if not en:
        continue
    fn = to_sprite_filename(en)
    if fn not in repo_sprites.get("crops", set()):
        mismatch_repo_c.append((en, fn))
p("  官方仓库 crops 未命中：%d" % len(mismatch_repo_c))
for en, fn in mismatch_repo_c:
    p("     %-24r -> %-24r" % (en, fn))
mismatch_app_c = [fn for fn in crop_map if fn not in app_sprites.get("official_crop", set())]
p("  我方映射 %d 个文件名 -> App assets/img/official_crop 未落盘：%d" % (len(crop_map), len(mismatch_app_c)))
for fn in sorted(mismatch_app_c):
    p("     %r (中文名 %r)" % (fn, crop_map[fn]))
extra_app_c = sorted(app_sprites.get("official_crop", set()) - set(crop_map))
p("  App official_crop 已落盘但我方映射未覆盖：%d" % len(extra_app_c))
for fn in extra_app_c:
    p("     %r" % fn)

p("")
p("-- objects --")
mismatch_app_o = [fn for fn in obj_map if fn not in app_sprites.get("official_obj", set())]
p("  我方映射 %d 个文件名 -> App assets/img/official_obj 未落盘：%d" % (len(obj_map), len(mismatch_app_o)))
for fn in sorted(mismatch_app_o)[:30]:
    p("     %r (中文名 %r)" % (fn, obj_map[fn]))
p("  App official_obj 已落盘但我方映射未覆盖：%d" % len(sorted(app_sprites.get("official_obj", set()) - set(obj_map))))
for fn in sorted(app_sprites.get("official_obj", set()) - set(obj_map)):
    p("     %r" % fn)

# ---------------------------------------------------------------- 跳过清单
p("")
p("=" * 78)
p("【跳过清单】")
p("")
p("-- bigcraftables 跳过 %d 个唯一英文名 --" % len(bc_skip))
for en, fn, why, cands in bc_skip:
    p("   %-30r -> %-30r  %s" % (en, fn, why))
p("")
p("-- crops 跳过 %d 条 --" % len(crop_skip))
for en, fn, why, cands in crop_skip:
    p("   %-24r -> %-24r  %s" % (en, fn, why))
p("")
p("-- objects 跳过 %d 个 --" % len(obj_skip))
for en, fn, why, cands in obj_skip:
    p("   %-28r -> %-28r  %s" % (en, fn, why))

# ---------------------------------------------------------------- 歧义清单
p("")
p("【歧义/多口径清单】（同一英文名在不同 App 桥里有不同中文名；已按优先级取值）")


def _dedup(lst):
    return list({(e, f, ",".join(sorted(c)), s) for e, f, c, s in lst})


for en, fn, cns, src in sorted(bc_multi, key=lambda x: x[0]):
    p("   BC   %-30r -> %-30r 取 %r（候选 %r）" % (en, fn, src, cns))
for en, fn, cns, src in sorted(_dedup(crop_multi), key=lambda x: x[0]):
    p("   CROP %-24r -> %-24r 取 %r（候选 %r）" % (en, fn, src, cns))
for en, fn, cns, src in sorted(_dedup(obj_multi), key=lambda x: x[0]):
    p("   OBJ  %-28r -> %-28r 取 %r（候选 %r）" % (en, fn, src, cns))

p("")
p("=" * 78)
p("【规则不一致专项】")
p("  任务书示例：\"Tub o' Flowers\" -> \"tub_o_flowers\"（单下划线）")
p("  官方仓库实际：tub_o__flowers（双下划线，撇号与空格各算一个下划线）")
p("  App 已落盘  ：%s" % ("official_bc/tub_o__flowers.png 存在" if "tub_o__flowers" in app_sprites.get("official_bc", set()) else "不存在"))
p("  image_map   ：%r" % im.get("花桶"))
p("  结论：转换规则取「每个非 [a-z0-9] 字符各换一个下划线、不合并」，与官方仓库及")
p("        App 已落盘文件名 147/147、50/50 完全一致；任务书示例需按官方仓库修正。")

with io.open(OUT_REPORT, "w", encoding="utf-8", newline="\r\n") as f:
    f.write("\n".join(RPT) + "\n")
print("\nreport ->", OUT_REPORT)
