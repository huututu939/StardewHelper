# -*- coding: utf-8 -*-
"""v2.14：把 v6 数据包里本版要用的 4 个模块搬进 assets/data/。

做法是「原样搬运 + 递归剥掉 source_url」：v6 的数值字段经过上轮审计可信，
而中文名字段不可信（见 build_recipe_names_v214.py）。这几个模块是「整块读取 +
按结构渲染」，不需要逐字段重排，搬运比裁剪更不容易出错。

产出：
  weaponCompendium.json  ← weapon_compendium.json   （63 件武器）
  gingerIslandGuide.json ← ginger_island.json       （姜岛攻略）
  marriageGuide.json     ← marriage_guide.json      （结婚攻略）
  processingProfit.json  ← processing_profit.json   （加工利润排序）

用法：python tools/build_assets_v214.py [--apply]
"""
import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = _REPO_ROOT() + '/stardew_valley_1.6_data_v6'
DATA = os.path.join(ROOT, "app", "src", "main", "assets", "data")

JOBS = [
    ("weapon_compendium.json", "weaponCompendium.json"),
    ("ginger_island.json", "gingerIslandGuide.json"),
    ("marriage_guide.json", "marriageGuide.json"),
    ("processing_profit.json", "processingProfit.json"),
]
DROP_KEYS = {"source_url"}


def strip(node):
    if isinstance(node, dict):
        return dict((k, strip(v)) for k, v in node.items() if k not in DROP_KEYS)
    if isinstance(node, list):
        return [strip(v) for v in node]
    return node


def main():
    apply = "--apply" in sys.argv
    total = 0
    for src_name, dst_name in JOBS:
        p = os.path.join(PKG, src_name)
        d = json.load(io.open(p, encoding="utf-8"))
        data = strip(d["data"])
        out = os.path.join(DATA, dst_name)
        size = len(json.dumps(data, ensure_ascii=False).encode("utf-8")) / 1024.0
        print("%-24s → %-24s %-6s %.1f KB  顶层键 %s"
              % (src_name, dst_name, "", size, list(data)))
        total += size
        if apply:
            with io.open(out, "w", encoding="utf-8") as f:
                json.dump(data, f, ensure_ascii=False, indent=1)
    print("\n合计 %.1f KB" % total)
    if not apply:
        print("（未加 --apply，未写文件）")
    else:
        print("[--apply] 已写入 4 个 assets 文件")


if __name__ == "__main__":
    main()