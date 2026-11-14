# -*- coding: utf-8 -*-
"""生成「农场年度报告」的设计方向 HTML 预览（选版用，不是 App 代码）。

用法：python tools/gen_report_previews.py
产物：仓库根的 `报告预览/`（index.html + A/B/C/D 四套方向）

## 为什么要有这个
用户 2026-10-20 反馈第一版报告长图「做得好难看呀」。**审美这事靠嘴说不清**，
所以先把四个方向各做成一版 HTML，用**真实存档数据** 1:1 复刻，让用户自己挑，
挑完再回 App 里写 Canvas 渲染（那时版式是照着用户点头的稿子写的，不是照着我猜的）。

## 数据是真的
全部取自仓库里的真实存档 `存档/ff_312128132.zip`（1.5.6，角色 Amy，农场 ff），
数值与 App 实际生成的报告逐项一致（已用独立脚本对照过）。不是示意图。

## 字体与图标
直接**引用仓库里的原件**（相对路径），不往预览目录拷二进制：
字体 6.8 MB，拷一次就多一份。

## 四个方向（2026-10-20 用户选了 C）
  A 像素仪表盘 —— 彩色数字格 + 24 格作息时钟 + 奖状称号 + 四色数据墙（信息最全，最闹）
  B 极简海报 —— 零格子零色块零图标，只靠字号层级/细线/留白（高级感最强）
  C 像素卡片墙 —— 游戏成就面板：每项一张独立小卡片 + 硬边像素阴影（**用户选的**）
  D 简约×像素 —— B 的骨架 + A 的小件

## 之后想改版式
改这个脚本 → 重跑 → 看稿子 → 用户点头 → 再改 App 的 ReportCanvas.kt。
"""
import io
import os

_HERE = os.path.dirname(os.path.abspath(__file__))     # StardewHelper/tools
_APP = os.path.dirname(_HERE)                          # StardewHelper
ROOT = os.path.dirname(_APP)                           # 仓库根
OUT = os.path.join(ROOT, "报告预览")

# ── 真实数据（ff 档，1.5.6）────────────────────────────────────────────
D = {
    "farmer": "Amy",
    "farm": "ff",
    "farmType": "河流农场",
    "date": "第 3 年 · 夏 6 日",
    "gender": "女",
    "playTime": "100 小时 25 分",
    "days": "258 天",
    "earned": "2,985,021",
    "money": "1,625,666",
    "bedtime": "23:50",
    "title": "勇闯矿洞的冒险家",
    "comment": "杀过 3437 只怪，矿井第 221 层以下你说了算。",
    "tags": ["腰缠万贯", "钓鱼佬", "勤劳种田人"],
    "egg": "你说最喜欢的是「关切的猿」——猿猴听到了会回你一句谢谢。",
    "chips": ["河流农场", "单身", "最深层 221"],
    "groups": [
        ("农业", "ach_crops", "crops", [
            ("播种", "2,540"), ("作物出货", "5,038"), ("总出货", "16,948"),
            ("锄地", "1,516"), ("采集", "1,707"), ("合成", "1,404"),
            ("做饭", "13"), ("砍树桩", "261"),
            ("矿石", "铜 1,424 · 铁 1,109 · 金 728 · 铱 3,972"),
        ]),
        ("探险", "ach_combat", "combat", [
            ("杀怪", "3,437"), ("史莱姆", "1,103"), ("最深层", "221"),
            ("到矿底", "4"), ("敲石", "6,273"), ("砸晶球", "223"),
            ("捡石头", "5,424"), ("挖到的钻石", "39"),
            ("最爱杀", "灰尘精灵 443 · 红色泥浆 345 · 冰冻史莱姆 323"),
        ]),
        ("生活", "ach_home", "home", [
            ("游戏天数", "258"), ("平均睡觉", "23:50"), ("步数", "248,648"),
            ("钓鱼次数", "2,160"), ("钓到的鱼", "1,773"), ("挖松露", "107"),
            ("除草", "78"), ("晕倒", "15"), ("翻垃圾桶", "250"),
        ]),
        ("社交", "ach_social", "social", [
            ("送礼", "122"), ("完成任务", "96"), ("秘密纸条", "20"),
            ("金核桃", "62"),
            ("最好的伙伴", "罗宾、莱纳斯、艾米丽（认识 35 位）"),
        ]),
    ],
    "headIcons": [("游玩时长", "ach_home"), ("游戏内天数", "ach_quest"),
                  ("累计金币", "ach_money"), ("当前金币", "ach_collect")],
    "headValues": [("游玩时长", "100 小时 25 分"), ("游戏内天数", "258 天"),
                   ("累计金币", "2,985,021"), ("当前金币", "1,625,666")],
}

