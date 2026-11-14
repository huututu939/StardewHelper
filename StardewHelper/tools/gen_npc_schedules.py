#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""把《星露谷NPC行程表（全维度）》Markdown 转成结构化 JSON。

输入：一份中文整理稿（34 位 NPC、1883 条表格正文行）。
输出：app/src/main/assets/data/npcSchedules.json

用法：
    python tools/gen_npc_schedules.py [输入.md] [输出.json]

不带参数时使用默认输入路径（本机 Downloads）和默认输出路径（本仓库 assets/data）。
脚本会做一轮自检，任何一项不通过都会以非零退出码退出。

解析取舍（与需求说明一致/不一致的地方都记在这里）：
1. 引用（「同XXX」「与XXX一致」「季节+行程」）：
   - 出现在 `**标签**：同XXX`、`#### 标题：同XXX`、或独立成段的「同XXX。」处 → 按引用解析，
     把目标变体的 rows 复制过来，同时把原始引用文字保留在 note 里。
   - 括号里带具体时间轴（`7:10 AM ...`、`→ ...`）的是「内联摘要」→ 整段进 note，rows 留空。
   - `> ` 引用块一律只当说明文字（note），不参与引用解析（note 字段的定义就是这样写的）。
2. 目标定位：先「标签相同」，再「描述符被目标原文包含」；两条都落空即视为未解析。
   未解析的引用会保留 note、rows 留空，并打印清单（见 REF_HINTS 里的人工判断）。
3. `#### 特殊星期/特殊日期` 这类容器标题本身也生成一个变体（rows 为空，note 写明是容器），
   以满足「任何 #### 标题或 **标签** 都要有对应变体」。
4. 标签括号里的「常规 / 晴天 / 雨天 / 绿雨」是类型描述，整段留在 label 里；
   其余括号内容拆成 cond / dates / weekdays / year。
5. 引用变体的 rows 是目标行的副本，所以 JSON 里 rows 元素总数会大于 MD 的 1883 行；
   MD 源表行数（1883）单独统计并做等值断言。
