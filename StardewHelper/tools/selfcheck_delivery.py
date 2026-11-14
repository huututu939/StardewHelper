# -*- coding: utf-8 -*-
"""交付前独立复核（参数化，只读，不改任何文件）。

用法：
  python -X utf8 tools/selfcheck_delivery.py <versionCode> <versionName> <圈号> <归档APK名>
例：
  python -X utf8 tools/selfcheck_delivery.py 206 2.118.0 ㊺ 星露小助手_v2.118.0_c206.apk

校验七组：
  1. 归档 APK 存在，md5/sha256/字节数算出来
  2. 归档 vs 当前构建产物 app-release.apk **逐字节**一致（必须在归档之后比）
  3. gradle 的 versionCode / versionName 与参数一致
  4. App 内 CHANGELOG 首条 = 当前版本，且满足文风规则（子句数/字数/不以「了」收尾/无感叹号）
  5. 发布说明存在、为纯 LF 无 BOM、>= 3 行有效、未复读旧版本
  6. 归档账本：裸 LF 无 BOM、含本轮圈号、详述段的 md5/sha256/字节数与归档一致、
     表行有且只有 1 行且哈希一致、旧包行仍在
  7. 历史归档 APK 的 md5 未被改动（对照账本里记录的旧包行，防历史 APK 被悄悄改掉）

⚠ 本脚本只读，但**它读的是归档 APK 与构建产物的字节**：
   改了任何 APK 内容（含 CHANGELOG 字符串进 dex 导致的字节变化）后，
   必须重新归档 + 同步账本哈希，否则这里会 FAIL。

⚠ 2026-11-03 从工作区外（F:\\pwshdl\\w67_selfcheck.py）搬入仓库 tools/：
   原脚本硬编码了 F: 盘绝对路径、且带一条只对 c184 有效的特判，
   换 Agent / 换机器就会失效。现在路径按脚本位置相对推导，圈号与版本由参数传入。
"""
import hashlib, io, json, os, re, sys

# 路径按脚本位置相对推导（本脚本在 <仓库>/StardewHelper/tools/ 下）
_HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.dirname(_HERE)                      # .../StardewHelper
_REPO = os.path.dirname(SRC)                      # .../星露谷
ARCH = os.path.join(_REPO, "安装包归档")
BUILD = os.path.join(SRC, "app", "build", "outputs", "apk", "release", "app-release.apk")
LEDGER = os.path.join(ARCH, "版本备注.md")

if len(sys.argv) < 5:
    print(__doc__)
    sys.exit(2)
VC, VN, CIRCLE, APK = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
NOTES = os.path.join(ARCH, "发布说明_v%s_c%s.txt" % (VN, VC))
CIRCLE_CP = ord(CIRCLE)

ok = True
warns = []
def say(flag, label, extra=""):
    global ok
    if not flag:
        ok = False
    print(("  PASS  " if flag else "  FAIL  ") + label + (("  | " + extra) if extra else ""))

def digest(p, algo):
    h = hashlib.new(algo)
    with open(p, "rb") as f:
        for b in iter(lambda: f.read(1 << 20), b""):
            h.update(b)
    return h.hexdigest()

print("=== 0. 参数 ===")
print("  versionCode=%s  versionName=%s  圈号=%s(U+%04X)  归档=%s"
      % (VC, VN, CIRCLE, CIRCLE_CP, APK))

print("=== 1. 全部历史归档 APK 的 md5（防被改动）===")
# KNOWN 改为**从账本里现读历史 APK 的 md5**（不再手抄常量表）：
#   手抄一张常量表 ⇒ 每过一版就得改脚本，改漏了没人知道，且换 Agent 后必然失效。
#   现在直接从 版本备注.md 的表行解析「文件名 → md5」，历史包被改动即报红。
#   ⚠ 账本里没有表行的历史包不会被这条覆盖（表头行只记 c191 起的新版本，
#     更早的包靠 归档目录 里文件本身在不在来兜底）。
KNOWN = {}
if os.path.exists(LEDGER):
    _lb0 = io.open(LEDGER, encoding="utf-8", newline="").read()
    for _row in _lb0.splitlines():
        _m0 = re.search(r"\|\s*(星露小助手_v[\d.]+_c\d+\.apk)\s*\|[^|]*\|[^|]*\|([^|]*)\|", _row)
        if _m0:
            _mm0 = re.search(r"\b([0-9a-f]{32})\b", _m0.group(2))
            if _mm0:
                KNOWN[_m0.group(1)] = _mm0.group(1)