# 值的「长度」决定它能不能待在窄格子里。
# 像素字体是等宽的，10 个字符约 240px，超过就换行会挤坏版式 —— 长值一律独占整行。
LONG_VAL = 10


def is_long(v):
    return len(v) > LONG_VAL


def split_rows(rows):
    """长值单独拎出来（顺序不变：短值在前、长值在后）。"""
    short = [(k, v) for k, v in rows if not is_long(v)]
    long_ = [(k, v) for k, v in rows if is_long(v)]
    return short, long_


# ── 公共外壳 ───────────────────────────────────────────────────────────
BASE_CSS = """
@font-face{font-family:'FusionPixel';src:url('../StardewHelper/tools/fonts/fusion_pixel.ttf')format('truetype');
  font-weight:400;font-style:normal;font-display:block}
*{box-sizing:border-box;margin:0;padding:0}
body{background:#221E1A;color:#EFE3C8;font-family:'FusionPixel',monospace;
  -webkit-font-smoothing:none;font-smooth:never;
  padding:0 0 80px;letter-spacing:0}
img{image-rendering:pixelated;display:block}
.wrap{width:1080px;margin:0 auto}
.notice{width:1080px;margin:0 auto;padding:28px 0 18px}
.notice h1{font-size:36px;line-height:1.4;color:#F2C069;font-weight:400}
.notice p{font-size:24px;line-height:1.6;color:#C9B896;margin-top:10px}
.notice .tags{margin-top:14px;display:flex;gap:10px;flex-wrap:wrap}
.notice .tags span{font-size:24px;padding:5px 12px;border:2px solid #5A4A36;color:#D8C7A4}
.notice .src{font-size:24px;color:#8E7F66;margin-top:16px}
.note{width:1080px;margin:0 auto;padding:16px 0;font-size:24px;color:#8E7F66}
.phonebar{width:1080px;margin:0 auto;padding:14px 0 0;display:flex;gap:14px;align-items:center}
.phonebar button{font-family:'FusionPixel',monospace;font-size:24px;line-height:1;
  color:#EFE3C8;background:#3A322A;border:2px solid #5A4A36;padding:11px 16px;cursor:pointer}
.phonebar .hint{font-size:24px;color:#8E7F66}
/* 手机视图：393 CSS px = 6.1 寸 1080p 手机的逻辑宽度。
   zoom 让 1080 宽的稿子在 393 宽里「重新排版」（文字仍清晰，只是整体变小），
   对应真机上「同样的字、物理尺寸只有一半」这件事。
   真机上位图是 1:1 像素映射（字更清楚），浏览器里只能近似。 */
body.phone{width:393px;margin:0 auto;background:#141110}
body.phone .zoomwrap{zoom:0.363889;width:1080px}
body.phone .phonebar{width:393px;position:sticky;top:0;z-index:9;background:#141110;
  padding:10px 0}
body.phone .phonebar button{font-size:15px;padding:7px 10px}
body.phone .phonebar .hint{display:none}
"""


def page(title, desc, tags, body_html, extra_css=""):
    return """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=1080">
<title>%s · 星露小助手 农场年度报告 预览</title>
<style>%s%s</style></head>
<body>
<div class="notice">
  <h1>%s</h1>
  <p>%s</p>
  <div class="tags">%s</div>
  <div class="src">数据取自你的真实存档 ff（1.5.6，角色 Amy）· 1080 宽竖版长图</div>
</div>
<div class="phonebar">
  <button onclick="togglePhone()">手机视图</button>
  <span class="hint">切到手机视图 = 按 393 CSS px 宽（6.1 寸 1080p 真机）重排后的样子。手机上看同一张图，字只有电脑上的约 60%%，这才是真机观感。</span>
</div>
<div class="zoomwrap">
<div class="wrap">
%s
</div>
</div>
<div class="note">← 回到 <a href="index.html" style="color:#F2C069">方向总览</a> ｜ 这一页是 1:1 真实比例，浏览器缩放会影响观感，建议 100%% 缩放看</div>
<script>
function togglePhone(){
  document.body.classList.toggle('phone');
  var on = document.body.classList.contains('phone');
  document.querySelector('.phonebar button').textContent = on ? '桌面视图' : '手机视图';
}
if (location.hash === '#phone') {
  document.body.classList.add('phone');
  document.querySelector('.phonebar button').textContent = '桌面视图';
}
</script>
</body></html>
""" % (title, BASE_CSS, extra_css, title, desc,
       "".join("<span>%s</span>" % t for t in tags), body_html)


