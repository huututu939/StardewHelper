# -*- coding: utf-8 -*-
"""
发布后冒烟：站在**匿名使用者**的立场，验一遍 gitee 发布仓上真正能拿到的东西。

用法（在 StardewHelper/ 目录下）：
  python tools/smoke_publish.py              # 校验远端发布仓（期望远端 == 本地构建版本）
  python tools/smoke_publish.py --expect 109 # 撤回发布后：远端**就应该**停在 c109
  python tools/smoke_publish.py --base https://gitee.com/xxx/yyy/raw/master/
  python tools/smoke_publish.py --tries 8 --gap 4
  python tools/smoke_publish.py --help       # 只打印用法

## 为什么有 --expect：撤回发布时「落后」是预期，不是失败

2026-10-17 撤回 c110（用户只要求上传脚本，没要求发版）之后，远端清单**故意**停在 c109，
而本地 gradle 已经是 c110。默认口径（期望远端 == 本地）会把这种状态报成失败——
那会诱导人去「重推一次」，而重推正好把刚撤回的 APK 又发上去了。
所以撤回后必须 `--expect <旧构建号>` 跑，让脚本知道期望的是什么。

**B 组（与本地构建产物三方一致）只在期望版本 == 本地版本时才成立**，
撤回场景下它自动跳过，A 组（远端自洽）照旧全查。

## 为什么必须有这个脚本：gitee raw 的「过期 200」

发完版不验，等于没发。但**重试逻辑写错比不验更坏**——它会把「还没同步好」
判成「发布失败」，也会把「旧内容还挂在缓存上」判成「发布成功」。

2026-10-15 发 c96 时踩过：重试条件写成 `if r.status == 200: break`，
结果第一次 GET 拿到的就是 gitee raw 缓存里**上一版**的 version.json，
脚本当场判绿，而远端其实还没同步完。**只看 HTTP 状态码的重试是假绿。**

所以本脚本的核心约定写在下面这个函数里：

    重试的终止条件永远是「**内容**符合预期」，绝不是「HTTP 200」。

`fetch_verified()` 强制要求调用方传一个 `validate(body) -> bool`，
语义是「这份内容是不是我要的那一份」。拿不到就是拿不到，重来。

## 校验分两组，这是有意的

**A 组「远端自洽」**——远端此刻实际部署的东西自身是否一致。
    任何时候都该成立，与本地构建进度无关。

**B 组「与本地一致」**——远端是不是本地刚构建出来的这一版。
    只在「远端 versionCode == 本地 gradle versionCode」时才成立；
    否则报「远端落后/超前源码」并跳过，**不算失败**——构建完还没 push
    是正常状态，不该让冒烟报红（口径与 verify 49.5 的 warn 一致）。

## 覆盖的五件事

  ① 清单：字段自洽 + 文件名纯 ASCII（中文名进 URL 要靠各客户端自觉编码，
     严格客户端直接抛 UnicodeEncodeError——c88 踩过）
  ② 产物：清单 md5/size ↔ 实际下载的字节，三方一致
  ③ 旧包：发布仓口径是**只留最新版**，历史 APK 必须全部 404
     （历史包枚举自发布仓 git 历史，不写死任何文件名）
  ④ data/：整理数据必须公开可得（CC BY-NC-SA 3.0 的 SA 义务）。
     用 gitee 匿名 contents API 列目录核对**文件数**，比逐个探文件强。
  ⑤ tools/send_save.bat：App 里「下载电脑脚本」按钮的唯一目标（c110 起）。
     它必须与源码仓那份**逐字节一致**——发布仓的 autocrlf 曾把 CRLF 换成 LF，
     换行符一变，用户双击就是另一个报错。

退出码 0 = 全过，1 = 有失败项。
"""
import hashlib
import io
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)

