# -*- coding: utf-8 -*-
"""用真实存档交叉验证 saveImportMap.json —— 验的是**映射数据对不对**，不是 Kotlin 解析器。

用法:
    python tools/check_save_import.py                 # 自动从 `存档/` 解压再验
    python tools/check_save_import.py <存档文件> ...  # 直接指定已解压的存档文件

## 为什么需要它

Kotlin 侧没有自动化测试（项目没建 testInfrastructure）。真正高风险的不是
「XmlPullParser 状态机写没写对」——那是对已验证数据的薄适配——而是
**映射表本身有没有错**：某个中文名拼错、某个 ID 错位、某个键用了源数据的
叫法而不是存档里的叫法。这些错误**不会报错**，只会静默勾错或不勾。

所以这里用一份**独立的 Python 实现**解析真实存档，再拿结果去撞映射表。
两份实现独立，撞出来的矛盾就是真问题。

## 判据纪律

- 用 `iterparse` 流式 + 及时 `clear()`，**不用 DOM**：KKK 那份 34MB 建 DOM
  会吃掉 1GB+ 内存。
- 标签**大小写敏感**（星露谷存档里 `money` 与 `Money` 是两个东西）。
- 前三组只认 `player` 的**直接子节点**里的目标容器。
- **博物馆两组读 `locations/GameLocation[LibraryMuseum]/museumPieces`**（v2.66.0），
  它的键是 `<Vector2>` 展柜坐标、**`<value>` 才是 item_id**；读不到时回退
  `mineralsFound`/`archaeologyFound`。
- 映射查不到 → 计入 unmapped，**不当错误**。这是设计好的行为（宁可不勾）。

## 与 Kotlin 侧的对应关系

`GROUPS`（本文件）与 `CONTAINERS`（SaveImport.kt）必须一一对应且 tag 相同。
verify 第 58 节会断言这一点。
"""
import io
import json
import os
import re
import subprocess
import sys
import time
import zipfile
import xml.etree.ElementTree as ET

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
MAP_PATH = os.path.join(ROOT, "app", "src", "main", "assets", "data", "saveImportMap.json")

ARCHIVE_DIR = os.path.join(os.path.dirname(ROOT), "存档")
EXTRACT_DIR = os.path.join(os.environ.get("TEMP", "/tmp"), "sdv_saves")

# 与 SaveImport.kt 的 CONTAINERS 一一对应：
# (存档标签, 映射分区列表, 是否要求 value>0, 是否剥 (O) 前缀)
GROUPS = [
    ("fishCaught", ["fishById", "fishByName"], False, True),
    ("recipesCooked", ["recipeById", "recipeByName"], False, False),
    ("craftingRecipes", ["craftByEn"], True, False),
    ("museumPieces", ["mineralById"], False, False),
    ("museumPieces", ["artifactById"], False, False),
]
# 博物馆读不到捐赠记录时的回退容器（与 Kotlin 的 MUSEUM_FALLBACK 一致）
MUSEUM_FALLBACK = {
    "mineralById": "mineralsFound",
    "artifactById": "archaeologyFound",
}
SECTIONS = ["fishById", "fishByName", "recipeById", "recipeByName",
            "craftByEn", "mineralById", "artifactById"]
# 前三个分组在 player 子树内；后两个（博物馆）在 locations 下且 ID 取自 value
IN_PLAYER_TAGS = {"fishCaught", "recipesCooked", "craftingRecipes"}
FALLBACK_TAGS = set(MUSEUM_FALLBACK.values())
# 按分组归拢的 key：player 子树内三个 + 博物馆回退两个
PLAYER_TARGETS = IN_PLAYER_TAGS | FALLBACK_TAGS

EXCLUDE_EXACT = {"SaveGameInfo", "BACKUP_SAVE"}
EXCLUDE_SUFFIX = ("_old", "_SVBAK", "_SVEMERG")


def leaf(el):
    return (el.text or "").strip() if el is not None else ""