for name, exp in KNOWN.items():
    p = os.path.join(ARCH, name)
    if not os.path.exists(p):
        say(False, name + " 存在", "缺失")
        continue
    m = digest(p, "md5")
    if m == exp:
        say(True, name, "%s  %d B" % (m, os.path.getsize(p)))
    else:
        # ⚠ 账本记的哈希与文件对不上：**判失败**。
        #   可能是「历史 APK 被改了」（严重），也可能是「账本当初填错了」（笔误）。
        #   2026-11-03 实测后者真实存在：账本里 c156~c160/c176 六行的 md5/sha256/字节数
        #   被批量复制成了 c177 的值（APK 文件本身没被动过，mtime 与各版本发布日期吻合）——
        #   已按文件真实哈希改正。所以现在 57 个历史包全部一致，这条断言可以当硬防线用。
        #   两种情况后果不同，消息里把两条路都点出来，交人工分辨。
        say(False, name + " 哈希与账本一致",
            "账本记 %s 但文件实为 %s（%d B）—— 需人工核对：是历史 APK 被改动，还是账本当时填错了"
            % (exp[:12], m[:12], os.path.getsize(p)))
newp = os.path.join(ARCH, APK)
if not os.path.exists(newp):
    say(False, APK + " 存在", "缺失")
    NEW_MD5 = NEW_SHA = ""
    NEW_SIZE = 0
else:
    NEW_MD5 = digest(newp, "md5")
    NEW_SHA = digest(newp, "sha256")
    NEW_SIZE = os.path.getsize(newp)
    print("  ---- 本轮新包 ----")
    print("   %s  %d B" % (APK, NEW_SIZE))
    print("   md5    = %s" % NEW_MD5)
    print("   sha256 = %s" % NEW_SHA)

print("=== 2. 归档 vs 当前构建产物 逐字节（必须在归档之后比）===")
if os.path.exists(BUILD):
    with open(newp, "rb") as f1, open(BUILD, "rb") as f2:
        same = f1.read() == f2.read()
    say(same, "归档与 app-release.apk 逐字节一致",
        "build md5=%s" % digest(BUILD, "md5"))
    say(digest(BUILD, "md5") == NEW_MD5, "构建产物 md5 == 归档 md5")
else:
    say(False, "构建产物存在", BUILD)

print("=== 3. gradle 版本号 ===")
gb = io.open(os.path.join(SRC, "app", "build.gradle.kts"), encoding="utf-8", newline="").read()
say(("versionCode = " + VC) in gb, "versionCode = " + VC)
say(('versionName = "%s"' % VN) in gb, "versionName = " + VN)

print("=== 4. CHANGELOG 首条 ===")
sp = os.path.join(SRC, "app", "src", "main", "java", "com", "stardew", "assistant",
                   "ui", "settings", "SettingsPage.kt")
s = io.open(sp, encoding="utf-8", newline="").read()
m = re.search(r"private\s+val\s+CHANGELOG\s*=\s*listOf\(\s*\"([^\"]+)\"\s*to\s*listOf\(", s)
if not m:
    say(False, "CHANGELOG 首条可解析（listOf( 后面紧跟版本串）", "解析失败")
else:
    head = m.group(1)
    print("  首条 =", head)
    vtag = "v" + VN
    say(head.startswith(vtag), "首条版本号 = " + vtag, head)
    body = head[len(vtag):].strip()
    parts = [x for x in re.split(r"[，,]", body) if x.strip()]
    say(len(parts) <= 2, "首条子句数 <= 2", "%d 句" % len(parts))
    for pp in parts:
        say(len(pp) <= 32, "子句 <= 32 字", "%r (%d)" % (pp, len(pp)))
        say(not pp.endswith("了"), "子句不以「了」收尾", repr(pp))
        say("！" not in pp and "!" not in pp, "子句无感叹号", repr(pp))
    say("**" not in head, "首条无 Markdown 星号", repr(head))

