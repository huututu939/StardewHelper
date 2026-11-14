# -*- coding: utf-8 -*-
"""
发布脚本：把最新 release APK + version.json 提交进 gitee 发布仓。

发布仓（../发布仓/）是独立 git 仓库，与源码仓零耦合，只放四样东西：
  - apk/stardew-assistant_vX.Y.Z_cNN.apk   **只保留最新版**（历史/旧包不放这里，
    全量归档在本地 安装包归档/；2026-10-15 定口径：旧包占公开仓体积且没人从那里回头下旧版）
  - version.json                    App「检查更新」读取的唯一清单
  - data/*.json + data/LICENSE      App 整理数据（CC BY-NC-SA 3.0，发版时从 assets/data 同步）。
    2026-10-15 加：原计划放独立仓 stardew-helper-data，被 gitee 风控「涉嫌外链滥用(RAW)」
    锁私有无法开源；SA 要求数据公开可得，改为随发布仓一起发（数据**只放 JSON 不放图标**，
    图标无授权）。data/README.md 手写在仓里，本脚本不覆盖它。
  - tools/send_save.bat             电脑端推送脚本（v2.68.0 / c110）。原先只发在 QQ 群，
    不在群里的用户拿不到，而 App 里已经放了「下载 send_save.bat」按钮指向这里。
    **逐字节复制**：仓里的 .gitattributes 钉了 `*.bat -text`，否则 autocrlf=input 会把
    CRLF 换成 LF，而 cmd.exe 读 LF-only 批处理的标签与括号块有已知问题。

用法（在 StardewHelper/ 目录下）：
  python tools/publish_gitee.py            # 构建后同步发布仓并 commit（不 push）
  python tools/publish_gitee.py --push     # 同上并尝试 git push（需已配好凭据）
  python tools/publish_gitee.py --check    # 只校验发布仓与 gradle/CHANGELOG 一致性，不写
  python tools/publish_gitee.py --help     # 只打印用法，不碰任何文件
  python tools/publish_gitee.py --notes-file <路径>   # 用文件里的多行说明覆盖 notes（见下）

推完之后**必须再验一遍**（站在匿名使用者立场，四项：清单/产物三方一致、旧包已 404、data/ 完好）：
  python tools/smoke_publish.py
⚠ 冒烟脚本不是可选项：c96 那次手工冒烟把重试写成只判 HTTP 200，
  撞上 gitee raw 缓存里的「过期 200」当场判绿——假绿比不验更坏。

⚠ 本脚本**默认就会写文件并 commit**。v2.58.0 时因为没实现 --help，
  一条 `python tools/publish_gitee.py --help` 直接把当时的最新版又复制回了
  发布仓并提交，等于误触发了一次发布。所以 --help / 未知参数必须在这里挡住。

⚠ 本脚本**只能发布 app/build.gradle.kts 里的当前版本**（版本号永远从 gradle 现算）。
  要发历史版本（如把 c89 回滚上架当回滚目标）得手工写 version.json，
  口径见 安装包归档/版本备注.md 的对应记录。

## --notes-file：批量补发时 notes 不能只取 CHANGELOG 首条

默认 notes 取 App 内更新日志（SettingsPage CHANGELOG）**首条**。这只在
「发布仓紧跟着当前版本」时成立——用户从上一版跳一版，首条就是完整的更新说明。

**但发布仓可能落后多个版本**（2026-10-15 就是：c89 之后清过仓，c90~c94 积攒了
5 个版本才第一次推）。此时用户是从 c89 直接跳到 c94，弹窗只显示 v2.60.0 那一行，
前面 4 个版本的变化一个字都看不到——这是真实的用户信息缺失，不是美观问题。

所以给 `--notes-file <路径>`：UTF-8 文本，**一行一条 note**，覆盖 CHANGELOG 首条。
给了它就不再要求「首条版本号 == 当前版本」；不给时该检查照旧（发布版必须有
对得上的日志条目，写旧版等于骗人）。
"""
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import shutil
import datetime

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)                       # StardewHelper/
PUB = os.path.join(os.path.dirname(ROOT), "发布仓")  # 星露谷/发布仓
APK_BUILD = os.path.join(ROOT, "app", "build", "outputs", "apk", "release", "app-release.apk")