# ── 方向 A：像素仪表盘（游戏 UI 感）─────────────────────────────────────
def build_a():
    css = """
.card{background:#F8E7C0;border:3px solid #6B4218;padding-bottom:40px}
.bar{height:18px;background:#A9640F}
.hero{position:relative;padding:28px 44px 0}
.hero .tag{font-size:24px;color:#A9640F}
.hero .farm{font-size:60px;line-height:1.25;color:#4A2C12;margin-top:4px}
.hero .sub{font-size:24px;color:#7E5B31;margin-top:8px}
.hero .badge{position:absolute;right:44px;top:24px;width:96px;height:96px}
.rule{height:3px;background:#6B4218;margin:22px 44px 0;position:relative}
.rule:after{content:'';position:absolute;left:0;right:0;top:3px;height:3px;background:#A9640F}
.sect{padding:22px 44px 0}
.bigs{display:grid;grid-template-columns:1fr 1fr;gap:16px}
.big{background:#FFE9C4;border:2px solid #6B4218;padding:14px 16px 16px;position:relative}
.big .lab{font-size:24px;color:#7E5B31;display:flex;align-items:center;gap:10px}
.big .lab img{width:40px;height:40px}
.big .val{font-size:48px;color:#4A2C12;margin-top:10px;line-height:1.2}
.clock .lab{font-size:24px;color:#7E5B31}
.clock .strip{display:flex;gap:2px;margin-top:10px;justify-content:center}
.clock .cell{width:36px;height:30px;background:#EECF8F;border:1px solid #8A5A28}
.clock .cell.on{background:#A9640F;border:2px solid #4A2C12}
.clock .scale{display:grid;grid-template-columns:repeat(5,1fr);font-size:24px;color:#7E5B31;margin-top:6px}
.clock .scale span{text-align:center}
.clock .scale span:first-child{text-align:left}
.clock .scale span:last-child{text-align:right}
.clock .tip{font-size:24px;color:#4A2C12;text-align:center;margin-top:6px}
.award{background:#FFE9C4;border:3px solid #A9640F;padding:20px 28px 22px;text-align:center;position:relative}
.award:before{content:'';position:absolute;left:7px;right:7px;top:7px;bottom:7px;border:1px solid #A9640F}
.award .k{font-size:24px;color:#7E5B31}
.award .t{font-size:48px;color:#A9640F;margin:12px 0 10px}
.award .t .st{color:#A9640F;margin:0 14px}
.award .c{font-size:24px;color:#4A2C12}
.award .tags{margin-top:14px;display:flex;gap:8px;justify-content:center}
.award .tags span{font-size:24px;background:#D9BE83;border:2px solid #A9640F;padding:5px 12px;color:#4A2C12}
.grp{margin-top:22px}
.grp .bar2{background:#4F9A3D;height:52px;display:flex;align-items:center;gap:12px;
  padding:0 10px;border:2px solid #6B4218;color:#fff}
.grp .bar2 img{width:40px;height:40px}
.grp .bar2 span{font-size:36px}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:10px}
.cellc{background:#DCE9C8;border:2px solid #4F9A3D;padding:10px 12px 12px}
.cellc .k{font-size:24px;color:#7E5B31}
.cellc .v{font-size:30px;color:#4A2C12;margin-top:4px}
.long{grid-column:1 / -1}
.combat .cellc{background:#F2D6CE;border-color:#C44538}
.combat .bar2{background:#C44538}
.home .cellc{background:#D6E4F0;border-color:#3F7FB5}
.home .bar2{background:#3F7FB5}
.social .cellc{background:#E6DBF2;border-color:#8E6BBF}
.social .bar2{background:#8E6BBF}
.egg{background:#FFE9C4;border:2px solid #E9D3A0;padding:16px 20px;margin-top:22px;
  display:flex;gap:14px;align-items:stretch}
.egg .barq{width:8px;background:#7E5B31}
.egg .tx{font-size:24px;color:#4A2C12;line-height:1.5}
.foot{height:62px;background:#6B4218;margin-top:26px;display:flex;align-items:center;
  justify-content:center;color:#FFE9C4;font-size:24px;border-top:3px solid #A9640F}
"""
    b = ['<div class="card"><div class="bar"></div>']
    b.append('<div class="hero"><img class="badge" src="../StardewHelper/app/src/main/assets/img/ui/trophy.png">'
             '<div class="tag">农场年度报告</div>'
             '<div class="farm">%s</div>'
             '<div class="sub">%s · %s · %s · %s</div></div>' %
             (D["farm"], D["farmer"], D["farmType"], D["date"], D["gender"]))
    b.append('<div class="rule"></div>')
    b.append('<div class="sect"><div class="bigs">')
    for (lab, val), (lab2, ic) in zip(D["headValues"], D["headIcons"]):
        b.append('<div class="big"><div class="lab"><img src="../StardewHelper/app/src/main/assets/img/ui/%s.png">%s</div>'
                 '<div class="val">%s</div></div>' % (ic, lab, val))
    b.append('</div></div>')
    cells = "".join('<div class="cell%s"></div>' % (" on" if h == 23 else "") for h in range(24))
    b.append('<div class="sect clock"><div class="lab">作息</div>'
             '<div class="strip">%s</div>'
             '<div class="scale"><span>0</span><span>6</span><span>12</span><span>18</span><span>24</span></div>'
             '<div class="tip">平均 %s 上床，晚睡</div></div>' % (cells, D["bedtime"]))
    b.append('<div class="sect"><div class="award"><div class="k">你是一个怎样的农民</div>'
             '<div class="t"><span class="st">★</span>%s<span class="st">★</span></div>'
             '<div class="c">%s</div><div class="tags">%s</div></div></div>' %
             (D["title"], D["comment"],
              "".join("<span>%s</span>" % t for t in D["tags"])))
    for gname, gicon, gcls, rows in D["groups"]:
        b.append('<div class="sect grp %s"><div class="bar2"><img src="../StardewHelper/app/src/main/assets/img/ui/%s.png"><span>%s</span></div>'
                 '<div class="grid">' % (gcls, gicon, gname))
        for k, v in rows:
            long = is_long(v)
            b.append('<div class="cellc%s"><div class="k">%s</div><div class="v">%s</div></div>'
                     % (" long" if long else "", k, v))
        b.append('</div></div>')
    b.append('<div class="sect egg"><div class="barq"></div><div class="tx">%s</div></div>' % D["egg"])
    b.append('<div class="foot">星露小助手 · 开源项目</div>')
    b.append('</div>')
    return page(
        "方向 A · 像素仪表盘",
        "最像游戏 UI 的一版：大标题 + 彩色数字格 + 24 格作息时钟 + 奖状称号 + 四色数据墙。信息密度最高，最像游戏里点开的一页统计。",
        ["信息密度高", "游戏 UI 感", "四色分区", "睡眠做成时钟"],
        "".join(b), css)


