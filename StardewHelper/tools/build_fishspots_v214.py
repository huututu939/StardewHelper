# -*- coding: utf-8 -*-
"""v2.14：构建 assets/data/fishSpots.json（钓鱼地图·钓点数据）。

背景：v6 数据包的 fishing_map.json 按「钓点 → 鱼」组织，但它的中文名是机器直译
（鲑鱼写作「三文鱼」、大眼鱼写作「白斑狗鱼」、木跃鱼写作「木 skip鱼」），
而 App 现有 fishing.json 的 74 条鱼名才是官方中文维基译名。鱼名一条都不改，
只按 name_en 把 v6 的「钓点 / 经验 / 四档售价 / 时段」挂到 App 官方名上。

产出结构：
{
  "spots":   [{name, area, region}, ...20],
  "byFish":  {官方鱼名: {spots:[...], xp, prices:{normal,silver,gold,iridium}, timeRanges:[...]}},
  "crabPot": {官方鱼名: {price, area, note}}      # 蟹笼鱼没有钓点，单独放
  "unmapped": {v6名: name_en}                     # 映射不上的，记录下来不丢
}

用法：python tools/build_fishspots_v214.py [--apply]
"""
import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PKG = _REPO_ROOT() + '/stardew_valley_1.6_data_v6'
DATA = os.path.join(ROOT, "app", "src", "main", "assets", "data")
OUT = os.path.join(DATA, "fishSpots.json")
AUDIT = os.path.join(ROOT, "tools", "fishspots_unmapped_v214.json")

# v6 name_en → App 官方中文名（官方中文维基 NavboxFish 列表逐条核对）
ALIAS = {
    # 鱼王 / 传说
    "Legend": "传说之鱼", "Legend II": "传说之鱼二代",
    "Crimsonfish": "绯红鱼", "Son of Crimsonfish": "绯红鱼之子",
    "Glacierfish": "冰川鱼", "Glacierfish Jr.": "小冰川鱼",
    "Angler": "鮟鱇鱼", "Ms. Angler": "雌鮟鱇鱼",
    "Mutant Carp": "变种鲤鱼", "Radioactive Carp": "放射性鲤鱼",
    # 沙滩 / 海洋
    "Pufferfish": "河豚", "Anchovy": "鳀鱼", "Tuna": "金枪鱼",
    "Sardine": "沙丁鱼", "Red Snapper": "红鲷鱼", "Red Mullet": "红鲻鱼",
    "Herring": "鲱鱼", "Octopus": "章鱼", "Squid": "鱿鱼",
    "Sea Cucumber": "海参", "Tilapia": "罗非鱼", "Flounder": "比目鱼",
    "Halibut": "大比目鱼", "Sturgeon": "鲟鱼",
    # 河流 / 湖泊
    "Bream": "鲷鱼", "Catfish": "鲶鱼", "Largemouth Bass": "大嘴鲈鱼",
    "Smallmouth Bass": "小嘴鲈鱼", "Perch": "河鲈", "Pike": "狗鱼",
    "Rainbow Trout": "虹鳟鱼", "Salmon": "鲑鱼", "Shad": "西鲱",
    "Sunfish": "太阳鱼", "Walleye": "大眼鱼", "Tiger Trout": "虎纹鳟鱼",
    "Dorado": "麻哈脂鲤", "Lingcod": "蛇齿单线鱼", "Chub": "鲢鱼",
    "Bullhead": "大头鱼", "Carp": "鲤鱼", "Midnight Carp": "午夜鲤鱼",
    "Goby": "虾虎鱼", "Woodskip": "木跃鱼", "Eel": "鳗鱼",
    # 矿井 / 沙漠 / 特殊
    "Ice Pip": "冰柱鱼", "Lava Eel": "岩浆鳗鱼", "Stonefish": "石鱼",
    "Sandfish": "沙鱼", "Scorpion Carp": "蝎鲤鱼",
    "Ghostfish": "鬼鱼", "Spook Fish": "幽灵鱼",
    "Slimejack": "史莱姆鱼", "Void Salmon": "虚空鲑鱼",
    "Blobfish": "水滴鱼", "Midnight Squid": "午夜鱿鱼",
    # 姜岛
    "Blue Discus": "蓝铁饼鱼", "Lionfish": "狮子鱼", "Stingray": "黄貂鱼",
    # 1.6 水母（App 里叫「凝胶」）
    "River Jelly": "河凝胶", "Cave Jelly": "洞穴凝胶", "Sea Jelly": "海凝胶",
    # 长鳍金枪鱼在官方译名里是「青花鱼」
    "Albacore": "青花鱼",
}

