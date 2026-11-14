# -*- coding: utf-8 -*-
"""方向 E「像素故事墙」预览（2026-10-20 研究后做的）。

## 为什么有 E
用户否掉了「表格风」和「卡片墙」两版，说「不好看」。这次先上网研究再动手，
从 Steam Year in Review / Infinite Backlog Year in Review / 像素 UI 设计资料里
提取了五条能落地的做法，E 是按这五条重排的：

1. **竖版长图 = 几屏纵向堆叠，每屏一件事**（Steam / Spotify 回顾的共同做法）。
   深浅底交替 = 翻页感。原版把 30+ 个数字塞进一屏，就是"不好看"的根因。
2. **每个数字配一句换算吐槽**（Steam 给"年度最爱"配副标题的做法）。
   吐槽必须由真实数字算出来，不能编。
3. **可视化替代数字列表**：用分段像素块（Steam 的柱状图/热力图的像素版）。
4. **像素风的高级感来自约束**：有限色板（7 色）、4px 网格对齐、硬边框、零圆角。
5. **数据不够就整段不显示**（Infinite Backlog 的做法）。

## 数据
仍是真实存档 ff（1.5.6，Amy）。每天平均 = 该计数 ÷ 游戏内天数 258。
"""
import io
import os

# 复用 A~D 那套预览稿的页面外壳（同一套标题/说明/底注样式）
from gen_report_previews import page

_HERE = os.path.dirname(os.path.abspath(__file__))     # StardewHelper/tools
_APP = os.path.dirname(_HERE)                          # StardewHelper
ROOT = os.path.dirname(_APP)                           # 仓库根
OUT = os.path.join(ROOT, "报告预览")

# ── 真实数据（ff 档，1.5.6）────────────────────────────────────────────
DAYS = 258
D = {
    "farmer": "Amy", "farm": "ff", "farmType": "河流农场",
    "date": "第 3 年 · 夏 6 日", "gender": "女",
    "playTime": "100 小时 25 分", "playHours": 100.42,
    "days": "%d 天" % DAYS,
    "earned": "2,985,021", "money": "1,625,666",
    "bedtime": "23:50", "bedtimeHour": 23,
    "title": "勇闯矿洞的冒险家",
    "comment": "杀过 3437 只怪，矿井第 221 层以下你说了算。",
    "tags": ["腰缠万贯", "钓鱼佬", "勤劳种田人"],
    "egg": "你说最喜欢的是「关切的猿」——猿猴听到了会回你一句谢谢。",
    # 每天平均：计数 ÷ 游戏内天数
    "perDay": [
        ("战斗", "ach_combat", 3437, "只怪", "打 13 只怪"),
        ("耕种", "ach_crops", 2540, "颗种", "种 10 颗菜"),
        ("钓鱼", "ach_fishing", 2160, "次", "钓 8 次鱼"),
        ("采集", "ach_collect", 1707, "次", "采 6 样"),
        ("社交", "ach_social", 122, "份礼", "送 0.5 份礼"),
    ],
    "topMonsters": [("灰尘精灵", 443), ("红色泥浆", 345), ("冰冻史莱姆", 323)],
    "bestFriends": ["罗宾", "莱纳斯", "艾米丽"],
    "friendCount": 35,
    "jokes": {
        "time": "相当于连着种 4 天的地，中间没合过眼",
        "bed": "比镇上的鸡睡得还晚",
        "daily": "一天下来：打 13 只怪、种 10 颗菜、钓 8 次鱼",
    },
}

ICON = "../StardewHelper/app/src/main/assets/img/ui/"


def per_day_rows():
    """每天平均的行：(分组, 图标, 每天平均数, 单位, 吐槽短语)。"""
    out = []
    for g, ic, total, unit, joke in D["perDay"]:
        out.append((g, ic, total / float(DAYS), unit, joke))
    return out


