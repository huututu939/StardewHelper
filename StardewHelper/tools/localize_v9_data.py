import json, io, sys, os
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
A = _REPO_ROOT() + "/StardewHelper/app/src/main/assets/data/"

# ── 1) 天气：key/value 全英 → 中文化 ──
WEATHER = {"sunny": "晴天", "rainy": "雨天", "windy": "大风",
           "stormy": "雷雨", "snowy": "下雪", "green_rain": "绿雨"}
SEASON = {"spring": "春", "summer": "夏", "fall": "秋", "winter": "冬"}

wl = json.load(open(A + "weatherLuck.json", encoding="utf-8"))
w = wl["weather"]
seasons = {}
for sk, so in w["seasons"].items():
    seasons[SEASON.get(sk, sk)] = {
        "possible_weather": [WEATHER.get(x, x) for x in so.get("possible_weather", [])],
        "probabilities": {WEATHER.get(k, k): v for k, v in so.get("probabilities", {}).items()},
        "notes": so.get("notes", ""),
    }
# weather_effects 的 value 是长文本，只换 key
eff = {WEATHER.get(k, k): v for k, v in w["weather_effects"].items()}
wl["weather"] = {
    "seasons": seasons,
    "weather_effects": eff,
    "probabilities_note": w.get("probabilities_note", ""),
    "tv_forecast": w.get("tv_forecast", ""),
}
json.dump(wl, open(A + "weatherLuck.json", "w", encoding="utf-8"),
          ensure_ascii=False, separators=(",", ":"))
print("weatherLuck: seasons ->", list(seasons.keys()))
print("             weather_effects ->", list(eff.keys()))

# ── 2) 采集：locations / uses 里的英文要换中文 ──
# 只替换确定是英文专名的部分；整串是中文的（"任何可砍的树"）原样保留
ITEM_CN = {
    # 用途：加工品 / 料理
    "Basic Fertilizer": "初级肥料", "Deluxe Fertilizer": "高级肥料",
    "Quality Bobber": "优质浮标", "Trap Bobber": "陷阱浮标", "Torch": "火把",
    "Fall Foraging Bundle": "秋季采集包", "Fall Seeds": "秋季种子",
    "Fried Mushroom": "炒蘑菇", "Stir Fry": "炒什锦", "Salad": "沙拉",
    "Field Snack": "矿工零食", "Quality Oil": "优质油", "Mayonnaise": "蛋黄酱",
    # 地点
    "Secret Woods": "秘密森林", "Forest Farm": "森林农场", "Farm Cave": "农场洞",
    "Cindersap Forest": "煤矿森林", "Mountain": "山地", "Beach": "海滩",
    "Swamp": "沼泽", "Tunnel": "隧道", "Bus Stop": "巴士站",
    "Mushroom Tree": "蘑菇树", "Diggy Branch": "掘地虫",
}
fg = json.load(open(A + "foraging.json", encoding="utf-8"))
repl = 0
for it in fg["items"]:
    # locations：形如 "Secret Woods(16%)"
    new_loc = []
    for loc in it.get("locations", []):
        s = loc
        for en, cn in ITEM_CN.items():
            if en in s:
                s = s.replace(en, cn)
                repl += 1
        new_loc.append(s)
    it["locations"] = new_loc
    it["uses"] = [ITEM_CN.get(u, u) for u in it.get("uses", [])]
json.dump(fg, open(A + "foraging.json", "w", encoding="utf-8"),
          ensure_ascii=False, separators=(",", ":"))
print("foraging: 替换英文 %d 处" % repl)
print("  sample locations:", json.dumps(fg["items"][1]["locations"], ensure_ascii=False))
print("  sample uses:", json.dumps(fg["items"][1]["uses"], ensure_ascii=False))


# ── 路径解析（2026-11-10 开源清理）────────────────────────────────
# 原先这里写死本机绝对路径，公开仓里必然跑不通，
# 而且会把本机目录结构一起公开。改成基于脚本位置推导 + 环境变量覆盖。
def _REPO_ROOT():
    """仓根（本仓根 = StardewHelper/ 的上一级）。可用 SDH_REPO_ROOT 覆盖。"""
    _env = os.environ.get("SDH_REPO_ROOT")
    if _env:
        return _env
    return os.path.dirname(os.path.dirname(os.path.abspath(__file__)))


def _V9_DIR():
    """1.6 官方数据包目录。可用 SDH_V9_DIR 覆盖（公开仓不含该数据）。"""
    _env = os.environ.get("SDH_V9_DIR")
    if _env:
        return _env
    return os.path.join(_REPO_ROOT(), "旧文件归档", "stardew_valley_1.6_data_v9")
