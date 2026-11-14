# -*- coding: utf-8 -*-
"""方向 F「鹈鹕镇特刊」与 G「成就墙」的预览稿（2026-10-20 第二轮研究后）。

## 这一轮研究的关键结论
查了 2025 年各家年度报告的做法（美团=新闻播报、小红书=奇怪列车、携程=旅行博物馆、
B站=时光大厦、网易云=年度角色、航旅纵横=趣味标签），共同规律是：

  **让报告"高级"的最大来源不是排版多精致，而是给整份报告一个统一的叙事装置。**
  数据是同一个数据，套上"新闻/列车/博物馆/短片"之后，观感完全不同。

于是做了两个新装置：

  F **鹈鹕镇特刊** —— 星露谷游戏里本来就有《鹈鹕镇新闻》（每季一期报纸，
    报道农场主的成就），这是最正宗的梗。报纸语言：报头、通栏双细线、栏目标题、
    大标题、导语、编者按。朱红 + 墨黑 + 报纸黄的经典报纸配色。

  G **成就墙** —— 12 枚游戏成就徽章（项目自带的 ach_*.png）对应 12 项数据，
    一个不多一个不少。徽章感 + 居中 + 大量留白。

像素图表的画法（来自 Pixel Art Infographic Creator 的规范）：
  柱状图 8px 宽 / 16px 间距；每个彩色元素 1-2px 深色描边；网格对齐；
  调色板控制在 5-7 色；文字只放关键词、字号大而醒目。
"""
import io
import os

from gen_report_previews import page

_HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(os.path.dirname(os.path.dirname(_HERE)), "报告预览")
ICON = "../StardewHelper/app/src/main/assets/img/ui/"

DAYS = 258
D = {
    "farmer": "Amy", "farm": "ff", "farmType": "河流农场",
    "date": "第 3 年 · 夏 6 日", "gender": "女",
    "playTime": "100 小时 25 分",
    "days": "%d 天" % DAYS,
    "earned": "2,985,021", "money": "1,625,666",
    "bedtime": "23:50", "bedtimeHour": 23,
    "title": "勇闯矿洞的冒险家",
    "comment": "杀过 3437 只怪，矿井第 221 层以下你说了算。",
    "tags": ["腰缠万贯", "钓鱼佬", "勤劳种田人"],
    "egg": "你说最喜欢的是「关切的猿」——猿猴听到了会回你一句谢谢。",
    "perDay": [
        ("战斗", "ach_combat", 13.3, "只怪/天"),
        ("耕种", "ach_crops", 9.8, "颗种/天"),
        ("钓鱼", "ach_fishing", 8.4, "次/天"),
        ("采集", "ach_collect", 6.6, "次/天"),
        ("社交", "ach_social", 0.5, "份礼/天"),
    ],
    "monsters": [("灰尘精灵", 443), ("红色泥浆", 345), ("冰冻史莱姆", 323)],
    "friends": ["罗宾", "莱纳斯", "艾米丽"],
    "friendCount": 35,
    "joke": "一天下来：打 13 只怪、种 10 颗菜、钓 8 次鱼",
}

# 12 枚成就徽章 = 12 项数据（项目自带的 ach_*.png 正好 12 张）
BADGES = [
    ("ach_combat", "3,437", "只怪", "战斗"),
    ("ach_crops", "2,540", "颗种", "耕种"),
    ("ach_fishing", "2,160", "次", "钓鱼"),
    ("ach_money", "2,985,021", "金币", "累计金币"),
    ("ach_crafting", "1,404", "件", "制作"),
    ("ach_collect", "1,707", "次", "采集"),
    ("ach_cooking", "13", "顿", "做饭"),
    ("ach_quest", "96", "个", "任务"),
    ("ach_social", "122", "份", "送礼"),
    ("ach_home", "258", "天", "游戏内天数"),
    ("ach_town", "35", "位", "认识的村民"),
    ("walnut", "62", "个", "金核桃"),
]


def clock_cells(hour):
    """24 格时钟 + 刻度。

    刻度用 **4 等分 + 末列右对齐**（0 / 6 / 12 / 18 / 24），
    而不是 24 等分 —— 「12」「18」两个字（24px 字号约 48px 宽）比 1/24 的列宽还宽，
    排成 24 列会互相压住、连成一串数字。
    """
    cells = "".join('<div class="c%s"></div>' % (" on" if h == hour else "") for h in range(24))
    ticks = "".join("<span>%s</span>" % t for t in ("0", "6", "12", "18", "24"))
    return cells, ticks