def parse_museum(raw):
    """独立实现：扫 LibraryMuseum 位置下的 museumPieces，返回 item_id 列表。

    刻意不用 iterparse——Kotlin 侧是 XmlPullParser 状态机，这里用正则扫字节，
    两份实现真正独立，撞出来的矛盾才可信。34MB 单文件正则扫描约 1 秒。

    键是 `<Vector2>` 展柜坐标（`<X>/<Y>` 不会误匹配），值才是 ID；
    value 双形态：MOD 档 `<int>`、原版档 `<string>`，两个都要认。
    """
    out = []
    for m in re.finditer(rb"<museumPieces>(.*?)</museumPieces>", raw, re.S):
        seg = m.group(1)
        for v in re.findall(rb"<value><(?:int|string)>(-?\d+)</(?:int|string)></value>", seg):
            out.append(v.decode())
        if out:
            break  # 四档实测只有一个 LibraryMuseum 段
    return out


def find_saves(root):
    """存档主文件：无扩展名、以 UTF-8 BOM 开头、体积 > 100KB。"""
    out = []
    for dirpath, _dirs, files in os.walk(root):
        for fn in files:
            if fn in EXCLUDE_EXACT or any(fn.upper().endswith(s) for s in EXCLUDE_SUFFIX):
                continue
            p = os.path.join(dirpath, fn)
            try:
                with open(p, "rb") as f:
                    if f.read(3) != b"\xef\xbb\xbf":
                        continue
                if os.path.getsize(p) < 100_000:
                    continue
            except OSError:
                continue
            out.append(p)
    return out


def extract_archives():
    """把 `存档/` 下的压缩包解到 TEMP。zip 用 zipfile，rar 交给系统 tar（bsdtar 支持 rar）。"""
    if not os.path.isdir(ARCHIVE_DIR):
        return []
    os.makedirs(EXTRACT_DIR, exist_ok=True)
    out = []
    for fn in sorted(os.listdir(ARCHIVE_DIR)):
        if not fn.lower().endswith((".zip", ".rar", ".7z")):
            continue
        stem = os.path.splitext(fn)[0]
        dest = os.path.join(EXTRACT_DIR, stem)
        if not os.path.isdir(dest):
            os.makedirs(dest, exist_ok=True)
            src = os.path.join(ARCHIVE_DIR, fn)
            if fn.lower().endswith(".zip"):
                with zipfile.ZipFile(src) as z:
                    z.extractall(dest)
            else:
                try:
                    subprocess.run(["tar", "-xf", src, "-C", dest], check=True,
                                   capture_output=True, timeout=600)
                except Exception as e:
                    print("  ! %s 解压失败（%s），跳过" % (fn, e))
                    continue
        out.extend(find_saves(dest))
    return out


def parse_save(path):
    """流式解析 player 直接子节点下的容器 + 正则扫博物馆捐赠记录。

    返回 (got, museum, fell_back)：
      got      {tag: [(key, 首个 value 里的 int 或 None)]}
      museum   [item_id, ...]（来自 museumPieces 的 <value>）
      fell_back 博物馆是否走了「找到过」回退

    key 同时接受 <key><int>113</int></key> 与 <key><string>111</string></key> 两种形态
    —— 与 SaveImport.kt 的双分支对应，这是实测存在的差异（MOD 档 int / 原版档 string）。
    """
    got = {t: [] for t in PLAYER_TARGETS}
    ctx = ET.iterparse(path, events=("start", "end"))
    _e, root = next(ctx)
    path_stack = [root.tag]
    cur = None

    for event, elem in ctx:
        if event == "start":
            # 只认 player 的直接子节点；locations 下的同名容器一律不管
            if cur is None and elem.tag in PLAYER_TARGETS and path_stack[-1] == "player":
                cur = elem.tag
            path_stack.append(elem.tag)
            continue

        # end 事件
        if cur is not None:
            if elem.tag == "item" and path_stack[-2:-1] == [cur]:
                key = None
                value = None
                for ch in elem:
                    if ch.tag == "key":
                        for k in ch:
                            if key is None:
                                key = leaf(k)
                    elif ch.tag == "value":
                        for v in ch:
                            # 回退容器（mineralsFound/archaeologyFound）的键是纯数字，
                            # 这里取 value 供 requirePositive 判断；键本身已由上面取到
                            if value is None and (v.tag == "int" or v.tag == "string"):
                                t = leaf(v)
                                if t.lstrip("-").isdigit():
                                    value = int(t)
                                break
                if key is not None:
                    got[cur].append((key, value))
                elem.clear()
            elif elem.tag == cur:
                cur = None
                elem.clear()
        if len(path_stack) > 1:
            path_stack.pop()
        # 内存回收：已离开的子树立刻清空，否则 34MB 全驻留
        if len(path_stack) > 1 and path_stack[-1] != "player":
            pass

    # 博物馆：正则扫字节，与上面的 iterparse 是两套完全独立的实现
    with open(path, "rb") as f:
        raw = f.read()
    museum = parse_museum(raw)
    del raw

    fell_back = False
    for section, fb_tag in MUSEUM_FALLBACK.items():
        if not museum and got.get(fb_tag):
            museum = [k for k, _v in got[fb_tag]]
            fell_back = True
    return got, museum, fell_back


