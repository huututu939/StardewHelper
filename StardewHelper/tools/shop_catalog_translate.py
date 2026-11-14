# -*- coding: utf-8 -*-
# 商店目录数据管线（v2.56.0）第 3 步/4：五层译名对齐：数据字典+派生 / langlinks / redirects+基词 / File 哈希 / v9 像素哈希
# 全链顺序：shop_catalog_fetch -> shop_catalog_parse -> shop_catalog_translate -> shop_catalog_merge
# 中间产物都在 %TEMP%（开发期缓存，不入库）；最终产物 app/src/main/assets/data/shopCatalog.json 入库。
# ⚠ 本组脚本是「数据出处」的记录：wiki 表结构或项目译名口径变了，按序重跑并人工复核 merge 输出。
# -*- coding: utf-8 -*-
"""
译名对齐第 1 层：扩展字典（image_map 文件名反推 + 派生后缀规则），
输出仍未命中的清单给 langlinks 批查。
"""
import io
import json
import os
import re

A = _REPO_ROOT() + '/StardewHelper' + '/app/src/main/assets'
D = os.path.join(A, "data")
TMP = os.environ["TEMP"]

# 1) 基础字典：数据文件里的 name_en/name_cn
base = {}
for f in os.listdir(D):
    if not f.endswith(".json") or "wiki" in f:
        continue
    try:
        j = json.load(io.open(os.path.join(D, f), encoding="utf-8"))
    except Exception:
        continue
    def walk(o):
        if isinstance(o, dict):
            if "name_en" in o:
                cn = o.get("name_cn") or o.get("name")
                if cn:
                    base.setdefault(str(o["name_en"]).strip().lower(), str(cn).strip())
            for v in o.values():
                walk(v)
        elif isinstance(o, list):
            for v in o:
                walk(v)
    walk(j)

# 2) image_map 文件名反推：value 里含 ASCII 文件名的（446_Rabbit's_Foot.png /
#    "Loom.png" 等），剥数字前缀与扩展名 -> 英文名 -> 键（中文）
im = json.load(io.open(os.path.join(A, "image_map.json"), encoding="utf-8"))
from_fn = {}
for cn, fn in im.items():
    b = os.path.basename(str(fn)).replace(".png", "").replace(".webp", "")
    b = re.sub(r"^\d+_", "", b)
    if re.search(r"[\u4e00-\u9fff]", b):
        continue  # 中文文件名不产生英文映射
    en = b.replace("_", " ").strip().lower()
    if en:
        from_fn.setdefault(en, cn)

# 3) 派生后缀规则（种子/树苗/糖渍 之类在数据里的构词）
def derive(en):
    e = en.strip().lower()
    for src in (base, from_fn):
        if e in src:
            return src[e], "dict"
    m = re.match(r"^(.+?) seeds$", e)
    if m:
        for src in (base, from_fn):
            if m.group(1) in src:
                return src[m.group(1)] + "种子", "seeds"
    m = re.match(r"^(.+?) sapling$", e)
    if m:
        for src in (base, from_fn):
            if m.group(1) in src:
                return src[m.group(1)] + "树苗", "sapling"
    m = re.match(r"^(.+?) starter$", e)
    if m:
        for src in (base, from_fn):
            if m.group(1) in src:
                return src[m.group(1)] + "秧苗", "starter"
    m = re.match(r"^(.+?) (recipe|crafting recipe)$", e)
    if m:
        for src in (base, from_fn):
            if m.group(1) in src:
                return src[m.group(1)] + "配方", "recipe"
    return None, None

# 跑草稿里全部 item_en
draft = json.load(io.open(os.path.join(TMP, "shop_catalog_draft.json"), encoding="utf-8"))
all_en = sorted({r["item_en"] for rows in draft.values() for r in rows})
hit, miss = {}, []
for en in all_en:
    cn, how = derive(en)
    if cn:
        hit[en] = (cn, how)
    else:
        miss.append(en)
print("总 %d 名：字典/文件名/派生命中 %d，仍缺 %d" % (len(all_en), len(hit), len(miss)))
byhow = {}
for v in hit.values():
    byhow[v[1]] = byhow.get(v[1], 0) + 1
print("命中构成：", byhow)
io.open(os.path.join(TMP, "shop_hit.json"), "w", encoding="utf-8").write(
    json.dumps(hit, ensure_ascii=False, indent=1))