# ── 方向 B：极简海报 ───────────────────────────────────────────────────
def build_b():
    css = """
.card{background:#FBF6EA;padding:60px 72px 56px;color:#3A2A18}
.kicker{display:flex;justify-content:space-between;align-items:baseline}
.kicker .k1{font-size:24px;color:#A9640F}
.kicker .k2{font-size:24px;color:#A89A80}
.farm{font-size:120px;line-height:1.05;color:#2E2115;margin-top:46px}
.hr{height:1px;background:#E0D5BE;margin:26px 0 0}
.sub{font-size:24px;color:#8A7A63;margin-top:20px}
.nums{display:grid;grid-template-columns:1.7fr 1fr 1fr 1fr;margin-top:74px}
.nums .n{padding-right:24px;border-right:1px solid #E0D5BE}
.nums .n:last-child{border-right:0}
.nums .v{font-size:48px;color:#2E2115;line-height:1.2;white-space:nowrap}
.nums .l{font-size:24px;color:#A89A80;margin-top:8px}
.title{font-size:72px;color:#A9640F;margin-top:78px;line-height:1.15}
.comment{font-size:24px;color:#7A6B55;margin-top:16px;line-height:1.6}
.tags{font-size:24px;color:#A9640F;margin-top:14px}
.sec{margin-top:52px}
.sec .sh{display:flex;align-items:center;gap:14px;font-size:36px;color:#2E2115}
.sec .sh i{display:block;width:12px;height:12px;background:#A9640F;font-style:normal}
.sec .sh b{flex:1;height:1px;background:#E0D5BE;font-weight:400}
.kv{display:grid;grid-template-columns:auto 1fr auto 1fr;column-gap:18px;row-gap:0;
  margin-top:16px;font-size:24px}
.kv .k{color:#A89A80;padding:9px 0;border-bottom:1px dashed #E7DECA}
.kv .v{color:#2E2115;padding:9px 0;border-bottom:1px dashed #E7DECA;text-align:right}
.kv .wide{grid-column:1 / -1}
.kv .wide.k{grid-column:1 / 2}
.kv .wide.v{grid-column:2 / -1;text-align:left;padding-left:18px}
.sleep{margin-top:52px}
.sleep .lab{font-size:24px;color:#8A7A63}
.sleep .bar{height:6px;background:#EDE3CE;margin-top:14px;position:relative}
.sleep .bar i{position:absolute;left:0;top:0;bottom:0;width:99%;background:#A9640F}
.sleep .bar u{position:absolute;right:-1px;top:-6px;width:4px;height:18px;background:#2E2115;text-decoration:none}
.sleep .txt{font-size:24px;color:#7A6B55;margin-top:14px}
.egg{font-size:24px;color:#7A6B55;line-height:1.6;margin-top:34px}
.foot{margin-top:54px;padding-top:20px;border-top:1px solid #E0D5BE;
  display:flex;justify-content:space-between;font-size:24px;color:#B5A78D}
"""
    b = ['<div class="card">']
    b.append('<div class="kicker"><span class="k1">农场年度报告</span>'
             '<span class="k2">%s</span></div>' % D["date"])
    b.append('<div class="farm">%s</div>' % D["farm"])
    b.append('<div class="hr"></div>')
    b.append('<div class="sub">%s · %s · %s</div>' % (D["farmer"], D["farmType"], D["gender"]))
    b.append('<div class="nums">')
    for lab, val in D["headValues"]:
        b.append('<div class="n"><div class="v">%s</div><div class="l">%s</div></div>' % (val, lab))
    b.append('</div>')
    b.append('<div class="title">★ %s ★</div>' % D["title"])
    b.append('<div class="comment">%s</div>' % D["comment"])
    b.append('<div class="tags">%s</div>' % " · ".join(D["tags"]))
    for gname, gicon, gcls, rows in D["groups"]:
        b.append('<div class="sec"><div class="sh"><i></i>%s<b></b></div>' % gname)
        b.append('<div class="kv">')
        short, long_ = split_rows(rows)
        for k, v in short:
            b.append('<div class="k">%s</div><div class="v">%s</div>' % (k, v))
        for k, v in long_:
            b.append('<div class="k wide">%s</div><div class="v wide">%s</div>' % (k, v))
        b.append('</div></div>')
    b.append('<div class="sleep"><div class="lab">平均 %s 上床</div>'
             '<div class="bar"><i></i><u></u></div>'
             '<div class="txt">一天 24 格，深夜那格亮着。</div></div>' % D["bedtime"])
    b.append('<div class="egg">%s</div>' % D["egg"])
    b.append('<div class="foot"><span>星露小助手 · 开源项目</span>'
             '<span>Pelican Town</span></div>')
    b.append('</div>')
    return page(
        "方向 B · 极简海报",
        "把像素风做到最克制的一版：没有格子、没有色块、没有图标，只有字号层级、细线和大量留白。像一张杂志内页，靠排版而不是装饰撑起来。",
        ["留白多", "零色块零边框", "只有墨色 + 一个强调色", "高级感最强"],
        "".join(b), css)