# ⚠ 版本号、发布仓路径、构建产物路径**只从 publish_gitee 取**，本文件不再自己解析一遍。
#   起因：初版这里照抄了 `versionName\s*="([^"]+)"`（`=` 后少一个 `\s*`），
#   而 build.gradle.kts 写的是 `versionName = "2.62.0"`——`=` 与 `"` 之间有空格，
#   直接 AttributeError 崩掉。publish_gitee 那份是对的，所以**只有第三份是错的**，
#   而第三份正是「判定口径散落多处必然漂移」（v2.27 老坑）的一次当场兑现：
#   同一段正则复制三份，第三份当场就抄错了。verify 49.5 里还有第四份
#   （`versionName = "..."` 写死单空格）——只是碰巧还能用。
from publish_gitee import APK_BUILD, PUB, gradle_version   # noqa: E402

SRC_DATA = os.path.join(HERE, os.pardir, "app", "src", "main", "assets", "data")

DEFAULT_BASE = "https://gitee.com/hututu00/xingluguzhushou/raw/master/"
UA = {"User-Agent": "stardew-helper-smoke/1.0 (anonymous)"}
TIMEOUT = 30


class Result(object):
    """收集勾/叉，最后统一打印。失败项必须带上「怎么发现的」，不然等于没报。"""

    def __init__(self):
        self.rows = []
        self.failed = []
        self.notes = []

    def ok(self, title, detail=""):
        self.rows.append((True, title, detail))

    def fail(self, title, detail=""):
        self.rows.append((False, title, detail))
        self.failed.append(title)

    def note(self, text):
        self.notes.append(text)

    def print_all(self):
        for good, title, detail in self.rows:
            print("  %s %s%s" % ("PASS" if good else "FAIL", title,
                                ("  —— " + detail) if detail else ""))
        for n in self.notes:
            print("  ..   " + n)
        print()
        if self.failed:
            print("FAIL 冒烟未过（%d 项）：%s" % (len(self.failed), "；".join(self.failed)))
            return 1
        print("PASS 冒烟全过（%d 项）" % len(self.rows))
        return 0


def http_get(url, tries=3, gap=3.0):
    """裸 GET，只为「拿一份字节」用。**任何需要判断内容对错的地方都不许直接用它的返回值。**"""
    last = None
    for i in range(tries):
        try:
            req = urllib.request.Request(url, headers=UA)
            with urllib.request.urlopen(req, timeout=TIMEOUT) as r:
                return r.read(), r.status
        except urllib.error.HTTPError as e:
            if e.code == 404:            # 404 是确定答案，重试没有意义
                return b"", 404
            last = e.code
        except Exception as e:           # noqa: BLE001
            last = repr(e)
        if i < tries - 1:
            time.sleep(gap)
    return b"", last


def fetch_verified(url, validate, tries=6, gap=3.0, what=""):
    """重试到 **内容** 满足 validate 为止。

    这是本脚本存在的理由，也是最容易被写坏的一行代码：
    把 `if body: break` 或 `if status == 200: break` 写在这里，
    等于把 gitee raw 缓存的过期内容当成真相——c96 就是这么假绿的。

    validate 收**字节**、返 bool：「这份内容是不是我要的那一份」。
    返回 (body, status)；tries 用尽仍不满足则返回最后一次拿到的内容。
    """
    body, st = b"", None
    for i in range(tries):
        body, st = http_get(url, tries=1)
        if st == 200 and validate(body):
            return body, st
        if i < tries - 1:
            time.sleep(gap)
    if tries > 1 and what:
        print("  ..   「%s」重试 %d 次仍未拿到预期内容，最后一次 HTTP %s" % (what, tries, st))
    return body, st


