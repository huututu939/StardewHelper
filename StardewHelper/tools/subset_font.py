# -*- coding: utf-8 -*-
"""把 Fusion Pixel 像素字体按「项目自有语料」做子集化，砍掉 APK 里那 7 MB 字体。

## 为什么子集是安全的
App 要渲染的文本来源是**固定的**：assets/data/*.json 里的游戏数据 + Kotlin 里硬编码的
界面文案。数据集不变，字形集合就不变。所以子集不会随时间腐坏。

⚠ **不能按「界面文案」单独切**——那会漏掉图鉴正文、作物名、村民名、礼物分类等
全部游戏数据文本，用户会看到大面积回退到系统字体。

## 语料来源（三处都要，缺一不可）
  1. app/src/main/assets/**/*.json —— 游戏数据（键与值都算，键是显示名的一部分）
  2. app/src/main/java/**/*.kt   —— 界面文案是**硬编码在 Kotlin 里**的，不在 JSON 里
  3. app/src/main/res/**/*.xml    —— 小组件布局与 strings.xml
另加 BASE_RANGES 里的常用符号/全角区间（成本很低，但能挡住符号类漏字）。

## 用法
  python tools/subset_font.py            # 生成：写 app/src/main/res/font/fusion_pixel.ttf
  python tools/subset_font.py --report   # 只报告语料规模与前后体积，不写文件
  python tools/subset_font.py --check    # 只校验：当前字体是否已覆盖全部语料（verify 用）

## 为什么源字体放在 tools/fonts/
原始 7 MB 字体留在 `tools/fonts/fusion_pixel.ttf`——该目录**不在 app/src 下，不会进 APK**，
但留在 git 里，这样本脚本永远可以**从完整的原字体重建**子集。
否则一旦把 res/font 里那份覆盖成子集，重跑脚本就变成「在子集上再切」，会不可逆地丢字形。
`git show HEAD~N:StardewHelper/app/src/main/res/font/fusion_pixel.ttf` 是等价的备份，
但那依赖提交历史，不适合当长期约定。

## 不做的事
- 不动 @font/fusion_pixel 与 R.font.fusion_pixel 的**资源引用**（文件名照旧）
- 不丢 name 表：--name-IDs='*' --name-languages='*' 全量保留（含 OFL 版权与许可信息）
- 不需要 --no-subset-tables：本字体是纯轮廓，没有 EBLC/EBDT/sbix/SVG 位图表

## v2.60.0 起：子集必须改家族名（OFL 1.1 第 3 条）
子集属于 OFL 定义的 Modified Version，**不得继续使用保留名「缝合像素 / Fusion Pixel」**
（保留名写在原字体版权行后，是许可文本里明文保留的）。所以切完后统一改写主名字：
  nameID 1/16 -> TutuPixel · 4 -> TutuPixel Regular · 6 -> TutuPixel-Regular · 17 -> Regular
**版权（0）、版本（5）、许可指针（13）、许可 URL（14）一个字不动**——那是 OFL 第 2 条
要求随副本保留的元数据，verify 41.3 逐条钉着。
运行期零影响：Compose 走 Font(R.font.fusion_pixel)、小组件走 @font/fusion_pixel，
都是资源引用，不读字体内部家族名。
"""
import argparse
import json
import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC_FONT = os.path.join(ROOT, "tools", "fonts", "fusion_pixel.ttf")
OUT_FONT = os.path.join(ROOT, "app", "src", "main", "res", "font", "fusion_pixel.ttf")

# ── 基础字符集：与语料无关、但几乎必然要显示的东西 ──────────────────────────
# 逐个区间展开，pyftsubset 会自动忽略字体里不存在的码位，所以「多留」几乎免费。
BASE_RANGES = [
    (0x0020, 0x007E),   # ASCII 可打印（数字/字母/常用半角符号）
    (0x00A0, 0x00FF),   # Latin-1 补充
    (0x2010, 0x2027),   # – — ‘ ’ “ ” † ‡ • …
    (0x2030, 0x205E),   # ‰ ′ ″ § ¶ ※ etc.
    (0x2190, 0x21FF),   # 箭头 ← → ↔ ↑ ↓
    (0x2460, 0x24FF),   # 带圈数字与字母 ①②③ Ⓐ
    (0x25A0, 0x25FF),   # 几何图形 ▲ ● ■ ◆
    (0x2600, 0x26FF),   # 杂项符号 ★ ☆ ☀
    (0x3000, 0x303F),   # CJK 标点 。、《》「」
    (0xFF01, 0xFF60),   # 全角
    (0xFFE0, 0xFFE6),   # ￥ ￠ ￡ ￦ ￨ ￩
]