CSS = """
.card{background:#F3E4C0;color:#3A2A18}
.screen{position:relative}
.dark{background:#3A2A18;color:#F3E4C0}
.light{background:#F3E4C0;color:#3A2A18}
.pad{padding:0 56px}

/* ── 屏 1 封面 ── */
.cover .trophy{position:absolute;right:56px;top:44px;width:112px;height:112px;image-rendering:pixelated}
.cover .kicker{font-size:24px;color:#C08A4E;padding-top:44px}
.cover .farm{font-size:72px;line-height:1.2;color:#FFE9C4;margin-top:10px}
.cover .sub{font-size:24px;color:#B49A72;margin-top:8px}
.cover .hr{height:3px;background:#A9640F;margin:26px 0 0}
.cover .q{font-size:24px;color:#C08A4E;margin-top:28px}
.cover .title{font-size:48px;color:#F2C069;margin:10px 0 12px}
.cover .cm{font-size:24px;color:#E8D6B0;line-height:1.6}
.cover .tags{margin-top:16px;display:flex;gap:10px;flex-wrap:wrap}
.cover .tags span{font-size:24px;color:#C08A4E;border:2px solid #6B4218;padding:4px 12px}

/* ── 屏 2 时光 ── */
.big{font-size:72px;line-height:1.1;color:#2E2115;margin-top:34px}
.biglabel{font-size:24px;color:#A08A66;margin-top:6px}
.joke{font-size:24px;color:#A9640F;margin-top:14px;line-height:1.6}
.three{display:grid;grid-template-columns:repeat(3,1fr);gap:24px;margin-top:40px}
.three .t{font-size:24px;color:#A08A66}
.three .v{font-size:36px;color:#2E2115;margin-top:4px;white-space:nowrap}
.hr2{height:2px;background:#C9AE7E;margin:32px 0 0}

/* ── 屏 3 作息 ── */
.clock .lab{font-size:24px;color:#C08A4E;margin-top:40px}
.clock .strip{display:flex;gap:4px;margin-top:14px}
.clock .c{flex:1;height:34px;background:#5A4326;border:2px solid #241708}
.clock .c.on{background:#A9640F;border-color:#F2C069}
.clock .scale{display:flex;justify-content:space-between;font-size:24px;color:#8A6E48;margin-top:8px}
.clock .big{color:#F2C069;margin-top:22px}

/* ── 屏 4 日常 ── */
.rows{margin-top:32px}
.row{display:grid;grid-template-columns:120px 1fr 150px;gap:16px;align-items:center;margin-bottom:20px}
.row .ic{width:36px;height:36px;display:inline-block;vertical-align:-8px;margin-right:10px}
.row .nm{font-size:24px;color:#3A2A18}
.row .bar{display:flex;gap:4px}
.row .b{width:22px;height:22px;background:#E0CB9C}
.row .b.on{background:#4F9A3D}
.row .v{font-size:24px;color:#2E2115;text-align:right;white-space:nowrap}
.row .v b{color:#A9640F}

/* ── 屏 5 人情（深底！）──
   ⚠ 深底上的文字必须用浅色。这里踩过：名字沿用浅屏的 #3A2A18，
   在深底上直接隐形（怪名/好友名整排看不见）。 */
.mon{display:flex;gap:24px;margin-top:28px;align-items:flex-end}
.mon .m{flex:1;text-align:center}
.mon .m .n{font-size:24px;color:#E8D6B0;margin-top:8px}
.mon .m .c{font-size:36px;color:#F2C069}
.mon .m .mm{font-size:24px;color:#8A6E48}
.fr{display:flex;gap:12px;margin-top:24px;align-items:center;flex-wrap:wrap}
.fr .f{background:#4A3722;border:2px solid #A9640F;padding:6px 12px;font-size:24px;color:#F3E4C0}
.egg{margin-top:28px;font-size:24px;color:#E8D6B0;line-height:1.6;border-left:6px solid #A9640F;padding-left:16px}
.foot{background:#241708;color:#B49A72;font-size:24px;padding:18px 56px;display:flex;justify-content:space-between}
"""