def parse_args(argv):
    opts = {"help": False, "tries": 6, "gap": 3.0, "base": DEFAULT_BASE, "expect": None}
    i = 0
    while i < len(argv):
        a = argv[i]
        if a in ("--help", "-h"):
            opts["help"] = True
        elif a == "--base":
            if i + 1 >= len(argv):
                raise SystemExit("ERROR --base 缺参数：后面要给 raw 基址 URL")
            opts["base"] = argv[i + 1].rstrip("/") + "/"
            i += 1
        elif a == "--expect":
            # 撤回发布后要显式说「远端就应该停在旧版」，否则本脚本会去等一个
            # 永远不会出现的版本号（并把「落后」误报成失败）。
            if i + 1 >= len(argv):
                raise SystemExit("ERROR --expect 缺参数：给期望的 versionCode，如 --expect 109")
            opts["expect"] = int(argv[i + 1])
            i += 1
        elif a == "--tries":
            if i + 1 >= len(argv):
                raise SystemExit("ERROR --tries 缺参数")
            opts["tries"] = int(argv[i + 1])
            i += 1
        elif a == "--gap":
            if i + 1 >= len(argv):
                raise SystemExit("ERROR --gap 缺参数")
            opts["gap"] = float(argv[i + 1])
            i += 1
        else:
            raise SystemExit("ERROR 未知参数：%s（用 --help 看用法）" % a)
        i += 1
    return opts


def ever_committed_apks(repo):
    """枚举发布仓 git 历史里**曾经**提交过的所有 apk/ 文件名。

    不写死任何包名——旧包清单随版本变，写死就等于给后人留一个「改了就红」的雷。
    用 git 历史是唯一能自动覆盖历次发版的方式。

    ⚠ 必须带 `--no-renames`（v2.68.0 实测修的真漏洞）。发一次新版在 git 里是
      「删掉旧 apk + 加上新 apk」，而 git 默认的重命名检测会把这一对**折叠成一次 R**
      （APK 有 70%+ 字节相同，很容易过阈值）。`--diff-filter=A` 于是认不出「新增」，
      历史包清单就只剩最早那一个——实测 c110 撤回后跑冒烟，③ 只报了 c89 一个包，
      **刚撤回的 c110 根本没进检查名单**，而它照样打 PASS。
      一条只查 1/10 的断言，比没查更容易骗人。
    """
    names = set()
    if not os.path.isdir(os.path.join(repo, ".git")):
        return names
    r = subprocess.run(
        ["git", "-C", repo, "log", "--pretty=format:", "--name-only",
         "--diff-filter=A", "--no-renames", "--", "apk/"],
        capture_output=True, text=True, encoding="utf-8", errors="replace")
    for line in (r.stdout or "").splitlines():
        line = line.strip()
        if line.startswith("apk/") and line.endswith(".apk"):
            names.add(os.path.basename(line))
    cur = os.path.join(repo, "apk")
    if os.path.isdir(cur):
        for f in os.listdir(cur):
            if f.endswith(".apk"):
                names.add(f)
    return names


def local_data_names():
    if not os.path.isdir(SRC_DATA):
        return None
    return sorted(f for f in os.listdir(SRC_DATA) if f.endswith(".json"))


