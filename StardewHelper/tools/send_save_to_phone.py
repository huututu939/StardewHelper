# -*- coding: utf-8 -*-
"""把电脑上的星露谷存档压缩包推给手机的「星露小助手」。

用法（在 PowerShell / cmd 里都行）：
    python tools/send_save_to_phone.py                 # 自动找全部农场，让你选
    python tools/send_save_to_phone.py --host 192.168.1.7
    python tools/send_save_to_phone.py --zip D:\\某农场.zip
    python tools/send_save_to_phone.py --probe           # 只探活，不发存档

为什么需要它：
    手机端 v2.67.0 起可以直接开一个「接收」窗口，PC 端跑这个脚本把 zip 推过去，
    省掉「右键压缩 → 传 QQ/微信 → 手机文件管理器里找」这一串手动操作。
    **不用登录任何账号**——Steam 的云存档 Web API 只对游戏自己的开发者开放，
    第三方拿不到星露谷 AppID 的读权限（见 SaveReceiver.kt 文件头的理由）。

存档路径（Windows）：
    %APPDATA%\\StardewValley\\Saves\\<农场名>\\
    目录名是 `Saves`（官方 wiki 中英文版一致）。脚本会按 Saves → Save_Game →
    SaveGame 的顺序挨个找，兼容个别改过目录名的 MOD 安装。

安全性：
    只往**你指定的那个局域网 IP** 发一个 zip，不联网、不上传任何别的东西。
    手机端收满一个包就自动关闭监听（单次即焚）。
"""
from __future__ import annotations

import argparse
import io
import os
import socket
import sys
import tempfile
import urllib.error
import urllib.request
import zipfile
from pathlib import Path

TIMEOUT = 30

# 存档里要排除的：游戏自己写的备份/合并档，都可能比主存档大，
# 与 App 端的 ZIP_EXCLUDE_* 同一套口径（两端保持一致，用户少踩坑）。
EXCLUDE_SUFFIX = ("_old", "_SVBAK", "_SVEMERG")
EXCLUDE_EXACT = {"SaveGameInfo", "BACKUP_SAVE"}


def saves_root() -> Path:
    appdata = os.environ.get("APPDATA")
    if not appdata:
        sys.exit("✗ 找不到 APPDATA 环境变量，这个脚本目前只支持 Windows。")
    base = Path(appdata) / "StardewValley"
    # 官方路径是 `Saves`（英文/中文 wiki 一致）。历史版本与个别 MOD 装在别的目录名下，
    # 所以按优先级挨个试，而不是只认一个——用户不该为了配合脚本去挪存档。
    for name in ("Saves", "Save_Game", "SaveGame"):
        d = base / name
        if d.is_dir() and any(x.is_dir() for x in d.iterdir()):
            if name != "Saves":
                print("提示：没找到 Saves，用的是 %s（这不是标准路径，请留意）。" % name)
            return d
    return base / "Saves"   # 都不存在时给出标准路径，报错文案才有指导性


def is_excluded(name: str) -> bool:
    base = name.rsplit("/", 1)[-1].rsplit("\\", 1)[-1]
    if base in EXCLUDE_EXACT:
        return True
    return base.lower().endswith(tuple(s.lower() for s in EXCLUDE_SUFFIX))


def list_farms(root: Path) -> list[Path]:
    """列出候选农场文件夹：里面必须有「不是备份」的大文件。"""
    if not root.is_dir():
        sys.exit("✗ 找不到存档目录：%s\n   确认游戏玩过、且 Steam 存档同步过。" % root)
    out = []
    for d in sorted(root.iterdir()):
        if not d.is_dir():
            continue
        files = [f for f in d.iterdir() if f.is_file() and not is_excluded(f.name)]
        big = [f for f in files if f.stat().st_size > 100 * 1024]
        if big:
            out.append(d)
    return out