io.open(os.path.join(TMP, "shop_miss2.txt"), "w", encoding="utf-8").write("\n".join(miss))
print("\n仍缺清单（给 langlinks）：")
for m_ in miss:
    print("   ", m_)


# ════ 以下为第 2~5 层（原 translate2/3/4 + pixmatch，按序执行）════
# -*- coding: utf-8 -*-
"""
译名对齐第 2 层：langlinks 批量反查（英文 wiki 页 -> zh 页面名 = 官方简中）。
带项目中文名核对：langlinks 结果若与项目既有词一致标 ok，不一致标 conflict/新增。
"""
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

TMP = os.environ["TEMP"]
A = _REPO_ROOT() + '/StardewHelper' + '/app/src/main/assets'

# 1) 项目中文名全集（核对用）
cn_set = set()
cn_set.update(json.load(io.open(os.path.join(A, "image_map.json"), encoding="utf-8")).keys())
D = os.path.join(A, "data")
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
print("项目中文名全集 %d 条" % len(cn_set), file=sys.stderr)

miss = [l.strip() for l in io.open(os.path.join(TMP, "shop_miss2.txt"), encoding="utf-8") if l.strip()]
print("待查 %d 个" % len(miss), file=sys.stderr)

API = "https://wiki.stardewvalley.net/mediawiki/api.php"


def langlinks(titles):
    u = (API + "?action=query&prop=langlinks&lllang=zh&format=json&formatversion=2&titles="
         + urllib.parse.quote("|".join(titles)))
    req = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0"})
    d = json.loads(urllib.request.urlopen(req, timeout=60).read().decode("utf-8"))
    out = {}
    for p in d["query"]["pages"]:
        ll = p.get("langlinks") or []
        out[p["title"]] = ll[0]["title"] if ll else None
    return out


result = {}
todo = list(miss)
B = 50
for i in range(0, len(todo), B):
    batch = todo[i:i + B]
    try:
        got = langlinks(batch)
    except Exception as e:
        print("batch %d FAIL %s" % (i, e), file=sys.stderr)
        got = {}
    for t in batch:
        result[t] = got.get(t)
    time.sleep(0.5)
    print("  %d/%d" % (min(i + B, len(todo)), len(todo)), file=sys.stderr)

hit = {k: v for k, v in result.items() if v}
none = [k for k, v in result.items() if not v]
inproj = {k: v for k, v in hit.items() if v in cn_set}
new = {k: v for k, v in hit.items() if v not in cn_set}
print("\nlanglinks 命中 %d / 无页面 %d" % (len(hit), len(none)), file=sys.stderr)
print("  其中与项目既有词一致 %d，新词 %d" % (len(inproj), len(new)), file=sys.stderr)
print("\n新词（项目里没有，进目录后要确认图鉴可达性）：", file=sys.stderr)
for k, v in sorted(new.items()):
    print("   %-32s %s" % (k, v), file=sys.stderr)
print("\n查无页面（需手工或派生）：", file=sys.stderr)
for k in none:
    print("   ", k, file=sys.stderr)
io.open(os.path.join(TMP, "shop_langlinks.json"), "w", encoding="utf-8").write(
    json.dumps({"map": result, "new": new, "none": none}, ensure_ascii=False, indent=1))

# -*- coding: utf-8 -*-
"""译名对齐第 3 层：对无页的 331 个名字，带 redirects=1 重查；
   仍无页的剥后缀（Seeds/Sapling/Starter/Ring/Boots…）查基词再拼中文。"""
import io
import json
import os
import re
import sys
import time
import urllib.parse
import urllib.request

TMP = os.environ["TEMP"]
A = _REPO_ROOT() + '/StardewHelper' + '/app/src/main/assets'

d = json.load(io.open(os.path.join(TMP, "shop_langlinks.json"), encoding="utf-8"))
mp = d["map"]
none = [k for k, v in mp.items() if not v]
print("待重查 %d" % len(none), file=sys.stderr)

API = "https://wiki.stardewvalley.net/mediawiki/api.php"


def langlinks(titles):
    u = (API + "?action=query&prop=langlinks&lllang=zh&redirects=1&format=json"
         "&formatversion=2&titles=" + urllib.parse.quote("|".join(titles)))
    req = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0"})
    dd = json.loads(urllib.request.urlopen(req, timeout=60).read().decode("utf-8"))
    out = {}
    for p in dd["query"]["pages"]:
        ll = p.get("langlinks") or []
        out[p["title"]] = ll[0]["title"] if ll else None
    return out