def read(p):
    return io.open(p, encoding="utf-8").read()


def gradle_version():
    gb = read(os.path.join(ROOT, "app", "build.gradle.kts"))
    vc = int(re.search(r"versionCode\s*=\s*(\d+)", gb).group(1))
    vn = re.search(r'versionName\s*=\s*"([^"]+)"', gb).group(1)
    return vc, vn


def changelog_head():
    """返回 (标题里的版本号, notes 列表)。"""
    sp = read(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                           "assistant", "ui", "settings", "SettingsPage.kt"))
    m = re.search(r'private val CHANGELOG = listOf\(\s*\r?\n\s*"v([0-9][0-9.]*)[^"]*" to listOf\((.*?)\),',
                  sp, re.S)
    if not m:
        raise SystemExit("✗ CHANGELOG 首条解析失败")
    notes = re.findall(r'\n\s+"([^"]+)"', m.group(2))
    return m.group(1), notes


def read_notes_file(path):
    """--notes-file：UTF-8 文本，一行一条 note。带 BOM 的（Windows 编辑器常见）也要认。"""
    if not os.path.isabs(path):
        path = os.path.join(os.getcwd(), path)
    if not os.path.exists(path):
        raise SystemExit("✗ --notes-file 指向的文件不存在：%s" % path)
    lines = [l.strip().lstrip("﻿") for l in read(path).splitlines()]
    notes = [l for l in lines if l]
    if not notes:
        raise SystemExit("✗ --notes-file 里一行非空内容都没有：%s" % path)
    return notes


def parse_args(argv):
    """v2.58.0 补：--help 与未知参数必须在这里挡住。
    此前两者都被忽略，--help 照样跑完整个发布流程并 commit（误触发过一次）。

    这里必须逐项解析而不是 set(sys.argv[1:])：--notes-file 后面跟着一个路径值，
    那个路径会被当成「未知参数」拒掉。
    """
    opts = {"help": False, "check": False, "push": False, "notes_file": None}
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ("--help", "-h"):
            opts["help"] = True
        elif a == "--check":
            opts["check"] = True
        elif a == "--push":
            opts["push"] = True
        elif a == "--notes-file":
            if i + 1 >= len(argv):
                raise SystemExit("✗ --notes-file 缺参数：后面要给文件路径")
            opts["notes_file"] = argv[i + 1]
            i += 1          # 跳过被当作参数值的那个路径
        else:
            raise SystemExit("✗ 未知参数：%s（本脚本默认会写文件并 commit，参数拼错代价很大）" % a)
        i += 1
    return opts


def git(repo, *args, **kw):
    cmd = ["git", "-C", repo] + list(args)
    return subprocess.run(cmd, capture_output=True, text=True, encoding="utf-8", **kw)


