# -*- coding: utf-8 -*-
"""下载星露谷官方 1.6.15 贴图（BigCraftables + Crops）到 assets/img/official_* 并逐张校验。

来源：GitHub 仓库 juliaramosguedes/stardew-data（分支 main）的 LFS 真实文件端点
    https://media.githubusercontent.com/media/juliaramosguedes/stardew-data/main/sprites/<dir>/<name>.png
注意：raw.githubusercontent.com / ghproxy.net 对该仓库不可用；LFS 端点必须带 /media/ 一级。

清单来源：GitHub API 一次拉全树
    https://api.github.com/repos/juliaramosguedes/stardew-data/git/trees/main?recursive=1

落盘：
    app/src/main/assets/img/official_bc/<英文名>.png      （147 张）
    app/src/main/assets/img/official_crop/<英文名>.png    （50 张）
并生成 tools/official_sprites_index.json（相对 assets 根的路径）。

校验：PNG magic = 89504E47 且尺寸必须 16x32；不合格的一律不入库（删掉坏文件并记录）。
重试：单张失败重试 3 次（指数退避）；个别失败不阻塞整体，最后报告失败清单。

用法：
    python tools/fetch_official_sprites.py            # 增量下载（已存在且合规则跳过）
    python tools/fetch_official_sprites.py --force    # 全量重下
    python tools/fetch_official_sprites.py --verify   # 离线体检本地文件，不联网
"""
import argparse
import json
import os
import struct
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor

sys.stdout.reconfigure(encoding="utf-8")

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")

TREE_API = "https://api.github.com/repos/juliaramosguedes/stardew-data/git/trees/main?recursive=1"
MEDIA = "https://media.githubusercontent.com/media/juliaramosguedes/stardew-data/main/sprites"

# 官方目录 -> 落盘子目录 / index.json 顶层 key
DIRS = (
    ("bigcraftables", "official_bc"),
    ("crops", "official_crop"),
)

PNG_MAGIC = b"\x89PNG\r\n\x1a\n"
EXPECT_W, EXPECT_H = 16, 32
UA = "StardewHelper-Audit/1.0 (official sprite fetch)"

INDEX_PATH = os.path.join(HERE, "official_sprites_index.json")
TREE_CACHE = os.path.join(HERE, "official_sprites_tree.json")

MAX_ATTEMPTS = 3
BACKOFF_BASE = 1.5
TIMEOUT = 60
WORKERS = 6

# ---------- 环境：清掉 4 个死代理变量 ----------
for _k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(_k, None)


# ---------- HTTP ----------
def http_get(url, timeout=TIMEOUT, attempts=MAX_ATTEMPTS):
    """带指数退避重试的 GET。返回 bytes；全部失败则抛最后一个异常。"""
    last = None
    for i in range(attempts):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
            with urllib.request.urlopen(req, timeout=timeout) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            if i < attempts - 1:
                time.sleep(BACKOFF_BASE * (2 ** i))
    raise last


# ---------- PNG 校验 ----------
def png_size(data):
    """返回 (w, h)；不是合法 PNG 则返回 None。"""
    if not data or len(data) < 24 or data[:8] != PNG_MAGIC:
        return None
    if data[12:16] != b"IHDR":
        return None
    return struct.unpack(">II", data[16:24])


def is_valid_sprite(data):
    size = png_size(data)
    return size == (EXPECT_W, EXPECT_H)


# ---------- 清单 ----------
def fetch_listing():
    """拉 GitHub 全树，抽出 bigcraftables / crops 的 png 名单。缓存到本地便于离线复跑。"""
    print("[1/3] 拉取 GitHub 仓库清单 ...", flush=True)
    try:
        raw = http_get(TREE_API, timeout=90)
        tree = json.loads(raw.decode("utf-8"))
        paths = [t["path"] for t in tree.get("tree", []) if t.get("type") == "blob"]
        listing = {}
        for src, _dst in DIRS:
            pref = "sprites/%s/" % src
            listing[src] = sorted(
                p[len(pref):-4] for p in paths if p.startswith(pref) and p.endswith(".png")
            )
        with open(TREE_CACHE, "w", encoding="utf-8") as f:
            json.dump(listing, f, indent=1, ensure_ascii=False, sort_keys=True)
        return listing
    except Exception as e:
        print("  ! 在线清单失败：%s" % (e,), flush=True)
        if os.path.exists(TREE_CACHE):
            print("  -> 回退到本地缓存清单 %s" % TREE_CACHE, flush=True)
            with open(TREE_CACHE, encoding="utf-8") as f:
                return json.load(f)
        raise