# ── F 鹈鹕镇特刊 ─────────────────────────────────────────────────────
# 字号口径（2026-10-20 改过一轮）：
#   画布 1080px 宽，在 6.1 寸 1080p 手机上是 155mm 宽 → 1px = 0.1435mm。
#   原来的正文 24px 在手机上只有 3.4mm（≈9.7pt），偏小、偏密，
#   在 27 寸显示器上却是 5.6mm —— **预览里看着舒服，手机上缩水 1.6 倍**，
#   这就是「预览好看、进游戏又不像」的主因。
#   所以正文提到 30px（手机上 4.31mm ≈ 12.2pt，和正常中文阅读一致）。
#   例外：`.stat .v.big` 用 40px 而不是 48px——「100 小时 25 分」在 48px 下
#   要占 8×48=384px，加上左边 4 字的标签 4×30=120px，共 504px，
#   超过栏宽 476px 会挤出栏外。40px 是 456px，刚好装下。
CSS_F = """
.card{background:#F2E8D2;color:#241C14}
.paper{padding:0 48px 40px}
.masthead{background:#241C14;padding:26px 48px 22px;text-align:center}
.masthead .t{font-size:60px;line-height:1.2;color:#F2E8D2;letter-spacing:2px}
.masthead .e{font-size:30px;line-height:1.3;color:#C9A46A;margin-top:8px}
.masthead .issue{font-size:30px;line-height:1.3;color:#C9A46A;margin-top:14px;
  border-top:2px solid #6B4A22;padding-top:14px}
.rule2{height:1px;background:#241C14;border-bottom:4px double #241C14;margin:0}
.kicker{font-size:30px;line-height:1.3;color:#8A6A45;margin-top:30px;text-align:center}
.headline{font-size:120px;line-height:1.05;color:#241C14;text-align:center;margin:8px 0 0}
.dek{font-size:30px;line-height:1.3;color:#5A4632;text-align:center;margin-top:12px}
.rule3{height:1px;background:#8A6A45;margin:26px 48px 0}
.col{display:grid;grid-template-columns:1fr 1fr;gap:0 32px;margin:24px 48px 0}
.col .h{font-size:30px;line-height:1.3;color:#B03A2E;font-weight:bold;margin-bottom:14px;
  border-bottom:2px solid #241C14;padding-bottom:8px}
.stat{border-bottom:1px dotted #B9A98C;padding:10px 0;display:flex;justify-content:space-between;align-items:baseline}
.stat .k{font-size:30px;line-height:1.3;color:#5A4632}
.stat .v{font-size:36px;line-height:1.3;color:#241C14}
.stat .v.big{font-size:40px}
.bar{display:flex;gap:4px;margin-top:8px}
.bar .b{width:22px;height:18px;background:#DCCDB0}
.bar .b.on{background:#B03A2E}
.lede{font-size:30px;line-height:1.6;color:#3A2E20;margin:24px 48px 0;text-indent:2em}
.editor{background:#EDE0C4;border-left:6px solid #B03A2E;padding:18px 22px;margin:24px 48px 0}
.editor .h{font-size:30px;line-height:1.3;color:#B03A2E;margin-bottom:10px}
.editor .t{font-size:30px;line-height:1.6;color:#3A2E20}
.clock{display:flex;gap:3px;margin-top:14px}
.clock .c{flex:1;height:30px;background:#DCCDB0;border:2px solid #8A6A45}
.clock .c.on{background:#B03A2E;border-color:#241C14}
.s{display:grid;grid-template-columns:repeat(4,1fr) auto;font-size:30px;line-height:1.3;color:#8A6A45;margin-top:8px}
.clock .s span{text-align:left;white-space:nowrap}
.clock .s span:last-child{text-align:right}
.list{font-size:30px;line-height:1.6;color:#3A2E20}
.list b{color:#241C14}
.foot{margin:30px 48px 0;border-top:2px solid #241C14;padding-top:14px;
  display:flex;justify-content:space-between;font-size:30px;line-height:1.3;color:#8A6A45}
"""