# ── 方向 C：像素卡片墙 ─────────────────────────────────────────────────
def build_c():
    css = """
.card{background:#F3E4C0;border:4px solid #6B4218;padding-bottom:36px}
.top{background:#4A2C12;padding:16px 24px;display:flex;align-items:center;gap:16px}
.top .tt{font-size:36px;color:#FFE9C4}
.top .sp{flex:1}
.top .tg{width:96px;height:96px}
.sect{padding:24px 28px 0}
.bigs{display:grid;grid-template-columns:repeat(4,1fr);gap:14px}
.big{background:#FFE9C4;border:2px solid #4A2C12;box-shadow:5px 5px 0 #8A5A28;padding:12px}
.big .ic{width:48px;height:48px;margin-bottom:8px}
.big .v{font-size:30px;color:#4A2C12;line-height:1.25;white-space:nowrap}
.big .l{font-size:24px;color:#8A6B44;margin-top:4px}
.sect .h{display:flex;align-items:center;gap:12px;margin:26px 0 12px}
.sect .h img{width:40px;height:40px}
.sect .h span{font-size:36px;color:#4A2C12}
.sect .h b{flex:1;height:2px;background:#C08A4E}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:12px}
.c{background:#FFF0D2;border:2px solid #4A2C12;box-shadow:4px 4px 0 #8A5A28;padding:10px 12px 12px}
.c .k{font-size:24px;color:#8A6B44}
.c .v{font-size:30px;color:#4A2C12;margin-top:2px;line-height:1.3}
.c.wide{grid-column:1 / -1}
.g-crops .c{background:#E4EFD2;border-color:#4F9A3D;box-shadow:4px 4px 0 #A9C896}
.g-crops .h span{color:#3E7A2F}
.g-combat .c{background:#F7E2DA;border-color:#C44538;box-shadow:4px 4px 0 #DF9A8F}
.g-combat .h span{color:#A63A2E}
.g-home .c{background:#E1ECF6;border-color:#3F7FB5;box-shadow:4px 4px 0 #9CC0DF}
.g-home .h span{color:#2F6795}
.g-social .c{background:#EDE4F7;border-color:#8E6BBF;box-shadow:4px 4px 0 #BFA8D9}
.g-social .h span{color:#75529F}
.sleepcard{background:#FFF0D2;border:2px solid #4A2C12;box-shadow:5px 5px 0 #8A5A28;
  padding:14px 16px 16px;margin-top:12px}
.sleepcard .k{font-size:24px;color:#8A6B44}
.sleepcard .strip{display:flex;gap:3px;margin-top:10px}
.sleepcard .cell{flex:1;height:26px;background:#EECF8F;border:1px solid #8A5A28}
.sleepcard .cell.on{background:#A9640F;border:2px solid #4A2C12}
.sleepcard .txt{font-size:24px;color:#4A2C12;margin-top:10px}
.ribbon{background:#4A2C12;border:2px solid #4A2C12;box-shadow:6px 6px 0 #8A5A28;
  padding:20px 24px 22px;margin-top:26px;text-align:center}
.ribbon .k{font-size:24px;color:#C08A4E}
.ribbon .t{font-size:48px;color:#F2C069;margin:8px 0}
.ribbon .c{font-size:24px;color:#E8D6B0}
.ribbon .tags{margin-top:12px;font-size:24px;color:#C08A4E}
.bubble{background:#FFF0D2;border:2px solid #4A2C12;box-shadow:5px 5px 0 #8A5A28;
  padding:14px 18px;margin-top:22px;position:relative;display:flex;gap:12px}
.bubble i{width:8px;background:#8A6B44;display:block}
.bubble .tx{font-size:24px;color:#4A2C12;line-height:1.5}
.foot{margin-top:28px;background:#4A2C12;color:#E8D6B0;font-size:24px;padding:14px 24px;
  display:flex;justify-content:space-between}
"""
    b = ['<div class="card">']
    b.append('<div class="top"><span class="tt">农场年度报告</span><span class="sp"></span>'
             '<img class="tg" src="../StardewHelper/app/src/main/assets/img/ui/trophy.png"></div>')
    b.append('<div class="sect"><div class="bigs">')
    for (lab, val), (lab2, ic) in zip(D["headValues"], D["headIcons"]):
        b.append('<div class="big"><img class="ic" src="../StardewHelper/app/src/main/assets/img/ui/%s.png">'
                 '<div class="v">%s</div><div class="l">%s</div></div>' % (ic, val, lab))
    b.append('</div></div>')
    for gname, gicon, gcls, rows in D["groups"]:
        b.append('<div class="sect g-%s"><div class="h"><img src="../StardewHelper/app/src/main/assets/img/ui/%s.png">'
                 '<span>%s</span><b></b></div>' % (gcls, gicon, gname))
        b.append('<div class="grid">')
        for k, v in rows:
            b.append('<div class="c%s"><div class="k">%s</div><div class="v">%s</div></div>'
                     % (" wide" if is_long(v) else "", k, v))
        b.append('</div></div>')
    cells = "".join('<div class="cell%s"></div>' % (" on" if h == 23 else "") for h in range(24))
    b.append('<div class="sect"><div class="sleepcard"><div class="k">作息 · 平均 %s 上床</div>'
             '<div class="strip">%s</div><div class="txt">一天 24 格，深夜那格亮着。</div>'
             '</div></div>' % (D["bedtime"], cells))
    b.append('<div class="sect"><div class="ribbon"><div class="k">你是一个怎样的农民</div>'
             '<div class="t">★ %s ★</div><div class="c">%s</div>'
             '<div class="tags">%s</div></div></div>' %
             (D["title"], D["comment"], " · ".join(D["tags"])))
    b.append('<div class="sect"><div class="bubble"><i></i><div class="tx">%s</div></div></div>' % D["egg"])
    b.append('<div class="foot"><span>星露小助手 · 开源项目</span>'
             '<span>%s · %s</span></div>' % (D["farmer"], D["date"]))
    b.append('</div>')
    return page(
        "方向 C · 像素卡片墙",
        "游戏成就/收集面板那一套：每项数据都是一张独立小卡片，带硬边像素阴影，四类颜色区分。最有「一屏一屏翻」的收集感。",
        ["卡片多", "硬阴影像素感", "分类四色", "收集面板感", "用户已选"],
        "".join(b), css)


