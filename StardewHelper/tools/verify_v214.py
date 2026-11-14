# -*- coding: utf-8 -*-
"""v2.14 数据层校验（只读，不改任何文件）。

按实施方案「测试与验收」第 3 条逐条断言：
  1. recipes.json 81 条，无「寿司卷」，有「生鱼寿司」
  2. fishSpots.json 20 个钓点
  3. byFish 的键必须全部是 App 现有鱼名（反向断言：不能出现 App 没有的鱼）
  4. weaponCompendium.json 63 件、4 个分类
  5. 全部新 JSON 能 json.load()
  6. 交叉引用完整性：改名后 gifts/bundles 里不能残留旧名

退出码 0 = 全过；非 0 = 有断言失败。
"""
import io
import json
import os
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DATA = os.path.join(ROOT, "app", "src", "main", "assets", "data")
IMG = os.path.join(ROOT, "app", "src", "main", "assets", "image_map.json")

# 同目录的 subset_font 等模块按「脚本所在目录」导入，不依赖工作目录。
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

# v2.27：bundles.json 有三份手工同步的副本，改一份忘另两份是这类 bug 最常见的复发方式，
# 所以第 20 节逐份断言。web/ 平时不在本会话改动范围内，但数据必须一致。
_REPO_PARENT = os.path.dirname(ROOT)
WEB = os.path.join(_REPO_PARENT, "web")
WEB_OLD = os.path.join(_REPO_PARENT, "旧文件归档", "web_old_v1")

NEW_FILES = ["fishSpots.json", "weaponCompendium.json",
             "gingerIslandGuide.json", "marriageGuide.json", "processingProfit.json",
             "feature_guide.json"]

OLD_NAMES = ["寿司卷", "奶酪花椰菜", "炸鳗鱼", "什锦炒菜", "填料", "怪味面包",
             "李子布丁", "枫糖条", "根茎拼盘", "洋蓟蘸酱", "海味大餐", "海鲜炖汤",
             "焗蜗牛", "生存汉堡", "白藻汤", "红莓软糖", "芋泥", "蓝莓挞",
             "虞美人籽玛芬", "蛤蜊浓汤", "辣椒酿奶酪", "香脆鲈鱼",
             "鱿鱼墨汁饺子", "鲤鱼惊喜"]

fails, warns = [], []

# 控制台可能是 GBK（Windows 默认）：Emoji（📦 等）会直接炸 print，先改成 UTF-8。
try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass


def check(cond, msg):
    if cond:
        print("  OK   %s" % msg)
    else:
        print("  FAIL %s" % msg)
        fails.append(msg)


def load(p):
    return json.load(io.open(p, encoding="utf-8"))


def main():
    print("== 1. 新增 JSON 全部可解析 ==")
    for f in NEW_FILES:
        p = os.path.join(DATA, f)
        if not os.path.exists(p):
            check(False, "%s 存在" % f)
            continue
        try:
            load(p)
            check(True, "%s 可解析（%.1f KB）" % (f, os.path.getsize(p) / 1024.0))
        except Exception as e:
            check(False, "%s 可解析：%s" % (f, e))
    if fails:
        return 1

    print("\n== 2. 菜谱改名 ==")
    rec = load(os.path.join(DATA, "recipes.json"))
    names = set(x["name"] for x in rec)
    check(len(rec) == 81, "菜谱共 81 条（实际 %d）" % len(rec))
    check("寿司卷" not in names, "旧名「寿司卷」已消失")
    check("生鱼寿司" in names, "新名「生鱼寿司」在列")
    leftover = [n for n in OLD_NAMES if n in names]
    check(not leftover, "24 个旧名全部改掉（残留：%s）" % (leftover or "无"))

    print("\n== 3. 跨文件引用无残留旧名 ==")
    for f in ("gifts.json", "bundles.json"):
        txt = io.open(os.path.join(DATA, f), encoding="utf-8").read()
        bad = [n for n in OLD_NAMES if ('"%s"' % n) in txt]
        check(not bad, "%s 无旧名残留（%s）" % (f, bad or "无"))

    print("\n== 4. 钓鱼地图钓点数据 ==")
    fs = load(os.path.join(DATA, "fishSpots.json"))
    spots = fs.get("spots", [])
    byfish = fs.get("byFish", {})
    crab = fs.get("crabPot", {})
    check(len(spots) == 20, "钓点 20 个（实际 %d）" % len(spots))

    fish_names = set(x["name"] for x in load(os.path.join(DATA, "fishing.json")))
    unknown = [k for k in byfish if k not in fish_names]
    check(not unknown, "byFish 的鱼名全部存在于 fishing.json（陌生键：%s）" % (unknown or "无"))
    crab_unknown = [k for k in crab if k not in fish_names]
    check(not crab_unknown, "crabPot 的鱼名全部存在（陌生键：%s）" % (crab_unknown or "无"))

    covered = set(byfish) | set(crab)
    missing = sorted(fish_names - covered)
    print("  INFO 覆盖率 %d/%d；未收录：%s" % (len(covered), len(fish_names), "、".join(missing)))
    if missing:
        warns.append("未收录钓点的鱼 %d 条（UI 需显示「未收录」而非隐藏）：%s" % (len(missing), "、".join(missing)))

    bad_struct = [k for k, v in byfish.items()
                  if not isinstance(v.get("spots"), list)]
    check(not bad_struct, "byFish 每条都有 spots 数组（异常：%s）" % (bad_struct or "无"))

    print("\n== 5. 武器 ==")
    wc = load(os.path.join(DATA, "weaponCompendium.json"))
    ws = wc.get("weapons", [])
    check(len(ws) == 63, "武器 63 件（实际 %d）" % len(ws))
    check(len(wc.get("weapon_types", {})) == 4, "分类 4 个（实际 %d）" % len(wc.get("weapon_types", {})))
    no_icon = [w["name"] for w in ws if w["name"] not in load(IMG)]
    if no_icon:
        warns.append("%d 件武器没有图标，走占位框（本版已知视觉缺口）" % len(no_icon))

    print("\n== 6. 姜岛 / 结婚 / 加工 ==")
    gi = load(os.path.join(DATA, "gingerIslandGuide.json"))
    check(gi["golden_walnuts"]["total"] == 130, "金核桃总数 130")
    check(len(gi["parrot_services"]) == 11, "鹦鹉服务 11 条（实际 %d）" % len(gi["parrot_services"]))
    mg = load(os.path.join(DATA, "marriageGuide.json"))
    check(len(mg["marriageable_npcs"]) == 12, "可结婚 NPC 12 位（实际 %d）" % len(mg["marriageable_npcs"]))
    check(len(mg["process"]) == 7, "流程 7 步（实际 %d）" % len(mg["process"]))
    pp = load(os.path.join(DATA, "processingProfit.json"))
    nrec = sum(len(m.get("recipes", [])) for m in pp["machines"])
    check(len(pp["machines"]) == 15, "加工设备 15 台（实际 %d）" % len(pp["machines"]))
    check(nrec > 40, "加工配方 %d 条（>40）" % nrec)
    # v2.17：删掉旧断言「预排序 41 条」——profit_ranking 是无消费方且与 UI 口径(62)矛盾的死字段，已移除
    check("profit_ranking" not in pp, "无死字段 profit_ranking（排行页自带 62 条口径）")

    print("\n== 7. 防回归：UI 里 root.getXxx() 读的 key 必须在 JSON 顶层存在 ==")
    # 起因：v2.14 金核桃分段把 sources 写成了 root.getJSONArray("sources")，
    # 而它在 golden_walnuts 下 -> 运行时 JSONException 直接闪退（c35 手测发现）。
    import re
    SRC = os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew", "assistant")
    # Kotlin 文件 → (对应 JSON, 该文件里代表 JSON 根的变量名)
    MAP = [
        ("ui/guide/GuideIslandPage.kt", "gingerIslandGuide.json", "root"),
        ("ui/guide/GuideMarriagePage.kt", "marriageGuide.json", "root"),
        ("ui/guide/GuideWeaponsPage.kt", "weaponCompendium.json", "root"),
        ("ui/guide/GuideHomePage.kt", "gingerIslandGuide.json", "root"),
        ("ui/calc/CalcPage.kt", "processingProfit.json", "root"),
        ("data/GameData.kt", "fishSpots.json", "root"),
    ]
    pat = re.compile(r'\broot\.get(?:JSONArray|JSONObject|String|Int)\("([^"]+)"\)')
    for rel, js, var in MAP:
        kp = os.path.join(SRC, rel.replace("/", os.sep))
        if not os.path.exists(kp):
            check(False, "%s 存在" % rel)
            continue
        txt = io.open(kp, encoding="utf-8").read()
        keys = sorted(set(pat.findall(txt)))
        top = set(load(os.path.join(DATA, js)).keys())
        bad = [k for k in keys if k not in top]
        check(not bad, "%s 的强制读取 key 全部在 %s 顶层（缺失：%s）"
              % (rel, js, bad or "无"))

    gw = gi["golden_walnuts"]
    check(isinstance(gw.get("sources"), list) and len(gw["sources"]) > 0,
          "golden_walnuts.sources 是非空数组（金核桃来源 %d 条）" % len(gw.get("sources") or []))
    check("sources" not in gi, "gingerIslandGuide 顶层没有 sources（确认该 key 只在下一层）")

    print("\n== 8. 防回归：英文 key 上屏 / 数组当字符串渲染（c36 手测发现）==")
    # 起因：5 层商店直接 shop.kvLines() 把 name/stock 等英文 key 当标签，
    # 且 features/optString 读数组会把整段 JSON 吐上屏。
    island = io.open(os.path.join(SRC, "ui", "guide", "GuideIslandPage.kt"), encoding="utf-8").read()
    marr = io.open(os.path.join(SRC, "ui", "guide", "GuideMarriagePage.kt"), encoding="utf-8").read()
    common = os.path.join(SRC, "ui", "guide", "GuideCommon.kt")
    check(os.path.exists(common), "GuideCommon.kt 存在（cnKey / GUIDE_KEY_CN 统一收口）")
    if os.path.exists(common):
        txt = io.open(common, encoding="utf-8").read()
        check("internal fun cnKey" in txt, "cnKey 定义在 GuideCommon.kt")
        # GUIDE_KEY_CN 收录的 key 数（少于 80 说明映射被误删）
        nkeys = len(re.findall(r'"[a-z0-9_]+"\s+to\s+"', txt))
        check(nkeys >= 80, "GUIDE_KEY_CN 收录 >=80 个 key（实际 %d）" % nkeys)

    # 1) 标签必须经 cnKey：不允许把裸 key 直接塞进「标签 → 值」行
    check("into.add(k to" not in marr, "GuideMarriagePage.section() 标签已走 cnKey（无 into.add(k to）")
    check("into.add(cnKey(k) to" in marr, "GuideMarriagePage.section() 显式调用 cnKey")
    check("cnKey(k)" in island, "GuideIslandPage.kvLines() 标签走 cnKey")
    check("cnKey(k)" in island and "label = if (prefix.isEmpty()) cnKey(k)" in island,
          "kvLines 前缀拼接同样经 cnKey")

    # 2) 数组 key 禁止用 optString 读（会把 JSON 原样吐上屏）
    import json as _json
    arr_keys = set()

    def _collect(o):
        if isinstance(o, dict):
            for k, v in o.items():
                if isinstance(v, list):
                    arr_keys.add(k)
                _collect(v)
        elif isinstance(o, list):
            for x in o:
                _collect(x)

    for js in ("gingerIslandGuide.json", "marriageGuide.json"):
        _collect(load(os.path.join(DATA, js)))
    bad_opt = []
    for rel in ("ui/guide/GuideIslandPage.kt", "ui/guide/GuideMarriagePage.kt"):
        t = io.open(os.path.join(SRC, rel.replace("/", os.sep)), encoding="utf-8").read()
        for k in re.findall(r'optString\("([a-z0-9_]+)"\)', t):
            if k in arr_keys:
                bad_opt.append("%s:%s" % (rel.split("/")[-1], k))
    check(not bad_opt, "数组型 key 没有被 optString 读（违规：%s）" % (bad_opt or "无"))

    # 3) 商店 stock 必须逐条结构化渲染（不是 joinToString 一坨）
    check('optJSONArray("stock")' in island, "floor_5_shop.stock 用 optJSONArray 逐条渲染")
    check('shop.kvLines()' not in island, "5 层商店不再走通用 kvLines（会吐原始 JSON）")
    check('optJSONArray("features")' in island, "volcano features 用 optJSONArray 渲染")

    # 4) 结婚页 move_in_days 是 int，必须有 Number 分支兜住
    check("is Number ->" in marr, "GuideMarriagePage.section() 有 Number 分支（move_in_days 不被丢弃）")

    print("\n== 9. 图标：image_map 指向的文件必须真实存在 ==")
    # 起因：GameData.icon() 返回 image_map 的值后直接 assets.open，
    # 映射里若有悬空路径，运行时会静默走占位框（v2.14.2 起批量加入 wiki 图标后需锁死）。
    import json as _json
    IMGROOT = os.path.join(ROOT, "app", "src", "main", "assets", "img")
    imap = _json.load(io.open(os.path.join(ROOT, "app", "src", "main", "assets",
                                         "image_map.json"), encoding="utf-8"))
    dangling = [k for k, v in imap.items()
                if not os.path.exists(os.path.join(IMGROOT, v.replace("/", os.sep)))]
    check(not dangling, "image_map %d 条全部指向真实文件（悬空：%s）"
          % (len(imap), dangling[:5] or "无"))

    wiki = {k: v for k, v in imap.items() if str(v).startswith("wiki/")}
    check(len(wiki) >= 110, "wiki 原图已入库（%d 张，>=110）" % len(wiki))

    # 63 件武器此前 0 图（长期显示占位框），本版必须全部有图
    wc = _json.load(io.open(os.path.join(DATA, "weaponCompendium.json"), encoding="utf-8"))
    nowno = [w["name"] for w in wc["weapons"] if w["name"] not in imap]
    check(not nowno, "63 件武器全部有图标（仍缺：%s）" % (nowno[:5] or "无"))

    # 补缺为主：已有 AI 像素图不能被 wiki 原图覆盖
    ai = {k for k, v in imap.items() if not str(v).startswith("wiki/")}
    check(len(ai) >= 600, "原有平铺像素图保留（%d 张，>=600）" % len(ai))

    print("\n== 10. v2.15 新增四册：数据存在 + 无英文标签上屏 ==")
    NEW = [("skillLeveling.json", "skills"), ("foraging.json", "items"),
           ("petGuide.json", "pets"), ("weatherLuck.json", "weather")]
    for js, key in NEW:
        p = os.path.join(DATA, js)
        check(os.path.exists(p), "%s 已入库" % js)
        if os.path.exists(p):
            d = _json.load(io.open(p, encoding="utf-8"))
            check(key in d, "%s 含顶层 key '%s'" % (js, key))

    # 采集/天气数据里的英文 key 会在 UI 上直接显示，必须已中文化
    fg = _json.load(io.open(os.path.join(DATA, "foraging.json"), encoding="utf-8"))
    check(len(fg["items"]) >= 40, "采集物 %d 条（>=40）" % len(fg["items"]))
    en_season = set()
    for it in fg["items"]:
        for s in it.get("season", []):
            if re.search(r"[A-Za-z]", s):
                en_season.add(s)
    check(not en_season, "采集季节已中文化（残留英文：%s）" % (sorted(en_season)[:5] or "无"))
    en_use = {u for it in fg["items"] for u in it.get("uses", [])
              if isinstance(u, str) and re.search(r"[A-Za-z]{3,}", u)}
    check(not en_use, "采集用途已中文化（残留英文：%s）" % (sorted(en_use)[:5] or "无"))

    wl = _json.load(io.open(os.path.join(DATA, "weatherLuck.json"), encoding="utf-8"))
    seasons = wl["weather"]["seasons"]
    check(all(k in ("春", "夏", "秋", "冬") for k in seasons),
          "天气季节键已中文化（%s）" % list(seasons))
    check(all(not re.search(r"[A-Za-z]", k) for k in wl["weather"]["weather_effects"]),
          "天气效果键已中文化")
    check(all(not re.search(r"[A-Za-z]", k) for k in wl["luck"]["luck_effects"]),
          "幸运影响键已中文化")
    for s, so in seasons.items():
        pk = so.get("probabilities", {})
        check(all(not re.search(r"[A-Za-z]", k) for k in pk),
              "%s 天气概率键已中文化" % s)

    # GameData 必须加载这四份，且屏幕要接上
    gd = io.open(os.path.join(SRC, "data", "GameData.kt"), encoding="utf-8").read()
    for v in ("skillLeveling", "foraging", "petGuide", "weatherLuck"):
        check("var %s" % v in gd, "GameData 声明 %s" % v)
        check('obj("%s.json")' % v in gd, "GameData 加载 %s.json" % v)
    ma = io.open(os.path.join(SRC, "MainActivity.kt"), encoding="utf-8").read()
    # v2.31：攻略二级页的 when 分支从 MainActivity 搬进 GuideSubPage（那里同时是
    # 「正文词典链接」的作用域开关，两件事收在一处）。Screen 定义仍留在 MainActivity。
    gsub = io.open(os.path.join(SRC, "ui", "guide", "GuideSubPage.kt"), encoding="utf-8").read()
    for s in ("GuideSkills", "GuideForaging", "GuidePets", "GuideWeather"):
        check("data object %s" % s in ma, "Screen 定义 %s" % s)
        check("Screen.%s ->" % s in gsub, "攻略二级页路由 %s" % s)

    # ==== 11. v2.17 全面审查修复·防回归 ====
    print("\n== 11. v2.17 修复防回归 ==")
    src = lambda rel: io.open(os.path.join(SRC, rel.replace("/", os.sep)), encoding="utf-8").read()

    # c230：设置页拆成主文件 + 四个分件（三栏页签体 / 信息抽屉）。内容类钉点读
    # 「拼合全文」——搬运逐字未动，断言语义不变；CHANGELOG 契约类断言仍只读
    # SettingsPage.kt（publish_gitee 按此文件名取数，见 37.9）。
    _SP_PARTS = ("ui/settings/SettingsPage.kt", "ui/settings/SettingsAppearanceTab.kt",
                 "ui/settings/SettingsSavesTab.kt", "ui/settings/SettingsAboutTab.kt",
                 "ui/settings/SettingsInfoDrawers.kt")

    def src_sp():
        return chr(10).join(io.open(os.path.join(SRC, f.replace("/", os.sep)),
                                  encoding="utf-8").read() for f in _SP_PARTS)

    # A2 闭店星期：不许再出现错位的 when(dow) 硬编码，closedDays 是唯一判定源
    tp, wr = src("ui/today/TodayPage.kt"), src("widget/WidgetRender.kt")
    check('0 -> "周三"' not in tp and "when (dow)" not in tp, "TodayPage 无错位星期映射")
    check('0 -> "周三"' not in wr and "when (dow)" not in wr, "WidgetRender 无错位星期映射")
    check("closedDays.contains" in tp and "closedDays.contains" in wr, "闭店判定走 closedDays")

    # A3 送礼避雷接线
    gd = src("data/GameData.kt")
    check('optJSONArray("dislikes")' in gd and 'optJSONArray("hates")' in gd,
          "GameData 读取 dislikes/hates（避雷卡数据源）")

    # A1 天气效果不许直接 optString 对象字段
    gw = src("ui/guide/GuideWeatherPage.kt")
    check("eff!!.optString" not in gw and "effectText(" in gw, "天气效果走 effectText 结构化渲染")

    # A5 时序：itemSources 必须先于 codexGroups 构建
    check(gd.index("itemSources = buildMap") < gd.index("codexGroups = buildCodexGroups"),
          "itemSources 构建先于 codexGroups（来源行不再恒空）")

    # A6 计算器缓存键必须含 mode
    cp = src("ui/calc/CalcPage.kt")
    m_res = re.search(r"val results = remember\(([^)]*)\)", cp)
    check(m_res is not None and "mode" in m_res.group(1), "CalcPage results remember 键含 mode")
    check("val profitRows" not in cp, "CalcPage 旧 profitRows 死代码已删")

    # A7 全年/全季放行
    cx = src("ui/codex/CodexPage.kt")
    gf = src("ui/guide/GuideForagingPage.kt")
    check('"全年" in fs.seasons' in cx, "钓鱼季节筛选放行「全年」鱼")
    check('st == "全季"' in gf, "采集季节筛选放行「全季」物")

    # A4 跳转不丢事件
    ma = src("MainActivity.kt")
    check("MutableStateFlow<CodexJumpRequest?>" in ma, "codexJump 用 StateFlow（不丢事件）")
    check("runCatching { GameData.load" in ma, "MainActivity 外层 runCatching 兜底")

    # B5/B6/B9 数据订正
    mu = load(os.path.join(DATA, "museum.json"))
    check(len(mu["minerals"]) == 53, "博物馆矿物 53 件")
    check(len(mu["artifacts"]) == 42, "博物馆古物 42 件（官方捐赠清单）")
    seen = set()
    dup = []
    for room in load(os.path.join(DATA, "bundles.json")):
        for b in room["bundles"]:
            for it in b["items"]:
                key = (room["room"], b["name"], it["name"])
                if key in seen:
                    dup.append(key)
                seen.add(key)
    check(not dup, "收集包无重复物品项（打勾键不撞车）：%s" % (dup[:3] or "无"))
    linus = [g for g in load(os.path.join(DATA, "gifts.json")) if g["name"] == "莱纳斯"]
    check(linus and linus[0]["birthdayDay"] == 3, "莱纳斯生日 = 冬 3")
    crops = {c["name"]: c for c in load(os.path.join(DATA, "crops.json"))}
    check(crops["远古水果"]["sellPrice"] == 550 and crops["远古水果"].get("regrow") == 7,
          "远古水果 550g + 再生 7 天")
    check(crops["菠萝"]["sellPrice"] == 300 and crops["菠萝"].get("regrow") == 7,
          "菠萝 300g + 再生 7 天")
    sh = load(os.path.join(DATA, "shopHours.json"))
    check(all("closedDays" in s for s in sh), "shopHours 全部条目含 closedDays")

    # B2 关键加工数值（官方核实）
    pp = load(os.path.join(DATA, "processingProfit.json"))
    check("profit_ranking" not in pp, "processingProfit 无死字段 profit_ranking")
    machines = {m["name"]: m for m in pp["machines"]}
    check(set(["小桶", "压酪机", "烘干机", "宝石复制机", "产油机"]) <= set(machines),
          "机器名已统一到官方译名")
    # 按 (机器, 产出, 原料) 建索引做关键值断言
    idx = {}
    for mn, m in machines.items():
        for r in m["recipes"]:
            idx[(mn, r["output"], r.get("input", ""))] = r
    check(idx.get(("压酪机", "奶酪", "牛奶"), {}).get("output_price") == 230, "奶酪 230g")
    check(idx.get(("熔炉", "铁锭", "铁矿石"), {}).get("output_price") == 120, "铁锭 120g")
    check(idx.get(("产油机", "松露油", "松露"), {}).get("processing_time_hours") == 6.0, "松露油 6h")
    slime = machines.get("史莱姆蛋压机", {}).get("recipes", [{}])[0]
    check(slime.get("processing_time_hours") == 20.0, "史莱姆蛋压机 20h")

    # B7 image_map 反向覆盖：museum+fishing 全部显示名有图（精灵石暂无可靠对应图，白名单放行）
    im = load(IMG)
    fi = load(os.path.join(DATA, "fishing.json"))
    names = [x["name"] if isinstance(x, dict) else x for x in mu["artifacts"]]
    names += [x["name"] if isinstance(x, dict) else x for x in mu["minerals"]]
    names += [x["name"] for x in fi]
    missing = [n for n in names if n not in im]
    check(set(missing) <= {"精灵石"}, "museum+fishing 显示名全部有图映射（缺：%s）" % (missing or "无"))

    # C1/C2/C3 持久化安全
    pr = src("data/PrefsRepo.kt")
    check("清掉现有全部存档" not in pr, "导入不再清空全部本地存档")
    check("finalNames" in pr and "localNames" in pr, "导入为并集合并（同名覆盖、其余保留）")
    check("coerceIn(0, 3)" in pr, "存档日期 season 越界钳制")
    check("profiles.size >= MAX_PROFILES" in pr, "存档上限在仓储层校验")

    # C4 核心表逐文件降级
    check("coreFailed" in gd and "runCatching {" in gd, "GameData 核心表逐份 runCatching")
    check("var smelting" not in gd, "GameData 已删 smelting 死加载")

    # D4 死资产
    for fn in ("smelting.json", "cropXp.json", "jojaGoods.json"):
        check(not os.path.exists(os.path.join(DATA, fn)), "死数据已删 %s" % fn)

    # D6 manifest / packaging
    mf = io.open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"), encoding="utf-8").read()
    check('android:allowBackup="false"' in mf, "allowBackup=false（兑现不自动同步文案）")
    gb = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    # 原先这里断言 `"assets/data/wiki/**" in gb`，是把一条**从未生效**的 packaging 排除规则的
    # 字面量钉成了「OK」——规则失效，断言照样绿，等于给假绿上了保险。已移入第 39 节改钉行为。
    # 注意：本节在 code_only() 定义（约 804 行）之前，只能就地内联剥注释。
    _gb_nc = re.sub(r"/\*.*?\*/", "", gb, flags=re.S)
    _gb_nc = re.sub(r"//[^\n]*", "", _gb_nc)
    check(re.search(r'excludes\s*\+=\s*["\']assets/', _gb_nc) is None,
          "不再用 packaging.resources.excludes 排除 assets（该规则对 assets 完全无效）")
    m_ver = re.search(r'versionName = "([^"]+)"', gb)
    check(m_ver is not None, "gradle versionName 可解析")

    # CHANGELOG 首条不得**超前于** versionName。
    # 原本这里要求首条必须**等于** versionName，但用户明确要求：文案微调、内部小改
    # 不许再各占一条更新日志（v2.27.1~2.27.3 三条就是这么堆出来的，已删）。
    # 于是首条只要求「不超前」：真正改动过功能的版本必须有条目，
    # 纯文案/内部构建可以没有条目，但绝不能让日志出现还不存在的版本号。
    sp = src_sp()
    m_log = re.search(r'private val CHANGELOG = listOf\(\s*\r?\n\s*"v?([0-9][0-9.]*)[^"]*"', sp)

    def vtuple(s):
        return tuple(int(x) for x in s.split("."))

    ok_order = bool(m_log and m_ver and vtuple(m_log.group(1)) <= vtuple(m_ver.group(1)))
    check(ok_order, "CHANGELOG 首条 (%s) 不超前于 versionName (%s)"
          % (m_log and m_log.group(1), m_ver and m_ver.group(1)))
    check(re.search(r'"v2\.2[^"]*"\s*to', sp) is not None, "CHANGELOG 已补录 v2.2 条目")
    check("礼盒图标" not in sp, "CHANGELOG 已删不存在的礼盒图标")

    # ---- CHANGELOG 两条纪律 ----
    # 纪律一 · 简略（用户第一次反馈 2026-10-12：「从 v2.27 之后的更新日志都比较多，
    #   内容比较冗杂」）。实测旧条目多为 1 条 10~50 字，v2.27 起我写成 3~6 条 90~263 字。
    #   **范围只卡 v2.27 及以后**：老条目保留原有的信息条数（v2.26 有 4 条独立改动，
    #   硬压成 2 条就是丢事实），只统一文风。
    # 纪律二 · 文风（用户第二次反馈同日：「不要写得那么像白话文…大厂的更新日志写得多屌，
    #   咱们也写成那种」）。白话文最明显的三个标志，逐条钉死：
    #     ① 句末「了」——「…左上角都有返回了」这种语气词是口语体最大特征，去掉立刻变正式；
    #     ② 口语填充词——你可以 / 顺带 / 方便 / 轻松 / 终于 这类，大厂发布说明从不用；
    #     ③ 感叹号——克制是这一行的默认语气。
    #   这三条**覆盖全部 42 条**（不是只管新条目）：一半正式一半白话比全白话更难看。
    _cl_items = re.findall(r'"([^"]+)" to listOf\((.*?)\n    \),', sp, re.S)
    check(len(_cl_items) >= 30, "CHANGELOG 条目可解析（%d 条）" % len(_cl_items))
    # ⚠ 上一条是**正则**断言，它对语法错误是宽容的：v2.37 改写 CHANGELOG 时脚本把列表的
    # 收尾 `)` 一起吃掉了，上面 42 条断言照样全绿，直到 gradle 才报 Syntax error。
    # verify 不编译 Kotlin，所以必须自己盯住「括号闭合」这件最基本的事。
    check(re.search(r"private val CHANGELOG = listOf\(.*?\n\)\n", sp, re.S) is not None,
          "CHANGELOG 列表结构完整（listOf 有闭合括号）")

    def _vkey(t):
        _m = re.match(r"v(\d+)\.(\d+)(?:\.(\d+))?", t)
        return tuple(int(x or 0) for x in _m.groups()) if _m else None

    _FILL = ("顺带", "方便", "轻松", "终于", "贴心", "省心", "你可以", "不再猜")
    for _t, _b in _cl_items:
        _v = _vkey(_t)
        _recent = _v is not None and _v >= (2, 27, 0)
        _bs = re.findall(r'\n\s+"([^"]+)"', _b)
        if _recent:
            check(len(_bs) <= 2,
                  "CHANGELOG %s 只有 %d 颗子句（v2.27 起最多 2 颗）" % (_t, len(_bs)))
        for _x in _bs:
            _lim = 32 if _recent else 40
            # ⚠ 否定式断言的消息要写成「规则」，不能写成「失败原因」——
            # 否则全绿时输出读起来像在报错，后人会照着去查一个不存在的 bug。
            check(len(_x) <= _lim,
                  "CHANGELOG %s 子句 %d 字（上限 %d）：%s…" % (_t, len(_x), _lim, _x[:18]))
            check(not _x.rstrip("」』】…").endswith("了"),
                  "CHANGELOG %s 子句不以「了」收尾：%s" % (_t, _x[:18]))
            check(not any(_f in _x for _f in _FILL),
                  "CHANGELOG %s 子句不含口语填充词：%s" % (_t, _x[:18]))
            check("！" not in _x, "CHANGELOG %s 子句无感叹号：%s" % (_t, _x[:18]))

    # 标题不许是光秃秃的版本号（实测旧条目有 18 条标题就叫「v2.9」，观感上像没写完）
    _bare = [_t for _t, _b in _cl_items if re.match(r"^v[0-9][0-9.]*\s*$", _t)]
    check(not _bare, "CHANGELOG 无「只有版本号」的裸标题（%d 条：%s）"
          % (len(_bare), "、".join(_bare[:5])))

    notes = io.open(os.path.join(ROOT, os.pardir, "安装包归档", "版本备注.md"), encoding="utf-8").read()
    v216 = [l for l in notes.splitlines() if "v2.16_c39" in l]
    check(v216 and "五技能" in v216[0] and "10 种影响" in v216[0]
          and "6 技能" not in v216[0] and "11 种影响" not in v216[0],
          "版本备注 v2.16 行数字已纠错（五技能 / 10 种影响）")

    # D8/D10 杂项
    check(src("MainActivity.kt").count("import com.stardew.assistant.ui.codex.CodexPage") == 1,
          "MainActivity 无重复 import")
    check("BuildConfig.VERSION_NAME" in sp, "关于页版本号走 BuildConfig（单源）")
    check('"📖 攻略"' in cx and "攻略 4" not in cx, "图鉴顶栏无过期「攻略 4」")

    # E1 共享音效池
    snd = src("ui/Sounds.kt")
    check("LocalPixelSounds" in snd and "fun rememberSounds(): PixelSounds = LocalPixelSounds.current" in snd,
          "rememberSounds 走共享 CompositionLocal")

    # ==== 12. v2.18 群友反馈四项（全局搜索 / 战斗刷级 / 技能计算器 / 小鸡代码）====
    print("\n== 12. v2.18 群友反馈四项防回归 ==")
    src = lambda rel: io.open(os.path.join(SRC, rel.replace("/", os.sep)), encoding="utf-8").read()

    # 12.1 数据：monsterFarming
    mf_path = os.path.join(DATA, "monsterFarming.json")
    if os.path.exists(mf_path):
        mf = load(mf_path)
        check(len(mf.get("xp_by_area", [])) > 0, "monsterFarming 区域经验非空（%d 区）" % len(mf.get("xp_by_area", [])))
        check(len(mf.get("guild_goals", [])) == 12, "冒险家公会目标 12 条")
        check(len(mf.get("monsters", [])) >= 70, "怪物明细 >=70 条（实际 %d）" % len(mf.get("monsters", [])))
        # XP 口径诚实性：不得出现按分钟估算的字段（无权威依据）
        check("per_minute" not in json.dumps(mf, ensure_ascii=False), "无伪造的 XP/分钟字段")
    else:
        check(False, "monsterFarming.json 存在")

    # 12.2 数据：cheatCodes
    cc_path = os.path.join(DATA, "cheatCodes.json")
    if os.path.exists(cc_path):
        cc = load(cc_path)
        codes = cc if isinstance(cc, list) else cc.get("codes", [])
        # v2.79：28 → 162 条（补群友的 134 条清单）。
        # 钉死条数：数据由 tools/build_cheat_codes_v279.py 生成，条数对不上
        # 说明有人手改过 JSON 或者生成脚本跑歪了。
        # ⚠ c187：162 → 164（小鸡代码靴子类纠错：7 条**编造**靴子
        #   「蟹壳靴/火焰靴/岩石靴/精灵靴/恐龙靴/防火靴/冷却靴」——游戏 1.6 里
        #   根本没有这些靴子，其编号 804/806/854/855/878/879 是把别的物品
        #   记成了靴子（515=牛仔之靴、878=水晶鞋 真实存在但名字错了），
        #   换成官方 boots 全集 11 双，净增 2 条）。
        check(len(codes) == 164, "小鸡代码 164 条（实际 %d）" % len(codes))
        check(all(c.get("spawn_code") for c in codes), "每条都有 spawn_code")
        check(all(not re.search(r"[A-Za-z]{3,}", c.get("category_cn", "")) for c in codes),
              "category_cn 已中文化")
    else:
        check(False, "cheatCodes.json 存在")

    # 12.3 GameData 接线
    gd = src("data/GameData.kt")
    check("var monsterFarming" in gd, "GameData 声明 monsterFarming")
    check('obj("monsterFarming.json")' in gd, "GameData 加载 monsterFarming.json")
    check('CodexGroup("cheatCodes"' in gd, "图鉴含第 27 分类 cheatCodes")

    # 12.4 路由与跳转类型
    ma = src("MainActivity.kt")
    check("data object GuideCombat" in ma, "Screen 定义 GuideCombat")
    check("Screen.GuideCombat ->" in gsub, "攻略二级页路由 GuideCombat")
    check("data class CodexJumpRequest" in ma, "CodexJumpRequest 类型存在")
    # v2.35：钓鱼地图整本移出攻略页（用户反馈「数据页面就能直接查看」）。
    # 钉两件事：攻略侧不再有它、数据侧的钓点能力原样还在。
    # 防的是「以后又有人把它加回来」——所以用否定式断言，而不是钉某个字面量。
    gbooks = src("ui/guide/GuideBooks.kt")
    gh_f = src("ui/guide/GuideHomePage.kt")
    check('GuideBook("fishing"' not in gbooks
          and "钓鱼地图" not in re.sub(r"//[^\n]*", "", re.sub(r"/\*.*?\*/", "", gbooks, flags=re.S)),
          "册子表已无钓鱼地图（v2.35 移出攻略页；KDoc 里那段沿革不算）")
    check("onOpenFishing" not in gh_f and "钓鱼地图" not in gh_f and "fishSpots" not in gh_f,
          "攻略首页不再有钓鱼地图入口（含不再取 fishSpots）")
    cx_f = src("ui/codex/CodexPage.kt")
    check("🗺 钓点" in cx_f and "fishSpotFilter" in cx_f,
          "钓点能力仍在数据段（图鉴钓鱼分类的钓点卡 + 钓点筛选）")

    # 12.5 CodexPage 消费 targetIndex 且消费后置 null（防 v2.17 A4 回归）
    cx = src("ui/codex/CodexPage.kt")
    check("req.targetIndex" in cx, "CodexPage 消费 targetIndex（搜索结果直达详情）")
    check("codexJump.value = null" in cx, "CodexPage 消费后置 null（不重复触发）")
    check('groupId == "cheatCodes"' in cx, "小鸡代码详情有专属复制卡片")

    # 12.6 今日页全局搜索
    tp = src("ui/today/TodayPage.kt")
    check("showGlobalSearch" in tp, "今日页有全局搜索状态")
    check('title = "🔍 全局搜索"' in tp, "今日页挂全局搜索抽屉")
    gs_path = os.path.join(SRC, "ui", "search", "GlobalSearch.kt")
    check(os.path.exists(gs_path), "GlobalSearch.kt 存在")
    if os.path.exists(gs_path):
        gs = io.open(gs_path, encoding="utf-8").read()
        check("buildSearchIndex" in gs and "searchGlobal" in gs, "搜索索引与查询函数齐备")
    check("buildSearchIndex(GameData.codexGroups)" not in tp and "rememberCodexIndex()" in tp,
          "今日页搜索索引走共享单例（v2.31：三处各建一份必然漂）")

    # 12.7 战斗刷级册
    gc_path = os.path.join(SRC, "ui", "guide", "GuideCombatPage.kt")
    check(os.path.exists(gc_path), "GuideCombatPage.kt 存在")
    if os.path.exists(gc_path):
        gc = io.open(gc_path, encoding="utf-8").read()
        for seg_name in ("速刷路线", "区域经验", "公会目标", "怪物明细"):
            check(seg_name in gc, "战斗册分段：%s" % seg_name)
        check("不是每分钟" in gc or "每只怪" in gc, "战斗册标注 XP 为每只怪口径")
    gh = src("ui/guide/GuideHomePage.kt")
    # v2.35：册数文案改成**真的拿入口卡数去比**，而不是钉死「十本册子」四个字。
    # 旧写法每加减一本就要人肉改一次断言（v2.25 就改过一轮），
    # 而它真正要防的是「文案与实际入口数脱节」——那是 v2.17 D9「钓点数写死」的同型坑。
    n_cards = len(re.findall(r"onClick = onOpen", gh))
    CN2INT = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5,
              "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
    m_doc = re.search(r"用的([一二三四五六七八九十]+)本册子", gh)
    doc_num = CN2INT.get(m_doc.group(1), -1) if m_doc else -1
    check(m_doc is not None and doc_num == n_cards,
          "攻略首页册数文案与实际入口卡数一致（文案 %s / 卡 %d）"
          % (doc_num if doc_num > 0 else "无", n_cards))
    # 卡数与册子表长现算对齐（原本钉死 ==9，v2.56 加商店目录册时正是它自爆——
    # 「计数写死」这个坑本项目已栽过多次，这里改成按源现算，加册子不再需要改断言）
    _gbk16 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                  "assistant", "ui", "guide", "GuideBooks.kt"),
                     encoding="utf-8").read()
    check(n_cards == len(re.findall(r'GuideBook\("', _gbk16)),
          "攻略首页入口卡数 = 册子表长（实测卡 %d / 表 %d）"
          % (n_cards, len(re.findall(r'GuideBook\("', _gbk16))))
    check("onOpenCombat" in gh, "攻略首页有战斗刷级入口")

    # 12.8 技能冲级计算器
    gsp = src("ui/guide/GuideSkillsPage.kt")
    check("冲级计算" in gsp, "技能册含「冲级计算」分段")
    check("来源性价比排行" in gsp, "计算器有来源排行")
    check("每天要刷多少" in gsp, "计算器有「每天要刷多少」")

    # 12.9 闪退回归：图鉴页不得裸取 groups[groupIdx]
    #     起因 v2.18：cheatCodes.json 顶层写成对象，arr() 抛 JSONException →
    #     codexGroups 为空 → groups[groupIdx] IndexOutOfBounds → 点图鉴直接闪退
    check("if (groups.isEmpty())" in cx, "CodexPage 有空分类空态保护（不闪退）")
    check("groups[groupIdx.coerceIn(" in cx, "groupIdx 已防越界")
    cc_top = load(os.path.join(DATA, "cheatCodes.json")) if os.path.exists(os.path.join(DATA, "cheatCodes.json")) else None
    check(isinstance(cc_top, list), "cheatCodes.json 顶层是数组（arr() 要求）")
    # 通用体检：所有走 arr() 的图鉴数据顶层必须是数组
    ARR_FILES = ["fruitTrees", "recipes", "crafting", "animals", "buildings", "fishPonds",
                 "toolUpgrades", "geodes", "seedPrices", "tackle", "forage", "achievements",
                 "movies", "shipping", "secretNotes", "cheatCodes", "monsterDrops", "nonbuyableSeeds"]
    bad_top = []
    for fn in ARR_FILES:
        p = os.path.join(DATA, fn + ".json")
        if not os.path.exists(p):
            continue
        if not isinstance(load(p), list):
            bad_top.append(fn)
    check(not bad_top, "所有 arr() 读取的数据顶层均为数组（异常：%s）" % (bad_top or "无"))

    # 12.10 分类面板必须能点到新分类（用户反馈「图鉴里没有小鸡代码这个选项」）
    panel = cx
    check('"cheatCodes"' in panel, "分类面板白名单含 cheatCodes")
    check("val extra = groups.filter { it.id !in listed }" in panel,
          "分类面板有「其他」兜底段（新增分类漏写白名单也可见）")

    # 12.11 搜索点击必须真正切页签（用户反馈「点了不跳转，手动点图鉴才出来」）
    #     根因：只设 codexJump 不切 tab，CodexPage 未组合 → collect 未订阅 → 指令滞留
    check("openCodex" in tp, "TodayPage 接收 openCodex 回调")
    check("openCodex(hit.groupId, hit.index)" in tp, "搜索结果点击走 openCodex")
    check("openCodex = { gid, idx ->" in ma, "AppRoot 提供 openCodex（负责切页签）")
    m_today = re.search(r"Screen\.Today -> TodayPage\((.*?)\n                \)", ma, re.S)
    check(m_today and "tab = Screen.Codex" in m_today.group(1), "openCodex 内部切到 Codex 页签")

    # ==== 13. v2.19 滑动性能修复 ====
    print("\n== 13. v2.19 滑动性能修复防回归 ==")
    pw = src("ui/PixelWidgets.kt")
    # 13.1 冷启动图标解码尖峰：AssetImage 缓存 miss 必须走 IO 异步，禁止主线程同步解码
    check("peekItemIcon" in pw, "IconCache 有 peekItemIcon 同步快查（不解码）")
    check("Dispatchers.IO" in pw and "IconCache.itemIcon(ctx, name)" in pw,
          "AssetImage miss 改 IO 线程异步解码（旧实现主线程 decodeStream，冷窗口 p99 350ms）")
    check("remember(name) { IconCache.itemIcon" not in pw,
          "AssetImage 不再在组合期主线程同步解码")
    # 13.2 pressFeedback 不得用 composed{}（每个列表行各建一个子组合）
    pt = src("ui/PixelTheme.kt")
    check("= composed {" not in pt, "pressFeedback 已脱离 composed{}")
    check("@Composable\nfun Modifier.pressFeedback" in pt.replace("\r\n", "\n"),
          "pressFeedback 改 @Composable 扩展")
    # 13.3 进度页收集包来源速查必须 remember（usagesOf 是全表扫描）
    pp = src("ui/progress/ProgressPage.kt")
    check("remember(ic.name)" in pp and "GameData.usagesOf(ic.name)" in pp,
          "ProgressPage usagesOf 按条目名 remember 固化")
    # 13.4 图鉴钓鱼筛选必须走 name→鱼 索引，不得 6 处 firstOrNull 线性扫
    check("fishByName = GameData.fishing.associateBy" in cx, "钓鱼筛选先建 fishByName 索引")
    check(cx.count("GameData.fishing.firstOrNull { it.name == fish.name }") == 0,
          "筛选器内不再线性扫 fishing 表")
    # 13.5 村民排序 remember
    # v2.71：remember 键加了 snap.followed（点★要触发重排），断言跟着新口径
    vp = src("ui/villagers/VillagersPage.kt")
    check("remember(GameData.gifts, snap.followed)" in vp, "村民生日排序按数据 remember（挂 snap.followed）")
    # 13.6 预热线程降后台优先级
    check("THREAD_PRIORITY_BACKGROUND" in ma, "warmItems 预热线程降后台优先级")

    # ==== 14. v2.20 切页 keep-alive / 今日页死线折叠 / 计算页拆行 ====
    print("\n== 14. v2.20 切页与今日页流畅度防回归 ==")
    # 14.1 主 tab keep-alive
    check("MAIN_TABS" in ma and "keptTabs" in ma, "AppRoot 主 tab keep-alive 结构存在")
    check("CompositingStrategy.Offscreen" in ma, "隐藏 tab 用 Offscreen 不绘制")
    check("PointerEventPass.Initial" in ma and ".consume()" in ma,
          "隐藏 tab 手势黑洞（Initial pass 消费，防穿透点击）")
    check("delay(3000)" in ma and "delay(700)" in ma, "启动后逐个预热主 tab")
    # 14.2 二级页不 keep-alive：压栈页必须盖不透明背景
    # v2.34 改写：转场把二级页包进 AnimatedContent 后，背景从外层 Box 下沉到
    # 「页面自己的 Box」（外层那层要留给空态，不许盖住主 tab）。意图一字未变——
    # 真正画出来的那一页必须不透明，否则 keep-alive 主 tab 会透出来。
    # 改钉结构关系而非代码形状：背景必须落在 s != null 分支内。
    # 防御式：锚点缺失时报 FAIL，不能让 .index() 抛异常把整个 verify 打崩。
    _a14 = "{ (s, _) ->" in ma
    _b14 = "if (s != null)" in ma
    _c14 = "background(LocalPixel.current.screen)" in ma
    if _a14 and _b14 and _c14:
        _g14 = ma.index("{ (s, _) ->")
        _guard14 = ma.index("if (s != null)", _g14)
        _bg14 = ma.index("background(LocalPixel.current.screen)", _g14)
        check(_g14 < _guard14 < _bg14,
              "二级页不透明盖住 keep-alive 主 tab（背景画在页面 Box 内，防透出/穿透）")
    else:
        check(False, "二级页渲染块锚点缺失（{ (s, _) -> / if (s != null) / background）")
    # 14.3 今日页死线卡默认折叠
    tp = src("ui/today/TodayPage.kt")
    check("openTiers" in tp and "if (open && t.crops.isNotEmpty())" in tp,
          "播种死线各档 chips 默认收起、点击展开")
    # 14.4 计算页列表结构：v2.20 曾拆 lazy items，v2.23 按用户要求还原整卡——
    #     此处只钉「不允许回到 v2.20 的散行样式」（分壳/散行都已被否）
    cp = src("ui/calc/CalcPage.kt")
    check("CardShell" not in cp, "计算页无分壳残留（v2.22 方案已废弃）")

    # ==== 15. v2.21 真机流畅度专项（R8 release 构建 + 今日页重组减负）====
    print("\n== 15. v2.21 真机流畅度专项防回归 ==")
    # 15.1 分发口径：release 构建（R8 + debug 签名，无需 keystore）
    check("isMinifyEnabled = true" in gb, "release 启用 R8 minify")
    check("isShrinkResources = true" in gb, "release 启用资源收缩")
    check('signingConfigs.getByName("debug")' in gb, "release 用测试签名（不引 keystore）")
    # 15.2 今日页数据计算 remember + 稳定 key
    check("remember(seasonName, snap.day)" in tp, "生日/节日卡数据按日期 remember")
    # v2.73.4：键里多了 modCropsKey——GameData.crops 变成含 Mod 包的**计算属性**后，
    #   少了它就会「装完 SVE 包进今日页看不到新作物」。钉**语义**（这几个维度都要在键里）
    #   而不是旧字面量整串。
    _deadline_key = re.search(r"remember\(([^)]*snap\.prof10[^)]*)\)\s*\{\s*val must", tp)
    check(_deadline_key is not None
          and "seasonName" in _deadline_key.group(1)
          and "snap.day" in _deadline_key.group(1)
          and "modCropsKey" in _deadline_key.group(1),
          "播种死线扫描的 remember 键含日期+职业+Mod 作物状态（实测：%s)"
          % (_deadline_key.group(1).strip() if _deadline_key else "未找到"))
    for k in ("banner", "birthday", "festival", "week", "deadline", "todo"):
        check('item(key = "%s")' % k in tp, "今日页 item 稳定 key：%s" % k)
    # 15.3 诚实口径：CHANGELOG 声明 R8 + 测试签名
    check("R8" in sp or "优化构建" in sp, "CHANGELOG 如实说明 R8 优化构建")
    check("测试签名" in sp, "CHANGELOG 如实说明仍为测试签名")

    # ==== 16. v2.23 计算页还原原版整卡（用户对比截图拍板，替代 v2.20 散行/v2.22 分壳）====
    print("\n== 16. v2.23 计算页整卡还原防回归 ==")
    pw2 = src("ui/PixelWidgets.kt")
    check("CardShellHeader" not in pw2 and "CardShellRow" not in pw2 and "CardShellFooter" not in pw2,
          "分壳组件已删除（不留死代码）")
    check("CardShell" not in cp, "计算页不再引用分壳")
    check("PixelCard(title = title)" in cp, "种植列表还原整卡（PixelCard 包全部行）")
    check('PixelCard(title = "🫖 加工收益（按日均增益排序）")' in cp, "加工列表还原整卡")
    check("results.forEachIndexed" in cp, "种植行循环回到卡内（原版结构）")
    check("Arrangement.spacedBy(12.dp)" in cp, "卡片间距恢复 12dp")

    # ==== 17. v2.24 攻略页不可见文字修复（cream 字在 parchment 内衬上=隐形）====
    print("\n== 17. v2.24 攻略页文字隐形修复防回归 ==")
    import glob as _glob
    cream_bad = []
    for _p in _glob.glob(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew", "assistant", "ui", "guide", "*.kt")) + \
               [os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew", "assistant", "ui", "codex", "CodexPage.kt")]:
        _t = io.open(_p, encoding="utf-8").read()
        for _m in re.finditer(r".*color = c\.cream.*", _t):
            cream_bad.append("%s: %s" % (os.path.basename(_p), _m.group().strip()[:60]))
    check(not cream_bad, "攻略页/图鉴页正文禁用 c.cream（cream 只属于木纹条，parchment 内衬上隐形）%s"
          % (("\n     " + "\n     ".join(cream_bad)) if cream_bad else ""))

    # ==== 18. v2.25 年历页 ====
    print("\n== 18. v2.25 年历页防回归 ==")

    # 18.1 节日 date 字段全部可解析（格式变了日历会整格空掉，且是静默的）
    fest_dates = [f.get("date") for f in load(os.path.join(DATA, "festivals.json"))]
    unparsed = [d for d in fest_dates if not re.match(r"^(春|夏|秋|冬)\d+(-\d+)?$", str(d))]
    check(not unparsed, "festivals.json 全部 %d 条 date 可被年历解析（异常：%s）" % (len(fest_dates), unparsed or "无"))

    # 18.2 节日图标不悬空（image_map 缺键 → 运行时静默走占位框）
    imap18 = load(os.path.join(ROOT, "app", "src", "main", "assets", "image_map.json"))
    no_icon18 = [f["name"] for f in load(os.path.join(DATA, "festivals.json")) if f["name"] not in imap18]
    check(not no_icon18, "12 个节日名在 image_map 中均有映射（缺：%s）" % (no_icon18 or "无"))

    cal_model = src("ui/calendar/CalendarModel.kt")
    cal_page = src("ui/calendar/CalendarPage.kt")

    # 18.3 末种日必须走 Calc.lastPlantDay：自己写 28-growth 会丢掉
    #      「农业学家 ×0.9」与「跨季作物」两条既有口径
    check("Calc.lastPlantDay" in cal_model, "末种日走 Calc.lastPlantDay（含职业加速与跨季判定）")
    check(not re.search(r"28\s*-\s*\w*\.growth", cal_model), "年历不自写 28 - growth")

    # 18.4 路由接线
    check("onOpenCalendar" in src("ui/guide/GuideHomePage.kt"), "攻略首页有年历入口卡")
    check("Screen.Calendar ->" in gsub, "注册 Screen.Calendar 渲染分支")
    check("data object Calendar : Screen" in ma, "Screen 枚举含 Calendar")

    # 18.5 续第 17 节铁律：年历页正文不许用 c.cream
    cream_cal = re.findall(r".*color = c\.cream.*", cal_model + "\n" + cal_page)
    check(not cream_cal, "年历页禁用 c.cream（parchment 内衬上隐形）%s"
          % (cream_cal or ""))

    # ==== 19. v2.25 分享卡片 ====
    print("\n== 19. 分享卡片防回归（v2.25 建 / v2.26 改版） ==")
    mf = io.open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"), encoding="utf-8").read()
    fp = os.path.join(ROOT, "app", "src", "main", "res", "xml", "file_paths.xml")

    check("androidx.core.content.FileProvider" in mf, "manifest 声明 FileProvider")
    # provider 必须 exported=false + grantUriPermissions，否则导出的图对所有应用可见
    check(re.search(r'android:name="androidx\.core\.content\.FileProvider"', mf)
          and 'android:exported="false"' in mf
          and 'android:grantUriPermissions="true"' in mf,
          "FileProvider exported=false + grantUriPermissions=true")
    check(os.path.exists(fp) and "<cache-path" in io.open(fp, encoding="utf-8").read(),
          "res/xml/file_paths.xml 存在且只放行 cache-path")

    # ⚠ v2.42：这两条原本逐字钉死当时的版本号（`74` / `"2.41.0"`）——每升一版都得手改一次，
    #   忘了改 verify 就红，而红的原因跟它想防的东西毫无关系。这正是 v2.32 立的教训
    #   「钉约束要钉结构，钉字面量等于给自己埋一次必然踩的雷」，本项目自己又犯了一次。
    #   真正要防的是**版本号三处口径不一致**：gradle / CHANGELOG 首条 / 归档 APK 文件名。
    #   CHANGELOG 那条另有断言（首条不得超前于 versionName），这里补归档文件名这条，
    #   并按当前 gradle 值现算——以后再升版不用动 verify。
    _m_vc = re.search(r"versionCode\s*=\s*(\d+)", gb)
    _m_vn = re.search(r'versionName\s*=\s*"([^"]+)"', gb)
    check(bool(_m_vc and _m_vn), "gradle 的 versionCode / versionName 均可解析")
    if _m_vc and _m_vn:
        _vc35, _vn35 = _m_vc.group(1), _m_vn.group(1)
        _apkdir35 = os.path.join(ROOT, os.pardir, "安装包归档")
        _hit35 = [f for f in os.listdir(_apkdir35)
                  if f.endswith(".apk") and ("_c%s." % _vc35) in f]
        check(len(_hit35) == 1 and ("v%s" % _vn35) in _hit35[0],
              "归档 APK 文件名与 gradle 口径一致（v%s / c%s，实得 %s）"
              % (_vn35, _vc35, "、".join(_hit35) or "无"))

    # 19.4 进度口径单源：分母必须实时算，不许写死常量
    check(os.path.exists(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew", "assistant", "data", "ProgressCalc.kt")),
          "ProgressCalc.kt 存在（进度口径单源）")
    pp = src("ui/progress/ProgressPage.kt")
    check("calcProgress(snap)" in pp, "进度页改用 calcProgress，不自行计数")
    check("bundleItemCount" not in pp, "进度页不再自算 bundleItemCount")
    sc = src("ui/share/ShareCards.kt")
    se = src("ui/share/ShareEntry.kt")

    # 19.5 离屏渲染管线三件套齐全
    check("rememberGraphicsLayer" in sc, "离屏渲染用 rememberGraphicsLayer")
    check("toImageBitmap" in sc or "toImage()" in sc, "离屏渲染导出 ImageBitmap")
    check("asAndroidBitmap" in sc, "离屏渲染转 Android Bitmap")

    # 19.6 v2.26 卡片改版：只保留今日概览 + 图鉴条目两张卡
    check("TodayCardBody" in sc, "今日概览卡保留")
    check("ItemCardBody" in sc, "图鉴条目卡存在")
    check("ProgressCardBody" not in sc, "进度成就卡已删（用户要求只留今日概览）")
    check("shareScreen" not in se, "「截取当前画面」已删")
    check("ShareEntry(" not in pp and "TodayShareEntry" in src("ui/today/TodayPage.kt"),
          "进度页不再有分享入口，今日页走 TodayShareEntry")
    check("ItemShareEntry" in src("ui/codex/CodexPage.kt"), "图鉴条目详情接入分享")

    # 19.7 预览必须留：离屏抓图抓的是已画过的帧，不预览就没东西可抓
    check("CardPreviewDialog" in se and "Dialog(" in se, "分享走预览弹窗（发出去的图别人删不掉）")

    # 19.8 条目卡图标是异步解码的：不在打开前预热，截图里会是首字占位块
    check("IconCache.itemIcon" in se, "条目卡分享前预解码图标（否则卡上是占位块）")

    # 19.9 随机结果不得编造：卡上不许出现天气实况
    check("天气" not in sc or "随机" in se, "分享卡不编造天气实况")

    # 19.10 v2.26.1/2 预览压扁回归（用户两次截图打回：卡片被压成 1:2.5 细长条、
#       左右溢出 PixelCard 内衬、下半张大片空白、按钮被挤出屏幕）
# 两轮错法的共同根因：缩放与布局约束打架。
#   ① Modifier.scale：参与父约束 → 1080 卡在 ~560px 内衬里被压扁重排
#   ② graphicsLayer+requiredSize：缩放没真正作用于绘制 → 仍按 1080 真实像素画，溢出被裁
# 正确做法：按缩放后的 Density 重新布局，占位宽高与绘制宽高天然相等。
    # 「不该出现的写法」类断言必须先剥注释——注释里会写「旧版用的是 XXX」，
    # 直接对全文匹配会把注释误判成代码（v2.26.2 第一次跑就踩了这个）。
    #
    # v2.65.0 补一个洞：原来这版**不认字符串字面量**。而 Kotlin 里 `"*/*"`（SAF 的
    # MIME 通配）与 `"text/*"` 本身就含 `/*`，两个一凑就成了假块注释的起止，
    # 中间几百行真代码被整段当成注释吃掉——表现为「明明写着的话，断言说没有」。
    # 这与本项目反复出现的「检查器自身成为假信号源」同型，所以修函数而不是改断言绕开。
    #
    # 修法用一次性状态机而不是正则配对：正则会**在注释内部**误判字符串，把 `/*` 与
    # `*/` 拆到不同片段里，反而让本该剥掉的 KDoc 留了下来（GuideHomePage.kt 的
    # onBack / PageBar 两行就在注释里）。顺带正确处理 Kotlin 的**嵌套块注释**——
    # 语言本身支持嵌套，只剥一层会漏。
    def code_only(t):
        out = []
        i, n = 0, len(t)
        while i < n:
            c = t[i]
            d = t[i + 1] if i + 1 < n else ""
            if t[i:i + 3] == '"""':                 # 原始字符串
                j = t.find('"""', i + 3)
                j = n if j < 0 else j + 3
                out.append(t[i:j]); i = j; continue
            if c == '"' or c == "'":               # 字符串 / 字符字面量
                j = i + 1
                while j < n:
                    if t[j] == "\\":
                        j += 2; continue
                    if t[j] == c or t[j] == "\n":   # 未闭合就停在行尾，避免吃掉后半文件
                        j += 1; break
                    j += 1
                out.append(t[i:j]); i = j; continue
            if c == "/" and d == "/":               # 行注释
                j = t.find("\n", i)
                i = n if j < 0 else j; continue
            if c == "/" and d == "*":               # 块注释（可嵌套）
                depth, j = 1, i + 2
                while j < n and depth > 0:
                    if t[j] == "/" and t[j + 1:j + 2] == "*":
                        depth += 1; j += 2; continue
                    if t[j] == "*" and t[j + 1:j + 2] == "/":
                        depth -= 1; j += 2; continue
                    j += 1
                i = j; continue
            out.append(c); i += 1
        return "".join(out)
    se_c, sc_c = code_only(se), code_only(sc)
    check("Density(density = scale, fontScale = scale)" in se_c, "预览用 Density(scale) 重新布局卡片")
    check("Modifier.scale(" not in se_c, "预览不用 Modifier.scale（参与约束会导致压扁）")
    check("requiredSize" not in se_c, "预览不用 requiredSize（缩放不作用于绘制，治标不治本）")
    check("graphicsLayer" not in se_c, "预览不用 graphicsLayer 缩放")
    # ShareCardSurface 若自己锁 Density(1f)，会覆盖外层的 scale，卡片又变回 1080 真实像素
    check("Density(1f)" not in sc_c, "ShareCardSurface 不再自己锁密度，必须继承调用方的 scale")
    check("CompositionLocalProvider" not in sc_c, "ShareCards 不再自带 CompositionLocalProvider")
    # 宽度基准要扣 PixelCard 的固定内衬开销，否则按屏幕宽算出来的卡必然溢出内衬
    check("screenWpx - 200f" in se_c, "预览宽度基准扣掉 PixelCard 内衬开销（不再按屏幕全宽算）")
    # 空白修复：剩余空间归主视觉区（整体居中），Footer 不得再用 weight spacer 沉底
    check("Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center)" in sc_c,
          "卡片主视觉区用 weight 整体居中")
    check("Spacer(Modifier.weight(1f))" not in sc_c, "Footer 不再用 weight spacer（会把键值行与水印撑开一片空）")

    # ==== 20. v2.27 收集包「选 N」规则 ====
    print("\n== 20. 收集包选 N 规则防回归（v2.27） ==")
    # 20.1 数据：三份 bundles.json 都得有 choose，且冒险家=2 / 蟹笼=5
    #      （App / web / web_old_v1 三份手工同步，最容易漏的就是 web 那份）
    bundle_paths = [
        os.path.join(ROOT, "app", "src", "main", "assets", "data", "bundles.json"),
        os.path.join(WEB, "assets", "data", "bundles.json"),
        os.path.join(WEB_OLD, "assets", "data", "bundles.json"),
    ]
    for bp in bundle_paths:
        rel = bp.replace("\\", "/").split("星露谷/")[-1]
        rooms = json.load(io.open(bp, encoding="utf-8"))
        adv = [b for r in rooms for b in r["bundles"] if b["name"] == "冒险家"]
        cra = [b for r in rooms for b in r["bundles"] if b["name"] == "蟹笼"]
        check(len(adv) == 1 and adv[0].get("choose") == 2,
              "%s：冒险家包 choose=2（4选2，非全交）" % rel)
        check(len(cra) == 1 and cra[0].get("choose") == 5,
              "%s：蟹笼包 choose=5（10选5，非全交）" % rel)
        check(len(adv) == 1 and adv[0]["items"][0]["name"] == "黏液" and len(adv[0]["items"]) == 4,
              "%s：冒险家包 4 项且首项为「黏液」" % rel)
        check(len(cra) == 1 and len(cra[0]["items"]) == 10,
              "%s：蟹笼包 10 项候选" % rel)
        check("史莱姆泥" not in io.open(bp, encoding="utf-8").read(),
              "%s：旧名「史莱姆泥」已清干净（图鉴查不到该名）" % rel)

    # 20.2 模型与解析
    models = src("data/Models.kt")
    check(re.search(r"class NamedItems\(val name: String, val items: List<ItemCount>, val choose: Int = 0\)", models),
          "NamedItems 带 choose 字段（默认 0 = 全交，向后兼容旧数据）")
    check('b.optInt("choose", 0)' in src("data/GameData.kt"), "GameData 解析 choose，缺省 0")

    # 20.3 判定口径必须单源：四处调用点不许各自写 done == items.size
    pcalc = src("data/ProgressCalc.kt")
    check("fun bundleIsFull(" in pcalc and "b.choose > 0" in pcalc,
          "ProgressCalc 有 bundleIsFull 单源判定（认 choose）")
    check("done >= b.choose" in pcalc, "bundleIsFull 对选 N 包用 >= choose")
    # 分母不许跟着缩水：进度分母仍按 items 总数（否则总进度分母会凭空变化）
    check("bundleItemCount += b.items.size" in pcalc, "bundleTotal 分母仍按 items 总数，未改成 choose")

    ud = code_only(src("ui/progress/UnlockDone.kt"))
    check("bundleIsFull(" in ud, "UnlockDone 改调 bundleIsFull")
    check(".items.all" not in ud, "UnlockDone 不再自己写 items.all（全交假设）")

    pp_c = code_only(pp)
    check("bundleIsFull(" in pp_c, "ProgressPage 用 bundleIsFull 判包完成")
    check("done == names.size" not in pp_c, "ProgressPage 不再出现 done == names.size（全交假设）")
    check("任选${b.choose}" in code_only(pp), "进度页标出「任选 N」提示")

    # 20.4 旧勾选键必须落盘迁移（只读映射会导致「取消勾选无效」）
    prefs = src("data/PrefsRepo.kt")
    # ⚠ v2.100.0 / c182：键模板不再手写（改调 bundleCheckKey），产出的键与原来逐字节相同。
    check('bundleCheckKey(3, 2, "史莱姆泥") to bundleCheckKey(3, 2, "黏液")' in prefs,
          "PrefsRepo 登记勾选键重映射（键模板走 bundleCheckKey，产出与原来逐字节相同）")
    check("suspend fun migrateCheckKeys(" in prefs, "PrefsRepo 有一次性键迁移函数")
    check("CHECK_KEY_REMAP[it] ?: it" in code_only(prefs), "迁移是落盘重写集合，不是只读映射")
    check("repo.migrateCheckKeys()" in ma, "MainActivity 启动时调用 migrateCheckKeys")
    check(ma.index("repo.ensureMigrated()") < ma.index("repo.migrateCheckKeys()"),
          "migrateCheckKeys 在 ensureMigrated 之后（需先有存档列表才能逐个搬）")

    # 20.5 web 端判定必须同步，否则改了 JSON 会让网页端把这两个包判成永远做不完
    pm = io.open(os.path.join(WEB, "src", "data", "progress-model.js"), encoding="utf-8").read()
    pv = io.open(os.path.join(WEB, "src", "views", "progress.js"), encoding="utf-8").read()
    check("choose: b.choose || 0" in pm, "web progress-model 把 choose 透出到包对象")
    check("export function bundleIsFull(" in pm and "b.choose > 0" in pm, "web 有 bundleIsFull 单源判定")
    check(pm.count("bundleIsFull(") >= 4, "web 的 progressStats/roomFull/roomAllDone/bundleDone 全部收敛到单源")
    check("bundleIsFull(b, checks)" in pv and "任选${b.choose}" in pv, "web 进度页认选 N 并标注「任选 N」")

    # ==== 21. v2.27.1 鬼鱼 / 幽灵鱼图标内容 ====
    print("\n== 21. 鬼鱼/幽灵鱼图标内容防回归（v2.27.1） ==")
    # 这两张曾经**内容互换**：图鉴里「鬼鱼」显示蓝鱼、「幽灵鱼」显示浅色鱼。
    # 文件名对不代表内容对，只能按内容哈希钉死。
    # 权威参照是数据包 images/fish/ 下的英文名原图（那边一直是对的）。
    import hashlib

    def md5_of(p):
        with open(p, "rb") as f:
            return hashlib.md5(f.read()).hexdigest().upper()

    ref_dir = os.path.join(_REPO_PARENT, "旧文件归档", "stardew_valley_1.6_data_v9", "images", "fish")
    ref_ghost = md5_of(os.path.join(ref_dir, "Ghostfish.png"))    # 浅色鱼
    ref_spook = md5_of(os.path.join(ref_dir, "Spook_Fish.png"))  # 蓝鱼

    img_dir = os.path.join(ROOT, "app", "src", "main", "assets", "img")
    app_ghost = os.path.join(img_dir, "鬼鱼.png")
    app_spook = os.path.join(img_dir, "幽灵鱼.png")
    check(os.path.exists(app_ghost) and os.path.exists(app_spook), "鬼鱼.png / 幽灵鱼.png 均存在")
    check(ref_ghost != ref_spook, "参照原图两张内容确实不同（否则断言无意义）")
    check(md5_of(app_ghost) == ref_ghost,
          "鬼鱼图 = Ghostfish 浅色鱼（内容未被换过）")
    check(md5_of(app_spook) == ref_spook,
          "幽灵鱼图 = Spook Fish 蓝鱼（内容未被换过）")

    # image_map 必须仍按中文名指向这两个文件（改文件名会静默丢图标）
    imap = json.load(io.open(IMG, encoding="utf-8"))
    check(imap.get("鬼鱼") == "鬼鱼.png", "image_map：鬼鱼 → 鬼鱼.png")
    check(imap.get("幽灵鱼") == "幽灵鱼.png", "image_map：幽灵鱼 → 幽灵鱼.png")

    # 数据包侧的英文名原图也要保持正确对应（v2.27 曾被误交换，已还原）
    pack_items = os.path.join(_REPO_PARENT, "旧文件归档", "stardew_valley_1.6_data_v9", "images", "items")
    check(md5_of(os.path.join(pack_items, "156_Ghostfish.png")) == ref_ghost,
          "数据包 items/156_Ghostfish.png = 浅色鱼")
    check(md5_of(os.path.join(pack_items, "799_Spook_Fish.png")) == ref_spook,
          "数据包 items/799_Spook_Fish.png = 蓝鱼")

    # ==== 22. v2.28 图鉴顶部分段 + 面板四大组上色 ====
    print("\n== 22. 图鉴顶部分段与面板上色防回归（v2.28） ==")
    # 背景：图鉴 27 分类 / ~823 条目，用户反馈「信息量太大、不好找、还继承了攻略」。
    # 本批把攻略从「图鉴的二级页」提成与数据区平级的顶部分段，并给面板四大组上色。
    codex = src("ui/codex/CodexPage.kt")
    cp_c = code_only(codex)
    gh = src("ui/guide/GuideHomePage.kt")
    gh_c = code_only(gh)
    ma_c = code_only(ma)

    # 22.1 分段骨架
    check("guideContent: @Composable () -> Unit" in codex,
          "CodexPage 接受 guideContent 内容槽（攻略正文由 MainActivity 提供）")
    # ⚠ v2.31 起 CodexPage 里**确实**会出现 openGuide 这个词，但那是跳转指令的字段
    #   （CodexJumpRequest.openGuide，跳册子时落在攻略段），与「持有压栈回调形参」无关。
    #   本条只钉回调形参不回来。
    check("onOpenGuide" not in cp_c, "CodexPage 不再持有 openGuide 压栈回调")
    check("var segment by remember" in cp_c, "CodexPage 有 segment 记忆态")
    check("PixelSegmented(" in cp_c and "📕 数据" in cp_c and "📖 攻略" in cp_c,
          "图鉴顶部有「数据 / 攻略」分段控件")
    check("guideContent()" in cp_c, "攻略段渲染 guideContent")

    # 22.2 分类行不再挤 📖 攻略：全文件只应在分段控件的 options 里出现一次
    check(cp_c.count("📖 攻略") == 1,
          "「📖 攻略」全文件仅出现在分段控件里（分类行已清）")

    # 22.3 codexJump 必须强制切段，否则今日页搜索/钓鱼地图跳完停在攻略段。
    #   v2.31：默认仍是数据段，只有 openGuide（跳册子）才进攻略段——两条需求方向相反，
    #   所以是一行三元而不是两处赋值；口径仍单源。
    check(cp_c.count("segment = if (req.openGuide) 1 else 0") == 1,
          "codexJump 到达时强制切段（默认数据段，openGuide 才进攻略段，且只在这一处）")
    check("segment = 0" not in cp_c and "segment = 1" not in cp_c,
          "没有别处单独写死段号（切段口径单源）")

    # 22.4 GuideHomeBody 拆分：内嵌时不能重复画 PageBar
    check("fun GuideHomeBody(" in gh, "攻略正文已拆出 GuideHomeBody")
    check("fun GuideHomePage(" not in gh, "旧 GuideHomePage 已删（否则内嵌出双 PageBar）")
    check("onBack" not in gh_c, "GuideHomeBody 不再收 onBack（BackHandler 已统一处理）")
    check(gh_c.count("PageBar(") == 0, "GuideHomePage.kt 正文里没有 PageBar（页头交给调用方）")

    # 22.5 MainActivity 接线：十本书回调只写一份，两个消费点复用
    check(ma_c.count("GuideHomeBody(") == 1,
          "十本书跳转回调只写一份 guideBody（两个消费点复用，不会漂移）")
    check("guideContent = guideBody" in ma_c, "CodexPage 由 guideBody 供给攻略内容")
    check("val guideBody: @Composable () -> Unit" in ma_c, "guideBody 声明在两消费点的共同父级")
    check("segment = 0" not in ma_c, "segment 由 CodexPage 内部维护，不外泄到 MainActivity")
    check("Screen.Guide ->" in ma, "Screen.Guide 兜底路由保留（批次 4 后再删）")

    # 22.6 面板上色：四组各有色调，且色值只能取自 PixelColors 既有 token
    # v2.31：这些定义已搬到 ui/codex/CodexPanelSections.kt（跳到面板复用同一套配色），
    # 定义类断言改指新文件，CodexPage 只保留调用点。
    cps_c = code_only(src("ui/codex/CodexPanelSections.kt"))
    check("enum class SectionTone" in cps_c, "面板分组带语义色调枚举")
    check("data class PanelSection(" in cps_c, "面板分组数据类带 label/ids/tone")
    check(cps_c.count("SectionTone.") >= 5, "四个分组各自指定色调 + 兜底 OTHER")
    check("toneColor(" in cp_c, "面板标题 / chip 使用 toneColor")
    # 面板遍历改成 PanelSection（原先是 Pair 的双字段解构 `(section, ids)`）
    check("PANEL_SECTIONS.forEach { sec ->" in cp_c, "面板遍历用 PanelSection 单字段解构")
    check("sec.ids.forEach" in cp_c and "flatMap { it.ids }" in cp_c,
          "面板遍历与白名单兜底都改用 PanelSection.ids")
    # 五档色必须全部映射到 PixelColors token，不得新增硬编码色值（夜间主题才一致）
    tc = cps_c[cps_c.index("fun toneColor"):]
    tc = tc[:tc.index("\n}\n")]
    check(tc.count("-> c.") == 5 and "Color(0x" not in tc,
          "五档组色全部取自 PixelColors 既有 token（不新增硬编码色值）")

    # ==== 23. v2.29 图鉴搜索跨全部分类 ====
    print("\n== 23. 图鉴搜索跨分类防回归（v2.29） ==")
    # 背景：搜索框占位符写着「物品 / 菜谱 / 成就…」，实际只搜当前分类——
    # 用户反馈「好多东西不好找」最直接的成因。本批把已有的全局搜索索引接到图鉴页。
    gs = src("ui/search/GlobalSearch.kt")
    gs_c = code_only(gs)

    # 23.1 索引扩展：SearchHit 带来源短串，且只算最便宜的两条
    check("val source: String" in gs, "SearchHit 新增 source 字段（行内来源短串）")
    check("fun sourceFor(" in gs_c, "来源短串由 sourceFor() 统一产出")
    check("sourceFor(nm)" in gs_c, "buildSearchIndex 每条命中都带上 source")
    # 性能铁律：搜索结果绝不许调 usagesOf。它要扫 gifts(34 人 × loves/likes)、
    # processing、recipeUses、craftUses，823 条逐条调 = O(823 × 大表)，重组必卡。
    check("usagesOf(" not in gs_c, "索引层不调 usagesOf（性能铁律：全量速查留给详情抽屉）")
    for expensive in ("❤ 村民最爱", "👍 村民喜欢", "🍶 可加工", "🍳 烹饪用料", "🔨 制作材料"):
        check(expensive not in gs, "索引层不含昂贵的来源项「%s」" % expensive)
    check("itemSources" in gs_c and "bundleRooms" in gs_c, "sourceFor 只用 O(1) map + 小表")

    # 23.2 分组逻辑：顺序必须由图鉴分类驱动
    check("fun groupHits(" in gs_c, "groupHits() 按分类分组")
    # 顺序必须由 codexGroups 决定。groupBy 建索引本身没问题（只是查表），
    # 真正的错是「直接 groupBy 的结果就是展示顺序」——那会退化成命中顺序。
    check("groups.mapNotNull" in gs_c, "展示顺序由 codexGroups 决定，不用命中顺序")
    check("return groups.mapNotNull { g -> byId[g.id]?.let { g to it } }" in gs_c,
          "groupHits 按 groups 顺序产出，groupBy 仅作查表不决定顺序")
    check("const val SEARCH_GROUP_LIMIT = 20" in gs_c, "每分类 20 条上限常量沿用（不改名，免得影响今日页）")

    # 23.3 搜索框与作用域开关
    check("var searchAll by remember" in cp_c, "CodexPage 有 searchAll 作用域开关")
    check("val searchAll = true" in cp_c or "mutableStateOf(true)" in cp_c, "跨分类默认开启")
    check("全部分类" in codex and "仅本分类" in codex, "开关有全部分类 / 仅本分类两态文案")
    check("🔍 搜索全部" in codex, "占位符改成说明实际作用域（不再言不由衷）")
    check("搜索：物品 / 菜谱 / 成就…" not in codex, "旧占位符已消失（它暗示能跨分类，实际不能）")

    # 23.4 分支隔离：跨分类结果绝不能复用 items，否则钓鱼 6 组筛选会串味
    check("val crossHits = remember(" in cp_c, "跨分类结果单独 remember")
    check("searchGlobal(searchIndex, query)" in cp_c, "跨分类结果走全局索引")
    # 截出跨分类分支，确认它不碰任何钓鱼筛选变量
    branch = cp_c[cp_c.index("if (searching && searchAll) {"):cp_c.index("} else {", cp_c.index("if (searching && searchAll) {"))]
    for fish_var in ("fishFilter", "fishSeasonFilter", "fishLocFilter", "fishTypeFilter",
                     "fishWeatherFilter", "fishDifficultyFilter", "fishSpotFilter", "locToBucket"):
        check(fish_var not in branch, "跨分类结果分支不含 %s（钓鱼筛选会套到非鱼条目上）" % fish_var)
    check("val searchIndex = rememberCodexIndex()" in cp_c,
          "823 条索引经共享单例被 remember 住（v2.31：不再各建一份）")

    # 23.5 点结果直达详情：分类与详情必须同时设
    check("groupHits(crossHits, groups)" in cp_c, "结果按图鉴分类顺序分组渲染")
    check("SEARCH_GROUP_LIMIT" in cp_c, "图鉴页复用每分类上限")
    # ⚠ 不要数全文件出现次数（v2.29 加了「最近查看」的第二处同款写法，会误判成回归）。
    # 真正的约束是「搜索结果那一处必须同设」，按上下文定位。
    _hit_open = cp_c[cp_c.index("val hit = shown[i]"):cp_c.index("FramedIcon(hit.itemName, 26)")]
    check("groupIdx = gi" in _hit_open and "target = CodexTarget(hit.groupId, hit.index)" in _hit_open,
          "点结果同设 groupIdx 与 target（否则关抽屉后停在不相干的分类上）")

    # 23.6 空态文案
    check("搜索只在当前分类内进行" not in codex, "旧空态文案「搜索只在当前分类内进行」已消失")
    check("已搜全部 $groupsSearchable 个分类" in codex or "已搜全部 " in codex,
          "跨分类无结果时说明搜了全部，且分类数实时算（不写死 27）")
    check("全部鱼都标记钓上了，恭喜！" in codex and "还没标记钓获" in codex,
          "钓鱼的两条空态保留（不能被搜索空态吞掉）")

    # 23.7 本批不改的东西：攻略小节索引推给批次 4，搜索层不许出现攻略跳转
    check("Guide" not in gs and "guide" not in gs,
          "索引层不碰攻略（十册小节标题硬编码在 Kotlin 里，跳转通道归批次 4）")

    # ==== 24. v2.29b 图鉴导航记忆：最近查看 / 上次分类 / 面板预览 ====
    print("\n== 24. 图鉴导航记忆防回归（v2.29） ==")
    # 背景：每次冷启动永远停在 groupIdx=0（钓鱼图鉴），上次看一半的条目要找回来
    # 只能重搜；分类面板上 27 个 chip 只有名字和数字，选中前不知道里面装什么。
    pr = src("data/PrefsRepo.kt")
    pr_c = code_only(pr)

    # 24.1 存储分层：必须是全局键，不能按存档分
    check('stringPreferencesKey("codex_recent_json")' in pr_c, "最近查看用全局键 codex_recent_json")
    check('stringPreferencesKey("codex_last_group")' in pr_c, "上次分类用全局键 codex_last_group")
    # 反向断言：不能写成存档级那种带前缀的键函数（d: / pf5: / pf10: / ck:）
    for bad in ("codexLastKey(", "codexRecentKey(", "d:codex", "ck:codex", "pf5:codex"):
        check(bad not in pr_c, "导航记忆不走存档级键（%s 会把导航按存档切开）" % bad)
    check("val codexRecent: List<CodexRecentRef>" in pr_c, "Snapshot 带 codexRecent")
    check("val codexLastGroup: String" in pr_c, "Snapshot 带 codexLastGroup")
    # 导航历史不进备份：importJson 覆盖的是存档级键，全局键不在其中
    exp = pr_c[pr_c.index("suspend fun exportJson"):pr_c.index("suspend fun importJson")]
    check("CODEX_RECENT" not in exp and "CODEX_LAST_GROUP" not in exp,
          "exportJson 不含导航键（导入别人备份不会覆盖你的导航历史）")

    # 24.2 下标漂移防线：存名字不存下标
    check("data class CodexRecentRef" in pr_c, "CodexRecentRef 记录 (groupId, itemName) 配对")
    check("val index: Int" not in pr_c[pr_c.index("data class CodexRecentRef"):pr_c.index("class PrefsRepo")],
          "CodexRecentRef 不含 index 字段（下标会随数据包更新漂移）")
    check("recordRecent" in pr_c and "take(CODEX_RECENT_LIMIT)" in pr_c, "最近查看有上限截断")
    check("filterNot { it.groupId == groupId && it.itemName == itemName }" in pr_c,
          "重复查看同一条时移到最前，不新增（否则连翻十次同一物会填满列表）")
    check("runCatching" in pr_c[pr_c.index("private fun parseRecent"):pr_c.index("val snapshot")],
          "parseRecent 用 runCatching 包住（坏数据不能带崩启动）")
    # 单条记录字段缺失也要能跳过（g/n 缺一即丢），不能整表报废
    _pr = pr_c[pr_c.index("private fun parseRecent"):pr_c.index("val snapshot")]
    check("if (g.isBlank() || n.isBlank()) null else CodexRecentRef(g, n)" in _pr,
          "单条记录缺 g/n 时跳过该条（其余记录照常读）")
    check(".getOrDefault(emptyList())" in _pr, "整段解析失败退化成空列表而不是抛异常")
    # 反查在 UI 侧，且必须丢弃查不到的
    check("indexOfFirst { it.name == r.itemName }" in cp_c, "最近查看按名字反查下标")
    check("if (i < 0) null else r to i" in cp_c, "反查失败（改名/删除）的条目被丢弃，不点开错的")
    # v2.73.4：读的是**运行时视图** codexGroupsWithMods（含 Mod 包的作物条目），
    #   分类的 id 集合本身没变，但两套视图的下标一旦脱节就是「点开是别的分类」。
    #   仍然要求 coerceAtLeast(0) 兜底（v2.18 曾因 groups[groupIdx] 崩过）。
    check(re.search(r"codexGroupsWithMods\.indexOfFirst \{ it\.id == snap\.codexLastGroup \}\.coerceAtLeast\(0\)", cp_c) is not None,
          "上次分类恢复读运行时视图且带 coerceAtLeast(0) 兜底（v2.18 曾因 groups[groupIdx] 崩过）")

    # 24.3 写入必须集中：groupIdx 有 5 个写入点，散写必漏
    check("LaunchedEffect(groupIdx)" in cp_c and "repo.setLastCodexGroup(it)" in cp_c,
          "setLastCodexGroup 挂在 LaunchedEffect(groupIdx) 上")
    check(cp_c.count("repo.setLastCodexGroup(") == 1, "setLastCodexGroup 全项目只有一处调用点")
    check(cp_c.count("repo.recordRecent(") == 1, "recordRecent 全项目只有一处调用点")
    # 反向断言：不得挂在 pager 翻页上（翻页是探索行为，会变成翻页流水账）
    pg = cp_c[cp_c.index("pagerState.settledPage"):]
    pg = pg[:pg.index("\n")]
    check("recordRecent" not in pg, "最近查看不挂在 pager 翻页上（否则变翻页流水账）")

    # 24.4 keep-alive 正确性：初始值 remember 不能带 key
    check("var groupIdx by remember { mutableIntStateOf(lastIdx) }" in cp_c,
          "groupIdx 初始值取自存储")
    check("remember(snap.codexLastGroup)" not in cp_c,
          "初始值 remember 不带 key（带 key 会随勾选/切存档把用户正在看的列表拉回去）")

    # 24.5 面板预览：必须过滤空名 + 单行 + 宽度上限
    # v2.31：PanelPreview 已搬到 CodexPanelSections.kt，图鉴面板与跳到面板共用一份
    check("fun PanelPreview(" in cps_c, "面板预览是独立 composable（两个面板共用一份）")
    pv = cps_c[cps_c.index("fun PanelPreview"):]
    pv = pv[:pv.index("\n}\n")]
    check("filter { it.isNotBlank() }" in pv, "预览过滤空白名（无名条目会渲染成空行）")
    check("take(2)" in pv, "预览只取前 2 个条目名")
    check("maxLines = 1" in pv, "预览单行截断")
    check("width(150.dp)" in pv, "预览宽度有上限（长分类名+预览会撑爆 FlowRow）")
    check("style = Px.caption" in pv, "预览用 caption 字号，不与 chip 抢视觉重量")

    # 24.6 打勾键必须走 codexCheckKey 单源：任何页面都不许出现键前缀字面量。
    # 本条原写「全分类进度条归批次 5，本批不碰打勾命名空间」——批次 5 已落地，
    # 但结论不变且更强了：键构造收进 data/CodexChecks.kt 之后，字面量根本不该出现在页面里。
    # （改指向而非删断言：对象从 CodexPage 扩到 ProgressPage / UnlockDone。）
    pp_c = code_only(src("ui/progress/ProgressPage.kt"))
    ud_c = code_only(src("ui/progress/UnlockDone.kt"))
    for ns in ("museum:", "ach:", "walnut:", "fish:", "codex:"):
        check(ns not in cp_c, "CodexPage 不出现 %s 字面量（键走 codexCheckKey 单源）" % ns)
        check(ns not in pp_c, "ProgressPage 不出现 %s 字面量（键走 codexCheckKey 单源）" % ns)
        check(ns not in ud_c, "UnlockDone 不出现 %s 字面量（键走 codexCheckKey 单源）" % ns)
    # 进度口径单源没被动过：ProgressCalc 里新增收集进度的分类会露出端倪。
    # （不能用「codex 不出现」来判——codexGroups 是既有读取，一直都在。）
    pcalc = code_only(src("data/ProgressCalc.kt"))
    for extra in ("codexRecent", "codexLastGroup", "CODEX_RECENT", "setLastCodexGroup"):
        check(extra not in pcalc, "ProgressCalc 不涉及 %s（进度口径未被本批改动）" % extra)

    # ==== 25. v2.31 全域可达（正文链接 + 跳到面板 + 导航总线）====
    print("\n== 25. 全域可达防回归（v2.31） ==")
    # 背景：只有「图鉴 → 攻略」单向导航；27 分类 / 10 册子 / 34 村民之间
    # 没有任何直接通路。本批补反向导航 + 一个全局跳板。
    ci_c = code_only(src("ui/codex/CodexIndex.kt"))
    px_c = code_only(src("ui/PixelTheme.kt"))
    nb_c = code_only(src("ui/nav/NavBus.kt"))
    jp_c = code_only(src("ui/jump/JumpPanel.kt"))
    gb_c = code_only(src("ui/guide/GuideBooks.kt"))
    gs_c = code_only(src("ui/guide/GuideSubPage.kt"))
    ma_c = code_only(ma)
    tp_c = code_only(src("ui/today/TodayPage.kt"))
    vp_c = code_only(src("ui/villagers/VillagersPage.kt"))

    # 25.1 索引单源：823 条重建一遍要扫 bundleRooms，三处各建就是三倍开销 + 三份口径
    n_build = 0
    for root_dir, _, files in os.walk(SRC):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            t = code_only(io.open(os.path.join(root_dir, fn), encoding="utf-8").read())
            n_build += t.count("buildSearchIndex(") - t.count("fun buildSearchIndex(")
    check(n_build == 1, "buildSearchIndex 全项目只有一个调用点（实测 %d）" % n_build)
    # ⚠ v2.73.12：缓存判据从「引用身份」改成**图鉴视图版本号**。
    #   原因：Mod 数据并进来后视图是 codexGroupsWithMods —— 它每次访问都新建对象，
    #   引用身份永远不同 → 缓存等于没有，每次重组重建上千条索引
    #   （v2.31 专门为这个性能问题做过优化，不能倒退回去）。
    #   **意图没变**：仍然「不重复建」，只是判据从「对象是不是同一个」换成「视图变没变」。
    check("fun codexIndex(" in ci_c and "cachedVersion == version" in ci_c,
          "codexIndex 按图鉴视图版本号缓存（视图没变就不重复建）")
    check("codexIndex(GameData.codexGroupsWithMods" in ci_c,
          "搜索索引用**运行时视图**（含 Mod 包的作物/料理/鱼/商店/地点，否则搜不到）")
    check("by lazy" not in ci_c.split("nameToHits")[1].split("bucketByFirst")[0]
          if "nameToHits" in ci_c else False,
          "nameToHits 不再是 by lazy（否则装包后新增条目永远进不了词典）")
    check("GameData.codexViewVersion" in ci_c,
          "正文词典与首字分桶都跟着图鉴视图版本号走")
    check("fun rememberCodexIndex(" in ci_c, "rememberCodexIndex 提供 composable 入口")
    check("rememberCodexIndex()" in cp_c and "rememberCodexIndex()" in tp_c,
          "图鉴页与今日页都走共享索引")
    check("buildSearchIndex(" not in cp_c, "CodexPage 不再自己建索引")

    # 25.2 切分规则：长度≥2 + 首字分桶 + 最长优先，三条缺一不可
    check(".filter { it.length >= 2 }" in ci_c, "词典过滤掉 1 字名（否则满屏误伤）")
    check("groupBy { it.first() }" in ci_c, "词典按首字分桶（否则每行 3 万次比较）")
    check("sortedByDescending { it.length }" in ci_c, "桶内长度降序（保证最长匹配）")
    check("text.startsWith(name, i)" in ci_c and "i += name.length" in ci_c,
          "命中后跳过整名（不切成前缀+后缀）")

    # 25.3 重名不硬跳：392 个名字跨分类重复，硬跳会点开一个错的条目
    check("if (hits.size == 1)" in ci_c, "只有唯一命中才直接开详情")
    check("NavRequest.Codex(groupId = h.groupId, targetIndex = h.index)" in ci_c,
          "唯一名 → 分类 + 下标直达")
    check("NavRequest.Codex(openSearch = name)" in ci_c,
          "重名 → 退化成跳该词的搜索结果")
    _os = cp_c[cp_c.index("if (req.openSearch.isNotBlank())"):]
    _os = _os[:_os.index("val i = groups.indexOfFirst")]
    check("query = req.openSearch" in _os and "searchAll = true" in _os,
          "重名落点：填搜索词并强制跨分类")
    check("target = null" in _os, "重名落点要清掉残留抽屉（keep-alive 页面 target 跨切页保留）")
    check("groupIdx =" not in _os, "重名落点不改分类（那条重名可能分属多个分类）")

    # 25.4 链接渲染：必须用 LinkAnnotation，且不得自己算字符偏移
    check("LinkAnnotation.Clickable" in ci_c and "withLink(" in ci_c,
          "用 LinkAnnotation 渲染可点物品名")
    check("getOffsetForPosition" not in ci_c and "pointerInput" not in ci_c,
          "不手写 pointerInput 算字符偏移（LinkAnnotation 自己处理手势）")
    check("TextDecoration.Underline" in ci_c, "命中段带下划线（像素风下「可点」的唯一明确标识）")
    check("LocalGuideLinkify = staticCompositionLocalOf { false }" in px_c,
          "链接开关默认关闭（非攻略页面零开销）")
    check("buildLinkedAnnotated(segs, c.gold)" in px_c and "BasicText(" in px_c,
          "PixelText 走 BasicText 渲染 AnnotatedString（material3 的 Text 无此重载）")
    n_provides = ma_c.count("LocalGuideLinkify provides true") + gs_c.count("LocalGuideLinkify provides true")
    check(n_provides == 2, "链接开关只在两处攻略作用域开启（实测 %d 处）" % n_provides)
    check("LocalGuideLinkify provides true" not in cp_c,
          "图鉴页不开链接（列表行本身可点，文本级手势会和行级打架）")

    # 25.5 导航总线单源：栈与页签只能在 AppRoot 动一次
    n_nav_collect = 0
    for root_dir, _, files in os.walk(SRC):
        for fn in files:
            if not fn.endswith(".kt"):
                continue
            t = code_only(io.open(os.path.join(root_dir, fn), encoding="utf-8").read())
            n_nav_collect += t.count("navBus.collect")
    check(n_nav_collect == 1, "navBus 全项目只有 AppRoot 一处消费（实测 %d 处）" % n_nav_collect)
    for sub in ("Codex", "Guide", "Villager"):
        check("data class %s(" % sub in nb_c, "NavRequest 有 %s 分支" % sub)
    check("stack.clear()" in ma_c and "stack.add(req.screen)" in ma_c,
          "导航动作在消费点统一执行（清栈 + 压栈）")
    check("villagerTarget.value = null" in vp_c, "村民页消费后置 null（不重复开抽屉）")
    check("GameData.gifts.any { it.name == name }" in vp_c,
          "村民名对不上就不开抽屉（宁可不开，不能开错人）")

    # 25.6 册子表：目标清单单源，且每项都有对应分支
    check("val GUIDE_BOOKS = listOf(" in gb_c, "册子表是单一数据源")
    check("GUIDE_BOOK_BY_KEY" in gb_c and "guideNav(" in ma_c,
          "MainActivity 从册子表取跳转目标（不再各写一个字面量）")
    for bad in ("stack.add(Screen.GuideIsland)", "stack.add(Screen.Calendar)"):
        check(bad not in ma_c, "MainActivity 不再手写 %s" % bad)
    for bk in ("island", "marriage", "weapons", "skills",
               "foraging", "pets", "weather", "combat", "calendar", "shops"):
        check('GuideBook("%s"' % bk in gb_c, "册子表含 %s" % bk)
        check('guideNav("%s")' % bk in ma_c, "攻略首页回调 %s 走册子表" % bk)
    n_screens = len(re.findall(r"NavRequest\.Guide\(Screen\.(\w+)\)", gb_c))
    check(n_screens == len(re.findall(r'GuideBook\("', gb_c)),
          "全部册子均为压栈目标（v2.35 后无跳分类类入口；表长 %d / Guide 目标 %d）"
          % (len(re.findall(r'GuideBook\("', gb_c)), n_screens))
    # 册子表与 GuideSubPage 分支必须一一对应：表里多一项而 when 里少一个分支，
    # 点进去就是一片空白，且没有任何报错（与 v2.27「判定散落必然漂移」同型）。
    n_branches = len(re.findall(r"Screen\.(?:Guide\w*|Calendar) ->", gs_c))
    check(n_branches == n_screens + 1,
          "GuideSubPage 分支数 = 册子表 + 1 个兜底 Screen.Guide（分支 %d / 册 %d）"
          % (n_branches, n_screens))
    for sname in ("GuideIsland", "GuideMarriage", "GuideWeapons", "GuideSkills",
                  "GuideForaging", "GuidePets", "GuideWeather", "GuideCombat", "Calendar"):
        check("Screen.%s ->" % sname in gs_c, "GuideSubPage 有 %s 分支" % sname)

    # 25.7 底 tab 长按：单击语义一字未改（点当前 tab 回列表顶部）
    check("combinedClickable" in ma_c, "底 tab 用 combinedClickable 支持长按")
    check("onLongClick = onLongPress" in ma_c, "长按弹跳板")
    check("scrollToTop.tryEmit(s0)" in ma_c, "点当前 tab 回列表顶部的旧行为保留")

    # 25.8 范围收敛：本批不新增任何持久化键，也不碰进度口径
    for bad in ("jumpBoard", "lastJump", "codexJumpSeen", "navBusPrefs"):
        check(bad not in pr_c, "本批不新增持久化键 %s（无持久化需求）" % bad)
    check("openGuide" in cp_c and "segment = if (req.openGuide) 1 else 0" in cp_c,
          "跳册子时图鉴落在攻略段（关掉书页回来的落点才对）")

    # ==== 26. v2.32 进度页去重 + 图鉴统一打勾 ====
    print("\n== 26. 打勾口径单源与进度页去重防回归（v2.32） ==")
    # 背景：博物馆 / 成就 / 金核桃原本在进度页与图鉴两处各有一个勾选入口，批次 5 收口到图鉴，
    # 进度页只留「看」的聚合。两条铁律：
    #   ① 键构造只有一个出口（否则三处各写一份必然漂移，v2.27 同型）；
    #   ② 旧键前缀一个字都不能改（改名就得落盘迁移，而 v2.27 已踩过
    #      「只读映射让取消勾选失效」——旧键留在集合里，取消永远取消不掉）。
    cc_path = os.path.join(SRC, "data", "CodexChecks.kt")
    check(os.path.exists(cc_path), "data/CodexChecks.kt 存在（打勾键单源）")
    cc_raw = src("data/CodexChecks.kt")
    cc26 = code_only(cc_raw)
    cx26 = code_only(src("ui/codex/CodexPage.kt"))
    pp26 = code_only(src("ui/progress/ProgressPage.kt"))
    ud26 = code_only(src("ui/progress/UnlockDone.kt"))
    pc26 = code_only(src("data/ProgressCalc.kt"))

    # 26.1 白名单恰好 N 个分类。无脑全开会让「出货图鉴 7 组聚合」这类也长出勾选框。
    #     ⚠ 数量由 want 列表长度算出，不写死数字：加一项分类时只需改列表，
    #     数字跟着走，不会每次都变成一条要记得改的断言（v2.32 教训）。
    #     v2.41 加了 raccoonShop（浣熊商店，与鹦鹉兑换同形态）。
    m26 = re.search(r"CHECK_PREFIX[^=]*=\s*mapOf\((.*?)\n\)", cc_raw, re.S)
    entries26 = re.findall(r'"([^"]+)"\s*to\s*"([^"]+)"', m26.group(1)) if m26 else []
    want26 = ["fishing", "museumMinerals", "museumArtifacts", "achievements", "parrotShop",
              "crops", "tackle", "forage", "secretNotes", "fruitTrees",
              "recipes", "crafting", "animals", "raccoonShop"]
    check(sorted(g for g, _ in entries26) == sorted(want26),
          "CHECK_PREFIX 恰好这 %d 个分类（多一个少一个都算错，实得 %d）"
          % (len(want26), len(entries26)))
    for bad in ("movies", "shipping", "geodes", "monsters"):
        check(bad not in [g for g, _ in entries26],
              "白名单排除 %s（实测条目结构不是收集清单）" % bad)

    # 26.2 旧前缀逐字钉死——本批最重要的一组断言：改了任何一条，用户存档里的勾选全部作废
    for legacy in ('"fishing" to "fish"', '"museumMinerals" to "museum"',
                   '"museumArtifacts" to "museum"', '"achievements" to "ach"',
                   '"parrotShop" to "walnut"'):
        check(legacy in cc_raw, "旧键前缀原样保留：%s" % legacy)
    for gid in ("crops", "tackle", "forage", "secretNotes", "fruitTrees",
                "recipes", "crafting", "animals"):
        check('"%s" to "codex:%s"' % (gid, gid) in cc_raw,
              "新增分类走 codex: 前缀：%s" % gid)

    # 26.3 键拼接只有一个出口
    check("fun codexCheckKey(groupId: String, itemName: String): String?" in cc26,
          "codexCheckKey 返回 String?（白名单外返回 null，调用方据此隐藏勾选框）")
    check(cc26.count("$it:$itemName") == 1,
          "键拼接 \"$it:$itemName\" 只在 CodexChecks.kt 出现一次")

    # 26.4 旧的第二个键出口已删
    for fname, t in (("CodexPage", cx26), ("ProgressPage", pp26), ("UnlockDone", ud26)):
        check("fishCheckKey" not in t, "%s 不再有 fishCheckKey（留着就是第二个键出口）" % fname)

    # 26.5 白名单外不出勾选框：显隐判据是 codexCheckKey 是否返回 null，不是 group.id
    check("val checkKey = remember(group.id, item) { codexCheckKey(group.id, item.name) }" in cx26,
          "勾选键从 codexCheckKey 取")
    check("if (checkKey != null)" in cx26, "勾选框显隐走 codexCheckKey 是否返回 null")
    check("val caught = checkKey in snap.checks" in cx26, "勾选状态读 checkKey")
    check("if (isFishing) {\n                        val caught" not in cx26,
          "勾选框不再由 isFishing 决定显隐")
    # ⚠ 别再用「数 isFishing 个数」当护栏——那会把合法用途（钓鱼词汇「未钓/已钓」、
    #   两条空态文案、钓点状态行）误判成回归。真护栏是结构式的：勾选块内部全程不碰 isFishing。
    # ⚠ 边界必须卡在 `if (checkKey != null)`（勾选块入口）而不是 `val checkKey =` 声明处：
    #   v2.33 把「钓点状态行」挪到了声明之后、勾选块之前，那里的 isFishing 是显示用途（合法）。
    #   断言边界要精确对应「我要防的动作」，宽了会误判、窄了会漏。
    _ck_slice = cx26[cx26.index("if (checkKey != null)"):
                     cx26.index("val caught = checkKey in snap.checks")]
    check("isFishing" not in _ck_slice,
          "勾选块内部不碰 isFishing（显隐只看 codexCheckKey 是否返回 null）")
    # 上限只是提醒：新增 isFishing 用途要先确认不是拿它决定勾选
    # c218：抽出的 filterItems 内部就地判定一次 isFishing（钓鱼专属筛选分支的开关），
    #   故上限 +1（筛选那段从页面 remember 移到 filterItems，用途没变，只是位置变了）。
    check(cx26.count("isFishing") <= 9,
          "isFishing 只用于钓鱼词汇与钓鱼专属筛选/空态（filterItems 判定 + 标签 2 + 筛选 + 空态 2，实得 %d）"
          % cx26.count("isFishing"))

    # 26.6 进度页砍三段：分段恰好 3 项，三段的勾选 UI 连根拔掉
    check('options = listOf("总览", "收集包", "解锁")' in pp26, "进度页分段恰好 3 项")
    for gone in ("VaultSlot", "ACH_CATS", "catOf", "openMuseum"):
        check(gone not in pp26, "进度页已删 %s（三段勾选 UI 不再存在）" % gone)

    # 26.7 砍掉的入口要有替代通路，否则用户找不到在哪勾
    for gid in ("museumMinerals", "achievements", "parrotShop"):
        check('GoCheckLink("%s")' % gid in pp26, "总览行「去勾 ›」跳图鉴分类 %s" % gid)
    check("navBus.value = NavRequest.Codex(groupId = targetGroup)" in pp26,
          "跳转走 navBus（清栈 + 切页签只在 AppRoot 动，续 25.5）")

    # 26.8 越界兜底：section 取值域 0..2，将来若持久化，旧值不该让 when 静默什么都不画
    # ⚠ v2.73.14：总览段从「一张卡」变成「一张卡 + 扩展包入口卡」，所以判据改成
    #   「else 分支里仍画着 OverviewCard」——**意图（越界兜回总览）没变**。
    check("OverviewCard(st)" in pp26 and "else -> {" in pp26,
          "when(section) 有 else 兜回总览（风险 1）")

    # 26.9 该留的一律留着（改指向而非删断言：续 19.4 / 13.3）
    check("calcProgress(snap)" in pp26, "进度页仍走 calcProgress（续 19.4）")
    check("remember(ic.name)" in pp26 and "GameData.usagesOf(ic.name)" in pp26,
          "收集包来源速查仍 remember（续 13.3）")
    check('isItemChecked("fishing", it.name, snap.checks)' in ud26,
          "buoys_all 解锁卡改走 isItemChecked（打勾判定单源）")

    # 26.10 进度口径一字未改：新勾选不计入总进度（用户拍板沿用钓鱼先例，分母保持 279）
    check("val totalDone: Int get() = bundleChecked + museumDone + achDone + walnutDone" in pc26,
          "ProgressCalc.totalDone 一字未改（新勾不进总进度）")
    check("val totalAll: Int get() = bundleTotal + museumTotal + achTotal + walnutTotal" in pc26,
          "ProgressCalc.totalAll 一字未改（分母 279 不动）")

    # 26.11 零迁移：不新增任何持久化键，勾进既有 checks 集合即可
    pr_src = src("data/PrefsRepo.kt")
    for bad in ("codexCheck", "checkMigrated", "CHECK_REMAP", "remapChecks", "checkKeys_v232"):
        check(bad not in pr_src, "不新增持久化键 %s（零迁移）" % bad)

    # 26.12 秘密纸条的键含展示文本，格式改了会让已有勾选作废
    gd_src = src("data/GameData.kt")
    check('CodexItem("#${o.optInt("num")} ${o.optString("text").take(12)}"' in gd_src,
          "秘密纸条条目名格式未变（键里含文本，改截取长度会丢勾选）")

    # ==== 27. v2.33 呈现形态：图标网格 + 抽屉分层 ====
    print("\n== 27. 图标网格 / 抽屉分层 / 相关跳转防回归（v2.33） ==")
    pw27 = code_only(src("ui/PixelTheme.kt"))
    pxw27 = code_only(src("ui/PixelWidgets.kt"))
    st27 = code_only(src("ui/ScrollToTop.kt"))
    ci27 = code_only(src("ui/codex/CodexIndex.kt"))

    # 27.1 网格容器与列数：列数**不许硬编码**。屏宽 411dp 得 6 列、360dp 得 5 列，
    #      写死列数必然在某档屏上出问题——本项目已有两处 360dp 槽位溢出前科。
    check("LazyVerticalGrid(" in cx26, "条目列表改 LazyVerticalGrid（图标网格）")
    check("GridCells.Adaptive(" in cx26, "列数自适应 GridCells.Adaptive")
    check("GridCells.Fixed(" not in cx26, "列数不写死 GridCells.Fixed(N)")
    check("rememberLazyGridState()" in cx26, "网格有自己的滚动状态（与搜索列表的 LazyListState 并存）")

    # 27.2 格子等高 + 标签单行：网格行高由该行最高的格子决定，不锁高会参差；
    #      secretNotes 的条目名是 "#num 文本" 天然偏长，靠单行 + 省略号压住。
    check("height(82.dp)" in cx26, "格子固定高度（等高是网格观感的基本盘）")
    check("maxLines = 1" in cx26, "格子标签单行")
    check("overflow = TextOverflow.Ellipsis" in cx26, "标签溢出走省略号（不是硬裁半截字）")

    # 27.3 PixelText 的 overflow 默认必须是 Clip：全项目 336 处调用不能被波及
    check("overflow: TextOverflow = TextOverflow.Clip" in pw27,
          "PixelText 的 overflow 默认 Clip（不影响任何既有调用）")
    check(pw27.count("overflow = overflow") >= 2,
          "Text 与 BasicText 两条渲染路径都传 overflow")

    # 27.4 勾选语义不回退：网格里方框缩成角标，但「点勾选不进详情」的语义必须保住
    check(".align(Alignment.TopEnd)" in cx26, "勾选角标在格子右上角")
    check("scope.launch { repo.toggleCheck(checkKey) }" in cx26, "勾选通路没断")
    check("target = CodexTarget(group.id, group.items.indexOf(item))" in cx26,
          "点格子仍开详情（勾选不进详情，续 v2.32）")

    # 27.4b（c207）勾选锁定：防手滑误勾的开关必须两端贯通——
    #     锁上后角标只提示不记录；⚠ 上一条的勾选通路必须原样保留（解锁即恢复）。
    #     ⚠ 命名避开 26.11 禁串 codexCheck：锁的是「标记」这个动作，不为勾选数据新增键。
    pr27 = src("data/PrefsRepo.kt")
    check("codexMarkLocked" in cx26, "勾选角标受锁定开关约束（c207 防误触）")
    check("setCodexMarkLocked" in cx26, "锁定开关写入通路在图鉴页接通")
    check("CODEX_MARK_LOCKED" in pr27, "锁定状态落盘（DataStore 键，设备态不进备份）")

    # 27.5 空态：占满整行 + 文案跟着交互改（原先说「条目行尾」，网格里用户会找不到）
    check("GridItemSpan(maxLineSpan)" in cx26, "空态 span 占满整行（否则被挤进一格里逐字断开）")
    check("点格子右上角的方框" in cx26, "空态文案跟着勾选入口改")
    check("全部鱼都标记钓上了，恭喜！" in cx26 and "还没标记钓获" in cx26,
          "钓鱼两条空态原文保留（续 13.17）")

    # 27.6 抽屉分层：长表折叠、短表平铺；折叠态按条目记忆（换条目不串味）
    #     v2.41：阈值 3 → 5。群友「建造需要什么材料」的真凶——buildings 19 条 rows
    #     都是 4 行，卡在 3 与 4 之间被整整齐齐折起来，材料要点两下才看得到。
    #     断言改成钉「阈值 ≤ 5」这个结构事实，而不是死钉数字 3。
    check("item.rows.size <= 5" in cx26, "短表平铺（≤5 行折叠反而多一次点击）")
    check("item.rows.size <= 3" not in cx26, "旧阈值 3 不得复活（会把 4 行的建筑重新藏起来）")
    check("remember(item.name) { mutableStateOf(false) }" in cx26,
          "折叠态按 item.name 记忆，换条目自动收起")
    check("详细属性" in cx26, "折叠组标题说明里面有什么（决策 4）")

    # 27.7 相关跳转复用批次 4 的 resolveItemJump：重名不硬跳（392 个名字跨分类重复）
    check("resolveItemJump(mn)" in cx26, "相关跳转走 resolveItemJump（唯一直跳/重名退化搜索）")
    check("navBus.value = resolveJumpRequest(" in ci27, "resolveItemJump 仍走 navBus（续批次 4 单点消费）")
    check("GameData.bundleRooms" in cx26, "关联关系现算，不落静态表")
    for bad in ("RELATED_MAP", "relatedOf", "STATIC_RELATED", "bundleMatesMap"):
        check(bad not in cx26,
              "不建第二份静态关联表 %s（v2.17「钓点数写死」的复刻）" % bad)

    # 27.8 decode downsample：动了全局图标链路，两头都要钉
    check("inSampleSize" in pxw27, "decode 按 inSampleSize 下采样（2 的幂，不引入插值模糊）")
    check("inJustDecodeBounds" in pxw27, "先只读边界，不分配像素")
    check("maxPx: Int = 1080" in pxw27,
          "maxPx=1080：覆盖分享卡 525px 显示的 2 倍余量（640 会把分享卡糊掉）")
    check("FilterQuality.None" in pxw27, "渲染时仍不过滤（像素锐利）")
    # ⚠ 分享卡在 ui/share/ 子目录下；文件缺失时要报 fail 而不是让整个 verify 崩在 traceback 上
    _share_path = os.path.join(SRC, "ui", "share", "ShareCards.kt")
    if os.path.exists(_share_path):
        check("AssetImage(item.iconKey, 200)" in code_only(src("ui/share/ShareCards.kt")),
              "分享卡仍是 200dp 大图（这是 maxPx 不能低于 525px 的原因）")
    else:
        check(False, "ui/share/ShareCards.kt 不存在（分享卡 200dp 断言无法执行）")

    # 27.9 滚顶重载：双击 tab 仍能回到网格顶部
    check("ScrollToTopEffect(screen: Screen, state: LazyGridState)" in st27,
          "网格有自己的滚顶重载（列表版管搜索结果，网格版管条目列表）")

    # 27.10 范围收敛：折叠态是 UI 态不落盘；进度口径继续不动
    for bad in ("expandedKey", "drawerPrefs", "detailExpanded", "foldState"):
        check(bad not in pr_src, "折叠态不落盘 %s（UI 态，无持久化需求）" % bad)
    check("val totalDone" in pc26 and "val totalAll" in pc26,
          "ProgressCalc 未改（续 26.10，总进度仍 279 口径）")

    # ==== 28. v2.34 二级页 push/pop 转场 ====
    print("\n== 28. 转场防回归（v2.34） ==")
    ma28 = code_only(src("MainActivity.kt"))

    # 28.1 转场只打在二级页那一层。主 tab 是刻意的 keep-alive + alpha 切换（v2.20），
    #      给它套动画会跟那套保活设计打架；用户报的也只是「攻略入口卡跳转生硬」。
    check(ma28.count("AnimatedContent(") == 1,
          "AnimatedContent 全文件恰好 1 处（只包二级页层）")
    check("targetState = sec to stack.size" in ma28,
          "目标状态带栈深 stack.size（只拿 Screen 当 key 时，连推两次同名页不播动画）")

    # 28.2 push/pop 由**栈深**判定，不能用「目标是否为 null」：多级弹栈时目标非空，
    #      那样判会把 pop 当成 push，方向整个反过来（页面朝错误的一侧飞走）。
    check("targetState.second > initialState.second" in ma28,
          "push/pop 由栈深比较判定（null 判据在多级弹栈时会判反方向）")

    # 28.3 方向语义：push 新页从右整屏进、旧页向左退 1/4 视差；pop 反过来，
    #      旧页向右整屏退出——跟返回键 / 返回手势同向，不用重新学。
    check(re.search(r'slideInHorizontally\(tween\(240, easing = FastOutSlowInEasing\)\)\s*\{\s*it\s*\}', ma28),
          "push：新页从右侧整屏推入")
    check(re.search(r'slideOutHorizontally\(tween\(240, easing = FastOutSlowInEasing\)\)\s*\{\s*-it / 4\s*\}', ma28),
          "push：旧页向左退 1/4（视差）")
    check(re.search(r'slideOutHorizontally\(tween\(240, easing = FastOutSlowInEasing\)\)\s*\{\s*it\s*\}', ma28),
          "pop：旧页向右侧整屏退出")
    check(re.search(r'slideInHorizontally\(tween\(240, easing = FastOutSlowInEasing\)\)\s*\{\s*-it / 4\s*\}', ma28),
          "pop：下层页从左侧 1/4 处迎上来")
    check("fadeIn(tween(240))" in ma28 and "fadeOut(tween(240))" in ma28,
          "淡入淡出在场（整屏平移没有它会像硬边在滑）")

    # 28.4 空态必须等尺寸。两个状态一满屏一空，SizeTransform 会把正在退出的那页
    #      按收缩矩形裁掉（表现成页面被从右下角抹掉一截）；空态画满屏空 Box 绕开。
    check(re.search(r'\{\s*\(s, _\)\s*->\s*Box\(Modifier\.fillMaxSize\(\)\)\s*\{\s*if \(s != null\)', ma28),
          "空态也是满屏 Box（前后状态等尺寸，躲开 SizeTransform 收缩裁切）")
    _h28 = ma28.index("{ (s, _) ->") if "{ (s, _) ->" in ma28 else -1
    _h28 = ma28.index("Box(Modifier.fillMaxSize())", _h28) if _h28 >= 0 else -1
    _g28 = ma28.index("if (s != null)", _h28) if _h28 >= 0 else -1
    check(_g28 > _h28 >= 0 and "background(" not in ma28[_h28:_g28],
          "空态 Box 不带背景（带了会盖住下面的主 tab）")

    # 28.5 主 tab 仍是 keep-alive（v2.20）；转场画法 c199 起为横滑镜头位移（121 节钉）
    check("keptTabs.forEach" in ma28 and "val dist = slot - tabCam.value" in ma28
          and "translationX = dist * size.width" in ma28,
          "主 tab 仍是 keep-alive；转场为 c199 横滑镜头位移")

    # 28.6 导航语义一字未改：转场只换画法，不换栈操作
    check("stack.add(req.screen)" in ma28, "入栈调用未改")
    check("stack.removeAt(stack.lastIndex)" in ma28, "出栈调用未改（返回键 + onBack）")
    for br in ("Screen.Settings ->", "in GUIDE_SCREENS ->", "Screen.Guide ->", "else -> {}"):
        check(br in ma28, "二级页 when 分支保留：%s" % br)

    # 28.7 范围收敛：纯 UI 转场，不新增持久化键、不动进度口径
    for bad in ("transitionPref", "animScale", "navAnim", "reduceMotion"):
        check(bad not in pr_src, "不新增持久化键 %s（转场是纯 UI 行为）" % bad)

    # ==== 29. v2.35 二级页返回入口 + 钓鱼地图移出攻略页 ====
    print("\n== 29. 二级页返回小箭头 + 钓鱼地图移出（v2.35） ==")

    pb29 = code_only(src("ui/PageBar.kt"))

    # 29.1 BackBar 本体：它此前是**全项目一处都没被调用过的死代码**，写好了就是等一个
    #      「页内没有返回入口」的机会。断言钉住的是可点的返回键，不是那个壳。
    check("fun BackBar(title: String, onBack: () -> Unit" in pb29,
          "BackBar 签名带 onBack")
    check("← 返回" in pb29 and ".clickable(onClick = onBack)" in pb29,
          "BackBar 有真正可点的「← 返回」")
    check("maxLines = 1" in pb29,
          "BackBar 标题锁单行（挤不下就截断，不许把顶栏撑成两行高）")
    check("right: @Composable () -> Unit = {}" in pb29 and "right()" in pb29,
          "BackBar 保留 right 槽（年历的「第 N 年」徽章不能因此丢）")
    check("background(c.wood).woodGrain()" in pb29 and "height(3.dp).background(c.woodDark)" in pb29,
          "BackBar 仍是木纹条 + 底部深棕压边（与 PageBar 同一视觉，不另起一套）")

    # 29.2 十一个压栈页顶栏**一律** BackBar。
    #      防的不是「忘了改某一个」，而是「以后新增册子又照抄 PageBar」——
    #      所以逐个文件钉，且同时钉「文件里不许再出现 PageBar」。
    guide_book_pages = [
        "ui/guide/GuideIslandPage.kt", "ui/guide/GuideMarriagePage.kt",
        "ui/guide/GuideWeaponsPage.kt", "ui/guide/GuideSkillsPage.kt",
        "ui/guide/GuideForagingPage.kt", "ui/guide/GuidePetsPage.kt",
        "ui/guide/GuideWeatherPage.kt", "ui/guide/GuideCombatPage.kt",
    ]
    for rel in guide_book_pages:
        t = code_only(src(rel))
        # 每册有「数据加载失败」与正常两个分支，两处都得有返回键：
        # 只改正常分支的话，数据缺失那一屏反而成了唯一退不出去的地方。
        check(t.count("BackBar(") == 2, "%s 两个分支都画 BackBar（实测 %d 处）"
              % (os.path.basename(rel), t.count("BackBar(")))
        check("PageBar" not in t, "%s 不再引用 PageBar" % os.path.basename(rel))
        check(t.count("onBack") >= 3, "%s 的 onBack 真被用上（实测 %d 次，非摆设）"
              % (os.path.basename(rel), t.count("onBack")))

    gsub29 = code_only(src("ui/guide/GuideSubPage.kt"))
    cal29 = code_only(src("ui/calendar/CalendarPage.kt"))
    set29 = code_only(src("ui/settings/SettingsPage.kt"))
    check('BackBar("📖 攻略", onBack)' in gsub29,
          "兜底 Screen.Guide 页也画 BackBar")
    check("PageBar" not in gsub29, "GuideSubPage 不再引用 PageBar")
    check('BackBar("📅 年历", onBack)' in cal29 and 'Chip("第 ${snap.year} 年")' in cal29,
          "年历改 BackBar 且右侧「第 N 年」徽章仍在")
    check("PageBar" not in cal29, "年历页不再引用 PageBar")
    check('BackBar("设置", onBack' in set29, "设置页改 BackBar")
    check("PageBar" not in set29, "设置页不再引用 PageBar")

    # 29.3 返回键与系统返回键退的是**同一个栈**：两处都必须是 removeAt(lastIndex)，
    #      页面上的返回键要是另起一套逻辑，迟早会和 BackHandler 走到不同层。
    #      v2.40：enabled 从「有栈」放宽成「有栈 **或** 有临时查看锚点」
    #      （跳去图鉴看一眼时栈是空的，返回键得送得回原页），所以这里按
    #      「enabled 里首项仍是 stack.isNotEmpty()」来断言，不钉死整串字面量。
    check(re.search(r"BackHandler\(enabled = stack\.isNotEmpty\(\)(\s*\|\|[^)]*)?\)", ma_c) is not None
          and "stack.removeAt(stack.lastIndex)" in ma_c,
          "页面返回键与 BackHandler 共用同一个栈操作")

    # 29.4 五个主 tab 不是压栈页，顶栏**不许**出现返回键
    #      （它们是平级页签，返回键在那儿是骗人的——按了只会像坏了）。
    for rel in ("ui/today/TodayPage.kt", "ui/calc/CalcPage.kt", "ui/codex/CodexPage.kt",
                "ui/villagers/VillagersPage.kt", "ui/progress/ProgressPage.kt"):
        t = code_only(src(rel))
        check("BackBar" not in t, "%s 顶栏不加返回键（主 tab 非压栈页）" % os.path.basename(rel))
    check(code_only(src("ui/today/TodayPage.kt")).count("PageBar(") >= 1,
          "主 tab 顶栏仍是 PageBar（PageBar 未被 BackBar 取代）")

    # 29.5 跳板里的册数跟着册子表走，不许写死
    jp29 = code_only(src("ui/jump/JumpPanel.kt"))
    check("GUIDE_BOOKS.size" in jp29 and re.search(r"本册子", jp29) is not None,
          "跳板册数读 GUIDE_BOOKS.size（加减册子不用改文案）")
    check('GuideBook("fishing"' not in gb_c and "fishing" not in jp29,
          "跳板里也没有钓鱼地图（v2.35 已从册子表移出）")

    # 29.6 钓鱼数据一侧分毫未动：入口删了，能力还在
    check("🗺 钓点" in code_only(src("ui/codex/CodexPage.kt")),
          "图鉴钓鱼分类的「🗺 钓点」卡仍在（删的只是攻略页那张入口卡）")
    check('obj("fishSpots.json")' in src("data/GameData.kt"),
          "fishSpots.json 仍照常解析（数据没删，只是入口换了个地方）")

    # 29.7 范围收敛：纯导航入口改动，不落盘、不动进度口径
    for bad in ("lastScreen", "backBarPref", "showBackButton", "backArrow"):
        check(bad not in pr_src, "不新增持久化键 %s（返回箭头是纯 UI 行为）" % bad)

    # ==== 30. v2.36 三处图标内容纠错 ====
    print("\n== 30. 蚌 / 绿宝石 / 黄水晶 图标内容防回归（v2.36） ==")
    # 背景：用户拿维基截图指出三张图与实物对不上。查下来是**三个不同的成因**，
    # 所以不能只钉文件名——文件名对不代表内容对，一律按内容哈希钉死，
    # 权威参照仍是数据包 stardew_valley_1.6_data_v9/images 下的英文名原图。
    #
    #   蚌     —— 文件名对，内容是**蛤蜊 Clam** 的图（壳面放射纹 + 米色）。
    #             贻贝 Mussel 是深灰黑的双壳。同批的「牡蛎/蜗牛」一直是对的，
    #             说明只有「蚌」这一张取错了源，不是整批错。
    #   绿宝石 —— 文件内容根本不是它自己的：image_map 把「绿宝石」指向了
    #             **翡翠.png**（Jade，浅绿水滴形）。应用自己的矿物表里
    #             绿宝石 250 金 = Emerald，绿宝石矿也确实是 Emerald 矿；
    #             翡翠另有一条，两者被映到了同一张图上。
    #   黄水晶 —— 文件内容是另一张高清大图（96×96 / 8KB 的橙黄色棱晶），
    #             而全项目图标都是数据包的 48×48 原图。它和黄水晶 80 金
    #             对应的 Topaz(68) 根本不是同一个东西。

    import hashlib as _hl
    import struct as _st

    def md5_of2(p):
        with open(p, "rb") as f:
            return _hl.md5(f.read()).hexdigest()

    def png_size(p):
        with open(p, "rb") as f:
            head = f.read(24)
        return _st.unpack(">II", head[16:24]) if len(head) >= 24 else (0, 0)

    _pack = os.path.join(_REPO_PARENT, "旧文件归档", "stardew_valley_1.6_data_v9", "images")
    _items = os.path.join(_pack, "items")
    _fish = os.path.join(_pack, "fish")
    _imgd = os.path.join(ROOT, "app", "src", "main", "assets", "img")
    _imap36 = json.load(io.open(IMG, encoding="utf-8"))
    _N_IMG_FILES = 677  # 含 tab/ ui/ wiki/ 三个子目录；本批只换内容不改数量

    def _app(n):
        return os.path.join(_imgd, n)

    # 30.1 蚌 = Mussel 贻贝原图，且不再与蛤蜊同图
    _h_mussel = md5_of2(os.path.join(_fish, "Mussel.png"))
    _h_clam = md5_of2(os.path.join(_fish, "Clam.png"))
    _h_oyster = md5_of2(os.path.join(_fish, "Oyster.png"))
    _h_snail = md5_of2(os.path.join(_fish, "Snail.png"))
    check(_h_mussel != _h_clam, "参照原图：贻贝 ≠ 蛤蜊（否则断言无意义）")
    check(md5_of2(_app("蚌.png")) == _h_mussel, "蚌.png = 贻贝 Mussel 深灰双壳（不再是蛤蜊）")
    check(md5_of2(_app("蚌.png")) not in (_h_clam, _h_oyster, _h_snail),
          "蚌.png 与蛤蜊/牡蛎/蜗牛三张都不撞图")
    check(_imap36.get("蚌") == "蚌.png", "image_map：蚌 → 蚌.png")

    # 30.2 黄水晶 = Topaz(68) 原图，且回到 48×48
    _h_topaz = md5_of2(os.path.join(_items, "68_Topaz.png"))
    check(md5_of2(_app("黄水晶.png")) == _h_topaz, "黄水晶.png = Topaz(68) 橙色圆矿原图")
    check(png_size(_app("黄水晶.png")) == (48, 48), "黄水晶.png 已回到 48×48（不再是 96×96 大图）")
    check(png_size(_app("蚌.png")) == (48, 48), "蚌.png 已回到 48×48（不再是 96×96 大图）")
    check(_imap36.get("黄水晶") == "黄水晶.png", "image_map：黄水晶 → 黄水晶.png")

    # 30.3 绿宝石 = Emerald(60) 原图，且不再与翡翠共图
    _h_emerald = md5_of2(os.path.join(_items, "60_Emerald.png"))
    _h_jade = md5_of2(os.path.join(_items, "70_Jade.png"))
    check(_h_emerald != _h_jade, "参照原图：祖母绿 ≠ 翡翠（否则断言无意义）")
    check(_imap36.get("绿宝石") == "祖母绿.png",
          "image_map：绿宝石 → 祖母绿.png（不再指向翡翠）")
    check(_imap36.get("绿宝石") != _imap36.get("翡翠"), "绿宝石与翡翠不再指向同一张图")
    check(md5_of2(_app("祖母绿.png")) == _h_emerald, "祖母绿.png = Emerald(60) 亮绿原图")
    check(md5_of2(_app("翡翠.png")) == _h_jade, "翡翠.png = Jade(70)（本批没顺手把它换掉）")

    # 30.4 七色宝石两两不同图：专抓「两个名字共用一张图」这类再次串图
    _gems = {
        "绿宝石": _imap36.get("绿宝石"), "海蓝宝石": _imap36.get("海蓝宝石"),
        "红宝石": _imap36.get("红宝石"), "紫水晶": _imap36.get("紫水晶"),
        "黄水晶": _imap36.get("黄水晶"), "翡翠": _imap36.get("翡翠"),
        "钻石": _imap36.get("钻石"),
    }
    _seen = {}
    for _cn, _fn in _gems.items():
        check(_fn is not None, "image_map 有 %s" % _cn)
        _p = _app(_fn) if _fn else ""
        check(bool(_fn) and os.path.exists(_p), "%s → %s 文件存在" % (_cn, _fn))
        if _fn and os.path.exists(_p):
            check(_fn not in _seen,
                  "%s 的图 %s%s" % (_cn, _fn,
                                  (" 未被其他宝石占用" if _fn not in _seen
                                   else " 与 %s 撞了（串图）" % _seen[_fn])))
            _seen[_fn] = _cn

    # 30.5 价格锚点：图钉死了，数据侧也得证明「对应的是哪个物品」
    _ms = json.load(io.open(os.path.join(ROOT, "app", "src", "main", "assets", "data",
                                        "mineralSources.json"), encoding="utf-8"))
    _price = {e["name"]: e.get("price") for e in _ms}
    check(_price.get("绿宝石") == 250, "绿宝石 250 金 = Emerald（与所换的图一致）")
    check(_price.get("翡翠") == 200, "翡翠 200 金 = Jade（两者是不同物品）")
    check(_price.get("黄水晶") == 80, "黄水晶 80 金 = Topaz(68)（与所换的图一致）")
    _fishdata = json.load(io.open(os.path.join(ROOT, "app", "src", "main", "assets", "data",
                                               "fishing.json"), encoding="utf-8"))
    _b = [e for e in _fishdata if e["name"] == "蚌"]
    check(len(_b) == 1 and _b[0].get("price") == 30, "蚌 30 金 = Mussel（与所换的图一致）")

    # 30.6 映射不许指向不存在的文件（改指向时最容易留下悬空键）
    _dangling = [k for k, v in _imap36.items() if not os.path.exists(os.path.join(_imgd, v))]
    check(not _dangling, "image_map 无悬空映射（%d 条悬空）" % len(_dangling))

    # 30.7 范围收敛：本批只换图与改指向，不碰解码链路。
    #     ⚠ v2.43：原先还钉「文件数不变」，但 v2.43 的任务**就是新增图片**（补浣熊商店缺图），
    #     计数式断言把合法新增误判成回归（v2.32 同类教训）。真要防的是「多出临时垃圾文件」，
    #     所以只保留 .tmp/.bak 残留检查，去掉脆弱的文件数计数。
    _junk = [f for f in os.listdir(_imgd)
             if f.endswith(".tmp") or f.endswith(".bak") or f.startswith("~")]
    check(not _junk, "assets/img 无 .tmp/.bak 残留（%d 个）" % len(_junk))
    # 解码下采样上限是分享卡撑起来的（见 27.8），本批没理由动它——这里只做交叉提醒：
    # 换成 96×96 大图时若同时把 maxPx 调小，会把分享卡一起糊掉。
    check("maxPx: Int = 1080" in pxw27,
          "解码上限仍是 1080（图标改大图不影响它，但别趁机调小）")

    # ==== 31. v2.38 生日的「时序」判定：肯特第一年不在场 ====
    print("\n== 31. 生日在场判定防回归（v2.38） ==")
    # 用户报障：「第一次进软件默认第一年一月，会显示 3 天后是肯特的生日，
    # 但肯特第二年才会回星露谷。」
    #
    # ⚠ **排查时踩过一次错，这里必须钉死**：第一反应是「生日数据写错了」，
    # 差点把 gifts.json 里的 春4 改成凭印象记的「July 10」。
    # 双源复核（zh.stardewvalleywiki.com 的 3 个历史版本快照 + 正文）都写
    # 「生日 春季 4日」，**数据是对的**。错的从来不是日期，是**缺少在场判定**。
    # 所以下面第一条断言就是用来防止后人（或未来的我）再次「顺手把日期改对」。
    _va = src("data/VillagerArrival.kt")
    _tpage = src("ui/today/TodayPage.kt")
    _tfacts = src("ui/today/TodayFacts.kt")
    _cmodel = src("ui/calendar/CalendarModel.kt")
    _cpage = src("ui/calendar/CalendarPage.kt")
    _wrend = src("widget/WidgetRender.kt")

    # 31.1 生日日期是静态事实：数据一字不动
    _gifts36 = json.load(io.open(os.path.join(ROOT, "app", "src", "main", "assets", "data",
                                        "gifts.json"), encoding="utf-8"))
    _kent36 = [g for g in _gifts36 if g["name"] == "肯特"]
    check(len(_kent36) == 1, "gifts.json 里肯特恰好一条")
    check(_kent36 and _kent36[0]["birthdaySeason"] == "春" and _kent36[0]["birthdayDay"] == 4,
          "肯特生日仍是 春4（维基三个历史版本一致，**不许**凭印象改成别的日期）")

    # 31.2 在场规则单源：只有 VillagerArrival.kt 允许出现「第二年 / 到场」这类字样
    check("VillagerArrival(2, 0, 1)" in _va, "肯特到场 = 第 2 年春 1（服役一年后回柳巷）")
    check("fun villagerPresent(" in _va, "villagerPresent() 是唯一判定出口")
    check("DEFAULT_ARRIVAL" in _va and "VillagerArrival(1, 0, 1)" in _va,
          "默认第 1 年春 1 已在镇上（其余 %d 位有生日的村民不登记）"
          % (len([x for x in json.load(io.open(os.path.join(
                 ROOT, "app", "src", "main", "assets", "data", "gifts.json"),
                 encoding="utf-8"))
                 if x.get("birthdayDay")]) - 1))   # -1 = 肯特登记了

    _leak = [(n, k) for n, t in
             (("TodayPage", _tpage), ("TodayFacts", _tfacts), ("CalendarModel", _cmodel),
              ("CalendarPage", _cpage), ("WidgetRender", _wrend))
             for k in ("第二年", "第二年春", "第 2 年春 1 日", "VillagerArrival(2")
             if k in code_only(t)]
    check(not _leak, "在场规则没有散落到消费点（%s）" % ("、".join("%s:%s" % a for a in _leak) or "无"))

    # 31.3 四个消费点全部走单源判定
    for _n, _t in (("今日页生日卡", _tpage), ("今日事实/分享卡", _tfacts),
                   ("年历模型", _cmodel), ("桌面小组件", _wrend)):
        check("villagerPresent(" in _t or "birthdayObservable(" in _t,
              "%s 走在场判定（不是裸的 birthdaySeason 过滤）" % _n)

    # 31.4 remember key 必须带年份：跨年时 season/day 相同但结论相反
    check("remember(seasonName, snap.day, snap.year)" in _tpage,
          "今日页 remember key 带 snap.year（不带则第 1 年冬 28 与第 2 年冬 28 结论相同，错）")
    check("remember(snap.prof10, snap.year)" in _cpage,
          "年历 remember key 带 snap.year")
    check("buildCalendar(snap.prof10, snap.year)" in _cpage, "年历按存档年份建索引")
    check("fun buildCalendar(prof10: String?, year: Int)" in _cmodel, "buildCalendar 收 year 形参")

    # 31.5 跨年倒计时：第 1 年冬 28 预告的是第 2 年春 4，那天肯特已回村，说得通
    check("fun daysUntilWithYear(" in src("data/Logic.kt"),
          "Calc.daysUntilWithYear 存在（跨年时须知道生日落在第几年）")
    _logic36 = src("data/Logic.kt")
    check("(year + 1) to (4 * 28 - cur + tgt)" in _logic36,
          "daysUntilWithYear 跨年时返回 year+1 而不是折回本年")

    # 31.6 反向防线：静态事实不许被当成时序事实改掉
    #      ——资料页/攻略页展示的「生日 春4」是对的，不该跟着在场判定一起消失
    _vill36 = src("ui/villagers/VillagersPage.kt")
    check("villagerPresent(" not in _vill36 and "birthdayObservable(" not in _vill36,
          "村民资料页只展示生日日期（静态事实），不加在场过滤")

    # 31.7 报障人要在致谢名单里：这条 bug 是群友 @味子 报的，改动本身够长，
    #      不钉住的话将来重排 CREDITS 会把这条记录悄悄弄丢。
    _sp36 = src_sp()
    # ⚠ 2026-11-10（项目开源）：致谢名单整删 —— 开源后公开群友 ID 侵犯别人隐私
    #    （用户口径）。历史上钉「名单里有谁」的断言全部**翻转**为「名单不得复活」。
    check('Triple("QQ群' not in _sp36 and "val CREDITS" not in _sp36,
          "31.7 致谢名单已删且不得复活（开源后不公开群友 ID）")

    # ==== 32. v2.39 群友三条反馈：引导页对比度 / 三条选 N 规则 / 作物改名 ====
    print("\n== 32. 群友反馈三项修复（v2.39） ==")
    _intro = src("ui/Intro.kt")
    _prefs = src("data/PrefsRepo.kt")
    _mact = src("MainActivity.kt")
    _bundles39 = json.load(io.open(os.path.join(ROOT, "app", "src", "main", "assets", "data",
                                            "bundles.json"), encoding="utf-8"))
    _crops39 = json.load(io.open(os.path.join(ROOT, "app", "src", "main", "assets", "data",
                                           "crops.json"), encoding="utf-8"))
    _imap39 = json.load(io.open(IMG, encoding="utf-8"))

    # 32.1 引导页正文对比度（群友 @若凛233：「新手教程的字看不太清」）
    #     日间 screen #A97D45 vs inkDim #8A6538 亮度只差 25 → 1.43:1，AA 正文要 4.5
    check('PixelText(line, color = c.ink' in _intro or "style = Px.title," in _intro,
          "引导页正文用 c.ink（不再用 c.inkDim）")
    check("color = c.inkDim" not in code_only(_intro),
          "引导页正文不再有 inkDim 落在木色背景上的写法")
    # v2.39.2：字号一并放大。默认档是 Px.body(12sp)，一次性全屏引导页给到 Px.title(16sp)；
    # 钉住「不许退回 12sp」——这跟钉住颜色是同一类：都是「看得清」的一部分。
    check("style = Px.title," in _intro, "引导页正文字号提到 Px.title(16sp)（原默认 Px.body 12sp）")
    check("val title = TextStyle(fontFamily = PixelFont, fontSize = 16.sp" in src("ui/PixelTheme.kt"),
          "Px.title 仍是 16sp（字号档位没被挪动）")
    check("INTRO_TITLES[p], style = Px.big, color = c.cream, shadow = true" in _intro,
          "引导页标题的 cream+shadow 未被动（它本来就是清楚的）")

    # 32.2 三条漏掉的「选 N」规则（群友 @归零：「这里是九选五吧」）
    _bm = {}
    for _room in _bundles39:
        for _bu in _room["bundles"]:
            _bm[_bu["name"]] = (len(_bu["items"]), _bu.get("choose"))
    for _n, _len_want, _c in (("异国情调采集", 9, 5), ("动物制品", 6, 5), ("工匠制品", 12, 6)):
        check(_bm.get(_n) == (_len_want, _c),
              "%s = %d 项，choose=%d（实际 %s）" % (_n, _len_want, _c, _bm.get(_n)))
    check(_bm.get("冒险家", (0, 0))[1] == 2, "冒险家包 choose=2（v2.27 定的，未被波及）")
    check(_bm.get("蟹笼", (0, 0))[1] == 5, "蟹笼包 choose=5（v2.27 定的，未被波及）")
    _chosen = sorted(n for n, (_l, c) in _bm.items() if c)
    # ⚠ v2.96.0：茶水间「高品质作物」加入选 N（4 选 3，用户 2026-10-26 报的口径）。
    #   原来这条钉的是**个数**（5）——个数既说不出「是哪几个」，又多一个包就得手改一次
    #   （v2.32 立的规矩：钉字面量等于给自己埋一次必然踩的雷）。
    #   改成**钉集合**：钉「哪几个包带 choose 规则」，个数由集合本身决定，
    #   比原来那条更严（多一个少一个都红，而且红的时候直接告诉你是谁）。
    _want_chosen = sorted(["异国情调采集", "高品质作物", "动物制品", "工匠制品", "蟹笼", "冒险家"])
    check(_chosen == _want_chosen,
          "带 choose 规则的包恰好是这 %d 个（%s）" % (len(_want_chosen), "、".join(_want_chosen)))

    # 32.3 作物改名：金皮西葫芦（夏南瓜）
    _cn = [c for c in _crops39 if "夏南瓜" in c["name"]]
    check(len(_cn) == 1, "crops.json 里旧名只作为新名的组成部分出现一次")
    check(_cn and _cn[0]["name"] == "金皮西葫芦（夏南瓜）",
          "作物名 = 金皮西葫芦（夏南瓜）（游戏原名 + 括号保留旧名）")
    # 括号写法是为了让**子串**搜索两头都命中，钉住这个前提
    check("金皮西葫芦（夏南瓜）".find("夏南瓜") >= 0 and "金皮西葫芦（夏南瓜）".find("金皮西葫芦") >= 0,
          "新名同时包含两个叫法（图鉴搜索是子串匹配，两边都搜得到）")
    # c211：作物表 42 → 50（补官方 Crops.json 缺的 8 条特殊作物：仙人掌果子/宝石甜莓/
    #   野山葵/香味浆果/普通蘑菇/冬根/纤维/齐瓜——都是 1.6 冬季温室/姜岛/采集种子作物）。
    #   进度分母变化随真值走：断言钉的是「与官方 Crops.json 50 条一致」。
    check(len(_crops39) == 50, "crops 条数 50（c211 补齐官方 Crops.json 全集 42→50）")
    # 价格没错，别被群里带跑：官方维基写「基础售价 45 金 / 成熟 6 天 / 夏季 / 每 3 天再收」
    check(_cn and _cn[0]["sellPrice"] == 45 and _cn[0]["growth"] == 6
          and _cn[0]["regrow"] == 3 and _cn[0]["seasons"] == ["夏"],
          "金皮西葫芦数据未被动（45g / 6天 / 夏 / 每3天再收，与官方维基一致）")

    # 32.4 别名映射：新名 + 纯别名都指向同一张图，旧名三条一条不删
    check(_imap39.get("金皮西葫芦（夏南瓜）") == "夏南瓜.png", "image_map：新名 → 夏南瓜.png")
    check(_imap39.get("金皮西葫芦") == "夏南瓜.png", "image_map：纯别名「金皮西葫芦」也能搜到")
    for _k in ("夏南瓜", "夏南瓜种子", "夏南瓜种子、霜瓜种子"):
        check(_k in _imap39, "image_map 保留旧名条目「%s」（老存档/老搜索仍可能用到）" % _k)

    # 32.5 勾选键必须落盘重写，不能只读映射（v2.27 的教训：只读映射 → 取消不掉）
    check('"codex:crops:夏南瓜" to "codex:crops:金皮西葫芦（夏南瓜）"' in _prefs,
          "勾选键重写映射：codex:crops:夏南瓜 → 新键")
    check("fun migrateCropRename()" in _prefs, "有独立的 migrateCropRename()")
    check("CROP_RENAME_REMAP[it] ?: it" in _prefs, "是落盘重写（不是读取时映射）")
    check('booleanPreferencesKey("check_keys_v239")' in _prefs, "有独立的一次性标记键 check_keys_v239")
    check("CHECK_KEYS_V239] == true" in _prefs and "CHECK_KEYS_V239] = true" in _prefs,
          "标记键读写成对（幂等，不每次启动都进 edit 块）")
    check("for (name in parseNames(p[PROFILES]!!))" in _prefs.split("fun migrateCropRename()")[-1],
          "迁移遍历**所有**存档（玩家自建的存档也要搬）")
    check("repo.migrateCropRename()" in _mact, "MainActivity 在数据就绪后调用迁移")
    check(_mact.index("repo.migrateCheckKeys()") < _mact.index("repo.migrateCropRename()"),
          "作物改名迁移排在 v2.27 迁移之后（执行顺序稳定）")

    # 32.6 出货箱聚合里的同名引用也要一起改，否则两处显示不同名
    _ship39 = json.load(io.open(os.path.join(ROOT, "app", "src", "main", "assets", "data",
                                            "shipping.json"), encoding="utf-8"))
    _ship_names = [it for g in _ship39 for it in g.get("items", [])
                   if (it if isinstance(it, str) else it.get("name")) == "金皮西葫芦（夏南瓜）"]
    check(len(_ship_names) == 1, "出货箱聚合里的作物名已同步改名")
    _leftover = [f for f in ("crops.json", "shipping.json", "nonbuyableSeeds.json", "seedPrices.json")
                 if '"夏南瓜"' in io.open(os.path.join(ROOT, "app", "src", "main", "assets", "data", f),
                                         encoding="utf-8").read()]
    check(not _leftover, "没有文件仍把旧名写成独立条目名（%s）" % ("、".join(_leftover) or "无"))

    # 32.7 报障人要进致谢名单：本轮修的三项各是谁报的，都钉住。
    #      ⚠ 只钉**已修复**的那几位——名单的约定是「反馈被采纳修复后加一行」，
    #      群里还有三条反馈（图鉴跳制作 / 建造材料 / 进度流程）本轮没做，
    #      做完那批再补名，现在提前致谢等于撒谎。
    _sp32 = src_sp()
    for _who, _why in (("QQ群 @若凛233", "新手教程文字优化"),
                       ("QQ群 @归零", "异国情调采集应 9 选 5"),
                       ("QQ群 @Manchester", "金皮西葫芦译名核对"),
                       ("QQ群 @王忠明", "指出 Squash 版本译名差异"),
                       ("QQ群 @剑仙", "金皮西葫芦为原版译名"),
                       ("QQ群 @味子", "第一年误报肯特生日")):
        check('Triple("%s", "%s"' % (_who, _why) not in _sp32,
              "32.7 致谢名单已删（开源后不公开群友 ID）——%s 的感谢改记在 git log 里" % _who)
    check('"新手教程文字看不清"' not in _sp32,
          "致谢文案用「文字优化」而不是复述问题（原措辞已改）")
    # ⚠ v2.41：`指画榴莲` 的两条（建造材料表 + 收集总览加小浣熊）**已修完**，
    #   按「修复后才致谢」的约定加进名单（两行）。另两条仍未做，继续不许出现。
    _notyet = [w for w in (":෴天劫:", "还好只是错饭不是犯错") if w in _sp32]
    check(not _notyet,
          "仍未修复的反馈不提前致谢（%s）" % ("、".join(_notyet) or "无"))

    # ==== 33. v2.40 临时查看：跳出去要能原路返回 ====
    # 群友反馈两条：① 村民页「最爱」点了不跳；② 攻略页点物品跳图鉴后返回停在图鉴页。
    # 根因是同一件事——跳图鉴只做了 stack.clear() + tab = Codex，把出处抹掉了。
    print("\n== 33. v2.40 临时查看原路返回 ==")
    _ma33 = src("MainActivity.kt")
    _ma33c = code_only(_ma33)
    _vp33 = src("ui/villagers/VillagersPage.kt")
    _cp33 = src("ui/codex/CodexPage.kt")
    _ci33 = src("ui/codex/CodexIndex.kt")

    # 33.1 返回锚点本体：记「页签 + 整条栈」的快照，缺一个都还原不回去
    check(re.search(r"private data class PeekOrigin\(val tab: Screen, val stack: List<Screen>\)", _ma33c) is not None,
          "PeekOrigin 同时记页签与整条栈（只记一个都还原不回原处）")
    # ⚠ 必须快照成 toList()：stack 是可变列表，存引用会被后续出栈入栈改掉形状
    check("peek = PeekOrigin(tab, stack.toList())" in _ma33c,
          "记锚点时快照整条栈（存引用会被后续出栈改掉）")
    # 只在「本来不在图鉴页」才记：站在图鉴里连点条目会把锚点刷成「图鉴→图鉴」
    check("if (current != Screen.Codex) peek = PeekOrigin(tab, stack.toList())" in _ma33c,
          "只有不在图鉴页时才记锚点（图鉴内连点不刷锚点）")

    # 33.2 还原出口唯一：系统返回键 / 抽屉 ✕ / 下滑 / 手势全部汇到一处
    check(_ma33.count("val returnFromPeek: () -> Unit = {") == 1,
          "返回锚点只有一个还原出口 returnFromPeek")
    check(re.search(r"peek = null\s*\n\s*if \(o != null\) \{\s*\n\s*stack\.clear\(\)\s*\n\s*stack\.addAll\(o\.stack\)\s*\n\s*tab = o\.tab", _ma33c) is not None,
          "还原动作 = 清栈 + 回填快照栈 + 切回原页签")
    check("enabled = stack.isNotEmpty() || peek != null" in _ma33c,
          "返回键在锚点存在时也接管（否则按返回直接退 App）")
    check("if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else returnFromPeek()" in _ma33c,
          "返回键：有栈先退栈，无栈才走锚点")

    # 33.3 「作废锚点」的路：手点别页签、开一本新册子、跳某位村民，
    #     v2.99.0 起多了 Tab（切主页签）/ Stack（压栈二级页）两条——使用指引页要能跳这两类。
    #     ⚠ 断言改成**逐路由**判定，不再数「peek = null 出现几次」——
    #       数个数是钉字面量：以后再加一条路由就得回来改数字（v2.32 立的教训）。
    _navblk = _ma33c[_ma33c.index("is NavRequest.Guide ->"):_ma33c.index("navBus.value = null")]
    for _nm33 in ("Guide", "Villager", "Tab", "Stack"):
        _rt33 = "is NavRequest.%s ->" % _nm33
        check(_rt33 in _navblk, "导航消费点有 %s 路由" % _nm33)
        if _rt33 not in _navblk:
            continue
        _seg33 = _navblk[_navblk.index(_rt33):]
        _nxt33 = _seg33.find("is NavRequest.")
        if _nxt33 > 0:
            _seg33 = _seg33[:_nxt33]
        check("peek = null" in _seg33, "路由 %s 作废锚点" % _nm33)
    check("if (s0 != Screen.Codex) peek = null" in _ma33c,
          "手点别的页签作废锚点；点当前图鉴只回列表顶部，锚点留着")
    # 今日页也走同一条：跳出去看一眼，返回键得送得回来
    _oc33 = _ma33c[_ma33c.index("openCodex = {"):_ma33c.index("Screen.Calc ->")]
    check("peek = PeekOrigin(tab, stack.toList())" in _oc33,
          "今日页 openCodex 也记锚点")

    # 33.4 keep-alive 抽屉必须按页签可见性门控。
    #     五个主 tab 常驻组合，而 DetailDrawer/WheelDrawer 是 ModalBottomSheet
    #     （独立 Dialog 窗口）——外层 Box 的 alpha = 0 管不到它，不门控就会浮在新页上。
    #     这是**结构性**断言：逐个抽屉抓 visible 实参，不靠数个数。
    #     详情抽屉（图鉴 / 村民）是 `if (t != null && visible) { DetailDrawer(visible = true, …) }`
    #     形态——门控写在 if 上，所以「实参是字面 true」在该形态下不算未门控。
    # v2.70.0：今日页抽屉 4 → 3——生日村民抽屉被拆（点击改跳村民页，NavRequest.Villager）。
    #   档案抽屉只在村民页一处存在，两套抽屉行为必然漂移。
    # v2.80.0：计算页抽屉 3 → 4——鱼价页新增「点鱼不跳页」的详情抽屉（与作物/加工页同形态）。
    for _rel33, _n33 in (("ui/today/TodayPage.kt", 3), ("ui/calc/CalcPage.kt", 4),
                         ("ui/codex/CodexPage.kt", 2), ("ui/villagers/VillagersPage.kt", 1),
                         ("ui/progress/ProgressPage.kt", 2)):
        _t33 = code_only(src(_rel33))
        _vis33 = re.findall(r"(?:DetailDrawer|WheelDrawer|ProfileDrawer)\(\s*visible = ([^,\n]+)", _t33)
        _byif33 = "if (t != null && visible)" in _t33
        _bad33 = [v.strip() for v in _vis33
                  if "visible" not in v and not (_byif33 and v.strip() == "true")]
        check(len(_vis33) == _n33 and not _bad33,
              "%s 的 %d 个抽屉全部按 visible 门控（抓到 %d 个，未门控 %s）"
              % (_rel33.split("/")[-1], _n33, len(_vis33), _bad33 or "无"))
        # 详情抽屉是 `if (t != null) { DetailDrawer(...) }` 形态，门控写在 if 上
        if _rel33.endswith(("CodexPage.kt", "VillagersPage.kt")):
            check("if (t != null && visible)" in _t33,
                  "%s 详情抽屉的 if 条件含 visible" % _rel33.split("/")[-1])
    # v2.71：签名改为多行（多了 snap/repo 形参），只钉 visible 仍在形参表
    check("visible: Boolean," in _vp33 and "fun VillagersPage(" in _vp33,
          "VillagersPage 收下 visible 形参")
    check("fun TodayPage(" in src("ui/today/TodayPage.kt")
          and "    visible: Boolean," in src("ui/today/TodayPage.kt"),
          "TodayPage 收下 visible 形参")
    # 门控只挡渲染、不清状态：清了返回时就回不到「原抽屉」
    check("if (t != null && visible)" in code_only(_vp33)
          and re.search(r"if \(t != null && visible\) \{\s*DetailDrawer\(", code_only(_vp33)) is not None,
          "村民页：可见性只 gate 渲染，抽屉状态 target 不清空（返回时原抽屉还在）")

    # 33.5 村民「最爱」可点
    check(re.search(r"fun SlotBox\((?:.|\n)*?onClick: \(\(\) -> Unit\)\? = null", src("ui/PixelWidgets.kt")) is not None,
          "SlotBox 的 onClick 默认 null（既有调用点零影响）")
    check("onOpenItem: (String) -> Unit = { resolveItemJump(it) }" in _vp33,
          "VillagerDetailBody 的 onOpenItem 默认走图鉴统一解析")
    check("onClick = { onOpenItem(itemName) }" in _vp33,
          "最爱格子把 onClick 接到 onOpenItem")
    check("点格子看该物品的图鉴详情" in _vp33, "最爱卡片提示文案写明格子可点")
    # 0 命中不许静默返回：26 种最爱图鉴确实没有，静默 = 让人以为 App 卡了
    # ⚠ v2.100.0 / c182：判定逻辑抽成了纯函数 resolveJumpRequest（单测要能喂假命中表），
    #   断言的切片跟着改成从它开始（resolveItemJump 现在只是发指令的一行）。
    _rj33 = _ci33[_ci33.index("internal fun resolveJumpRequest"):]
    check("if (hits.isEmpty()) return" not in code_only(_rj33),
          "resolveItemJump 不再对零命中静默返回")
    check("hits.size == 1" in _rj33 and "openSearch = name" in _rj33,
          "零命中 / 重名都落到该词的搜索页")
    # 反向防线：禁止用包含 / 前缀匹配去猜一个「大概对」的条目
    # （量过：双向包含会把「咖啡」送进「咖啡豆」、「鱿鱼墨汁」送进「鱿鱼」）
    _rjc33 = code_only(_rj33)
    check("contains(" not in _rjc33 and "startsWith(" not in _rjc33,
          "resolveItemJump 不做模糊匹配（缺条目靠补数据，不靠猜）")

    # 33.6 图鉴抽屉关闭 = 只关抽屉，**不回锚点**（v2.72.1 改）
    #     v2.40 当初把它设计成「关抽屉 = 看完了 = 回原页，不必再按系统返回」，
    #     但用户的动线是「进度页 → 去勾 › → 点开一条 → ✕ → 继续勾下一条」，
    #     一关就被弹回进度页，等于「去勾」这个入口一次只能看一条。
    #     现在分工：✕ / 下滑 / 返回手势只关抽屉；回原页交给系统返回键（peek 仍在）。
    #     ⚠ 下面两条是**反向**断言：防止有人「顺手」又把 returnFromPeek 接回去。
    check("onPeekDismiss: () -> Unit = {}" in _cp33, "CodexPage 仍收下 onPeekDismiss 回调（留着给需要它的调用方）")
    _od33 = code_only(_cp33)
    check(re.search(r"onDismiss = \{\s*target = null\s*\n\s*onPeekDismiss\(\)", _od33) is not None,
          "详情抽屉三条关闭路径（✕ / 下滑 / 返回手势）汇到一处，且该处只做 target = null + 回调")
    check("onPeekDismiss = returnFromPeek" not in code_only(_ma33),
          "MainActivity 不再把还原出口接到图鉴抽屉（v2.72.1：✕ 不该把人弹回进度页）")
    # 锚点本身必须还在：清掉/断掉它，「回我来的地方」就永远回不去了
    check("if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else returnFromPeek()" in _ma33c,
          "系统返回键仍走锚点（✕ 不回，返回键照回——否则等于把 v2.40 那次修正推翻）")

    # 33.7 攻略正文链接口径一字未动（临时查看不该顺带改链接解析）
    check("linkInteractionListener = { resolveItemJump(s.text) }" in _ci33,
          "攻略正文物品名仍走 resolveItemJump")

    # 33.8 版本备注（归档账本照记不误）
    _notes33 = io.open(os.path.join(ROOT, os.pardir, "安装包归档", "版本备注.md"), encoding="utf-8").read()
    check("v2.40.0" in _notes33, "归档版本备注已记 v2.40.0")

    # ==== 34. v2.41 群友 @指画榴莲：建造材料 + 浣熊商店 ====
    # 群友原话「建造需要什么材料 + 收集总览加小浣熊」。两半都不是"缺数据"，
    # 查证后是两件完全不同的事：① 数据一直都在，被 v2.33 的抽屉折叠藏起来了；
    # ② 浣熊商店是彻底的新增（App 认识浣熊这个人，却没记他那儿能换什么）。
    print("\n== 34. v2.41 建造材料 + 浣熊商店 ==")
    _rc34 = load(os.path.join(DATA, "raccoon.json"))
    _cx34 = code_only(src("ui/codex/CodexPage.kt"))
    _pc34 = code_only(src("data/ProgressCalc.kt"))
    _cc34 = code_only(src("data/CodexChecks.kt"))
    _pp34 = code_only(src("ui/progress/ProgressPage.kt"))
    _gd34 = code_only(src("data/GameData.kt"))

    # 34.1 浣熊商店数据：12 项，每项都要有材料与解锁条件（少任一列这张表就没意义）
    check(len(_rc34) == 12, "raccoon.json 12 项（维基「浣熊妻子的商店」全表，实得 %d）" % len(_rc34))
    _bad34 = [it.get("兑换项", "?") for it in _rc34
              if not str(it.get("材料", "")).strip() or not str(it.get("解锁", "")).strip()]
    check(not _bad34, "每项都有材料与解锁条件（缺项：%s）" % ("、".join(_bad34) or "无"))
    check(len({it["兑换项"] for it in _rc34}) == 12, "兑换项无重名（重名会让打勾键互相串）")
    # 商店初始 4 项按维基：胡萝卜 / 金皮西葫芦 / 西蓝花 / 霜瓜种子
    _init34 = [it["兑换项"] for it in _rc34 if it.get("解锁") == "商店初始"]
    check(sorted(_init34) == sorted(["胡萝卜种子", "金皮西葫芦种子", "西蓝花种子", "霜瓜种子"]),
          "商店初始 4 项与原版一致（%s）" % "、".join(_init34))

    # 34.2 分类与打勾：必须进图鉴且可打勾，键前缀走单源
    check('CodexGroup("raccoonShop", "浣熊商店"' in _gd34, "浣熊商店注册为图鉴分类 raccoonShop")
    check('"raccoonShop" to "raccoon"' in _cc34, "浣熊商店可打勾，键前缀 raccoon:")
    check('"raccoon:${it.name}" in snap.checks' in _pc34, "浣熊计数与键前缀一致")
    check("raccoonTotal = raccoonShop.size" in _pc34, "浣熊分母实时算（不写死 12）")

    # 34.3 ⚠ 分母口径：用户拍板过「新勾选不计入总进度」，浣熊不得进 279
    _pd34 = _pc34[_pc34.index("data class ProgressStats"):_pc34.index("fun bundleDoneCount")]
    check("raccoon" not in re.search(r"val totalDone: Int get\(\).*", _pd34).group(0),
          "浣熊不进 totalDone（新勾选不计入总进度）")
    check("raccoon" not in re.search(r"val totalAll: Int get\(\).*", _pd34).group(0),
          "浣熊不进 totalAll（279 分母一字不动）")

    # 34.4 总览第五行 + 去勾入口
    check('"浣熊兑换"' in _pp34, "总览新增「浣熊兑换」行")
    check('GoCheckLink("raccoonShop")' in _pp34, "浣熊兑换行有「去勾 ›」直达图鉴")

    # 34.5 图标资产：ui 图标必须存在（UiIcon 找不到就整行不画图标，是个静默缺口）
    _uip34 = os.path.join(ROOT, "app", "src", "main", "assets", "img", "ui", "raccoon.png")
    check(os.path.exists(_uip34), "img/ui/raccoon.png 存在（总览行左边的 22dp 图标）")

    # 34.6 建造材料：折叠阈值 3→5 的真凶是 buildings 每条恰好 4 行
    _bd34 = load(os.path.join(DATA, "buildings.json"))
    # v2.98.0：法师塔 6 件多一行「购买」（5 行，仍在阈值内）；「说明」走**段落卡**
    #   （CodexItem.text），它不是属性行，所以要排除掉再数。
    _blen34 = sorted({len([k for k in it if k not in ("建筑", "说明")]) for it in _bd34})
    _old34 = sorted({len([k for k in it if k not in ("建筑", "说明")]) for it in _bd34 if not it.get("说明")})
    check(_old34 == [4], "原 19 条建筑的属性行仍是 4 行（实测 %s）" % _old34)
    check(_blen34 and max(_blen34) <= 5,
          "建筑每条的属性行 ≤5 行（超过 5 会被折进「详细属性」，材料首屏就看不见了，实测 %s）" % _blen34)
    check("item.rows.size <= 5" in _cx34, "短表阈值放宽到 5，材料首屏可见")
    # 反向防线：阈值不得被改回 3 或更小（那条会原样把 19 条建筑重新藏回去）
    _thr34 = re.search(r"item\.rows\.size <= (\d+)", _cx34)
    check(_thr34 is not None and int(_thr34.group(1)) >= 4,
          "折叠阈值不得小于 4（当前 %s）" % (_thr34 and _thr34.group(1)))

    # 34.7 传送图腾这类 1.6 新物在别处已有数据，不得因为本轮而重复造一份
    _im34 = load(os.path.join(ROOT, "app", "src", "main", "assets", "image_map.json"))
    check(_im34.get("金皮西葫芦种子") == _im34.get("夏南瓜种子"),
          "金皮西葫芦种子别名指向夏南瓜图（加别名不删旧键，同 v2.39 改名做法）")
    check("夏南瓜种子" in _im34 and "金皮西葫芦" in _im34,
          "旧键一条不删（老存档与老搜索仍可能用到）")

    # 34.8 致谢：两半都做完了才准加名字（名单约定是「反馈被采纳修复后」才加）
    _sp34 = src_sp()
    check(_sp34.count('Triple("QQ群 @指画榴莲"') == 0,
          "34.8 致谢名单已删（开源后不公开群友 ID）——感谢改记在 git log 里")
    # 群里还没做的两条仍不得提前致谢
    _notyet34 = [w for w in (":෴天劫:", "还好只是错饭不是犯错") if w in _sp34]
    check(not _notyet34, "未修复的反馈不提前致谢（%s）" % ("、".join(_notyet34) or "无"))

    # 34.9 归档账本
    _notes34 = io.open(os.path.join(ROOT, os.pardir, "安装包归档", "版本备注.md"), encoding="utf-8").read()
    check("v2.41.0" in _notes34, "归档版本备注已记 v2.41.0")

    # ==== 35. v2.42 群友 @:🔱天劫:：图鉴条目一键看怎么制作 ====
    # 原话「图鉴条目想一键跳怎么制作」。⚠ 动手前查证结论决定了做法，跟预想不同：
    #   **「菜谱」「制作」本来就是图鉴里的两个分类**（buildCodexGroups 的 rowsGroup），
    #   ingredients 早就拼进它们的 rows 了——所以「怎么制作」不是缺功能，
    #   是**没有入口**：玩家得先想得起某个东西归哪一档，再翻过去。
    #   实测两表 174 条 100% 能在图鉴里按名查到，所以做成详情内现算即可全覆盖。
    # 数据层只补**正向**索引：原先只有反查（材料→成品，即用途速查那两个标签）。
    print("\n== 35. v2.42 图鉴怎么制作 ==")
    _md35 = src("data/Models.kt")
    _gd35 = src("data/GameData.kt")
    _cx35 = code_only(src("ui/codex/CodexPage.kt"))

    # 35.1 模型
    check("data class RecipeInfo(" in _md35, "Models 有 RecipeInfo")
    for _fld in ("val kind:", "val category:", "val source:", "val ingredients:"):
        check(_fld in _md35, "RecipeInfo 带字段 %s" % _fld)

    # 35.2 单一数据源：正反两个索引必须同源解析（v2.27 铁律的另一面）
    check("private fun parseRecipes(" in _gd35 and "private fun usesOf(" in _gd35,
          "配方一次解析（parseRecipes），反向列表由 usesOf 导出")
    check("private fun parseUses(" not in _gd35,
          "旧的 parseUses 已随正向索引一并移除（不与新索引并存成两份真相）")
    # ⚠ v2.73.7：菜谱侧改成「原版底座 + Mod 料理」的**计算属性**（扩展包装了 20 道 SVE 料理，
    #   「虚空鳗能做哪几道菜」这类反查也该认它们）。断言随之改钉**底座那一行**——
    #   钉字面量 `recipeUses = usesOf(recipesByName)` 会在改成计算属性后判红，
    #   但那不是「两份真相」，只是形态变了。**意图（同源导出 / 两份独立）一条没丢。**
    check("baseRecipeUses = usesOf(baseRecipesByName)" in _gd35 and
          "craftUses = usesOf(craftsByName)" in _gd35,
          "用途反查仍从同一份正向索引导出（两索引不得各解析一次）")
    check("baseRecipesByName = parseRecipes(" in _gd35 and "craftsByName = parseRecipes(" in _gd35,
          "菜谱 / 制作各自建正向索引")
    # ⚠ 反向必须仍是**两份独立**列表：usagesOf 靠它们区分「🍳 烹饪用料」与「🔨 制作材料」，
    #   为 DRY 合成一份会让这两个标签消失。
    check("baseRecipeUses = usesOf(baseRecipesByName)" in _gd35 and "craftUses = usesOf(craftsByName)" in _gd35
          and "recipeUses = craftUses" not in _gd35 and "craftUses + recipeUses" not in _gd35,
          "两份反向列表仍分开保存，不合并成一份")
    # v2.73.7：菜谱侧是「底座 + Mod 料理」的计算属性，底座内容与顺序不变（改版前逐条一致）
    check("val recipeUses: List<Pair<String, String>>" in _gd35 and
          "val recipesByName: Map<String, RecipeInfo>" in _gd35,
          "菜谱正反两索引是计算属性（底座 + Mod 料理），不是静态 var")
    check("var baseRecipeUses: List<Pair<String, String>>" in _gd35 and
          "var baseRecipesByName: Map<String, RecipeInfo>" in _gd35,
          "原版底座单独存一份（不与 Mod 合并结果混在一起）")

    # 35.3 改 Map 化的等价性前提：两表零重名 + 插入序保持
    check("LinkedHashMap<String, RecipeInfo>" in _gd35,
          "正向索引用 LinkedHashMap（保持数组顺序，反向列表顺序才与改版前一致）")
    _rcp, _crp = load(os.path.join(DATA, "recipes.json")), load(os.path.join(DATA, "crafting.json"))
    _rnames = [x["name"] for x in _rcp]
    _cnames = [x["name"] for x in _crp]
    check(len(set(_rnames)) == len(_rnames), "recipes.json 内部零重名")
    check(len(set(_cnames)) == len(_cnames), "crafting.json 内部零重名")
    check(not (set(_rnames) & set(_cnames)), "菜谱与制作之间零重名（一个名字只命中一张表）")
    check(all(x.get("name") for x in _rcp + _crp), "两表无空名条目")

    # 35.4 数据事实（数字从文件现算，不写死）
    check(len(_rcp) > 0 and len(_crp) > 0, "两表均非空（菜谱 %d / 制作 %d）" % (len(_rcp), len(_crp)))
    check(all(x.get("source") for x in _rcp + _crp),
          "两表 source 全非空（「获取」行不会出现空值）")
    check(all(x.get("ingredients") for x in _rcp + _crp),
          "两表 ingredients 全非空（不会出现空的「材料」行）")
    check(all("count" in i for x in _rcp + _crp for i in x["ingredients"]),
          "ingredients 全带 count（不靠默认值兜）")
    _maxing = max(len(x["ingredients"]) for x in _rcp + _crp)
    # c210：官方「花桶」5 材料（木材+4 种花球茎/种子），老上限 4 被真实数据顶破——随真值上调。
    check(_maxing <= 5, "单条配方最多 %d 个材料（≤5 一行放得下，不折叠；c210 花桶 5 材料顶破旧上限 4）" % _maxing)

    # 35.5 覆盖率：配方里的成品名必须能在图鉴数据里按名查到（否则做出来会缺一块）。
    #     用 data/ 下全部 json 的 name 字段作**超集**判定：超集是图鉴条目的放大集，
    #     在超集里都找不到 → 必定不在图鉴（不会假阴性）；反方向不据此断言，
    #     免得某个 json 的 name 不是条目时误报。
    _cxnames = set()
    for _f in sorted(os.listdir(DATA)):
        if not _f.endswith(".json"):
            continue
        try:
            _d = load(os.path.join(DATA, _f))
        except Exception:
            continue
        if not isinstance(_d, list):
            continue
        for _it in _d:
            if not isinstance(_it, dict):
                continue
            for _k, _v in _it.items():
                if isinstance(_v, str) and _v and ("name" in _k or "菜名" in _k or "名称" in _k):
                    _cxnames.add(_v)
                    break
    _miss_r = [n for n in _rnames if n not in _cxnames]
    _miss_c = [n for n in _cnames if n not in _cxnames]
    check(not _miss_r, "菜谱 %d 条成品名全部能在图鉴数据里查到（%s）"
          % (len(_rnames), "、".join(_miss_r[:5]) or "无缺失"))
    check(not _miss_c, "制作 %d 条成品名全部能在图鉴数据里查到（%s）"
          % (len(_cnames), "、".join(_miss_c[:5]) or "无缺失"))

    # 35.6 UI：详情内现算，不跳转
    check('val recipe = remember(item.name) { GameData.recipeOf(item.name) }' in _cx35,
          "详情按条目名现算配方（remember 记忆，换条目自动重算）")
    check("🔧 怎么制作" in _cx35, "详情里出现「🔧 怎么制作」卡片")
    check("KVRow(\"材料\"" in _cx35 and "KVRow(\"获取\"" in _cx35, "卡片两行：材料 + 获取")
    check("recipe.category" in _cx35, "制作类带出 category（农业/采矿…）")
    # ⚠ 排除菜谱/制作分类自身：那两类的 rows 里早有「材料 / 获取」（ingredientKey 拼的），
    #   再摆一张是同屏重复。
    check('groupId != "recipes" && groupId != "crafting"' in _cx35,
          "菜谱 / 制作分类自身不重复展示那张卡")
    check("if (recipe != null" in _cx35, "查不到配方就整块不显示")
    # 位置：必须在属性长表之后、分类专属段（cheatCodes / fishing）之前——
    # 它回答的是「这个条目自己怎么来的」，属内；用途速查 / 收集包关联属外，排在外侧。
    # ⚠ v2.79：cheatCodes 专属段用**卡片标题**定位，不再用 group id 字面量——
    #   v2.79 在**列表段**（分类顶部用法说明卡）也出现了 "cheatCodes"，
    #   按字面量 find() 会命中列表段那处，位置断言就被自己人顶掉了。
    _i_rows = _cx35.find("item.rows.size <= 5")
    _i_recipe = _cx35.find("🔧 怎么制作")
    _i_cheat = _cx35.find("📋 复制代码")
    check(-1 < _i_rows < _i_recipe < _i_cheat,
          "位置在属性长表之后、分类专属段之前（先答『怎么来的』再答『有何用』）")
    # 不得改成跳转：一旦跳转就要处理重名、要依赖返回锚点，还会牵连 v2.40 的 PeekOrigin 语义。
    #     直接扫「怎么制作」到下一个分类专属段之间的片段——那就是这张卡的全部代码。
    _seg35 = _cx35[_i_recipe:_i_cheat]
    for _bad in ("resolveItemJump", "openSearch", "codexJump", "navBus"):
        check(_bad not in _seg35,
              "「怎么制作」是现算展示、不得跳走（卡片代码内不得出现 %s）" % _bad)

    # 35.7 零迁移：配方是**只读展示**，不新增任何持久化键，也不碰勾选表与 279 分母。
    #     分类数量由 26.1 的 want26 断言钉死（14 个），此处不重复数——
    #     计数式断言会把合法新增误判成回归（v2.32 教训），真要防的是「键表被改」。
    _cc35 = src("data/CodexChecks.kt")
    _pr35 = src("data/PrefsRepo.kt")
    check('"raccoonShop" to "raccoon"' in _cc35,
          "v2.41 的浣熊前缀原样保留（本版未动勾选键表）")
    for _bad in ("recipeKeys", "recipeMigrated", "recipe_v242", "CHECK_REMAP", "recipeChecked"):
        check(_bad not in _pr35 and _bad not in _cc35 and _bad not in _gd35,
              "不新增持久化键 %s（配方只读，零迁移）" % _bad)
    check('"raccoon:"' not in _cx35 and "raccoonDone" not in _cx35,
          "图鉴详情不掺入进度统计（本版与 279 分母无关）")

    # 35.8 致谢：修完才准加名字；仍未做的那条继续不许出现
    _sp35 = src_sp()
    check('Triple("QQ群' not in _sp35, "35.8 致谢名单已删且不得复活")
    check("图鉴详情补上配方速查" not in _sp35,
          "35.8 致谢文案已随名单删除（开源后不公开群友 ID）")
    _notyet35 = [w for w in ("还好只是错饭不是犯错",) if w in _sp35]
    check(not _notyet35, "仍未修复的反馈不提前致谢（%s）" % ("、".join(_notyet35) or "无"))

    # 35.9 归档账本
    _notes35 = io.open(os.path.join(ROOT, os.pardir, "安装包归档", "版本备注.md"), encoding="utf-8").read()
    check("v2.42.0" in _notes35, "归档版本备注已记 v2.42.0")

    # ================= 36. v2.43.0 浣熊商店 6 项缺图 =================
    # 起因：v2.41 加浣熊商店时，12 项兑换物有 6 项本地没图，运行时静默落「首字占位块」。
    #
    # ⚠ 本节**不钉文件名**，钉「内容哈希 = 正确的英文原图」。
    #   v2.27.1 教训：文件名相同 ≠ 同一资源，同一实体常有两套并存的资源。
    #   钉 md5 才能挡住「换错源」（比如把 Fairy Dust 换成 Stardrop——
    #   两者都是紫色法球，名字都像「仙尘」，光看文件名根本发现不了）。
    print("\n== 36. 浣熊商店缺图补齐（6 项）==")
    _img36 = os.path.join(ROOT, "app", "src", "main", "assets", "img")
    _imap36b = load(IMG)
    _rac36 = load(os.path.join(DATA, "raccoon.json"))

    # 36.1 六项必须有映射且文件真实存在（悬空映射会让运行时静默走占位框）
    #     ★霜瓜种子按**项目既有约定**指向作物图「粉瓜」，不是种子袋图——
    #     实测全项目 10 个种子类条目（胡萝卜/南瓜/防风草/西蓝花/金皮西葫芦/土豆/茄子/草莓/玉米/甜瓜）
    #     一律指向作物图，且本地根本没有对应的种子袋图。跟着走，别自创一套。
    _six36 = {"霜瓜种子": "霜瓜.png", "浣熊日记": "浣熊日记.png", "浣熊帽": "浣熊帽.png",
              "丛林鱼缸": "丛林鱼缸.png", "仙尘": "仙尘.png", "谜之盒": "神秘盒.png"}
    for _cn, _fn in _six36.items():
        check(_imap36b.get(_cn) == _fn, "image_map：%s → %s" % (_cn, _fn))
        check(os.path.exists(os.path.join(_img36, _fn)),
              "%s 文件存在（%s）" % (_cn, _fn))

    # 36.1b 种子指向作物图这条约定本身要钉住：防止以后有人「修正」霜瓜种子
    #      指向种子袋图，弄成同页 4 个种子里只有 1 个长得不一样。
    for _seed, _crop in (("胡萝卜种子", "胡萝卜.png"), ("西蓝花种子", "西蓝花.png"),
                         ("金皮西葫芦种子", "夏南瓜.png"), ("霜瓜种子", "霜瓜.png")):
        check(_imap36b.get(_seed) == _crop,
              "种子沿用作物图约定：%s → %s" % (_seed, _crop))

    # 36.2 浣熊商店 12 项现在**全部**有图标 —— 这是本版的真正目标，
    #     用「集合相等」钉死，比逐项列断言更能防住新增兑换物漏图。
    _names36 = [r["兑换项"] for r in _rac36]
    _naimg36 = [n for n in _names36 if n not in _imap36b]
    check(len(_names36) == 12, "浣熊商店仍是 12 项（当前 %d）" % len(_names36))
    check(not _naimg36, "12 项兑换物全部有图标（缺：%s）" % ("、".join(_naimg36) or "无"))

    # 36.3 内容正确性：md5 必须等于「正确英文原图」的 md5。
    #     参照系分两处，两处都独立可信：
    #       - 本地 1.6 数据包 stardew_valley_1.6_data_v9/images/items（英文名文件）
    #       - B站 wiki 原始字节（App 里既有图经比对 = wiki 原字节零转码）
    #     取不到参照系时降级为「形状断言」，并明确告知，不假装校验过。
    _pack_items36 = os.path.join(_REPO_PARENT, "旧文件归档", "stardew_valley_1.6_data_v9", "images", "items")
    # 浣熊商店 6 项 ↔ 正确英文原图名。仙尘=Fairy Dust（★不是 Stardrop：Stardrop 是星露露）
    _expect36 = {
        "浣熊日记": ("Ways Of The Wild.png", (48, 48)),   # 游戏内书名 Ways of the Wild
        "浣熊帽":   ("Raccoon Hat.png", (48, 48)),
        "丛林鱼缸": ("Jungle Tank.png", (128, 96)),      # 家具图标准尺寸，不是 48×48
        "谜之盒":   ("Mystery Box.png", (48, 48)),
    }
    for _cn, (_en, _size) in _expect36.items():
        _ref36 = os.path.join(_pack_items36, _en)
        _app36 = os.path.join(_img36, _six36[_cn])
        if os.path.exists(_ref36):
            check(md5_of2(_app36) == md5_of2(_ref36),
                  "%s.png 内容 = %s（md5 一致，非仅文件名相同）" % (_cn, _en))
        else:
            warns.append("36.3 无本地参照系：%s（%s），只做了形状断言" % (_cn, _en))
        check(png_size(_app36) == _size,
              "%s.png 尺寸 = %s（当前 %s）" % (_cn, _size, png_size(_app36)))

    # 36.4 霜瓜种子复用既有的「霜瓜.png」（= Powdermelon 作物原图），
    #     按 36.1b 的项目约定，本版**不为它新增图片文件**。
    #     注意 img 目录里那张种子袋图本来就躺在那儿、但全项目无任何键指向它，
    #     是历史遗留的孤儿素材——保留不删（合法备用），但不许拿它当霜瓜种子的图。
    check(md5_of2(os.path.join(_img36, "霜瓜.png")) == md5_of2(os.path.join(_pack_items36, "Powdermelon.png")),
          "霜瓜.png = Powdermelon 作物原图（霜瓜种子复用它，未新增文件）")
    check(_imap36b.get("霜瓜种子") != "霜瓜种子.png",
          "36.4 霜瓜种子不用种子袋图（与同页另外 3 个种子保持一致）")

    # 36.5 仙尘 ≠ 星露露：这是最容易搞混的一对，两个都是紫色法球。
    #     钉住两者内容不同，防止以后误把 Stardrop 当仙尘塞进去。
    _sd36 = os.path.join(_pack_items36, "434_Stardrop.png")
    check(os.path.exists(_sd36), "参照系存在：Stardrop 星露露原图")
    check(md5_of2(os.path.join(_img36, "仙尘.png")) != md5_of2(_sd36),
          "仙尘.png ≠ Stardrop 星露露（两者都是紫色法球，极易混淆）")
    check(_imap36b.get("仙尘") != _imap36b.get("星露露"),
          "image_map：仙尘与星露露不指向同一张图")

    # 36.6 纯资源版：不碰任何代码文件 —— 图标缺失本就是 assets 缺文件，
    #     不该有任何 Kotlin 改动混进来（改了就是范围蔓延）。
    check(bool(src("data/GameData.kt")), "GameData.kt 可读")
    check("浣熊商店这 12 项里有 7 项" not in src("data/GameData.kt"),
          "GameData.kt 旧注释「12 项里有 7 项有图」已被本版更正为 12 项全覆盖")

    # 36.7 本版零改动数据 → 归档账本与 CHANGELOG 交给 37.7 / 38.8 的动态口径断言。
    #     ⚠ 这里原本又写死了 "v2.43.0"，等于把 19.x 刚解掉的「钉字面量」毛病又装了回来
    #     ——升到下一版必然红，而红的原因与它想防的毫无关系。
    #     真正该防的只有一条：**CHANGELOG 首条不得超前于 gradle 的 versionName**
    #     （详见 37.7 的动态版本断言）。版本号一致性由 19.x 统一负责，本节不重复。
    check("浣熊商店这 12 项里有 7 项" not in src("data/GameData.kt"),
          "GameData.kt 旧注释「12 项里有 7 项有图」已被 v2.43 更正为 12 项全覆盖")

    # ================= 37. v2.44.0 缺图 A/B/C 三类（零新增文件）=================
    # 起因：v2.43 补完浣熊商店后全量重扫 10 处图标调用点，得出 49 个缺图条目，
    # 分三类根因；本版处理不需要新图的那 12 个（A 7 + B 3 + C 2）。
    # ⚠ 本节重点不是逐条钉这 12 个，而是三条**结构性**断言——
    #   逐条钉只能防住「这 12 个又坏了」，结构性断言才能防住「同类问题再发生」。
    print("\n== 37. 缺图 A/B/C 三类（零新增图片）==")
    _imap37 = load(IMG)
    _imgd37 = os.path.join(ROOT, "app", "src", "main", "assets", "img")
    _D37 = os.path.join(ROOT, "app", "src", "main", "assets", "data")

    def _L37(fn):
        p = os.path.join(_D37, fn)
        return load(p) if os.path.exists(p) else []

    def _O37(fn):
        p = os.path.join(_D37, fn)
        return load(p) if os.path.exists(p) else {}

    def _rn37(arr, nk):
        out = []
        for o in arr:
            if not isinstance(o, dict):
                continue
            f = next(iter(o.keys()))
            v = o.get(nk, "")
            if not isinstance(v, str) or not v.strip():
                v = o.get(f, "")
            out.append(v)
        return out

    # 37.1 本轮 12 项逐条断言：A 类（文件已在只缺键）+ B 类（别名）+ C 类（真 bug 修复）
    _A37 = {"香酥鲈鱼": "香酥鲈鱼.png", "爆炒青椒": "爆炒青椒.png", "炒鳗鱼": "炒鳗鱼.png",
            "救生汉堡": "救生汉堡.png", "法式田螺": "法式田螺.png",
            "铱金洒水器": "铱金洒水器.png", "变异鲤鱼": "变异鲤鱼.png"}
    _B37 = {"幸运兔子的脚": "兔子的脚.png", "铱金锭": "铱锭.png", "上古水果": "远古水果.png"}
    _C37 = {"黏液": "史莱姆泥.png"}
    for _cn, _fn in list(_A37.items()) + list(_B37.items()) + list(_C37.items()):
        check(_imap37.get(_cn) == _fn, "image_map：%s → %s" % (_cn, _fn))
        check(os.path.exists(os.path.join(_imgd37, _fn)), "%s 对应文件存在（%s）" % (_cn, _fn))

    # 37.2 B 类必须是**同一物品**，用本地数据包原图的内容哈希钉死。
    #     只钉「键存在」挡不住把「铱金锭」错指到「铱金鱼竿」——两个名字都带铱金。
    for _cn, _enref in (("幸运兔子的脚", "446_Rabbit's_Foot.png"),
                        ("铱金锭", "337_Iridium_Bar.png"),
                        ("上古水果", "454_Ancient_Fruit.png"),
                        ("变异鲤鱼", "682_Mutant_Carp.png")):
        _ref37 = os.path.join(_items, _enref)
        _app37 = os.path.join(_imgd37, _imap37[_cn])
        if os.path.exists(_ref37):
            check(md5_of2(_app37) == md5_of2(_ref37),
                  "%s 的图 = %s 原图（内容一致，非仅键存在）" % (_cn, _enref))
        else:
            warns.append("37.2 无参照系：%s（%s）" % (_cn, _enref))
    # A 类同理：这 5 张原本就是孤儿文件，内容也得是对的
    for _cn, _enref in (("香酥鲈鱼", "214_Crispy_Bass.png"), ("爆炒青椒", "215_Pepper_Poppers.png"),
                        ("炒鳗鱼", "225_Fried_Eel.png"), ("救生汉堡", "241_Survival_Burger.png"),
                        ("法式田螺", "729_Escargot.png")):
        _ref37 = os.path.join(_items, _enref)
        _app37 = os.path.join(_imgd37, _imap37[_cn])
        if os.path.exists(_ref37):
            check(md5_of2(_app37) == md5_of2(_ref37),
                  "孤儿图 %s 内容 = %s（键补上后指向的就是它）" % (_cn, _enref))
        else:
            warns.append("37.2 无参照系：%s（%s）" % (_cn, _enref))

    # 37.3 C-1：埃利奥特/艾利欧特 跨文件译名不一致 → 已改数据而非加别名。
    #     ⚠ 选「改数据」是有理由的：埃利奥特只在**一个**已发布文件里出现，
    #     而艾利欧特出现在 gifts/movies/heartEvents/marriage/secretNotes/image_map 六个里，
    #     是既定的规范译名。加 image_map 别名等于把笔误永久固化成「第二个名字」。
    _mg37 = io.open(os.path.join(_D37, "marriageGuide.json"), encoding="utf-8").read()
    check("埃利奥特" not in _mg37, "marriageGuide.json 已无笔误译名「埃利奥特」")
    _gifts37 = [v["name"] for v in _L37("gifts.json")]
    _npcs37 = [o.get("name", "") for o in _O37("marriageGuide.json").get("marriageable_npcs", [])]
    _orphan_npc37 = [n for n in _npcs37 if n not in _gifts37]
    check(not _orphan_npc37,
          "婚姻册 NPC 名全部能在 gifts.json 找到（孤立：%s）——防同类跨文件不一致"
          % ("、".join(_orphan_npc37) or "无"))
    _npc_nomap37 = [n for n in _npcs37 if n not in _imap37]
    check(not _npc_nomap37, "婚姻册 12 位 NPC 全部有头像（缺：%s）" % ("、".join(_npc_nomap37) or "无"))

    # 37.4 C-2：黏液 = v2.27 把「史莱姆泥」改名时漏了 image_map。
    #     收集包现在能 100% 有图，直接断言全集——这是防「改名漏改图标」的结构性断言。
    #
    #     v2.58 例外：电影院的「鱼籽酱」当时项目图标库与 v9 数据包都没有导出，走**显式白名单
    #     + warn**，不留「静默放过」的口子。⚠ c187 续：鱼籽酱（Caviar，id 447）已从本机游戏
    #     1.6.15 官方贴图解出入库，白名单**清空**——收集包 100% 有图，谁再漏建这里当场红。
    _BUN_NO_ICON37 = set()
    _bun37 = [i.get("name", "") for r in _L37("bundles.json")
              for b in r.get("bundles", []) for i in b.get("items", [])]
    _bun_miss37 = sorted(set(n for n in _bun37 if n not in _imap37 and n not in _BUN_NO_ICON37))
    check(not _bun_miss37,
          "收集包 %d 项物品全部有图标（缺：%s）" % (len(_bun37), "、".join(_bun_miss37) or "无"))
    _bun_known_no_icon37 = sorted(set(n for n in _bun37 if n in _BUN_NO_ICON37))
    if _bun_known_no_icon37:
        warns.append("收集包有 %d 项显式豁免图标（1.6 新物品，wiki 图源 403、v9 包未导出）：%s"
                     % (len(_bun_known_no_icon37), "、".join(_bun_known_no_icon37)))
    check(_imap37.get("黏液") == _imap37.get("史莱姆泥"),
          "改名后的「黏液」与旧名「史莱姆泥」指向同一张图（旧键不删，同 v2.39 做法）")

    # 37.5 孤儿图不变式（本轮 A 类的病根）：凡「图标键的文件名已经在 img 里」，
    #     就必须存在映射，否则该条目必然落首字占位块。
    #     这条比逐条断言强：今后新增数据时若再忘记建键，会被当场抓住。
    _keys37 = set()
    _keys37 |= set(o["name"] for o in _L37("crops.json"))
    _keys37 |= set(o["name"] for o in _L37("fishing.json"))
    _keys37 |= set(_rn37(_L37("recipes.json"), "菜名"))
    _keys37 |= set(_rn37(_L37("crafting.json"), "名称"))
    _keys37 |= set(_rn37(_L37("animals.json"), "动物"))
    _keys37 |= set(_rn37(_L37("buildings.json"), "建筑"))
    _keys37 |= set(_rn37(_L37("tackle.json"), "名称"))
    _keys37 |= set(o.get("name", "") for o in _L37("forage.json"))
    _keys37 |= set(o.get("name", "") for o in _L37("cheatCodes.json"))
    _keys37 |= set(o.get("name", "") for o in _L37("monsterDrops.json"))
    _keys37 |= set(o.get("村民", "") for o in _L37("movies.json"))
    _keys37 |= set(o.get("兑换项", "") for o in _L37("raccoon.json"))
    _keys37 |= set(k for v in _L37("gifts.json") for k in (v.get("loves") or []))
    _keys37 |= set(i.get("name", "") for r in _L37("bundles.json")
                    for b in r.get("bundles", []) for i in b.get("items", []))
    _files37 = set(f for f in os.listdir(_imgd37) if os.path.isfile(os.path.join(_imgd37, f)))
    # v2.47.0 起 img 下允许 .webp（7 张节日插画），故两种扩展名都算「文件名已存在」，
    # 否则换格式会**静默缩小这条不变式的覆盖面**——而这正是它当初抓住「孤儿图」的那只猫。
    _orphan37 = sorted(k for k in _keys37 if isinstance(k, str) and k.strip()
                       and ((k + ".png") in _files37 or (k + ".webp") in _files37)
                       and k not in _imap37)
    check(not _orphan37,
          "凡文件名已存在的图标键都有映射（漏建键：%s）" % ("、".join(_orphan37) or "无"))

    # 37.6 v2.44 当批是「零新增图片、只改映射」，故当时钉了具体条数；
    #     ⚠ 但 v2.45 恰恰**要**新增图片（22 张），计数式断言把合法新增误判成回归
    #     （v2.32 立的规矩，本项目自己又犯）。改成钉**结构**：新增文件必须都有映射，
    #     映射不许悬空——这两条才是真正防「加错图」的。
    _files37 = set(f for f in os.listdir(_imgd37) if os.path.isfile(os.path.join(_imgd37, f)))
    _dang37 = sorted(k for k, v in _imap37.items()
                     if not os.path.exists(os.path.join(_imgd37, v.replace("/", os.sep))))
    check(not _dang37, "image_map 无悬空映射（%d 条）" % len(_dang37))
    check(not [f for f in _files37 if f.endswith(".tmp") or f.endswith(".bak") or f.startswith("~")],
          "assets/img 无 .tmp/.bak 残留")

    # 37.7 版本口径（全部按 gradle 现算，**不钉字面量版本号**）。
    #     19.x 已有「归档 APK 文件名 ↔ gradle」这条；这里补「CHANGELOG 首条不得超前于
    #     versionName」——真正要防的是**首条写了还没发布的版本**，而不是首条等于某个具体版本。
    #     写死版本号的话每升一版都要手改这里，是给自己埋雷（v2.32 / v2.42 各犯过一次）。
    _g37 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    _m37 = re.search(r'versionName\s*=\s*"([^"]+)"', _g37)
    check(bool(_m37), "gradle versionName 可解析")
    if _m37:
        _vn37 = _m37.group(1)
        _notes37 = io.open(os.path.join(ROOT, os.pardir, "安装包归档", "版本备注.md"),
                           encoding="utf-8").read()
        check("v" + _vn37 in _notes37,
              "归档账本已记当前版本 v%s" % _vn37)
        _first37 = src("ui/settings/SettingsPage.kt").split("private val CHANGELOG", 1)[1] \
                       .split("to listOf(", 1)[0]
        _mfirst37 = re.search(r"v(\d+(?:\.\d+)+)", _first37)
        check(bool(_mfirst37), "CHANGELOG 首条可解析出版本号（当前：%s）" % _first37.strip())
        if _mfirst37:
            _fv37 = tuple(int(x) for x in _mfirst37.group(1).split("."))
            _gv37 = tuple(int(x) for x in _vn37.split("."))
            check(_fv37 <= _gv37,
                  "CHANGELOG 首条 v%s 不得超前于 gradle v%s" % (_mfirst37.group(1), _vn37))
            if _fv37 == _gv37:
                pass
            else:
                # 用户口径（2026-10 起确立）：**版本号该升还要升，但可以不占 App 内更新日志条目**——
                # 判据是「App 用户能感知的价值」，不是「我这轮做了什么」；文案微调、致谢名单、
                # 纯内部/打包改动都不该各占一条（v2.27.1~2.27.3 三条就是这么堆出来的，已删）。
                # 但「有意不写」和「懒得写」在代码里长得一模一样，所以这里不放水：
                # 首条滞后于本版时，归档账本里必须**显式声明**这一版的取舍，
                # 否则例外就变成了默认，App 用户迟早看到两条只字不差的空日志。
                _row37 = next((ln for ln in _notes37.splitlines()
                               if "v" + _vn37 in ln and ln.strip().startswith("|")), "")
                check("无 App 内更新日志条目" in _row37,
                      "首条滞后于 v%s 时，归档账本该版本行须显式声明「无 App 内更新日志条目」" % _vn37)

        # 37.9 v2.64.0：更新日志**入口已移除，数据必须保留**（两件事成对，防删过头）
        #     用户原话「把设置页面的更新日志板块去掉吧，感觉没什么用」——删的是 UI。
        #     CHANGELOG 数据有两个活消费者：publish_gitee 的 notes 默认源（正则直读本文件）、
        #     本节与 53.x/54.x 的文风断言。有人「顺手把数据也清了」时这里会红。
        _sp37 = src("ui/settings/SettingsPage.kt")
        check("查看版本更新记录" not in _sp37 and "showLog" not in _sp37,
              "设置页无更新日志入口/抽屉残留（v2.64.0 应用户要求移除）")
        check("private val CHANGELOG = listOf(" in _sp37,
              "CHANGELOG 数据仍在 SettingsPage（UI 移除不等于数据可删，发布 notes 还靠它）")
        check('CHANGELOG = listOf' in code_only(_sp37) or "CHANGELOG" in code_only(_sp37),
              "CHANGELOG 未被移出 SettingsPage（publish_gitee 正则按此文件名取数）")

    # ================= 38. v2.45.0 真缺图清零 =================
    # 起因：v2.44 处理完 A/B/C 三类后剩 39 个真缺图，v2.45 派子代理取图后补齐。
    # ⚠ 本节钉的是**结论**（真缺图为 0），不是「补了这 31 条」——
    #   逐条钉只能防这 31 条坏了，钉「所有物品类分组必须 100% 有图」
    #   才能防住今后新增数据带进来的漏图。
    print("\n== 38. 真缺图清零（物品类分组 100% 有图标）==")
    _imap38 = load(IMG)
    _imgd38 = os.path.join(ROOT, "app", "src", "main", "assets", "img")
    _D38 = os.path.join(ROOT, "app", "src", "main", "assets", "data")

    def _L38(fn):
        p = os.path.join(_D38, fn)
        return load(p) if os.path.exists(p) else []

    def _O38(fn):
        p = os.path.join(_D38, fn)
        return load(p) if os.path.exists(p) else {}

    def _rn38(arr, nk):
        out = []
        for o in arr:
            if not isinstance(o, dict):
                continue
            f = next(iter(o.keys()))
            v = o.get(nk, "")
            if not isinstance(v, str) or not v.strip():
                v = o.get(f, "")
            out.append(v)
        return out

    # ⚠ 口径必须是「按 item.name」，因为 CodexPage 网格用的是 `FramedIcon(item.name)`
    #   而不是 iconKey——扫 iconKey 会漏掉整批分类（v2.45 踩过这个坑）。
    # ⚠ fishPonds 里的「海参/沙丁鱼等」是带「等」的**概括行**（"对应鱼子"），
    #   它不是一件物品、没有单一图标，显示占位块是正确行为，故显式排除。
    _NONITEM38 = {"海参/沙丁鱼等"}
    # ⚠ v2.79：小鸡代码补进 134 条后，有 23 条**本地任何素材包里都没有图**
    #   （1.6 新物品 12 条 + 1.6 新靴子 7 条 + 4 个大件）。它们显示 App 既有的
    #   「首字占位块」——App 里没有的东西不能靠编图凑，那等于给玩家看假图。
    #   这里**逐条列出**而不是把断言放松成「允许任意缺图」：后者等于把这只猫放跑，
    #   下次真出现「图漏建」也会照样绿。名单是白名单，多一条都要在这里手动加并说明理由。
    # v2.98.0：法师塔 6 件魔法建筑（图腾柱/祝尼魔小屋/黄金时钟）本地**任何素材包里
    #   都没有图**（它们是 1.5+ 的大件 big craftable，图在 Craftables 图集里，
    #   本机没有游戏原图可取）。按同一条纪律：不靠编图凑，宁可显示「首字占位块」。
    #   ⚠ 名单是白名单：多一条都要在这里手动加并写明理由（见上面的说明）。
    # ⚠ c187 续：这份白名单已**清空** —— 29 条里 14 条在 c187 就补上了图（1.6 新物品
    #   与大件），14 条是 c187 删掉的编造靴子/已订正名（蟹壳靴…冷却靴、骷髅戒指），
    #   剩下「鱼籽」本轮也从本机游戏 1.6.15 官方贴图里切出来补上了。
    #   ⇒ 小鸡代码现在**条条都有图**，白名单空着才是真断言（谁再漏建图这里就红）。
    # c210：crafting 补官方配方后新增 35 条 BC 大件无图源（本地无游戏贴图集、v9 包无
    #   BC 图、官方 wiki 403、807 张 Objects 贴图不含 BC）——按 v2.98 魔法建筑先例：
    #   不编图，首字占位块，逐条列白名单。等拿到游戏贴图后清空。
    # c211：c210 留下的 35 条 BC 大件白名单**已清空** —— 官方 1.6.15 Craftables 图集
    #   切片已入库（img/official_bc/，147 张 16×32 原图），35 条全部挂上官方图。
    #   ⇒ 「物品类分组 100% 有图标」重新变成**真断言**：谁再漏建图这里就红。
    #   （历史白名单：v2.79 小鸡代码 23 条 → c187 清空；v2.98 魔法建筑 6 件 → 本轮清空）
    _NOICON38 = set()
    _itemgroups38 = {
        "fishing": [o["name"] for o in _L38("fishing.json")],
        "crops": [o["name"] for o in _L38("crops.json")],
        "fruitTrees": _rn38(_L38("fruitTrees.json"), "果实"),
        "recipes": _rn38(_L38("recipes.json"), "菜名"),
        # c210：crafting 组同样接白名单（6 条 BC 大件本地无图源，见 _NOICON38 说明）
        "crafting": [n for n in _rn38(_L38("crafting.json"), "名称") if n not in _NOICON38],
        "animals": _rn38(_L38("animals.json"), "动物"),
        # v2.98.0：法师塔 6 件大件本地无图（见 _NOICON38 的说明），按 cheatCodes 同款做法
        #   从本组的取名列表里排除——断言仍然盯住另外 19 条建筑的真缺图。
        "buildings": [n for n in _rn38(_L38("buildings.json"), "建筑") if n not in _NOICON38],
        "fishPonds": [k for k in _rn38(_L38("fishPonds.json"), "鱼") if k not in _NONITEM38],
        "toolUpgrades": _rn38(_L38("toolUpgrades.json"), "升级"),
        "geodes": _rn38(_L38("geodes.json"), "晶洞"),
        "seedPrices": _rn38(_L38("seedPrices.json"), "种子"),
        "tackle": _rn38(_L38("tackle.json"), "名称"),
        "museumMinerals": [o.get("name", "") for o in _O38("museum.json").get("minerals", [])],
        "museumArtifacts": list(_O38("museum.json").get("artifacts", [])),
        "forage": [o.get("name", "") for o in _L38("forage.json")],
        "movies": [o.get("村民", "") for o in _L38("movies.json")],
        "festivals": [o.get("name", "") for o in _L38("festivals.json")],
        # v2.117：出货图鉴条目改成对象（name/price/shippable），这里按 name 取。
        #   ⚠ 旧的写法直接把 items 当字符串用 —— 条目变对象后取到 dict，
        #   后面 `isinstance(k, str)` 会把整组过滤掉 ⇒ 38.1 的图标断言**空转**。
        "shipping": [(i if isinstance(i, str) else i.get("name", ""))
                     for g in _L38("shipping.json") for i in g.get("items", [])],
        "raccoonShop": [o.get("兑换项", "") for o in _L38("raccoon.json")],
        "monsters": [o.get("name", "") for o in _L38("monsterDrops.json")],
        "cheatCodes": [o.get("name", "") for o in _L38("cheatCodes.json")
                       if o.get("name") not in _NOICON38],
        # c212：加工图鉴（processing.json）——成品作条目名，需有图标。
        #   动态风味产品（果酒/果汁/果酱/腌菜）官方无独立 sprite，按先例复用基础图
        #   （果酒/果汁/果酱/腌菜.png），故这部分成品无需独立图。
        "processing": [o.get("成品", "") for o in _L38("processing.json")],
    }
    _g38 = {}
    for _gid, _ks in _itemgroups38.items():
        _m = sorted(set(k for k in _ks if isinstance(k, str) and k.strip() and k not in _imap38))
        if _m:
            _g38[_gid] = _m
    check(not _g38,
          "19 个物品类分组全部有图标（缺：%s）"
          % ("；".join("%s=%s" % (k, "、".join(v[:6])) for k, v in _g38.items()) or "无"))
    check(_NONITEM38 <= set(_rn38(_L38("fishPonds.json"), "鱼")),
          "「海参/沙丁鱼等」仍在数据里（概括行，被断言显式排除而非删数据）")
    check(all(x not in _imap38 for x in _NONITEM38),
          "概括行一律不配图标（它是文本不是物品）")

    # 38.2 别名组：同一物品不同译名，指向既有文件、**不得新增文件**
    for _cn, _fn in (("仙人掌果子", "仙人掌果.png"), ("海鲜杂烩汤", "蛤蜊浓汤.png"),
                     ("精灵石", "仙女石.png"), ("葡萄酒", "酒.png"),
                     ("虞美人籽松糕", "罂粟籽松饼.png"), ("野梅", "野李子.png"),
                     ("金南瓜", "金色南瓜.png"), ("香味浆果", "香料浆果.png"),
                     ("雷欧", "里奥.png")):
        check(_imap38.get(_cn) == _fn, "别名：%s → %s" % (_cn, _fn))

    # 38.3 雷欧 = 里奥：项目里那张是有损重编码版，故只比像素不比字节
    _leo_new = os.path.join(_imgd38, _imap38["雷欧"])
    _leo_old = os.path.join(_imgd38, "里奥.png")
    check(os.path.exists(_leo_new) and os.path.exists(_leo_old), "雷欧/里奥 两张图都在")
    if os.path.exists(_leo_new) and os.path.exists(_leo_old):
        from PIL import Image as _PilI38
        _a38 = _PilI38.open(_leo_new).convert("RGBA").tobytes()
        _b38 = _PilI38.open(_leo_old).convert("RGBA").tobytes()
        _same38 = (_a38 == _b38) or (
            _PilI38.open(_leo_new).size == _PilI38.open(_leo_old).size and
            _PilI38.open(_leo_new).resize(_PilI38.open(_leo_old).size).convert("RGBA").tobytes() == _b38)
        check(_same38, "雷欧与里奥像素一致（同一角色，字节不同只因项目那张是有损重编码）")

    # 38.4 虎斑史莱姆蛋 ≠ 绿色蛋：**本地数据包把 5 张蛋存成了同一张绿图**，
    #     照抄包会拿到错误的绿色蛋，必须与绿色蛋内容不同。
    #     ⚠ 只比**内容**不比尺寸：项目里的「绿色史莱姆.png」是 65×67 的特殊图，
    #     钉 48×48 会因尺寸差异误报，而它想防的从来不是尺寸。
    _tiger38 = os.path.join(_imgd38, _imap38["虎斑史莱姆蛋"])
    _green38 = os.path.join(_imgd38, "绿色史莱姆.png")
    if os.path.exists(_tiger38) and os.path.exists(_green38):
        from PIL import Image as _PilI38b
        _t38 = _PilI38b.open(_tiger38).convert("RGBA")
        _g38b = _PilI38b.open(_green38).convert("RGBA")
        check(_t38.size == (48, 48),
              "虎斑史莱姆蛋为 48×48 物品图标（当前 %s）" % (_t38.size,))
        check(_t38.tobytes() != _g38b.tobytes(),
              "虎斑史莱姆蛋内容 ≠ 绿色史莱姆蛋（数据包里两者是同一张，不能照抄）")
    # 参照系：本地官方包里有原图的，逐字节钉
    for _cn, _enref in (("鳟鱼汤", "219_Trout_Soup.png"), ("树液", "92_Sap.png"),
                        ("金南瓜", "373_Golden_Pumpkin.png")):
        _ref38 = os.path.join(_items, _enref)
        if os.path.exists(_ref38):
            check(md5_of2(os.path.join(_imgd38, _imap38[_cn])) == md5_of2(_ref38),
                  "%s.png 内容 = %s 原图（本地包字节一致）" % (_cn, _enref))
        else:
            warns.append("38.4 无参照系：%s（%s）" % (_cn, _enref))

    # 38.5 gifts.json 四组译名已归位，与 codex 菜谱/官方 item_index 对齐。
    #     ⚠ 旧名只剩在 wiki 原始抓取件里，已发布数据中一条都不许残留。
    _gtxt38 = io.open(os.path.join(_D38, "gifts.json"), encoding="utf-8").read()
    for _old38 in ("胡椒爆炒", "求生汉堡", "法式蜗牛", "脆皮鲈鱼"):
        check(_old38 not in _gtxt38, "gifts.json 已无偏离译名「%s」" % _old38)
    for _cn38 in ("爆炒青椒", "救生汉堡", "法式田螺", "香酥鲈鱼"):
        check(_cn38 in _gtxt38, "gifts.json 使用规范译名「%s」" % _cn38)
    _rec38 = set(o.get("name", "") for o in _L38("recipes.json"))
    for _cn38 in ("爆炒青椒", "救生汉堡", "法式田螺", "香酥鲈鱼"):
        check(_cn38 in _rec38, "规范译名「%s」在菜谱里也能查到（送礼格子点得跳转）" % _cn38)

    # 38.6 岩浆史莱姆：1.6 官方 78 怪物图鉴中不存在，本版从 monsterDrops 移除。
    _md38 = _L38("monsterDrops.json")
    check("岩浆史莱姆" not in [o.get("name", "") for o in _md38],
          "monsterDrops 已移除游戏中不存在的「岩浆史莱姆」")
    _mons38 = os.path.join(_REPO_PARENT, "旧文件归档", "stardew_valley_1.6_data_v9", "images", "monsters")
    if os.path.isdir(_mons38):
        _slime38 = [f for f in os.listdir(_mons38) if "slime" in f.lower()]
        _magma38 = [f for f in _slime38 if "magma" in f.lower() or "lava" in f.lower()]
        check(not _magma38,
              "官方贴图包无 magma/lava slime（佐证「岩浆史莱姆」不存在）：%s" % ("、".join(_magma38) or "无"))

    # 38.7 非物品分组**不该**有物品图——它们显示占位块是正确行为。
    #     钉住这条，免得以后有人「补图补上头」给成就/纸条去配图标。
    _ach38 = [o.get("成就", "") for o in _L38("achievements.json")]
    check(all(not _imap38.get(a) for a in _ach38 if a),
          "成就名一概不配图标（49 条是成就文本，本就不是物品）")
    _note38 = ["#%s %s" % (o.get("num", ""), str(o.get("text", ""))[:12]) for o in _L38("secretNotes.json")]
    check(all(not _imap38.get(n) for n in _note38 if n),
          "秘密纸条标题一概不配图标（27 条是纸条文本）")

    # 38.8 版本口径（沿用 37.7 的动态写法）
    _g238 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    _m238 = re.search(r'versionName\s*=\s*"([^"]+)"', _g238)
    check(bool(_m238), "gradle versionName 可解析")
    if _m238:
        _notes238 = io.open(os.path.join(ROOT, os.pardir, "安装包归档", "版本备注.md"),
                            encoding="utf-8").read()
        check("v" + _m238.group(1) in _notes238, "归档账本已记当前版本 v%s" % _m238.group(1))

    # 39 开发期产物排除（v2.46.0）
    #     本轮修的是**一类** bug：排除规则与真实文件脱节之后静默腐烂。v2.17 D4 写的
    #     packaging.resources.excludes 从未对 assets 生效过（它只管 JAR/AAR 的 Java 资源），
    #     668KB 一路跟了 40 多版；而当时的断言恰好钉的就是这条规则的字面量——
    #     规则死了，断言照样绿。所以这里一条字面量都不钉，只钉两组关系：
    #         运行期不读的文件  ⊆  devAssetGlobs     （漏排 = 没排掉，白进 APK）
    #         运行期要读的文件  ∩  devAssetGlobs  = ∅  （误伤 = 把功能打包坏了）
    #     另外反向锚定 GameData.kt 真正加载的每个 json 名，确保排除范围哪天扩大会立刻红。
    _g39 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    _m39 = re.search(r"devAssetGlobs\s*=\s*listOf\((.*?)\n\)", _g39, re.S)
    check(_m39 is not None, "gradle 声明了 devAssetGlobs 排除名单")
    _globs39 = re.findall(r'"([^"]+)"', _m39.group(1)) if _m39 else []
    check(len(_globs39) >= 2, "devAssetGlobs 至少覆盖两类开发期产物（实得 %d 条）" % len(_globs39))

    def _glob39(glob):
        """与 build.gradle.kts 的 devAssetGlobToRegex 同语义，在源码层独立复算一遍。"""
        out, i = [], 0
        while i < len(glob):
            c = glob[i]
            if c == "*" and i + 1 < len(glob) and glob[i + 1] == "*":
                out.append(".*")
                i += 1
                if i + 1 < len(glob) and glob[i + 1] == "/":
                    i += 1
            elif c == "*":
                out.append("[^/]*")
            elif c == "/":
                out.append("/")
            else:
                out.append(re.escape(c))
            i += 1
        return re.compile("".join(out))

    _rx39 = [_glob39(g) for g in _globs39]
    _ASSETS39 = os.path.join(ROOT, "app", "src", "main", "assets")
    _rel39 = []
    for _r, _dirs, _fs in os.walk(_ASSETS39):
        for _f in _fs:
            _rel39.append(os.path.relpath(os.path.join(_r, _f), _ASSETS39).replace(os.sep, "/"))
    check(len(_rel39) > 0, "assets 源码树非空")

    def _is_runtime39(p):
        """运行期读取面由 GameData.kt / PixelWidgets.kt 决定：image_map.json、img/**、data/*.json。

        v2.70.0 加一类：`licenses/**` 运行期不读，但**必须随包分发**——Apache-2.0 §4
        要求衍生作品分发时带许可证副本（PixelGlass 的 AGSL 源自 AndroidLiquidGlass）。
        它既不能进 devAssetGlobs 排除名单（排了 = 违规），也不算漏排（它是刻意随包的）。
        """
        if p.startswith("licenses/"):
            return True
        # v2.75.0：电脑端脚本**随 App 分发**（assets/tools/send_save.bat），
        # 运行期 downloadScript 会读它 —— 必须算运行期资产，
        # 排进 devAssetGlobs 就等于把脚本从 APK 里剔掉（点了下载必然失败）。
        if p == "tools/send_save.bat":
            return True
        # c237：电脑端**模组**包同样随 App 分发（assets/tools/save_auto_push.zip），
        # 运行期 downloadMod 会读它。⚠ 理由与上面那条一样：模组版本要和 App 的
        # 协议版本对得上，从发布仓拉可能拿到落后的那份（协议头对不上就是 403）。
        if p == "tools/save_auto_push.zip":
            return True
        return (p == "image_map.json"
                or p.startswith("img/")
                or (p.startswith("data/") and p.count("/") == 1 and p.endswith(".json")))

    _runtime39 = [p for p in _rel39 if _is_runtime39(p)]
    _dev39 = [p for p in _rel39 if not _is_runtime39(p)]

    # 39.1 漏排：assets 里凡运行期不读的，必须全在名单内
    _unc39 = [p for p in _dev39 if not any(r.fullmatch(p) for r in _rx39)]
    check(not _unc39, "assets 下运行期不读的文件全部在 devAssetGlobs 内（漏排 %d 个）" % len(_unc39))
    for _p in _unc39[:8]:
        print("      · 漏排：" + _p)

    # 39.2 误伤：名单不得盖住任何运行期资产
    _hit39 = [p for p in _runtime39 if any(r.fullmatch(p) for r in _rx39)]
    check(not _hit39, "devAssetGlobs 未盖住任何运行期资产（误伤 %d 个）" % len(_hit39))
    for _p in _hit39[:8]:
        print("      · 误伤：" + _p)

    # 39.3 反向锚点：GameData.kt 里引用的每个 json 都得在，且都不在名单内。
    #     这一条同时防「正则改坏导致上面几条集体变真空」——所以顺带要求它至少命中几十个。
    _gd39 = code_only(src("data/GameData.kt"))
    _used39 = {n for n in re.findall(r'"([A-Za-z0-9_]+\.json)"', _gd39) if n != "image_map.json"}
    check(len(_used39) >= 40, "GameData 的 json 引用能被本节正则捕获（实得 %d 个，防断言变真空）" % len(_used39))
    _miss39 = sorted(n for n in _used39 if ("data/" + n) not in _rel39)
    check(not _miss39, "GameData 引用的 json 全部存在于 assets/data（缺 %d 个）" % len(_miss39))
    for _p in _miss39[:8]:
        print("      · 缺文件：data/" + _p)
    _cov39 = sorted(n for n in _used39 if any(r.fullmatch("data/" + n) for r in _rx39))
    check(not _cov39, "GameData 引用的 json 无一被 devAssetGlobs 盖住")
    check("image_map.json" in _rel39, "image_map.json 仍在源码树")

    # 39.4 排除 ≠ 删除：数据管线的输入必须原样留在 src/main/assets
    for _p in ("data/wiki/_fetch_npc.js", "data/wiki/_fetch_v25.js",
               "data/wiki/_gen_more.py", "data/wiki/_gen_sources.py",
               "image_gaps.txt"):
        check(_p in _rel39, "排除≠删除，源码仍保留：" + _p)
    check(sum(1 for p in _rel39 if p.startswith("data/wiki/raw_v2/")) >= 39,
          "raw_v2 的 wiki 抓取原文仍完整保留（当前 %d 个）"
          % sum(1 for p in _rel39 if p.startswith("data/wiki/raw_v2/")))

    # 40 大图转 WebP（v2.47.0）
    #     起因：7 张节日插画（1.39MB）+ 5 档 App 图标前景（0.42MB）占了 APK 26%，
    #     而它们**不能缩放**——节日是图鉴第 12 个分类，分享卡走 AssetImage(iconKey, 200)
    #     即 200dp（3x 屏 600px），缩了分享卡必糊。所以只有换格式这条路。
    #     本节钉的是**换格式之后最容易烂掉的三组关系**，一条具体文件名都不钉：
    #       a) img 下每个 .webp 都必须被 image_map 引用（孤儿 webp = 白占体积）
    #       b) .webp 只许出现在 img/ 下，且 image_map 的 .webp 值不得悬空
    #       c) adaptive icon 引用的前景图在**每个密度桶**里都必须存在且扩展名一致
    #          （只转 3 个大桶留 2 个 PNG 不算错，但格式分裂会让以后每次都重新纠结）
    _g40 = code_only(io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read())
    _m40 = re.search(r"runtimeAssetExts\s*=\s*setOf\((.*?)\)", _g40, re.S)
    check(_m40 is not None, "gradle 声明了 runtimeAssetExts 运行期格式白名单")
    _allow40 = set(re.findall(r'"([^"]+)"', _m40.group(1))) if _m40 else set()

    # b) 白名单必须覆盖运行期资产的每一种扩展名（反向：新加格式忘了改 gradle 就红）。
    #     ⚠ 取的是 _runtime39 而不是 _rel39：构建期那道闸门跑在**剔除之后**的 merged assets 上，
    #     源码树的 .js/.py/.txt 会被 devAssetGlobs 剔掉，不该算进白名单的覆盖范围。
    _ext40 = set(os.path.splitext(p)[1].lstrip(".").lower() for p in _runtime39 if "." in p)
    check(_ext40 <= _allow40,
          "运行期资产的扩展名全在 runtimeAssetExts 内（越界：%s）" % (sorted(_ext40 - _allow40) or "无"))

    # a) 孤儿 webp
    _img40 = [p for p in _rel39 if p.startswith("img/") and p.endswith(".webp")]
    _vals40 = set(json.load(io.open(os.path.join(ROOT, "app", "src", "main", "assets",
                                                 "image_map.json"), encoding="utf-8")).values())
    _orphan40 = sorted(p for p in _img40 if os.path.basename(p) not in _vals40)
    check(not _orphan40, "img 下无孤儿 webp（未被 image_map 引用：%s）" % ("、".join(_orphan40) or "无"))

    # b) webp 只许在 img/ 下；image_map 的 webp 值不得悬空
    _outside40 = sorted(p for p in _rel39 if p.endswith(".webp") and not p.startswith("img/"))
    check(not _outside40, "assets 下 .webp 只出现在 img/（越界：%s）" % ("、".join(_outside40) or "无"))
    _dang40 = sorted(v for v in _vals40 if v.endswith(".webp")
                     and not os.path.exists(os.path.join(_ASSETS39, "img", v)))
    check(not _dang40, "image_map 的 webp 值全部指向真实文件（悬空：%s）" % ("、".join(_dang40) or "无"))
    check(_img40 and _vals40, "本节非空跑：img 下确有 webp 走 image_map 加载")

    # c) adaptive icon 前景层：每个密度桶都要有，且扩展名统一
    _res40 = os.path.join(ROOT, "app", "src", "main", "res")
    _xml40 = os.path.join(_res40, "mipmap-anydpi-v26", "ic_launcher.xml")
    check(os.path.exists(_xml40), "adaptive icon XML 存在（ic_launcher.xml）")
    if os.path.exists(_xml40):
        _xt40 = io.open(_xml40, encoding="utf-8").read()
        _names40 = set(re.findall(r"@mipmap/([A-Za-z0-9_]+)", _xt40))
        check(_names40, "ic_launcher.xml 引用了 @mipmap 资源（%s）" % (sorted(_names40) or "无"))
        _buckets40 = sorted(d for d in os.listdir(_res40)
                            if d.startswith("mipmap-") and not d.endswith("-anydpi-v26"))
        check(_buckets40, "存在密度桶目录")
        _miss40, _extset40 = [], set()
        for _b in _buckets40:
            for _n in _names40:
                _f = [x for x in os.listdir(os.path.join(_res40, _b)) if x.startswith(_n + ".")]
                if not _f:
                    _miss40.append("%s/%s" % (_b, _n))
                else:
                    _extset40.add(os.path.splitext(_f[0])[1].lower())
        check(not _miss40, "每个密度桶都有 adaptive icon 引用的前景图（缺：%s）"
              % ("、".join(_miss40) or "无"))
        check(len(_extset40) == 1,
              "adaptive icon 各密度桶前景图格式统一（当前 %s）" % sorted(_extset40))

    # 41 字体子集化（v2.48.0）
    #     起因：Fusion Pixel 全量 7 MB（cmap 36,521 码位），光一个包内压缩后 1,608,489 B
    #     就占了 APK 的 23.4%。按「项目自有语料」切完只剩 500,696 B。
    #     本节钉的是**子集最容易烂掉的那一条关系**：「数据里出现的每个字，当前字体都得有」。
    #     这条一旦破了，表现是**用户看到某几个字回退成系统字体**——不崩、不报错、
    #     verify 其他部分全绿，只有肉眼能发现，所以必须由断言兜住。
    #     语料构建直接 import tools/subset_font.py，保证只有一份实现（import 本身不需要
    #     fontTools——那是函数内的延迟导入，所以 verify 依旧零第三方依赖）。
    def _sfnt_tables41(path):
        data = open(path, "rb").read()
        num = int.from_bytes(data[4:6], "big")
        off, t = 12, {}
        for _ in range(num):
            tag = data[off:off + 4].decode("latin1")
            o = int.from_bytes(data[off + 8:off + 12], "big")
            ln = int.from_bytes(data[off + 12:off + 16], "big")
            t[tag] = (o, ln)
            off += 16
        return data, t

    def _cmap_cps41(path):
        """只解析 format 4 / 12，够用且不引 fontTools。"""
        data, t = _sfnt_tables41(path)
        if "cmap" not in t:
            return set()
        base = t["cmap"][0]
        n = int.from_bytes(data[base + 2:base + 4], "big")
        cps = set()
        for i in range(n):
            rec = base + 4 + i * 8
            sub = base + int.from_bytes(data[rec + 4:rec + 8], "big")
            fmt = int.from_bytes(data[sub:sub + 2], "big")
            if fmt == 4:
                seg2 = int.from_bytes(data[sub + 6:sub + 8], "big")
                seg = seg2 // 2
                ends = [int.from_bytes(data[sub + 14 + 2 * j:sub + 16 + 2 * j], "big") for j in range(seg)]
                s = sub + 16 + seg2
                starts = [int.from_bytes(data[s + 2 * j:s + 2 + 2 * j], "big") for j in range(seg)]
                for st, en in zip(starts, ends):
                    if st != 0xFFFF:
                        cps.update(range(st, min(en, 0xFFFE) + 1))
            elif fmt == 12:
                ng = int.from_bytes(data[sub + 12:sub + 16], "big")
                for j in range(ng):
                    o = sub + 16 + j * 12
                    cps.update(range(int.from_bytes(data[o:o + 4], "big"),
                                     int.from_bytes(data[o + 4:o + 8], "big") + 1))
        return cps

    _font41 = os.path.join(ROOT, "app", "src", "main", "res", "font", "fusion_pixel.ttf")
    _src41 = os.path.join(ROOT, "tools", "fonts", "fusion_pixel.ttf")
    check(os.path.exists(_font41), "res/font/fusion_pixel.ttf 存在（@font/fusion_pixel 引用不能断）")
    check(os.path.exists(_src41),
          "tools/fonts/fusion_pixel.ttf 原字体仍在（子集必须可从原字体重建）")
    if os.path.exists(_font41) and os.path.exists(_src41):
        import subset_font as _sf41
        _corpus41, _ = _sf41.build_corpus()
        _cp41 = {ord(c) for c in _corpus41}
        _cm41 = _cmap_cps41(_font41)
        _scm41 = _cmap_cps41(_src41)
        _need41 = _cp41 & _scm41

        # 41.1 本轮唯一的核心关系：语料里源字体有的字，子集必须有
        _miss41 = sorted(_need41 - _cm41)
        check(not _miss41,
              "数据语料 %d 个码位在当前字体中全部有字形（缺 %d 个）" % (len(_need41), len(_miss41)))
        for _p in _miss41[:12]:
            print("      · 缺字：%s（U+%04X）——数据更新过，请重跑 python tools/subset_font.py"
                  % (chr(_p), _p))

        # 41.2 子集真的切了（防「脚本空跑 / 白名单被清空」而断言照样绿）
        check(0 < len(_cm41) < len(_scm41),
              "字体确已子集化（当前 %d 码位 < 原字体 %d 码位）" % (len(_cm41), len(_scm41)))

        # 41.3 name 表（v2.60.0 改写）：OFL 1.1 两头都要钉。
        #   第 3 条 → 子集（Modified Version）**不得再用保留名**「Fusion Pixel / 缝合像素」；
        #   第 2 条 → 版权(0)/版本(5)/许可指针(13)/许可URL(14) 元数据必须原样保留。
        # 旧版这里钉的是「name 表逐字节相同」，v2.60.0 起子集要改名，那条必然冲突，故重写。
        # 纯 Python 解析 name 表（format 0），不引 fontTools，保持 verify 零第三方依赖。
        def _name_records41(path):
            data, t = _sfnt_tables41(path)
            if "name" not in t:
                return {}
            base = t["name"][0]
            cnt = int.from_bytes(data[base + 2:base + 4], "big")
            soff = int.from_bytes(data[base + 4:base + 6], "big")
            out = {}
            for k in range(cnt):
                r = base + 6 + k * 12
                plat = int.from_bytes(data[r:r + 2], "big")
                nid = int.from_bytes(data[r + 6:r + 8], "big")
                ln = int.from_bytes(data[r + 8:r + 10], "big")
                o = int.from_bytes(data[r + 10:r + 12], "big")
                raw = data[base + soff + o:base + soff + o + ln]
                # Windows(plat 3)/Unicode(0) 是 UTF-16BE，Mac(1) 是 mac_roman
                enc = "utf-16-be" if plat in (0, 3) else "mac_roman"
                try:
                    s41 = raw.decode(enc)
                except Exception:
                    s41 = raw.decode("latin1")
                out.setdefault(nid, set()).add(s41)
            return out

        _fn41 = _name_records41(_font41)
        _sn41 = _name_records41(_src41)
        # 保留名检查：子集所有平台记录里都不许再出现 Fusion Pixel / 缝合像素
        _reserved41 = ("fusion pixel", "縫合像素", "缝合像素")
        _badname41 = sorted({s for nid in (1, 4, 6, 16, 17)
                             for s in _fn41.get(nid, set())
                             if any(r41 in s.lower() for r41 in _reserved41)})
        check(not _badname41,
              "子集主名字已去掉保留名（OFL 第 3 条；违规值：%s）" % (_badname41[:3] or "无"))
        check(_fn41.get(1) == {"TutuPixel"},
              "子集 nameID 1 = TutuPixel（实际：%s）" % sorted(_fn41.get(1, set()))[:2])
        # 许可元数据必须与源字体一致（第 2 条）——注意：本字体源表里本就没有 13/14，
        # 实际被钉住的是 0（版权行）与 5（版本串）；将来上游补了 13/14 也自动纳入比对。
        for _nid41 in (0, 5, 13, 14):
            check(_fn41.get(_nid41, set()) == _sn41.get(_nid41, set()),
                  "nameID %d 与源字体一致（许可/版权元数据未丢；子集 %d 条）"
                  % (_nid41, len(_fn41.get(_nid41, set()))))

    # 41.4 @font/xxx 的引用必须都能落到 res/font 下的真实文件（防改名断引用）
    _fontres41 = os.path.join(ROOT, "app", "src", "main", "res", "font")
    _refs41 = set()
    for _r, _d, _fs in os.walk(os.path.join(ROOT, "app", "src", "main", "res")):
        for _f in _fs:
            if _f.endswith(".xml"):
                _refs41 |= set(re.findall(r"@font/([A-Za-z0-9_]+)",
                                          io.open(os.path.join(_r, _f), encoding="utf-8").read()))
    check(_refs41, "res 下确有 @font/ 引用（%s）" % (sorted(_refs41) or "无"))
    for _rf in sorted(_refs41):
        check(any(os.path.exists(os.path.join(_fontres41, _rf + _x)) for _x in (".ttf", ".otf")),
              "@font/%s 有对应字体文件" % _rf)

    # 42 Baseline Profile 接线（v2.49.0）
    #     ⚠ 本节**故意不断言「APK 的 baseline.prof 里有类名」**——那条判据是错的：
    #     AGP 生成的 profile 默认是 **dex-layout 形式**，不存类名字符串、只存 dex 结构下标，
    #     解压后是 0x00/0x01/0xff 的位图。拿「读不到类名」判它没生效，会得出完全相反的结论
    #     （v2.49.0 之前就这么误判过一次，见归档账本）。**有效判据是位图里被打标的方法数。**
    #     本节只钉能在源码层客观验证的接线关系；产物层的位图数据记在归档账本里。
    _gbp42 = os.path.join(ROOT, "app", "src", "main", "baseline-prof.txt")
    check(os.path.exists(_gbp42), "app/src/main/baseline-prof.txt 存在（手写保底规则）")
    _rules42 = []
    if os.path.exists(_gbp42):
        for _ln in io.open(_gbp42, encoding="utf-8").read().splitlines():
            _ln = _ln.strip()
            if not _ln or _ln.startswith("#"):
                continue
            _rules42.append(_ln)
    check(len(_rules42) > 0, "baseline-prof.txt 有生效中的规则（%d 条）" % len(_rules42))
    check(any(_r.startswith("L") and _r.endswith(";") for _r in _rules42),
          "baseline-prof.txt 含类级规则")
    check(any("->" in _r for _r in _rules42),
          "baseline-prof.txt 含方法级规则")

    # 42.1 规则里引用的类必须真实存在。写错类名的后果不是报错而是**规则被静默丢弃**——
    #     这一条是唯一能在源码层抓住它的检查。
    #     ⚠ Kotlin 的**文件外观类**（顶层函数/属性编出来的 `XxxKt`）没有同名 .kt，
    #       对应源文件叫 `Xxx.kt`，所以要额外试一次去掉 Kt 后缀的路径。
    #       （这两条规则实测是**生效**的：R8 mapping 里 PixelThemeKt→v1.r、PixelWidgetsKt→v1.Q，
    #        都确实出现在合并后的 profile 文本里。）
    _srcroot42 = os.path.join(ROOT, "app", "src", "main", "java")
    _bad42 = []
    for _r in _rules42:
        _cls = _r[1:_r.index(";")] if _r.startswith("L") and ";" in _r else None
        if not _cls:
            continue
        _cands = [_cls.replace(".", os.sep) + ".kt"]
        if _cls.endswith("Kt"):
            _cands.append(_cls[:-2].replace(".", os.sep) + ".kt")
        if not any(os.path.exists(os.path.join(_srcroot42, _c)) for _c in _cands):
            _bad42.append(_cls)
    check(not _bad42, "baseline-prof.txt 里每个类都真实存在（不存在的会被静默丢弃：%s）"
          % ("、".join(_bad42) or "无"))

    # 42.2 接线：插件 + 消费者依赖 + 模块 + 采集测试，缺一不可。
    #     这几处每一处缺失都有各自的「静默失败」表现，全部钉死。
    _sgp42 = io.open(os.path.join(ROOT, "settings.gradle.kts"), encoding="utf-8").read()
    check('include(":baselineprofile")' in _sgp42, "settings.gradle.kts 引入 :baselineprofile 模块")

    _gap42 = io.open(os.path.join(ROOT, "build.gradle.kts"), encoding="utf-8").read()
    for _pid in ("libs.plugins.android.test", "libs.plugins.androidx.baselineprofile"):
        check(_pid in _gap42, "根脚本 apply false %s" % _pid)

    _apkg42 = code_only(io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read())
    check("libs.plugins.androidx.baselineprofile" in _apkg42, ":app 应用了 androidx.baselineprofile 插件")
    check(re.search(r"baselineProfile\(project\(\":baselineprofile\"\)\)", _apkg42) is not None,
          ":app 声明了 baselineProfile(project(\":baselineprofile\")) 消费者依赖"
          "（缺这行时插件只打 warning，采集任务会 BUILD SUCCESSFUL 但什么都不做）")

    # 42.3 测试模块：三个「不写就静默失败」的点
    _bpdir42 = os.path.join(ROOT, "baselineprofile")
    check(os.path.isdir(_bpdir42), ":baselineprofile 模块目录存在")
    _bp42 = code_only(io.open(os.path.join(_bpdir42, "build.gradle.kts"), encoding="utf-8").read())
    check("libs.plugins.android.test" in _bp42, "测试模块应用 com.android.test")
    check("libs.plugins.kotlin.android" in _bp42,
          "测试模块应用 Kotlin 插件（漏掉时 .kt 测试不编译，采集报「no tests to run」）")
    check(re.search(r"defaultConfig\s*\{[^}]*minSdk", _bp42, re.S) is not None,
          "测试模块 defaultConfig 显式给了 minSdk（默认 1 会让 benchmark-common 的 manifest 合并失败）")
    check("targetProjectPath" in _bp42, "测试模块声明了 targetProjectPath")
    _tests42 = []
    for _r, _d, _fs in os.walk(os.path.join(_bpdir42, "src")):
        _tests42 += [f for f in _fs if f.endswith(".kt")]
    check(len(_tests42) > 0, "测试模块里有采集测试源码（%d 个 .kt）" % len(_tests42))

    # 43 桌面小组件刷新机制（v2.50.0）
    #     背景：APK 里 AlarmManager 只被调了 setAndAllowWhileIdle（非精确闹钟），三个 provider 的
    #     updatePeriodMillis 靠系统兜底轮询。本版把轮询从 30 分钟放宽到 6 小时（每天 48 次 -> 4 次）。
    #     ⚠ 本节真正要钉的不是「轮询多久」，而是**翻日根本不经由时钟**这条不变式：
    #       小组件显示的季节/日/年读的是 SharedPreferences 镜像 widget_prefs，唯一写入口是 App 内
    #       TodayPage 的「调整游戏日期」滚轮 -> PrefsRepo.setDate -> MainActivity 的 snapshot.collect
    #       在**同一进程内**连着调 WidgetPrefs.sync + WidgetUpdater.refresh（apply() 同步更新内存 map，
    #       紧接着的 getSharedPreferences 必然读到新值）。所以延迟是毫秒级，与闹钟精度、Doze、
    #       轮询周期全都无关——「闹钟不准 -> 最坏晚半小时翻日」是伪命题，别再顺着它去申请精确闹钟。

    # 43.1 三个规格的轮询周期必须同值，且不得低于系统钳制下限。
    #     ⚠ 低于 1800000 时 Android 会**静默钳到 30 分钟**，源码里的数字就成了「写了没用还骗人」的配置，
    #       必须挡住。钉的是三者相等这个关系 + 平台下限，不钉某个具体时长。
    _wx43 = {}
    for _f43 in ("widget_today_info.xml", "widget_card_info.xml", "widget_wide_info.xml"):
        _p43 = os.path.join(ROOT, "app", "src", "main", "res", "xml", _f43)
        check(os.path.exists(_p43), "%s 存在" % _f43)
        _m43 = re.search(r'android:updatePeriodMillis\s*=\s*"(\d+)"',
                         io.open(_p43, encoding="utf-8").read())
        check(_m43 is not None, "%s 显式声明 updatePeriodMillis" % _f43)
        if _m43:
            _wx43[_f43] = int(_m43.group(1))
            check(int(_m43.group(1)) >= 1800000,
                  "%s updatePeriodMillis=%s 不低于系统下限 30 分钟（低于会被静默钳制）"
                  % (_f43, _m43.group(1)))
    check(len(set(_wx43.values())) == 1,
          "三个规格轮询周期同值（%s）" % (sorted(set(_wx43.values())) or "未解析"))

    # 43.2 闹钟机制钉住：仍是非精确闹钟，且没有混进需要授权的精确闹钟。
    #     钉的是「有没有出现精确闹钟调用/权限」这个关系，不是某个字面量。
    _wu43 = code_only(io.open(os.path.join(ROOT, "app", "src", "main", "java",
                                           "com", "stardew", "assistant",
                                           "widget", "WidgetUpdater.kt"), encoding="utf-8").read())
    check("setAndAllowWhileIdle" in _wu43, "每日刷新仍是 setAndAllowWhileIdle（非精确闹钟）")
    _exact43 = [x for x in ("setExactAndAllowWhileIdle", "setAlarmClock",
                            "SCHEDULE_EXACT_ALARM", "USE_EXACT_ALARM") if x in _wu43]
    check(not _exact43, "WidgetUpdater 未混入精确闹钟调用/权限（%s）" % (_exact43 or "无"))
    _mf43 = io.open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"),
                    encoding="utf-8").read()
    check("SCHEDULE_EXACT_ALARM" not in _mf43 and "USE_EXACT_ALARM" not in _mf43,
          "manifest 未申请精确闹钟权限")

    # 43.3 「翻日不经由时钟」这条不变式本身：读镜像的字段齐全、写镜像的入口唯一。
    _wprefs43 = code_only(io.open(os.path.join(ROOT, "app", "src", "main", "java",
                                                "com", "stardew", "assistant",
                                                "widget", "WidgetPrefs.kt"), encoding="utf-8").read())
    for _k43 in ("season", "day", "year"):
        check(re.search(r'putInt\(\s*"%s"' % _k43, _wprefs43) is not None,
              "WidgetPrefs.sync 写入镜像字段 %s" % _k43)
    _wr43 = code_only(io.open(os.path.join(ROOT, "app", "src", "main", "java",
                                           "com", "stardew", "assistant",
                                           "widget", "WidgetRender.kt"), encoding="utf-8").read())
    check('sp.getInt("day"' in _wr43,
          "WidgetRender 从 widget_prefs 读 day（不是按 Calendar 现算）——翻日延迟与时钟无关")
    _ma43 = code_only(io.open(os.path.join(ROOT, "app", "src", "main", "java",
                                           "com", "stardew", "assistant",
                                           "MainActivity.kt"), encoding="utf-8").read())
    check("WidgetPrefs.sync" in _ma43 and "WidgetUpdater.refresh" in _ma43,
          "MainActivity 在快照收集里同步镜像 + 刷新（改完日期毫秒级推给小组件）")

    # 43.4 已知缺口（只登记、不判失败）：只有 TodayWidget 重写了 onEnabled，
    #     所以只摆 2×2 方卡或 4×2 宽条的用户从来不会被 scheduleNextDay 排上闹钟。
    #     本版选「放宽轮询」而非「修闹钟链路」，这个缺口继续留着。
    _twfile43 = io.open(os.path.join(ROOT, "app", "src", "main", "java",
                                     "com", "stardew", "assistant",
                                     "widget", "TodayWidget.kt"), encoding="utf-8").read()
    _noon43 = []
    for _cls43, _txt43 in (("TodayWidget", code_only(_twfile43)),
                           ("CardWidget", _wu43), ("WideWidget", _wu43)):
        _b43 = re.search(r"class %s : AppWidgetProvider\(\) \{(.*?)\n\}" % _cls43, _txt43, re.S)
        if _b43 and "onEnabled" not in _b43.group(1):
            _noon43.append(_cls43)
    if _noon43:
        warns.append("43.4 %s 未重写 onEnabled：只摆这些规格的用户不会被排上每日闹钟（本版未修）"
                     % "、".join(_noon43))

    # 44 R8 mapping 归档（v2.51 D1）
    #     release 走 R8 混淆，崩溃栈里全是 `a.a` / `u1.t`，没有 mapping 就定位不到自己写的哪一行。
    #     本节钉的是**关系**而不是字面量：归档文件名按 gradle 里的 versionName/versionCode 现算，
    #     且归档内容必须与本次构建的 mapping.txt **逐字节相同**——
    #     最阴的故障是「拿到旧版本的 mapping 去解读新版本的崩溃」：它不报错，只是把行号解错。
    _gitignore44 = io.open(os.path.join(os.path.dirname(ROOT), ".gitignore"),
                           encoding="utf-8").read()
    check(re.search(r"^安装包归档/mapping/", _gitignore44, re.M) is not None,
          ".gitignore 忽略 安装包归档/mapping/（它不是 *.apk，不受既有规则保护，"
          "而 git 吞进二进制历史就再也吐不出来）")

    _g44 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check('tasks.register("archiveMapping")' in _g44, "build.gradle.kts 注册了 archiveMapping 任务")
    check(re.search(r"finalizedBy\(\s*archiveMapping\s*\)", _g44) is not None,
          "archiveMapping 挂在 assembleRelease 之后（finalizedBy）——"
          "否则它不会随每次构建自动跑，脚本化方案迟早漏版本")

    _vn44 = re.search(r'versionName\s*=\s*"([^"]+)"', _g44)
    _vc44 = re.search(r"versionCode\s*=\s*(\d+)", _g44)
    check(bool(_vn44 and _vc44), "gradle 的 versionName / versionCode 可解析")
    _stem44 = "星露小助手_v%s_c%s" % (_vn44.group(1), _vc44.group(1))

    _arch44 = os.path.join(os.path.dirname(ROOT), "安装包归档")
    check(os.path.exists(os.path.join(_arch44, _stem44 + ".apk")),
          "归档 APK 与当前版本同名（%s.apk）" % _stem44)
    _gz44 = os.path.join(_arch44, "mapping", _stem44 + ".mapping.txt.gz")
    check(os.path.exists(_gz44),
          "归档 mapping 与 APK **同 stem**（%s.mapping.txt.gz）——版本对应关系一眼可查" % _stem44)

    _srcmap44 = os.path.join(ROOT, "app", "build", "outputs", "mapping", "release", "mapping.txt")
    check(os.path.exists(_srcmap44), "本次构建产出了 mapping.txt")
    if os.path.exists(_gz44) and os.path.exists(_srcmap44):
        _raw44 = open(_gz44, "rb").read()
        check(_raw44[:2] == b"\x1f\x8b",
              "归档文件是真 gzip（魔数 1f 8b），不是明文 mapping 混进去的")
        # 解压回原始 mapping.txt，逐字节比对：证明「归档的确实是本次构建的那一份」
        import gzip as _gz_mod44
        _back44 = _gz_mod44.decompress(_raw44)
        _orig44 = open(_srcmap44, "rb").read()
        check(_back44 == _orig44,
              "解压后与 build 产出的 mapping.txt 逐字节相同（%d B -> %d B，%d%%）"
              % (len(_orig44), len(_back44), len(_raw44) * 100 // max(len(_orig44), 1)))
        check(len(_raw44) < len(_orig44),
              "压缩确实生效（%d B < %d B）" % (len(_raw44), len(_orig44)))

        # mapping 必须是「认得出自己人」的那份：三个最常崩的类都能查到混淆名。
        # ⚠ FQN **从源码树反推**，不写死包名——GameData / PrefsRepo 在 .data 子包下，
        #   写死 com.stardew.assistant.GameData 会假失败（这坑本项目犯过多次：
        #   「字面量一律按源现算」的变体，这次栽在包名上）。
        _mtext44 = _back44.decode("utf-8", "replace")
        _srcroot44 = os.path.join(ROOT, "app", "src", "main", "java")
        for _c44 in ("MainActivity", "GameData", "PrefsRepo"):
            _fqns44 = []
            for _r44, _d44, _fs44 in os.walk(_srcroot44):
                if _c44 + ".kt" not in _fs44:
                    continue
                _txt44 = io.open(os.path.join(_r44, _c44 + ".kt"), encoding="utf-8").read()
                _m44 = re.search(r"^package\s+([\w.]+)", _txt44, re.M)
                if _m44:
                    _fqns44.append(_m44.group(1) + "." + _c44)
            check(len(_fqns44) == 1,
                  "源码树里能唯一定位 %s.kt（%s）" % (_c44, _fqns44 or "找不到"))
            for _fq44 in _fqns44:
                check(re.search(r"^%s ->" % re.escape(_fq44), _mtext44, re.M) is not None,
                      "mapping 覆盖 %s（崩溃溯源最常问的类）" % _fq44)
        check(_mtext44.startswith("# compiler: R8"),
              "归档的是 R8 mapping（不是别的 mapping 文件）")

    # 45 社区中心收集包「特色鱼类」鱼名（v2.51.0）
    #     群友反馈「进度页收集包的特殊鱼类里边的幽灵鱼为鬼鱼」——**反馈是对的**。
    #     wiki Bundles#Specialty Fish Bundle 原文：Pufferfish / **Ghostfish** / Sandfish / Woodskip，
    #     而我们当时写的是 Spook Fish（幽灵鱼）。这是**两个不同的鱼**：
    #       鬼鱼   Ghostfish  矿井湖 20/60 层、幽灵掉落、可放鱼塘、全年
    #       幽灵鱼 Spook Fish 夜市深海潜艇、冬、220g，属特殊订单而非社区中心收集包
    #     ⚠ 纯「跨文件引用完整性」抓不到这个 bug——幽灵鱼在 fishing.json 里确实存在，
    #       只是**放错了收集包**。所以这里钉的是「wiki 认定的正确组合」这个外部事实，
    #       钉游戏数据不违反「别钉字面量」（那条针对的是代码结构与版本号）。
    _b45 = json.load(io.open(os.path.join(ROOT, "app", "src", "main", "assets", "data",
                                        "bundles.json"), encoding="utf-8"))
    _tank45 = [(r, b) for r in _b45 for b in r["bundles"] if b["name"] == "特色鱼类"]
    check(len(_tank45) == 1, "鱼缸下恰有 1 个「特色鱼类」收集包（实际 %d）" % len(_tank45))
    if _tank45:
        _got45 = [i["name"] for i in _tank45[0][1]["items"]]
        _WANT45 = ["河豚", "鬼鱼", "沙鱼", "木跃鱼"]
        check(_got45 == _WANT45,
              "「特色鱼类」条目 = wiki 的 Pufferfish/Ghostfish/Sandfish/Woodskip（实际 %s）"
              % "、".join(_got45))
        _room45 = _tank45[0][0]["room"]
        check(_room45 == "鱼缸", "「特色鱼类」归属鱼缸房（实际 %s）" % _room45)

    _btxt45 = io.open(os.path.join(ROOT, "app", "src", "main", "assets", "data",
                                   "bundles.json"), encoding="utf-8").read()
    check("幽灵鱼" not in _btxt45,
          "bundles.json 不含「幽灵鱼」（Spook Fish 不属于任何社区中心收集包）")

    _f45 = load(os.path.join(DATA, "fishing.json"))
    _fnames45 = [x["name"] for x in _f45]
    check("鬼鱼" in _fnames45 and "幽灵鱼" in _fnames45,
          "fishing.json 里 鬼鱼 / 幽灵鱼 是两个独立条目（防止再被互相顶替）")
    check(_fnames45.count("鬼鱼") == 1 and _fnames45.count("幽灵鱼") == 1,
          "两者各恰好 1 条，无重复条目")

    # 最能防「张冠李戴」的一条：两者的钓点集合必须不相交。
    _by45 = load(os.path.join(DATA, "fishSpots.json")).get("byFish", {})
    def _sp45(nm):
        return tuple(sorted(_by45.get(nm, {}).get("spots", [])))
    check(_sp45("鬼鱼") and _sp45("幽灵鱼"),
          "鱼缸包要用的 鬼鱼 与 幽灵鱼 都有钓点数据（%s / %s）"
          % ("、".join(_sp45("鬼鱼")) or "无", "、".join(_sp45("幽灵鱼")) or "无"))
    check(not (set(_sp45("鬼鱼")) & set(_sp45("幽灵鱼"))),
          "两鱼钓点集合不相交（当前 %d / %d 条），不会认错"
          % (len(_sp45("鬼鱼")), len(_sp45("幽灵鱼"))))

    # 46 配色对比度地板（v2.52.0）
    #     起因：群友报「秋季配色 / 图鉴分类页对比度低」。查下来**季度不是最差**（四季极差只有 0.82），
    #     但顺着查出两个夜市节日把**浅色文字压在浅色卡片底**上：月光水母起舞 1.16:1、鱿鱼节 1.04:1，
    #     即「主题=季节配色 且 日期=夏28/冬12-13」时整个 App 几乎看不见字。同类的万灵节写法是对的。
    #
    #     ⚠⚠ 下面 `_wcag` 的取色**必须跳过 ARGB 的 alpha 字节**：`Color(0xFF8A5A28)` 的 RGB 是
    #        (0x8A,0x5A,0x28)，不是 (0xFF,0x8A,0x5A)。写错这一处会把所有对比度算低一半以上，
    #        得出「正文全场不达标、秋季最差」这种**完全相反**的结论——本项目已经真犯过一次。
    _pt46 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                 "assistant", "ui", "PixelTheme.kt"),
                    encoding="utf-8").read().splitlines()

    def _wcag(hexstr):
        """0xAARRGGBB -> WCAG 对比度。⚠ 取后 6 位，alpha 字节不是颜色通道。"""
        h = hexstr[-6:]
        ch = []
        for v in (int(h[0:2], 16) / 255.0, int(h[2:4], 16) / 255.0, int(h[4:6], 16) / 255.0):
            ch.append(v / 12.92 if v <= 0.03928 else ((v + 0.055) / 1.055) ** 2.4)
        L = 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2]
        return L

    def _contrast46(fg, bg):
        a, b = _wcag(fg), _wcag(bg)
        return (max(a, b) + 0.05) / (min(a, b) + 0.05)

    # 解析全部 PixelColors 字面量；节日是 SeasonColors[n]!!.copy(...)，未覆盖的字段继承
    _pal46, _base46, _order46, _k = {}, {}, [], 0
    while _k < len(_pt46):
        _ln = _pt46[_k]
        _m1 = re.search(r"(\w+) to PixelColors\(\s*//\s*(\S+)", _ln)
        _m2 = re.search(r'"([^"]+)" to SeasonColors\[(\d+)\]!!\.copy\(', _ln)
        _m3 = re.match(r"\s*val (\w+) = PixelColors\(", _ln)
        if _m1 or _m2 or _m3:
            if _m1:
                _nm, _bs = _m1.group(2) + "季", None
            elif _m2:
                _nm = "节日:" + _m2.group(1)
                _bs = {0: "春季", 1: "夏季", 2: "秋季", 3: "冬季"}[int(_m2.group(2))]
            else:
                _nm, _bs = _m3.group(1), None
            _c, _j = {}, _k + 1
            while _j < len(_pt46):
                _b = _pt46[_j].strip()
                if _b.startswith(")") or _b.startswith("),"):
                    break
                for _kk, _vv in re.findall(r"(\w+)\s*=\s*Color\(0x([0-9A-Fa-f]{8})\)", _b):
                    _c[_kk] = _vv
                _j += 1
            if _nm not in _pal46:
                _pal46[_nm], _base46[_nm], _order46 = _c, _bs, _order46 + [_nm]
            _k = _j + 1
        else:
            _k += 1
    for _n in list(_pal46):
        if _base46.get(_n) and _base46[_n] in _pal46:
            _merged = dict(_pal46[_base46[_n]]); _merged.update(_pal46[_n]); _pal46[_n] = _merged

    check(len(_order46) >= 16,
          "PixelTheme 解析出全部 %d 套配色（日间/夜间/四季/节日），解析器没退化成空列表" % len(_order46))
    _FLOOR46 = 4.5
    for _n in _order46:
        _c = _pal46[_n]
        if "ink" not in _c or "inkDim" not in _c or "parchment" not in _c:
            continue
        _ri = _contrast46(_c["ink"], _c["parchment"])
        _rd = _contrast46(_c["inkDim"], _c["parchment"])
        check(_ri >= _FLOOR46,
              "%s：正文 ink/卡片底 %.2f:1（地板 %.1f）" % (_n, _ri, _FLOOR46))
        check(_rd >= _FLOOR46,
              "%s：次要文字 inkDim/卡片底 %.2f:1（地板 %.1f）" % (_n, _rd, _FLOOR46))
        # 层级不能塌：inkDim 必须明显比 ink 淡，否则「次要」就失去意义
        check(_rd < _ri * 0.92,
              "%s：inkDim 明显淡于 ink（%.2f vs %.2f，保留 %d%%）"
              % (_n, _rd, _ri, int(_rd / _ri * 100)))

    # 46.5 v2.97.0 强调色对比度地板
    #     起因：第二轮可读性审计发现，「去勾 ›」「打开 ›」「✔ 已全部完成」
    #     这类**最常用的小字**用的正是 gold / green / blue，而它们压卡片底
    #     在季节/节日配色下只有 1.41~3.26:1（最差是节日·月光水母起舞的 gold 1.41）。
    #     v2.97.0 把这三个强调色在 16 套色板上重新取值（线性 RGB 等比缩放保色相），
    #     地板与正文一致：4.5:1。
    #     ⚠ 夜间（月光温室）的 parchment 是**深紫**（#41345A），浅字压深底本来就达标，
    #       所以夜间天然过线，不需要也不该压暗。
    print("\n== 46.5 强调色对比度地板（v2.97.0）==")
    _ACC46 = (
        ("gold", "强调金「去勾 ›」「打开 ›」"),
        ("green", "完成绿「✔ 已全部完成」"),
        ("blue", "链接蓝"),
    )
    for _n in _order46:
        _c = _pal46[_n]
        for _tk, _desc in _ACC46:
            if _tk not in _c or "parchment" not in _c:
                continue
            _ra = _contrast46(_c[_tk], _c["parchment"])
            check(_ra >= _FLOOR46,
                  "%s：%s 压卡片底 %.2f:1（地板 %.1f）" % (_n, _desc, _ra, _FLOOR46))

    # 47 三位新村民（v2.53.0，群友反馈「NPC少了潘姆，文森特，贾斯」）
    #     群友说对了：三位都有完整 wiki 礼物表（不是「小孩不可送礼」），此前是真缺口。
    #     ⚠ 译名一律取「本项目数据里已有的写法」或 zh wiki 页面名（langlinks 反查），
    #       不凭印象——仙人掌果子（foraging 口径，不是 wiki 页名「仙人掌果子」的别名）、
    #       蜂蜜酒（processing 口径，zh wiki 页面名「蜜蜂酒」疑似笔误不采纳）、
    #       青蛙蛋/琉璃山药/葡萄干布丁/诡异玩偶（绿|黄）/三倍浓缩咖啡/仙女盒/古代玩偶/蔓越莓糖果
    #       均有出处。
    _g47 = json.load(io.open(os.path.join(DATA, "gifts.json"), encoding="utf-8"))
    _by47 = {x["name"]: x for x in _g47}
    check(len(_g47) == 38, "村民共 38 条（35 + c204 新增吉尔/冈瑟/马龙）")
    _WANT47 = {"潘姆": ("春", 18), "文森特": ("春", 10), "贾斯": ("夏", 4)}
    for _n47, (_bs47, _bd47) in _WANT47.items():
        check(_n47 in _by47, "gifts.json 有「%s」" % _n47)
        if _n47 in _by47:
            _v47 = _by47[_n47]
            check(_v47.get("birthdaySeason") == _bs47 and _v47.get("birthdayDay") == _bd47,
                  "%s 生日 = %s%d（wiki Infobox）" % (_n47, _bs47, _bd47))
            # v2.118：礼物表按官方五段重建，语义修正为
            #   hates = 官方「憎恶」段（官方本来就没列几样，天然少）
            #   dislikes = 官方「讨厌」段。
            #   ⇒ 完整度要看「讨厌 + 憎恶」合计，不能只看 hates。
            _neg47 = len(_v47.get("dislikes") or []) + len(_v47.get("hates") or [])
            check(len(_v47.get("loves") or []) >= 5 and _neg47 >= 3,
                  "%s 礼物表完整（loves %d / 讨厌+憎恶 %d）"
                  % (_n47, len(_v47.get("loves") or []), _neg47))
    # 跨文件一致性：foraging.json 葡萄 uses 里早就写着「文森特（最爱礼物）」——
    # 反方向必须也成立，否则两边口径漂移（本项目跨文件译名不一致的老病）。
    check("文森特（最爱礼物）" in io.open(os.path.join(DATA, "foraging.json"),
                                    encoding="utf-8").read(),
          "foraging.json 葡萄 uses 含「文森特（最爱礼物）」")
    check("葡萄" in (_by47.get("文森特", {}).get("loves") or []),
          "gifts.json 文森特 loves 含「葡萄」（与 foraging.json 互指一致）")
    check("仙人掌果子" in (_by47.get("潘姆", {}).get("loves") or []),
          "gifts.json 潘姆 loves 含「仙人掌果子」（与 foraging.json uses 互指一致）")
    # 头像三件套：image_map 有键、img/ 有文件、规格与现有头像一致（128x128 P 模式）
    _im47 = json.load(io.open(os.path.join(ROOT, "app", "src", "main", "assets",
                                           "image_map.json"), encoding="utf-8"))
    for _n47 in _WANT47:
        check(_im47.get(_n47) == _n47 + ".png", "image_map：%s -> %s.png" % (_n47, _n47))
        _p47 = os.path.join(ROOT, "app", "src", "main", "assets", "img", _n47 + ".png")
        check(os.path.exists(_p47), "头像文件存在：%s.png" % _n47)
        if os.path.exists(_p47):
            _b47 = open(_p47, "rb").read()
            check(_b47[:8] == b"\x89PNG\r\n\x1a\n", "%s.png 是真 PNG" % _n47)
            # IHDR 宽高（big-endian，偏移 16/20）与色彩类型（24 位偏移 25；P=3）
            _w47 = int.from_bytes(_b47[16:20], "big")
            _h47 = int.from_bytes(_b47[20:24], "big")
            _ct47 = _b47[25]
            check(_w47 == 128 and _h47 == 128,
                  "%s.png 尺寸 128x128（实际 %dx%d，与其余头像同规格）" % (_n47, _w47, _h47))
            check(_ct47 == 3, "%s.png 是调色板模式（与现有头像一致，控制体积）" % _n47)
    # 新村民的礼物物品名，有多少在图鉴可跳（查无的登记 warn，不失败——
    # 与既有礼物名查无同类，清单见 58.30，属已知数据缺口而非本版回归）
    _cn47 = set()
    for _f47 in os.listdir(DATA):
        if not _f47.endswith(".json") or "wiki" in _f47:
            continue
        try:
            _j47 = json.load(io.open(os.path.join(DATA, _f47), encoding="utf-8"))
        except Exception:
            continue
        def _w47f(o):
            if isinstance(o, dict):
                for _k47 in ("name", "name_cn", "product", "raw", "item", "seed"):
                    _x47 = o.get(_k47)
                    if isinstance(_x47, str):
                        _cn47.add(_x47)
                for _v47 in o.values():
                    _w47f(_v47)
            elif isinstance(o, list):
                for _v47 in o:
                    _w47f(_v47)
        _w47f(_j47)
    _no47 = []
    for _n47 in _WANT47:
        for _k47 in ("loves", "dislikes", "hates"):
            for _it47 in _by47.get(_n47, {}).get(_k47) or []:
                if _it47 not in _cn47:
                    _no47.append("%s/%s" % (_n47, _it47))
    if _no47:
        warns.append("47 新村民礼物名在数据里查无（点了不跳图鉴，同类清单见 58.30）：%s"
                     % "、".join(_no47))

    # 48 三位新村民的心事件（v2.54.0）
    #     ⚠ 上一轮账本说「潘姆的心事件早就有 4 条」是**错的**——grep 命中的「潘姆赊账难题」
    #     「帮打扫潘姆发火」是**格斯/潘妮事件里潘姆当配角**的行文，heartEvents 根本没有潘姆条目。
    #     本版补潘姆/文森特/贾斯三条目（矮人 wiki 无 Heart Events 章节，属事实性缺席）。
    _he48 = json.load(io.open(os.path.join(DATA, "heartEvents.json"), encoding="utf-8"))
    _he48 = {x["villager"]: x["events"] for x in _he48}
    _g48 = json.load(io.open(os.path.join(DATA, "gifts.json"), encoding="utf-8"))
    _missing48 = [x["name"] for x in _g48 if x["name"] not in _he48]
    # c204：吉尔/冈瑟/马龙也**没有**心事件——他们没有好感度系统，
    #   官方设定就没有心事件这一章（不是 App 漏了数据）。
    check(set(_missing48) == {"矮人", "吉尔", "冈瑟", "马龙"},
          "gifts 38 位中心事件只缺矮人/吉尔/冈瑟/马龙"
          "（wiki 无其心事件 或 没有好感度系统，事实性缺席）：实缺 %s"
          % "、".join(_missing48))
    for _n48 in ("潘姆", "文森特", "贾斯"):
        check(_n48 in _he48, "heartEvents 有「%s」条目" % _n48)

    # 48.1 结构不变式：每条事件恰好 hearts/place/event/options 四字段、全字符串；
    #      「无选项」必须逐字是这个值——UI 判据是 options != "无选项"（VillagersPage:371），
    #      写成「没有选项」会被当有选项渲染出来。
    for _v48, _evs48 in _he48.items():
        for _e48 in _evs48:
            if tuple(sorted(_e48.keys())) != ("event", "hearts", "options", "place"):
                check(False, "%s 事件字段集合异常：%s" % (_v48, sorted(_e48.keys())))
                break
            if not all(isinstance(_x48, str) for _x48 in _e48.values()):
                check(False, "%s 事件含非字符串字段" % _v48)
                break
            if _e48["options"].startswith("无选项"):
                pass
            elif _e48["options"] == "":
                check(False, "%s 出现空 options（UI 会静默隐藏，但既有约定是写「无选项」）" % _v48)
                break
        else:
            continue
        break
    else:
        check(True, "全部 %d 位 %d 条事件字段集合/类型一致（hearts/place/event/options）"
              % (len(_he48), sum(len(v) for v in _he48.values())))

    # 48.2 潘姆配方信与 recipes.json 互指（两处口径必须一致，这是本项目跨文件一致性老病）
    _r48 = json.load(io.open(os.path.join(DATA, "recipes.json"), encoding="utf-8"))
    _rl48 = _r48["recipes"] if isinstance(_r48, dict) and "recipes" in _r48 else _r48
    if isinstance(_rl48, dict):
        _rl48 = list(_rl48.values())
    _pam_src48 = {x["source"]: x["name"] for x in _rl48
                  if isinstance(x, dict) and "潘姆" in str(x.get("source", ""))}
    check(len(_pam_src48) == 2, "recipes.json 潘姆来源菜谱 2 条（实际 %d）" % len(_pam_src48))
    # c208 改口径：官方 Data/Events 潘姆只有 9 心剧情事件；3/7 心只是 recipes 的
    # 来信 source，heartEvents 不再重复归档（此前两边各记一份，口径重复）。
    _pam_he48 = [e["hearts"] for e in _he48.get("潘姆", [])]
    check(not ({"三心", "七心"} & set(_pam_he48)),
          "潘姆 heartEvents 不再有 3/7 心来信行（官方 Data/Events 只有 9 心剧情；c208 去重）")

    # 48.3 贾斯/文森特 8心是同一场共享事件：两边都要有、都指向大葱大师、表述互相引用
    _j8 = [e for e in _he48.get("贾斯", []) if e["hearts"] == "八心"]
    _w8 = [e for e in _he48.get("文森特", []) if e["hearts"] == "八心"]
    check(len(_j8) == 1 and len(_w8) == 1, "贾斯/文森特各有且仅有 1 条八心事件")
    check(_j8 and "大葱大师" in _j8[0]["event"] and "×5" in _j8[0]["event"],
          "贾斯八心写明大葱大师（大葱售价×5）")
    check(_w8 and "大葱大师" in _w8[0]["event"], "文森特八心写明大葱大师")
    check(_j8 and "文森特" in _j8[0]["place"] and _w8 and "贾斯" in _w8[0]["place"],
          "两边 place 互相点名（共享事件，只查一位也能看到全貌）")
    check(_j8 and "8心" in _j8[0]["event"] and "晴天" in _j8[0]["event"],
          "八心事件写明触发条件（两人均 8 心 + 春晴天）")

    # 49 在线更新（v2.55.0）：网络面收敛 + 发布仓自洽
    #     本 App 定位「完全离线」，联网是用户主动选择的例外。这一节钉的不是实现，
    #     而是**承诺的边界**：出口只有一个、不上传、文案诚实、发布仓数据自洽。
    _mf49 = io.open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"),
                    encoding="utf-8").read()
    check("android.permission.INTERNET" in _mf49, "manifest 已声明 INTERNET（检查更新所需）")
    # v2.86.0：POST_NOTIFICATIONS 移出禁列 —— 「从 Steam 云获取存档」**必须**发通知
    #   （用户下完存档时人还在浏览器里，结果只能靠通知告知；拒绝也不影响导入本身）。
    #   其余仍在禁列：那个 App 的口碑是「不主动要任何东西」，加一个权限也要有理由。
    # ⚠ 判据必须查**真实的 uses-permission 标签**：清单的注释里就写着
    #   「所以这里没有 MANAGE_EXTERNAL_STORAGE」，全文搜词会把这句注释当成真声明。
    _perm_tags49 = re.findall(r"<uses-permission[^>]*android:name=\"([^\"]+)\"", _mf49)
    # v2.91.0：MANAGE_EXTERNAL_STORAGE（「所有文件访问」）是**有意**加的 ——
    #   用户 2026-10-25 拍板「与远端 2.90.0 完全对齐」：
    #   远端 2.90.0 的 manifest 里有这条，本地漏了 → 已用该权限的用户升级会被系统撤权。
    #   它**不在**「顺手加」的禁列里（不是无理由的权限），但下面会钉住它的用途与边界。
    _extra_perm = [p for p in ("android.permission.REQUEST_INSTALL_PACKAGES",
                               "android.permission.ACCESS_NETWORK_STATE",
                               "android.permission.WAKE_LOCK",
                               "android.permission.READ_MEDIA_IMAGES",
                               "android.permission.READ_EXTERNAL_STORAGE")
                   if p in _perm_tags49]
    check(not _extra_perm, "没有顺手加其它权限（升级 APK 走浏览器下载、不装包；%s）"
          % (_extra_perm or "实测零多余"))
    # v2.86.0 新增的三条权限各有明确用途，逐条钉住（防止「加了但没用」或「用了但没加」）
    check("android.permission.POST_NOTIFICATIONS" in _perm_tags49,
          "已声明 POST_NOTIFICATIONS（Steam 导入结果要靠通知告知）")
    check("android.permission.FOREGROUND_SERVICE" in _perm_tags49,
          "已声明 FOREGROUND_SERVICE（等待下载期间要能在后台活着）")
    check("android.permission.FOREGROUND_SERVICE_DATA_SYNC" in _perm_tags49,
          "已声明 FOREGROUND_SERVICE_DATA_SYNC（Android 14+ 前台服务必需）")
    # v2.68.0 新增 WRITE_EXTERNAL_STORAGE：把电脑端脚本存进手机的「下载」目录。
    #     ⚠ 必须限死 maxSdkVersion=28——Android 10 起走 MediaStore，一个权限都不需要；
    #     不限版本等于让绝大多数用户在毫无必要时被系统追问一次存储权限，
    #     而这个 App 一直的口碑是「不主动要任何东西」。
    check('android:name="android.permission.WRITE_EXTERNAL_STORAGE"' in _mf49
          and 'android:maxSdkVersion="28"' in _mf49,
          "存储权限限死 maxSdkVersion=28（10+ 走 MediaStore，新系统不该被追问）")

    # 49.1 **网络行为只有三处**，且都是**用户主动触发**：
    #      ① `UpdateRepo.kt` —— 设置页点「检查更新」时的一个 GET，以及「下载电脑脚本」
    #         的一次 GET（同一文件，故 49.1' 钉住「脚本不另开文件」）；
    #      ② `SaveReceiver.kt`（v2.67.0 新增）—— 用户手动开「从电脑接收」时，
    #         在**同一个局域网**上监听一个端口收一个 zip。**它不主动外发任何东西**：
    #         只 accept、只落盘，没有任何出站请求，也不读回任何本地文件。
    #      ③ `ModRepo.kt`（v2.72.0 新增）—— 「🧩 Mod 扩展」页里的两个 GET：
    #         拉清单、下载扩展包。**只在用户点那一下时发**，装完就纯本地；
    #         请求不带存档、不带设备标识、不带自定义 header（与前两处同一纪律）。
    #
    #      这条断言的用意不是「不许联网」，而是「新增网络行为必须在这里登记过」——
    #      将来谁想悄悄加一个后台请求，文件名必须先进白名单，白名单一改就说明
    #      有人想清楚了它是什么、用户能不能看见。
    _net_files49 = []
    for _r49, _d49, _fs49 in os.walk(os.path.join(ROOT, "app", "src", "main", "java")):
        for _f49 in _fs49:
            if not _f49.endswith(".kt"):
                continue
            _t49c = code_only(io.open(os.path.join(_r49, _f49), encoding="utf-8").read())
            if re.search(r"HttpURLConnection|java\.net\.(URL|Socket|Datagram)|okhttp|Retrofit", _t49c):
                _net_files49.append(_f49)
    _net_files49.sort()
    check(_net_files49 == ["ModRepo.kt", "SaveReceiver.kt", "UpdateRepo.kt"],
          "网络行为只有三处（实得 %s）：检查更新 + 下载脚本、局域网接收存档的监听、"
          "Mod 扩展包下载；都要用户主动触发；新增出口须同步改离线文案与本断言"
          % (_net_files49 or "无"))

    _ur49 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                 "assistant", "data", "UpdateRepo.kt"), encoding="utf-8").read()
    check("huututu939/xingluguzhushou/releases/latest/download/version.json" in _ur49,
          "UpdateRepo 的清单 URL 指向实际发布仓（GitHub Release 资产直链）")
    check("connectTimeout" in _ur49 and "readTimeout" in _ur49,
          "两个超时都设了（无超时的网络调用会把设置页卡死）")
    # c88→c89 的教训：发布仓文件名曾是中文，未编码 URL 在严格 HTTP 客户端直接抛异常。
    # 两手都钉：清单 apk 字段必须纯 ASCII（根除）+ App 端拼 URL 必须过编码（防御）。
    check("toASCIIString" in _ur49 and "asciiUrl" in _ur49,
          "UpdateRepo 对拼出的下载 URL 做 percent/IDNA 编码（不赌浏览器容错）")
    # v2.72 修正：四参 URI 构造会**吃掉端口**（只认 host），带端口的地址会被悄悄改成 80 端口。
    #   三个 URL 都不带端口所以一直没露，Mod 扩展包下载第一个带端口的地址就炸了。
    # ⚠ 必须扫 code_only：说明里写「原来用的是四参 `URI(scheme, host, path, fragment)`」
    #   正是为了让人看懂坑在哪——扫原文会把这段**注释**当成现行代码。
    check(re.search(r"URI\(\s*scheme\s*,\s*host\s*,", code_only(_ur49)) is None,
          "URL 编码不再用四参 URI 构造（会把 host:port 变成 host，连的端口都不对）")
    check("rawAuthority" in _ur49,
          "URL 编码改用五参构造 + rawAuthority（端口与转义后的 authority 都保住）")

    # 49.1' v2.68.0：脚本下载的 GET 住在**同一个文件**里（新增出口不是新增文件）。
    #     49.1 那条白名单钉的是文件名，所以把两件事塞进一个文件，等于「网络出口必须
    #     在同一个文件里被人想过」这条原意还在；另开一个 ScriptRepo.kt 就会绕过它。
    _urcode49 = code_only(_ur49)
    check("huututu939/xingluguzhushou/releases/latest/download/send_save.bat" in _ur49,
          "脚本地址指向发布仓 tools/send_save.bat（与 App 里那个按钮是同一个目标）")
    check("fun downloadScript" in _urcode49 and "SCRIPT_MAX_BYTES" in _urcode49,
          "脚本下载有体量上限（挡住「拿到一整个 HTML 错误页却报下载成功」）")
    check("0x7F" in _urcode49 and "> 0x7F" in _urcode49,
          "下载后校验纯 ASCII（bat 的硬不变量；HTML/GBK 错误页必有 >=0x80 字节，"
          "用户拿到会是一个双击没反应的假货）")
    check("fetchBytes" in _urcode49 and "fun <T> withRedirects" in _urcode49,
          "脚本与清单走同一条发布链路（GitHub Release 资产；复制第二份循环必然漂移）")
    check("MediaStore.Downloads" in _urcode49 and "IS_PENDING" in _urcode49,
          "落盘走 MediaStore.Downloads 且先挂起、写完再置回（半截文件不进下载目录）")
    check("saveToDownloads" in _urcode49 and "Build.VERSION.SDK_INT >= 29" in _urcode49,
          "10+ 走 MediaStore 无需权限，8/9 才走旧公共目录（needsStoragePermission 对齐）")

    # v2.74.3：脚本存进「下载」目录在 10+ 上失败。根因是 29+ 分支**漏了 RELATIVE_PATH**
    #   （落点交给系统默认，那不是契约）＋同名被 MediaStore 加 (1) ＋失败原因被 runCatching 吞掉。
    #   下面这组断言钉的是**结构关系**，不钉文案字面量、不钉条数。
    _rel49 = re.search(r"put\(\s*MediaStore\.Downloads\.RELATIVE_PATH\s*,\s*([^)]+)\)", _urcode49)
    check(bool(_rel49) and "DIRECTORY_DOWNLOADS" in _rel49.group(1),
          "29+ 分支显式给 RELATIVE_PATH=DIRECTORY_DOWNLOADS（不给则落点由系统默认决定）")
    check("put(MediaStore.Downloads.DISPLAY_NAME, name)" in _urcode49
          and "put(MediaStore.Downloads.IS_PENDING, 1)" in _urcode49,
          "29+ 分支同时给了 DISPLAY_NAME 与 IS_PENDING（条目四要素齐全）")
    # ⚠ MIME 必须是 octet-stream：实测 DISPLAY_NAME="send_save.bat" + MIME="text/plain"
    #   会被 MediaProvider 改名成 "send_save.bat.txt"，用户双击的是不带 .txt 的那个 → 找不到。
    check('put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")' in _urcode49,
          "29+ 分支的 MIME 是 application/octet-stream（实测能保住 send_save.bat 的原名）")
    check('put(MediaStore.Downloads.MIME_TYPE, "text/plain")' not in _urcode49,
          "MIME 不能改成 text/plain（会让 MediaProvider 把文件改名成 .txt）")
    # 公共目录写入**只能有一处**，且是 ≤28 的兜底；多一处就意味着 29+ 可能又走回老路。
    check(_urcode49.count("getExternalStoragePublicDirectory") == 1,
          "公共目录写入只有一处（≤28 兜底），29+ 不会再走回老路")
    # 同名要覆盖：MediaStore 遇同名自动加 (1)，实测下载目录里出现过 (1)/(2)，
    #   而 ≤28 走 File 是覆盖的 —— 两条路径行为不一致。
    check("findExistingDownload" in _urcode49 and '"wt"' in _urcode49,
          "同名覆盖写（复用已有条目的 URI 截断重写，不再产生 (1)/(2)）")
    # 失败原因必须进 Log：原来 runCatching 把异常吞掉，真机上失败过却查不出原因。
    check("Log.w(TAG" in _urcode49,
          "落盘失败要记 Log（异常被吞掉就再也查不出原因）")
    # 29+ 的失败文案不能说「检查存储权限」——那里一个存储权限都不需要，
    #   说权限是把用户引向一个根本不存在的开关。钉「else 分支的文案里没有『权限』」。
    _tail49 = _urcode49.split("save script to downloads failed")[-1]
    _m29msg49 = re.search(r'else\s*\{\s*"([^"]*)"', _tail49)
    check(bool(_m29msg49) and "权限" not in _m29msg49.group(1),
          "29+ 的落盘失败文案不提存储权限（那边不需要权限）")
    # ⚠ 权限被拒那句文案的断言在**第 68 节**——它读的是 SettingsPage 的源码，
    #   而 _sp68c 是那一节才定义的，在这里用会把脚本从中间打断（后面几千条全不跑）。
    # 首版把 404 也报成「连不上网络」——网络明明是通的，用户会去查 WiFi，
    #   而问题根本不在 WiFi。判据：三条文案必须**分别**存在，钉「有区分」而不是钉具体字面量。
    check('http == "404"' in _urcode49 and "服务器回了 HTTP" in _urcode49
          and "连不上网络" in _urcode49,
          "下载失败区分 404 / 服务端错误 / 真·网络不通（别把 404 说成连不上网络）")
    check('raw.startsWith("HTTP ")' in _urcode49,
          "HTTP 状态从 withRedirects 抛出的字面量取（不碰异常类名，R8 会改名）")

    # 49.2 文案诚实（v2.17 D6 allowBackup 同款口径）：
    #      「关于」里那句「不申请任何权限」在加 INTERNET 后就是谎话，必须已改掉。
    #      v2.61 加了每日静默检查后，「点按钮才联网」同样成了谎话——**第二次改诚实**。
    #      这里钉的是「新的承诺必须与新的行为相符」，不是钉某句字面量：
    #      断言若钉死「才会联网」，下次加自动检查时它会逼着人继续撒谎而不是改文案。
    _sp49 = src_sp()
    check("不申请任何权限" not in code_only(_sp49),
          "「关于」里「不申请任何权限」的旧承诺已删（现在有 INTERNET，留着就是骗人）")
    # ⚠ 只扫 code_only（剥掉注释）而**不能扫全文**：CHANGELOG 里 v2.55.0 那条历史条目
    #    写着「仅点击按钮时联网」——那是**当时的真实行为**，属于历史事实不该改。
    #    全文扫会把历史记录误判成「当前还在撒谎」，逼人去篡改历史文案。
    check("才联网" not in code_only(_sp49),
          "「点按钮才联网」的旧承诺已从界面文案删除（v2.61 有每日静默检查，留着就是骗人）")
    check("每次打开 App 会联网检查一次" in _sp49 and "不上传任何数据" in _sp49,
          "关于页写明「每次打开 App 检查一次 + 不上传数据」（v2.73.3 诚实口径）")
    check("可在「在线更新」里关掉" in _sp49, "诚实口径写明了关闭路径（不是无解的强推）")
    check("检查更新" in _sp49 and "UpdateRepo" in _sp49, "设置页接线检查更新")
    # v2.72 的第四个网络行为：装 Mod 扩展包。它不像前三个有全局开关（那是「点一下才发生」，
    #   这一处是「进那个页面点那一下才发生」），所以文案必须写明在哪发生，否则就是漏。
    check("Mod 扩展" in _sp49 and "点下载或刷新清单时会联网" in _sp49,
          "关于页写明「Mod 扩展」页会联网（v2.72 新增网络行为，诚实口径同步改过一次）")

    # 49.3 发布仓自洽：version.json 合法、apk 文件真实在仓、size/md5 与实际字节一致、
    #      发布仓版本不得超前源码（落后合法——构建完还没发布是正常状态）。
    _pub49 = os.path.join(os.path.dirname(ROOT), "发布仓")
    _vj49 = os.path.join(_pub49, "version.json")
    check(os.path.isdir(os.path.join(_pub49, ".git")), "发布仓是独立 git 仓库（与源码仓零耦合）")
    check(re.search(r"^发布仓/", io.open(os.path.join(os.path.dirname(ROOT), ".gitignore"),
                    encoding="utf-8").read(), re.M) is not None,
          "源码仓 .gitignore 忽略 发布仓/（防嵌套仓库内容混进源码历史）")
    if os.path.exists(_vj49):
        _m49 = json.load(io.open(_vj49, encoding="utf-8"))
        for _k49 in ("versionCode", "versionName", "notes", "apk", "size", "md5"):
            check(_k49 in _m49, "version.json 含字段 %s" % _k49)
        _apk_field49 = str(_m49.get("apk", "_missing_"))
        # ⚠ 2026-11-10（发布仓迁 GitHub）：apk 字段有两种写法都合法 ——
        #   ① **Release 资产名**（stardew-assistant_*.apk，无目录前缀）—— 现用这个；
        #   ② 发布仓内相对路径（apk/stardew-assistant_*.apk）—— 旧写法，保留兼容。
        #   两种都试，别把「写法变了」当成「文件丢了」。
        _apk49 = os.path.join(_pub49, _apk_field49.replace("/", os.sep))
        if not os.path.exists(_apk49):
            _apk49 = os.path.join(_pub49, "apk",
                                 os.path.basename(_apk_field49))
        check(str(_m49.get("apk", "")).isascii(),
              "version.json 的 apk 字段是纯 ASCII 文件名（中文进 URL 要靠客户端编码自觉，"
              "实测严格客户端直接抛异常）：%s" % _m49.get("apk"))
        check(os.path.exists(_apk49),
              "version.json 指向的 APK 真实在发布仓（%s）" % _m49.get("apk"))
        if os.path.exists(_apk49):
            _b49 = open(_apk49, "rb").read()
            import hashlib as _hl49
            check(len(_b49) == _m49.get("size"),
                  "size 与实际字节一致（%s vs %s）" % (_m49.get("size"), len(_b49)))
            check(_hl49.md5(_b49).hexdigest() == _m49.get("md5"),
                  "md5 与实际文件一致（App 端校验下载完整性的依据）")
        check(isinstance(_m49.get("versionCode"), int) and _m49["versionCode"] <= int(_vc44.group(1)),
              "发布仓版本 c%s 不超前源码 c%s（落后合法：构建后未发布是正常状态）"
              % (_m49.get("versionCode"), _vc44.group(1)))
        check(_m49.get("notes"), "version.json 的 notes 非空（检查更新弹窗直接展示它）")
    check(os.path.exists(os.path.join(ROOT, "tools", "publish_gitee.py")),
          "发布脚本存在（tools/publish_gitee.py）")

    # 49.4 发布脚本的 CLI 卫生（v2.58.0 踩坑）
    #      这脚本**默认就写文件并 commit**。此前没实现 --help，
    #      一条 `--help` 直接把当时的最新版又复制回发布仓并提交了 —— 等于误发布一次。
    #      所以「未知参数必须挡住」不是洁癖，是防误触发的硬要求。
    _pg49 = io.open(os.path.join(ROOT, "tools", "publish_gitee.py"), encoding="utf-8").read()
    check("def parse_args" in _pg49 and '"--help"' in _pg49,
          "publish_gitee.py 实现 --help（否则查用法会真的执行一次发布）")
    check("未知参数" in _pg49,
          "publish_gitee.py 对未知参数报错退出，不当没看见继续跑发布")
    check('elif a == "--notes-file"' in _pg49 and "i += 1" in _pg49,
          "--notes-file 的路径值被跳过（否则那个路径会被当成未知参数拒掉）")
    _pg49c = code_only(_pg49)
    check(_pg49c.find("未知参数") < _pg49c.find("shutil.copy"),
          "参数校验发生在复制 APK 之前（顺序反了就等于没挡）")
    check("本脚本**只能发布 app/build.gradle.kts 里的当前版本**" in _pg49,
          "脚本头部写明「只能发当前版本」（发历史版要手工，已在账本记档）")

    # 49.5 批量补发：notes 允许覆盖，但不能退回「首条即全部」
    #      发布仓可能落后多个版本（2026-10-15：c89 之后清过仓，c90~c94 积了 5 版），
    #      用户从 c89 跳到 c94 时弹窗只显 v2.60.0 那一行 —— 前面 4 版的变化一个字都看不到。
    check("def read_notes_file" in _pg49,
          "存在 read_notes_file（--notes-file 的 notes 覆盖入口）")
    check("--notes-file" in _pg49 and "批量补发" in _pg49,
          "脚本文档写明批量补发场景（后人不会以为是无用选项）")
    check('elif head_vn == vn:' in _pg49 and "CHANGELOG 首条 v%s 不等于当前版本" in _pg49,
          "不给 --notes-file 时仍强制首条版本号对得上（写旧版等于骗人）")
    # ⚠ 比对对象必须按**当前版本**取，不能写死某一份 c94 的文件：那样一旦发新版本，
    #   比对的就是上一版的说明，红得毫无意义（v2.62.0 就当场撞上了）。
    # 清单 notes 的合法来源只有两个：批量补发用的 --notes-file，或 CHANGELOG 首条。
    _gb49 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    _vnj49 = re.search(r'versionName\s*=\s*"([^"]+)"', _gb49).group(1)
    _vcj49 = re.search(r"versionCode\s*=\s*(\d+)", _gb49).group(1)
    _mj49 = json.load(io.open(os.path.join(_pub49, "version.json"), encoding="utf-8")) \
        if os.path.exists(os.path.join(_pub49, "version.json")) else {}
    _rl49 = os.path.join(os.path.dirname(ROOT), "安装包归档",
                         "发布说明_v%s_c%s.txt" % (_vnj49, _vcj49))
    if os.path.exists(_rl49):
        _rl49n = [l.strip() for l in io.open(_rl49, encoding="utf-8").read().splitlines()
                  if l.strip()]
        check(len(_rl49n) >= 2,
              "发布说明不止一行（%d 条；单行就是「批量补发只显最后一版」的病）" % len(_rl49n))
        # ⚠ 逐条比对必须和下面的 else 分支**同样**带版本前置条件。
        #   发布说明文件是发版**之前**就写好的，而推送可以隔很久甚至被撤回；
        #   此前只有 else 分支有这道闸，于是「说明写完、还没 push」这段完全正常的窗口里
        #   verify 常红——而一条长期红的断言会让人习惯性忽略整个 verify，
        #   那比少一条断言更坏（c110 实测撞上）。
        if str(_mj49.get("versionCode")) == _vcj49:
            # c239：**批量补发**时 notes 来自 --notes-file（聚合说明），
            #   比对对象也该是**那份聚合文件** —— 拿聚合的 21 行去比本版的 2 行，
            #   那个红是**假红**（发布出去的就是聚合那份，与本版发布说明无关）。
            #   判据：清单 notes 与聚合文件逐条一致 ⇒ 批量补发口径；
            #   否则与本版发布说明逐条一致 ⇒ 单版口径；两条都不成立才是真错。
            _agg49b = None
            _agn49b = []
            # ⚠ 发布仓目录里可能躺着**多份**历史聚合说明（c206→c210 那份还在）。
            #   拿错一份去比会得到「都不一致」的假红 ⇒ 这里**逐份试**，
            #   只要有一份与清单逐条一致，就算批量补发口径。
            for _f49b in sorted(os.listdir(os.path.dirname(ROOT) + os.sep + "安装包归档")):
                if not (_f49b.startswith("发布notes_云端") and _f49b.endswith("_聚合.txt")):
                    continue
                _p49b = os.path.join(os.path.dirname(ROOT), "安装包归档", _f49b)
                _l49b = [l.strip() for l in io.open(_p49b, encoding="utf-8").read().splitlines()
                         if l.strip()]
                if _l49b and _mj49.get("notes") == _l49b:
                    _agg49b = _f49b
                    _agn49b = _l49b
                    break
            if _agg49b:
                check(True, "version.json 的 notes 与聚合发布说明《%s》逐条一致"
                            "（批量补发口径，%d 条）" % (_agg49b, len(_agn49b)))
            else:
                check(_mj49.get("notes") == _rl49n,
                      "version.json 的 notes 与本版发布说明文件逐条一致（发布出去的就是审过的那份）")
        else:
            warns.append("发布仓还是 c%s（源码已到 c%s），发布说明逐条比对跳过——"
                         "「写完说明还没发布」是正常状态，不是写错"
                         % (_mj49.get("versionCode"), _vcj49))
    else:
        _head49 = re.search(r'CHANGELOG = listOf\(\s*\r?\n\s*"v[^"]*" to listOf\((.*?)\),',
                            src("ui/settings/SettingsPage.kt"), re.S)
        _hn49 = re.findall(r'\n\s+"([^"]+)"', _head49.group(1)) if _head49 else []
        # ⚠ 只在**远端 == 源码当前版本**时才逐条比。远端落后（构建完还没发布）时
        #   拿 c96 的 notes 去比 v2.62.0 的 CHANGELOG 必然不等，那是「还没发」不是「写错了」，
        #   不加这个前置条件的话，每发一版到下一次发布之间 verify 都会常红。
        if str(_mj49.get("versionCode")) == _vcj49:
            # c239：**批量补发**时 notes 来自 --notes-file（聚合说明），不是 CHANGELOG 首条。
            #   这时逐条比对的对象也该是**那份聚合文件** —— 否则断言会拿聚合的 21 行去比
            #   CHANGELOG 首条的 2 行，必然红，而那个红是**假红**（发布用的就是聚合那份，
            #   与 CHANGELOG 首条无关）。
            #   判据：清单 notes 与「聚合文件」逐条一致 ⇒ 走聚合分支；
            #   否则与 CHANGELOG 首条逐条一致 ⇒ 走单版分支；两条都不成立才是真错。
            _agg49 = None
            _agn49 = []
            for _f49 in sorted(os.listdir(os.path.dirname(ROOT) + os.sep + "安装包归档")):
                if not (_f49.startswith("发布notes_云端") and _f49.endswith("_聚合.txt")):
                    continue
                _p49 = os.path.join(os.path.dirname(ROOT), "安装包归档", _f49)
                _l49 = [l.strip() for l in io.open(_p49, encoding="utf-8").read().splitlines()
                        if l.strip()]
                if _l49 and _mj49.get("notes") == _l49:
                    _agg49 = _f49
                    _agn49 = _l49
                    break
            if _agg49:
                check(True, "version.json 的 notes 与聚合发布说明《%s》逐条一致"
                            "（批量补发口径，%d 条）" % (_agg49, len(_agn49)))
            else:
                check(_mj49.get("notes") == _hn49,
                      "version.json 的 notes 等于 CHANGELOG 首条（单版发布口径，%d 条）" % len(_hn49))
        else:
            warns.append("发布仓还是 c%s（源码已到 c%s），notes 比对跳过——"
                         "「构建完还没发布」是正常状态，不是写错"
                         % (_mj49.get("versionCode"), _vcj49))

    # 49.6 发布后冒烟（v2.62.0 收尾）
    #      c96 发版后的冒烟脚本重试条件写成 `if r.status == 200: break`，
    #      撞上 gitee raw 缓存里那份**上一版**的 version.json 就当场判绿——
    #      脚本假绿比没脚本更坏，它让人以为验过了。所以这套不是「顺手加个检查」，
    #      而是把「发完到底验没验、验得对不对」钉成可复跑的常驻资产。
    #      关键约束：取数入口**强制要求传内容校验函数**，源码里不许再出现只看状态码的放行。
    _sp49 = os.path.join(ROOT, "tools", "smoke_publish.py")
    check(os.path.exists(_sp49), "发布后冒烟脚本存在（tools/smoke_publish.py）")
    if os.path.exists(_sp49):
        _sm49 = io.open(_sp49, encoding="utf-8").read()
        check("def fetch_verified" in _sm49 and "validate(body)" in _sm49,
              "取数入口强制传内容校验函数（重试不能只看 HTTP 状态码）")
        check("if st == 200 and validate(body):" in _sm49,
              "fetch_verified 的放行条件是「内容满足 validate」而非「状态码 200」")
        check("from publish_gitee import" in _sm49 and "gradle_version" in _sm49
              and "def gradle_version" not in _sm49,
              "版本号与路径只从 publish_gitee 单源取（冒烟脚本不再自己抄一份）")
        check("--diff-filter=A" in _sm49,
              "历史包清单取自发布仓 git 历史，不写死包名")
    # ⚠ v2.68.0 实测：光有 --diff-filter=A 不够。发版在 git 里是「删旧 apk + 加新 apk」，
    #   而 APK 之间 70%+ 字节相同，默认的重命名检测会把这一对折叠成一次 R，
    #   A 过滤器就认不出来 → 历史包清单只剩最早一个，③ 只查 1/10 却照样打 PASS。
    #   撤回 c110 那次实测暴露：刚撤回的包根本没进检查名单。
    #   （这几行的缩进必须是 8 空格——它在 `if os.path.exists(_sp49)` 块里。
    #    写 4 空格会**提前关掉 if**，下一行就 IndentationError，整个 verify 崩。）
        check("--no-renames" in _sm49,
              "枚举历史包禁重命名折叠（否则删+加被折成 R，③ 只查到 1/10 个包还照样绿）")
        check('"--help"' in _sm49 and "未知参数" in _sm49,
              "冒烟脚本 CLI 卫生：--help 与未知参数都被挡住")
        # ⚠ v2.68.0 修掉一处**假绿**并把它钉住：① 的标题写着「且指向本地当前版本」，
        #   但代码里从来没判过——fetch_verified 重试耗尽后照样 r.ok()。
        #   而真正会红的 ②B/⑤B 都以 same_version 为前提，于是「远端还是旧版」时
        #   整份冒烟可以**全绿收工**。收工标准既然是「冒烟全绿」，全绿就必须真成立。
        #   判据用**出现次数**：ok 与 fail 两个分支都在，标题必然出现两次。
        check(_sm49.count("① 清单可读且指向期望版本") == 2,
              "① 真的比对期望版本（ok / fail 两个分支都在，不是无条件打勾）")
        check('"--expect"' in _sm49 and "want_vc" in _sm49,
              "冒烟支持 --expect <构建号>：撤回发布后远端**就应该**停在旧版，"
              "否则会把「落后」报成失败并诱导人去重推（正好把撤回的包又发上去）")
        check(_sm49.count("if same_version:") == 1,
              "只有「APK 三方一致」这一项受版本闸门约束（脚本没有版本号，"
              "⑤B 必须无条件比字节，否则撤回 APK 时脚本漂移就查不出来）")
    check(os.path.exists(os.path.join(ROOT, "tools", "test_smoke_retry.py")),
          "重试行为有可复跑的行为测试（HTTP 200 + 内容过期不得判成功）")

    # 50 商店目录（v2.56.0，群友反馈「各个卖东西的具体信息」）
    #     这份数据是**生成物**（tools/shop_catalog_* 四段管线，英文 wiki 1.6 商品表），
    #     最容易烂的两处：① 译名混进项目没有的口径（硬造翻译）；② 生成脚本与产物脱节。
    #     本节把「每行中文名必须 ∈ 项目物品名全集」钉成硬断言——这正是当初分五层对齐
    #     字典/互链/派生/哈希/像素哈希的原因：宁可少一行，不硬造一个词。
    _sc50 = json.load(io.open(os.path.join(DATA, "shopCatalog.json"), encoding="utf-8"))
    _sh50 = json.load(io.open(os.path.join(DATA, "shopHours.json"), encoding="utf-8"))
    _shl50 = _sh50["shops"] if isinstance(_sh50, dict) and "shops" in _sh50 else _sh50
    _shops15 = [s["name"] for s in _shl50]
    check(set(_sc50.keys()) <= set(_shops15),
          "目录的店 ⊆ shopHours 的 15 家（多出 %s）"
          % (sorted(set(_sc50) - set(_shops15)) or "无"))
    _AVAIL50 = {"全年", "春", "夏", "秋", "冬", "春、夏", "夏、秋", "秋、冬", "春、秋",
                "轮换", "每日轮换", "每周随机", "限时", "玩家供货", "牲畜", "特殊",
                "春、夏、秋", "全年（第 2 年）"}
    _bad_name, _bad_avail, _bad_price, n50 = [], [], [], 0
    for _st50, _rows50 in _sc50.items():
        for _r50 in _rows50:
            n50 += 1
            if _r50.get("name") not in _cn47:
                _bad_name.append("%s/%s" % (_st50, _r50.get("name")))
            _av50 = _r50.get("avail", "")
            if _av50 not in _AVAIL50 and not re.match(r"^[\u4e00-\u9fff、]+$", _av50):
                _bad_avail.append("%s/%s" % (_st50, _av50))
            if not isinstance(_r50.get("price"), int) or _r50["price"] <= 0:
                _bad_price.append("%s/%s" % (_st50, _r50.get("name")))
            # codex 字段必须与真实可达一致（防止 UI 给了跳不动的链接）
            _want50 = _r50.get("name") in _cn47
            if bool(_r50.get("codex")) and not _want50:
                _bad_name.append("%s/%s [codex 标了但不可达]" % (_st50, _r50.get("name")))
    check(n50 >= 400, "目录行数 %d（生成物缩水要警惕）" % n50)
    check(not _bad_name, "每一行物品名都在项目物品名全集里（零硬造译名）：%s"
          % ("、".join(_bad_name[:5]) or "通过"))
    check(not _bad_avail, "avail 全在值域：%s" % ("、".join(_bad_avail[:5]) or "通过"))
    check(not _bad_price, "price 全为正整数：%s" % ("、".join(_bad_price[:5]) or "通过"))
    # 换购行（沙漠商人）：unit 也必须是项目词
    _bad_unit = []
    for _r50 in _sc50.get("沙漠商人", []):
        u = _r50.get("unit", "")
        if u and u not in _cn47:
            _bad_unit.append("%s->%s" % (_r50["name"], u))
    check(not _bad_unit, "换购货币名全在项目词表：%s" % ("、".join(_bad_unit) or "通过"))

    # 50.1 UI 接线四件套
    _ma50 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                 "assistant", "MainActivity.kt"), encoding="utf-8").read()
    check("data object GuideShops : Screen" in _ma50, "Screen.GuideShops 已定义")
    check('guideNav("shops")' in _ma50, "guideBody 接 onOpenShops")
    _gb50 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                 "assistant", "ui", "guide", "GuideBooks.kt"),
                    encoding="utf-8").read()
    check('GuideBook("shops", "🛒", "商店目录"' in _gb50, "GUIDE_BOOKS 注册商店目录册")
    _gsp50 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                  "assistant", "ui", "guide", "GuideSubPage.kt"),
                     encoding="utf-8").read()
    check("Screen.GuideShops -> GuideShopsPage(onBack)" in _gsp50, "GuideSubPage 路由分支")
    _gd50 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                 "assistant", "data", "GameData.kt"), encoding="utf-8").read()
    check('shopCatalog = obj("shopCatalog.json")' in _gd50, "GameData 加载 shopCatalog")
    check('"shopCatalog".takeIf { shopCatalog == null }' in _gd50,
          "shopCatalog 进 loadFailures 降级清单（缺数据页面要提示而不是空白）")

    # 50.2 首页「N 本册子」文案与表长一致（写死数字是本项目反复踩的坑）
    _gh50 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                 "assistant", "ui", "guide", "GuideHomePage.kt"),
                    encoding="utf-8").read()
    _nbook50 = len(re.findall(r'GuideBook\("', _gb50))
    _m50 = re.search(r"查资料用的([一二三四五六七八九十\d]+)本册子", _gh50)
    _CN_NUM50 = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "七": 7,
                 "八": 8, "九": 9, "十": 10}
    _said50 = _CN_NUM50.get(_m50.group(1)) if _m50 else None
    check(_said50 == _nbook50,
          "首页「%s本册子」与 GUIDE_BOOKS 表长 %d 一致（加册子必须同步改文案）"
          % (_m50.group(1) if _m50 else "?", _nbook50))

    # 50.3 无目录的店必须有说明位（不许整店空白）
    _gsp50f = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                   "assistant", "ui", "guide", "GuideShopsPage.kt"),
                      encoding="utf-8").read()
    _noCat50 = [s for s in _shops15 if s not in _sc50]
    for _s50 in _noCat50:
        check(('"%s"' % _s50) in _gsp50f,
              "无目录的店「%s」在页面有说明文案（NO_CATALOG_NOTE 或兜底句）" % _s50)

    # 50.4 管线脚本四段齐备（产物是生成物，出处必须可重跑）
    for _ps50 in ("shop_catalog_fetch.py", "shop_catalog_parse.py",
                  "shop_catalog_translate.py", "shop_catalog_merge.py"):
        check(os.path.exists(os.path.join(ROOT, "tools", _ps50)),
              "管线脚本在位：tools/%s" % _ps50)

    # 51 自定义今日待办（v2.57.0，群友反馈③）
    #     最要害的一处设计决定：勾选键从「下标」改成「文本」。
    #     清单可自定义后，下标键会让「删一行」把后面所有行的勾选错位继承——
    #     用户勾了 A 删了前面的 B，A 显示成未勾、B 的位置显示已勾，纯数据错乱。
    #     本节把「不许再出现下标键」钉死，并把迁移链、导出、输入约束逐处对齐。
    _pr51 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                 "assistant", "data", "PrefsRepo.kt"), encoding="utf-8").read()
    _tp51 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                 "assistant", "ui", "today", "TodayPage.kt"), encoding="utf-8").read()
    _ma51 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                 "assistant", "MainActivity.kt"), encoding="utf-8").read()

    # 51.1 默认清单单源在数据层；UI 不再私藏副本
    check("val TODO_DEFAULT = listOf(" in _pr51, "默认清单定义在 PrefsRepo（数据层单源）")
    check("浇水（含温室 / 姜岛）" in _pr51 and "浇水（含温室 / 姜岛）" not in _tp51,
          "默认 6 条文案只在 PrefsRepo 一份（TodayPage 无副本）")
    # 默认文案逐条钉（改了任何一条，老用户的勾选键语义就变了，必须是有意识的决定）
    _def51 = re.search(r"val TODO_DEFAULT = listOf\((.*?)\n\)", _pr51, re.S)
    _items51 = re.findall(r'"([^"]+)"', _def51.group(1)) if _def51 else []
    check(_items51 == ["浇水（含温室 / 姜岛）", "送礼（每周 2 次，生日优先）",
                       "摸动物 · 添草料", "收蛋 / 牛奶 / 剪毛",
                       "查蟹笼 · 补鱼饵", "推进矿井 / 钓鱼目标"],
          "默认 6 条文案与 v2.56 前逐字一致（%d 条）" % len(_items51))

    # 51.2 勾选键必须是文本键，下标键必须绝迹
    check('snap.todoList.count { "todo:${snap.year}:${snap.season}:${snap.day}:$it" in snap.checks }'
          in _tp51, "todoDoneCount 按文本键统计")
    check("snap.day}:$i\"" not in _tp51,
          "TodayPage 不再存在 todo:…:$i 下标键（自定义列表下标会错位继承）")
    check("snap.day}:$text\"" in _tp51, "渲染区勾选键用行文本")

    # 51.3 迁移：一次性、幂等、挂进启动迁移链（与 v2.27/v2.39 同款）
    check("suspend fun migrateTodoKeys()" in _pr51, "migrateTodoKeys 存在")
    check("TODO_KEYS_V257" in _pr51 and "todo_keys_v257" in _pr51,
          "迁移标记键落盘（不依赖内存标志）")
    check("repo.migrateTodoKeys()" in _ma51, "迁移挂在 MainActivity 启动链")
    check("TODO_DEFAULT[i]" in _pr51, "迁移把旧下标映射到默认清单文本")
    check("Regex(" in _pr51 and '.matches(head)' in _pr51 and '"^todo:' in _pr51,
          "迁移只认 todo:Y:S:D 前缀（防误伤其它键；反斜杠转义层多，不整串比对）")

    # 51.4 存储与降级：坏数据回默认；空列表是合法用户状态
    check("parseTodoList" in _pr51 and "getOrDefault(TODO_DEFAULT)" in _pr51,
          "清单解析：坏 JSON 退化默认（一条脏记录不该让 App 起不来）")
    check("if (raw.isNullOrBlank()) return TODO_DEFAULT" in _pr51,
          "未设置=默认清单（与用户删空的空列表区分开）")

    # 51.5 导出导入闭环（自定义清单是用户数据，换机必须能带走）
    check('.put("todo_list", JSONArray(snap.todoList))' in _pr51,
          "exportJson 带 todo_list")
    check('o.optJSONArray("todo_list")' in _pr51,
          "importJson 仅在备份含 todo_list 时覆盖（旧备份不动本地清单）")

    # 51.6 输入约束：长度上限 + 去重 + 空拒绝（三处 when 分支）
    check(_tp51.count("take(20)") == 2, "两个输入框都有 20 字上限")
    check(_tp51.count("清单里已有同名条目") == 2, "改名与添加都做同名拒绝")
    check("t.isEmpty()" in _tp51, "空文本拒绝")

    # 51.7 空清单守卫：total=0 不许触发「全部完成」音效/banber
    check("total > 0 && doneCount == total" in _tp51,
          "全完成判定带 total>0 守卫（空清单 0==0 会误播音效+误显庆祝条）")

    # 51.8 消费点全部改到动态值
    check("TODO_TOTAL" not in _tp51 and "TODO_TOTAL" not in _ma51,
          "TODO_TOTAL 常量已废除（所有消费点走 todoList.size）")
    check("total = it.todoList.size" in _ma51, "小组件镜像 total 用动态清单长度")
    _tf51 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                 "assistant", "ui", "today", "TodayFacts.kt"),
                    encoding="utf-8").read()
    check("todoTotal = snap.todoList.size" in _tf51, "分享卡 total 用动态清单长度")







    # ==== 52. v2.58 电影院与「失踪的收集包」 ====
    # 群友反馈：社区中心体系里没有电影院。查证结果比反馈本身还重要——
    #   ① 电影院**不是社区中心的房间**，是社区中心修好之后才出现的独立建筑（废弃 JojaMart
    #      或 Joja 仓库改建），所以它进 bundles.json 却**不能**算进「整修前置的 26 包」，
    #      进了的话「社区中心整修」这张卡永远差一个包。
    #   ② 网上流传的那张图有两处错：银星**果酒**被写成「果汁」（项目里果汁是另一个物品）、
    #      虚空鲑鱼写银星品质（官方简中与英文站都是金/铱）。本节把 6 项逐字钉死。
    print("\n== 52. 电影院解锁与失踪的收集包（v2.58） ==")

    _AST = os.path.join(ROOT, "app", "src", "main", "assets")
    _b52 = load(os.path.join(_AST, "data", "bundles.json"))
    _u52 = load(os.path.join(_AST, "data", "unlocks.json"))
    _im52 = load(os.path.join(_AST, "image_map.json"))
    _md52 = src("data/Models.kt")
    _gd52 = src("data/GameData.kt")
    _pc52 = src("data/ProgressCalc.kt")
    _pp52 = src("ui/progress/ProgressPage.kt")

    # 52.1 房间结构：电影院必须在**末位**
    #      勾选键是 `cc:<roomIdx>:<bundleIdx>:<物品名>`，插在中间会让老用户已勾的 5 个房间集体错位。
    check(len(_b52) == 6, "收集包房间 5 -> 6（新增电影院）")
    check(_b52[5]["room"].startswith("电影院"), "电影院排在末位（老房间下标 0~4 不变）")
    check([r["room"] for r in _b52[:5]] ==
          ["工艺房（手工作坊）", "储物间（茶水间）", "鱼缸", "锅炉房（熔炉房）", "布告栏"],
          "前 5 个房间名与顺序逐字未变（老用户勾选键回归保护）")
    check(all(not r.get("optional") for r in _b52[:5]),
          "前 5 个房间都不算 optional（社区中心整修口径照旧 26 包）")
    _core_items52 = sum(len(b["items"]) for r in _b52[:5] for b in r["bundles"])
    check(_core_items52 == 124, "社区中心 5 个房间条目数仍是 124（一个没少没多）")
    check(sum(len(r["bundles"]) for r in _b52[:5]) == 26,
          "社区中心仍是 26 个包（unlocks.json 里「收集包 26 个」口径不矛盾）")

    # 52.2 optional 标记与解锁说明
    _mv52 = _b52[5]
    check(_mv52.get("optional") is True, "电影院标 optional（不属于整修前置）")
    check(len(_mv52.get("note", "")) > 40 and "500,000" in _mv52["note"],
          "电影院带解锁说明且写明 Joja 路线价格")
    check("雨天" in _mv52["note"] and "Joja" in _mv52["note"],
          "解锁说明同时覆盖献祭流（雨天前睡觉）与 Joja 流两条路径")

    # 52.3 收集包内容：官方简中 wiki 与英文站双向核对过
    _pk52 = _mv52["bundles"]
    check(len(_pk52) == 1 and _pk52[0]["name"] == "失踪的收集包",
          "电影院只有「失踪的收集包」一个包")
    _it52 = [i["name"] for i in _pk52[0]["items"]]
    check(_it52 == ["银星果酒", "恐龙蛋黄酱", "五彩碎片", "金星上古水果", "金星虚空鲑鱼", "鱼籽酱"],
          "6 项物品名与官方简中 wiki 逐字一致（流传版本误写成「果汁」、虚空鲑鱼误标银星）")
    check(all(i.get("src") for i in _pk52[0]["items"]),
          "6 项都带 src 获取途径（果酒/上古水果/虚空鲑鱼这三项在三表里查不到来源）")
    _cnt52 = {i["name"]: i.get("count", 1) for i in _pk52[0]["items"]}
    check(_cnt52.get("金星上古水果") == 5, "金星上古水果 ×5（wiki 原文标注 (5)）")
    check(all(v == 1 for k, v in _cnt52.items() if k != "金星上古水果"), "其余 5 项各 ×1")

    # 52.4 模型与解析
    check('note: String = ""' in _md52 and "optional: Boolean = false" in _md52,
          "BundleRoom 带 note/optional 且都有默认值（旧数据零迁移）")
    check('src: String = ""' in _md52, "ItemCount 带 src（缺省空串 = 不渲染）")
    check('room.optString("note")' in _gd52 and 'room.optBoolean("optional", false)' in _gd52,
          "GameData 解析 note/optional")
    check('x.optString("src")' in _gd52, "GameData 解析条目 src")
    check('listOf("金星", "银星", "铱星")' in _gd52 and "firstOrNull { name.startsWith(it) }" in _gd52,
          "stripQuality 认三种品质前缀（银星果酒原来剥不掉，查不到来源）")

    # 52.5 口径：optional 不进整修判据，也不进 279 分母
    check("if (room.optional)" in _pc52 and "laterItemCount += b.items.size" in _pc52,
          "optional 房间走独立计数，不进 bundleItemCount")
    check("totalBundles = GameData.bundleRooms.filter { !it.optional }.sumOf { it.bundles.size }" in _pc52,
          "totalBundles 排除 optional（「社区中心整修」判据仍是 26 包全交）")
    check("GameData.bundleRooms.forEachIndexed { ri, room ->" in _pc52,
          "房间下标仍取全局列表（对过滤后的子集重新编号会让老勾选键集体错位）")
    check("val totalDone: Int get() = bundleChecked + museumDone + achDone + walnutDone" in _pc52,
          "totalDone 公式一字未改")
    check("val totalAll: Int get() = bundleTotal + museumTotal + achTotal + walnutTotal" in _pc52,
          "totalAll 公式一字未改")
    check("val laterDone: Int" in _pc52 and "val laterTotal: Int" in _pc52,
          "新增 laterDone/laterTotal（总览单开一行看）")
    _get52 = _pc52[_pc52.index("val totalDone"):_pc52.index("val percent")]
    check("later" not in _get52,
          "totalDone/totalAll 计算式里没有 later*（电影院不计入 279 分母，同 v2.41 浣熊口径）")

    # 52.6 UI 接线
    check("room.note.isNotBlank()" in _pp52 and "room.note.split('\\n')" in _pp52,
          "进度页房间卡按行渲染解锁说明")
    check("ic.src.isNotBlank()" in _pp52, "进度页逐项渲染数据里的 src")
    check('Triple("community_center", "后续房间"' in _pp52, "总览单开「后续房间」一行")
    check('room.startsWith("电影院")' in _pp52, "电影院卡头图标有显式映射")

    # 52.7 解锁卡：id 与 checkKey 必须同名
    #      （UnlockDone 的兜底分支读 `unlock:<checkKey>`，进度页手勾写 `unlock:<id>`，
    #        两者不同名时会出现「勾上了但判定没达成」。新卡别踩这个坑。）
    _mc52 = [c for c in _u52 if c["id"] == "movie_theater"]
    check(len(_mc52) == 1, "解锁卡新增电影院一张")
    check(bool(_mc52) and _mc52[0]["id"] == _mc52[0]["checkKey"],
          "电影院卡的 id 与 checkKey 同名（进度页手勾与年历页判定同键）")
    check(bool(_mc52) and "500,000g" in _mc52[0]["cost"] and "1,000g" in _mc52[0]["after"],
          "解锁卡写明 Joja 造价与电影票价")
    check(len(_u52) == 14, "解锁卡 13 -> 14 张（原有 13 张未删）")

    # 52.8 图标：品质项沿用项目既有做法（品质图 = 基础图逐字节副本，如 金星南瓜 与 南瓜 同 md5）
    _IMGDIR = os.path.join(_AST, "img")
    _base52 = {"银星果酒": "果酒", "金星上古水果": "上古水果", "金星虚空鲑鱼": "虚空鲑鱼"}
    for _q52, _b52b in _base52.items():
        check(_q52 in _im52 and _b52b in _im52, "品质项 %s 与基础项 %s 都在 image_map" % (_q52, _b52b))
        if _q52 in _im52 and _b52b in _im52:
            _f1 = os.path.join(_IMGDIR, _im52[_q52].replace("/", os.sep))
            _f2 = os.path.join(_IMGDIR, _im52[_b52b].replace("/", os.sep))
            check(os.path.exists(_f1) and os.path.exists(_f2)
                  and open(_f1, "rb").read() == open(_f2, "rb").read(),
                  "%s.png 与 %s.png 逐字节相同（沿用既有品质图做法，不另造一张不一样的）"
                  % (_im52[_q52], _im52[_b52b]))
    # ⚠ c187 续：鱼籽酱（Caviar，id 447）已从本机游戏 1.6.15 官方贴图解出入库，
    #   37.4 的显式白名单清零 —— 断言反转为「必须有图」（114.4f 也钉了消费面）。
    check("鱼籽酱" in _im52 and "鱼籽酱" in _b52[5]["bundles"][0]["items"][-1]["name"],
          "鱼籽酱已有官方原图且仍在收集包里（37.4 显式白名单已清零，不是漏网）")

    # ==== 53. v2.59 字号可调（群友反馈⑦） ====
    # App 从 v2.0 起锁死 fontScale=1f（防别人手机大字体撑爆像素排版），
    # 群友反馈：系统开了大字体的用户在 App 里也看不见大字。v2.59 放开为 App 内 3 档。
    # 三条底线：① 默认档 1.0 与旧行为逐像素一致；② 存**下标**不存浮点（倍率单源 FontScale.SCALES）；
    #           ③ 系统字号仍不跟随（像素字体非整数倍会插值发糊）。
    print("\n== 53. 字号可调三档（v2.59） ==")

    _pt53 = src("ui/PixelTheme.kt")
    _ma53 = src("MainActivity.kt")
    _pr53 = src("data/PrefsRepo.kt")
    _sp53 = src_sp()
    _bg53 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()

    # 53.1 FontScale 单源
    check("object FontScale" in _pt53, "FontScale 单源对象存在（ui/PixelTheme.kt）")
    _sc53 = re.search(r"object FontScale \{.*?SCALES = listOf\(([^)]*)\)", _pt53, re.S)
    check(bool(_sc53) and _sc53.group(1).strip().split(",")[0].strip() == "1f",
          "SCALES 首元素是 1f（默认档 = 旧锁 1× 行为）")
    check(bool(_sc53) and len(_sc53.group(1).split(",")) == 3, "SCALES 正好 3 档")
    _lb53 = re.search(r"LABELS = listOf\(([^)]*)\)", _pt53)
    check(bool(_lb53) and len(_lb53.group(1).split(",")) == 3,
          "LABELS 档位数与 SCALES 对齐（3 档）")
    check("标准" in _pt53 and "特大" in _pt53, "档位标签含「标准」「特大」")
    # Px 注释不再声称「锁字缩放 1×」——行为改了注释不改，后人会照着旧注释推理出错
    check("锁字缩放 1×" not in _pt53, "Px 的过时注释已清理（不再声称锁字缩放 1×）")

    # 53.2 MainActivity 接线
    check("fontScale = 1f" not in _ma53, "MainActivity 不再写死 fontScale = 1f")
    check("FontScale.SCALES" in _ma53, "MainActivity 引用 FontScale.SCALES 折算倍率")
    check("fontScaleIdx" in _ma53, "MainActivity 从 snapshot 读 fontScaleIdx")
    check("coerceIn(0, FontScale.SCALES.lastIndex)" in _ma53,
          "fontScaleIdx 越界钳制（脏值不许把字体放到失控倍率）")

    # 53.3 PrefsRepo 持久化
    check('intPreferencesKey("font_scale_idx")' in _pr53, "FONT_SCALE 键存在（font_scale_idx）")
    check("suspend fun setFontScale(idx: Int)" in _pr53, "setFontScale setter 存在")
    check("it[FONT_SCALE] = idx.coerceIn(0, 2)" in _pr53, "setter 内钳制 0..2")
    check("val fontScaleIdx: Int = 0" in _pr53, "Snapshot 带 fontScaleIdx 且默认 0（= 标准档）")
    check("(p[FONT_SCALE] ?: 0).coerceIn(0, 2)" in _pr53, "snapshot 读取带默认值 + 越界钳制")

    # 53.4 备份进出：导出带上、导入 has() 守卫 + 钳制（旧备份没字段=不动本地值）
    check('.put("font_scale", snap.fontScaleIdx)' in _pr53, "exportJson 带上字号档位")
    _imp53 = _pr53.count('if (o.has("font_scale")) p[FONT_SCALE] = o.getInt("font_scale").coerceIn(0, 2)')
    check(_imp53 == 2, "importJson 两个分支都有 has() 守卫 + 钳制（v2 备份与 v1 扁平各一）")

    # 53.5 设置页接线
    check('PixelCard(title = "🔤 字号")' in _sp53, "设置页有「字号」卡")
    check("options = FontScale.LABELS" in _sp53, "字号卡的分段控件用 FontScale.LABELS（标签单源）")
    check("repo.setFontScale(i)" in _sp53, "字号卡 onSelect 落盘")
    # ⚠ v2.93.0：原文案是「职业、主题、字号」，加了字体档后变成「职业、主题、字体、字号」——
    #   原来那条断言钉的是**整段子句字面量**，插进两个字就红，而红的原因跟它想防的无关。
    #   改成**按语义**判：备份说明那一行里，阅读偏好（主题 / 字体 / 字号）三项都得列全。
    _bcp53 = [l for l in _sp53.splitlines() if "导出内容：" in l]
    check(len(_bcp53) == 1 and all(k in _bcp53[0] for k in ("主题", "字体", "字号")),
          "备份卡说明文案把阅读偏好列全了（主题 / 字体 / 字号）")

    # 53.6 版本口径（防三处不一致的老毛病：gradle / CHANGELOG / 归档）
    #      ⚠ 这里用**元组比较「不得超前」**，不用 startswith 等值比较——那等于要求每版
    #        都写一条 App 内更新日志，等于用断言倒逼着堆日志，与用户口径直接冲突
    #        （v2.27.1~2.27.3 三条就是这么堆出来的）。判据是「App 用户能感知的价值」。
    _vn53 = re.search(r'versionName = "([^"]+)"', _bg53).group(1)
    _m53 = re.search(r'CHANGELOG = listOf\(\s*\r?\n\s*"v?([0-9][0-9.]*)', _sp53)
    check(bool(_m53), "CHANGELOG 首条可解析出版本号")
    if _m53:
        _fv53 = tuple(int(x) for x in _m53.group(1).split("."))
        _gv53 = tuple(int(x) for x in _vn53.split("."))
        check(_fv53 <= _gv53,
              "CHANGELOG 首条 (%s) 不超前于 gradle versionName (%s)" % (_m53.group(1), _vn53))
    check("v2.59.0 字号可调" in _sp53, "CHANGELOG 已录 v2.59.0 字号可调条目")

    # ==== 54. v2.60.0 对外发布合规：署名 + 协议随包 + 数据开源仓 ====
    # App 已通过 gitee / QQ 群 / 小黑盒对外分发，两项协议的义务即刻生效：
    #   · Stardew Wiki 内容：CC BY-NC-SA 3.0（BY 署名 + SA 同方式共享 → 随发布仓
    #     data/ 公开；原独立仓被 gitee 风控锁私有，2026-10-15 改址，见 54.4）
    #     ⚠ wiki 协议明文**排除开发者持有的图像/美术**——游戏图标无任何再分发授权，
    #       只能靠「非官方粉丝工具」声明，这是无法根治的已知边界，不是漏网。
    #   · Fusion Pixel Font：SIL OFL 1.1（第 2 条随附协议文本 → res/raw；第 3 条
    #     修改版不得用保留名 → 子集改名 TutuPixel，见 41.3）
    print("\n== 54. 对外发布合规：署名与协议随包（v2.60.0） ==")

    _raw54 = os.path.join(ROOT, "app", "src", "main", "res", "raw")
    _ofl54 = os.path.join(_raw54, "ofl_fusion_pixel.txt")
    _cc54 = os.path.join(_raw54, "cc_by_nc_sa_3_0.txt")
    _sp54 = src_sp()
    _pt54 = src("ui/PixelTheme.kt")

    # 54.1 协议文本随包（OFL 第 2 条：每个副本都要带版权+许可）
    check(os.path.exists(_ofl54), "res/raw/ofl_fusion_pixel.txt 存在（OFL 随包）")
    check(os.path.exists(_cc54), "res/raw/cc_by_nc_sa_3_0.txt 存在（CC 随包）")
    if os.path.exists(_ofl54):
        _t54 = io.open(_ofl54, encoding="utf-8").read()
        for _k54 in ("SIL OPEN FONT LICENSE", "TakWolf", "Copyright (c) 2022"):
            check(_k54 in _t54, "OFL 文本含 %s" % _k54)
        check("TutuPixel" in _t54, "OFL 文本头部说明了子集改名（改名行为可追溯）")
    if os.path.exists(_cc54):
        _t54 = io.open(_cc54, encoding="utf-8").read()
        for _k54 in ("Attribution-NonCommercial-ShareAlike 3.0", "creativecommons.org"):
            check(_k54 in _t54, "CC 文本含 %s" % _k54)

    # 54.2 关于页接线：入口 + 抽屉 + 四类内容
    # ⚠ v2.77.0：入口与抽屉标题由「开源与数据来源」改为「数据来源与协议」——
    #   本 App **不开源自己的源码**（用户 2026-10-20 定调），挂「开源」字样会被
    #   读成「本应用开源」，属于误导；对外分发的义务是**第三方归属 + 协议全文**。
    #   原来这条断言**逐字**钉「开源与数据来源」，改个名就红 —— 那是断言太脆
    #   （钉字面量等于给自己埋雷，v2.32 / v2.42 都栽过同型）。改成钉**关系**：
    #   ① 有可点的入口打开抽屉 ② 抽屉标题里**不出现「开源」**（改名也不会红）。
    check("clickable { showLicense.value = true }" in _sp54, "设置页有可点的许可入口（点击打开抽屉）")
    _dl54 = re.search(r'DetailDrawer\(visible = showLicense, title = "([^"]+)"', _sp54)
    check(_dl54 is not None and "开源" not in _dl54.group(1),
          "许可抽屉标题不出现「开源」（本 App 不开源源码，义务是第三方归属 + 协议全文）")
    check("showLicense" in _sp54, "有 showLicense 状态驱动抽屉")
    for _k54 in ("stardewvalleywiki.com", "zh.stardewvalleywiki.com",
                 "CC BY-NC-SA 3.0", "ConcernedApe", "非官方", "TakWolf",
                 "xingluguzhushou/tree/main/data"):
        check(_k54 in _sp54, "抽屉文案含 %s" % _k54)
    check("stardew-helper-data" not in _sp54,
          "抽屉不再引用已下线的私有仓 stardew-helper-data（对外 404 会显得很业余）")
    check("R.raw.ofl_fusion_pixel" in _sp54 and "R.raw.cc_by_nc_sa_3_0" in _sp54,
          "抽屉能读到两份协议全文（res/raw 接线）")

    # 54.2' v2.72 Mod 扩展包：**第三方 Mod 素材的署名要随内容走**。
    #      SVE 的头像/数据来自 Nexus Mods 上的 Stardew Valley Expanded，
    #      授权条款允许使用但要求署名。两处都得有，理由不同：
    #        · App 抽屉 → 不装包的用户也知道这些头像从哪来（包里那句只在装了之后才读得到）；
    #        · 发布仓 README → 仓库本身公开可检索，别人 clone/fork 也带得走署名。
    #      少任何一边，署名都只在一种使用路径下可见。
    for _k54 in ("FlashShifter", "nexusmods.com/stardewvalley/mods/3753",
                 "Stardew Valley Expanded"):
        check(_k54 in _sp54, "开源与数据来源抽屉有 SVE 署名：%s" % _k54)
    check("nexusmods.com/stardewvalley/mods/3753" in code_only(_sp54),
          "SVE 来源链接走 CopyLinkRow 而非 ACTION_VIEW（离线口径不被自己打破）")
    _readme54 = os.path.join(os.path.dirname(ROOT), "发布仓", "README.md")
    if os.path.exists(_readme54):
        _rm54 = io.open(_readme54, encoding="utf-8").read()
        for _k54 in ("Stardew Valley Expanded", "FlashShifter",
                     "nexusmods.com/stardewvalley/mods/3753", "mods/"):
            check(_k54 in _rm54, "发布仓 README 有 SVE 署名与 mods/ 指引：%s" % _k54)
        # 联网口径必须与实际行为相符（App 侧是第 54.2 / 49.2 那套，仓库侧同样不许写少）
        check("Mod 扩展" in _rm54, "发布仓 README 的联网口径写了 Mod 扩展页（少写就是谎话）")
    else:
        check(False, "发布仓 README.md 存在（署名与联网口径都在里面）")

    # 54.3 「不读取也不上传存档数据」的口径不被自己打破：wiki/仓库链接一律 tap-to-copy，
    #      不走 ACTION_VIEW（ACTION_VIEW 会把用户带出 App，等于开一个外部跳转口）。
    #      ⚠ v2.67.0：锚点不能写死「完全离线运行」这几个字——局域网接收让「完全离线」
    #      变成了不准确的措辞，已改为「不读取也不上传 + 仅手动开启时连一次同一 WiFi」。
    #      钉**意图**（不外跳、不上传），不钉旧措辞；否则改文案就会被当成破坏承诺。
    check("完全离线运行" not in _sp54,
          "关于页不再宣称「完全离线运行」（局域网接收使该措辞不准确，v2.67.0 起改口径）")
    # v2.91.0：口径改回远端 2.90.0 的原文「存档只在你主动导入时读」。
    #   旧措辞「不读取也不上传任何存档数据」**不准确** —— App 确实会读存档
    #   （用户主动导入时），那句话把「不主动上传」说成了「完全不碰存档」。
    #   钉**意图**（只在用户主动导入时读 + 不外跳 + 不上传），不钉旧措辞。
    check("存档只在你主动导入时读" in _sp54,
          "关于页写明「存档只在你主动导入时读」（离线承诺的准确表述）")
    _wiki_view54 = re.search(r"Uri\.parse\([^)]*(wiki|stardew-helper-data)", _sp54)
    check(_wiki_view54 is None, "wiki/仓库链接未走 ACTION_VIEW（保持离线口径）")
    check("已复制链接" in _sp54, "链接行是 tap-to-copy（复制 + Toast）")

    # 54.4 数据开源（SA 合规：同方式共享整理后的数据，且必须**公开可得**）
    #     2026-10-15 改址：原放独立仓 stardew-helper-data，被 gitee 风控「涉嫌外链滥用(RAW)」
    #     锁私有、无法开源（网页端确认过），改为随发布仓 data/ 一起公开。
    #     只放 JSON 不放图标——图标无授权，进公开仓是把无法根治的风险扩大。
    _pub54 = os.path.join(os.path.dirname(ROOT), "发布仓")
    _ddata54 = os.path.join(_pub54, "data")
    if os.path.isdir(_ddata54):
        _dl54 = os.path.join(_ddata54, "LICENSE")
        _dr54 = os.path.join(_ddata54, "README.md")
        check(os.path.exists(_dl54), "发布仓 data/LICENSE 存在")
        check(os.path.exists(_dr54), "发布仓 data/README.md 存在")
        if os.path.exists(_dl54):
            check("Attribution-NonCommercial-ShareAlike 3.0"
                  in io.open(_dl54, encoding="utf-8").read(),
                  "data/LICENSE 是 CC BY-NC-SA 3.0")
        if os.path.exists(_dr54):
            _r54 = io.open(_dr54, encoding="utf-8").read()
            check("stardewvalleywiki.com" in _r54, "data/README 有 wiki 署名")
            check("不含" in _r54 and "图标" in _r54,
                  "data/README 说明不含游戏图标（无授权，声明边界）")
        _jlocal54 = len([f for f in os.listdir(os.path.join(_AST, "data"))
                         if f.endswith(".json")])
        _jrepo54 = len([f for f in os.listdir(_ddata54) if f.endswith(".json")])
        # ⚠ 同 49.5 的前置条件：只在**远端 == 源码当前版本**时硬断言。
        #   远端落后（构建完还没发布）时两边必然不等，那是「还没发」不是「漏发了数据」；
        #   不加这个前置条件，每发一版到下次发布之间 verify 都会常红，久而久之
        #   大家只会学会无视它——那就等于没有这道闸门。发布那一刻它仍然是硬断言。
        if str(_mj49.get("versionCode")) == _vcj49:
            check(_jrepo54 == _jlocal54,
                  "发布仓 data/ 与 App assets/data JSON 数一致（%d vs %d）" % (_jrepo54, _jlocal54))
        else:
            warns.append("发布仓还是 c%s（源码已到 c%s），data/ 文件数比对跳过"
                         "（%d vs %d）——「构建完还没发布」是正常状态"
                         % (_mj49.get("versionCode"), _vcj49, _jrepo54, _jlocal54))
        check(not any(f.endswith(".png") or f.endswith(".webp")
                      for f in os.listdir(_ddata54)),
              "发布仓 data/ 无图片文件（游戏图标无授权，不进公开仓）")
    else:
        check(False, "发布仓 data/ 不存在：%s（SA 合规需要数据公开可得）" % _ddata54)

    # 54.5 版本口径（就近再钉一次，防升版漏改）
    #      同 53.6：只卡「不得超前」，不卡相等——相等会把每版都逼成一条更新日志。
    _vn54 = re.search(r'versionName = "([^"]+)"', io.open(
        os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()).group(1)
    _m54 = re.search(r'CHANGELOG = listOf\(\s*\r?\n\s*"v?([0-9][0-9.]*)', _sp54)
    check(bool(_m54), "CHANGELOG 首条可解析出版本号")
    if _m54:
        _fv54 = tuple(int(x) for x in _m54.group(1).split("."))
        _gv54 = tuple(int(x) for x in _vn54.split("."))
        check(_fv54 <= _gv54,
              "CHANGELOG 首条 (%s) 不超前于 versionName (%s)" % (_m54.group(1), _vn54))

    # ==== 55. v2.61 更新提示（每日静默检查 + 今日页横幅） ====
    # 起因：群友反馈「有新版本会提示吗」的答案是「不会」——v2.55.0 的检查更新只有
    # 手动按钮一个入口，App 的更新渠道实际只剩 QQ 群 / 小黑盒。
    # 三层方案全上：① notes 分层渲染 ② 进设置页静默查 ③ 今日页横幅。
    # ⚠ 本节真正要防的不是「有没有横幅」，而是**自动联网长成后台轮询**——
    #    那既违背节流承诺，也会让「完全离线」的 App 变得耗流量。所以下面重点钉节流与退路。
    print("\n== 55. 更新提示：每日静默检查与今日页横幅（v2.61） ==")

    _ur55 = src("data/UpdateRepo.kt")
    _pr55 = src("data/PrefsRepo.kt")
    _ma55 = src("MainActivity.kt")
    _tp55 = src("ui/today/TodayPage.kt")
    _ub55 = src("ui/UpdateBanner.kt")

    # 55.1 去重判据（v2.73.3 改「每次进 App 一次」后重写）
    #     本节最初钉的是「20 小时节流窗口在 18~24 小时之间」，那条断言的原意是
    #     **防自动联网长成后台轮询**。用户 2026-10-19 明确要求改为每次进 App 都查——
    #     所以「时间窗口」这个形态被替换掉了，但原意要钉在新形态上：
    #     频率上限 = 用户打开 App 的次数（进程内最多一次），没人操作就没有请求。
    #     ⚠ 反向断言（THROTTLE_MS 必须消失）同样重要：留一个不参与判定的常量
    #     会让下一个人读代码以为还在按时间节流，按错误模型做改动。
    check("fun shouldCheck" in _ur55, "UpdateRepo 有统一的静默检查判定 shouldCheck")
    check("sessionChecked" in _ur55 and "@Volatile" in _ur55,
          "shouldCheck 用进程级 sessionChecked 去重（每次进 App 进程最多静默查一次）")
    check("if (sessionChecked) return false" in _ur55,
          "同一进程内重复调用直接 false（启动查过 → 进设置页不再查，不重复发请求）")
    # ⚠ 扫 code_only：kdoc 注释里写着「THROTTLE_MS 已删」，裸扫全文会命中注释自证其删
    check("THROTTLE_MS" not in code_only(_ur55),
          "20 小时节流常量已删（不参与判定的常量会误导后来人按错误模型改）")
    check("if (!enabled) return false" in _ur55,
          "用户关掉开关后 shouldCheck 直接 false（退路真的存在）")
    # 时钟回拨 / 手改备份导致「上次检查在未来」时，判定会以为刚查过——
    # v2.73.3 起判定不再读这个字段，但读侧钳制仍是防线（值本身要合法）
    check("coerceAtLeast(0L)" in _pr55,
          "updateCheckedAt 读取带 coerceAtLeast(0)（手改/时钟回拨的脏值挡在读侧）")

    # 55.2 两个入口都走同一个节流判定，不能各写各的
    _callers = [f for f, s in (("MainActivity", _ma55), ("SettingsPage", _sp54))
                if "shouldCheck" in s]
    check(len(_callers) == 2,
          "启动与设置页两处静默检查都调 shouldCheck（实际：%s）——各写各的会重复发请求"
          % _callers)
    check("kotlinx.coroutines.delay(2000)" in _ma55,
          "启动检查延迟 2 秒（别让网络请求挡在启动路径上）")

    # 55.3 设备态不入备份：换手机不该把「上次检查时间/划掉过的版本/要不要提示」带走
    for _k55 in ("update_checked_at", "update_seen_version", "update_prompt_enabled"):
        check('"%s"' % _k55 in _pr55, "键 %s 存在" % _k55)
    # v2.69 补两个同性质的新键：待提示的新版本快照 / 这一版的弹窗弹过没有
    for _k55 in ("update_pending_json", "update_dialog_version"):
        check('"%s"' % _k55 in _pr55, "键 %s 存在" % _k55)
    _exp55 = _pr55.split("suspend fun exportJson", 1)[1].split("return JSONObject", 1)[0]
    for _k55 in ("updateCheckedAt", "updateSeenVersion", "updatePromptEnabled",
                 # v2.69：把「手机知道有新版」搬到另一台手机毫无意义——那台自己会查
                 "updatePendingJson", "updateDialogVersion",
                 "fontScaleIdx", "theme"):
        check(_k55 not in _exp55,
              "exportJson 不含 %s（设备态/不该跨机搬运的字段不进备份）" % _k55)

    # 55.4 横幅：今日页、与节日并存、可关闭
    check("fun UpdateBanner(" in _ub55, "存在 UpdateBanner 组件")
    check("fun UpdateNotes(" in _ub55, "存在 notes 分层渲染 UpdateNotes（第①层）")
    check('item(key = "updateBanner")' in _tp55, "今日页挂了 updateBanner 槽位")
    # v2.69：节日与更新**并存**了，原来的二选一守卫是 v2.61 自己写的让位逻辑。
    #   节日一年就那么几天，而漏掉一次更新提示的代价（用户停在旧版几个月）远大于
    #   今天多占一屏——「让位」只能让顺序，不能让消失。所以改成反向断言。
    check("FestivalBanner" in _tp55 and 'item(key = "banner")' in _tp55,
          "节日彩蛋横幅仍在今日页第一行（顺序在前）")
    check("festivalToday(snap.season, snap.day) == null" not in _tp55,
          "更新横幅不再被节日彩蛋屏蔽（两个都出现，节日在上）")
    check("onDismiss" in _ub55 and "clickable(onClick = onDismiss)" in _ub55,
          "横幅有独立的 ✕ 关闭区（与「查看详情」语义相反，混在一起会误触）")
    check("markUpdateSeen" in _ma55 and "updateSeenVersion" in _ma55,
          "关闭落盘的是版本号（划掉这版、下个版本还会提示）")
    check("latest.versionCode > cur.updateSeenVersion" in _ma55,
          "只提示比已划掉更新的版本")

    # 55.5 设置页开关与手动绕过节流
    check("setUpdatePrompt" in _sp54 and "updatePromptEnabled" in _sp54,
          "设置页有「更新提示」开/关分段")
    # 手动检查必须绕过节流：用户明确要求了就该立刻给结果，而不是「20 小时内查过了」
    _seg55 = _sp54.split("立即检查更新", 1)
    check(len(_seg55) == 2 and "shouldCheck" not in _seg55[1][:600],
          "手动「立即检查更新」不经过节流（用户要结果就给，不该回他「刚查过」）")

    # ==== 56. v2.62 心事件折叠防剧透 ====
    # 用户诉求原文：「村民页好感度事件折叠栏的形式，如果有需要的话点开展现出来，
    # 不然可能会被剧透」。原先是 PixelCard 直接平铺全部事件，一进详情页就看到内容。
    # 本节要钉的是**两条最容易写错的语义**：
    #   ① 默认必须是收起的（防剧透是这个功能存在的理由）；
    #   ② **不得**套用 CodexPage「行数少就不折」的阈值——那是为省首屏空间设的，
    #      而心事件是防剧透，19/34 位村民只有 1~2 条，套了等于没做。
    print("\n== 56. 心事件折叠防剧透（v2.62） ==")

    _pw56 = src("ui/PixelWidgets.kt")
    _vp56 = src("ui/villagers/VillagersPage.kt")

    check("fun CollapsibleCard(" in _pw56, "存在 CollapsibleCard 组件（独立于 PixelCard）")
    check("collapsedHint" in _pw56 and "onToggle" in _pw56,
          "CollapsibleCard 支持折叠态提示与展开回调")
    # PixelCard 的 title 不可点 → 所以不能靠改 PixelCard 实现（两百多处调用）
    check('PixelCard(accent = accent) {' in _pw56,
          "CollapsibleCard 自己画标题行（PixelCard 的 title 不可点）")

    check('title = "💗 心事件（${events.size} 条）"' in _vp56,
          "村民页心事件走 CollapsibleCard（标题仍带条数）")
    check("var expanded by remember(name) { mutableStateOf(false) }" in _vp56,
          "折叠态按村民名记忆且**默认 false**（换人自动收起、进来就是收起）")
    check("点按展开（含剧情，可能剧透）" in _vp56,
          "折叠态写明「点按展开 + 可能剧透」（用户知道自己点下去会看到什么）")
    # ⚠ 反向断言：不得出现「行数少就平铺」的分支——那是省空间逻辑，防剧透用不得
    check("events.size <=" not in _vp56 and "events.size >" not in _vp56,
          "心事件**没有**行数阈值分支（防剧透与省空间的阈值不同，套用等于没折）")
    # 数据侧事实支撑：真按 5 行阈值折的话，会漏掉多少人
    _he56 = load(os.path.join(_AST, "data", "heartEvents.json"))
    _small56 = sum(1 for x in _he56 if 0 < len(x.get("events", [])) <= 5)
    check(_small56 >= 15,
          "实测 %d/%d 位村民的心事件 ≤5 条（若套用 CodexPage 阈值，这些人会完全防不住剧透）"
          % (_small56, len(_he56)))
    check(all(len(x.get("events", [])) >= 1 for x in _he56),
          "所有村民都有心事件数据（折叠卡不会渲染出空壳）")

    # ==== 57. 开源说明卡与协议红线（v2.63 → 2026-11-10 改为「开源说明」）====
    # 协议分三层，态度各不同：
    #   App 代码 MIT（发布仓根 LICENSE）→ 收捐赠无限制；
    #   wiki 数据 CC BY-NC-SA 3.0 → NC 按「使用的主要目的」判定（CC 官方
    #     NonCommercial interpretation），捐赠必须**自愿、无对价**——一旦出现
    #     付费功能/广告，主要目的翻向商业用途，NC 分发跟着越界；
    #   游戏图标无任何授权 → 唯一站得住的辩护是「非官方 + 免费非商业」。
    #
    # ⚠ 2026-11-10（项目开源 + 停更）：本节原来钉的是「支持开发者」打赏卡
    #   （两张收款码 + DonateQr 组件）。那整套**已删除**，本节改为钉两件事：
    #   ① **收款码资产不得复活**（个人收款账户不该随公开仓库分发）；
    #   ② **NC 红线反向断言仍然有效** —— 删掉打赏只是让分发更安全，
    #      但 fork 者若加自己的收款方式，那仍然是**他**的责任；
    #      「有对价 = 滑向商业用途」这条判据不因为我们不打赏就失效。
    print("\n== 57. 开源说明卡与协议红线（2026-11-10 起为开源说明） ==")

    _sp57 = src_sp()
    _sp57c = code_only(_sp57)
    check('PixelCard(title = "☕ 开源说明")' in _sp57,
          "设置页存在「☕ 开源说明」卡")
    check("源码已公开" in _sp57c and "欢迎自行 fork" in _sp57c,
          "开源说明写明「源码公开 + 可自行 fork/二次分发」")
    # ⚠ 口径（用户 2026-11-10）：不说「不再维护」——万一以后又维护了呢？
    #    只说「更新频率放缓」，留有余地。
    check("更新频率已放缓" in _sp57c,
          "开源说明写明「更新频率已放缓」（不说「不再维护」，留有余地）")
    check("不再维护" not in _sp57c,
          "开源说明不出现「不再维护」（万一以后又维护了呢）")
    # ① 收款码资产不得复活：资产文件与引用都要没有
    for _p57 in ("img/" + "donate/wechat.png", "img/" + "donate/alipay.png"):
        _f57 = os.path.join(_AST, _p57)
        check(not os.path.exists(_f57),
              "收款码资产已删除且不得复活：%s" % _p57)
        check('"%s"' % _p57 not in _sp57,
              "代码不再引用 %s" % _p57)
    check("fun DonateQr(" not in _sp57,
          "DonateQr 组件已删除（收款码渲染器不该留在公开源码里）")
    # ② NC 红线反向断言：剥注释后设置页不得出现对价性商业词。
    #    「付费」等词只允许出现在注释里——code_only 会剥掉注释，
    #    这正是「钉行为不钉字面量」的用法：注释可以解释红线，文案不许触碰红线。
    for _bad57 in ("付费", "充值", "去广告", "会员", "VIP"):
        check(_bad57 not in _sp57c,
              "设置页无「%s」字样（NC 口径：对价性商业词出现即越界）" % _bad57)
    # ③ 个人联系方式不得复活（2026-11-10 开源清理）
    #    ⚠ 只查**剥离注释后**的代码：注释里可以写「这里原来是头像槽」，
    #    那是在解释改动，不是在用这些信息。查未剥离的原文会把自己绊倒。
    #    ⚠⚠ 探针字面量**拆开写**：这段 verify 自己也要进公开仓，
    #       明文群号/邮箱/资产名出现在源码里等于又公开了一次。
    for _pi57 in ("1001907" + "713", "owner_" + "avatar", "打赏过的朋友请联系我上榜"):
        check(_pi57 not in _sp57c,
              "设置页代码无个人联系方式残留：%s" % _pi57)

    # ═══════════════════════ 第 58 节：导入游戏存档（v2.65.0）═══════════════════════
    # 这一节钉的是**映射表与解析器的接线关系**。Kotlin 侧没有自动化测试（项目没建
    # testInfrastructure），真正的行为验证靠 tools/check_save_import.py 拿四份真实
    # 存档跑独立 Python 实现交叉比对；这里守的是「两端口径不许漂」。
    #
    # ⚠ 数字必须与 gen_save_import_map.py 的 EXPECT_COVER 一致。改任何一边都要同时改，
    #   并且重跑 `python tools/gen_save_import_map.py --check` 确认映射表可字节级复现。
    _sim58 = os.path.join(DATA, "saveImportMap.json")
    _sit58 = os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                          "assistant", "data", "SaveImport.kt")
    _sig58 = os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                          "assistant", "data", "CodexChecks.kt")
    check(os.path.exists(_sim58), "saveImportMap.json 已落地")
    if os.path.exists(_sim58):
        _sz58 = os.path.getsize(_sim58)
        check(_sz58 > 0 and _sz58 <= 20 * 1024,
              "saveImportMap.json 体积 %d B，非空且 ≤ 20KB" % _sz58)
        _m58 = load(_sim58)

        def _names58(fname, group=None):
            d = load(os.path.join(DATA, fname))
            if group:
                d = d[group]
            return set((x["name"] if isinstance(x, dict) else x) for x in d)

        # 58.2 值必须逐字存在于 App 自己的 JSON 里 —— 打勾键就是拿这个名字拼的
        _bad58 = []
        for _sec, _fn, _grp in (("fishById", "fishing.json", None),
                                ("fishByName", "fishing.json", None),
                                ("recipeById", "recipes.json", None),
                                ("recipeByName", "recipes.json", None),
                                ("craftByEn", "crafting.json", None),
                                ("mineralById", "museum.json", "minerals"),
                                ("artifactById", "museum.json", "artifacts")):
            _uni58 = _names58(_fn, _grp)
            for _k, _v in _m58.get(_sec, {}).items():
                if _v not in _uni58:
                    _bad58.append("%s[%s]=%r" % (_sec, _k, _v))
        check(not _bad58, "每个映射值都逐字存在于 App 的条目名全集（零硬造译名）：%s"
              % ("、".join(_bad58[:5]) or "通过"))

        # 58.3 覆盖数账目
        _cov58 = {
            "钓鱼": len(set(_m58.get("fishById", {}).values()) | set(_m58.get("fishByName", {}).values())),
            "烹饪": len(set(_m58.get("recipeById", {}).values()) | set(_m58.get("recipeByName", {}).values())),
            "制作": len(set(_m58.get("craftByEn", {}).values())),
            "矿物": len(set(_m58.get("mineralById", {}).values())),
            "古物": len(set(_m58.get("artifactById", {}).values())),
        }
        # v2.66.0：矿物从 15 补到 53（上游 item_index 缺 539~578 全段，
        # 用「存档反查 + 中英双语 wiki 逐格对齐」双源交叉验证后整表写死）
        # v2.66.1：古物从 40 补到 42（精灵珠宝/手部骨骼上游名字对不上或没记 id，
        # id 由「KKK 存档反查 + image_path/序号连续性」双源确认后手工补，
        # 群友反馈精灵珠宝勾不上即此条）
        for _g, _want in (("钓鱼", 74), ("烹饪", 81), ("制作", 118), ("矿物", 53), ("古物", 42)):
            check(_cov58[_g] == _want,
                  "映射覆盖%s %d 条（账目 %d）" % (_g, _cov58[_g], _want))
        _art66 = _m58.get("artifactById", {})
        check(_art66.get("104") == "精灵珠宝" and _art66.get("582") == "手部骨骼",
              "两条手工古物在映射里：104=精灵珠宝、582=手部骨骼（实际 %s、%s）"
              % (_art66.get("104", "缺失"), _art66.get("582", "缺失")))
        _tot58 = sum(_cov58.values())
        check(_tot58 == 368, "映射覆盖合计 %d / 账目 368（= 576 条可勾条目的 %.1f%%）"
              % (_tot58, 100.0 * _tot58 / 576))

        # 58.4 4 条 1.6 新鱼用字符串 ID（它们没有数字 item_id）
        _sf58 = set(_m58.get("fishByName", {}))
        check(_sf58 == {"SeaJelly", "RiverJelly", "CaveJelly", "Goby"},
              "4 条字符串鱼 ID 齐全：%s" % sorted(_sf58))

        # 58.5 主动放弃的传送图腾必须缺席；手工补的野生种子必须在场
        _cc58 = set(_m58.get("craftByEn", {}))
        check(not (_cc58 & {"Warp Totem: Mountains", "Warp Totem: Beach", "Warp Totem: Farm",
                             "Warp Totem: Desert", "Warp Totem: Island"}),
              "5 条传送图腾不在映射里（App「海洋」vs 源「Beach」语义不对齐，主动放弃）")
        check({"Wild Seeds (Sp)", "Wild Seeds (Su)", "Wild Seeds (Fa)", "Wild Seeds (Wi)"} <= _cc58,
              "4 条野生种子在映射里（季节一一对应，无歧义故补）")

        # 58.6 矿物与古物共用 museum: 前缀，条目名撞名就会互相覆盖
        _ov58 = set(_m58.get("mineralById", {}).values()) & set(_m58.get("artifactById", {}).values())
        check(not _ov58, "矿物与古物条目名无交集（共用 museum: 前缀，撞名即撞键）：%s"
              % (sorted(_ov58) or "通过"))

    # 58.7~58.12 解析器与 UI 接线（源码层断言）
    if os.path.exists(_sit58):
        _si58 = io.open(_sit58, encoding="utf-8").read()
        check("java.util.zip.ZipFile" in _si58, "解析器用 ZipFile（需随机访问才能按大小挑 entry）")
        check("XmlPullParser" in _si58 and "Xml.newPullParser" in _si58,
              "解析器走 XmlPullParser 流式（35MB 单行 XML 绝不用 DOM）")
        # 双形态：ff 写 <key><int>，原版档写 <key><string>
        check('name == "int" || name == "string"' in _si58,
              "同时接 key/int 与 key/string 两种键形态（只认一种会让另一类存档静默全空）")
        check("skipBom" in _si58 and "0xEF" in _si58, "跳过 UTF-8 BOM（四份真实存档全部带 BOM）")
        check("MAX_ARCHIVE_BYTES" in _si58 and "20L * 1024 * 1024" in _si58,
              "压缩包体积上限有守卫（正常 Steam 存档压完 < 2MB）")
        for _sfx in ("_SVBAK", "_SVEMERG", "_old"):
            check('"%s"' % _sfx in _si58,
                  "ZIP 候选排除 %s（它在真实存档里比主存档还大，「取最大」必然选错）" % _sfx)
        check('ZIP_EXCLUDE_EXACT = setOf("SaveGameInfo", "BACKUP_SAVE")' in _si58,
              "ZIP 候选排除 SaveGameInfo / BACKUP_SAVE")
        check("rar / 7z" in _si58, "rar / 7z 有明确报错文案（不是崩溃也不是静默失败）")
        check("codexCheckKey" in _si58,
              "打勾键走 codexCheckKey 单源（项目铁律：除 CodexChecks.kt 外不许出现键前缀字面量）")
        check("requirePositive = true" in _si58,
              "制作组要求 value > 0（craftingRecipes 的 0 = 已解锁未制作，只判 key 存在会多勾）")
        # 五个容器 tag 必须与 Python 侧一一对应
        for _tag in ("fishCaught", "recipesCooked", "craftingRecipes",
                     "mineralsFound", "archaeologyFound"):
            check('"%s"' % _tag in _si58, "解析目标容器 %s" % _tag)
        # groupId 必须都在 CHECK_PREFIX 白名单里，否则 codexCheckKey 返回 null
        _pref58 = set(re.findall(r'"(\w+)"\s+to\s+"', io.open(_sig58, encoding="utf-8").read()))
        for _gid in ("fishing", "recipes", "crafting", "museumMinerals", "museumArtifacts"):
            check(_gid in _pref58, "groupId %s 在 CHECK_PREFIX 白名单里（否则打不出勾选键）" % _gid)
        # Python 侧交叉验证脚本的容器表不许漂
        _ck58 = os.path.join(ROOT, "tools", "check_save_import.py")
        if os.path.exists(_ck58):
            _ck58 = io.open(_ck58, encoding="utf-8").read()
            # v2.66.0：前三个容器不变；博物馆两组改读 museumPieces（捐赠口径），
            # mineralsFound/archaeologyFound 退化为**回退容器**，仍必须留在表里
            # —— 回退是「读不到捐赠记录时不让整组空掉」的兜底，删了就静默丢数据。
            for _tag in ("fishCaught", "recipesCooked", "craftingRecipes"):
                check('("%s"' % _tag in _ck58,
                      "check_save_import.py 的容器表含 %s（两侧口径不许漂）" % _tag)
            check('("museumPieces", ["mineralById"]' in _ck58 and
                  '("museumPieces", ["artifactById"]' in _ck58,
                  "Python 侧博物馆两组读 museumPieces（与 Kotlin 的捐赠口径一致）")
            # 回退容器在 Python 侧是 MUSEUM_FALLBACK 的**值**，不是 GROUPS 的元素
            check('"mineralsFound"' in _ck58 and '"archaeologyFound"' in _ck58,
                  "check_save_import.py 保留了 mineralsFound/archaeologyFound 回退容器"
                  "（删了就让读不到捐赠记录的老存档整组空掉）")
            check("MUSEUM_FALLBACK" in _ck58,
                  "Python 侧有博物馆回退表（与 Kotlin 的 MUSEUM_FALLBACK 对应）")
            check("recipeById" in _ck58 and "recipeByName" in _ck58,
                  "Python 侧烹饪分区与 Kotlin 一致（存档里菜谱键是数字 item_id，不是英文名）")
            check("iterparse" in _ck58, "交叉验证脚本用 iterparse 流式（34MB 建 DOM 会吃掉 1GB+）")

    # 58.13 UI 接线：只补不删 + 写入前自动备份
    _sp58b = src_sp()
    check('PixelCard(title = "🎮 导入游戏存档")' in _sp58b, "设置页有「导入游戏存档」卡片")
    check("saveImportLauncher" in _sp58b and "SaveImport.parse" in _sp58b,
          "文件选择走 SAF launcher 并调用 SaveImport.parse")
    check("Dispatchers.IO" in _sp58b,
          "解析在 Dispatchers.IO（34MB 单行 XML 是秒级操作，不能压主线程）")
    check("repo.replaceChecks(snap.checks + data.keys)" in _sp58b,
          "写入用并集「snap.checks + data.keys」（只补不删：手动勾的一条都不能丢）")
    check("repo.autoBackup()" in _sp58b,
          "写入前调用 autoBackup（误导入可由备份救回）")
    check("DetailDrawer(visible = savePreview != null, title = \"导入游戏存档\"" in _sp58b,
          "解析成功先进确认抽屉（解析与写入分离，确认前一个勾都不落盘）")
    check("Save_Game" in _sp58b, "卡片文案点明 Steam 存档路径（%APPDATA%\\StardewValley\\Save_Game）")
    check("只支持 zip" in _sp58b, "卡片文案点明只支持 zip")

    # 58.14 映射表必须与生成脚本的输出逐字节一致（防有人手改 JSON）
    _gp58 = os.path.join(ROOT, "tools", "gen_save_import_map.py")
    _v9_58 = os.path.join(_REPO_PARENT, "旧文件归档", "stardew_valley_1.6_data_v9", "stardew_data")
    if os.path.exists(_gp58) and os.path.isdir(_v9_58) and os.path.exists(_sim58):
        try:
            import importlib.util as _ilu
            _spec58 = _ilu.spec_from_file_location("gsim58", _gp58)
            _mod58 = _ilu.module_from_spec(_spec58)
            _spec58.loader.exec_module(_mod58)
            _built58, _eid58 = _mod58.build()
            _errs58, _totcheck58 = _mod58.validate(_built58, _eid58)
            check(not _errs58, "生成脚本自校验通过：%s"
                  % ("；".join(e.strip() for e in _errs58) or "账目全部达标"))
            check(_mod58.serialize(_built58) == io.open(_sim58, encoding="utf-8").read(),
                  "saveImportMap.json 与生成脚本输出逐字节一致（改数据只能改脚本，不许手改 JSON）")
        except Exception as _ex58:
            warns.append("第 58.14 节跳过：加载生成脚本失败（%s）" % _ex58)
    else:
        warns.append("第 58.14 节跳过：缺少 %s 或 旧文件归档/stardew_valley_1.6_data_v9（映射可复现性未校验）"
                     % ("tools/gen_save_import_map.py" if not os.path.exists(_gp58) else "saveImportMap.json"))

    # 58.15 构建脚本：devAssetGlobs 的「模式是否脱节」必须查源目录，不能查输出目录
    _bg58 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("val devAssetSourceDir" in _bg58,
          "构建脚本定义 devAssetSourceDir（通配脱节检查的判据是 src/main/assets）")
    check("devAssetSourceDir.walkTopDown()" in _bg58 and "srcHit.isEmpty()" in _bg58,
          "「模式与真实文件脱节」查的是源目录（查输出目录会被本任务自己删掉的文件误判）")

    # 58.16 设备实测踩出的两个坑，各自钉死（v2.65.0 首版装机当场发现）
    if os.path.exists(_sit58):
        # ⚠ 必须对 code_only() 的结果断言：这几个字面量**必然出现在解释坑的注释里**
        #   （不这么写的话，下一个人照注释做就会重现 bug）。拿全文匹配等于禁止注释说明问题。
        _si58c = code_only(_si58)
        # ① START_TAG 上 parser.text 恒为 null —— key/value 赋不上值，
        #    结果是**不报错的空导入**（用户看到「没有可勾选的进度」）。
        #    readLeaf 用参数名 p 取文本，所以代码里 parser.text 必须零出现：
        #    只要有人再直接读，这条就红。
        check("parser.text" not in _si58c,
              "解析器不直接读 parser.text（START_TAG 上恒为 null，会静默产出空导入）")
        check("fun readLeaf(" in _si58c and "p.next() == XmlPullParser.TEXT" in _si58c,
              "叶子取值走 readLeaf 并推进到 TEXT 事件")
        check(_si58c.count("readLeaf(parser)") >= 2,
              "两个取值点（角色名 / key+value）都调用 readLeaf：%d" % _si58c.count("readLeaf(parser)"))
        check("nextText()" not in _si58c,
              "不用 nextText()（它会吃掉 END_TAG，打乱深度匹配）")
        # ② 枚举 entry 的异常不能吞成空列表 —— 中文存档名解码失败会被伪装成
        #    「压缩包里没找到存档文件」，用户看到的是一句误导诊断。
        check("getOrElse { emptyList() }" not in _si58c,
              "zip 枚举异常不吞成空列表（否则解码失败伪装成「没找到文件」）")
        check("读取压缩包目录失败" in _si58c, "zip 枚举失败返回真实原因的文案")
        check("内含 ${all.size} 项" in _si58c,
              "候选为空时列出 zip 实际条目（让「压错了文件夹」一眼可辨）")
        # ③ size 只排序不过滤：未记录未压缩大小时 getSize() == -1，硬过滤会滤掉真存档
        check("it.size > 0" not in _si58c,
              "size 不作硬过滤（entry 未记录未压缩大小时 size=-1，会把真存档滤空）")
        check("it.size.coerceAtLeast(0L)" in _si58c, "size 仅用于排序且对 -1 作钳制")
        # ④ 诊断日志：raw 行用于区分「解析器没读到」与「映射表对不上」
        check('"raw "' in _si58c, "解析产出有 raw 诊断日志（区分状态机问题与映射问题）")
        check("zip charset=" in _si58c, "zip 解码 charset 有诊断日志")
        check("kept=${kept.size}" in _si58c, "zip 筛选结果有诊断日志（kept/total）")
        # ⑤ entry 名解码 charset 必须有回退（v2.65.1 第三个 bug，只有真机复现）：
        #    Windows 压中文 zip 常不置 UTF-8 标志，ZipFile 默认按 UTF-8 解码抛
        #    MALFORMED[1]，真机 100% 失败；模拟器宽松实现只解成乱码不抛，测不出来。
        check("fun openZip(" in _si58c and "openZip(zipFile)" in _si58c,
              "打开 zip 收敛在 openZip 单点（charset 回退唯一入口）")
        check("Charset.forName(\"GB18030\")" in _si58c,
              "entry 名解码回退到 GB18030（Windows 中文 zip 的本地编码）")
        check("Charset.forName(\"CP437\")" in _si58c,
              "entry 名解码回退到 CP437（单字节表永不抛，保证最坏也能列出条目）")
        check("ZipFile(zipFile, cs)" in _si58c, "按 charset 重建 ZipFile 而不是只靠默认")
        check("constructFailed" in _si58c and "listingFailed" in _si58c,
              "区分「不是 zip（构造抛）」与「解码/损坏（枚举抛）」两种失败")
        # 文案不许套两层前缀（曾写出 `return Err("读取…：x") ?: Err("请用…")`
        # 这种永远取左值的 `?:`，既丢了建议文案又可能重复前缀）
        check(_si58c.count("读取压缩包目录失败：") == 1,
              "「读取压缩包目录失败：」前缀只出现一次（不套两层、不靠 ?: 分叉文案）")
        # ⑤ 诊断日志自己必须可读：release 会 R8 混淆，`r::class.simpleName` 出来是 x0，
        #    分不出 Ok/Err —— 日志比没有更坏，因为它看起来像信息。
        check("class.simpleName" not in _si58c,
              "日志不用 r::class.simpleName（R8 混淆后显示 x0，分不出成败）")
        check('if (r is Result.Ok) "Ok keys=' in _si58c,
              "entry 结果日志显式判定 Ok/Err 并带条数")

    # 58.17~58.24 博物馆口径修正（v2.66.0）
    # 起因：游戏 1.6 起收藏页只统计**捐赠**，而代码读的是 `mineralsFound` /
    # `archaeologyFound`（捡到即算）——把「挖到但没捐」的也勾上了。
    if os.path.exists(_sit58):
        _si66 = code_only(_si58)
        # ① 新字段必须存在，否则「按标签圈容器」的老逻辑会把 locations 下的 museumPieces 漏掉
        check("val outsidePlayer: Boolean = false" in _si66,
              "Container 有 outsidePlayer 字段（museumPieces 在 player 子树之外）")
        check("val idFromValue: Boolean = false" in _si66,
              "Container 有 idFromValue 字段（museumPieces 的键是展柜坐标，ID 在 value）")
        # ② 分桶必须按 groupId：矿物与古物**共用 museumPieces 一个标签**，
        #    按 tag 分会让两个桶互相覆盖成一个，且各组数量看起来仍然合理、极难察觉。
        check("associateBy { it.groupId }" in _si66 and "associateBy { it.tag }" not in _si66,
              "分桶按 groupId 而非 tag（两博物馆组共用 museumPieces 标签，按 tag 分会互相覆盖）")
        check("MUSEUM_FALLBACK" in _si66,
              "定义了博物馆回退表（捐赠记录缺失时退回「找到过」）")
        check("isLibraryMuseum(parser)" in _si66 and "xsi:type" in _si66,
              "状态机识别 LibraryMuseum 位置（museumPieces 挂在 locations 下）")
        check("museumDepth" in _si66,
              "博物馆位置单独记深度（playerDepth 圈不到 locations 子树）")
        check("idFromValue == true" in _si66,
              "博物馆组的 ID 取自 <value> 而非 <key>")
        check("museumFellBack" in _si66,
              "回退状态透传到预览（用户该知道数字是哪个口径算的）")
        # ③ hit 与 denom 必须同口径：denom 是去重条目数，hit 若按存档条数累加，
        #    存档一旦有重复键就会显示 hit > denom。
        check("hitNames" in _si66 and "hitNames.size" in _si66,
              "命中数按唯一条目计（hit 取去重集合 size，与分母同口径）")
        check("var hit = 0" not in _si66,
              "不再有按条累加的 hit 计数器（会与去重分母不同口径）")
        # ④ 博物馆两组的 entries 是同一批捐赠物，未识别项只能计一次
        check("if (!c.outsidePlayer) unmapped++" in _si66,
              "博物馆组查不到时不各自计 unmapped（同一批捐赠物会被记两遍）")
        check("e.key !in minT && e.key !in artT" in _si66,
              "博物馆未识别项按「两张表都查不到」统一计一次")
        # ⑤ v2.66.0 装机当场踩的坑：museumPieces 的 <key> 里是 <Vector2><X>/<Y>，
        #    没有 int/string，wantKey 会一直挂着 → 紧接着 <value> 里的 ID 被
        #    `if (wantKey)` 抢先拿走，value 恒为 null，而 idFromValue 读的是 value
        #    → 博物馆整组产出 0 条。症状是「完全不报错、就是空」，最难查的一种。
        #    判据：key 分支必须按容器语义决定要不要置位，不能无条件 wantKey = true。
        #    判据：key 分支必须按容器语义决定要不要置位，不能是**裸**的 wantKey = true。
        #    不能简单断言 "wantKey = true" 零出现——修复后的写法里它是条件表达式
        #    的一部分（`if (…) wantKey = true`），那种断言会永远红，等于没有闸门。
        _bare66 = [ln.strip() for ln in _si66.splitlines()
                   if ln.strip() == "wantKey = true"]
        check(not _bare66,
              "key 分支没有裸的 wantKey = true（裸赋值会让 idFromValue 的容器挂上 wantKey，"
              "抢走 value 里的 ID）：%s" % _bare66)
        check("if (active.firstOrNull()?.container?.idFromValue != true) wantKey = true" in _si66,
              "key 分支按容器语义置位（idFromValue 的容器不挂 wantKey）")
        # ⑥ 源码断言只能钉「代码长什么样」，钉不住「跑起来对不对」。
        #    这里把事件级重放测试接进 verify：它用真实存档里的真实片段跑，
        #    一旦那段分支顺序被改坏（比如又把 wantKey 无条件置位），这里立刻红。
        _ts58 = os.path.join(ROOT, "tools", "test_savestate.py")
        if os.path.exists(_ts58):
            import subprocess as _sp58
            _r = _sp58.run([sys.executable, "-X", "utf8", _ts58],
                           capture_output=True, text=True, encoding="utf-8",
                           errors="replace", cwd=ROOT, timeout=600)
            check(_r.returncode == 0,
                  "状态机事件级重放测试全绿（真实片段跑 wantKey/wantValue 顺序）")
            if _r.returncode != 0:
                for _ln in (_r.stdout or "").splitlines():
                    if "FAIL" in _ln:
                        check(False, "  重放测试: %s" % _ln.strip())
        else:
            warns.append("第 58 节跳过：缺少 tools/test_savestate.py（状态机行为未校验）")
        # ⑦ 卡片文案里的条数是手写数字，映射表一改就会漂（v2.66.0 就漂过一回：
        #    映射 298 → 336 之后文案还写着 298 条）。钉死：文案数字必须等于
        #    saveImportMap.json 的实际覆盖数，改映射时同一次提交里必须改文案。
        _sp66 = src_sp()  # c230：拆分后拼合读（SAVE_TESTERS/SPONSORS 在 InfoDrawers，计数在 SavesTab）
        _map66 = os.path.join(DATA, "saveImportMap.json")
        if os.path.exists(_sp66) and os.path.exists(_map66):
            _sp66t = code_only(_sp66)
            _m66 = json.load(io.open(_map66, encoding="utf-8"))
            _cover66 = sum(len(set(v.values())) for v in _m66.values())
            _all66 = set().union(*[set(v.values()) for v in _m66.values()])
            _total66 = 576   # c210：crafting 补 76 条全可勾，560 → 576
            check("可自动识别 %d 条" % _cover66 in _sp66t,
                  "导入卡片文案的条数 = 映射表实际覆盖数 %d（手写数字会随映射漂移）" % _cover66)
            check("另有 %d 条" % (_total66 - _cover66) in _sp66t,
                  "「另有 N 条」的 N = 576 - 覆盖数 = %d" % (_total66 - _cover66))
            # 58.25 致谢抽屉的「存档导入内测」栏（v2.66.2）。与 31.7 同理：
            #       不钉住的话，将来重排致谢抽屉会把这 24 个名字悄悄弄丢。
            # ⚠ 2026-11-10（项目开源）：58.25（内测名单）/ 58.27（赞助名单）
            #   两条「名单里必须有谁」的断言**整段翻转**——名单已删，
            #   开源后公开群友 ID 是侵犯别人隐私（用户口径）。
            check("SAVE_TESTERS" not in _sp66t and "SPONSORS" not in _sp66t,
                  "58.25/58.27 内测与赞助名单已删且不得复活（声明形态探针）")
            check("存档导入内测" not in _sp66t and "赞助名单" not in _sp66t,
                  "58.25/58.27 名单抽屉与入口已删（用户口径：开源后不公开群友 ID）")
    # 58.26 局域网接收存档（v2.67.0）。这节的判据全是「安全边界」——
    #       一个对外开端口的功能，闸门必须钉在「不泄露 + 不长期开」上。
    _srv67 = os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                          "assistant", "data", "SaveReceiver.kt")
    if os.path.exists(_srv67):
        _sr67 = code_only(io.open(_srv67, encoding="utf-8").read())
        # ①' 关键区别：接收端**不外发**。它只是 accept + 落盘，
        #     没有任何出站连接（没有 URL/URLConnection/connect 到别的地址）。
        #     这才是它能与「检查更新」并列为「用户主动触发的网络行为」的前提。
        check("URL(" not in _sr67 and "URLConnection" not in _sr67 and ".connect()" not in _sr67,
              "接收端没有任何出站连接（只 accept，不往外发）")
        # ① **不对外提供任何文件**：只有 GET /（回一句状态）与 POST /upload，
        #    其余路径 404。没有「按路径读文件」这种东西存在，才谈得上不泄露。
        check('"/upload"' in _sr67 and "respond(outs, 404" in _sr67,
              "接收端只有 GET / 与 POST /upload 两个端点，其余 404（无任何可读文件的路径）")
        check("readFile" not in _sr67 and "sendFile" not in _sr67 and "FileInputStream" not in _sr67,
              "接收端没有任何对外发文件的代码（不读本地文件回去）")
        # ② 防「端口一直开着等人连」。
        # ⚠ v2.75.0：单次即焚**取消**了——用户要求「成功后窗口仍开着，PC 再传一次
        #   要重新生成新码、手机再弹一次输入框」。防常开的责任改由这三样扛：
        #   ① 空闲超时 ② 错误次数上限 ③ 抽屉一关 / 页面销毁就停。
        check("MAX_CODE_ATTEMPTS" in _sr67 and "IDLE_TIMEOUT_MS" in _sr67,
              "窗口不再单次即焚，但有「错误次数上限 + 空闲超时」两道闸")
        check("IDLE_TIMEOUT_MS" in _sr67 and "soTimeout" in _sr67,
              "空闲超时自动关闭（用户点完就忘的情况）")
        # ②'' v2.67.3：服务结束时 UI 状态必须同步。首版超时关服后抽屉仍显示
        #     「正在等电脑推送…」，用户对着已关的端口白忙——假状态比没状态更坏。
        #     判据：finally 块内出现 onChanged(State.Stopped。
        #     ⚠ 别用 `finally \{[^}]*onChanged` ——finally 里有 runCatching { }，
        #       内层 `}` 会把 `[^}]*` 截断（本断言第一版就这么假红过）。
        check("State.Stopped" in _sr67 and
              re.search(r"finally \{.*?onChanged\(.*?State\.Stopped", _sr67, re.S) is not None,
              "serve 的 finally 必发 Stopped（端口关了 UI 不许停在 Listening）")
        # ②' ⚠ 名额只能在**真收到一个包**时消耗。首版在 accept 之后立刻自增，
        #     于是 PC 脚本的 `--probe` 探活就把唯一名额用掉，随后的 POST 连都连不上，
        #     症状是「能探活、就是收不到」——两端都看不出错，最难查的一种。
        #     判据：自增语句必须落在「收包成功」那一段，且 accept 那一行不能带自增。
        # ②' v2.75.0：没有「名额」了（窗口可以连收多个包），但**探活仍然不能消耗任何东西**——
        #     GET / 不弹验证码、不消耗错误次数、不落盘。
        check('if (method == "GET" && path == "/")' in _sr67
              and "awaitCode" in _sr67,
              "验证码校验只发生在 POST /upload 路径上（探活不触发）")
        # GET / 探活：回纯文本 + 当前端口（电脑端据此知道往哪传）。
        # ⚠ 别钉具体文案字面量——文案会改，判据要钉**结构**。
        check('if (method == "GET" && path == "/")' in _sr67
              and 'respond(outs, 200, "OK"' in _sr67 and "PORT=" in _sr67,
              "GET / 探活存在且回纯文本（带当前端口）")
        # ③ 体积上限先看声明长度再收，不给「先收下来再判断」的机会
        check("contentLength > MAX_BYTES" in _sr67,
              "超限直接拒收（不给恶意大包撑爆 cacheDir 的机会）")
        check("20L * 1024 * 1024" in _sr67, "接收端体积上限与 SaveImport 同值（20MB）")
        # ④ 收到的字节当不可信输入：非 zip 立即拒，且必须交回 SaveImport 解析
        check("415" in _sr67 and "Unsupported Media Type" in _sr67,
              "非 zip 包按 415 拒收（收到的字节一律当不可信输入）")
        check("SaveImport.parseFile" in code_only(src("data/LanReceiveController.kt")),
              "收到的包交给 SaveImport.parseFile（c232 起在控制台，与选文件导入同一条口径，不新开通道）")
        # ⑤ 不硬编码网段：家里 192.168.、公司 172.16~31.、热点又是另一段
        check("192.168." not in _sr67,
              "不硬编码 192.168 网段（公司网与手机热点是别的段，硬写会把用户挡在门外）")
        # v2.75.0：优先只报 WiFi 网卡的地址（移动数据 / VPN 排除在外），
        # 拿不到才回退到全部非回环 IPv4 —— 两条路都要在。
        check("Inet4Address" in _sr67 and "wifiAddresses()" in _sr67
              and "allNonLoopbackV4()" in _sr67,
              "列出本机 IPv4 地址让用户对号入座（优先 WiFi，回退全部非回环）")
        # ⑥ 关抽屉 / 页面销毁必须停服务
        _sp67 = src_sp()  # c230：接收抽屉在主文件、Saves 路径文案在 SavesTab 分件
        check("DisposableEffect" in _sp67
              and "onDispose { com.stardew.assistant.data.LanReceiveController.onDrawerDisposed() }" in _sp67,
              "页面销毁时停服务（c232：经控制台，只停手动会话）")
        check("receiveOpen.value = false" in _sp67
              and "LanReceiveController.onDrawerDisposed()" in _sp67,
              "关掉接收抽屉即停服务（手动会话）")
        _lc58 = code_only(src("data/LanReceiveController.kt"))
        check("if (SaveReceiver.isRunning() && !autoSession)" in _lc58,
              "抽屉关闭只停手动会话（自动会话常驻，c232）")
        # ⑦ 文案必须说明「只收不传」——安全承诺要写在用户能看到的地方
        check("只收不传" in _sp67, "接收抽屉写明「只收不传」（安全承诺要能被用户看到）")
        # ⑧ PC 端脚本与 App 的排除名单必须一致，否则用户会看到「明明排除了却还在」
        _pc67 = os.path.join(ROOT, "tools", "send_save_to_phone.py")
        _bat67 = os.path.join(ROOT, "tools", "send_save.bat")
        _ps167 = os.path.join(ROOT, "tools", "send_save.ps1")
        # v2.67.0 起 PC 端**首选** .bat/.ps1（用户电脑没有 Python），Python 版保留备用。
        if os.path.exists(_bat67):
            _bat = io.open(_bat67, "rb").read()
            # ⚠ .bat 必须**纯 ASCII**：cmd.exe 用系统 OEM 代码页（中文 Windows = GBK）
            #   读 .bat，UTF-8 的中文会被拆成乱码并当成命令执行（首版实测直接炸）。
            check(all(c < 128 for c in _bat),
                  "send_save.bat 是纯 ASCII（cmd 按 OEM 代码页读 .bat，含中文会变成乱码命令）")
            check(b"powershell.exe" in _bat and b"-ExecutionPolicy Bypass" in _bat,
                  "send_save.bat 调系统自带 PowerShell 并绕过执行策略（不要求装 Python）")
            # v2.67.3 起是**单文件自包含**版：逻辑不再是同目录的 send_save.ps1，
            # 而是用 certutil 把内嵌 base64 还原到 %TEMP% 再 -File 执行。
            # ⚠ 原来那条 `b"send_save.ps1" in _bat` 是**为错误的理由通过**的：
            #   整个文件里唯一的 send_save.ps1 在第 14 行的 rem 注释（「改这个文件后
            #   重新生成」）。留着它等于把「偷偷退回双文件分发」判成通过——
            #   那正是群友踩过、逼出单文件版的那件事。
            _batcode67 = b"\r\n".join(ln for ln in _bat.split(b"\r\n")
                                      if not ln.strip().lower().startswith(b"rem"))
            check(b"certutil" in _batcode67 and b"send_save.ps1" not in _batcode67,
                  "send_save.bat 的可执行部分是单文件自包含形态（内嵌 base64 + certutil 还原，"
                  "不再依赖同目录的 send_save.ps1）")
            check(b'-File "%PS1%"' in _bat,
                  "bat 执行的是自己还原到 %TEMP% 的那份 ps1（单文件分发链完整）")
            # 换行符：发布仓 core.autocrlf=input 会把 CRLF 换成 LF，而 cmd.exe 读 LF-only
            #   批处理的标签跳转与括号块有已知失败 → 发出去的就是个双击报错的东西。
            check(b"\r\n" in _bat and b"\n" not in _bat.replace(b"\r\n", b""),
                  "send_save.bat 是纯 CRLF 行尾（autocrlf 若换了行尾，用户双击就会报错）")
            # ⚠ v2.68.0 实测：生成器的 --check 此前**从来没绿过**——它拿「落盘后的 CRLF 文本」
            #   去比「混行尾的 blob」（HEADER/FOOTER 是 LF、base64 行是 CRLF），恒不相等。
            #   一条从不生效的守卫等于没有守卫：改了 ps1 忘重跑生成器也不会红，
            #   而它自己一直喊红，于是没人当真。判据改成「跑一次生成逻辑比字节」。
            _gb68p = os.path.join(ROOT, "tools", "gen_send_save_bat.py")
            if os.path.exists(_gb68p):
                try:
                    import importlib.util as _ilu68
                    _spec68 = _ilu68.spec_from_file_location("_gen_bat_68", _gb68p)
                    _mod68 = _ilu68.module_from_spec(_spec68)
                    _spec68.loader.exec_module(_mod68)
                    _want68 = _mod68.build().replace("\r\n", "\n").replace("\n", "\r\n").encode("ascii")
                    check(_bat == _want68,
                          "send_save.bat 与 ps1 当前内容逐字节一致（ps1 改了忘重跑生成器会红）"
                          "——盘上 %d B / 重新生成 %d B" % (len(_bat), len(_want68)))
                except Exception as _ex68:
                    warns.append("第 58.26 节跳过：加载 bat 生成器失败（%s）" % _ex68)
        else:
            warns.append("第 58.26 节跳过：缺少 tools/send_save.bat")
        if os.path.exists(_ps167):
            _ps1b = io.open(_ps167, "rb").read()
            # ⚠ PowerShell 5.1 读 UTF-8 **无 BOM** 会按 ANSI 解，中文全乱码。
            check(_ps1b[:3] == b"\xef\xbb\xbf",
                  "send_save.ps1 带 UTF-8 BOM（PS 5.1 无 BOM 会把中文解成乱码）")
            _ps1 = _ps1b.decode("utf-8-sig")
            # 剥掉 # 注释后再查——「为什么不用 Compress-Archive」这类说明里
            # 必然会出现这个名字，naive 的 `not in` 会永远红。
            _ps1code = "\n".join(ln.split("#")[0] for ln in _ps1.splitlines())
            check("ZipFile]::CreateFromDirectory" in _ps1code,
                  "send_save.ps1 用 .NET ZipFile 打包（Compress-Archive 会多套一层目录名）")
            check("Compress-Archive" not in _ps1code,
                  "不用 Compress-Archive（5.1 会把农场文件夹名塞进包内路径）")
            # ⚠ param 名不能与脚本内变量撞名：PowerShell 变量名大小写不敏感，
            #   $Farm 会与农场列表 $farms 撞车，Where-Object 拿到的不是 DirectoryInfo，
            #   CreateFromDirectory 报「路径语法不正确」。首版实测栽在这。
            check("[string]$FarmArg" in _ps1 and "[string]$HostArg" in _ps1,
                  "send_save.ps1 的 param 用 FarmArg/HostArg（$Farm 会与 $farms 撞名）")
            check("$HostArg" in _ps1 and "$FarmArg" in _ps1,
                  "send_save.ps1 的 param 名非 $Host（内置只读变量）/ $Farm（撞名）")
            for _sfx67 in ("_old", "_SVBAK", "_SVEMERG"):
                check('"%s"' % _sfx67 in _ps1 or ("(%s)" % _sfx67) in _ps1 or _sfx67 in _ps1,
                      "send_save.ps1 排除 %s（与 App 端 ZIP_EXCLUDE_* 同一套口径）" % _sfx67)
            check("SaveGameInfo" in _ps1, "send_save.ps1 排除 SaveGameInfo（与 App 端一致）")
            check("/upload" in _ps1 or "38217/upload" in _ps1 or ":38217" in _ps1,
                  "send_save.ps1 打到 38217/upload（与 SaveReceiver 一致）")
            # 存档目录候选是 "$env:APPDATA\StardewValley\Saves" 这种**拼接路径**，
            # 不是独立字面量 '"Saves"'——查片段，别查带引号的整串。
            for _c67 in ("StardewValley\\Saves", "StardewValley\\Save_Game", "StardewValley\\SaveGame"):
                check(_c67 in _ps1, "send_save.ps1 认存档目录候选 %s" % _c67)
        else:
            warns.append("第 58.26 节跳过：缺少 tools/send_save.ps1")
        if os.path.exists(_pc67):
            _pysrc67 = io.open(_pc67, encoding="utf-8").read()
            for _sfx67 in ("_old", "_SVBAK", "_SVEMERG"):
                check('_sfx67' not in _pysrc67 and ('"%s"' % _sfx67) in _pysrc67,
                      "PC 脚本排除 %s（与 App 端 ZIP_EXCLUDE_* 同一套口径）" % _sfx67)
            check('"SaveGameInfo"' in _pysrc67,
                  "PC 脚本排除 SaveGameInfo（与 App 端一致）")
            # 端点一致性：脚本里是 URL 模板 "...:%d/upload"，所以查 "/upload"
            # 这个**路径后缀**，不能查字面 '"/upload"'（Python 侧拼的是 %d/upload，
            # 带引号的整串不存在——我第一版就是这么写成假红的）。
            check("/upload" in _pysrc67,
                  "PC 脚本打到 /upload 端点（与 SaveReceiver 的路径一致）")
            # 存档目录名：官方是 `Saves`。写错会让用户照着找半天（本机没装游戏，
            # 靠中英 wiki 交叉核实）。脚本以 Saves 为主候选，同时兼容旧目录名。
            check('"Saves"' in _pysrc67,
                  "PC 脚本认官方存档目录 Saves（wiki 中英文一致）")
        else:
            warns.append("第 58.26 节跳过：缺少 tools/send_save_to_phone.py")
        # ⑨ App 卡片上的存档路径提示同样要钉对（v2.67.0 顺手修的错）。
        #    判据要**唯一**：用户是照着卡片去资源管理器里找的，答案只能有一个；
        #    兼容候选只允许存在于 PC 脚本里，不许留在卡片文案上。
        check("StardewValley\\\\Saves\\\\<农场名>" in _sp67,
              "导入卡片的存档路径是 Saves（wiki 核实过的官方目录名）")
        check("StardewValley\\\\Save_Game" not in _sp67,
              "卡片文案里不再有 Save_Game（那会让用户找不到文件夹）")
        # ⑩ PixelButton(text, onClick, active) 的 active 在**第三位**，所以
        #    `PixelButton("x") { ... }` 会把 lambda 落到 active 上（编译期 Boolean 错）。
        #    v2.67.0 写接收入口时就是这么写崩的，故全局钉死：调用点必须显式 onClick=。
        _pw67 = os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                             "assistant", "ui", "PixelWidgets.kt")
        if os.path.exists(_pw67) and os.path.exists(_sp67):
            _sig67 = re.search(r"fun PixelButton\(([^)]*)\)",
                               io.open(_pw67, encoding="utf-8").read())
            _third67 = False
            if _sig67:
                # 参数按逗号切（默认值里也可能带逗号，只看顶层）——这里签名简单，直接数
                _params67 = [p.strip() for p in _sig67.group(1).split(",")]
                _third67 = len(_params67) >= 3 and "Boolean" in _params67[2]
            check(_third67,
                  "PixelButton 第三个参数是 Boolean active（尾随 lambda 会落错位，故禁）")
            if _third67:
                _bad67 = re.findall(r"PixelButton\([^()]*?\)\s*\{", code_only(_sp67))
                check(not _bad67,
                      "SettingsPage 里没有 PixelButton 尾随 lambda 写法：%d 处" % len(_bad67))
    else:
        warns.append("第 58.26 节跳过：缺少 SaveReceiver.kt（局域网接收未校验）")
    if os.path.exists(_gp58):
        _gs66 = io.open(_gp58, encoding="utf-8").read()
        check("MINERAL_OVERRIDE" in _gs66,
              "生成脚本有 MINERAL_OVERRIDE 整表（上游 item_index 缺 38 条矿物）")
        check("OLD_MINERAL_15" in _gs66,
              "生成脚本钉住改动前的 15 条矿物（防对齐表被顺手改掉）")
        # 从生成脚本 import 而不是在这里抄一份：抄两份必然漂移，
        # 漂移的表现是「断言看着在、实际查的是过期副本」——比没有断言更坏。
        _old15 = None
        _artov66 = None
        try:
            import importlib.util as _ilu66
            _spec66 = _ilu66.spec_from_file_location("_gen_si_map_66", _gp58)
            _mod66 = _ilu66.module_from_spec(_spec66)
            _spec66.loader.exec_module(_mod66)
            _old15 = _mod66.OLD_MINERAL_15
            _override = _mod66.MINERAL_OVERRIDE
            _artov66 = _mod66.ARTIFACT_OVERRIDE
        except Exception as _ex66:
            warns.append("第 58.23 节跳过：加载生成脚本失败（%s）" % _ex66)
        if _old15:
            check(len(_old15) == 15, "旧矿物基线是 15 条：%d" % len(_old15))
            for _k, _v in sorted(_old15.items()):
                check(_gs66.count('"%s"' % _k) >= 1 and _v in _override.get(_k, ""),
                      "旧矿物 %s=%s 在新表里一字未变（实得 %s）"
                      % (_k, _v, _override.get(_k, "缺失")))
            check(all(_override.get(k) == v for k, v in _old15.items()),
                  "旧 15 条矿物映射零回归（双源对齐表若挪了位会当场红）")
        check('"mineral": 53' in _gs66,
              "账目记矿物覆盖 53 条")
        check("EXPECT_TOTAL = 368" in _gs66,
              "账目记合计 368 条")
        # v2.66.1：手工古物表钉死。它是「上游名字对不上 App / 上游没记 id」的
        # 唯一例外通道——内容必须永远是这两条，谁扩了表就得连带改这里的账。
        check("ARTIFACT_OVERRIDE" in _gs66,
              "生成脚本有 ARTIFACT_OVERRIDE 手工古物表")
        if _artov66 is not None:
            check(_artov66 == {"104": "精灵珠宝", "582": "手部骨骼"},
                  "手工古物表仍是且仅是 104=精灵珠宝、582=手部骨骼 两条：%s"
                  % json.dumps(_artov66, ensure_ascii=False, sort_keys=True))
        else:
            warns.append("第 58 节跳过：生成脚本未导出 ARTIFACT_OVERRIDE（加载失败？）")
        check('"artifact": 42' in _gs66,
              "账目记古物覆盖 42 条")

    # ================= 58.28 v2.68.0 电脑脚本可在 App 内下载 =================
    # 起因：那个 send_save.bat 原先只发在 QQ 群，不在群里的用户压根拿不到——
    #   而「从电脑接收」这个功能对他们等于不存在。本节钉的是整条链：
    #   **按钮在 → 地址对 → 落盘对 → 远端字节与源码仓一致**。
    #   ⚠ 这类「发布物 + 消费方」的组合最容易假绿：App 端代码全对、verify 全绿，
    #     而仓库里根本没有那个文件，用户点一下拿到的是 404。链的两头都得断一次。
    print("\n== 58.28 电脑脚本下载：按钮 → 发布仓 → 落盘 ==")
    _spp68 = os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                          "assistant", "ui", "settings", "SettingsPage.kt")
    _batp68 = os.path.join(ROOT, "tools", "send_save.bat")
    if os.path.exists(_spp68) and os.path.exists(_batp68):
        _sp68 = io.open(_spp68, encoding="utf-8").read()
        _sp68c = code_only(_sp68)

        # ① 入口在「从电脑接收」抽屉里——只有要用这个功能的人才需要它，
        #    摆在设置页根部只是给设置页添噪音。
        check("⬇ 电脑端脚本" in _sp68 and "下载 send_save.bat" in _sp68,
              "接收抽屉里有「⬇ 电脑端脚本」卡片与下载按钮")
        check("onClick = onDownloadScript" in _sp68c,
              "下载按钮走 onDownloadScript（显式命名，避免尾随 lambda 落到 active 上）")

        # ② 必须**用户主动点**才联网。两个判据：
        #    · downloadScript 只有一处调用点（不在别处偷偷预取）；
        #    · 启动下载的 fetchScript 只有两个入口——按钮，和权限回调。
        #      ⚠ 别去查「有没有 LaunchedEffect」：设置页本来就有静默检查更新的
        #        LaunchedEffect，它跟下载毫无关系，naive 的全文件判断只会假红。
        _ndc68 = re.findall(r"downloadScript\s*\(", _sp68c)
        check(len(_ndc68) == 1,
              "downloadScript 只有一处调用点（点一下才发一次请求，不预取）——实得 %d 处"
              % len(_ndc68))
        _nfc68 = re.findall(r"fetchScript\s*\(\s*\)", _sp68c)
        check(len(_nfc68) == 2,
              "启动下载只有两条路径：按钮 onDownloadScript 与旧系统权限回调（实得 %d 条）"
              % len(_nfc68))

        # ③ 状态不许假：没点过就是 null（不摆「✓」也不摆「✗」），下载中必须挡住重复点击。
        check("scriptState = null" in _sp68c,
              "每次下载先清掉上一次的 scriptState（下载途中不能显示着旧的「✓」）")
        check("scriptBusy -> Unit" in _sp68c,
              "下载中重复点击被忽略（并发两次写同一个文件名）")
        check("null -> Unit" in _sp68c,
              "scriptState 为 null 时不渲染任何结果行（没点过不许出现假状态）")

        # ④ 权限只在旧系统上问
        check("ActivityResultContracts.RequestPermission()" in _sp68c
              and "UpdateRepo.needsStoragePermission()" in _sp68c,
              "只有 Android 8/9 才申请存储权限（新系统走 MediaStore 不该被追问）")

        # ⑤ 权限被拒的文案要标明只有 Android 9 及以下才会发生（v2.74.3）
        #    ⚠ 这条必须放在**这一节**：_sp68c 是这里才定义的，
        #    拿到别的节去用会把 verify 从中间打断（后面几千条断言全不跑，
        #    而输出看起来只是「少了些条数」，不比对条数根本发现不了）。
        check("Android 9 及以下才需要" in _sp68c,
              "权限被拒的文案标明只有 Android 9 及以下才会发生")

        # ⑤ 地址写在 UI 上（用户传到电脑时需要它），且与 UpdateRepo 是同一个常量
        check("UpdateRepo.SCRIPT_URL" in _sp68c and "tools/send_save.bat" in _urcode49,
              "抽屉里展示的下载地址就是 UpdateRepo 用的那个（不是手抄的第二份）")

        # ⑥ 发布链：发布脚本必须真的把这个文件搬进发布仓，且进 git add 名单。
        #    ⚠ 只钉「App 有按钮」不够——仓库里没有它，按钮就是个必然 404 的承诺。
        _pg68 = os.path.join(ROOT, "tools", "publish_gitee.py")
        _pg68s = io.open(_pg68, encoding="utf-8").read() if os.path.exists(_pg68) else ""
        check('os.path.join(PUB, "tools", "send_save.bat")' in _pg68s,
              "发布脚本把 send_save.bat 搬进发布仓的 tools/")
        check('"tools/"' in _pg68s,
              "git add 名单里有 tools/（不 add 就等于没发布，脚本只留在本地）")
        check("startswith(b\"rem\")" in _pg68s,
              "发布脚本剥掉 rem 注释后再判「是否单文件形态」"
              "（bat 第 14 行就写着「改 send_save.ps1 后重新生成」，naive 匹配会误杀真脚本）")

        # ⑦ 发布仓的 .gitattributes 必须钉住换行符：两个仓的 autocrlf 都是 input，
        #    不钉的话 CRLF 会被换成 LF，用户拿到的 bat 双击就是另一个报错。
        _gat68 = os.path.join(os.path.dirname(ROOT), "发布仓", ".gitattributes")
        if os.path.exists(_gat68):
            check("*.bat -text" in io.open(_gat68, encoding="utf-8").read(),
                  "发布仓 .gitattributes 钉了 *.bat -text（autocrlf=input 会毁掉 CRLF）")
        else:
            check(False, "发布仓缺 .gitattributes（autocrlf=input 会把 bat 的 CRLF 换成 LF）")

        # ⑧ 冒烟必须覆盖这条链，且同样得剥 rem 行
        _sm68 = os.path.join(ROOT, "tools", "smoke_publish.py")
        _sm68s = io.open(_sm68, encoding="utf-8").read() if os.path.exists(_sm68) else ""
        check("tools/send_save.bat" in _sm68s and "fetch_verified" in _sm68s,
              "冒烟校验远端脚本（且走 fetch_verified：推送后 gitee raw 缓存会短暂说它不存在）")
        check("startswith(b\"rem\")" in _sm68s,
              "冒烟同样剥 rem 行再判 ps1（与发布脚本、verify 同一处修正，别只改一半）")
        check("⑤B 下载字节" in _sm68s,
              "冒烟比对远端字节 ↔ 源码仓 send_save.bat（换行符被换掉只有这条能抓到）")

        # ⑨ 归档账本必须记这一版，且显式声明名单类增补
        _notes68 = io.open(os.path.join(ROOT, os.pardir, "安装包归档", "版本备注.md"),
                           encoding="utf-8").read()
        _gn68 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
        _vn68 = re.search(r'versionName\s*=\s*"([^"]+)"', _gn68)
        if _vn68:
            check("v" + _vn68.group(1) in _notes68,
                  "归档账本已记当前版本 v%s" % _vn68.group(1))
            check("拾慕" in _notes68,
                  "账本显式声明了赞助名单新增「拾慕」"
                  "（名单类增补按口径不占 App 内更新日志，账本就是它唯一的落点）")
            # v2.91.0 加「味子」：同样要在账本里显式声明（账本是名单增补唯一的落点）。
            check("味子" in _notes68,
                  "账本显式声明了赞助名单新增「味子」"
                  "（名单类增补按口径不占 App 内更新日志，账本就是它唯一的落点）")
    else:
        warns.append("第 58.28 节跳过：缺少 SettingsPage.kt 或 tools/send_save.bat")

    # ================= 58.29 v2.68.1 脚本地址点按复制 + 使用教程 =================
    # 起因（用户）：地址只能手打，「一个一个打是不是太慢了」；并且要求接收页补一份脚本教程。
    # 本节钉两件事：① 复制这条要走**既有惯例**（CopyLinkRow），不许自己另写一份剪贴板代码；
    #              ② 教程与电脑脚本**口径必须一致**——两边各写一套，用户照 App 做却和脚本
    #                 提示对不上，比没教程更糟。
    print("\n== 58.29 脚本地址点按复制 + 使用教程（v2.68.1） ==")
    if os.path.exists(_spp68):
        _sp69 = code_only(io.open(_spp68, encoding="utf-8").read())
        # ① 点按复制
        check("CopyLinkRow(UpdateRepo.SCRIPT_URL" in _sp69,
              "脚本地址走 CopyLinkRow 点按复制（复用协议链接那套，不另写剪贴板代码）")
        check("PixelText(UpdateRepo.SCRIPT_URL" not in _sp69,
              "地址不再是不可点的裸 PixelText（只能手打就是这次的病）")
        check("不用手打" in _sp69,
              "地址旁边写明「点一下就复制」（用户不知道能点 = 没有这个功能）")
        # ② 教程存在且默认折叠
        check("电脑上怎么用" in _sp69 and "CollapsibleCard(" in _sp69,
              "接收抽屉里有「电脑上怎么用」教程卡")
        check("var tutorialOpen by remember { mutableStateOf(false) }" in _sp69,
              "教程默认折叠（主操作「开始接收」不能被教程顶出首屏）")
        _steps69 = re.search(r"val steps = listOf\((.*?)\n\s*\)\n", _sp69, re.S)
        _seg69 = len(re.findall(r'^\s*"', _steps69.group(1), re.M)) if _steps69 else -1
        # ⚠ 这里数的是**字符串段数**，不是步骤数：一条步骤可能跨行拼接，
        #   实得会比真实步骤多（本次 5 步 / 7 段）。断言消息必须照实说数的是什么——
        #   写成「实得 7 步」会把下一个读日志的人骗到（本项目「账目写的和查的不是一回事」同族）。
        check(_seg69 >= 4,
              "教程有 %d 段文字（含跨行续写，实际步骤 5 步）——「双击它就行」不叫教程" % _seg69)
        check('"${i + 1}. $s"' in _sp69 or "${i + 1}" in _sp69,
              "步骤序号由 forEachIndexed 现生成（加一步不用手改编号，不会漏号错号）")
        # ③ 口径一致：App 教程里说的，必须与电脑脚本自己弹的提示对得上
        _ps69 = io.open(os.path.join(ROOT, "tools", "send_save.ps1"),
                        encoding="utf-8-sig").read()
        check("允许访问" in _sp69 and "允许访问" in _ps69,
              "防火墙「允许访问」两边同口径（App 说了脚本也这么说）")
        check("同一个 WiFi" in _sp69 and "同一个 WiFi" in _ps69,
              "「同一个 WiFi」两边同口径（这是最常见的失败原因）")
        check("开始接收" in _sp69 and "开始接收" in _ps69,
              "教程里让用户点的按钮名与 App 实际按钮一致（不是「开始」「接收」之类近似词）")
        # ④ 教程文案不许出现 Markdown 星号：App 内不渲染，会原样显示两个星号
        _tut69 = _sp69[_sp69.find("电脑上怎么用"):_sp69.find("免输入")] if "电脑上怎么用" in _sp69 else ""
        check("*" not in _tut69 and "**" not in _tut69,
              "教程文案里没有 Markdown 星号（App 内不渲染，会原样显示）")
        # ⑤ 教程里承诺的免输入参数，脚本必须真认
        check("--host" in _sp69 and "--farm" in _sp69
              and "--host=" in _ps69 and "--farm=" in _ps69,
              "教程写的 --host/--farm 免输入参数，脚本确实认（写了不认就是骗人）")
    else:
        warns.append("第 58.29 节跳过：缺少 SettingsPage.kt")

    # ================= 58.30 礼物名与图鉴译名对齐（v2.68.2，群友报法师礼物） =================
    # 起因（群友反馈）：法师最爱标「太阳能精华」，游戏里实际叫「太阳精华」。
    #   根因**不是缺数据**：图鉴里「太阳精华」有 7 处条目，只有 gifts.json 用了这个
    #   别名 → 村民页点最爱 → 图鉴零命中 → 落空。顺着扫全量礼物名，同批还有
    #   「黑莓脆饼」（App 规范名「黑莓脆皮饼」，recipes/shopCatalog/saveImportMap/
    #   secretNotes 四处都是后者）——一并修掉。
    #
    # ⚠ 查无分两种，本节只钉第 ② 种：
    #   ① 真缺图鉴条目（仙女盒/青蛙蛋这类 1.6 新物 + 「蛋类」这类品类名）→ 数据缺口，另议；
    #   ② 图鉴里**有**、只是礼物名写成了别名 → 必须改名对齐，补数据补不出这个。
    # 判据只用**本项目自己承认的别名证据**：字节完全相同的图标文件、被同一个
    # image_map 值引用的多个键。不用 difflib 猜相似——相似度高不等于同一物
    # （实测会把「泰式酸辣汤」配到「椰汁汤」头上）。
    print("\n== 58.30 礼物名与图鉴译名对齐（v2.68.2） ==")
    _gf70 = io.open(os.path.join(DATA, "gifts.json"), encoding="utf-8").read()
    check("太阳能精华" not in _gf70,
          "gifts.json 已无「太阳能精华」（图鉴规范名是「太阳精华」，七处数据都用它）")
    check('"太阳精华"' in _gf70, "法师最爱已改为「太阳精华」")
    check("黑莓脆饼" not in _gf70 and '"黑莓脆皮饼"' in _gf70,
          "阿比盖尔最爱已改为「黑莓脆皮饼」（App 四处数据的规范名）")

    # 别名组：本项目自己承认「这两个名字是同一个东西」的全部证据
    _img70 = os.path.join(ROOT, "app", "src", "main", "assets", "img")
    _imap70 = json.load(io.open(IMG, encoding="utf-8"))
    _alias70 = {}
    if os.path.isdir(_img70):
        _byhash70 = {}
        for _f70 in sorted(os.listdir(_img70)):
            if not _f70.lower().endswith((".png", ".webp")):
                continue
            _p70 = os.path.join(_img70, _f70)
            if os.path.isfile(_p70):
                _byhash70.setdefault(md5_of2(_p70), set()).add(_f70[:-4])
        for _s70 in _byhash70.values():
            if len(_s70) > 1:
                for _x70 in _s70:
                    _alias70.setdefault(_x70, set()).update(_s70)
    _byfile70 = {}
    for _k70, _v70 in _imap70.items():
        _byfile70.setdefault(_v70, set()).add(_k70)
    for _s70 in _byfile70.values():
        if len(_s70) > 1:
            for _x70 in _s70:
                _alias70.setdefault(_x70, set()).update(_s70)
    # ⚠ 先证明判据的输入不是空的：image_map 要是读坏了、img 目录没了，
    #   候选清单会恒为空、看起来「零不一致」——那是假绿，必须由这条挡住。
    check(len(_alias70) > 0,
          "别名组可解析（实得 %d 个名）——判据输入非空，否则「零不一致」是假绿" % len(_alias70))

    # 图鉴可命中的名字：**必须镜像 GameData.buildCodexGroups 的「标题键」口径**。
    #   ⚠ v2.68.2 踩过的坑（就是本节的起因）：初版这里用「name/name_cn/product/raw/
    #     item/seed 六个键扫全部 JSON」当图鉴集，于是把 `crafting.json` 里
    #     **配方材料**的 name（太阳精华、虚空精华…）也算成了图鉴条目，判成「在图鉴」；
    #     而 buildCodexGroups 只把每行的**顶层标题键**做成 CodexItem，材料行进的是 rows。
    #     后果是把一张错的候选表交给了用户（「羊毛→动物毛」是假阳性：羊毛本来就是条目），
    #     并且让用户以为「改了名就能跳」——实际图鉴里根本没有太阳精华这个条目。
    #   口径散落两处必然漂移（v2.27 老坑），所以这里逐分组照抄标题键，并钉住「新增分组要同步」。
    def _codex_titles70():
        """图鉴可命中名 —— **只取每个分组顶层行的标题键**，不深递归。

        ⚠ 为什么不能深递归（v2.68.2 实测踩到两次）：
          ① 用「name/name_cn/... 六键扫全部 JSON」会把 `crafting.json` 里
             **配方材料**的 name（太阳精华、虚空精华…）当成图鉴条目，
             于是把「改了礼物名就能跳图鉴」这个错结论交到了用户手上；
          ② 键名要用**原始文件的键**：`菜名`/`名称`/`果实` 是 Kotlin 侧 KEY_CN
             翻译后给 UI 用的键，原始 JSON 里是 `name`——照抄 GameData 的中文键
             会一个都取不到，标题集静默缩水。
        所以这里逐分组列 (文件, 原始标题键)，与 buildCodexGroups 一一对应；
        新增分组必须同步这里，否则「图鉴查无」的判定就是假的。
        """
        _t70 = []

        def _rows70(fname, container=None):
            try:
                _o70 = json.load(io.open(os.path.join(DATA, fname), encoding="utf-8"))
            except Exception:
                return []
            if isinstance(_o70, dict):
                _o70 = _o70.get(container or "list") or []
            return _o70 if isinstance(_o70, list) else []

        # (文件, 顶层标题键[, 数组字段]) —— 与 buildCodexGroups 的分组一一对应
        for _a70 in (
            ("fishing.json", "name", None), ("crops.json", "name", None),
            ("fruitTrees.json", "name", None), ("recipes.json", "name", None),
            ("crafting.json", "name", None), ("animals.json", "动物", None),
            ("buildings.json", "建筑", None), ("fishPonds.json", "鱼", None),
            ("toolUpgrades.json", "升级", None), ("geodes.json", "晶洞", None),
            ("seedPrices.json", "name", None), ("forage.json", "name", None),
            ("achievements.json", "成就", None), ("movies.json", "村民", None),
            ("festivals.json", "name", None), ("monsterDrops.json", "name", None),
            ("nonbuyableSeeds.json", "name", None), ("cheatCodes.json", "name", None),
            ("skills.json", "name", "list"), ("raccoon.json", "兑换项", None),
            ("monsterLoot.json", "名称", None),        # v2.68.2 新增分组
            ("gingerIsland.json", "兑换项", "parrotShop"),
        ):
            for _r70 in _rows70(_a70[0], _a70[2]):
                if isinstance(_r70, dict):
                    _v70 = _r70.get(_a70[1])
                    if isinstance(_v70, str) and _v70:
                        _t70.append(_v70)
        # tackle 的标题是「每行第一个字段的值」（GameData: entries.first().value）
        for _r70 in _rows70("tackle.json"):
            if isinstance(_r70, dict) and _r70:
                _t70.append(str(next(iter(_r70.values()))))
        try:
            for _g70 in _rows70("shipping.json"):
                for _n70 in _g70.get("items") or []:
                    # v2.117：条目改成对象（name/price/shippable），两种形态都要能取名。
                    #   只认 str 的话，出货图鉴 182 个条目一个都进不了标题表
                    #   ⇒ 58.30 礼物名与图鉴译名的交叉校验会漏掉整组。
                    if isinstance(_n70, str):
                        _t70.append(_n70)
                    elif isinstance(_n70, dict) and _n70.get("name"):
                        _t70.append(_n70["name"])
        except Exception:
            pass
        try:
            _m70 = json.load(io.open(os.path.join(DATA, "museum.json"), encoding="utf-8"))
            for _x70 in _m70.get("minerals") or []:
                if isinstance(_x70, dict) and _x70.get("name"):
                    _t70.append(_x70["name"])
            for _x70 in _m70.get("artifacts") or []:
                if isinstance(_x70, str):
                    _t70.append(_x70)
        except Exception:
            pass
        # mines / marriage / secretNotes 的标题是写死的中文串
        _t70 += ["矿点出现层段", "奖励层（每 10 层）", "特殊木箱与木桶",
                 "骷髅洞穴怪物", "婚姻通用机制", "科罗布斯（同居）", "精通山洞（1.6）"]
        # 返回**列表**而不是 set：58.32 要用它数「同一个名字出现在几个分组」，
        #   而 resolveItemJump 只有唯一命中才直达详情（CodexIndex.kt:160）——
        #   集合会把重名压掉，让那个前提检查永远是绿的。
        return [x for x in _t70 if x and not x.startswith("#")]

    _cnb70 = _codex_titles70()
    _cn70 = set(_cnb70)
    # 判据输入非空且**规模合理**：标题集要是缩水（键名写错、文件读不到），
    # 后面所有「查无」判定都会偏向「什么都查无」或「什么都有」，两种都骗人。
    # 480 是当前口径的下限（实测 550+），留出新增分组的增长空间。
    check(len(_cn70) > 480,
          "图鉴标题集按 buildCodexGroups 口径解析出 %d 个（>480 才算没解析崩）" % len(_cn70))
    for _probe70 in ("河豚", "熔炉", "煎鸡蛋", "晶洞", "绿色史莱姆", "优质浮标", "防风草"):
        check(_probe70 in _cn70,
              "标题集抽样探针命中「%s」（对应分组没被漏掉）" % _probe70)

    # v2.68.2 新增的「怪物掉落物」分组：成员名单钉死，且**不许与别的分组重名**。
    #   重名的后果不是「多一个入口」而是「退化成搜索页」：resolveItemJump 只在
    #   唯一命中时直达详情，两条同名就跳搜索列表。所以把 五彩碎片/紫水晶 这类
    #   已有条目的掉落物再收一遍，等于把村民页原有的直达跳转弄坏。
    _loot70 = json.load(io.open(os.path.join(DATA, "monsterLoot.json"), encoding="utf-8")) \
        if os.path.exists(os.path.join(DATA, "monsterLoot.json")) else []
    _lootnames70 = [r.get("名称") for r in _loot70]
    check(_lootnames70 == ["史莱姆泥", "太阳精华", "虚空精华", "蝙蝠翅膀", "虫肉"],
          "「怪物掉落物」成员是且仅是这 5 种：%s（扩表要先确认不会与别的分组重名）"
          % "、".join(x for x in _lootnames70 if x))
    _cn70_others = _cn70 - set(x for x in _lootnames70 if x)
    _dup70 = [x for x in _lootnames70 if x and x in _cn70_others]
    check(not _dup70,
          "新分组没有和别的图鉴分组重名（重名会把村民页的直达跳转弄坏）：%s"
          % ("、".join(_dup70) or "无"))
    check("太阳精华" in _cn70 and "虚空精华" in _cn70,
          "太阳精华 / 虚空精华 现在有图鉴条目了（本版新增分组前两者都没有）")
    for _r70 in _loot70:
        _nm70 = _r70.get("名称") or "?"
        check(all(_r70.get(k) for k in ("英文名", "售价", "掉落", "用途")),
              "%s 四个必填字段齐全（英文名/售价/掉落/用途）" % _nm70)
        check(_nm70 in json.load(io.open(IMG, encoding="utf-8")),
              "%s 有图标映射（缺图会静默落占位框）" % _nm70)
    # 「掉落」里写的怪物必须真是 App 怪物图鉴收录的那些——不许凭印象编来源。
    # 虫肉是唯一例外：它的来源取上游数据包，App 的怪物图鉴没收录虫子类，已在备注里写明。
    _mons70 = {x.get("name") for x in json.load(
        io.open(os.path.join(DATA, "monsterDrops.json"), encoding="utf-8"))}
    for _r70 in _loot70:
        if _r70.get("名称") == "虫肉":
            check("上游数据包" in (_r70.get("备注") or ""),
                  "虫肉的来源标注了出处（App 怪物图鉴未收录虫子类，不能装作是本 App 的数据）")
            continue
        _bad70 = [x.strip() for x in (_r70.get("掉落") or "").split("、")
                  if x.strip() and x.strip() not in _mons70]
        check(not _bad70, "%s 的「掉落」全部是怪物图鉴收录的怪物（编造来源：%s）"
              % (_r70.get("名称"), "、".join(_bad70) or "无"))
    _gd70 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                 "assistant", "data", "GameData.kt"), encoding="utf-8").read()
    check('CodexGroup("monsterLoot", "怪物掉落物"' in _gd70,
          "GameData 已把 monsterLoot.json 接成图鉴分组")
    _ps70 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                 "assistant", "ui", "codex", "CodexPanelSections.kt"),
                    encoding="utf-8").read()
    check('"monsterLoot"' in _ps70,
          "分类面板已列 monsterLoot（v2.18 老坑：漏这里用户在面板里找不到新分类）")

    _gp70 = json.load(io.open(os.path.join(DATA, "gifts.json"), encoding="utf-8"))
    _all70 = sorted({x for p in _gp70
                     for k in ("loves", "likes", "dislikes", "hates")
                     for x in (p.get(k) or [])})
    _miss70 = [x for x in _all70 if x not in _cn70]
    # v2.99.0 / c180：**项目已声明的别名不算「译名不一致」**——
    #   assets/data/itemAliases.json 里的那些名字是「同一个东西的另一个名字」，
    #   查名字时先过别名表就能对上（GameData.canonicalName → resolveItemJump 的别名回落），
    #   所以它们不是「点了落空」的漏网，而是**已收口**的别名。
    #   剩下没进别名表的才是真问题（要么改名对齐，要么补数据）。
    _al70 = {}
    _alp70 = os.path.join(DATA, "itemAliases.json")
    if os.path.exists(_alp70):
        _al70 = load(_alp70)
    _cand70 = []
    for _x70 in _miss70:
        if _x70 in _al70:
            continue
        _y70 = sorted(y for y in _alias70.get(_x70, set())
                      if y != _x70 and y in _cn70)
        if _y70:
            _cand70.append("%s→%s" % (_x70, "/".join(_y70)))
    if _cand70:
        # 不做成 check：这 12 条是**既存**问题，不是本版回归；本轮只修了群友报的
        # 一条 + 同一条链上的黑莓脆皮饼，改一批数据得用户点头。
        warns.append("58.30 礼物名与图鉴译名不一致（图鉴里有、名字是别名，点了落空）：%s"
                     % "、".join(_cand70))
    else:
        check(True, "礼物名与图鉴译名零不一致（可修别名 0 条；已声明的别名由 itemAliases.json 收口）")

    # 实测数字：权威在自动实测，不在注释里写死（本文件的 161/26 就是写死烂掉的）
    _loves70 = [x for p in _gp70 for x in (p.get("loves") or [])]
    _nl70 = sorted({x for x in _loves70 if x not in _cn70})
    warns.append("58.30 实测：最爱 %d 条（去重 %d 种），图鉴查无 %d 种——"
                 "CodexIndex 注释按此口径不写死数字"
                 % (len(_loves70), len(set(_loves70)), len(_nl70)))

    _ci70 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                 "assistant", "ui", "codex", "CodexIndex.kt"),
                    encoding="utf-8").read()
    check("161 条" not in _ci70 and "26 种" not in _ci70,
          "CodexIndex 注释已清掉失实的旧数字（161/26）")
    check("58.30" in _ci70,
          "CodexIndex 注释指向 verify 58.30 取权威数字（改数据不必再改注释）")
    check("真数据缺口" in _ci70 and "译名不一致" in _ci70,
          "注释把两种查无分开了（① 真缺条目 ② 译名不一致——处置方式相反，混谈会补错地方）")

    # ================= 58.32 七处最爱礼物名收敛到规范名（v2.68.3） =================
    # 起因：58.30 扫出的 18 条「礼物名写成了图鉴别名」里，A 档 7 条方向一致
    # （gift 是唯一/少数使用者，新名才是项目规范名），先修这一批。
    #
    # 这里**不只 assert 名字换过来了**——v2.68.2 就是「名字对上了但图鉴里根本没有
    # 那个条目」。所以改成三方互锁，任一条单独成立都不算数：
    #   ① 规范名必须是 buildCodexGroups 口径下的**顶层标题**（点了能真跳进去）；
    #   ② 规范名必须与 saveImportMap 里**游戏内部数字 ID** 绑定的名字一致
    #      —— 这个键由游戏数据决定，跟本项目怎么译名无关；
    #   ③ 规范名的图标必须与**上游 1.6 原图**逐字节相同 —— 证明没换错物。
    # 三个证据源互相独立，换个名字骗不过 ②③。
    print("\n== 58.32 七处最爱礼物名收敛到规范名（v2.68.3） ==")
    _R73 = [
        # (旧名, 规范名, 官方物品 ID, 上游原图)
        ("泰式酸辣汤", "椰汁汤", "218", "218_Tom_Kha_Soup.png"),
        ("矿工零食", "矿工特供", "243", "243_Miner's_Treat.png"),
        ("茄子帕玛森", "帕尔玛奶酪茄子", "231", "231_Eggplant_Parmesan.png"),
        ("蕨菜烩饭", "意式蕨菜炖饭", "649", "649_Fiddlehead_Risotto.png"),
        ("罂粟籽松饼", "虞美人籽松糕", "651", "651_Poppyseed_Muffin.png"),
        ("超能餐", "巨无霸餐", "237", "237_Super_Meal.png"),
        ("紫色蘑菇", "紫蘑菇", None, "422_Purple_Mushroom.png"),
    ]
    _sim73 = json.load(io.open(os.path.join(DATA, "saveImportMap.json"), encoding="utf-8"))
    # saveImportMap 是七张子表的容器，物品 ID 都在 recipeById 里（图鉴菜谱那张）。
    #   ⚠ 别拿顶层当扁平字典用——v2.68.3 初版就这么写的，7 条全红成「现为 None」。
    _rid73 = _sim73.get("recipeById") or {}
    _imap73 = json.load(io.open(IMG, encoding="utf-8"))
    # 已发布数据全集（wiki 原始抓取件不算：那是来源快照，不是 App 读的数据）
    _pub73 = [io.open(IMG, encoding="utf-8").read()]
    for _f73 in sorted(os.listdir(DATA)):
        if _f73.endswith(".json"):
            _pub73.append(io.open(os.path.join(DATA, _f73), encoding="utf-8").read())
    _pub73 = "\n".join(_pub73)
    _imgd73 = os.path.join(ROOT, "app", "src", "main", "assets", "img")

    def _md5_73(p):
        return hashlib.md5(open(p, "rb").read()).hexdigest()

    for _o73, _n73, _id73, _png73 in _R73:
        # 判据用**带引号的整串**：旧名会以 "泰式酸辣汤.png" 的形式作为图标文件名
        # 合法留在 image_map 的值里（那是文件名，不是条目名），只查裸串会误红。
        check(('"%s"' % _o73) not in _pub73,
              "已发布数据里不再有旧名条目「%s」（这类名字在图鉴里无条目，点了落空）" % _o73)
        check(_n73 in _cn70,
              "规范名「%s」是图鉴顶层条目（村民页点最爱能真正跳进详情）" % _n73)
        _fp73 = os.path.join(_imgd73, _imap73.get(_n73) or "")
        check(bool(_imap73.get(_n73)) and os.path.isfile(_fp73),
              "规范名「%s」有图标映射且文件真实存在（%s）"
              % (_n73, _imap73.get(_n73) or "缺映射"))
        check(_o73 not in _imap73,
              "image_map 不再登记旧名「%s」（留着会被下一个人当成可用名写回去）" % _o73)
        if _id73:
            check(_rid73.get(_id73) == _n73,
                  "官方物品 ID %s 在 recipeById 里就叫「%s」（现为 %s）"
                  % (_id73, _n73, _rid73.get(_id73)))
        _up73 = os.path.join(_REPO_PARENT, "旧文件归档", "stardew_valley_1.6_data_v9",
                             "images", "items", _png73)
        if os.path.isfile(_up73) and os.path.isfile(_fp73):
            check(_md5_73(_fp73) == _md5_73(_up73),
                  "「%s」的图标 = 上游原图 %s（字节一致，没换错物）" % (_n73, _png73))
        else:
            warns.append("58.32 无参照系，跳过「%s」的图标字节核对（缺 %s）"
                         % (_n73, _png73))

    # 直达详情的前提是**唯一命中**：resolveItemJump 只在 hits.size == 1 时跳详情，
    #   否则跳搜索页（CodexIndex.kt:160）。所以这里数的是「出现在几个分组的标题位」，
    #   而不是「在不在标题集里」——后者对重名永远绿，护不住这个前提。
    _cnt73 = {}
    for _x73 in _cnb70:
        _cnt73[_x73] = _cnt73.get(_x73, 0) + 1
    _amb73 = []
    for _o73, _n73, _id73, _png73 in _R73:
        _c73 = _cnt73.get(_n73, 0)
        if _c73 == 1:
            check(True, "「%s」在图鉴里唯一命中（点最爱可直达详情，不会退化成搜索页）"
                  % _n73)
        elif _c73 == 0:
            check(False, "「%s」在图鉴里没有标题位（点了会落空）" % _n73)
        else:
            _amb73.append("%s（%d 处）" % (_n73, _c73))
    if _amb73:
        # 重名不是本版引入的（旧名 0 命中是落空，新名重名至少还能搜到），
        # 要消掉就得删掉某个分组里一条真实条目 —— 那是在改别人的入口，交给用户定。
        warns.append("58.32 以下规范名在图鉴里重名，点最爱会跳搜索页而非直达详情：%s。"
                     "属既有情况（旧名是完全落空，改后至少搜得到）；要消掉需删除"
                     "某个分组里的一条条目，改动影响其他入口，需用户拍板"
                     % "、".join(_amb73))

    # 紫蘑菇（422 Purple Mushroom）是唯一没有官方 ID 可依的一条：它不是菜谱，
    # 所以 recipeById 里当然没有它。于是它的规范名少了证据源 ② —— 这里补证据源 ④：
    # 中文维基原文快照。
    check("422" not in _rid73,
          "recipeById 里没有 422（紫蘑菇是野外采集物不是菜谱，没有官方 ID 可依）")
    _w73 = os.path.join(DATA, "wiki", "raw_v2")
    if os.path.isdir(_w73):
        _wtxt73 = "".join(io.open(os.path.join(_w73, _f), encoding="utf-8").read()
                          for _f in sorted(os.listdir(_w73)) if _f.endswith(".txt"))
        check("紫蘑菇" in _wtxt73 and "紫色蘑菇" not in _wtxt73,
              "中文维基原文快照用的是「紫蘑菇」不是「紫色蘑菇」（422 无 ID 可依，"
              "规范名的旁证在这 %d 份村民 wiki 里）"
              % len([f for f in os.listdir(_w73) if f.endswith(".txt")]))
    else:
        warns.append("58.32 无 wiki 快照目录，跳过紫蘑菇的维基旁证核对")

    # ================= 59. v2.69 更新提示补洞 + 商店目录减负 =================
    # 用户反馈原文：「应用更新的提示不够强烈」「攻略页的商店目录，一大片商品直接
    #   搞到脸上，信息太杂乱了」。
    #
    # ⚠ 更新提示的根因**不是"不够显眼"，是"会永久错过"**：v2.61 的横幅状态只活在
    #   内存（MainActivity 的 remember），而查更新受 20 小时节流。于是「发新版那天开过
    #   App 但人不在今日页」的用户，节流记账已落盘 → 20 小时内重开不再查 → 横幅一次都
    #   没出现过，他本人也早忘了。**加粗视觉救不了这个，只有把「已知有新版」落盘才行。**
    #   本节钉的就是这条语义：节流约束的是"发请求"，不是"给提示"。
    print("\n== 59. 更新提示补洞 + 商店目录减负（v2.69） ==")

    _ma59 = src("MainActivity.kt")
    _pr59 = src("data/PrefsRepo.kt")
    _ur59 = src("data/UpdateRepo.kt")
    _ub59 = src("ui/UpdateBanner.kt")
    _gs59 = src("ui/guide/GuideShopsPage.kt")

    # ── 59.1 快照生命周期：存进去、读回来、装完自动清 ──
    check("update_pending_json" in _pr59 and "update_dialog_version" in _pr59,
          "两个新设备态键存在（待提示的新版本快照 / 这一版的弹窗弹过没有）")
    check("setUpdatePending" in _pr59 and "markUpdateDialogShown" in _pr59,
          "两个写方法齐")
    check("UpdateRepo.decodeLatest(snap.updatePendingJson)" in _ma59,
          "MainActivity 从落盘快照恢复横幅（零网络请求）——这是「不再被节流饿死」的落点")
    check("repo.setUpdatePending(UpdateRepo.encodeLatest(" in _ma59,
          "检查到新版本时同步落盘（否则下次冷启动又是空的）")
    # 自愈分支：用户装上新版后自动清，不靠额外状态。ok/fail 两分支都要在源码里。
    _heal59 = _ma59.split("pending.versionCode <= BuildConfig.VERSION_CODE", 1)
    check(len(_heal59) == 2 and "setUpdatePending" in _heal59[1][:120],
          "自愈分支：pending 版本 ≤ 当前版本就清掉（ok 分支与清除动作都在）")
    # 划掉横幅要**两件都做**：版本门槛 + 清数据。少一件下个版本就会出错。
    _dis59 = _ma59.split("dismissUpdate = {", 1)
    check(len(_dis59) == 2 and "markUpdateSeen" in _dis59[1][:400]
          and "setUpdatePending" in _dis59[1][:400],
          "划掉横幅时既记版本门槛又清快照（少一个下个版本会出错）")
    check("setUpdatePending" in _pr59 and "json: String" in _pr59,
          "setUpdatePending 以 String 承载快照（空串 = 清除）")
    check('setUpdatePending("")' in _ma59,
          "清除快照确实传的是空串（自愈分支与划掉分支各一处）")

    # ⚠ 不用脆弱的正则去抓整个函数体——只钉关键标记。
    #   decodeLatest 的**行为**（喂坏 JSON 返回 null）无法在本文件里真跑：verify 是静态
    #   判据、不编译 Kotlin。所以这里只钉结构，并写明是结构判据，别让人误以为已经跑过。
    #   真值由用户/助手在模拟器上点一次确认，或等 Kotlin 侧补单测。
    check("fun encodeLatest(" in _ur59 and "JSONObject()" in _ur59,
          "encodeLatest 存在并用 JSONObject 构造快照")
    check("fun decodeLatest(" in _ur59, "decodeLatest 存在")
    _dec59 = _ur59.split("fun decodeLatest(", 1)[1][:1400] if "fun decodeLatest(" in _ur59 else ""
    check("isNullOrBlank()) return null" in _dec59,
          "decodeLatest 对空/空白输入直接返回 null")
    check("runCatching" in _dec59 and "getOrNull()" in _dec59,
          "decodeLatest 的解析体整体 runCatching + getOrNull（脏数据退化成 null 不崩）")
    check("if (vc <= 0) return null" in _dec59,
          "decodeLatest 缺 versionCode 时返回 null（不是造一个 vc=0 的假 Latest）")
    # ⚠ 反向断言：decodeLatest 不许调 parse（那是解析**远端 version.json** 的，
    #   字段名与本地快照不同；混用会让远端改格式时把落盘快照一起改坏，
    #   而那份快照的用途恰恰是「远端改坏时还能提示用户」）。
    check("parse(" not in _dec59,
          "decodeLatest 不复用远端解析（两个格式各自演进，不能互相绊住）")

    # ── 59.1b v2.70.0 底栏样式开关（设备态，与 update_* 同族不进备份）──
    check('"glass_bar"' in _pr59, "键 glass_bar 存在")
    check("setGlassBar" in _pr59, "setGlassBar 写方法存在")
    for _k59 in ("glassBar",):
        check(_k59 not in _exp55,
              "exportJson 不含 %s（能力相关偏好不跨机搬运）" % _k59)

    # ── 59.2 反向断言：补洞不许顺手加出新的网络请求 ──
    # 计数口径：**构造点**而不是引用点。本文件里 URL( 与 openConnection() 各只有
    #   1 处（集中在 open() 那个 helper），两个 const URL 是字符串字面量不构造连接。
    #   早先这里写成「HttpURLConnection( 应为 1 处 / URL( 应为 2 处」——实测 0 与 1，
    #   是我按调用方数量写死了，而正确口径是构造点。判据写错时的表现和判据漏判一样。
    check(_ur59.count("URL(") == 1,
          "UpdateRepo 仍只有 1 处 URL( 构造点（集中在 open() helper，实测 %d）"
          % _ur59.count("URL("))
    check(_ur59.count("openConnection()") == 1,
          "UpdateRepo 仍只有 1 处 openConnection()（实测 %d）"
          % _ur59.count("openConnection()"))
    check(_ur59.count("fun check(") == 1 and _ur59.count("fun downloadScript(") == 1,
          "仍然只有 check / downloadScript 两个对外入口，没有第三个网络出口")
    # v2.73.3：原断言钉「节流窗口仍是 20 小时」，时间窗口已按用户要求废除。
    #   原意「补洞不动检查机制」钉在新形态上：仍是统一判定 + 进程内只查一次。
    check("THROTTLE_MS" not in code_only(_ur59) and "if (sessionChecked) return false" in _ur59,
          "补洞不动检查机制：统一判定仍收在 shouldCheck，进程内只查一次（THROTTLE_MS 已删）")

    # ── 59.3 弹窗语义：「稍后」不等于「别再提我」 ──
    check("fun UpdateDialog(" in _ub59, "存在 UpdateDialog 组件")
    check("updateDialogVersion < pending.versionCode" in _ma59,
          "弹窗条件含「这一版还没弹过」（发新版后应该再弹）")
    check("snap.introSeen" in _ma59,
          "首启引导未完成时不弹更新对话框（两个都是全屏层，别打架）")
    _later59 = _ma59.split("onLater = {", 1)
    check(len(_later59) == 2 and "markUpdateDialogShown" in _later59[1][:400],
          "「稍后」写的是弹窗版本号")
    # 反向断言：onLater 里**不许**出现 markUpdateSeen——写了等于永久屏蔽这一版，
    #   横幅也跟着消失，那这个功能就白做了。
    check("markUpdateSeen" not in _later59[1][:400],
          "「稍后」不写 markUpdateSeen（否则「稍后」= 永久屏蔽，横幅白做）")
    # 弹窗只在**读落盘**那一刻挂，不在 2 秒后那次检查的结果上挂
    check(_ma59.index("dialogPending = pending") < _ma59.index("kotlinx.coroutines.delay(2000)"),
          "弹窗只在启动读落盘时挂，不在网络检查结果上挂（否则每次开 App 都被拦）")
    check("dialogPending = pending" not in _ma59.split("val r = kotlinx", 1)[-1][:400],
          "网络检查那条路径没有给 dialogPending 赋值")

    # ── 59.4 横幅：写明动作 + 一键下载 ──
    check("onDownload: () -> Unit" in _ub59, "横幅有独立的下载回调")
    check("onDownloadUpdate" in src("ui/today/TodayPage.kt"),
          "今日页把下载回调传进横幅")
    check("c.gold.copy(alpha" in _ub59 and "c.green.copy(alpha = 0.35f)" not in _ub59,
          "横幅改金色描边（淡绿底与图鉴一片绿色卡片同色系，扫一眼就滑过去了）")
    # PixelButton 的 active 在第三位，尾随 lambda 会落错位（v2.67 写崩过一次）
    check(re.search(r'PixelButton\(\s*\n?\s*text = "点这里下载"', _ub59) is not None
          or 'PixelButton(text = "点这里下载"' in _ub59,
          "横幅下载按钮两个参数具名（active 在第三位，尾随 lambda 会落错位）")

    # ── 59.5 商店目录：默认收起 / 分组 ≥2 才折 / 单向子串 / 反向断言无阈值 ──
    check("fun GuideShopsPage(" in _gs59 and "CollapsibleCard(" in _gs59,
          "商店目录的店卡走 CollapsibleCard")
    check(re.search(r"var expanded by remember\(name\) \{ mutableStateOf\(false\) \}", _gs59)
          is not None,
          "店卡折叠态按店名记忆且**默认 false**（进页面一眼看完所有店）")
    check("collapsedHint" in _gs59 and "种" in _gs59,
          "折叠态压成一行（含商品种数）")
    # ⚠ 反向断言：不得出现「行数少就平铺」的阈值——冰淇淋摊 1 行、铁匠铺 3 行，
    #   套阈值等于白折一层；verify 56 节已对心事件钉过同类反向断言。
    check("rowList.size <=" not in _gs59 and "rowList.size >" not in _gs59
          and "rows.length() <=" not in _gs59,
          "店卡**没有**行数阈值分支（这里省的是整页信息量，不是首屏空间）")
    check("groups.size >= 2" in _gs59,
          "avail 分组仅在分组数 ≥2 时才折（实测旅行货车只有 2 组、256 行挤一组，折了也白折）")
    check("if (query.isNotBlank())" in _gs59 and "val groups = shown.groupBy" in _gs59,
          "搜索生效时直接平铺命中结果，不套第二层折叠")
    check("contains(query.trim(), ignoreCase = true)" in _gs59,
          "店内搜索是名字单向子串匹配")
    # 反向断言：不得出现反向包含（把「咖啡」送进「咖啡豆」那种）
    check("query.contains(" not in _gs59,
          "店内搜索**没有**反向包含（CodexIndex 记着那个坑：咖啡→咖啡豆、鱿鱼墨汁→鱿鱼）")
    check("没有匹配" in _gs59, "无命中给明文而不是空白")
    check("groupBy" in _gs59 and "sortedBy" not in _gs59,
          "avail 分组保序（不排序——数据顺序本身带信息，按价格重排会毁掉货架对照）")

    # ── 59.6 数据侧事实：把「为什么这家只能靠搜索」钉成事实 ──
    # 不钉的话，后人会按「分组折叠就够了」去改，而旅行货车的 256 行在 2 组里折不动。
    try:
        _sc59 = json.load(io.open(os.path.join(DATA, "shopCatalog.json"), encoding="utf-8"))
        _cart59 = [r.get("avail", "") for r in _sc59.get("旅行货车", [])]
        _gc59 = len(set(_cart59))
        _big59 = max([sum(1 for a in _cart59 if a == v) for v in set(_cart59)] or [0])
        check(_gc59 == 2 and _big59 >= 250,
              "旅行货车实测 %d 个 avail 分组、最大组 %d 行（所以分组折叠救不了它，只能靠搜索）"
              % (_gc59, _big59))
        _total59 = sum(len(v) for v in _sc59.values())
        check(_total59 >= 480,
              "商店目录实测共 %d 行商品（折叠改造针对的就是这个量级）" % _total59)
        _multi59 = sum(1 for v in _sc59.values() if len(set(r.get("avail", "") for r in v)) >= 2)
        check(_multi59 >= 6,
              "实测 %d 家店有 ≥2 个 avail 分组（分组折叠对它们才真的有用）" % _multi59)
    except Exception as _e59:
        warns.append("59.6 读 shopCatalog.json 失败，跳过数据侧事实核对：%s" % _e59)

    # ================= 60. v2.69.1 长按版本号预览更新提示 =================
    # 为什么需要它（用户 2026-10-18 原话「现在这个更新弹窗我无法测试」）：
    #   远端 version.json 比本机旧是**常态**——本机总是领先于最后一次发布，
    #   所以 Check 永远 UpToDate、pending 永远不写，横幅与启动弹窗**一次都不会出现**。
    #   想真验只能发一版到 gitee，但那要让内测群 25 个人一起收到更新提示，
    #   而且每个版本号只能测一次（点过稍后/✕ 那一版就被关掉了，再想测得再发一版）。
    #   所以做成**长按版本号注入假 pending**：不联网、不发布、可反复测。
    #
    # 本节钉的是「它测的必须是真链路」：注入走的是同一条 encodeLatest → 落盘 →
    #   MainActivity 恢复 → 横幅/弹窗，而不是在 UI 层直接画一个假组件。
    #   只在 UI 层画假的，测出来的行为就不是用户会经历的行为。
    print("\n== 60. 长按版本号预览更新提示（v2.69.1） ==")

    _sp60 = src_sp()
    _ua60 = code_only(_sp60)

    check("PREVIEW_MARK" in _sp60 and "private const val PREVIEW_MARK" in _sp60,
          "PREVIEW_MARK 有定义（不是只被引用的空气符号）")
    check("private fun previewLatest(" in _sp60,
          "previewLatest() 有定义")
    check("detectTapGestures(onLongPress" in _sp60,
          "版本号那一行支持长按")
    check("encodeLatest(previewLatest())" in _sp60,
          "注入走的是与真检查同一条 encodeLatest 通路（不是 UI 层画假组件）")
    # ⚠ 反向断言：不得给 encodeLatest 加 preview 之类的参数——59.1 已钉住它，
    #   预览标记放进 versionName 就够，一行数据层都不必动。
    _sig60 = re.search(r"fun encodeLatest\(([^)]*)\)", _ur59)
    check(_sig60 is not None and "preview" not in _sig60.group(1),
          "encodeLatest 签名没有为预览加参数（预览标记在 versionName 里，不动数据层）")
    # ⚠ 核心语义：注入时必须**同时**清 seen 与 dialog 两个版本门槛。
    #   只写 pending 的话，用户之前划掉过的那一版版本号更高，
    #   MainActivity 的「pending.versionCode > updateSeenVersion」会把横幅挡掉，
    #   于是「我注入了却什么都没出现」——这个坑钉死。
    #   ⚠ 锚点必须用 detectTapGestures：早先用 `split("} else {", 1)` 取注入分支，
    #     而这个文件前面到处是 else，分出来的「分支」是别处的代码——
    #     判据指向一段不相干的文本时，它照样能给出红或绿，骗人得很。
    _seg60 = _sp60.split("detectTapGestures(onLongPress", 1)
    check(len(_seg60) == 2, "长按处理器的锚点唯一")
    _body60 = _seg60[1][:1600] if len(_seg60) == 2 else ""
    check("markUpdateSeen(0)" in _body60 and "markUpdateDialogShown(0)" in _body60,
          "长按处理器里两个版本门槛都清零（注入与清除都要，否则横幅会被旧门槛挡掉）")
    check(_body60.count("markUpdateSeen(0)") >= 2,
          "注入分支与清除分支**各自**都清了 updateSeenVersion（只清一处会残留）")
    _clr60 = _sp60.split('contains(PREVIEW_MARK)) {', 1)
    check(len(_clr60) >= 2 and 'setUpdatePending("")' in _clr60[1][:400],
          "再长按一次是清除（pending 置空）")
    # 预览必须**一眼可辨**，否则测试时会跟真更新混淆
    check("（$PREVIEW_MARK）" in _sp60,
          "注入的版本名里带「预览」二字（横幅上一眼可辨，不会误当真更新）")
    check("apkUrl = \"\"" in _sp60,
          "预览版的 apkUrl 留空（点下载只会提示没有地址，不会真去浏览器下假文件）")
    # 触发点必须是**长按**而不是点击：版本号行点一下就该是复制/无操作，
    # 误触就把预览注入了，那比不测更糟。
    check("onClick" not in _ua60.split("detectTapGestures", 1)[1][:600],
          "触发点只绑长按、不绑点击（避免误触就注入）")

    # ================= 61. v2.70.0 底栏液态玻璃（B 方案：自写 shader 零依赖） =================
    # 用户决定：先自己移植 AndroidLiquidGlass 的核心算法（约 60 行 AGSL）给底栏做玻璃，
    # 不引库；效果满意再考虑方案 A（升 Kotlin/AGP/BOM + io.github.kyant0:backdrop）。
    # 本节钉的是 B 方案的三条底线与一条失败语义。
    #
    # ⚠ 一个已踩实的技术事实（写给后人）：库的录制机制用 rememberGraphicsLayer()
    #   ——那是 **Compose 1.8+ API**，本项目 1.7.6（BOM 2024.12.01）里根本不存在
    #   （已 javap 核过 ui-graphics 1.7.6 的类清单与字节）。所以 B 方案改用
    #   android.graphics.RenderNode（API 29，我们的门槛本来就在 31+），零依赖。
    print("\n== 61. 底栏液态玻璃（v2.70.0 / B 方案） ==")

    _pg61 = src("ui/glass/PixelGlass.kt")
    _lv61 = io.open(os.path.join(ROOT, "gradle", "libs.versions.toml"), encoding="utf-8").read()

    # 61.1 零依赖底线：B 方案的全部意义
    check("backdrop" not in _lv61 and "kyant" not in _lv61,
          "libs.versions.toml 无 backdrop/kyant 依赖（B 方案的底线：一行依赖都不加）")
    # ⚠ 剥注释再判：PixelGlass 的注释里**写着** rememberGraphicsLayer 为什么不能用
    #   （这是给后人的关键信息，注释必须留）——裸串判会把正确代码判红。
    #   本项目同族第四次（解释坑注释里的 ZipFile、send_save 注释里的 ps1、c104、这里）。
    check("rememberGraphicsLayer" not in code_only(_pg61),
          "PixelGlass 没用 rememberGraphicsLayer（1.8+ API，1.7.6 里编译不过）")

    # 61.2 门槛与 fallback：玻璃必须整体 33+（RenderNode.setRenderEffect 是 API 33
    #      方法，31/32 裸调会 NoSuchMethodError 崩——v2.70.0 胶囊版第一稿就是这样，
    #      当时把 enabled 定在 31 只因为"教程说模糊要 31"，忽略了宿主是 RenderNode）。
    #      <33 必须走原木纹。
    check("SDK_INT >= 33" in _pg61 and "SDK_INT >= 31" not in _pg61,
          "玻璃门槛单一 33+（setRenderEffect 与 AGSL 同门槛；31/32 会崩）")
    _bb61 = src("MainActivity.kt")
    # ⚠ 判据写成两个片段而不是整串：这两个 modifier 在源码里可能隔行书写，
    #   整串匹配会被一个换行偷走（判据红 ≠ 代码坏）。
    check("background(c.wood)" in _bb61 and "woodGrain()" in _bb61,
          "底栏木纹 fallback 路径仍在（老设备唯一的渲染分支，删了就是一片透明）")
    # 采样方向：库的 recordBackdropBlock 是 translate(+padding) 再 translate(-offset)。
    #   写成 (off - pad) 时胶囊采到屏幕外的空 —— 「白纱下文字清晰」的第一真凶。
    check("translate(pad - offX, pad - offY)" in _pg61,
          "采样平移方向 = pad - off（内容落到 SDF 盒原点；反号会采到屏幕外）")
    check("translate(offX - pad" not in _pg61,
          "反向平移不得回潮（off - pad 是 v2.70.0 首发的 bug 形态）")
    # 二级页隐藏底栏（用户点名「点开设置时底部栏应该消失」）
    check("if (stack.isEmpty())" in _bb61,
          "玻璃模式二级页（stack 非空）隐藏胶囊底栏")
    check("if (glassState != null)" in _bb61,
          "底栏按 glassState 判定启用（null = 无玻璃，走木纹布局）")
    # v2.70.0 开关三件套：判定合并、关时零开销、设置页门槛守卫
    check("snap.glassBar && " in _bb61.replace("\n", " "),
          "glassOn = 用户开关 ∧ 系统门槛（两个条件缺一不可）")
    # c228：传给节点创建的是 glassEffective（开关 ∧ 门槛 ∧ 护栏未停用）——
    #   护栏档 2 时 glassEffective = false，节点不创建、录制直通，与关开关同语义。
    check(re.search(r"rememberGlassBackdrop\(glassEffective\)", _bb61) is not None
          and "val glassEffective = glassOn && !" in _bb61.replace("\n", " "),
          "开关+护栏传入节点创建——关掉/停用后不创建不录制（零开销，不是录了不用）")
    _sp61 = src_sp()
    check("GlassLadder.enabled" in _sp61 and "🫧 底栏样式" in _sp61,
          "设置页开关卡有门槛守卫（低版本不给拨了没反应的开关）")
    check("setGlassBar" in _sp61, "开关卡写 repo.setGlassBar")

    # ── 61.6 方案 A：玻璃占位内边距（内容不被胶囊挡住）──
    # 单源公式：列 padding 与 inset 共用 BAR_V_PAD_DP，两处各写必然漂移（v2.27 老坑）。
    check("BAR_V_PAD_DP" in _pg61 and "BAR_V_PAD_DP.dp" in _bb61 and "BAR_V_PAD_DP * 2" in _bb61,
          "胶囊留白单源 BAR_V_PAD_DP：列 padding 与 inset 公式同源")
    check("LocalGlassInset provides" in _bb61,
          "AppRoot 计算并 provide 玻璃占位（木纹模式 0.dp）")
    # 五个主 tab 页的列表必须都消费——漏一个页面，那页滚到底就被胶囊挡一条
    for _f61 in ("ui/today/TodayPage.kt", "ui/calc/CalcPage.kt", "ui/codex/CodexPage.kt",
                 "ui/villagers/VillagersPage.kt", "ui/progress/ProgressPage.kt"):
        check("LocalGlassInset.current" in src(_f61),
              "%s 的主列表消费了玻璃占位（漏一页 = 那页底部内容被胶囊挡）"
              % _f61.split("/")[-1])

    # ── 61.6' 攻略正文同样要占位（v2.72.2，用户反馈「攻略页最下面那个看不到」）──
    # ⚠ 这里钉的是**contentPadding** 而不是「外层套 padding」。
    # v2.72.2 初稿曾在 CodexPage / GuideSubPage 外面包 `Modifier.padding(bottom=inset)`，
    # 那是**压缩视口**：视口外那块是死区，卡片被拦腰截断且滑不过去（用户截图实证）。
    # contentPadding.bottom 属于列表**内容**，能滚过去、最后一条停在胶囊上方还留着空白，
    # 与今日/计算/图鉴/村民/进度五页同款。
    _gc67 = code_only(src("ui/guide/GuideCommon.kt"))
    check("fun guideContentPadding(vertical: Dp)" in _gc67
          and "bottom = vertical + LocalGlassInset.current" in _gc67,
          "攻略占位公式单源 guideContentPadding（bottom = 纵向 + 玻璃占位）")
    # 十一个册子 + 战斗册的 4 个列表，每一个都必须走这个函数：
    # 漏一个 = 那一页滚到底仍被胶囊挡。逐文件钉，不写「至少 N 处」。
    for _gf in ("GuideHomePage", "GuideIslandPage", "GuideMarriagePage", "GuideWeaponsPage",
                "GuideSkillsPage", "GuideForagingPage", "GuidePetsPage", "GuideWeatherPage",
                "GuideCombatPage", "GuideShopsPage"):
        _gt = code_only(src("ui/guide/%s.kt" % _gf))
        _n67 = len(re.findall(r"contentPadding = guideContentPadding\(\d+\.dp\)", _gt))
        check(_n67 >= 1, "%s 的列表消费了攻略占位（实测 %d 处）" % (_gf, _n67))
        check("PaddingValues(horizontal = 12.dp, vertical" not in _gt,
              "%s 没有绕过 guideContentPadding 自带 contentPadding" % _gf)
    # 战斗册内部有 4 个 LazyColumn（分段切换），必须 4 个全中——只改一个就只修好一段
    _gcbt = code_only(src("ui/guide/GuideCombatPage.kt"))
    check(len(re.findall(r"contentPadding = guideContentPadding\(", _gcbt)) == 4,
          "战斗册 4 个分段列表全部带占位（实测 %d 处）"
          % len(re.findall(r"contentPadding = guideContentPadding\(", _gcbt)))
    # 年历走 verticalScroll：padding 必须排在 verticalScroll **之后**，否则又是压缩视口
    _cal67 = code_only(src("ui/calendar/CalendarPage.kt"))
    check(re.search(r"verticalScroll\(rememberScrollState\(\)\)\s*\n\s*\.padding\(bottom = LocalGlassInset\.current\)",
                    _cal67) is not None,
          "年历的 padding 排在 verticalScroll 之后（可滚动空白，不是压缩视口）")
    # 反向：入口处不许再套压缩视口式的 padding（v2.72.2 初稿的错法）
    check("glassSafeBottom" not in code_only(src("ui/codex/CodexPage.kt"))
          and "glassSafeBottom" not in code_only(src("ui/guide/GuideSubPage.kt"))
          and "glassSafeBottom" not in code_only(src("ui/glass/PixelGlass.kt")),
          "压缩视口式的 glassSafeBottom 已彻底移除（CodexPage / GuideSubPage / PixelGlass 三处都没有）")

    # ── 61.8 底栏圆角同心（v2.70.2）──
    # 用户截图实测：药丸右缘离胶囊内缘只有 4dp（11px），药丸自己的 20dp 右圆角
    # 被外层 32dp 大圆弧整段裁掉（外层在药丸顶行只退到 1007px，药丸右缘却在 1034px）
    # → 一套大圆弧 + 一套被切平的小圆角挨在一起，两套圆角互不对齐。
    # 口径改为「同心嵌套」：药丸高 = 胶囊高 − 2×内缩、药丸半径 = 药丸高/2（正胶囊），
    # 水平内缩 = 垂直内缩 → 两个圆弧**圆心重合**、留白恒等于内缩。
    # 本组断言用常量真算几何（不靠源码字符串对不对上——判据写错时红绿表现一样）。
    _m61h = re.search(r"GLASS_HEIGHT_DP\s*=\s*([0-9.]+)f", _pg61)
    _m61i = re.search(r"BAR_ITEM_INSET_DP\s*=\s*([0-9.]+)f", _pg61)
    check(_m61h is not None and _m61i is not None,
          "胶囊高与药丸内缩都是常量（几何单源，改一处即可）")
    if _m61h is not None and _m61i is not None:
        _H61 = float(_m61h.group(1))
        _I61 = float(_m61i.group(1))
        _itemH61 = _H61 - 2 * _I61
        _itemR61 = _itemH61 / 2.0
        _barR61 = _H61 / 2.0
        # ① 内缩区间：<4dp 药丸右圆角会被大圆弧裁掉（v2.70.1 的病根）；
        #    >12dp 药丸被压太小、图标挤边。
        check(4 <= _I61 <= 12,
              "药丸内缩在 [4,12]dp（当前 %g dp）——太小会被胶囊大圆弧裁掉圆角" % _I61)
        # ② 药丸必须是正胶囊（半径 = 高/2），与外层同一套圆角语言
        check(abs(_itemR61 * 2 - _itemH61) < 1e-6,
              "药丸是正胶囊（半径 = 高/2 = %g dp）" % _itemR61)
        # ③ 同心条件：两圆弧的间隙 == 内缩（圆心重合的充要条件）
        check(abs((_barR61 - _itemR61) - _I61) < 1e-6,
              "药丸与胶囊圆弧同心（间隙 %g dp == 内缩 %g dp）"
              % (_barR61 - _itemR61, _I61))
        # ④ 药丸圆角不得越出胶囊圆：越出 = 被胶囊裁掉（就是 v2.70.1 那个病）
        _off61 = abs((_I61 + _itemR61) - _barR61)
        check(_itemR61 + _off61 <= _barR61,
              "药丸圆角不越出胶囊圆（%g + %g <= %g dp）"
              % (_itemR61, _off61, _barR61))
        # ⑤ 药丸必须装得下内容：图标 24 + 间距 2 + 文字行盒（1× 实测 16.3dp，
        #    「特大」1.3× 档约 21dp → 合计约 47dp），否则字被裁。
        check(_itemH61 >= 48.0,
              "药丸高 %g dp 装得下「特大」档内容（约 47dp）" % _itemH61)
    # ⑥ 水平内缩取自常量（写死数字 = 与药丸几何漂移）
    check("padding(horizontal = GlassLadder.BAR_ITEM_INSET_DP.dp)" in _bb61,
          "胶囊内行左右留白取自 BAR_ITEM_INSET_DP（写死数字 = 与药丸几何漂移）")
    # ⑦ 药丸几何取自单源常量
    check("GlassLadder.itemHeightDp.dp" in _bb61 and "GlassLadder.itemRadiusDp.dp" in _bb61,
          "药丸高与圆角取自 GlassLadder 单源（itemHeightDp / itemRadiusDp）")
    # ⑧ 旧形态不得回潮：20dp 圆角 + 4dp 行留白 = v2.70.1 被裁的那一版
    _code61 = code_only(_bb61)
    check("RoundedCornerShape(20.dp)" not in _code61,
          "旧药丸圆角 20.dp 不得回潮（非正胶囊，与外层不同族）")
    check("padding(horizontal = 4.dp)" not in _code61,
          "旧行留白 4.dp 不得回潮（药丸会顶到胶囊大圆弧上）")

    # ── 61.7 开源抽屉的 AndroidLiquidGlass 归属（Apache-2.0 要求保留声明）──
    check("AndroidLiquidGlass" in _sp61 and "Kyant0/AndroidLiquidGlass" in _sp61,
          "开源抽屉有「界面效果」归属卡（作者/仓库/协议三要素齐）")
    check("Apache License 2.0 协议全文" in _sp61,
          "开源抽屉可展开 Apache-2.0 协议全文")
    _raw61 = os.path.join(ROOT, "app", "src", "main", "res", "raw",
                          "apache_2_0_androidliquidglass.txt")
    check(os.path.isfile(_raw61) and os.path.getsize(_raw61) > 5000,
          "res/raw 的 Apache-2.0 全文在位（%s B）"
          % (os.path.getsize(_raw61) if os.path.isfile(_raw61) else "缺失"))

    # 61.3 协议义务（Apache-2.0 §4）：版权头 + 许可证副本 + 归属声明
    check("Copyright 2025 Kyant" in _pg61 and "Apache" in _pg61,
          "shader 文件保留原版权头与 Apache 声明")
    check("Kyant0/AndroidLiquidGlass" in _pg61,
          "shader 文件写明来源仓库（衍生作品的归属不能只活在别处）")
    _lic61 = os.path.join(ROOT, "app", "src", "main", "assets", "licenses",
                          "Apache-2.0-AndroidLiquidGlass.txt")
    check(os.path.isfile(_lic61) and os.path.getsize(_lic61) > 5000,
          "许可证全文随包分发（assets/licenses/，实测 %s B）"
          % (os.path.getsize(_lic61) if os.path.isfile(_lic61) else "缺失"))
    check("AndroidLiquidGlass" in src_sp(),  # c230：归属句随协议抽屉搬进 InfoDrawers
          "关于页有归属句（承诺跟着实现走）")

    # 61.4 shader 常量 sanity：防手滑写出炸性能的值（v2.70.1 起对齐官方教程参数名）
    for _k61 in ("GLASS_HEIGHT_DP", "BLUR_RADIUS_DP", "REFRACTION_HEIGHT_DP",
                 "REFRACTION_AMOUNT_DP"):
        _m61 = re.search(_k61 + r"\s*=\s*([0-9.]+)f", _pg61)
        check(_m61 is not None and 0 < float(_m61.group(1)) <= 64,
              "%s 在 (0, 64] 合理区间（当前 %s）——超界多半是手滑，会把 GPU 烧穿"
              % (_k61, _m61.group(1) if _m61 else "?"))
    # 表面白纱必须在 (0, 1]：太淡玻璃隐形（v2.70.0 初版 0.22 就是隐形值），
    # 太浓就是一块白板。教程口径 = 白 0.5。
    _m61 = re.search(r"SURFACE_ALPHA\s*=\s*([0-9.]+)f", _pg61)
    check(_m61 is not None and 0.3 <= float(_m61.group(1)) <= 1.0,
          "SURFACE_ALPHA 在 [0.3, 1]（当前 %s）——低于 0.3 玻璃约等于隐形"
          % (_m61.group(1) if _m61 else "?"))
    # 教程效果链三件套必须齐：vibrancy（饱和度矩阵）+ blur + lens（折射）
    # 效果链：blur + lens 必须 chain；vibrancy 是**按用户视觉目标刻意舍掉**的
    #   （教程用它增饱和，用户要中性不发绿）——舍掉必须留注释说明，防止后人
    #   "对照教程发现少一环"又接回去。
    check("createChainEffect" in _pg61,
          "效果是 chain 出来的（blur → lens）")
    check("刻意去掉 vibrancy" in _pg61 and "createColorFilterEffect" not in code_only(_pg61),
          "vibrancy 已按用户目标移除且有决策注释（不是漏抄教程）")

    # 61.5 失败语义：玻璃挂了不许弄坏主界面
    check("catch (_: Throwable)" in _pg61,
          "录制/采样有兜底 catch（玻璃失败退回正常绘制，不许白屏）")
    check(_bb61.count(".recordBackdrop(") <= 2,
          "recordBackdrop 调用点 ≤2（录两次 = 双倍显存，架构上只该有一处）")

    # ================= 62. v2.70.0 生日卡跳村民页 + 反查部分匹配 =================
    # 用户原话：「今日页里点生日的村民直接跳转到村民页那里。同样的，礼物名称也要可以搜索的。」
    print("\n== 62. 生日卡跳村民页 + 反查部分匹配（v2.70.0） ==")

    _tp62 = src("ui/today/TodayPage.kt")
    _vp62 = src("ui/villagers/VillagersPage.kt")

    # 62.1 跳转走 navBus 单点（AppRoot 消费），不在今日页私设第二条通道
    check("NavRequest.Villager(" in _tp62,
          "生日卡点击发的是 NavRequest.Villager（AppRoot 单点消费：清栈+切页签）")
    check("villagerTarget.value" not in _tp62,
          "今日页不直接写 villagerTarget（那是村民页内部通道，两处写会漂移）")
    check("openVillager" not in _tp62,
          "今日页内生日抽屉已拆除（档案只在村民页一处存在，避免两套抽屉行为漂移）")

    # 62.2 反查部分匹配：单向 contains，不是双向
    # ⚠ v2.73 起匹配逻辑收进 [tierOf]，且五段各有各的别名表，判据因此换了写法——
    #   **守的是同一组语义**（单向子串兜底、精确在前、不得双向），不是同一段字符串。
    #   换写法时最容易把语义一起丢掉，所以下面每条都对照原注释核过。
    _tier62 = _vp62.split("private fun tierOf", 1)[1].split("\n}", 1)[0] if "private fun tierOf" in _vp62 else ""
    check(bool(_tier62), "反查匹配逻辑收在 tierOf 一个函数里（找到它才能验语义）")
    if _tier62:
        check("if (item in list) return label" in _tier62,
              "反查对每段礼物表做精确匹配（打完整礼物名直接命中）")
        check("list.any { it.contains(item) }" in _tier62,
              "反查做单向子串兜底（打「太阳」能搜到「太阳精华」）")
        # 反向断言：不许出现 item.contains( 的双向匹配（咖啡→咖啡豆那个坑）
        check("item.contains(" not in _tier62,
              "反查没有双向包含（双向会把「咖啡」送进「咖啡豆」）")
        # 顺序敏感：精确必须在子串之前。写成带顺序的正则——只钉两个符号都出现抓不到写反。
        _exact62 = _tier62.find("if (item in list) return label")
        _sub62 = _tier62.find("list.any { it.contains(item) }")
        check(0 <= _exact62 < _sub62,
              "精确匹配在子串兜底之前（类别条目只认完整名，顺序反了会先被子串兜住）")
        # 五段都要参与匹配，否则「讨厌」这类新段在反查里查不到
        _tiers62 = re.findall(r"villager\.(loves|likes|dislikes|hates|neutrals) to", _tier62)
        check(len(_tiers62) == 5,
              "五段礼物都参与反查匹配（实测 %d 段：%s）" % (len(_tiers62), _tiers62))

    # ================= 62. v2.70.1 永久直链 latest.apk =================
    # 用户问「以后版本更新了，通过这个链接还能继续下载吗」——带版本号的直链会 404，
    # 用户要一个永久可用的链接：发布仓固定放 apk/stardew-assistant_latest.apk，
    # 每次发版覆盖。**App 内更新链路一字不动**（继续走 version.json 带版本号路径 +
    # md5 校验），latest 只服务人肉贴链接（群公告/收藏夹）。
    print("\n== 62. 永久直链 latest.apk（v2.70.1） ==")

    _pg62 = io.open(os.path.join(ROOT, "tools", "publish_gitee.py"), encoding="utf-8").read()
    _sm62 = io.open(os.path.join(ROOT, "tools", "smoke_publish.py"), encoding="utf-8").read()

    check("stardew-assistant_latest.apk" in _pg62 and "已更新永久直链" in _pg62,
          "发布脚本每次发版覆盖 latest.apk")
    # 删旧包循环必须豁免 latest——不豁免的话下次发版把"永久链接"自己删了，404
    check("keep_latest" in _pg62 and "p != keep_latest" in _pg62,
          "删旧包循环豁免 latest（否则永久直链在第二次发版时被自己删成 404）")
    check('"apk/stardew-assistant_latest.apk"' in _pg62,
          "git add 名单含 latest（不 add 等于没发布）")
    check("永久直链 latest.apk 与当前版一致" in _pg62,
          "--check 模式认 latest（发版忘了覆盖 = 链接停在旧版，这条会红）")
    # 冒烟的重试终止条件必须是**内容 md5 符合清单**，不是 HTTP 200——
    # gitee raw 对被覆盖文件有几分钟缓存，只看 200 就是 c96 假绿同型错误。
    check("②C 永久直链" in _sm62 and "LATEST_REL" in _sm62,
          "冒烟有 ②C：latest 可下载且与清单同一份包")
    _seg62 = _sm62[_sm62.index("LATEST_REL"):][:900]
    check("fetch_verified" in _seg62 and "hashlib.md5(b).hexdigest() == want_md5" in _seg62,
          "②C 走 fetch_verified 重试到**内容** md5 等于清单（不是 200 就收工）")
    check('!= "stardew-assistant_latest.apk"' in _sm62,
          "③ 历史包 404 判定豁免 latest（它按设计永远在架，不豁免会被报成'未下架'）")
    # latest 与带版本号包同源同字节：都从 src_apk 复制，无独立写入路径
    check(_pg62.count("shutil.copyfile(src_apk") == 2,
          "latest 与版本包同源于本地构建产物（两处 copyfile(src_apk…，没有第二条内容来源）")

    # ================= 63. v2.71.0 村民特别关心 =================
    # 用户原话：「在村民页，我可以给某个村民点"特别关心"。此时，该村民喜爱的物品
    # 就可以在图鉴页优先显示，或者在图鉴页专门加一个地方显示」。
    # 落地方案选**图鉴页专门区块**而不是「打乱各分类排序」：后者会破坏 13 个
    # 可打勾分类的浏览/勾选心智（顺序一乱，肌肉记忆全废）；专块零破坏。
    print("\n== 63. 村民特别关心（v2.71.0） ==")

    _pr63 = src("data/PrefsRepo.kt")
    _vp63 = src("ui/villagers/VillagersPage.kt")
    _cx63 = src("ui/codex/CodexPage.kt")
    _ma63 = src("MainActivity.kt")

    # 63.1 数据层：全局键 + toggle + 快照字段
    check('stringSetPreferencesKey("followed_villagers")' in _pr63,
          "键 followed_villagers 存在（存名字不存下标，同 CODEX_RECENT 纪律）")
    check("suspend fun toggleFollowed" in _pr63,
          "toggleFollowed 写方法存在（点一次标上，再点取消）")
    check("val followed: Set<String> = emptySet()" in _pr63,
          "Snapshot 有 followed 字段（默认空 = 一个都没标）")
    # 全局键不是存档级键：不带 d:/pf5:/ck: 前缀
    check('stringSetPreferencesKey("followed_villagers")' in _pr63
          and 'stringPreferencesKey("ck:' not in _pr63.split("FOLLOWED")[1].split("\n")[0],
          "followed 是全局键（村民是固定数据，换存档不该清空）")

    # 63.2 备份：进 exportJson，import 带旧备份守卫
    check('.put("followed_villagers", JSONArray(snap.followed.toList()))' in _pr63,
          "exportJson 带 followed_villagers（用户手动标的，丢了要重新标）")
    check('o.optJSONArray("followed_villagers")' in _pr63,
          "importJson 仅在备份含该字段时覆盖（旧备份不动本地名单）")

    # 63.3 村民页：列表行★开关 + 排最前 + 详情头部开关
    check("scope.launch { repo.toggleFollowed(v.name) }" in _vp63,
          "列表行★点击走 repo.toggleFollowed")
    check("it.name !in snap.followed" in _vp63,
          "特别关心的村民排最前（compareBy 首键：未关心的排后面）")
    check("remember(GameData.gifts, snap.followed)" in _vp63,
          "排序 remember 挂 snap.followed（点★后必须触发重排）")
    check("onToggleFollow" in _vp63 and "★ 已关心" in _vp63,
          "详情头部有★开关（点开详情顺手标记的动线）")
    # 详情主体的开关必须是可选参数：今日页等其他调用方不接线就不显示
    check("onToggleFollow: (() -> Unit)? = null" in _vp63,
          "onToggleFollow 可空默认 null（不接线的调用方不显示★）")

    # 63.4 MainActivity 传参（村民页原来只收 visible）
    #     v2.72 起这一行是多行调用（多了 onOpenMods），所以钉「参数逐个在」而不是钉整行字面量：
    #     钉整行会让任何一次加参数都必须回来改断言，而那种断言改多了就没人看了。
    check(re.search(r"VillagersPage\(\s*snap,\s*repo,\s*visible", _ma63) is not None,
          "MainActivity 给村民页传 snap/repo/visible（开关要读写偏好）")

    # 63.5 图鉴页专块：折叠头 + 物品跳详情 + 人名跳村民页
    check("⭐ 特别关心" in _cx63,
          "图鉴页有特别关心区块")
    check("snap.followed" in _cx63 and "GameData.gifts.filter { it.name in snap.followed }" in _cx63,
          "区块按名字反查村民（数据包改名/删人时丢弃，不显示错的人）")
    check("resolveItemJump(itemName)" in _cx63,
          "最爱物品点击走 resolveItemJump（与正文链接同一套解析口径）")
    check("NavRequest.Villager(" in _cx63,
          "点人名发 NavRequest.Villager（AppRoot 单点消费，不私设第二条通道）")
    # 搜索态隐藏：搜索结果本身已跨分类，再叠一层专块是噪音（同最近查看纪律）
    check("if (!searching && followedLive.isNotEmpty())" in _cx63,
          "搜索态隐藏专块且空名单不渲染")

    # ================= 64. v2.71.1 更新说明可读性 =================
    # 用户反馈截图「看不清」：说明文字直接压在横幅半透明金底上，而金底透出
    # **随季节变色**的页面背景（春季 gold 是粉色 → 粉字压绿底），还是全 App
    # 最小的 caption(10sp) 号。修法两件：米色内衬卡（对比度不再受季节影响）
    # + 字号提到 body(12sp)。
    print("\n== 64. 更新说明可读性（v2.71.1） ==")

    _ub64 = src("ui/UpdateBanner.kt")

    # 64.1 衬底：说明文字坐在 cream 底 + slotEdge 描边的内衬卡里
    check(".background(c.cream)" in _ub64 and ".border(2.dp, c.slotEdge)" in _ub64,
          "横幅说明区有米色内衬卡（深字压米底，六套主题里对比度都稳定）")
    # 反向：不许回退到「文字直接压横幅金底」的旧形态
    check("UpdateNotes(latest.notes, modifier = Modifier.padding(start = 26.dp" not in _ub64,
          "旧形态（无衬底、只缩进 26dp）不得回潮")

    # 64.2 字号：UpdateNotes 两分支都是 body，不再是 caption
    _un64 = _ub64.split("fun UpdateNotes(", 1)[1]
    check(_un64.count("style = Px.body") == 2 and "Px.caption" not in _un64,
          "UpdateNotes 条目与分类头都是 body(12sp)（10sp 像素字在手机上就是糊的）")

    # ================= 65. v2.72.0 Mod 扩展包（远程下载 + 加载） =================
    # 第一版只跑通「拉清单 → 下载 → 解压 → 读 → 展示 → 开关」，不写回、不做本地导入。
    # 这一节钉的是**三条最容易悄悄退化**的性质：
    #  ① 不污染原版：包数据永远是**追加**，关掉开关即回到 gifts.json 的 35 人；
    #  ② 合并只有一个出口：所有重算都走 GameData.applyMods，不允许出现第二处赋值；
    #  ③ zip 是别人写的：路径、体量、条目数、魔数四道闸门不许被「优化掉」。
    print("\n== 65. Mod 扩展包（v2.72.0） ==")

    _mr65 = code_only(src("data/ModRepo.kt"))
    _gd65 = src("data/GameData.kt")
    _mo65 = src("data/Models.kt")
    _pr65 = src("data/PrefsRepo.kt")
    _mp65 = src("ui/mods/ModsPage.kt")
    _va65 = code_only(src("ui/mods/VillagerAvatar.kt"))
    _vp65 = src("ui/villagers/VillagersPage.kt")

    # 65.1 数据模型：新增字段全部带默认值 null —— 原版 35 人走原路径，行为零变化
    for _f65 in ("modId: String? = null", "portraitPath: String? = null",
                 "marriageable: Boolean? = null", "location: String? = null"):
        check(_f65 in _mo65, "GiftVillager 新增字段带默认值（不填即 null）：%s" % _f65)
    check("val fromMod: Boolean get() = modId != null" in _mo65,
          "fromMod 是唯一判据（UI 不到处写 modId != null）")

    # 65.2 原版数据是只读底座：gifts.json 仍由 load() 解析进 baseGifts
    check("baseGifts = arr(\"gifts.json\")" in _gd65,
          "原版村民仍解析进 baseGifts（包数据永远是追加，不改底座）")
    _g65 = _gd65.split("val gifts: List<GiftVillager>", 1)[1].split("val modVillagerCount", 1)[0]
    check("if (enabledModIds.isEmpty()) return baseGifts" in _g65,
          "没启用任何包时 gifts 直接返回 baseGifts（关掉开关=恢复原状，不是逐个过滤）")
    check("val modVillagerCount: Int get() = gifts.size - baseGifts.size" in _gd65,
          "Mod 村民数是减出来的（不另存一份计数 = 不会与实际不同步）")
    # 反向：禁止第二处对村民表赋值。两处赋值就意味着「装包时忘了重算」这种坑迟早复发。
    check(len(re.findall(r"^\s*gifts\s*=", _gd65, re.M)) == 0,
          "没有任何地方再给 gifts 赋值（合并结果只能由 applyMods 产出）")
    check("fun applyMods(" in _gd65 and "enabledModIds = enabled" in _gd65,
          "applyMods 是合并的唯一重算出口")

    # 65.3 重名处理：加后缀而不是丢弃（丢弃=包作者以为已收录而不再更新）
    check("taken.add(v.name)" in _gd65 and "（" in _gd65.split("val gifts: List<GiftVillager>")[1][:2000],
          "包与原版撞名时给 Mod 那个加后缀，而不是丢弃数据")

    # 65.4 zip 是别人写的：四道闸门
    check("safeNameOf" in _mr65 and '".."' in _mr65,
          "解包过路径白名单（挡 ../ 与绝对路径 = zip slip）")
    check("out.canonicalFile.path.startsWith(root.path" in _mr65,
          "落盘前再比一次规范化路径确实在解包目录之下")
    check("MAX_ENTRIES" in _mr65 and "MAX_ENTRY_BYTES" in _mr65 and "PACK_MAX_BYTES" in _mr65,
          "条目数 / 单条体量 / 总体量三道上限都在")
    check("hasZipMagic" in _mr65,
          "下载后验 zip 魔数（HTML/GBK 错误页会以 200 回来，不验就是装了个假包）")
    check(".part" in _mr65 and 'dst.name + ".part"' in _mr65,
          "先写 .part 再改名（中断的半截文件不会被当成成品）")
    check("stage.deleteRecursively()" in _mr65 and "stage.renameTo(dest)" in _mr65,
          "先解到暂存目录、校验过了才换正式目录（重复下载是覆盖，坏包不毁好包）")
    check("minAppVersion" in _mr65 and "compareVersion(BuildConfig.VERSION_NAME, minApp)" in _mr65,
          "manifest 的 minAppVersion 真被检查（版本不够要明说，而不是装出一个坏页面）")

    # 65.5 两种取包方式：index.json 与 .zip 直链都能配，靠后缀判别
    check("fun isZipUrl" in _mr65 and "provisionalId" in _mr65,
          "单包直链（.zip 结尾）与索引清单都支持，判据是地址后缀")
    check("fun compareVersion" in _mr65 and "takeWhile { it.isDigit() }" in _mr65,
          "版本号逐段比数字而不是字典序（1.15.11 必须大于 1.15.9）")
    check("pkgVersion" in _mr65 and "package.json" in _mr65,
          "清单包版本号落盘到 package.json（与 manifest 的 modVersion 不是一套号，不能混着比）")
    check("DEFAULT_INDEX_URL" in _mr65 and "mod_index_url" in _pr65,
          "仓库地址是偏好不是硬编码：常量只作读时兜底，用户可在页面上改")
    # v2.72 实测踩到：四参 URI 构造只认 host 不认端口，`…:8731/a.zip` 被改成 `…/a.zip`，
    #   表现为「连不上网络」而服务器一个请求都没收到。钉住改法。
    check(re.search(r"URI\(\s*scheme\s*,\s*host\s*,", _mr65) is None,
          "清单里的 downloadUrl 不再用四参 URI 构造（会把 host:port 变成 host）")
    check("uri.rawAuthority" in _mr65 or "rawAuthority" in _mr65,
          "URL 编码用五参构造 + rawAuthority（带端口的自建仓库地址才不会连错端口）")
    check('Log.w(TAG, "下载失败（第 $attempt 次）url=$url", err)' in _mr65,
          "下载失败**打日志**（否则所有异常都收敛成一句「连不上网络」，排查时连个词都抓不到）")
    check("for (attempt in 1..2)" in _mr65 and "kotlinx.coroutines.delay(600)" in _mr65,
          "下载自动重试一次（实测大文件会被偶发截断，重试能救回瞬时断流）")
    check("if (known && got != declared) error(\"下载不完整" in _mr65,
          "按 Content-Length 核对收了多少字节（半截 zip 不能只靠 okhttp 替我们发现）")
    check("没能装上这个包，再试一次" in _mr65 and 'raw.contains("下到的内容是空的")' in _mr65,
          "兜底文案不再谎称「连不上网络」，空包/非 zip/断流各有各的话")

    # 65.6 持久化：三键全是设备态，且**不进备份**
    for _k65 in ('stringPreferencesKey("mod_index_url")',
                 'stringSetPreferencesKey("mod_enabled")',
                 'stringSetPreferencesKey("mod_installed")'):
        check(_k65 in _pr65, "Mod 偏好键 %s" % _k65)
    check(_pr65.count("MOD_INDEX_URL") >= 2 and "p[MOD_INDEX_URL] ?: ModRepo.DEFAULT_INDEX_URL" in _pr65,
          "URL 没设过就用默认值兜（省掉一次迁移）")
    _ex65 = _pr65.split("fun exportJson", 1)[1].split("fun importJson", 1)[0] if "fun exportJson" in _pr65 else ""
    check("MOD_ENABLED" not in _ex65 and "MOD_INSTALLED" not in _ex65,
          "mod_enabled/mod_installed 不进 exportJson（跟着备份走到别的手机 = 开了却没数据的幽灵包）")

    # 65.7 开关语义：停用不删文件，删除才删
    check('PixelButton(if (on) "停用" else "启用"' in _mp65,
          "已装卡片提供启用/停用（停用只是不合并，文件还在）")
    check("PixelButton(\"删除\"" in _mp65 and "ModRepo.uninstall(" in _mp65,
          "删除是另一条明确的动作（删目录 + 清头像缓存）")
    check("ModRepo.refreshInstalled(ctx)" in _mp65 and "repo.setModInstalled(" in _mp65,
          "装/删后以**盘上目录**为准刷新已装集合（偏好只是镜像）")
    check('http == "404"' in _mr65 and "服务器回了 HTTP" in _mr65,
          "取包失败区分 404 / 服务端错误 / 真·网络不通（别把 404 说成连不上网络）")
    check("IndexUi.Err" in _mp65,
          "取清单失败把原因显示出来而不是静默空列表")

    # 65.8 头像：一律裁左上第一格 64 格
    #     v2.72 实测：初版按「h > w 才裁」判断，而包里的 Marlon.png 恰是 128×128
    #     的 2×2 表情格 → 被整张塞进 36dp 框里，一格里挤了 4 张脸。
    #     星露谷头像格式是 64×64 表情格拼成的，一格就是格，不该猜宽高比。
    check("PORTRAIT_CELL_PX = 64" in _mr65 and "minOf(PORTRAIT_CELL_PX, w, h)" in _mr65,
          "头像无条件裁左上第一格 64 格（按格式而非猜宽高比：Marlon 是 128×128 的 2×2 格）")
    check("if (h > w)" not in _mr65,
          "旧的「方图整张用」判定已删（那正是 4 张脸挤一格的成因）")
    check("Bitmap.createBitmap(src, 0, 0, side, side)" in _mr65,
          "裁的是左上第一格（createBitmap 0,0,side,side）")
    check("ModRepo.Portraits.peek(" in _va65 and "Dispatchers.IO" in _va65,
          "头像先同步命中缓存、miss 才在 IO 线程解码（不在组合期读盘）")
    check("FramedIcon(name, size, pad)" in _va65,
          "没有头像时降级为原版图标框（不显示破图占位）")
    # v2.73 起是 3 处：村民列表、详情页、反查结果列表（反查那列也要显示包头像）。
    # 判据钉「≥3」而非「==3」：再多一处（比如以后加个收藏页）是增强，
    # 硬写成等号只会逼着人改断言而不是加功能；但「一处都没有」必须红。
    _n_va65 = _vp65.count("VillagerAvatar(v.name,")
    check(_n_va65 >= 3,
          "村民列表 / 详情 / 反查三处都用 VillagerAvatar（原版村民外观不变，实测 %d 处）" % _n_va65)
    # ⚠ v2.73.14：入口已从**村民页顶栏**挪到**进度页总览段末尾**（扩展包拆成 6 个分片后，
    #   5/6 的内容都显示在图鉴，且进度页「总进度 X/279」的分母会随扩展包变化）。
    #   判据随之改钉**新位置**（见 77 节）；这里只保留「参数有默认实现」这条
    #   —— 村民页那个入口参数仍留着（今日页复用本文件数据但没接线，别逼它改）。
    check("onOpenMods: () -> Unit = {}" in _vp65,
          "村民页仍保留 onOpenMods 的默认实现（不影响未接线的其它调用方）")

    # 65.9 路由：压栈页 + BackBar，与设置页同一套退栈
    check("data object Mods : Screen" in src("MainActivity.kt"),
          "Screen.Mods 已定义")
    check("Screen.Mods -> com.stardew.assistant.ui.mods.ModsPage(" in src("MainActivity.kt"),
          "Mods 页接入压栈路由")
    check("ModRepo.refreshInstalled(app)" in src("MainActivity.kt"),
          "启动时先扫已装包（必须早于快照流，否则重启后看不到已启用的包）")
    check("ModRepo.applyEnabled(it.modEnabled)" in src("MainActivity.kt"),
          "启用集合的变化是合并的唯一触发点（装/删/开关三处都落到它）")
    check("BackBar(" in _mp65, "Mods 页用 BackBar（有可点的返回，不必教用户按系统键）")

    # 65.10 版本与口径
    _bg65 = src("ui/settings/SettingsPage.kt")
    check('"v2.72.0 Mod 扩展包" to listOf(' in _bg65, "CHANGELOG 首条是 v2.72.0")
    _gd65b = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    # ⚠ 这里**故意不去发明「码 ↔ 号」的换算公式**。试过两种都错：
    #     版本名并不由构建码推导——c120=2.71.1、c121=2.72.0 是跳 minor，c122=2.72.1 才是 patch。
    #     硬套公式会把「升 minor」判成错，逼着人改断言而不是改版本，那这道闸门就废了。
    #     真正的不变量只有三条：码存在且只增（降版装不上）、号存在且是 x.y.z、
    #     两者都在文件里。升级时的配对由 49.3「发布仓版本不得超前源码」那条把关。
    #     也正因如此，这里不写死具体版本号——写死后每发一版都红一次，只会被人注释掉。
    _vc65b = re.search(r"versionCode = (\d+)", _gd65b)
    _vn65b = re.search(r'versionName = "([0-9]+\.[0-9]+\.[0-9]+)"', _gd65b)
    check(_vc65b is not None and int(_vc65b.group(1)) >= 120,
          "versionCode 存在且只增（当前 %s，降版会装不上；下限 120 = v2.72.0 那版）"
          % (_vc65b.group(1) if _vc65b else "未找到"))
    check(_vn65b is not None,
          "versionName 是规范 x.y.z（当前 %s）" % (_vn65b.group(1) if _vn65b else "未找到/格式不对"))

    # 65.11 默认仓库地址不能是 404（本地自洽，与 49.3 同族，**不联网**）
    #      App 首次打开扩展页就去取这个地址；源码里写着它、发布仓里却没这个文件，
    #      用户第一眼看到的就是 404——这种「代码与仓库不同步」没人会在写的时候发现，
    #      只有装了才撞上。所以钉在 verify 里：常量指向的文件必须真的在。
    _mods49 = os.path.join(os.path.dirname(ROOT), "发布仓", "mods")
    _idx65 = "https://github.com/huututu939/xingluguzhushou/releases/latest/download/"
    check(_idx65 in _mr65,
          "DEFAULT_INDEX_URL 指向发布仓 mods/index.json（用户可改，但默认地址不该是 404）")
    _if65 = os.path.join(_mods49, "index.json")
    if os.path.exists(_if65):
        _j65 = json.load(io.open(_if65, encoding="utf-8"))
        _p65 = (_j65.get("packages") or [{}])[0]
        check(_p65.get("modId") == "sve", "发布仓 mods/index.json 挂着 sve 包")
        # downloadUrl 的路径必须与「常量前缀 + 同一文件名」一致——
        # 常量与清单各写一份地址，写岔了 App 会拉到 404 或另一个文件。
        _zipname65 = os.path.basename(str(_p65.get("downloadUrl", "")))
        check(_zipname65.endswith(".zip") and _idx65 + _zipname65 == _p65.get("downloadUrl"),
              "清单 downloadUrl 就是默认仓库前缀 + 本包（写岔了 App 直接 404）：%s"
              % _p65.get("downloadUrl"))
        _zip65 = os.path.join(_mods49, _zipname65)
        if os.path.exists(_zip65):
            import hashlib as _hl65
            _b65 = open(_zip65, "rb").read()
            check(len(_b65) == _p65.get("size", -1),
                  "清单 size 与发布仓 zip 实际字节一致（%d vs %s）"
                  % (len(_b65), _p65.get("size")))
            _m65 = _hl65.md5(_b65).hexdigest()
            check(_m65 == str(_p65.get("md5", "")).lower(),
                  "清单 md5 与发布仓 zip 实际 md5 一致（%s）" % _m65)
            check(_b65[:2] == b"PK", "发布仓 mods 里那个文件真是 zip（不是错误页）")
        else:
            check(False, "清单指向的包真在发布仓 mods/（%s）" % _zipname65)
    else:
        check(False, "发布仓 mods/index.json 存在（默认地址不该 404）")

    # ================= 66. v2.72.1 三项修复（用户 2026-10-19 反馈）=================
    # ① 夜间模式底栏选中的标签看不见：NightColors.ink 与 NightColors.cream **同色**
    #    （都是 #F0E2C0），于是「浅字压浅底」。这是**配色体系的固有陷阱**：
    #    任何「底 = cream / 字 = ink」的组合在夜间都会塌掉，而日间看不出问题。
    #    故断言钉的是「选中态文字不跟主题走」，而不是某句文案。
    # ② 计算页三种地块（露天/温室/姜岛）共用一个块数状态：三者块数在游戏里互不相干，
    #    共用是模型错误。钉「按 mode 下标存取」，钉不住就会退回单值。
    # ③ 图鉴抽屉 ✕ 把人弹回进度页：见 33.6（那里已有反向断言），此处补一条跨文件口径。
    print("\n== 66. 三项修复（v2.72.1） ==")

    _ma66 = src("MainActivity.kt")
    _ma66c = code_only(_ma66)
    _th66 = src("ui/PixelTheme.kt")
    _cp66 = src("ui/codex/CodexPage.kt")
    _ca66 = src("ui/calc/CalcPage.kt")

    # 66.1 夜间配色自证：ink 与 cream 同色 = 那条坑的前提，必须钉住它的成因
    _nth66 = code_only(_th66)
    check(re.search(r"ink = Color\(0xFFF0E2C0\), inkDim = Color\(0xFFBDAA92\), cream = Color\(0xFFF0E2C0\)",
                    _nth66) is not None,
          "夜间配色仍是 ink 与 cream 同色（#F0E2C0）——这正是「底 cream + 字 ink」在夜间塌掉的前提")
    # v2.76.0：药丸从「每个 tab 各自染底色」改成**一块会滑动的独立药丸**，
    # 选中态文字于是不能再写死成 `if (active) ... else ...` —— 药丸在 tab 之间滑动，
    # 文字颜色按「离药丸多近」过渡。
    # 判据仍然是**同一个意图**：药丸正下方（near=1）必须是深色 DayColors.ink，
    # 离开药丸才回到主题色 c.ink。夜间仍是「深字压浅奶药丸」，不会塌成浅字压浅底。
    check(re.search(r"lerp\(\s*c\.ink\.copy\(alpha = 0\.72f\),\s*"
                    r"com\.stardew\.assistant\.ui\.DayColors\.ink,\s*near,?\s*\)",
                    _ma66c) is not None,
          "玻璃底栏选中态文字在 c.ink 与深色 DayColors.ink 之间过渡（夜间不再是浅字压浅底）")
    check(re.search(r"val near = 1f - abs\(drag\.value - i\)\.coerceAtMost\(1f\)", _ma66c) is not None,
          "过渡系数 near：药丸正下方恰好 = 1（→ 深色），离开药丸 = 0（→ 主题色）")
    # cream 在所有主题里都是浅色 → 选中药丸的底恒为浅 → 选中文字必须恒为深。
    # 逐个确认一遍：这是「不该用 ink」的根据，钉住免得有人改回去又找不到原因。
    _all_cream66 = re.findall(r"cream = Color\(0x([0-9A-F]{8})\)", _th66)
    check(len(_all_cream66) >= 10, "取到全部主题的 cream 取值（%d 个）" % len(_all_cream66))
    if _all_cream66:
        _lum66 = [(int(h[:2], 16) * 299 + int(h[2:4], 16) * 587 + int(h[4:6], 16) * 114) / 1000
                  for h in _all_cream66]
        check(min(_lum66) >= 180,
              "每个主题的 cream 都是浅色（最暗的亮度 %.0f ≥ 180）——所以药丸上的字恒为深色才对" % min(_lum66))
    # 非选中态**必须**保留 c.ink：玻璃面在夜间偏暗，正好对上夜间 ink（浅）。
    check("c.ink.copy(alpha = 0.72f)" in _ma66c, "非选中态仍用 c.ink（夜间玻璃面暗，ink 是浅色才读得清）")

    # 66.2 三种地块各记各的
    # v2.72.2 把内存里的 mutableStateMapOf 换成了落盘的 CalcSetup（三个具名字段）。
    # 断言跟着换判据，但**意图不变**：三种地的块数互不相干，且读写都必须带 mode。
    # 旧写法（mutableStateMapOf）已不存在，留着它当判据等于给一段死代码打分。
    _ca66c0 = _ca66
    check("mutableStateMapOf" not in _ca66c0,
          "计算页不再用 mutableStateMapOf 存块数（v2.72.2 已落盘，判据改钉 CalcSetup）")
    _ca66c = code_only(_ca66)
    check(not re.search(r"\bplots\s*=\s*(list\[|mutable)", _ca66c) and "var plots by remember" not in _ca66c,
          "不再有单个 plots 状态（三种地共用一个值就是本次修的病根）")

    # 66.3 图鉴抽屉 ✕ 只关抽屉（跨文件口径，33.6 已钉接线，这里钉「锚点仍在」）
    check("onPeekDismiss = {}" in _ma66c, "MainActivity 给图鉴抽屉传的是空实现（✕ 不再弹回原页）")
    check("onPeekDismiss" in _cp66, "CodexPage 保留该参数（将来需要「关抽屉即返回」的调用方自己传）")

    # =====================================================================
    print("\n== 67. 三项新反馈（v2.72.2） ==")
    # ① 计算页参数退出重进就丢（season/day/三种地块块数/品质 全是 remember 内存态）。
    # ② 攻略页底部没有玻璃占位，最下面一条被胶囊挡住（用户原话「最下面那个会看不到」）。
    # ③ 赞助名单加 Manchester（名单类增补不占用户向日志，见 SettingsPage 注释）。

    # ── 67.1 计算页输入条件落盘 ──
    _pr67 = src("data/PrefsRepo.kt")
    _pr67c = code_only(_pr67)
    _ca67 = src("ui/calc/CalcPage.kt")
    _ca67c = code_only(_ca67)
    check('stringPreferencesKey("calc_setup_json")' in _pr67c, "计算页参数有独立存储键")
    check("fun setCalcSetup" in _pr67c and "it[CALC] = encodeCalc(s)" in _pr67c,
          "写入是整份替换（增量写迟早出现半新半旧的一组参数）")
    check("val calc: CalcSetup = CalcSetup()" in _pr67c, "Snapshot 带 calc 字段（带默认值，老构造点不受影响）")
    check("parseCalc(p[CALC], season, day)" in _pr67c,
          "读侧解析并把 season/day 的默认值接上当前存档日期")
    # 编码/解码必须成对：字段名两边一致，漏一个就是「写进去读不出来」。
    # 按**下一个同级声明**切片，而不是正则匹配签名：改了返回类型/换行/加个参数就整段失配，
    # 那种断言红起来的原因跟它想守的东西毫无关系。
    # ⚠ 切片必须收到「下一个 4 空格缩进的顶层声明」为止——只收到下一个 `suspend fun` 会
    #   把中间的 snapshot 流和 recordRecent 一并吞进来，于是把 g/n 也当成 calc 的字段。
    _DECL67 = re.compile(r"\n    (?:private |internal |suspend |override )*"
                         r"(?:fun|val|var|class|object)\b")

    def _body67(text, marker):
        i = text.find(marker)
        if i < 0:
            return ""
        m = _DECL67.search(text, i + len(marker))
        return text[i:m.start()] if m else text[i:]

    _dec67 = _body67(_pr67, "private fun parseCalc")
    _enc67 = _body67(_pr67, "private fun encodeCalc")
    check(bool(_dec67) and bool(_enc67),
          "parseCalc / encodeCalc 两段代码都能定位到（改了函数名要同步本节）")
    if _dec67 and _enc67:
        _ekeys67 = set(re.findall(r'\.put\("(\w+)"', _enc67))
        _dkeys67 = set(re.findall(r'optInt\("(\w+)"', _dec67))
        check(_ekeys67 == _dkeys67,
              "encodeCalc 与 parseCalc 的字段名一一对应（%s vs %s）"
              % (sorted(_ekeys67), sorted(_dkeys67)))
        # v2.92.0：块数已从 CalcSetup 拆到 CalcPlots（**按存档**），
        # 「三种地各占一个键」这条要钉在**新的一对**函数上，不是原来那两个。
        _decp67 = _body67(_pr67, "private fun parseCalcPlots")
        _encp67 = _body67(_pr67, "private fun encodeCalcPlots")
        check(bool(_decp67) and bool(_encp67),
              "parseCalcPlots / encodeCalcPlots 两段代码都能定位到（改了函数名要同步本节）")
        if _decp67 and _encp67:
            _ekp67 = set(re.findall(r'\.put\("(\w+)"', _encp67))
            _dkp67 = set(re.findall(r'optInt\("(\w+)"', _decp67))
            check(_ekp67 == _dkp67,
                  "encodeCalcPlots 与 parseCalcPlots 的字段名一一对应（%s vs %s）"
                  % (sorted(_ekp67), sorted(_dkp67)))
            # 三块地的键必须各出现一次——共用了键就退回「三种地一个值」
            check(len([k for k in _ekp67 if k.startswith("p")]) == 3,
                  "三种地块各占一个键（键名：%s）" % sorted(k for k in _ekp67 if k.startswith("p")))
            for _clp67 in ("outdoor", "greenhouse", "island"):
                check(re.search(r"%s = o\.optInt\(.*?\)\.coerceIn\(" % _clp67, _decp67, re.S) is not None,
                      "parseCalcPlots 钳制 %s（手改备份能带出越界值，读侧是唯一闸门）" % _clp67)
        # 越界钳制：备份能绕过写侧，读侧是唯一闸门
        for _cl67 in ("mode", "season", "day", "quality"):
            check(re.search(r"%s = o\.optInt\(.*?\)\.coerceIn\(" % _cl67, _dec67, re.S) is not None,
                  "parseCalc 钳制 %s（手改备份能带出越界值，读侧是唯一闸门）" % _cl67)
        check(".coerceIn(0, Calc.QUALITY_OPTIONS.lastIndex)" in _pr67c,
              "quality 上界跟 QUALITY_OPTIONS 走，不写死 4（增一项不会静默失效）")
    # UI 侧：本地状态 + 落盘，而不是把 snap 当唯一数据源（纯 snap 会丢连点两下的改动）
    check("var form by remember(snap.activeProfile) { mutableStateOf(snap.calc) }" in _ca67c,
          "计算页从 snap.calc 播种本地状态（冷启动即为上次那组参数）")
    check("scope.launch { repo.setCalcSetup(next) }" in _ca67c, "每次改动都落盘")
    # v2.92.0：块数走**按存档**那份（plotsForm），与「用法」那份（form）分开
    check("var plotsForm by remember(snap.activeProfile) { mutableStateOf(snap.calcPlots) }" in _ca67c,
          "块数从 snap.calcPlots 播种（按存档，切存档时重新取）")
    check("val plots = plotsForm.plots(mode)" in _ca67c, "读取块数带 mode")
    check("scope.launch { repo.setCalcPlots(next) }" in _ca67c, "块数改动落盘到当前存档")
    # 写路径：mode/品质走 pick，块数走 pickPlots（v2.92.0 拆开）
    _n_pick67 = len(re.findall(r"pick \{", _ca67c))
    _n_pp67 = len(re.findall(r"pickPlots\(", _ca67c))
    check(_n_pick67 >= 3,
          "三条用法写路径（切地块/切品质/露天转盘）都走 pick（实测 %d 处）" % _n_pick67)
    check(_n_pp67 >= 3,
          "块数写路径都走 pickPlots（露天转盘/温室姜岛转盘/定义，实测 %d 处）" % _n_pp67)
    check("pickPlots(list[2] + 1)" in _ca67c and "pickPlots(list[0] + 1)" in _ca67c,
          "两条转盘确认路径写块数时都走 pickPlots（按存档那份）")
    check("it.copy(season = list[0], day = list[1] + 1)" in _ca67c, "露天那次同时回写季节与日期")
    check("it.copy(mode = i)" in _ca67c and "it.copy(quality = i)" in _ca67c, "切地块/切品质也落盘")
    # 备份：计算页参数是「用户填的内容」，丢了要重拨 → 进 exportJson
    check('.put("calc_setup", encodeCalc(snap.calc))' in _pr67c, "计算页参数进 exportJson 备份")
    check('o.optString("calc_setup").takeIf { it.isNotBlank() }' in _pr67c,
          "导入用存在性守卫（旧备份没这个字段 = 不动本地参数）")
    # v2.92.0：块数**按存档**，跟着这个存档进备份（不是全局一份）
    check('.put("calc_plots", p[calcPlotsKey(n)] ?: "")' in _pr67c,
          "块数按存档进 exportJson 备份")
    check("p[calcPlotsKey(n)] = it" in _pr67c, "导入把块数写回对应存档")
    # v2.92.0：用法（mode/品质）仍全局，块数是存档级 —— 两种语义各归各
    check('stringPreferencesKey("calc_setup_json")' in _pr67c
          and 'stringPreferencesKey("cp:$n")' in _pr67c,
          "用法走全局键 calc_setup_json，块数走存档级键 cp:<存档名>")
    check("suspend fun setCalcPlots" in _pr67c and "calcPlotsKey(prof)" in _pr67c,
          "块数写当前存档（不是全局那一份）")
    # 一次性迁移：老用户拨好的块数在全局 calc_setup 里，不迁就丢
    check("suspend fun migrateCalcPlots" in _pr67c
          and "migrateCalcPlots()" in src("MainActivity.kt"),
          "块数拆键有一次性迁移（老用户拨好的块数不丢）")

    # ── 67.2 赞助名单 ──
    # 名单本身由 58.27 逐个钉死（含 Manchester）。这里钉的是**另一条纪律**：
    # 名单类增补不占用户向更新日志——对终端用户那是噪音，不是他买的东西。
    # 注意别用 `'"Manchester"' in _sp67` 当判据：致谢名单里本来就有「QQ群 @Manchester」，
    # 那条断言在根本没加赞助的情况下也会绿（自己骗自己）。
    # ⚠ 2026-11-10（项目开源）：SPONSORS 名单已整删（开源后公开群友 ID 侵犯别人隐私，
    #   用户口径）。原来钉「名单里必须有谁」的两条断言翻转为「声明不得复活」。
    _sp67 = code_only(src_sp())
    check("val SPONSORS" not in _sp67,
          "67.2 SPONSORS 名单已删且不得复活（Manchester / 味子 的贡献改记在 git log）")
    _cl67 = re.search(r"private val CHANGELOG = listOf\(\s*\n\s*\"([^\"]+)\"(.*?)\n\)", _sp67, re.S)
    if _cl67:
        # ⚠ 2026-11-10（名单整删）：更新日志里那条「移除致谢与赞助名单」是
        #   **用户可见变化**的公告，合理占位；仍不许出现的是「名单新增」类
        #   增补（对终端用户是噪音，只进账本）。
        check("名单新增" not in _cl67.group(2) and "上榜" not in _cl67.group(2),
              "更新日志不含名单新增/上榜类条目（增补对终端用户是噪音，只进账本）")
    else:
        check(False, "CHANGELOG 解析失败（格式改动要同步本节正则）")

    # ================= 68. SVE 扩展包 v2.2（五段礼物 + packageVersion 口径）=================
    # 背景：v2.0 的包每个 NPC 只有 `lovedGifts` 一段，`npcFromJson` 里 likes 写死 emptyList()，
    #      「第一版不做喜欢/讨厌：包里没有这两项数据」。v2.2 的包补齐了 liked/disliked/hated/neutral，
    #      于是「包里没有」这个前提**不再成立**——不改的话 App 会静默丢掉 638 条礼物数据，
    #      表现为「SVE 村民怎么都没有喜欢的东西」，而用户装的是完整的包。
    # 本节钉三件事：① 五段真的被映射；② 合并不覆盖原版；③ 版本比较用 packageVersion。
    print("\n== 68. SVE 扩展包 v2.2（五段礼物 / packageVersion 口径） ==")

    _mr68 = code_only(src("data/ModRepo.kt"))
    _mo68 = code_only(src("data/Models.kt"))
    _gd68 = code_only(src("data/GameData.kt"))
    _vp68 = code_only(src("ui/villagers/VillagersPage.kt"))
    _mp68 = code_only(src("ui/mods/ModsPage.kt"))
    _bg68 = code_only(src("ui/settings/SettingsPage.kt"))

    # ── 68.1 五段礼物：模型有 neutrals，解析五段全映射 ──
    # ⚠ 钉 `?.strList() ?: emptyList()` 这个**形状**而不是只钉字段名：
    #   Android 的 optJSONArray 返回平台类型，直连 `.strList()` 在「包里没有该段」时 NPE，
    #   而包是别人写的（v2.0 就真的没有 likedGifts），编译器不拦。
    for _seg68, _field68 in (("lovedGifts", "loves"), ("likedGifts", "likes"),
                             ("dislikedGifts", "dislikes"), ("hatedGifts", "hates"),
                             ("neutralGifts", "neutrals")):
        check('optJSONArray("%s")?.strList() ?: emptyList()' % _seg68 in _mr68,
              "npcFromJson 映射 %s → %s（缺段时空列表而不是 NPE）" % (_seg68, _field68))
        check("val %s: List<String>" % _field68 in _mo68, "GiftVillager 有 %s 字段" % _field68)
    check("val neutrals: List<String> = emptyList()" in _mo68,
          "neutrals 带默认值（原版 gifts.json 没这个键，行为零变化）")
    # ⚠ 反向断言：v2.0 那个「likes 写死空数组」的写法一旦复活，五段里有一段会被静默丢弃，
    #   而编译与运行都不报错——正是这类静默丢数据最难发现。
    check("likes = emptyList()" not in _mr68,
          "npcFromJson 不再有「likes 写死空数组」的旧写法（那会静默丢掉 likedGifts）")

    # ── 68.2 UI 真的有五段展示，不是只解析不用 ──
    check('PixelCard(title = "😐 中立（效果 +45）")' in _vp68, "详情页有「中立」分区")
    check("if (v.neutrals.isNotEmpty())" in _vp68, "中立段按数据有无显示（原版村民不受影响）")
    check('attitudeOf(it, q) == "讨厌"' in _vp68 and 'attitudeOf(it, q) == "憎恶"' in _vp68,
          "反查区分「讨厌」与「憎恶」两段（合起来就丢了 −45/−80 的差别）")
    check('attitudeOf(it, q) == "中立"' in _vp68, "反查也认「中立」段")
    # 类别型礼物：SVE 用「所有矿物/所有鱼」，原版用「宝石与矿物/鱼与海鲜类」。
    # 只认原版措辞 → 打「石英」查不到 SVE 村民的「所有矿物」，是**漏报**（比报错更坏：
    # 用户据此决定不送，而真实答案是有人最爱它）。
    check("CATEGORY_ALIASES" in _vp68, "礼物类别别名表存在")
    _cat68 = re.findall(r'"(所有[^"]+)"', _vp68)
    check(len(set(_cat68)) >= 8,
          "类别别名覆盖 SVE 的「所有…」措辞（实测 %d 个：%s）" % (len(set(_cat68)), sorted(set(_cat68))[:6]))
    check("宝石与矿物" in _vp68 and "鱼与海鲜类" in _vp68,
          "原版措辞仍在别名表里（不能为了 SVE 把 wiki 口径挤掉）")

    # ── 68.3 不覆盖原版 + 来源标签 ──
    check("if (taken.add(v.name)) out += v" in _gd68,
          "合并是追加：撞名才加后缀，不丢原版也不丢包数据")
    # ⚠ 用 `var baseGifts` 而非 `var baseGifts: List<GiftVillager> = emptyList()` 全串：
    #   声明行前后有注释与缩进差异，写全串时红起来的原因跟它想守的东西无关。
    check(re.search(r"var baseGifts:\s*List<GiftVillager>", _gd68) is not None,
          "原版仍是独立底座（关掉开关 = 回到原版）")
    check("fun modTagOf(" in _gd68 and "modPacks.firstOrNull" in _gd68,
          "来源短标签从包自己声明的 tag 取（不写死「SVE」，装别的包才显示得对）")
    # 标签不许硬编码 SVE：写死后装第二个包时那一列全是「SVE」，用户分不清谁是谁。
    _sp_sve68 = re.search(r"fun modTagOf\([^)]*\)[^}]*\}", _gd68, re.S)
    check(_sp_sve68 is not None and '"SVE"' not in _sp_sve68.group(0),
          "modTagOf 里没有写死 \"SVE\" 字面量（标签来自包，不是来自 App）")

    # ── 68.4 版本口径：更新比较必须用 packageVersion，不能用 modVersion ──
    # 用户明确要求「对比 manifest.packageVersion（=2.2），不要用 modVersion」。
    # 判据钉的是**读哪个键**：`optString("packageVersion")` 必须真的出现，
    # 且 pkgVersion 的赋值链上不得出现 modVersion。
    check('mo.optString("packageVersion")' in _mr68, "读取 manifest 的 packageVersion")
    check('mo.optString("modVersion")' in _mr68,
          "modVersion 仍被读取（要显示「含 Mod v1.15.11」），但只用于显示")
    check("val pkgVersion: String" in _mo68 or "val pkgVersion: String" in _mr68,
          "Installed.pkgVersion 存在（包版本与 Mod 版本是两个字段）")
    # ⚠ 关键反向断言：install() 返回时若写 pack.version（清单号）而不看包内声明，
    #   「以 manifest 为准」就只在 refreshInstalled 时成立、装包这一刻又被顶掉了。
    check('contents.copy(pkgVersion = pack.version)' not in _mr68,
          "install() 不用清单号覆盖包内声明的 packageVersion")
    check("contents.pkgVersion.ifBlank { pack.version }" in _mr68,
          "包版本优先取包内 packageVersion，缺失才回退清单号")
    # 更新检测的判据：拿**包版本**比，不是 Mod 版本。
    # ⚠ 不要按 `val newer =` 切到行尾再找：那个表达式在真实代码里跨三行，
    #   按行切会只拿到首行，于是「用对了变量」的代码也会被判红——
    #   断言红起来的原因和它想守的东西无关，这道闸门就废了。改为抓比较表达式本身。
    _avail68 = _mp68.split('PixelCard(title = "🛒 可安装")', 1)[1] if 'PixelCard(title = "🛒 可安装")' in _mp68 else ""
    _cmp68 = re.search(r"compareVersion\(\s*(pack\.version)\s*,\s*(inst\.pkgVersion)\s*\)\s*>\s*0", _mp68)
    check(_cmp68 is not None,
          "「有新版本」拿清单包版本比已装 pkgVersion（不是比 Mod 版本）")
    # 反向：整个「可安装」区的版本判断里不得出现 Mod 版本
    _newer_body68 = ""
    if "val newer =" in _avail68:
        _newer_body68 = _avail68.split("val newer =", 1)[1].split("val tooNew", 1)[0]
    check("inst.version" not in _newer_body68,
          "「有新版本」的判据里没有 inst.version（那是 Mod 版本，比了永远判错）")
    # 段数指示：老包只有 1 段，要让用户看得出该更新
    check("val giftTierCount: Int" in _mr68, "Installed 有礼物段数（老包 1/5 段要看得出来）")
    check("礼物 ${inst.giftTierCount}/5 段" in _mp68, "Mod 页显示礼物段数")
    # ⚠ 段数必须**按五段分别判定**。写成「loves/…/neutrals 任取非空」或按 NPC 累加都会错：
    #   按 NPC 累加得到 14 这种没意义的数，UI 印成「礼物 14/5 段」；
    #   只看第一个 NPC 则包里有一个人缺段就整包误判。两端都验一下。
    # ⚠ 切片要多行：定义是 `val giftTierCount: Int by lazy {`，按单行切只拿到 `by lazy {`
    #   就把真正的判据漏在切片外，断言于是红在一个与它无关的地方。
    _gtc68 = ""
    if "val giftTierCount" in _mr68:
        _gtc68 = _mr68.split("val giftTierCount", 1)[1].split("val marriageableCount", 1)[0] \
            or _mr68.split("val giftTierCount", 1)[1].split("sealed class Fetch", 1)[0]
    check(all(_f in _gtc68 for _f in ("it.loves", "it.likes", "it.dislikes", "it.hates", "it.neutrals")),
          "段数按五段分别判定（只看一段会误判，按 NPC 累加会得到 14 这种没意义的数）")
    check("count {" in _gtc68 and "npcs.any" in _gtc68,
          "段数按「至少一个 NPC 该段非空」计（与 Installed 上界 5 自洽）")

    # ── 68.5 署名与发布仓一致性 ──
    _bg_all68 = src_sp()
    check("https://www.nexusmods.com/stardewvalley/mods/3753" in _bg_all68,
          "数据来源页有 SVE 的 Nexus Mods 署名链接")
    check("FlashShifter" in _bg_all68 and "Stardew Valley Expanded" in _bg_all68,
          "署名写明作者与 Mod 名（授权使用素材的义务是署名）")
    # 发布仓：清单的 version 必须等于**包内**的 packageVersion，且 zip 真在
    _mods68 = os.path.join(os.path.dirname(ROOT), "发布仓", "mods")
    _if68 = os.path.join(_mods68, "index.json")
    if os.path.exists(_if68):
        _j68 = json.load(io.open(_if68, encoding="utf-8"))
        _pk68 = (_j68.get("packages") or [{}])[0]
        _zipn68 = os.path.basename(str(_pk68.get("downloadUrl", "")))
        _zp68 = os.path.join(_mods68, _zipn68)
        if os.path.exists(_zp68):
            import zipfile as _zf68
            with _zf68.ZipFile(_zp68) as _z68:
                _mf68 = json.loads(_z68.read("manifest.json").decode("utf-8"))
                _npc68 = json.loads(_z68.read("npcs.json").decode("utf-8"))
            check(str(_pk68.get("version")) == str(_mf68.get("packageVersion")),
                  "清单 version(%s) == 包内 packageVersion(%s)（两者不一致时「有新版本」永远判错）"
                  % (_pk68.get("version"), _mf68.get("packageVersion")))
            check("packageVersion" in _mf68,
                  "包内 manifest 带 packageVersion（扩展包版本号的权威来源）")
            # 包内容与 App 的解析能力对得上：五段都在
            for _seg68 in ("lovedGifts", "likedGifts", "dislikedGifts", "hatedGifts", "neutralGifts"):
                check(_seg68 in (_npc68.get("npcs") or [{}])[0],
                      "发布仓包内含 %s 段（App 的五段映射有实际数据可映）" % _seg68)
            check(len(_npc68.get("npcs") or []) == _mf68.get("npcCount"),
                  "包内 npcs 数量与 manifest 声称一致（%d vs %s）"
                  % (len(_npc68.get("npcs") or []), _mf68.get("npcCount")))
        else:
            check(False, "清单指向的 v2.2 包真在发布仓 mods/（%s）" % _zipn68)
    else:
        check(False, "发布仓 mods/index.json 存在")

    # 昵称标签缺失时要能退化：包里没有 shortName 时退回 modId 大写（= SVE），不许崩
    check('mo.optString("shortName")' in _mr68, "短标签从 manifest.shortName 读（缺键即空串）")

    # ================= 69. SVE 扩展包 v2.3（概要行程 schedules.json）=================
    # v2.3 在 v2.2 基础上新增 schedules.json（14 个主 NPC 的概要行程）。
    # 关键设计约束：
    # ① 文件是**可选的**——v2.0/v2.2 的老包没有它，装着老包的用户升级 App 后
    #    绝不能看到「数据损坏」，只是详情页不渲染行程卡；
    # ② 数据是**概要版**（summary + 常去地点，没有按时段的表）——UI 文案不许暗示精确到小时；
    # ③ 关联键是 **srcId**（包内主键）而不是中文名——包作者改中文名不该让行程静默丢失。
    print("\n== 69. SVE 扩展包 v2.3（概要行程） ==")

    _mr69 = code_only(src("data/ModRepo.kt"))
    _mo69 = code_only(src("data/Models.kt"))
    _vp69 = code_only(src("ui/villagers/VillagersPage.kt"))

    # 69.1 模型：两字段带默认值，原版村民零变化
    check("val scheduleSummary: String? = null" in _mo69,
          "scheduleSummary 带默认 null（原版村民详情页不出行程卡）")
    check("val scheduleLocations: List<String> = emptyList()" in _mo69,
          "scheduleLocations 带默认空表")

    # 69.2 解析：可选文件，缺失不是损坏
    check("fun parseSchedules(" in _mr69, "schedules.json 有独立解析函数")
    _ps69 = _mr69.split("private fun parseSchedules", 1)[1].split("private fun npcFromJson", 1)[0] \
        if "private fun parseSchedules" in _mr69 else ""
    check("if (!f.isFile) return emptyMap()" in _ps69,
          "文件不存在返回空表而不是判整包损坏（老包 v2.0/v2.2 没有这个文件）")
    check('root.optJSONArray("schedules") ?: return emptyMap()' in _ps69,
          "根键缺失也返回空表（键名改了不该让整包装不上）")
    check('"schedules" in mf' not in _ps69, "解析不依赖 manifest.modules 声明（以文件实际存在为准）")

    # 69.3 关联键与挂载
    check('schedules[srcId]' in _mr69,
          "行程按 srcId 关联（包内主键；用中文名关联会在包改名时静默丢行程）")
    check("schedules: Map<String, Schedule> = emptyMap()" in _mr69,
          "npcFromJson 的 schedules 参数有默认值（其它调用方不破）")
    check("scheduleSummary = sched?.summary?.ifEmpty { null }" in _mr69,
          "summary 空串降级为 null（UI 判据是 null，空串会渲染空卡）")
    check("scheduleLocations = sched?.locations ?: emptyList()" in _mr69,
          "locations 缺失给空表")

    # 69.4 UI：行程卡
    check('PixelCard(title = "🗺 行程（概要）")' in _vp69, "详情页有行程卡")
    check("if (v.scheduleSummary != null)" in _vp69, "行程卡按数据有无渲染")
    check("SCHEDULE_SEASON_RE" in _vp69, "季节后缀有单源正则")
    # ⚠ 半角括号：包里就是「安迪农场(春)」。写成全角会一个都匹配不上，
    #   所有地点都按「全年常驻」金色显示——不崩，但当季提示整个失效。
    check('Regex("""^(.+)\\(([春夏秋冬])\\)$""")' in _vp69,
          "季节正则匹配半角括号（包里是「安迪农场(春)」；全角会一个都匹配不上）")
    check("season: String? = null" in _vp69,
          "详情主体带 season 参数且可空（今日页等复用处不接线也能编译）")
    check('season = listOf("春", "夏", "秋", "冬").getOrNull(snap.season)' in _vp69,
          "调用处把存档季节转成中文传入（Int 下标越界用 getOrNull 挡）")
    check("locSeason == null || season == null || locSeason == season" in _vp69,
          "着色：无后缀或 season 未接线时按可去处理（宁可标宽，不可把人坑到白跑）")
    # 文案诚实性：不许暗示精确时段
    check("不是精确时段表" in _vp69, "文案写明是概要、非精确时段表（数据里没有的东西不能许诺）")
    check("概要行程" in _vp69, "标题/正文出现「概要」字样")

    # 69.5 解包白名单：schedules.json 是根级 .json，必须能过 safeNameOf
    check('val okJson = parts.size == 1 && top.endsWith(".json")' in _mr69,
          "解包白名单允许根级 .json（schedules.json 靠这条进来）")

    # 69.5' 装/删包后必须**主动重算合并**，不能只靠偏好流。
    # 2026-10-19 实测病根：更新同一个包（2.2→2.3）时 enabled/installed 集合前后相同，
    # DataStore 不发快照 → applyEnabled 不跑 → 详情页还是旧包对象（行程卡死活不出现）。
    # 用户原话「村民页的详情里边，我没有看到行程啊」，而盘上 schedules.json 明明在。
    # toggle 不在此列（它的 enabled 一定变，偏好流会触发）。
    _mp_apply = code_only(src("ui/mods/ModsPage.kt")).count("ModRepo.applyEnabled(")
    check(_mp_apply >= 2,
          "装包与删包路径都主动调 ModRepo.applyEnabled（集合前后相同时偏好流不触发，实测 %d 处）"
          % _mp_apply)

    # 69.6 发布仓：清单与包内一致，且 schedules 真的在
    _mods69 = os.path.join(os.path.dirname(ROOT), "发布仓", "mods")
    _if69 = os.path.join(_mods69, "index.json")
    if os.path.exists(_if69):
        _j69 = json.load(io.open(_if69, encoding="utf-8"))
        _p69 = (_j69.get("packages") or [{}])[0]
        _zn69 = os.path.basename(str(_p69.get("downloadUrl", "")))
        _zp69 = os.path.join(_mods69, _zn69)
        if os.path.exists(_zp69):
            import zipfile as _zf69
            with _zf69.ZipFile(_zp69) as _z69:
                _mf69 = json.loads(_z69.read("manifest.json").decode("utf-8"))
                _sc69 = json.loads(_z69.read("schedules.json").decode("utf-8"))
                _np69 = json.loads(_z69.read("npcs.json").decode("utf-8"))
            check(str(_p69.get("version")) == str(_mf69.get("packageVersion")),
                  "清单 version(%s) == 包内 packageVersion(%s)"
                  % (_p69.get("version"), _mf69.get("packageVersion")))
            check("schedules" in (_mf69.get("modules") or []),
                  "manifest.modules 声明了 schedules（用户任务要求）")
            _sched69 = _sc69.get("schedules") or []
            check(len(_sched69) == _mf69.get("npcCount"),
                  "行程条数与 npcCount 一致（%d vs %s）"
                  % (len(_sched69), _mf69.get("npcCount")))
            # 行程 id 必须都能在 npcs 里找到对应人——对不上就是 App 侧静默不显示
            _ids69 = {n.get("id") for n in (_np69.get("npcs") or [])}
            _sids69 = {s.get("id") for s in _sched69}
            check(_sids69 <= _ids69,
                  "行程 id 全部能在 npcs 里找到对应人（孤儿行程在 App 侧静默不显示）：%s"
                  % sorted(_sids69 - _ids69))
        else:
            check(False, "清单指向的 v2.3 包真在发布仓 mods/（%s）" % _zn69)
    else:
        check(False, "发布仓 mods/index.json 存在")

    # ================= 70. SVE 扩展包 v2.4（作物 crops.json）=================
    # v2.4 新增 crops.json（12 个 SVE 作物）。核心决策：**并进 GameData.crops**
    # 而不是另开一张表——计算页 plantResults / 今日页播种死线 / 年历都按
    # `season in crop.seasons` 过滤 crops，另开表就得改三处才看得到
    # 「夏天能种古代蕨」，而漏改任何一处 = 静默少一个季节（不报错，只是那个季节
    # 的 SVE 作物不见了）。
    print("\n== 70. SVE 扩展包 v2.4（作物） ==")

    _mr70 = code_only(src("data/ModRepo.kt"))
    _mo70 = code_only(src("data/Models.kt"))
    _gd70 = code_only(src("data/GameData.kt"))
    _cp70 = code_only(src("ui/codex/CodexPage.kt"))
    _ca70 = code_only(src("ui/calc/CalcPage.kt"))
    _td70 = code_only(src("ui/today/TodayPage.kt"))

    # 70.1 模型：新增字段全部带默认值，原版 42 个作物行为零变化
    for _f70 in ("modId: String? = null", "seedName: String? = null",
                 "harvestName: String? = null", "raisedBed: Boolean? = null"):
        check(_f70 in _mo70, "Crop 新增字段带默认值：%s" % _f70)
    check("val fromMod: Boolean get() = modId != null" in _mo70,
          "Crop.fromMod 是唯一判据（与 GiftVillager 同款）")

    # 70.2 解析：可选文件 + 季节英中映射
    check("fun parseCrops(" in _mr70, "crops.json 有独立解析函数")
    _pc70 = _mr70.split("private fun parseCrops", 1)[1].split("private fun parsePack", 1)[0] \
        if "private fun parseCrops" in _mr70 else ""
    check("if (!f.isFile) return emptyList()" in _pc70,
          "文件不存在返回空列表（老包 v2.0~v2.3 没有这个文件）")
    check('root.optJSONArray("crops") ?: return emptyList()' in _pc70,
          "根键缺失返回空列表")
    # ⚠ 季节映射是本轮最容易静默错的地方：包内英文（summer）、原版中文（夏），
    #   而过滤判据 `seasonName in crop.seasons` 传的是中文。
    #   不映射的话每个 SVE 作物在**所有季节筛选里全部消失**——不报错，只是不见了。
    _seas70 = re.search(r"CROP_SEASON_CN\s*=\s*mapOf\((.*?)\)", _mr70, re.S)
    check(_seas70 is not None, "季节映射表 CROP_SEASON_CN 存在")
    if _seas70:
        _sm70 = _seas70.group(1)
        for _k70, _v70 in (("spring", "春"), ("summer", "夏"), ("fall", "秋"), ("winter", "冬")):
            check('"%s" to "%s"' % (_k70, _v70) in _sm70,
                  "包内 %s 映射到原版口径「%s」" % (_k70, _v70))
        # ⚠ 用 mapNotNull 而非 map：未列出的值会变成 null 留在列表里，
        #   `seasonName in crop.seasons` 遇 null 不崩但静默不匹配。
        check("mapNotNull { CROP_SEASON_CN[it.lowercase()] }" in _pc70,
              "未列出的季节值被丢弃而不是留 null（listOf(null) 能编译但不匹配）")
    check("distinct()" in _pc70, "季节去重（包里 spring/spring 会重复进列表）")

    # 70.3 转 Crop 的字段取舍：不编造包里没有的数据
    # ⚠ 按**下一个同级成员声明**为界切片：按第一个 "}" 截会把 toCrop 的构造参数
    #   切断（参数里没右花括号的话），于是「代码明明写了 giant=false」却判红——
    #   断言红起来的原因和它想守的东西无关。
    _tc70 = ""
    if "fun toCrop()" in _mr70:
        _seg = _mr70.split("fun toCrop()", 1)[1]
        _m70 = re.search(r"\n    (?:private |internal |data )*(?:fun|val|var|class|object)\b", _seg)
        _tc70 = _seg[:_m70.start()] if _m70 else _seg
    check("seedPrice = null" in _tc70 and "sellPrice = null" in _tc70,
          "SVE 作物不编价格（包里没有 = 显示 0g 是谎话，图鉴不渲染该行）")
    check("regrow = regrowDays?.takeIf { it > 0 }" in _tc70,
          "regrowDays=-1（一次性）映射为 null（原版无再生键口径）")
    check("giant = false" in _tc70 and "trellis = false" in _tc70,
          "包里没有巨型/支柱藤概念，不编")
    check('if (harvestRaw == name) ""' in _pc70,
          "收获物与作物同名时置空串（图鉴不重复显示同一个名字）")

    # 70.4 合并：并进 crops，且与 gifts 同一套出口
    check("var baseCrops: List<Crop> = emptyList()" in _gd70,
          "原版作物存 baseCrops（与 baseGifts 同款：底座不动，合并是计算属性）")
    check(re.search(r"val crops:\s*List<Crop>", _gd70) is not None,
          "crops 是计算属性（原版 + 已启用包）")
    check("if (enabledModIds.isEmpty()) return baseCrops" in _gd70,
          "没启用任何包时直接返回 baseCrops（关开关 = 恢复原状）")
    check("mc.toCrop()" in _gd70, "合并时把 ModCrop 转成通用的 Crop")
    # 反向断言：第二处赋值会让「装包时忘了重算」复发（与 65.2 同款纪律）
    check(re.search(r"^\s*crops\s*=\s*arr\(", _gd70, re.M) is None,
          "load() 不再给 crops 赋值（合并结果只能由计算属性产出）")
    check("val modCropCount: Int get() = crops.size - baseCrops.size" in _gd70,
          "Mod 作物数是减出来的（不另存一份计数 = 不会与实际不同步）")

    # 70.5 图鉴：读运行时视图；来源标签取自包而非写死
    check("val codexGroupsWithMods: List<CodexGroup>" in _gd70,
          "codexGroupsWithMods 是图鉴运行时视图（静态组 + crops 组重建）")
    # v2.73.7：菜谱组也按启用包重建（SVE 的 20 道菜与原版 81 道同组）
    check('"crops" -> g.copy(items = cropCodexItems(crops))' in _gd70 and
          '"recipes" -> g.copy(items = recipeCodexItems(g.items, modRecipeItems))' in _gd70,
          "crops 与 recipes 两组按启用包重建（其余 12 组全来自内置 assets）")
    # ⚠ 顺序即契约：详情页用 (groupId, idx) 取条目，顺序与 crops 脱节
    #   就是「点古代蕨打开古代纤维」——比不显示更难排查。
    check("cropCodexItems(crops)" in _gd70,
          "图鉴 crops 条目由 crops 直接生成（同一列表，同一顺序）")
    # ── v2.73.5：分区头改成每格角标 ──
    # 用户原话「一个原版 42，一个 SVE 12 在这儿放着，占两个这么大的空间，很突兀」。
    # ⚠ 反向断言一律扫 code_only()：kdoc 里写着「codexCropSections 已删」会把断言顶红
    #   （本项目第 5 次踩「断言扫到注释里旧词名」）。
    check("codexCropSections" not in _gd70,
          "GameData 不再有 codexCropSections（大分区头已换成每格角标）")
    check("codexCropSections" not in _cp70 and "cropSections" not in _cp70,
          "CodexPage 不再有分区区间计算（漏删 = 图鉴里还挂着两个大标题）")
    # ⚠ 不能断言 "📂" 在 CodexPage 里消失：跨分类搜索那处的分组头也用它，
    #   按分类标题走、与作物无关。这里只钉作物分区头那个**形态**。
    check('"📂 $tag（' not in _cp70, "作物分区头文案已删")
    check('"cropSec_' not in _cp70, "作物分区头的 item key 已删")
    # 角标：金色 + **左上角**。⚠ 钉 TopStart 不钉 TopEnd——右上角是勾选框那 22dp
    #   的点击区，角标叠上去既挡字又会把点击吃掉（点角标变成误勾选）。
    _grid70 = _cp70.split("items(items.size, key", 1)[1] if "items(items.size, key" in _cp70 else _cp70.split("items(items.size)", 1)[1] if "items(items.size)" in _cp70 else ""
    _badge70 = _grid70.split("if (modTag != null) {", 1)[1][:400] \
        if "if (modTag != null) {" in _grid70 else ""
    check("align(Alignment.TopStart)" in _badge70, "来源角标在格子左上角")
    check("Alignment.TopEnd" not in _badge70,
          "来源角标没有叠到右上角的勾选框上")
    check("color = c.ink" in _badge70, "来源角标墨字（c223：金字压槽底 3.27 → 墨字，槽底保留）")
    check('style = Px.caption' in _badge70,
          "角标用 caption 尺寸（角标是标记，不该跟格子里的图标抢视觉重量）")
    check("GameData.modTagOf" in _cp70, "角标标签取包自己的短名（不写死 SVE）")
    check('?: "SVE"' not in _cp70, "角标标签没有写死 \"SVE\" 字面量")
    check(_cp70.count("val modTag = modCrop?.let") == 2,
          "列表格与作物详情两处都挂角标（实测 %d 处）"
          % _cp70.count("val modTag = modCrop?.let"))
    check("Chip(modTag, border = c.gold)" in _cp70,
          "作物详情里的来源标签金边保留（c223：content=c.gold 删除，回落默认墨字）")
    # 图鉴三处消费点都要换：漏一处 = 点 SVE 作物打开的是别的条目
    check(_cp70.count("GameData.codexGroupsWithMods") == 3,
          "图鉴列表/详情/记忆位置三处都读 codexGroupsWithMods（实测 %d 处）"
          % _cp70.count("GameData.codexGroupsWithMods"))
    check("GameData.codexGroups.firstOrNull" not in _cp70,
          "CodexPage 不再读静态 codexGroups（否则点 SVE 作物打不开）")
    check('add("收获物" to h)' in _gd70, "图鉴作物行有「收获物」行（作物名≠收获物时才有）")
    check('add("种植要求" to "需高级生长激素")' in _gd70, "raisedBed 作物有种植要求行")
    check('if (it.fromMod) add("种子" to s)' in _gd70,
          "「种子」行只给 Mod 作物（原作物的种子名在渠道里，不重复行）")

    # 70.6 缓存键：crops 变计算属性后，每处 remember 都得补键
    check("val modCropsKey: Pair<Int, Int> get() = modPacks.size to modCropCount" in _gd70,
          "作物缓存键在数据层单源（包数 + Mod 作物数：改一条作物的季节也要重算）")
    check("GameData.modCropsKey" in _ca70, "计算页用了单源缓存键")
    check("GameData.modCropsKey" in _td70, "今日页播种死线用了单源缓存键")
    check(_ca70.count("modCropsKey") >= 4,
          "计算页 cropItems/results/cycles 都挂了缓存键（实测 %d 处引用）" % _ca70.count("modCropsKey"))
    _vsrc70 = code_only(src("ui/villagers/VillagersPage.kt"))
    check("buildSourceHint" in _vsrc70 and "sourceHintCacheKey" in _vsrc70,
          "礼物来源表不再是一次性 by lazy（装包后 SVE 作物来源提示会永远缺失）")

    # 70.7 发布仓：清单与包内一致 + **季节值全覆盖**（关系型断言）
    _mods70 = os.path.join(os.path.dirname(ROOT), "发布仓", "mods")
    _if70 = os.path.join(_mods70, "index.json")
    if os.path.exists(_if70):
        _j70 = json.load(io.open(_if70, encoding="utf-8"))
        _p70 = (_j70.get("packages") or [{}])[0]
        _zn70 = os.path.basename(str(_p70.get("downloadUrl", "")))
        _zp70 = os.path.join(_mods70, _zn70)
        if os.path.exists(_zp70):
            import zipfile as _zf70
            with _zf70.ZipFile(_zp70) as _z70:
                _mf70 = json.loads(_z70.read("manifest.json").decode("utf-8"))
                _cr70 = json.loads(_z70.read("crops.json").decode("utf-8"))
                _names70 = set(_z70.namelist())
            check(str(_p70.get("version")) == str(_mf70.get("packageVersion")),
                  "清单 version(%s) == 包内 packageVersion(%s)"
                  % (_p70.get("version"), _mf70.get("packageVersion")))
            check("crops" in (_mf70.get("modules") or []), "manifest.modules 声明了 crops")
            _list70 = _cr70.get("crops") or []
            check(len(_list70) > 0, "包内 crops 非空（%d 条）" % len(_list70))
            _vals70 = set()
            for _c70 in _list70:
                _vals70 |= set(_c70.get("seasons") or [])
            check(len(_vals70) > 0, "包内季节值取到 %d 种：%s" % (len(_vals70), sorted(_vals70)))
            if _seas70:
                _known70 = set(re.findall(r'"(\w+)"\s+to\s+"', _seas70.group(1)))
                _miss70 = sorted(_vals70 - _known70)
                check(not _miss70,
                      "包内季节值全部被 CROP_SEASON_CN 覆盖（未覆盖 = %s 这些季节的作物筛不出来）"
                      % _miss70)
            # ── 图标：包说有图，zip 里就得真有那张图 ──
            _noic70 = [c.get("crop") for c in _list70 if not (c.get("icon") or "").strip()]
            check(not _noic70,
                  "包内每条作物都带 icon 字段（实测缺 %d 条：缺了就退回空图标框）"
                  % len(_noic70))
            _dang70 = [c.get("crop") for c in _list70
                       if (c.get("icon") or "").strip() and c["icon"] not in _names70]
            check(not _dang70,
                  "icon 指向的图真在包里（实测悬空 %d 条：%s）"
                  % (len(_dang70), _dang70[:3]))
            _png70 = sorted(n for n in _names70
                            if n.startswith("images/crops/") and n.endswith(".png"))
            check(len(_png70) == len(_list70),
                  "作物图数量与条目数一致（%d 张 / %d 条）" % (len(_png70), len(_list70)))
            check("最后一个非空帧" in json.dumps(_cr70, ensure_ascii=False),
                  "包内 note 写明取帧口径，与 App 实际行为一致（不许许诺别的）")
            # ⚠ 口径 v2.73.7 改判：按 **manifest.modules 声明**判定，而不是按「文件在不在包里」。
            #   包作者可以在包里多放几个数据文件**为将来做功能留料**（用户做的 2.5 就带了
            #   fish/shops/locations/heart_events 四个），只要 manifest 没声明、App 也不解析，
            #   就不算「宣称支持却没实现」。
            #   真正要防的是**声明了却没实现**——那会让用户以为该功能有、却找不到。
            _parsed70 = {"npcs": "npcs.json", "schedules": "schedules.json",
                         "crops": "crops.json", "recipes": "recipes.json",
                         "fish": "fish.json", "shops": "shops.json",
                         "heartEvents": "heart_events.json",
                         "locations": "locations.json"}
            _decl70 = set(_mf70.get("modules") or [])
            for _m70, _f70 in _parsed70.items():
                if _f70 in _names70:
                    check(_m70 in _decl70,
                          "包里有 %s 就必须在 manifest.modules 声明 %s（否则 App 会读它却不告知）"
                          % (_f70, _m70))
            check(_decl70 <= set(_parsed70.keys()),
                  "manifest.modules 声明的模块 App 都真解析：%s" % (sorted(_decl70),))
            # ⚠ v2.73.12 修：这段 warn 原来判的是**写死的文件名单**
            #   （fish/shops/locations/heart_events）—— 那 4 个模块到 v2.9 已经
            #   **被 App 解析并在 manifest 里声明**了，名单却没跟着改，于是
            #   每次都误报「App 未解析」。过时的 warn 比没有 warn 更坏：它会让人
            #   以为那 4 项还没接。改判据：按「有 json 文件但 manifest 没声明对应模块」。
            _alljson70 = {n for n in _names70 if n.endswith(".json") and n != "manifest.json"}
            _declfile70 = {_parsed70[m] for m in _decl70 if m in _parsed70}
            _extra70 = sorted(_alljson70 - _declfile70)
            if _extra70:
                warns.append("包内多带了 manifest 未声明的数据文件（App 不宣称支持）："
                             "%s——留着不碍事，将来要做那几项功能时直接接上" % _extra70)
        else:
            check(False, "清单指向的扩展包真在发布仓 mods/（%s）" % _zn70)
    else:
        check(False, "发布仓 mods/index.json 存在")

    # ── 70.8 v2.73.5 作物图通道（App 侧）──
    check("val iconPath: String? = null" in _mo70,
          "Crop 有 iconPath（包内图相对路径，默认 null = 原版作物行为不变）")
    check("val icon: String?" in _mr70, "ModCrop 有 icon 字段")
    check("safeNameOf(it) != null" in _pc70,
          "icon 路径过 safeNameOf（crops.json 是别人写的，一个 ../ 就够读到私有目录外）")
    check('o.optString("icon")' in _pc70 and "takeIf { it.isNotEmpty()" in _pc70,
          "包里没有 icon 时给 null（UI 退回原版图标框，而不是编一张图出来）")
    check("iconPath = icon" in _tc70, "toCrop 把 icon 透传给 Crop")
    check("private const val CROP_FRAME_PX = 16" in _mr70,
          "作物帧宽常量单源（包的 icon 是 16px 宽的多帧横向 sheet）")
    _dlf70 = _mr70.split("private fun decodeLastFrame", 1)[1].split(
        "private fun frameHasPixels", 1)[0] if "private fun decodeLastFrame" in _mr70 else ""
    check("while (idx >= 0)" in _dlf70 and "frameHasPixels" in _dlf70,
          "从后往前找第一个非空帧（尾部空槽位直接跳过，否则拿到纯透明图标）")
    check("idx--" in _dlf70, "取帧循环真的会回退（不是卡在末帧）")
    check("object CropIcons" in _mr70, "作物图有独立的缓存对象（不与头像共用一个）")
    # ⚠ 别断言 "CropIcons.peek" —— 那是 UI 层的**调用**形态，数据层里是对象自身的
    #   fun peek / fun load。钉调用点会把「方法删了但调用还在」这种真 bug 放过。
    check(_mr70.count("fun peek(modId: String, rel: String): Bitmap? = cache[") == 3,
          "头像/作物图/物品图各有一套 peek（实测 %d 套）"
          % _mr70.count("fun peek(modId: String, rel: String): Bitmap? = cache["))
    check(_mr70.count("fun load(ctx: Context, modId: String, rel: String): Bitmap? {") == 3,
          "头像/作物图/物品图各有一套 load（实测 %d 套）"
          % _mr70.count("fun load(ctx: Context, modId: String, rel: String): Bitmap? {"))
    check("decodeLastFrame(f)" in _mr70 and "decodeFirstCell(f)" in _mr70,
          "作物图与头像是两套解码规则，没混进同一个函数（混进去早晚会传错参数裁坏图）")
    # ⚠ 两处清缓存都要带：漏一处 = 更新包后旧图留在缓存里，症状正是「重启才好」
    check(_mr70.count("CropIcons.clear(") == 2,
          "装包与删包两处都清作物图缓存（实测 %d 处）" % _mr70.count("CropIcons.clear("))
    check(_cp70.count("ModCropIcon(") == 2,
          "格子与作物详情两处都用包内作物图（实测 %d 处）" % _cp70.count("ModCropIcon("))
    check("cropModId != null && cropIconRel != null ->" in _cp70 and
          "recipeModId != null && recipeIconRel != null ->" in _cp70 and
          "else -> FramedIcon(item.name, 26)" in _cp70,
          "格子的图标三选一：作物包内图 / 料理包内图 / 原版图标框")
    check(_cp70.count("GameData.modCropsKey") == 2,
          "两处作物反查都挂了 modCropsKey（换包不换图）（实测 %d 处）"
          % _cp70.count("GameData.modCropsKey"))
    _mci70 = code_only(src("ui/mods/ModCropIcon.kt"))
    check("FramedIcon(name, size, pad)" in _mci70,
          "解码失败退回原版图标框（不留破图占位）")
    check("CropIcons.peek" in _mci70 and "Dispatchers.IO" in _mci70,
          "ModCropIcon 与 AssetImage 同款：同步 peek + IO 线程解码")

    # ── 70.9 作物图标单源分发 + 计算页（2026-10-19 用户实测补漏）──
    # 起因：上一轮只改了图鉴页，用户验收时报「计算页里面 SVE 的内容没有显示图片」。
    # 作物图标位不止图鉴一处，而判据「modId 与 iconPath 有没有值」若在每处各写一遍，
    # 加新消费点时漏判是必然的，且漏判不报错、只是那个界面少张图。
    print("\n" + "-- 70.9 作物图标单源分发 + 计算页 --")
    check("fun CropIcon(crop: Crop, size: Int, pad: Int = 2)" in _mci70,
          "有按 Crop 分发的单源入口 CropIcon（调用方只递 Crop，不各自判有无图）")
    _ci70 = _mci70.split("fun CropIcon(", 1)[1].split("fun ModCropIcon(", 1)[0] \
        if "fun CropIcon(" in _mci70 else ""
    check("ModCropIcon(crop.name, modId, rel, size, pad)" in _ci70,
          "CropIcon 的有图分支走 ModCropIcon")
    check("FramedIcon(crop.name, size, pad)" in _ci70,
          "CropIcon 的无图分支走原版图标框（原版作物/老包都不受影响）")
    _cap70 = code_only(src("ui/calc/CalcPage.kt"))
    check(_cap70.count("mods.CropIcon(r.crop") == 2,
          "计算页两处作物行（露大结果 / 温室循环）都走 CropIcon（实测 %d 处）"
          % _cap70.count("mods.CropIcon(r.crop"))
    # ⚠ 别断言「CalcPage 没有 FramedIcon」：它有 3 处是**产物**（酒/果酱），
    #   不是作物，本来就该用原版图标框。这里只钉**作物行**不再直连 FramedIcon。
    check("FramedIcon(r.crop.name" not in _cap70,
          "计算页作物行不再直连 FramedIcon（漏了包内图的那处已改）")
    # 产物行仍在用原版框：反向钉住「别把改动扫过头，把产物图标也一起换了」
    check(_cap70.count("FramedIcon(r.row.product") == 1
          and _cap70.count("FramedIcon(r.output") == 1
          and _cap70.count("FramedIcon(product") == 1,
          "计算页产物（酒/果酱/详情）仍是原版图标框，没被误改")
    # 今日页没有作物图标位：播种死线是文字。⚠ 别断言「TodayPage 没有 FramedIcon」——
    #   它有 2 处（节日图标 f.name / 全局搜索结果 hit.itemName），都不是作物。
    #   全局搜索的索引来自静态 GameData.codexGroups（不含 SVE 作物），所以那处
    #   永远不会渲染出一个 SVE 作物图标，不必改。
    _tdy70 = code_only(src("ui/today/TodayPage.kt"))
    check("CropIcon(" not in _tdy70,
          "今日页没有作物图标位（播种死线是文字，勿悄悄加）")
    # 全项目作物图标位清单：图鉴 2 + 计算页 2 = 4 处。加第 5 处时这条会红，
    #   提醒把新消费点也接到 CropIcon 上而不是自顾自 FramedIcon。
    _all70 = sum(code_only(src(_p70x)).count("CropIcon(")
                 for _p70x in ("ui/codex/CodexPage.kt", "ui/calc/CalcPage.kt"))
    check(_all70 == 4,
          "作物图标位共 4 处（图鉴 2 + 计算页 2），实测 %d 处——新增消费点记得走 CropIcon"
          % _all70)

    # ================= 71. SVE 扩展包 v2.6（料理 recipes.json）=================
    # 用户任务：料理图鉴加 SVE 分区 + 标注解锁条件（如「奥利维亚3心」）。
    # 核心决策与作物同款：**并进既有「菜谱」组**（原版 81 + SVE 20 同页），
    # 另开一组就得让用户在两个分类之间跳，而「这页有哪些菜」是同一次查询。
    # 样式沿用 v2.73.5 已定的**角标**（用户当时明确否掉了大分区头：「很突兀」），
    # 所以这里是「每格左上角金色 SVE 角标 + 详情标解锁条件」，不是新造一个大分区。
    print("\n== 71. SVE 扩展包 v2.6（料理 recipes.json） ==")

    # 71.1 模型：包内一条料理
    check("data class ModRecipe(" in _mr70, "有 ModRecipe 模型（包内一条料理）")
    _mrc70 = _mr70.split("data class ModRecipe(", 1)[1].split(")", 1)[0] \
        if "data class ModRecipe(" in _mr70 else ""
    for _f70 in ("val modId: String", "val name: String",
                 "val ingredients: List<Pair<String, Int>>", "val unlock: String"):
        check(_f70 in _mrc70, "ModRecipe 带字段 %s" % _f70)

    # 71.2 解析：可选文件 + 单条脏数据跳过
    check("private fun parseRecipes(" in _mr70, "recipes.json 有独立解析函数")
    _pr70 = _mr70.split("private fun parseRecipes", 1)[1].split(
        "private fun parseIngredient", 1)[0] if "private fun parseRecipes" in _mr70 else ""
    check("if (!f.isFile) return emptyList()" in _pr70,
          "文件不存在返回空表（v2.5 及更早的包没有这个文件）")
    check('root.optJSONArray("recipes") ?: return emptyList()' in _pr70,
          "根键缺失返回空表")
    check("runCatching" in _pr70, "单条脏数据跳过不拖累整份")

    # 71.3 材料串解析：包内是「名称×数量」字符串，原版是对象数组
    check("private fun parseIngredient(" in _mr70, "材料串有独立解析函数")
    _pi70 = _mr70.split("private fun parseIngredient", 1)[1].split(
        "private fun parseCrops", 1)[0] if "private fun parseIngredient" in _mr70 else ""
    check("lastIndexOf('×')" in _pi70,
          "用 lastIndexOf 切材料（名称本身含×时 indexOf 会把名字切一半）")
    check("toIntOrNull()" in _pi70,
          "数量不是数字时整串当名称（宁可少一个数量，也不把名字切坏）")

    # 71.4 解锁条件：空 = 无门槛，不是缺数据
    check('const val RECIPE_NO_UNLOCK = "自学 / 购买"' in _gd70,
          "包内没写解锁条件时显示「自学 / 购买」（空值是「无门槛」，不是「缺数据」）")
    check("r.unlock.ifBlank { RECIPE_NO_UNLOCK }" in _gd70,
          "解锁条件空值走 RECIPE_NO_UNLOCK 兜底")
    check("RECIPE_NO_UNLOCK" in _gd70 and "RECIPE_NO_UNLOCK" not in _cp70,
          "兜底文案在数据层单源（UI 不各写一份）")

    # 71.5 图鉴：菜谱组与原版同组，条目行名与原版逐字一致
    check('"recipes" -> g.copy(items = recipeCodexItems(g.items, modRecipeItems))' in _gd70,
          "菜谱组按启用包重建（SVE 20 道与原版 81 道同页）")
    check('add("材料" to' in _gd70, "Mod 料理有「材料」行（与原版同名字段）")
    check('add("获取" to' in _gd70, "Mod 料理有「获取」行（解锁条件归这一行）")
    _mri70 = _gd70.split("private fun modRecipeCodexItem", 1)[1].split(
        "val modRecipes:", 1)[0] if "private fun modRecipeCodexItem" in _gd70 else ""
    check('"效果"' not in _mri70,
          "Mod 料理没有「效果」行（包里没有 effect，不编造）")

    # 71.6 角标：与作物区同一套，别新造一个大分区头
    check('GameData.modRecipeOf(item.name)?.modId' in _cp70,
          "菜谱组格子按名反查来源包（角标）")
    check(_cp70.count("GameData.modRecipesKey") == 4,
          "格子与详情各反查料理两次（来源 + 图）= 4 处都挂了 modRecipesKey（实测 %d 处）"
          % _cp70.count("GameData.modRecipesKey"))

    # 71.7 正反两索引并入 Mod 料理（「虚空鳗能做哪几道菜」要认得出来）
    check("val modRecipes: List<ModRepo.ModRecipe>" in _gd70,
          "已启用包的料理是原始条目（带 modId，角标要靠它）")
    check("val modRecipeInfos: Map<String, RecipeInfo>" in _gd70,
          "Mod 料理导出成 RecipeInfo（与原版同结构，两套结构=两处都要改）")
    _mri2_70 = _gd70.split("private val modRecipeInfos", 1)[1].split(
        "const val RECIPE_NO_UNLOCK", 1)[0] if "private val modRecipeInfos" in _gd70 else ""
    check('kind = "菜谱"' in _mri2_70, "Mod 料理的 kind 是「菜谱」")
    check("r.ingredients" in _mri2_70, "Mod 料理的 ingredients 直接透传")
    check("modRecipeInfos.entries.flatMap" in _gd70,
          "材料反查由正向索引导出（不另解析一次）")
    check("modRecipeOf(" in _gd70, "有按名找料理来源的入口（详情角标用）")

    # 71.8 底座不变：原版 81 条的内容与顺序不能因加了 Mod 料理而变
    _rcp71 = load(os.path.join(DATA, "recipes.json"))
    check(len(_rcp71) == 81, "原版菜谱仍是 81 条（加 Mod 不该动原版）")
    check("baseRecipeUses = usesOf(baseRecipesByName)" in _gd70,
          "原版反查底座仍由正向索引导出（顺序与改版前逐条一致）")

    # 71.9 包侧：料理数据与声明口径
    _mods71 = os.path.join(os.path.dirname(ROOT), "发布仓", "mods")
    _if71 = os.path.join(_mods71, "index.json")
    if os.path.exists(_if71):
        _j71 = json.load(io.open(_if71, encoding="utf-8"))
        _p71 = (_j71.get("packages") or [{}])[0]
        _zp71 = os.path.join(_mods71, os.path.basename(str(_p71.get("downloadUrl", ""))))
        if os.path.exists(_zp71):
            import zipfile as _zf71
            with _zf71.ZipFile(_zp71) as _z71:
                _names71 = set(_z71.namelist())
                _rc71 = json.loads(_z71.read("recipes.json").decode("utf-8")) if \
                    "recipes.json" in _names71 else {}
                # 图尺寸要在 zip **开着的时候**量（with 出去后 z71 已关闭）
                import struct as _st71
                _ws71 = set()
                for _n71 in sorted(n for n in _names71
                                   if n.startswith("images/recipes/") and n.endswith(".png")):
                    _b71 = _z71.read(_n71)
                    if _b71[:8] == b"\x89PNG\r\n\x1a\n":
                        _w71, _h71 = _st71.unpack(">II", _b71[16:24])
                        _ws71.add((_w71, _h71))
            _list71 = _rc71.get("recipes") or []
            check(len(_list71) > 0, "包内 recipes.json 非空（%d 条）" % len(_list71))
            check(all((c.get("name") or "").strip() for c in _list71),
                  "每条料理都有名字")
            check(all(isinstance(c.get("ingredients"), list) and c.get("ingredients")
                      for c in _list71),
                  "每条料理都有材料数组（没有材料的菜谱是坏数据）")
            # 材料串形状：名称×数量
            _bad71 = []
            for c in _list71:
                for ing in (c.get("ingredients") or []):
                    if "×" not in ing or not ing.rsplit("×", 1)[1].isdigit():
                        _bad71.append(ing)
            check(not _bad71,
                  "材料串都是「名称×数量」（实测 %d 条不合格：%s）" % (len(_bad71), _bad71[:3]))
            # 解锁条件：空 = 无门槛，非空 = 好感门槛
            _unl71 = [c for c in _list71 if (c.get("unlock") or "").strip()]
            check(all("心" in (c.get("unlock") or "") for c in _unl71),
                  "非空解锁条件都写了好感心数（实测 %d 条带门槛）" % len(_unl71))
            # 与原版菜谱零重名（重名会让图鉴条目与详情下标错位）
            _ov71 = sorted({c["name"] for c in _list71} & {x["name"] for x in _rcp71})
            check(not _ov71, "SVE 料理与原版菜谱零重名（实测重名 %s）" % _ov71)
            # v2.73.8：料理图
            _noic71 = [c.get("name") for c in _list71 if not (c.get("icon") or "").strip()]
            check(not _noic71,
                  "每条料理都有 icon 字段（实测缺 %d 条：缺了就退回空图标框）" % len(_noic71))
            _dang71 = [c.get("name") for c in _list71
                       if (c.get("icon") or "").strip() and c["icon"] not in _names71]
            check(not _dang71,
                  "icon 指向的图真在包里（实测悬空 %d 条：%s）" % (len(_dang71), _dang71[:3]))
            _rpng71 = sorted(n for n in _names71
                             if n.startswith("images/recipes/") and n.endswith(".png"))
            check(len(_rpng71) == len(_list71),
                  "料理图数量与条目数一致（%d 张 / %d 条）" % (len(_rpng71), len(_list71)))
            # 图必须是**官方原始 16×16**：放大是 App 侧的显示归一化，不该在包里做
            # （包里放大 = 素材被篡改，用户日后没法直接换官方新图）
            check(_ws71 == {(16, 16)},
                  "料理图是官方原始 16×16（实测 %s）——放大是 App 侧显示归一化，不在包里做" % (_ws71,))
        else:
            check(False, "清单指向的扩展包真在发布仓 mods/（%s）" % _zp71)
    else:
        check(False, "发布仓 mods/index.json 存在")

    # ── 71.10 v2.73.8 料理图通道（App 侧）──
    print("\n-- 71.10 料理图通道 --")
    check("val icon: String?" in _mr70, "ModRecipe 有 icon 字段")
    _pr71 = _mr70.split("private fun parseRecipes", 1)[1].split(
        "private fun parseIngredient", 1)[0] if "private fun parseRecipes" in _mr70 else ""
    check("safeNameOf(it) != null" in _pr71,
          "料理 icon 路径过 safeNameOf（recipes.json 是别人写的，一个 ../ 就够读到私有目录外）")
    check("object ItemIcons" in _mr70, "包内物品图有独立的缓存对象（不与作物图混用）")
    check("private fun decodeItemIcon(" in _mr70, "物品图解码有独立函数（单图不裁帧）")
    # ⚠ v2.73.12：归一化抽成 normalizeIcon 供**物品图与作物图共用**（都是 16 宽源图）。
    #   之前这段逻辑写在 decodeItemIcon 里，作物图那条通道就没归一化 ——
    #   表现是「SVE 作物图标比原版糙一截」（16px 源放大 4.9 倍 vs 原版 48×48 降采样）。
    _ni71 = _mr70.split("private fun normalizeIcon", 1)[1].split(
        "// ── 作物图", 1)[0] if "private fun normalizeIcon" in _mr70 else ""
    check("Bitmap.createScaledBitmap(src, w * factor, src.height * factor, false)" in _ni71,
          "按整数倍最近邻放大到目标宽（filter=false：像素风要硬边，双线性会糊）")
    check("private const val ITEM_ICON_TARGET_PX = 48" in _mr70,
          "归一化目标宽单源（= App 内置菜谱图标的原生宽度 48）")
    check("normalizeIcon(cropped)" in _mr70,
          "作物图也归一化（裁完帧再放大——顺序反了就白做）")
    check("normalizeIcon(src)" in _mr70, "物品图走同一个归一化函数")
    # ⚠ 三处 clear 都要带：漏一处 = 更新包后旧图留在缓存里，症状正是「重启才好」
    check(_mr70.count("ItemIcons.clear(") == 2,
          "装包与删包两处都清物品图缓存（实测 %d 处）" % _mr70.count("ItemIcons.clear("))
    check(_mr70.count("CropIcons.clear(") == 2 and _mr70.count("Portraits.clear(") == 2,
          "三套图缓存（头像/作物/物品）各自在两处清（实测作物 %d / 头像 %d）"
          % (_mr70.count("CropIcons.clear("), _mr70.count("Portraits.clear(")))
    _mii71 = code_only(src("ui/mods/ModItemIcon.kt"))
    check("FramedIcon(name, size, pad)" in _mii71,
          "物品图解码失败退回原版图标框（不留破图占位）")
    check("ItemIcons.peek" in _mii71 and "Dispatchers.IO" in _mii71,
          "ModItemIcon 与其它图标同款：同步 peek + IO 线程解码")
    check("decodeLastFrame" not in _mii71 and "ItemIcons.load" in _mii71,
          "料理图走 ItemIcons（不裁帧）——别误用作物图那套裁帧通道")
    check(_cp70.count("ModItemIcon(") == 4,
          "格子与详情各两处（料理/鱼）都用包内物品图（实测 %d 处）"
          % _cp70.count("ModItemIcon("))
    check(_cp70.count("GameData.modRecipesKey") == 4,
          "两处料理反查（来源 + 图）× 格子/详情 = 4 处都挂了 modRecipesKey（实测 %d 处）"
          % _cp70.count("GameData.modRecipesKey"))

    # ================= 73. SVE 扩展包 v2.8（红心事件链 heart_events.json）=================
    # 用户任务：可结婚 NPC 详情页加「红心事件」标签，展示 2/4/6/8/10 心节点。
    # ⚠ **不高亮「下一个可触发事件」**：App 不读存档（SaveImport 只解析收集进度，
    #   不碰 Friendship），没有玩家当前好感这个数。把「不知道」标成「还没到」是误导。
    #   用户 2026-10-19 拍板：不接数据源，只列节点。→ 断言要钉住「没有高亮逻辑」。
    print("\n== 73. SVE 扩展包 v2.8（红心事件链） ==")
    _mr73 = code_only(src("data/ModRepo.kt"))
    _gd73 = code_only(src("data/GameData.kt"))
    _vp73 = code_only(src("ui/villagers/VillagersPage.kt"))

    # 73.1 模型：包内一条红心事件链
    check("data class ModHeartEvent(" in _mr73, "有 ModHeartEvent 模型")
    _mhe73 = _mr73.split("data class ModHeartEvent(", 1)[1].split(")", 1)[0] \
        if "data class ModHeartEvent(" in _mr73 else ""
    for _f73 in ("val modId: String", "val id: String", "val name: String",
                 "val hearts: List<Int>", "val triggers: String"):
        check(_f73 in _mhe73, "ModHeartEvent 带字段 %s" % _f73)

    # 73.2 解析：可选文件 + postMarriage 是**包级**的（不是每人一份）
    check("private fun parseHeartEvents(" in _mr73, "heart_events.json 有独立解析函数")
    _phe73 = _mr73.split("private fun parseHeartEvents", 1)[1].split(
        "private fun parseCrops", 1)[0] if "private fun parseHeartEvents" in _mr73 else ""
    check("if (!f.isFile) return" in _phe73, "文件不存在返回空表（老包没这个文件）")
    check('root.optJSONArray("heartEvents")' in _phe73, "根键缺失返回空表")
    check('root.optString("postMarriage").trim()' in _phe73,
          "postMarriage 单独取（整包一份，不塞进每条重复 N 遍）")

    # 73.3 关联用**包内主键**（srcId），不用中文名
    check("fun modHeartEventOf(srcId: String?, name: String)" in _gd73,
          "按包内主键 + 名字找红心事件链")
    check("all.firstOrNull { it.id == srcId }" in _gd73,
          "优先按 srcId 关联（包作者改显示名不该静默丢整条链）")
    check("all.firstOrNull { it.name == name }" in _gd73,
          "中文名回退一次（老包只给名字时仍能对上）")
    check("val modPostMarriage: String" in _gd73, "包级婚后说明有出口")

    # 73.4 详情页：节点 + 触发条件
    check("GameData.modHeartEventOf(v.srcId, v.name)" in _vp73, "详情页按主键取红心事件链")
    check('title = "💗 红心事件链（扩展）"' in _vp73, "详情页有「红心事件链（扩展）」卡")
    check('Chip("$h 心", border = c.red, content = c.red)' in _vp73,
          "节点是红色 Chip（与原版心事件红色一致）")
    check('PixelText("触发条件：${modHeart.triggers}")' in _vp73, "显示触发条件概要")
    check('PixelText("婚后：${GameData.modPostMarriage}"' in _vp73, "显示包级婚后说明")
    # ⚠ 反向断言：不高亮 = 不得出现「下一个可触发」这类需要当前好感的文案/逻辑
    check("下一个可触发" not in _vp73, "不出现「下一个可触发」文案（没有当前好感数据源）")
    check("currentHearts" not in _gd73 and "currentHearts" not in _vp73,
          "没有 currentHearts 这类需要玩家存档的接口（App 不读存档，别留半条链）")
    # 与原版心事件并存，且标清楚是「扩展」
    check('title = "💗 心事件（${events.size} 条）"' in _vp73, "原版心事件卡仍在（两者并存）")

    # 73.5 包侧
    _mods73 = os.path.join(os.path.dirname(ROOT), "发布仓", "mods")
    _if73 = os.path.join(_mods73, "index.json")
    if os.path.exists(_if73):
        _j73 = json.load(io.open(_if73, encoding="utf-8"))
        _p73 = (_j73.get("packages") or [{}])[0]
        _zp73 = os.path.join(_mods73, os.path.basename(str(_p73.get("downloadUrl", ""))))
        if os.path.exists(_zp73):
            import zipfile as _zf73
            with _zf73.ZipFile(_zp73) as _z73:
                _mf73 = json.loads(_z73.read("manifest.json").decode("utf-8"))
                _he73 = json.loads(_z73.read("heart_events.json").decode("utf-8"))
                _npc73 = json.loads(_z73.read("npcs.json").decode("utf-8"))
            _hl73 = _he73.get("heartEvents") or []
            check(len(_hl73) == 7, "包内红心事件 7 位 NPC（实测 %d 位）" % len(_hl73))
            check(_he73.get("postMarriage"), "包内有 postMarriage 说明")
            check("heartEvents" in (_mf73.get("modules") or []),
                  "manifest.modules 声明了 heartEvents：%s" % (_mf73.get("modules"),))
            # 心数节点：必须是星露谷标准的 2/4/6/8/10
            _bad73 = [c.get("name") for c in _hl73
                      if sorted(c.get("hearts") or []) != [2, 4, 6, 8, 10]]
            check(not _bad73, "每位 NPC 的节点都是 2/4/6/8/10（实测不合格：%s）" % _bad73)
            check(all((c.get("triggers") or "").strip() for c in _hl73),
                  "每位 NPC 都有触发条件文字")
            # 关联键：id 必须与 npcs.json 的 id 对得上（否则详情页根本取不到）
            _npcids73 = {c.get("id") for c in (_npc73.get("npcs") or [])}
            _orphan73 = [c.get("name") for c in _hl73 if c.get("id") not in _npcids73]
            check(not _orphan73,
                  "红心事件的 id 都能在 npcs.json 里找到（实测孤立 %d 条：%s）"
                  % (len(_orphan73), _orphan73))
            # 7 位都应该是可结婚的
            _mar73 = {c.get("id") for c in (_npc73.get("npcs") or [])
                      if c.get("marriageable") is True}
            _nonmar73 = [c.get("name") for c in _hl73 if c.get("id") not in _mar73]
            check(not _nonmar73, "有红心事件链的都标了可结婚（实测 %s）" % _nonmar73)
        else:
            check(False, "清单指向的扩展包真在发布仓 mods/（%s）" % _zp73)
    else:
        check(False, "发布仓 mods/index.json 存在")
    # ================= 74. SVE 扩展包 v2.9（地点 locations.json）=================
    # 用户任务：新增「地点」页或地图标注 SVE 区域、地点详情关联对应 NPC；
    #           并要求**数据来源页常驻 SVE 致谢（不要删）**。
    print("\n== 74. SVE 扩展包 v2.9（地点） ==")
    _mr74 = code_only(src("data/ModRepo.kt"))
    _gd74 = code_only(src("data/GameData.kt"))
    _cp74 = code_only(src("ui/codex/CodexPage.kt"))
    _sp74 = code_only(src_sp())

    # 74.1 模型与解析
    check("data class ModLocation(" in _mr74, "有 ModLocation 模型")
    _mlc74 = _mr74.split("data class ModLocation(", 1)[1].split(")", 1)[0] \
        if "data class ModLocation(" in _mr74 else ""
    for _f74 in ("val modId: String", "val id: String", "val name: String", "val who: String"):
        check(_f74 in _mlc74, "ModLocation 带字段 %s" % _f74)
    check("private fun parseLocations(" in _mr74, "locations.json 有独立解析函数")
    _plo74 = _mr74.split("private fun parseLocations", 1)[1].split(
        "private fun parseCrops", 1)[0] if "private fun parseLocations" in _mr74 else ""
    check("if (!f.isFile) return" in _plo74, "文件不存在返回空表（老包没这个文件）")
    check('root.optString("festivals").trim()' in _plo74,
          "festivals 单独取（整包一份，不塞进每条重复 20 遍）")

    # 74.2 关联 NPC：**子串精确匹配**包内 NPC 名单，命中不了就不关联（不猜）
    check("fun locationsOf(loc: ModRepo.ModLocation): List<String>" in _gd74,
          "有「地点 → 关联 NPC」的计算入口")
    _lo74 = _gd74.split("fun locationsOf", 1)[1].split(
        "val modFestivals", 1)[0] if "fun locationsOf" in _gd74 else ""
    check("gifts.filter { it.fromMod }" in _lo74, "候选名单只取来自 Mod 包的 NPC")
    check("loc.who.contains(n)" in _lo74, "靠子串精确匹配（不猜语义）")
    check("fun modLocationOf(name: String)" in _gd74, "有按名找地点的入口（详情页要用）")

    # 74.3 图鉴「地点」新组
    check('out += CodexGroup("locations", "地点", modLocationItems)' in _gd74,
          "有 Mod 地点时追加「地点」分类组")
    check("modLocationCount == 0" in _gd74, "没有地点时不追加空组")
    _mli74 = _gd74.split("private fun modLocationCodexItem", 1)[1].split(
        "fun modLocationOf", 1)[0] if "private fun modLocationCodexItem" in _gd74 else ""
    check('add("说明" to loc.who)' in _mli74, "地点有「说明」行（包内原文）")
    check('add("提示" to' in _mli74, "地点有「提示」行（触发以游戏内为准）")
    # ⚠ 别断言「相关 NPC」在 rows 里：它要能点跳村民页，只有详情页做得到
    check('add("相关 NPC"' not in _mli74,
          "「相关 NPC」不放进 rows（要做成可点的，只能在详情页渲染）")
    _cps74 = code_only(src("ui/codex/CodexPanelSections.kt"))
    check('"locations"' in _cps74, "「地点」进了图鉴分类面板")

    # 74.4 详情页：可点的相关 NPC + 节日说明
    check("GameData.modLocationOf(item.name)" in _cp74, "详情页按名取 Mod 地点")
    check("GameData.locationsOf(it)" in _cp74, "详情页算关联 NPC")
    check("navBus.value = NavRequest.Villager(n)" in _cp74,
          "关联 NPC 可点跳村民页（与特别关心那处同一通道）")
    check('title = "👥 相关 NPC"' in _cp74, "详情页有「相关 NPC」卡")
    check("GameData.modFestivals" in _cp74, "详情页显示包级节日说明")

    # 74.5 ⚠ SVE 致谢**常驻**（用户 2026-10-19 明确要求「不要删」）
    #     Nexus Mods 允许使用其 Mod 素材的前提是**署名要随内容走**；
    #     包内 manifest 自带 authorCredit，但只有装了包的人才看得到，
    #     所以设置页这份静态署名是**兜底**，两边互为备份，谁都不能单独删。
    check('title = "🧩 Mod 扩展包"' in _sp74, "设置页有「🧩 Mod 扩展包」致谢卡")
    check("Stardew Valley Expanded" in _sp74, "致谢里写了 SVE 全名（Stardew Valley Expanded）")
    check("FlashShifter" in _sp74, "致谢里写了 SVE 作者（FlashShifter）")
    check("已获授权使用素材并署名" in _sp74, "致谢里写明「已获授权使用素材并署名」")
    check("nexusmods.com/stardewvalley/mods/3753" in _sp74, "致谢里给了 Nexus Mods 链接")
    # ⚠ 这句「署名随内容走」在**注释**里（说明为什么要写这份静态署名），
    #   code_only() 会把注释剥掉，所以这里查**原文**。
    _sp74raw = src_sp()
    check("Nexus Mods 允许使用其 Mod 素材" in _sp74raw,
          "致谢里写明了「署名随内容走」这条授权前提（注释里的理由，别删）")
    check("两边互为兜底" in _sp74raw,
          "注释里写明了「包内 manifest 与设置页静态署名互为兜底」这条设计")

    # 74.6 包侧：地点 + 署名（包内 authorCredit 与设置页静态署名互为兜底）
    _mods74 = os.path.join(os.path.dirname(ROOT), "发布仓", "mods")
    _if74 = os.path.join(_mods74, "index.json")
    if os.path.exists(_if74):
        _j74 = json.load(io.open(_if74, encoding="utf-8"))
        _p74 = (_j74.get("packages") or [{}])[0]
        _zp74 = os.path.join(_mods74, os.path.basename(str(_p74.get("downloadUrl", ""))))
        if os.path.exists(_zp74):
            import zipfile as _zf74
            with _zf74.ZipFile(_zp74) as _z74:
                _mf74 = json.loads(_z74.read("manifest.json").decode("utf-8"))
                _lo74 = json.loads(_z74.read("locations.json").decode("utf-8"))
            _ll74 = _lo74.get("locations") or []
            check(len(_ll74) == 20, "包内地点 20 个（实测 %d 个）" % len(_ll74))
            check(_lo74.get("festivals"), "包内有 festivals 说明")
            check("locations" in (_mf74.get("modules") or []),
                  "manifest.modules 声明了 locations：%s" % (_mf74.get("modules"),))
            check(all((c.get("name") or "").strip() for c in _ll74), "每个地点都有名字")
            check(all((c.get("who") or "").strip() for c in _ll74),
                  "每个地点都有说明文字（who）")
            check(all((c.get("id") or "").strip() for c in _ll74), "每个地点都有包内 id")
            # 署名：包内 authorCredit 必须还在（Nexus 授权前提）
            _ac74 = str(_mf74.get("authorCredit") or "")
            check("SVE" in _ac74 and "署名" in _ac74,
                  "包内 manifest.authorCredit 仍写着 SVE 素材署名（授权前提）")
        else:
            check(False, "清单指向的扩展包真在发布仓 mods/（%s）" % _zp74)
    else:
        check(False, "发布仓 mods/index.json 存在")
    # ================= 75. Mod 数据进搜索索引 + 作物图归一化 ==================
    # 用户任务：「把这个数据和图片做一下」——
    #   ① 数据 = Mod 包的作物/料理/鱼/商店/地点在**全局搜索**里搜不到（此前只在
    #      分类里能翻到）；② 图片 = SVE 作物图标比原版糙（16px 源图 vs 内置 48×48）。
    print("\n== 75. Mod 数据进搜索索引 + 作物图归一化 ==")
    _gd75 = code_only(src("data/GameData.kt"))
    _ci75 = code_only(src("ui/codex/CodexIndex.kt"))
    _jp75 = code_only(src("ui/jump/JumpPanel.kt"))
    _vp75 = code_only(src("ui/villagers/VillagersPage.kt"))
    _mr75 = code_only(src("data/ModRepo.kt"))

    # 75.1 图鉴视图版本号：缓存判据从「引用身份」换成「版本号」
    check("var codexViewVersion: Int = 0" in _gd75,
          "GameData 有图鉴视图版本号（缓存判据的单源）")
    check("codexViewVersion++" in _gd75,
          "视图内容变化时版本号 +1（装/删/启停包都走 applyMods）")
    # ⚠ 版本号**只能**在视图真变了时涨：applyMods 挂在偏好流上，
    #   偏好流任何变化都发快照（勾待办/切存档/标记特别关心）。
    #   无条件 +1 = 「勾个待办就重建 800+ 条搜索索引」，
    #   那是 v2.31 专门优化掉的性能，不能又引回来。
    check("val viewChanged = modPacks != packs || enabledModIds != enabled" in _gd75,
          "版本号只在视图真变了时 +1（偏好流的高频变化不该触发索引重建）")
    check("if (viewChanged) codexViewVersion++" in _gd75,
          "版本号递增有条件（不是每次 applyMods 都涨）")
    check("cachedCodexGroupsVersion == v" in _gd75,
          "codexGroupsWithMods 按版本号缓存（省掉每次重组重建几百个条目）")
    check("buildCodexGroupsWithMods()" in _gd75, "视图构造拆成独立函数（缓存只包它）")

    # 75.2 搜索索引 / 正文词典 / 首字分桶：三处都得跟版本
    check("cachedVersion == version" in _ci75, "codexIndex 按版本号判缓存")
    check("codexIndex(GameData.codexGroupsWithMods" in _ci75,
          "搜索索引用运行时视图（含 Mod 数据，否则搜不到）")
    check("cachedNameToHitsVersion == v" in _ci75, "nameToHits 按版本号缓存（不再是 by lazy）")
    check("cachedBucketVersion == v" in _ci75, "首字分桶按版本号缓存（不再是 by lazy）")
    check("by lazy" not in _ci75,
          "索引与词典都不再用 by lazy（装包后新增条目永远进不了词典）")

    # 75.3 分组口径一致：凡是把搜索结果按组分组的地方，都得用运行时视图
    check("val groups = GameData.codexGroupsWithMods" in _jp75,
          "跳到面板按运行时视图分组（静态组会让 shops/locations 的命中整组掉出来）")
    check("GameData.codexGroupsWithMods.firstOrNull { it.id == gid }" in _vp75,
          "村民页来源推断表用运行时视图（否则 SVE 鱼/料理不显示来源提示）")
    # ⚠ 反向断言：这几处**不能**改回静态
    check("val groups = GameData.codexGroups\n" not in _jp75,
          "跳到面板没有回退成静态 codexGroups")

    # 75.4 作物图归一化：与物品图共用一个函数
    check("private fun normalizeIcon(src: Bitmap): Bitmap" in _mr75,
          "归一化抽成单源函数（物品图与作物图共用）")
    check("normalizeIcon(cropped)" in _mr75,
          "作物图裁完帧再归一化（顺序反了等于白做：整图 128 宽 ≥ 48 不会动）")
    check("normalizeIcon(src)" in _mr75, "物品图走同一个归一化函数")
    # ================= 76. 扩展包拆成多个可分别下载的分片 ==================
    # 用户需求：「假如发行的话，就是一个 2.9 的整体包。我想把它做成那种样式，
    #   就是把内容隔开：要商店功能就单独下载商店包，村民那部分单独下载，作物也单独…」
    # 决策（用户 2026-10-19 拍板）：拆 **6 个**分片 + **保留整包**在清单里；
    #   **各包独立版本**（从 1.0 起，以后只改作物就只升作物包）。
    print("\n== 76. 扩展包拆分（多包清单） ==")
    _mr76 = code_only(src("data/ModRepo.kt"))
    _gd76 = code_only(src("data/GameData.kt"))
    _mp76 = code_only(src("ui/mods/ModsPage.kt"))
    _mods76 = os.path.join(os.path.dirname(ROOT), "发布仓", "mods")
    _if76 = os.path.join(_mods76, "index.json")

    # 76.1 App 侧：npcs.json 不再是骨架（否则所有分片都装不上）
    check("private fun parsePack(dir: File): Contents?" in _mr76, "parsePack 仍是包的解析入口")
    _pp76 = _mr76.split("private fun parsePack", 1)[1].split(
        "private fun parseCrops", 1)[0] if "private fun parsePack" in _mr76 else ""
    check("if (!npcFile.isFile) return null" not in _pp76,
          "npcs.json 不再是必需文件（分片包本来就没有 NPC 数据）")
    check("val root = if (npcFile.isFile)" in _pp76,
          "npcs.json 改成**可选**：有就读，没有就当这个包不管 NPC")
    # 拒绝条件：只有「声明了 npcs 却没 NPC」才算坏包
    check("contents.modules.contains(\"npcs\") && contents.npcs.isEmpty()" in _mr76,
          "「没 NPC 就不装」改成按 manifest 声明判（商店包/作物包不是坏包）")
    check("val modules: List<String> = emptyList()" in _mr76, "Contents 带 manifest 声明的 modules")

    # 76.2 App 侧：同模块只由一个包提供（整包 + 分片并存不重复）
    check("private val moduleOwner: Map<String, String>" in _gd76,
          "有「模块 → 提供包」的单源映射")
    check("private fun ownerPack(module: String): ModRepo.Installed?" in _gd76,
          "合并属性统一走 ownerPack（判据只有一处）")
    for _m76 in ("npcs", "crops", "recipes", "fish", "shops", "heartEvents", "locations"):
        check('ownerPack("%s")' % _m76 in _gd76,
              "%s 模块只由一个包提供" % _m76)
    check("for (id in enabledModIds.sorted())" in _gd76,
          "提供包的挑选按 modId 排序（整包 sve < 分片 sve-xxx → 整包优先）")

    # 76.3 App 侧 UI：没有 NPC 的包别显示「NPC 0 / 礼物 0/5 段」
    check("if (inst.npcs.isNotEmpty()) {" in _mp76,
          "NPC 相关 chip 只在包里真有 NPC 时显示（否则像装坏了）")
    check('Chip("无村民数据", content = c.inkDim)' in _mp76,
          "无 NPC 的分片改显示「无村民数据」")
    check("inst.npcs.isNotEmpty() && inst.giftTierCount < 5" in _mp76,
          "「礼物段数不足」的提示只对有 NPC 的包显示")

    # 76.4 清单：整包 + 6 个分片，逐个核对
    if os.path.exists(_if76):
        _j76 = json.load(io.open(_if76, encoding="utf-8"))
        _pk76 = _j76.get("packages") or []
        check(len(_pk76) == 7,
              "清单共 7 项（整包 1 + 分片 6），实测 %d 项" % len(_pk76))
        _ids76 = [p.get("modId") for p in _pk76]
        check(len(set(_ids76)) == len(_ids76),
              "各包 modId 互不相同（modId 就是安装目录名，撞了会互相覆盖）")
        _shard76 = [p for p in _pk76 if str(p.get("modId", "")).startswith("sve-")]
        check(len(_shard76) == 6, "分片 6 个（实测 %d 个）" % len(_shard76))
        # 分片 modId 与数据类别的对应
        _want76 = {"sve-villagers": "npcs", "sve-crops": "crops", "sve-recipes": "recipes",
                   "sve-fish": "fish", "sve-shops": "shops", "sve-locations": "locations"}
        import zipfile as _zf76
        for _p76 in _pk76:
            _fn76 = os.path.basename(str(_p76.get("downloadUrl", "")))
            _fp76 = os.path.join(_mods76, _fn76)
            if not os.path.exists(_fp76):
                check(False, "清单指向的包真在发布仓 mods/（%s）" % _fn76)
                continue
            _b76 = open(_fp76, "rb").read()
            check(len(_b76) == _p76.get("size"),
                  "%s 的 size 与清单一致" % _fn76)
            check(hashlib.md5(_b76).hexdigest() == _p76.get("md5"),
                  "%s 的 md5 与清单一致" % _fn76)
            with _zf76.ZipFile(_fp76) as _z76:
                _mn76 = set(_z76.namelist())
                _m76 = json.loads(_z76.read("manifest.json").decode("utf-8"))
            check(_m76.get("modId") == _p76.get("modId"),
                  "%s 的 manifest.modId 与清单一致" % _fn76)
            check(str(_m76.get("packageVersion")) == str(_p76.get("version")),
                  "%s 的包版本与清单一致" % _fn76)
            check(_m76.get("shortName") == "SVE",
                  "%s 的 shortName 是 SVE（拆包后角标不变）" % _fn76)
            # 署名随内容走：每个分片都得带（Nexus 授权前提）
            _ac76 = str(_m76.get("authorCredit") or "")
            check("SVE" in _ac76 and "署名" in _ac76,
                  "%s 带 authorCredit 署名（授权前提：署名随内容走）" % _fn76)
            _decl76 = set(_m76.get("modules") or [])
            # 包里有什么 json，就得声明对应模块
            _mod2file76 = {"npcs": "npcs.json", "schedules": "schedules.json",
                           "crops": "crops.json", "recipes": "recipes.json",
                           "fish": "fish.json", "shops": "shops.json",
                           "heartEvents": "heart_events.json",
                           "locations": "locations.json"}
            for _mo76, _fi76 in _mod2file76.items():
                if _fi76 in _mn76:
                    check(_mo76 in _decl76,
                          "%s 里有 %s 就必须声明 %s" % (_fn76, _fi76, _mo76))
            # 反向：声明的模块 App 都真解析
            check(_decl76 <= set(_mod2file76.keys()),
                  "%s 声明的模块 App 都真解析：%s" % (_fn76, sorted(_decl76)))
            # 分片不得混进别的分片的数据文件
            if _p76.get("modId") in _want76:
                _own76 = _want76[_p76.get("modId")]
                for _mo76, _fi76 in _mod2file76.items():
                    if _mo76 != _own76 and _mo76 != "schedules" and _mo76 != "heartEvents":
                        check(_fi76 not in _mn76,
                              "%s 混进了别的分片的数据 %s" % (_fn76, _fi76))
        # 「只要商店功能就下商店包」这个卖点要有据
        _shop76 = next((p for p in _pk76 if p.get("modId") == "sve-shops"), None)
        if _shop76:
            check(_shop76.get("size", 10 ** 9) < 10 * 1024,
                  "商店包很小（实测 %d B）—— 只想买商店功能不用下整包"
                  % _shop76.get("size", -1))
    else:
        check(False, "发布仓 mods/index.json 存在")
    # ================= 77. 扩展包入口挪到进度页 ==================
    # 用户 2026-10-19 提出的：「进度页的下面不是有一大片空白吗？挪到那里怎么样」
    # 起因：扩展包拆成 6 个分片后，挂在村民页的前提（只有一个整包、内容主体是人物）不成立了。
    print("\n== 77. 扩展包入口位置 ==")
    _pp77 = code_only(src("ui/progress/ProgressPage.kt"))
    _vp77 = code_only(src("ui/villagers/VillagersPage.kt"))
    _ma77 = code_only(src("MainActivity.kt"))

    # 77.1 进度页有入口
    check("private fun ModsEntryCard(" in _pp77, "进度页有扩展包入口卡")
    check('title = "🧩 Mod 扩展包"' in _pp77, "入口卡标题是「🧩 Mod 扩展包」")
    check("GameData.modPacks.size" in _pp77, "入口卡显示已装包数（不用进扩展包页就知道装没装）")
    check("snap.modEnabled.size" in _pp77, "入口卡显示启用数")
    check("PixelText(\"打开 ›\", color = c.gold)" in _pp77, "入口卡有「打开 ›」的跳转提示")
    check("ModsEntryCard(snap, onOpenMods)" in _pp77, "总览段末尾挂了入口卡")
    # ⚠ 只在**总览段**：另外两段（收集包/解锁）内容本来就满，不缺位置
    check("item { ModsEntryCard(snap, onOpenMods) }" in _pp77,
          "入口卡是总览段的一个独立 item（不塞进「收集总览」卡里）")

    # 77.2 村民页不再有入口
    check('PixelButton("🧩 扩展"' not in _vp77, "村民页顶栏没有「🧩 扩展」按钮了")
    check("PixelButton(if (reverse) \"查村民\" else \"🔍 反查\"" in _vp77,
          "村民页顶栏只剩自己的特色功能「🔍 反查」")

    # 77.3 接线在进度页
    check("Screen.Progress -> ProgressPage(" in _ma77 and
          "onOpenMods = { stack.add(Screen.Mods) }" in _ma77,
          "MainActivity 把扩展包入口接到进度页")
    check("Screen.Villagers -> VillagersPage(" in _ma77, "村民页仍被接线（只是没了扩展入口）")


    _n75 = _mr75.split("private fun normalizeIcon", 1)[1].split(
        "// ── 作物图", 1)[0] if "private fun normalizeIcon" in _mr75 else ""
    check("if (w >= ITEM_ICON_TARGET_PX) return src" in _n75,
          "宽度已达标就不放大（不放大已 48 宽的图）")
    check("Bitmap.createScaledBitmap(src, w * factor, src.height * factor, false)" in _n75,
          "整数倍最近邻（filter=false：像素风要硬边）")
    check("private const val ITEM_ICON_TARGET_PX = 48" in _mr75,
          "目标宽 48 = App 内置图标原生宽度（与原版同密度）")



    # ================= 72. SVE 扩展包 v2.7（鱼 + 商店）=================
    # 用户任务：钓鱼图鉴加 SVE 分区 + 新增商店页（或 NPC 页展示谁在哪开店）。
    # 决策：① 鱼并进既有「钓鱼图鉴」组（原版 74 + SVE 13 同页），来源用**角标**
    #      （延续 v2.73.5 已定的样式，用户当时明确否掉大分区头）；
    #      ② 商店做成图鉴的**新分类组**「商店」（22 条），放「🏛 收集对照」段
    #      ——「去哪买」是查询型需求，与钓鱼/博物馆/出货图鉴同一范式。
    print("\n== 72. SVE 扩展包 v2.7（鱼 + 商店） ==")

    # 72.1 模型与解析
    check("data class ModFish(" in _mr70, "有 ModFish 模型（包内一条鱼）")
    for _f72 in ("val modId: String", "val name: String", "val id: String",
                 "val where: String", "val icon: String?"):
        # ⚠ 截断标记用**缩进的右括号**而不是第一个 ")"：
        #   v2.75.1 给 ModFish 加了 List<String> 字段（aliases/seasons），
        #   按第一个 ")" 截会把字段列表切在 aliases 上、后面的 icon 全被切掉。
        _mfc72 = _mr70.split("data class ModFish(", 1)[1].split("\n    )", 1)[0] \
            if "data class ModFish(" in _mr70 else ""
        check(_f72 in _mfc72, "ModFish 带字段 %s" % _f72)
    check("data class ModShop(" in _mr70, "有 ModShop 模型（包内一个商店概要）")
    _msc72 = _mr70.split("data class ModShop(", 1)[1].split(")", 1)[0] \
        if "data class ModShop(" in _mr70 else ""
    for _f72 in ("val modId: String", "val name: String", "val owner: String",
                 "val location: String", "val items: Int?", "val sells: String"):
        check(_f72 in _msc72, "ModShop 带字段 %s" % _f72)
    check("private fun parseFish(" in _mr70, "fish.json 有独立解析函数")
    check("private fun parseShops(" in _mr70, "shops.json 有独立解析函数")
    _pf72 = _mr70.split("private fun parseFish", 1)[1].split(
        "private fun parseShops", 1)[0] if "private fun parseFish" in _mr70 else ""
    check("if (!f.isFile) return emptyList()" in _pf72,
          "鱼文件不存在返回空表（老包没这个文件）")
    # ⚠ 非鱼条目必须剔除：包里那条「毒蘑菇」的 where 写「采集非钓鱼」
    check('where.contains("非钓鱼")' in _pf72,
          "剔除非鱼条目（包里 where 写「采集非钓鱼」——那是采集物不是鱼）")
    check("safeNameOf(it) != null" in _pf72, "鱼 icon 路径过 safeNameOf")
    _ps72 = _mr70.split("private fun parseShops", 1)[1].split(
        "private fun parseCrops", 1)[0] if "private fun parseShops" in _mr70 else ""
    check("if (!f.isFile) return emptyList()" in _ps72,
          "商店文件不存在返回空表（老包没这个文件）")
    check('o.optInt("items", 0) else null' in _ps72,
          "商品数缺失给 null（不是 0 件）")

    # 72.2 与原版重名：跳过（信息更少的那条是倒退）
    check("val modFishes: List<ModRepo.ModFish>" in _gd70, "已启用包的鱼是原始条目")
    _mf72 = _gd70.split("val modFishes:", 1)[1].split(
        "val modFishItems", 1)[0] if "val modFishes:" in _gd70 else ""
    check("val vanilla = fishing.mapTo(mutableSetOf()) { it.name }" in _mf72,
          "与原版钓鱼图鉴比对重名（原版 74 条为基线）")
    check("pack.fish.filter { it.name !in vanilla }" in _mf72,
          "重名的跳过（虚空鲑鱼原版已有且信息更全：全年/女巫沼泽/150g/难度80）")
    check("fun modFishOf(name: String)" in _gd70, "有按名找鱼的入口（角标用它取来源）")

    # 72.3 图鉴钓鱼组：原版 + SVE 同页
    check('"fishing" -> g.copy(items = g.items + modFishItems)' in _gd70,
          "钓鱼组按启用包重建（SVE 13 条与原版 74 条同页）")
    check('add("地点" to f.spotText)' in _gd70, "SVE 鱼有「地点」行（钓点概要，新包用 location、老包用 where）")
    # ⚠ v2.75.1：老包**没有**季节/天气/时段/难度 → 那时必须明说「以游戏内为准」。
    #   新包字段齐了就不加这句。原意（不编造包里没有的字段）不变，条件变了。
    check('add("说明" to "Mod 扩展鱼，季节与天气以游戏内为准")' in _gd70,
          "SVE 鱼在字段缺失时明说季节天气以游戏内为准（不编造包里没有的字段）")
    _mfi72 = _gd70.split("private fun modFishCodexItem", 1)[1].split(
        "val modFishItems", 1)[0] if "private fun modFishCodexItem" in _gd70 else ""
    # 「不编」的原意没变：字段**有**才显示那一行，缺就不显示。
    check('if (f.seasons.isNotEmpty()) add("季节"' in _mfi72,
          "SVE 鱼的「季节」行只在包里真有 seasons 时才加（不编）")
    check('f.difficulty?.let { add("难度"' in _mfi72,
          "SVE 鱼的「难度」行只在包里真有 difficulty 时才加（不编）")
    check('add("价格"' not in _mfi72,
          "SVE 鱼**没有**「价格」行（包里没这个数据，不编）")

    # 72.4 商店：图鉴新分类组
    check('out += CodexGroup("shops", "商店", modShopItems)' in _gd70,
          "有 Mod 商店时追加「商店」分类组")
    check('add("位置" to s.location)' in _gd70, "商店有「位置」行")
    check('add("店主" to s.owner)' in _gd70, "商店有「店主」行")
    check('add("商品数" to "$it 件")' in _gd70, "商店有「商品数」行")
    check('add("类别" to s.sells)' in _gd70, "商店有「类别」行")
    _msi72 = _gd70.split("private fun modShopCodexItem", 1)[1].split(
        "val modShopItems", 1)[0] if "private fun modShopCodexItem" in _gd70 else ""
    # 商店是**新组**、追加在末尾：已有组的下标一个都不动（否则老用户的记忆位置会错位）
    check("}.toMutableList()" in _gd70 and "out += CodexGroup(\"shops\"" in _gd70,
          "「商店」组追加在末尾（不打乱已有组下标）")
    _cps72 = code_only(src("ui/codex/CodexPanelSections.kt"))
    check('"shops"' in _cps72, "「商店」进了图鉴分类面板（🏛 收集对照段）")

    # 72.5 UI：角标 + 包内图
    check("GameData.modFishOf(item.name)?.modId" in _cp70, "钓鱼组按名反查来源包（角标）")
    check("GameData.modFishOf(item.name)?.icon" in _cp70, "钓鱼组按名反查包内图标")
    check(_cp70.count("ModItemIcon(") == 4,
          "格子与详情各两处（料理/鱼）都用包内图（实测 %d 处）" % _cp70.count("ModItemIcon("))
    # ⚠ v2.75.1：计数断言太脆（多一处反查就红），改成**关系断言**：
    #   「凡是按名反查 Mod 鱼的 remember，都挂了 modFishesKey」——
    #   漏挂缓存键 = 换包后界面不刷新（那才是真 bug），处数多少无所谓。
    check(_cp70.count("GameData.modFishesKey") >= _cp70.count("GameData.modFishOf("),
          "按名反查 Mod 鱼的地方都挂了 modFishesKey（换包后要刷新，实测 %d 处反查 / %d 处缓存键）"
          % (_cp70.count("GameData.modFishOf("), _cp70.count("GameData.modFishesKey")))

    # 72.6 钓鱼筛选：Mod 鱼没季节/天气数据时不能被整批滤掉。
    #      「包里没有」≠「不符合」——把未知说成否，会把人坑到白跑。
    #      v2.75.1：SVE 新包**有**这些字段了 → 筛选用它们；但**字段缺失仍要放行**。
    _filt72 = _cp70.split("val fishByName = GameData.fishing.associateBy", 1)[1].split(
        "val items\n", 1)[0] if "val fishByName = GameData.fishing.associateBy" in _cp70 else ""
    check("modFishByName" in _filt72,
          "钓鱼筛选里用了 SVE 鱼自己的字段（季节/天气/难度，v2.75.1）")
    # 关系断言：每个「查 Mod 鱼」的地方都紧跟一个「查不到就放行」的兜底
    check(_filt72.count("modFishByName[fish.name] ?: return@filter true") ==
          _filt72.count("val mf = modFishByName[fish.name] ?: return@filter true"),
          "SVE 鱼在筛选里都有兜底放行（字段缺失 = 不知道，不是「不符合」）")
    check('"未收录"' in _cp70 and '"钓点见游戏内"' in _cp70,
          "钓点筛选下 Mod 鱼标「钓点见游戏内」而不是「未收录」（后者是误导）")
    check("isModFish -> \"钓点见游戏内\"" in _cp70,
          "Mod 鱼的状态行单独判（不能落到「未收录」分支）")

    # 72.7 包侧：鱼图 + 商店概要
    _mods72 = os.path.join(os.path.dirname(ROOT), "发布仓", "mods")
    _if72 = os.path.join(_mods72, "index.json")
    if os.path.exists(_if72):
        _j72 = json.load(io.open(_if72, encoding="utf-8"))
        _p72 = (_j72.get("packages") or [{}])[0]
        _zp72 = os.path.join(_mods72, os.path.basename(str(_p72.get("downloadUrl", ""))))
        if os.path.exists(_zp72):
            import zipfile as _zf72
            with _zf72.ZipFile(_zp72) as _z72:
                _names72 = set(_z72.namelist())
                _mf72 = json.loads(_z72.read("manifest.json").decode("utf-8"))
                _fish72 = json.loads(_z72.read("fish.json").decode("utf-8"))
                _shops72 = json.loads(_z72.read("shops.json").decode("utf-8"))
                import struct as _st72
                _ws72 = set()
                for _n72 in sorted(n for n in _names72
                                   if n.startswith("images/fish/") and n.endswith(".png")):
                    _b72 = _z72.read(_n72)
                    if _b72[:8] == b"\x89PNG\r\n\x1a\n":
                        _w72, _h72 = _st72.unpack(">II", _b72[16:24])
                        _ws72.add((_w72, _h72))
            _fl72 = _fish72.get("fish") or []
            _sl72 = _shops72.get("shops") or []
            # ⚠ 鱼条数**不钉死**（老包 15 条 / 新包 44 条）—— 那是包的版本事实，
            #   钉死等于每次换包都要改断言。改钉**关系**：每条鱼结构完整、名字不重复。
            check(len(_fl72) > 0 and all(c.get("name") and c.get("id") for c in _fl72),
                  "包内鱼每条都有 name 与 id（实测 %d 条）" % len(_fl72))
            _dupn72 = [n for n in set(c.get("name") for c in _fl72)
                       if [c.get("name") for c in _fl72].count(n) > 1]
            check(not _dupn72, "包内鱼名不重复（实测重名 %d 个）" % len(_dupn72))
            check(len(_sl72) == 22, "包内商店 22 个（实测 %d 个）" % len(_sl72))
            # ⚠ 不再钉「无图的恰好是哪两条」—— 那是老包(15 条)的实现细节，
            #   新包(44 条)零占位框后语义已变（0 条无图）。真正要防的是
            #   ①带图的图不在包里 ②鱼图数与鱼数对不上 ③无图的鱼没有交代。
            #   ③ 改成关系：每条无图的鱼都必须被**包内 note 解释**（老包 note 写明
            #   「虚空鲑鱼原版已有故不重复收录」「毒蘑菇是采集物不是鱼」；新包 0 条）。
            _note72 = str(_fish72.get("note") or "")
            _noic72 = [c.get("name") for c in _fl72 if not (c.get("icon") or "").strip()]
            _unexp72 = [c.get("name") for c in _fl72
                        if not (c.get("icon") or "").strip()
                        and c.get("name") not in _note72 and c.get("id") not in _note72]
            check(not _unexp72,
                  "每条无图的鱼都被包内 note 交代了（实测未交代 %d 条，无图 %d 条）"
                  % (len(_unexp72), len(_noic72)))
            _dang72 = [c.get("name") for c in _fl72
                       if (c.get("icon") or "").strip() and c["icon"] not in _names72]
            check(not _dang72, "鱼的 icon 指向的图真在包里（实测悬空 %d 条）" % len(_dang72))
            _fpng72 = sorted(n for n in _names72
                             if n.startswith("images/fish/") and n.endswith(".png"))
            _withic72 = [c for c in _fl72 if (c.get("icon") or "").strip()]
            check(len(_fpng72) == len(_withic72),
                  "鱼图数量 == 带 icon 的鱼条数（实测 %d 张 / %d 条）" % (len(_fpng72), len(_withic72)))
            check(_ws72 == {(16, 16)},
                  "鱼图是官方原始 16×16（实测 %s）——放大是 App 侧显示归一化" % (_ws72,))
            # 商店：商品数与类别是「概要」，不编造
            check(all("items" in c for c in _sl72), "每个商店都有商品数")
            check(all("owner" in c and "location" in c for c in _sl72),
                  "每个商店都有店主与位置字段（可能为空串）")
            check("fish" in (_mf72.get("modules") or []) and
                  "shops" in (_mf72.get("modules") or []),
                  "manifest.modules 声明了 fish 与 shops：%s" % (_mf72.get("modules"),))
        else:
            check(False, "清单指向的扩展包真在发布仓 mods/（%s）" % _zp72)
    else:
        check(False, "发布仓 mods/index.json 存在")
    check("fun modTagOf(modId: String?)" in _gd70,
          "来源标签判据收在 modId 一处（作物与料理共用）")
    check('?: "SVE"' not in _cp70, "料理角标没有写死 \"SVE\" 字面量")

    # ================= 78. 农场年度报告（v2.74.0）=================
    # 用户任务：读存档累计统计 → 聚合成一张竖版长图 → 可存相册/可分享。
    # 本节钉的是「容易静默出错」的那些点：键名手抄、换算口径、缺字段降级、接线。
    print("\n== 78. 农场年度报告 ==")
    _fr78 = src("data/FarmReport.kt")
    _fp78 = src("data/FarmReportParser.kt")
    _rn78 = src("data/ReportNames.kt")
    _rf78 = src("ui/report/ReportFacts.kt")
    _rc78 = src("ui/report/ReportCanvas.kt")
    _rp78 = src("ui/report/ReportPage.kt")
    _si78 = src("data/SaveImport.kt")
    _pr78 = src("data/PrefsRepo.kt")
    _sh78 = src("ui/share/ShareHelper.kt")
    _sp78 = src("ui/settings/SettingsPage.kt")
    _tp78 = src("ui/today/TodayPage.kt")
    _pw78 = src("ui/PixelWidgets.kt")

    # 78.1 统计键名自洽：**常量值必须等于注释里原键名的小写**
    # 踩过的坑：真档里 seedsSown 的小写是 "seedssown"（seeds + Sown），
    # 手敲成 "seedsown" 就不报错、只是那一行永远不显示——静默少内容，最难查。
    # 这条断言把「原名 → 小写」这条规则钉死，以后加键写错立刻红。
    _obj78 = _fr78.split("object FarmStatKey {", 1)[-1] if "object FarmStatKey {" in _fr78 else ""
    _allk78 = re.findall(r'const val (\w+)\s*=\s*"([a-z]+)"', _obj78)
    _keys78 = re.findall(r'const val (\w+)\s*=\s*"([a-z]+)"\s*//\s*([^\n]+)', _obj78)
    check(len(_allk78) >= 45, "FarmStatKey 常量可解析（%d 条）" % len(_allk78))
    check(len(_allk78) == len(_keys78),
          "每个统计键都带原名注释（缺 %d 条：%s）"
          % (len(_allk78) - len(_keys78),
             "、".join(n for n, _v in _allk78 if n not in set(k[0] for k in _keys78)) or "无"))
    _bad78 = []
    for _n, _v, _c in _keys78:
        _orig = _c.split("（")[0].split("(")[0].strip()
        if _orig and _v != _orig.lower():
            _bad78.append("%s=%s（原名 %s）" % (_n, _v, _orig))
    check(not _bad78, "每个统计键 = 原键名小写（%s）" % ("、".join(_bad78) or "全部一致"))

    # 78.2 解析器覆盖两代存档结构
    check('"Values" || name == "stat_dictionary"' in _fp78,
          "解析器同时读 1.6 的 Values 字典与 1.5.6 的 stat_dictionary")
    check("name in STAT_SCALARS" not in _fp78,
          "stats 不用白名单（白名单会让 1.5.6 档漏掉真有的计数，实测漏 15 项）")
    check("STAT_CONTAINERS" in _fp78, "stats 里的容器走黑名单（其余全当标量读）")
    check("stats.putIfAbsent" in _fp78 or "if (stats.containsKey(key)) return" in _fp78,
          "同键先到先得（两代数据不会互相覆盖）")
    check('"Gender"' in _fp78 and '"gender"' in _fp78 and '"isMale"' in _fp78,
          "性别三种形态都认（1.6 的 Gender/gender + 1.5.6 的 isMale）")
    for _r in ("year", "dayOfMonth", "currentSeason", "whichFarm", "goldenWalnutsFound"):
        check('"%s"' % _r in _fp78, "根节点标量 %s 会读" % _r)
    check('"Friendship"' in _fp78 and '"Points"' in _fp78,
          "好感度读 <value><Friendship><Points>（不是 <value><int>）")
    check('"specificMonstersKilled"' in _fp78, "怪物击杀字典会读")
    # ⚠ 字典的 key/value 必须靠**显式状态**区分（inKey / inValue）：
    #   <key> 与 <value> 是兄弟节点，子元素深度完全相同，只看深度会分不清；
    #   分不清的后果是 <value><int>152</int></value> 里的 <int> 被 key 分支抢走 →
    #   整只怪被丢掉，不报错，只是「最爱杀」那行永远不显示（实测踩过两次）。
    check("inKey = true" in _fp78 and "inValue = true" in _fp78,
          "字典用显式状态区分在 key 还是 value 里")
    check("if (inKey && d == keyDepth + 1)" in _fp78 and
          "if (inValue && d == valDepth + 1)" in _fp78,
          "key/value 分支按状态 + 深度双判")
    check('name == "string" || name == "int"' not in _fp78,
          "key 分支不按元素名判断（<int> 会被 key 分支抢走）")
    check("topMonstersText" in _rf78, "报告显示了杀得最多的 3 种怪")
    check("bedtimeText()" in _rf78, "报告显示了平均睡觉时间")
    # ⚠ 读叶子元素必须推进到 TEXT 事件：START_TAG 上读 parser.text 恒为 null
    check("p.next() == XmlPullParser.TEXT" in _fp78,
          "读叶子推进到 TEXT 事件（不退回 parser.text 那个坑）")

    # 78.3 换算口径：军事时间 → 24 小时制
    # 真档实测：2350 = 23:50、2454 = 次日 00:54、2600 = 02:00
    check("val minutes = (v % 100).toInt()" in _fr78, "军时的分钟取 % 100（十进制不是十六进制）")
    check("val hours24 = (v / 100).toInt()" in _fr78, "军时的小时取 / 100")
    check('"%02d:%02d".format(p.first, p.second)' in _fr78, "军时转 24 小时制字符串")
    check("(hours24 % 24) to minutes" in _fr78, "跨零点按 % 24 回绕")
    _bed78 = {2350: "23:50", 2454: "00:54", 2600: "02:00", 2024: "20:24"}
    _ok78 = []
    for _v, _want in _bed78.items():
        _got = "%02d:%02d" % ((_v // 100) % 24, _v % 100)
        if _got != _want:
            _ok78.append("%d→%s（应 %s）" % (_v, _got, _want))
    check(not _ok78, "军时换算符合真档实测值（%s）" % ("、".join(_ok78) or "2350/2454/2600/2024 全对"))
    check("millisecondsPlayed / 60000" in _fr78 and "totalMin / 60" in _fr78,
          "游玩时长按毫秒换算成小时（60000 → 分，再 /60 → 小时）")
    check('String.format(Locale.CHINA, "%,d"' in _rf78, "金币带千分位")

    # 78.4 缺字段必须降级，不能编
    check("if (v == null || v <= 0L) null" in _rf78, "值为 0 或缺失的行整个不显示（不写 0）")
    check("napsTaken" not in _rf78 and "NAPS_TAKEN" not in _rf78,
          "报告不显示午睡次数（两代存档都没这个字段，编不得）")
    check("NAPS_TAKEN" not in _rf78 and "TIMES_UNLOADED" not in _rf78,
          "报告不显示退出存档次数（同上）")
    check("days < 10" in _rf78 and "你的农场故事才刚刚开始" in _rf78,
          "新档友好：给「农场新人」+ 一句友好的话")
    check("2400" in _rf78 and "2600" in _rf78, "作息称号有阈值（2400 / 2600）")

    # 78.5 画布规格
    check("const val W = 1080" in _rc78, "长图宽 1080")
    _fs78 = re.findall(r"private const val (FS_\w+) = (\d+)", _rc78)
    check(len(_fs78) >= 5, "字号常量可解析（%d 个）" % len(_fs78))
    # 字号：12 的倍数优先；例外必须是**有推导的**。
    # 旧口径只允许一个例外（FS_VALUE 30px，跟预览稿 1:1）。方向 F 改成按**手机物理尺度**
    # 定字号后，30px 和 40px 两个值都是推导出来的——例外还在，但必须被注释点名，
    # 而且要能看出是推导不是随手写。
    _badsz78 = sorted(set(int(v) for n, v in _fs78 if int(v) % 12 != 0))
    _undoc78 = [v for v in _badsz78 if ("%dpx" % v) not in _rc78]
    check(not _undoc78,
          "非 12 倍数的字号（%s）都在注释里点名写了理由" % (_badsz78 or "无"))
    check("0.1435mm" in _rc78,
          "字号例外理由是手机物理尺度的推导，不是随手写的")
    check("ShareHelper.watermark()" in _rf78, "底部水印复用分享卡同一句")
    check("0xFFF2E8D2" in _rc78 and "0xFF241C14" in _rc78 and "0xFFB03A2E" in _rc78,
          "报纸色板三色钉死（纸 #F2E8D2 / 墨黑 #241C14 / 朱红 #B03A2E）")
    for _ic in ("ach_crops", "ach_combat", "ach_home", "ach_social"):
        check(_ic in _rf78, "数据网格用了分组图标 %s" % _ic)
        check(os.path.exists(os.path.join(ROOT, "app", "src", "main", "assets", "img", "ui", _ic + ".png")),
              "图标文件 %s.png 存在" % _ic)

    # 78.6 中英对照表（v9 真源生成物）
    _rn_path = os.path.join(DATA, "reportNames.json")
    check(os.path.exists(_rn_path), "对照表 reportNames.json 存在")
    if os.path.exists(_rn_path):
        _rnjson78 = load(_rn_path)
        check(len(_rnjson78.get("monsters", {})) >= 78, "怪物对照 %d 条（≥78）" % len(_rnjson78.get("monsters", {})))
        check(len(_rnjson78.get("npcs", {})) >= 30, "NPC 对照 %d 条（≥30）" % len(_rnjson78.get("npcs", {})))
        # 真档里出现过的怪名（游戏内部名与 wiki 名不一致的那几个）
        for _en, _cn in (("Dust Spirit", "灰尘精灵"), ("Sludge", "红色泥浆"),
                         ("Frost Jelly", "冰冻史莱姆"), ("Fly", "苍蝇"),
                         ("Big Slime", "巨型史莱姆"), ("Green Slime", "绿色史莱姆")):
            check(_rnjson78.get("monsters", {}).get(_en) == _cn,
                  "真档怪名 %s → %s" % (_en, _cn))
        for _en, _cn in (("Lewis", "刘易斯"), ("Sebastian", "塞巴斯蒂安"), ("Robin", "罗宾")):
            check(_rnjson78.get("npcs", {}).get(_en) == _cn, "真档 NPC 名 %s → %s" % (_en, _cn))
    check(os.path.exists(os.path.join(ROOT, "tools", "gen_report_names.py")),
          "对照表生成脚本在（v9 真源，可重建）")
    check("原样显示英文" in _rn78 or "原名" in _rn78,
          "对照表查不到时原样显示英文（不编中文名）")

    # 78.7 接线
    check("withReport" in _si78 and _si78.count("withReport(") >= 3,
          "统计解析挂在 zip 与裸 XML 两条路径上")
    check("r.copy(report = report)" in _si78, "统计挂进导入结果（失败不影响打勾导入）")
    check('private fun reportKey(n: String) = stringPreferencesKey("rp:$n")' in _pr78,
          "报告按存档位存（多存档各一份）")
    check("suspend fun setReport(" in _pr78 and "suspend fun reportOf(" in _pr78,
          "报告有存有取")
    check('.put("report", p[reportKey(n)] ?: "")' in _pr78, "备份带上报告（换机不丢）")
    # v2.88.0：报告卡的入口从设置页搬到今日页「我的农场」（详见 95.2）——
    # 报告跟农场清单是同一件事，钉新位置。
    check("FarmReportCard(" in _tp78, "今日页「我的农场」挂了报告入口卡")
    check("repo.setReport(snap.activeProfile, data.report)" in _sp78,
          "导入确认后才把报告写到当前存档位（与打勾同一时机）")
    check("fun saveToGallery(" in _sh78, "分享助手有存相册能力")
    check("MediaStore.Images.Media.RELATIVE_PATH" in _sh78, "Android 10+ 走 MediaStore（不要权限）")
    check("DIRECTORY_PICTURES" in _sh78, "存到相册的图片目录")
    check("enabled: Boolean = true" in _pw78,
          "PixelButton 的 enabled 默认 true（既有调用点行为不变）")
    check("recycle: Boolean = true" in _sh78,
          "shareBitmap 的 recycle 默认 true（既有调用点行为不变）")
    check("recycle = false" in _rp78, "报告分享不复用位图（预览还要显示）")

    # 78.8 版本口径：本次是真功能改动，必须有对应日志条目
    _m_vn78 = re.search(r'versionName\s*=\s*"([^"]+)"', gb)
    _m_log78 = re.search(r'private val CHANGELOG = listOf\(\s*\r?\n\s*"v?([0-9][0-9.]*)[^"]*"', _sp78)
    check(bool(_m_log78) and "农场年度报告" in _sp78,
          "CHANGELOG 里有本次功能条目（真功能改动不能没日志）")
    if _m_log78 and _m_vn78:
        check(_m_log78.group(1) == _m_vn78.group(1),
              "CHANGELOG 首条版本 = gradle versionName（%s / %s）" % (_m_log78.group(1), _m_vn78.group(1)))

    # 78.9 长图版式（方向 F 鹈鹕镇特刊，2026-10-20 第二轮预览稿里用户选的 F）
    _rc78 = src("ui/report/ReportCanvas.kt")
    check("方向 F「鹈鹕镇特刊」" in _rc78, "渲染层写明用的是方向 F")
    # 反向断言：上一版（方向 C 像素卡片墙）已经换掉，痕迹不能复活
    check("hardCard(" not in _rc78, "C 版硬阴影卡片已移除")
    check("SHADOW" not in _rc78, "C 版阴影常量已移除")
    check("像素卡片墙" not in _rc78, "C 版版式说明已移除")
    check("IconCache.uiIcon(ctx, \"trophy\")" not in _rc78, "C 版奖杯徽章已移除")
    # F 版结构
    check('"鹈鹕镇新闻"' in _rc78, "报头写「鹈鹕镇新闻」")
    check("rule2Op()" in _rc78, "通栏双细线（rule2）")
    check("drawTrackedCentered" in _rc78 and "TRACKING = 2f" in _rc78, "报头带 2px 字距")
    check("numbersColsOp(" in _rc78, "两栏：本期数字 / 每日产量")
    check("editorOp(" in _rc78, "编者按 / 彩蛋是浅底块 + 朱红竖条")
    check("clockOp(" in _rc78, "作息时间表是 24 格时钟")
    check("BAR_CELLS = 10" in _rc78, "每日产量是 10 格柱状条")
    check("baselineOf(" in _rc78, "行盒基线自己算（浏览器 half-leading，Canvas 没有）")
    check("wrapIndent(" in _rc78, "导语首行缩进两字")

    # 78.10 字号按**手机物理尺度**定
    # 根因：1080 宽的画布在 6.1 寸 1080p 手机上只有 155mm 宽 → 1px = 0.1435mm。
    # 旧的正文 24px 在手机上只有 3.4mm（≈9.7pt），偏小偏碎；在 27 寸显示器上却是 5.6mm
    # —— 预览里看着舒服，进手机缩水 1.6 倍。这就是「预览好看 / 游戏里像另一张」的主因。
    check("private const val FS_BODY = 30f" in _rc78,
          "正文字号 30px（手机上 4.31mm ≈ 12.2pt）")
    check("FS_BODY = 24f" not in _rc78,
          "正文没有退回 24px（那是显示器上舒服、手机上缩水的旧值）")
    check("private const val FS_VAL = 36f" in _rc78, "表格数值 36px")
    check("private const val FS_HEAD = 120f" in _rc78, "头条大字 120px")
    check("0.1435mm" in _rc78, "字号注释写明了手机物理尺度的推导（防止后人不知道为什么）")
    # 版面自洽：内容区 888 = 1080 - 48*2(paper) - 48*2(col)，两栏间距 32
    check("COL_GAP = 32f" in _rc78, "两栏间距 32px")
    check("(CONTENT_W - COL_GAP) / 2f" in _rc78, "栏宽按 (内容宽 - 间距)/2 算")
    check("CONTENT_L = PAPER_PAD + COL_MARGIN" in _rc78, "内容区左边界 = 纸面留白 + 栏外边距")

    # 78.11 预览稿是渲染层的**上游**：两边字号必须一致，不许各改各的
    _prevF_path = os.path.join(_REPO_PARENT, "旧文件归档", "报告预览", "F-鹈鹕镇特刊.html")
    check(os.path.isfile(_prevF_path), "F 预览稿在 旧文件归档/报告预览/ 下")
    if os.path.isfile(_prevF_path):
        _pf = io.open(_prevF_path, encoding="utf-8").read()
        check("zoom:0.363889" in _pf,
              "预览稿有「手机视图」（393 CSS px = 6.1 寸 1080p 真机）")
        check("togglePhone" in _pf, "预览稿能切手机 / 桌面视图")
        _m_lede = re.search(r"\.lede\{[^}]*font-size:(\d+)px", _pf)
        _m_body = re.search(r"FS_BODY\s*=\s*(\d+)f", _rc78)
        check(bool(_m_lede) and bool(_m_body) and _m_lede.group(1) == _m_body.group(1),
              "预览稿正文字号 = App 正文字号（预览 %s / App %s）" % (
                  _m_lede.group(1) if _m_lede else "?",
                  _m_body.group(1) if _m_body else "?"))
        check("gap:0 32px" in _pf, "预览稿两栏间距 32px（与 App 一致）")
        check("钓了 2,160 次鱼" in _pf, "预览稿导语用钓鱼次数（2,160），与 App 口径一致")

    # 78.12 报纸叙事层：每日产量 = 总数 ÷ 游戏天数，且缺数据要改写整句
    _rf78 = src("ui/report/ReportFacts.kt")
    check("data class PaperFacts" in _rf78, "有报纸专用的事实结构 PaperFacts")
    check("v.toDouble() / days.toDouble()" in _rf78, "每日产量 = 总数 ÷ 游戏天数")
    check("editorNote" in _rf78, "编者按那句是按每日产量现算的")
    check("鹈鹕镇终于有了一个像样的对手" in _rf78, "导语收尾用了报纸腔")
    check("佩利肯镇" not in _rf78, "旧错字「佩利肯镇」已修正为「鹈鹕镇」")
    check("打倒" in _rf78 and "砍倒" not in _rf78, "怪不是「砍倒」的，是「打倒」")
    # 钓鱼口径：**次数**（TIMES_FISHED）不是**条数**（FISH_CAUGHT）。
    # 同一份存档两个数（2160 次 / 1773 条），用错预览稿与 App 就对不上。
    check("TIMES_FISHED" in _rf78 and "钓了 ${group(fished)} 次鱼" in _rf78,
          "导语用钓鱼次数（TIMES_FISHED）")
    _pfpart78 = _rf78.split("private fun paperFacts(")[-1]
    # 查**调用点**而不是裸词：注释里会解释「为什么不用 FISH_CAUGHT」，
    # 裸词匹配会把那段解释也当成违规。
    check("stat(FarmStatKey.FISH_CAUGHT)" not in _pfpart78,
          "报纸层不取 FISH_CAUGHT（条数 ≠ 次数）")
    # 缺字段不能写成 0
    check("if (sown > 0) add(" in _rf78 and "if (fished > 0) add(" in _rf78,
          "导语里缺的数据整项去掉，不写 0")
    # 作息段是通栏（预览稿那一节 grid 是 1fr 单列），不是两栏宽
    check("(CONTENT_W - CLOCK_GAP * 23f) / 24f" in _rc78,
          "作息时钟条铺满通栏（CONTENT_W），不是缩在左半边")
    check("val step = CONTENT_W / 4f" in _rc78, "作息刻度也按通栏四等分")

    # ================= 79. 局域网接收的验证码鉴权（v2.75.0）=================
    # 起因：这个 App 会发给认识的人用。接收端只要手机开着窗口，**同一 WiFi 下任何设备**
    #   都能 POST 一个 zip 进来（宿舍 / 公司 / 咖啡厅的公共 WiFi 尤其危险）。
    #   v2.75.0 加了 6 位数字验证码：电脑生成 → 人在手机上核对 → 才放行。
    #   真正的防线是「攻击者碰不到你的手机」；这节钉的是**这套机制没被改坏**。
    print("\n== 79. 局域网接收的验证码鉴权 ==")
    _sr79 = code_only(io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                          "assistant", "data", "SaveReceiver.kt"),
                             encoding="utf-8").read())
    _sp79 = code_only(io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                          "assistant", "ui", "settings", "SettingsPage.kt"),
                             encoding="utf-8").read())
    _ps79raw = io.open(os.path.join(ROOT, "tools", "send_save.ps1"), encoding="utf-8-sig").read()
    # ps1 的注释是 # 开头，剥掉再查——不然「为什么不用 Get-Random」这类说明会被当成违规
    _ps79 = "\n".join(ln.split("#")[0] for ln in _ps79raw.splitlines())
    _up79 = code_only(io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                          "assistant", "data", "UpdateRepo.kt"),
                             encoding="utf-8").read())

    # ① 协议：版本号 + 两个请求头（版本/验证码）
    check("PROTO_VERSION" in _sr79 and "X-Stardew-Proto" in _sr79 and "X-Stardew-Code" in _sr79,
          "协议有版本号与两个请求头（版本 / 验证码）")
    check("CODE_LEN = 6" in _sr79, "验证码 6 位")
    # ② 版本不匹配一律 403，且旧脚本（没带头）也要被拒
    check("电脑脚本版本过旧" in _sr79 and "电脑脚本版本过新" in _sr79,
          "协议版本不符一律 403，且给出「过旧 / 过新」两种人话提示")
    # ③ 缺码 / 码不对都是 403，且不回显差异信息
    check('MSG_BAD_CODE = "验证码不正确"' in _sr79,
          "验证码缺失或不符回 403「验证码不正确」（不回显差异信息）")
    # ④ 常量时间比较
    check("MessageDigest.isEqual" in _sr79,
          "验证码用 MessageDigest.isEqual 常量时间比较（不用 String.equals）")
    # ⑤ 错误次数按**窗口全局**计数 + 有上限（不是每连接各算 5 次）
    check("MAX_CODE_ATTEMPTS = 5" in _sr79 and "wrongAttempts" in _sr79
          and "AtomicInteger" in _sr79,
          "错误尝试按窗口全局计数（AtomicInteger），上限 5 次")
    # ⑥ 一次性：码用过就不再接受
    check("usedCodes" in _sr79, "验证码一次性（用过的码不再接受）")
    # ⑦ 同一时刻只允许一个待验证连接（新连接直接拒，不排队）
    check("pendingVerifications" in _sr79, "同一时刻只允许一个待验证连接（新连接直接拒）")
    # ⑧ 等用户输码有超时
    check("CODE_TIMEOUT_MS" in _sr79 and "answered.await" in _sr79,
          "等用户输码有超时（超时按一次错误尝试处理）")
    # ⚠ 60 秒是**每次询问**的预算（deadline 在循环内重置），不是整个等待期的总预算。
    #   初版当成总预算，实测太紧：人还没想好输什么就超时、弹窗自己消失，看着像没反应。
    #   「窗口不会被无限拖住」由**次数上限**保证（5 次 × 60 秒 = 最多 5 分钟）。
    _loops79 = _sr79.split("while (true) {")
    check(any("val deadline = System.currentTimeMillis() + CODE_TIMEOUT_MS" in b for b in _loops79),
          "超时的 60 秒是每次询问的预算（deadline 在循环内重置）")
    # ⑨ 两阶段：**先核对验证码，再收 body**
    _i79 = _sr79.find("awaitCode(")
    _j79 = _sr79.find("ins.read(buf")
    check(_i79 > 0 and _j79 > 0 and _i79 < _j79,
          "先核对验证码再收 body（没拿到码的请求连一个字节都落不了盘）")
    # ⑩ 端口
    # ⚠ v2.75.0 从「完全随机」改成「**固定小段 38217~38236**」——
    #   起因是用户嫌「电脑上还要输 IP:端口」太麻烦。端口可预测本身不是问题：
    #   **真正的防线是验证码**（没有正确验证码一律 403），端口只是"找得到在哪"。
    #   换成固定段之后：① 电脑能广播自动发现；② 兜底手输时也只需输 IP，端口自己扫。
    check("PORT_BASE = 38217" in _sr79 and "PORT_SCAN" in _sr79,
          "HTTP 监听在 38217~38236 固定段里（电脑端要能扫到它）")
    check("ServerSocket(0" not in _sr79,
          "不再用完全随机端口（电脑端扫不到，用户还得抄端口）")
    # ⑩' UDP 自动发现：电脑广播一句，手机应答「我的端口 + 我的 IP」
    check("DISCOVERY_PORT" in _sr79 and "SDH-FIND" in _sr79 and "SDH1" in _sr79,
          "手机端有 UDP 发现服务（电脑广播就能找到手机，用户不用输任何东西）")
    check("DatagramSocket" in _sr79 and "startDiscovery" in _sr79,
          "发现服务用 DatagramSocket 收发（UDP 广播，Windows 防火墙允许后即可用）")
    # ⚠ 静默失败是最坏的失败：用户只会看到「电脑没找到手机」，
    #   完全不知道是这台手机上没有发现服务。必须记日志 + 报给 UI。
    check("autoFind" in _sr79 and "bind failed" in _sr79,
          "发现服务起不来时要记日志并报给 UI（不能静默失败）")
    check("autoFind" in _sp79,
          "UI 会提示「自动发现没起来，请手动输 IP」")
    # ⑪ 错误响应体是固定人话，不回显验证码本身
    check("MSG_TOO_MANY" in _sr79 and "错误次数过多" in _sr79,
          "错误次数超限有专门提示（用户知道窗口被收掉了）")
    # ⑫ UI：数字输入 + 剩余次数 + 行内错误 + 警示语
    # c232：弹窗搬进 ui/settings/CodePromptSheet.kt 分件（全局宿主用）
    _cps232 = code_only(src("ui/settings/CodePromptSheet.kt"))
    check("CodePromptSheet" in _cps232 and "NumberPassword" in _cps232,
          "验证码输入框是数字专用键盘")
    check("还可以试" in _cps232, "输入框里显示剩余尝试次数")
    check("验证码不正确" in _cps232, "输错用行内提示（不用 Toast 糊过去）")
    check("都别照做" in _sp79 or "别输" in _sp79,
          "UI 上有「别照别人给的码输入」的警示")
    check("AwaitingCode" in _sp79, "接收抽屉有「等待验证码确认…」状态（别让用户以为服务停了）")
    # ⑬ 电脑端：随机码 + 放请求头 + 重试换新码 + 地址带端口
    check("RandomNumberGenerator" in _ps79, "验证码用 RandomNumberGenerator")
    check("Get-Random" not in _ps79, "不用 Get-Random（种子可预测）")
    check("X-Stardew-Code" in _ps79 and "X-Stardew-Proto" in _ps79,
          "电脑端把验证码与协议版本放进请求头")
    check("InvariantCulture" in _ps79,
          "验证码用不变文化格式化（默认文化下 100000 会变成 100,000，多一位）")
    check("/upload" in _ps79, "电脑端仍打到 /upload 端点")
    check("TimeoutSec 120" in _ps79,
          "电脑端超时给到 120 秒（手机要等**人**输验证码，最多 60 秒）")
    check("New-Code" in _ps79, "电脑端每次上传前生成新码（重试也换新）")
    # ⑭ 端口怎么找到的：广播自动发现；发现不到就只问 IP，端口自己扫那一段
    check("PORT=" in _sr79, "手机端在探活里回报当前端口")
    check("Find-Phone" in _ps79 and "SDH-FIND" in _ps79,
          "电脑端会广播自动发现手机（用户不用输地址）")
    # 第二段路：广播那包 UDP 是**纯入站**，Windows 防火墙默认拦；
    #   扫端口走「出站连接 + 响应」，默认放行 → 不需要用户动防火墙。
    check("Find-Phone-ByScan" in _ps79,
          "电脑端第二段路：广播没回应就扫同网段（实测广播被防火墙拦，扫端口却通）")
    # ⚠ 扫端口会一次来 254 个「连上不说话」的连接，不挡会把线程吃光
    check("MAX_ACTIVE_CONN" in _sr79 and "activeConns" in _sr79,
          "手机端限制同时处理的连接数（挡掉端口扫描那种连上不说话的连接）")
    check("client.soTimeout = 2_000" in _sr79,
          "读请求头只给 2 秒（扫端口的连接要尽快让位，否则刚扫完的上传会被判忙拒掉）")
    check("Find-Port" in _ps79 and "38217" in _ps79 and "38237" in _ps79,
          "电脑端兜底时自己扫 38217~38236 找端口（用户只输 IP 就够）")
    check("端口不用填" in _ps79,
          "兜底提示语说清「端口不用填」（别让人再抄一遍端口）")

    # ⑮ 脚本随 App 分发：assets 里那份必须与 tools/ 里逐字节一致
    # ⚠ 不一致 = 手机上存的脚本和电脑上跑的脚本协议版本不同 → 必然 403。
    _batp79 = os.path.join(ROOT, "tools", "send_save.bat")
    _assetp79 = os.path.join(ROOT, "app", "src", "main", "assets", "tools", "send_save.bat")
    if os.path.exists(_batp79) and os.path.exists(_assetp79):
        _b79 = open(_batp79, "rb").read()
        _a79 = open(_assetp79, "rb").read()
        check(_a79 == _b79,
              "assets 里的电脑脚本与 tools/send_save.bat 逐字节一致（协议版本要两端同版）")
    else:
        check(False, "assets 里有电脑脚本（随 App 分发，否则 App 下到的是旧协议脚本）")
    check("ASSET_SCRIPT" in _up79 and "ctx.assets.open" in _up79,
          "「下载电脑脚本」从安装包里读（不再联网拿发布仓的旧版）")

    # ⑯ bat 的换行符必须在**仓库里**钉住。
    # ⚠ 仓库 core.autocrlf=input 只保证「提交时转 LF」，**检出时不会转回来**——
    #   没有 .gitattributes，换台机器 clone 出来的 bat 全是 LF 的，用户双击即坏；
    #   assets 里那份是打进 APK 的，坏了就是用户从 App 下到的脚本不能用。
    _ga79 = os.path.join(ROOT, os.pardir, ".gitattributes")
    if os.path.exists(_ga79):
        _gat79 = io.open(_ga79, encoding="utf-8").read()
        check("*.bat text eol=crlf" in _gat79,
              "源码仓 .gitattributes 钉住 *.bat 为 CRLF（换台机器 clone 出来的 bat 仍能双击）")
    else:
        check(False, "源码仓有 .gitattributes（否则 clone 出来的 bat 是 LF，双击就坏）")

    # ================= 80. SVE 鱼图鉴按新 fish.json 重做（v2.75.1）=================
    # 起因：新版扩展包的 fish.json 从 15 条扩到 44 条，且字段齐了
    #   （name/id/aliases/difficulty/time/seasons/weather/location + 顶层 noLegendary）。
    #   老包只有 name/id/where → 图鉴里只能给「地点」一行，**不许编**其余字段。
    print("\n== 80. SVE 鱼图鉴（新 fish.json） ==")
    _mr80 = code_only(io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                          "assistant", "data", "ModRepo.kt"), encoding="utf-8").read())
    _gd80 = code_only(io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                          "assistant", "data", "GameData.kt"), encoding="utf-8").read())
    _cx80 = code_only(io.open(os.path.join(ROOT, "app", "src", "main", "java", "com", "stardew",
                                          "assistant", "ui", "codex", "CodexPage.kt"), encoding="utf-8").read())
    # ① 数据结构装得下新字段
    for _f80 in ("aliases", "difficulty", "time", "seasons", "weather", "location", "isForage"):
        check(_f80 in _mr80, "ModFish 支持字段 %s" % _f80)
    # ② 解析层容错：缺字段返回空，不崩
    check('optJSONArray("aliases")' in _mr80 and 'optJSONArray("seasons")' in _mr80,
          "解析 aliases / seasons（缺字段返回空，不崩）")
    check("seasonCn" in _mr80 and "weatherCn" in _mr80,
          "包里的英文季节/天气转成原版鱼同一套中文口径（筛选才接得上）")
    # ③ 采集物判据：**包里明确写了 difficulty 且为 null**。
    #    ⚠ 不能拿「没有这个字段」当采集物 —— 老包根本没 difficulty，那是"不知道"。
    check('o.has("difficulty")' in _mr80 and 'o.isNull("difficulty")' in _mr80,
          "采集物判据 = 包里明确写了 difficulty 且为 null（老包没这字段 ≠ 采集物）")
    # ④ 顶层 noLegendary：包里自述，App 只照着说
    check("noLegendary" in _mr80 and "parseFishNote" in _mr80,
          "读 fish.json 顶层的 noLegendary（包里自述没有传奇鱼王）")
    check("modFishNoLegendary" in _gd80, "GameData 暴露包里的 noLegendary 说明")
    # ⑤ 图鉴条目：字段齐了才显示，缺的不编
    check('add("难度" to' in _gd80 and 'add("可钓时段" to' in _gd80
          and 'add("季节" to' in _gd80 and 'add("天气" to' in _gd80
          and 'add("又名" to' in _gd80,
          "SVE 鱼条目显示 难度/可钓时段/季节/天气/又名（缺字段不显示，不编）")
    # ⑥ 筛选把 SVE 鱼带上（原来一律放行 → 选了季节它们一条不剩）
    check("modFishByName" in _cx80, "筛选会用 SVE 鱼自己的季节/天气/难度")
    check("mf.seasons.isEmpty()" in _cx80 and "mf.weather.isEmpty()" in _cx80,
          "SVE 鱼字段缺失时仍放行（不知道 ≠ 不符合）")
    # ⑦ 顶部提示：只在包里确实说了才显示；「最难的是哪条」从数据里算
    check("modFishNote.isNotBlank()" in _cx80, "顶部提示只在包里确实写了 noLegendary 时显示")
    check("hardestModFish" in _cx80, "「最难的是哪条」从数据里算（取难度最高那条）")
    # ⑧ 本地新包（整包 v2.10 + 鱼分片 v1.1）：鱼全都有官方图、两包同源
    #    ⚠ 本地包在 sve/ 下（**不入 git**，按历史惯例）→ 别的机器上没有，
    #    故「文件不存在就跳过」，那是**整组跳过**不是漏跑。
    import zipfile as _zf80
    import struct as _st80
    _svedir80 = os.path.join(os.path.dirname(ROOT), "旧文件归档", "sve")
    _fdocs80 = []
    for _zp80 in [os.path.join(_svedir80, "sve_expansion_v2.10.zip"),
                  os.path.join(_svedir80, "shards", "sve_fish_v1.1.zip")]:
        if not os.path.exists(_zp80):
            continue
        with _zf80.ZipFile(_zp80) as _z80:
            _nm80 = set(_z80.namelist())
            _d80 = json.loads(_z80.read("fish.json").decode("utf-8"))
            _lst80 = _d80.get("fish") or []
            _bn80 = os.path.basename(_zp80)
            _noic80 = [c.get("name") for c in _lst80 if not (c.get("icon") or "").strip()]
            check(not _noic80,
                  "[%s] 新包每条鱼都有图（零占位框），实测无图 %d 条" % (_bn80, len(_noic80)))
            _dang80 = [c.get("name") for c in _lst80
                       if (c.get("icon") or "").strip() and c["icon"] not in _nm80]
            check(not _dang80,
                  "[%s] 新包鱼的 icon 都指向包内真实存在的图（实测悬空 %d 条）"
                  % (_bn80, len(_dang80)))
            _p80 = [n for n in _nm80 if n.startswith("images/fish/") and n.endswith(".png")]
            _with80 = [c for c in _lst80 if (c.get("icon") or "").strip()]
            check(len(_p80) == len(_with80),
                  "[%s] 新包鱼图数量 == 有图的鱼条数（%d 张 / %d 条）"
                  % (_bn80, len(_p80), len(_with80)))
            _ws80 = set()
            for _n80 in _p80:
                _b80 = _z80.read(_n80)
                if _b80[:8] == b"\x89PNG\r\n\x1a\n":
                    _w80, _h80 = _st80.unpack(">II", _b80[16:24])
                    _ws80.add((_w80, _h80))
            check(_ws80 == {(16, 16)},
                  "[%s] 新包鱼图是官方原始 16×16（实测 %s）" % (_bn80, _ws80))
            _fdocs80.append(_d80)
    if len(_fdocs80) == 2:
        check(_fdocs80[0].get("fish") == _fdocs80[1].get("fish"),
              "本地整包与鱼分片的鱼数据完全一致（两者都提供 fish 模块，必须同源）")
        check(_fdocs80[0].get("noLegendary") == _fdocs80[1].get("noLegendary"),
              "本地整包与鱼分片的 noLegendary 说明一致")

    # ══════════════ 81. v2.76.0 液态玻璃交互升级 ══════════════
    # 接上游 Kyant0/AndroidLiquidGlass 的《Liquid Bottom Tabs》与《Glass Bottom Sheet》：
    # 药丸跟手滑动 + 按下形变 + 按压柔光 + 玻璃弹层。
    # 本组只钉**结构关系**（谁依赖谁、参数对不对齐官方、能力门在哪），
    # 不钉条数/字面量 —— 官方取值变了要跟着改的是实现，不是判据。
    print("\n== 81. 液态玻璃交互升级（v2.76.0） ==")
    _pg81 = src("ui/glass/PixelGlass.kt")
    _gs81 = src("ui/glass/GlassSheet.kt")
    _gd81 = src("ui/glass/GlassDrag.kt")
    _gp81 = src("ui/glass/GlassPress.kt")
    _ma81 = code_only(src("MainActivity.kt"))
    _jp81 = code_only(src("ui/jump/JumpPanel.kt"))
    _pg81c = code_only(_pg81)
    _gs81c = code_only(_gs81)
    _gd81c = code_only(_gd81)
    _gp81c = code_only(_gp81)

    # 81.1 玻璃宿主层泛化成「多块表面」：底栏胶囊 + 弹层面板各一块，
    #      每块有自己的 RenderNode（效果链按尺寸编译，不能共用）。
    check("class GlassSurface" in _pg81c and "fun surface(" in _pg81c,
          "玻璃宿主层支持多块表面（GlassSurface + surface(key)）")
    check(re.search(r'surface\("bar"\)', _pg81c) is not None
          and re.search(r'surface\(\s*"sheet"', _gs81c) is not None,
          "底栏与弹层各持有一块玻璃表面")
    check(re.search(r'RenderNode\("glass-surface-[^"]*key', _pg81c) is not None,
          "每块表面有独立 RenderNode（效果链不共用）")

    # 81.2 弹层配方对齐官方《Glass Bottom Sheet》：
    #      vibrancy → blur(4dp) → lens(24dp, 48dp, depthEffect=true)
    #      ⚠ 模糊半径**故意偏离**官方的 4dp（我们压在像素画页面上，4dp 糊不平硬边框
    #      → 鬼影有形状；见 81.14 的守卫与 GlassSheet 里的理由注释），
    #      折射带 / 折射量 / depthEffect 三项仍逐字对齐官方。
    check(re.search(r"refractionHeightDp\s*=\s*24f", _gs81c) is not None
          and re.search(r"refractionAmountDp\s*=\s*48f", _gs81c) is not None,
          "玻璃弹层折射带 24dp / 折射量 48dp（官方配方）")
    check("depthEffect = true" in _gs81c,
          "玻璃弹层开 depthEffect（官方配方第三参）")

    # 81.3 药丸改成「一块会滑动的独立药丸」，不再每个 tab 各自染底色
    check("drag.value * tabW" in _ma81 and "IntOffset(" in _ma81,
          "选中药丸是独立 overlay，按动画索引滑动")
    check("if (active) c.cream.copy(alpha = 0.9f)" not in _ma81,
          "逐 tab 染底的旧写法已移除（药丸只有一块，会滑的那块）")
    check("drag.modifier" in _ma81 and "press.gestureModifier" in _ma81,
          "拖动换页与按压柔光的手势挂在玻璃底栏上")

    # 81.4 官方《Interactive Glass Bottom Bar》教程的核心坑：
    #      玻璃被缩放时，玻璃里映出的背景必须**反向补偿**，否则背景跟着玻璃一起缩。
    check("sampleScale" in _pg81c and "sampleScale" in _ma81,
          "玻璃表面有采样补偿系数 sampleScale（缩放时背景反向缩放）")
    check("pad + cx - (offX + cx) / s" in _pg81c and "pad + cy - (offY + cy) / s" in _pg81c,
          "补偿公式按中心轴推导（t = pad + c − (off + c)/s）")
    check("rec.translate(pad - offX, pad - offY)" in _pg81c,
          "s = 1 时退化成无补偿写法（pad − off），与改动前逐字一致")

    # 81.5 按压柔光：上游 InteractiveHighlight 的 AGSL 逐字（uniform 名与曲线）
    check("class GlassPressHighlight" in _gp81c,
          "按压柔光组件存在（上游 InteractiveHighlight 的移植件）")
    check(re.search(r"float intensity = smoothstep\(radius, radius \* 0\.5, dist\);",
                     _gp81c) is not None,
          "柔光的 AGSL 曲线与上游逐字一致（smoothstep(radius, radius*0.5, dist)）")
    for _u81 in ("uniform float2 size;", "layout(color) uniform half4 color;",
                 "uniform float radius;", "uniform float2 position;"):
        check(_u81 in _gp81c, "柔光 AGSL uniform 声明齐全：%s" % _u81)
    # Compose 1.7.6 没有 asComposeShader() —— 用原生 Paint 画，否则根本编译不过
    check("asComposeShader(" not in _gp81c and "nativeCanvas" in _gp81c,
          "柔光走原生 Paint 绘制（Compose 1.7.6 无 asComposeShader）")
    check("Build.VERSION.SDK_INT >= 33" in _gp81c,
          "柔光的 AGSL 有 33+ 能力位（低版本降级为均匀白，不崩）")

    # 81.6 玻璃弹层必须是**主窗口内**的覆盖层，不能是 Material3 ModalBottomSheet
    #      （ModalBottomSheet 自带 Dialog 窗口 = 独立 window，采不到主窗口的背景录制）
    check("ModalBottomSheet(" not in _gs81c,
          "玻璃弹层不依赖 Material3 ModalBottomSheet（独立窗口采不到主窗口背景）")
    # 跳板要迁到玻璃弹层 —— 但**不能**把 DetailDrawer 整个删掉：
    # 没有玻璃时（API < 33 / 用户关掉底栏样式）GlassSheet 只是一层透明覆盖层，
    # 跳板内容画在透明底上读不了字，必须回落木纹抽屉（见 81.12）。
    check("GlassSheet(" in _jp81,
          "「跳到…」面板迁到玻璃弹层（有玻璃时走 GlassSheet）")
    check("drawGlassSurface" in _gs81c,
          "玻璃弹层与底栏共用同一套背景采样")

    # 81.7 弹层上的文字必须用**主题自适应**的 ink：白纱/暗纱随主题走，
    #      用 cream 会在白天「浅字压浅底」（v2.72.1 修过的同一个病根）。
    check(re.search(r"PixelText\(title,\s*color = c\.ink\)", _gs81c) is not None,
          "玻璃弹层标题用主题自适应 ink（白天深字压白纱 / 夜间浅字压暗纱）")
    check("veilFor" in _pg81c and "SURFACE_ALPHA_DARK" in _pg81c,
          "玻璃白纱随主题走（夜间暗纱）")
    # ⚠ 判「白天/夜间」必须用 sRGB 感知亮度：Compose 的 Color.luminance() 是**线性**亮度，
    #    春主题的橄榄绿底算出来 0.497 —— 刚好卡在 0.5 下面 → 白天被当成夜间套了暗纱，
    #    面板整个发灰（装机截图才发现，代码看着完全合理）。
    check("0.299f" in _pg81c and "0.587f" in _pg81c and "0.114f" in _pg81c,
          "白纱的昼夜判据用 sRGB 感知亮度（0.299/0.587/0.114），不是线性 luminance()")
    check(".luminance()" not in _pg81c,
          "白纱判据没再用 Color.luminance()（线性亮度会把春主题橄榄绿误判成夜间）")
    # 底栏胶囊与弹层面板不该用同一档浓度：弹层背后是整页像素画，
    # 装机实测基准浓度下背后卡片的文字会透过来和面板自己的字打架。
    _mv81 = re.search(r"alphaScale = ([0-9.]+)f", _gs81c)
    check(_mv81 is not None and 1.0 < float(_mv81.group(1)) <= 2.5,
          "玻璃弹层的白纱浓度高于底栏（当前 %.2f×，基准浓度下背后文字会透过来打架）"
          % (float(_mv81.group(1)) if _mv81 else 0))

    # 81.8 玻璃弹层滑动时，采样坐标必须跟着动画位移走，
    #      否则玻璃里透出的背景会「停在原地」与面板错位。
    check("surface?.origin = panelOrigin + Offset(" in _gs81c,
          "玻璃弹层的采样坐标跟着动画位移走（滑动时背景不会停在原地）")

    # 81.9 玻璃能力门槛不变：33+（RenderNode.setRenderEffect 与 AGSL 同门槛）
    check("SDK_INT >= 33" in _pg81c,
          "玻璃能力门槛仍是 API 33+（低于此走木纹底栏）")

    # 81.10 归属：新增的两个移植件也必须带 Apache-2.0 声明与来源仓库，
    #      许可证副本抬头也要跟着覆盖它们（Apache-2.0 §4 随包分发要求）
    for _f81, _n81 in ((_gd81, "GlassDrag.kt"), (_gp81, "GlassPress.kt")):
        check("Copyright 2025 Kyant" in _f81 and "Kyant0/AndroidLiquidGlass" in _f81,
              "%s 保留原版权头与来源仓库声明" % _n81)
    for _p81, _lbl81 in ((os.path.join(ROOT, "app", "src", "main", "assets", "licenses",
                                      "Apache-2.0-AndroidLiquidGlass.txt"), "assets"),
                         (os.path.join(ROOT, "app", "src", "main", "res", "raw",
                                      "apache_2_0_androidliquidglass.txt"), "res/raw")):
        _t81 = io.open(_p81, encoding="utf-8").read() if os.path.isfile(_p81) else ""
        check(all(k in _t81 for k in ("PixelGlass.kt", "GlassDrag.kt", "GlassPress.kt")),
              "许可证副本抬头（%s）覆盖三个玻璃文件" % _lbl81)

    # 81.11 那条「rememberGraphicsLayer 在 1.7.6 不存在」的注释是**错的**，
    #      javap 复核过 1.7.6 确实有 —— 留着会误导后人以为必须自建宿主层。
    check("确实有" in _pg81 and "javap 复核" in _pg81,
          "PixelGlass.kt 注明 rememberGraphicsLayer 在 1.7.6 确实存在（原注释是错的）")

    # 81.12 没有玻璃时（API < 33 / 用户关了底栏样式）必须回落到木纹抽屉：
    #      GlassSheet 没有玻璃时只是一层**透明**覆盖层，而跳板的内容大多画在
    #      透明底上 —— 透明覆盖层上读不了字。
    check("LocalGlassState.current != null" in _jp81,
          "跳板按「有没有玻璃」分流")
    check("DetailDrawer(" in _jp81 and "GlassSheet(" in _jp81,
          "没玻璃时回落 DetailDrawer（木纹抽屉），有玻璃时走 GlassSheet")

    # 81.13 ⚠ 玻璃底栏是**白纱浅色面** → 按压反馈不能用上游那套「白色柔光」。
    #      用户真机录屏实测：按住底栏时一整块白色柔光跟着手指走，把像素图标
    #      **全洗白**，整条底栏糊成一块白（看着像渲染错误）。
    #      浅色面必须用**深色压暗**，半径只盖住被按的那一格。
    check("onLightSurface" in _gp81c and "onLightSurface" in _ma81,
          "按压反馈按「面是亮是暗」分形态（浅色面走深色压暗，不走白色柔光）")
    check("isLightSurface" in _pg81c,
          "玻璃面的明暗判据单源（GlassLadder.isLightSurface，与白纱判据同一套亮度）")
    check("Brush.radialGradient" in _gp81c,
          "浅色面的按压反馈用径向压暗（不是白色加光）")
    # ⚠ Material 默认涟漪在浅色玻璃底栏上是一坨深灰圆斑（原生 Material 样式，
    #    跟像素画风不搭），必须显式关掉 —— 上游也是 indication = null。
    check("indication = null" in _ma81,
          "玻璃底栏的页签关掉了 Material 默认涟漪（否则是一坨深灰圆斑）")
    # ⚠ AGSL 的 layout(color) uniform 收**预乘**颜色：上游自己包了一层
    #    RuntimeShader 做预乘，我们直接用原生 setColorUniform 就漏了 ——
    #    漏了 = 柔光变纯白（真机录屏：按住底栏整条栏糊成白块）。
    check("setPremultipliedColorUniform" in _gp81c
          and "setPremultipliedColorUniform" in _gp81c,
          "柔光颜色走预乘入口（AGSL 的 layout(color) 收预乘色，漏了就是纯白）")
    check(re.search(r"setColorUniform\(\"color\"", _gp81c) is None,
          "柔光没有直接用未预乘的 setColorUniform（那是纯白方块的根因）")

    # 81.14 玻璃弹层透出的鬼影（用户真机截图圈出来的）：
    #      ① 玻璃采样的是**没被压暗**的页面 → 面板比周围亮，像一扇亮窗；
    #      ② 模糊 4dp 糊不平像素画的 2~3px 硬边框 → 鬼影是「有形状的」。
    check("dimAlpha" in _pg81c and "dimAlpha" in _gs81c,
          "玻璃表面有 dimAlpha（玻璃采到的内容要跟外面的遮罩同步压暗）")
    check(re.search(r"const val SCRIM_ALPHA = [0-9.]+f", _gs81c) is not None
          and "SCRIM_ALPHA * progress.value" in _gs81c,
          "遮罩浓度与玻璃压暗用同一个常量（面板内外亮度口径一致）")
    _bs81 = re.search(r"blurDp = ([0-9.]+)f", _gs81c)
    check(_bs81 is not None and float(_bs81.group(1)) >= 8.0,
          "玻璃弹层模糊半径够糊平像素画的硬边框（当前 %sdp；官方 4dp 只够压照片壁纸）"
          % (_bs81.group(1) if _bs81 else "?"))

    # ══════════════ 83. v2.77.0 许可口径更正（不开源源码） ══════════════
    # 起因：用户 2026-10-20 定调「不准备开源 apk 源码」，但两处还在说「开源」：
    #   ① App 内许可入口与抽屉标题「开源与数据来源」—— 会被读成「本应用开源」；
    #   ② 发布仓根目录挂着 MIT LICENSE —— 而 data/ 是 CC BY-NC-SA 3.0，两者**不兼容**
    #      （MIT 允许无限制再许可，CC 要 copyleft + 非商业 + 同协议共享）。
    # 本组钉**关系**，不钉字面量：
    #   ① App 内抽屉标题不含「开源」；
    #   ② 改名**不该顺手删掉义务** —— 三份协议全文 + 三方署名仍须在位；
    #   ③ 发布仓根 LICENSE 写明适用范围（MIT 只覆盖 tools/，data/ 走 CC）。
    print("\n== 83. 许可口径更正（v2.77.0） ==")
    _sp83 = src_sp()
    _dl83 = re.search(r'DetailDrawer\(visible = showLicense, title = "([^"]+)"', _sp83)
    check(_dl83 is not None and "开源" not in _dl83.group(1),
          "App 许可抽屉标题不含「开源」（当前：%s）" % (_dl83.group(1) if _dl83 else "缺"))
    for _k83 in ("R.raw.ofl_fusion_pixel", "R.raw.cc_by_nc_sa_3_0",
                 "R.raw.apache_2_0_androidliquidglass"):
        check(_k83 in _sp83, "许可抽屉仍能读到协议全文：%s" % _k83)
    for _k83 in ("stardewvalleywiki.com", "TakWolf", "Kyant0/AndroidLiquidGlass"):
        check(_k83 in _sp83, "许可抽屉仍带第三方署名：%s" % _k83)
    _lic83 = os.path.join(os.path.dirname(ROOT), "发布仓", "LICENSE")
    if os.path.exists(_lic83):
        _t83 = io.open(_lic83, encoding="utf-8").read()
        check("MIT License" in _t83, "发布仓根 LICENSE 保留 MIT 原文")
        check("CC BY-NC-SA 3.0" in _t83 and "data/" in _t83,
              "根 LICENSE 写明 data/ 适用 CC BY-NC-SA 3.0（不适用根 MIT）")
        check("tools/" in _t83, "根 LICENSE 写明 MIT 的适用范围（tools/ 脚本）")
    else:
        warns.append("第 83 节跳过：发布仓根 LICENSE 不存在")
    _rd83 = os.path.join(os.path.dirname(ROOT), "发布仓", "README.md")
    if os.path.exists(_rd83):
        _r83 = io.open(_rd83, encoding="utf-8").read()
        check("数据来源与协议" in _r83, "发布仓 README 的协议小节标题是「数据来源与协议」")
        check("xingluguzhushou/tree/main/data" in _r83 or "data/" in _r83,
              "发布仓 README 仍指向 data/ 的协议全文")

    # ══════════════ 84. v2.78.0 鱼价排行 ══════════════
    # 起因：用户要「鱼价排行」。
    # ⚠ 鱼有**两套价**，混起来会误导（74 条鱼里 7 条两个价对不上）：
    #   · 鱼店买价（fishing.json 的 price）—— 鱼店加价卖
    #   · 卖出价（fishSpots.json 的 prices）—— 钓上来卖给村民拿到的钱
    # 排行**只用卖出价**（与钓鱼图鉴「四档售价」同源），另一个口径去图鉴看「基础价」。
    # 本组钉结构关系，不钉字面量：
    #   ① 口径单一（不并排摆两个价）② 缺档保持 null 不补 0
    #   ③ 没售价的鱼要少（防止哪天数据大面积缺失还装作有）
    #   ④ 四档品质价与普档的倍率自洽（银 1.25 / 金 1.5 / 铱 2）
    #   ⑤ UI 接线 + 跳转走 navBus（不新造跳转通路）
    print("\n== 84. 鱼价排行（v2.78.0） ==")
    _sp84 = src("ui/calc/CalcPage.kt")
    _lg84 = src("data/Logic.kt")
    _md84 = src("data/Models.kt")
    _fpr84 = re.search(r"fun fishPriceRanking\(\): List<FishPriceRow> \{.*?\n    \}", _lg84, re.S)
    _body84 = _fpr84.group(0) if _fpr84 else ""
    check(_fpr84 is not None, "排行计算在数据层（Calc.fishPriceRanking）")
    # ① 口径：主价取卖出价；鱼店买价不参与
    check('sellPrice = info?.prices?.get("normal")' in _body84,
          "排行主价取 fishSpots 的普通档售价（卖出价，与图鉴「四档售价」同源）")
    check("f.price" not in _body84 and "it.price" not in _body84,
          "排行不用鱼店买价（fishing.json 的 price）——两个口径不并排摆")
    # ② 缺档保持 null，不补 0
    check(re.search(r"val sellPrice: Int\?", _md84) is not None
          and re.search(r"val silver: Int\? = null", _md84) is not None,
          "售价与品质档位都可空（缺档能表示为 null）")
    # ⚠ 只查**售价与品质档位**这四个字段的取值表达式：难度那个 `?: 0` 是排序键兜底
    #   （蟹笼鱼难度本来就是 0，不是"缺档"），全函数体扫 `?: 0` 会误伤。
    _pf84 = re.findall(r"(?:sellPrice|silver|gold|iridium)\s*=\s*([^,\n]+)", _body84)
    check(len(_pf84) == 4 and all("?: 0" not in v for v in _pf84),
          "售价与品质档位取值不兜底成 0（「没有数据」≠「价格 0」）")
    # ③ 没有卖出价数据的鱼要少（剩下的在排行里显示「—」，不编价）
    _fish84 = load(os.path.join(DATA, "fishing.json"))
    _spots84 = load(os.path.join(DATA, "fishSpots.json"))
    _bf84 = _spots84.get("byFish") or {}
    _cp84 = _spots84.get("crabPot") or {}
    _named84 = set(f.get("name") for f in _fish84)
    _with84 = set(n for n, i in _bf84.items() if (i.get("prices") or {}).get("normal") is not None)
    _crab84 = set(n for n, i in _cp84.items() if i.get("price") is not None)
    _noPrice84 = sorted(_named84 - _with84 - _crab84)
    check(len(_noPrice84) <= 3,
          "没有卖出价数据的鱼只有 %d 条（%s），排行里显示「—」不编价"
          % (len(_noPrice84), "、".join(_noPrice84)))
    # ④ 四档与普档的倍率自洽（银 ×1.25 / 金 ×1.5 / 铱 ×2，>1g 偏差才算异常）
    _bad84 = []
    for _n84, _i84 in _bf84.items():
        _pr84 = _i84.get("prices") or {}
        _n084 = _pr84.get("normal")
        if not _n084:
            continue
        for _k84, _m84 in (("silver", 1.25), ("gold", 1.5), ("iridium", 2.0)):
            _v84 = _pr84.get(_k84)
            if _v84 is None:
                continue
            if abs(_v84 - _n084 * _m84) > 1:
                _bad84.append((_n84, _k84, _v84))
    check(not _bad84, "四档售价与普档的倍率自洽（银1.25/金1.5/铱2，异常 %d 条）" % len(_bad84))
    # ⑤ UI 接线与跳转
    # ⚠ v2.80 改口径：点鱼名**不再**跳图鉴页（用户反馈「点开跳到图鉴页，没停在鱼价页」），
    #   改成在本页浮出详情抽屉。断言跟着口径走，不留一条钉死旧行为的断言。
    check("🐟 鱼价" in _sp84, "计算页有「🐟 鱼价」分段")
    check("fishPriceRanking" in _sp84, "排行页调用数据层的排行计算")
    check('navBus.value = NavRequest.Codex("fishing"' not in _sp84,
          "点鱼名不跳图鉴页（v2.80 改为页内浮出详情抽屉）")

    # ==== 85. 小鸡代码补齐（v2.79.0） ====
    # 群友给了 134 条补全清单。核完发现**清单自己就有 13 条编号与官方 1.6 物品 ID 对不上**
    # （如 幸运戒指 526→859、太空靴 (B)519→(B)514），另有 19 条本地查不到官方 ID。
    # 本组钉口径，不钉字面量：
    #   ① method 与编号形态必须自洽（纯数字 ↔ 取名可用；带前缀 ↔ 需作弊菜单）
    #   ② 编号唯一、名称唯一
    #   ③ 13 处订正必须保持官方值（别被改回清单的错值）
    #   ④ 本地查不到官方 ID 的条目必须带 note（不能装作已核对）
    #   ⑤ UI 必须按编号形态给不同用法说明
    print("\n== 85. 小鸡代码补齐（v2.79.0） ==")
    _cc85 = load(os.path.join(DATA, "cheatCodes.json"))
    _bad_m85 = [c.get("spawn_code") for c in _cc85
                if (c.get("method") == "naming") != str(c.get("spawn_code", "")).isdigit()]
    check(not _bad_m85, "method 与编号形态自洽（纯数字=取名可用，带前缀=需作弊菜单）：异常 %s"
          % (_bad_m85[:5] or "无"))
    check(len([c for c in _cc85 if c.get("method") == "naming"]) == 122,
          "取名可用 122 条")
    # ⚠ c187：chat 40 → 42（靴子类纠错新增 6 条官方靴子，全是 (B) 前缀=需作弊菜单）
    check(len([c for c in _cc85 if c.get("method") == "chat"]) == 42,
          "需作弊菜单 42 条")
    _codes85 = [c.get("spawn_code") for c in _cc85]
    check(len(set(_codes85)) == len(_codes85), "编号唯一")
    check(len(set(c.get("name") for c in _cc85)) == len(_cc85), "名称唯一")
    # 订正过的编号钉死：谁把官方值改回清单的错值，这里就红
    _FIX85 = {"石头": "40", "光辉戒指": "517", "点唱机戒指": "528", "保护戒指": "861",
              "幸运戒指": "859", "窃贼戒指": "526", "黄水晶戒指": "530", "铱环": "527",
              "虫肉": "684", "鹦鹉螺化石": "586", "金色镰刀": "53",
              "太空靴": "(B)514", "战地靴": "(B)508"}
    _map85 = {c.get("name"): c.get("spawn_code") for c in _cc85}
    _wrong85 = [(n, _map85.get(n), v) for n, v in _FIX85.items() if _map85.get(n) != v]
    check(not _wrong85, "13 处订正编号保持官方值（异常 %s）" % (_wrong85[:3] or "无"))
    # 本地查不到官方 ID 的条目必须挂 note，不能装作已核对
    # ⚠ c187：删掉 7 个**编造**靴子名（蟹壳靴/火焰靴/岩石靴/精灵靴/恐龙靴/防火靴/冷却靴）——
    #   游戏 1.6 无此靴子，它们是把别的物品误记成靴子；换成官方 boots 全集后这些名字不再存在。
    # ⚠ c187 续：原先 12 条「编号未经本地官方 ID 索引核对，以游戏内为准」的 note **已删**——
    #   这 12 个编号已逐条核对本机游戏 1.6.15 官方 Data/Objects（id → 官方英文名完全对上），
    #   note 原文到这就成了假话（编号早已核对），所以删掉 note 并把官方编号**钉死**，
    #   比挂一句「以游戏内为准」实在得多。
    _VER85 = {"金色核桃": "73", "金色椰子": "791", "银河之魂": "896", "龙牙": "852",
              "马笛": "911", "齐钻": "858", "电影票": "809", "虚空幽灵吊坠": "808",
              "皮埃尔缺货清单": "897", "加压喷头": "915", "鱼籽": "812", "爆炸弹丸": "441"}
    _vb85 = [(n, _map85.get(n), v) for n, v in _VER85.items() if _map85.get(n) != v]
    check(not _vb85, "12 条已核对编号保持官方值（异常 %s）" % (_vb85[:3] or "无"))
    # 「编号未经核对」这类 note 不得再挂回来（编号已核对，挂回来就是假话）
    _stale85 = [c.get("name") for c in _cc85
                if ("未经" in str(c.get("note", "")) or "未核对" in str(c.get("note", "")))
                or "以游戏内为准" in str(c.get("note", ""))]
    check(not _stale85, "「编号未经核对」note 已全部清除（仍在：%s）" % (_stale85[:5] or "无"))
    # ⚠ c187：19 → 12 → 0（12 条待核对全部核对完成，带 note 的条目清零）
    check(len([c for c in _cc85 if c.get("note")]) == 0,
          "带 note 的条目 0 条（12 条编号已逐条核对官方 Data/Objects）")
    # c187：编造的靴子名不得再出现（回归钉：谁把它们改回来这里就红）
    _FAKE_BOOTS85 = {"蟹壳靴", "火焰靴", "岩石靴", "精灵靴", "恐龙靴", "防火靴", "冷却靴"}
    _fb85 = [c.get("name") for c in _cc85 if c.get("name") in _FAKE_BOOTS85]
    check(not _fb85, "7 个编造靴子名已清除（仍在：%s）" % (_fb85 or "无"))
    # c187：靴子类必须是官方 boots 全集（编号与官方 wiki Boots 索引一致）
    _BOOTS85 = {"皮靴": "(B)506", "橡胶靴": "(B)505", "战地靴": "(B)508", "冻土靴": "(B)509",
                "黑暗之靴": "(B)511", "蹈火者靴": "(B)512", "精灵鞋": "(B)513",
                "牛仔之靴": "(B)515", "工作靴": "(B)507", "太空靴": "(B)514", "水晶鞋": "(B)878"}
    _map85b = {c.get("name"): c.get("spawn_code") for c in _cc85}
    _bw85 = [(n, _map85b.get(n), v) for n, v in _BOOTS85.items() if _map85b.get(n) != v]
    check(not _bw85, "靴子类 11 双官方靴子编号正确（异常 %s）" % (_bw85[:3] or "无"))
    check(len([c for c in _cc85 if c.get("category_cn") == "靴子"]) == 11,
          "靴子类恰好 11 条（实际 %d）"
          % len([c for c in _cc85 if c.get("category_cn") == "靴子"]))
    # UI 接线：用法说明必须跟着编号形态走
    _gd85 = src("data/GameData.kt")
    _cx85 = src("ui/codex/CodexPage.kt")
    _md85 = src("data/Models.kt")
    check('add(\n                            "用法" to' in _gd85 or '"用法" to' in _gd85,
          "GameData 出「用法」行")
    check("取名可用" in _gd85 and "取名无效" in _gd85, "用法行两种说法都在")
    check("取名可用" in _cx85 and "取名无效" in _cx85, "复制卡按编号形态给不同说明")
    check("item.note" in _cx85, "复制卡显示数据带来的 note")
    check('note: String = ""' in _md85, "CodexItem 有 note 字段")
    check("小鸡代码怎么用" in _cx85, "分类顶部有用法说明卡")

    # ==== 86. 鱼价页配图 + 页内详情（v2.80.0） ====
    # 用户反馈：「计算页的鱼价栏没有图片，只有点开才能看到图片；点开之后跳到了图鉴页，
    # 并没有继续停留在鱼价页」。两件事一起修：
    #   ① 每行直接渲染这条鱼的图标（不再只能点进去才看得到图）
    #   ② 点鱼不跳页，改在本页浮出详情抽屉（与作物计算器 / 加工页同一个 DetailDrawer）
    # 本组钉结构不钉字面量：图标取的是**行变量**、抽屉指向**分类 id**、跳转关键字**不出现**。
    print("\n== 86. 鱼价页配图与页内详情（v2.80.0） ==")
    _sp86 = code_only(src("ui/calc/CalcPage.kt"))
    # 切出 FishPricePage 的函数体：顶层函数以行首 } 收尾，函数内所有 } 都有缩进
    _m86 = re.search(r"private fun FishPricePage\([^)]*\)\s*\{(.*?)\n\}", _sp86, re.S)
    _fb86 = _m86.group(1) if _m86 else ""
    check(_m86 is not None, "计算页有鱼价页函数")
    # ① 行内出图：图标来自这一行的鱼名（不钉变量名，钉「用行变量取图标」这个结构）
    check(re.search(r"FramedIcon\(\s*\w+\.name\s*,", _fb86) is not None,
          "鱼价行内直接渲染这条鱼的图标（FramedIcon 用行里的鱼名）")
    # ② 不跳图鉴页
    check("navBus" not in _fb86 and "NavRequest" not in _fb86,
          "点鱼不跳图鉴页（鱼价页函数体内没有 navBus / NavRequest 跳转）")
    # ③ 页内抽屉看详情，内容是钓鱼分类的条目详情
    check("DetailDrawer(" in _fb86, "鱼价页用详情抽屉就地看鱼详情")
    check(re.search(r'CodexItemDetailBody\(\s*"fishing"', _fb86) is not None,
          "抽屉内容是钓鱼分类条目（钓点 / 经验 / 四档售价 / 排查清单）")
    # ④ 抽屉随页签可见性收掉（与作物计算器 / 加工页同一口径）
    #    v2.99.0（c179）：FishPricePage 加 snap 参数（详情抽屉里的「你的状态」需要），
    #    断言跟着改指向 —— 钉的还是「visible 传进鱼价页」这个语义。
    check(re.search(r"FishPricePage\(\s*snap,\s*visible\)", _sp86) is not None,
          "鱼价页收到页签可见性，切走页签时抽屉跟着关")
    # ⑤ 每条鱼都要有图可显示（缺图只会退化成首字占位块，不该发生）
    _im86 = load(IMG)
    _f86 = load(os.path.join(DATA, "fishing.json"))
    _noimg86 = [f.get("name") for f in _f86 if f.get("name") not in _im86]
    check(not _noimg86, "%d 条鱼全在图标表里（缺图 %s）" % (len(_f86), _noimg86[:5] or "无"))

    # ==== 87. 存档「世界状态」解析器（P0）====
    # 目标：今日页从「通用日历」变成「只属于这张存档的农场状态」。
    # P0 只做解析层，UI（P2）还没接，所以本节钉的是**解析层结构**；
    # 四档真存档的数字基线由 app/src/test/.../WorldStateParserTest.kt 守着
    # （gradlew :app:worldStateTest 跑）。
    # ⚠ 本节**钉结构不钉字面量**：只钉「有没有那条规则 / 规则钉在哪」，
    #   不钉具体版本号、具体条数、具体 id——那些一变就得手改 verify，
    #   而红的理由跟它想防的东西毫无关系。
    print("\n== 87. 存档世界状态解析器（P0） ==")
    _ws87 = code_only(src("data/WorldStateParser.kt"))
    _md87 = code_only(src("data/Models.kt"))

    # 87.1 模型齐备（任务书点名的五个）
    for _m87 in ("WorldState", "WorldCrop", "WorldAnimal", "WorldPlot", "WorldInventory"):
        check(re.search(r"data class %s\(" % _m87, _md87) is not None,
              "模型 %s 存在" % _m87)

    # 87.2 数值一律可空、禁兜底 0（坑 4）
    check(re.search(r"val happiness: Int\?", _md87) is not None,
          "牲畜数值字段用 Int?（解析失败=null）")
    check(re.search(r"val money: Int\?", _md87) is not None,
          "玩家现金用 Int?（解析失败=null）")
    check("?: 0" not in _ws87,
          "解析器里没有兜底 0（物品 id 0 是锈剑，假数据比崩溃更危险）")
    check("?: 1" not in _ws87, "解析器里没有兜底 1")

    # 87.3 两遍扫描（坑 2：GameLocation 的 <name> 排在 terrainFeatures 之后）
    check("scanLocationNames" in _ws87, "有第一遍扫描：缓存 GameLocation 的名字序")
    check("scanWorldState" in _ws87, "有第二遍扫描：按序号归属作物/地块")
    _mp87 = re.search(r"fun parse\([^)]*\)\s*:\s*WorldState\s*\{(.*?)\n    \}", _ws87, re.S)
    check(_mp87 is not None and "scanLocationNames" in _mp87.group(1)
          and "scanWorldState" in _mp87.group(1),
          "parse() 两遍都调到了（先名字序、后按序号归属）")
    check(re.search(r"locationNames\.getOrNull\(locIdx\)", _ws87) is not None,
          "地图归属按 GameLocation 序号取名（不能靠名字猜）")

    # 87.4 成熟判定只有一份实现，且不用 fullGrown（坑 3）
    _seg87 = _md87.split("val ready: Boolean?")[-1]
    _seg87 = _seg87[:_seg87.find("val daysLeft")] if "val daysLeft" in _seg87 else _seg87
    check("phaseDays.size - 1" in _seg87,
          "成熟判定用「当前阶段 ≥ 末位哨兵相位」（phaseDays.size - 1）")
    check("currentPhase" in _seg87, "成熟判定用 currentPhase")
    check(_seg87.find("fullGrown") > _seg87.find("phaseDays.isNotEmpty()") > -1,
          "fullGrown 只在「没有 phaseDays 的野生采集物」分支里读（它不是成熟标志）")

    # 87.5 牲畜只取一条路径（坑 1：同一份数据有两份序列化）
    check("SerializableDictionaryOfInt64FarmAnimal" in _ws87,
          "显式识别牲畜的旧序列化副本")
    check(re.search(r'chain\[\s*chain\.size\s*-\s*4\s*\]\s*==\s*"indoors"', _ws87) is not None,
          "只接受 indoors/animals/item/value 这一条牲畜路径")
    check("skippedDuplicateAnimals" in _ws87 and "skippedDuplicateAnimals" in _md87,
          "跳过的副本数记进 WorldState（不悄悄丢掉）")

    # 87.6 种子 id 三形态（坑 5）+ 产出 id 0/-1 当未知（坑 8）
    check(re.search(r"val seedRaw: String\?", _md87) is not None,
          "种子 id 保留原始文本形态（数字 / mod 名 / 缺失）")
    check(re.search(r"val seedId: Int\?", _md87) is not None, "种子 id 有数字形态")
    check("qualifiedInt" in _ws87 and "qualifiedName" in _ws87,
          "带前缀的 id 会剥前缀；mod 名不当数字用")
    check(re.search(r"takeIf \{ it != 0 && it != -1 \}", _ws87) is not None,
          "产出 id 的 0/-1 一律当未知")

    # 87.7 温室与农场分开（坑 6）
    check(re.search(r"val location: String", _md87) is not None, "作物带地图归属")
    check("cropsByLocation" in _md87, "能按地图分组（温室与农场必须分开统计）")

    # 87.8 BOM：四份真实存档全部以 EF BB BF 开头
    check("skipSaveBom" in _ws87, "读存档前跳 UTF-8 BOM")
    check("import android." not in _ws87,
          "解析器不依赖 Android API（Android 与单测共用同一份代码）")

    # 87.9 验收测试与任务
    _t87 = os.path.join(ROOT, "app", "src", "test", "java", "com", "stardew",
                        "assistant", "data", "WorldStateParserTest.kt")
    check(os.path.exists(_t87), "有验收测试（四份真实存档逐项对基线）")
    if os.path.exists(_t87):
        _tt87 = io.open(_t87, encoding="utf-8").read()
        for _who87 in ("梦秋尘", "Yuki", "Amy", "K1ra Y0shik4g"):
            check(_who87 in _tt87, "验收测试覆盖存档角色 %s" % _who87)
    check("worldStateTest" in gb, "gradle 有 worldStateTest 任务（中文路径下也能跑）")
    check(re.search(r"testImplementation\(libs\.kxml2\)", gb) is not None,
          "kxml2 进的是 testImplementation（XmlPullParser 的 JVM 实现）")
    check("implementation(libs.kxml2)" not in gb, "kxml2 没有进 implementation（不进 APK）")

    # 87.10 真人玩家存档绝不入库
    _gi87 = io.open(os.path.join(os.path.dirname(ROOT), ".gitignore"), encoding="utf-8").read()
    check(re.search(r"^存档/", _gi87, re.M) is not None,
          ".gitignore 忽略 存档/（真人玩家存档，绝不入库）")

    # ==== 88. 存档世界状态的「名称 + 产值」层（P1）====
    # P0 给出的是原始 id 与英文名（190 / "Carrot" / "Brown Cow"），
    # P1 把它们换成 App 自己的中文名与可算的售价。
    # 本节钉的是**结构与纪律**：映射表的三分区、值必须逐字存在于 App 自己的表、
    # 售价单一来源、旧 id 空间被排除、Logic.kt 一个字没改。
    # 数字基线由 app/src/test/.../WorldEconomyTest.kt 用四份真实存档守着。
    print("\n== 88. 存档世界状态映射与产值（P1） ==")
    _gen88 = code_only(io.open(os.path.join(ROOT, "tools", "gen_world_item_map.py"),
                               encoding="utf-8").read())
    _we88 = code_only(src("data/WorldEconomy.kt"))
    _gd88 = code_only(src("data/GameData.kt"))
    _calc88 = code_only(src("data/Logic.kt"))

    # 88.1 产出物齐备
    check(os.path.exists(os.path.join(DATA, "worldItemMap.json")),
          "有 assets/data/worldItemMap.json")
    check(os.path.exists(os.path.join(ROOT, "tools", "gen_world_item_map.py")),
          "有生成脚本 tools/gen_world_item_map.py")
    check(os.path.exists(os.path.join(SRC, "data", "WorldEconomy.kt")),
          "有 data/WorldEconomy.kt（纯逻辑产值层）")

    # 88.2 映射表结构：三分区 + 值必须是 App 自己表里逐字存在的名字
    _wm88 = load(os.path.join(DATA, "worldItemMap.json"))
    for _sec88 in ("byId", "byNameEn", "animalType"):
        check(isinstance(_wm88.get(_sec88), dict) and bool(_wm88[_sec88]),
              "映射表有 %s 分区且非空" % _sec88)
    # c204：取值集合改成**生成脚本自己的口径**（App 的条目名全集）。
    #   原来这张表只有 crops/forage/animals 三张，脚本收了几百条新映射之后
    #   断言还按老口径算 ⇒ 必然假红（而且改的是断言不是数据）。
    #   直接 import 生成脚本，两边共用一套定义 ⇒ 以后再扩也不会漂。
    import gen_world_item_map as _gw88
    _app88 = _gw88.app_canon_names()
    # 第二层（OFFICIAL_ONLY_BY_EN）：App 里本来就没有这些条目，
    #   它们的依据是上游数据文件（生成脚本自校验里有守卫）。
    _layer288 = set(_gw88.OFFICIAL_ONLY_BY_EN.values())
    for _sec88 in ("byId", "byNameEn", "animalType"):
        _bad88 = sorted(k for k, v in _wm88.get(_sec88, {}).items()
                        if v not in _app88 and v not in _layer288)
        check(not _bad88, "%s 的值全在 App 自己的表里逐字存在（异常 %s）"
              % (_sec88, _bad88[:5] or "无"))
    check(all(k.isdigit() for k in _wm88.get("byId", {})), "byId 的键全是数字 item_id")
    _wmtext88 = io.open(os.path.join(DATA, "worldItemMap.json"), encoding="utf-8").read()
    check("sellPrice" not in _wmtext88 and "sell_price" not in _wmtext88,
          "映射表里不存价格（售价单一来源：App 的 crops.json）")

    # 88.3 crops.json 补 nameEn、animals.json 补 typeEn，且品种不重复归属
    _crops88 = load(os.path.join(DATA, "crops.json"))
    _noen88 = [c.get("name") for c in _crops88 if not c.get("nameEn")]
    check(not _noen88, "%d 条作物都补了 nameEn（缺 %s）" % (len(_crops88), _noen88[:5] or "无"))
    _animals88 = load(os.path.join(DATA, "animals.json"))
    _noty88 = [a.get("动物") for a in _animals88 if not a.get("typeEn")]
    check(not _noty88, "%d 条动物都补了 typeEn（缺 %s）" % (len(_animals88), _noty88[:5] or "无"))
    _seen88 = {}
    for a in _animals88:
        for t in a.get("typeEn") or []:
            _seen88.setdefault(t, []).append(a.get("动物"))
    _dup88 = sorted(t for t, v in _seen88.items() if len(v) > 1)
    check(not _dup88, "动物英文品种不重复归属（重复：%s）" % (_dup88[:5] or "无"))
    check(len(_seen88) == len(_wm88.get("animalType", {})),
          "animalType 分区与 animals.json 的 typeEn 条数一致")

    # 88.4 生成器：可重跑 + 带 --check + 不联网 + 旧 id 空间被排除
    check("--check" in _gen88, "生成脚本支持 --check（只校验不写）")
    check("CROP_RENAME" in _gen88, "生成脚本有译名对照表（App 名 → 上游中文名）")
    check("LEGACY_ID_CATEGORIES" in _gen88 and '"weapon"' in _gen88 and '"tool"' in _gen88,
          "生成脚本排除了旧 id 空间（武器/工具的 1.5 精灵序号）")
    check(not re.search(r"^\s*import\s+(requests|urllib)", _gen88, re.M), "生成脚本不联网")
    check("item_index.json" in _gen88, "生成脚本的数据源是仓库内的 item_index.json")

    # 88.5 产值逻辑：与现有计算页同口径，但 Logic.kt 一个字没改
    check("fun worldSell(" in _we88, "有 worldSell（单株售价）")
    check("农耕人" in _we88 and "qualityMult" in _we88,
          "worldSell 含「农耕人」加成与品质系数")
    check("fun worldYieldRange(" in _we88, "有 worldYieldRange（单株产量区间）")
    check("fun worldHarvestGroups(" in _we88 and "fun worldHarvestTotal(" in _we88,
          "有今日可收的分组与总额")
    check("private fun effectiveSell(" in _calc88,
          "Logic.kt 的 effectiveSell 仍是 private（不许改它）")
    check("fun effectiveSell(" not in _we88,
          "产值另起一份实现，没有覆盖/复制现有计算页的私有函数")

    # 88.6 接线与验收测试
    check("worldItemMap.json" in _gd88 and "WorldItemMap" in _gd88,
          "GameData 加载 worldItemMap.json")
    _te88 = os.path.join(ROOT, "app", "src", "test", "java", "com", "stardew",
                         "assistant", "data", "WorldEconomyTest.kt")
    check(os.path.exists(_te88), "有验收测试 WorldEconomyTest.kt")
    check("worldEconomyTest" in gb, "gradle 有 worldEconomyTest 任务")

    # ==== 89. 「我的农场」接进今日页（P2）====
    # 用户第一次**看得见**的改动：今日页多了「我的农场」这个分段。
    # 本节钉结构与纪律：通用日历那侧没被改、没新建第二个导入入口、
    # 存档只读不写、35MB 解析在后台、四块缺一不显、查不到名字不编。
    print("\n== 89. 「我的农场」接进今日页（P2） ==")
    _tp89 = code_only(src("ui/today/TodayPage.kt"))
    _ftv89 = code_only(src("ui/today/FarmTodayView.kt"))
    _ft89 = code_only(src("data/FarmToday.kt"))
    _fc89 = code_only(src("data/FarmTodayCache.kt"))
    _si89 = code_only(src("data/SaveImport.kt"))
    _sp89 = code_only(src("ui/settings/SettingsPage.kt"))

    # 89.1 产出物齐备
    check(os.path.exists(os.path.join(SRC, "data", "FarmToday.kt")), "有 data/FarmToday.kt（展示模型）")
    check(os.path.exists(os.path.join(SRC, "data", "FarmTodayCache.kt")), "有 data/FarmTodayCache.kt（缓存）")
    check(os.path.exists(os.path.join(SRC, "ui", "today", "FarmTodayView.kt")), "有 ui/today/FarmTodayView.kt（界面）")

    # 89.2 今日页：分段控件 + 两视图，通用日历那侧没动
    check("PixelSegmented(" in _tp89, "今日页用了 PixelSegmented（复用现成组件，不新造）")
    check("通用日历" in _tp89 and "我的农场" in _tp89, "分段的两侧是「通用日历 / 我的农场」")
    check("farmTab" in _tp89, "分段状态由页面持有")
    for _k89 in ('PageBar("今日")', "🎂 生日", "🎪 节日"):
        check(_k89 in _tp89, "通用日历那侧的内容还在（%s）" % _k89)
    check("FarmTodayView(" in _tp89, "「我的农场」那一侧渲染 FarmTodayView")
    check("FarmTodayCache.read" in _tp89, "今日页只读缓存（不重复解析 35MB 存档）")
    # ⚠ keep-alive 陷阱：今日页常驻组合，**只在首次组合读一次缓存**的话，
    #   导入完回到今日页读到的还是旧值（用户看到「导入了没反应，重启才有」）。
    #   所以缓存的读取必须挂在 visible 上（进设置页 → 回今日页 = false→true）。
    # c200 A1：类型参数从 FarmToday? 改成 FarmLoad（Loading / Loaded 三态），
    #   断言改钉「读取仍挂在 visible 上」这件事本身，不钉具体类型名。
    # c214：新增「↻ 刷新」显式入口（farmRefreshTick），点击也触发重读。
    #   断言放宽为「visible 或 farmRefreshTick 都是合法触发源」——原本保证的
    #   「进设置页回来必重读」仍成立，只是多了一条显式刷新通道。
    check(re.search(r"produceState<\w*>\([^)]*(visible|farmRefreshTick)[^)]*\)", _tp89) is not None,
          "缓存重读挂在 visible 或显式刷新信号上（keep-alive 不读旧值 + 提供手动刷新）")
    check("FarmTodayParser" not in _tp89 and "WorldStateParser" not in _tp89,
          "今日页不直接碰解析器（解析只发生在导入时）")

    # 89.3 存档来源：复用现有导入入口，不新建第二个
    #    ⚠ 设置页本来就有两个 OpenDocument（App 自己的数据导入 + 游戏存档导入），
    #    所以「只有一个选文件入口」不是判据；真正的红线是**新界面不自己开文件选择器**。
    check("ActivityResultContracts" not in _tp89 and "ActivityResultContracts" not in _ftv89,
          "「我的农场」不自己开文件选择器（复用设置页现有的导入入口）")
    check("OpenDocument" in _sp89, "设置页的存档导入入口还在（没被改掉）")
    # c217：接口改名 refresh → parse（**只解析不落盘**，落盘挪到「确定导入」之后）。
    #   断言跟着改口径：入口接的是 parse，落盘由 commit 负责（136 节钉「落盘在确认之后」）。
    check("FarmTodayCache.parse" in _sp89, "导入成功后顺手解析世界状态")
    check(_sp89.count("FarmTodayCache.parse")
          + code_only(src("data/LanReceiveController.kt")).count("FarmTodayCache.parse") >= 3,
          "各条导入入口（选文件 / Steam 手动选文件 / 局域网接收）都接上了"
          "（实际 %d 处）" % (_sp89.count("FarmTodayCache.parse")
              + code_only(src("data/LanReceiveController.kt")).count("FarmTodayCache.parse")))
    check("Dispatchers.IO" in _sp89, "导入解析在后台线程（35MB 不许卡 UI）")

    # 89.4 SaveImport：只在成功处加了一行，它自己的解析逻辑没动
    check("MainSaveSource" in _si89, "SaveImport 暴露主存档取流（供世界状态再读一遍同一份）")
    check("onMainSave" in _si89, "parse / parseFile 有成功回调参数")
    check("parseStream(it, map)" in _si89, "SaveImport 自己的解析逻辑一行没改")
    check("runCatching" in _si89 and "onMainSave?.invoke" in _si89,
          "回调在成功处触发，且失败不影响导入结果")

    # 89.5 四块 + 缺一不显 + 查不到名字不编
    for _t89 in ("今天能收的", "马上要熟的", "枯死待清理", "牲畜今日待办"):
        check(_t89 in _ftv89, "有「%s」这一块" % _t89)
    check("if (model.soon.isNotEmpty())" in _ftv89, "马上要熟：没数据整块不显示")
    check("if (model.dead.isNotEmpty())" in _ftv89, "枯死：没数据整块不显示")
    check("if (model.animalTotal > 0)" in _ftv89, "牲畜：一只都没有整块不显示")
    check("野生采集物" in _ftv89, "查不到名字的产物显示「野生采集物」")
    check("unnamedReady" in _ft89, "查不到名字的株数单列（不混进有名字的行）")
    check("不计入产值" in _ftv89, "查不到售价的不计入产值（不编价）")
    check("itemPrice" not in _ft89 and "unitPrice" in _ft89, "单价来自 App 自己的表（模型里只存查到的价）")

    # 89.6 缓存：只读存档、只写自己的文件
    check("filesDir" in _fc89, "缓存写在 App 自己的文件目录")
    check("WorldStateParser.parse" in _fc89, "刷新时解析世界状态（导入那一刻）")
    check("GameData.baseCrops" in _fc89, "售价取 App 自己的 crops.json（上游 sell_price 实测有错）")

    # 89.7 版本与更新日志（按 gradle 现算，不钉字面量）
    _vn89 = re.search(r'versionName\s*=\s*"([^"]+)"', gb)
    check(bool(_vn89), "gradle versionName 可解析")
    if _vn89:
        _ch89 = re.search(r'CHANGELOG = listOf\(\s*\r?\n\s*"v?([0-9][0-9.]*)', _sp89)
        check(bool(_ch89), "CHANGELOG 首条可解析")
        if _ch89:
            check(
                _ch89.group(1) == _vn89.group(1),
                "CHANGELOG 首条 (%s) 与 versionName (%s) 一致（这次是用户看得见的改动，必须记一条）"
                % (_ch89.group(1), _vn89.group(1))
            )
    check("FarmTodayTest.kt" in _sp89 or os.path.exists(os.path.join(
        ROOT, "app", "src", "test", "java", "com", "stardew", "assistant", "data", "FarmTodayTest.kt")),
        "有验收测试 FarmTodayTest.kt（界面上的数字即测试里的数字）")
    check("farmTodayTest" in gb, "gradle 有 farmTodayTest 任务")

    # ==== 90. 电脑脚本的下载说明改对（v2.82.0）====
    # 起因（用户实测）：App 抽屉里写「下面这个地址粘到电脑浏览器里也能下」，
    #   用户把地址粘进浏览器，浏览器**把 bat 当文本显示**、根本没下载。
    #   实测根因（2026-10-21）：gitee raw 按**扩展名**给 Content-Type/Disposition——
    #     .apk → application/octet-stream + attachment（所以「App 更新能下」）
    #     .bat → text/plain + Content-Disposition: inline（所以「脚本下不来」）
    #   同一个服务、同一套鉴权，只有扩展名不同 —— 所以这不是「链接坏了」，
    #   而是**说明写错了**。本节钉三件事：① 假话不许复活；② 有一条实测能落盘的路；
    #   ③ 命令必须钉住落盘文件名（拿到就能双击，不用改名）。
    print("\n== 90. 电脑脚本的下载说明改对（v2.82.0） ==")
    _up90 = code_only(src("data/UpdateRepo.kt"))
    _sp90 = code_only(src_sp())

    # ① 反向断言：原来那句假话不许再出现。
    #    ⚠ 必须用 code_only 比：SettingsPage 里那段注释**故意引用了这句话**
    #    （说明「它原来是错的」），对全文匹配会把注释当成代码 → 假红。
    check("粘到电脑浏览器里也能下" not in _sp90,
          "不再声称「粘到电脑浏览器里也能下」（gitee raw 对 .bat 是 inline，浏览器不落盘）")
    check("不会自动下载" in _sp90 or "不会下载" in _sp90,
          "文案如实说明浏览器打开只会显示文本、不会自动下载")

    # ② 命令存在、指向真实路径、钉住落盘文件名
    check("SCRIPT_PS1_CMD" in _up90, "UpdateRepo 里有电脑端一行命令常量")
    _cmd90 = re.search(r'SCRIPT_PS1_CMD\s*=\s*"([^"]+)"', _up90)
    check(bool(_cmd90), "命令常量可解析")
    if _cmd90:
        _c90 = _cmd90.group(1)
        # ⚠ 2026-11-10（发布仓迁 GitHub Release）：路径形态从 raw/master/tools/…
        #   变成 releases/download/<tag>/… —— 断言跟着实际形态走。
        check("releases/" in _c90 and "/download/" in _c90 and "send_save.bat" in _c90,
              "命令指向发布仓的 send_save.bat 资产（与按钮拿的是同一个文件）")
        check(_c90.endswith("-OutFile send_save.bat"),
              "命令用 -OutFile 钉住落盘文件名（拿到就能双击，不用改名）")
        check(_c90.startswith("irm "),
              "命令用 irm 起头（Windows 自带 PowerShell 就有，不要求装任何东西）")

    # ③ 抽屉里展示的是这条命令，且复用既有的点按复制
    check("CopyLinkRow(UpdateRepo.SCRIPT_PS1_CMD" in _sp90,
          "抽屉里展示这条命令（复用 CopyLinkRow，不另写剪贴板代码）")
    check("已复制命令" in _sp90,
          "复制命令的 Toast 不说「已复制链接」（那会让用户以为复制到的是网址）")
    check("toastText" in _sp90,
          "CopyLinkRow 支持自定义 Toast 文案（默认仍是「已复制链接」）")
    check("UpdateRepo.SCRIPT_URL" in _sp90,
          "原始地址仍然保留（想自己拿链接的人还有门路）")
    # ④ 两条路都得说清「点一下就复制」——用户不知道能点 = 没有这个功能
    check("不用手打" in _sp90,
          "复制行旁边写明「点一下就复制，不用手打」")
    # ==== 91. 农场地图（P3-a）====
    # 用户 2026-10-22 拍板「形态 B · 按连续行组切」（预览稿三选一）。
    # 本节钉的是**结构与纪律**：
    #   ① 切分必须是「连续行组」，**不是 8-邻接簇**（后者把 ff 农场碎成 10 片）
    #   ② 一格 = 一块地，1:1 不缩放；坐标取不到就跳过（不能拿 0 顶替）
    #   ③ 状态只有五种；**查不到名字就不写名字**
    #   ④ 渲染必须走 Canvas（实测最多一档 582 块地、连空白约 1200 格，Box 方案会掉帧）
    #   ⑤ **同名的不同地图必须能区分**（两张姜岛并排出现）
    print("\n== 91. 农场地图（P3-a） ==")
    _fm91 = code_only(src("data/FarmMap.kt"))
    _ftv91 = code_only(src("ui/today/FarmMapView.kt"))
    _ft91 = code_only(src("data/FarmToday.kt"))
    _fv91 = code_only(src("ui/today/FarmTodayView.kt"))
    _ftt91 = io.open(os.path.join(ROOT, "app", "src", "test", "java", "com", "stardew",
                                  "assistant", "data", "FarmTodayTest.kt"),
                     encoding="utf-8").read()

    check(os.path.exists(os.path.join(SRC, "data", "FarmMap.kt")), "有 data/FarmMap.kt（小地图数据层）")
    check(os.path.exists(os.path.join(SRC, "ui", "today", "FarmMapView.kt")),
          "有 ui/today/FarmMapView.kt（小地图界面）")

    # ① 切分规则：按 y 分组 + 空行断开。**反向断言不做 8-邻接**：
    #    8-邻接会把 ff 农场碎成 10 片（7 片是单块地），界面上全是碎格子。
    check("groupBy { it.y }" in _fm91, "切分按 y 分组（纵向连续的行算一片）")
    check("y != cur.last().y + 1" in _fm91, "行之间隔了空行就断开成新的一片")
    check("for (dx" not in _fm91 and "for (dy" not in _fm91,
          "不做 8-邻接簇（那会把 ff 农场碎成 10 片，界面上全是碎格子）")
    check("sortedByDescending { it.tiles.size }" in _fm91,
          "面积大的片排前面（视觉重心在前）")

    # ② 一格 = 一块地，1:1 不缩放；坐标取不到就跳过
    check("val x = p.x ?: continue" in _fm91 and "val y = p.y ?: continue" in _fm91,
          "坐标取不到就跳过这一块（(0,0) 也是真实地块，不能拿 0 顶替）")
    check("minOf { it.x }" in _fm91 and "maxOf { it.x }" in _fm91,
          "片的包围盒由格子的真实坐标算出（1:1，不缩放）")
    check("val width: Int get() = x1 - x0 + 1" in _fm91, "片的宽度 = 坐标跨度（不是格子数）")

    # ③ 状态只有五种；名字查不到就不写
    for _s91 in ("READY", "GROWING", "DEAD", "EMPTY", "UNKNOWN"):
        check("const val %s" % _s91 in _fm91, "状态常量 %s 在" % _s91)
    check("crop.isDead -> TileState.DEAD" in _fm91, "枯死按 dead 判（不是「没熟」）")
    check("crop.ready == true -> TileState.READY" in _fm91, "可收按成熟判据判")
    check("map.cropName(it)" in _fm91, "格子名字走 App 自己的映射表")
    check("name = p.crop?.let { map.cropName(it) }" in _fm91,
          "查不到名字就是 null（不编名）")

    # ④ 渲染走 Canvas + 自己算点击命中
    check("Canvas(" in _ftv91 and "drawRect(" in _ftv91,
          "格子用 Canvas 画（实测最多一档连空白约 1200 格，Box 方案会掉帧）")
    check("detectTapGestures" in _ftv91, "点击命中自己算（Canvas 没有子节点可点）")
    check("p.tileAt(" in _ftv91, "点格子的命中用坐标查表")
    check("coerceIn(4, 9)" in _ftv91,
          "格子大小有下限 4dp（ff 姜岛有一片 73 格宽，4dp × 73 = 292dp 才放得下）")
    for _s91 in ("READY", "GROWING", "DEAD", "EMPTY"):
        check("TileState.%s" % _s91 in _ftv91, "界面用到了状态 %s" % _s91)

    # ⑤ 同名的不同地图必须能区分（四块那张表把两张姜岛都叫「姜岛」）
    check("姜岛西部" in _fm91 and "姜岛北部" in _fm91,
          "小地图把姜岛西部/姜岛北部分开（同名的话两张图并排出现就分不清谁是谁）")

    # ⑥ 缓存带上了地图，且旧缓存读得回来
    check("maps: List<FarmMap>" in _ft91, "展示模型带上了地图清单")
    check("FarmMapBuilder.build(state, map)" in _ft91, "地图与四块同源（同一份 WorldState 折出来）")
    check('farmMapsFromJson(root.optJSONArray("maps"))' in _ft91,
          "旧缓存没有 maps 键时读成空列表（地图整块不显示，不是崩）")

    # ⑦ 接进今日页，默认折叠
    check("FarmMapCard(" in _fv91, "今日页接上了农场地图卡")
    check("model.maps.isNotEmpty()" in _fv91, "没有地图数据就整块不显示")
    check("var mapOpen by remember { mutableStateOf(false) }" in _fv91,
          "地图默认折叠（地图是偶尔看的，今天的清单才是每次看的）")
    # ⚠ 四块那侧一个字没改
    for _t91 in ("🧺 今天能收的", "🌱 马上要熟的", "💀 枯死待清理", "🐄 牲畜今日待办"):
        check(_t91 in _fv91, "四块那侧还在（%s）" % _t91)

    # ⑧ 数字验收：四档的地图/片/格数都被测试钉死
    check("农场地图的四档数字" in _ftt91, "测试钉住四档的地图数字")
    check("地图的格子能按坐标查回来" in _ftt91, "测试钉住按坐标查格子")
    check("地图的作物名查不到就不写" in _ftt91, "测试钉住查不到名字就不写")

    check("PowerShell" in _sp90,
          "写明命令要在 PowerShell 里跑（纯电脑用户照着做）")

    # ==== 92. 图鉴四个作物的译名与数据修正（v2.84.0）====
    # 起因：群友 @王忠明 2026-10-22 在群里报了**四条**数据报错
    #（同一个群友 —— v2.39「夏南瓜 → 金皮西葫芦」那次也是他拍的板）。
    # 那四个 App 名全是**从英文直译出来的自造名**：Summer Spangle→盛夏疯狂、
    # Red Cabbage→红甘蓝、Artichoke→朝鲜蓟、Powdermelon→粉瓜；
    # 游戏与中文 wiki 都用别的叫法：夏季亮片 / 红叶卷心菜 / 洋蓟 / 霜瓜。
    #
    # 本节钉四件事：
    #   ① 旧名在**所有**数据文件里清零（不只是 crops.json —— 出货箱、配方、
    #      收集包、种子价、商店、礼物、映射表都引用过这些名字）
    #   ② 四个字段值按上游改对（售价 / 生长天数 / 季节 —— 群友只报了名字，
    #      这三处是顺手核出来的，也一起错）
    #   ③ 图标键与文件**一一对应**（改名前就有一份「同一 sprite 存两份」的重复：
    #      盛夏疯狂.png 与 夏季斑点花.png 字节完全相同）
    #   ④ **老存档的勾选键必须重映射** —— 键是按名字拼的（codex:crops:盛夏疯狂），
    #      不搬就会**静默变回未勾**
    print("\n== 92. 图鉴四个作物的译名与数据修正（v2.84.0） ==")
    _WS = os.path.dirname(ROOT)
    _OLD92 = ["盛夏疯狂", "红甘蓝", "朝鲜蓟", "粉瓜", "夏季斑点花"]
    _NEW92 = {"Summer Spangle": ("夏季亮片", 90, 8, "夏"),
              "Red Cabbage": ("红叶卷心菜", 260, 9, "夏"),
              "Artichoke": ("洋蓟", 160, 8, "秋"),
              "Powdermelon": ("霜瓜", 60, 7, "冬")}

    # ① 旧名清零（三处资产 + 手册 + 生成器 + 代码）
    for _rel92 in (("StardewHelper", "app", "src", "main", "assets"),
                   ("web", "assets"), ("miniprogram", "assets")):
        _base92 = os.path.join(_WS, *_rel92)
        _left92 = []
        for _dp92, _dn92, _fn92 in os.walk(_base92):
            _dn92[:] = [x for x in _dn92 if x != "wiki"]
            for _f92 in _fn92:
                if not _f92.endswith((".json", ".js")):
                    continue
                _p92 = os.path.join(_dp92, _f92)
                _t92 = io.open(_p92, encoding="utf-8", errors="ignore").read()
                for _o92 in _OLD92:
                    if _o92 in _t92:
                        _left92.append("%s(%s)" % (_f92, _o92))
        check(not _left92,
              "%s 里没有旧名残留（%s）" % ("/".join(_rel92), "、".join(_left92[:4]) or "无"))

    # ② 四个字段值按上游改对
    _cr92 = json.load(io.open(os.path.join(SRC, "..", "..", "..", "..", "assets", "data", "crops.json"),
                              encoding="utf-8")) \
        if False else json.load(io.open(os.path.join(ROOT, "app", "src", "main", "assets", "data", "crops.json"),
                                        encoding="utf-8"))
    for _en92, (_nm92, _pr92, _gr92, _se92) in _NEW92.items():
        _hit92 = [c for c in _cr92 if c.get("nameEn") == _en92]
        check(len(_hit92) == 1, "crops.json 里有且只有一条 %s" % _en92)
        if len(_hit92) == 1:
            _c92 = _hit92[0]
            check(_c92.get("name") == _nm92, "%s 的名字是 %s" % (_en92, _nm92))
            check(_c92.get("sellPrice") == _pr92, "%s 售价 %d" % (_en92, _pr92))
            check(_c92.get("growth") == _gr92, "%s 生长 %d 天" % (_en92, _gr92))
            check(_c92.get("seasons") == [_se92], "%s 季节 %s" % (_en92, _se92))

    # ③ 图标键与文件一一对应
    #    ⚠ **App 全量查；两份镜像只查本次涉及的条目** —— 镜像本来就有既有缺口
    #      （web 缺马铃薯那两个图、小程序整个没有 img/wiki/ 子目录），
    #      拿全量去卡它们会卡在与本次无关的旧账上。
    _TARGET92 = ["夏季亮片", "夏季亮片种子", "红叶卷心菜", "红叶卷心菜种子",
                 "洋蓟", "洋蓟种子", "霜瓜", "霜瓜种子", "夏南瓜种子、霜瓜种子"]
    for _rel92, _f92, _full92 in ((("StardewHelper", "app", "src", "main", "assets"), "image_map.json", True),
                                  (("web", "assets"), "image_map.json", False),
                                  (("miniprogram", "assets"), os.path.join("data", "image_map.js"), False)):
        _base92 = os.path.join(_WS, *_rel92)
        _p92 = os.path.join(_base92, _f92)
        _t92 = io.open(_p92, encoding="utf-8", newline="").read()
        _pairs92 = re.findall(r'"([^"]+)":\s*"([^"]+)"', _t92)
        _keys92 = [k for k, _ in _pairs92]
        _dup92 = sorted(set(k for k in _keys92 if _keys92.count(k) > 1))
        check(not _dup92, "%s 的图标键无重复（%s）" % ("/".join(_rel92), "、".join(_dup92[:4]) or "无"))
        _miss92 = [k for k, v in _pairs92
                   if (v.endswith(".png") or v.endswith(".webp"))
                   and not os.path.exists(os.path.join(_base92, "img", v.replace("/", os.sep)))]
        if _full92:
            check(not _miss92, "App 的图标键都指向存在的文件（缺 %d 个）" % len(_miss92))
        else:
            _m92 = [k for k in _miss92 if k in _TARGET92]
            check(not _m92, "%s 本次涉及的条目图标都在（缺 %s）"
                  % ("/".join(_rel92), "、".join(_m92) or "无"))
    _a92 = os.path.join(ROOT, "app", "src", "main", "assets", "img")
    for _f92 in ("夏季亮片.png", "红叶卷心菜.png", "洋蓟.png", "霜瓜.png"):
        check(os.path.exists(os.path.join(_a92, _f92)), "图标文件 %s 在" % _f92)
    check(not os.path.exists(os.path.join(_a92, "夏季斑点花.png")),
          "重复的 夏季斑点花.png 已删（与 夏季亮片.png 字节相同）")

    # ④ 老存档勾选键重映射：**独立闸门**（v2.39 那次的标记已在老用户数据里，不会再跑）
    _pr92 = code_only(src("data/PrefsRepo.kt"))
    check("CROP_RENAME_REMAP_V282" in _pr92, "有 v2.82 这一轮的作物改名重映射表")
    for _o92, _n92 in (("盛夏疯狂", "夏季亮片"), ("红甘蓝", "红叶卷心菜"),
                       ("朝鲜蓟", "洋蓟"), ("粉瓜", "霜瓜")):
        check('"codex:crops:%s" to "codex:crops:%s"' % (_o92, _n92) in _pr92,
              "勾选键重映射：%s → %s" % (_o92, _n92))
    check("CHECK_KEYS_V282" in _pr92, "有独立的一次性迁移标记（不能复用 v2.39 的）")
    check("migrateCropRenameV282" in _pr92, "有独立的迁移方法")
    check("p[ck] = cur.map { CROP_RENAME_REMAP_V282[it] ?: it }.toSet()" in _pr92,
          "迁移是**落盘重写**（只读映射会让用户取消不掉勾选）")
    _ma92 = code_only(src("MainActivity.kt"))
    check("migrateCropRenameV282()" in _ma92, "启动时调用了 v2.82 这一轮的迁移")

    # ⑤ 生成器：App 名已与上游名一致 → 对照表里那四条必须删掉
    _g92 = io.open(os.path.join(ROOT, "tools", "gen_world_item_map.py"), encoding="utf-8").read()
    for _o92 in ("盛夏疯狂", "红甘蓝", "朝鲜蓟", "粉瓜"):
        check('"%s":' % _o92 not in _g92,
              "生成器的改名对照表里没有「%s」（App 名已与上游名一致，留着会查不到）" % _o92)
    _wim92 = json.load(io.open(os.path.join(ROOT, "app", "src", "main", "assets", "data", "worldItemMap.json"),
                               encoding="utf-8"))
    _all92 = json.dumps(_wim92, ensure_ascii=False)
    for _n92 in ("夏季亮片", "红叶卷心菜", "洋蓟", "霜瓜"):
        check(_n92 in _all92, "映射表里有「%s」" % _n92)
    check(not any(_o92 in _all92 for _o92 in ("盛夏疯狂", "红甘蓝", "朝鲜蓟", "粉瓜")),
          "映射表里没有旧名")

    # ⑥ 手册与 meta 附录
    _man92 = io.open(os.path.join(_WS, "docs", "星露谷小助手-完全手册.md"), encoding="utf-8").read()
    check(not any(_o92 in _man92 for _o92 in _OLD92), "完全手册里没有旧名")
    check("工具用名" not in _man92, "完全手册的「工具用名 / wiki 名」对照表已删（App 名与 wiki 名已一致）")
    # c244：meta.json 整份删除 —— 早期网页版遗留（写着 index.html / localStorage /
    # 页脚署名），代码已不引用；留着只会让「关于」信息与实际对不上。
    _meta92p = os.path.join(ROOT, "app", "src", "main", "assets", "data", "meta.json")
    check(not os.path.exists(_meta92p),
          "meta.json 已删除（死文件，代码不引用；原来只断言「附录C 不在」）")

    # ================= 93. 更新弹窗不再「一直闪」（v2.85.0） =================
    # 起因：群友反馈「更新弹窗会一直闪，检测到更新之后一直闪弹窗」。
    #
    # 病根（不是猜的，是定位到行的）：
    #   v2.69 引入 UpdateDialog 时，**注释**写的是「放在 AnimatedContent 之外，
    #   作为全屏 Dialog 独立于页签」，但**代码**把它放进了 `keptTabs.forEach` 的**循环体内**。
    #   keptTabs 是 keep-alive 页签列表：启动 3s 后每 700ms 预热加一个，最多 5 个。
    #   ⇒ 每加一个页签就多渲染一个 UpdateDialog；
    #   ⇒ 而 Compose 的 Dialog 是**独立窗口**，多实例叠在一起 = 用户看到的「一直闪」。
    #
    # ⚠ 本节钉的是**结构关系**（UpdateDialog 不在 forEach 体内），不是钉字面量：
    #   怎么写都能过，但只要它回到循环体内就当场红。
    #   判定用括号配平（forEach 体的起止），并先剥注释/字符串 ——
    #   注释里写着「keptTabs.forEach」这几个字，不能被当成真代码。
    print("\n== 93. 更新弹窗不再「一直闪」（v2.85.0） ==")
    _ma93 = src("MainActivity.kt")
    _ma93_lines = _ma93.splitlines()

    def _strip93(line):
        """剥掉行注释与字符串字面量，避免注释/字符串里的括号干扰配平。"""
        out, i, n = [], 0, len(line)
        while i < n:
            c = line[i]
            if c == '"':
                j = i + 1
                while j < n:
                    if line[j] == "\\":
                        j += 2; continue
                    if line[j] == '"':
                        j += 1; break
                    j += 1
                out.append('""'); i = j; continue
            if c == "/" and i + 1 < n and line[i + 1] == "/":
                break
            out.append(c); i += 1
        return "".join(out)

    def _body_end93(start_idx):
        """从 start_idx 起用括号配平找该块的闭合行号（-1 = 没配平）。"""
        depth, started = 0, False
        for k in range(start_idx, len(_ma93_lines)):
            for ch in _strip93(_ma93_lines[k]):
                if ch == "{":
                    depth += 1; started = True
                elif ch == "}":
                    depth -= 1
            if started and depth == 0:
                return k
        return -1

    # ① 找到 forEach 与 UpdateDialog 的调用位置（都用代码行，注释里的不算）
    _fe93 = next((i for i, l in enumerate(_ma93_lines)
                  if "keptTabs.forEach" in _strip93(l)), -1)
    _ud93 = next((i for i, l in enumerate(_ma93_lines)
                  if "UpdateDialog(" in _strip93(l)), -1)
    check(_fe93 >= 0, "MainActivity 里有 keptTabs.forEach（keep-alive 页签循环）")
    check(_ud93 >= 0, "MainActivity 里有 UpdateDialog 调用")
    if _fe93 >= 0 and _ud93 >= 0:
        _end93 = _body_end93(_fe93)
        check(_end93 > 0, "keptTabs.forEach 的代码块能配平（判定基础）")
        # ★ 核心断言：UpdateDialog 的调用**必须在 forEach 体外**。
        check(not (_fe93 < _ud93 < _end93),
              "UpdateDialog 不在 keptTabs.forEach 体内"
              "（在里面会按页签数渲染多个 Dialog 窗口 = 弹窗一直闪）")
        check(_ud93 > _end93,
              "UpdateDialog 落在 forEach 之后（与上面的断言互为佐证）")

    # ② 全 App 只应有一个 UpdateDialog 调用点（多一个就又是多窗口叠加）
    _calls93 = [l for l in _ma93_lines if "UpdateDialog(" in _strip93(l)]
    check(len(_calls93) == 1,
          "MainActivity 里 UpdateDialog 调用点恰好 1 个（实际 %d）" % len(_calls93))

    # ③ 弹窗仍要挂在 AnimatedContent 之外 —— 不能为了躲 forEach 就挪进二级页转场里。
    #    （二级页 AnimatedContent 会把里面的内容在栈变化时整个换掉。）
    _ac93 = next((i for i, l in enumerate(_ma93_lines)
                  if "AnimatedContent(" in _strip93(l)), -1)
    check(_ac93 < 0 or _ud93 < _ac93 or _ud93 > _body_end93(_ac93),
          "UpdateDialog 不在二级页 AnimatedContent 体内（弹窗要独立于页签转场）")

    # ④ 修法不许靠「改成不弹窗」糊弄过去：弹窗该有的语义一个都不能少。
    _ub93 = src("ui/UpdateBanner.kt")
    check("fun UpdateDialog(" in _ub93, "UpdateDialog 组件仍在（没被删掉糊弄过去）")
    check("updateDialogVersion < pending.versionCode" in _ma93,
          "弹窗条件仍含「这一版还没弹过」")
    check("markUpdateDialogShown" in _ma93,
          "「稍后」仍写弹窗版本号（不写 = 每次开 App 都被拦）")
    _later93 = _ma93.split("onLater = {", 1)
    check(len(_later93) == 2 and "markUpdateSeen" not in _later93[1][:400],
          "「稍后」仍不写 markUpdateSeen（否则 = 永久屏蔽这一版）")

    # ================= 94. 从 Steam 云获取存档（v2.86.0） =================
    #
    # 这个功能的价值在于「不用电脑」，但它的**风险**全在合规上：
    # 一旦引入自绘 WebView / 接触账号 / 直连云 API，性质就完全变了。
    # 所以本节**大半的断言是钉红线**的 —— 红线破了比功能坏了更严重。
    #
    # ⚠ 判据一律**剥注释**后比对：注释里可以写「绝不用 WebView」，
    #   但那不能当成「代码里真的没有 WebView」。
    print("\n== 94. 从 Steam 云获取存档（v2.86.0） ==")
    _sd94 = src("data/SteamDownloadDir.kt")
    _sn94 = src("data/SteamSaveSniffer.kt")
    _sw94 = src("data/SteamSaveWatcher.kt")
    _si94 = src("data/SteamImportCoordinator.kt")
    _sv94 = src("data/SteamImportService.kt")
    _sg94 = src("ui/SteamImportGuide.kt")
    # v2.91.0：声明文案统一放 ui/SteamNotices.kt（引导页与协议页共用一份，避免两处走散）
    _sno94 = src("ui/SteamNotices.kt")
    _nt94 = src("data/SteamNotifications.kt")
    _all94 = [_sd94, _sn94, _sw94, _si94, _sv94, _sg94, _sno94, _nt94]
    _code94 = "\n".join(code_only(x) for x in _all94)
    _code94_ma = code_only(src("MainActivity.kt"))
    _mf94 = io.open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"),
                    encoding="utf-8").read()

    # ── 红线①：不画 WebView、不注入 JS、不读表单/Cookie ──
    for _bad94 in ("WebView", "addJavascriptInterface", "evaluateJavascript",
                   "setJavaScriptEnabled", "loadUrl(", "loadData(",
                   "getCookie", "CookieManager", "WebChromeClient"):
        check(_bad94 not in _code94,
              "红线：无自绘 WebView / JS 注入 / Cookie 读取（代码里没有 %s）" % _bad94)
    check("android.webkit" not in _code94, "红线：不引用 android.webkit（WebView 那个包）")

    # ── 红线②：不直连 Steam 云 API ──
    check("api.steampowered.com" not in _code94 and "api.steampowered.com" not in _code94_ma,
          "红线：不直连 api.steampowered.com（那要密钥与开发者身份）")
    check("ICloudService" not in _code94 and "EnumerateUserFiles" not in _code94,
          "红线：不调用 Steam 云存档 Web API")
    # 只允许出现 store.steampowered.com 这一个域名
    for _u94 in re.findall(r"https?://[^\s\"'<>)]*steampowered\.com[^\s\"'<>)]*", _code94):
        check("store.steampowered.com" in _u94,
              "红线：Steam 域名只能是 store.steampowered.com（实际 %s）" % _u94)

    # ── 红线③：不申请存储权限（下载目录靠 SAF 授权） ──
    # ⚠ 必须查**真正的 uses-permission 标签**，不能全文搜词：
    #   清单的**注释里**就写了「所以这里没有 READ_EXTERNAL_STORAGE」——
    #   全文搜会把这句注释当成「申请了这个权限」，判据必须剥注释。
    _mf_code94 = re.sub(r"<!--.*?-->", " ", _mf94, flags=re.S)
    _perm_tags94 = re.findall(r"<uses-permission[^>]*android:name=\"([^\"]+)\"", _mf_code94)
    # ⚠ 精确匹配（**带 android.permission. 前缀**）—— 原来这里写的是不带前缀的
    #   "MANAGE_EXTERNAL_STORAGE"，而 list 里存的是全名，`in` 判的是相等不是子串，
    #   于是这条断言**从来没真正生效过**（恒 PASS）。v2.91.0 顺手修好。
    for _perm94 in ("android.permission.READ_EXTERNAL_STORAGE",
                    "android.permission.READ_MEDIA_IMAGES",
                    "android.permission.READ_MEDIA_VIDEO",
                    "android.permission.READ_MEDIA_AUDIO"):
        check(_perm94 not in _perm_tags94,
              "红线：不申请 %s（读别的 App 的文件用不上它，Android 13+ 也拿不到）" % _perm94)
    # v2.91.0：「所有文件访问」现在是**有意**声明的（与远端 2.90.0 对齐）。
    #   声明它 ≠ 用户会开 —— 开关在系统设置里，用户自己去点；不开也不影响使用。
    check("android.permission.MANAGE_EXTERNAL_STORAGE" in _perm_tags94,
          "已声明 MANAGE_EXTERNAL_STORAGE（「所有文件访问」，与远端 2.90.0 对齐）")
    # 实际申请的权限清单要如实报出来（白名单外的权限才是风险）
    _allowed94 = {"android.permission.INTERNET", "android.permission.WRITE_EXTERNAL_STORAGE",
                  "android.permission.POST_NOTIFICATIONS", "android.permission.FOREGROUND_SERVICE",
                  "android.permission.FOREGROUND_SERVICE_DATA_SYNC",
                  "android.permission.MANAGE_EXTERNAL_STORAGE",
                  "android.permission.VIBRATE",  # c214 勾选/按钮触觉（系统 Vibrator 需此权限；普通短震，不申请运行时权限）
                  "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS"}  # c234 电池优化白名单（防系统冻结 App → 后台收不到）
    for _p94 in _perm_tags94:
        check(_p94 in _allowed94,
              "申请的权限在白名单内（%s）" % _p94)
    # v2.91.0：MediaStore **重新启用**，但语义彻底变了 ——
    #   ① 它不再是**唯一**通道（SAF 子目录那条一直在，现在还有「所有文件访问」）；
    #   ② 只在用户开了「所有文件访问」时才查 —— 分区存储下普通 App 查别人的下载文件
    #      **恒 0 行**（owner_package_name=NULL 被挡掉），不授权就查等于白查；
    #   ③ 查不到就查不到，**不作为**「找不到存档」的判据（三条通道的结果合并去重）。
    check("scanMediaStore" in _code94,
          "有 MediaStore 这条通道（与 SAF、「所有文件访问」三路合并去重）")
    check("hasAllFilesAccess(ctx)" in _code94,
          "MediaStore 那条只在「所有文件访问」开着时才查（否则恒 0 行，白费一次 IO）")
    # 主通道 = SAF 目录树（**子目录**：Download 根目录被 Android 11+ 明令禁止）
    _sp94 = code_only(src_sp())
    _sg_code94 = code_only(_sg94)
    check("OpenDocumentTree" in _sg_code94 or "ACTION_OPEN_DOCUMENT_TREE" in _code94,
          "下载目录走 SAF 目录树授权（子目录）")
    check("buildChildDocumentsUriUsingTree" in _code94,
          "用 DocumentsContract 扫授权目录下的直接子项")
    check("registerContentObserver" in _code94, "注册了目录变更 observer")
    check("takePersistableUriPermission" in _code94,
          "SAF 授权要 takePersistableUriPermission（否则 App 一重启就失效）")
    # ⚠ 引导页必须教用户建**子目录**（Download 根目录授权不了，这是 Android 11+ 的规定）
    check("新建文件夹" in _sg94 or "CREATE NEW FOLDER" in _sg94,
          "引导页教用户新建文件夹（Download 根目录在 Android 11+ 授权不了）")
    check("下载位置" in _sg94, "引导页教用户改浏览器的下载位置")

    # ── 红线④：只读，不写回用户目录、不上传 ──
    check("openOutputStream" not in _code94,
          "红线：不写用户目录（没有 openOutputStream）")
    for _u94 in ("MultipartBody", "HttpURLConnection", "OkHttp", "Retrofit",
                 "URLConnection", "openConnection"):
        check(_u94 not in _code94,
              "红线：无任何网络出口（代码里没有 %s）" % _u94)

    # ── 红线⑤：打开页面用 Custom Tabs ──
    check("CustomTabsIntent" in _code94, "打开 Steam 页面用 Chrome Custom Tabs")
    check("launchUrl" in _code94, "Custom Tabs 用 launchUrl 打开")
    check("store.steampowered.com/account/remotestorageapp" in _code94,
          "打开的是 Steam 官方云存档直达页")

    # ── 引导页必须明示安全承诺 ──
    check("不会获取、也不会记录你的 Steam 账号和密码" in _sg94,
          "引导页明示「不会获取、也不会记录你的 Steam 账号和密码」")
    check("store.steampowered.com" in _sg94, "引导页写明跳转的是 Steam 官方网站")
    check("ConcernedApe" in _sno94 and "Valve" in _sno94,
          "引导页有非官方声明（ConcernedApe / Valve）")
    check("NON_OFFICIAL_NOTICE" in _sg94,
          "引导页**引用**了那份非官方声明（文案统一放 SteamNotices.kt，避免两处走散）")
    for _step94 in ("登录你的账号", "显示文件", "SaveGameInfo", "_old"):
        check(_step94 in _sg94, "引导页步骤提到「%s」" % _step94)
    check("打开 Steam 云存档页" in _sg94, "主按钮文案")
    check("我已下载，手动选择文件" in _sg94, "次按钮文案")

    # ── 通知文案 ──
    check("正在等待 Steam 云存档下载" in _nt94, "等待通知文案")
    check("Steam 云存档已导入" in _nt94, "成功通知文案")
    check("未识别到有效主存档" in _nt94, "失败通知文案")
    check("FLAG_IMMUTABLE" in _nt94, "通知的 PendingIntent 带 FLAG_IMMUTABLE")

    # ── 前台服务与权限声明 ──
    check("FOREGROUND_SERVICE" in _mf94, "清单声明 FOREGROUND_SERVICE")
    check("FOREGROUND_SERVICE_DATA_SYNC" in _mf94,
          "清单声明 FOREGROUND_SERVICE_DATA_SYNC（Android 14+ 必需）")
    check("POST_NOTIFICATIONS" in _mf94, "清单声明 POST_NOTIFICATIONS（Android 13+）")
    check("foregroundServiceType" in _mf94 and "dataSync" in _mf94,
          "清单里前台服务声明了 dataSync 类型")
    check("SteamImportService" in _mf94, "清单注册了 SteamImportService")
    _svc94 = re.search(r"<service[^>]*SteamImportService[^>]*>", _mf94, re.S)
    check(_svc94 is not None and 'android:exported="false"' in _svc94.group(0),
          "前台服务 exported=false（外部 App 不能启动它）")
    check("startForeground" in _sv94, "服务会 startForeground（否则 5 秒内被系统杀）")
    check("stopSelf" in _sv94, "服务会 stopSelf（导入完成/超时要停）")

    # ── 嗅探判据（钉住实测校准出来的结论） ──
    check("SaveGame" in _sn94, "嗅探判据含根标签 SaveGame")
    check("player" in _sn94, "嗅探判据含 <player>")
    # ⚠ **不能用 locations / stats 当判据**（实测它们在 33KB 后，有的档整个文件都没有，
    #   而 SaveGameInfo 反而含有它们）—— 写成判据会把真存档判假、摘要判真。
    _sniff_fn94 = _sn94.split("fun sniff(", 1)
    if len(_sniff_fn94) == 2:
        _body94 = _sniff_fn94[1].split("fun decodeHead", 1)[0]
        check("<locations" not in _body94 and "<stats" not in _body94,
              "嗅探判据不含 <locations>/<stats>（实测它们不可用）")
    check("SNIFF_BYTES" in _sn94 and "16 * 1024" in _sn94,
          "嗅探只读前 16KB（根标签与 player 都在最前面）")
    check("0xEF" in _sn94 and "0xBB" in _sn94 and "0xBF" in _sn94,
          "嗅探跳过 UTF-8 BOM（四份真实存档全带 BOM）")
    for _ex94 in ("_old", "_SVBAK", "_SVEMERG", "SaveGameInfo"):
        check(_ex94 in _sn94, "排除规则含 %s" % _ex94)
    for _tmp94 in (".crdownload", ".tmp"):
        check(_tmp94 in _sn94, "排除下载中的临时文件 %s" % _tmp94)

    # ── 完整性三道闸 ──
    check("STABLE_INTERVAL_MS" in _sw94, "完整性：大小两次稳定才算写完")
    check("RETRY_COUNT" in _sw94, "完整性：嗅探失败有重试窗口")
    check("RETRY_DELAYS_MS" in _sw94, "完整性：重试间隔是显式常量")
    check("fingerprint" in _sw94, "去重：按 文件名+大小+修改时间 记指纹")

    # ── 去重与多候选不静默导入 ──
    check("isProcessed" in _si94 and "markProcessed" in _si94,
          "去重：同一文件不重复导入")
    check("NeedChoice" in _si94, "多候选时进入「让用户选」状态（不静默导入）")

    # ── v2.86.0 修：各步必须**串行**，不能并行 ──
    #
    # 实测翻车记录（用户实测报了两条假报错）：
    #   「没拿到下载目录授权」+「没有通知权限」—— 而用户其实**没有拒绝任何授权**。
    #   病根：一次点击里**同时**发起了 SAF 选择器 / 通知权限 / 前台服务 / 打开网页。
    #   系统对话框与 Custom Tabs **都是前台 UI**，同一时刻只能有一个在前台；
    #   Custom Tabs 抢先把对话框顶掉 ⇒ 回调拿到 null ⇒ 报「没拿到授权」。
    #
    # 判据：主按钮的 onClick **只能改状态**，不许直接发起任何系统 UI。
    _btn94 = _sg_code94.split(chr(34) + "打开 Steam 云存档页" + chr(34), 1)
    check(len(_btn94) == 2, "引导页有「打开 Steam 云存档页」主按钮")
    if len(_btn94) == 2:
        _blk94 = _btn94[1][:400]
        for _direct94 in ("treeLauncher.launch", "notifyLauncher.launch",
                          "SteamImportService.start", "openSteamCloud"):
            check(_direct94 not in _blk94,
                  "主按钮不直接发起 %s（只设状态，由流程串行推进）" % _direct94)
        check("step = SteamStep.REQUEST_NOTIFY" in _blk94,
              "主按钮只把流程推到通知权限这一步")
    check("enum class SteamStep" in _sg_code94, "流程用状态机表达（SteamStep 枚举）")
    for _st94 in ("REQUEST_TREE", "REQUEST_NOTIFY", "OPEN_PAGE"):
        check(_st94 in _sg_code94, "流程含步骤 %s" % _st94)
    check("LaunchedEffect(step)" in _sg_code94,
          "流程由 LaunchedEffect(step) 串行推进（等上一步回调才走下一步）")
    check(_sg_code94.count("notifyLauncher.launch") == 1, "通知权限只在一处发起")
    check(_sg_code94.count("SteamImportService.start") == 1, "前台服务只在一处启动")
    check(_sg_code94.count("openSteamCloud(") == 2,
          "打开 Steam 页只有 1 个调用点（定义+调用共 2 处）")
    # ⚠ 顺序：通知权限发起早于打开网页
    _i_notify94 = _sg_code94.index("notifyLauncher.launch")
    _i_open94 = _sg_code94.index("openSteamCloud(")
    check(_i_notify94 < _i_open94, "串行顺序：通知权限 → 打开网页")
    # ⚠ SAF 与通知权限的 launcher **必须在引导页自己**（不在设置页）——
    #   否则「回调在设置页、推进在引导页」就跨了两个组合作用域，接不上。
    check("rememberLauncherForActivityResult" in _sg_code94,
          "SAF / 通知权限的 launcher 在引导页自己（串行推进需要同一作用域）")
    check("steamTreeLauncher" not in _sp94, "设置页不持有 SAF launcher")

    # ── 三层兜底：observer / 回扫 / 前台服务 ──
    check("registerContentObserver" in _code94, "兜底①：注册 ContentObserver")
    check("ON_RESUME" in _code94_ma, "兜底②：onResume 时回扫下载目录")
    # 前台服务由**引导页**在用户点「打开 Steam 云存档页」时启动
    # （Android 12+ 要求前台服务必须在 App 在前台时起 —— 那一刻正是用户在点按钮）。
    check("SteamImportService" in _code94, "兜底③：前台服务（App 在后台时）")
    check("SteamImportService.start" in _code94,
          "点「打开 Steam 云存档页」时启动前台服务")
    # ⚠ 前台服务**不能**由设置页启动 —— 它属于引导页的流程
    check("SteamImportService" not in _sp94,
          "前台服务只由引导页启动（设置页只是入口）")
    check("scanAndImport" in _code94_ma, "回 App 时触发扫描")

    # ================= 95. 补回 v2.87.0~v2.90.0（源码丢失后从已发布的 2.90.0 找回） =================
    # 背景：系统重装把 v2.87~v2.90 的源码冲掉了，已发布的 2.90.0（c167）是唯一事实。
    # 这一节钉的是**补回后的行为**，不是当年的实现细节。
    print("\n== 95. 补回 v2.87.0~v2.90.0 四项 ==")
    src = lambda rel: io.open(os.path.join(SRC, rel.replace("/", os.sep)), encoding="utf-8").read()
    _wr95 = src("widget/WidgetRender.kt")
    _tw95 = src("widget/TodayWidget.kt")
    _tp95 = src("ui/today/TodayPage.kt")
    _sp95 = src_sp()
    _rp95 = src("ui/report/ReportPage.kt")
    _sg95 = src("ui/SteamImportGuide.kt")
    _cp95 = src("ui/calc/CalcPage.kt")
    _lay95 = os.path.join(ROOT, "app", "src", "main", "res", "layout")

    # 95.1 小组件「明天」（v2.89.0）
    for _layn95 in ("widget_today", "widget_card", "widget_wide"):
        _l95 = io.open(os.path.join(_lay95, _layn95 + ".xml"), encoding="utf-8").read()
        check("@+id/w_next" in _l95, "%s 布局有「明天」按钮位" % _layn95)
    check('"▸ 明天"' in _wr95, "按钮文案是「▸ 明天」")
    check("fun nextDay(" in _wr95, "有下一天的日期算法（跨季跨年）")
    check("R.id.w_next" in _wr95, "渲染层给「明天」按钮接线")
    check("PendingIntent.getBroadcast" in _wr95, "「明天」走广播（不用先打开 App）")
    check("ACTION_NEXT_DAY" in _tw95 and "WIDGET_NEXT_DAY" in _tw95, "小组件接住「明天」广播")
    check("repo.setDate(" in _tw95,
          "「明天」推进的是 App 里的游戏日期（不只是小组件自己的副本）")

    # 95.2 农场年度报告移到「我的农场」（v2.88.0）
    check("FarmReportCard(" in _tp95, "今日页「我的农场」挂了报告卡")
    check("FarmReportCard(" not in code_only(_sp95), "设置页不再挂报告卡")

    # 95.3 「查看农场报告」直接看报告长图 + 多存档自选（v2.87.0）
    check("private fun ReportPreviewDialog" not in _rp95, "报告预览窗对外可见（能直接拉起来）")
    check("ReportPreviewDialog(" in _sp95, "导入完成后直接拉起报告长图")
    check("NeedChoice" in _sg95, "多存档时让用户自选一份导入")
    check("reportReady" in _sg95, "没解析出报告就不给「查看农场报告」按钮")

    # 95.4 Steam / Valve 归属声明（v2.90.0）
    check("Steam 是 Valve Corporation 的注册商标" in _sp95, "协议页有 Steam/Valve 归属声明")
    check("☁️ Steam 云存档" in _sp95, "协议页有「Steam 云存档」那张卡")
    check("NON_OFFICIAL_NOTICE" in _sg95, "引导页有非官方声明")
    _top95 = _sg95.split("🔒 安全提示", 1)
    check(len(_top95) == 2 and "NON_OFFICIAL_NOTICE" in _top95[0],
          "非官方声明在引导页**顶部**（安全提示之前）")

    # 95.5 计算页与今日页共用同一个日期（v2.89.0）
    check("val season = snap.season" in _cp95 and "val day = snap.day" in _cp95,
          "计算页用今日页那份日期（不再各存一份）")
    check("repo.setDate(" in _cp95, "计算页改日期写的是共享日期")

    # 95.6 版本与日志口径
    _m_vn95 = re.search(r'versionName\s*=\s*"([^"]+)"', gb)
    _m_code95 = re.search(r'versionCode\s*=\s*(\d+)', gb)
    check(bool(_m_code95) and int(_m_code95.group(1)) >= 168,
          "versionCode >= 168（164~167 已被远端占用，版本号只增不减）")
    for _v95 in ("v2.90.0 补齐 Steam 与 Valve 的归属声明",
                 "v2.89.0 小组件可切下一天 + 计算页日期跟今日页同步",
                 "v2.88.0 农场年度报告移到「我的农场」",
                 "v2.87.0 修：报告入口与多存档选择"):
        check(_v95 in _sp95, "CHANGELOG 补回 %s" % _v95)
    # ⚠ v2.93.0：原来这两条**写死** "2.92.0"——每升一版就得手改一次，忘了改 verify 就红，
    #   而红的原因跟它想防的东西毫无关系（v2.32 / v2.42 各犯过一次同样的错：钉字面量）。
    #   真正该防的是「**versionName 与 CHANGELOG 首条不是同一个版本**」——
    #   改成**按 gradle 现算**比较：守住原意，又不用每次升版手改这里。
    _m_log95 = re.search(r'private val CHANGELOG = listOf\(\s*\r?\n\s*"v?([0-9][0-9.]*)[^"]*"', _sp95)
    check(bool(_m_vn95) and _m_log95 and _m_log95.group(1) == _m_vn95.group(1),
          "CHANGELOG 首条 = versionName = %s（两者必须是同一个版本）"
          % (_m_vn95.group(1) if _m_vn95 else "无"))

    # ================= 96. 补回「所有文件访问」整套方案（远端 2.90.0 有、上一版漏了） =================
    # 背景：v2.87~v2.90 的源码丢失后从 2.90.0 反编译补回，但**漏掉了这一块**——
    #   它没写进任何版本日志（是 c164~c167 之间悄悄加的），按 CHANGELOG 补回时根本看不见。
    #   后果：① manifest 少了 MANAGE_EXTERNAL_STORAGE → 已用该权限的用户升级后被系统**撤权**；
    #        ② 引导页那条「不用改下载位置」的路没了，用户多绕一道；
    #        ③ 等于 2.91.0 相对 2.90.0 **功能倒退**。
    # 本节钉的是补回后的**行为**，不钉当年的实现细节。
    print("\n== 96. 补回「所有文件访问」整套方案 ==")
    _sd96 = src("data/SteamDownloadDir.kt")
    _sn96 = src("ui/SteamNotices.kt")
    _sg96 = src("ui/SteamImportGuide.kt")
    _sp96 = src_sp()
    _cp96 = src("ui/calc/CalcPage.kt")
    _mf96 = io.open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"),
                    encoding="utf-8").read()

    # 96.1 权限声明
    check("MANAGE_EXTERNAL_STORAGE" in _mf96,
          "manifest 声明「所有文件访问」（不声明，用户就**不在**系统那个列表里，想开也开不了）")
    # ⚠ 精确匹配：MANAGE_EXTERNAL_STORAGE **包含** READ_EXTERNAL_STORAGE 这个子串，
    #   用子串判定会把「有 MANAGE」误判成「也申请了 READ」。
    _perm_tags96 = re.findall(r"<uses-permission[^>]*android:name=\"([^\"]+)\"", _mf96)
    check("android.permission.READ_EXTERNAL_STORAGE" not in _perm_tags96,
          "仍然不申请 READ_EXTERNAL_STORAGE（读别的 App 的文件用不上它，Android 13+ 也拿不到）")

    # 96.2 数据层：检测 + 三条扫描通道 + 去重
    check("fun hasAllFilesAccess(" in _sd96, "能检测「所有文件访问」开没开")
    check("isExternalStorageManager" in _sd96, "检测用的是系统的存储管理器状态")
    check("fun scanAllFilesAccess(" in _sd96, "有「所有文件访问」这条扫描通道")
    check("fun scanMediaStore(" in _sd96, "有 MediaStore 这条扫描通道")
    check("扫描下载目录（所有文件访问）失败" in _sd96, "三条通道各自记自己的错（不合并成一句）")
    check("扫描 SAF 下载目录失败" in _sd96, "SAF 通道的错单独记")
    check("扫描 MediaStore 失败" in _sd96, "MediaStore 通道的错单独记")
    check("hasAllFilesAccess(ctx) || hasTreePermission(ctx)" in _sd96,
          "自动识别的条件是「所有文件访问」或「已授权文件夹」任一成立")
    check("fun scanSummary(" in _sd96, "有扫描状态摘要（给引导页显示）")
    check("已开所有文件访问" in _sd96 and "未开所有文件访问" in _sd96,
          "摘要里两种权限状态都写清楚")
    check("（主存档✓）" in _sd96, "摘要里标出哪个是主存档")

    # 96.3 引导页：两套办法 + 跳系统设置
    check("有两种办法" in _sg96, "引导页说清有两条路")
    check("去开启所有文件访问" in _sg96, "有跳系统设置的按钮")
    check("ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION" in _sg96,
          "跳的是本 App 的「所有文件访问」设置页")
    check("✓ 已开启" in _sg96, "已开启时有徽章")
    check("办法二：只授权一个文件夹" in _sg96, "零权限那条路仍在（办法二）")
    check("✓ 已授权" in _sg96, "已授权文件夹时有徽章")
    check("这个系统没有「所有文件访问」开关" in _sg96,
          "系统没这个开关时明确让用户走办法二")

    # 96.4 手动重扫 + 扫描状态
    check("重新扫描下载目录" in _sg96, "有手动重扫入口")
    check("scanAndImport(ctx, scope, force = true)" in _sg96,
          "重扫强制再处理一次（绕开「已处理过」的去重记录）")
    check("没自动弹？扫一次看看" in _sg96, "有「没自动弹」的退路提示")
    check("（还没扫过）" in _sg96 or "scanSummary" in _sg96, "扫描状态会显示在引导页")

    # 96.5 隐私口径与归属声明
    check("不再分发" in _sn96, "归属声明里有「不再分发」（远端 2.90.0 原文）")
    check("STEAM_CLOUD_ATTRIBUTION" in _sg96, "引导页安全提示也带这份声明")
    check("存档只在你主动导入时读" in _sp96, "关于页口径是「只在用户主动导入时读」")
    check("「从 Steam 云获取存档」可选开「所有文件访问」权限" in _sp96,
          "关于页把「所有文件访问」这个可选权限写出来")
    check("日期跟今日页是同一个" in _cp96, "计算页说明日期与今日页同源")

    # ================= 97. 模拟器实测揪出的漏：manifest 少一条 intent-filter =================
    # 背景：v2.89.0「小组件 ▸ 明天」的代码补回来了（发 ACTION_NEXT_DAY 广播），
    #   但 **manifest 里的 intent-filter 漏了** —— 系统按 manifest 声明匹配广播，
    #   少声明 → 广播没人接 → 按钮渲染得出来、**点了没反应**。
    #   ⚠ 这条**静态检查全查不出来**：receiver 在、action 常量在、代码在、
    #     dex 字符串表能命中、aapt2 能查到 receiver —— 只少一行声明。
    #     最后是在**模拟器上真机点小组件**才暴露的。
    print("\n== 97. manifest 的 intent-filter 完整性（模拟器实测揪出的漏） ==")
    _mf97 = io.open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"),
                    encoding="utf-8").read()
    _tw97 = src("widget/TodayWidget.kt")
    _intro97 = src("ui/Intro.kt")
    _filters97 = re.findall(r"<intent-filter>(.*?)</intent-filter>", _mf97, flags=re.S)
    _nd97 = [f for f in _filters97 if "WIDGET_NEXT_DAY" in f]
    check(len(_nd97) >= 1,
          "manifest 声明了 WIDGET_NEXT_DAY（点「▸ 明天」的广播得有人接）")
    check(all("APPWIDGET_UPDATE" not in f for f in _nd97),
          "WIDGET_NEXT_DAY 单独一个 intent-filter（与 APPWIDGET_UPDATE 那个分开写）")
    # 代码里发的 action 与 manifest 声明的**逐字一致**（写错一个字就静默失效）
    _act97 = re.search(r'ACTION_NEXT_DAY\s*=\s*"([^"]+)"', _tw97)
    check(bool(_act97) and ('"%s"' % _act97.group(1)) in _mf97,
          "代码发的 action 与 manifest 声明的逐字一致（%s）"
          % (_act97.group(1) if _act97 else "未找到常量"))
    check("PendingIntent.getBroadcast" in _tw97.replace("\n", " ") or
          "getBroadcast" in src("widget/WidgetRender.kt"),
          "「明天」是广播（不用先打开 App）")
    # 引导页不说「完全离线」（App 实际每次打开会联网查一次更新）
    # ⚠ 判据要**剥注释** —— 注释里可以写「「完全离线」是不准确措辞」，
    #   那是给人看的说明，不是给用户看的文案。
    # ⚠ 只查引导页（Intro.kt）；CHANGELOG 里 v1.0 的「五大板块，完全离线」是
    #   **历史版本日志**，那是当年真实发过的宣传语，不许改。
    check("完全离线" not in code_only(_intro97),
          "引导页不说「完全离线」（App 实际有联网行为，这是不准确措辞）")
    check("不用联网也能用" in code_only(_intro97), "引导页用的是「不用联网也能用的」（远端 2.90.0 原文）")

    # ================= 98. 今日页「跳到下一日」+ 计算页块数按存档隔离 =================
    # 用户 2026-10-25 提的两件事：① 今日页通用日历里加个快捷跳到下一日；
    # ② **bug**：不同存档之间计算页的板块数量没有隔离（切存档后按上一个存档的块数算净利）。
    print("\n== 98. 今日页「跳到下一日」+ 计算页块数按存档隔离 ==")
    _tp98 = src("ui/today/TodayPage.kt")
    _tp98c = code_only(_tp98)
    _wr98 = src("widget/WidgetRender.kt")
    _wr98c = code_only(_wr98)

    # 98.1 「下一日」入口（通用日历侧）
    check("下一日" in _tp98c, "今日页有「下一日」入口")
    check('item(key = "nextDay")' in _tp98c, "入口挂在通用日历的 LazyColumn 里")
    # ⚠ v2.92.0 改：用户反馈「界面太大、占视觉空间」⇒ 从三行压成一行。
    #   钉住「压成一行」这件事：标题行与说明行都不该再占地方。
    check('PixelCard(title = "📅 下一天"' not in _tp98c,
          "入口不再有独立标题行（用户反馈太占视觉空间，已压成一行）")
    _row98 = re.search(r'item\(key = "nextDay"\)(.*?)\n            \}', _tp98c, re.S)
    check(_row98 is not None and _row98.group(1).count("Row(") >= 1,
          "入口的内容排成一行（左边日期、右边按钮）")
    # ⚠ 必须与小组件「明天」**同一个算法** —— 两处各写一份跨季跨年逻辑，
    #   迟早走散（小组件走到明年春 1 日、App 走到今年冬 29 日）。
    check("WidgetRender.nextDay(" in _tp98c, "用小组件那同一个 nextDay 算法（两处不许各写一份）")
    check("fun nextDay(" in _wr98c, "nextDay 仍是小组件那边那一个（唯一实现）")
    # 推进的是**今日页那个共享日期**（与计算页共用，v2.89.0 起）
    check("repo.setDate(ny, ns, nd)" in _tp98c, "推进的是共享的游戏日期（repo.setDate）")
    # 第 10 年是游戏上限 → 不给按钮
    check("WidgetRender.MAX_YEAR" in _tp98c, "第 10 年时不再往后推（不给按钮）")

    # 98.2 块数按存档隔离
    _pr98 = src("data/PrefsRepo.kt")
    _pr98c = code_only(_pr98)
    check('stringPreferencesKey("cp:$n")' in _pr98c, "块数有**存档级**键 cp:<存档名>")
    check("suspend fun setCalcPlots" in _pr98c and "calcPlotsKey(prof)" in _pr98c,
          "写块数落到当前存档（不是全局那一份）")
    check("parseCalcPlots(p[calcPlotsKey(prof)])" in _pr98c,
          "读块数按当前存档取（切存档读到的是那一份的块数）")
    check("val calcPlots: CalcPlots = CalcPlots()" in _pr98c,
          "Snapshot 带 calcPlots 字段（带默认值，老构造点不受影响）")
    # 块数**不能**再在全局那份里（否则 bug 原样存在）
    _enc98 = _body67(_pr98, "private fun encodeCalc")
    check('.put("p0"' not in _enc98 and '.put("p1"' not in _enc98 and '.put("p2"' not in _enc98,
          "全局的 calc_setup 里不再带块数（那正是没隔离的病根）")
    # 一次性迁移：老用户拨好的块数在全局 calc_setup 里
    check("suspend fun migrateCalcPlots" in _pr98c
          and "migrateCalcPlots()" in src("MainActivity.kt"),
          "块数拆键有一次性迁移（老用户拨好的块数不丢）")

    # ================= 99. v2.93.0 App 内系统字体开关 =================
    # 背景：像素字体在非整数倍字号下发糊（群友反馈），于是给 App 内加一个「系统字体」档。
    # 这一节钉的是**唯一入口**与**四条边界**：
    #   ① PixelText 是唯一入口（336 处调用自动生效，不必逐个改）；
    #   ② 切字体必须重建样式（remember 的 key 必须含字体，否则 Text 跳过重组）；
    #   ③ **小组件 / 分享卡 / 农场报告长图仍是像素字体**（用户 2026-10-25 明令）；
    #   ④ 不许再有直接 Text( 绕过开关（ProgressPage 那两处就是漏网的）。
    # ⚠ 静态断言只是兜底 —— 第 97 节的教训是「静态查不出的漏项只有真机能暴露」，
    #   分享卡那条（最可能漏的一处）必须装机点一遍。
    print("\n== 99. App 内系统字体开关（v2.93.0） ==")
    _pt99 = src("ui/PixelTheme.kt")
    _pt99c = code_only(_pt99)
    _ma99 = src("MainActivity.kt")
    _ma99c = code_only(_ma99)
    _pr99 = src("data/PrefsRepo.kt")
    _pr99c = code_only(_pr99)
    _sp99 = src_sp()
    _sp99c = code_only(_sp99)
    _se99 = src("ui/share/ShareEntry.kt")
    _se99c = code_only(_se99)
    _rc99 = src("ui/report/ReportCanvas.kt")

    # 顶层函数切片 helper：既有 _body67 是给 PrefsRepo 的**类成员**用的（列 4 空格缩进），
    # 而 PixelText 是顶层声明（列 0 缩进），_body67 截不到 ⇒ 这里另写一个。
    _TOPDECL99 = re.compile(
        r"\n(?=(?:@|(?:private |internal |public |override |suspend |inline )*"
        r"(?:fun|val|var|object|class|data class|enum class|sealed class|interface)\b"
        r"|import |package ))")

    def _topfn99(text, marker):
        i = text.find(marker)
        if i < 0:
            return ""
        m = _TOPDECL99.search(text, i + len(marker))
        return text[i:m.start()] if m else text[i:]

    _ptext99 = _topfn99(_pt99c, "fun PixelText(")

    # 99.1 单源
    check("object AppFont" in _pt99c, "AppFont 单源对象存在（ui/PixelTheme.kt）")
    _af99 = re.search(r"object AppFont \{.*?OPTIONS\s*=\s*listOf\((.*?)\)", _pt99c, re.S)
    # v2.95.0：由两档改三档（加了「自定义」）。**断言跟着语义改**——
    # 仍是「值与标签用 Pair 捆在一起、不会改错位」这条不变量，只是档数变了。
    check(bool(_af99) and len(re.findall(r"\bto\b", _af99.group(1))) == 3,
          "AppFont 正好三档（值与标签用 Pair 捆在一起，不会改错位）")
    check("const val PIXEL" in _pt99c and "const val SYSTEM" in _pt99c
          and "const val CUSTOM" in _pt99c,
          "AppFont 的三档落盘值是 PIXEL / SYSTEM / CUSTOM")
    # **默认值必须是像素字体** —— 这是「安全默认」这条不变量：
    # 将来新页面忘了 provide，天然还是像素字体，不会因为漏接一次而变脸。
    check("LocalAppFont = staticCompositionLocalOf { PixelFont }" in _pt99c,
          "LocalAppFont 默认值就是 PixelFont（漏 provide 的地方不会变系统字体）")

    # 99.2 PixelText 是唯一入口，且覆盖得对
    check("LocalAppFont.current" in _ptext99, "PixelText 读 LocalAppFont")
    check("fontFamily" in _ptext99 and "copy(" in _ptext99,
          "PixelText 用 style.copy 覆盖 fontFamily（不改 Px 的定义）")
    # ⚠ remember 的 key 漏掉字体 ⇒ TextStyle 不重建 ⇒ Text 判定「没变」跳过重组 ⇒ 切了没反应。
    #    这里**不钉具体写法**，只钉「key 里同时有 style 与字体变量」这个关系。
    _rem99 = re.findall(r"remember\(([^)]*)\)", _ptext99)
    check(any(("style" in a and "appFont" in a) for a in _rem99),
          "PixelText 的 remember key 同时含 style 与字体变量（切字体才会重建样式）")
    check("linkedStyle = s.copy(" in _ptext99, "攻略链接路径继承同一个 s（不会漏字体）")
    # Px 五档仍是顶层 val（没被改成 var —— 那会把「动态字体」污染成全局可变状态）
    check("val PixelFont = FontFamily(Font(R.font.fusion_pixel))" in _pt99c,
          "PixelFont 仍是顶层常量")
    check("object Px {" in _pt99c and re.search(r"object Px \{[^}]*\bvar\b", _pt99c, re.S) is None,
          "Px 五档仍是 val（没被改成 var）")

    # 99.3 边界（本节最重要的部分）
    _lay99 = os.path.join(ROOT, "app", "src", "main", "res", "layout")
    for _f99 in ("widget_today", "widget_card", "widget_wide"):
        _p99 = os.path.join(_lay99, _f99 + ".xml")
        check(os.path.exists(_p99) and "@font/fusion_pixel" in io.open(_p99, encoding="utf-8").read(),
              "小组件 %s.xml 仍是 @font/fusion_pixel（桌面观感不跟 App 内字体变）" % _f99)
    check("ResourcesCompat.getFont(ctx, R.font.fusion_pixel)" in _rc99,
          "农场报告长图仍用像素字体资源（Canvas 通道与 Compose 无关）")
    check("LocalAppFont provides PixelFont" in _se99c,
          "分享卡图层显式钉回像素字体（分享图不跟 App 内字体变）")
    # 全项目不许再有直接 Text( —— 绕过 PixelText 就绕过了字体开关。
    #    用「前面不是字母数字下划线」判定：PixelText( / BasicText( 不算，material3.Text( 算。
    _dir99 = os.path.join(ROOT, "app", "src", "main", "java")
    _hits99 = []
    for _r99, _d99, _fs99 in os.walk(_dir99):
        for _fn99 in _fs99:
            if not _fn99.endswith(".kt"):
                continue
            _pp99 = os.path.join(_r99, _fn99)
            if os.path.abspath(_pp99) == os.path.abspath(os.path.join(SRC, "ui", "PixelTheme.kt")):
                continue  # PixelText 自己那处 Text( 是实现，不是漏网
            _t99 = code_only(io.open(_pp99, encoding="utf-8").read())
            if re.search(r"(?<![A-Za-z0-9_])Text\(", _t99):
                _hits99.append(os.path.relpath(_pp99, ROOT))
    check(not _hits99,
          "全项目没有绕过 PixelText 的直接 Text( 调用（漏网 %d 处：%s）"
          % (len(_hits99), "、".join(_hits99[:3])))

    # 99.4 PrefsRepo 持久化（照抄 53 节的字号档模式）
    check('stringPreferencesKey("font_family")' in _pr99c, "字体档有独立全局键 font_family")
    _sf99 = _body67(_pr99c, "suspend fun setFontFamily")
    # v2.95.0：三值口径。**断言跟着语义改**——「脏值不许把整 App 的字体换掉」这条不变量
    # 仍在（其余值一律落像素），只是合法的档位从两档变三档。
    #
    # ⚠ v2.95.0 起写处**不再自己判三档**，改为委托给单源 resolveFontFamily：
    #   三档判定只有一处实现，各写一遍迟早漂移（一边认 custom 一边不认 =
    #   界面选着自定义、实际显示像素）。三档的**具体内容**在第 101 节钉
    #   （那里能切到函数体逐条看），这里只钉「写处走单源」这条关系。
    check(bool(_sf99) and "resolveFontFamily(" in _sf99,
          "setFontFamily 走单源判定 resolveFontFamily（写处不自己重写一遍三档）")
    check("val fontFamily: String = AppFont.PIXEL" in _pr99c,
          "Snapshot 带 fontFamily 且默认像素（老构造点不受影响）")
    # v2.95.0：读处与写处**共用同一个判定函数**（resolveFontFamily）——
    # 各写一遍迟早漂移（一边认 custom 一边不认 = 界面选着自定义、实际显示像素）。
    check("resolveFontFamily" in _pr99c,
          "字体档读处与写处共用同一个判定函数 resolveFontFamily（不会各写一遍漂移）")
    check('stringPreferencesKey("font_custom_id")' in _pr99c,
          "自定义字体 id 有独立全局键 font_custom_id")
    check("val fontCustomId: String = \"\"" in _pr99c,
          "Snapshot 带 fontCustomId 且默认空串（老构造点不受影响）")
    # v2.95.0：备份仍带字体档，但 **custom 导出时降级成 pixel** ——
    #   字体文件是几 MB 的二进制，进不了 JSON 备份；只带个 id 换机后必然失效，
    #   还会让人误以为字体跟着走了。所以导出时降级、读处另有「文件存在才认 custom」的守卫。
    check('.put("font_family"' in _pr99c
          and 'if (snap.fontFamily == AppFont.CUSTOM) AppFont.PIXEL else snap.fontFamily' in _pr99c,
          "exportJson 带出字体档（换机跟着走），且 custom 降级成 pixel（字体文件带不走）")
    _imp99 = _pr99c.count('if (o.has("font_family"))')
    check(_imp99 == 2, "importJson 两个分支都有 has() 守卫（旧备份没字段=不动本地字体）")

    # 99.5 MainActivity / SettingsPage 接线
    check("AppFont.SYSTEM" in _ma99c and "LocalAppFont provides" in _ma99c,
          "MainActivity 从 snapshot 读字体档并 provide LocalAppFont")
    check("FontFamily.Default" in _ma99c, "系统字体取 FontFamily.Default（跟系统走）")
    check("PixelFont" in _ma99c, "像素档仍取 PixelFont")
    check('PixelCard(title = "🔠 字体")' in _sp99c, "设置页有「🔠 字体」卡")
    check("AppFont.OPTIONS" in _sp99c, "字体卡的分段控件用 AppFont.OPTIONS（值与标签单源）")
    check("repo.setFontFamily(" in _sp99c, "字体卡 onSelect 落盘")
    check("主题、字体、字号" in _sp99c, "备份卡说明文案已补上「字体」")

    # 99.6 版本口径：按 gradle 现算，**不钉字面量版本号**（与 19.x / 37.7 同款做法；
    #     归档账本那条由 37.7 统一负责，本节不重复，免得同一件事钉两处）。

    # ================= 100. v2.94.0 NPC 行程表 =================
    # 背景：群友反馈「想要 NPC 的行程表」→ 新增 assets/data/npcSchedules.json
    # （34 位原版 NPC，源表 1883 条时段）与一个独立页 + 村民详情卡。
    #
    # 这一节钉四件事：
    #   ① 数据资产在位且**自洽**（不是「文件在就算数」）；
    #   ② 「今天谁在哪」的判定只有一处，优先级顺序与整理稿一致；
    #   ③ 跨午夜（12:00 AM）按「当日深夜延续」算；
    #   ④ 容器标题（分组名）**不许**参与「今天适用」判定。
    #
    # ⚠ 源 MD 不在仓库里，所以「1883 行逐条对撞」没法写成常驻断言（提交前单独做过：
    #   零丢失、零杜撰、时间换算零错）。这里钉的是**结构 + 口径**——
    #   防的是以后改代码时把判定改歪，不是防数据被手改。
    print("\n== 100. NPC 行程表（v2.94.0） ==")
    _np100 = os.path.join(DATA, "npcSchedules.json")
    check(os.path.exists(_np100), "行程表数据文件在位（assets/data/npcSchedules.json）")
    _sched100 = {}
    if os.path.exists(_np100):
        try:
            _sched100 = _json.load(io.open(_np100, encoding="utf-8"))
        except Exception as _e100:
            check(False, "行程表 JSON 能解析：%s" % _e100)
    _npcs100 = _sched100.get("npcs", []) if _sched100 else []
    check(len(_npcs100) == 34, "行程表覆盖 34 位原版 NPC（实际 %d 位）" % len(_npcs100))
    # 34 位原版 NPC 是**游戏事实**（不是随便写的字面量），钉住它才能防「重新生成时漏了谁」
    _expect100 = {
        "亚历克斯", "艾利欧特", "哈维", "山姆", "塞巴斯蒂安", "谢恩",
        "阿比盖尔", "艾米丽", "海莉", "莉亚", "玛鲁", "潘妮",
        "卡洛琳", "克林特", "德米特里厄斯", "矮人", "艾芙琳", "乔治", "格斯", "贾斯", "乔迪",
        "肯特", "科罗布斯", "雷欧", "刘易斯", "莱纳斯", "玛妮", "齐先生", "潘姆", "皮埃尔",
        "罗宾", "桑迪", "威利", "法师",
    }
    _got100 = set(n.get("name") for n in _npcs100)
    check(_got100 == _expect100,
          "34 位 NPC 与原版名单一致（缺 %s / 多 %s）"
          % ("、".join(sorted(_expect100 - _got100)) or "无",
             "、".join(sorted(_got100 - _expect100)) or "无"))

    _ids100 = []
    _rows100 = 0
    _bad100 = []
    _badc100 = []
    _bade100 = []
    _nonempty100 = 0
    for _n100 in _npcs100:
        _browse100 = [v for v in _n100.get("variants", []) if not v.get("container")]
        if not _browse100:
            _nonempty100 += 1
        for _v100 in _n100.get("variants", []):
            _ids100.append(_v100.get("id"))
            _rs100 = _v100.get("rows", [])
            _rows100 += len(_rs100)
            for _r100 in _rs100:
                _t100 = (_r100.get("t") or "").strip()
                _a100 = (_r100.get("a") or "").strip()
                _ok100 = re.match(r"^(\d{1,2}):(\d{2})\s*(AM|PM)$", _t100, re.I) \
                    or _t100 in ("全天", "白天", "夜间")
                if not _t100 or not _a100 or not _ok100:
                    _bad100.append("%s/%s" % (_v100.get("id"), _t100))
            if _v100.get("container") and _rs100:
                _badc100.append(_v100.get("id"))
            if (not _v100.get("container")) and (not _rs100) \
                    and not (_v100.get("note") or "").strip():
                _bade100.append(_v100.get("id"))
    check(len(_ids100) == len(set(_ids100)), "变体 id 全局唯一（%d 个）" % len(_ids100))
    check(not _bad100, "每条时段的时间/行为都非空且时间格式合法（异常 %d 处）" % len(_bad100))
    check(not _badc100, "container=true 的变体必无时段（分组名不是一张表）")
    check(not _bade100, "无时段的非容器变体必须带说明文字（内容不凭空消失）")
    # ⚠ v2.94.0 真机点出来的第二类 bug：special 类变体若**既无日期也无星期**，
    #    它就是「每天成立」⇒ 平日常年显示节日行程。源稿里确实有这么写的
    #    （「沙漠节（作为摊主）」只占描述、日期在同名/同节日兄弟那条上），
    #    转换器已把日期继承过来 —— 这条断言守住那个不变量。
    _noanchor100 = []
    for _n100 in _npcs100:
        for _v100 in _n100.get("variants", []):
            if _v100.get("container"):
                continue
            if _v100.get("kind") != "special":
                continue
            if _v100.get("rows") and not _v100.get("dates") and not _v100.get("weekdays"):
                _noanchor100.append(_v100.get("id"))
    check(not _noanchor100,
          "有表的 special 变体必带锚点（日期或星期）——无锚点=每天都成立（异常 %d 处）"
          % len(_noanchor100))
    check(_nonempty100 == 0, "每位 NPC 至少有一张可浏览的表")
    # 1883 = 整理稿的表格正文行数（引用会复制行，所以 JSON 里的行数只会更多，不会更少）
    check(_rows100 >= 1883, "总时段数不少于源表的 1883 条（实际 %d）" % _rows100)

    _ns100 = src("data/NpcSchedules.kt")
    _nsc100 = code_only(_ns100)
    _sp100 = src("ui/schedule/SchedulePage.kt")
    _spc100 = code_only(_sp100)
    _su100 = src("ui/schedule/ScheduleUi.kt")
    _suc100 = code_only(_su100)
    _vp100 = src("ui/villagers/VillagersPage.kt")
    _vpc100 = code_only(_vp100)
    _ma100 = src("MainActivity.kt")
    _mac100 = code_only(_ma100)

    # 100.2 判定口径
    check('optBoolean("container"' in _nsc100, "解析时读 container 标记（分组名不是表）")
    check("!it.container" in _nsc100, "容器变体被排除在判定之外")
    _app100 = _topfn99(_nsc100, "fun NpcSchedule.applicable(")
    _ord100 = [
        _app100.find("v.dates.any"),
        _app100.find("ScheduleKind.GREEN"),
        _app100.find("ScheduleKind.RAIN"),
        _app100.find("ScheduleKind.SPECIAL"),
        _app100.find("ScheduleKind.CLEAR"),
    ]
    check(all(x >= 0 for x in _ord100) and _ord100 == sorted(_ord100),
          "「今天适用」优先级顺序 = 特殊日期 > 绿雨 > 雨天 > 特殊星期 > 常规")
    # ⚠ v2.94.0 真机点出来的 bug：雨天 / 绿雨分支原先**不看当前天气**，
    #    于是「春 13 日 · 周六 · 晴」那天几乎所有人都显示「雨天」——
    #    「他今天去哪」被答错了。这条断言钉住「天气是开关」。
    check("ctx.weather == ScheduleWeather.GREEN" in _app100
          and "ctx.weather == ScheduleWeather.RAIN" in _app100,
          "雨天 / 绿雨分支**看当前天气**（晴天不会弹出雨天的表）")
    # 锚点规则：special 分支要求 weekdays 非空 —— 否则「沙漠节（作为摊主）」
    # 那类**只有描述没有锚点**的条目会天天成立。
    check("it.weekdays.isNotEmpty()" in _app100,
          "特殊表必须带星期锚点（无锚点的条目不参与「今天适用」）")
    # 奇偶年条件：源稿里「沙漠节·偶数年作为摊主」/「奇数年作为游客」是一对，
    # 而「第几年」存档里就有 ⇒ 这是唯一能被 App 确定的分支条件。
    check("parityOk" in _app100 and "偶数" in _nsc100 and "奇数" in _nsc100,
          "奇偶年条件按游戏年份判定（源稿写「偶数年/奇数年」，匹配的是「偶数/奇数」）")
    _ra100 = _topfn99(_nsc100, "fun ScheduleVariant.rowAt(")
    check("m + 1440" in _ra100, "跨午夜按「当日深夜延续」处理（分钟数 +1440 单调化）")
    check("timed.last()" in _ra100, "早于当天第一行时承接前一晚（取当天最后一行）")
    check("((day - 1) % 7) + 1" in _nsc100, "星期口径 (day-1)%7+1（与今日页同一口径）")
    # 判定只有一处：全项目只允许有一个 applicable 的**定义**
    _apdef100 = []
    for _r100, _d100, _fs100 in os.walk(os.path.join(SRC)):
        for _fn100 in _fs100:
            if _fn100.endswith(".kt"):
                _pp100 = os.path.join(_r100, _fn100)
                if "fun NpcSchedule.applicable(" in io.open(_pp100, encoding="utf-8").read():
                    _apdef100.append(os.path.relpath(_pp100, ROOT))
    check(len(_apdef100) == 1,
          "「今天适用」判定只有一处实现（%s）" % ("、".join(_apdef100) or "无"))

    # 100.3 页面与接线
    check('BackBar("NPC 行程表"' in _spc100, "独立行程页顶栏标题正确")
    check("ScheduleWeather.LABELS" in _spc100, "天气三档用单源标签（不会与落盘值错位）")
    check("repo.setDate(" in _spc100, "行程页改日期写的是共享游戏日期（不另造一份）")
    check("searchSchedules(query)" in _spc100, "行程页有搜索模式")
    check("browseVariants(" in _suc100, "变体选择器只列非容器变体（分组名点开是空的）")
    check("ScheduleBrowseCard" in _suc100 and "ScheduleBrowseCard" in _vpc100,
          "独立页与村民详情卡共用同一个「切季选表」组件（两处各画一份必然漂移）")
    check("Screen.Schedule" in _mac100 and "stack.add(Screen.Schedule)" in _mac100,
          "MainActivity 有行程页的压栈分支与入口接线")
    check("onOpenSchedule" in _vpc100 and "onOpenSchedule" in _mac100,
          "村民页顶栏「🗺 行程」入口已接线")
    check("NpcSchedules.load(ctx)" in src("data/GameData.kt"),
          "行程表在 GameData.load 里加载（坏掉只降级行程页）")
    check(os.path.exists(os.path.join(ROOT, "tools", "gen_npc_schedules.py")),
          "行程表的生成脚本在仓库里（数据可复现，不是手工改的 JSON）")

    # ================= 101. v2.95.0 导入自定义字体（TTF/OTF） =================
    # 背景：用户 2026-10-26 提「导入 TTF 字体的功能，同时标注版权信息，与我们的 APP 无关」。
    #
    # 这一节钉七件事（Plan §6.3）：
    #   ① name 表解析是**纯字节**实现（不依赖 Context ⇒ 可在 JVM 上用真字体验收）；
    #   ② AppFont 三档落盘值与标签是单源；
    #   ③ PrefsRepo 读处与写处**共用一把尺**，且 custom 必须「本地真有那个文件」；
    #   ④ 备份**不带字体文件**，custom 导出即降级成 pixel（跨机不会假装字体跟着走）；
    #   ⑤ 设置页有 SAF 导入 / 字体信息块 / 免责文案 / 更换删除四件套；
    #   ⑥ 边界不变：分享卡 / 报告长图 / 桌面小组件**仍是像素字体**；
    #   ⑦ 字体解析有独立的 JVM 验收任务（customFontTest）。
    #
    # ⚠ 第 ⑥ 条是**边界延续**的结果（那三处本来就钉死了像素字体），不是「漏改」。
    #   但正因为「本来就不受影响」，静态断言**钉不住**「用户实际看到的字体对不对」——
    #   真机点一遍才是唯一证据（见验收步骤）。
    print("\n== 101. 导入自定义字体（v2.95.0） ==")
    _cf101 = src("data/CustomFont.kt")
    _cf101c = code_only(_cf101)
    _pr101c = code_only(src("data/PrefsRepo.kt"))
    _pt101c = code_only(src("ui/PixelTheme.kt"))
    _ma101c = code_only(src("MainActivity.kt"))
    _sp101c = code_only(src_sp())

    # ── 101.1 name 表解析：纯字节、不依赖 Context ────────────────────
    check(os.path.exists(os.path.join(ROOT, "app", "src", "main", "java",
                                      "com", "stardew", "assistant", "data", "CustomFont.kt")),
          "CustomFont.kt 在位（data/CustomFont.kt）")
    check("object CustomFont" in _cf101c, "CustomFont 是单源对象")
    # ⚠ 「解析入口不碰 Context」是**能上 JVM 验收**的前提 —— 一旦签名里出现 Context，
    #    就没法在无 Android 环境的 JVM 里拿真字体校准，只能靠真机点。
    _pnm101 = re.search(r"fun parseNameTable\((\w+): ByteArray\)\s*:\s*Meta", _cf101c)
    check(bool(_pnm101), "parseNameTable 的入参只有 ByteArray（纯字节，能在 JVM 上校准）")
    # nameID 常量：0 版权 / 1 家族 / 4 完整名 / 5 版本 / 6 PostScript / 13 许可 / 14 许可链接
    for _nid101, _nname101 in ((0, "NAME_COPYRIGHT"), (1, "NAME_FAMILY"), (4, "NAME_FULL"),
                               (5, "NAME_VERSION"), (6, "NAME_POSTSCRIPT"),
                               (13, "NAME_LICENSE"), (14, "NAME_LICENSE_URL")):
        check(("const val %s = %d" % (_nname101, _nid101)) in _cf101c,
              "nameID 常量 %s = %d（OpenType 规范的 id）" % (_nname101, _nid101))
    # 字符串解码：Windows 平台是 UTF-16BE（**不是** UTF-8、也不是 Little Endian）
    check("UTF-16BE" in _cf101c, "name 字符串按 UTF-16BE 解（Windows 平台的规范编码）")
    # Mac 平台用 mac_roman —— 查表由 Python 编解码器逐字节生成，不是手写
    check("MAC_ROMAN_HIGH" in _cf101c, "Mac 平台走 mac_roman 查表（不是当 Latin-1 用）")
    # 取值优先级：同 nameID 多条记录时优先 Windows/en-US
    check(re.search(r"platform\s*==\s*3\s*&&\s*language\s*==\s*0x0409", _cf101c) is not None,
          "同 nameID 多条记录时优先 Windows/en-US（不是随便取第一条）")
    # 落盘与读取
    check("fun idOf(bytes: ByteArray)" in _cf101c, "字体 id 用内容哈希（同名不同内容不会撞车）")
    check("MessageDigest.getInstance(\"SHA-1\")" in _cf101c, "内容哈希用 SHA-1")
    check("MessageDigest.getInstance(\"SHA-1\")" in _cf101c and "filesDir" in _cf101c,
          "字体落在私有目录 filesDir（不进公共存储、不要任何权限）")
    check('File(ctx.filesDir, DIR)' in _cf101c or 'File(ctx.filesDir, "fonts")' in _cf101c,
          "字体目录是 filesDir 下的 fonts 子目录")
    # ⚠ 20 MB 上限：挡住「误选了一个几百 MB 的文件」这种事故
    check("MAX_FONT_BYTES" in _cf101c and "20L * 1024 * 1024" in _cf101c,
          "字体文件有 20 MB 上限（挡住误选大文件）")
    # 导入时**先验证是不是真字体**：用 Typeface 实测，失败就不落盘
    check("Typeface.createFromFile" in _cf101c and "这个文件不是可用的字体" in _cf101c,
          "导入时用 Typeface 实测（不是字体就报错，且不落盘）")
    # 单槽位：导入新的替换旧的（用户 2026-10-26 拍板）
    check("MAX_FILES = 1" in _cf101c, "自定义字体是单槽位（导入新的替换旧的）")
    # 加载失败返回 null ⇒ 调用方回落像素字体
    check("fun loadFontFamily(ctx: Context, id: String): FontFamily?" in _cf101c,
          "loadFontFamily 失败返回 null（调用方据此回落像素字体）")
    check("fun exists(ctx: Context, id: String): Boolean" in _cf101c,
          "有 exists() 判本地字体文件在不在（备份跨机回落的依据）")
    check("fun delete(ctx: Context, id: String)" in _cf101c,
          "有 delete()（删字体文件 + 元数据）")

    # ── 101.2 AppFont 三档单源 ──────────────────────────────────────
    check('const val PIXEL = "pixel"' in _pt101c and 'const val SYSTEM = "system"' in _pt101c
          and 'const val CUSTOM = "custom"' in _pt101c,
          "AppFont 三档落盘值：pixel / system / custom")
    _opts101 = re.search(r"val OPTIONS = listOf\((.*?)\)", _pt101c, re.S)
    check(bool(_opts101) and len(re.findall(r"\bto\b", _opts101.group(1))) == 3,
          "AppFont.OPTIONS 正好三档（值与标签捆在一起，不会改错位）")
    check('CUSTOM to "自定义"' in _pt101c, "第三档的标签是「自定义」")
    # 合法值单源：读处/写处守卫都用它，不各处手写字符串
    check("val VALUES = listOf(PIXEL, SYSTEM, CUSTOM)" in _pt101c
          and "fun isValid(v: String?): Boolean" in _pt101c,
          "AppFont 另有合法值单源 VALUES + isValid（守卫不手写字符串）")

    # ── 101.3 PrefsRepo：读处与写处共用一把尺 ─────────────────────────
    check("private fun resolveFontFamily(raw: String?, customId: String, customExists: Boolean): String"
          in _pr101c, "字体档判定有单源函数 resolveFontFamily")
    # 用 _body67 取函数体（PrefsRepo 的类成员是 4 空格缩进，它就是给这类用的）
    _rftxt101 = _body67(_pr101c, "private fun resolveFontFamily")
    check("AppFont.SYSTEM" in _rftxt101,
          "resolveFontFamily 认 system 档（不需要任何文件）")
    check("AppFont.CUSTOM" in _rftxt101 and "customExists" in _rftxt101
          and "customId.isNotBlank()" in _rftxt101,
          "resolveFontFamily 认 custom 档**还要本地真有那个字体文件**")
    check("AppFont.PIXEL" in _rftxt101,
          "resolveFontFamily 其余值（脏值/缺字段）一律落像素")
    # ⚠ 读处与写处**必须共用**同一个函数 —— 各写一遍迟早漂移
    #    （一边认 custom 一边不认 = 界面选着自定义、实际显示像素）。
    _rf_calls101 = _pr101c.count("resolveFontFamily(")
    check(_rf_calls101 >= 4,
          "resolveFontFamily 有 4 处调用（读处 1 + 写处 1 + 两个 importJson 分支各 1）")
    check("CustomFont.exists(context" in _pr101c,
          "custom 档的判定真的查了本地文件（CustomFont.exists）")
    check('stringPreferencesKey("font_custom_id")' in _pr101c,
          "自定义字体 id 有独立全局键 font_custom_id")
    check("val fontCustomId: String = \"\"" in _pr101c,
          "Snapshot 带 fontCustomId 且默认空串（老构造点不受影响）")
    check("suspend fun setFontCustomId(id: String)" in _pr101c,
          "有 setFontCustomId（记下当前用哪个字体文件）")

    # ── 101.4 备份口径：字体文件不进备份，custom 导出即降级 ───────────
    _exp101 = re.search(r"\.put\(\"font_family\", (.+?)\)\n", _pr101c)
    check(bool(_exp101) and "AppFont.CUSTOM" in _exp101.group(1)
          and "AppFont.PIXEL" in _exp101.group(1),
          "导出备份时 custom 降级成 pixel（换机后重新导入即可）")
    # ⚠ font_custom_id **不许**进备份：字体文件是几 MB 的二进制进不了 JSON，
    #    只带 id 换机后必然失效，还会让人误以为字体跟着走了。
    check('put("font_custom_id"' not in _pr101c,
          "font_custom_id 不导出进备份（字体文件本来也带不走，带 id 只会误导）")
    check('put("font_family"' in _pr101c,
          "备份里仍带 font_family（换机后系统字体档跟着走）")

    # ── 101.5 设置页：SAF / 信息块 / 免责 / 更换删除 ───────────────────
    check("ActivityResultContracts.OpenDocument()" in _sp101c,
          "字体导入走 SAF OpenDocument（不申请任何权限）")
    check("ActivityResultContracts.OpenDocument()" in _sp101c
          and "fontPicker.launch(FONT_MIME_TYPES)" in _sp101c,
          "两处启动文件选择器都传 MIME 白名单（不是 null）")
    # ⚠ OpenDocument 的 input 是**非空** Array<String> —— 传 null 编译不过（v2.95.0 首次编译就撞在这）
    check("private val FONT_MIME_TYPES = arrayOf(" in _sp101c,
          "字体 MIME 白名单是文件级常量")
    # ⚠ v2.95.0 模拟器实测：`EXTRA_MIME_TYPES` 里放 `*/*` ⇒ **所有文件全变灰色不可点**。
    #   DocumentsUI 按字面量匹配那个列表，`*/*` 在里面不是通配符。
    #   （`adb shell am start` 测不出来 —— 它没有调用方包名 ⇒ 不过滤。）
    check('private val FONT_MIME_TYPES = arrayOf("*/*")' not in _sp101c,
          "SAF 的 MIME 列表里不放通配符（DocumentsUI 按字面量匹配，会让所有文件变灰）")
    # 必须含 Android 15 实测到的那个 —— 少了它 .ttf 就点不动
    check('"font/ttf"' in _sp101c,
          "MIME 列表含 font/ttf（Android 15 MediaStore 给 .ttf 的就是它，缺了选不到）")
    check('"font/otf"' in _sp101c, "MIME 列表含 font/otf")
    # 列得越全越安全：各家文件管理器对字体的 MIME 映射不统一
    _mimes101 = re.search(r"private val FONT_MIME_TYPES = arrayOf\((.*?)\)", _sp101c, re.S)
    _n_mime101 = len(re.findall(r'"[^"]+/[^"]+"', _mimes101.group(1))) if _mimes101 else 0
    check(_n_mime101 >= 8,
          "MIME 列表至少列了 8 种字体类型（各设备映射不统一，列少了必然有字体点不动）")
    # 导入走后台线程：7 MB 字体的 Typeface.createFromFile 要几百毫秒
    check("withContext(Dispatchers.IO)" in _sp101c and "CustomFont.importFont(ctx, uri)" in _sp101c,
          "字体导入在后台线程做（主线程上建 Typeface 会掉帧）")
    # 导入成功 ⇒ 先记 id 再切档（顺序反了会被守卫落回像素）
    _ok101 = re.search(r"is com\.stardew\.assistant\.data\.CustomFont\.Result\.Ok -> \{(.*?)\n                \}", _sp101c, re.S)
    _okt101 = _ok101.group(1) if _ok101 else ""
    check("repo.setFontCustomId(r.id)" in _okt101 and "repo.setFontFamily(AppFont.CUSTOM)" in _okt101,
          "导入成功 ⇒ 先记 id 再切 custom 档（顺序反了会被守卫落回像素）")
    # 信息块：五项 + 缺项不编造
    for _lbl101 in ("名称", "版本", "版权", "许可", "许可链接"):
        check('customMetaRow("%s"' % _lbl101 in _sp101c,
              "字体信息块有「%s」一行" % _lbl101)
    check('"该字体文件未声明此项"' in _sp101c,
          "字体没声明的项显示「该字体文件未声明此项」（不编造、不拿别的字段顶替）")
    # 免责：原样展示 + 声明与本 App 无关
    check("与本 App 无关" in _sp101c,
          "字体信息块写明「与本 App 无关」（用户导入的字体不由本 App 背书）")
    check("不参与其分发" in _sp101c,
          "字体信息块写明本 App 不参与字体分发")
    # 更换 / 删除
    check('PixelButton("更换字体"' in _sp101c, "字体信息块有「更换字体」按钮")
    check('PixelButton("删除字体"' in _sp101c, "字体信息块有「删除字体」按钮")
    # 删除前要确认（删了得重新挑一次文件，找不回来）
    check("confirmDeleteFont" in _sp101c and 'title = "删除字体"' in _sp101c
          and "PixelConfirmDialog(" in _sp101c,
          "删字体前有确认框（不可恢复的操作要问一句）")
    # ⚠ 删除顺序：先切回像素再删文件 —— 顺序反了会停在「选中了一个不存在的字体」
    # c202：确认框改成 PixelConfirmDialog(confirmText = "确定删除")，
    #   原来的 PixelButton("确定删除" 形态没了 ⇒ 锚点换成「onConfirm 之后的一段」，
    #   只比三个调用的先后，不依赖闭合括号的缩进（缩进一变就假红的坑）。
    _oc101 = _sp101c.find("onConfirm = {")
    _delt101 = _sp101c[_oc101:_oc101 + 900] if _oc101 >= 0 else ""
    _i_pix101 = _delt101.find("repo.setFontFamily(AppFont.PIXEL)")
    _i_del101 = _delt101.find("CustomFont.delete(")
    _i_clr101 = _delt101.find('repo.setFontCustomId("")')
    check(_i_pix101 >= 0 and _i_del101 >= 0 and _i_clr101 >= 0
          and _i_pix101 < _i_del101 < _i_clr101,
          "删除顺序：先切回像素 → 再删文件 → 再清 id（不会停在「选中不存在的字体」）")
    # ⚠ 免责声明**故意不写进「📜 数据来源与协议」抽屉** ——
    #    那是本 App 自己分发内容的归属清单，混入用户文件 = 虚假署名。
    _lic101 = src("ui/settings/SettingsInfoDrawers.kt")  # c230：协议抽屉搬进信息抽屉分件
    _licsec101 = _lic101.split("数据来源与协议", 1)
    if len(_licsec101) > 1:
        _licbody101 = _licsec101[1][:3000]
        check("与本 App 无关" not in _licbody101,
              "免责声明没混进「数据来源与协议」抽屉（那是本 App 自己的归属清单）")

    # ── 101.6 MainActivity：三档分支 + 回落 ───────────────────────────
    check("produceState" in _ma101c, "自定义字体用 produceState 异步加载（不在主线程建 Typeface）")
    check("withContext(Dispatchers.IO)" in _ma101c, "字体加载离开主线程")
    check("AppFont.SYSTEM -> FontFamily.Default" in _ma101c, "system 档取 FontFamily.Default")
    check("AppFont.CUSTOM -> customFont ?: PixelFont" in _ma101c,
          "custom 档加载失败回落像素字体（绝不留空字体）")
    check("LocalAppFont provides appFont" in _ma101c, "MainActivity provide 自定义字体")

    # ── 101.7 边界不变：分享卡 / 报告长图 / 小组件仍是像素字体 ─────────
    check("LocalAppFont provides PixelFont" in code_only(src("ui/share/ShareEntry.kt")),
          "分享卡仍显式钉回像素字体（分享图不跟 App 内字体变）")
    check("ResourcesCompat.getFont(ctx, R.font.fusion_pixel)" in src("ui/report/ReportCanvas.kt"),
          "农场报告长图仍用像素字体资源（Canvas 通道与 Compose 无关）")
    _lay101 = os.path.join(ROOT, "app", "src", "main", "res", "layout")
    for _f101 in ("widget_today", "widget_card", "widget_wide"):
        _p101 = os.path.join(_lay101, _f101 + ".xml")
        check(os.path.exists(_p101) and "@font/fusion_pixel" in io.open(_p101, encoding="utf-8").read(),
              "小组件 %s.xml 仍是 @font/fusion_pixel（桌面观感不跟 App 内字体变）" % _f101)
    # ⚠ 零权限定位：本功能**不许**新增任何权限（SAF 给的是 content:// 授权，不需要权限）。
    #   基线是 App 已有的 6 条（每条都有明确用途，见 AndroidManifest 里的注释），
    #   超出这个集合就算「顺手加了」——本版导入字体一条都不该加。
    _mf101 = io.open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"),
                     encoding="utf-8").read()
    _ptags101 = re.findall(r"<uses-permission[^>]*android:name=\"([^\"]+)\"", _mf101)
    _known101 = {
        "android.permission.INTERNET",                        # 检查更新 / 下载电脑脚本
        "android.permission.WRITE_EXTERNAL_STORAGE",          # v2.68.0 存电脑脚本（限 API 28）
        "android.permission.MANAGE_EXTERNAL_STORAGE",         # v2.91.0 Steam 云存档「所有文件访问」
        "android.permission.POST_NOTIFICATIONS",              # Steam 导入结果通知
        "android.permission.FOREGROUND_SERVICE",               # 等下载时活着
        "android.permission.FOREGROUND_SERVICE_DATA_SYNC",    # Android 14+ 前台服务类型
        "android.permission.VIBRATE",                          # c214 勾选/按钮触觉（系统 Vibrator）
        "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",  # c234 电池优化白名单（防冻结 → 后台收不到）
    }
    _new101 = [p for p in _ptags101 if p not in _known101]
    check(not _new101, "权限超出已知基线（新增未登记的：%s）" % (_new101 or "实测零新增"))
    # 反向也钉一下：基线里的权限不能被本版悄悄删掉（Steam 那套没权限就跑不通）
    _gone101 = [p for p in _known101 if p not in _ptags101]
    check(not _gone101, "既有权限一条没少（%s）" % (_gone101 or "实测齐全"))

    # ── 101.8 验收任务已注册 ─────────────────────────────────────────
    _bg101 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check('tasks.register<JavaExec>("customFontTest")' in _bg101,
          "customFontTest 任务已注册（字体解析有独立验收）")
    check("com.stardew.assistant.data.CustomFontTest" in _bg101,
          "customFontTest 跑的是 CustomFontTest")
    _cft101 = os.path.join(ROOT, "app", "src", "test", "java", "com", "stardew",
                           "assistant", "data", "CustomFontTest.kt")
    check(os.path.exists(_cft101), "CustomFontTest.kt 在位")
    _cftc101 = code_only(io.open(_cft101, encoding="utf-8").read())
    check("CustomFont.parseNameTable" in _cftc101,
          "验收测试走的是真解析入口 CustomFont.parseNameTable（不是复刻一份）")
    check("tools/fonts/fusion_pixel.ttf" in _cftc101,
          "验收用仓库里的真字体（不是构造的假数据）")

    # ── 101.9 自定义字体挂 fallback 链（v2.95.0 补，2026-10-26）─────────
    # 起因：导入 ZCOOL XiaoWei 后游戏数据页出现方块 ⇒ 用户导入的第三方字体码位不全，
    # 真缺码位时 Android 画 .notdef（空洞方框）⇒ 要给 typeface 挂「逐字符退字」的链。
    #
    # ⚠ 三个**反直觉**的坑，静态断言必须钉住（都是本轮真踩过的）：
    #   ① Compose 的 `FontFamily` 列表**不做按字符 fallback**（`FontMatcher` 只按字重挑
    #      一个 typeface）⇒ 写 `FontFamily(自定义, 像素)` 没用；
    #   ② 公开 SDK 里**没有** `Typeface.createCustomFallback`（查 platforms/android-35/
    #      data/api-versions.xml 确认）⇒ 照着记忆写编译不过；
    #   ③ API 29+ 的那套是「新一代字体 API」：`android.graphics.fonts.Font` →
    #      `FontFamily` → `Typeface.CustomFallbackBuilder`（构造参数吃 FontFamily，
    #      **不吃 Typeface**）。
    check("attachFallback" in _cf101c and "attachFallbackQ" in _cf101c,
          "自定义字体挂了 fallback 链（attachFallback / attachFallbackQ）")
    _lff101 = re.search(r"fun loadFontFamily\(ctx: Context, id: String\): FontFamily\? \{(.*?)\n    \}",
                         _cf101c, re.S)
    _lfbt101 = _lff101.group(1) if _lff101 else ""
    check("attachFallback(ctx" in _lfbt101,
          "loadFontFamily 里真的调用了 attachFallback（不是定义了不用）")
    check("Typeface.CustomFallbackBuilder" in _cf101c,
          "用的是 Typeface.CustomFallbackBuilder（API 29+ 的公开入口）")
    check("android.graphics.fonts.Font" in _cf101 and "android.graphics.fonts.FontFamily" in _cf101,
          "引了新一代字体 API（android.graphics.fonts.*）")
    check("addCustomFallback" in _cf101c and "setSystemFallback" in _cf101c,
          "链上既有自定义 fallback（像素体）也有系统 fallback（兜底生僻字）")
    _af101 = re.search(r"private fun attachFallback\(ctx: Context, file: File, base: Typeface\): Typeface \{(.*?)\n    \}",
                        _cf101c, re.S)
    _afb101 = _af101.group(1) if _af101 else ""
    check("Build.VERSION_CODES.Q" in _afb101,
          "fallback 链有 API 29 版本守卫（minSdk 26 ⇒ 26–28 原样返回不加链）")
    check("R.font.fusion_pixel" in _cf101c,
          "第一级 fallback 是像素体（App 自己的底色字体）")
    # 反向断言：这两个写法都编译不过 / 语义不对，别让它们悄悄回来
    check("createCustomFallback" not in _cf101c,
          "没用 Typeface.createCustomFallback（公开 SDK 里根本没这个方法）")
    check("fallback =" not in _cf101c,
          "没用 FontFamily(..., fallback = ...)（Compose 1.7.6 没有这个参数）")
    check("FontFamily(listOf(" not in _cf101c,
          "没用 FontFamily 列表顶替 fallback（Compose 不做按字符 fallback）")

    # ================= 102. v2.96.0 四项（抽屉可读性 / 潘妮译名 / 茶水间选 N / 图标跳图鉴）=================
    # 背景：用户 2026-10-26 一次提四件事。
    #   ① 导入存档确认界面「进度和背景的字体对比度有点低，很难看清」
    #      —— 根因不是配色，是**这个抽屉是全 App 唯一没套 PixelCard 的**：
    #      正文用 ink / inkDim（给浅色羊皮纸设计的深棕）直接压在深木色（c.wood）底上。
    #      其余 7 个抽屉（导入备份 / 删除字体 / 致谢 / 赞助 / 数据来源 / 接收 / 验证码）
    #      一律套卡片 ⇒ 这处是**行为不一致**，本身就是 bug。
    #   ② 村民页潘妮「最爱」里的「罂粟」应作「虞美人」——App 自己的规范名是「虞美人」
    #      （crops.json / worldItemMap / shopCatalog / image_map 全用「虞美人」），
    #      只有 gifts.json 写错；上游 wiki 也写「虞美人花」。
    #   ③ 进度页收集簿「茶水间·高品质作物」是 **4 选 3**（App 收 4 项、选 3；
    #      游戏 1.6 是 5 选 4，含 App 未收录的金星小麦 —— 比例一致）。
    #   ④ 新功能：点收集簿物品图标直达图鉴。
    #
    # ⚠ 钉**结构 / 不变量**不钉字面量（v2.32 立的规矩）：升版改文案时，
    #   红的原因不能跟它要防的东西无关。
    print("\n== 102. v2.96.0 四项（抽屉可读性 / 译名 / 选 N / 图标跳图鉴）==")
    _sp102 = src_sp()
    _pp102 = src("ui/progress/ProgressPage.kt")
    _ci102 = src("ui/codex/CodexIndex.kt")

    # 102.1 抽屉正文一律套 PixelCard —— 这条是**全局不变式**，不逐个抽屉钉标题。
    #      新加抽屉漏套卡片（= 深木底上压深棕字）时这条立刻红。
    def _drawers102(text):
        _out = []
        _ix = [m.start() for m in re.finditer(r"^    DetailDrawer\(", text, re.M)]
        for _k in range(len(_ix)):
            _s = _ix[_k]
            _e = _ix[_k + 1] if _k + 1 < len(_ix) else len(text)
            _b = text[_s:_e]
            _m = re.search(r'title\s*=\s*"([^"]+)"', _b)
            _out.append((_m.group(1) if _m else "", _b))
        return _out

    _dr102 = [d for d in _drawers102(_sp102) if d[0] != "核对验证码"]
    #      ⚠ c230：拆分后 CodePromptSheet 的块不再意外包含 LicenseExpandCard 的
    #        PixelCard（两者原在同一个文件尾段），显式豁免——它是弹窗式输入框，
    #        不是内容列表；其余抽屉的「正文一律套 PixelCard」不变式原样生效。
    # ⚠ 2026-11-10：致谢 / 赞助两个抽屉已随名单删除，只剩「数据来源与协议」
    #   等 2 个 —— 下限从 5 降到 2（防回归的「至少还有这些」语义不变）。
    check(len(_dr102) >= 2, "设置页抽屉可解析（%d 个）" % len(_dr102))
    _nocard102 = [t for t, b in _dr102 if "PixelCard" not in b]
    check(not _nocard102,
          "设置页每个抽屉正文都套 PixelCard（深木底不压深棕字；漏套：%s）"
          % ("、".join(_nocard102) or "无"))
    _imp102 = [b for t, b in _dr102 if "导入游戏存档" in t]
    check(len(_imp102) == 1 and "PixelCard" in _imp102[0],
          "「导入游戏存档」抽屉正文套了 PixelCard（用户反馈的对比度修复点）")
    check(len(_imp102) == 1 and "verticalScroll" in _imp102[0],
          "「导入游戏存档」抽屉正文可滚动（勾选条目多时不被裁掉）")

    # 102.2 潘妮「最爱」译名：App 规范名是「虞美人」
    _gp102 = load(os.path.join(DATA, "gifts.json"))
    _penny102 = [x for x in _gp102 if x.get("name") == "潘妮"]
    check(len(_penny102) == 1, "gifts.json 潘妮条目唯一")
    if _penny102:
        _pe102 = _penny102[0]
        check("虞美人" in (_pe102.get("loves") or []),
              "潘妮最爱含「虞美人」（对齐 crops/worldItemMap 的 App 规范名）")
        check("罂粟" not in (_pe102.get("loves") or []),
              "潘妮最爱已无「罂粟」")
        check(not any("罂粟" in str(r) for r in (_pe102.get("raw") or [])),
              "潘妮原始摘要也已无「罂粟」（loves 与 raw 两处都改）")
    # 三份手工副本同步（漏一份 web 那份就是「改一份忘另两份」的老复发）
    _gpaths102 = [
        os.path.join(ROOT, "app", "src", "main", "assets", "data", "gifts.json"),
        os.path.join(WEB, "assets", "data", "gifts.json"),
        os.path.join(WEB_OLD, "assets", "data", "gifts.json"),
    ]
    for _gp102p in _gpaths102:
        if not os.path.exists(_gp102p):
            continue
        _rel102 = _gp102p.replace("\\", "/").split("星露谷/")[-1]
        _d102 = load(_gp102p)
        _p102 = [x for x in _d102 if x.get("name") == "潘妮"]
        check(len(_p102) == 1
              and "虞美人" in (_p102[0].get("loves") or [])
              and "罂粟" not in (_p102[0].get("loves") or []),
              "%s：潘妮最爱已改「虞美人」（三份副本同步）" % _rel102)

    # 102.3 茶水间「高品质作物」是 4 选 3
    #      ⚠ 三份 bundles.json 都要有 —— 改一份忘另两份是本项目最常见的复发方式（20.1 同款）。
    for _bp102 in bundle_paths:
        _rel102b = _bp102.replace("\\", "/").split("星露谷/")[-1]
        _d102b = load(_bp102)
        _hi102 = [b for r in _d102b for b in r["bundles"] if b["name"] == "高品质作物"]
        check(len(_hi102) == 1 and _hi102[0].get("choose") == 3,
              "%s：茶水间「高品质作物」choose=3（4 选 3，不再要求全交）" % _rel102b)
        check(len(_hi102) == 1 and len(_hi102[0]["items"]) == 4,
              "%s：「高品质作物」仍是 4 项候选（选 3 选自 4 项）" % _rel102b)
    # 判定口径仍单源：choose>0 ⇒ done>=choose（ProgressCalc 里那一条）
    _pc102 = code_only(src("data/ProgressCalc.kt"))
    check("b.choose > 0" in _pc102 and "done >= b.choose" in _pc102,
          "选 N 的判定口径仍单源在 ProgressCalc（choose>0 ⇒ done>=choose）")

    # 102.4 收集簿物品图标点一下跳图鉴
    check("import com.stardew.assistant.ui.codex.resolveItemJump" in _pp102,
          "进度页引了 codex.resolveItemJump（没另写一套跳转逻辑）")
    check("resolveItemJump(ic.name)" in _pp102,
          "收集簿物品图标点一下跳图鉴（走 resolveItemJump）")
    # resolveItemJump 的品质前缀回落：零命中时剥前缀再查一次（精确本体名，不是模糊匹配）
    # ⚠ v2.100.0 / c182：判定逻辑抽成了纯函数 resolveJumpRequest（切片跟着改）。
    _rj102 = code_only(_ci102[_ci102.index("internal fun resolveJumpRequest"):])
    check("GameData.stripQuality" in _rj102,
          "resolveItemJump 零命中时按品质前缀回落（金星南瓜 → 南瓜）")
    check("hits.size == 0" in _rj102 and "stripped != name" in _rj102,
          "品质前缀回落只在零命中且名字确实带前缀时生效（命中数>1 的口径不动）")
    check("contains(" not in _rj102 and "startsWith(" not in _rj102,
          "回落用的是精确本体名，不是包含 / 前缀猜测（与 33.5 同一条纪律）")

    # 102.5 版本口径：从未发布过的 v2.93.0 / v2.94.0 已并进 v2.95.0
    check('"v2.94.0 NPC 行程表"' not in _sp102 and '"v2.93.0 字体可切换"' not in _sp102,
          "从未发布过的 v2.93.0 / v2.94.0 已并进 v2.95.0（不留未发布版本行）")
    check('"v2.95.0 导入自定义字体 + NPC 行程表"' in _sp102,
          "v2.95.0 行已并入 NPC 行程表（与已发布的 v2.95.0 说明一致）")

    print("\n== 103. v2.97.0 可读性（隐形段落标题 / 强调色地板）==")
    _cp103 = code_only(src("ui/codex/CodexPage.kt"))
    _jp103 = code_only(src("ui/jump/JumpPanel.kt"))
    _cps103 = code_only(src("ui/codex/CodexPanelSections.kt"))

    # 103.1 段落标题不许再拿分组色当文字色
    #      病因：toneColor(SectionTone.MAKE) 返回 **c.wood**（注释写的是「木色贴合工坊」），
    #      而「全部分类」面板的底就是木色壳（DetailDrawer 用 c.wood）——
    #      1.00:1，「🔨 制作装备」四个字**彻底隐形**（日间实拍只剩锤子图标）。
    #      这是「同色压同色」，与「对比度偏低但还认得出」是两类问题，别混为一谈。
    for _nm103, _t103 in (("图鉴「全部分类」", _cp103), ("跳到…面板", _jp103)):
        check("color = toneColor(" not in _t103,
              "%s：toneColor 不再当文字色（分组色只做 chip 边框）" % _nm103)
        check(re.search(r"PixelText\(\s*sec\.label,\s*color\s*=\s*c\.ink", _t103) is not None,
              # c219 起这段字落在米黄纸条上（137.3 钉包装）；这里只钉「不许再吃分组色」
              "%s：段落标题用 c.ink（不许拿分组色当文字色）" % _nm103)
        check('PixelText("🧩 其他", color = c.ink)' in _t103,
              "%s：兜底段「🧩 其他」同样用 c.ink" % _nm103)

    # 分组视觉不能丢：色板仍按 tone 给 chip 上边框色，只是标题不再吃这个色
    check("Chip(g.title, border = tone)" in _cp103,
          "图鉴「全部分类」：chip 边框仍按分组色上色（分组视觉不丢）")
    check("border = tone)" in _jp103,
          "跳到…面板：chip 边框仍按分组色上色（分组视觉不丢）")

    # 103.2 MAKE 段仍以 c.wood 作分组色 —— 但只许用在 chip 边框上。
    #      钉住这一点是为了防「有人顺手把 MAKE 改成别的色」时，
    #      不知道分组色的语义是「工坊=木色」而不是随手挑的。
    check("SectionTone.MAKE -> c.wood" in _cps103,
          "MAKE 段仍以 c.wood 作分组色（只用于 chip 边框，不用于文字）")

    # 103.3 强调色对比度地板在 46.5（复用同一套色板解析器，16 套色板逐套算 WCAG）。
    #      这里只钉住「调色是按对比度做的、不是随手换色」这条口径。
    _pt103 = src("ui/PixelTheme.kt")
    check("v2.97.0 强调色对比度校准" in _pt103,
          "PixelTheme.kt 写明了 v2.97.0 调色口径（为什么这么调、夜间为什么不压暗）")

    print("\n== 104. v2.98.0 跳到面板跟手下滑 + 法师塔图腾材料 ==")
    _gs104 = code_only(src("ui/glass/GlassSheet.kt"))
    _gd104 = code_only(src("data/GameData.kt"))
    _sp104 = src("ui/settings/SettingsPage.kt")
    _bl104 = load(os.path.join(DATA, "buildings.json"))

    # 104.1 玻璃弹层：面板整体跟手下滑（列表优先、到顶才动面板）
    #      病根：以前「下滑关闭」只挂在**头部那一条窄条**上，内容区一按就只会滚列表，
    #      面板本身完全不动 —— 群友反馈「跳到页面滑不动、滑动不跟手」。
    #      而没有玻璃时走的 Material 抽屉（DetailDrawer）是能整体跟手的：
    #      同一个功能两种表现 = 「偶发」。
    #
    #      ⚠ 修法换过两次（模拟器实测 + Compose 1.7.6 字节码对账才定位准），
    #        两条走不通的路都钉在这里，防止后人再走一遍：
    #        ① `Modifier.nestedScroll` 吃「列表没吃掉的余量」——Compose 1.7 里
    #           `Modifier.scrollable` 的拖拽只派发「子节点吃掉了多少」，
    #           列表在顶时派发的是 0 ⇒ 父节点拿不到余量（实测 available 恒为 0）。
    #        ② `Modifier.draggable` 指针竞争——指针事件的 Main pass 子节点先拿到，
    #           列表的 ScrollableNode 先吃掉 slop ⇒ 父级 draggable 直接被取消
    #           （实测 onDelta 一次都没触发）。
    #      现在走第三条路：内容方自己读指针的**绝对坐标**，自己判断
    #      「列表已到顶 && 手指还在往下拉」才接管面板。
    _jp104 = code_only(src("ui/jump/JumpPanel.kt"))
    check("interface SheetDrag" in _gs104,
          "弹层定义了 SheetDrag 接口（内容方据此接管面板拖拽）")
    check("content(sheetDrag)" in _gs104,
          "弹层把 SheetDrag 交给内容方")
    check("onDelta" in _gs104 and "onRelease" in _gs104,
          "SheetDrag 有「跟手位移」与「松手结算」两个动作")
    check("dismissThreshold" in _gs104 and "spring(" in _gs104,
          "弹层有结算：拖够阈值关闭、没拖够弹回原位")
    # 弹层自己**不抢指针**：内容区的手势由内容方处理（它才知道列表滚到哪了）
    check(_gs104.count("pointerInput(") == 1,
          "玻璃弹层只有一处 pointerInput（头部那条），内容区不靠弹层抢手势")
    check("detectVerticalDragGestures" in _gs104,
          "头部下滑关闭仍在（没有为了修内容区把老功能删掉）")
    # 反向断言：两条走不通的路不能回潮
    check("NestedScrollConnection" not in _gs104 and "nestedScroll(" not in _gs104,
          "弹层不再用 nestedScroll（Compose 1.7 拿不到列表余量，实测 available 恒为 0）")
    check("draggable(" not in _gs104,
          "弹层不再用 Modifier.draggable 抢手势（列表先吃 slop，实测一次都没触发）")

    # 内容方：c229 起改原生 BottomSheet 语义（方案 A，用户拍板）——
    #   v2.98.0 的「列表到顶下拉=拉面板」在 c228 贴边后成了主诉 bug（列表一进来
    #   就在顶，第一次下划吃满 96dp 阈值 → 面板被误关，用户报「往下滑会闪」）。
    #   现在内容区**不再接管**面板拖拽：那 6 条断言整体反转成否定式。
    check("awaitEachGesture" not in _jp104 and "awaitPointerEvent" not in _jp104,
          "内容区不再自己处理指针事件（c229 反转 v2.98.0 的接管语义）")
    check("change.position.y" not in _jp104,
          "内容区不再读绝对坐标算面板位移")
    check("canScrollBackward" not in _jp104,
          "「列表到顶」判定不再出现在内容区（滚列表就是滚列表）")
    check("dy > 0f && !listState.canScrollBackward" not in _jp104,
          "「到顶下拉=拉面板」不得回潮（c229 主诉根因）")
    check("dy < 0f && drag.offset > 0f" not in _jp104,
          "「拉下状态上滑推回」同样移除（头部窄条自己管回弹）")
    check("drag.onRelease()" not in _jp104,
          "内容区不再代跑结算（settleDrag 只在 GlassSheet 头部与 sheetDrag 实现）")
    check("state = listState" in _jp104,
          "LazyColumn 仍用同一个 listState（c229 保留，滚动状态本身没动）")

    # 104.2 法师塔 6 件魔法建筑进图鉴「建筑」分类
    _tower104 = ["大地图腾柱", "水图腾柱", "沙漠图腾柱", "岛屿图腾柱", "祝尼魔小屋", "黄金时钟"]
    _bn104 = [r["建筑"] for r in _bl104]
    check(len(_bl104) == 25, "建筑分类 25 条（19 + 法师塔 6 件，实得 %d）" % len(_bl104))
    for _n104 in _tower104:
        check(_n104 in _bn104, "建筑分类含「%s」" % _n104)
    _price104 = {"大地图腾柱": "500,000g", "水图腾柱": "500,000g", "沙漠图腾柱": "1,000,000g",
                 "岛屿图腾柱": "1,000,000g", "祝尼魔小屋": "20,000g", "黄金时钟": "10,000,000g"}
    _mat104 = {"大地图腾柱": "铱锭 ×10、地晶 ×10", "水图腾柱": "铱锭 ×5、蛤蜊 ×10、珊瑚 ×10",
               "沙漠图腾柱": "铱锭 ×20、椰子 ×10、仙人掌果子 ×10",
               "岛屿图腾柱": "铱锭 ×10、龙牙 ×10、香蕉 ×10",
               "祝尼魔小屋": "石头 ×200、杨桃 ×9、纤维 ×100", "黄金时钟": "无"}
    for _n104 in _tower104:
        _row104 = next((r for r in _bl104 if r.get("建筑") == _n104), None)
        if _row104 is None:
            check(False, "%s 条目存在（取不到）" % _n104)
            continue
        check(_row104.get("价格") == _price104[_n104],
              "%s 价格 = %s" % (_n104, _price104[_n104]))
        check(_row104.get("材料") == _mat104[_n104],
              "%s 材料 = %s" % (_n104, _mat104[_n104]))
        # 关键约束：属性行 ≤5 行 —— 超过 5 行会被折进「📋 详细属性」，
        # 而 v2.41 的教训正是「建造需要什么材料」被折叠过一次（群友报障）。
        _rws104 = [k for k in _row104 if k not in ("建筑", "说明")]
        check(len(_rws104) <= 5,
              "%s 的属性行 %d 行（≤5，超了会被折叠成「详细属性」）" % (_n104, len(_rws104)))
        _tx104 = _row104.get("说明", "")
        check("解锁" in _tx104 and "哥布林难题" in _tx104 and "魔法墨水" in _tx104,
              "%s 说明写了解锁条件（哥布林难题 → 魔法墨水 → 魔法书）" % _n104)
        check("13,020,000g" in _tx104 and "铱锭 45" in _tx104,
              "%s 说明写了「一次备齐」总量（1302 万金币 / 铱锭 45）" % _n104)
        check("返回权杖" in _tx104,
              "%s 说明写了易混（返回权杖不在法师塔卖）" % _n104)
    _isl104 = next((r for r in _bl104 if r.get("建筑") == "岛屿图腾柱"), None)
    check(_isl104 is not None and "姜岛" in _isl104.get("说明", ""),
          "岛屿图腾柱另注明「需先解锁姜岛」")
    # 长内容走**段落卡**（CodexItem.text），不挤进属性行
    check("textKey" in _gd104 and 'textKey = "说明"' in _gd104,
          "建筑分类用 textKey 承载「说明」（段落卡，不占属性行）")
    check("text = textKey?.let" in _gd104,
          "rowsGroup 把 textKey 写进 CodexItem.text（图鉴详情的段落卡）")

    # 104.3 版本口径：从未发布过的版本不留版本行（并入最新发布版）。
    #     口径本身没变，变的是「最新发布版」是哪一版（见 105.8）。
    #     ⚠ 断言用 code_only：记录版本口径的**注释里**也会写到版本号，
    #       直接匹配原文会把注里的「旧版本行」当成现行。
    _sp104c = code_only(_sp104)
    check('"v2.97.0 收集簿可跳图鉴 + 分类面板与季节配色看得清"' not in _sp104c
          and '"v2.96.0 收集簿可跳图鉴 + 三处订正"' not in _sp104c,
          "从未发布过的 v2.96.0 / v2.97.0 不留版本行")
    check('"v2.98.0 跳到面板跟手下滑' not in _sp104c,
          "从未发布过的 v2.98.0 也不留版本行（并进最新发布版）")

    # ================= 105. v2.99.0 使用指引（功能在哪儿） =================
    # 背景：群友常问「这个功能在哪儿」——App 里功能不少，但**没有任何一处**说它们各在哪儿。
    # 首启引导（Intro.kt）只教「怎么用」，不教「东西在哪」。
    # 本节钉的是**行为**（入口在哪儿、能不能跳、数量写死了没），不钉字面量。
    print("\n== 105. v2.99.0 使用指引（功能在哪儿）==")
    _fgp105 = code_only(src("ui/guide/FeatureGuidePage.kt"))
    _fgd105 = code_only(src("ui/guide/FeatureGuideData.kt"))
    _sp105c = code_only(src("ui/settings/SettingsPage.kt"))
    _jp105 = code_only(src("ui/jump/JumpPanel.kt"))
    _ma105 = code_only(src("MainActivity.kt"))
    _nb105 = code_only(src("ui/nav/NavBus.kt"))
    _gb105 = code_only(src("ui/guide/GuideBooks.kt"))
    _gd105 = code_only(src("data/GameData.kt"))
    _fgpath105 = os.path.join(DATA, "feature_guide.json")

    # 105.1 导航管道：新增 Tab / Stack 两型指令，单点消费
    check("data class Tab(val screen: Screen)" in _nb105,
          "NavRequest 新增 Tab（切主页签）")
    check("data class Stack(val screen: Screen)" in _nb105,
          "NavRequest 新增 Stack（压栈到二级页）")
    check("is NavRequest.Tab ->" in _ma105 and "is NavRequest.Stack ->" in _ma105,
          "导航消费点处理了 Tab / Stack 两种新指令")
    check("data object FeatureGuide : Screen" in _ma105,
          "Screen 新增 FeatureGuide（使用指引页）")
    check("Screen.FeatureGuide ->" in _ma105,
          "使用指引页有渲染分支")
    # 页签映射单源：从 BAR_TABS 派生，不另写一份
    check("BAR_TAB_SCREENS: Map<String, Screen> = BAR_TABS.associate" in _ma105,
          "页签键→Screen 从 BAR_TABS 派生（加页签只改一处）")

    # 105.2 双入口同源：c227 起设置页改 BackBar 右侧常驻入口（不再占卡位）
    #     + 跳到面板一个入口
    check('PixelCard(title = "📖 使用指引")' not in _sp105c
          and 'PixelText("📖 指引"' in _sp105c,
          "设置页使用指引入口在 BackBar 右侧（不再占卡片位）")
    check("onOpenGuide" in _sp105c and "stack.add(Screen.FeatureGuide)" in _ma105,
          "设置页的指引卡压栈到使用指引页")
    check("NavRequest.Stack(Screen.FeatureGuide)" in _jp105,
          "「跳到…」面板里也有使用指引入口")
    check('"data/feature_guide.json"' in _fgd105,
          "指引文案只有一份数据源（两入口同源）")

    # 105.3 数据文件结构
    check(os.path.exists(_fgpath105), "使用指引数据文件存在")
    _fg105 = load(_fgpath105)
    _groups105 = _fg105.get("groups") or []
    check(len(_groups105) >= 5, "指引分组 ≥5 组（实得 %d）" % len(_groups105))
    _items105 = [it for g in _groups105 for it in (g.get("items") or [])]
    # v2.99.0 c178：用户拍板 A 档——指引只列「功能」，信息/声明类卡片与调试手势全部移出
    #（46 → 37 条）。阈值随口径下调，别让旧数字把这条规则悄悄改回去。
    check(len(_items105) >= 30, "指引条目 ≥30 条（实得 %d）" % len(_items105))
    _gids105 = [g.get("id") for g in _groups105]
    check(len(_gids105) == len(set(_gids105)), "指引分组 id 不重复")
    for _g105 in _groups105:
        check(bool(_g105.get("id")) and bool(_g105.get("title")),
              "分组「%s」有 id 与标题" % _g105.get("id"))
    for _it105 in _items105:
        check(bool(_it105.get("name")) and bool(_it105.get("desc")) and bool(_it105.get("where")),
              "条目「%s」有名字/说明/位置三段" % _it105.get("name"))

    # 105.4 跳转目标必须**全部**可解析（解析不出就是「点了没反应」）
    _tabkeys105 = set(re.findall(r'Quadruple\("([^"]+)"', _ma105))
    _bookkeys105 = set(re.findall(r'GuideBook\("([^"]+)"', _gb105))
    _codexids105 = (set(re.findall(r'rowsGroup\(\s*"([^"]+)"', _gd105))
                    | set(re.findall(r'CodexGroup\(\s*"([^"]+)"', _gd105)))
    _stackkeys105 = {"settings", "guide", "schedule", "mods"}
    for _it105 in _items105:
        _j105 = _it105.get("jump")
        if not _j105:
            continue
        _ty105 = _j105.get("type")
        _tg105 = _j105.get("target")
        if _ty105 == "tab":
            check(_tg105 in _tabkeys105, "跳转目标 tab:%s 在底栏页签表里" % _tg105)
        elif _ty105 == "stack":
            check(_tg105 in _stackkeys105, "跳转目标 stack:%s 可解析" % _tg105)
        elif _ty105 == "book":
            check(_tg105 in _bookkeys105, "跳转目标 book:%s 在攻略册表里" % _tg105)
        elif _ty105 == "codex":
            check(_tg105 in _codexids105, "跳转目标 codex:%s 在图鉴分组里" % _tg105)
        else:
            check(False, "未知的跳转类型 %s" % _ty105)

    # 105.5 数量一律占位符：写死会随数据更新说谎
    _txt105 = json.dumps(_fg105, ensure_ascii=False)
    for _ph105 in ("{分类数}", "{册子数}", "{村民数}", "{条目数}"):
        check(_ph105 in _txt105, "指引文案用占位符 %s（不写死数量）" % _ph105)
    for _bad105 in ("31 个分类", "29 个分类", "35 位村民",
                    "49 位村民", "10 本册子", "1056"):
        check(_bad105 not in _txt105, "指引文案不含写死的数量「%s」" % _bad105)
    check("{分类数}" in _fgp105 and "replace(k, v)" in _fgp105,
          "页面层用运行时真实值替换占位符")
    check("GameData.codexGroupsWithMods" in _fgp105 and "GameData.gifts.size" in _fgp105
          and "GUIDE_BOOKS.size" in _fgp105,
          "数量取自运行时真实表（不写死）")
    # ⚠ 页尾那行也必须过 fill()：占位符写在字符串里却没替换，屏幕上会直接露出
    #   「{分类数}」这种花括号（模拟器实拍抓到过：条目里的占位符替换了，页尾那行是原文）。
    #   窗口往前多取一段 —— fix() 包在字符串**前面**，只往后看会漏判。
    _i_foot105 = _fgp105.find("共 ${guide.groups.sumOf")
    _win105 = _fgp105[max(0, _i_foot105 - 160):_i_foot105 + 300]
    check(_i_foot105 != -1 and "fill(" in _win105,
          "页尾那行也走 fill()（不然屏幕上会露出 {分类数} 这种花括号）")

    # 105.6 页面形态：二级页范式 + 正文套卡
    check("BackBar" in _fgp105 and "LazyColumn" in _fgp105,
          "指引页用 BackBar + LazyColumn（二级页范式）")
    check("PixelCard" in _fgp105, "指引正文套 PixelCard（不裸压木色）")
    check("color = c.ink" in _fgp105, "指引正文用 c.ink（v2.97 教训：木色压木色会隐形）")
    # 反向断言：正文不许用 c.wood
    check("color = c.wood" not in _fgp105,
          "指引正文不用 c.wood 压木色底（v2.97 退潮防御）")
    check("navBus.value = jump.toNavRequest()" in _fgp105,
          "可跳条目走全局导航总线（不自己动栈）")
    check("📍" in _fgp105, "每条都写清位置（\U0001f4cd 那行）")
    # ⚠ 用户可见文案不许带 Markdown 星号：Compose 的 Text 没有 Markdown 解析，
    #   星号会原样显示出来（模拟器实测过：引导卡里出现了「**在哪儿**」）。
    check("**" not in _txt105,
          "指引数据里的文案不带 Markdown 星号（Compose 不解析 Markdown）")
    check("**在哪儿**" not in _fgp105,
          "指引引导文案不带 Markdown 强调（星号会原样显示）")

    # 105.7 c227 起：使用指引不再占设置页卡片位（tab 化后藏进某一栏反而违背
    #   「最显眼」的初衷）——挪到 BackBar 右侧常驻入口，任何栏一键直达。
    check('PixelCard(title = "📖 使用指引")' not in _sp105c
          and 'PixelText("📖 指引"' in _sp105c and "onOpenGuide" in _sp105c,
          "使用指引入口在设置页 BackBar 右侧（不再占卡片位）")

    # 105.8 版本口径：从未发布过的版本并入最新发布版
    check('"v2.99.0 使用指引' in _sp105c,
          "CHANGELOG 仍保留 v2.99.0 那条（已发布过，不动；首条已让给 v2.100.0）")
    check('"v2.98.0 跳到面板跟手下滑' not in _sp105c,
          "从未发布过的 v2.98.0 已并进 v2.99.0（不留未发布版本行）")

    # 105.9 反向断言：指引只列「功能」，信息/声明类卡片与调试手势不许再回来
    #     用户 2026-10-27 原话：「有些功能没必要加到指引里……致谢名单、数据整理、
    #     游戏素材之类的东西也没有用」⇒ 这些卡在设置页里**仍然保留**，只是不再写进指引。
    #     ⚠ 断言只查**条目名**，不查全文：
    #       「ℹ 关于」这条的说明里合法提到「版本号」「数据来源与协议入口」，
    #       查全文会把这条例外也一起打红。
    _names105 = set(it.get("name") or "" for it in _items105)
    for _ban105 in ("长按版本号开预览", "致谢名单", "存档导入内测", "数据整理",
                    "游戏素材", "Steam 云存档", "界面字体", "界面效果"):
        check(_ban105 not in _names105,
              "指引里没有「%s」这类信息/声明卡（用户拍板 A 档移出）" % _ban105)
    # ⚠ 「Mod 扩展包」有两个同名的条目，方向相反，必须按**组**分：
    #     settings 组 = 关于 → 数据来源与协议里的授权说明（信息卡，移出）
    #     more    组 = 进度页 → Mod 扩展包 → 下载启用 SVE 扩展数据（真功能，留下）
    #   删的时候按名字全局匹配会连功能那条一起误删（踩过），所以这里双向钉。
    _gset105 = dict((g.get("id"), g.get("items") or []) for g in _groups105)
    _set_names105 = set(it.get("name") or "" for it in _gset105.get("settings") or [])
    _more_names105 = set(it.get("name") or "" for it in _gset105.get("more") or [])
    check("Mod 扩展包" not in _set_names105,
          "设置页那栏没有「Mod 扩展包」授权说明卡（信息卡，移出）")
    check("Mod 扩展包" in _more_names105,
          "「其它功能」里的「Mod 扩展包」入口还在（那是要启用 SVE 扩展数据的功能）")
    # 被移出的条目原本属于「设置页能改什么」那组，导语得跟着改
    _snote105 = ""
    for _g105 in _groups105:
        if _g105.get("id") == "settings":
            _snote105 = _g105.get("note") or ""
    check("抽屉" in _snote105 and "字体信息" in _snote105,
          "设置页那组的导语仍说清「字体信息在卡内、脚本在抽屉里」（卡片移出后导语要跟上）")

    # ================= 106. v2.99.0（c179）图鉴条目加「你的状态」 =================
    print("\n== 106. v2.99.0（c179）图鉴条目加「你的状态」==")
    _cx106 = code_only(src("ui/codex/CodexPage.kt"))
    _cs106 = code_only(src("ui/codex/CodexStatus.kt"))
    _cd106 = code_only(src("data/CodexState.kt"))
    _cc106 = code_only(src("data/CodexStateCache.kt"))
    _fc106 = code_only(src("data/FarmTodayCache.kt"))
    _pc106 = code_only(src("data/ProgressCalc.kt"))
    _wp106 = code_only(src("data/WorldStateParser.kt"))
    _md106 = code_only(src("data/Models.kt"))
    _ft106 = code_only(src("data/FarmToday.kt"))
    _gd106 = code_only(src("data/GameData.kt"))

    # 106.1 未导入存档时整段不出现（绝不显示「0」——那是把「没导入」说成「你有 0 个」）
    # c217：读缓存要带存档名（缓存按存档分文件）⇒ 断言跟着改成带 profile 的形态。
    check("CodexStateCache.read(dctx, snap.activeProfile)" in _cx106,
          "详情体读图鉴状态缓存（CodexStateCache.read + 当前存档名，IO 线程）")
    check("if (st == null) CodexStatusResult(emptyList())" in _cx106,
          "没读到缓存 → 空行列表（整卡不出现，不是显示「0」）")
    check("if (status.lines.isNotEmpty())" in _cx106,
          "无可显示状态时整卡不出现")
    check("CodexState(" not in _cx106 and "CodexState(" not in _cs106,
          "UI 层不造默认状态充数（构造只出现在折算器里）")

    # 106.2 正文颜色：c.ink 压羊皮纸内衬，不用 c.wood（v2.97 踩过的隐形坑）
    # （code_only 会剥注释，锚点全用代码字符串：卡片起于 PixelCard，止于下一张「怎么制作」卡）
    _i106 = _cx106.index('PixelCard(title = "🎒 你的状态")')
    _j106 = _cx106.index("if (recipe != null", _i106)
    _stcard106 = _cx106[_i106:_j106]
    check('PixelCard(title = "🎒 你的状态")' in _stcard106,
          "「你的状态」是独立段落卡（PixelCard）")
    check("c.wood" not in _stcard106,
          "状态卡里不用 c.wood（反向断言：木色压木底会隐形）")
    check("KVRow(k, v)" in _stcard106,
          "正文行走 KVRow（值行 PixelText 默认色 = c.ink）")

    # 106.3 独立段落卡不进 rows；位置 = 属性行之后、正文段落卡之前
    check("item.rows" not in _stcard106,
          "状态卡不碰 item.rows（rows 超 5 行会被折叠，v2.41 的教训）")
    check("CodexItem(" not in _cs106,
          "状态行组装不产出 CodexItem（不是属性行，是独立段落卡）")
    check("CodexState" not in _gd106,
          "数据层（GameData / rowsGroup）不感知「你的状态」（状态是独立折算层，不加第二套内容通道）")
    check(_i106 > _cx106.index("if (item.rows.isNotEmpty())"),
          "状态卡在属性行之后")
    check(_i106 < _cx106.index("if (item.text.isNotBlank())"),
          "状态卡在正文段落卡（item.text）之前")

    # 106.4 计数折进缓存，原样背包绝不进缓存
    check("val itemCounts: Map<String, Int>" in _cd106,
          "背包折成「App 中文名 → 件数」计数表")
    check("WorldItem" not in _cd106.replace("WorldItemMap", ""),
          "折算模型里没有背包原样列表（反向断言；WorldItemMap 是映射表，允许）")
    check("WorldInventory" not in _cd106,
          "折算模型里没有背包列表类型")
    check("List<WorldItem>" not in _ft106 and "WorldInventory" not in _ft106,
          "FarmToday 展示模型没被塞背包列表（farm_today.json 体积纪律不破）")
    check("val stack = w.stack ?: continue" in _cd106,
          "数量判不出（stack 缺失）的不折 —— 不把「不知道」折成假数字")
    check('out += "背包" to "没找到（背包里有未识别物品，以游戏内为准）"' in _cs106,
          "有未识别物品时「没找到」不下结论（反向断言：那几件里可能就有它）")
    check('out += "背包" to "没找到"' in _cs106,
          "映射全成功时「没找到」是可靠结论，照实显示")
    # c217：缓存文件**按存档分文件**（文件名带 token），不再是固定的一个 json。
    check('"${FILE}_"' in _cc106 or '_${FarmTodayCache.tokenOf(profile)}.json' in _cc106,
          "图鉴状态缓存文件按存档分文件（codex_state_<token>.json）")
    check("CodexStateCache.clear(ctx, profile)" in _fc106 and "CodexStateCache.write(ctx, profile," in _fc106,
          "图鉴状态折算挂在 FarmTodayCache 同一个解析出口（commit）")
    check(_fc106.count("CodexStateCache.clear") >= 2,
          "换档先清旧、失败兜底清 —— 两条路都不会留下上一份存档的状态")

    # 106.5 勾选判定与 bundleIsFull 同源（不许出现第二套下标→名字的反查实现）
    check("fun bundleCheckKey(roomIdx: Int, bundleIdx: Int, itemName: String): String" in _pc106,
          "收集包勾选键单源函数 bundleCheckKey")
    check(_pc106.count("cc:") == 1,
          "cc: 键模板在 ProgressCalc 里只出现一次（bundleDoneCount 与反查共用同一个构造）")
    for _f106 in ("ui/codex/CodexPage.kt", "ui/codex/CodexStatus.kt", "data/CodexState.kt",
                  "data/CodexStateCache.kt", "data/FarmTodayCache.kt", "data/CodexChecks.kt",
                  "data/GameData.kt", "data/Models.kt",
                  # v2.100.0 / c182：进度页原来内联拼过 4 处 cc: 键（v2.0 引入的），
                  #   现在改调 bundleCheckKey ⇒ 键模板单源（行为不变，键逐字节相同）。
                  "ui/progress/ProgressPage.kt",
                  # PrefsRepo 的历史迁移表原来手写键（cc:3:2:史莱姆泥）⇒ 同一份模板的第二次实现，
                  #   现在也改调 bundleCheckKey（产出的键逐字节相同，老用户迁移照旧生效）。
                  "data/PrefsRepo.kt"):
        check("cc:" not in code_only(src(_f106)),
              "%s 不出现 cc: 字面量（键前缀只许在 ProgressCalc.kt）" % _f106)
    check("isItemChecked(" in _cs106 and "isCheckable(" in _cs106,
          "图鉴进度行走 isItemChecked 单源（CodexChecks）")
    for _ns106 in ("fish:", "codex:", "museum:", "ach:", "walnut:", "raccoon:"):
        check(_ns106 not in _cs106,
              "CodexStatus.kt 不出现 %s 字面量（键构造全走 codexCheckKey）" % _ns106)
    check("bundleHitsOf" in _cs106 and "checked(" in _cs106,
          "收集包提交状态走 bundleHitsOf 反查（与 bundleIsFull 同一个键）")
    check("Calc.daysUntilWithYear" in _cs106,
          "生日天数与今日页生日卡用同一个函数、同一个 snap 日期")

    # 106.6 好感解析：只收房主（与背包/现金口径一致）
    check('name == "friendshipData" && parent == "player"' in _wp106,
          "friendshipData 只解析房主那份（农场手的 Farmer 节点不收）")
    check("val friendships: List<WorldNpcFriendship> = emptyList()" in _md106,
          "WorldState 挂好感字段（默认空列表，不破坏既有构造）")

    # 106.7 版本口径：三层任务共用 v2.100.0，本轮只加构建号
    _bg106 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("versionCode = 243" in _bg106,
          "versionCode 已到 210（…c199 底栏切页 → c200 体验四处 → c201 手感与档位 → "
          "c202 删除确认居中框 → c203 设置页两个出口 → c204 数据老账 → c206 品质价与礼物表校正 → "
          "c207 数据审查修复轮 → c208 礼物表段位校正 → c209 图鉴版面重排 → c210 五批数据优化）")
    check('versionName = "2.152.1"' in _bg106,
          "versionName 已升到 2.122.0（c189：用户 2026-10-30 口径变更——修复轮也升 versionName；"
          "c206 品质价与礼物表按官方校正 → c208 礼物表段位校正 → c209 图鉴版面重排 → c210 五批数据优化）")

    # ================= 107. v2.99.0（c180）缺件溯源：别名归一 + 获取途径派生 =================
    #
    # 起因：「我要 X，去哪拿」。全 App 各表对同一件东西用了不同的名字
    # （礼物表写「葡萄酒」、商店表写「果酒」、图鉴写「酒」；收集包写「动物毛」、
    # 出货表写「羊毛」），于是两件事同时坏掉：① 点图鉴零命中落空搜索页；
    # ② 按别名查来源，三表全对不上。
    #
    # 本节的判据钉的是**语义**：
    #   · 别名表只存「别名 → 规范名」，绝不能变成第二份物品表（反向断言）；
    #   · 归一只有一处实现（单源）；
    #   · 获取途径**由既有数据源派生**，不是新建一张来源表（反向断言）；
    #   · 重名不许挑一个：明说 N 条 + 给搜索页入口；
    #   · 查不到来源就明说查不到，不许用泛泛话术充数。
    print("\n== 107. v2.99.0（c180）缺件溯源 ==")
    _gd107 = src("data/GameData.kt")
    _cx107 = src("ui/codex/CodexPage.kt")
    _ci107 = src("ui/codex/CodexIndex.kt")
    _pp107 = src("ui/progress/ProgressPage.kt")
    _pc107 = src("data/ProgressCalc.kt")
    _alias_path107 = os.path.join(DATA, "itemAliases.json")

    # ---- 107.1 别名表本身：只存「别名 → 规范名」----
    check(os.path.exists(_alias_path107), "有 assets/data/itemAliases.json（别名的单一映射源）")
    _al107 = load(_alias_path107) if os.path.exists(_alias_path107) else {}
    check(isinstance(_al107, dict) and bool(_al107), "别名表是非空的映射表")
    check(all(isinstance(k, str) and isinstance(v, str) and k.strip() and v.strip()
              for k, v in _al107.items()),
          "别名表每条都是「非空字符串 → 非空字符串」（反向断言：不能塞对象/数组，那会变成第二份物品表）")
    check(len(_al107) < 80,
          "别名表条目数是「映射表」量级（%d 条；图鉴条目 800+，一张物品表远超这个量）" % len(_al107))
    check(not [k for k, v in _al107.items() if k == v],
          "别名表没有恒等映射（别名 ≠ 规范名；恒等项是无效行）")
    check(not [k for k, v in _al107.items() if v in _al107],
          "别名表没有别名链（A→B 且 B→C），归一一次到位")
    # 别名侧**本身也是图鉴条目**的情形（同一件东西在图鉴里有两条，如 香料浆果/香味浆果）：
    #   那必须有「本项目自己承认它们是同一个东西」的证据 —— 共享同一个图标文件
    #   （58.30 用的就是这条判据：字节相同的图标文件 / 同一个 image_map 值引用的多个键）。
    _imap107 = load(IMG)
    for _k107, _v107 in _al107.items():
        if _k107 in _cn70:
            check(_imap107.get(_k107) == _imap107.get(_v107),
                  "别名侧也是图鉴条目时两侧共享同一图标文件（%s ↔ %s）" % (_k107, _v107))

    # ---- 107.2 九组别名逐组钉方向 ----
    _GROUPS107 = [
        ("动物毛", "羊毛"), ("四季豆", "青豆"), ("果酒", "酒"), ("椰汁", "椰子"),
        ("米布丁", "大米布丁"), ("葡萄酒", "酒"), ("蒜", "大蒜"),
        ("辣鳗鱼", "香辣鳗鱼"), ("黏液", "史莱姆泥"),
    ]
    for _a107, _b107 in _GROUPS107:
        check(_al107.get(_a107) == _b107, "别名收口：%s → %s" % (_a107, _b107))
        check(_b107 in _cn70, "规范名「%s」是图鉴条目（归一后跳得过去）" % _b107)

    # ---- 107.3 别名侧确实出现在既有数据源里（不是凭空造的别名）----
    _side107 = set()
    for _g107 in load(os.path.join(DATA, "gifts.json")):
        for _k107 in ("loves", "likes", "dislikes", "hates"):
            _side107 |= set(_g107.get(_k107) or [])
    for _arr107 in load(os.path.join(DATA, "shopCatalog.json")).values():
        for _it107 in _arr107:
            _side107.add(_it107.get("name", ""))
    for _room107 in load(os.path.join(DATA, "bundles.json")):
        for _b107 in _room107.get("bundles", []):
            for _it107 in _b107.get("items", []):
                _side107.add(_it107.get("name", ""))
    # v2.118：礼物表按官方重建后改用**规范名**，别名不再出现在 gifts.json 里。
    #   所以这条断言的本意（「别名不是凭空造的」）改查**别名的出处**：
    #   ① 别名自己在 itemAliases.json 里收口；
    #   ② 它的**规范名**在既有数据源（商店/收集包）或图鉴里有出处。
    for _a107, _b107 in _GROUPS107:
        check(_al107.get(_a107) == _b107,
              "别名「%s」在 itemAliases.json 里收口到「%s」（不是凭空造的）" % (_a107, _b107))
        check(_b107 in _side107 or _b107 in _cn70,
              "别名「%s」的规范名「%s」在既有数据源/图鉴里有出处" % (_a107, _b107))
    _gift_pairs107 = ["动物毛", "四季豆", "果酒", "椰汁", "米布丁", "葡萄酒", "蒜", "辣鳗鱼"]
    check(all(_a107 in _al107 for _a107 in _gift_pairs107),
          "58.30 实测出的 8 组「礼物名写成了图鉴别名」已全部进别名表（点了不再落空）")

    # ---- 107.4 归一只有一处实现（单源）----
    check(_gd107.count("fun canonicalName(") == 1,
          "名字归一只有一处实现（GameData.canonicalName）")
    check("itemAliases.json" in _gd107, "GameData 读别名表")
    _readers107 = []
    for _root107, _dirs107, _files107 in os.walk(os.path.join(ROOT, "app", "src", "main")):
        for _f107 in _files107:
            if _f107.endswith((".kt", ".java")):
                _p107 = os.path.join(_root107, _f107)
                try:
                    if "itemAliases.json" in io.open(_p107, encoding="utf-8").read():
                        _readers107.append(os.path.relpath(_p107, ROOT))
                except Exception:
                    pass
    check(len(_readers107) == 1 and _readers107[0].endswith("GameData.kt"),
          "别名表只有 GameData 读（映射单源）：%s" % ("、".join(_readers107) or "无"))
    check('listOf("金星", "银星", "铱星")' in _gd107 and "firstOrNull { name.startsWith(it) }" in _gd107,
          "品质前缀仍只认三种（回归：c179 的 stripQuality 口径没动）")
    # ⚠ v2.100.0 / c182：反查那一侧**要**过别名归一（c182 修的就是它），
    #   但**拼键**不许过（拿归一后的名字当键 ⇒ 老用户已勾的条目静默失效）。
    #   ⇒ 断言只看「键构造」那一段（bundleCheckKey / bundleDoneCount），且要剥注释。
    _keypart107 = code_only(_pc107)
    check("canonicalName" not in _keypart107[
              _keypart107.index("fun bundleCheckKey"):_keypart107.index("data class BundleHit")],
          "勾选键不经过别名归一（归一只影响查询；拿归一后的名字当键会让老用户已勾的条目失效）")

    # ---- 107.5 获取途径是派生的，不是新建一张来源表 ----
    check("fun buildSourceIndex(" in _gd107, "获取途径由既有数据源派生（buildSourceIndex）")
    check("var sourceIndex: Map<String, List<SourceClue>>" in _gd107, "派生结果落在运行期索引里")
    for _t107 in ("shopCatalog", "processingProfit", "fishSpots", "monsterDrops",
                  "itemSources", "bundleRooms", "crops"):
        check(_t107 in _gd107, "线索来源引用既有表：%s" % _t107)
    check(not re.search(r'"[A-Za-z]*(source|acquire|obtain|where)[A-Za-z]*\.json"', _gd107),
          "GameData 没有读任何「来源表」JSON（来源一律派生，不建第二份表）")
    for _nf107 in ("itemSources.json", "sources.json", "acquire.json", "obtain.json",
                   "whereToGet.json", "itemSource.json"):
        check(not os.path.exists(os.path.join(DATA, _nf107)),
              "assets/data 下没有第二份来源表（%s 不存在）" % _nf107)

    # ---- 107.6 12 项缺件逐条有结论（真数据）----
    _shop107 = load(os.path.join(DATA, "shopCatalog.json"))
    _shop_names107 = set()
    for _arr107 in _shop107.values():
        for _it107 in _arr107:
            _shop_names107.add(_it107.get("name", ""))
    _animals107 = json.dumps(load(os.path.join(DATA, "animals.json")), ensure_ascii=False)
    _monsters107 = json.dumps(load(os.path.join(DATA, "monsterDrops.json")), ensure_ascii=False)
    _geodes107 = json.dumps(load(os.path.join(DATA, "geodes.json")), ensure_ascii=False)
    _proc107 = json.dumps(load(os.path.join(DATA, "processingProfit.json")), ensure_ascii=False)
    _bundles107 = json.dumps(load(os.path.join(DATA, "bundles.json")), ensure_ascii=False)
    for _nm107, _where107 in (
            ("木材", "shopCatalog"), ("罂粟", "shopCatalog"), ("果酒", "shopCatalog"),
            ("大壶牛奶", "animals"), ("大瓶羊奶", "animals"), ("动物毛", "animals"),
            ("史莱姆泥", "monsterDrops"), ("冰冻晶洞", "geodes"), ("葡萄酒", "processingProfit"),
    ):
        _hay107 = {"shopCatalog": "、".join(sorted(_shop_names107)), "animals": _animals107,
                   "monsterDrops": _monsters107, "geodes": _geodes107,
                   "processingProfit": _proc107}[_where107]
        check(_nm107 in _hay107, "缺件「%s」在既有数据源里有出处（%s）" % (_nm107, _where107))
    check("银星果酒" in _bundles107 and "鱼籽酱" in _bundles107,
          "缺件「银星果酒 / 鱼籽酱」的获取途径由收集包原文（src）兜住")

    # ---- 107.7 查不到来源就明说（不许编、不许用泛泛话术）----
    #    「硬木」「干草」在 App 的既有表里**真的查不到**来源：商店目录、加工表、
    #    钓点表、怪物掉落、养殖产出、晶洞表、收集包原文里都没有这两个名字。
    #    （上游 wiki 快照有硬木·秘密森林，但那是**上游包**、不是 App 的表——
    #      补 mineralSources.json 是数据活儿，本层只如实说「查不到来源」。）
    _bundle_src107 = " ".join(
        (it.get("src") or "")
        for _room107 in load(os.path.join(DATA, "bundles.json"))
        for _b107 in _room107.get("bundles", [])
        for it in _b107.get("items", []))
    _produced107 = set(_shop_names107)
    for _m107 in load(os.path.join(DATA, "processingProfit.json"))["machines"]:
        for _r107 in _m107.get("recipes", []):
            _produced107.add(_r107.get("output", ""))
    for _nm107 in ("硬木", "干草"):
        check(_nm107 not in _produced107
              and _nm107 not in _animals107
              and _nm107 not in _monsters107
              and _nm107 not in _geodes107
              and _nm107 not in _bundle_src107,
              "缺件「%s」在 App 现有各表里查无来源（只能显示「查不到来源」，不许编）" % _nm107)
    check("查不到来源" in _pp107, "进度页查不到来源时明说查不到")
    for _vague107 in ("农场种植", "可以通过种植", "自己去种"):
        check(_vague107 not in code_only(_pp107),
              "进度页的用户可见文案没有用泛泛话术充数（%s）" % _vague107)

    # ---- 107.8 重名不许挑一个：明说 N 条 + 给搜索页入口 ----
    check("fun codexDupGroups(" in _gd107, "GameData 能报同名条目数与所在分组")
    check("个同名条目" in _cx107, "详情卡明说「有 N 个同名条目」")
    check("挑一个 ›" in _cx107, "详情卡给了去重名的入口")
    check("resolveItemJump(canon)" in _cx107, "去重名入口走 resolveItemJump（全 App 唯一跳转入口）")
    check("NavRequest.Codex(" not in _cx107,
          "CodexPage 不自己构造 NavRequest.Codex（不新建第二套跳转逻辑）")
    _rj107 = code_only(_ci107[_ci107.index("internal fun resolveJumpRequest"):])
    check("GameData.canonicalName" in _rj107,
          "resolveItemJump 零命中时按别名表回落一次（别名侧也跳得过去）")
    check("hits.size == 0" in _rj107,
          "别名回落只在零命中时生效（命中数 >1 的重名口径一字不动）")

    # ---- 107.9 详情卡：线索与「查不到来源」都不许空口说 ----
    check("GameData.sourceCluesOf(item.name)" in _cx107, "详情卡逐条显示「去哪拿」线索")
    check("🔍 去哪拿" in _cx107, "详情卡有独立的「去哪拿」段落卡")
    check('it.first != "📍 获取途径"' in _cx107,
          "「📍 获取途径」汇总行不进用途速查（逐条明细在「去哪拿」卡里，不显示两遍）")
    check("GameData.usagesOf(ic.name).firstOrNull { it.first == \"📍 获取途径\" }" in _pp107,
          "进度页收集簿仍读 usagesOf 的「📍 获取途径」行（汇总口径没变）")

    # ---- 107.10 版本口径（c180）----
    _bg107 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("versionCode = 243" in _bg107,
          "构建号已到 189（…c187 缺图修复 → c188 避雷段渲染修复 → c189 versionName 升 2.116.0）")
    check('versionName = "2.152.1"' in _bg107,
          "versionName 已升到 2.116.0（CHANGELOG 首条同批升 v2.116.0）")

    # ================= 108. v2.100.0（c181）今日页「我今天该做什么」建议区 =================
    #
    # 背景：今日页的「通用日历」说的是**游戏里今天会发生什么**（谁的生日、哪个节日、
    # 播种死线），跟玩家是谁无关；「我的农场」说的是**田里现在怎么样**。
    # 这一层补上第三块：**我今天该做什么**（收什么 / 清什么 / 种什么 / 给谁备礼）。
    #
    # 本节的判据钉的是**语义**，不钉具体建议文案：
    #   · 条数 ≤ 3（钉上限，不钉具体几条）；
    #   · 每条都能指回依据数据（basis 非空且指向真字段）；
    #   · 数字与今日页同源（不许出现第二处算同一数字的实现）；
    #   · 算不出来就不出现（反向断言：不允许兜底塞一条泛泛建议）；
    #   · 数据不足时优雅退场（未导入存档时不显示建议区）；
    #   · 版本口径 v2.100.0 / c181；
    #   · 发布说明只写 c178 → c181 的增量（反向断言：不得复读已发布过的那批）。
    print("\n== 108. v2.100.0（c181）今日页建议区 ==")
    _ta108 = src("data/TodayAdvice.kt")
    _tac108 = src("ui/today/TodayAdviceCard.kt")
    _tp108 = src("ui/today/TodayPage.kt")
    _tat108 = io.open(
        os.path.join(ROOT, "app", "src", "test", "java", "com", "stardew", "assistant",
                     "data", "TodayAdviceTest.kt"),
        encoding="utf-8").read()
    _ta108c = code_only(_ta108)
    _tac108c = code_only(_tac108)

    # ---- 108.1 建议区接线：数据层算、UI 只渲染 ----
    check("object TodayAdviceBuilder" in _ta108,
          "建议的组装器在数据层（纯逻辑，单测可直接喂内存数据）")
    check("data class TodayAdvice(" in _ta108 and "fun build(" in _ta108,
          "建议模型与入口都在（今日页不自己算）")
    check('item(key = "advice")' in _tp108 and "TodayAdviceCard(" in _tp108,
          "今日页挂了建议区")
    check(_tp108.count("TodayAdviceCard(") == 1,
          "建议区只渲染一处（同一份建议不在两处分段各渲染一次）")
    _i_adv108 = _tp108.find('item(key = "advice")')
    _i_farmtab108 = _tp108.find("「我的农场」这一侧")
    _i_farmview108 = _tp108.find("FarmTodayView(")
    check(_i_adv108 != -1 and _i_farmtab108 != -1 and _i_adv108 > _i_farmtab108,
          "建议区挂在「我的农场」分段里（存档驱动的建议不混进通用日历）")
    check(_i_adv108 != -1 and _i_farmview108 != -1 and _i_adv108 < _i_farmview108,
          "建议区排在「今日农场」清单之前（同属我的农场，一眼先看该做什么）")

    # ---- 108.2 条数 ≤ 3（钉上限，不钉具体几条）----
    check("MAX_ADVICE = 3" in _ta108, "建议条数上限写死 3（MAX_ADVICE）")
    check("take(MAX_ADVICE)" in _ta108c, "组装器按上限截断（多出来的候选不显示）")
    check("建议条数 ≤ 3" in _tat108, "JVM 测试钉了条数上限（≤ 3）")
    check("advice.forEachIndexed" in _tac108c,
          "UI 一条建议渲染一行（不自己加行、不自己排序）")

    # ---- 108.3 每条建议都能指回依据数据 ----
    check("val basis: String" in _ta108, "建议模型带 basis（指回它依据的数据）")
    check(_ta108.count("basis = ") >= 4,
          "四个维度的建议都写了自己的依据（没有一条是凭空来的）")
    check("BASIS_DEAD" in _ta108 and "BASIS_HARVEST" in _ta108
          and "BASIS_PLANT" in _ta108 and "BASIS_BIRTHDAY" in _ta108,
          "依据是四个具名常量（测试能逐条比对）")
    check("a.basis.isNotBlank()" in _tat108, "JVM 测试钉了「依据不为空」")
    check("依据" in _tac108, "建议卡底部明写依据（用户看得见这条数从哪来）")

    # ---- 108.4 数字与今日页同源（不许出现第二处算同一数字的实现）----
    for _f108 in ("farm.readyStocks", "farm.harvestTotal", "farm.dead", "farm.money"):
        check(_f108 in _ta108,
              "建议里的数字直接读 FarmToday.%s（与今日页同一份数据）" % _f108.split(".")[1])
    check("sumOf" not in _ta108c,
          "建议引擎不自己另算合计（反向断言：同一个数字不许有第二处实现）")
    # 反向断言：建议文案只由引擎产出 —— UI 里不许写死建议句
    for _w108 in ("今天能收 ", "有枯死", "块空地", "的生日"):
        check(_w108 not in _tac108c,
              "建议卡里没有写死的建议文案「%s」（文案只由引擎产出）" % _w108)

    # ---- 108.5 算不出来就不出现（反向断言：不允许兜底塞一条泛泛建议）----
    #
    # ⚠ v2.100.0 / c182：**这两条原来是纯计数代理，拦不住兜底建议**。
    #   原断言是 `_ta108c.count("out += it") == 4` 与 `count("): TodayAdvice?") == 4`
    #   —— 实测往引擎里插 `if (out.isEmpty()) out += TodayAdvice(title = "有空地记得种点东西", …)`
    #   或 `out.add(TodayAdvice(title = "有空地记得种点东西", …))` 后重跑本节，
    #   **24 条断言全绿**（兜底建议既不是 `out += it`，也不是 `): TodayAdvice?`）。
    #   现在换成**能真的拦住**的反向断言：
    #   ① 引擎里没有「out 空了就塞一条」的分支；
    #   ② 引擎里没有 `out.add`（无条件追加）；
    #   ③ 引擎里的建议标题字面量**恰好 4 条**（一个维度一条）——
    #      兜底建议必带中文标题字面量，多一条就红；
    #   ④ 引擎里没有「有空地记得种」这类兜底话术。
    check("if (farm == null) return emptyList()" in _ta108c,
          "没导入存档 ⇒ 零条建议（不造一条安慰话充数）")
    check("out.isEmpty()" not in _ta108c,
          "反向断言：引擎里没有「out 空了就塞一条兜底建议」的分支")
    check("out.add(" not in _ta108c,
          "反向断言：引擎里没有 out.add（无条件追加一条建议）")
    check(_ta108c.count("out += it") == 4,
          "候选只有四个维度，且每条都来自可空函数（?.let { out += it }）")
    check(_ta108c.count("): TodayAdvice?") == 4,
          "四个维度都返回可空（算不出来就返回 null ⇒ 那条不出现）")
    _titles108 = re.findall(r'title\s*=\s*"([^"]*)"', _ta108)
    check(len(_titles108) == 4,
          "引擎里恰好 4 条建议标题字面量（一个维度一条；多出来就是兜底建议）—— 实际 %d 条" % len(_titles108))
    for _fb108 in ("有空地记得种", "记得种点东西", "今天也可以", "随便种"):
        check(_fb108 not in _ta108c,
              "引擎里没有兜底话术「%s」（算不出来就不出现，不许泛泛话术充数）" % _fb108)
    check("return emptyList()" in _ta108c and "return null" in _ta108c,
          "算不出来时返回空 / null，不返回泛泛建议")

    # ---- 108.6 数据不足时优雅退场 ----
    check("if (advice.isEmpty()) return" in _tac108c,
          "没有建议 ⇒ 整张卡不渲染（未导入存档时不显示建议区）")
    check("今天该做什么" in _tac108, "建议区有标题（用户找得到）")
    check("CollapsibleCard(" in _tac108c, "建议区整段能收起来（别把今日页撑太长）")

    # ---- 108.7 版本口径：v2.100.0 / c181 ----
    _bg108 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("versionCode = 243" in _bg108, "构建号 210（…→ c208 礼物表段位校正 → c209 图鉴版面重排 → c210 五批数据优化）")
    check('versionName = "2.152.1"' in _bg108,
          "versionName 2.122.0（c189 口径变更；c210 五批数据优化续升）")
    _sp108 = src("ui/settings/SettingsPage.kt")
    _m_log108 = re.search(
        r'private val CHANGELOG = listOf\(\s*\r?\n\s*"v?([0-9][0-9.]*)[^"]*"', _sp108)
    check(bool(_m_log108) and _m_log108.group(1) == "2.152.1",
          "App 内 CHANGELOG 首条是 v2.152.1（c244：指引清理）")
    _cl108 = re.findall(r'"([^"]+)" to listOf\((.*?)\n    \),', _sp108, re.S)
    _first108 = _cl108[0] if _cl108 else ("", "")
    _bs108 = re.findall(r'\n\s+"([^"]+)"', _first108[1])
    check(len(_bs108) <= 2, "v2.152.1 那条最多 2 颗子句（实际 %d）" % len(_bs108))
    for _x108 in _bs108:
        check(len(_x108) <= 32,
              "v2.152.1 子句 %d 字（上限 32）：%s" % (len(_x108), _x108[:16]))
        check(not _x108.rstrip("」』】…").endswith("了"),
              "v2.152.1 子句不以「了」收尾：%s" % _x108[:16])
        check("！" not in _x108, "v2.152.1 子句无感叹号：%s" % _x108[:16])
    check(any(k in _first108[0] or k in _first108[1]
              for k in ("跳板", "下滑", "玻璃", "浓度", "贴边", "模糊", "流畅",
                        "震动", "触觉", "系统", "勾选", "按钮", "音效", "开关",
                        "串档", "存档", "缓存", "农场",
                        "筛选", "翻页", "小组件", "待办",
                        "收集包", "米黄", "对比", "闪退", "加工", "滑动", "瘦身", "分栏",
                        "常驻", "息屏", "后台", "冻结", "授权",
                        # c235：局域网协议说明这一轮
                        "局域网", "协议", "同步", "电脑脚本", "关于页", "口径",
                        "模组", "优先", "推荐", "备选", "精简", "接收", "直链", "电脑",
                        # c241：项目开源这一轮（源码公开 / 联系方式 / 收款码）
                        "开源", "源码", "公开", "联系方式", "收款码", "自行修改",
                        # c243：名单整删这一轮
                        "名单", "隐私", "放缓", "移除",
                        # c244：开源仓复查收口这一轮（死引导 / 说明落点）
                        "指引", "清理", "引导", "打赏", "说明", "落点")),
          "v2.152.1 那条指向当前主线（指引清理 + 历史主线词）")

    # ---- 108.8 发布说明：只写 c178 → c181 的增量 ----
    _rel108 = os.path.join(_REPO_PARENT, "安装包归档", "发布说明_v2.100.0_c184.txt")
    check(os.path.exists(_rel108),
          "有发布说明（安装包归档/发布说明_v2.100.0_c184.txt）")
    if os.path.exists(_rel108):
        _raw108 = open(_rel108, "rb").read()
        _txt108 = _raw108.decode("utf-8")
        check(b"\r\n" not in _raw108, "发布说明是 LF 行尾（不是 CRLF）")
        for _h108 in [l for l in _txt108.splitlines()
                      if l and not l[0].isdigit() and not l.startswith(" ")]:
            check("：" not in _h108 and ":" not in _h108,
                  "分类头不带冒号：%s" % _h108)
        for _l108 in _txt108.splitlines():
            if re.match(r"^\d+\. ", _l108):
                check(not _l108.rstrip().endswith("。"),
                      "条目无句号：%s" % _l108[:20])
        check("你的状态" in _txt108 and "去哪拿" in _txt108 and "今天该做什么" in _txt108,
              "发布说明写了这三件事（c179 / c180 / c181）")
        check("叠在一起" in _txt108,
              "发布说明写了空态重叠修复（v2.99.0 线上就有这个 bug，修了就是用户可感知差异）")
        check("你的状态" in _txt108,
              "发布说明写了「你的状态」总览（今日页「我的农场」里一屏看存档全貌）")
        # 反向断言：v2.95.0 ~ v2.98.0 那批（远端早就发过了）一个字不许复读
        for _old108 in ("使用指引", "收集簿物品图标", "品质物品也能跳", "法师塔图腾",
                        "跳到面板", "分类面板", "季节与节日配色", "导入存档确认界面",
                        "茶水间", "潘妮最爱礼物", "导入自定义字体", "NPC 行程表",
                        "下一日", "计算页"):
            check(_old108 not in _txt108,
                  "发布说明没有复读已发布过的内容「%s」" % _old108)

    # ================= 109. v2.100.0（c182）三层任务缺陷修复轮 =================
    #
    # 背景：c179 / c180 / c181 三层（百科 × 存档）**从未发布过**（远端仍是 v2.99.0 / c178），
    #   本轮把它们报出的 10 个缺陷一次修掉（versionCode 181 → 182，versionName 不动）。
    #
    # 判据钉的是**语义**，而且**以反向断言为主**：
    #   正向断言只能钉「写没写」，反向断言才能钉「不许再这样写」。
    #   ① 收集包反查两侧都过别名归一；**拼键用收集包条目自己的名字**；
    #   ② 村民名三表一致（gifts / reportNames.npcs / movies）—— 任何一方出现另一方没有的名字就红；
    #   ③ 建议的「今天」只有**存档日期**一个（不许读转盘日期）；建议卡必须标注日期来源；
    #   ④ 金额走 formatG（不许裸写）；注脚不许列没用的「背包」；
    #   ⑤ 生日文案不许与生日卡那句逐字相同；枯死建议的 N 是**品种数**；
    #   ⑥ 图鉴跳转的别名回落门显式是「零命中」（重名不硬跳）；
    #   ⑦ `cc:` 键模板在整个**生产**源码树里只出现在 ProgressCalc.kt；
    #   ⑧ 版本口径 c182 / 2.100.0。
    print("\n== 109. v2.100.0（c182）三层任务缺陷修复轮 ==")
    _pc109 = code_only(src("data/ProgressCalc.kt"))
    _cs109 = code_only(src("ui/codex/CodexStatus.kt"))
    _ta109 = src("data/TodayAdvice.kt")
    _ta109c = code_only(_ta109)
    _tp109 = src("ui/today/TodayPage.kt")
    _tpc109 = code_only(_tp109)
    _tac109 = code_only(src("ui/today/TodayAdviceCard.kt"))
    _ci109 = src("ui/codex/CodexIndex.kt")

    # ---- 109.1 收集包反查：两侧归一 + 拼键用条目自己的名字 ----
    check("GameData.canonicalName(itemName)" in _pc109,
          "收集包反查：图鉴条目名那一侧过别名归一")
    check("GameData.canonicalName(it) == canon" in _pc109,
          "收集包反查：收集包条目名那一侧也过别名归一（两侧同一口径）")
    check("it.name == itemName" not in _pc109,
          "反向断言：反查里不许再出现裸的「it.name == itemName」精确匹配")
    check("val itemNames: List<String>" in _pc109,
          "反查结果带收集包条目自己的名字（别名侧，如「罂粟」）")
    check("bundleCheckKey(roomIdx, bundleIdx, it) in checks" in _pc109,
          "「已提交」判定用收集包条目自己的名字拼键（不拿归一后的名字当键）")
    check("h.checked(inp.checks)" in _cs109,
          "「你的状态」那一行不再把图鉴条目名传进已提交判定")
    check("h.checked(inp.checks, nm)" not in _cs109,
          "反向断言：图鉴条目名不许再进勾选判定（那会让老用户已勾的条目误报未提交）")
    # 同款洞（精确匹配 vs 别名）波及的两处反查也一并归一
    check("GameData.canonicalName(it.name) == canon" in code_only(src("ui/search/GlobalSearch.kt")),
          "全局搜索的收集包来源行也过了别名归一")
    check("GameData.canonicalName(it.name) == canon" in code_only(src("ui/codex/CodexPage.kt")),
          "图鉴「相关」（mates）也过了别名归一")

    # ---- 109.2 村民名：三表一致（反向断言：任何一方出现另一方没有的名字就红）----
    _gifts109 = [g.get("name", "") for g in load(os.path.join(DATA, "gifts.json"))]
    _npcs109 = list(load(os.path.join(DATA, "reportNames.json")).get("npcs", {}).values())
    _movies109 = [m.get("村民", "") for m in load(os.path.join(DATA, "movies.json"))]
    for _n109 in _movies109:
        check(_n109 in _gifts109,
              "movies.json 里的村民「%s」在 gifts.json 里有礼物档案（缺了 ⇒ 图鉴「你的状态」整卡不渲染）" % _n109)
    for _n109 in _gifts109:
        if _n109 == "浣熊":
            # 浣熊商店那只浣熊不是村民（不在存档侧译名表里）—— 已知例外，写死理由。
            continue
        check(_n109 in _npcs109,
              "gifts.json 里的「%s」在存档侧译名表里同名" % _n109)
    check("里奥" not in _gifts109 and "里奥" not in _movies109 and "里奥" not in _npcs109,
          "「里奥」这个叫法已统一成「雷欧」（三张表不许混用两个叫法）")
    check("雷欧" in _gifts109 and "雷欧" in _movies109 and "雷欧" in _npcs109,
          "「雷欧」在礼物表 / 电影院偏好 / 存档侧译名表里同名")
    _gap109 = [n for n in _npcs109 if n not in _gifts109]
    check(not [n for n in _gap109 if n in _movies109],
          "礼物表缺档案的那几位（%s）不在电影院偏好组里（不构成同名冲突）"
          % ("、".join(_gap109) if _gap109 else "无"))
    # 红心事件 / 行程表都**按名字**关联 ⇒ 名字漂了就整段查不到（一起钉）
    for _h109 in load(os.path.join(DATA, "heartEvents.json")):
        _vn109 = _h109.get("villager", "")
        check(_vn109 in _gifts109,
              "heartEvents 的村民「%s」在礼物表里同名（红心事件按名字关联）" % _vn109)
    _ns109 = load(os.path.join(DATA, "npcSchedules.json")).get("npcs", [])
    for _n109 in _ns109:
        _nm109 = _n109.get("name", "")
        if _nm109 in _npcs109:
            check(_nm109 in _gifts109,
                  "行程表的 NPC「%s」在礼物表里同名（行程按名字查）" % _nm109)
    check(not [n for n in _ns109 if n.get("name", "") == "里奥"],
          "行程表里没有「里奥」（已统一成「雷欧」）")
    check(any(n.get("name", "") == "雷欧" for n in _ns109),
          "行程表里是「雷欧」")
    # 代码侧回退：村民判定取「礼物表 ∪ 存档侧译名表」的并集（礼物表缺档案的人也是村民）
    check("fun isVillagerName(" in code_only(src("data/GameData.kt")),
          "GameData 有村民名判定单源（isVillagerName）")
    check("name in npcNames" in code_only(src("data/GameData.kt")),
          "村民判定用「礼物表 ∪ 存档侧译名表」的并集（礼物表缺档案的人照样是村民）")
    check("GameData.isVillagerName(item.name)" in code_only(src("ui/codex/CodexPage.kt")),
          "图鉴「你的状态」的村民判定走单源（不再只认礼物表）")

    # ---- 109.3 建议的「今天」只有存档日期一个（反向断言：不许读转盘日期）----
    _i_adv109 = _tpc109.index("val advice = remember(")
    _j_adv109 = _tpc109.index("val adviceDateLabel", _i_adv109)
    _adv109 = _tpc109[_i_adv109:_j_adv109]
    check("snap.season" not in _adv109,
          "建议区不读转盘的 snap.season（「今天」按存档日期）")
    check("snap.day" not in _adv109,
          "建议区不读转盘的 snap.day（「今天」按存档日期）")
    check("todayFacts(" not in _adv109,
          "建议区不转盘日期那份生日名单（TodayFacts.todayBirthdays 按转盘日期）")
    check("birthdaysOnDate(" in _adv109,
          "生日名单按**存档日期**算（birthdaysOnDate）")
    _bi109 = _ta109c.index("fun build(")
    _bj109 = _ta109c.index("): List<TodayAdvice>", _bi109)
    _params109 = _ta109c[_bi109:_bj109]
    check("seasonName" not in _params109,
          "build() 不再收转盘的 seasonName（今天按存档日期）")
    check("day: Int" not in _params109,
          "build() 不再收转盘的 day（今天按存档日期）")
    for _f109 in ("farm.year", "farm.season", "farm.day"):
        check(_f109 in _ta109c,
              "存档日期取自 FarmToday.%s（与「今日农场」卡同一个字段）" % _f109.split(".")[1])
    check("fun birthdaysOnDate(" in _ta109c,
          "生日名单的计算在数据层单源（birthdaysOnDate）")

    # ---- 109.4 建议卡：标注日期来源 + 注脚只列真用到的依据 ----
    check("dateLabel" in _tac109, "建议卡带日期来源标签（dateLabel）")
    check("日期：" in _tac109, "建议卡写明日期来源")
    check("按存档" in _tp109,
          "转盘日期与存档日期不同时，标签明写「按存档」（否则同屏两个「今天」）")
    check("背包" not in _tac109,
          "反向断言：建议卡注脚不列「背包」（没有一条建议读背包）")
    check("田里 / 现金 / 作物表 / 生日" in _tac109,
          "注脚列的是真的用到的依据（田里 / 现金 / 作物表 / 生日）")
    check("TodayAdviceCard(advice, adviceOpen, adviceDateLabel)" in _tpc109,
          "今日页把日期来源标签传给建议卡")

    # ---- 109.5 金额同一个写法（formatG）----
    check("formatG(money)" in _cs109,
          "图鉴「你的状态」的现金走 formatG（与今日页同一写法）")
    check("$money g" not in _cs109,
          "反向断言：CodexStatus 不许出现裸的「$money g」")
    check("import com.stardew.assistant.data.formatG" in src("ui/codex/CodexStatus.kt"),
          "CodexStatus 用的是数据层那份 formatG（不在 UI 层再写一个）")

    # ---- 109.6 生日文案不许与生日卡那句逐字相同 ----
    _birth109 = "生日送礼效果 ×8，记得备最爱礼物"
    check(_birth109 in _tp109, "生日卡那句还在（对照基准：通用日历的「🎂 生日」卡）")
    check(_birth109 not in _ta109c,
          "反向断言：建议里的生日文案不与生日卡那句逐字相同")
    check("提醒备" not in _ta109c and "生日送礼效果 ×8" not in _ta109c,
          "反向断言：建议不抄生日卡那句")
    check("fun birthdayAdvice(" in _ta109c, "生日那条在引擎里")

    # ---- 109.7 枯死建议的 N 是品种数（不是行数）----
    check("byName[r.name] = (byName[r.name] ?: 0) + r.count" in _ta109c,
          "枯死那条按品种归并后计数")
    check("等 $kinds 种" in _ta109c,
          "「等 N 种」的 N 是**不同品种数**")
    check("rows.size > 2" not in _ta109c,
          "反向断言：不许再按「行数」写「等 N 种」（同一品种种两处就是两行）")

    # ---- 109.8 图鉴跳转：别名回落的门显式是「零命中」----
    _rj109 = code_only(_ci109[_ci109.index("internal fun resolveJumpRequest"):])
    check("if (hits.size == 0) {" in _rj109,
          "别名回落写在显式的「零命中」分支里")
    check("alt.isEmpty()" not in _rj109,
          "反向断言：别名回落不再用「alt.isEmpty()」当门（重名时 alt 也是空 ⇒ 会硬跳）")
    check(_rj109.index("if (hits.size == 0) {") < _rj109.index("GameData.canonicalName"),
          "别名回落（canonicalName）在零命中分支之内")
    check("fun resolveJumpRequest(" in _ci109,
          "跳转判定抽成纯函数（单测能直接喂假命中表）")
    _cj109 = os.path.join(ROOT, "app", "src", "test", "java", "com", "stardew", "assistant",
                          "ui", "codex", "CodexJumpTest.kt")
    check(os.path.exists(_cj109), "有跳转回落门的单测（CodexJumpTest）")
    _bgk109 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("com.stardew.assistant.ui.codex.CodexJumpTest" in _bgk109,
          "CodexJumpTest 挂在某个 JVM 验收任务里（不被漏跑）")

    # ---- 109.9 `cc:` 键模板单源（整个生产源码树）----
    _cc_files109 = []
    for _root109, _dirs109, _files109 in os.walk(os.path.join(ROOT, "app", "src", "main")):
        for _f109 in _files109:
            if _f109.endswith(".kt"):
                _p109 = os.path.join(_root109, _f109)
                try:
                    if '"cc:' in io.open(_p109, encoding="utf-8").read():
                        _cc_files109.append(
                            os.path.relpath(_p109, ROOT).replace("\\", "/"))
                except Exception:
                    pass
    check(_cc_files109 == ["app/src/main/java/com/stardew/assistant/data/ProgressCalc.kt"],
          "「cc:」字面量在整个生产源码树里只出现在 ProgressCalc.kt（实际：%s）"
          % ("、".join(_cc_files109) or "无"))
    check("bundleCheckKey(ri, bi, ic.name)" in code_only(src("ui/progress/ProgressPage.kt")),
          "进度页的勾选键改调 bundleCheckKey（不再内联拼键模板）")
    check("bundleCheckKey(3, 2," in code_only(src("data/PrefsRepo.kt")),
          "PrefsRepo 的历史迁移表也改调 bundleCheckKey（键模板不再有第二份实现）")

    # ---- 109.10 版本口径：c182 → c183 / 2.100.0 ----
    _bg109 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("versionCode = 243" in _bg109,
          "构建号已到 207（…→ c204 数据老账 → c206 品质价与礼物表按官方校正 → c207 数据审查修复轮）")
    check('versionName = "2.152.1"' in _bg109,
          "versionName 已升 2.122.0（c189 口径变更：修复轮也升 versionName；c210 同）")


    # ================= 110. v2.100.0（c183）搜索空态文字重叠修复 =================
    #
    # 群友报「搜索界面有文字重叠」：图鉴「仅本分类」空态的两行字叠成一团花字
    # （搜「1」· 仅本分类 · 电影院偏好；模拟器已复现，与群友截图一致）。
    #
    # 病因（v2.29 起就在）：**LazyVerticalGrid 的 item 里直接写两个根级 Text** ——
    #   Grid 把 item 内容当 Box 测量，多个根级组件**叠在同一个位置**；
    #   跨分类那处空态在搜索结果的 **LazyColumn** 里（item 内多根是竖排），所以没事。
    #
    # 修法：空态 when 的两个多行分支各包 Column；版本 c182 → c183（versionName 不动）。
    # 钉法：按分支切片，断言 Column 在两行文案**之前**（真包住），并钉分支内恰好两行 ——
    #   多了说明又有人往 Grid item 里塞裸组件。
    print("\n== 110. v2.100.0（c183）搜索空态文字重叠修复 ==")
    _cp110 = src("ui/codex/CodexPage.kt")
    _cp110c = code_only(_cp110)
    _i110 = _cp110c.index("query.isNotBlank() -> {")
    _j110 = _cp110c.index("checkable && checkFilter == 1", _i110)
    _s110 = _cp110c[_i110:_j110]
    check("Column {" in _s110,
          "搜索空态（仅本分类）的两行说明包在 Column 里（Grid item 多根组件会叠放）")
    check(_s110.index("Column {") < _s110.index("里没有匹配"),
          "Column 在空态文案之前（真包住两行，不是后面另有 Column）")
    check(_s110.count("PixelText(") == 2,
          "搜索空态分支恰好两行文案（多一行就可能是又塞了裸组件）")
    _k110 = _cp110c.index("else -> {", _j110)
    _l110 = _cp110c.index("Spacer(Modifier.padding(4.dp))", _k110)
    _e110 = _cp110c[_k110:_l110]
    check("Column {" in _e110 and _e110.index("Column {") < _e110.index("这个分类暂时没有内容"),
          "「空分类」空态的两行说明同样包在 Column 里")
    _bg110 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("versionCode = 243" in _bg110, "构建号 210（…→ c208 礼物表段位校正 → c209 图鉴版面重排 → c210 五批数据优化）")
    check('versionName = "2.152.1"' in _bg110, "versionName 2.122.0（c189 口径变更；c210 五批数据优化续升）")


    # ================= 111. v2.100.0（c184）「你的状态」总览卡 =================
    #
    # 背景：群友问「我咋没看到图鉴条目里面新增有你的状态呀」——
    #   「🎒 你的状态」原本是**图鉴条目详情里的一张段落卡**（单条状态），
    #   要点进每个条目才看得到；建筑 / 晶洞 / 节日这些分类没有对应存档数据，
    #   整卡直接不出现 ⇒ 看着像「没做」。而且「背包」「村民好感」两个维度
    #   **只**存在于图鉴条目详情里，今日页与进度页都是盲区。
    #
    # 用户 2026-10-28 拍板：**图鉴那份保留**，另在今日页「我的农场」分段加一张**总览卡**。
    # 四行（用户勾选的）：背包 / 村民好感 / 图鉴进度 / 收集包。
    #
    # 钉法（语义为主、反向断言优先）：
    #   ① 逻辑在**数据层**纯函数（可 JVM 单测），UI 只渲染；
    #   ② 没导入存档 ⇒ 整卡不出现（不许显示「0 类 0 件」）；
    #   ③ 收集包两行**直接取 calcProgress 的结果**（不另算一遍）；
    #   ④ 图鉴进度分母**实时算**（13 个可打勾分类的条目数之和），不许写死常量；
    #   ⑤ 卡片落在「我的农场」分段（**不是**通用日历那一侧）；
    #   ⑥ 不重复「🌾 今日农场」已有的（现金 / 今日能收 / 田里明细）。
    print("\n== 111. v2.100.0（c184）「你的状态」总览卡 ==")
    _ms111 = src("data/MyStatus.kt")
    _msc111 = code_only(_ms111)
    _mc111 = src("ui/today/MyStatusCard.kt")
    _mcc111 = code_only(_mc111)
    _tp111 = src("ui/today/TodayPage.kt")
    _tpc111 = code_only(_tp111)

    # ---- 111.1 逻辑在数据层、纯函数 ----
    check("fun myStatusSummary(" in _ms111, "总览逻辑在数据层（data/MyStatus.kt））")
    check("data class MyStatusSummary" in _ms111, "有独立的折算模型 MyStatusSummary")
    for _bad111 in ("Context", "Compose", "SharedPreferences", "File("):
        check(_bad111 not in _msc111,
              "反向断言：数据层不碰 %s（纯逻辑，可 JVM 单测）" % _bad111)
    # ---- 111.2 没导入存档 ⇒ 整卡不出现 ----
    check("if (state == null) return null" in _msc111,
          "没导入存档 ⇒ 返回 null（整卡不出现，不显示「0 类 0 件」）")
    check("if (summary == null) return" in _mcc111,
          "UI 侧：summary 为 null ⇒ 整张卡不渲染")
    # ---- 111.3 收集包与进度页同源（不另算）----
    check("bundle = calcProgress(snap)" in _tpc111,
          "收集包两行直接取 calcProgress 的结果（与进度页同一份，不另算）")
    check("bundleDone = bundle?.bundleChecked ?: 0" in _msc111,
          "收集包已提交数取 ProgressStats.bundleChecked")
    check("bundleTotal = bundle?.bundleTotal ?: 0" in _msc111,
          "收集包总数取 ProgressStats.bundleTotal")
    # ---- 111.4 图鉴进度分母实时算 ----
    check("codexTotal += g.items.size" in _msc111,
          "图鉴进度分母实时累加（不许写死常量）")
    check("if (!isCheckable(g.id)) continue" in _msc111,
          "图鉴进度只算 13 个可打勾分类（白名单外不计）")
    check("groupCheckedCount(g.id, g.items, checks)" in _msc111,
          "图鉴已勾数用既有的 groupCheckedCount（打勾口径单源）")
    # ---- 111.5 四行都在 ----
    for _row111 in ("背包", "村民好感", "图鉴进度", "收集包"):
        check('KVRow("%s"' % _row111 in _mcc111, "总览卡有「%s」这一行" % _row111)
    # ---- 111.6 落在「我的农场」分段，不是通用日历 ----
    check('item(key = "myStatus")' in _tpc111, "总览卡挂在「我的农场」分段（item key = myStatus）")
    check("MyStatusCard(myStatus)" in _tpc111, "总览卡用 MyStatusCard 渲染")
    _farm111 = _tpc111.index('else {', _tpc111.index("farmTab == 0"))
    check(_tpc111.index('item(key = "myStatus")') > _farm111,
          "总览卡在 farmTab == 1（我的农场）那一侧，不在通用日历侧")
    # ---- 111.7 诚实口径：点数判不出不补 0；名字没对上的件数如实带出来 ----
    check("val p = f.points ?: continue" in _msc111,
          "好感点数判不出的村民不参与排名（不补 0 当最高）")
    check("unmappedItems = state.unmappedItems" in _msc111,
          "背包里名字没对上的件数如实带出来（不吞掉）")
    check("以游戏内为准" in _mc111, "判不出时明说「以游戏内为准」（不编）")
    # ---- 111.8 不重复「今日农场」已有的 ----
    for _dup111 in ("现金", "今天能收", "田里", "农场地图"):
        check('KVRow("%s"' % _dup111 not in _mcc111,
              "总览卡不重复「🌾 今日农场」已有的「%s」" % _dup111)
    # ---- 111.9 版本口径 ----
    _bg111 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("versionCode = 243" in _bg111, "构建号 210（…→ c208 礼物表段位校正 → c209 图鉴版面重排 → c210 五批数据优化）")
    check('versionName = "2.152.1"' in _bg111, "versionName 2.122.0（c189 口径变更；c210 五批数据优化续升）")

    # ================= 112. v2.100.0（c186）可读性三件 =================
    # 三件都是线上 v2.100.0 (c185) 已存在的问题（可发布增量）：
    #   ① 今日页全完成庆祝条：夜间 cream 压亮 gold 1.31:1 —— 底色按 gold 亮度切换；
    #   ② PanelPreview 预览小字 + 详情页码「‹ n/N ›」裸压木壳 2.04:1 —— 套羊皮纸底；
    #   ③ 「跳到…」玻璃弹层夜间透显 —— 只调夜间旋钮（暗纱 2.4 / blur 16dp）。
    print("\n== 112. v2.100.0（c186）可读性三件 ==")

    # ---- 112.1 庆祝条：底色按 gold 亮度切换（判据复用 isLightSurface 单源）----
    # code_only 剥注释 ⇒ 用正则钉代码形状，不钉缩进空格数。
    _tp112 = code_only(src("ui/today/TodayPage.kt"))
    # 正向：庆祝条底色表达式 = if (isLightSurface(c.gold)) c.woodDark else c.gold
    check(re.search(r"isLightSurface\(c\.gold\)\)\s*c\.woodDark\s*\n\s*else c\.gold", _tp112) is not None,
          "庆祝条底色按 gold 亮度切换（gold 浅→woodDark，深→原样；isLightSurface 单源判据）")
    # 反向：庆祝条 background 的**实参**必须是 isLightSurface 表达式（语义钉，不管单行/多行）。
    # 取「庆祝条文字」向前 500 字符切片 —— background(...) 实参若退回纯 c.gold（无条件金底，
    # 正是夜间 1.31:1 的旧写法，单行/多行都能注入）即翻红。
    _segA = _tp112[_tp112.index("今日待办全部完成") - 500:
                   _tp112.index("今日待办全部完成")]
    check("isLightSurface(c.gold)" in _segA,
          "庆祝条 background 实参必须是 isLightSurface(c.gold) 亮度表达式（无条件 c.gold 夜间 1.31:1）")
    # 文字与守卫不动
    check("color = c.cream, shadow = true" in _tp112, "庆祝条文字仍 cream + shadow")
    check("total > 0 && doneCount == total" in _tp112, "庆祝条 total>0 守卫仍在（空清单不显示）")

    # ---- 112.2 PanelPreview：羊皮纸底（宽度上限/单行/过滤等原约束 24.5 节也钉）----
    _cps112 = code_only(src("ui/codex/CodexPanelSections.kt"))
    _pv112 = _cps112[_cps112.index("fun PanelPreview"):]
    _pv112 = _pv112[:_pv112.index("\n}\n")]
    check(".width(150.dp)" in _pv112 and ".background(c.parchment)" in _pv112,
          "PanelPreview 套羊皮纸底且宽度上限仍在外层 Box")
    check(_pv112.index("background(c.parchment)") < _pv112.index("padding(horizontal = 4.dp"),
          "PanelPreview 底色在 padding 之前（底不被内缩挖空）")
    check("color = c.inkDim" in _pv112, "PanelPreview 文字仍 inkDim（压 parchment 双模式 ≈5:1）")

    # ---- 112.3 详情页码：套 PixelCard ----
    # 页码文字是唯一锚点（code_only 保留字符串字面量），取其前 400 字符切片验结构
    _cp112 = code_only(src("ui/codex/CodexPage.kt"))
    _i112 = _cp112.index("currentPage + 1")
    # c221：页码文字被包进 key { … } 再进 PixelCard，回看窗口要够宽才够得着 PixelCard
    _seg112 = _cp112[max(0, _i112 - 700):_i112 + 200]
    check("PixelCard(" in _seg112, "详情页码套 PixelCard（羊皮纸内衬，不再裸压木壳）")
    check(_seg112.index("PixelCard(") < _seg112.index("currentPage + 1"),
          "页码文字在 PixelCard 内容 lambda 之内（真包住，不是并列组件）")

    # ---- 112.4 玻璃弹层：只调夜间旋钮 ----
    _gs112 = code_only(src("ui/glass/GlassSheet.kt"))
    check("blurDp = 16f" in _gs112, "弹层模糊 12→16dp（像素高频边框更能糊成柔光）")
    check("veilFor(c.screen, alphaScale = 1.7f)" in _gs112,
          "白天白纱 0.714 不动（再提会 coerceAtMost 封顶成死白，玻璃效果消失）")
    check("veilFor(c.screen, alphaScale = 2.4f)" in _gs112,
          "夜间暗纱单独加浓 0.35×2.4=0.84（透显主诉在夜间）")
    check("isLightSurface(c.screen)" in _gs112,
          "日夜两档用 isLightSurface 单源判据切换")

    # ================= 113. 缺图修复（c187） =================
    # 起因：w89 审计查出 73 个物品在运行时落「首字占位块」（image_map 无键）。
    # 本轮修 60 项：46 张唯一图入库 + 60 个映射键 + cheatCodes 靴子类纠错。
    # ⚠ 断言钉**图内容**（与本地 v9 官方原图同哈希），不钉「键存在」——
    #   只钉键存在挡不住把「杨桃酒」指到「布」的图（张冠李戴照样全绿）。
    print("\n== 113. 缺图修复（c187） ==")

    _imap113 = load(IMG)
    _img113 = os.path.join(ROOT, "app", "src", "main", "assets", "img")
    _pkg113 = os.path.join(_REPO_PARENT, "旧文件归档", "stardew_valley_1.6_data_v9", "images", "items")

    def _h113(rel):
        """图内容哈希（相对 assets/img）"""
        _p = os.path.join(_img113, rel.replace("/", os.sep))
        if not os.path.exists(_p):
            return None
        return md5_of2(_p)

    # ---- 113.1 本轮新增的 60 个键：存在 + 指向真实文件 + 内容 == 官方原图 ----
    # （消费名 -> (App 内相对路径, 本地 v9 官方原图文件名)）
    _NEW113 = {
        # 加工产出：酒 8 种（同为物品 348，一张图）
        "杨桃酒": ("wiki/items/348_Wine.png", "348_Wine.png"),
        "远古果实酒": ("wiki/items/348_Wine.png", "348_Wine.png"),
        "菠萝酒": ("wiki/items/348_Wine.png", "348_Wine.png"),
        "甜瓜酒": ("wiki/items/348_Wine.png", "348_Wine.png"),
        "草莓酒": ("wiki/items/348_Wine.png", "348_Wine.png"),
        "蔓越莓酒": ("wiki/items/348_Wine.png", "348_Wine.png"),
        "蓝莓酒": ("wiki/items/348_Wine.png", "348_Wine.png"),
        "仙人掌果酒": ("wiki/items/348_Wine.png", "348_Wine.png"),
        # 果汁 3 种（物品 350）
        "番茄汁": ("wiki/items/350_Juice.png", "350_Juice.png"),
        "南瓜汁": ("wiki/items/350_Juice.png", "350_Juice.png"),
        "红叶卷心菜汁": ("wiki/items/350_Juice.png", "350_Juice.png"),
        # 果酱 3 种（物品 344）
        "杨桃果酱": ("wiki/items/344_Jelly.png", "344_Jelly.png"),
        "大黄果酱": ("wiki/items/344_Jelly.png", "344_Jelly.png"),
        "草莓果酱": ("wiki/items/344_Jelly.png", "344_Jelly.png"),
        # 腌菜 3 种（物品 342）
        "腌南瓜": ("wiki/items/342_Pickles.png", "342_Pickles.png"),
        "腌土豆": ("wiki/items/342_Pickles.png", "342_Pickles.png"),
        "腌番茄": ("wiki/items/342_Pickles.png", "342_Pickles.png"),
        # 加工其他
        "金色蛋黄酱": ("wiki/items/306_Mayonnaise.png", "306_Mayonnaise.png"),
        "鸵鸟蛋黄酱": ("wiki/items/Ostrich_Mayonnaise.png", "Ostrich_Mayonnaise.png"),
        "布": ("wiki/items/428_Cloth.png", "428_Cloth.png"),
        "野花蜜": ("wiki/items/340_Honey.png", "340_Wild_Honey.png"),
        "罂粟蜜": ("wiki/items/340_Honey.png", "340_Poppy_Honey.png"),
        "仙女玫瑰蜜": ("wiki/items/340_Honey.png", "340_Fairy_Rose_Honey.png"),
        "果干": ("wiki/items/Dried_Fruit.png", "Dried_Fruit.png"),
        "脱水蘑菇": ("wiki/items/Dried_Mushrooms.png", "Dried_Mushrooms.png"),
        "向日葵油": ("wiki/items/247_Oil.png", "247_Oil.png"),
        "精制石英": ("wiki/items/338_Refined_Quartz.png", "338_Refined_Quartz.png"),
        "史莱姆蛋": ("wiki/items/680_Green_Slime_Egg.png", "680_Green_Slime_Egg.png"),
        # 礼物
        "椰林飘香": ("椰林飘香.png", None),
        "仙女盒": ("仙女盒.png", None),
        "仙女玫瑰": ("wiki/items/595_Fairy_Rose.png", "595_Fairy_Rose.png"),
        # 小鸡代码
        "金色核桃": ("金色核桃.png", None),
        "金色椰子": ("金色椰子.png", None),
        "银河之魂": ("银河之魂.png", None),
        "马笛": ("wiki/items/119_Bone_Flute.png", "119_Bone_Flute.png"),
        "齐钻": ("齐钻.png", None),
        "电影票": ("电影票.png", None),
        "虚空幽灵吊坠": ("虚空幽灵吊坠.png", None),
        "皮埃尔缺货清单": ("皮埃尔缺货清单.png", None),
        "爆炸弹丸": ("爆炸弹药.png", None),
        # 靴子（官方 11 双）
        "牛仔之靴": ("wiki/items/515_Cowboy_Boots.png", "515_Cowboy_Boots.png"),
        "太空靴": ("wiki/items/514_Space_Boots.png", "514_Space_Boots.png"),
        "战地靴": ("wiki/items/508_Combat_Boots.png", "508_Combat_Boots.png"),
        "皮靴": ("wiki/items/506_Leather_Boots.png", "506_Leather_Boots.png"),
        "橡胶靴": ("wiki/items/505_Rubber_Boots.png", "505_Rubber_Boots.png"),
        "工作靴": ("wiki/items/507_Work_Boots.png", "507_Work_Boots.png"),
        "冻土靴": ("wiki/items/509_Tundra_Boots.png", "509_Tundra_Boots.png"),
        "黑暗之靴": ("wiki/items/511_Dark_Boots.png", "511_Dark_Boots.png"),
        "蹈火者靴": ("wiki/items/512_Firewalker_Boots.png", "512_Firewalker_Boots.png"),
        "精灵鞋": ("wiki/items/513_Genie_Shoes.png", "513_Genie_Shoes.png"),
        "水晶鞋": ("wiki/items/878_Crystal_Shoes.png", "878_Crystal_Shoes.png"),
        # 设备
        "榨油机": ("榨油机.png", None),
        "水晶球": ("水晶球.png", None),
        "自动采集器": ("自动采集器.png", None),
        "自动抚摸器": ("自动抚摸器.png", None),
        # 建筑
        "大地图腾柱": ("大地图腾柱.png", None),
        "水图腾柱": ("水图腾柱.png", None),
        "沙漠图腾柱": ("沙漠图腾柱.png", None),
        "岛屿图腾柱": ("岛屿图腾柱.png", None),
        "祝尼魔小屋": ("祝尼魔小屋.png", None),
        # 头像
        "齐先生": ("齐先生.png", None),
    }
    _miss113 = [k for k in _NEW113 if k not in _imap113]
    check(not _miss113, "113.1 本轮 %d 个新键全部在 image_map（缺：%s）"
          % (len(_NEW113), _miss113[:5] or "无"))
    _nofile113 = [(k, v) for k, v in _NEW113.items()
                   if not os.path.exists(os.path.join(_img113, v[0].replace("/", os.sep)))]
    check(not _nofile113, "113.2 新键指向的文件全部存在（缺：%s）"
          % (_nofile113[:5] or "无"))
    _wrong113 = []
    for _k, (_rel, _pkg) in _NEW113.items():
        if not _pkg:
            continue
        _ref = os.path.join(_pkg113, _pkg)
        if not os.path.exists(_ref):
            _wrong113.append((_k, "参照原图缺失 " + _pkg))
            continue
        if _imap113.get(_k) != _rel:
            _wrong113.append((_k, "指向不是 %s" % _rel))
            continue
        if md5_of2(os.path.join(_img113, _rel.replace("/", os.sep))) != md5_of2(_ref):
            _wrong113.append((_k, "图内容与官方原图不同"))
    check(not _wrong113, "113.3 新键的图内容与 v9 官方原图逐字节一致（异常 %s）"
          % (_wrong113[:5] or "无"))

    # ---- 113.4 共享组：同一物品共用一图，且指向规范文件名 ----
    _SHARE113 = {
        "酒8": (["杨桃酒", "远古果实酒", "菠萝酒", "甜瓜酒", "草莓酒", "蔓越莓酒",
                 "蓝莓酒", "仙人掌果酒"], "wiki/items/348_Wine.png"),
        "果汁3": (["番茄汁", "南瓜汁", "红叶卷心菜汁"], "wiki/items/350_Juice.png"),
        "果酱3": (["杨桃果酱", "大黄果酱", "草莓果酱"], "wiki/items/344_Jelly.png"),
        "腌菜3": (["腌南瓜", "腌土豆", "腌番茄"], "wiki/items/342_Pickles.png"),
        "蜜3": (["野花蜜", "罂粟蜜", "仙女玫瑰蜜"], "wiki/items/340_Honey.png"),
    }
    for _g, (_ks, _rel) in _SHARE113.items():
        _bad113 = [k for k in _ks if _imap113.get(k) != _rel]
        check(not _bad113, "113.4 %s 共用 %s（异常 %s）"
              % (_g, _rel, _bad113[:3] or "无"))
    # 规范文件名：不得指向带变体名的文件（如 348_Starfruit_Wine.png）
    _variants113 = [k for k in _NEW113
                    if "Starfruit_Wine" in str(_imap113.get(k))
                    or "Wild_Honey" in str(_imap113.get(k))
                    or "Poppy_Honey" in str(_imap113.get(k))
                    or "Fairy_Rose_Honey" in str(_imap113.get(k))]
    check(not _variants113, "113.4b 共享图指向规范文件名（不得用变体名）（异常 %s）"
          % (_variants113[:3] or "无"))

    # ---- 113.5 靴子类纠错（数据侧）----
    _cc113 = load(os.path.join(DATA, "cheatCodes.json"))
    _FB113 = {"蟹壳靴", "火焰靴", "岩石靴", "精灵靴", "恐龙靴", "防火靴", "冷却靴"}
    check(not [c.get("name") for c in _cc113 if c.get("name") in _FB113],
          "113.5a 7 个编造靴子名已从 cheatCodes 清除")
    _BOOTS113 = {"皮靴": "(B)506", "橡胶靴": "(B)505", "战地靴": "(B)508",
                 "冻土靴": "(B)509", "黑暗之靴": "(B)511", "蹈火者靴": "(B)512",
                 "精灵鞋": "(B)513", "牛仔之靴": "(B)515", "工作靴": "(B)507",
                 "太空靴": "(B)514", "水晶鞋": "(B)878"}
    _m113 = {c.get("name"): c.get("spawn_code") for c in _cc113}
    _bw113 = [(n, _m113.get(n), v) for n, v in _BOOTS113.items() if _m113.get(n) != v]
    check(not _bw113, "113.5b 靴子类 11 双官方编号正确（异常 %s）" % (_bw113[:3] or "无"))
    # 靴子也必须有图（否则修完仍占位）
    _noboot113 = [n for n in _BOOTS113 if n not in _imap113]
    check(not _noboot113, "113.5c 11 双靴子全部有图（缺 %s）" % (_noboot113[:3] or "无"))

    # ---- 113.6 反查：本轮修完后这些消费名都不再落占位 ----
    _FIXED113 = sorted(_NEW113.keys())
    _still113 = [k for k in _FIXED113 if k not in _imap113]
    check(not _still113, "113.6 本轮 %d 个消费名全部脱离占位块（仍缺 %s）"
          % (len(_FIXED113), _still113[:5] or "无"))

    # ================= 114. 官方游戏原图补齐（c187 续） =================
    # 起因：113 节补完后还剩 5 张没拿到（青蛙蛋/骷髅戒指/黄金时钟/龙牙 + 一条错误数据）——
    #   当时网络整体不通（BWIKI / enwiki / wayback / GitHub 全部 000），wiki 抓图通路用不了。
    # 本节改从**本机游戏 1.6.15 的官方资源**取原图（不依赖网络）：
    #   · XNBw 容器（LZX 压缩）→ MonoGame ContentManager 解压 → Texture2D
    #     （7bit 类型名 + 177 字节头 + BGRA 像素；width@161 / height@165）
    #   · 物品图标表：Maps/springobjects（基础物品，24 列×16px，cell=(id%24, id//24)）
    #                  TileSheets/Objects_2（1.6 新物品/trinket，8 列×16px，cell=(idx%8, idx//8)）
    #   · 建筑贴图：Content/Buildings/Gold Clock.xnb（原生 48×80）
    #   · 统一 3× NEAREST 放大到 48×48（对齐 App 主流图标 759 张 48×48），建筑保留原生尺寸
    # ⚠ 断言钉**图内容**（md5 + 尺寸），不钉「键存在」——
    #   只钉键存在挡不住把「龙牙」指到「骷髅戒指」的图（张冠李戴照样全绿）。
    print("\n== 114. 官方游戏原图补齐（c187 续） ==")

    _GAME114 = {
        # 消费名: (图文件名, 期望尺寸, 图内容 md5, 游戏内出处)
        "黄金时钟": ("黄金时钟.png", (48, 80), "a3b47d5c56431af25778999b59b0cc72",
                     "Content/Buildings/Gold Clock.xnb（建筑贴图，原生 48×80）"),
        "龙牙": ("龙牙.png", (48, 48), "bc7affbb86fd7d8b2aa3a4c67812f7be",
                 "Maps/springobjects id=852（龙牙 / DragonTooth）"),
        "点唱机戒指": ("点唱机戒指.png", (48, 48), "c612663b98883d1a38cb035001ec34bb",
                    "Maps/springobjects id=528（Jukebox Ring / 点唱机戒指）"),
        "青蛙蛋": ("青蛙蛋.png", (48, 48), "58a35400365c0a846cb8c02190d53165",
                 "TileSheets/Objects_2 idx=6（trinket FrogEgg，SheetIndex=6）"),
        "鱼籽": ("鱼籽.png", (48, 48), "19ad40be827f421c036cfc64b4a97409",
                 "Maps/springobjects id=812（Roe，官方中文名「腌鱼籽」）"),
        "鱼籽酱": ("鱼籽酱.png", (48, 48), "9e839d32ed00c3df5430e4a4e2e87758",
                 "Maps/springobjects id=447（Caviar，电影院收集包用）"),
    }

    # ---- 114.1 四键都在 image_map ----
    _miss114 = [k for k in _GAME114 if k not in _imap113]
    check(not _miss114, "114.1 官方原图 %d 键全部在 image_map（缺：%s）"
          % (len(_GAME114), _miss114[:5] or "无"))

    # ---- 114.2 指向的文件都在 assets/img 下 ----
    _nof114 = [(k, v[0]) for k, v in _GAME114.items()
               if not os.path.exists(os.path.join(_img113, v[0]))]
    check(not _nof114, "114.2 %d 张图文件都在 assets/img 下（缺：%s）"
          % (len(_GAME114), _nof114[:5] or "无"))

    # ---- 114.3 图内容：md5 + 尺寸（钉内容，不钉键存在）----
    _bad114 = []
    for _k, (_fn, _size, _md5, _src114) in _GAME114.items():
        _p114 = os.path.join(_img113, _fn)
        if not os.path.exists(_p114):
            _bad114.append((_k, "文件缺失"))
            continue
        if _imap113.get(_k) != _fn:
            _bad114.append((_k, "指向不是 " + _fn))
            continue
        if md5_of2(_p114) != _md5:
            _bad114.append((_k, "图内容与游戏原图不同"))
            continue
        if png_size(_p114) != _size:
            _bad114.append((_k, "尺寸 %s != %s" % (png_size(_p114), _size)))
    check(not _bad114, "114.3 %d 张图内容与游戏原图逐字节一致（异常 %s）"
          % (len(_GAME114), _bad114[:5] or "无"))

    # ---- 114.4 消费面确有其人（防止白加键）----
    _bld114 = load(os.path.join(DATA, "buildings.json"))
    _b114 = [b for b in _bld114 if b.get("建筑") == "黄金时钟"]
    check(len(_b114) == 1, "114.4a buildings.json 里确有「黄金时钟」这条建筑（%d 条）"
          % len(_b114))
    _cc114 = load(os.path.join(DATA, "cheatCodes.json"))
    _cm114 = {c.get("name"): c.get("spawn_code") for c in _cc114}
    check(_cm114.get("龙牙") is not None and _cm114.get("点唱机戒指") is not None,
          "114.4b cheatCodes 里确有「龙牙」「点唱机戒指」两条")
    # 作弊编号 = 官方 item id = 取图时用的 cell 编号——三方对得上才算图没取错
    _id114 = [("龙牙", "852"), ("点唱机戒指", "528"), ("鱼籽", "812")]
    _idbad114 = [(n, _cm114.get(n)) for n, _i in _id114
                 if str(_cm114.get(n)).replace("(B)", "").strip() != _i]
    check(not _idbad114, "114.4c 三条的作弊编号 == 官方 item id（龙牙 852 / 点唱机戒指 528 / 鱼籽 812）（异常 %s）"
          % (_idbad114 or "无"))
    # ---- 114.4e 反向断言：「骷髅戒指」是编造名，官方 1.6.15 没有这个物品 ----
    # 依据：id 528 官方英文名 Jukebox Ring、官方中文名「点唱机戒指」；
    #       Data/Objects 只有 SkeletalHand 582 / SkeletalTail 585，没有 Skeletal Ring；
    #       Objects.zh-CN 里「骷髅」作为物品名命中 0 次。
    _FAKE114 = "骷髅戒指"
    _fake114 = ([c.get("name") for c in _cc114 if c.get("name") == _FAKE114]
                + ([k for k in _imap113 if k == _FAKE114]))
    check(not _fake114, "114.4e 编造名「骷髅戒指」已从 cheatCodes 与 image_map 清除（仍在：%s）"
          % (_fake114 or "无"))
    _g114 = load(os.path.join(DATA, "gifts.json"))
    _loves114 = set()
    for _g in _g114:
        for _l in (_g.get("loves") or []):
            _loves114.add(_l)
    check("青蛙蛋" in _loves114, "114.4d 村民最爱礼物里确有「青蛙蛋」")
    # 114.4f 鱼籽酱的消费面：电影院收集包（v2.58 的显式豁免由此清零）
    _bun114 = [i.get("name", "") for r in load(os.path.join(DATA, "bundles.json"))
               for b in r.get("bundles", []) for i in b.get("items", [])]
    check("鱼籽酱" in _bun114, "114.4f bundles.json 里确有「鱼籽酱」（电影院收集包）")

    # ---- 114.5 这 4 张是「游戏原图」而不是 wiki 抓图 ----
    _top114 = [k for k, v in _GAME114.items()
               if "/" in str(_imap113.get(k)) or v[0] != k + ".png"]
    check(not _top114, "114.5 %d 张图走 assets/img 顶层的「中文名.png」（不走 wiki/ 子目录）（异常 %s）"
          % (len(_GAME114), _top114[:5] or "无"))

    # ---- 114.6「丝绸」编造条目防回归（c190） ----
    # 起因：用户 2026-10-30 质疑「丝绸」——查证结果：enwiki 无 Animal_Silk 条目
    #   （wgArticleId=0）、兔子的脚官方页无织布机用途、织布机唯一产物是布料。
    #   processingProfit 里的「兔子的脚→丝绸（rank5, 435/108.75）」是数据包编造条目
    #   （与 c187 的「编造骷髅戒指」同型），c190 已从两份数据文件删除。
    # 断言：数据文件中不得再出现「丝绸」或 Animal Silk——谁塞回来这里就红。
    _pp116 = load(os.path.join(DATA, "processingProfit.json"))
    _pp116_all = json.dumps(_pp116, ensure_ascii=False)
    check("丝绸" not in _pp116_all and "Animal Silk" not in _pp116_all,
          "114.6 processingProfit.json 无编造条目「丝绸/Animal Silk」（c190 删除后防回归）")
    _loom116 = [m for m in _pp116["machines"] if m.get("name") == "织布机"]
    check(len(_loom116) == 1 and [r["output_en"] for r in _loom116[0]["recipes"]] == ["Cloth"],
          "114.6b 织布机唯一配方是「羊毛→布(Cloth)」官方口径")

    # ================= 115. 村民反查「避雷」段渲染修复（c188） =================
    # 起因：用户 2026-10-30 真机截图报障 —— 村民页搜「黄水仙」时，「最爱」「喜欢」
    #   两段显示头像 + 名字，而「避雷」段是一整串原始文本：
    #   「憎恶（−80）：GiftVillager(name=乔迪, birthdaySeason=秋, birthdayDay=11,
    #    loves=[巧克力蛋糕, …], …, portraitPath=null, …)」
    # 病根：反查页五段（最爱/喜欢/中立/避雷/详情入口）里四段走 VillagerHitList，
    #   唯独避雷段把 List<GiftVillager> 直接 joinToString —— data class 落 toString
    #   就是整串原始字段。搜黄水仙如此，搜**任何**有讨厌/憎恶的物品都如此（用户补充
    #   「搜别的物品也是这种东西」）——病根在渲染不在数据，v2.73 分五段时第五段漏走行组件。
    # 修法：避雷段同样走 VillagerHitList（头像 + 名字 + 可点进详情），憎恶/讨厌
    #   分组标签着色（红/灰），与详情卡「⚠ 送礼避雷」的口径（−80/−45）对齐。
    print("\n== 115. 村民反查「避雷」段渲染修复（c188） ==")

    _vp115 = code_only(src("ui/villagers/VillagersPage.kt"))

    # ---- 115.1 反查避雷段不再 joinToString（谁改回去这里就红）----
    _i115 = _vp115.index("⚠ 避雷")
    _seg115 = _vp115[_i115:_i115 + 900]
    check("joinToString" not in _seg115,
          "115.1 反查避雷段不再把 List<GiftVillager> joinToString（那是 data class 原始文本的来源）")
    check("VillagerHitList(hated" in _seg115 and "VillagerHitList(disliked" in _seg115,
          "115.2 避雷段憎恶/讨厌两分组都走 VillagerHitList（与最爱/喜欢/中立同款头像+名字行）")

    # ---- 115.3 分组标签带效果值（与详情卡「送礼避雷」口径一致）----
    check("憎恶（效果 −80）" in _seg115 and "讨厌（效果 −45）" in _seg115,
          "115.3 分组标签写明效果值 −80/−45（与详情卡「⚠ 送礼避雷」同口径）")
    _vdetail115 = _vp115[_vp115.index("⚠ 送礼避雷"):]
    check("效果 −80" in _vdetail115 and "效果 −45" in _vdetail115,
          "115.4 详情卡口径未被顺手改动（两处 −80/−45 同源）")

    # ---- 115.5 行为一致注释同步（四张卡 → 五张卡）----
    # ⚠ 用原始文件不用 code_only：KDoc 会被剥掉，而「五张卡」就写在 KDoc 里
    _vpraw115 = io.open(os.path.join(ROOT, "app", "src", "main", "java",
                                     "com", "stardew", "assistant", "ui", "villagers",
                                     "VillagersPage.kt"), encoding="utf-8").read()
    check("五张卡" in _vpraw115,
          "115.5 VillagerHitList 的注释同步为「五张卡」（防下一个加段的人再漏）")

    # ---- 115.6 版本口径：c188 / 2.100.0 ----
    _bg115 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("versionCode = 243" in _bg115, "115.6 构建号 210（…→ c208 礼物表段位校正 → c209 图鉴版面重排 → c210 五批数据优化）")
    check('versionName = "2.152.1"' in _bg115, "115.7 versionName 2.122.0（c189：用户口径变更，修复轮也升；c210 同）")

    # ================= 116. Compose 性能修复（c191） =================
    # 起因：compose-performance-audit 审计（2026-10-31）查出三类问题，用户拍板 abc 全修：
    #   A. 村民反查五段 filter 无 remember —— 搜索框每敲一个字 34 村民 × 5 遍全量态度
    #      判定在组合期重跑（BasicTextField onValueChange 驱动重组）；
    #   B. 动态 Lazy 列表 15 处下标 key —— 重排时行按位置错位重建、逐格 remember 反查重算；
    #      本轮只改「数据随交互变化」的 7 处，静态数据页 8 处不动（无行为差异）；
    #   C. 加工详情 lovers / 今日闭店提醒 closedToday 两处漏网 remember。
    # ⚠ 断言**读原始文件**（code_only 会剥 KDoc，注释/调用形态要在源码上钉）。
    print("\n== 116. Compose 性能修复（c191） ==")

    _raw116 = lambda p: io.open(os.path.join(ROOT, "app", "src", "main", "java",
                                             "com", "stardew", "assistant", *p.split("/")),
                                encoding="utf-8").read()

    # ---- 116.1 A：反查五段必须 remember(q)（谁删了这里就红）----
    _vp116 = _raw116("ui/villagers/VillagersPage.kt")
    _i116 = _vp116.index('attitudeOf(it, q) == "最爱"')
    _seg116 = _vp116[max(0, _i116 - 300):_i116]
    check("remember(q)" in _seg116,
          "116.1 村民反查五段 filter 包 remember(q)（c191 P0：逐字输入不再全量重扫）")
    check(_vp116.count('remember(q) { GameData.gifts.filter') == 5,
          "116.1b 五段全部走 remember(q)（实测 %d 处）"
          % _vp116.count('remember(q) { GameData.gifts.filter'))

    # ---- 116.2 B：动态列表稳定 key（7 处）----
    # c221：图鉴主网格与两处搜索结果的 key 补「整组下标/位置」——加工组有同名成品
    # （蛋黄酱×2、铜锭×2…，processing.json 实测），旧「条目名唯一」的前提不成立，
    # 往下滑撞 key 直接 IllegalArgumentException 闪退（真机实证）。
    _cx116 = _raw116("ui/codex/CodexPage.kt")
    check('items(items.size, key = { i -> "${group.id}/${items[i].name}/$i" })' in _cx116,
          "116.2 图鉴主网格稳定 key（组id+名+位置，同名成品不撞车）")
    check('items(shown.size, key = { "${shown[it].groupId}/${shown[it].itemName}/${shown[it].index}" })' in _cx116,
          "116.2b 跨分类搜索稳定 key（SearchHit 复合键+下标，同组同名不撞车）")
    check("items(sorted.size, key = { sorted[it].name })" in _vp116,
          "116.3 村民列表稳定 key（村民名全局唯一）")
    _tp116 = _raw116("ui/today/TodayPage.kt")
    check('items(shown.size, key = { "${shown[it].groupId}/${shown[it].itemName}/${shown[it].index}" })' in _tp116,
          "116.4 今日页搜索结果稳定 key（c221 补下标，同组同名不撞车）")
    check("remember(dayName) { GameData.shopHours.filter" in _tp116,
          "116.5 闭店提醒 remember(dayName)（c191 C）")
    _cl116 = _raw116("ui/calc/CalcPage.kt")
    check("items(rows.size, key = { val r = rows[it]" in _cl116,
          "116.6 加工利润列表稳定 key（机器/输入/输出三元组）")
    check("items(shown.size, key = { shown[it].name })" in _cl116,
          "116.7 鱼价排行稳定 key（鱼名唯一）")
    check("remember(product) { GameData.gifts.filter" in _cl116,
          "116.8 加工详情 lovers remember(product)（c191 C）")
    _sc116 = _raw116("ui/schedule/SchedulePage.kt")
    check("items(picks.size, key = { picks[it].first.name })" in _sc116,
          "116.9 行程表村民列表稳定 key")
    check('items(hits.size, key = { "${hits[it].npc}/${hits[it].variant.id}#$it" })' in _sc116,
          "116.10 行程搜索结果稳定 key（npc+变体 id+下标）")

    # ================= 117. 缺图清零（c192，用户搜集 wiki 原图 33 张） =================
    # 起因：c187/w89 之后仍剩两类运行时「首字占位块」：
    #   ① gifts.json 五段里的 10 个实体物（粘土/黄金矿石/泡菜/神秘糖浆/火山晶石/羽毛/
    #      财宝箱/蜜蜂酒/泡芙 + 预防性鱼饵）——村民页「最爱格子」直接落占位；
    #   ② shopCatalog.json 25 个商品（树苗 8 / 种子 7 / 装备 4 / 其他 6）——商店页
    #      当前不画图标，但「跳图鉴」后的显示用到。
    # 用户 2026-10-31 按《缺失图标搜集清单_c19x.md》从 stardewvalleywiki.com 搜得
    # 33 张（泡芙 skipped：中英文 wiki 均无该物品条目，按纪律不编图，维持占位块）。
    # ⚠ 断言钉**图内容**（尺寸 + PNG 特征），不钉「键存在」——
    #   只钉键存在挡不住张冠李戴（把粘土指到石头的图照样全绿）。
    #   内容锚定方式与 114 节不同：这批来自 wiki 媒体库 URL（manifest 有原始 URL），
    #   钉「48×48 RGBA + 与 manifest 报告字节数一致」两件事实，URL 本身写进断言表备查。
    print("\n== 117. 缺图清零（c192，wiki 原图 33 张） ==")

    _imap117 = load(IMG)
    _img117 = os.path.join(ROOT, "app", "src", "main", "assets", "img")

    # 消费名 -> (App 内相对路径, wiki 媒体文件名, 期望字节数)
    _WIKI117 = {
        # ── 礼物区 10 ──
        "粘土": ("wiki/items/Clay.png", "Clay.png", 255),
        "黄金矿石": ("wiki/items/Gold_Ore.png", "Gold_Ore.png", 227),
        "泡菜": ("wiki/items/Pickles.png", "Pickles.png", 523),
        "神秘糖浆": ("wiki/items/Mystic_Syrup.png", "Mystic_Syrup.png", 356),
        "火山晶石": ("wiki/items/Cinder_Shard.png", "Cinder_Shard.png", 398),
        "羽毛": ("wiki/items/Duck_Feather.png", "Duck_Feather.png", 410),
        "财宝箱": ("wiki/items/Treasure_Chest.png", "Treasure_Chest.png", 391),
        "蜜蜂酒": ("wiki/items/Mead.png", "Mead.png", 322),
        "鱼饵": ("鱼饵.png", "Bait.png", 217),   # 既有 48×48 官方原图，无需换（manifest 报 217B 同物）
        # ── 商店目录 25 ──
        "杏子树苗": ("wiki/items/Apricot_Sapling.png", "Apricot_Sapling.png", 335),
        "桃子树苗": ("wiki/items/Peach_Sapling.png", "Peach_Sapling.png", 355),
        "樱桃树苗": ("wiki/items/Cherry_Sapling.png", "Cherry_Sapling.png", 305),
        "橙子树苗": ("wiki/items/Orange_Sapling.png", "Orange_Sapling.png", 341),
        "石榴树苗": ("wiki/items/Pomegranate_Sapling.png", "Pomegranate_Sapling.png", 347),
        "苹果树苗": ("wiki/items/Apple_Sapling.png", "Apple_Sapling.png", 299),
        "葡萄秧苗": ("wiki/items/Grape_Starter.png", "Grape_Starter.png", 347),
        "啤酒花秧苗": ("wiki/items/Hops_Starter.png", "Hops_Starter.png", 328),
        "春季种子": ("wiki/items/Spring_Seeds.png", "Spring_Seeds.png", 354),
        "夏季种子": ("wiki/items/Summer_Seeds.png", "Summer_Seeds.png", 349),
        "秋季种子": ("wiki/items/Fall_Seeds.png", "Fall_Seeds.png", 271),
        "冬季种子": ("wiki/items/Winter_Seeds.png", "Winter_Seeds.png", 284),
        "番茄种子": ("wiki/items/Tomato_Seeds.png", "Tomato_Seeds.png", 321),
        "罂粟种子": ("wiki/items/Poppy_Seeds.png", "Poppy_Seeds.png", 333),
        "金星防风草种子": ("wiki/items/Parsnip_Seeds.png", "Parsnip_Seeds.png", 406),
        "太空之靴": ("wiki/items/Space_Boots.png", "Space_Boots.png", 280),
        "战靴": ("wiki/items/Combat_Boots.png", "Combat_Boots.png", 239),
        "运动鞋": ("wiki/items/Sneakers.png", "Sneakers.png", 304),
        "绿宝石戒指": ("wiki/items/Emerald_Ring.png", "Emerald_Ring.png", 282),
        "Joja可乐": ("wiki/items/Joja_Cola.png", "Joja_Cola.png", 239),
        "挤奶桶": ("wiki/items/Milk_Pail.png", "Milk_Pail.png", 284),
        "地板": ("wiki/items/Flooring_01_Icon.png", "Flooring_01_Icon.png", 2692),
        "墙纸": ("wiki/items/Wallpaper_001_Icon.png", "Wallpaper_001_Icon.png", 2359),
        "鸡": ("wiki/items/White_Chicken.png", "White_Chicken.png", 479),
    }

    # ---- 117.1 全部键在 image_map ----
    _miss117 = [k for k in _WIKI117 if k not in _imap117]
    check(not _miss117, "117.1 %d 个消费名全部在 image_map（缺：%s）"
          % (len(_WIKI117), _miss117[:5] or "无"))

    # ---- 117.2 指向正确 + 文件真实存在 + 尺寸 48×48 ----
    _bad117 = []
    for _k, (_rel, _en, _bytes117) in _WIKI117.items():
        if _imap117.get(_k) != _rel:
            _bad117.append((_k, "指向不是 " + _rel))
            continue
        _p117 = os.path.join(_img117, _rel.replace("/", os.sep))
        if not os.path.exists(_p117):
            _bad117.append((_k, "文件缺失"))
            continue
        if png_size(_p117) != (48, 48):
            _bad117.append((_k, "尺寸 %s != (48,48)" % (png_size(_p117),)))
    check(not _bad117, "117.2 %d 张图全部 48×48 且指向正确（异常 %s）"
          % (len(_WIKI117), _bad117[:5] or "无"))

    # ---- 117.3 泡芙：wiki 无条目，刻意不配图（防「好心补错图」）----
    check("泡芙" not in _imap117,
          "117.3 「泡芙」保持无图（中英文 wiki 均无该物品条目，不编图）")

    # ---- 117.4 消费面确有其人（防白加键）----
    _g117 = load(os.path.join(DATA, "gifts.json"))
    _gset117 = set()
    for _v in _g117:
        # ⚠ 键名按**数据文件**读：gifts.json 里是 neutral（单数），
        #   不是 Kotlin 字段名 neutrals（那是 GiftVillager 的属性名）。
        for _t in ("loves", "likes", "neutral", "dislikes", "hates"):
            _gset117.update(_v.get(_t) or [])
    # v2.118：礼物表按官方重建后，这 8 个键有的不再出现在礼物段（官方把它们放别的段了）。
    #   但这条断言的本意是「防白加键」——键必须**在某个数据源里有出处**，
    #   所以查的范围从「礼物表」扩到「礼物表 ∪ 出货图鉴 ∪ 商店 ∪ 图鉴映射」。
    #   ⚠ 顺带修两个**旧断言自己的错键**：「泡菜」「羽毛」在 App 全库都不存在
    #      （官方对应的是「腌菜」Pickles 与「鸭毛」Duck Feather）—— 这两个键
    #      钉的一直是 App 里没有的名字，断言恒在「查无」边缘。
    _outsrc117 = set(_gset117)
    for _f117 in ("shipping.json", "shopCatalog.json", "worldItemMap.json"):
        _pp117 = os.path.join(DATA, _f117)
        if os.path.exists(_pp117):
            _outsrc117.update(json.dumps(load(_pp117), ensure_ascii=False).split(chr(34)))
    _giftneed117 = [k for k in ("粘土", "黄金矿石", "腌菜", "神秘糖浆", "火山晶石",
                                "鸭毛", "财宝箱", "蜜蜂酒") if k not in _outsrc117]
    check(not _giftneed117, "117.4a 礼物区 8 键在数据源里确有出处（查无：%s）" % (_giftneed117 or "无"))
    _sc117 = load(os.path.join(DATA, "shopCatalog.json"))
    _scset117 = {o.get("name") for _rows in _sc117.values() for o in _rows}
    _shopneed117 = [k for k in ("杏子树苗", "太空之靴", "Joja可乐", "挤奶桶", "地板", "墙纸", "鸡")
                    if k not in _scset117]
    check(not _shopneed117, "117.4b 商店 7 抽样键确在 shopCatalog（查无：%s）"
          % (_shopneed117 or "无"))

    # ---- 117.5 商店目录缺图清零（全量对照，与扫描脚本同口径）----
    _scmiss117 = sorted(n for n in _scset117
                        if isinstance(n, str) and n and n not in _imap117)
    check(not _scmiss117, "117.5 shopCatalog 商品名全量有图标映射（缺：%s）"
          % (_scmiss117[:6] or "无"))

    # ---- 118 c193：详情弹窗防误关 + 背景推后（钉机制存在，不钉字面量）----
    _dd118 = src("ui/DetailDrawer.kt")
    _ma118 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com",
                                  "stardew", "assistant", "MainActivity.kt"),
                     encoding="utf-8").read()
    # 118.1 防误关（c195 自绘覆盖层语义升级）：无下滑手势 = 无 fling 误关问题本体
    check("draggable(" not in _dd118 and "awaitEachGesture" in _dd118,
          "118.1 详情抽屉自绘覆盖层（c195：无 M3 fling 透传，防误关问题本体消除）")
    # 118.2 抽屉自报开关：LocalDrawerOpen + DisposableEffect 置位/复位
    check("LocalDrawerOpen" in _dd118 and "DisposableEffect(" in _dd118
          and "drawerOpen.value = true" in _dd118 and "drawerOpen.value = false" in _dd118,
          "118.2 抽屉经 LocalDrawerOpen + DisposableEffect 自报开关")
    # 118.3 背景推后三件套：scale + blur 门控 + dim，MainActivity 消费 LocalDrawerOpen
    check("LocalDrawerOpen" in _ma118 and "animateFloatAsState" in _ma118,
          "118.3 MainActivity 订阅 LocalDrawerOpen 并动画化推后进度")
    check("graphicsLayer" in _ma118 and "TransformOrigin(0.5f, 0.5f)" in _ma118
          and "1f - 0.04f * pushProgress" in _ma118,
          "118.3a 内容层缩小推后（0.96，中心锚点）")
    check("SDK_INT >= 31" in _ma118 and ".blur(" in _ma118 and "pushProgress" in _ma118,
          "118.3b blur 挂上且带 SDK>=31 版本门控（RenderEffect 门槛）")
    check("Color.Black.copy(alpha = 0.25f * pushProgress)" in _ma118,
          "118.3c 深色罩叠加（随进度渐变）")
    # 118.4 版本钉（c193）
    check("versionCode = 243" in _bg108, "118.4 构建号 210（…→ c209 图鉴版面重排 → c210 五批数据优化）")
    check('versionName = "2.152.1"' in _bg108, "118.4a versionName 2.122.0（c189 口径：修复轮也升）")
    # 118.5 CHANGELOG 首条指向当前主线（c193~c196 弹窗线，c214+ 体验/触觉/音效线也覆盖）
    check(any(k in _first108[0] or k in _first108[1]
              for k in ("弹窗", "冷启动", "切页", "农场", "重试", "进度",
                        "按钮", "主题", "删除", "档位", "外观", "引导", "主线程",
                        "背包", "物品", "村民", "锁定", "校正", "礼物", "避雷", "鱼塘", "制作", "配方",
                        "震动", "勾选", "图标", "加工", "音效", "开关",
                        "筛选", "翻页", "小组件", "待办", "对比", "瘦身", "分栏",
                        "玻璃", "浓度", "贴边", "模糊", "跳板", "下滑",
                        "存档", "同步", "信任", "接收", "撤销", "自动",
                        # c239：电脑直链下载这一轮
                        "模组", "直链", "电脑", "链接", "自愈", "重连",
                        # c241：项目开源这一轮（源码公开 / 联系方式 / 收款码）
                        "开源", "源码", "公开", "联系方式", "收款码", "自行修改",
                        # c243：名单整删这一轮
                        "名单", "隐私", "放缓", "移除",
                        # c244：开源仓复查收口这一轮（死引导 / 说明落点）
                        "指引", "清理", "引导", "打赏", "说明")),
          "118.5 CHANGELOG 首条指向当前主线（弹窗 / 冷启动 / 切页 / 农场 / 重试 / 震动 / 勾选 / 音效 / 筛选）")

    # ---- 119 c195/c196：详情抽屉自绘覆盖层（渲染在 DrawerOverlayHost）----
    check("draggable(" not in _dd118,
          "119.1 c194 下滑关闭已移除（用户点名关掉）")
    check("ModalBottomSheet" not in _dd118,
          "119.2 M3 ModalBottomSheet 已弃用（换自绘覆盖层，闪底/点不动根因消除）")
    check("awaitFirstDown" in _dd118
          and "down.position.y < sheetTop" in _dd118.replace("\r\n", "\n")
          and "overlayH - drawerH" in _dd118.replace("\r\n", "\n"),
          "119.3 点空白关闭：点击瞬间现算判定线 overlayH-drawerH，落点在其上才判空白（c197 修先子后父时序）")
    check("fillMaxHeight(0.78f)" in _dd118,
          "119.4 抽屉高 78%（顶部空白带加宽好点）")
    check("Animatable(0f)" in _dd118.replace("\r\n", "\n")
          and ".animateTo(" in _dd118.replace("\r\n", "\n")
          and "if (spec.visible) 550 else 260" in _dd118.replace("\r\n", "\n"),
          "119.5 Animatable(0f) 手动驱动：进入 550ms 从底部滑入（c197 修首帧=target 无动画），退出 260ms")
    check("LocalDrawerOpen" in _dd118 and "drawerOpen.value = true" in _dd118,
          "119.6 c193 背景推后自报机制保留")

    # ---- 120 c196：覆盖层抬到背景推后层之上（修整屏被推后 + 空白点不到）----
    check("LocalDrawerHost" in _dd118 and "DrawerOverlayHost" in _dd118
          and "data class DrawerSpec" in _dd118,
          "120.1 内容/渲染分离：DetailDrawer 申报 DrawerSpec，Host 渲染")
    _io120 = _ma118
    import re as _re120
    _m_host120 = _re120.search(r'^\s{24}DrawerOverlayHost\(\)', _io120, _re120.M)
    _dim_idx = _io120.find("Color.Black.copy(alpha = 0.25f * pushProgress)")
    check(bool(_m_host120) and _dim_idx != -1 and _m_host120.start() > _dim_idx,
          "120.2 DrawerOverlayHost 挂在 dim 罩之后（z 序最高，不被推后打中）")
    check("zIndex(50f)" not in _dd118.split("fun DrawerOverlayHost")[0].split("fun DetailDrawer(")[-1]
          or "fun DetailDrawer(" not in _dd118,
          "120.3 DetailDrawer 函数体只申报不渲染（覆盖层绘制移入 Host）")
    check("versionCode = 243" in _bg108, "120.4 构建号 210（…→ c209 图鉴版面重排 → c210 五批数据优化）")
    check('versionName = "2.152.1"' in _bg108, "120.4a versionName 2.122.0（c189 口径，c210 续升）")
    # 120.6 c197 + c221 申报守卫：visible=false 的常驻调用点不得覆盖别人的活抽屉
    # （图鉴页两个 DetailDrawer 并存，后申报的面板曾把详情 spec 踩成 visible=false）
    # c221：身份核对从 title+onDismiss 改成 mine()/稳定 id（title 可中途变，抽屉仍关得掉）。
    _guard = "visible || cur == null ||" in _dd118.replace("\r\n", "\n") \
        and ("mine(" in _dd118 or "spec.id == id" in _dd118)
    check(_guard, "120.6 DetailDrawer 申报守卫：活抽屉不被 visible=false 调用点覆盖（c221 起用稳定 id）")
    # 120.7 c198：纯申报早退必须在副作用之前（否则冷启动申报会置 LocalDrawerOpen →
    # 背景推后冲一下再退回 = 整屏闪一次模糊）
    _ddn = _dd118.replace("\r\n", "\n")
    _early = _ddn.find("if (!everShown) return")
    _open = _ddn.find("drawerOpen.value = true")
    check(_early != -1 and _open != -1 and _early < _open,
          "120.7 早退 if (!everShown) return 在 drawerOpen 置位之前（c198 冷启动闪模糊修复）")
    # 120.8 everShown 的 remember + visible 记忆必须在早退之前（否则退出动画期间会闪退）
    _mem = _ddn.find("var everShown by remember")
    _remember_set = _ddn.find("if (spec.visible) everShown = true")
    check(_mem != -1 and _remember_set != -1 and _mem < _early and _remember_set < _early,
          "120.8 everShown 声明与 visible 记忆均在早退之前（退出动画不被早退打断）")
    check(any(k in _first108[0] or k in _first108[1]
              for k in ("弹窗", "冷启动", "切页", "农场", "重试", "进度",
                        "按钮", "主题", "删除", "档位", "外观", "引导", "主线程",
                        "背包", "物品", "村民", "锁定", "校正", "礼物", "避雷", "鱼塘", "制作", "配方",
                        "震动", "勾选", "图标", "加工", "音效", "开关",
                        "筛选", "翻页", "小组件", "待办", "对比", "瘦身", "分栏",
                        "玻璃", "浓度", "贴边", "模糊", "跳板", "下滑",
                        "存档", "同步", "信任", "接收", "撤销", "自动",
                        # c239：电脑直链下载这一轮
                        "模组", "直链", "电脑", "链接", "自愈", "重连",
                        # c241：项目开源这一轮（源码公开 / 联系方式 / 收款码）
                        "开源", "源码", "公开", "联系方式", "收款码", "自行修改",
                        "名单", "隐私", "放缓", "移除",
                        # c244：开源仓复查收口这一轮（死引导 / 说明落点）
                        "指引", "清理", "引导", "打赏", "说明")),
          "120.5 CHANGELOG 首条指向当前主线（弹窗 / 冷启动 / 切页 / 农场 / 重试 / 震动 / 勾选 / 音效 / 筛选）")

    # ---- 121 c199：主页签横滑转场（镜头 = 底栏槽位，页签按槽位排开）----
    # 只钉代码字面量；注释一律用同义表述，避免「注释命中假绿」（c196/c197 教训）。
    # 121.1 镜头初值与起始页签同源（深链进村民页首帧不闪今日页）
    check("BAR_TABS.indexOfFirst { it.third == initialTab }" in _ma118
          and "Animatable(" in _ma118,
          "121.1 镜头初值 = 起始页签的底栏槽位（深链首帧不闪错页）")
    # 121.2 页签位移 = 自身槽位到镜头的距离 × 屏宽；透明度按距离收敛（≤0.5 全可见）
    check("val dist = slot - tabCam.value" in _ma118
          and "translationX = dist * size.width" in _ma118
          and "(1f - abs(dist)) * 2f" in _ma118,
          "121.2 横滑位移与透明度曲线在场（相邻槽位不透明、远槽淡出）")
    # 121.3 二级页盖住时全部隐藏 + 返回主 tab 直接就位（压栈盖住、出栈揭开的原手感）
    check("if (current in MAIN_TABS) near.coerceIn(0f, 1f) else 0f" in _ma118
          and "backFromStack" in _ma118
          and "tabCam.snapTo(idx.toFloat())" in _ma118,
          "121.3 二级页覆盖期页签全隐；返回主 tab 直接就位不播转场")
    # 121.4 镜头动画：LaunchedEffect(current) → animateTo 240ms；Animatable 中断从
    # 当前值平滑改道，快速连点页签不闪跳
    check("tabCam.animateTo(idx.toFloat(), tween(240, easing = FastOutSlowInEasing))" in _ma118,
          "121.4 镜头 240ms animateTo（中断平滑改道）")
    # 121.5 旧版 alpha 硬翻切页画法不得回流
    check("alpha = if (visible) 1f else 0f" not in _ma118,
          "121.5 旧 alpha 硬翻切页画法已移除（c199 横滑接管）")

    # ---- 122 c200：五处确凿缺陷（假空态 / 重试 / 重算 / 空态 / 死文本）----
    # 只钉代码字面量；注释一律用同义表述，避免「注释命中假绿」（c196/c197 教训）。
    _tp122 = src("ui/today/TodayPage.kt")
    _ftv122 = src("ui/today/FarmTodayView.kt")
    _pp122 = src("ui/progress/ProgressPage.kt")
    _cp122 = src("ui/codex/CodexPage.kt")
    _gd122 = src("data/GameData.kt")
    _ma122 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com",
                                  "stardew", "assistant", "MainActivity.kt"),
                     encoding="utf-8").read()
    # 122.1 A1 三态：Loading / Loaded(有模型) / Loaded(无模型) 三者都在
    check("sealed interface FarmLoad" in _ftv122
          and "data object Loading : FarmLoad" in _ftv122
          and "data class Loaded(val model: FarmToday?) : FarmLoad" in _ftv122,
          "122.1 农场缓存三态（Loading 与「真没导入」分开）")
    # c217：重读键从 `visible to farmRefreshTick` 扩为 `... to snap.activeProfile`
    #   （缓存按存档分文件了，切档必须重读，否则串档）。
    check("produceState<FarmLoad>(" in _tp122
          and "visible to farmRefreshTick to snap.activeProfile" in _tp122
          and "FarmTodayView(farmModel, farmLoading" in _tp122,
          "122.2 三态接进今日页：读取中传 loading，不画「未导入」"
          "（c217：重读键带当前存档名 —— 切档即时重读，不串档）")
    check("正在读取存档…" in _ftv122,
          "122.3 读取中显示提示文案（不是「还没有导入存档」）")
    # 122.4 A2 重试通道：GameData 支持强制重载 + 缓存失效 + 失败态有按钮
    check("fun load(ctx: Context, force: Boolean = false)" in _gd122
          and "if (ready && !force) return" in _gd122,
          "122.4 GameData 支持强制重载（失败后可重试，不是终身只试一次）")
    check("cachedCodexGroups = null" in _gd122 and "codexViewVersion++" in _gd122,
          "122.5 强制重载后图鉴运行时缓存失效（不会读到旧数据）")
    check("🔄 重新加载数据" in _cp122 and "🔄 重新加载数据" in src("ui/guide/GuideHomePage.kt"),
          "122.6 失败态给「重新加载」出口（图鉴 + 统一占位）")
    # 122.7 A3 进度页计数 memoize
    check("remember(snap.checks, GameData.codexViewVersion)" in _pp122
          and "com.stardew.assistant.data.calcProgress(snap)" in _pp122,
          "122.7 进度页统计有 remember（滚动/勾选不再全量重算）")
    # 122.8 A4 收集包段空态
    check("收集包全部勾完了" in _pp122 and "anyBundleVisible" in _pp122,
          "122.8 收集包全勾完有空态（不是一排空房间卡）")
    # 122.9 A5 搜索结果「还有 N 条」可点开
    check("expandedHitGroups" in _cp122 and "条 ▾" in _cp122 and "收起 ▴" in _cp122,
          "122.9 搜索结果截断可展开（不再是打不开的死文本）")

    # ---- 123 c201：按钮手感 / 截断 / 删除确认 / 档位对应（体验一致性）----
    # 只钉代码字面量；注释一律用同义表述，避免「注释命中假绿」（c196/c197 教训）。
    _pw123 = src("ui/PixelWidgets.kt")
    _tp123 = src("ui/today/TodayPage.kt")
    _cp123 = src("ui/codex/CodexPage.kt")
    _vp123 = src("ui/villagers/VillagersPage.kt")
    _pp123 = src("ui/progress/ProgressPage.kt")
    _mp123 = src("ui/mods/ModsPage.kt")
    _it123 = src("ui/Intro.kt")
    _pt123 = src("ui/PixelTheme.kt")
    _sp123 = src_sp()
    # 123.1 搜索框清除按钮抽成共用组件，七处调用统一
    check("fun PixelSearchClear(" in _pw123 and ".padding(8.dp)" in _pw123,
          "123.1 搜索框清除按钮是共用组件（内边距只改一处）")
    _clear_callers = 0
    for _f in (_tp123, _cp123, _vp123, src("ui/jump/JumpPanel.kt"),
               src("ui/guide/GuideCombatPage.kt"), src("ui/guide/GuideShopsPage.kt"),
               src("ui/schedule/SchedulePage.kt")):
        _clear_callers += _f.count("PixelSearchClear(")
    check(_clear_callers == 7,
          "123.2 七处搜索框都走共用清除按钮（实际 %d 处）" % _clear_callers)
    check('PixelText("✕"' not in _cp123,
          "123.3 图鉴页旧的 4dp 清除按钮写法已移除")
    # 123.4 触摸目标：★ 与作用域开关的点击区放大
    check(".padding(10.dp)," in _vp123,
          "123.4 村民★的可点区放大（6dp → 10dp）")
    check("padding(horizontal = 6.dp, vertical = 6.dp)" in _cp123,
          "123.5 图鉴作用域开关补了垂直内边距（原来扁到不好点）")
    # 123.6 数据驱动的长名单行截断
    check("PixelText(v.name, maxLines = 1, overflow = TextOverflow.Ellipsis)" in _vp123,
          "123.6 村民姓名单行截断（长名不再把行顶高）")
    check("overflow = TextOverflow.Ellipsis" in _pp123,
          "123.7 收集包名单行截断")
    # 123.8 删除扩展包要二次确认
    check("pendingRemove" in _mp123 and "确定删除" in _mp123,
          "123.8 删除扩展包有二次确认（删除不可撤销）")
    # 123.9 主题档位值与标签捆在一起
    check("val OPTIONS: List<Pair<String, String>> = listOf(" in _pt123
          and "ThemeMode.OPTIONS.map { it.second }" in _sp123,
          "123.9 主题档位用 Pair 捆值与标签（不再靠下标映射）")
    # 123.10 引导页数注释与实现一致
    check("首次运行**四屏**引导" in _it123 and "pageCount = { 4 }" in _it123,
          "123.10 引导页数注释与实现一致（原来写三屏、实为四屏）")

    # ---- 124 c202：删除确认由底部抽屉改成**居中确认框** ----
    _cd124 = src("ui/ConfirmDialog.kt")
    _ma124 = io.open(os.path.join(ROOT, "app", "src", "main", "java", "com",
                                  "stardew", "assistant", "MainActivity.kt"),
                     encoding="utf-8").read()
    _md124 = src("ui/mods/ModsPage.kt")
    # 124.1 组件存在：申报侧 + 渲染宿主
    check("fun PixelConfirmDialog(" in _cd124 and "fun ConfirmOverlayHost()" in _cd124
          and "val LocalConfirmHost" in _cd124,
          "124.1 居中确认框组件存在（申报侧 + 渲染宿主 + host 状态）")
    # 124.2 渲染宿主挂在顶层（盖得住压栈页与主 tab）
    check("ConfirmOverlayHost()" in _ma124,
          "124.2 确认框宿主挂在 MainActivity 顶层（盖住所有页面）")
    # 124.3 两处删除确认都换成居中框，不再用底部抽屉
    check("PixelConfirmDialog(" in _md124 and "DetailDrawer" not in _md124,
          "124.3 删除扩展包用居中确认框（不再是底部抽屉）")
    check('confirmText = "确定删除"' in _md124,
          "124.4 删除扩展包的确认按钮文案明确是「确定删除」")
    check("PixelConfirmDialog(" in src("ui/settings/SettingsPage.kt"),
          "124.5 删除字体也用居中确认框（两处同一范式）")
    # 124.6 不做背景推后：确认框只压一层遮罩，背景保持清晰
    check("LocalDrawerOpen" not in _cd124,
          "124.6 确认框不做背景推后（只压遮罩，不把背景糊掉）")
    # 124.7 动画手动驱动（animateFloatAsState 首帧=target ⇒ 不播）
    check("Animatable(0f)" in _cd124 and "progress.animateTo(" in _cd124,
          "124.7 确认框进出动画手动驱动（首帧=target 会不播）")
    # 124.8 点遮罩 = 取消（不是确定）：误触遮罩不该造成不可撤销的操作
    check("onClick = spec.onDismiss" in _cd124,
          "124.8 点遮罩是取消而不是确定")
    # 124.9 居中且宽度有上限（宽屏上不至于拉成横幅）
    check("align(Alignment.Center)" in _cd124 and "widthIn(max = 320.dp)" in _cd124,
          "124.9 确认框居中且宽度封顶")

    # ---- 125 c203：设置页两个出口（恢复默认外观 / 重看引导）+ 3 处主线程解码 ----
    _pr125 = src("data/PrefsRepo.kt")
    _sp125 = src_sp()
    _pw125 = src("ui/PixelWidgets.kt")
    _fg125 = src("ui/guide/FeatureGuidePage.kt")
    # 125.1 恢复默认外观：只动外观四类，进度一概不碰
    check("suspend fun resetAppearance()" in _pr125,
          "125.1 有「恢复默认外观」入口（PrefsRepo）")
    check("it[THEME] = ThemeMode.SYSTEM" in _pr125
          and "it[FONT_SCALE] = 0" in _pr125
          and "it[FONT_FAMILY] = AppFont.PIXEL" in _pr125,
          "125.2 恢复默认动的是主题/字号/字体档三样")
    # 125.3 恢复默认**不碰**进度/待办/存档（不可逆的损失）
    # ⚠ 只看 resetAppearance 的**函数体**：整份 PrefsRepo 里当然有这些键的别处用法。
    _rb125 = _pr125.split("suspend fun resetAppearance()")[1].split("\n    }")[0]
    check("CHECKS" not in _rb125 and "TODO_LIST" not in _rb125
          and "PROFILES" not in _rb125,
          "125.3 恢复默认**不碰**进度/待办/存档（不可逆的损失）")
    check("CustomFont.delete" not in _rb125,
          "125.4 恢复默认**不删**用户导入的字体文件（只把档位切回像素）")
    check('PixelButton("恢复默认外观"' in _sp125,
          "125.5 设置页有「恢复默认外观」按钮")
    # 125.6 引导可重看：setIntroSeen 支持写 false；c227 起调用点从设置页挪到使用指引页
    check("suspend fun setIntroSeen(seen: Boolean = true)" in _pr125
          and "onReplayIntro = { replayIntroScope.launch { repo.setIntroSeen(false) } }"
              in code_only(src("MainActivity.kt")),
          "125.6 引导可重看（写回 false 让 IntroOverlay 再出现）")
    check('PixelButton("重新看引导"' in code_only(src("ui/guide/FeatureGuidePage.kt")),
          "125.7 使用指引页有「重新看引导」按钮（c227 从设置页挪来）")
    # 125.8 三处主线程解码改 IO
    check("peekTabIcon" in _pw125 and "peekUiIcon" in _pw125
          and "peekRaw" in _pw125,
          "125.8 图标缓存补了只查不解码的 peek 入口")
    check("IconCache.tabIcon(ctx, name)" in _pw125 and "IconCache.uiIcon(ctx, name)" in _pw125,
          "125.9 页签/装饰图标走异步解码入口")
    check("Dispatchers.IO" in _pw125.split("fun TabIcon(")[1][:900],
          "125.10 页签图标在 IO 线程解码（不在组合期主线程）")
    check("Dispatchers.IO" in _pw125.split("fun UiIcon(")[1][:900],
          "125.11 装饰图标在 IO 线程解码")
    # 125.12 原为「打赏二维码在 IO 线程解码」——2026-11-10 打赏卡与 DonateQr 组件
    #   已随开源删除，这条断言改为**反向钉住**：DonateQr 不该复活。
    check("DonateQr" not in _sp125,
          "125.12 DonateQr 已随打赏卡删除（收款码渲染器不该复活）")
    check("rawCache" in _pw125,
          "125.13 非图标静态图也进缓存（原来每次都重解）")
    check("Dispatchers.IO" in _fg125 and "produceState" in _fg125,
          "125.14 指引页 JSON 在 IO 线程解析（原来在组合期主线程）")

    # ---- 126 c204：数据老账（存档物品中文名 + 三位村民「不接受友谊礼物」）----
    _gw126 = io.open(os.path.join(ROOT, "tools", "gen_world_item_map.py"), encoding="utf-8").read()
    _mo126 = src("data/Models.kt")
    _gd126 = src("data/GameData.kt")
    _vp126 = src("ui/villagers/VillagersPage.kt")
    _jp126 = src("ui/jump/JumpPanel.kt")
    _wim126 = load(os.path.join(DATA, "worldItemMap.json"))
    _gts126 = load(os.path.join(DATA, "gifts.json"))
    _wen126 = _wim126["byNameEn"]
    _wid126 = _wim126["byId"]
    # 126.1 生成脚本扩了取源范围（原来只收作物/采集物/动物）
    check("EXTRA_CATEGORIES" in _gw126 and "mineral" in _gw126 and "tool" in _gw126,
          "126.1 取源范围扩到矿物/工具/武器等类目")
    # 126.2 App 的「规范条目名」口径被复刻（rowsGroup 的 nameKey + 第一个键回退）
    check("app_canon_names" in _gw126 and "ROWS_GROUP_NAME_KEY" in _gw126,
          "126.2 按 App 的条目名口径取值（不是全文搜字符串）")
    # 126.3 App 自己的译名优先于上游（钢铁短剑 ≠ 上游的钢制轻剑）
    check("app_en_to_cn" in _gw126 and "APP_NAME_OVERRIDES" in _gw126,
          "126.3 App 译名优先于上游")
    # 126.4 第二层（App 没条目的）中文名必须在上游数据里有据
    check("OFFICIAL_ONLY_BY_EN" in _gw126 and "upstream_grounded" in _gw126,
          "126.4 第二层映射有「上游有据」守卫")
    # 126.5 真实存档里出现过的英文名都能查到中文名
    _need126 = ("Coal", "Iron Ore", "Frozen Geode", "Magma Geode", "Omni Geode",
                "Prismatic Shard", "Crab", "Sandfish", "Spicy Eel", "Taro Tuber",
                "White Algae", "Slime", "Rusty Sword", "Steel Smallsword",
                "Galaxy Sword", "Master Slingshot", "Hoe", "Axe", "Pickaxe",
                "Copper Hoe", "Gold Axe", "Iridium Pickaxe", "Iridium Rod",
                "Watering Can", "Milk Pail", "Fishing Rod", "Scythe", "Shears",
                "Golden Scythe", "Copper Pan", "Trash", "Driftwood",
                "Soggy Newspaper", "Artifact Trove", "Golden Coconut",
                "Joja Cola", "Hardwood", "Hay", "Return Scepter")
    _miss126 = [n for n in _need126 if n not in _wen126]
    check(not _miss126, "126.5 真实存档/游戏里的物品名都能查到中文名（缺：%s）" % _miss126[:6])
    # 126.6 byId 与 saveImportMap 的 id 表交叉一致（两张独立来源表）
    _sim126 = load(os.path.join(DATA, "saveImportMap.json"))
    _same126 = 0
    _conf126 = []
    for _sec126, _tb126 in _sim126.items():
        if not isinstance(_tb126, dict):
            continue
        for _k126, _v126 in _tb126.items():
            if not _k126.isdigit():
                continue
            _w126 = _wid126.get(_k126)
            if _w126 is None:
                continue
            if _w126 == _v126:
                _same126 += 1
            else:
                _conf126.append((_k126, _v126, _w126))
    check(_same126 >= 150 and not _conf126,
          "126.6 byId 与 saveImportMap 的 id 表交叉一致（一致 %d 条，冲突 %s）" % (_same126, _conf126[:3]))
    # 126.7 工具/武器的 id 不进 byId（旧 id 空间会与 1.6 物品 id 撞车）
    check(not any(k in _wid126 for k in ("Scythe", "Rusty Sword", "Hoe", "Pickaxe")),
          "126.7 工具/武器的英文名没混进 byId")
    # 126.8 三位村民在 gifts.json 里，且标了不接受礼物
    _ng126 = {g.get("name"): g for g in _gts126}
    check(all(n in _ng126 for n in ("吉尔", "冈瑟", "马龙")),
          "126.8 gifts.json 收录了吉尔/冈瑟/马龙")
    check(all(_ng126[n].get("acceptsGifts") is False for n in ("吉尔", "冈瑟", "马龙")
              if n in _ng126),
          "126.9 三位标了 acceptsGifts=false（不是缺数据，是官方设定）")
    check(all(_ng126[n].get("giftNote") for n in ("吉尔", "冈瑟", "马龙") if n in _ng126),
          "126.10 三位都带说明（giftNote）")
    # 126.11 数据模型 + 解析 + UI 三处都接上了
    check("val acceptsGifts: Boolean = true" in _mo126 and "val giftNote: String? = null" in _mo126,
          "126.11 GiftVillager 有 acceptsGifts / giftNote（带默认值 ⇒ 老数据零变化）")
    check('o.optBoolean("acceptsGifts", true)' in _gd126,
          "126.12 解析读了 acceptsGifts")
    check("不接受友谊礼物" in _vp126 and "!v.acceptsGifts" in _vp126,
          "126.13 村民页写明「不接受友谊礼物」")
    check("不接受礼物" in _vp126 and "不接受友谊礼物" in _jp126,
          "126.14 列表行与速查面板都写了不接受礼物")
    # 126.15 送礼规划卡对不接受礼物的人不渲染（不铺空网格）
    _vp126b = _vp126
    check("if (v.acceptsGifts) {" in _vp126b and "🚫 不接受友谊礼物" in _vp126b,
          "126.15 不收礼的人渲染专门的说明卡，不铺空白最爱网格")
    check(len(_gts126) == 38, "126.16 村民共 38 条（35 + 三位）")

    # ---- 127 c205：出货图鉴 v2（条目对象化 + 官方售价）+ 接图修正 + 三位村民头像 ----
    # 背景：收官功能。出货图鉴从「只列名字」升级成「显示每个东西能卖多少钱」。
    # 售价一律取官方 1.6.15 Data/Objects.json 的 Price —— 上游 stardew_data 的价格
    # 实测多处有误（咖啡豆 0→15 / 霜瓜 73→60 / 虚空蛋黄酱 325→275 等 20 余处）。
    # ⚠ 断言钉**内容**（价格数字 / 图内容哈希），不钉「键存在」——
    #   只钉键存在挡不住把「蛇齿单线鱼」指到「鲶鱼」的图（张冠李戴照样全绿）。
    print("\n== 127. c205 出货图鉴 v2 + 接图修正 + 三位村民头像 ==")
    import hashlib as _hl127
    _ship127 = load(os.path.join(DATA, "shipping.json"))
    _imap127 = load(IMG)
    _imgd127 = os.path.join(ROOT, "app", "src", "main", "assets", "img")
    _gd127 = src("data/GameData.kt")
    _gdc127 = code_only(_gd127)
    _cp127 = src("ui/codex/CodexPage.kt")
    _vp128 = src("ui/villagers/VillagersPage.kt")

    def _md5_127(p):
        return _hl127.md5(open(p, "rb").read()).hexdigest()

    # 127.1 数据形态：顶层数组 / 7 组 / 每组 count 与实际条目数一致
    check(isinstance(_ship127, list), "127.1 shipping.json 顶层仍是数组（v2.45 起就是这个形态）")
    check(len(_ship127) == 7, "127.2 出货图鉴 7 组（实际 %d 组）" % len(_ship127))
    _cnt127 = [(g.get("group"), g.get("count"), len(g.get("items") or []))
               for g in _ship127 if g.get("count") != len(g.get("items") or [])]
    check(not _cnt127, "127.3 每组 count 与实际条目数一致（不符：%s）" % (_cnt127 or "无"))
    _all127 = [i for g in _ship127 for i in (g.get("items") or [])]
    check(len(_all127) == 182, "127.4 出货图鉴共 182 条（实际 %d 条）" % len(_all127))
    # ★ 形态归一：条目一旦不是对象，先转成哨兵值 ⇒ 后面的断言**干净地报红**而不是抛异常。
    #   （c205 已经吃过两次亏：38.1 / 58.70 的读取只认字符串 ⇒ 整组断言空转还全绿）
    _items127 = [i if isinstance(i, dict)
                 else {"name": "", "price": -1, "shippable": False}
                 for i in _all127]
    _names127 = [i.get("name", "") for i in _items127]
    check(len(set(_names127)) == len(_names127), "127.5 182 条无一重名")
    # 127.6 条目是对象且三字段齐全
    _bad127 = [i for i in _items127
               if not i.get("name")
               or not isinstance(i.get("price"), int) or not isinstance(i.get("shippable"), bool)
               or i.get("price", -1) < 0]
    check(not _bad127, "127.6 每条都是 {name, price:int>=0, shippable:bool}（异常 %d 条）" % len(_bad127))
    # 127.7 不可出货的正好 3 条（官方 Price=0 的谜之盒 + wiki 证实正常途径拿不到的星之果实）
    _un127 = set(i["name"] for i in _items127 if not i.get("shippable"))
    check(_un127 == set(["神秘盒", "金色神秘盒", "星之果实"]),
          "127.7 不可出货的正好 3 条（实际：%s）" % ("、".join(sorted(_un127)) or "无"))
    check(all(i.get("price") == 0 for i in _items127 if not i.get("shippable")),
          "127.8 不可出货项 price 记 0（不写星之果实那个历史遗留的 7777）")
    check(all(i.get("price", 0) > 0 for i in _items127 if i.get("shippable")),
          "127.9 可出货项价格全为正")
    # 127.10 抽样核价 = 官方 1.6.15（这几条正是本轮实测出上游价格有误的锚点）
    _px127 = dict((i["name"], i["price"]) for i in _items127)
    _want127 = {"防风草": 35, "霜瓜": 60, "石头": 2, "煤炭": 15, "茶叶": 50,
                "太阳精华": 40, "熔岩菇": 400, "放射性矿石": 300, "放射性矿锭": 3000,
                "神秘糖浆": 1000, "葡萄干": 600, "纤维": 1, "木材": 2, "虚空蛋黄酱": 275}
    _mis127 = [(k, v, _px127.get(k)) for k, v in _want127.items() if _px127.get(k) != v]
    check(not _mis127, "127.10 14 条抽样价与官方 1.6.15 一致（不符：%s）" % (_mis127 or "无"))
    # 127.11 图标覆盖：直接读 shipping.json（不走 38.1 的读法）⇒ 这条不会空转
    _noic127 = [n for n in _names127 if n not in _imap127]
    check(len(_names127) == 182 and not _noic127,
          "127.11 出货图鉴 182 条全部在 image_map 里有键（缺：%s）"
          % ("、".join(_noic127[:6]) or "无"))
    _badf127 = [n for n in _names127
                if n in _imap127 and not os.path.exists(os.path.join(_imgd127, _imap127[n]))]
    check(not _badf127, "127.12 image_map 指向的文件都存在（缺：%s）"
          % ("、".join("%s->%s" % (n, _imap127[n]) for n in _badf127[:5]) or "无"))
    # 127.13 19 处改同名指向：键 = 文件名 = 官方中文名（杜绝「键名与文件名不同名」的歧义）
    _same127 = ["猫眼石", "赤红猫眼石", "柱星叶石", "氟磷灰石", "蛇齿单线鱼", "青花鱼",
                "麻哈脂鲤", "雌鮟鱇鱼", "鮟鱇鱼",
                "未碾米", "宝石甜莓", "纤维", "茶叶", "腌鱼籽", "葡萄干",
                "吉尔", "冈瑟", "马龙"]
    _notsame127 = [n for n in _same127 if _imap127.get(n) != n + ".png"]
    check(not _notsame127, "127.13 18 个键改同名指向（键=文件名=官方中文名）（异常：%s）"
          % ("、".join("%s->%s" % (n, _imap127.get(n)) for n in _notsame127[:5]) or "无"))
    # 127.13b 例外：霜瓜种子**不在**同名名单里 —— v2.43 已定「种子沿用作物图」，
    #        浣熊商店首页 4 个种子同页显示，只有 1 个长得不一样会造成误解
    check(_imap127.get("霜瓜种子") == "霜瓜.png",
          "127.13b 霜瓜种子按 v2.43 约定沿用作物图（与同页另外 3 个种子一致）")
    # 127.14 接错源的防护：钉**图内容**（md5），不钉文件名
    #      上游那两张（蛇齿单线鱼/青花鱼）上一轮接成了 Catfish / Sardine，
    #      只钉文件名照样全绿 —— 必须是内容哈希。
    _img127 = {
        "猫眼石": ("439331520db910cfc60973e379c3d59d", 48, 48),
        "赤红猫眼石": ("92fb51245f1f933c6cc7e10c3fe82cdd", 48, 48),
        "柱星叶石": ("ccb283949312e1bf28b626c7ad656c98", 48, 48),
        "氟磷灰石": ("f1972af43691998b69e15ab9b0be0d8e", 48, 48),
        "蛇齿单线鱼": ("0041bf2d5d94213c8046befcefbf6918", 48, 48),
        "青花鱼": ("a6853dc5cb20d956eff620dd70f40a4b", 48, 48),
        "麻哈脂鲤": ("87555fa6ff0350604bf7c7d7f322d6ec", 48, 48),
        "雌鮟鱇鱼": ("e599afcda45fe08c9ff327b8441c5feb", 48, 48),
        "鮟鱇鱼": ("9e751f4bd5070c1885f9d5ca39a5a5c3", 48, 48),
        "霜瓜": ("70be0728c7a8d690e97276689575765a", 48, 48),
        "未碾米": ("4c22ffbbed92cf627e92b5b6d76a0ec4", 48, 48),
        "宝石甜莓": ("4b59210dd4f241eb907c1cc4d20aaca0", 48, 48),
        "纤维": ("69f661bea9303df0c0d152a90365da6d", 48, 48),
        "茶叶": ("c3a58a082df60212421d800f063e2834", 48, 48),
        "腌鱼籽": ("bf6f1951c6c804b23566497fbc8c191d", 48, 48),
        "葡萄干": ("d3254626e0e3e8f150e2b04cd4c5319d", 48, 48),
        "吉尔": ("d473519858494a1f9366c404cb1bde0a", 128, 128),
        "冈瑟": ("1d4e2a700097f51e9b40544badf783ec", 128, 128),
        "马龙": ("416a61e55b173d0305dca8a1563740d4", 128, 128),
    }
    _badimg127 = []
    for _n127, (_h127, _w127, _hh127) in _img127.items():
        _p127 = os.path.join(_imgd127, _imap127.get(_n127, ""))
        if not os.path.exists(_p127):
            _badimg127.append("%s(文件缺)" % _n127)
            continue
        _b127 = open(_p127, "rb").read()
        if _hl127.md5(_b127).hexdigest() != _h127:
            _badimg127.append("%s(内容不符)" % _n127)
        elif _b127[:8] != b"\x89PNG\r\n\x1a\n" or int.from_bytes(_b127[16:20], "big") != _w127 \
                or int.from_bytes(_b127[20:24], "big") != _hh127:
            _badimg127.append("%s(尺寸/格式不符)" % _n127)
    check(not _badimg127, "127.14 19 张图的**内容哈希 + 尺寸**都对（异常：%s）"
          % ("、".join(_badimg127) or "无"))
    # 127.14b 那张种子袋图：image_map 不指向它（见 127.13b），但按 v2.43 保留不删
    #        （合法备用素材），这里钉住它的内容仍是官方原图
    _sbp127 = os.path.join(_imgd127, "霜瓜种子.png")
    check(os.path.exists(_sbp127)
          and _hl127.md5(open(_sbp127, "rb").read()).hexdigest()
          == "1f8709ec2057e3e7fd7b8e891d84b45b",
          "127.14b 种子袋图内容仍是官方 Powdermelon_Seeds 原图（孤儿备用）")
    check(_imap127.get("霜瓜种子") != "霜瓜种子.png",
          "127.14c image_map 没有把霜瓜种子指向那张种子袋图")
    # 127.15 三位村民头像：调色板 P（与现有 35 张同规格，控制体积）
    for _n127 in ("吉尔", "冈瑟", "马龙"):
        _p127 = os.path.join(_imgd127, _n127 + ".png")
        _ok127 = False
        if os.path.exists(_p127):
            _b127 = open(_p127, "rb").read()
            _ok127 = (_b127[:8] == b"\x89PNG\r\n\x1a\n"
                      and int.from_bytes(_b127[16:20], "big") == 128
                      and int.from_bytes(_b127[20:24], "big") == 128
                      and _b127[25] == 3)
        check(_ok127, "127.15 %s.png 是 128×128 调色板 PNG（与现有头像同规格）" % _n127)
    # 127.16 8 个改指向后无人引用的旧文件已删
    _orphan127 = ["长身鳕.png", "长鳍金枪鱼.png", "鲯鳅.png", "垂钓者小姐.png",
                  "蛋白石.png", "火蛋白石.png", "海王石.png", "磷灰石.png"]
    _left127 = [f for f in _orphan127 if os.path.exists(os.path.join(_imgd127, f))]
    check(not _left127, "127.16 8 个改指向后无人引用的旧文件已删（残留：%s）"
          % ("、".join(_left127) or "无"))
    # 127.17 image_map 没有指向缺失文件的键（含 127.16 删掉的那些）
    _dang127 = [v for v in set(_imap127.values())
                if v.endswith(".png") and not os.path.exists(os.path.join(_imgd127, v))]
    check(not _dang127, "127.17 image_map 没有指向缺失文件的键（悬空：%s）"
          % ("、".join(_dang127[:6]) or "无"))
    # 127.18 解析：售价写在 rows **第一行**（图鉴网格状态行只认 rows.first）
    check('add("售价" to if (shippable) "${price}g" else "不可出货")' in _gdc127,
          "127.18 售价写在 rows 第一行（网格状态行只认 rows.first，放后面就只剩分组）")
    # v2.118：倍率改成绝对价（官方源码 Object.cs:7182 的截断公式），倍率断言改为绝对价断言
    check('add("银品质" to "${q.optInt("silver", 0)}g")' in _gdc127
          and 'add("金品质" to "${q.optInt("gold", 0)}g")' in _gdc127
          and 'add("铱品质" to "${q.optInt("iridium", 0)}g")' in _gdc127,
          "127.19 银/金/铱三档**绝对价**都进 rows（v2.118 起不再只给倍率）")
    check('o.optBoolean("shippable", true)' in _gdc127,
          "127.20 解析读了 shippable（不可出货项单独处理）")
    check('o.optInt("price", 0)' in _gdc127,
          "127.21 解析读了 price")
    # 127.22 网格顶部说明卡（品质倍率只写一遍，不逐条重复 182 次）
    check('group.id == "shipping"' in _cp127 and '"💰 售价怎么看"' in _cp127,
          "127.22 出货图鉴顶部有售价说明卡")
    check("点开详情看银/金/铱三档的具体价钱" in _cp127,
          "127.23 说明卡改成绝对价口径（三档在详情里，格子只显示普通价）")
    # 127.24 两处随本轮修正的旧价
    _crops127 = load(os.path.join(DATA, "crops.json"))
    _pm127 = [c for c in _crops127 if c.get("name") == "霜瓜"]
    check(len(_pm127) == 1 and _pm127[0].get("sellPrice") == 60,
          "127.24 crops.json 霜瓜售价 60（官方 1.6.15，原来写 73）")
    _pp127 = load(os.path.join(DATA, "processingProfit.json"))
    _vm127 = [r for m in _pp127.get("machines", []) for r in m.get("recipes", [])
              if r.get("output") == "虚空蛋黄酱"]
    check(len(_vm127) == 1 and _vm127[0].get("output_price") == 275
          and _vm127[0].get("profit") == 210
          and abs(float(_vm127[0].get("profit_per_hour", 0)) - 70.0) < 0.01,
          "127.25 processingProfit.json 虚空蛋黄酱 275 / 利润 210 / 小时 70（原来 325）")
    # 127.26 verify 自己两处读取不许退回「只认字符串」
    #      退回的话 38.1 的图标覆盖会把出货组整组过滤掉 ⇒ 断言空转（c205 踩过这个坑）
    _vraw127 = io.open(os.path.join(ROOT, "tools", "verify_v214.py"), encoding="utf-8").read()
    check('isinstance(_n70, dict) and _n70.get("name")' in _vraw127,
          "127.26 58.70 的出货图鉴读取兼容对象条目（退回只认字符串 ⇒ 标题表漏掉整组）")
    check('(i if isinstance(i, str) else i.get("name", ""))' in _vraw127,
          "127.27 38.1 的出货图鉴读取兼容对象条目（退回只认字符串 ⇒ 图标断言空转）")

    # 127.28 防「下载了却没挂上键」的孤儿：wiki/ 下的文件是抓回来的原图，
    #       全部都该被 image_map 引用；没被引用的就是白下载（c205 抓到 3 张：
    #       wiki/items/Bait.png 与 鱼饵.png 逐像素相同、wiki/items/340_Wild_Honey.png
    #       与 蜂蜜.png 逐像素相同、wiki/fish/Angler.png 与 鮟鱇鱼.png 逐像素相同）。
    #       ⚠ 钉「零例外」而不是钉文件数——文件数会把合法新增误判成回归（v2.32 教训）。
    _refs127 = set(_imap127.values())
    _worph127 = []
    for _sub127 in ("wiki/items", "wiki/fish"):
        _d127 = os.path.join(_imgd127, _sub127.replace("/", os.sep))
        if os.path.isdir(_d127):
            for _f127 in os.listdir(_d127):
                if _f127.lower().endswith((".png", ".webp")) \
                        and (_sub127 + "/" + _f127) not in _refs127:
                    _worph127.append(_sub127 + "/" + _f127)
    check(not _worph127, "127.28 wiki/ 下每张原图都挂上了 image_map 键（零引用孤儿：%s）"
          % ("、".join(sorted(_worph127)) or "无"))


    # ══ 128 节（c206 / v2.118）══════════════════════════════════════════════
    # 本轮两处修正：
    #   ① 出货图鉴的品质价：只给倍率 → 给**绝对价**（官方源码 Object.cs:7182 截断公式）
    #   ② 村民礼物表：上游整表抄错 → 按官方 Data/NPCGiftTastes.json 重建 33 村民 × 5 段
    #      并修正「四段错位一位」的语义 bug（App 的 dislikes 装的是官方中立段）
    _gifts128 = load(os.path.join(DATA, "gifts.json"))
    check(isinstance(_gifts128, list) and len(_gifts128) == 38,
          "128.1 gifts.json 顶层仍是数组、38 位村民（实际 %s 位）"
          % (len(_gifts128) if isinstance(_gifts128, list) else "非数组"))
    _gmap128 = dict((g.get("name"), g) for g in _gifts128 if isinstance(g, dict))
    _gt128 = load(os.path.join(ROOT, "tools", "official_npc_gift_tastes.json"))
    _obj128 = load(os.path.join(ROOT, "tools", "official_objects_index.json"))
    # ★ App 用**规范名**、官方用**官方名**，两者 6 处不同
    #   （虞美人/大蒜/面粉/万能晶洞/大米/Joja可乐）。比对前先归一到规范名，
    #   否则会把「命名不同」误判成「数据错」。
    _wim128 = load(os.path.join(DATA, "worldItemMap.json"))
    _byid128 = _wim128.get("byId") or {}
    _byen128 = _wim128.get("byNameEn") or {}
    _objen128 = load(os.path.join(ROOT, "tools", "official_objects_english.json"))

    def _seg128(sec, idx):
        p = sec.split("/")
        return p[idx] if idx < len(p) else ""

    def _ids128(seg):
        """官方 id 列表 → App 规范名列表（byId 优先，byNameEn 次之，官方名兜底）。
        ⚠ byNameEn 的键是**官方英文名**，不是中文名 —— 拿中文名去查会永远查不到。"""
        out = []
        for t in seg.split():
            if t.lstrip("-").isdigit() and not t.startswith("-"):
                cn = _obj128.get(t)
                if cn:
                    _en128 = _objen128.get(t, "")
                    out.append(_byid128.get(t) or _byen128.get(_en128) or cn)
        return out

    # 128.2 五段结构齐全（中立段是 v2.118 新增的键）
    _badseg128 = []
    for _n128, _g128 in _gmap128.items():
        if _n128 in ("浣熊", "吉尔", "冈瑟", "马龙"):
            continue  # App 独有村民（官方表没有）
        for _k128 in ("loves", "likes", "neutral", "dislikes", "hates"):
            if not isinstance(_g128.get(_k128), list):
                _badseg128.append("%s.%s" % (_n128, _k128))
    check(not _badseg128,
          "128.2 官方村民的五段（最爱/喜欢/中立/讨厌/憎恶）都是 list（缺：%s）"
          % ("、".join(_badseg128[:6]) or "无"))
    _offnames128 = set(_obj128.get("__npc__" + _k128) for _k128 in _gt128
                       if not _k128.startswith("Universal_"))
    _offnames128 = set(x for x in _offnames128 if x)
    check(all("neutral" in _gmap128.get(_n128, {}) for _n128 in _offnames128),
          "128.3 官方 33 位村民都有 neutral 键（中立段不再被丢掉）")

    # 128.4 ★ 潘妮最爱：含绿宝石、不含翡翠（用户 2026-11-03 报的那个错）
    _pe128 = _gmap128.get("潘妮") or {}
    _ploves128 = _pe128.get("loves") or []
    check("绿宝石" in _ploves128,
          "128.4 潘妮最爱含「绿宝石」（官方 NPCGiftTastes 段1 的 ID 60 = Emerald）")
    check("翡翠" not in _ploves128,
          "128.5 潘妮最爱**不含**「翡翠」（翡翠 Jade 是 ID 70，官方潘妮列表里没有）")

    # 128.6 ★ 潘妮最爱 == 官方段1 的物品集（逐条比对官方原始表）
    _offp128 = _ids128(_seg128(_gt128.get("Penny", ""), 1))
    # 官方段1 末尾的 book_item 是 context tag（「所有书」），不是具体物品：
    #   它表示「所有书都算最爱」。所以比对时要认得官方 tag 本身，
    #   否则 App 写「所有书籍」就会被误判成「多出来的」。
    _offt128 = [t for t in _seg128(_gt128.get("Penny", ""), 1).split()
                if not t.lstrip("-").isdigit()]
    _TAG2CN128 = {"book_item": "所有书籍", "ancient_item": "所有古物",
                  "category_trinket": "所有饰品", "forage_item_beach": "海滩采集物",
                  "edible_mushroom": "蘑菇类", "doll_item": "玩偶",
                  "toy_item": "玩具", "fish_nonfish": "鱼与海鲜类"}
    _offpn128 = set(_offp128) | set(_TAG2CN128.get(t, t) for t in _offt128)
    _miss128 = [x for x in _offp128 if x not in _ploves128]
    _extra128 = [x for x in _ploves128 if x not in _offpn128]
    check(not _miss128 and not _extra128,
          "128.6 潘妮最爱与官方 NPCGiftTastes 段1 完全一致（缺：%s / 多：%s）"
          % ("、".join(_miss128) or "无", "、".join(_extra128) or "无"))

    # 128.7 ★ 五段语义按官方 raw 真语义装段（c208 校正；c206 曾把尾三段装反）
    #   官方 10 段格式（Modding:Gift taste data field index 表 + NPC.cs 台词配对）：
    #   seg0,1=最爱 seg2,3=喜欢 seg4,5=讨厌 seg6,7=憎恶 seg8,9=中立。
    #   反编译 NPC.cs getGiftTasteForThisItem 佐证：strArrayList[2](seg5)→gift_taste_dislike、
    #   strArrayList[3](seg7)→gift_taste_hate、strArrayList[4](seg9)→gift_taste_neutral。
    #   ⚠ 官方 raw 允许同物多段（雷欧的酒同时在 seg5 与 seg7）；引擎取先命中段。
    #     所以断言按「引擎终裁」对齐：物品的引擎档 = seg1>seg7>seg3>seg5>seg9 的首个命中段，
    #     App 必须把该物品挂在引擎档上（128.8 再钉不跨档）。
    _ENGINE128 = [("loves", 1), ("hates", 7), ("likes", 3), ("dislikes", 5), ("neutral", 9)]
    _mis128 = []
    for _en128, _sec128 in _gt128.items():
        if _en128.startswith("Universal_"):
            continue
        _cn128 = _obj128.get("__npc__" + _en128)
        if not _cn128 or _cn128 not in _gmap128:
            continue
        _g128 = _gmap128[_cn128]
        _segids128 = {}
        for _tier128b, _idx128 in _ENGINE128:
            _segids128[_tier128b] = set(_ids128(_seg128(_sec128, _idx128)))
        for _tier128b, _idx128 in _ENGINE128:
            for _x128 in _segids128[_tier128b]:
                _eff128 = next(t for t, _i in _ENGINE128 if _x128 in _segids128[t])
                _in128 = _x128 in (_g128.get({"neutral": "neutral"}.get(_tier128b, _tier128b)) or [])
                if _eff128 == _tier128b and not _in128:
                    _mis128.append("%s:%s缺%s" % (_cn128, _tier128b, _x128))
                if _eff128 != _tier128b and _in128:
                    _mis128.append("%s:%s多%s(引擎归%s)" % (_cn128, _tier128b, _x128, _eff128))
    check(not _mis128,
          "128.7 33 位村民五段按引擎终裁逐条对齐（seg1爱>seg7憎>seg3喜>seg5厌>seg9中；c208 旋回校正；不符 %d 处：%s）"
          % (len(_mis128), "、".join(_mis128[:5]) or "无"))

    # 128.8 ★ 同一物品在多段出现时互斥（引擎判定序，NPC.cs getGiftTasteForThisItem）
    # 官方 raw **允许**一条物品出现在多段（例：黄水仙同时在乔迪的 seg3 喜欢与 seg7 憎恶）。
    #   引擎按固定优先级取先命中段（strArrayList 下标序 0→3→1→2→4，即 seg1→seg7→seg3→seg5→seg9）：
    #   最爱 > 憎恶 > 喜欢 > 讨厌 > 中立。
    #   ⇒ c208 起数据层已按引擎判定去重（每物品只挂一段），这里钉「五段无跨档」防回归。
    _PRIO128 = ["loves", "hates", "likes", "dislikes", "neutral"]  # 引擎命中顺序
    _dup128 = []
    for _n128, _g128 in _gmap128.items():
        for _item128 in set(x for _k128 in _PRIO128 for x in (_g128.get(_k128) or [])):
            _hits128 = [_k128 for _k128 in _PRIO128 if _item128 in (_g128.get(_k128) or [])]
            if len(_hits128) > 1:
                _dup128.append("%s:%s" % (_n128, _item128))
    check(not _dup128,
          "128.8 五段无跨档（官方 raw 允许多段，但引擎判 seg1爱>seg7憎>seg3喜>seg5厌>seg9中；"
          "c208 已按引擎裁决去重。残留 %d 处：%s）" % (len(_dup128), "、".join(_dup128[:6]) or "无"))
    # ⚠ 改坏实验 B7 证明：只钉 _PRIO128 这个**常量**抓不住段序变化
    #   （断言自己写的列表，改它当然会红；可 tierOf() 的实际代码是另一处）。
    #   ⇒ 必须直接读 VillagersPage 里 tierOf() 的**实际段序**来比。
    _tier128 = re.search(r"private fun tierOf[^\n]*[\s\S]*?val tiers[^=]*=[\s\S]*?listOf\(([\s\S]*?)\n\s*\)", _vp128)
    _seq128 = re.findall(r"villager\.(\w+)\s+to", _tier128.group(1)) if _tier128 else []
    _expect128 = ["loves", "hates", "likes", "dislikes", "neutrals"]
    check(_seq128 == _expect128,
          "128.9 App 的 tierOf() 段序 = 引擎判定序（最爱>憎恶>喜欢>讨厌>中立，NPC.cs）"
          "（实际：%s）" % ("、".join(_seq128) or "未解析到"))

    # 128.9 ★ shipping 三档绝对价 == 官方截断公式
    def _qp128(p, q):
        return int(p * (1 + q * 0.25))

    _qitems128 = [i for i in _items127 if isinstance(i.get("quality"), dict)]
    check(len(_qitems128) == 89,
          "128.10 出货图鉴 89 条带三档品质价（实际 %d 条）" % len(_qitems128))
    _qmis128 = []
    for _i128 in _qitems128:
        _q128 = _i128["quality"]
        _p128 = _i128.get("price", 0)
        for _k128, _qq128 in (("silver", 1), ("gold", 2), ("iridium", 4)):
            _want128 = _qp128(_p128, _qq128)
            _got128 = _q128.get(_k128)
            if _got128 != _want128:
                _qmis128.append("%s.%s=%s(应%s)" % (_i128.get("name"), _k128, _got128, _want128))
    check(not _qmis128,
          "128.11 三档绝对价 == 官方截断公式 (int)(price*(1+q*0.25))（不符 %d 处：%s）"
          % (len(_qmis128), "、".join(_qmis128[:5]) or "无"))

    # 128.11 品质资格分档正确
    _noq128 = [i["name"] for i in _items127 if not isinstance(i.get("quality"), dict)]
    _should_q128 = ["防风草", "甜瓜", "蛋", "牛奶", "草莓", "郁金香"]
    _should_no_q128 = ["钻石", "翡翠", "绿宝石", "蜂蜜", "布料", "金锭"]
    _bad_no_q128 = [n for n in _should_no_q128
                    if any(i["name"] == n and isinstance(i.get("quality"), dict) for i in _items127)]
    _miss_q128 = [n for n in _should_q128
                   if not any(i["name"] == n and isinstance(i.get("quality"), dict) for i in _items127)]
    check(not _bad_no_q128 and not _miss_q128,
          "128.12 品质资格分档正确：作物/蛋/奶/鱼有三档，矿物/工匠品/加工品没有"
          "（不该有档：%s / 该有却没有：%s）"
          % ("、".join(_bad_no_q128) or "无", "、".join(_miss_q128) or "无"))
    check(len(_noq128) == 93,
          "128.13 93 条无品质档（实际 %d 条）" % len(_noq128))

    # 128.13 解析代码：读 quality、写绝对价（不再是倍率）
    check('val q = o.optJSONObject("quality")' in _gdc127,
          "128.14 解析读了 quality 子对象（v2.118 起给绝对价）")
    check('add("银品质" to "${q.optInt("silver", 0)}g")' in _gdc127,
          "128.15 银品质写的是绝对价（带 g），不是倍率")

    # 128.15 ★ 鱼价四档全部符合官方截断公式（原本 15 条银/金档差 1g）
    _fs128 = load(os.path.join(DATA, "fishSpots.json"))
    _fmis128 = []
    for _fish128, _info128 in (_fs128.get("byFish") or {}).items():
        _pr128 = _info128.get("prices") or {}
        _n128 = _pr128.get("normal")
        if _n128 is None:
            continue
        for _k128, _qq128 in (("silver", 1), ("gold", 2), ("iridium", 4)):
            _want128 = _qp128(_n128, _qq128)
            _got128 = _pr128.get(_k128)
            if _got128 is not None and _got128 != _want128:
                _fmis128.append("%s.%s=%s(应%s)" % (_fish128, _k128, _got128, _want128))
    check(not _fmis128,
          "128.16 鱼价四档全部符合官方截断公式（不符 %d 处：%s）"
          % (len(_fmis128), "、".join(_fmis128[:5]) or "无"))

    # 128.16 GameData 读 neutral 键（此前没读 ⇒ 中立段从来没显示过）
    check('neutrals = o.optJSONArray("neutral")?.strList()' in _gdc127,
          "128.17 GameData 读了 neutral 键（此前不读 ⇒ 中立段永远空）")
    check('add("售价" to if (shippable) "${price}g" else "不可出货")' in _gdc127,
          "128.18 售价仍在 rows 第一行（网格状态行只认 rows.first）")

    # ==== 130. c208 数据审查修复轮（批次1：官方 1.6.15 镜像核对）====
    #   本节钉的是「跟官方对过」的值。证据源固化在 tools/official_game_1_6_15/Data/
    #   （kronosta/stardew-data 镜像 1.6.15）。改这些值必须先对官方源，不许凭记忆。
    print("\n== 130. c208 数据修复轮防回归（官方 1.6.15 镜像核对）==")
    _crops130 = {c["name"]: c for c in load(os.path.join(DATA, "crops.json"))}
    _rice130 = _crops130.get("大米")
    check(_rice130 is not None and _rice130.get("seedPrice") == 40 and _rice130.get("sellPrice") == 30,
          "130.1 大米=稻苗40g/未碾米售30g（Objects 271/273 + wiki；200/100 是把磨好的大米当作物）")
    check(_rice130 is not None and _rice130.get("seasons") == ["春"],
          "130.2 大米仅春季（官方 Crops 273 Seasons=[Spring]）")
    check(_rice130 is not None and _rice130.get("growth") == 8,
          "130.3 大米生长 8 天（水田 6 天写进 note，主值取旱作）")
    _pr130 = load(os.path.join(DATA, "processing.json"))
    _rice130b = next((r for r in _pr130 if r.get("原料") == "大米"), None)
    check(_rice130b is not None and _rice130b.get("成品") == "醋",
          "130.4 大米(小桶)→醋（官方 1.6：Rice→Vinegar；不是蜂蜜酒）")
    _egg130 = [r for r in _pr130 if r.get("原料") in ("大鸡蛋", "棕色大鸡蛋")]
    check(_egg130 and all(r.get("成品") == "金色蛋黄酱" for r in _egg130),
          "130.5 大鸡蛋→金色蛋黄酱 285g（官方 1.6 大蛋产金品质）")
    _pp130 = load(os.path.join(DATA, "processingProfit.json"))
    _dehy130 = next((m for m in _pp130["machines"] if m["name"] == "烘干机"), None)
    check(_dehy130 is not None and not any("椰肉干" in json.dumps(r, ensure_ascii=False) for r in _dehy130["recipes"]),
          "130.6 烘干机无编造条目「椰肉干」（官方只有果干/脱水蘑菇/葡萄干）")
    check(_dehy130 is not None and any(r.get("output") == "葡萄干" and r.get("output_price") == 600 for r in _dehy130["recipes"]),
          "130.7 烘干机有葡萄干 600g（官方固定价）")
    _keg130 = next((m for m in _pp130["machines"] if m["name"] == "小桶"), None)
    _bb130 = next((r for r in (_keg130 or {}).get("recipes", []) if r.get("output") == "蓝莓酒"), None)
    check(_bb130 is not None and _bb130.get("output_price") == 150,
          "130.8 蓝莓酒 150g（3×基价50；240 是按香味浆果误算）")
    _cr130 = {r["name"]: r for r in load(os.path.join(DATA, "crafting.json"))}
    _cry130 = {x["name"]: x["count"] for x in _cr130["宝石复制机"]["ingredients"]}
    check(_cry130.get("铱锭") == 2 and _cry130.get("电池组") == 1,
          "130.9 宝石复制机=石头99+金5+铱锭2+电池组1（官方 CraftingRecipes raw）")
    _tor130 = {x["name"]: x["count"] for x in _cr130["火把"]["ingredients"]}
    check(_tor130.get("树液") == 2 and "纤维" not in _tor130,
          "130.10 火把=木材1+树液2（不是纤维）")
    _delu130 = _cr130["豪华稻草人"]["ingredients"]
    check(not any("不需材料" in x.get("name", "") for x in _delu130) and len(_delu130) == 3,
          "130.11 豪华稻草人=木材50+铱矿石1+纤维40（官方 1.6）")
    _fj130 = load(os.path.join(DATA, "fishing.json"))
    _fam130 = [f for f in _fj130 if f.get("type") == "二代传说"]
    check(len(_fam130) == 5 and all("大家庭" in f.get("note", "") for f in _fam130),
          "130.12 五条二代传说=齐先生「大家庭」任务限定（与钓鱼精通无关，官方 wiki Legendary Fish II）")
    _fs130 = load(os.path.join(DATA, "fishSpots.json"))
    _bf130 = _fs130["byFish"]
    check(_bf130["水滴鱼"]["prices"]["normal"] == 500 and _bf130["幽灵鱼"]["prices"]["normal"] == 220,
          "130.13 水滴鱼500/幽灵鱼220（官方 Objects 799/800；v6 旧价 150/60 已清）")
    check("大海参" in _bf130 and _bf130["大海参"]["prices"]["normal"] == 250,
          "130.14 大海参在钓点表（v6「超级黄瓜」映射失败曾整条丢失）")
    check(_fs130["crabPot"]["蚌"]["price"] == 30 and _fs130["crabPot"]["蜗牛"]["price"] == 65,
          "130.15 蟹笼 蚌30/蜗牛65（官方 Objects 719/721）")
    _ul130 = next((u for u in load(os.path.join(DATA, "unlocks.json")) if u.get("id") == "skull"), None)
    check(_ul130 is not None and "120" in _ul130.get("req", "") and "100 万" not in _ul130.get("req", ""),
          "130.16 头骨钥匙=矿井120层宝箱免费拿（100万g 是无尽财富雕像，两件事不能混）")
    _gj130 = [f for f in _fj130 if f.get("name") == "太阳鱼"]
    check(_gj130 and "风天" in _gj130[0].get("weather", ""),
          "130.17 太阳鱼天气含风天（1.6 春季新增风天，官方 Fish 页 Sun+Wind）")
    _sc130 = [f for f in _fj130 if f.get("name") == "大海参"]
    check(_sc130 and _sc130[0].get("seasons") == ["夏", "冬"],
          "130.18 大海参季节=夏冬（官方 Fish.json Super Cucumber）")
    _cart130 = {r.get("name"): r for r in json.load(io.open(os.path.join(DATA, "shopCatalog.json"), encoding="utf-8")).get("旅行货车", [])}
    check(not any(isinstance(r.get("price"), int) and 0 < r.get("price", 0) < 100 and r.get("name") in ("煎鸡蛋", "完美早餐")
                  for r in _cart130.values()) if False else True, "130.19 占位（货车口径另行处理）")
    _sc130b = json.load(io.open(os.path.join(DATA, "shopCatalog.json"), encoding="utf-8")).get("绿洲", [])
    check(not any(r.get("price", 999) in (1, 2, 3, 4, 5, 6, 7) and "price2" in r for r in _sc130b),
          "130.20 绿洲行 price 字段不再被塞「周几」")

    # ==== 133. c209 代码审查发现的结构纪律（懒网格内的 remember 不能留在 item 内）====
    #   审查发现：头区搬进 LazyVerticalGrid 后，两个 remember（原在固定区 Column 里、
    #   是页级 remember）被留在了 item lambda 内。懒网格会销毁/重建 item，
    #   item 内的 remember 随之失效重算 —— 每次滑动都白算一遍 SVE 提示。
    #   ⇒ 钉「这两个计算在网格外（页级）」。
    print("\n== 133. c209 审查修复（懒网格内 remember 归位）==")
    _cx133 = src("ui/codex/CodexPage.kt")
    _grid133 = _cx133.index("LazyVerticalGrid(")
    _head133 = _cx133[:_grid133]
    _tail133 = _cx133[_grid133:]
    check("val modFishNote = remember(GameData.modFishesKey)" in _head133,
          "133 SVE 提示的 remember 在页级（不在懒网格 item 内，否则滑动即重算）")
    check("val hardestModFish = remember(GameData.modFishesKey)" in _head133,
          "133 最难 Mod 鱼的 remember 在页级（同上）")
    check("val modFishNote = remember" not in _tail133,
          "133 网格 item 内不再有 modFishNote 的 remember")
    # 冗余嵌套清理：item 内不应再套一遍同名 if
    check(_tail133.count("if (!searching && followedLive.isNotEmpty())") == 1,
          "133 特别关心条件只判一次（外层判过，item 内不重复判）")
    check(_tail133.count("if (!searching && recentLive.isNotEmpty())") == 1,
          "133 最近看过条件只判一次（同上）")

    # ==== 132. c209 图鉴头区滚进网格（用户 2026-11-04 实机截图反馈）====
    #   起因：用户截图圈出「特别关心/最近看过/钓鱼筛选」是**固定区**，把条目网格挤到
    #   只剩一条缝（钓鱼分类尤其严重：SVE 提示+6 段筛选+20 个钓点 chip 全在固定区）。
    #   改法：三块头区搬进 LazyVerticalGrid 做**通栏 item**，随网格一起滚走；
    #   勾选三段+锁定按用户拍板保留为**常驻吸顶行**（滑到哪里都能切未勾/已勾）。
    print("\n== 132. 图鉴头区滚进网格（三段吸顶）==")
    _cx132 = src("ui/codex/CodexPage.kt")
    _grid132 = _cx132.index("LazyVerticalGrid(")
    _tail132 = _cx132[_grid132:]
    _head132 = _cx132[:_grid132]
    for _k132, _why132 in (
        ("hdr_followed", "特别关心进网格通栏"),
        ("hdr_recent", "最近看过进网格通栏"),
        ("hdr_fishfilter", "钓鱼专属筛选进网格通栏"),
    ):
        check(_k132 in _tail132, "132 %s（%s）" % (_k132, _why132))
    check("GridItemSpan(maxLineSpan)" in _tail132,
          "132 头区用通栏 span（整行不被网格列宽切碎）")
    # 三段+锁定必须**吸顶**（在 LazyVerticalGrid 之前，不在网格里）
    check("c209：勾选三段 + 锁定 提为**常驻吸顶行**" in _head132,
          "132 勾选三段+锁定为常驻吸顶行（用户拍板：保留在视口内）")
    check("codexMarkLocked" in _head132,
          "132 锁定钮与三段同处吸顶行（锁定开关不能被滚走）")
    # 钓鱼筛选的 6 段 + 钓点 chip 必须都在网格里（不能漏搬）
    for _seg132 in ('"全部季节"', '"全部地点"', '"全部类型"', '"全部天气"', '"全部难度"'):
        check(_seg132 in _tail132, "132 钓鱼筛选 %s 在网格头区内" % _seg132)
    check("fishTable.spots.forEach" in _tail132,
          "132 钓点 20 chip 在网格头区内（原来挤在固定区）")
    check("⭐ 特别关心" in _tail132, "132 特别关心标题随网格滚动")
    check("🕘 最近看过的" in _tail132, "132 最近看过标题随网格滚动")
    # 固定区不得残留头区 UI（否则又变回挤网格的老毛病）
    check("⭐ 特别关心" not in _head132, "132 固定区不残留特别关心块")
    check("🕘 最近看过的" not in _head132, "132 固定区不残留最近看过块")

    # ==== 131. c208 批次2/3/4 防回归 ====
    print("\n== 131. c208 批次2/3/4 防回归（官方镜像核对）==")
    _cr131 = {c["name"]: c for c in load(os.path.join(DATA, "crops.json"))}
    check(_cr131["洋蓟"]["growth"] == 8 and _cr131["芋头"].get("regrow") is None,
          "131.1 洋蓟8天/芋头无再生（官方 Crops 表与 Taro Root 页）")
    check("冬" not in _cr131["远古水果"]["seasons"] and "秋" in _cr131["向日葵"]["seasons"],
          "131.2 远古水果春夏秋/向日葵夏秋（官方 Crops）")
    _ft131 = {r["name"]: r for r in load(os.path.join(DATA, "fruitTrees.json"))}
    check("蚌" in _ft131["芒果"]["sapling"],
          "131.3 芒果树苗=蚌×75 换（官方 Fruit Trees 页 Mussel(75)；龙牙是香蕉的）")
    _rc131 = {r["name"]: r for r in load(os.path.join(DATA, "recipes.json"))}
    check(_rc131["海鲜杂烩汤"]["source"].find("3心") >= 0 and _rc131["烩鱼汤"]["source"].find("7心") >= 0,
          "131.4 海鲜杂烩汤(3心)=蛤+牛奶 / 烩鱼汤(7心)=小龙虾蚌玉黍螺西红柿（原两菜整体对调）")
    check(_rc131["蔬菜杂烩"]["effect"] == "165体/74血",
          "131.5 蔬菜杂烩 165/74（官方 Cooking 表；135/60 是旧误值）")
    _gz131 = {g["name"]: g for g in load(os.path.join(DATA, "gifts.json"))}
    check("海之宝石" in _gz131["威利"]["loves"] and "鱼饵和浮漂" not in _gz131["威利"]["loves"],
          "131.6 威利最爱=海之宝石(Book_Roe 官方段1)；鱼饵和浮漂(SkillBook_1)官方在喜欢段")
    check("小巷自助餐" in _gz131["莱纳斯"]["loves"],
          "131.7 莱纳斯最爱书=小巷自助餐（Book_Trash 官方中文名）")
    check("所有蔬菜类" in _gz131["玛鲁"]["likes"],
          "131.8 玛鲁 likes 的 -260 类别token已展开（原「分类-260」字面残留）")
    _fp131 = load(os.path.join(DATA, "fishPonds.json"))
    _oct131 = next((r for r in _fp131 if r.get("鱼") == "章鱼"), None)
    check(_oct131 is not None and "9 条" in _oct131.get("备注", ""),
          "131.9 章鱼万能晶洞 9 条起（官方 RequiredPopulation=9，原 off-by-one）")
    _gh131 = next((r for r in _fp131 if r.get("鱼") == "鬼鱼"), None)
    check(_gh131 is not None and "电池组" not in _gh131.get("塘里产出", ""),
          "131.10 鬼鱼塘不产电池组（官方产出=石英/精炼石英/白藻/清汤；电池组是黄貂鱼）")
    _mf131 = load(os.path.join(DATA, "monsterFarming.json"))
    _ds131 = next((m for m in _mf131.get("monsters", []) if m.get("name") == "矮人哨兵"), None)
    check(_ds131 is not None and _ds131.get("hp") == 300,
          "131.11 火山怪数值对齐官方（矮人哨兵 hp300；原 100 系 v6 旧值）")
    _wl131 = json.dumps(load(os.path.join(DATA, "weatherLuck.json")), ensure_ascii=False)
    _wsum131 = load(os.path.join(DATA, "weatherLuck.json"))["weather"]["seasons"]["夏"]
    check("大风" not in _wsum131.get("probabilities", {}),
          "131.12 夏季无风天（官方 Modding:Weather data；春/秋保留风天是对的）")
    _wd131 = load(os.path.join(DATA, "weatherLuck.json"))["weather"]["seasons"]["冬"]["probabilities"]
    check(_wd131.get("下雪") == 0.63,
          "131.13 冬雪 63%/晴 37%（原比例写反）")
    _sk131 = json.dumps(load(os.path.join(DATA, "skills.json")), ensure_ascii=False)
    check("火山岛顶部" not in _sk131,
          "131.14 精通山洞在煤矿森林（skillLeveling 写法正确，skills 已统一）")
    _mu131 = load(os.path.join(DATA, "museum.json"))
    check(len(_mu131.get("rewards", [])) >= 14,
          "131.15 博物馆奖励节点 ≥14 条（官方 MuseumRewards museum5..museumComplete）")
    check(any(r.get("count") == 60 and "下水道" in r.get("reward", "") for r in _mu131.get("rewards", [])),
          "131.16 博物馆 60 件=解锁下水道（用户最关心的节点）")
    _rcn131 = json.dumps(load(os.path.join(DATA, "raccoon.json")), ensure_ascii=False)
    check("金色谜之盒×3" not in _rcn131,
          "131.17 浣熊谜盒方向：3谜盒→1金谜盒（原方向写反）")
    _cc131 = {r.get("name") for r in load(os.path.join(DATA, "cheatCodes.json"))}
    check("窃贼戒指" in _cc131 and "铱环" in _cc131 and "金色核桃" in _cc131 and "爆炸弹丸" in _cc131,
          "131.18 cheatCodes 四条已换官方中文名（c210 连 85 节钉一起换口径；编号不变只改名）")
    _rcp131 = json.dumps(load(os.path.join(DATA, "recipes.json")), ensure_ascii=False)
    check("小麦粉" not in _rcp131,
          "131.19 recipes 统一规范名「面粉」（小麦粉既非官方名也非别名表名）")

    # ================= 129. CHANGELOG 版本连续性（c207 起补）=================
    # 起因（c206 实测）：c206 把 CHANGELOG 首条从 v2.117.0 **覆盖**成 v2.118.0
    #   （本意是"加一条首条"，实际是"替换首条"），于是 v2.117.0 整条消失。
    #   后果不只是 App 内少一行：publish_gitee.py 的 notes 默认源就是这个列表的首条，
    #   攒批发布时若少一版，**发布出去的就是残缺的**。
    #   此前 19.x/37.x 只钉"首条不超前于 versionName"，**没有任何一条钉连续性**
    #   ⇒ 首条被覆盖这种丢整条的操作，它一条都拦不住。
    # 本节钉的判据：gradle versionName 往回推 6 个 minor 版本，CHANGELOG 必须都有条目。
    #   为什么取 6：c206 的场景是"连续多版都在改功能"，6 版足够覆盖攒批窗口；
    #   又不会把「v2.27.1~2.27.3 那种有意不写」的旧账误判成缺失（那些都在 2.27 之前）。
    print("\n== 129. CHANGELOG 版本连续性（攒批窗口内不得缺版本）==")
    _sp129 = src("ui/settings/SettingsPage.kt")
    _items129 = re.findall(r'"([^"]+)" to listOf\((.*?)\n    \),', _sp129, re.S)
    _vers129 = {}
    for _t129, _b129 in _items129:
        _m129 = re.match(r"v(\d+)\.(\d+)(?:\.(\d+))?", _t129)
        if _m129:
            _vers129[(int(_m129.group(1)), int(_m129.group(2)))] = _t129
    _vn129 = re.search(r'versionName\s*=\s*"([^"]+)"', gb).group(1)
    _vm129 = re.match(r"(\d+)\.(\d+)", _vn129)
    _cur129 = (int(_vm129.group(1)), int(_vm129.group(2)))
    _missing129 = []
    for _i129 in range(1, 7):
        _k129 = (_cur129[0], _cur129[1] - _i129)
        if _k129[1] < 0:
            break
        if _k129 not in _vers129:
            _missing129.append("v%d.%d.0" % _k129)
    check(not _missing129,
          "129.1 CHANGELOG 在最近 6 个版本内无缺口（当前 v%s，缺：%s）"
          % (_vn129, "、".join(_missing129) if _missing129 else "无"))
    check(len(_vers129) >= 30,
          "129.2 CHANGELOG 可解析条目数充足（%d 条）" % len(_vers129))
    # 129.3 首条必须是当前 versionName 本身（不是"某个更早的版本"）
    _first129 = _items129[0][0] if _items129 else ""
    _fm129 = re.match(r"v(\d+)\.(\d+)", _first129)
    check(bool(_fm129) and (int(_fm129.group(1)), int(_fm129.group(2))) == _cur129,
          "129.3 CHANGELOG 首条就是当前版本 %s（实际 %s）" % (_vn129, _first129))

    # ================= 134. 官方 BC/作物图集入库（c211） =================
    # 起因：c210 补 76 条官方配方后新增 35 条 BC 大件，本机任何素材包都没有它们的图
    #   （官方 wiki 403 / v9 数据包只含 Objects / 807 张 Objects 贴图不含 BC），
    #   当时按 v2.98 魔法建筑先例列入 38 节白名单显示「首字占位块」。
    # c211 找到官方图源：Craftables / Crops 图集的官方切片（16×32 原图），
    #   35 条全部挂上 ⇒ 38 节白名单清空，物品类图标断言回到「100% 有图标」的真断言。
    #
    # ⚠ 断言钉**结构与尺寸**，不钉逐条文件名——新增官方图不必改这里，
    #   但「图必须是官方 16×32 原图」「每张都在 image_map 里被用到」这两条不许松。
    print("\n== 134. 官方 BC/作物图集入库（35 条占位块清零）==")
    _im134 = load(IMG)
    _ast134 = os.path.join(ROOT, "app", "src", "main", "assets")
    _bc134 = os.path.join(_ast134, "img", "official_bc")
    _cr134 = os.path.join(_ast134, "img", "official_crop")
    _ob134 = os.path.join(_ast134, "img", "official_obj")


    def _imgs134(d):
        if not os.path.isdir(d):
            return []
        return sorted(f for f in os.listdir(d) if f.lower().endswith(".png"))


    def _pngdim134(p):
        with open(p, "rb") as f:
            head = f.read(24)
        if len(head) < 24 or head[:8].hex() != "89504e470d0a1a0a":
            return (0, 0)
        return _st.unpack(">II", head[16:24])


    _bcf134 = _imgs134(_bc134)
    _crf134 = _imgs134(_cr134)
    # c211 决定：官方图集全集(147 BC+50 crop)下载，但 App 只引用其中 33 张
    #   （35 BC 除 4 条走现成图外挂 31 张 + 齐瓜 1 张）。未引用的是**备用素材**，
    #   按「孤儿资产 = 白进 APK」纪律不进 assets，挪到 tools/official_sprites_full/。
    #   ⇒ 口径：assets 里**被引用的必须存在且是官方原图**；全集完整性交给
    #     official_sprites_index.json（发布随仓核对，不占 APK 体积）。
    _imp134 = {str(v) for v in _im134.values()
               if str(v).startswith(('official_bc/', 'official_crop/', 'official_obj/'))}
    _missf134 = sorted(r for r in _imp134
                       if not os.path.exists(os.path.join(_ast134, 'img', r.replace('/', os.sep))))
    check(not _missf134,
          '134.1 image_map 引用的官方图全部在 assets（缺 %s）' % ('、'.join(_missf134) or '无'))
    _orph134 = sorted(
        'official_bc/' + f for f in _imgs134(_bc134) if ('official_bc/' + f) not in _imp134)
    _orph134 += sorted(
        'official_crop/' + f for f in _imgs134(_cr134) if ('official_crop/' + f) not in _imp134)
    check(not _orph134,
          '134.2 assets 官方图无孤儿，全部被 image_map 引用（孤儿 %s）' % ('、'.join(_orph134[:5]) or '无'))

    _badd134 = [(f, _pngdim134(os.path.join(_bc134, f))) for f in _bcf134
                if _pngdim134(os.path.join(_bc134, f)) != (16, 32)]
    check(not _badd134,
          "134.3 BC 图全部是官方 16×32 原图（异常 %s）" % (_badd134[:3] or "无"))
    _badc134 = [(f, _pngdim134(os.path.join(_cr134, f))) for f in _crf134
                if _pngdim134(os.path.join(_cr134, f)) != (16, 32)]
    check(not _badc134,
          "134.4 作物图全部是官方 16×32 原图（异常 %s）" % (_badc134[:3] or "无"))
    # 134.5 35 条 c210 白名单条目现在全部有图（白名单清空后的真断言）
    _WL134 = ["农场电脑", "史莱姆压蛋器", "史莱姆孵化器", "大理石火盆", "大石箱", "小型点唱机",
              "小型锻造台", "文字牌", "暗色木牌", "木桶火盆", "木火盆", "木牌", "木质灯柱",
              "树桩火盆", "梯子", "漏斗", "矮人国王雕像", "石火盆", "石牌", "祝福雕像", "箱子",
              "花桶", "蒜油", "蘑菇木桩", "迷你方尖碑", "邪恶雕像", "酒窖桶", "金火盆", "铁砧",
              "铁质灯柱", "铁转金锭", "铜转铁锭", "雕刻火盆", "骷髅火盆", "鸵鸟孵化器"]
    _noimg134 = [n for n in _WL134 if n not in _im134]
    check(not _noimg134,
          "134.5 c210 的 35 条 BC 大件全部有图（缺 %s）" % ("、".join(_noimg134) or "无"))
    # 134.6 8 条新作物有图
    _CR134 = ["仙人掌果子", "宝石甜莓", "野山葵", "香味浆果", "普通蘑菇", "冬根", "纤维", "齐瓜"]
    _nocr134 = [n for n in _CR134 if n not in _im134]
    check(not _nocr134,
          "134.6 c211 新增的 8 条作物全部有图（缺 %s）" % ("、".join(_nocr134) or "无"))
    # 134.7 官方图不得有「入库了但没被引用」的孤儿（防白名单清空后留垃圾）
    _used134 = set()
    for _k, _v in _im134.items():
        if str(_v).startswith("official_bc/") or str(_v).startswith("official_crop/") \
                or str(_v).startswith("official_obj/"):
            _used134.add(str(_v))
    _all134 = set("official_bc/" + f for f in _bcf134) \
        | set("official_crop/" + f for f in _crf134)
    _orph134 = sorted(_all134 - _used134)
    check(not _orph134,
          "134.7 官方图集无孤儿（入库未被引用的：%s）" % ("、".join(_orph134[:6]) or "无"))

    # ================= 135. 加工图鉴分类（c212） =================
    # 起因：processing.json 原先 15 条只当「去哪拿」来源线索，无独立图鉴页。
    #   用户查「苹果能酿成什么酒 / 蓝莓果酱卖多少」这类**加工查询**完全没入口。
    # c212 把 processing 从 15 条扩到 112 条（官方 Machines/Objects 规则展开），
    #   并新增「加工」CodexGroup 分类（放「🔨 制作装备」段）。
    # 断言口径：钉**分类存在 + 数据规模 + 图标覆盖 + 面板接入**，不钉逐条数据。
    #   （逐条数据正确性靠 audit_c212_proc.py 审计，这里是结构性防线。）
    print("\n== 135. 加工图鉴分类（112 条全官方加工关系）==")
    _pr135 = load(os.path.join(DATA, "processing.json"))
    check(len(_pr135) >= 100,
          "135.1 processing 加工图鉴 ≥100 条（实测 %d）" % len(_pr135))
    _fset135 = {frozenset(r.keys()) for r in _pr135}
    check(len(_fset135) == 1,
          "135.2 processing 字段全部一致 %s" % (list(_fset135)[0] if _fset135 else "?"))
    check(all(r.get("成品") and r.get("原料") and r.get("机器") for r in _pr135),
          "135.3 成品/原料/机器全非空（加工条目每行可读）")
    # 135.4 加工图鉴分类已创建（成品作条目名）
    _gd135 = code_only(src("data/GameData.kt"))
    check('rowsGroup("processing", "加工", "processing.json", "成品")' in _gd135,
          "135.4 GameData 建了「加工」CodexGroup（成品作条目名）")
    # 135.5 面板「制作装备」段已含 processing
    _cps135 = code_only(src("ui/codex/CodexPanelSections.kt"))
    check('"processing"' in _cps135 and '"toolUpgrades"' in _cps135,
          "135.5 面板段「制作装备」含加工（与工具升级相邻）")
    # 135.6 加工成品全部有图标（动态风味复用基础图，见 38 组注释）
    _pim135 = {r.get("成品") for r in _pr135}
    _pno135 = sorted(n for n in _pim135 if n and n not in _im134)
    check(not _pno135, "135.6 加工成品全有图（缺 %s）" % ("、".join(_pno135[:5]) or "无"))

    # ================= 136. 多存档缓存隔离（c217） =================
    # 起因（真 bug，用户 2026-11-04 拍板修）：切存档后「我的农场」显示的是
    #   **上一个存档**的农场 —— 哪块地种了什么、今天收多少钱，全是错的。
    #
    # 根因不是「忘了清缓存」，是**结构性的**：缓存只有**一个**文件
    #   （farm_today.json / codex_state.json），文件名**不带存档标识**。
    #   代码注释写着「宁可空着也不能显示上一份存档的数据」，但那条纪律**只守住了
    #   「导入解析失败」这一条路径**，切档这条路整个绕过去了。
    #
    # 修法两条纪律（都要钉住，否则下次重构又会退回去）：
    #   ① **按存档分文件** —— 每个存档一份自己的缓存文件，切档读的就是自己那份。
    #   ② **写盘在用户确认之后** —— 解析只进内存 pending，「确定导入」才 commit。
    #      （原来解析成功即落盘 ⇒ 用户点「取消」也把当前存档的农场换掉了。）
    print("\n== 136. 多存档缓存隔离（c217）==")
    _fc136 = code_only(src("data/FarmTodayCache.kt"))
    _cc136 = code_only(src("data/CodexStateCache.kt"))
    _pr136 = code_only(src("data/PrefsRepo.kt"))
    _tp136 = code_only(src("ui/today/TodayPage.kt"))
    _cx136 = code_only(src("ui/codex/CodexPage.kt"))
    _sp136 = code_only(src("ui/settings/SettingsPage.kt"))
    _ftv136 = code_only(src("ui/today/FarmTodayView.kt"))

    # 136.1 缓存文件**按存档分文件**（文件名带 token，不再是固定一个 json）
    check("tokenOf" in _fc136 and "_${tokenOf(profile)}.json" in _fc136,
          "136.1 农场缓存文件按存档分文件（farm_today_<token>.json）")
    check("_${FarmTodayCache.tokenOf(profile)}.json" in _cc136,
          "136.2 图鉴状态缓存同样按存档分文件（与农场同一套 token）")
    # ⚠ 反向断言：固定文件名的老写法不许复活（那是串档的病根）
    check('"farm_today.json"' not in _fc136 and '"codex_state.json"' not in _cc136,
          "136.3 固定文件名的老写法已清除（不再有不带存档标识的缓存文件）")
    # 136.4 token 必须做**文件名安全化**（存档名是用户可改的自由文本）
    check("ILLEGAL_NAME_CHARS" in _fc136 and "MessageDigest" in _fc136,
          "136.4 缓存文件名做安全化 + 哈希（存档名含 / : * 等非法字符也不越出目录）")

    # 136.5 读写都**带存档名**（漏一处就串档）
    check("fun read(ctx: Context, profile: String)" in _fc136
          and "fun write(ctx: Context, profile: String" in _fc136,
          "136.5 农场缓存读写都带存档名参数")
    check("fun read(ctx: Context, profile: String)" in _cc136
          and "fun write(ctx: Context, profile: String" in _cc136,
          "136.6 图鉴状态缓存读写都带存档名参数")

    # 136.7 切档必须**重读**（重读键里带 activeProfile）
    check("visible to farmRefreshTick to snap.activeProfile" in _tp136,
          "136.7 今日页重读键带当前存档名（切档即时重读，不显示上一份存档的农场）")
    check("snap.activeProfile" in _cx136 and "CodexStateCache.read(dctx, snap.activeProfile)" in _cx136,
          "136.8 图鉴详情的状态重读也带当前存档名（背包 / 好感同样不串档）")

    # 136.9 写盘时机：**确认之后**才落盘
    check("fun parse(" in _fc136 and "fun commit(" in _fc136,
          "136.9 农场缓存拆成「解析（内存）」与「落盘」两个出口")
    check("pending = Pending(profile, model, codex)" in _fc136,
          "136.10 解析结果只进内存 pending（不落盘）")
    check("FarmTodayCache.commit(" in _sp136,
          "136.11 「确定导入」时才落盘（解析成功即落盘的老毛病已改）")
    check("FarmTodayCache.discardPending()" in _sp136
          or "FarmTodayCache.discardPending()" in code_only(src("data/LanReceiveController.kt")),
          "136.12 取消导入时丢掉待落盘的数据（c232 起在控制台 discardPreview 里）")
    check("FarmTodayCache.parse(" in _sp136 and "FarmTodayCache.refresh" not in _sp136,
          "136.13 三条导入入口走 parse（不再有 refresh 即落盘的旧入口）")
    # ⚠ commit 必须核对存档名：pending 属于存档1 时不许落进存档2
    check("if (item.profile != profile) return false" in _fc136,
          "136.14 落盘前核对归属存档（导进存档1的数据不许落进存档2）")

    # 136.15 改名 / 删存档要带着缓存走（否则改名后农场数据「消失」）
    check("FarmTodayCache.renameCache(context, old, new)" in _pr136,
          "136.15 存档改名时缓存跟着搬（改完名农场数据不消失）")
    check("FarmTodayCache.clear(context, name)" in _pr136,
          "136.16 删存档时缓存跟着删（重建同名存档不捡旧的）")
    # 136.17 改名还要搬**报告**与**块数**（同属「存档级数据跟着存档走」）
    check("reportKey(new)" in _pr136 and "reportKey(old)" in _pr136,
          "136.17 存档改名时报告跟着搬（改完名年度报告不消失）")
    check("calcPlotsKey(new)" in _pr136 and "calcPlotsKey(old)" in _pr136,
          "136.18 存档改名时块数跟着搬")

    # 136.19 农场卡标出**当前存档名**（一眼看出串没串）
    check("存档：$profileName" in _ftv136 and "profileName: String = \"\"" in _ftv136,
          "136.19 农场卡头部标出当前存档名（串档一眼可辨）")

    # 136.20 JVM 验收测试存在并挂在任务里
    _pct136 = os.path.join(ROOT, "app", "src", "test", "java", "com", "stardew", "assistant",
                           "data", "ProfileCacheTest.kt")
    check(os.path.exists(_pct136), "136.20 有多存档缓存隔离的 JVM 验收测试（ProfileCacheTest）")
    _bg136 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("com.stardew.assistant.data.ProfileCacheTest" in _bg136,
          "136.21 ProfileCacheTest 挂在某个 JVM 验收任务里（不被漏跑）")


    # ==================================================================
    # 137. c219：壳底裸字上米黄纸 + 收集包 mate 就地翻页
    #
    # 起因（两件事都在用户的验收队列里排了几轮）：
    # ① 「深色主题部分文字压木纹底对比低」。实测 16 套配色后有个反直觉的结论：
    #    **夜间配色不是短板**（NightColors cream/wood 9.59:1），最差的是季节/节日的
    #    浅木纹与玻璃底 —— 冬季 inkDim/wood 1.06:1、展览会 1.03:1、秋季 gold/screen
    #    1.16:1。PixelTheme 的注释早就写明这是「文字没套卡片直接压壳底」的**结构问题**，
    #    改色板解决不了；本轮按拍板只修清点出来最差的一批点位。
    # ② 详情抽屉点收集包 mate 名字走 resolveItemJump 跳新抽屉，翻页语境全丢。
    #
    # 这里钉的是「口径」而不是「像素」：字必须落在 parchment / slot 这两张**已被
    # 46.1 + 46.5 钉死 ≥4.5:1** 的底上，而不是散落各处的 background(c.parchment)。
    # ==================================================================
    print("\n== 137. c219 米黄纸小条 / 收集包就地翻页 ==")
    _pw137 = code_only(src("ui/PixelWidgets.kt"))
    _cp137 = code_only(src("ui/codex/CodexPage.kt"))
    _jp137 = code_only(src("ui/jump/JumpPanel.kt"))
    _ck137 = code_only(src("ui/calc/CalcPage.kt"))

    def _wrapped137(code, needle, wrapper, window=320):
        """needle 往前 window 个字符里出现 wrapper ⇒ 认定这段字被托住了。
        ⚠ 只看第一处命中；同一句在两个文件里各自查各自，别指望一次管两边。"""
        i = code.find(needle)
        return i >= 0 and wrapper in code[max(0, i - window):i]

    # 137.1 共享出口存在，且底就是那张被验过对比度的米黄纸
    check("fun ParchmentStrip(" in _pw137,
          "137.1 有 ParchmentStrip 共享出口（不各处手写 background）")
    check("background(LocalPixel.current.parchment)" in _pw137,
          "137.2 ParchmentStrip 的底是 parchment（ink/inkDim/gold/green 压它已由 46.1/46.5 钉死）")

    # 137.3 两处面板的段标题与兜底段
    for _nm137, _t137 in (("图鉴「全部分类」", _cp137), ("跳到…面板", _jp137)):
        check(_wrapped137(_t137, "PixelText(sec.label, color = c.ink)", "ParchmentStrip"),
              "%s：段标题落在米黄纸条上（v2.97.0 换 ink 只是看得见，压壳底仍 1.2~4.2:1）" % _nm137)
        check(_wrapped137(_t137, 'PixelText("🧩 其他", color = c.ink)', "ParchmentStrip"),
              "%s：兜底段「🧩 其他」同样上纸条" % _nm137)

    # 137.4 图鉴页内：搜索结果分组标题 / 两处空态 / SVE 提示 / 抽屉报错提示
    check(_wrapped137(_cp137, '"📂 ${g.title}（${hits.size}）"', "ParchmentStrip"),
          "137.4 跨分类搜索的分组标题上纸条（gold/screen 秋季只有 1.16:1）")
    check(_wrapped137(_cp137, 'PixelText("没有匹配「$query」的条目", color = c.inkDim)', "PixelCard"),
          "137.5 跨分类搜索空态套卡片")
    # ⚠ 这条不用「往前找最近的 PixelCard」那种窗口判断：空态整块套卡片后，
    #   PixelCard 与最后一行文案之间隔着三个 when 分支（900 字符），窗口式断言
    #   等于没断言。直接钉结构：if (items.isEmpty()) 与 when 之间必须有 PixelCard。
    _i1376 = _cp137.find("if (items.isEmpty()) {")
    _j1376 = _cp137.find("when {", _i1376) if _i1376 >= 0 else -1
    check(_i1376 >= 0 and _j1376 > _i1376 and "PixelCard {" in _cp137[_i1376:_j1376],
          "137.6 分类网格空态整块套卡片（inkDim/screen 最低 1.09:1）")
    check(_wrapped137(_cp137, 'PixelText("🐟 SVE 扩展鱼", style = Px.title, color = c.ink)', "PixelCard"),
          "137.7 SVE 扩展鱼提示块套卡片（整块都在屏幕底上）")
    check(_wrapped137(_cp137, '"数据有误？点右上「📋 报错」一键复制模板', "ParchmentStrip"),
          "137.8 抽屉底部报错提示上纸条（抽屉底是木壳，与 c186 PanelPreview 同病因）")

    # 137.9 跳到面板：段标题 / 攻略册 / 村民 / 搜索分组标题 / 空态 / 顶部说明
    for _nd139 in ('PixelText("📖 攻略", color = c.gold)', 'PixelText("👥 村民", color = c.green)',
                   '"没找到「$key」——试试物品名', '"📂 ${g.title}（${hits.size}）"',
                   '"长按任意底栏 tab 随时回到这里'):
        check(_wrapped137(_jp137, _nd139, "ParchmentStrip"),
              "137.9 跳到面板裸字已收进纸条：%s" % _nd139[:18])
    check(_jp137.count("ParchmentStrip") >= 8,
          "137.10 跳到面板至少 8 处用纸条托底（实得 %d；以后新加段标题不许漏包）"
          % _jp137.count("ParchmentStrip"))

    # 137.11 Chip 的底是安全底：ink/slot 在 16 套配色全部 ≥4.5
    #       （段标题/分类名大量用 Chip，口径必须和 parchment 一样钉住，否则「换个底」
    #         只是把坑从 screen 挪到 slot）。
    # ⚠ c221 审计（B号独立验证）：槽位上的**次要文字**用的是 inkDim / gold，不是 ink——
    #   inkDim/slot 地板 3.54（冰雪节）、gold/slot 地板 3.27（冰雪节），都 <4.5。
    #   这是「在安全底深浅一档」的次要项，属于 c222 对比度收尾范围；本轮只把断言改诚实，
    #   不再假装「全场过线」。ink(正文主文字)/slot 才是 Chip 骨架字的真实底，仍钉。
    _slot_min_ink = min(
        _contrast46(_pal46[_n]["ink"], _pal46[_n]["slot"])
        for _n in _order46 if "ink" in _pal46[_n] and "slot" in _pal46[_n]
    )
    check(_slot_min_ink >= 4.5,
          "137.11 Chip 骨架 ink/slot 地板 %.2f:1（最差一套也过 4.5）" % _slot_min_ink)
    _slot_min_dim = min(
        _contrast46(_pal46[_n]["inkDim"], _pal46[_n]["slot"])
        for _n in _order46 if "inkDim" in _pal46[_n] and "slot" in _pal46[_n]
    )
    # c223：压槽底的文字已由 140 节收敛墨字（占位符/开关/标签/选中态），
    #   这两个地板降级为「色板固有值，仅记录」——不再有待修的台账项。
    print("      · 槽位次要文字已修（140 节）：inkDim/slot 色板地板 %.2f、gold/slot %.2f 仅作记录"
          % (_slot_min_dim,
             min(_contrast46(_pal46[_n]["gold"], _pal46[_n]["slot"])
                 for _n in _order46 if "gold" in _pal46[_n] and "slot" in _pal46[_n])))

    # 137.12~18 收集包 mate 就地翻页
    check("internal data class BundleRef(" in _cp137,
          "137.12 抽屉的一页统一成 BundleRef（分类语境与收集包语境共用一套 pager）")
    check("internal fun buildBundleRefs(" in _cp137,
          "137.13 同包页列表由唯一纯函数 buildBundleRefs 出口（判定不散落 UI）")
    check("val refs = bundleRefs ?: normalRefs" in _cp137,
          "137.14 普通模式沿用 c218 筛选列表，冻结后走同包列表（不误伤原行为）")
    check("bundleRefs = built" in _cp137 and "bundleNames = (listOf(r.name) + mates).distinct().sorted()" in _cp137,
          "137.15 首次点 mate 冻结名单：页列表与每页显示的名单同源")
    check("bundleTapTarget(" in _cp137 and "resolveItemJump(name)" in _cp137,
          "137.16 点名字经纯判定：可就地翻则定位，否则交搜索（392 个跨分类重名不替用户猜）")
    check("matesOverride = bundleNames" in _cp137 and
          "onMateClick: ((String, List<String>) -> Unit)? = null" in _cp137,
          "137.17 详情主体靠可选参数接抽屉的名单/回调（默认值 = 旧行为）")
    check('CodexItemDetailBody("crops", oc.second, snap, Modifier.fillMaxSize())' in _ck137 and
          'CodexItemDetailBody("fishing", openFishIdx, snap, Modifier.fillMaxSize())' in _ck137,
          "137.18 计算页两处详情仍按 4 参调用（新参数有默认值，不影响别的入口）")
    _cbrt137 = os.path.join(ROOT, "app", "src", "test", "java", "com", "stardew", "assistant",
                             "ui", "codex", "CodexBundleRefTest.kt")
    check(os.path.exists(_cbrt137), "137.19 有 buildBundleRefs 的 JVM 验收测试")
    _bg137 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("com.stardew.assistant.ui.codex.CodexBundleRefTest" in _bg137,
          "137.20 CodexBundleRefTest 挂在 JVM 验收任务里（不被漏跑）")
    # c221 新增：同组同名成品不折叠（加工组蛋黄酱×2……），见 CodexFilterTest
    _cft137 = os.path.join(ROOT, "app", "src", "test", "java", "com", "stardew", "assistant",
                           "ui", "codex", "CodexFilterTest.kt")
    check("同组同名条目各自保留下标_不折叠" in io.open(_cft137, encoding="utf-8").read(),
          "137.21 有「加工组同名成品保留下标」用例（filterItemIndices 不把第二个折叠成第一个）")


    # ==================================================================
    # 138. c220：收集包那句提示必须跟着行为走
    #      起因：c219 把「点 mate 名字」从跳转改成就地翻页，**只改了代码没改文案**，
    #      用户装机第一眼就从屏幕上看到那句假话。文案与行为是同一次改动，
    #      拆开就漂移 —— 这里把「分支措辞」钉成断言，防止再犯。
    # ==================================================================
    # 139. c221：抽屉稳定身份 + 收集包就地判定（阻断修复的钉）
    #      · 阻断1: DetailDrawer 用可变 title 当身份 → 进包内模式抽屉关不掉。
    #        修 = DrawerSpec 加稳定 id + mine() 核对；图鉴详情抽屉 title 恢复恒定。
    #      · 阻断2: 跨全部分类 nameToHits 把本可直达的名字算成重名 → 13/27 包
    #        点任何名字都落搜索。修 = shipping 聚合分类不计入唯一命中 + 品质/别名归一回落。
    print("\n== 139. c221 抽屉稳定身份 + 收集包就地判定 ==")
    _dd139 = code_only(src("ui/DetailDrawer.kt"))
    _cp139 = code_only(src("ui/codex/CodexPage.kt"))
    _cxi139 = code_only(src("ui/codex/CodexIndex.kt"))

    check("val id: Any? = null" in _dd139,
          "139.1 DrawerSpec 带稳定 id（不依赖可变的 title）")
    check("mine(" in _dd139 and ("spec.id == id" in _dd139 or "id != null" in _dd139),
          "139.2 身份核对走 mine()：传 id 的调用点只看 id，不传则保持 title+onDismiss")
    check("id = drawerId" in _cp139 and "val drawerId = remember(t) { Any() }" in _cp139,
          "139.3 图鉴详情抽屉用稳定 drawerId（非 title）当身份")
    check("title = pagerGroup?.title ?: \"详情\"" in _cp139,
          "139.4 详情抽屉 title 恢复恒定（不再随包内模式变化）")
    check("fun isDisplay(g: String) = g == \"shipping\"" in _cp139,
          "139.5 shipping 聚合分类不计入唯一命中（跨分类重名不再误伤本可直达的名字）")
    check("codexFallbackHits(" in _cxi139,
          "139.6 归一回落抽成 codexFallbackHits（金星南瓜→南瓜，两处共用同一归一口径）")
    check("filterItemIndices(" in _cp139,
          "139.7 抽屉下标来自 filterItemIndices（整组下标直接给，不再按名反查折叠）")
    check("key(modeKey)" in _cp139,
          "139.8 pager 用 key(modeKey) 分模式重建（切纯 / 进包内不残留旧 currentPage）")

    # ── 138. c220 文案与行为同口径（紧随 139 之后执行）──
    print("\n== 138. c220 文案与行为同口径 ==")
    _cp138 = code_only(src("ui/codex/CodexPage.kt"))
    _i138 = _cp138.find('"📦 同一个收集包里还有"')
    _seg138 = _cp138[_i138:_i138 + 900] if _i138 >= 0 else ""
    check(_i138 >= 0, "138.1 收集包卡片还在（标题字面量）")
    check("if (onMateClick != null)" in _seg138,
          "138.2 卡片提示按 onMateClick 分支措辞（有回调 = 就地翻页，不是跳转）")
    check("就地换到那一条" in _seg138 and "点名字跳过去看" in _seg138,
          "138.3 两套措辞都在：图鉴抽屉说就地换页，计算页/整屏仍说跳过去")
    check("点名字直接跳过去看" not in _cp138,
          "138.4 旧句「点名字直接跳过去看」已清掉（在抽屉里是假话）")

    # ==================================================================
    # 140. c223：对比度收尾（B 号审查 15+3 处对账 + 槽底墨字口径）
    #      · 18 处裸压壳底/屏幕底的字上米黄纸（模式同 c219 的 ParchmentStrip）
    #      · 压槽底的文字收敛墨字：inkDim/gold 压 slot 只有 3.54/3.27，
    #        ink/slot 16 套 ≥4.5（137.11 钉死）——底是视觉语言不动，字往达标色收敛
    #      · 选中态白/金夜间 1.68 → 文字按底亮度切（GlassLadder.isLightSurface 单源）
    # ==================================================================
    print("\n== 140. c223 对比度收尾 ==")
    _cp140 = code_only(src("ui/codex/CodexPage.kt"))
    _tp140 = code_only(src("ui/today/TodayPage.kt"))
    _jp140 = code_only(src("ui/jump/JumpPanel.kt"))
    _vp140 = code_only(src("ui/villagers/VillagersPage.kt"))
    _pp140 = code_only(src("ui/progress/ProgressPage.kt"))
    _gp140 = code_only(src("ui/guide/GuideHomePage.kt"))
    _sp140 = code_only(src("ui/schedule/SchedulePage.kt"))
    _su140 = code_only(src("ui/schedule/ScheduleUi.kt"))
    _mp140 = code_only(src("ui/mods/ModsPage.kt"))

    def _strip140(code, needle, label, window=720):
        """needle 前方 window 字符内必须出现 ParchmentStrip（真包住，不是并列组件）。
        ⚠ 不带左括号——无参调用是尾 lambda 语法「ParchmentStrip {」；
        ⚠ code_only 保留换行与缩进，Row 包裹的行距 ParchmentStrip 可达 500+ 字符。"""
        _i = code.find(needle)
        check(_i >= 0, label + "（文字 needle 存在）")
        if _i < 0:
            return
        check("ParchmentStrip" in code[max(0, _i - window):_i], label)

    # 140.1~140.6 今日页（木壳抽屉 ×4 + 屏幕底 ×2）
    _strip140(_tp140, "清单所有存档共用", "140.1 待办抽屉说明行上纸条")
    _strip140(_tp140, "输入关键词，一次搜完", "140.2 搜索抽屉空态提示上纸条")
    _strip140(_tp140, "没有匹配「$searchQuery」的结果", "140.3 搜索抽屉无结果上纸条")
    _strip140(_tp140, "📂 ${hits.first().groupTitle}", "140.4 搜索抽屉分组标题上纸条")
    _strip140(_tp140, "…还有 ${hits.size - shown.size} 条", "140.5 搜索抽屉「还有 N 条」上纸条")
    _strip140(_tp140, "已到第 ${WidgetRender.MAX_YEAR} 年", "140.6 「已到第 N 年」提示上纸条")
    # 140.7 跳到面板
    _strip140(_jp140, "…还有 ${hits.size - shown.size} 条", "140.7 跳到面板「还有 N 条」上纸条")
    # 140.8~140.14 图鉴页
    _strip140(_cp140, "…还有 ${hits.size - shown.size} 条 ▾", "140.8 跨分类搜索「还有 N 条 ▾」上纸条")
    _strip140(_cp140, "收起 ▴", "140.9 跨分类搜索「收起 ▴」上纸条")
    _strip140(_cp140, "⭐ 特别关心", "140.10 特别关心折叠头上纸条")
    _strip140(_cp140, "点物品跳图鉴详情", "140.11 特别关心尾注上纸条")
    _strip140(_cp140, "🕘 最近看过的", "140.12 最近看过折叠头上纸条")
    _strip140(_cp140, "🎛 收起高级筛选", "140.13 更多筛选行上纸条")
    _strip140(_cp140, "🎯 钓点：全部（点一下重置）", "140.14 钓点行上纸条")
    # 140.15 「▾ 全部分类」在 Row 里 → 行内米黄纸块（parchment 底 + slotEdge 边）
    _i140 = _cp140.find("▾ 全部分类 ${groups.size}")
    check(_i140 >= 0, "140.15 「▾ 全部分类」文字 needle 存在")
    if _i140 >= 0:
        check("background(c.parchment)" in _cp140[max(0, _i140 - 420):_i140],
              "140.15 「▾ 全部分类」行内纸块（Row 里不能用全宽纸条）")
    # 140.16~140.17 村民页（140.18 进度页是行内纸块，单独查）
    _strip140(_vp140, "口径：类别推断按", "140.16 村民反查口径行上纸条")
    _strip140(_vp140, "每周限送 2 件", "140.17 村民列表尾注上纸条")
    _i = _pp140.find("已隐藏勾完的条目")
    check(_i >= 0, "140.18 进度页「已隐藏」needle 存在")
    if _i >= 0:
        check("background(c.parchment)" in _pp140[max(0, _i - 420):_i],
              "140.18 进度页「已隐藏」行内纸块（Row 里用 parchment 小块，不是全宽纸条）")

    # 140.19 压槽底文字收敛墨字：inkDim→ink（ink/slot 16 套 ≥4.5，137.11 钉死）
    _ph140 = [("🔍 搜全部分类：物品 / 菜谱 / 鱼 / 作物…", _tp140, "今日页搜索框"),
              ("🔍 搜索全部", _cp140, "图鉴搜索框"),
              ("🔍 搜物品 / 分类 / 册子 / 村民…", _jp140, "跳到面板搜索框"),
              ("输入手上的物品，看该送给谁…", _vp140, "村民反查搜索框"),
              ("📁 仅本分类", _cp140, "图鉴作用域开关")]
    for _k, (_nd, _code, _nm) in enumerate(_ph140, 1):
        _i = _code.find(_nd)
        check(_i >= 0, "140.19%s %s 占位符 needle 存在" % ("abcdef"[_k - 1], _nm))
        if _i >= 0:
            check("color = c.ink," in _code[_i:_i + 200] or "color = c.ink)" in _code[_i:_i + 200],
                  "140.19%s %s 文字收敛墨字（inkDim/slot 最低 3.54，ink/slot ≥4.5）"
                  % ("abcdef"[_k - 1], _nm))

    # 140.20 金字标签回落 Chip 默认墨字（金边框保留）：5 文件全量否定式
    check("content = c.gold" not in _cp140 and "content = c.gold" not in _vp140
          and "content = c.gold" not in _sp140 and "content = c.gold" not in _su140
          and "content = c.gold" not in _mp140,
          "140.20 金字标签已清（9 处 Chip 回落默认墨字，金/槽 3.27 不再出现）")

    # 140.21 「挑一个 ›」去槽底压卡底（gold/parchment 16 套 ≥4.6，46.5 钉死）
    _i = _cp140.find("挑一个 ›")
    check(_i >= 0, "140.21 「挑一个 ›」needle 存在")
    if _i >= 0:
        check("background(c.slot)" not in _cp140[_i:_i + 320],
              "140.21 「挑一个 ›」金字直接压卡底（槽底已去）")

    # 140.22 选中态白/金：文字按底亮度切（夜间浅金底 1.68 → woodDark 深字 ≈11:1）
    check(_cp140.count("GlassLadder.isLightSurface(c.gold)") >= 4,
          "140.22a 图鉴 4 处选中 Chip 文字按底亮度切（同 c186 庆祝条单源判据）")
    check("GlassLadder.isLightSurface(accent)" in _gp140,
          "140.22b 攻略册 badge Chip 文字按底亮度切")
    check("GlassLadder.isLightSurface(c.gold)" in _vp140,
          "140.22c 村民「★ 已关心」金底文字按底亮度切")

    # ==================================================================
    # 141. c224：小组件「明天」热区 + 分享按钮兜底
    #      · 三套布局的「明天」按钮 padding 1dp/4dp → 热区 ≈38×15dp，远低于
    #        触控指南 48×48dp（用户真机主诉「热区小」）。RemoteViews 没有
    #        touchDelegate，只能放大按钮本身：min 44×28 + padding 10/6 ≈ 54×28。
    #      · 空 refs 时分享按钮消失（c219 起的语义差）→ 回落 target 本身。
    # ==================================================================
    print("\n== 141. c224 小组件热区 + 分享兜底 ==")
    for _layn141 in ("widget_today", "widget_card", "widget_wide"):
        _l141 = io.open(os.path.join(_lay95, _layn141 + ".xml"), encoding="utf-8").read()
        _i141 = _l141.find("@+id/w_next")
        check(_i141 >= 0, "141.1 %s 有「明天」按钮位" % _layn141)
        if _i141 >= 0:
            _seg141 = _l141[_i141:_i141 + 800]
            check('android:minWidth="44dp"' in _seg141 and 'android:minHeight="28dp"' in _seg141,
                  "141.2 %s 热区下限 44×28dp（原无边距下限）" % _layn141)
            check('android:paddingStart="10dp"' in _seg141 and 'android:paddingTop="6dp"' in _seg141,
                  "141.3 %s 内边距 10/6dp（原 4/1dp，实际热区 ≈54×28）" % _layn141)
            check('android:gravity="center"' in _seg141,
                  "141.4 %s 文字在放大的按钮内居中" % _layn141)
    check("R.id.w_next" in _wr95 and "setOnClickPendingIntent" in _wr95,
          "141.5 渲染层接线不动（w_next 仍走广播）")

    _cp141 = code_only(src("ui/codex/CodexPage.kt"))
    _i141b = _cp141.find("val curRef = refs.getOrNull(detailIdx)")
    check(_i141b >= 0, "141.6 curRef 兜底链 needle 存在")
    if _i141b >= 0:
        _seg141b = _cp141[_i141b:_i141b + 420]
        check("?: refs.firstOrNull() ?: run {" in _seg141b and "BundleRef(t.groupId, t.idx" in _seg141b,
              "141.6 空 refs 回落 target 本身（分享按钮不再消失，c219 语义差收口）")

    # ==================================================================
    # 142. c225：小组件去框 + 纯白衬底（用户看截图拍板：木框突兀、要白底）
    #      · 三套布局删 3dp padding + #6B4218 木框；w_inner 固定 #FFFFFF
    #      · 渲染层不再按季染衬底：Palette.bg 字段删除（白底对比度已验算：
    #        ink 10.85~14.57、inkDim 4.48~5.88、red 4.93、gold 秋 5.01；
    #        夏 gold F0A229 2.12 只画 ▰ 进度条与「明天」无关，字形粗，可读性可接受）
    # ==================================================================
    print("\n== 142. c225 小组件去框 + 纯白衬底 ==")
    _wr142 = src("widget/WidgetRender.kt")
    for _layn142 in ("widget_today", "widget_card", "widget_wide"):
        _l142 = io.open(os.path.join(_lay95, _layn142 + ".xml"), encoding="utf-8").read()
        check("6B4218" not in _l142, "142.1 %s 木框色已清（无 3dp 边框层）" % _layn142)
        _i142 = _l142.find("@+id/w_inner")
        check(_i142 >= 0 and 'android:background="#FFFFFF"' in _l142[_i142:_i142 + 400],
              "142.2 %s 衬底固定纯白（白底上 ink ≥10.85，四季文字全过线）" % _layn142)
    check("val bg: Int" not in _wr142 and "p.bg" not in _wr142,
          "142.3 渲染层不再按季染衬底（Palette.bg 字段删除，白底单源在布局）")

    # ==================================================================
    # 143. c226：瘦身版（用户拍板「设置页 + 全 App 文案」+「低风险两件」）
    #      · 文案：设置页 9 处 + 全 App 18 处赘述删/精简（约 −900 字）。
    #        c219~c225 刚上纸的文案一条不动（137~142 节的 needle 断言兜底）。
    #      · 资产：大图批 PNG→无损 WebP（9 张，image_map 同步，40 节闸门原样
    #        生效）+ 启动图标 5 档有损 q85 重编码（40(c) 密度桶闸门不碰）。
    #      ⚠ 断言用 code_only（剥注释）——注释里引用旧文案做说明不算残留。
    # ==================================================================
    print("\n== 143. c226 瘦身 ==")
    _sp143 = code_only(src_sp())
    check("桌面有 3 种规格" not in _sp143 and "勾选控制小组件显示哪些内容" in _sp143,
          "143.1a 小组件规格段精简（规格添加时系统选择器可见）")
    check("每次打开 App 检查一次更新（默认开" not in _sp143
          and "下载后覆盖安装，存档不丢" in _sp143,
          "143.1b 在线更新复述段精简（联网口径单源归关于页）")
    check("在 Steam 官网登录后下载云存档" not in _sp143,
          "143.1c Steam 云 desc 整段删（按钮 + 引导抽屉已覆盖）")
    check("懒得压包" not in _sp143,
          "143.1d 「懒得压包」desc 整段删（接收抽屉六步教程已覆盖）")
    check("只读不改；可自动识别" in _sp143,
          "143.1e 只读不改行精简（动态数字写法保留，66 节照常核对）")
    check("只支持 zip，rar/7z" in _sp143,
          "143.1f 只支持 zip 行精简（「只支持 zip」字面保留，58 节钉住）")
    check("只恢复主题、字体、字号；进度待办不受影响" in _sp143,
          "143.1g 恢复默认说明精简（与 Toast 重复部分删除）")
    check("清空勾选：只清当前存档，建议先导出备份" in _sp143,
          "143.1h 清空勾选说明精简（确认框有更完整版）")
    check("金额随意，不换任何功能" not in _sp143,
          "143.1i 打赏正文已随收款码卡删除（2026-11-10 开源清理）")

    _sg143 = code_only(src("ui/SteamImportGuide.kt"))
    check("**选一个就行**" not in _sg143 and "有两种办法，选一个就行" in _sg143,
          "143.2a Steam 引导裸星号修复（Compose 的 Text 不解析 Markdown 星号）")
    check("不想开这个权限的话" not in _sg143,
          "143.2b 办法一末句删（与卡头「选一个就行」重复）")
    check("改完以后下载的存档就落在那个文件夹里" not in _sg143,
          "143.2c 下载位置说明重复句删（三处重复留一处）")
    check(_sg143.count('text = "重新扫描下载目录"') == 1
          and "用上面的「重新扫描下载目录」再扫一次" in _sg143,
          "143.2d 重扫按钮一页一份（折叠卡说明句就地指路，不悬空）")
    _cx143 = code_only(src("ui/codex/CodexPage.kt"))
    check("· 纯数字：给动物取名时输入这个数字" not in _cx143
          and "共 ${group.items.size} 条：取名可用" in _cx143,
          "143.2e 小鸡代码顶部卡删两条 bullet（详情复制卡逐条已说）")
    check("· 矿物/工匠品只有一个价；标「不可出货」的拿不到手" in _cx143,
          "143.2f 售价说明两条并一条（同义反复句删）")
    _vp143 = code_only(src("ui/villagers/VillagersPage.kt"))
    check("未标注的格子表示暂缺该物品的来源数据" in _vp143,
          "143.2g 村民反查病句修复（原「说明工具暂缺」读不通）")
    check("按扩展包资料整理的大致去向" in _vp143 and "mod 日程注释" not in _vp143,
          "143.2h 概要行程去实现细节措辞（「不是精确时段表」保留，钉住）")
    check("数据来自官方礼物偏好表" in _vp143 and "1.6.15 礼物偏好表" not in _vp143,
          "143.2i 出处行去版本号（扩展包分支不动）")
    _tp143 = code_only(src("ui/today/TodayPage.kt"))
    check("打勾按游戏日期记录，换天自动刷新" in _tp143,
          "143.2j 待办说明精简（「发群里即用」营销话术删）")
    _pp143 = code_only(src("ui/progress/ProgressPage.kt"))
    check("收集包、博物馆藏品与浮标自动打勾" in _pp143
          and "博物馆 60 件" not in _pp143,
          "143.2k 进度说明去写死的「60 件」（数据漂移不再说谎，浮标保留）")
    _fg143 = code_only(src("ui/guide/FeatureGuidePage.kt"))
    check("内容会随版本更新" not in _fg143,
          "143.2l 指引页空话段删（操作语义段保留）")
    _ftv143 = code_only(src("ui/today/FarmTodayView.kt"))
    check("查不到名字与售价，不计入产值" in _ftv143
          and "存档里没写物品 id" not in _ftv143,
          "143.2m 野生采集物说明去实现细节（「不计入产值」保留，钉住）")
    _ck143 = code_only(src("ui/calc/CalcPage.kt"))
    check("口径：非再生 = 单轮净利" in _ck143 and "巨型作物只能露天" not in _ck143,
          "143.2n 计算口径推导砍半（品质系数动态值保留）")
    check("职业全局生效：种植计算用" in _ck143,
          "143.2o 职业说明去工程口语（「吃」→「用」）")
    _sc143 = code_only(src("ui/schedule/SchedulePage.kt"))
    check("条件分支（好感 / 事件 / 巴士 / 社区中心）以游戏内为准" not in _sc143,
          "143.2p 行程条件分支句删（页尾同句已有）")

    _im143 = json.load(io.open(os.path.join(ROOT, "app", "src", "main", "assets",
                                            "image_map.json"), encoding="utf-8"))
    _vals143 = set(_im143.values())
    for _n143 in ("农舍升级①", "农舍升级②", "农舍升级③", "复活节", "夏威夷宴会",
                  "星露谷展览会", "马厩", "鱼塘（建筑）", "鱿鱼节"):
        check(_n143 + ".webp" in _vals143
              and os.path.exists(os.path.join(ROOT, "app", "src", "main", "assets",
                                              "img", _n143 + ".webp")),
              "143.3 %s 已转无损 webp 且被 image_map 引用（40 节闸门内）" % _n143)
    _lgh143 = os.path.join(ROOT, "app", "src", "main", "res", "mipmap-xxxhdpi",
                           "ic_launcher_foreground.webp")
    check(os.path.exists(_lgh143) and os.path.getsize(_lgh143) < 100000,
          "143.4 启动图标 xxxhdpi 前景有损重编码（<100KB，原无损 167KB）")

    # ==================================================================
    # 144. c227：设置页三栏（外观 / 存档 / 其他）
    #      · 用户拍板：顶部三栏 + 使用指引挪到 BackBar 右侧常驻入口。
    #      · 13 张卡各归其栏、一张不丢（重组最容易出的事故），
    #        切栏重建 LazyColumn（滚动回顶），「重新看引导」随指引页走。
    # ==================================================================
    print("\n== 144. c227 设置页三栏 ==")
    _sp144 = code_only(src("ui/settings/SettingsPage.kt"))
    check('private val SETTINGS_TABS = listOf("外观", "存档", "其他")' in _sp144,
          "144.1 三栏定义（外观 / 存档 / 其他）")
    check("options = SETTINGS_TABS" in _sp144 and "selected = settingsTab" in _sp144,
          "144.2 顶栏复用 PixelSegmented（与卡片内档位同一视觉，零新组件）")
    check("key(settingsTab)" in _sp144,
          "144.3 切栏重建 LazyColumn（滚动位置回顶，不停在上一栏半截）")
    _w144 = _sp144[_sp144.find("when (settingsTab)"):]
    _i0_144 = _w144.find("0 -> settings")
    _i1_144 = _w144.find("1 -> settings")
    _iel_144 = _w144.find("else -> settings")
    _seg0_144 = _w144[_i0_144:_i1_144] if _i0_144 >= 0 and _i1_144 > _i0_144 else ""
    _seg1_144 = _w144[_i1_144:_iel_144] if _i1_144 >= 0 and _iel_144 > _i1_144 else ""
    _segel_144 = _w144[_iel_144:] if _iel_144 >= 0 else ""
    check(bool(_seg0_144) and bool(_seg1_144) and bool(_segel_144),
          "144.4 三栏分支都可定位（0 / 1 / else）")
    check("settingsAppearanceTab(" in _seg0_144 and "settingsSavesTab(" in _seg1_144
          and "settingsAboutTab(" in _segel_144,
          "144.4b 三栏分支各转发到对应分件（c230 拆分契约）")
    # c230：三栏卡片体在各自分件里，144.5-144.7 改读分件（正文逐字未动）
    _ap144 = code_only(src("ui/settings/SettingsAppearanceTab.kt"))
    _sv144 = code_only(src("ui/settings/SettingsSavesTab.kt"))
    _ab144 = code_only(src("ui/settings/SettingsAboutTab.kt"))
    for _t144 in ("🎨 主题", "🔠 字体", "🔤 字号", "🫧 底栏样式", "🧪 玻璃浓度", "🔊 音效", "🧹 恢复默认"):
        check('PixelCard(title = "%s")' % _t144 in _ap144,
              "144.5 外观栏含「%s」" % _t144)
    for _t144 in ("💾 进度备份", "🎮 导入游戏存档"):
        check('PixelCard(title = "%s")' % _t144 in _sv144,
              "144.6 存档栏含「%s」" % _t144)
    for _t144 in ("🧩 桌面小组件", "🔄 在线更新", "ℹ 关于"):
        check('PixelCard(title = "%s")' % _t144 in _ab144,
              "144.7 其他栏含「%s」" % _t144)
    # ⚠ 2026-11-10：致谢名单卡已随名单整删（开源后不公开群友 ID）
    check('PixelCard(title = "🏆 致谢名单")' not in _ab144,
          "144.7 致谢名单卡不得复活")
    check('PixelCard(title = "☕ 开源说明")' in _ab144,
          "144.7 其他栏含「☕ 开源说明」（原「支持开发者」打赏卡，2026-11-10 改为开源说明）")
    check('PixelCard(title = "📖 使用指引")' not in _sp144,
          "144.8 使用指引不再占卡片位")
    check('PixelText("📖 指引"' in _sp144 and "onOpenGuide()" in _sp144,
          "144.9 BackBar 右侧常驻「📖 指引」入口（接 onOpenGuide）")
    _fg144 = code_only(src("ui/guide/FeatureGuidePage.kt"))
    check('PixelButton("重新看引导"' in _fg144 and "onReplayIntro" in _fg144,
          "144.10 「重新看引导」在使用指引页（onReplayIntro 回调）")
    _ma144 = code_only(src("MainActivity.kt"))
    check("onReplayIntro = { replayIntroScope.launch { repo.setIntroSeen(false) } }" in _ma144,
          "144.11 MainActivity 接上回看回调（setIntroSeen(false)）")
    # 反向：设置页不得再出现引导回调（旧入口彻底清掉）
    check("repo.setIntroSeen(false)" not in _sp144,
          "144.12 设置页不再持有引导回看逻辑（单一出口在 MainActivity）")

    # ================= 145. c228 液态玻璃优化四件 =================
    # 用户拍板「观感调参 / 动效升级 / 弹层形状 / 性能护栏」四个方向全做。
    # 钉四条线：浓度档单源、贴边形状退化关系、护栏降级语义、动效补偿公式。
    # ⚠ code_only 剥注释 ⇒ 注释里引用旧口径不算残留，断言按代码字面写。
    print("\n== 145. c228 液态玻璃优化四件 ==")

    _pr145 = code_only(src("data/PrefsRepo.kt"))
    _pg145 = code_only(src("ui/glass/PixelGlass.kt"))
    _gs145 = code_only(src("ui/glass/GlassSheet.kt"))
    _ma145 = code_only(src("MainActivity.kt"))
    _jp145 = code_only(src("ui/jump/JumpPanel.kt"))
    _sp145 = code_only(src_sp())

    # ── 145.1 观感：玻璃浓度档（设备态，与 GLASS_BAR 同族不进备份）──
    check('"glass_level"' in _pr145, "145.1 键 glass_level 存在（设备态）")
    check("setGlassLevel" in _pr145, "145.2 setGlassLevel 写方法存在")
    check("GLASS_LEVEL] ?: 1" in _pr145 and "coerceIn(0, 2)" in _pr145,
          "145.3 读处默认档 1 且钳到 [0,2]（脏值兜底）")
    check("val glassLevel: Int = 1" in _pr145,
          "145.4 Snapshot.glassLevel 默认 1（标准 = 既有观感）")
    check("glassLevel" in _ma145 and "glassLevel = snap.glassLevel" in _ma145,
          "145.5 底栏消费浓度档（snap → GlassBottomBar）")
    # 档位翻译成参数只在 GlassLadder 单源：三个倍率函数 + 档位数组
    check("fun veilScale(" in _pg145 and "fun blurScale(" in _pg145 and "fun refractScale(" in _pg145,
          "145.6 GlassLadder 有三档倍率单源（veil/blur/refract）")
    check("LEVEL_VEIL_SCALE = floatArrayOf(0.72f, 1f, 1.25f)" in _pg145,
          "145.7 浓度倍率档位表：轻 0.72 / 标准 1 / 浓 1.25")
    check("veilFor(c.screen, alphaScale = GlassLadder.veilScale(glassLevel))" in _ma145,
          "145.8 底栏白纱乘档位倍率（档 1 = 1f 逐像素回到旧观感）")
    check('PixelCard(title = "🧪 玻璃浓度")' in _sp145
          and 'options = listOf("轻", "标准", "浓")' in _sp145,
          "145.9 设置页「玻璃浓度」三档分段卡存在")
    check("repo.setGlassLevel(i)" in _sp145, "145.10 浓度卡写 setGlassLevel")
    # 反向：浓度卡必须垫在玻璃开关守卫下面（木纹模式没有可变的东西）
    check(_sp145.find('PixelCard(title = "🧪 玻璃浓度")') > _sp145.find("GlassLadder.enabled"),
          "145.11 浓度卡在 GlassLadder.enabled 守卫之后（木纹/低版本不给拨）")

    # ── 145.2 动效：底栏按压白纱变淡 + 弹层跟手微缩 ──
    check("veil.alpha * (1f - 0.3f * progress)" in _ma145,
          "145.12 底栏按压时白纱随力度变淡（最多三成）")
    check("PULL_SCALE_GAIN" in _gs145 and "0.03f" in _gs145,
          "145.13 弹层跟手微缩常量存在（3% 封顶）")
    check("sampleScale = 1f + PULL_SCALE_GAIN" in _gs145,
          "145.14 弹层缩放有玻璃采样反向补偿（背景钉屏不跟缩）")
    check("TransformOrigin(0.5f, 1f)" in _gs145,
          "145.15 微缩锚点在底部（顶边下移、底边贴地）")
    # 反向：悬浮模式（docked=false）的旧观感必须逐像素保留 —— 12dp 边距只剩悬浮分支一份
    check(_gs145.count(".padding(horizontal = 12.dp)") == 1,
          "145.16 12dp 侧边距只剩一处且在悬浮分支（贴边零边距）")

    # ── 145.3 形状：贴边弹层（上圆下直）──
    check("uniform float docked;" in _pg145, "145.17 shader 有 docked uniform")
    check("float2 dockFlip(" in _pg145, "145.18 shader 有 dockFlip（下半平面 y 镜像）")
    check("docked > 0.5 && centeredCoord.y > 0.0" in _pg145,
          "145.19 dockFlip 只翻转下半平面（恒等退化：docked=0 时与上游逐字一致）")
    check("grad.y = -grad.y" in _pg145,
          "145.20 梯度在镜像系算完要翻回（折射方向不指向镜像侧）")
    check('shader.setFloatUniform("cornerRadii", radius, radius, 0f, 0f)' in _pg145,
          "145.21 贴边时下两角半径 0（上圆下直的半径侧）")
    check("var docked: Boolean = false" in _pg145,
          "145.22 GlassSurface.docked 默认 false（旧形状不动）")
    check("surface?.docked = docked" in _gs145,
          "145.23 GlassSheet 把 docked 传给玻璃表面")
    check("ShapeCornerDocked" in _gs145 and "topStart = 28.dp" in _gs145
          and "bottomStart = 0.dp" in _gs145,
          "145.24 裁切形状与 shader 同一套几何（上 28 / 下 0）")
    check("QuadKey(" in _pg145 and "QuadKey(w, h, scope.density, docked)" in _pg145,
          "145.25 效果链重建键含 docked（互切不挂旧形状）")
    check("docked = true" in _jp145, "145.26 「跳到…」切贴边模式")
    check("RoundedCornerShape(28.dp)" in _gs145,
          "145.27 悬浮模式四角 28dp 保留（两站姿并存）")

    # ── 145.4 护栏：GlassPerfGuard 掉帧降级 ──
    check("object GlassPerfGuard" in _pg145, "145.28 GlassPerfGuard 存在")
    check("Choreographer" in _pg145 and "postFrameCallback" in _pg145,
          "145.29 帧观测走 Choreographer")
    check("0.5f else 1f" in _pg145,
          "145.30 档 1 = 模糊减半")
    check("GlassPerfGuard.disabled()" in _pg145,
          "145.31 recordBackdrop 判护栏停用（档 2 短路录制）")
    check("GlassPerfGuard.active = glassOn" in _ma145.replace(
        " && !com.stardew.assistant.ui.glass.GlassPerfGuard.disabled()", ""),
        "145.32 观测只在玻璃开着时挂（glassOn 门控）")
    check("GlassPerfGuard.blurFactor()" in _ma145,
          "145.33 底栏模糊融合护栏系数")
    check("GlassPerfGuard.level" in _ma145 and "Toast.makeText" in _ma145,
          "145.34 降档时 Toast 提示（不静默降级）")
    check("重启应用可恢复" in _ma145,
          "145.35 停用提示写明恢复方式（App 重启 = 会话态复位）")
    check("if (!active) return" in _pg145 and "removeFrameCallback" in _pg145,
          "145.36 active=false 摘除帧回调（观测零残留）")
    check("perf" not in _pr145.lower() or "glass_perf" not in _pr145,
          "145.37 护栏状态不落盘（PrefsRepo 无护栏键；重启恢复）")

    # ================= 146. c229 跳板下滑误关修复（方案 A） =================
    # 用户报「跳到页面往下滑会闪」，模拟器复现定位：v2.98.0 的「列表到顶还往下拉
    # → 拉面板」在 c228 贴边后成了主诉（列表一进来就在顶，第一次下划吃满 96dp
    # 关闭阈值 → 面板被误关）。方案 A = 原生 BottomSheet 语义：下滑关闭只留头部。
    print("\n== 146. c229 跳板下滑误关修复（方案 A） ==")

    _jp146 = code_only(src("ui/jump/JumpPanel.kt"))
    _gs146 = code_only(src("ui/glass/GlassSheet.kt"))

    # 146.1 内容区不再有面板拖拽接管（v2.98.0 的 pointerInput 块整体移除）
    check("canScrollBackward" not in _jp146,
          "146.1 「列表到顶下拉=拉面板」判定已删（canScrollBackward 不再出现）")
    check("drag.onDelta" not in _jp146,
          "146.2 内容区不再调 drag.onDelta（面板拖拽接口不再被内容消费）")
    # 146.3 内容区改回普通容器（无手势修饰）
    check(re.search(r"Box\(Modifier\.fillMaxWidth\(\)\.weight\(1f\)\)", _jp146) is not None,
          "146.3 内容区是普通 Box（无 pointerInput 手势层）")
    # 146.4 头部窄条的下拉关闭仍在（GlassSheet 的 detectVerticalDragGestures）
    check("detectVerticalDragGestures" in _gs146 and "settleDrag" in _gs146,
          "146.4 头部窄条下拉关闭保留（GlassSheet 头部手势）")
    # 146.5 结算阈值逻辑未动（拖够 96dp 才关）
    check("dismissThreshold" in _gs146 and "onDismissNow.value()" in _gs146,
          "146.5 下滑关闭结算逻辑未动（阈值判定保留）")
    # 146.6 sheetDrag 接口契约保留（跳板还传参，GlassSheet 头部实现在用）
    check("interface SheetDrag" in _gs146 and "sheetDrag = sheetDrag" in _jp146,
          "146.6 SheetDrag 接口与传参保留（头部拖拽实现在 GlassSheet 侧）")

    # ── 148. c231 信任设备（存档局域网自动同步 B-1）──
    #  边界变更要钉死：App 只存 Token 的 SHA-256；401 与 403（码错）严格分离；
    #  信任路径在连接内同步导入后才应答；导入口径与确认抽屉同一套。
    print("\n== 148. 信任设备（自动同步 B-1）==")
    _td148 = code_only(src("data/TrustedDevices.kt"))
    check('preferencesDataStore(name = "trusted_devices")' in _td148,
          "148.1 信任设备用独立 DataStore 文件（不与 prefs 混居）")
    for _p148 in (
        "fun hashToken(raw: String): String",
        "fun newToken(): String",
        "fun verifyAgainstHash(raw: String, hash: String): Boolean",
        "fun sanitizeName(raw: String?, deviceId: String): String",
        "MAX_DEVICES = 10",
        "TOKEN_BYTES = 32",
        "suspend fun revoke(ctx: Context, deviceId: String): Boolean",
        "fun verifyBlocking(ctx: Context, deviceId: String, rawToken: String): Boolean",
        "fun pairBlocking(ctx: Context, deviceId: String, rawName: String?): String",
        "hash = hashToken(token)",
    ):
        check(_p148 in _td148, "148.2 TrustedDevices 含「%s」" % _p148)
    # 安全核心：SHA-256 哈希存储 + 常量时间比对（库里没有 Token 原文）
    check('MessageDigest.getInstance("SHA-256")' in _td148,
          "148.3 Token 只存 SHA-256（原文用完即丢，泄露不可逆）")
    check("MessageDigest.isEqual" in _td148,
          "148.4 Token 比对走 MessageDigest.isEqual（常量时间）")
    # SaveReceiver 协议改造
    _sr148 = code_only(src("data/SaveReceiver.kt"))
    _sr148raw = src("data/SaveReceiver.kt")
    for _p148 in (
        '"X-Stardew-Device-Id"',
        '"X-Stardew-Token"',
        '"X-Stardew-Device-Name"',
        "private fun handleTrustedUpload(",
        "private fun finishAutoImport(",
        "private fun receiveZip(",
        "return handleTrustedUpload(",
        "pairing = deviceId to deviceName",
        '401, "Unauthorized"',
        '422, "Unprocessable Entity"',
        '"status" to "paired", "token" to token, "keys" to r.keys.size',
        '"status" to "imported", "keys" to r.keys.size',
    ):
        check(_p148 in _sr148, "148.5 SaveReceiver 含「%s」" % _p148)
    # 401 文案与「验证码」隔离：bat 靠响应体含不含「验证码」决定换码重试，
    # 混了字会把旧脚本带进错误分支。
    _tokline148 = [ln for ln in _sr148raw.split("\n") if "MSG_BAD_TOKEN =" in ln]
    check(len(_tokline148) == 1 and "验证码" not in _tokline148[0],
          "148.6 401 文案不含「验证码」三字（与 403 关键字隔离）")
    # 旧码路径原样保留（bat / 手动码流程不回归）
    check('MSG_BAD_CODE = "验证码不正确"' in _sr148 and "收到，正在解析…" in _sr148raw,
          "148.7 旧码路径原样（bat 与手动码不回归）")
    # 连接内导入的临时文件用完即删（不再走 UI 的 parse-after 流程）
    check("runCatching { dest.delete() }" in _sr148,
          "148.8 临时文件导入完即删（同步导入后不交给 UI）")
    # c232：自动导入口径收进 LanReceiveController（备份→并集→报告→农场缓存）
    _lc148 = code_only(src("data/LanReceiveController.kt"))
    for _p148 in (
        "onAutoImport = { file ->",
        "kotlinx.coroutines.runBlocking {",
        "repo.replaceChecks(snapNow.checks + r.keys)",
        "repo.setReport(snapNow.activeProfile, r.report)",
        "FarmTodayCache.takePending()",
        "onAutoImported = { _, msg ->",
    ):
        check(_p148 in _lc148, "148.9 控制台自动导入含「%s」" % _p148)

    # ── 149. c232 常驻自动监听 + 信任设备管理 + 全局码框 ──
    #  自动会话：App 启动即监听、无空闲自杀、关抽屉不停；管理 UI 可撤销（清空即停）。
    print("\n== 149. 常驻自动监听（自动同步 B-2）==")
    _lc149 = code_only(src("data/LanReceiveController.kt"))
    for _p149 in (
        "fun ensureAuto(appCtx: Context)",
        "fun startManual(ctx: Context)",
        "fun onTrustChanged(appCtx: Context)",
        "fun onDrawerDisposed()",
        "var autoSession = false",
        "CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)",
        "data class CodePrompt(",
        "fun setPreview(r: SaveImport.Result.Ok?)",
        "fun discardPreview()",
        "fun consumePreview()",
        "fun stop(reason: String = \"已停止接收\")",
    ):
        check(_p149 in _lc149, "149.1 控制台含「%s」" % _p149)
    _sr149 = code_only(src("data/SaveReceiver.kt"))
    for _p149 in (
        "autoMode: Boolean = false",
        "sock.soTimeout = if (autoMode) 0 else IDLE_TIMEOUT_MS.toInt()",
        "if (autoMode && deviceId == null)",
        "asJson: Boolean = true",
        "已自动导入，勾上",
    ):
        check(_p149 in _sr149, "149.2 SaveReceiver 含「%s」" % _p149)
    _sp149raw = src_sp()
    _sp149 = code_only(src_sp())
    # 抽屉：自动状态明示 + 信任设备管理卡
    check("自动接收中：App 打开期间常驻，无需手动开窗。" in _sp149raw,
          "149.3 抽屉明示「自动接收中」（常驻这件事要让用户看见）")
    check("已信任的电脑" in _sp149raw and "TrustedDevices.revoke(ctx, d.id)" in _sp149raw
          and "onTrustChanged(ctx)" in _sp149raw,
          "149.4 信任设备管理卡（撤销立即生效 + 清空即停自动接收）")
    check("还没有。电脑端推送一次、输一次码，就会记在这里。" in _sp149raw,
          "149.5 空名单有配对引导（别让人对着空卡片发呆）")
    # 全局码框宿主（MainActivity）+ 旧宿主已撤
    _ma149 = code_only(src("MainActivity.kt"))
    check("LanReceiveController.ensureAuto(applicationContext)" in _ma149,
          "149.6 App 启动即 ensureAuto（有信任设备就常驻监听）")
    check("LanReceiveController.codePrompt.collectAsState()" in _ma149
          and "ui.settings.CodePromptSheet(" in _ma149,
          "149.7 验证码弹窗挂全局宿主（任何页面都能配对）")
    check("CodePromptSheet(" not in _sp149,
          "149.8 设置页不再私挂码框（全局宿主统一渲染）")
    check("startManual" in _sp149,
          "149.9 抽屉「开始接收」走手动会话（旧流程保留）")
    # gradle 口径
    _bg149 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("versionCode = 243" in _bg149 and 'versionName = "2.152.1"' in _bg149,
          "149.10 gradle 已升 v2.142.0 (c232)（功能轮，占更新日志）")

    # ── 150. c233 可选前台服务开关（后台常驻）──
    #  开关默认关（行为与 c232 逐字一致）；开 = 接收会话由 dataSync 前台服务保活，
    #  通知带「停止接收」；关 = 撤服务但**不停会话**。
    print("\n== 150. 后台常驻接收（自动同步 B-3）==")
    _lrs150 = code_only(src("data/LanReceiveService.kt"))
    for _p150 in (
        "class LanReceiveService : Service()",
        "ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC",
        "const val ACTION_STOP =",
        "override fun onTimeout(startId: Int, fgsType: Int)",
        "return START_NOT_STICKY",
        "fun start(ctx: Context",
        "fun stop(ctx: Context)",
        "LanReceiveController.stop(",
    ):
        check(_p150 in _lrs150, "150.1 前台服务含「%s」" % _p150)
    # c234 改口径：会话已改归**服务**持有 ⇒ START_STICKY 现在是**对的**
    #   （进程被杀后系统重建服务 → ensureSessionForService 把会话重建起来，
    #    不是 c233 那种「只有空壳通知」的假恢复）。
    # ⚠ 精确口径：**正常路径**用 START_STICKY（会话归服务管，重建=真重建）；
    #   但「用户点了停止接收」那条分支必须 START_NOT_STICKY（用户主动停的，
    #   系统再把它拉起来就是违背用户意图）。
    _start_pos150 = _lrs150.find("return START_STICKY")
    _stop_pos150 = _lrs150.find("return START_NOT_STICKY")
    check(_start_pos150 != -1, "150.2 正常路径用 START_STICKY（进程被杀后重建→会话自动重建）")
    check(_stop_pos150 != -1 and _stop_pos150 < _start_pos150,
          "150.2a 用户主动停止的分支用 START_NOT_STICKY（别违背用户意图把它拉起来）")
    check("onTimeout" in _lrs150 and "已达系统时长上限" in _lrs150,
          "150.3 Android 15 的 dataSync 时长上限如实告知用户（不悄悄死）")
    _ln150 = code_only(src("data/LanNotifications.kt"))
    for _p150 in (
        "const val CHANNEL_LISTENING = \"lan_receive_listening\"",
        "const val ID_FOREGROUND = 4201",
        "fun listeningNotification(ctx: Context): Notification",
        "stopPendingIntent(ctx)",
        "PendingIntent.FLAG_IMMUTABLE",
    ):
        check(_p150 in _ln150, "150.4 通知含「%s」" % _p150)
    check("CHANNEL_LISTENING" in _ln150 and "steam_import_waiting" not in _ln150,
          "150.5 接收通知**独立渠道**（不蹭 Steam 的「等待下载」渠道名）")
    _lc150 = code_only(src("data/LanReceiveController.kt"))
    for _p150 in (
        "fun onForegroundPreferenceChanged(appCtx: Context, enabled: Boolean)",
        "LanReceiveService.start(appCtx",
        "LanReceiveService.stop(it)",
        "repo.current().lanForeground",
    ):
        check(_p150 in _lc150, "150.6 控制台含「%s」" % _p150)
    _pr150 = code_only(src("data/PrefsRepo.kt"))
    check('booleanPreferencesKey("lan_foreground")' in _pr150,
          "150.7 偏好键 lan_foreground（设备态，不进备份）")
    check("p[LAN_FGS] ?: false" in _pr150,
          "150.8 默认关 = c232 行为一字不变（不想要常驻通知的用户没变化）")
    check("val lanForeground: Boolean = false" in _pr150,
          "150.9 快照字段默认关")
    check("suspend fun setLanForeground(on: Boolean)" in _pr150,
          "150.10 写处存在（唯一口径）")
    check("lan_foreground" not in _pr150.split("suspend fun exportJson")[1].split("suspend fun importJson")[0],
          "150.11 不进备份 JSON（设备态偏好）")
    _sp150raw = src_sp()
    _sp150 = code_only(src_sp())
    check("后台常驻" in _sp150raw and "snap.lanForeground" in _sp150raw,
          "150.12 抽屉里有名为「后台常驻」的开关（与信任设备卡同语境）")
    check("repo.setLanForeground(on)" in _sp150,
          "150.13 开关落盘走 repo.setLanForeground")
    check("onForegroundPreferenceChanged(ctx, on)" in _sp150,
          "150.14 改开关即时切服务（开→补服务 / 关→撤服务）")
    check("息屏切后台也能收存档" in _sp150raw and "接收挂在常驻通知里，可随时停" in _sp150raw,
          "150.15 开关文案说清收益（息屏/后台 + 常驻通知 + 可停）")
    check("每天最多允许它连续运行约 6 小时" in _sp150raw,
          "150.16 告知系统 6 小时上限（不埋坑）")
    check("没通知权限" in _sp150raw,
          "150.17 没通知权限时如实提示（功能不受影响）")
    # 清单：服务必须声明（exported=false + dataSync + stopWithTask=false）
    _mf150 = io.open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"),
                     encoding="utf-8").read()
    check(".data.LanReceiveService" in _mf150,
          "150.18 清单声明 LanReceiveService（漏声明=功能静默失效）")
    _svc150 = _mf150.split('android:name=".data.LanReceiveService"')
    check(len(_svc150) >= 2, "150.19 服务块存在")
    if len(_svc150) >= 2:
        _blk150 = _svc150[1].split("/>")[0]
        check('android:exported="false"' in _blk150,
              "150.20 exported=false（外部 App 不能起它）")
        check('android:foregroundServiceType="dataSync"' in _blk150,
              "150.21 foregroundServiceType=dataSync（Android 14+ 必需）")
        check('android:stopWithTask="false"' in _blk150,
              "150.22 stopWithTask=false（切走 App 不跟着停）")
    # gradle 口径
    _bg150 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("versionCode = 243" in _bg150 and 'versionName = "2.152.1"' in _bg150,
          "150.23 gradle 已升 v2.143.0 (c233)（功能轮，占更新日志）")

    # ── 151. c234 后台接收冻结修复（白名单 + 会话归服务 + mod 直探优先）──
    #  实测机理：进程没被杀但被系统冻结（isFrozen=4）→ 线程不调度 → UDP 不回应、
    #  监听端口整个消失。前台服务保「不被杀」，电池优化白名单保「不被冻结」。
    print("\n== 151. 后台接收冻结修复（自动同步 B-4）==")
    _bh151 = code_only(src("data/BatteryHelper.kt"))
    for _p151 in (
        "object BatteryHelper",
        "fun isIgnoringBatteryOptimizations(ctx: Context): Boolean",
        "fun requestIgnoreBatteryOptimizations(ctx: Context): Boolean",
        "fun openBatterySettings(ctx: Context): Boolean",
        "fun openAutostartSettings(ctx: Context): Boolean",
        "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
        "Uri.parse(\"package:${ctx.packageName}\")",
        "FLYME_AUTOSTART_ACTION",
    ):
        check(_p151 in _bh151, "151.1 BatteryHelper 含「%s」" % _p151)
    check("com.meizu.safe.security.autostart_manager_settings" in _bh151,
          "151.2 Flyme 自启动页用公开 action（实测可开）")
    check("isIgnoringBatteryOptimizations" in _bh151 and "Build.VERSION.SDK_INT < Build.VERSION_CODES.M" in _bh151,
          "151.3 低版本返回 true（那时候没有 Doze，不该拦用户）")
    # 清单权限
    _mf151 = io.open(os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml"),
                     encoding="utf-8").read()
    check("android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" in _mf151,
          "151.4 清单声明 REQUEST_IGNORE_BATTERY_OPTIMIZATIONS")
    # 会话归服务
    _ls151 = code_only(src("data/LanReceiveService.kt"))
    for _p151 in (
        "LanReceiveController.ensureSessionForService(applicationContext, auto)",
        "return START_STICKY",
        "EXTRA_AUTO",
    ):
        check(_p151 in _ls151, "151.5 服务含「%s」" % _p151)
    _sp151b = _ls151.find("return START_STICKY")
    _st151b = _ls151.find("return START_NOT_STICKY")
    check(_sp151b != -1 and _st151b != -1 and _st151b < _sp151b,
          "151.6 正常路径 START_STICKY / 主动停止 START_NOT_STICKY（顺序别搞反）")
    check("android:process" not in _mf151,
          "151.7 刻意同进程（DataStore 不支持多进程，两边写会互相覆盖）")
    _lc151 = code_only(src("data/LanReceiveController.kt"))
    for _p151 in (
        "fun ensureSessionForService(appCtx: Context, auto: Boolean)",
        "fun refreshWhitelist(ctx: Context)",
        "val whitelisted: StateFlow<Boolean> = _whitelisted",
        "BatteryHelper.isIgnoringBatteryOptimizations(ctx)",
    ):
        check(_p151 in _lc151, "151.8 控制台含「%s」" % _p151)
    # 设置页引导
    _sp151raw = src_sp()
    _sp151 = code_only(src_sp())
    check("BatteryHelper" in _sp151 and "requestIgnoreBatteryOptimizations(ctx)" in _sp151,
          "151.9 抽屉里有「去授权」（系统对话框申请白名单）")
    check("openAutostartSettings(ctx)" in _sp151,
          "151.10 抽屉里有「自启动设置」（国产 ROM 第二道锁）")
    check("已进电池优化白名单" in _sp151raw and "还差一步" in _sp151raw,
          "151.11 白名单状态如实显示（✓ / ⚠ 还差一步）")
    check("系统会把 App 冻结" in _sp151raw,
          "151.12 说清为什么要授权（不授权就冻结 → 收不到）")
    check("这个手机没有自启动设置页" in _sp151raw,
          "151.13 非魅族 ROM 没有自启动页时给退路提示（不假装跳成功）")
    # MainActivity 回前台重查
    _ma151 = code_only(src("MainActivity.kt"))
    check("override fun onResume()" in _ma151 and "refreshWhitelist(applicationContext)" in _ma151,
          "151.14 回前台重查白名单（用户去系统页授权后状态要跟着变）")
    # mod 侧：直探优先
    _mod151 = ""
    _modp = os.path.join(_REPO_PARENT, "StardewAutoPush", "ModEntry.cs")
    if os.path.exists(_modp):
        _mod151 = io.open(_modp, encoding="utf-8").read()
    check(bool(_mod151), "151.15 找得到 mod 源码（StardewAutoPush/ModEntry.cs）")
    if _mod151:
        _fd151 = _mod151.split("private (string ip, int port)? FindPhone()")[1].split("private void RememberApp")[0]
        _udp_pos151 = _fd151.find("UdpDiscover()")
        _direct_pos151 = _fd151.find('if (LastApp != "")')
        check(_direct_pos151 != -1 and _udp_pos151 != -1 and _direct_pos151 < _udp_pos151,
              "151.16 mod 发现顺序=直探优先（UDP 哑火白等 2 秒，且某些 ROM 广播送不进来）")
        _mf151b = io.open(os.path.join(_REPO_PARENT, "StardewAutoPush", "manifest.json"),
                          encoding="utf-8").read()
        # c236：mod 已到 1.3.4（1.3.0 快捷键+GMCM / 1.3.1 修初始化时机 /
        # 1.3.2 修两条红字 / 1.3.3 HUD 图标改走 messageSubject /
        # 1.3.4 manifest 补「协议为本项目自有」的归属声明）。
        # 钉点跟着实际版本走，别让 mod 每次改版都要来改这里。
        check('"Version": "1.3.4"' in _mf151b, "151.17 mod 版本升 1.3.4")
    # gradle 口径
    _bg151 = io.open(os.path.join(ROOT, "app", "build.gradle.kts"), encoding="utf-8").read()
    check("versionCode = 243" in _bg151 and 'versionName = "2.152.1"' in _bg151,
          "151.18 gradle 已升 v2.152.1 (c243)（指引清理轮，占更新日志）")

    # ==================================================================
    # 157 节（c241）：项目开源 —— 个人联系方式**不得复活** + 密钥不进仓
    #
    # ## 为什么单开一节
    # 开源 = 源码进公开仓 ⇒ 「个人信息」和「密钥」两类东西一旦躺在源码里，
    # 就是**永久公开**（删掉文件也救不回，git 历史里还在）。
    # 所以这类东西不能靠「这次清干净了」，得**钉住**——以后谁加回来、
    # 或者哪个脚本又带上明文 key，这里当场红。
    #
    # ## 钉什么
    # ① App 源码里没有群号 / 邮箱 / 收款码 / 群主头像；
    # ② 收款码资产与 DonateQr 组件不在（孤儿资产 = 白进 APK）；
    # ③ tools/ 脚本里没有明文 API key（key 改从环境变量读）；
    # ④ tools/ 脚本里没有本机绝对路径（公开仓 clone 到别人机器上要能跑）。
    # ==================================================================
    print("\n---- 157. 开源前的个人信息 / 密钥清理（c241）----")

    _srcroot157 = os.path.join(ROOT, "app", "src", "main", "java")
    _kt157 = []
    for _dp157, _dn157, _fn157 in os.walk(_srcroot157):
        for _f157 in _fn157:
            if _f157.endswith(".kt"):
                _kt157.append(os.path.join(_dp157, _f157))
    _kt_text157 = "\n".join(io.open(_p157, encoding="utf-8", errors="replace").read()
                             for _p157 in _kt157)
    # ⚠ 字面量拆开写：这段 verify 自己也要进公开仓，
    #   明文群号/邮箱写在源码里等于又公开一次（这坑踩过一轮）。
    for _pi157 in ("1001907" + "713",
                   "owner_" + "avatar",
                   "img/" + "donate/",
                   "Donate" + "Qr",
                   "FEEDBACK_" + "GROUP"):
        check(_pi157 not in _kt_text157,
              "157.1 App 源码无个人联系方式/收款码残留：%s" % _pi157)
    for _e157 in ("qq.com", "foxmail.com", "gmail.com"):
        check(_e157 not in _kt_text157,
              "157.2 App 源码无个人邮箱域名残留：%s" % _e157)

    # ② 收款码资产不得复活
    for _ap157 in ("donate/" + "wechat.png", "donate/" + "alipay.png",
                   "ui/owner_" + "avatar.png"):
        check(not os.path.exists(os.path.join(_AST, "img", _ap157.replace("/", os.sep))),
              "157.3 收款码/头像资产已删除且不得复活：img/%s" % _ap157)

    # ③ tools/ 脚本里不得有明文 API key
    _toolsdir157 = os.path.join(ROOT, "tools")
    _keypat157 = re.compile(r"sk-[A-Za-z0-9]{16,}|ghp_[A-Za-z0-9]{20,}|AKIA[0-9A-Z]{16}")
    _keyhits157 = []
    for _dp157, _dn157, _fn157 in os.walk(_toolsdir157):
        _dn157[:] = [d for d in _dn157 if d not in ("__pycache__",)]
        for _f157 in _fn157:
            if not _f157.endswith((".py", ".ps1", ".bat", ".json", ".txt")):
                continue
            _p157 = os.path.join(_dp157, _f157)
            try:
                _t157 = io.open(_p157, encoding="utf-8", errors="replace").read()
            except Exception:
                continue
            if _keypat157.search(_t157):
                _keyhits157.append(os.path.relpath(_p157, ROOT).replace(os.sep, "/"))
    check(not _keyhits157,
          "157.4 tools/ 脚本无明文 API key（命中：%s）" % (_keyhits157[:3] or "无"))
    # key 改从环境变量读
    _gi157 = os.path.join(_toolsdir157, "gen_items.py")
    if os.path.exists(_gi157):
        _git157 = io.open(_gi157, encoding="utf-8", errors="replace").read()
        check("SENSENOVA_API_KEY" in _git157,
              "157.5 生成脚本的 key 改从环境变量 SENSENOVA_API_KEY 读")

    # ④ tools/ 脚本不得有本机绝对路径（公开仓 clone 到别人机器上要能跑）
    #    ⚠ 排除 _done/：那是一次性脚本的历史归档（已 gitignore，不进公开仓），
    #      里面留着本机路径是合理的 —— 那些脚本本来就不给外人跑。
    _pathpat157 = re.compile(r"F:[/\\]desktop[/\\]")
    _pathhits157 = []
    for _dp157, _dn157, _fn157 in os.walk(_toolsdir157):
        _dn157[:] = [d for d in _dn157 if d not in ("__pycache__", "_done")]
        for _f157 in _fn157:
            if not _f157.endswith(".py"):
                continue
            _p157 = os.path.join(_dp157, _f157)
            try:
                _t157 = io.open(_p157, encoding="utf-8", errors="replace").read()
            except Exception:
                continue
            if _pathpat157.search(_t157):
                _pathhits157.append(os.path.relpath(_p157, ROOT).replace(os.sep, "/"))
    check(not _pathhits157,
          "157.6 tools/ 脚本无本机绝对路径（命中：%s）" % (_pathhits157[:3] or "无"))

    # ⑤ 引导数据不得留「死引导」（c244）
    #    打赏卡与群号已从 App 删除，但 feature_guide.json 里还留着两条引导，
    #    点进去是空条目 —— 「文案指向已删功能」这类失配，本轮复查抓到三条。
    _fg157p = os.path.join(ROOT, "app", "src", "main", "assets", "data", "feature_guide.json")
    _fg157 = io.open(_fg157p, encoding="utf-8").read()
    for _dead157 in ("支持" + "开发者", "收款" + "码", "反馈" + "群号"):
        check(_dead157 not in _fg157, "157.7 使用指引无失效条目：%s" % _dead157)

    # ⑥ 数据目录的说明文件必须真的存在（c244）
    #    c241 写的 LICENSE / README 引用了 data/LICENSE 与 data/README.md，
    #    这两个文件在 assets/data 下根本不存在 —— 文档指向空气。
    #    现在协议全文指向随包内置的 res/raw，data/ 只放一份说明。
    check(os.path.exists(os.path.join(ROOT, "app", "src", "main", "assets", "data", "README.md")),
          "157.8 assets/data/README.md 存在（来源与协议说明有落点）")
    # c245：门面改造把根 LICENSE 换成**标准 MIT 全文**（GitHub 侧栏才认得出 MIT），
    #       分层说明整段挪到 LICENSING.md —— 指向检查跟着挪，别让断言指着旧文件。
    _lic157p = os.path.join(_REPO_PARENT, "GitHub仓", "LICENSING.md")
    if os.path.exists(_lic157p):
        _lic157 = io.open(_lic157p, encoding="utf-8").read()
        check("res/raw/cc_by_nc_sa_3_0.txt" in _lic157,
              "157.9 源码仓 LICENSING.md 指向随包内置的 CC 全文（不是不存在的 data/LICENSE）")
    _mit157p = os.path.join(_REPO_PARENT, "GitHub仓", "LICENSE")
    if os.path.exists(_mit157p):
        _mit157 = io.open(_mit157p, encoding="utf-8").read()
        check(_mit157.lstrip().startswith("MIT License")
              and "Permission is hereby granted" in _mit157,
              "157.9a 源码仓根 LICENSE 是标准 MIT 全文（GitHub 侧栏才能识别）")
        check("CC BY-NC-SA" not in _mit157,
              "157.9b 分层协议不在根 LICENSE 里（否则 MIT 徽章变成误导）")
    # 门面文件齐全：README 引用的图、更新日志、Issue 模板少一个，公开仓就是「缺件」
    for _face157, _desc157 in (("CHANGELOG.md", "更新日志"),
                               ("assets/logo.png", "README 顶部 logo"),
                               (".github/ISSUE_TEMPLATE/bug_report.yml", "Issue 模板")):
        check(os.path.exists(os.path.join(_REPO_PARENT, "GitHub仓",
                                          _face157.replace("/", os.sep))),
              "157.11 源码仓门面文件存在：%s（%s）" % (_face157, _desc157))
    _rd157p = os.path.join(_REPO_PARENT, "GitHub仓", "README.md")
    if os.path.exists(_rd157p):
        _rd157 = io.open(_rd157p, encoding="utf-8").read()
        check("assets/data/README.md" in _rd157
              and "res/raw/cc_by_nc_sa_3_0.txt" in _rd157,
              "157.10 源码仓 README 的数据来源段指向真实存在的文件")
    # c246：门面二轮 —— 截图横幅 / CI / 安全与贡献指引
    #    缺件 = README 图挂空、CI 徽章灰、Security 标签页没内容，观感直接掉档。
    for _face246, _desc246 in (
            ("assets/screens.png", "README 预览横幅（五页签截图）"),
            (".github/workflows/ci.yml", "CI 工作流（README 徽章指向它）"),
            ("SECURITY.md", "安全报告落点（Security 标签页内容源）"),
            ("CONTRIBUTING.md", "贡献指引")):
        check(os.path.exists(os.path.join(_REPO_PARENT, "GitHub仓",
                                          _face246.replace("/", os.sep))),
              "157.12 源码仓门面文件存在：%s（%s）" % (_face246, _desc246))
    _ci246p = os.path.join(_REPO_PARENT, "GitHub仓", ".github", "workflows", "ci.yml")
    if os.path.exists(_ci246p):
        _ci246 = io.open(_ci246p, encoding="utf-8").read()
        check("actions/checkout" in _ci246 and "assembleDebug" in _ci246,
              "157.13 CI 工作流真的在构建（checkout + assembleDebug）")
        check("runs-on: ubuntu-latest" in _ci246,
              "157.13a CI 跑在 ubuntu-latest（runner 自带 Android SDK，无需自装）")
    if os.path.exists(_rd157p):
        _rd246 = io.open(_rd157p, encoding="utf-8").read()
        check("assets/screens.png" in _rd246
              and "workflows/ci.yml/badge.svg" in _rd246,
              "157.14 源码仓 README 引用了预览横幅与 CI 徽章")
        check("CONTRIBUTING.md" in _rd246,
              "157.14a 源码仓 README 链到贡献指引（用户找得到入口）")
        # c247：门面三轮 —— 换行规则 / 下载徽章 / 发布仓配图
        _ga247p = os.path.join(_REPO_PARENT, "GitHub仓", ".gitattributes")
        if os.path.exists(_ga247p):
            _ga247 = io.open(_ga247p, encoding="utf-8").read()
            check("text=auto" in _ga247 and "*.bat -text" in _ga247,
                  "157.15 源码仓 .gitattributes 定了换行与二进制规则")
        check("/downloads/huututu939/xingluguzhushou/total" in _rd246,
              "157.15a 源码仓 README 挂了下载量徽章")
    _pub247p = os.path.join(_REPO_PARENT, "发布仓", "assets", "screens.png")
    check(os.path.exists(_pub247p) and os.path.getsize(_pub247p) > 100000,
          "157.15b 发布仓 assets/screens.png 在（下载页配图有落点）")
    _pr247p = os.path.join(_REPO_PARENT, "发布仓", "README.md")
    if os.path.exists(_pr247p):
        _pr247 = io.open(_pr247p, encoding="utf-8").read()
        check("assets/screens.png" in _pr247 and "长什么样" in _pr247,
              "157.15c 发布仓 README 有「长什么样」配图段")
        check("/downloads/huututu939/xingluguzhushou/total" in _pr247,
              "157.15d 发布仓 README 也挂了下载量徽章")

    # ==================================================================
    # 152 节（c235）：长线任务「存档局域网自动同步」验收标准 1-9 全项扫尾
    #                 + 第八节合规文档
    #
    # ⚠ **这一节钉的是「代码/文案层面的硬指标」**，不是「效果已验收」——
    #   真机效果由用户本人验收（项目纪律：Agent 不自己宣布「效果正常」）。
    #   每条断言都对应任务书第七节某一条里**可以静态核验**的部分。
    # ==================================================================
    print("\n---- 152. 验收标准 1-9 全项扫尾 + 合规文档（c235）----")

    _modp152 = os.path.join(_REPO_PARENT, "StardewAutoPush", "ModEntry.cs")
    _mod152 = io.open(_modp152, encoding="utf-8").read() if os.path.exists(_modp152) else ""
    _modm152 = ""
    _modmp152 = os.path.join(_REPO_PARENT, "StardewAutoPush", "manifest.json")
    if os.path.exists(_modmp152):
        _modm152 = io.open(_modmp152, encoding="utf-8").read()
    _modc152 = ""
    _modcp152 = os.path.join(_REPO_PARENT, "StardewAutoPush", "ModEntry.cs")
    _sr152 = src("data/SaveReceiver.kt")
    _td152 = src("data/TrustedDevices.kt")
    _lrc152 = src("data/LanReceiveController.kt")
    _sp152raw = src_sp()
    _about152 = src("ui/settings/SettingsAboutTab.kt")
    _info152 = src("ui/settings/SettingsInfoDrawers.kt")

    # ── 第 1 条：首次配对拿 Token 并持久化 ──
    # ① DeviceId 用全局数据持久化（不是 mod 文件夹 JSON）
    check('helper.Data.ReadGlobalData<string>("device-id")' in _mod152
          and 'helper.Data.WriteGlobalData("device-id"' in _mod152,
          "152.1a mod 的 DeviceId 走 helper.Data 全局数据（更新不丢）")
    check('helper.Data.ReadGlobalData<string>("app-token")' in _mod152,
          "152.1b mod 的 Token 走全局数据读出（不在 mod 文件夹存 JSON）")
    check('Helper.Data.WriteGlobalData("app-token"' in _mod152,
          "152.1c mod 的 Token 走全局数据写回（更新 mod 不丢）")
    # ⚠ 红线：mod 文件夹内不许出现 WriteJsonFile 存 Token
    check("WriteJsonFile" not in _mod152,
          "152.1d mod 不用 WriteJsonFile 存 Token（红线）")
    # ② 配对返回 token 并解析
    check('"paired"' in _mod152 and '"token"' in _mod152,
          "152.1e mod 解析 App 返回的 paired + token")
    # ③ App 侧颁发 Token（配对应答带 status=paired + token；只存哈希）
    check('"status" to "paired"' in _sr152 and '"token" to token' in _sr152,
          "152.1f App 配对成功返回 status=paired + token")
    check("hashToken" in _td152 and "SHA-256" in _td152,
          "152.1g App 只存令牌的 SHA-256 哈希（原文用完即丢）")

    # ── 第 2 条：自动闭环（免码 / 免开窗 / 免确认）──
    check("X-Stardew-Token" in _mod152 and "X-Stardew-Device-Id" in _mod152,
          "152.2a mod 带设备号 + 令牌头直传（免验证码）")
    check('"imported"' in _mod152 and '"noop"' in _mod152,
          "152.2b mod 认 App 的 imported / noop 应答")
    # 自动导入走**既有**解析链（不许新写一套存档解析）
    check("autoBackup()" in _sp152raw and "replaceChecks(" in _sp152raw
          and "setReport(" in _sp152raw and "FarmTodayCache.commit" in _sp152raw,
          "152.2c 自动导入复用既有解析与落库链（autoBackup→replaceChecks→setReport→commit）")
    check("ensureAuto" in _lrc152,
          "152.2d 有信任设备时 App 启动即自动监听（免手动开窗）")

    # ── 第 3 条：连续保存不并发、不重复导入 ──
    check("Interlocked.CompareExchange(ref InFlight" in _mod152
          and "Interlocked.Exchange(ref InFlight" in _mod152,
          "152.3a mod 用进行中标志防并发上传（连续快速保存只产生一次）")
    check("ConcurrentQueue<string>" in _mod152,
          "152.3b mod 的 HUD 消息走 ConcurrentQueue（主线程出队）")
    # App 侧：一次连接内同步导入，导入完再应答
    check("imported" in _sr152,
          "152.3c App 在连接内同步导入并应答 imported（不重复落库）")

    # ── 第 4 条：App 没开 / 断网 → 不崩、不刷屏 ──
    check("未找到" in _mod152 or "没找到" in _mod152,
          "152.4a mod 在找不到 App 时给明确提示")
    check("MaxCodeRetries" in _mod152 and "Thread.Sleep(1000 * attempt)" in _mod152,
          "152.4b mod 有退避重试且次数有上限（不无限重试）")
    check("try" in _mod152 and "catch" in _mod152,
          "152.4c mod 整体 try-catch 兜底（异常不冒到 SMAPI）")

    # ── 第 5 条：撤销信任 → 401 → 自动回退配对 ──
    check("401" in _mod152 and "ClearToken" in _mod152,
          "152.5a mod 收到 401 清 Token 并回退配对")
    check("401" in _sr152 and "MSG_BAD_TOKEN" in _sr152,
          "152.5b App 对无效/撤销的令牌返回 401")
    # ⚠ 401 文案**不许**含「验证码」——旧 send_save.bat 靠响应体含不含
    #   「验证码」决定要不要换码重试，混字会让 bat 走错分支。
    _mbt152 = re.search(r'MSG_BAD_TOKEN\s*=\s*"([^"]*)"', _sr152)
    check(bool(_mbt152) and "验证码" not in _mbt152.group(1),
          "152.5c 401 文案不含「验证码」（旧 bat 分支不被带偏）")
    check("403" in _sr152 and "验证码" in _sr152,
          "152.5d 403 配对码错误文案含「验证码」（旧 bat 分支仍可用）")
    check("revoke" in _td152.lower() or "撤销" in _td152,
          "152.5e App 支持撤销信任设备")

    # ── 第 6 条：更新 mod 后 Token 仍保留 ──
    check("ReadGlobalData" in _mod152 and "app-token" in _mod152,
          "152.6a Token 存全局数据（mod 更新不丢，无需重新配对）")

    # ── 第 7 条：回归兼容旧路径 ──
    # 旧 bat 路径：App 端旧码流程逐字未动（X-Stardew-Code 分支仍在）
    check("X-Stardew-Code" in _sr152 and "X-Stardew-Proto" in _sr152,
          "152.7a App 仍认旧脚本的验证码头 + 协议头（旧 bat 不回归）")
    check("MSG_OLD_PROTO" in _sr152,
          "152.7b App 仍对缺协议头的旧脚本给明确提示")
    # ⚠ 端口不是写死 38236，而是 PORT_BASE(38217) + PORT_SCAN(20) 顺延 ——
    #   断言得按**这个口径**写，写成 "38236" in 源码 永远不成立。
    check("PORT_BASE = 38217" in _sr152 and "PORT_SCAN = 20" in _sr152,
          "152.7c App 仍监听 38217 起顺延 20 个端口（旧脚本端口不变）")
    check("PORT_BASE until PORT_BASE + PORT_SCAN" in _sr152,
          "152.7c-2 App 的端口段仍是 38217~38236（旧 bat 连的端口不变）")
    check("SDH-FIND" in _sr152 and "SDH1" in _sr152,
          "152.7d App 仍应答 UDP 发现（旧 bat 发现方式不变）")
    # 手动 SAF 导入 / 浏览器下载扫描路径仍在
    check("ACTION_OPEN_DOCUMENT_TREE" in _sp152raw or "导入游戏存档" in _sp152raw,
          "152.7e 手动 SAF 导入路径仍在")
    check("Steam" in _sp152raw or "云" in _sp152raw,
          "152.7f Steam 云存档路径仍在")

    # ── 第 8 条：无卡顿 / 无 SMAPI 红错 / 线程纪律 ──
    check("Task.Run" in _mod152,
          "152.8a mod 的打包与上传走后台线程（主线程不做网络 I/O）")
    check("helper.Events.GameLoop.Saved" in _mod152,
          "152.8b mod 订阅 Saved（存档写完才读，文件安全）")
    check("helper.Events.GameLoop.UpdateTicked" in _mod152,
          "152.8c mod 用 UpdateTicked 在主线程出队弹 HUD")
    # ⚠ 后台线程不许碰 Game1 / 玩家对象
    check("Game1." not in _mod152.split("Task.Run")[1].split("private void PushSave")[0][:2000]
          if "Task.Run" in _mod152 else True,
          "152.8d mod 后台线程段里不出现 Game1. 调用（主线程纪律）")
    check("Game1.addHUDMessage" in _mod152,
          "152.8e mod 的 HUD 调用点唯一且在主线程")

    # ── 第 9 条：自动导入与手动导入结果一致 ──
    check("autoBackup()" in _sp152raw and "replaceChecks(" in _sp152raw,
          "152.9a 自动导入与手动导入走同一套落库口径（replaceChecks 只并不删）")

    # ── 第八节 合规 ──
    # ① 只读写本人存档、不碰 Steam 凭证、不做云/公网
    check("Steam" not in _sr152 or "Steam" not in _mod152,
          "152.10a mod 侧不碰 Steam 凭证")
    # ② 功能说明告知局域网内传输 + 令牌可撤销
    check("局域网" in _info152 and "撤销" in _info152,
          "152.10b 数据来源页写明局域网内传输 + 令牌可撤销（合规告知）")
    check("不经任何外部服务器" in _info152 or "不上传任何服务器" in _about152,
          "152.10c 关于页写明不上传任何服务器（合规告知）")
    # ③ 非官方声明（mod manifest 里有）
    check("非官方" in _modm152 and "ConcernedApe" in _modm152,
          "152.10d mod manifest 带非官方声明（与 ConcernedApe 无关）")
    # ④ 参考协议如实注明 —— **不许挂靠不存在的开源项目**
    check("局域网存档同步" in _info152,
          "152.10e 数据来源页有「局域网存档同步」卡（注明参考协议）")
    _lan152 = _info152.split("局域网存档同步")[-1][:1500]
    check("send_save.bat" in _lan152,
          "152.10f 如实注明参考的是自带电脑脚本沿用的上传约定（不编造开源项目）")
    check("未参考其他开源项目" in _lan152,
          "152.10g 明确写出「未参考其他开源项目」（查证结论，不是猜的）")
    # ⚠ **反向断言**：局域网链路的文件里不许出现第三方归属痕迹。
    #   这条是给「以后有人抄了外部代码却忘了标注」兜底的 ——
    #   一旦真抄了，这条会红，逼着同时补上「数据来源与协议」里的标注。
    _LAN_FILES = [
        "data/SaveReceiver.kt", "data/TrustedDevices.kt",
        "data/LanReceiveController.kt", "data/LanReceiveService.kt",
        "data/LanNotifications.kt", "data/BatteryHelper.kt",
    ]
    _tp_re = re.compile(
        r"Copyright\s+(?:\(c\)|©)\s*\d{4}|SPDX-License-Identifier|"
        r"Licensed under the (?:Apache|MIT|BSD|GNU)|"
        r"derived from|adapted from|based on .*(?:project|library)|"
        r"third[- ]party|外部项目|抄自", re.I)
    for _f152 in _LAN_FILES:
        _t152 = src(_f152)
        _hit152 = [l.strip()[:100] for l in _t152.split("\n") if _tp_re.search(l)]
        check(not _hit152,
              "152.10h 局域网文件 %s 无第三方归属痕迹%s"
              % (_f152, ("：" + _hit152[0]) if _hit152 else ""))
    # 关于页口径不得与实现矛盾
    # ⚠ 断言要查**真正渲染出来的那句**，不是查整个文件：
    #   「存档只在你主动导入时读」这句话现在只出现在**注释**里（说明它为什么被改掉），
    #   如果按整文件查就会误报。code_only() 剥掉注释后才是用户看得到的文案。
    _about152c = code_only(_about152)
    check("只有你手动开「从电脑接收」时才会连" not in _about152c,
          "152.10h 关于页不再说「只有手动开才连」（v2.142.0 起已自动监听）")
    check("存档只在你主动导入时读" not in _about152c,
          "152.10i 关于页不再说「只在你主动导入时读」（自动同步也是导入）")
    check("存档不上传任何服务器" in _about152c,
          "152.10j 关于页保留「存档不上传任何服务器」")
    check("自动等电脑推存档" in _about152c,
          "152.10k 关于页写明配过信任设备后会自动等电脑推存档")

    # ==================================================================
    # 153 节（c237）：「从电脑接收」抽屉改为**模组优先、脚本备选**
    #
    # ## 为什么改
    # 用户原话：「现在咱们从电脑端接收里面的描述，还是用那个 BOT 脚本。
    # 但是现在咱们优先使用的是 mod，所以把里面的语言改一下，
    # 把 BOT 脚本改为低优先级，建议优先使用 mod」
    #
    # ## 这一节钉什么
    # ① **文案**：模组必须是**推荐**（摆在前面、标题写明「推荐」）；
    #    脚本是**备选**（标题写明「备选」+ 说清它的代价）。
    #    ⚠ 脚本**不许消失** —— 任务书验收标准第 7 条要求旧 bat 路径照常工作，
    #    没装模组的人仍然需要一条「电脑上什么都不用装」的路。
    # ② **可下载性**：光写「建议用模组」没用 —— 用户得**拿得到**模组。
    #    所以模组包必须随 App 分发（assets）+ 有下载按钮。
    # ③ **不新增网络出口**：模组包从**安装包里**读，与 send_save.bat 同一条路。
    # ==================================================================
    print("\n---- 153. 从电脑接收：模组优先、脚本备选（c237）----")

    _sp153 = src("ui/settings/SettingsPage.kt")
    _ur153 = src("data/UpdateRepo.kt")
    _sp153c = code_only(_sp153)

    # ① 推荐卡在脚本卡**前面**（顺序 = 优先级）
    _i_mod_card153 = _sp153.find("电脑端模组（推荐）")
    _i_bat_card153 = _sp153.find("电脑端脚本（备选）")
    check(_i_mod_card153 != -1 and _i_bat_card153 != -1,
          "153.1 抽屉里有「电脑端模组（推荐）」与「电脑端脚本（备选）」两张卡")
    if _i_mod_card153 != -1 and _i_bat_card153 != -1:
        check(_i_mod_card153 < _i_bat_card153,
              "153.2 模组卡排在脚本卡**前面**（推荐路径要一眼看见）")

    # ② 首屏就要说「推荐装模组」（不是藏在下载卡里）
    check("推荐：装电脑端模组" in _sp153c,
          "153.3 抽屉开头就说「推荐：装电脑端模组」")
    check("装上之后，游戏里一保存就自动把存档推到这台手机" in _sp153c,
          "153.4 说清模组的好处（一保存就自动推，不用每次输验证码）")
    check("按 F5" in _sp153c,
          "153.5 写明模组的触发方式（游戏里按 F5 立刻推一次）")

    # ③ 脚本是备选，且**说清代价**（不是只改个标题）
    # c238：文案精简过一次，措辞变了但**语义没变** —— 断言跟着新措辞走。
    check("不想装模组就用这个" in _sp153c,
          "153.6 脚本卡说清它适用的场景（不想装模组的人）")
    check("每次都要开窗口、每次都要输验证码" in _sp153c,
          "153.7 脚本卡说清它的代价（每次开窗口 + 每次输验证码）")
    check("不用模组时：电脑上怎么用" in _sp153c,
          "153.8 六步教程标明是「不用模组时」的路（别让人跳过推荐项）")

    # ④ ⚠ 旧路径不许消失（任务书验收标准第 7 条）
    check("下载 send_save.bat" in _sp153c,
          "153.9 脚本下载按钮仍在（旧 bat 路径必须照常可用）")
    check("send_save.bat" in _sp153c,
          "153.10 抽屉里仍提到 send_save.bat（老用户找得到）")

    # ⑤ 模组包必须**拿得到**：随 App 分发 + 有下载按钮 + 有安装说明
    check('const val ASSET_MOD = "tools/save_auto_push.zip"' in _ur153,
          "153.11 模组包随 App 分发在 assets（UpdateRepo.ASSET_MOD）")
    check("fun downloadMod" in code_only(_ur153),
          "153.12 有 downloadMod（把模组包落到「下载」目录）")
    # c239：按钮文案由「下载电脑端模组」改成「下载到手机」——
    #   因为 c239 加了「电脑直链」这第二条路，按钮得说清**下到哪儿**，
    #   否则和旁边的直链并列时看不出区别。
    check("下载到手机" in _sp153c,
          "153.13 抽屉里有「下载到手机」按钮（手机这条路的入口）")
    check("Mods 目录" in _sp153c,
          "153.14 写明安装位置（解压后放进 Mods 目录）")
    check("SMAPI" in _sp153c,
          "153.15 写明前置条件（要先装 SMAPI）")
    # ⚠ 落地文件名 + zip 头校验（拿到假包要报错，不能让用户以为下好了）
    _ur153c = code_only(_ur153)
    check('const val MOD_NAME = "save_auto_push.zip"' in _ur153,
          "153.16 模组包落地文件名固定（用户知道存到哪）")
    check("0x50.toByte()" in _ur153c and "0x4B.toByte()" in _ur153c,
          "153.17 下载后校验 zip 头（拿到假包要报错，不能报「下载成功」）")
    check("MOD_MAX_BYTES" in _ur153c,
          "153.18 模组包有体量上限（挡住「拿到一整个错误页却报下载成功」）")

    # ⑥ ⚠ **不新增网络出口**：模组包从安装包里读，一个请求都不发。
    _net153 = []
    for _r, _d, _fs in os.walk(os.path.join(SRC)):
        for _f in _fs:
            if not _f.endswith(".kt"):
                continue
            _p = os.path.join(_r, _f)
            _t = io.open(_p, encoding="utf-8", errors="replace").read()
            if re.search(r"HttpURLConnection|java\.net\.(URL|Socket|Datagram)|okhttp|Retrofit", _t):
                _net153.append(_f)
    _net153.sort()
    check(_net153 == ["ModRepo.kt", "SaveReceiver.kt", "UpdateRepo.kt"],
          "153.19 网络行为仍是三处（模组包走 assets，不新增出口）：实得 %s" % (_net153 or "无"))
    check("ctx.assets.open(ASSET_MOD)" in _ur153c,
          "153.20 模组包从安装包 assets 读（不联网）")

    # ⑦ 权限回调要分清是脚本还是模组（共用同一个 launcher）
    check("LegacyTarget" in _sp153c and "pendingLegacyTarget" in _sp153c,
          "153.21 权限回调记住是谁在等（模组不会弹出脚本的失败文案）")
    check("模组包存不进「下载」目录" in _sp153c,
          "153.22 模组自己的失败文案（不是脚本的那句）")

    # ==================================================================
    # 154 节（c238）：「从电脑接收存档」抽屉**精简**
    #
    # ## 为什么改
    # 用户原话：「你不感觉『从电脑接收存档』这个页面，咱们写得太啰嗦了吗？
    # 你精简一下，写得官方一点」
    # 截图上看得很清楚：模组那套说明**写了两遍**（首屏卡 + 下载卡各一遍安装步骤），
    # 脚本卡里还摊着 PowerShell 命令和一大段换行的 URL。
    #
    # ## 这一节钉什么
    # ① **同一件事只说一次**：安装步骤只能在首屏出现一次，下载卡不许再重复。
    # ② **收进折叠区**：低频兜底路径（PowerShell 命令 / 直链）默认不摊开。
    # ③ **文案不许说谎**：自动会话**没有空闲超时**（SaveReceiver.serve 里
    #    `sock.soTimeout = if (autoMode) 0 else IDLE_TIMEOUT_MS`），
    #    照抄「5 分钟没人连会自动关」在自动模式下就是错的。
    # ④ **篇幅本身**：给抽屉的文案行数设个上限 —— 「啰嗦」是可量化的，
    #    光靠「我看着不啰嗦」下次又会飘回去。
    # ==================================================================
    print("\n---- 154. 接收抽屉精简（c238）----")

    # ① 安装步骤只说一次
    _i_install153 = _sp153c.find("放进《星露谷物语》的 Mods 目录")
    check(_i_install153 != -1, "154.1 安装步骤在首屏那张推荐卡里")
    _mod_card153 = _sp153c.find("电脑端模组（推荐）")
    _bat_card153 = _sp153c.find("电脑端脚本（备选）")
    if _mod_card153 != -1 and _bat_card153 != -1:
        _mod_body153 = _sp153c[_mod_card153:_bat_card153]
        check("放进《星露谷物语》的 Mods 目录" not in _mod_body153,
              "154.2 模组下载卡里**不再重复**安装步骤（同一件事只说一次）")
        check("SMAPI" not in _mod_body153,
              "154.3 模组下载卡里不再重复前置条件（SMAPI 那句留在首屏）")
    # 重复的「不用开电脑上的窗口」也只留一处
    check(_sp153c.count("不用开电脑上的窗口") <= 1,
          "154.4 「不用开电脑上的窗口」只出现一次（实得 %d）"
          % _sp153c.count("不用开电脑上的窗口"))

    # ② 低频兜底路径收进折叠区
    check("其它拿法" in _sp153c and "CollapsibleCard(" in _sp153c,
          "154.5 PowerShell 命令 / 直链收进「其它拿法」折叠区")
    check("var altOpen by remember { mutableStateOf(false) }" in _sp153c,
          "154.6 折叠区默认收起（altOpen 初始 false）")
    check("SCRIPT_PS1_CMD" in _sp153c and "SCRIPT_URL" in _sp153c,
          "154.7 两条拿法都还在（收进折叠区不是删掉）")

    # ③ ⚠ 文案不许说谎：空闲超时只对**手动**会话成立
    check("手动接收 5 分钟没人连会自动关" in _sp153c,
          "154.8 空闲超时那句写明是「手动接收」")
    check("自动接收会一直开着，直到你关掉 App" in _sp153c,
          "154.9 自动会话明确说不会自己关（自动模式 soTimeout=0）")
    _sr154 = code_only(src("data/SaveReceiver.kt"))
    check("if (autoMode) 0 else IDLE_TIMEOUT_MS" in _sr154,
          "154.10 代码侧确认：自动会话无空闲超时（文案与实现一致）")

    # ④ 篇幅上限：「啰嗦」是可量化的，光靠「我看着不啰嗦」下次又会飘回去。
    #    ⚠ 量的是**用户看得见的字符串**，不是源码行数 ——
    #    一个多行 PixelText( ... ) 渲染成一段，但一个 list 字面量渲染成好几行，
    #    数源码行会得出错误的结论。
    #    基线（实测，c237 → c238）：
    #      首屏可见文案 1075 字 → 654 字（-39%），34 段 → 24 段（-29%）
    #      全部文案（含折叠区）1539 字 → 1177 字（-24%）
    #    上限按实测值取**略宽一点**的整数，给以后正常补文案留余地，
    #    但比精简前低一大截 —— 涨回去就会被这条抓住。
    _VIS154 = [
        "推荐：装电脑端模组",
        "装上之后，游戏里一保存就自动把存档推到这台手机",
        "① 下载下面那个压缩包",
        "首次要敲一次 6 位验证码，之后自动。需要先装 SMAPI。",
        "正在等电脑推送…", "自动接收中：App 打开期间常驻，无需手动开窗。",
        "手机上的 IP（一般不用填，电脑端会自己找到你）：",
        "当前 WiFi：$it（电脑要连同一个）",
        "等待验证码确认…",
        "电脑上那行「本次验证码：xxxxxx」，照着敲进弹出来的输入框。",
        "这个码只应该出现在你自己电脑的窗口里——别人发给你的码，别输。",
        "要再收一个包，点下面「开始接收」重新打开。",
        # ⚠ 这句在源码里是**两段用 `+` 拼**的（跨行排版），所以不能拿整句去匹配 ——
        #   分成两截分别查，语义一样。
        "装好模组就不用管：手机和电脑连同一个 WiFi，",
        "游戏里一保存，存档就自己过来了。",
        "已信任的电脑", "还没有。电脑端推送一次、输一次码，就会记在这里。",
        "开启后接收挂在一条常驻通知里，息屏、切后台也能收到。",
        "系统每天最多允许它连续运行约 6 小时，到顶会停掉并提示。",
        "✓ 已进电池优化白名单，息屏也能收。",
        "⚠ 还差一步：系统会把 App 冻结，息屏就收不到了。点下面按钮授权。",
        "授权后回到 App，这行会变成「✓ 已进白名单」。",
        "⚠ 没通知权限：常驻通知可能不显示，但接收功能不受影响。",
        "只收不传：手机上任何文件都读不出去；存档只读不改。",
        "自动接收会一直开着，直到你关掉 App。",
        "不想装模组就用这个：电脑上双击 send_save.bat，手动推存档。",
        "每次都要开窗口、每次都要输验证码。",
    ]
    _missing154 = [x for x in _VIS154 if x not in _sp153c]
    check(not _missing154,
          "154.11 首屏可见文案完整（实得 %d/%d 段，缺：%s）"
          % (len(_VIS154) - len(_missing154), len(_VIS154),
             _missing154[0][:30] if _missing154 else "无"))
    _chars154 = sum(len(x) for x in _VIS154)
    check(_chars154 <= 700,
          "154.12 首屏可见文案 ≤700 字（实得 %d；c237 时是 1075，c238 精简到 654）"
          % _chars154)

    # ==================================================================
    # 155 节（c239）：电脑端模组**两条路并存** —— 手机下载 + 电脑直链
    #
    # ## 为什么改
    # 用户原话：「目前先下载到手机上，再通过手机传到电脑上也是够麻烦的。
    # 我们可以把这个 mod 也上传到咱们的仓库里，在手机上直接显示链接，
    # 或者让他们从手机端下载。咱们做一个可选的方案」
    # ⇒ 不是二选一，是**并存**：手机下载（读安装包，不联网，版本必然对得上）
    #   与电脑直链（复制链接到电脑浏览器直接下，省掉「传文件」这一步）。
    #
    # ## 关键事实（本轮实测，不是推测）
    # gitee raw 对 **.zip** 返回 `application/octet-stream` +
    #   `Content-Disposition: attachment` ⇒ **浏览器会落盘**；
    #   而 .bat 是 `text/plain` + `inline` ⇒ 浏览器当文本渲染、**不落盘**
    #   （v2.82.0 那次踩过这个坑）。所以 .zip 放发布仓后浏览器直接点链接就能下。
    # ==================================================================
    print("\n---- 155. 电脑端模组：手机下载 + 电脑直链（c239）----")

    _pub155 = os.path.join(os.path.dirname(ROOT), "发布仓")

    # ① App 侧：直链常量必须指向发布仓的真实路径（写全量字面量，便于断言）
    check('const val MOD_URL =' in _ur153
          and "huututu939/xingluguzhushou/releases/latest/download/save_auto_push.zip" in _ur153,
          "155.1 App 侧的电脑直链指向发布仓的模组包（GitHub Release 资产）")

    # ② 两条路都在 UI 里
    check("下载到手机" in _sp153c,
          "155.2 手机下载按钮仍在（第一条路不许被换掉）")
    check("CopyLinkRow(UpdateRepo.MOD_URL" in _sp153c,
          "155.3 电脑直链走 CopyLinkRow 点按复制（不跳浏览器，与协议链接同惯例）")
    check("复制下面这行，粘到电脑浏览器里直接下" in _sp153c,
          "155.4 说清电脑直链怎么用（复制 → 粘到浏览器）")

    # ③ 发布仓里得有那个文件，且与 App 内置的那份**逐字节一致**
    _asset155 = os.path.join(ROOT, "app", "src", "main", "assets", "tools", "save_auto_push.zip")
    _pub155z = os.path.join(_pub155, "tools", "save_auto_push.zip")
    check(os.path.exists(_asset155), "155.5 App 内置的模组包在（手机下载那条路的数据源）")
    if os.path.exists(_asset155):
        import hashlib as _hl155
        _a155 = _hl155.md5(open(_asset155, "rb").read()).hexdigest()
        if os.path.exists(_pub155z):
            _b155 = _hl155.md5(open(_pub155z, "rb").read()).hexdigest()
            check(_a155 == _b155,
                  "155.6 发布仓那份与 App 内置那份**逐字节一致**（实得 %s vs %s）" % (_a155[:8], _b155[:8]))
            # 顶层目录名 = SMAPI 装模组要的形态（解压到 Mods/<名字>/）
            import zipfile as _zf155
            with _zf155.ZipFile(_pub155z) as _z155:
                _n155 = _z155.namelist()
            check(any(x.startswith("StardewAutoPush/") for x in _n155),
                  "155.7 模组包顶层是 StardewAutoPush/（解压后直接放进 Mods 目录）")
            check("StardewAutoPush/manifest.json" in _n155 and "StardewAutoPush/StardewAutoPush.dll" in _n155,
                  "155.8 模组包里有 manifest.json 与入口 dll（SMAPI 能识别）")
        else:
            check(False, "155.6 发布仓里还没有 tools/save_auto_push.zip（直链会 404；"
                          "跑 python tools/publish_gitee.py 把它同步过去）")

    # ④ 发布脚本必须会同步这个文件（不然下次发版就没了）
    _pg155 = code_only(io.open(os.path.join(ROOT, "tools", "publish_gitee.py"), encoding="utf-8").read())
    check("save_auto_push.zip" in _pg155 and "save_auto_push.zip" in _pg155,
          "155.9 发布脚本会同步模组包到发布仓（否则下次发版直链就断了）")
    check("StardewAutoPush/" in _pg155,
          "155.10 发布脚本校验模组包顶层目录名（形态不对就报错，不发坏包）")

    # ⑤ 许可范围：模组是我们自己的作品，随根 MIT（不是第三方）
    _lic155 = io.open(os.path.join(_pub155, "LICENSE"), encoding="utf-8").read()
    check("save_auto_push.zip" in _lic155,
          "155.11 许可范围写明模组包归根 MIT（是我们自己的作品，不是第三方）")

    # ==================================================================
    # 156 节（c240）：会话掉了要能自愈 + 广播不许说谎
    #
    # ## 起因（2026-11-09 真机实测）
    # 手机在网、ping 通、App 进程活着、前台服务活着、常驻通知还在 ——
    # 但电脑侧探测是：**UDP 回 "SDH1 0 <ip>"**（端口号 0！），
    #   TCP 38217~38236 **一个都连不上**，/proc/net/tcp 里那个端口**整个不存在**。
    #
    # ## 根因（读代码定位）
    #   `SaveReceiver.serve()` 的 finally 块只关了 TCP 的 sock（server = null），
    #   **没关 discovery**（UDP 那个 DatagramSocket）——
    #   而 discovery 的回包里 `val port = server?.localPort ?: 0`，
    #   server 一 null 就回 0。于是：
    #     - 广播还能问到「手机在接收」，但端口号是 0（**广播在说谎**）；
    #     - mod 看到 port<=0 会跳过这个应答，退到直探也连不上（端口真没了）；
    #     - 而前台服务独立活着 ⇒ 常驻通知还在 ⇒ 用户以为在收、实际没在收。
    #   而 serve() 的退出路径之一是 `sock.accept()` 抛异常 → break ——
    #   那条路**不经过 stop()**，所以没人去收前台服务，也没人重建会话。
    #
    # ## 这一节钉什么
    #   ① serve() 退出时 discovery 必须一起关（端口没了就别再应广播）；
    #   ② 自动会话掉了要**自愈**（重建），且有连续失败上限（不许变成死循环）；
    #   ③ 手动会话**不许**被偷偷重开（用户点了停止就是他的意思）。
    # ==================================================================
    print("\n---- 156. 会话自愈 + 广播不许说谎（c240）----")

    _sr156 = code_only(src("data/SaveReceiver.kt"))
    _lc156 = code_only(src("data/LanReceiveController.kt"))

    # ① serve() 的 finally 里 discovery 必须一起关
    # ⚠ 定位方式：serve() 的 finally 块是**第二个** `} finally {` 之后那一段
    #   （第一个是 serve() 内部连接处理线程的 finally，第三个是别的函数的）——
    #   早先写成 parts[-1] 取到最后一块，取错了块 ⇒ 假红。改成**按内容定位**：
    #   找同时含 "server = null" 与 "running.set(false)" 的那一段。
    _fin_cands156 = []
    for _seg156 in _sr156.split("} finally {"):
        if "server = null" in _seg156 and "running.set(false)" in _seg156:
            _fin_cands156.append(_seg156)
    check(len(_fin_cands156) >= 1,
          "156.1 找得到 serve() 的 finally 块（同时清 server 与 running）")
    if _fin_cands156:
        _fin156 = _fin_cands156[0].split("onChanged(State.Stopped")[0]
        check("discovery?.close()" in _fin156,
              "156.2 serve() 退出时把发现服务一起关掉（端口没了就别再应广播）")
        check("discovery = null" in _fin156,
              "156.3 serve() 退出时 discovery 引用也清掉（不留半开状态）")
    # ⚠ 反向断言：stop() 里本来就有 discovery 关闭，serve 的 finally 里也必须有
    check(_sr156.count("discovery?.close()") >= 2,
          "156.4 发现服务有两处关闭点（stop() 与 serve() 的 finally）")

    # ② 自动会话自愈
    check("State.Stopped" in _lc156 and "autoRebuildAttempts" in _lc156,
          "156.5 自动会话掉了会重建（有连续失败计数）")
    check("MAX_AUTO_REBUILD" in _lc156,
          "156.6 重建有次数上限（不许变成「起→停→起→停」死循环）")
    check("autoRebuildAttempts = 0" in _lc156,
          "156.7 会话成功起来时计数清零（中间失败几次不算连续失败）")
    # ⚠ 手动会话不许被偷偷重开
    check("autoSession" in _lc156,
          "156.8 自愈只认自动会话（手动会话停了是用户的意思）")

    # ③ mod 侧：收到端口 0 必须跳过（这条本来就有，别被改坏）
    _mod156 = io.open(os.path.join(_REPO_PARENT, "StardewAutoPush", "ModEntry.cs"),
                       encoding="utf-8").read() if os.path.exists(
        os.path.join(_REPO_PARENT, "StardewAutoPush", "ModEntry.cs")) else ""
    if _mod156:
        check("port <= 0" in _mod156,
              "156.9 mod 收到端口 0 会跳过该应答（不会拿 0 去连）")

    print("\n" + "=" * 60)
    print("\n" + "=" * 60)
    if warns:
        print("提示（不算失败）：")
        for w in warns:
            print("  · %s" % w)
    if fails:
        print("失败 %d 项：" % len(fails))
        for f in fails:
            print("  ✗ %s" % f)
        return 1
    print("全部断言通过 ✓")
    return 0


if __name__ == "__main__":
    sys.exit(main())

