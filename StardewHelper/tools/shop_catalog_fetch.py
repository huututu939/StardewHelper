# -*- coding: utf-8 -*-
# 商店目录数据管线（v2.56.0）第 1 步/4：抓 15 家商店的英文 wiki wikitext 到 %TEMP%/shop_wikitext/
# 全链顺序：shop_catalog_fetch -> shop_catalog_parse -> shop_catalog_translate -> shop_catalog_merge
# 中间产物都在 %TEMP%（开发期缓存，不入库）；最终产物 app/src/main/assets/data/shopCatalog.json 入库。
# ⚠ 本组脚本是「数据出处」的记录：wiki 表结构或项目译名口径变了，按序重跑并人工复核 merge 输出。
import json, os, re, urllib.request, urllib.parse
PAGES = {
 "皮埃尔的杂货店": "Pierre's General Store",
 "Joja超市": "JojaMart",
 "铁匠铺": "Blacksmith",
 "星之果实酒吧": "The Stardrop Saloon",
 "哈维的诊所": "Harvey's Clinic",
 "冰淇淋摊": "Ice Cream Stand",
 "电影院": "Movie Theater",
 "木匠的商店": "Carpenter's Shop",
 "玛妮的牧场": "Marnie's Ranch",
 "法师塔": "Wizard's Tower",
 "旅行货车": "Traveling Cart",
 "冒险家公会": "Adventurer's Guild",
 "鱼店": "Fish Shop",
 "绿洲": "Oasis",
 "沙漠商人": "Desert Trader",
}
out = os.path.join(os.environ["TEMP"], "shop_wikitext")
os.makedirs(out, exist_ok=True)
for cn, en in PAGES.items():
    u = ("https://wiki.stardewvalley.net/mediawiki/api.php?action=parse&page="
         + urllib.parse.quote(en) + "&prop=wikitext&format=json&formatversion=2")
    req = urllib.request.Request(u, headers={"User-Agent": "Mozilla/5.0"})
    try:
        t = json.loads(urllib.request.urlopen(req, timeout=30).read().decode("utf-8"))["parse"]["wikitext"]
    except Exception as e:
        print("%-12s FAIL %s" % (en, e)); continue
    p = os.path.join(out, en.replace(" ", "_").replace("'", "") + ".txt")
    open(p, "w", encoding="utf-8").write(t)
    secs = [m.group(0).strip("= ").strip() for m in re.finditer(r"==+[^=\n]+==+", t)]
    print("%-24s %6d B  章节: %s" % (en, len(t), "、".join(secs[:8])))