# ── 方向 D：简约 × 像素（混合）────────────────────────────────────────
def build_d():
    css = """
.card{background:#FBF6EA;padding:56px 64px 52px;color:#3A2A18}
.kicker{display:flex;justify-content:space-between;align-items:center}
.kicker .l{display:flex;align-items:center;gap:14px}
.kicker .k1{font-size:24px;color:#A9640F}
.kicker .tg{width:48px;height:48px}
.kicker .k2{font-size:24px;color:#A89A80}
.farm{font-size:96px;line-height:1.05;color:#2E2115;margin-top:40px}
.hr{height:1px;background:#E0D5BE;margin:24px 0 0}
.sub{font-size:24px;color:#8A7A63;margin-top:18px}
.nums{display:grid;grid-template-columns:1.8fr 1fr 1fr 1fr;gap:14px;margin-top:56px}
.nums .n{background:#F3E9D4;border:2px solid #8A6B44;padding:12px 14px 14px;position:relative}
.nums .n:before{content:'';position:absolute;left:3px;top:3px;width:calc(100% - 6px);height:3px;
  background:rgba(255,255,255,.6)}
.nums .v{font-size:36px;color:#2E2115;line-height:1.2;white-space:nowrap}
.nums .l{font-size:24px;color:#A89A80;margin-top:6px}
.clock{margin-top:44px}
.clock .lab{font-size:24px;color:#8A7A63}
.clock .strip{display:flex;gap:2px;margin-top:10px}
.clock .cell{flex:1;height:22px;background:#E3D5B4;border:1px solid #B9A886}
.clock .cell.on{background:#A9640F;border:2px solid #4A2C12}
.clock .txt{font-size:24px;color:#7A6B55;margin-top:12px}
.title{font-size:72px;color:#A9640F;margin-top:60px;line-height:1.15}
.comment{font-size:24px;color:#7A6B55;margin-top:16px;line-height:1.6}
.tags{font-size:24px;color:#A9640F;margin-top:14px}
.sec{margin-top:48px}
.sec .sh{display:flex;align-items:center;gap:14px;font-size:36px;color:#2E2115}
.sec .sh i{display:block;width:12px;height:12px;background:#A9640F;font-style:normal}
.sec .sh b{flex:1;height:1px;background:#E0D5BE;font-weight:400}
.kv{display:grid;grid-template-columns:auto 1fr auto 1fr;column-gap:18px;row-gap:0;
  margin-top:16px;font-size:24px}
.kv .k{color:#A89A80;padding:9px 0;border-bottom:1px dashed #E7DECA}
.kv .v{color:#2E2115;padding:9px 0;border-bottom:1px dashed #E7DECA;text-align:right}
.kv .wide{grid-column:1 / -1}
.kv .wide.k{grid-column:1 / 2}
.kv .wide.v{grid-column:2 / -1;text-align:left;padding-left:18px}
.egg{font-size:24px;color:#7A6B55;line-height:1.6;margin-top:30px}
.foot{margin-top:48px;padding-top:20px;border-top:1px solid #E0D5BE;
  display:flex;justify-content:space-between;font-size:24px;color:#B5A78D}
"""
    b = ['<div class="card">']
    b.append('<div class="kicker"><span class="l"><span class="k1">农场年度报告</span>'
             '<img class="tg" src="../StardewHelper/app/src/main/assets/img/ui/trophy.png"></span>'
             '<span class="k2">%s</span></div>' % D["date"])
    b.append('<div class="farm">%s</div>' % D["farm"])
    b.append('<div class="hr"></div>')
    b.append('<div class="sub">%s · %s · %s</div>' % (D["farmer"], D["farmType"], D["gender"]))
    b.append('<div class="nums">')
    for lab, val in D["headValues"]:
        b.append('<div class="n"><div class="v">%s</div><div class="l">%s</div></div>' % (val, lab))
    b.append('</div>')
    cells = "".join('<div class="cell%s"></div>' % (" on" if h == 23 else "") for h in range(24))
    b.append('<div class="clock"><div class="lab">作息 · 平均 %s 上床</div>'
             '<div class="strip">%s</div>'
             '<div class="txt">一天 24 格，深夜那格亮着。</div></div>' % (D["bedtime"], cells))
    b.append('<div class="title">★ %s ★</div>' % D["title"])
    b.append('<div class="comment">%s</div>' % D["comment"])
    b.append('<div class="tags">%s</div>' % " · ".join(D["tags"]))
    for gname, gicon, gcls, rows in D["groups"]:
        b.append('<div class="sec"><div class="sh"><i></i>%s<b></b></div>' % gname)
        b.append('<div class="kv">')
        short, long_ = split_rows(rows)
        for k, v in short:
            b.append('<div class="k">%s</div><div class="v">%s</div>' % (k, v))
        for k, v in long_:
            b.append('<div class="k wide">%s</div><div class="v wide">%s</div>' % (k, v))
        b.append('</div></div>')
    b.append('<div class="egg">%s</div>' % D["egg"])
    b.append('<div class="foot"><span>星露小助手 · 开源项目</span>'
             '<span>Pelican Town</span></div>')
    b.append('</div>')
    return page(
        "方向 D · 简约 × 像素（混合）",
        "拿 B 的骨架换掉 A 的闹：留白、细线、零色块都留着，只把「像素感」收进几个小件——"
        "奖杯图标、四个像素槽位、24 格作息条、橙色小方块。",
        ["B 的留白 + A 的像素件", "克制但有像素味"],
        "".join(b), css)