def main():
    opts = parse_args(sys.argv[1:])
    if opts["help"]:
        print(io.open(os.path.abspath(__file__), encoding="utf-8").read())
        return 0

    tries, gap, base = opts["tries"], opts["gap"], opts["base"]
    r = Result()
    gvc, gvn = gradle_version()
    print("冒烟目标：%s" % base)
    print("本地构建：v%s (c%d)" % (gvn, gvc))
    print()

    # ---------- ① 清单：重试到「内容说的是期望版本」为止 ----------
    # ⚠ v2.68.0 修掉一处**假绿**：① 的标题一直写着「且指向本地当前版本」，但代码里
    #   从来没判过——fetch_verified 重试耗尽后照样 r.ok()，只补一行 note。
    #   于是「推送了但缓存还是旧版」这种情况，整份冒烟可以**全绿收工**
    #   （②B/⑤B 这些真正会红的项都以 same_version 为前提，被一起跳过了）。
    #   收工标准是「冒烟全绿」，那全绿就必须真的意味着远端是我们要的那一版。
    want_vc = opts["expect"] if opts["expect"] is not None else gvc
    if opts["expect"] is not None and opts["expect"] != gvc:
        r.note("期望版本 c%s（--expect），本地构建是 c%s —— 远端落后是本次的**预期**"
               % (want_vc, gvc))

    def _vc_is_target(body):
        try:
            return json.loads(body.decode("utf-8")).get("versionCode") == want_vc
        except Exception:
            return False

    body, st = fetch_verified(base + "version.json", _vc_is_target, tries, gap, "version.json")
    try:
        man = json.loads(body.decode("utf-8"))
    except Exception:
        r.fail("① 清单可解析", "HTTP %s，内容不是合法 JSON" % st)
        return r.print_all()

    rvc = man.get("versionCode")
    r.ok("① 清单可读且指向期望版本 c%s" % want_vc,
         "versionCode=%s versionName=%s（HTTP %s）" % (rvc, man.get("versionName"), st)) \
        if rvc == want_vc else r.fail(
        "① 清单可读且指向期望版本 c%s" % want_vc,
        "远端是 c%s（HTTP %s）——未发布 / raw 缓存未过期 / 撤回未生效，**别重推，等一会儿再跑**"
        % (rvc, st))
    # 文件名里的构建号必须与期望版本一致（原实现按本地 gradle 现算，撤回后会误报）
    r.ok("① 清单 apk 文件名与期望版本对得上",
         man.get("apk")) if str(man.get("apk", "")).endswith("_c%s.apk" % want_vc) \
        else r.fail("① 清单 apk 文件名与期望版本对得上",
                    "清单 %r，期望文件名以 _c%s.apk 结尾" % (man.get("apk"), want_vc))

    notes = man.get("notes") or []
    r.ok("① 清单 notes 非空且每条非空",
         "%d 条%s" % (len(notes), "" if all(str(n).strip() for n in notes) else "（有空串！）"))
    r.ok("① 清单 apk 文件名纯 ASCII",
         "%r" % man.get("apk")) if str(man.get("apk", "")).isascii() \
        else r.fail("① 清单 apk 文件名纯 ASCII", "含非 ASCII：%r" % man.get("apk"))

    # ---------- B 组：与本地构建比对（版本对得上才成立） ----------
    same_version = (rvc == gvc)
    if not os.path.exists(APK_BUILD):
        r.note("本地没有构建产物 %s，跳过「与本地一致」一组比对" % APK_BUILD)
        same_version = False
    if not same_version:
        r.note("远端是 c%s、本地是 c%d —— 远端落后/超前源码，以下只做「远端自洽」比对"
               % (rvc, gvc))

    # ---------- ② 产物：清单 ↔ 实际下载 ----------
    apk_rel = man.get("apk") or ""
    dl, st2 = http_get(base + apk_rel)
    if st2 != 200 or not dl:
        r.fail("② 清单指向的 APK 可下载", "HTTP %s" % st2)
    else:
        dl_md5 = hashlib.md5(dl).hexdigest()
        r.ok("② APK 可下载",
             "%d B / md5 %s" % (len(dl), dl_md5[:8]))
        r.ok("② 下载字节 ↔ 清单 size 一致",
             "%d" % len(dl)) if len(dl) == man.get("size") \
            else r.fail("② 下载字节 ↔ 清单 size 一致",
                        "下载 %d，清单 %s" % (len(dl), man.get("size")))
        r.ok("② 下载字节 ↔ 清单 md5 一致",
             dl_md5[:8]) if dl_md5 == man.get("md5") \
            else r.fail("② 下载字节 ↔ 清单 md5 一致",
                        "下载 %s，清单 %s" % (dl_md5, man.get("md5")))
        if same_version:
            loc = open(APK_BUILD, "rb").read()
            loc_md5 = hashlib.md5(loc).hexdigest()
            r.ok("②B 清单 md5 ↔ 本地构建产物 md5 一致（三方一致）",
                 loc_md5[:8]) if (dl_md5 == loc_md5 == man.get("md5")) \
                else r.fail("②B 清单 md5 ↔ 本地构建产物 md5 一致（三方一致）",
                            "本地 %s / 清单 %s / 下载 %s"
                            % (loc_md5[:8], str(man.get("md5"))[:8], dl_md5[:8]))

    # ---------- ②C 永久直链：latest.apk 必须存在且与清单同一份包 ----------
    # 固定名链接（群公告/收藏夹用的就是这个）——发版覆盖 latest，链接永不失效。
    # 重试到「内容 md5 == 清单 md5」：gitee raw 对**被覆盖的文件**有几分钟缓存，
    # 刚发完版直接 http_get 可能拿到上一版；只看 200 就是 c96 假绿的同型错误。
    # 版本闸门（same_version）在这里不适用：latest 的定义就是"跟清单一致"，
    # 远端清单说什么版本，latest 就该是什么字节——落后同样是坏。
    LATEST_REL = "apk/stardew-assistant_latest.apk"
    want_md5 = man.get("md5") or ""
    body_l, st_l = fetch_verified(base + LATEST_REL,
                                  lambda b: hashlib.md5(b).hexdigest() == want_md5,
                                  tries=12, gap=8.0, what=LATEST_REL)
    if st_l == 404:
        r.fail("②C 永久直链 latest.apk 与清单同一份包", "404（发布脚本没生成 latest？）")
    elif st_l != 200 or not body_l:
        r.fail("②C 永久直链 latest.apk 与清单同一份包", "HTTP %s" % st_l)
    else:
        got = hashlib.md5(body_l).hexdigest()
        if got == want_md5:
            r.ok("②C 永久直链 latest.apk 与清单同一份包",
                 "%d B / md5 %s" % (len(body_l), got[:8]))
        else:
            # 重试耗尽仍是旧内容：缓存没过期或 latest 没被覆盖——两种都要红
            r.fail("②C 永久直链 latest.apk 与清单同一份包",
                   "重试后仍是 md5 %s（清单 %s）——raw 缓存未过期或 latest 未随发版更新"
                   % (got[:8], str(want_md5)[:8]))

    # ---------- ③ 旧包必须 404（发布仓口径：只留最新版） ----------
    all_apks = ever_committed_apks(PUB)
    # 永久直链不参与"历史包下架"判定：它按设计永远在架、永远等于最新版
    stale = sorted(a for a in all_apks
                   if a != os.path.basename(apk_rel) and a != "stardew-assistant_latest.apk")
    if not stale:
        r.ok("③ 无历史包需要清理", "git 历史里只出现过当前这一个包名")
    else:
        alive = []
        for a in stale:
            _b, s = http_get(base + "apk/" + a, tries=1)
            if s == 200:
                alive.append(a)
        if alive:
            r.fail("③ 历史包已下架（发布仓只留最新版）",
                    "仍可访问：%s" % "、".join(alive))
        else:
            r.ok("③ 历史包已下架（发布仓只留最新版）",
                 "%d 个历史包全部 404：%s" % (len(stale), "、".join(stale)))

    # ---------- ④ data/ 必须公开可得 ----------
    want_data = local_data_names()
    m = re.match(r"https?://gitee\.com/([^/]+)/([^/]+)/raw/", base)
    api = "https://gitee.com/api/v5/repos/%s/%s/contents/" % (m.group(1), m.group(2)) if m else None
    listed = None
    entries = None
    if api and want_data is not None:
        b, s = http_get(api + "data", tries=2, gap=2.0)
        try:
            entries = json.loads(b.decode("utf-8"))
            listed = sorted(e["name"] for e in entries if e.get("name", "").endswith(".json"))
        except Exception:
            entries = listed = None
    if listed is not None:
        miss = sorted(set(want_data) - set(listed))
        r.ok("④ data/ 的 JSON 与 assets/data 一一对应",
             "%d 个" % len(listed)) if not miss else r.fail(
            "④ data/ 的 JSON 与 assets/data 一一对应",
            "远端缺 %d 个：%s" % (len(miss), "、".join(miss[:5])))
        names = [e["name"] for e in entries]
        for extra in ("LICENSE", "README.md"):
            r.ok("④ data/%s 在列" % extra) if extra in names else r.fail(
                "④ data/%s 在列" % extra, "CC BY-NC-SA 的协议全文与说明必须随数据一起公开")
    else:
        # API 不可用（限流/被墙）时降级为探针：**只降级检查强度，不放过「整个 data/ 取不到」**
        got = 0
        for probe in (["LICENSE"] + (want_data[:1] + want_data[-1:] if want_data else [])):
            b, s = http_get(base + "data/" + probe, tries=1)
            if s == 200 and b:
                got += 1
        if got == len(1 + (2 if want_data else 0)):
            r.ok("④ data/ 可访问（API 不可用，只做了抽样探针）", "抽样 %d 项均 200" % got)
        else:
            r.fail("④ data/ 可访问", "%d/%d 项 200" % (got, 1 + (2 if want_data else 0)))

    # ---------- ⑤ tools/send_save.bat：App「下载电脑脚本」按钮的唯一目标 ----------
    # 这里的 validate 判的是**内容**：这个文件 c110 才第一次进仓，推送后 gitee raw
    # 缓存里短时间是「不存在」，直接看状态码会把「还没同步好」判成「发布失败」。
    # 顺带把 App 端那两条完整性校验（纯 ASCII / 不提 send_save.ps1）在这里也跑一遍——
    # 远端这份要是被换成 GBK 或某个 HTML 错误页，每个点按钮的用户都会拿到一个坏文件。
    def _is_the_bat(body):
        if not body or any(b > 0x7F for b in body):
            return False
        if b"\n" in body.replace(b"\r\n", b""):        # 纯 CRLF（cmd 对 LF-only 批处理有已知问题）
            return False
        code = b"\r\n".join(ln for ln in body.split(b"\r\n")
                            if not ln.strip().lower().startswith(b"rem"))
        # ⚠ 必须剥 rem 行再查 ps1：bat 的生成说明里就写着「edit send_save.ps1 and regenerate」，
        #   naive 的全文匹配会把正确的单文件脚本判成旧双文件版（同一坑，本项目第三次撞上）。
        return b"certutil" in code and b"send_save.ps1" not in code

    bat_body, bat_st = fetch_verified(base + "tools/send_save.bat", _is_the_bat,
                                      tries, gap, "tools/send_save.bat")
    if bat_st != 200 or not _is_the_bat(bat_body):
        r.fail("⑤ tools/send_save.bat 可下载且是单文件自包含版（App 下载按钮指向它）",
               "HTTP %s / %d B" % (bat_st, len(bat_body)))
    else:
        r.ok("⑤ tools/send_save.bat 可下载且是单文件自包含版（App 下载按钮指向它）",
             "%d B / md5 %s / 纯 ASCII / 纯 CRLF" % (len(bat_body), hashlib.md5(bat_body).hexdigest()[:8]))
        # ⚠ 这一项**不受 same_version 约束**（与 ②B 不同）：脚本没有版本号，
        #   远端该是哪份永远就是源码仓那份；撤回 APK 发布时脚本仍在仓里，照样要比字节。
        local_bat = os.path.join(HERE, "send_save.bat")
        lb = open(local_bat, "rb").read() if os.path.exists(local_bat) else b""
        r.ok("⑤B 下载字节 ↔ 源码仓 send_save.bat 逐字节一致",
             "%d B" % len(bat_body)) if (lb and bat_body == lb) \
            else r.fail("⑤B 下载字节 ↔ 源码仓 send_save.bat 逐字节一致",
                        "本地 %d B / 远端 %d B——不相等通常是 autocrlf 换了行尾，"
                        "或发布仓的 .gitattributes 没生效" % (len(lb), len(bat_body)))

    return r.print_all()


if __name__ == "__main__":
    sys.exit(main())