# ---------- 下载 ----------
def fetch_one(src_dir, name, dest_path, force, offline=False):
    """下载单张并校验。返回 (ok: bool, bytes: int, reason: str)。"""
    # 已存在且合规 -> 跳过（幂等）
    if not force and os.path.exists(dest_path):
        with open(dest_path, "rb") as f:
            data = f.read()
        if is_valid_sprite(data):
            return True, len(data), "cached"
        try:
            os.remove(dest_path)  # 坏文件不入库
        except OSError:
            pass

    if offline:  # --verify 模式：只体检本地，绝不联网
        return False, 0, "missing-or-invalid (offline)"

    url = "%s/%s/%s.png" % (MEDIA, src_dir, name)
    try:
        data = http_get(url, timeout=TIMEOUT, attempts=MAX_ATTEMPTS)
    except Exception as e:  # noqa: BLE001
        return False, 0, "download: %s: %s" % (type(e).__name__, e)

    if not data or data[:8] != PNG_MAGIC:
        return False, len(data or b""), "not-png (magic=%s)" % (data[:8].hex() if data else "empty")

    size = png_size(data)
    if size != (EXPECT_W, EXPECT_H):
        return False, len(data), "bad-size %sx%s (want %dx%d)" % (
            size[0] if size else "?", size[1] if size else "?", EXPECT_W, EXPECT_H)

    tmp = dest_path + ".tmp"
    with open(tmp, "wb") as f:
        f.write(data)
    os.replace(tmp, dest_path)
    return True, len(data), "downloaded"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true", help="忽略本地已有文件，全量重下")
    ap.add_argument("--verify", action="store_true", help="只校验本地已有文件，不联网下载")
    args = ap.parse_args()

    if args.verify:
        # 离线体检：清单用本地缓存，落盘目录 = 期望集合
        if os.path.exists(TREE_CACHE):
            with open(TREE_CACHE, encoding="utf-8") as f:
                listing = json.load(f)
        else:
            listing = {}
        offline = True
    else:
        listing = fetch_listing()
        offline = False

    print("\n[2/3] 下载 + 校验", flush=True)
    index = {"bigcraftables": {}, "crops": {}}
    failures = []
    total_bytes = 0
    counts = {}

    for src, dst in DIRS:
        names = listing.get(src, [])
        outdir = os.path.join(ASSETS, "img", dst)
        os.makedirs(outdir, exist_ok=True)
        ok_n = 0
        print("\n  -- %s -> img/%s/  (目标 %d 张)" % (src, dst, len(names)), flush=True)

        def work(n):
            return (n,) + fetch_one(src, n, os.path.join(outdir, n + ".png"), args.force, offline)

        with ThreadPoolExecutor(max_workers=WORKERS) as ex:
            for i, (name, ok, nbytes, reason) in enumerate(ex.map(work, names), 1):
                if ok:
                    ok_n += 1
                    total_bytes += nbytes
                    index[src][name] = "img/%s/%s.png" % (dst, name)
                else:
                    failures.append("%s/%s.png : %s" % (src, name, reason))
                if i % 25 == 0 or i == len(names):
                    print("     %d/%d  成功 %d" % (i, len(names), ok_n), flush=True)

        counts[src] = ok_n
        # 目录里多余的旧文件（不在清单里）不算失败，但清掉，避免脏数据
        keep = set(n + ".png" for n in names)
        for fn in os.listdir(outdir):
            if fn.endswith(".png") and fn not in keep and not offline:
                try:
                    os.remove(os.path.join(outdir, fn))
                    print("     (清理多余文件 %s)" % fn, flush=True)
                except OSError:
                    pass

    # ---------- 写 index.json ----------
    print("\n[3/3] 写索引 %s" % INDEX_PATH, flush=True)
    payload = {
        "bigcraftables": dict(sorted(index["bigcraftables"].items())),
        "crops": dict(sorted(index["crops"].items())),
    }
    with open(INDEX_PATH, "w", encoding="utf-8") as f:
        json.dump(payload, f, indent=1, ensure_ascii=False, sort_keys=True)
        f.write("\n")

    # ---------- 汇总 ----------
    print("\n" + "=" * 62)
    print("bigcraftables : %d 张" % counts.get("bigcraftables", 0))
    print("crops         : %d 张" % counts.get("crops", 0))
    print("合计成功      : %d 张" % (counts.get("bigcraftables", 0) + counts.get("crops", 0)))
    print("总字节数      : %d B (%.1f KB)" % (total_bytes, total_bytes / 1024.0))
    print("index.json    : %s" % INDEX_PATH)
    if failures:
        print("失败 %d 张：" % len(failures))
        for f in failures:
            print("  - " + f)
    else:
        print("失败：无")
    print("=" * 62)

    # 复跑校验：index 里每条都要能在 assets 下找到且尺寸正确
    bad = []
    for src, _dst in DIRS:
        for name, rel in payload[src].items():
            p = os.path.join(ASSETS, rel.replace("/", os.sep))
            if not os.path.exists(p):
                bad.append("%s (缺文件)" % rel)
                continue
            with open(p, "rb") as f:
                d = f.read()
            if png_size(d) != (EXPECT_W, EXPECT_H):
                bad.append("%s (尺寸不符)" % rel)
    print("本地复检：%d 条索引，%d 条异常" % (sum(len(v) for v in payload.values()), len(bad)))
    for b in bad[:20]:
        print("  ! " + b)

    return 1 if (failures or bad) else 0


if __name__ == "__main__":
    sys.exit(main())