# 蟹笼鱼：App 名 → v6 name_en
CRAB = {
    "蛤": "Clam", "龙虾": "Lobster", "小龙虾": "Crayfish",
    "螃蟹": "Crab", "鸟蛤": "Cockle", "蚌": "Mussel",
    "牡蛎": "Oyster", "虾": "Shrimp", "蜗牛": "Snail",
    "玉黍螺": "Periwinkle",
}


def load(p):
    return json.load(io.open(p, encoding="utf-8"))


def main():
    apply = "--apply" in sys.argv
    src = load(os.path.join(PKG, "fishing_map.json"))["data"]
    app_fish = load(os.path.join(DATA, "fishing.json"))
    app_names = set(x["name"] for x in app_fish)

    spots, by_fish, unmapped = [], {}, {}

    for L in src["fishing_locations"]:
        spot = L["location_name"]
        spots.append({
            "name": spot,
            "area": L.get("area_type", ""),
            "region": L.get("region", ""),
        })
        for f in L.get("fish", []):
            en = f.get("name_en")
            cn = ALIAS.get(en)
            if cn is None:
                unmapped[f["name"]] = en
                continue
            if cn not in app_names:
                # App 没有这个名字 → 不能写进 byFish，否则图鉴会出现陌生条目
                unmapped[f["name"]] = en
                continue
            e = by_fish.setdefault(cn, {"spots": [], "xp": None,
                                        "prices": None, "timeRanges": []})
            if spot not in e["spots"]:
                e["spots"].append(spot)
            if e["xp"] is None:
                e["xp"] = f.get("fishing_xp")
            if e["prices"] is None and isinstance(f.get("sell_price"), dict):
                e["prices"] = f["sell_price"]
            for tr in f.get("time_ranges") or []:
                if tr not in e["timeRanges"]:
                    e["timeRanges"].append(tr)

    # 鱼王：地点字段是自由文本，不属于 20 个钓点，单独标注
    for key, tag in (("legendary_fish", "鱼王"), ("legendary_fish_1_6", "1.6 鱼王")):
        for f in src.get(key, []):
            cn = ALIAS.get(f.get("name_en"))
            if cn is None or cn not in app_names:
                unmapped[f["name"]] = f.get("name_en")
                continue
            e = by_fish.setdefault(cn, {"spots": [], "xp": None,
                                        "prices": None, "timeRanges": []})
            e["legendary"] = tag
            if e["xp"] is None:
                e["xp"] = f.get("fishing_xp")
            if e["prices"] is None and isinstance(f.get("sell_price"), dict):
                e["prices"] = f["sell_price"]
            elif e["prices"] is None and isinstance(f.get("sell_price"), (int, float)):
                e["prices"] = {"normal": f["sell_price"]}

    # 蟹笼鱼：没有钓点语义，单独一节
    by_crab_en = dict((x["name_en"], x) for x in src["crab_pot_fish"]["fish"])
    crab = {}
    for app_n, en in CRAB.items():
        f = by_crab_en.get(en)
        if not f or app_n not in app_names:
            continue
        crab[app_n] = {
            "price": f.get("price_normal"),
            "water": f.get("location"),
            "note": f.get("note", ""),
            "chance": f.get("trap_chance_non_mariner"),
        }

    covered = set(by_fish) | set(crab)
    missing = sorted(app_names - covered)

    print("钓点 %d 个" % len(spots))
    print("按钓点挂上数据的鱼 %d 条" % len(by_fish))
    print("蟹笼鱼 %d 条" % len(crab))
    print("覆盖率 %d/%d" % (len(covered), len(app_names)))
    print("仍未收录 %d 条：%s" % (len(missing), "、".join(missing)))
    print("映射失败的 v6 条目 %d：%s" % (len(unmapped), unmapped or "无"))

    if not apply:
        print("\n（未加 --apply，未写文件）")
        return

    out = {"spots": spots, "byFish": by_fish, "crabPot": crab}
    with io.open(OUT, "w", encoding="utf-8") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)
    print("\n[--apply] 已写出 %s（%.1f KB）"
          % (OUT, os.path.getsize(OUT) / 1024.0))
    with io.open(AUDIT, "w", encoding="utf-8") as f:
        json.dump({"unmapped": unmapped, "app_fish_without_spots": missing},
                  f, ensure_ascii=False, indent=1)
    print("[--apply] 已写出审计 %s" % AUDIT)


if __name__ == "__main__":
    main()