# 1) redirects=1 重查原名
res = {}
for i in range(0, len(none), 50):
    batch = none[i:i + 50]
    try:
        got = langlinks(batch)
    except Exception as e:
        print("batch %d FAIL %s" % (i, e), file=sys.stderr)
        got = {}
    for t in batch:
        res[t] = got.get(t)
    time.sleep(0.4)

hit2 = {k: v for k, v in res.items() if v}
still = [k for k, v in res.items() if not v]
print("redirects 重查命中 %d，仍缺 %d" % (len(hit2), len(still)), file=sys.stderr)

# 2) 基词查询 + 中文后缀拼接
SUFFIX = [
    (r"^(.*?) seeds$", "种子"), (r"^(.*?) sapling$", "树苗"),
    (r"^(.*?) starter$", "秧苗"), (r"^(.*?) bulb$", "球茎"),
    (r"^(.*?) ring$", "戒指"), (r"^(.*?) boots$", "靴"),
    (r"^(.*?) recipe$", "配方"), (r"^(.*?) (crafting )?recipe$", "配方"),
    (r"^(.*?) statue$", "雕像"), (r"^(.*?) torch$", "火炬"),
]
base_todo = []
pairs = {}
for name in still:
    ln = name.lower()
    for pat, suf in SUFFIX:
        m = re.match(pat, ln)
        if m:
            b = m.group(1).strip()
            base_todo.append(b)
            pairs[name] = (b, suf)
            break
base_todo = sorted(set(base_todo))
print("基词待查 %d" % len(base_todo), file=sys.stderr)
bres = {}
for i in range(0, len(base_todo), 50):
    batch = base_todo[i:i + 50]
    try:
        bres.update(langlinks(batch))
    except Exception:
        pass
    time.sleep(0.4)

res3 = {}
for name, (b, suf) in pairs.items():
    bz = bres.get(b)
    if bz:
        res3[name] = bz + suf
print("基词派生命中 %d" % len(res3), file=sys.stderr)

# 合并
final = dict(hit2)
final.update(res3)
leftover = [k for k in still if k not in res3]
print("\n三轮合计新命中 %d，仍无解 %d" % (len(final), len(leftover)), file=sys.stderr)
print("仍无解清单：")
for k in leftover:
    print("   ", k)
io.open(os.path.join(TMP, "shop_langlinks2.json"), "w", encoding="utf-8").write(
    json.dumps({"extra": final, "leftover": leftover}, ensure_ascii=False, indent=1))

# -*- coding: utf-8 -*-
"""
译名对齐第 4 层：图片 sha1 匹配。
项目 img/*.png 是英文 wiki 原图（v2.45 验证内容一致），文件名已是中文；
英文 wiki File:X.png 的 sha1 批量查（不下载），与本地 sha1 索引对撞 -> 英文->中文。
"""
import hashlib
import io
import json
import os
import sys
import time
import urllib.parse
import urllib.request

TMP = os.environ["TEMP"]
A = _REPO_ROOT() + '/StardewHelper' + '/app/src/main/assets'

# 1) 本地 sha1 索引
im = json.load(io.open(os.path.join(A, "image_map.json"), encoding="utf-8"))
local = {}
for cn, fn in im.items():
    p = os.path.join(A, "img", os.path.basename(str(fn)))
    if not os.path.exists(p):
        continue
    h = hashlib.sha1(open(p, "rb").read()).hexdigest()
    local.setdefault(h, []).append(cn)
print("本地有图条目 %d 个哈希" % len(local), file=sys.stderr)

need = json.load(io.open(os.path.join(TMP, "shop_langlinks.json"), encoding="utf-8"))
need2 = json.load(io.open(os.path.join(TMP, "shop_langlinks2.json"), encoding="utf-8"))
hit = {k: v for k, v in need["map"].items() if v}
hit.update(need2["extra"])
todo = [k for k in need2["leftover"] if k not in hit]
# 去括号注解/锚点：'Bait (item)' -> Bait；'Scarecrow#Rarecrows' -> Scarecrow
def clean(n):
    n = n.split("#")[0].strip()
    n = n.strip("'\" ")
    return n
todo_clean = {clean(t): t for t in todo}
print("langlinks 已覆盖 %d，sha1 待匹配 %d" % (len(hit), len(todo_clean)), file=sys.stderr)

API = "https://wiki.stardewvalley.net/mediawiki/api.php"


