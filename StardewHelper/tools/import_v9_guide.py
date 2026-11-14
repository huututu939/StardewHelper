import json, io, sys, os, re
sys.stdout = io.TextIOWrapper(sys.stdout.buffer, encoding="utf-8")
V9 = _V9_DIR() + "/stardew_data/"
APP = _REPO_ROOT() + "/StardewHelper/app/src/main/assets/data/"

def unwrap(d):
    """剥掉 {meta, data} 包装，保留 meta 里的 module_name_cn"""
    if isinstance(d, dict) and "meta" in d and "data" in d:
        return d["data"], d["meta"].get("module_name_cn", "")
    return d, ""

for f, out in [
    ("skill_leveling.json", "skillLeveling.json"),
    ("foraging.json", "foraging.json"),
    ("pet_guide.json", "petGuide.json"),
    ("weather_luck.json", "weatherLuck.json"),
]:
    d = json.load(open(V9 + f, encoding="utf-8"))
    data, cn = unwrap(d)
    json.dump(data, open(APP + out, "w", encoding="utf-8"),
              ensure_ascii=False, separators=(",", ":"))
    print("%-22s -> %-24s %6.1f KB  (%s)" % (
        f, out, os.path.getsize(APP + out) / 1024, cn or "-"))
    if isinstance(data, dict):
        print("    顶层 key: %s" % list(data.keys()))

print()
print("已写入:", sorted([x for x in os.listdir(APP) if x in (
    "skillLeveling.json", "foraging.json", "petGuide.json", "weatherLuck.json")]))

# 导入的是 wiki 原始数据，采集和天气里全是英文 key——必须接着跑中文化，
# 否则 UI 会把它们直接当标签上屏（verify 第 10 节会红）。
# 本脚本幂等：重复运行 = 重新导入 + 重新中文化，不会叠加破坏。
import subprocess
HERE = os.path.dirname(os.path.abspath(__file__))
for script in ("localize_foraging.py", "localize_weather.py"):
    print()
    print("--- %s ---" % script)
    subprocess.run([sys.executable, os.path.join(HERE, script)], check=True)


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
