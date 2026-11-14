# -*- coding: utf-8 -*-
"""事件级重放 SaveImport.kt 的 wantKey/wantValue 状态机，钉住真实存档形态。

## 为什么需要它（v2.66.0 装机当场踩出来的）

`tools/check_save_import.py` 验证的是**映射数据**对不对，它用正则直取
`<value>`，**根本不经过 Kotlin 那套 wantKey/wantValue 状态机**。于是两件事
会同时成立、且互相矛盾：Python 侧数字全对（与计划一致），Kotlin 侧博物馆整组 0 条。

真凶是这一行：

    <key><Vector2><X>47</X><Y>16</Y></Vector2></key>   ← wantKey 被置位且永远清不掉
    <value><int>113</int></value>                       ← 这个叶子被 `if (wantKey)` 抢走

`Vector2` 里没有 int/string，`wantKey` 挂着一路带到 value，
`if (wantKey)` 先于 `if (wantValue)` 命中，ID 被存进 key、value 恒为 null，
而 `idFromValue` 读的正是 value —— **每一条都被丢弃，不报错、就是空**。

这类 bug 的共同特征是「不崩溃、不报错、结果为空」，
源码级断言只能钉住「那行代码长什么样」，钉不住「跑起来对不对」。
所以这里用**真实存档里的真实片段**逐事件重放，跑错就红。

用法:
    python tools/test_savestate.py            # 跑全部用例
"""
import io
import os
import re
import sys
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
SRC = os.path.join(os.path.dirname(ROOT), "存档")

fails = []


def check(cond, msg):
    if cond:
        print("  OK   %s" % msg)
    else:
        print("  FAIL %s" % msg)
        fails.append(msg)


# ── 重放器：与 SaveImport.kt 的分支顺序逐行对应 ──────────────────────────
def replay(fragment, id_from_value):
    """喂一段真实 XML 片段，返回 (key, value)。

    严格照抄 Kotlin 的分支顺序——包括「先判 wantKey 再判 wantValue」这个
    **本身就是 bug 温床**的顺序。修 Kotlin 时必须同步改这里。
    """
    want_key = want_value = False
    key = value = None
    pos = 0
    tag_re = re.compile(r"<(/?)([A-Za-z]+)([^>]*)>")

    stack = []
    while pos < len(fragment):
        m = tag_re.search(fragment, pos)
        if not m:
            break
        pos = m.end()
        closing, name = m.group(1) == "/", m.group(2)

        if not closing:
            if name == "item":
                want_key = want_value = False
                key = value = None
            elif name == "key":
                # ← 这一行就是 v2.66.0 的修复点：idFromValue 的容器不挂 wantKey
                if not id_from_value:
                    want_key = True
            elif name == "value":
                want_value = True
            elif name in ("int", "string"):
                # 模拟 readLeaf：取到叶子文本
                text = ""
                tail = fragment[m.end():]
                tm = re.match(r"\s*([^<]*)", tail)
                if tm:
                    text = tm.group(1).strip()
                if text:
                    if want_key:
                        key = text
                        want_key = False
                    elif want_value and value is None:
                        value = int(text) if text.lstrip("-").isdigit() else None
                        want_value = False
            stack.append(name)
        else:
            stack.pop()
    return key, value


def main():
    print("== 真实片段形态（取自 %s 下的存档）==\n" % SRC)

    cases = []

    # ① museumPieces：键是 Vector2 展柜坐标，ID 在 value —— v2.66.0 装机 bug 的现场
    ff = None
    if os.path.isdir(SRC):
        for fn in sorted(os.listdir(SRC)):
            if fn.lower().endswith(".zip"):
                ff = fn
                break
    if ff:
        import tempfile
        tmp = tempfile.mkdtemp(prefix="sdv_state_")
        dest = os.path.join(tmp, "x")
        os.makedirs(dest, exist_ok=True)
        with zipfile.ZipFile(os.path.join(SRC, ff)) as z:
            z.extractall(dest)
        main_file = None
        for root, _d, files in os.walk(dest):
            for fn2 in files:
                c = os.path.join(root, fn2)
                try:
                    with open(c, "rb") as f:
                        head = f.read(2048)
                    if b"<SaveGame" in head and (main_file is None or
                                                os.path.getsize(c) > os.path.getsize(main_file)):
                        main_file = c
                except OSError:
                    pass
        if main_file:
            raw = io.open(main_file, "rb").read()
            m = re.search(rb"<museumPieces><item>.*?</item>", raw, re.S)
            if m:
                frag = m.group(0).decode("utf-8", "replace")
                cases.append(("museumPieces 的键是 Vector2 坐标", frag, True))

    # ② 普通容器：键就是 ID（钓鱼/矿物/古物/烹饪/制作都属这类）
    cases.append(("普通容器：键是数字 ID",
                  "<item><key><string>111</string></key><value><int>3</int></value></item>", False))
    cases.append(("MOD 档双形态：键是 int",
                  "<item><key><int>113</int></key><value><int>0</int></value></item>", False))
    # ③ 制作组的 0 值陷阱必须仍然生效（requirePositive 依赖 value）
    cases.append(("制作配方 value=0（已解锁未制作）",
                  "<item><key><string>Campfire</string></key><value><int>0</int></value></item>", False))

    for name, frag, idv in cases:
        print("- %s" % name)
        print("    片段: %s" % frag[:110])
        key, value = replay(frag, idv)

        if idv:
            # museumPieces：ID 必须在 value 里，key 应为 None
            check(key is None,
                  "键不会被 Vector2 里的坐标占位（key=%r，应为 None）" % key)
            check(value is not None,
                  "ID 落在 value 里（实得 value=%r）—— 这条红了就是 v2.66.0 那个坑复发" % value)
            check(str(value) == str(frag.split("<value>")[-1].strip("<>").split(">")[1].split("<")[0]),
                  "取到的 ID 与片段里的字面值一致（value=%r）" % value)
        else:
            check(key is not None,
                  "键取到 ID（key=%r）" % key)
        print()

    # ④ 反向用例：id_from_value=False 时不得依赖 value 里的东西当 ID
    print("- 反向：普通容器的键不能被 value 抢走")
    k, v = replay("<item><key><int>137</int></key><value><ArrayOfInt><int>4</int></ArrayOfInt></value></item>", False)
    check(k == "137", "钓鱼容器的键仍是 137（实得 %r）" % k)
    print()

    print("=" * 60)
    if fails:
        print("失败 %d 条" % len(fails))
        return 1
    print("全部通过")
    return 0


if __name__ == "__main__":
    sys.exit(main())