def build_e():
    b = ['<div class="card">']

    # ── 屏 1 封面（深）──
    b.append('<div class="screen dark cover"><div class="pad">'
             '<img class="trophy" src="%strophy.png">'
             '<div class="kicker">农场年度报告</div>'
             '<div class="farm">%s</div>'
             '<div class="sub">%s · %s · %s · %s</div>'
             '<div class="hr"></div>'
             '<div class="q">你是一个怎样的农民</div>'
             '<div class="title">★ %s ★</div>'
             '<div class="cm">%s</div>'
             '<div class="tags">%s</div>'
             '</div></div>' % (
                 ICON, D["farm"], D["farmer"], D["farmType"], D["date"], D["gender"],
                 D["title"], D["comment"],
                 "".join("<span>%s</span>" % t for t in D["tags"])))

    # ── 屏 2 时光（浅）──
    b.append('<div class="screen light pad">'
             '<div class="big">%s</div><div class="biglabel">游玩时长</div>'
             '<div class="joke">%s</div>'
             '<div class="three">'
             '<div><div class="t">游戏内天数</div><div class="v">%s</div></div>'
             '<div><div class="t">累计金币</div><div class="v">%s</div></div>'
             '<div><div class="t">当前金币</div><div class="v">%s</div></div>'
             '</div><div class="hr2"></div></div>' % (
                 D["playTime"], D["jokes"]["time"], D["days"], D["earned"], D["money"]))

    # ── 屏 3 作息（深）──
    cells = "".join('<div class="c%s"></div>' % (" on" if h == D["bedtimeHour"] else "")
                    for h in range(24))
    b.append('<div class="screen dark clock pad">'
             '<div class="lab">你的作息</div>'
             '<div class="strip">%s</div>'
             '<div class="scale"><span>0</span><span>6</span><span>12</span><span>18</span><span>24</span></div>'
             '<div class="big">%s</div><div class="biglabel">平均上床时间</div>'
             '<div class="joke">%s</div></div>' % (
                 cells, D["bedtime"], D["jokes"]["bed"]))

    # ── 屏 4 日常（浅）──
    rows = per_day_rows()
    mx = max(r[2] for r in rows)
    b.append('<div class="screen light pad"><div class="lab" '
             'style="font-size:24px;color:#A08A66;margin-top:34px">你的一天</div>'
             '<div class="rows">')
    for g, ic, per, unit, joke in rows:
        n = max(1, int(round(per / mx * 10)))
        bar = "".join('<div class="b%s"></div>' % (" on" if i < n else "") for i in range(10))
        b.append('<div class="row"><span class="nm"><img class="ic" src="%s%s.png">%s</span>'
                 '<span class="bar">%s</span>'
                 '<span class="v"><b>%s</b> %s/天</span></div>' % (
                     ICON, ic, g, bar, ("%.1f" % per).rstrip("0").rstrip("."), unit))
    b.append('</div><div class="joke">%s</div></div>' % D["jokes"]["daily"])

    # ── 屏 5 人情（深）──
    b.append('<div class="screen dark pad" style="padding-top:34px;padding-bottom:34px">'
             '<div class="lab" style="font-size:24px;color:#C08A4E">杀得最多的怪</div>'
             '<div class="mon">')
    for i, (n, c) in enumerate(D["topMonsters"]):
        b.append('<div class="m"><div class="c">%d</div><div class="n">%s</div></div>' % (c, n))
    b.append('</div>'
             '<div class="lab" style="font-size:24px;color:#C08A4E;margin-top:28px">最好的伙伴</div>'
             '<div class="fr">%s</div>'
             '<div class="egg" style="color:#E8D6B0;border-color:#A9640F">%s</div>'
             '</div>' % (
                 "".join("<span class='f'>%s</span>" % f for f in D["bestFriends"]),
                 D["egg"]))

    # ── 底栏 ──
    b.append('<div class="foot"><span>星露小助手 · 开源项目</span>'
             '<span>%s · %s</span></div>' % (D["farmer"], D["date"]))
    b.append('</div>')
    return "".join(b)


def main():
    os.makedirs(OUT, exist_ok=True)
    html = page(
        "方向 E · 像素故事墙",
        "按研究结论重排的一版：竖版长图 = 5 屏纵向堆叠，每屏一件事；每个数字配一句算出来的吐槽；"
        "日常用分段像素条代替数字列表；深浅底交替当翻页；7 色板 + 4px 网格 + 硬边框。",
        ["一屏一件事", "数字配吐槽", "分段像素条", "深浅底翻页", "7 色板 4px 网格"],
        build_e(), CSS)
    p = os.path.join(OUT, "E-像素故事墙.html")
    io.open(p, "w", encoding="utf-8", newline="").write(html)
    print("写入", p, os.path.getsize(p), "B")


if __name__ == "__main__":
    main()
