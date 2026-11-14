# -*- coding: utf-8 -*-
"""下载官方 1.6.15 数据镜像核心文件到 tools/official_game_1_6_15/Data/（只增不删）"""
import os, sys, urllib.request, json
sys.stdout.reconfigure(encoding="utf-8")

BASE = "https://ghproxy.net/https://raw.githubusercontent.com/kronosta/stardew-data/master/Data/"
DEST = r"F:\Desktop\星露谷\StardewHelper\tools\official_game_1_6_15\Data"
os.makedirs(DEST, exist_ok=True)

FILES = [
    "Crops.json", "Fish.json", "FishPondData.json", "CraftingRecipes.json",
    "CookingRecipes.json", "Machines.json", "FarmAnimals.json", "FruitTrees.json",
    "Shops.json", "MuseumRewards.json", "Objects.json", "BigCraftables.json",
    "ConcessionTastes.json", "MoviesReactions.json", "Weapons.json", "Tools.json",
    "SecretNotes.json", "Monsters.json", "Pets.json", "Bundles.json", "Locations.json",
    "Buildings.json", "NPCGiftTastes.json",
]

# 清死代理
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(k, None)

ok, fail = [], []
for fn in FILES:
    dest = os.path.join(DEST, fn)
    if os.path.exists(dest) and os.path.getsize(dest) > 0:
        ok.append(fn + " (已有)")
        continue
    try:
        req = urllib.request.Request(BASE + fn, headers={"User-Agent": "stardew-helper-audit"})
        with urllib.request.urlopen(req, timeout=60) as r:
            data = r.read()
        with open(dest, "wb") as f:
            f.write(data)
        ok.append("%s (%d B)" % (fn, len(data)))
    except Exception as e:
        fail.append("%s: %s" % (fn, e))

print("OK:")
for x in ok: print(" ", x)
if fail:
    print("FAIL:")
    for x in fail: print(" ", x)