def main():
    opts = parse_args(sys.argv[1:])
    if opts["help"]:
        print(__doc__)
        return 0

    check_only = opts["check"]
    do_push = opts["push"]
    if not os.path.isdir(os.path.join(PUB, ".git")):
        raise SystemExit("✗ 发布仓不存在或未初始化：%s" % PUB)

    vc, vn = gradle_version()
    head_vn, head_notes = changelog_head()
    print("gradle: v%s (c%d) / CHANGELOG 首条: v%s（%d 条 notes）" % (vn, vc, head_vn, len(head_notes)))

    # notes 三种来源，优先级：--notes-file > CHANGELOG 首条（须对得上版本）
    if opts["notes_file"]:
        notes = read_notes_file(opts["notes_file"])
        print("notes 覆盖：--notes-file %s（%d 条）" % (opts["notes_file"], len(notes)))
    elif head_vn == vn:
        notes = head_notes
    else:
        raise SystemExit(
            "✗ CHANGELOG 首条 v%s 不等于当前版本 v%s——发布版必须有对得上的日志条目"
            "（检查更新弹窗直接展示 notes）\n"
            "  若这是**批量补发**（发布仓落后多个版本、用户会从更旧的版本跳过来），"
            "用 --notes-file <路径> 指定一份汇总说明，一行一条。" % (head_vn, vn))

    # 发布仓文件名用纯 ASCII：中文文件名进 URL 要靠各客户端自觉编码，
    # v2.55.0 冒烟实测严格 HTTP 客户端直接抛 UnicodeEncodeError——根除优于打补丁。
    # （本地 安装包归档/ 仍用中文名，那是给人看的文件，不进 URL。）
    apk_name = "stardew-assistant_v%s_c%d.apk" % (vn, vc)
    dst_apk = os.path.join(PUB, "apk", apk_name)
    src_apk = APK_BUILD
    if not os.path.exists(src_apk):
        raise SystemExit("✗ 找不到构建产物 %s，先跑 assembleRelease" % src_apk)

    data = open(src_apk, "rb").read()
    md5 = hashlib.md5(data).hexdigest()
    manifest = {
        "versionCode": vc,
        "versionName": vn,
        "date": datetime.date.today().isoformat(),
        "notes": notes,
        "apk": "apk/" + apk_name,
        "size": len(data),
        "md5": md5,
    }
    want_json = json.dumps(manifest, ensure_ascii=False, indent=2) + "\n"

    if check_only:
        ok_apk = os.path.exists(dst_apk) and hashlib.md5(open(dst_apk, "rb").read()).hexdigest() == md5
        cur = read(os.path.join(PUB, "version.json")) if os.path.exists(os.path.join(PUB, "version.json")) else ""
        cur_m = json.loads(cur) if cur.strip() else {}
        ok_json = (cur_m.get("versionCode") == vc and cur_m.get("md5") == md5
                   and cur_m.get("apk") == manifest["apk"])
        # App 的「下载电脑脚本」按钮指的就是这个文件：清单对上了、脚本没同步上去，
        # 用户点按钮拿到的是 404，而发布本身看上去是成功的。
        src_bat_chk = os.path.join(ROOT, "tools", "send_save.bat")
        dst_bat_chk = os.path.join(PUB, "tools", "send_save.bat")
        ok_bat = (os.path.exists(src_bat_chk) and os.path.exists(dst_bat_chk)
                  and open(src_bat_chk, "rb").read() == open(dst_bat_chk, "rb").read())
        # 永久直链：latest 与当前版包字节一致才算过（发版忘了覆盖 = 链接停在旧版，
        # 用户拿到旧包还以为拿到最新——与当年"假状态"同类，必须显式判）
        latest_chk = os.path.join(PUB, "apk", "stardew-assistant_latest.apk")
        ok_latest = (os.path.exists(latest_chk)
                     and hashlib.md5(open(latest_chk, "rb").read()).hexdigest() == md5)
        print("APK 在位且哈希一致: %s" % ("✓" if ok_apk else "✗"))
        print("version.json 与当前版本一致: %s" % ("✓" if ok_json else "✗"))
        print("tools/send_save.bat 与源码仓一致: %s" % ("✓" if ok_bat else "✗"))
        print("永久直链 latest.apk 与当前版一致: %s" % ("✓" if ok_latest else "✗"))
        sys.exit(0 if (ok_apk and ok_json and ok_bat and ok_latest) else 1)

    if not (os.path.exists(dst_apk) and hashlib.md5(open(dst_apk, "rb").read()).hexdigest() == md5):
        os.makedirs(os.path.dirname(dst_apk), exist_ok=True)
        shutil.copyfile(src_apk, dst_apk)
        print("已复制 APK -> apk/%s（%d B）" % (apk_name, len(data)))

    # 永久直链副本（2026-10-18 用户要"发版后仍然有效的下载链接"）：
    # apk/stardew-assistant_latest.apk 固定文件名，每次发版覆盖成最新包。
    # 群公告/收藏夹可以贴这个 URL 永不失效；App 内更新**不读它**（继续走
    # version.json 的带版本号路径 + md5 校验，链路一字未动）。
    # 字节相同就不重写——git 里相同 blob 的"修改"只会制造空提交噪音。
    dst_latest = os.path.join(PUB, "apk", "stardew-assistant_latest.apk")
    if not (os.path.exists(dst_latest) and hashlib.md5(open(dst_latest, "rb").read()).hexdigest() == md5):
        shutil.copyfile(src_apk, dst_latest)
        print("已更新永久直链 -> apk/stardew-assistant_latest.apk（%d B）" % len(data))
    else:
        print("永久直链已是最新包，跳过重写")
    io.open(os.path.join(PUB, "version.json"), "w", encoding="utf-8", newline="\n").write(want_json)
    print("已写 version.json（v%s / md5 %s）" % (vn, md5[:8]))

    # data/ 同步（2026-10-15）：整理数据随发布仓公开（CC BY-NC-SA 3.0 的「公开可得」）。
    # 每次发版把 assets/data 的 JSON 整体覆盖过去，保证远端与 App 包内一致。
    # LICENSE 从 res/raw 的 CC 文本拷（与 APK 内置那份同源）；README.md 不碰（手写说明）。
    src_data = os.path.join(ROOT, "app", "src", "main", "assets", "data")
    dst_data = os.path.join(PUB, "data")
    os.makedirs(dst_data, exist_ok=True)
    for f in os.listdir(dst_data):                       # 先清空：数据文件只增不减的假设会烂
        if f.endswith(".json"):
            os.remove(os.path.join(dst_data, f))
    n_data = 0
    for f in sorted(os.listdir(src_data)):
        if f.endswith(".json"):
            shutil.copyfile(os.path.join(src_data, f), os.path.join(dst_data, f))
            n_data += 1
    shutil.copyfile(os.path.join(ROOT, "app", "src", "main", "res", "raw", "cc_by_nc_sa_3_0.txt"),
                    os.path.join(dst_data, "LICENSE"))
    print("已同步 data/：%d 个 JSON + LICENSE" % n_data)

    # tools/ 同步（v2.68.0 / c110）。起因：电脑端推送脚本原先只发在 QQ 群，
    # 不在群里的用户压根拿不到，而 App 里已经有「下载 send_save.bat」按钮去拉这个地址——
    # 仓库里没有它，那个按钮就是个必然 404 的空承诺。
    #
    # ⚠ 这里**必须逐字节**复制，且发布仓的 .gitattributes 钉了 `*.bat -text`：
    #   两个仓的 core.autocrlf 都是 input，会把 CRLF 悄悄换成 LF；
    #   而 cmd.exe 读 LF-only 的批处理有已知的诡异失败（标签跳转 / 括号块），
    #   这段 bat 里两样都用到了。发一份换过行的脚本 = 发一个双击报错的假货。
    src_bat = os.path.join(ROOT, "tools", "send_save.bat")
    if not os.path.exists(src_bat):
        raise SystemExit("✗ 找不到 %s。App 的「下载电脑脚本」指向发布仓的 tools/send_save.bat，\n"
                         "  这里缺了，用户点按钮只会拿到 404。先跑 python tools/gen_send_save_bat.py。"
                         % src_bat)
    bat = open(src_bat, "rb").read()
    if any(b > 0x7F for b in bat):
        raise SystemExit("✗ send_save.bat 含非 ASCII 字节：cmd.exe 按系统 OEM 代码页读它，"
                         "UTF-8 的中文会被当乱码命令执行（这条已在 gen_send_save_bat.py 拦过一次，"
                         "这里防的是文件被别的工具改过）")
    if b"\r\n" not in bat or (bat.count(b"\n") - bat.count(b"\r\n")) != 0:
        raise SystemExit("✗ send_save.bat 不是纯 CRLF 行尾（CRLF %d / 裸 LF %d）。"
                         "cmd.exe 对 LF-only 批处理的标签与括号块有已知问题。"
                         % (bat.count(b"\r\n"), bat.count(b"\n") - bat.count(b"\r\n")))
    _bl = bat.split(b"\r\n")
    if max(len(x) for x in _bl) > 8000:
        raise SystemExit("✗ send_save.bat 有超长行（%d 字符），超过 cmd 的 8191 上限会被截断"
                         % max(len(x) for x in _bl))
    # ⚠ 判「有没有回到双文件形态」必须先剥 `rem` 注释行：bat 第 14 行就写着
    #   「edit send_send.ps1 and regenerate」——那是生成源的说明，不是它在找旁边的 ps1。
    #   naive 的 `b"send_save.ps1" in bat` 会把**正确的单文件脚本**判成旧版（第一版就这么写错了）。
    _bat_code = b"\r\n".join(ln for ln in bat.split(b"\r\n")
                             if not ln.strip().lower().startswith(b"rem"))
    if b"send_save.ps1" in _bat_code:
        raise SystemExit("✗ send_save.bat 的**可执行部分**还在用 send_save.ps1——那是 v2.67.3 之前的"
                         "双文件版本，群友已经因为它报过错。当前必须是单文件自包含版"
                         "（内嵌 base64 + certutil 还原到 %%TEMP%%）")
    if b"certutil" not in _bat_code:
        raise SystemExit("✗ send_save.bat 的可执行部分里没有 certutil——内嵌 PowerShell 的还原步骤不见了，"
                         "单文件形态不成立。重新跑 python tools/gen_send_save_bat.py")
    bat_md5 = hashlib.md5(bat).hexdigest()
    dst_bat = os.path.join(PUB, "tools", "send_save.bat")
    os.makedirs(os.path.dirname(dst_bat), exist_ok=True)
    if not (os.path.exists(dst_bat) and open(dst_bat, "rb").read() == bat):
        shutil.copyfile(src_bat, dst_bat)
        print("已复制电脑端脚本 -> tools/send_save.bat（%d B / md5 %s）" % (len(bat), bat_md5[:8]))
    else:
        print("tools/send_save.bat 已是最新（%d B / md5 %s）" % (len(bat), bat_md5[:8]))

    # tools/ 同步（c239）：电脑端**模组**包（存档自动推送）。
    #
    # ## 为什么它也要进发布仓
    #   App 里那个「下载电脑端模组」按钮读的是**安装包内**的 assets（不联网，
    #   理由见 UpdateRepo.ASSET_MOD：模组版本必须与 App 的协议版本对得上，
    #   从网络拉可能拿到落后于 App 的那份，用户会拿到一个必然被 403 的版本）。
    #   但**电脑上直接下载**这条路走的就是发布仓：用户复制链接到电脑浏览器，
    #   一点就下 —— 不用「手机下载 → 再传到电脑」。这两条路**并存**，缺一不可。
    #
    # ## 为什么 .zip 能直接用浏览器下
    #   实测（2026-11-09，gitee raw）：**.zip** 返回
    #     Content-Type: application/octet-stream
    #     Content-Disposition: attachment; filename="xxx.zip"
    #   ⇒ 浏览器**会落盘**。而 .bat 是 text/plain + inline ⇒ 浏览器当文本渲染、
    #   不落盘（v2.82.0 那次踩过这个坑，所以脚本才改走 irm -OutFile）。
    #   ⇒ 模组包放发布仓后，**电脑浏览器直接点链接就能下**，不用绕。
    #
    # ⚠ 源是 **App 的 assets 那一份**（app/src/main/assets/tools/save_auto_push.zip），
    #   不是 StardewAutoPush/bin/ 下的构建产物 —— 后者名字带版本号、
    #   每次构建都会变，而 App 内置的那份是入库的、稳定的。两份必须**逐字节一致**
    #   （verify 钉住），否则「手机下的」和「电脑下的」会是两个不同的模组版本。
    src_mod = os.path.join(ROOT, "app", "src", "main", "assets", "tools", "save_auto_push.zip")
    if not os.path.exists(src_mod):
        raise SystemExit("✗ 找不到 %s。\n"
                         "  App 的「下载电脑端模组」读的是它，发布仓的直链也指它 —— 两边缺一，"
                         "用户点链接就是 404。\n"
                         "  先到 StardewAutoPush/ 跑 dotnet build（会重新产出 zip），"
                         "再把它复制到那个位置。" % src_mod)
    mod = open(src_mod, "rb").read()
    # ⚠ zip 的硬不变量：头两个字节必须是 PK。拿到的不是它（文件被换过/损了），
    #   用户在电脑上解压会得到一个坏包，却看不出是哪一步坏的。
    if len(mod) < 4 or mod[0:2] != b"PK":
        raise SystemExit("✗ save_auto_push.zip 的头部不是 PK（%r）——它不是个 zip，"
                         "用户在电脑上会解不开" % mod[:4])
    import zipfile
    with zipfile.ZipFile(__import__("io").BytesIO(mod)) as _zf:
        _names = _zf.namelist()
    # 顶层必须是 StardewAutoPush/ —— SMAPI 装模组是解压到 Mods/<名字>/，
    #   顶层不对的话用户得自己挪目录。
    if not any(n.startswith("StardewAutoPush/") for n in _names):
        raise SystemExit("✗ save_auto_push.zip 顶层不是 StardewAutoPush/（实得 %s）——"
                         "用户在电脑上解压后还得自己挪目录" % _names[:3])
    mod_md5 = hashlib.md5(mod).hexdigest()
    dst_mod = os.path.join(PUB, "tools", "save_auto_push.zip")
    os.makedirs(os.path.dirname(dst_mod), exist_ok=True)
    if not (os.path.exists(dst_mod) and open(dst_mod, "rb").read() == mod):
        shutil.copyfile(src_mod, dst_mod)
        print("已复制电脑端模组 -> tools/save_auto_push.zip（%d B / md5 %s）"
              % (len(mod), mod_md5[:8]))
    else:
        print("tools/save_auto_push.zip 已是最新（%d B / md5 %s）" % (len(mod), mod_md5[:8]))

    # 口径（2026-10-15）：发布仓只留最新包，旧版删掉——全量在本地归档目录。
    # 口径（2026-10-18）：永久直链 latest.apk **不在此删除**——它就是"永远指向最新版"
    #   的那个文件，每次发版被上面覆盖更新；这里把它当旧包删掉，链接就 404 了。
    keep = os.path.normpath(dst_apk)
    keep_latest = os.path.normpath(dst_latest)
    old = []
    for r, _d, fs in os.walk(os.path.join(PUB, "apk")):
        for f in fs:
            p = os.path.normpath(os.path.join(r, f))
            if p != keep and p != keep_latest:
                old.append(p)
    for p in old:
        os.remove(p)
        git(PUB, "rm", "--cached", "--quiet", os.path.relpath(p, PUB).replace("\\", "/"))
        print("已删旧包：%s" % os.path.basename(p))

    r = git(PUB, "add", "version.json", "apk/" + apk_name, "apk/stardew-assistant_latest.apk",
            "data/", "tools/", ".gitattributes")
    if r.returncode != 0:
        raise SystemExit("✗ git add 失败：%s" % r.stderr.strip())
    r = git(PUB, "-c", "user.name=hututu00", "-c", "user.email=hututu00@example.com",
            "commit", "-m", "发布 v%s (c%d)" % (vn, vc), "--allow-empty")
    print("commit: %s" % (r.stdout.strip().splitlines() or [r.stderr.strip()])[0])

    if do_push:
        r = git(PUB, "push", "origin", "master")
        if r.returncode == 0:
            print("✓ 已推送 gitee")
        else:
            print("✗ push 失败（多半是没配凭据）。手动推送：\n"
                  "    cd %s\n    git push origin master\n"
                  "  首次会要求 gitee 用户名+私人令牌（令牌勾 projects 权限即可）。" % PUB)
            print("  git 输出：%s" % (r.stderr.strip()[:300] or r.stdout.strip()[:300]))
            sys.exit(1)
    else:
        print("（未 push；确认无误后加 --push 或手动推）")


if __name__ == "__main__":
    main()