# ── 索引页 ─────────────────────────────────────────────────────────────
def build_index():
    css = """
body{background:#221E1A;color:#EFE3C8;font-family:'FusionPixel',monospace;
  -webkit-font-smoothing:none;font-smooth:never;padding:0 0 80px}
.inner{width:1080px;margin:0 auto;padding:56px 0 0}
h1{font-size:48px;color:#F2C069;font-weight:400}
.lead{font-size:24px;color:#C9B896;margin-top:16px;line-height:1.7}
.list{margin-top:44px;display:grid;gap:18px}
.item{display:block;text-decoration:none;color:inherit;border:2px solid #4A3F2E;
  padding:22px 24px;background:#2B2620}
.item:hover{border-color:#F2C069;background:#322C24}
.item .n{font-size:36px;color:#F2C069}
.item .d{font-size:24px;color:#B7A88A;margin-top:10px;line-height:1.6}
.item .k{margin-top:12px;font-size:24px;color:#8E7F66}
.item .k span{border:1px solid #4A3F2E;padding:3px 8px;margin-right:8px;color:#C9B896}
.tip{margin-top:36px;font-size:24px;color:#8E7F66;line-height:1.7}
"""
    rows = [
        ("A-像素仪表盘.html", "方向 A · 像素仪表盘",
         "最像游戏 UI：彩色数字格 + 24 格作息时钟 + 奖状称号 + 四色数据墙。信息最全，最闹。",
         ["信息密度高", "四色分区", "睡眠做成时钟"]),
        ("B-极简海报.html", "方向 B · 极简海报",
         "最克制：没有格子没有色块没有图标，只有字号层级、细线和留白。高级感最强，像杂志内页。",
         ["留白多", "零色块", "高级感最强"]),
        ("C-像素卡片墙.html", "方向 C · 像素卡片墙",
         "成就面板那一套：每项数据一张独立小卡片 + 硬边像素阴影，四类颜色区分。收集感最强。",
         ["卡片多", "硬阴影", "收集面板感"]),
        ("D-简约乘像素.html", "方向 D · 简约 × 像素（混合）",
         "B 的骨架 + A 的小件：留白细线都留着，像素感收进奖杯、像素槽位、24 格作息条。",
         ["留白 + 像素件", "最贴需求描述"]),
        ("E-像素故事墙.html", "方向 E · 像素故事墙  ← 研究后重排",
         "按 Steam 年度回顾等成熟做法的结论重排：竖版长图 = 5 屏纵向堆叠、每屏一件事，"
         "每个数字配一句算出来的吐槽，日常用分段像素条代替数字列表，深浅底交替当翻页。",
         ["一屏一件事", "数字配吐槽", "分段像素条", "深浅底翻页"]),
        ("F-鹈鹕镇特刊.html", "方向 F · 鹈鹕镇特刊  ← 报纸隐喻",
         "星露谷游戏里本来就有《鹈鹕镇新闻》，于是把报告做成报纸特刊：报头、通栏双细线、"
         "两栏、导语、编者按。文字为主，朱红点缀。",
         ["报纸隐喻", "游戏正统梗", "文字为主", "朱红 + 墨黑 + 报纸黄"]),
        ("G-成就墙.html", "方向 G · 成就墙  ← 徽章隐喻",
         "12 枚游戏成就徽章对应 12 项数据，一个不多一个不少。徽章感 + 居中 + 大量留白，"
         "像刚解锁的一整面奖杯墙。",
         ["徽章叙事", "12 图标 = 12 数据", "居中留白", "收集仪式感"]),
    ]
    items = "".join(
        '<a class="item" href="%s"><div class="n">%s</div><div class="d">%s</div>'
        '<div class="k">%s</div></a>' % (f, n, d, "".join("<span>%s</span>" % k for k in ks))
        for f, n, d, ks in rows)
    body = """<div class="inner">
<h1>农场年度报告 · 四个方向</h1>
<div class="lead">四个方向都用你 <b>ff</b> 存档里的真实数字（角色 Amy，1.5.6 档，100 小时 25 分 / 258 天 / 杀怪 3,437 只…），不是示意图。
点下面任意一个看 1080 宽的完整长图，建议开几个标签页并排对比。</div>
<div class="list">%s</div>
<div class="tip">生成脚本：<b>StardewHelper/tools/gen_report_previews.py</b>（改数据或加方向都改它，然后重跑）。<br>
选定之后在 App 里实现（App 侧版式照着 C 写：<b>ui/report/ReportCanvas.kt</b>）。</div>
</div>""" % items
    return """<!DOCTYPE html>
<html lang="zh-CN"><head><meta charset="utf-8">
<meta name="viewport" content="width=1080">
<title>农场年度报告 · 方向总览</title>
<style>%s</style></head>
<body>%s</body></html>""" % (css, body)


def main():
    os.makedirs(OUT, exist_ok=True)
    for fn, html in (
        ("A-像素仪表盘.html", build_a()),
        ("B-极简海报.html", build_b()),
        ("C-像素卡片墙.html", build_c()),
        ("D-简约乘像素.html", build_d()),
        ("index.html", build_index()),
    ):
        p = os.path.join(OUT, fn)
        io.open(p, "w", encoding="utf-8", newline="").write(html)
        print("写入", p, os.path.getsize(p), "B")


if __name__ == "__main__":
    main()
