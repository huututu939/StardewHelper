# -*- coding: utf-8 -*-
"""v2.14：按官方中文维基订正 24 道菜谱的中文名。

背景：v2.13.1 的菜名来自 v6 数据包，已证实不可靠——「寿司卷」实为「生鱼寿司」，
群友报的这批错名里它就是一条（当时被我误判成误报）。tools/audit_recipe_names_v214.py
用官方中文维基食谱总表逐条比对，确认 24 条需要改名。

改名会连带影响：
  - gifts.json：村民喜欢/喜爱列表（奶酪花椰菜、炸鳗鱼）
  - bundles.json：收集包成员（寿司卷）
  - image_map.json：新名字要有图标，指向旧名字的 png

用法：python tools/build_recipe_names_v214.py [--apply]
"""
import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
AS = os.path.join(ROOT, "app", "src", "main", "assets")
DATA = os.path.join(AS, "data")
OUTMAP = os.path.join(AS, "image_map.json")

# 旧名（v6 直译/自造） → 官方中文维基名
REN = {
    "奶酪花椰菜": "乳酪花椰菜",   # Cheese Cauliflower
    "寿司卷": "生鱼寿司",          # Maki Roll
    "炸鳗鱼": "炒鳗鱼",            # Fried Eel
    "什锦炒菜": "蔬菜什锦盖饭",   # Stir Fry
    "填料": "塞料面包",            # Stuffing
    "怪味面包": "奇怪的小面包",   # Strange Bun
    "李子布丁": "葡萄干布丁",      # Plum Pudding
    "枫糖条": "枫糖棒",            # Maple Bar
    "根茎拼盘": "块茎拼盘",        # Roots Platter
    "洋蓟蘸酱": "水煮洋蓟",        # Artichoke Dip
    "海味大餐": "海之菜肴",        # Dish O' The Sea
    "海鲜炖汤": "海鲜杂烩汤",      # Fish Stew
    "焗蜗牛": "法式田螺",          # Escargot
    "生存汉堡": "救生汉堡",        # Survival Burger
    "白藻汤": "清汤",              # Pale Broth
    "红莓软糖": "蔓越莓糖果",      # Cranberry Candy
    "芋泥": "夏威夷芋泥",          # Poi
    "蓝莓挞": "蓝莓千层酥",        # Blueberry Tart
    "虞美人籽玛芬": "虞美人籽松糕",  # Poppyseed Muffin
    "蛤蜊浓汤": "烩鱼汤",          # Chowder
    "辣椒酿奶酪": "爆炒青椒",      # Pepper Poppers
    "香脆鲈鱼": "香酥鲈鱼",        # Crispy Bass
    "鱿鱼墨汁饺子": "墨汁意大利饺",  # Squid Ink Ravioli
    "鲤鱼惊喜": "惊喜鲤鱼",        # Carp Surprise
}


def load(p):
    return json.load(io.open(p, encoding="utf-8"))


def rename_in(node, hits):
    """就地替换并返回 node 本身。

    整串相等 → 整串替换；长文本里做子串替换。命中次数记进 hits。
    （字典/列表必须返回自身，否则会把子树替换成计数。）
    """
    if isinstance(node, dict):
        for k in list(node):
            node[k] = rename_in(node[k], hits)
        return node
    if isinstance(node, list):
        for i in range(len(node)):
            node[i] = rename_in(node[i], hits)
        return node
    if isinstance(node, str):
        if node in REN:
            hits[node] = hits.get(node, 0) + 1
            return REN[node]
        for old, new in REN.items():
            if old in node:
                hits[old] = hits.get(old, 0) + 1
                node = node.replace(old, new)
        return node
    return node


def main():
    apply = "--apply" in sys.argv

    rec = load(os.path.join(DATA, "recipes.json"))
    gifts = load(os.path.join(DATA, "gifts.json"))
    bundles = load(os.path.join(DATA, "bundles.json"))
    imgmap = load(OUTMAP)

    # 冲突预检：新名不能已经存在，否则改名会产生重名条目
    cur = set(x["name"] for x in rec)
    clash = [(o, n) for o, n in REN.items() if n in cur and o in cur]
    if clash:
        print("!! 改名后会产生重名，请人工处理：%s" % clash)
        return 1

    h_rec, h_gifts, h_bundles = {}, {}, {}
    rename_in(rec, h_rec)
    rename_in(gifts, h_gifts)
    rename_in(bundles, h_bundles)

    # 图标：新名沿用旧名的 png
    alias = {}
    for old, new in REN.items():
        if old in imgmap:
            alias[new] = imgmap[old]
        elif old + ".png" in os.listdir(os.path.join(AS, "img")):
            alias[new] = old + ".png"
    no_icon = [n for n in REN.values() if n not in alias and n not in imgmap]

    print("recipes.json  改名 %d 处，涉及 %d 个条目" % (sum(h_rec.values()), len(h_rec)))
    print("gifts.json    改名 %d 处：%s" % (sum(h_gifts.values()),
          "、".join("%s×%d" % (k, v) for k, v in h_gifts.items()) or "无"))
    print("bundles.json  改名 %d 处：%s" % (sum(h_bundles.values()),
          "、".join("%s×%d" % (k, v) for k, v in h_bundles.items()) or "无"))
    print("图标别名      %d 条；仍无图标 %d：%s" % (len(alias), len(no_icon), "、".join(no_icon) or "无"))
    print()
    print("改名后菜谱总数 %d" % len(rec))
    leftover = [x["name"] for x in rec if x["name"] in REN]
    print("残留旧名：%s" % (leftover or "无"))

    if not apply:
        print("\n（未加 --apply，未写文件）")
        return 0

    with io.open(os.path.join(DATA, "recipes.json"), "w", encoding="utf-8") as f:
        json.dump(rec, f, ensure_ascii=False, indent=1)
    with io.open(os.path.join(DATA, "gifts.json"), "w", encoding="utf-8") as f:
        json.dump(gifts, f, ensure_ascii=False, indent=1)
    with io.open(os.path.join(DATA, "bundles.json"), "w", encoding="utf-8") as f:
        json.dump(bundles, f, ensure_ascii=False, indent=1)
    imgmap.update(alias)
    with io.open(OUTMAP, "w", encoding="utf-8") as f:
        json.dump(imgmap, f, ensure_ascii=False, indent=1)
    print("\n[--apply] 已写 recipes / gifts / bundles / image_map")
    return 0


if __name__ == "__main__":
    sys.exit(main())