def zip_farm(farm: Path, dest: Path) -> int:
    """把整个农场文件夹压成 zip（含子目录，因为 mod 存档会有 Mods 文件夹）。"""
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
        for path in sorted(farm.rglob("*")):
            if path.is_dir():
                continue
            rel = path.relative_to(farm.parent)
            if any(is_excluded(str(p)) for p in rel.parts[:-1]):
                continue   # 备份子目录整个跳过
            if is_excluded(str(rel)):
                continue
            z.write(path, rel.as_posix())
    return dest.stat().st_size


def probe(host: str, port: int) -> bool:
    url = "http://%s:%d/" % (host, port)
    try:
        with urllib.request.urlopen(url, timeout=5) as r:
            body = r.read(200).decode("utf-8", "replace")
            print("✓ 手机端在听：%s" % body.strip())
            return True
    except (urllib.error.URLError, socket.timeout, OSError) as e:
        print("✗ 连不上 %s：%s" % (url, e))
        return False


def send(host: str, port: int, zip_path: Path) -> int:
    data = zip_path.read_bytes()
    url = "http://%s:%d/upload" % (host, port)
    req = urllib.request.Request(url, data=data, method="POST",
                                 headers={"Content-Type": "application/zip"})
    try:
        with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
            body = r.read(400).decode("utf-8", "replace").strip()
            print("✓ 手机端已收到（%d 字节）%s" % (len(data), ("：" + body) if body else ""))
            print("  现在看手机：它会弹出确认抽屉，核对数字后点「确定导入」。")
            return 0
    except urllib.error.HTTPError as e:
        detail = e.read(400).decode("utf-8", "replace").strip()
        print("✗ 手机端拒绝了这个包（HTTP %d）：%s" % (e.code, detail))
        return 1
    except (urllib.error.URLError, socket.timeout, OSError) as e:
        print("✗ 发送失败：%s" % e)
        print("  常见原因：① 手机不在同一 WiFi；② 手机上的接收窗口已关；"
              "③ 防火墙拦了——先允许 Python 通过 Windows 防火墙。")
        return 1


def main() -> int:
    ap = argparse.ArgumentParser(description="把星露谷存档推给手机的星露小助手")
    ap.add_argument("--host", default="", help="手机的局域网 IP（接收窗口上会显示）")
    ap.add_argument("--port", type=int, default=38217, help="接收端口（默认 38217）")
    ap.add_argument("--zip", dest="zip_path", default="", help="已经是 zip 就直接给路径")
    ap.add_argument("--probe", action="store_true", help="只探活，不发送")
    ap.add_argument("--farm", default="", help="农场名（不含路径）")
    args = ap.parse_args()

    host = args.host.strip()
    if host:
        if args.probe:
            return 0 if probe(host, args.port) else 1
        if args.zip_path:
            return send(host, args.port, Path(args.zip_path))
        print("✗ --host 需要配 --zip 或 --farm")
        return 2

    # 交互：列农场 → 选一个 → 让用户输手机 IP
    root = saves_root()
    farms = list_farms(root)
    print("找到 %d 个农场（在 %s）：" % (len(farms), root))
    for i, d in enumerate(farms, 1):
        print("  %d) %s" % (i, d.name))
    if not farms:
        return 1

    pick = args.farm or input("选哪个农场（序号或名字，回车取消）：").strip()
    if not pick:
        print("已取消。")
        return 1
    farm = next((d for d in farms if d.name == pick), None)
    if farm is None and pick.isdigit():
        idx = int(pick) - 1
        farm = farms[idx] if 0 <= idx < len(farms) else None
    if farm is None:
        print("✗ 没找到这个农场。")
        return 1

    host = input("手机上的接收窗口显示的 IP（形如 192.168.1.7）：").strip()
    if not host:
        print("已取消。")
        return 1

    tmpdir = tempfile.mkdtemp(prefix="sd_save_")
    zp = Path(tmpdir) / (farm.name + ".zip")
    size = zip_farm(farm, zp)
    print("已打包 %s（%.1f MB）" % (zp.name, size / 1048576.0))
    return send(host, args.port, zp)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\n已取消。")
        sys.exit(1)