# 纵向度量表：App 纯横排（hhea/vmtx 为竖排预留），丢掉不影响任何渲染。
DROP_TABLES = ["vhea", "vmtx"]

_KT_RAW = re.compile(r'"""(.*?)"""', re.S)
_KT_STR = re.compile(r'"((?:[^"\\\n]|\\.)*)"')


def _walk_json(node, out):
    if isinstance(node, dict):
        for k, v in node.items():
            if isinstance(k, str):
                out.update(k)
            _walk_json(v, out)
    elif isinstance(node, list):
        for v in node:
            _walk_json(v, out)
    elif isinstance(node, str):
        out.update(node)


def corpus_from_json():
    chars = set()
    files = 0
    base = os.path.join(ROOT, "app", "src", "main", "assets")
    for r, _d, fs in os.walk(base):
        for f in fs:
            if not f.lower().endswith(".json"):
                continue
            p = os.path.join(r, f)
            try:
                with open(p, encoding="utf-8") as fh:
                    _walk_json(json.load(fh), chars)
                files += 1
            except Exception as e:                      # 坏文件不该让整条流水线崩
                print("  ! 跳过 %s: %s" % (os.path.relpath(p, ROOT), e), file=sys.stderr)
    return chars, "%d 个 .json" % files


def corpus_from_kotlin():
    chars = set()
    files = 0
    base = os.path.join(ROOT, "app", "src", "main", "java")
    for r, _d, fs in os.walk(base):
        for f in fs:
            if not f.endswith(".kt"):
                continue
            txt = open(os.path.join(r, f), encoding="utf-8").read()
            for m in _KT_RAW.findall(txt):
                chars.update(m)
            for m in _KT_STR.findall(_KT_RAW.sub('""', txt)):
                chars.update(m)
            files += 1
    return chars, "%d 个 .kt" % files


_XML_COMMENT = re.compile(r"<!--.*?-->", re.S)


def corpus_from_xml():
    chars = set()
    files = 0
    base = os.path.join(ROOT, "app", "src", "main", "res")
    # ⚠ 注释不是 UI 文本：`<!-- -->` 里的汉字永远不会被渲染，收进语料有两个坏处——
    #     一是白白占字形，二是把「加一句中文注释」变成「必须重跑字体子集」的陷阱
    #     （v2.50.0 给三个小组件 xml 加说明注释，就凭空多出 毫/渲/闹 三个码位，
    #      被 verify 第 41 节的「语料 ⊆ 字体」断言当场抓住）。
    #     Kotlin 那边本来就只收字符串字面量、不收注释，两边口径必须一致。
    for r, _d, fs in os.walk(base):
        for f in fs:
            if not f.endswith(".xml"):
                continue
            txt = open(os.path.join(r, f), encoding="utf-8").read()
            chars.update(_XML_COMMENT.sub(" ", txt))
            files += 1
    return chars, "%d 个 .xml" % files


def corpus_base():
    chars = set()
    for lo, hi in BASE_RANGES:
        for cp in range(lo, hi + 1):
            chars.add(chr(cp))
    return chars, "%d 个区间" % len(BASE_RANGES)


def build_corpus():
    parts, labels = [], []
    for fn in (corpus_from_json, corpus_from_kotlin, corpus_from_xml, corpus_base):
        chars, label = fn()
        parts.append(chars)
        labels.append((fn.__name__.replace("corpus_from_", ""), chars, label))
    total = set()
    for c in parts:
        total |= c
    return total, labels


def font_cmap(path):
    from fontTools.ttLib import TTFont
    f = TTFont(path, lazy=True)
    cm = set(f.getBestCmap().keys())
    f.close()
    return cm


def font_family(path):
    from fontTools.ttLib import TTFont
    f = TTFont(path, lazy=True)
    name = f["name"].getDebugName(1)
    f.close()
    return name


# v2.60.0：OFL 1.1 第 3 条——修改版不得使用保留名「缝合像素 / Fusion Pixel」。
# 只改「呈现给用户的主名字」（1/4/6/16/17）；版权(0)/版本(5)/许可指针(13)/许可URL(14)
# 必须原样保留（第 2 条），verify 41.3 会双向钉死：新名不许含保留名，旧元数据不许丢。
RENAME_FAMILY = "TutuPixel"
NAME_REWRITE = {
    1: "TutuPixel",              # Font Family
    4: "TutuPixel Regular",      # Full Font Name
    6: "TutuPixel-Regular",      # PostScript名（规范要求无空格）
    16: "TutuPixel",             # Typographic Family
    17: "Regular",               # Typographic Subfamily
}


