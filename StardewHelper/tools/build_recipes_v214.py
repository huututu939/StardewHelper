# -*- coding: utf-8 -*-
"""v2.14：用官方 81 道菜谱重建 App 的 recipes.json，并补齐 image_map 别名。

背景：群友在群里报了一屏菜谱错名/错材料/缺失。经核对群友反馈 20 条里 19 条成立，
      官方数据源 = stardew_valley_1.6_data_v6/recipes.json（EN+ZH Cooking 总表，81 道）。
      另有 2 条是群友误报（寿司卷=生鱼寿司、小麦粉=大麦粉），本脚本按官方数据保持原样。

原 App 59 条 → 官方 81 条。炸薯条/蝴蝶结面 这类官方不存在的条目直接消失。

用法：python tools/build_recipes_v214.py [--apply]
      不带 --apply 只打印报告，不动文件。
"""
import io
import json
import os

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = _REPO_ROOT() + '/stardew_valley_1.6_data_v6'
DATA = os.path.join(ROOT, "app", "src", "main", "assets", "data")
IMGMAP = os.path.join(ROOT, "app", "src", "main", "assets", "image_map.json")

# 官方新名 → App 里已有图标的旧名。只收「确认是同一个东西」的，别乱猜。
ALIAS = {
    # —— 改名菜谱 ——
    "红之盛宴": "红盘菜",
    "椰汁汤": "泰式酸辣汤",
    "巨无霸餐": "超能餐",
    "墨西哥薄饼": "玉米饼",
    "大米布丁": "米布丁",
    "黑莓脆皮饼": "黑莓脆饼",
    "红莓酱": "蔓越莓酱",
    "意式蕨菜炖饭": "蕨菜烩饭",
    "帕尔玛奶酪茄子": "茄子帕玛森",
    "矿工特供": "矿工零食",
    # —— 改名材料 ——
    "醋": "香醋",
    "红叶卷心菜": "红甘蓝",
    "甘蓝菜": "甘蓝",
    "西红柿": "番茄",
    "螃蟹": "蟹",
    "虹鳟": "虹鳟鱼",
    "杏": "杏子",
    "山洞胡萝卜": "山洞萝卜",
    # —— img/ 里有文件但 image_map 没登记的，直接补登记 ——
    "白藻": "白藻",
    "贻贝": "贻贝",
}


def load(p):
    return json.load(io.open(p, encoding="utf-8"))


def main():
    apply = "--apply" in __import__("sys").argv
    official = load(os.path.join(PKG, "recipes.json"))["data"]["recipes"]
    imgmap = load(IMGMAP)

    # 1) 构建新 recipes.json
    new = []
    for r in official:
        ings = []
        for i in r.get("ingredients", []):
            ings.append({
                "name": i["item"],
                "count": i.get("quantity", 1) or 1,
            })
        new.append({
            "name": r["name"],
            "ingredients": ings,
            "source": (r.get("source_detail") or "").strip() or "其他",
            "effect": "%s体/%s血" % (r.get("energy"), r.get("health")),
        })

    # 2) 算图标覆盖
    need = set()
    for r in new:
        need.add(r["name"])
        for i in r["ingredients"]:
            need.add(i["name"])
    have = set(imgmap)
    imgdir = os.path.join(ROOT, "app", "src", "main", "assets", "img")
    pngs = set(os.listdir(imgdir))
    missing = sorted(n for n in need if n not in have)
    aliasable = {}
    for n in missing:
        if n not in ALIAS:
            continue
        target = ALIAS[n]
        if target in have:                 # 旧名已有登记 → 复制映射
            aliasable[n] = imgmap[target]
        elif (target + ".png") in pngs:    # img/ 有文件但没登记 → 直接登记
            aliasable[n] = target + ".png"
    still = [n for n in missing if n not in aliasable]

    old = load(os.path.join(DATA, "recipes.json"))
    oldn = set(x["name"] for x in old)
    newn = set(x["name"] for x in new)

    print("=" * 70)
    print("App 菜谱 %d 条 → 官方 %d 条" % (len(old), len(new)))
    print("  删除（官方不存在）%d：%s" % (len(oldn - newn), "、".join(sorted(oldn - newn))))
    print("  新增 %d 道" % len(newn - oldn))
    print()
    print("需图标 %d 个：已有 %d / 别名可补 %d / 仍缺 %d"
          % (len(need), len(need) - len(missing), len(aliasable), len(still)))
    if aliasable:
        print("  别名补：%s" % "、".join("%s→%s" % (k, v) for k, v in sorted(aliasable.items())))
    if still:
        print("  仍缺（显示占位图）：%s" % "、".join(still))

    if not apply:
        print("\n（未加 --apply，未写文件）")
        return

    with io.open(os.path.join(DATA, "recipes.json"), "w", encoding="utf-8") as f:
        json.dump(new, f, ensure_ascii=False, indent=1)
    print("\n[--apply] 已重建 recipes.json：%d 条" % len(new))

    if aliasable:
        imgmap.update(aliasable)
        with io.open(IMGMAP, "w", encoding="utf-8") as f:
            json.dump(imgmap, f, ensure_ascii=False, indent=1)
        print("[--apply] 已补 image_map %d 条，总计 %d" % (len(aliasable), len(imgmap)))


if __name__ == "__main__":
    main()