def build_f():
    b = ['<div class="card">']
    # 报头
    b.append('<div class="masthead"><div class="t">鹈鹕镇新闻</div>'
             '<div class="e">PELICAN TOWN GAZETTE</div>'
             '<div class="issue">%s · 农场主特刊</div></div>' % D["date"])
    b.append('<div class="rule2"></div>')
    # 头条
    b.append('<div class="paper">'
             '<div class="kicker">%s 的农场 · %s</div>'
             '<div class="headline">%s</div>'
             '<div class="dek">%s</div>' % (
                 D["farmer"], D["farmType"], D["farm"], D["title"]))
    b.append('<div class="rule3"></div>')
    # 导语
    b.append('<div class="lede">%s 本报获悉：这位农场主在 %d 个游戏日里种下 %s 颗种子、'
             '钓了 %s 次鱼、打倒 %s 只怪，最高下到矿井第 221 层。'
             '本刊特派员表示，鹈鹕镇终于有了一个像样的对手。</div>' % (
                 D["farmer"], DAYS, "2,540", "2,160", "3,437"))
    # 两栏
    b.append('<div class="col">')
    b.append('<div><div class="h">本期数字</div>'
             '<div class="stat"><span class="k">游玩时长</span><span class="v big">%s</span></div>'
             '<div class="stat"><span class="k">游戏内天数</span><span class="v">%s</span></div>'
             '<div class="stat"><span class="k">累计金币</span><span class="v">%s</span></div>'
             '<div class="stat"><span class="k">当前金币</span><span class="v">%s</span></div>'
             '<div class="stat"><span class="k">平均上床</span><span class="v">%s</span></div>'
             '</div>' % (D["playTime"], D["days"], D["earned"], D["money"], D["bedtime"]))
    b.append('<div><div class="h">本报评的每日产量</div>')
    mx = max(x[2] for x in D["perDay"])
    for g, ic, per, unit in D["perDay"]:
        n = max(1, int(round(per / mx * 10)))
        bar = "".join('<div class="b%s"></div>' % (" on" if i < n else "") for i in range(10))
        b.append('<div class="stat" style="display:grid;grid-template-columns:1fr auto;row-gap:6px">'
                 '<span class="k">%s</span><span class="v">%s %s</span>'
                 '<div class="bar" style="grid-column:1 / -1">%s</div></div>'
                 % (g, per, unit, bar))
    b.append('</div></div>')
    b.append('</div>')
    # 编者按
    b.append('<div class="editor"><div class="h">编者按</div>'
             '<div class="t">%s</div></div>' % D["joke"])
    # 作息
    cells, ticks = clock_cells(D["bedtimeHour"])
    b.append('<div class="col" style="grid-template-columns:1fr">'
             '<div><div class="h">作息时间表</div>'
             '<div class="clock">%s</div>'
             '<div class="s">%s</div>'
             '<div class="list" style="margin-top:10px">平均 <b>%s</b> 上床。</div>'
             '</div></div>' % (cells, ticks, D["bedtime"]))
    # 人情（怪名竖排，栏宽才放得下）
    b.append('<div class="col" style="grid-template-columns:1fr 1fr">'
             '<div><div class="h">手气最重的怪</div><div class="list">%s</div></div>'
             '<div><div class="h">关系最铁的人</div><div class="list">%s<br>共认识 %d 位村民</div></div>'
             '</div>' % (
                 "<br>".join("%s <b>%d</b>" % (n, c) for n, c in D["monsters"]),
                 "、".join(D["friends"]), D["friendCount"]))
    # 彩蛋
    b.append('<div class="editor"><div class="h">角落里的彩蛋</div>'
             '<div class="t">%s</div></div>' % D["egg"])
    b.append('<div class="foot"><span>星露小助手 · 开源项目</span>'
             '<span>%s · %s</span></div>' % (D["farmer"], D["date"]))
    b.append('</div></div>')
    return "".join(b)


# ── G 成就墙 ─────────────────────────────────────────────────────────
CSS_G = """
.card{background:#F3E4C0;color:#3A2A18}
.pad{padding:0 48px}
.top{text-align:center;padding:40px 48px 0}
.top .k{font-size:24px;color:#A9640F;letter-spacing:2px}
.top .farm{font-size:60px;color:#3A2A18;margin-top:8px}
.top .sub{font-size:24px;color:#8A6B44;margin-top:8px}
.ribbon{background:#3A2A18;color:#F3E4C0;text-align:center;padding:20px;margin:26px 48px 0}
.ribbon .t{font-size:36px;color:#F2C069}
.ribbon .c{font-size:24px;color:#E8D6B0;margin-top:8px}
.wall{display:grid;grid-template-columns:repeat(3,1fr);gap:16px;margin:26px 48px 0}
.medal{background:#FFF0D2;border:2px solid #6B4218;box-shadow:5px 5px 0 #C08A4E;
  padding:14px 12px 16px;text-align:center}
.medal .ic{width:64px;height:64px;margin:0 auto}
.medal .v{font-size:36px;color:#3A2A18;margin-top:8px;line-height:1.2}
.medal .u{font-size:24px;color:#A9640F}
.medal .n{font-size:24px;color:#8A6B44;margin-top:2px}
.sec{margin:30px 48px 0}
.sec .h{font-size:24px;color:#A9640F;border-bottom:2px solid #6B4218;padding-bottom:6px;
  margin-bottom:12px}
.clock{display:flex;gap:3px}
.clock .c{flex:1;height:24px;background:#E0CB9C;border:1px solid #A08A66}
.clock .c.on{background:#A9640F;border:2px solid #3A2A18}
.s{display:grid;grid-template-columns:repeat(4,1fr) auto;font-size:24px;color:#8A6B44;margin-top:6px}
.clock .s span{text-align:left;white-space:nowrap}
.clock .s span:last-child{text-align:right}
.tags{display:flex;gap:10px;margin-top:10px}
.tags span{font-size:24px;border:2px solid #A9640F;padding:4px 12px;color:#A9640F}
.egg{font-size:24px;line-height:1.6;color:#3A2A18;border-left:6px solid #A9640F;padding-left:16px}
.foot{background:#3A2A18;color:#B49A72;font-size:24px;padding:18px 48px;margin-top:30px;
  display:flex;justify-content:space-between}
"""


