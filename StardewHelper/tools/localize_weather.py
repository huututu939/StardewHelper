"""天气与幸运数据中文化：weather 的 key/value 是英文（sunny/rainy/green_rain…），
顺手把 seasons 的键也换成中文季节名。幂等，可重复运行。"""
import os
import json, io, sys, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
A = _REPO_ROOT() + "/StardewHelper/app/src/main/assets/data/"

WEATHER = {"sunny": "晴天", "rainy": "雨天", "windy": "大风",
           "stormy": "雷雨", "snowy": "下雪", "green_rain": "绿雨"}
SEASON = {"spring": "春", "summer": "夏", "fall": "秋", "winter": "冬"}
# luck_effects / tv_fortune 的 key 是英文短语，直接当标签上屏，必须译
LUCK_KEY = {
    "fishing_treasure": "钓鱼宝箱", "geode_contents": "晶球内容",
    "crop_quality": "作物产量", "ladder_drop": "梯子掉落",
    "monster_loot": "怪物掉落", "mining": "采矿", "pan_handling": "淘金",
    "garbage_can": "垃圾桶", "casino_slots": "赌场老虎机",
    "festival_spinner": "展览会转盘", "channel": "频道",
}

def cn_if_en(k, table):
    """英文才查表翻译；已是中文的原样返回。查不到就返回原文
    （宁可露英文，也不要把 key 变成 null/None）。"""
    if not isinstance(k, str) or not re.search(r"[A-Za-z]", k):
        return k
    return table.get(k, k)

wl = json.load(open(A + "weatherLuck.json", encoding="utf-8"))
w = wl["weather"]

seasons = {}
for sk, so in w.get("seasons", {}).items():
    # 键可能已是中文（二次运行）——仍要检查内部 probabilities 有没有被译过，
    # 不能因为键是中文就整段跳过，否则嵌套 key 会永远留在英文。
    seasons[SEASON.get(sk, sk)] = {
        "possible_weather": [cn_if_en(x, WEATHER) for x in so.get("possible_weather", [])],
        # probabilities 是嵌套 dict，key 也要译，否则「晴天 55%」显示成 sunny 0.55
        "probabilities": {cn_if_en(k, WEATHER): v for k, v in so.get("probabilities", {}).items()},
        "notes": so.get("notes", ""),
    }

# weather_effects 的 value 是长文本，只换 key
eff = {cn_if_en(k, WEATHER): v for k, v in w.get("weather_effects", {}).items()}

wl["weather"] = {
    "seasons": seasons,
    "weather_effects": eff,
    "probabilities_note": w.get("probabilities_note", ""),
    "tv_forecast": w.get("tv_forecast", ""),
}

# luck：luck_effects / tv_fortune 的 key 译中文，luck_boost_items 的 key 也一起看
lk = wl.get("luck", {})
if "luck_effects" in lk:
    lk["luck_effects"] = {cn_if_en(k, LUCK_KEY): v for k, v in lk["luck_effects"].items()}
if "tv_fortune" in lk:
    lk["tv_fortune"] = {cn_if_en(k, LUCK_KEY): v for k, v in lk["tv_fortune"].items()}
if "luck_boost_items" in lk:
    # 是数组（物品列表），不是字典，原样保留
    pass

json.dump(wl, open(A + "weatherLuck.json", "w", encoding="utf-8"),
          ensure_ascii=False, separators=(",", ":"))

print("seasons:", list(seasons.keys()))
print("weather_effects:", list(eff.keys()))
print("luck_effects:", list(lk.get("luck_effects", {}).keys()))
print("tv_fortune:", list(lk.get("tv_fortune", {}).keys()))
print("春概率:", json.dumps(seasons.get("春", {}).get("probabilities"), ensure_ascii=False))
print("冬概率:", json.dumps(seasons.get("冬", {}).get("probabilities"), ensure_ascii=False))
print("夏概率:", json.dumps(seasons.get("夏", {}).get("probabilities"), ensure_ascii=False))

# 残留检查：只在值里找（键已中文化）
res = set()
def collect(o):
    if isinstance(o, dict):
        for v in o.values():
            collect(v)
    elif isinstance(o, list):
        for v in o:
            collect(v)
    elif isinstance(o, str):
        res.update(re.findall(r"[A-Za-z]{3,}", o))
collect(wl)
print("值里残留英文:", sorted(res) or "无")


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
