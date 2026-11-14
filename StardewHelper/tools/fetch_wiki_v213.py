#!/usr/bin/env python3
"""v2.14 刷级数据抓取器（纯 stdlib）。

背景：群友投票要「战斗等级怎么刷快 / 刷技能最快路线」，但 App 现有数据里
skills.json 只有介绍/解锁，没有 XP；monsterDrops.json 只有 名字/掉落/出没，
没有 HP/经验；crafting.json 根本没有武器条目。

本脚本负责把英文 wiki 的原始页面抓下来缓存，后续再解析。
英文站 /api.php 已被关（404），但普通 HTML 页面可访问且不限流；
中文 biligame 的 API 会按请求数限流（前 3 条通，第 4 条起 567），只作补充。

用法：
    python tools/fetch_wiki_v213.py            # 抓缺失的页面（跳过已缓存）
    python tools/fetch_wiki_v213.py --force    # 全部重抓
"""
import os
import sys
import time
import urllib.request
import urllib.error

BASE = "https://stardewvalleywiki.com/"
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "wiki_raw_v213")
UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) StardewHelperDataBot/1.0 (offline game helper data sync)"

# 目标页面：slug -> 说明
PAGES = {
    "Monsters": "怪物总表：血量 / 伤害 / 战斗经验 / 掉落 / 出没",
    "Monster_Damage": "武器伤害表：剑 / 匕首 / 弓 的伤害阶梯",
    "Sword": "剑类武器详情（伤害 + 速度 + 升级来源）",
    "Crops": "作物总表：生长天数 / 再生 / 经验 / 售价",
    "Fish": "鱼类总表：难度 / 经验 / 出没季节",
    "Forage": "采集物总表：季节 / 经验",
    "Skill": "技能等级与经验曲线",
    "Smelting": "熔炼：矿石经验 / 产出",
    "Mining": "矿物与采矿经验",
}


def slug_to_file(slug: str) -> str:
    return os.path.join(OUT, slug + ".html")


def fetch(slug: str, force: bool) -> bool:
    dest = slug_to_file(slug)
    if os.path.exists(dest) and not force and os.path.getsize(dest) > 2000:
        print(f"  [skip] {slug:<18} 已缓存 {os.path.getsize(dest):>7} B")
        return True

    url = BASE + slug
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    for attempt in range(3):
        try:
            with urllib.request.urlopen(req, timeout=30) as resp:
                raw = resp.read()
            html = raw.decode("utf-8", errors="replace")
            if len(html) < 2000:
                raise ValueError(f"页面过短 ({len(html)} B)，疑似被拦截")
            with open(dest, "wb") as f:
                f.write(raw)
            print(f"  [ ok ] {slug:<18} {len(raw):>7} B")
            return True
        except urllib.error.HTTPError as e:
            print(f"  [HTTP {e.code}] {slug:<18} 第 {attempt + 1} 次")
        except Exception as e:
            print(f"  [fail ] {slug:<18} {type(e).__name__}: {e}")
        time.sleep(3 * (attempt + 1))
    return False


def main():
    force = "--force" in sys.argv
    os.makedirs(OUT, exist_ok=True)
    print(f"目标：{BASE}\n缓存：{OUT}\n")
    ok, bad = [], []
    for slug in PAGES:
        (ok if fetch(slug, force) else bad).append(slug)
        time.sleep(2.0)  # 礼貌间隔
    print(f"\n完成 {len(ok)}/{len(PAGES)}")
    if bad:
        print("失败：" + ", ".join(bad))
        sys.exit(1)


if __name__ == "__main__":
    main()