def build_g():
    b = ['<div class="card">']
    b.append('<div class="top"><div class="k">农场年度报告</div>'
             '<div class="farm">%s</div>'
             '<div class="sub">%s · %s · %s</div></div>' % (
                 D["farm"], D["farmer"], D["farmType"], D["date"]))
    b.append('<div class="ribbon"><div class="t">★ %s ★</div>'
             '<div class="c">%s</div>'
             '<div class="tags">%s</div></div>' % (
                 D["title"], D["comment"],
                 "".join("<span>%s</span>" % t for t in D["tags"])))
    b.append('<div class="wall">')
    for ic, v, u, n in BADGES:
        b.append('<div class="medal"><img class="ic" src="%s%s.png">'
                 '<div class="v">%s</div><div class="u">%s</div><div class="n">%s</div></div>'
                 % (ICON, ic, v, u, n))
    b.append('</div>')
    cells, ticks = clock_cells(D["bedtimeHour"])
    b.append('<div class="sec"><div class="h">作息时间表</div>'
             '<div class="clock">%s</div>'
             '<div class="s">%s</div>'
             '<div class="tags" style="margin-top:14px"><span>平均 %s 上床</span></div>'
             '</div>' % (cells, ticks, D["bedtime"]))
    b.append('<div class="sec"><div class="h">关系最铁的人</div>'
             '<div class="tags">%s</div>'
             '<div class="sec" style="margin-top:14px"><div class="h">手气最重的怪</div>'
             '<div class="tags">%s</div></div>'
             '<div class="egg" style="margin-top:20px">%s</div>'
             '</div>' % (
                 "".join("<span>%s</span>" % f for f in D["friends"]),
                 "".join("<span>%s %d</span>" % (n, c) for n, c in D["monsters"]),
                 D["egg"]))
    b.append('<div class="foot"><span>星露小助手 · 开源项目</span>'
             '<span>共认识 %d 位村民</span></div>' % D["friendCount"])
    b.append('</div>')
    return "".join(b)


def main():
    os.makedirs(OUT, exist_ok=True)
    for fn, title, desc, tags, body, css in (
        ("F-鹈鹕镇特刊.html", "方向 F · 鹈鹕镇特刊",
         "给整份报告一个叙事装置的第一版：星露谷游戏里本来就有《鹈鹕镇新闻》，"
         "于是把报告做成报纸特刊——报头、通栏双细线、两栏、导语、编者按。文字为主，朱红点缀。"
         "本轮字号已按**手机物理尺度**重定（正文 24px → 30px）：1080 宽的稿子在 6.1 寸手机上只有 155mm，"
         "24px 的字在手机上缩水到 3.4mm，看着碎；30px 是 4.3mm，和正常中文阅读一致。"
         "点页面顶部的「手机视图」可以直接按真机宽度看。",
         ["报纸隐喻", "游戏正统梗", "文字为主", "朱红 + 墨黑 + 报纸黄", "字号按真机尺度"],
         build_f(), CSS_F),
        ("G-成就墙.html", "方向 G · 成就墙",
         "另一个叙事装置：12 枚游戏成就徽章对应 12 项数据，一个不多一个不少。"
         "徽章感 + 居中 + 大量留白，像刚解锁的一整面奖杯墙。",
         ["徽章叙事", "12 图标 = 12 数据", "居中留白", "收集仪式感"],
         build_g(), CSS_G),
    ):
        p = os.path.join(OUT, fn)
        io.open(p, "w", encoding="utf-8", newline="").write(
            page(title, desc, tags, body, css))
        print("写入", p, os.path.getsize(p), "B")


if __name__ == "__main__":
    main()