"""
import hashlib
import json
import os
import re
import sys
from collections import Counter, OrderedDict

HERE = os.path.dirname(os.path.abspath(__file__))
DEFAULT_INPUT = (
    r"F:\Downloads\星露谷NPC行程表（全维度）.md（合并主文件·34位NPC·1883条）.md"
)
DEFAULT_OUTPUT = os.path.abspath(
    os.path.join(HERE, "..", "app", "src", "main", "assets", "data", "npcSchedules.json")
)

SOURCE_NAME = "星露谷物语 原版 NPC 行程表（全维度）·中文整理"
VERSION = "1.0"

EXPECTED_NPCS = [
    "亚历克斯", "艾利欧特", "哈维", "山姆", "塞巴斯蒂安", "谢恩",
    "阿比盖尔", "艾米丽", "海莉", "莉亚", "玛鲁", "潘妮",
    "卡洛琳", "克林特", "德米特里厄斯", "矮人", "艾芙琳", "乔治",
    "格斯", "贾斯", "乔迪", "肯特", "科罗布斯", "里奥",
    "刘易斯", "莱纳斯", "玛妮", "齐先生", "潘姆", "皮埃尔",
    "罗宾", "桑迪", "威利", "法师",
]
EXPECTED_ROWS = 1883

SEASON_MAP = {
    "春季": "spring", "夏季": "summer", "秋季": "fall", "冬季": "winter",
    "婚后": "married",
}
SEASON_WORD = {"春": "spring", "夏": "summer", "秋": "fall", "冬": "winter"}
SEASON_CN = {"spring": "春季", "summer": "夏季", "fall": "秋季", "winter": "冬季"}
WEEKDAY_CN = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5, "六": 6, "日": 7, "天": 7}
WEEKDAY_EN = {
    1: "monday", 2: "tuesday", 3: "wednesday", 4: "thursday",
    5: "friday", 6: "saturday", 7: "sunday",
}
NON_NUMERIC_TIMES = {"全天", "白天", "夜间"}

# 括号里属于「类型描述」而不是「条件」的词：整段保留在 label 里。
TYPE_WORDS = {"常规", "晴天", "雨天", "绿雨"}

# 常见标签 → 英文 id 片段（id 只允许小写字母、数字和点）。
LABEL_SLUGS = {
    "晴天": "clear", "雨天": "rain", "绿雨": "green",
    "常规": "regular", "特殊日": "special-day", "特殊日期": "special-date",
    "外出日": "outing", "说明": "notes",
    "特殊星期/特殊日期": "special-weekdays", "绿雨 / 特殊日": "green-special",
    "雨天方案 A": "rain-a", "雨天方案 B": "rain-b",
    "雨天 · 行程一": "rain-plan-1", "雨天 · 行程二": "rain-plan-2",
    "周一": "monday", "周二": "tuesday", "周三": "wednesday", "周四": "thursday",
    "周五": "friday", "周六": "saturday", "周日": "sunday",
    "周一至周五": "mon-fri", "周一与周三": "mon-wed", "周二与周四": "tue-thu",
    "周五与周日": "fri-sun", "周四、周五": "thu-fri", "周二、周五": "tue-fri",
    "周一、周二": "mon-tue", "周三、周五": "wed-fri", "周二、周五、周日": "tue-fri-sun",
    "周一 / 周日": "mon-sun", "周二 / 周四": "tue-thu",
    "周二 / 周三 / 周五": "tue-wed-fri", "周一 / 周五 / 周六": "mon-fri-sat",
    "周一/周四/周六": "mon-thu-sat", "周二/周三/周五": "tue-wed-fri",
    "周三/周五": "wed-fri", "周五/周六": "fri-sat", "周一/二/三/四": "mon-thu",
    "夏季周五": "friday",
    "沙漠节": "desert-festival", "沙漠节期间": "desert-festival",
    "夜市": "night-market",
    "社区中心未修复": "community-unfixed", "社区中心已修复": "community-fixed",
    "社区中心修复后": "community-fixed",
    "每月2号": "monthly-2", "每月23号": "monthly-23",
    "每月9号与23号的周二": "monthly-9-23-tue",
    "春16后": "spring-16-after", "春17后": "spring-17-after",
}
GROUP_SLUGS = {
    "好感不足6心（住在姜岛）": "group-low",
    "好感6心及以上（搬到山顶树屋）": "group-high",
}

# 无法按「标签相同 / 描述符被包含」自动定位的引用，附人工判断的目标。
REF_HINTS = {
    "同春季未修复雨天表":
        "春季「雨天」一节下的「社区中心未修复」子变体（Shane 春季 雨天 的子标签；"
        "该内容在 MD 里本身就是内联摘要，没有表格，所以即使定位成功也没有行）",
    "同春季已修复雨天表":
        "春季「雨天」一节下的「社区中心已修复」子变体（同上，MD 里是内联摘要，没有表格）",
    "同春季周三室内行程":
        "春季「周三」变体（Haley 春季 特殊星期/特殊日期 下的「周三（与海莉/亚历克斯好感均未达 6 心时）」）",
    "同夏季周二/周四诊所上班":
        "夏季「周二 / 周四」变体（Maru 夏季；该行在 MD 里本身是内联摘要，"
        "真正内容来自春季「周二 / 周四（诊所上班，当护士）」）",
}

DASHES = "–—―−"


# --------------------------------------------------------------------------
# 基础工具
# --------------------------------------------------------------------------
def sha8(text):
    return hashlib.sha1(text.encode("utf-8")).hexdigest()[:8]


def norm_text(text):
    """去掉括号内容与常见标点，用于标签/描述符的精确比对。"""
    s = re.sub(r"[（(][^（()）]*[)）]", "", text or "")
    s = re.sub(r"[\s、，,。．·/／:：;；'\"“”‘’]+", "", s)
    return s.lower()


def norm_flat(text):
    """只去掉括号符号本身（保留括号里的文字），用于「包含」式比对。"""
    s = re.sub(r"[（()）]", "", text or "")
    s = re.sub(r"[\s、，,。．·/／:：;；'\"“”‘’]+", "", s)
    return s.lower()


def strip_parens(text):
    return re.sub(r"[（(][^（()）]*[)）]", "", text or "").strip()


def split_chunks(text):
    """按 ，,；;、 以及破折号切块，同时保留分隔符。"""
    parts = re.split(r"([，,；;、]|——|—)", text or "")
    chunks, seps = [], []
    for i, piece in enumerate(parts):
        if i % 2 == 1:
            seps.append(piece)
        elif piece:
            chunks.append(piece)
            if len(seps) < len(chunks):
                seps.append("")
    while len(seps) < len(chunks):
        seps.append("")
    return chunks, seps[: len(chunks)]


# --------------------------------------------------------------------------
# 日期 / 星期解析
# --------------------------------------------------------------------------
def scan_dates(text, default_season=None):
    """从文本里抽出 (season, day, day2)。支持 春15 / 春 15 / 春 15-17 / 夏 3/24。"""
    out = []
    if not text:
        return out
    src = text.replace("–", "-").replace("—", "-").replace("−", "-")
    pattern = re.compile(r"([春夏秋冬])\s*(\d{1,2})(?:\s*[-\/]\s*(\d{1,2}))?")
    for m in pattern.finditer(src):
        out.append((SEASON_WORD[m.group(1)], int(m.group(2)),
                    int(m.group(3)) if m.group(3) else None))
    if default_season and not out:
        bare = re.compile(r"(?:^|[、，,\s])(\d{1,2})\s*(?:日)?(?:\s*[-\/]\s*(\d{1,2}))?\s*(?:日)?(?=$|[、，,\s])")
        for m in bare.finditer(src):
            out.append((default_season, int(m.group(1)),
                        int(m.group(2)) if m.group(2) else None))
    return out


def scan_weekday_mentions(text):
    """文本里**提到**的星期（不要求整段是星期表达式）。

    用于「条件里藏着星期」的写法：
      「雨天（且为周四）」   → 周四
      「晴天（即周一/周四/周日）」 → 一/四/日
    ⚠ **反向表述不作约束**：「周四、周五以外的工作日」说的是「除周四周五以外」，
      把它读成「只在周五」会把整张表判反 —— 所以见到「以外 / 非周」直接放弃。
    """
    s = text or ""
    if "以外" in s or "非周" in s:
        return []
    out = []
    for m in re.finditer(r"周([一二三四五六日])\s*至\s*周([一二三四五六日])", s):
        a, b = WEEKDAY_CN[m.group(1)], WEEKDAY_CN[m.group(2)]
        for wd in range(a, b + 1):
            if wd not in out:
                out.append(wd)
    rest = re.sub(r"周([一二三四五六日])\s*至\s*周([一二三四五六日])", "", s)
    for m in re.finditer(r"周([一二三四五六日])", rest):
        wd = WEEKDAY_CN[m.group(1)]
        if wd not in out:
            out.append(wd)
    return out


def parse_weekdays(text):
    """整段是否是纯星期表达式。是则返回 [1..7]，否则 None。"""
    s = (text or "").strip()
    if not s:
        return None
    s = s.replace("　", " ")
    m = re.fullmatch(r"周([一二三四五六日])\s*至\s*周([一二三四五六日])", s)
    if m:
        return list(range(WEEKDAY_CN[m.group(1)], WEEKDAY_CN[m.group(2)] + 1))
    tokens = [t for t in re.split(r"[、,，/\s]|与|和|及", s) if t]
    if not tokens:
        return None
    out = []
    carry = False
    for tok in tokens:
        tok = tok.strip()
        if not tok:
            continue
        m = re.fullmatch(r"周([一二三四五六日])", tok)
        if m:
            out.append(WEEKDAY_CN[m.group(1)])
            carry = True
            continue
        m = re.fullmatch(r"([一二三四五六日])", tok)
        if m and carry:
            out.append(WEEKDAY_CN[m.group(1)])
            continue
        return None
    return out or None


def parse_year(text):
    s = (text or "").strip()
    m = re.search(r"第\s*([0-9一二三四五六七八九十]+)\s*年", s)
    if not m:
        return 0
    raw = m.group(1)
    if raw.isdigit():
        return int(raw)
    cn = {"一": 1, "二": 2, "三": 3, "四": 4, "五": 5,
          "六": 6, "七": 7, "八": 8, "九": 9, "十": 10}
    if raw in cn:
        return cn[raw]
    return 0


# --------------------------------------------------------------------------
# 标签 → label / cond / dates / weekdays / year
# --------------------------------------------------------------------------
def parse_label(raw):
    """把原始标签拆成 (label, cond, dates, weekdays, year)。"""
    raw = (raw or "").strip()
    base = strip_parens(raw).strip()
    paren_groups = re.findall(r"[（(]([^（()）]*)[)）]", raw)

    dates = []
    weekdays = []
    year = 0
    cond_parts = []
    kept_parens = []

    # 1) 括号里的内容
    for content in paren_groups:
        chunks, seps = split_chunks(content)
        # ⚠ 「以外 / 非周」是**反向**表述（周四、周五以外的工作日），
        #   整段都不能当星期约束 —— 逐段判会漏：源稿把「周四、周五以外的」拆成
        #   「常规——周四」+「周五以外的工作日」两段，前一段不含「以外」却含「周四」。
        neg = ("以外" in content) or ("非周" in content)
        kinds = []
        for chunk in chunks:
            st = chunk.strip()
            if st in TYPE_WORDS:
                kinds.append("type")
                continue
            if parse_year(st):
                kinds.append("year")
                continue
            if parse_weekdays(st) is not None and not neg:
                kinds.append("weekday")
                continue
            if scan_dates(st):
                kinds.append("date")
                continue
            kinds.append("cond")
        if kinds and all(k == "type" for k in kinds):
            kept_parens.append(content)
            continue
        for i, (chunk, kind) in enumerate(zip(chunks, kinds)):
            st = chunk.strip()
            if kind == "year":
                year = year or parse_year(st)
            elif kind == "weekday":
                for wd in parse_weekdays(st) or []:
                    if wd not in weekdays:
                        weekdays.append(wd)
            elif kind == "date":
                for item in scan_dates(st):
                    if item not in dates:
                        dates.append(item)
            elif kind == "cond":
                cond_parts.append((seps[i - 1] if (i > 0 and cond_parts) else "", st))
                # 条件里可能藏着星期（「雨天（且为周四）」「晴天（即周一/周四/周日）」）——
                # 不读出来的话这些表就变成「天天成立」。
                if not neg:
                    for wd in scan_weekday_mentions(st):
                        if wd not in weekdays:
                            weekdays.append(wd)
    cond = "".join(sep + text for sep, text in cond_parts).strip()

    # 2) 主干里的日期 / 星期
    for item in scan_dates(base):
        if item not in dates:
            dates.append(item)
    for wd in parse_weekdays(base) or []:
        if wd not in weekdays:
            weekdays.append(wd)

    # 2.2) 「夏季周五」这种「季节前缀 + 星期」的写法。
    #      源稿在夏季段里写「夏季周五」，而 parse_weekdays 只认**纯**星期表达式
    #      ⇒ 不补这一条的话它没有星期锚点，夏天周五就不会出现在「今天适用」里。
    _sw = re.match(r"^(?:春季|夏季|秋季|冬季|春|夏|秋|冬)\s*(.+)$", base)
    if _sw:
        for wd in parse_weekdays(_sw.group(1)) or []:
            if wd not in weekdays:
                weekdays.append(wd)

    # 2.1) 「每月N号」= **每个季**的第 N 天（Stardew 里「月」就是季，一季 28 天）。
    #      源稿里这些条目只写「每月2号」「每月9号与23号」，没写季节 ⇒
    #      不处理的话它们**没有任何锚点**，会被「今天适用」判定当成「每天成立」——
    #      于是平时也显示体检日 / 跳绳日的行程，是错的。
    _mo = re.search(r"每月([^）)]*)", base)
    if _mo:
        for _d in re.findall(r"(\d+)\s*号", _mo.group(1)):
            for _se in ("spring", "summer", "fall", "winter"):
                _item = (_se, int(_d), None)
                if _item not in dates:
                    dates.append(_item)

    # 3) label：主干原文 + 保留下来的类型括号
    label = base
    if kept_parens:
        label = raw
    return label, cond, dates, weekdays, year


def kind_of(text):
    t = (text or "").strip()
    if t.startswith("晴天"):
        return "clear"
    if t.startswith("雨天"):
        return "rain"
    if t.startswith("绿雨"):
        return "green"
    if "特殊" in t:
        return "special"
    return "other"


# --------------------------------------------------------------------------
# 时间
# --------------------------------------------------------------------------
def time_to_minutes(text):
    m = re.fullmatch(r"(\d{1,2}):(\d{2})\s*(AM|PM)", (text or "").strip())
    if not m:
        return -1
    hour, minute = int(m.group(1)), int(m.group(2))
    ampm = m.group(3).upper()
    if ampm == "AM":
        hour = hour % 12
    else:
        hour = hour % 12 + 12
    return hour * 60 + minute


# --------------------------------------------------------------------------
# 引用（“同XXX”）
# --------------------------------------------------------------------------
TIME_AXIS_RE = re.compile(r"\d{1,2}\s*:\s*\d{2}\s*[AaPp][Mm]|→|~")
SEASON_PREFIX_RE = re.compile(r"^(春季|夏季|秋季|冬季)(.*)$", re.S)
SEASON_CHAR_RE = re.compile(r"^([春夏秋冬])\s*(\d|[、，,].*$|$)(.*)$", re.S)


def looks_like_inline_summary(text):
    """括号里带具体时间轴的是内联摘要，不是引用。"""
    return bool(TIME_AXIS_RE.search(text or ""))


def split_reference(text):
    """把引用文本拆成 (prefix, core)。不是引用时返回 None。

    支持三种写法：
      1. `同XXX表。` / `同XXX。`     —— 以「同」开头
      2. `与XXX一致。`              —— 以「与」开头且以「一致」结尾
      3. `夏季周三行程（…）。`       —— 季节 + 行程（省略「同」）
    """
    s = (text or "").strip()
    s = s.rstrip("。").strip()
    if not s or looks_like_inline_summary(s):
        return None
    if s.startswith("与") and s.endswith("一致"):
        return "", s[1:-2].strip()
    idx = s.find("同")
    if idx >= 0:
        return s[:idx].strip(), s[idx + 1:].strip()
    # 省略「同」的写法：季节 + 「行程」
    if re.match(r"^[春夏秋冬].*行程$", strip_parens(s)):
        return "", s
    return None


SEASON_CHAR_RE = re.compile(r"^([春夏秋冬])(?=\s*\d|\s*、|\s*$)(.*)$", re.S)


def reference_target_words(core):
    """从引用描述里取出季节与描述符。"""
    core = (core or "").strip()
    season = None
    rest = core
    m = SEASON_PREFIX_RE.match(core)
    if m:
        season = SEASON_MAP[m.group(1)]
        rest = m.group(2).strip()
    else:
        m = SEASON_CHAR_RE.match(core)
        if m:
            season = SEASON_WORD[m.group(1)]
            rest = m.group(2).strip()
    return season, rest


SEASON_CHAR_RE = re.compile(r"^([春夏秋冬])(?=\s*\d|\s*、|\s*$)(.*)$", re.S)


# --------------------------------------------------------------------------
# 解析 Markdown
# --------------------------------------------------------------------------
class Variant(object):
    def __init__(self, npc, season, group, kind, label, raw_label,
                 cond, dates, weekdays, year, src_line):
        self.npc = npc
        self.season = season
        self.group = group
        self.kind = kind
        self.label = label
        self.raw_label = raw_label
        self.cond = cond
        self.dates = dates
        self.weekdays = weekdays
        self.year = year
        self.rows = []
        self.note_lines = []
        self.ref_text = None
        self.src_line = src_line
        self.id = None
        self.is_container = False
        self.children = []
        self.resolved = None
        self.resolved_loose = False
        # v2.94.0：整季继承搬过来的变体（源稿写「与春季完全相同」）。
        #   自检要能把它们与「源稿直接写出来的」区分开。
        self.inherited = False

    @property
    def full_text(self):
        return self.raw_label


def parse_npc_heading(text):
    m = re.match(r"^##\s+(.+?)（(.+)）\s*$", text.strip())
    if not m:
        m2 = re.match(r"^##\s+(.+?)\s*\((.+)\)\s*$", text.strip())
        if not m2:
            return None
        m = m2
    en = m.group(1).strip()
    cn = m.group(2).strip()
    cn = cn.split(" / ")[0].strip()
    return en, cn


def parse_md(text):
    lines = text.split("\n")
    npcs = []
    npc = None
    season = None
    group = ""
    in_group = False
    cur = None          # 当前变体（#### / ##### / **标签**）
    cur_heading = None  # 当前 #### 级标题
    md_body_rows = 0
    stats = {"heading": 0, "tag": 0, "season_heading": 0}

    for idx, raw_line in enumerate(lines):
        line = raw_line.rstrip("\r")
        stripped = line.strip()

        if not stripped:
            continue
        if stripped.startswith("# "):
            # 一级标题（文件说明 / 分部说明）：整段忽略。
            npc = None
            cur = None
            cur_heading = None
            season = None
            group = ""
            continue
        if stripped.startswith("## "):
            head = parse_npc_heading(stripped)
            if head is None:
                continue
            en, cn = head
            npc = {
                "name": cn, "en": en, "note_lines": [], "variants": [],
            }
            npcs.append(npc)
            season = None
            group = ""
            in_group = False
            cur = None
            cur_heading = None
            continue
        if stripped.startswith("### "):
            text3 = stripped[4:].strip()
            cur = None
            cur_heading = None
            if text3 in SEASON_MAP:
                season = SEASON_MAP[text3]
                group = ""
                in_group = False
            elif re.match(r"^春季\s*/\s*夏季\s*/\s*秋季\s*/\s*冬季$", text3):
                season = "any"
                group = ""
                in_group = False
            else:
                # 条件分组（里奥）：下面的 #### 春季/夏季/... 决定季节
                group = text3
                season = None
                in_group = True
            continue
        if stripped.startswith("#### ") or stripped.startswith("##### "):
            level = 4 if stripped.startswith("#### ") else 5
            text4 = stripped[level + 1:].strip()
            cur = None
            if in_group and text4 in SEASON_MAP:
                # 里奥：分组下面的 #### 春季/夏季/秋季/冬季 只决定季节
                season = SEASON_MAP[text4]
                cur_heading = None
                stats["season_heading"] += 1
                continue
            label_raw, ref_text = split_head_ref(text4)
            label, cond, dates, weekdays, year = parse_label(label_raw)
            v = Variant(npc["en"], season, group, kind_of(label_raw), label,
                        label_raw, cond, dates, weekdays, year, idx + 1)
            v.ref_text = ref_text
            v.note_lines = []
            npc["variants"].append(v)
            stats["heading"] += 1
            cur = v
            cur_heading = v
            continue
        if stripped.startswith("**"):
            m = re.match(r"^\*\*(.+?)\*\*(.*)$", stripped)
            if not m:
                continue
            label_raw = m.group(1).strip()
            suffix = m.group(2).strip()
            ref_text = None
            if suffix.startswith("："):
                ref_text = suffix[1:].strip()
            label, cond, dates, weekdays, year = parse_label(label_raw)
            kind = kind_of(label_raw)
            if kind == "other" and cur_heading is not None:
                kind = cur_heading.kind
            v = Variant(npc["en"], season, group, kind, label, label_raw,
                        cond, dates, weekdays, year, idx + 1)
            v.ref_text = ref_text
            npc["variants"].append(v)
            stats["tag"] += 1
            if cur_heading is not None:
                cur_heading.children.append(v)
                cur_heading.is_container = True
            cur = v
            continue
        if stripped.startswith(">"):
            body = stripped[1:].strip()
            if cur is not None:
                cur.note_lines.append(body)
            elif npc is not None:
                npc["note_lines"].append(body)
                # 季节层的说明（`### 秋季` 之后、任何变体之前的引用块）：
                # 「与春季完全相同」这类**整季继承**的线索就藏在这里 ——
                # 不单独记下来，后面的 inherit_seasons 就没东西可依据。
                if season is not None:
                    npc.setdefault("season_notes", {}).setdefault(season, []).append(body)
            continue
        if stripped.startswith("|"):
            if cur is None:
                continue
            cells = [c.strip() for c in stripped[1:].strip().rstrip("|").split("|")]
            if len(cells) < 2:
                continue
            if cells[0] == "时间":
                continue
            if re.fullmatch(r"[-:\s]+", cells[0]):
                continue
            cur.rows.append({"t": cells[0], "a": cells[1]})
            md_body_rows += 1
            continue
        if re.fullmatch(r"[-*_]{3,}", stripped):
            continue
        # 普通段落：可能是独立成行的引用，也可能是补充说明
        if cur is not None:
            if (cur.ref_text is None and not cur.rows and not cur.note_lines
                    and split_reference(stripped) is not None):
                # 形如「同晴天常规。」的独立引用行
                cur.ref_text = stripped
            else:
                cur.note_lines.append(stripped)
        continue

    return npcs, md_body_rows, stats


def split_head_ref(text):
    """`晴天（常规）：同春季晴天常规表。` → ('晴天（常规）', '同春季晴天常规表。')"""
    if "：" in text:
        left, right = text.split("：", 1)
        return left.strip(), right.strip()
    return text.strip(), None


# --------------------------------------------------------------------------
# id 生成
# --------------------------------------------------------------------------
def slug_for_label(label):
    base = strip_parens(label).strip()
    if base in LABEL_SLUGS:
        return LABEL_SLUGS[base]
    wd = parse_weekdays(base)
    if wd:
        return "-".join(WEEKDAY_EN[w] for w in wd)
    dates = scan_dates(base)
    if dates:
        parts = []
        for season, d1, d2 in dates:
            tag = {"spring": "spring", "summer": "summer",
                   "fall": "fall", "winter": "winter"}[season]
            parts.append("%s-%d" % (tag, d1) if d2 is None
                         else "%s-%d-%d" % (tag, d1, d2))
        return "-".join(parts)
    return "v" + sha8(base)


def copy_variant(v, season):
    """复制一个变体到另一个季节（整季继承用）。"""
    cp = Variant(v.npc, season, v.group, v.kind, v.label, v.raw_label,
                 v.cond, list(v.dates), list(v.weekdays), v.year, v.src_line)
    cp.rows = [dict(r) for r in v.rows]
    cp.note_lines = list(v.note_lines)
    cp.inherited = True
    return cp


def inherit_seasons(npcs):
    """季节层的**整季继承**：源稿写「与春季完全相同」的季，把春季的基准表搬过来。

    ## 为什么需要
    源稿有 18 处季节层说明是「同某季」式整季继承（卡洛琳/克林特/艾芙琳/乔治/
    格斯/贾斯/乔迪/肯特 …）。不搬的话，那些季在 App 里**只剩体检日/夜市那几条**，
    其余日子判不出今天的表 —— 而源稿明明说了「与春季完全相同」。

    ## 搬哪些
    - **基准表**（晴天 / 雨天 / 按星期的特殊表）：整季都适用 ⇒ 搬；
    - **带日期的**（春 15 沙漠节、秋 25 体检）：日期绑在那一季 ⇒ **不搬**
      （体检日/夜市那些本来就在目标季里，是目标季自己的条目）；
    - **绿雨**：源稿常单独交代（「夏季绿雨（第一年）待在厨房中」）
      ⇒ **不搬**，搬了就是把春天的绿雨当成夏天的。

    ## 不覆盖
    目标季自己写了的同名条目优先（源稿说「常规、雨天及周二/周三/周五、周六行程同春季」
    时，意思正是「只有这几项跟春季一样」）。
    """
    copied = 0
    for npc in npcs:
        notes = npc.get("season_notes") or {}
        if not notes:
            continue
        for season, lines in notes.items():
            note = " ".join(lines)
            m = re.search(r"同([春夏冬])季", note)
            if not m:
                continue
            src_season = SEASON_WORD[m.group(1)]
            if src_season == season:
                continue
            have = set()
            for v in npc["variants"]:
                if v.season == season:
                    have.add((v.kind, v.label))
            for v in list(npc["variants"]):
                if v.season != src_season:
                    continue
                if v.kind not in ("clear", "rain", "special"):
                    continue
                if v.dates or v.is_container:
                    continue
                if not v.weekdays and v.kind == "special":
                    continue      # 无锚点的散文条目，不搬
                key = (v.kind, v.label)
                if key in have:
                    continue
                have.add(key)
                cp = copy_variant(v, season)
                cp.note_lines.append("（本季行程与%s相同）" % SEASON_CN.get(src_season, src_season))
                npc["variants"].append(cp)
                copied += 1
    return copied


def assign_ids(npcs):
    """生成全局唯一、只含小写字母/数字/点的变体 id。

    形如 alex.spring.clear / alex.spring.special.wednesday / leo.spring.special.group.low.tuesday
    """
    used = set()
    for npc in npcs:
        for v in npc["variants"]:
            parts = [re.sub(r"[^a-z0-9]+", "-", v.npc.lower()), v.season, v.kind]
            if v.group:
                g = GROUP_SLUGS.get(v.group)
                if not g:
                    g = "group-" + sha8(v.group)
                parts.append(g)
            slug = slug_for_label(v.label)
            if slug.startswith(v.kind + "."):
                slug = slug[len(v.kind) + 1:]
            if slug != v.kind:
                parts.append(slug)
            # 分隔符统一成点，保证 id 只含 [a-z0-9.]
            base_id = ".".join(parts).replace("-", ".")
            cand = base_id
            n = 2
            while cand in used:
                cand = "%s.%d" % (base_id, n)
                n += 1
            used.add(cand)
            v.id = cand


# --------------------------------------------------------------------------
# 引用解析
# --------------------------------------------------------------------------
def date_key(item):
    season, d1, d2 = item
    if d2 is None:
        return season, (d1,)
    return season, tuple(range(d1, d2 + 1))


def variant_date_keys(v):
    return {date_key(d) for d in v.dates}


def expanded_days(season, d1, d2):
    if d2 is None:
        return {d1}
    return set(range(d1, d2 + 1))


def cond_grams(text):
    s = re.sub(r"\s+", "", text or "")
    return {s[i:i + 2] for i in range(len(s) - 1)}


def label_keys(v):
    """用于比对的候选键。"""
    raw = v.raw_label or ""
    base = strip_parens(raw).strip()
    paren = "".join(re.findall(r"[（(]([^（()）]*)[)）]", raw))
    keys = {base, strip_parens(raw)}
    if paren:
        keys.add(base + paren)
        keys.add(base + re.sub(r"[，,；;、]", "", paren))
    return {norm_text(k) for k in keys if k}


def match_candidates(variants, desc, desc_norm, desc_weekdays, desc_dates,
                     ref_cond=""):
    """返回 (命中的变体列表, 是否宽松匹配)。"""
    if not variants:
        return [], False

    # 1) 标签精确匹配
    if desc_norm:
        exact = [v for v in variants if desc_norm in label_keys(v)]
        if len(exact) == 1:
            return exact, False
        if len(exact) > 1:
            return [max(exact, key=lambda x: cond_overlap(desc, x, ref_cond))], False

    # 2) 星期集合完全一致
    if desc_weekdays:
        same = [v for v in variants
                if v.weekdays and sorted(v.weekdays) == sorted(desc_weekdays)]
        if len(same) == 1:
            return same, False
        if len(same) > 1:
            return [max(same, key=lambda x: cond_overlap(desc, x, ref_cond))], False

    # 3) 日期集合完全一致
    if desc_dates:
        want = {date_key(d) for d in desc_dates}
        same = [v for v in variants if variant_date_keys(v) == want]
        if len(same) == 1:
            return same, False
        if len(same) > 1:
            return [max(same, key=lambda x: cond_overlap(desc, x, ref_cond))], False

    # 4) 逐个星期 / 日期匹配（多目标合并）
    multi = []
    if desc_weekdays:
        for wd in desc_weekdays:
            cand = [v for v in variants if wd in v.weekdays]
            if cand:
                multi.append(max(cand, key=lambda x: cond_overlap(desc, x, ref_cond)))
        if multi:
            return multi, False
    if desc_dates:
        for season, d1, d2 in desc_dates:
            days = expanded_days(season, d1, d2)
            cand = [v for v in variants
                    if any(date_key(d)[0] == season and
                           days.issubset(set(date_key(d)[1])) for d in v.dates)]
            if cand:
                multi.append(max(cand, key=lambda x: cond_overlap(desc, x, ref_cond)))
        if multi:
            return multi, False

    # 5) 描述符被目标原文包含（宽松）
    if desc_norm:
        flat = norm_flat(desc)
        inside = [v for v in variants if flat and flat in norm_flat(v.full_text)]
        if len(inside) == 1:
            return inside, False
        if len(inside) > 1:
            return [max(inside, key=lambda x: cond_overlap(desc, x, ref_cond))], False

    return [], False


def cond_overlap(desc, v, ref_cond=""):
    """引用方条件文本与候选条件文本的重合度，用于同标签多候选时的取舍。"""
    a = cond_grams(desc) | cond_grams(ref_cond)
    b = cond_grams(v.cond)
    if not a or not b:
        return 0
    return len(a & b)


def resolve_references(npcs, unresolved, loose_log):
    for npc in npcs:
        pool = npc["variants"]
        for v in npc["variants"]:
            if not v.ref_text:
                continue
            raw_ref = v.ref_text.strip().rstrip("。").strip()
            whole = v.ref_text.strip()
            if looks_like_inline_summary(whole):
                # 内联摘要：整段作为 note，不做引用解析
                v.note_lines.append(whole)
                v.ref_text = None
                continue
            v.note_lines.append(raw_ref)

            # 一条引用里可能用「；」并列多个目标
            parts = [p for p in re.split(r"[；;]", whole) if p.strip()]
            all_hits = []
            ok = True
            for part in parts:
                split = split_reference(part)
                if split is None:
                    ok = False
                    break
                prefix, core = split
                hits, loose, err = resolve_one(pool, v, core)
                if hits is None:
                    ok = False
                    ref_text = part.strip().rstrip("。").strip()
                    unresolved.append({
                        "id": v.id, "ref": ref_text,
                        "guess": REF_HINTS.get(ref_text, err or "无法定位目标变体"),
                    })
                    break
                all_hits.extend(hits)
                if loose:
                    loose_log.append({"id": v.id,
                                      "ref": part.strip().rstrip("。").strip(),
                                      "target": hits[0].id,
                                      "guess": "包含式宽松匹配"})
            if not ok:
                v.resolved = []
                continue
            v.resolved = all_hits


def resolve_one(pool, v, core):
    """解析单条引用描述。返回 (目标列表, 是否宽松, 失败说明)。"""
    season, rest = reference_target_words(core)
    paren_cond = ""
    pm = re.search(r"[（(]([^（()）]*)[)）]", rest)
    if pm:
        paren_cond = pm.group(1).strip()
        rest = strip_parens(rest)
    desc = rest.strip()
    for suffix in ("表", "行程"):
        if desc.endswith(suffix) and len(desc) > len(suffix):
            desc = desc[: -len(suffix)].strip()
    desc = desc.lstrip("的").strip()

    if season is None:
        season = v.season if "晴天常规" in desc else "spring"

    desc_weekdays = parse_weekdays(desc)
    desc_dates = scan_dates(desc, default_season=season if season in
                            ("spring", "summer", "fall", "winter") else None)
    desc_norm = norm_text(desc)

    cand = [x for x in pool if x.season == season]
    same_group = [x for x in cand if x.group == v.group]
    if same_group:
        cand = same_group
    cand = [x for x in cand if x is not v]

    if not desc:
        # 描述符为空：按引用方自身标签推导
        desc = strip_parens(v.raw_label).strip()
        desc_weekdays = parse_weekdays(desc)
        desc_dates = scan_dates(desc, default_season=season if season in
                                ("spring", "summer", "fall", "winter") else None)
        desc_norm = norm_text(desc)

    hits, loose = match_candidates(cand, desc, desc_norm,
                                   desc_weekdays, desc_dates, v.cond)
    if not hits:
        return None, False, "季节=%s 描述=%s（目标季节内找不到匹配变体）" % (
            season, desc or "(空)")
    if paren_cond:
        v.cond = (v.cond + "；" + paren_cond).strip("；") if v.cond else paren_cond
    return hits, loose, ""


# --------------------------------------------------------------------------
# 输出
# --------------------------------------------------------------------------
# 节日名：源稿里一个节日常被拆成好几条，只有第一条带日期。
#   「春16后沙漠节」/「沙漠节（作为摊主）」—— 标题不同名，但指的是同一个节日。
FESTIVAL_KEYS = ("沙漠节", "夜市", "展览会", "万灵节", "鱿鱼节", "复活节",
                 "花舞节", "鳟鱼大赛", "夏威夷宴会", "月光水母之舞", "冬日星盛宴")


def festival_of(label):
    """标签里含的节日名（用于同节日兄弟互认）；没有则返回 None。"""
    for f in FESTIVAL_KEYS:
        if f in (label or ""):
            return f
    return None


def build_output(npcs):
    out_variants = []
    for npc in npcs:
        # ── 同名兄弟的日期继承 ──
        # 源稿把一个节日拆成好几条：
        #   「沙漠节（春15-17，偶数年作为摊主）」/「沙漠节（奇数年作为游客）」
        #   「沙漠节（春15-17）」/「沙漠节（作为摊主）」
        # **只有第一条带日期**，后面的条目标题一样、日期却是空的。
        # 不继承的话，后面那些条目的「锚点」是空的 ⇒ 会被「今天适用」判定
        # 当成「每天成立」⇒ 平日常年显示沙漠节行程 —— 是错的。
        dated = {}
        fest_dated = {}
        dated_xs = {}
        fest_xs = {}
        for v in npc["variants"]:
            if v.dates and not v.is_container:
                dated.setdefault((v.season, v.kind, v.label), v.dates)
                dated_xs.setdefault((v.kind, v.label), v.dates)
                fk = festival_of(v.label)
                if fk:
                    fest_dated.setdefault((v.season, v.kind, fk), v.dates)
                    fest_xs.setdefault((v.kind, fk), v.dates)
        variants = []
        for v in npc["variants"]:
            rows = []
            if v.resolved:
                for target in v.resolved:
                    for r in target.rows:
                        rows.append({"t": r["t"], "m": time_to_minutes(r["t"]),
                                     "a": r["a"]})
            elif v.ref_text is None and v.rows:
                for r in v.rows:
                    rows.append({"t": r["t"], "m": time_to_minutes(r["t"]),
                                 "a": r["a"]})
            # 日期继承：同名兄弟 → 同节日兄弟（「春16后沙漠节」与「沙漠节（作为摊主）」
            #   指同一个节日，只是标题不同名）。
            eff = v.dates or dated.get((v.season, v.kind, v.label))
            if not eff:
                fk = festival_of(v.label)
                if fk:
                    eff = fest_dated.get((v.season, v.kind, fk))
            if not eff:
                # 跨季同源：源稿在夏季段里写「沙漠节：同春季。」—— 节日本身在春季，
                # 这条只是指路。日期从春季那条继承（判定时按日期所属季比对，
                # 所以它在自己的季里**不会**被误命中）。
                eff = dated_xs.get((v.kind, v.label))
                if not eff:
                    fk = festival_of(v.label)
                    if fk:
                        eff = fest_xs.get((v.kind, fk))
            if not eff and "替代" in (v.cond or "") and not v.weekdays:
                # 「社区中心修复后（替代冬季常规）」：它**替代整季的常规表** ⇒
                # 整季每天都成立。给成全季覆盖，判定才会选它而不是常规表；
                # 不给的话它没有锚点 ⇒ 永远选不中 ⇒ 冬季显示的是**没被替代**的旧表。
                # ⚠ 只在**没有星期约束**时才这么做：像「周三（…，替代室内行程）」
                #   这种带星期的，它的真约束是「每周三」，摊成整季就变成天天成立。
                eff = [(v.season, 1, 28)]
            dates = []
            for season, d1, d2 in (eff or []):
                item = {"season": season, "day": d1}
                if d2 is not None:
                    item["day2"] = d2
                dates.append(item)
            note = "\n".join(x for x in v.note_lines if x)
            if v.is_container and not rows:
                note = (note + "\n" if note else "") + \
                    "（容器标题：内容见下列子变体，共 %d 个）" % len(v.children)
            variants.append(OrderedDict([
                ("id", v.id),
                ("season", v.season),
                ("kind", v.kind),
                ("group", v.group),
                ("label", v.label),
                ("cond", v.cond),
                ("weekdays", v.weekdays),
                ("dates", dates),
                ("year", v.year),
                ("note", note),
                # v2.94.0：容器标题（`#### 特殊星期/特殊日期` 这类分组标题）**不是一张表**，
                #   只是下面子变体的分组名。显式打标，别让消费方靠 note 文本去嗅——
                #   它会带着 kind=special 且 weekdays 为空，被「今天适用」判定误命中，
                #   结果是给用户弹一张空表。
                ("container", bool(v.is_container)),
                ("rows", rows),
            ]))
        out_variants.append(OrderedDict([
            ("name", npc["name"]),
            ("en", npc["en"]),
            ("note", "\n".join(x for x in npc["note_lines"] if x)),
            ("variants", variants),
        ]))
    return OrderedDict([
        ("version", VERSION),
        ("source", SOURCE_NAME),
        ("npcs", out_variants),
    ])


# --------------------------------------------------------------------------
# 自检
# --------------------------------------------------------------------------
def self_check(data, md_text, md_body_rows, npcs, unresolved, loose_log, md_stats):
    problems = []
    names = [n["name"] for n in data["npcs"]]
    if len(names) != len(EXPECTED_NPCS):
        problems.append("NPC 数量 %d != %d" % (len(names), len(EXPECTED_NPCS)))
    missing = [n for n in EXPECTED_NPCS if n not in names]
    extra = [n for n in names if n not in EXPECTED_NPCS]
    if missing:
        problems.append("缺少 NPC：%s" % "、".join(missing))
    if extra:
        problems.append("多出 NPC：%s" % "、".join(extra))

    total_rows = 0
    kind_counts = Counter()
    ids = set()
    for npc in data["npcs"]:
        for v in npc["variants"]:
            if v["id"] in ids:
                problems.append("变体 id 重复：%s" % v["id"])
            ids.add(v["id"])
            kind_counts[v["kind"]] += 1
            total_rows += len(v["rows"])
            for r in v["rows"]:
                if r["a"].strip() not in md_text:
                    problems.append("行为文本未逐字出现在原文：%r" % r["a"])
                if not (re.fullmatch(r"\d{1,2}:\d{2}\s*(AM|PM)", r["t"]) or
                        r["t"] in NON_NUMERIC_TIMES):
                    problems.append("时间串非法：%r" % r["t"])
                if time_to_minutes(r["t"]) != r["m"]:
                    problems.append("分钟换算不一致：%r -> %r" % (r["t"], r["m"]))

    # 结构性核对：每个 #### / ##### 标题、每个 **标签** 都要有对应变体
    # ⚠ **整季继承搬过来的变体不算**（源稿那一季并没有逐条写出来，
    #   它写的是一句「与春季完全相同」）—— 所以要减去它们再比。
    inherited_count = sum(1 for npc in npcs for v in npc["variants"] if v.inherited)
    variant_count = sum(len(n["variants"]) for n in npcs) - inherited_count
    expect = md_stats["heading"] + md_stats["tag"]
    if variant_count != expect:
        problems.append("变体数 %d != #### 标题 %d + **标签** %d" %
                        (variant_count, md_stats["heading"], md_stats["tag"]))

    # 源表行数统计（同理：继承搬来的行是源表某些行的副本，不算新行）
    src_rows = 0
    for npc in npcs:
        for v in npc["variants"]:
            if not v.inherited:
                src_rows += len(v.rows)
    if src_rows != md_body_rows:
        problems.append("MD 表格正文行数 %d != 解析出的源行数 %d" %
                        (md_body_rows, src_rows))
    if md_body_rows != EXPECTED_ROWS:
        problems.append("MD 表格正文行数 %d != 期望 %d" % (md_body_rows, EXPECTED_ROWS))

    # 每条 MD 表格正文行都必须在输出里出现
    md_cells = []
    for raw_line in md_text.split("\n"):
        s = raw_line.strip()
        if not s.startswith("|"):
            continue
        cells = [c.strip() for c in s[1:].strip().rstrip("|").split("|")]
        if len(cells) < 2 or cells[0] == "时间":
            continue
        if re.fullmatch(r"[-:\s]+", cells[0]):
            continue
        md_cells.append((cells[0], cells[1]))
    out_pairs = set()
    for npc in data["npcs"]:
        for v in npc["variants"]:
            for r in v["rows"]:
                out_pairs.add((r["t"], r["a"]))
    for t, a in md_cells:
        if (t, a) not in out_pairs:
            problems.append("MD 表格行未出现在输出中：%s | %s" % (t, a[:24]))

    return problems, total_rows, src_rows, kind_counts


def main(argv):
    input_path = argv[1] if len(argv) > 1 else DEFAULT_INPUT
    output_path = argv[2] if len(argv) > 2 else DEFAULT_OUTPUT
    if not os.path.isfile(input_path):
        sys.stderr.write("找不到输入文件：%s\n" % input_path)
        return 2
    with open(input_path, "r", encoding="utf-8-sig") as fh:
        md_text = fh.read()

    npcs, md_body_rows, md_stats = parse_md(md_text)
    # 季节层「同某季」的整季继承要在 assign_ids **之前**做 ——
    # 搬过来的变体也得拿到 id。
    _inherited = inherit_seasons(npcs)
    assign_ids(npcs)

    unresolved = []
    loose_log = []
    resolve_references(npcs, unresolved, loose_log)

    data = build_output(npcs)
    problems, total_rows, src_rows, kind_counts = self_check(
        data, md_text, md_body_rows, npcs, unresolved, loose_log, md_stats)

    out_dir = os.path.dirname(output_path)
    if out_dir and not os.path.isdir(out_dir):
        os.makedirs(out_dir)
    with open(output_path, "w", encoding="utf-8", newline="\n") as fh:
        json.dump(data, fh, ensure_ascii=False, indent=2)
        fh.write("\n")

    variant_total = sum(len(n["variants"]) for n in data["npcs"])
    print("=" * 68)
    print("NPC 行程表转换完成")
    print("=" * 68)
    print("输入文件      : %s" % input_path)
    print("输出文件      : %s" % output_path)
    print("NPC 数        : %d" % len(data["npcs"]))
    print("变体总数      : %d" % variant_total)
    print("MD 表格正文行 : %d" % md_body_rows)
    print("源表行数(自检): %d" % src_rows)
    print("rows 总数     : %d（其中 %d 行是按引用规则从目标变体复制的）" %
          (total_rows, total_rows - src_rows))
    print("kind 计数     : %s" % ", ".join(
        "%s=%d" % (k, kind_counts.get(k, 0))
        for k in ("clear", "rain", "green", "special", "other")))
    print("结构核对      : #### 标题 %d + **标签** %d = %d 个变体（季节标题 %d 个不产生变体）"
          % (md_stats["heading"], md_stats["tag"],
             md_stats["heading"] + md_stats["tag"], md_stats["season_heading"]))
    empty = [(n["name"], v["id"], v["label"], v["note"])
             for n in data["npcs"] for v in n["variants"] if not v["rows"]]
    containers = [e for e in empty if e[3].startswith("（容器标题")]
    prose = [e for e in empty if not e[3].startswith("（容器标题")]
    print("空 rows 变体  : %d（容器标题 %d / 其它 %d）" %
          (len(empty), len(containers), len(prose)))
    print("-" * 68)
    if unresolved:
        print("未解析引用清单（%d 条）：" % len(unresolved))
        for item in unresolved:
            print("  - %s | %s | 判断目标：%s" %
                  (item["id"], item["ref"], item["guess"]))
    else:
        print("未解析引用清单：空")
    if loose_log:
        print("-" * 68)
        print("宽松匹配（目标标签被描述符包含）%d 条：" % len(loose_log))
        for item in loose_log:
            print("  - %s | %s -> %s" % (item["id"], item["ref"], item["target"]))
    print("-" * 68)
    if problems:
        print("自检未通过，共 %d 项：" % len(problems))
        for p in problems[:40]:
            print("  ! %s" % p)
        if len(problems) > 40:
            print("  ... 其余 %d 项省略" % (len(problems) - 40))
        return 1
    print("自检全部通过 ✅")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