def rename_family(path):
    """把子集字体的主名字改掉（OFL 第 3 条），版权与许可元数据原样不动。"""
    from fontTools.ttLib import TTFont
    f = TTFont(path)
    for rec in f["name"].names:
        if rec.nameID in NAME_REWRITE:
            rec.string = NAME_REWRITE[rec.nameID].encode(rec.getEncoding(), "replace")
    f.save(path)
    f.close()


def run_subset(corpus, out_path, extra_args):
    text = "".join(sorted(corpus))
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False,
                                     encoding="utf-8") as fh:
        fh.write(text)
        corpus_file = fh.name
    cmd = [
        "pyftsubset", SRC_FONT,
        "--text-file=" + corpus_file,
        "--output-file=" + out_path,
        "--layout-features=*",
        "--glyph-names",
        "--symbol-cmap", "--legacy-cmap",
        "--notdef-glyph", "--notdef-outline",
        "--recommended-glyphs",
        "--name-IDs=*", "--name-legacy", "--name-languages=*",
    ]
    if DROP_TABLES:
        cmd.append("--drop-tables+=" + ",".join(DROP_TABLES))
    cmd.extend(extra_args)
    try:
        subprocess.run(cmd, check=True)
    finally:
        os.unlink(corpus_file)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--report", action="store_true", help="只报告，不写文件")
    ap.add_argument("--check", action="store_true", help="只校验语料是否已被当前字体覆盖")
    ap.add_argument("--no-drop-vertical", action="store_true", help="保留 vhea/vmtx")
    args = ap.parse_args()

    if not os.path.exists(SRC_FONT):
        print("找不到源字体：%s" % SRC_FONT, file=sys.stderr)
        return 2

    corpus, labels = build_corpus()
    corpus_cp = {ord(c) for c in corpus}          # 集合运算一律走码位，字符串混用会恒为空集
    src_cm = font_cmap(SRC_FONT)

    print("语料规模（去重后码位数）")
    prev = set()
    for name, chars, label in labels:
        new = len(chars - prev)
        print("  %-10s %-14s 码位 %6d（较上一项新增 %5d）" % (name, label, len(chars), new))
        prev |= chars
    print("  %-10s %-14s 码位 %6d" % ("合计", "", len(corpus_cp)))
    print()
    in_src = corpus_cp & src_cm
    print("  源字体 cmap 共 %d 码位；语料命中其中 %d，语料里另有 %d 个源字体根本没有（忽略即可）"
          % (len(src_cm), len(in_src), len(corpus_cp - src_cm)))

    if args.check:
        cur_cm = font_cmap(OUT_FONT)
        missing = sorted(in_src - cur_cm)
        print()
        print("当前字体覆盖语料：%d / %d" % (len(in_src) - len(missing), len(in_src)))
        if missing:
            print("✗ 有 %d 个语料字符不在当前字体里——数据更新过但没重跑子集：" % len(missing))
            print("  " + "".join(chr(c) for c in missing[:60]))
            print("  修复：python tools/subset_font.py")
            return 1
        print("✓ 全部覆盖")
        return 0

    if args.report:
        before = os.path.getsize(SRC_FONT)
        print()
        print("源字体 %d B（%.2f MB）" % (before, before / 1048576.0))
        return 0

    extra = [] if args.no_drop_vertical else []
    # ⚠ 临时文件必须和目标同盘：Windows 的 os.replace 跨盘符会直接 WinError 17。
    tmp = os.path.join(os.path.dirname(OUT_FONT), ".fusion_pixel.subset.tmp")
    try:
        run_subset(corpus, tmp, extra)
        # OFL 第 3 条：切完必须改主名字（版权/许可元数据不动，见 rename_family）
        rename_family(tmp)
        after = os.path.getsize(tmp)
        before = os.path.getsize(SRC_FONT)
        print()
        print("子集结果：%d B -> %d B，省 %d B（-%.1f%%）"
              % (before, after, before - after, (before - after) * 100.0 / before))
        print("子集 cmap 码位：%d" % len(font_cmap(tmp)))
        print("家族名：%s -> %s" % (font_family(SRC_FONT), font_family(tmp)))
        os.replace(tmp, OUT_FONT)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)
    print("已写入 %s" % os.path.relpath(OUT_FONT, ROOT))
    return 0


if __name__ == "__main__":
    sys.stdout.reconfigure(encoding="utf-8")
    sys.exit(main())