def file_sha(names):
    titles = "|".join("File:%s.png" % n for n in names)
    u = (API + "?action=query&prop=imageinfo&iiprop=sha1&format=json&formatversion=2&titles="
         + urllib.parse.quote(titles))
    req = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0"})
    d = json.loads(urllib.request.urlopen(req, timeout=60).read().decode("utf-8"))
    out = {}
    for p in d["query"]["pages"]:
        ii = p.get("imageinfo") or []
        if ii and "title" in p:
            base = p["title"][len("File:"):]
            if base.endswith(".png"):
                base = base[:-4]
            out[base] = ii[0].get("sha1")
    return out


result = {}
keys = sorted(todo_clean)
for i in range(0, len(keys), 50):
    batch = keys[i:i + 50]
    try:
        got = file_sha(batch)
    except Exception as e:
        print("batch %d FAIL %s" % (i, e), file=sys.stderr)
        got = {}
    for n in batch:
        h = got.get(n)
        if h and h in local:
            cands = local[h]
            # 多个中文名共享一张图：优先与英文名语义无关时取最短常用名；这里取第一个并记录
            result[todo_clean[n]] = {"cn": cands[0], "all": cands}
    time.sleep(0.4)

print("sha1 命中 %d / %d" % (len(result), len(keys)), file=sys.stderr)
leftover = [t for t in todo if t not in result]
print("\n仍无解 %d：" % len(leftover), file=sys.stderr)
for k in leftover:
    print("   ", k)
io.open(os.path.join(TMP, "shop_sha1.json"), "w", encoding="utf-8").write(
    json.dumps({"sha1_hits": result, "leftover": leftover}, ensure_ascii=False, indent=1))

# -*- coding: utf-8 -*-
"""v9 英文图 <-> 项目中文图 的像素哈希匹配：构建 en->cn 映射（第 5 层，本地零网络）。"""
import hashlib
import io
import json
import os
import re
import sys

from PIL import Image

V9 = _V9_DIR() + '/images/items'
A = _REPO_ROOT() + '/StardewHelper' + '/app/src/main/assets'


def pixhash(path, scale=1):
    im = Image.open(path).convert("RGBA")
    if scale != 1:
        im = im.resize((im.width * scale, im.height * scale), Image.NEAREST)
    return hashlib.md5(im.tobytes()).hexdigest()


# 1) 项目图索引（按尺寸分组）
im = json.load(io.open(os.path.join(A, "image_map.json"), encoding="utf-8"))
proj = {}          # pixhash -> set(cn)
proj_sizes = {}
for cn, fn in im.items():
    p = os.path.join(A, "img", os.path.basename(str(fn)))
    if not os.path.exists(p):
        continue
    w, h = Image.open(p).size
    proj_sizes.setdefault((w, h), 0)
    proj_sizes[(w, h)] += 1
    proj.setdefault(pixhash(p), set()).add(cn)
print("项目图 %d 哈希；尺寸分布 %s" % (len(proj), proj_sizes), file=sys.stderr)

# 2) v9 图：先原尺寸匹配，再 3x NEAREST（项目图可能是放大版）
hit = {}
miss = []
for f in os.listdir(V9):
    if not f.endswith(".png"):
        continue
    en = re.sub(r"^\d+_", "", f[:-4]).replace("_", " ").strip()
    p = os.path.join(V9, f)
    try:
        w, h = Image.open(p).size
    except Exception:
        continue
    scale = 48 // w if w and 48 % w == 0 else 1
    for cand in (pixhash(p, scale), pixhash(p, 1), pixhash(p, 3)):
        if cand in proj:
            hit[en] = sorted(proj[cand])
            break
    else:
        miss.append(en)
print("v9 匹配命中 %d，未命中 %d" % (len(hit), len(miss)), file=sys.stderr)

# 3) 对未命中的待查英文名（草稿里 lookup 失败的），输出命中表
io.open(os.path.join(os.environ["TEMP"], "pix_en2cn.json"), "w", encoding="utf-8").write(
    json.dumps({k: v for k, v in sorted(hit.items())}, ensure_ascii=False, indent=1))
print("已写 pix_en2cn.json")

# 抽查关键物品
for k in ("Sugar", "Rice", "Ice Cream", "Amethyst Ring", "Jazz Seeds", "Fairy Seeds",
          "Copper Ore", "Mega Bomb", "Staircase", "Artifact Trove", "Hay", "Fiber"):
    print("   %-16s -> %s" % (k, hit.get(k)))