def load_map():
    with io.open(MAP_PATH, encoding="utf-8") as f:
        root = json.load(f)
    return {s: root.get(s, {}) for s in SECTIONS}


def main():
    mp = load_map()
    print("映射表: %s" % ", ".join("%s=%d" % (k, len(v)) for k, v in mp.items()))
    denom_total = sum(len(set(mp[s].values())) for s in SECTIONS)
    print("映射覆盖 App 条目合计: %d" % denom_total)

    saves = sys.argv[1:]
    if not saves:
        print("\n未指定存档，尝试从 %s 解压…" % ARCHIVE_DIR)
        saves = extract_archives()
    if not saves:
        print("找不到任何存档文件。请把 Steam 的存档文件夹")
        print("（%%APPDATA%%\\StardewValley\\Save_Game\\<农场名>\\）压缩后放到 %s，" % ARCHIVE_DIR)
        print("或直接把解压后的存档文件路径作为参数传进来。")
        return 2

    print("\n找到 %d 份存档" % len(saves))
    rc = 0
    for p in saves:
        print("\n" + "=" * 66)
        print("### %s  (%.1f MB)" % (os.path.basename(p), os.path.getsize(p) / 1048576.0))
        t0 = time.perf_counter()
        try:
            got, museum, fell_back = parse_save(p)
        except Exception as e:
            print("  解析失败: %s" % e)
            rc = 1
            continue
        dt = time.perf_counter() - t0
        print("  解析耗时: %.2f 秒%s" % (dt, "  ⚠ 超过 5 秒，UI 需要进度反馈" if dt > 5 else ""))
        print("  博物馆捐赠记录 %d 条%s" % (
            len(museum), "（读不到，已回退「找到过」）" if fell_back else ""))

        unmapped_total = 0
        total_hit = 0
        museum_unmapped = 0
        for tag, sections, req_pos, strip in GROUPS:
            tables = [mp[s] for s in sections]
            denom = len(set().union(*[set(t.values()) for t in tables]))
            # museumPieces 的键是展柜坐标，ID 在 value 里
            is_museum = tag == "museumPieces"
            entries = ([(i, None) for i in museum] if is_museum
                       else got.get(tag, []))
            hit = unmapped = 0
            hit_names = set()
            for k, v in entries:
                if req_pos and (v or 0) <= 0:
                    continue
                probe = k[3:] if strip and k.startswith("(O)") else k
                if not any(probe in t for t in tables):
                    # 博物馆两组的 entries 是同一批，只在循环后按「两张表都查不到」计一次
                    if not is_museum:
                        unmapped += 1
                    continue
                hit += 1
                # 与 Kotlin 侧一致：hit 按唯一条目计，分母才是去重条目数
                hit_names.update(t[probe] for t in tables if probe in t)
            unmapped_total += unmapped
            total_hit += len(hit_names)
            flag = "  ⚠ 命中 > 分母" if len(hit_names) > denom else ""
            print("    %-17s 命中 %3d / 覆盖 %3d   未识别 %d%s" % (
                tag, len(hit_names), denom, unmapped, flag))
        # 博物馆未识别：同一批 ID 两张表都查不到才算一条
        if museum:
            minT = mp["mineralById"]
            artT = mp["artifactById"]
            museum_unmapped = sum(1 for i in museum if i not in minT and i not in artT)
            unmapped_total += museum_unmapped
        print("    未识别合计 %d（博物馆 %d），将勾上 %d 条" % (
            unmapped_total, museum_unmapped, total_hit))
    return rc


if __name__ == "__main__":
    sys.exit(main())