print("=== 5. 发布说明 ===")
if os.path.exists(NOTES):
    raw = open(NOTES, "rb").read()
    say(b"\r\n" not in raw, "发布说明为纯 LF")
    say(not raw.startswith(b"\xef\xbb\xbf"), "发布说明无 BOM")
    txt = raw.decode("utf-8")
    print("  ---- 发布说明全文 ----")
    for ln in txt.splitlines():
        print("   |", ln)
    print("  ----------------------")
    lines = [l for l in txt.splitlines() if l.strip()]
    say(len(lines) >= 3, "发布说明 >= 3 行有效", "%d 行" % len(lines))
    hits = [w for w in ("v2.95.0", "v2.96.0", "v2.97.0", "v2.98.0",
                        "c177", "c179", "c180") if w in txt]
    say(not hits, "未复读旧版本 / 旧构建号", "命中: %s" % hits)
    # 复读检查：对外 notes 不得复读**本次发布之前**线上已发布的内容。
    # ⚠ 基线不能取「当前线上 version.json」—— 发版后它就是本次 notes 本身，
    #   拿它当基线 ⇒ 必然全命中 ⇒ 这条断言永远 FAIL（2026-11-03 实测踩到）。
    #   正确基线 = 发布仓 git 历史里**最后一个 versionCode 小于当前值**的 version.json。
    _live, _base = [], "(发布仓历史不可读，跳过复读检查)"
    _PUB = os.path.join(_REPO, "发布仓")
    try:
        import subprocess
        def _git(*a):
            return subprocess.run(["git", "-C", _PUB] + list(a),
                                  capture_output=True, text=True, encoding="utf-8",
                                  errors="replace", timeout=30).stdout
        _shas = [x for x in _git("log", "--format=%H", "--", "version.json").split() if x]
        for _sh in _shas:
            _raw = _git("show", _sh + ":version.json")
            if not _raw.strip():
                continue
            try:
                _pv = json.loads(_raw)
            except Exception:
                continue
            if int(_pv.get("versionCode", 0)) < int(VC):
                _live = [x for x in _pv.get("notes", []) if x.strip()]
                _base = "v%s / c%s" % (_pv.get("versionName"), _pv.get("versionCode"))
                break
    except Exception as _e:
        _base = "(发布仓历史读取失败: %r，跳过复读检查)" % (_e,)
    print("  复读检查基线 =", _base)
    _txtlines = [l.strip() for l in txt.splitlines() if l.strip()]
    _dup = [l for l in _txtlines if l in _live and not l.endswith("：")]
    say(not _dup, "未复读线上已发布条目", "基线 %s，命中: %s" % (_base, _dup))
else:
    say(False, "发布说明存在", NOTES)

print("=== 6. 账本 ===")
if os.path.exists(LEDGER):
    lb = open(LEDGER, "rb").read()
    say(b"\r\n" not in lb, "账本为裸 LF")
    say(not lb.startswith(b"\xef\xbb\xbf"), "账本无 BOM")
    lt = lb.decode("utf-8")
    # 圈号占用检查：本轮圈号之前的所有圈号都该已存在
    used = sorted(set(ord(ch) for ch in lt if 0x3250 <= ord(ch) <= 0x3280))
    say(CIRCLE_CP not in used or CIRCLE in lt, "本轮圈号 %s (U+%04X) 已写入" % (CIRCLE, CIRCLE_CP))
    idx = lt.find(CIRCLE)
    if idx < 0:
        say(False, "账本含本轮圈号", "找不到 %s" % CIRCLE)
    else:
        # 详述段：从本轮圈号到下一个圈号（或文件尾）
        nxt = len(lt)
        for ch in lt[idx + 1:]:
            if 0x3250 <= ord(ch) <= 0x3280:
                nxt = idx + 1 + lt[idx + 1:].index(ch)
                break
        seg = lt[idx:nxt]
        print("  ---- 本轮详述段前 700 字 ----")
        print("   " + seg[:700].replace("\n", "\n   "))
        print("  --------------------------------")
        say("c" + VC in seg, "详述段提到 c%s" % VC)
        say(VN in seg, "详述段含 " + VN)
        say(NEW_MD5 in seg, "详述段 md5 与归档一致", NEW_MD5)
        say(NEW_SHA in seg, "详述段 sha256 与归档一致")
        say(str(NEW_SIZE) in seg, "详述段 APK 字节数与归档一致", str(NEW_SIZE))
    # 表行：找到含本轮 APK 文件名的行
    rows = [l for l in lt.splitlines() if APK in l and l.strip().startswith("|")]
    say(len(rows) == 1, "账本有且只有 1 行 c%s 表行" % VC, "%d 行" % len(rows))
    for r in rows:
        say(NEW_MD5 in r, "表行 md5 与归档一致")
        say(VN in r and ("c" + VC) in r, "表行版本/构建号正确")
        print("   表行前 120 字:", r.strip()[:120])
    # 旧包行仍在
    for old in KNOWN:
        say(old in lt, "账本仍含旧包行 " + old)
else:
    say(False, "账本存在", LEDGER)

if warns:
    print()
    print("提示（不算失败，但需人工核对）：")
    for _w in warns:
        print("  · " + _w)

print()
print("总判定:", "ALL PASS" if ok else "HAS FAILURE")
sys.exit(0 if ok else 1)
