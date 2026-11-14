# -*- coding: utf-8 -*-
"""c211 · 鱼塘扩容门「按材料反查哪些鱼需要它」结构化索引生成器（纯数据层，本轮不改 UI）

输入：app/src/main/assets/data/fishPonds.json 的「扩容任务」字段（c210 已补全 30 行）
输出：app/src/main/assets/data/pondGates.json
      {
        "gates":      [ {fish, population, materials[], counts{}, raw, bucket} ... ],  # 逐门结构化
        "byMaterial": { 材料名: [ {fish, population, count} ... ] },         # 反查索引
        "unparsed":   [ {fish, raw, reason} ... ],                          # 解析失败/说明行（不静默丢弃）
        "noTask":     [ {fish, note} ... ]                                 # 原文中「扩容任务」为空的行
      }

──────────── 句式模板（人工读完 fishPonds.json 全部 30 行原文后归纳，共 3 种）────────────
T1 普通行·多选门：「N 条：材料A×c 或 材料B×c 或 …」
     「或」＝任选其一。例：4 条：椰子×3 或 仙人掌果子×3
T2 普通行·单选门：「N 条：材料A×c」
     例：2 条：钻石×1
T3 概括行·类别桶门：「鱼A、鱼B（BucketId）：N 条：材料A×c 或 …」，同桶的后续门用「；」续写
     例：沙鱼、蝎鲤鱼（DesertFish）：4 条：椰子×3 或 仙人掌果子×3；6 条：黄金遗物×1
     —— 桶前缀里列出的鱼名＝该桶覆盖的鱼，桶门对这批鱼同等适用（原文即如此写，非推断）。

剥除项：门文本尾部可能带括注，如「（沙漠鱼共用桶）」「（海洋蟹笼鱼共用桶）」
     → 解析材料时剥除；raw 字段保留原文（括注也留着），保证可追溯。

数字/符号：条数与数量均为阿拉伯数字（防御性同时支持中文数字 一/两/三…十）；
     乘号为 ×（U+00D7；防御性兼容 U+FF58 / x / X / *）。

raw 字段口径：
     普通行 → 该门自己的原文片段（如 "4 条：椰子×3 或 仙人掌果子×3"）；
     类别桶 → 该桶整组的原文片段（如 "沙鱼、蝎鲤鱼（DesertFish）：4 条：…；6 条：黄金遗物×1"），
              以便反查时能看出「这条门来自哪个桶、桶覆盖哪些鱼」。

数据来源纪律：索引内容 100% 取自 fishPonds.json 原文，不补充任何鱼塘知识。
去重纪律：同一 fish+条数门槛+材料组合在原文里可能出现两次（鱼塘行本身 + 概括行的桶展开，
     如沙鱼/蝎鲤鱼的 DesertFish 门），gates 与 byMaterial 只保留首次出现，
     去重条数与去重明细一律打印出来（不静默丢弃）。
"""

import io
import json
import os
import random
import re
import sys

sys.stdout.reconfigure(encoding="utf-8")

ROOT = r"F:\Desktop\星露谷\StardewHelper"
SRC = os.path.join(ROOT, "app", "src", "main", "assets", "data", "fishPonds.json")
DST = os.path.join(ROOT, "app", "src", "main", "assets", "data", "pondGates.json")

# ── 正则 ──────────────────────────────────────────────────────────────────────
# 门：人口门槛 + 冒号 + 材料列表
POP_RE = re.compile(r"^\s*(\d+|[一二两三四五六七八九十]+)\s*条\s*[：:]\s*(.*)$")
# 单个材料：名称 + 乘号 + 数量
MAT_RE = re.compile(r"^\s*(?P<name>.+?)\s*[×xX＊*]\s*(?P<cnt>\d+|[一二两三四五六七八九十]+)\s*$")
# 尾部括注（要剥掉）
ANNO_RE = re.compile(r"[（(][^（()）]*[）)]")

CN_DIGIT = {
    "零": 0, "一": 1, "二": 2, "两": 2, "三": 3, "四": 4,
    "五": 5, "六": 6, "七": 7, "八": 8, "九": 9,
}


def cn_to_int(tok):
    """数字串 → int（阿拉伯数字优先；中文数字为防御性兼容）"""
    tok = str(tok).strip()
    if tok.isdigit():
        return int(tok)
    if tok in CN_DIGIT:
        return CN_DIGIT[tok]
    m = re.fullmatch(r"十([一二两三四五六七八九])?", tok)
    if m:
        return 10 + (CN_DIGIT.get(m.group(1), 0) if m.group(1) else 0)
    m = re.fullmatch(r"([一二两三四五六七八九])十", tok)
    if m:
        return CN_DIGIT[m.group(1)] * 10
    return None


def split_fish_list(head):
    """桶标题里的鱼名列表：「沙鱼、蝎鲤鱼」→ ["沙鱼", "蝎鲤鱼"]"""
    return [x.strip() for x in re.split(r"[、,，/]", head) if x.strip()]


def parse_gate_body(body):
    """解析单个门的材料部分（'A×c 或 B×c'）→ [(材料名, 数量), ...]；失败返回 None"""
    clean = ANNO_RE.sub("", body)          # 剥掉（沙漠鱼共用桶）这类括注
    parts = [p.strip() for p in clean.split("或") if p.strip()]
    out = []
    for p in parts:
        m = MAT_RE.match(p)
        if not m:
            return None
        name = m.group("name").strip()
        cnt = cn_to_int(m.group("cnt"))
        if not name or cnt is None:
            return None
        out.append((name, cnt))
    return out or None


def split_groups(text):
    """把一行「扩容任务」切成「组」。返回 [(bucket, bucket_fishes, [段, ...])]。

    桶组：某段里「（」出现在第一个「：」之前 → 该段是桶标题，桶组延续到下一个桶标题之前。
    普通行：每个门自成一段，桶头为空。
    """
    segments = [s.strip() for s in text.split("；") if s.strip()]
    groups = []
    for seg in segments:
        colon = seg.find("：")
        paren = seg.find("（")
        is_bucket_head = colon != -1 and paren != -1 and paren < colon
        if is_bucket_head:
            head = seg[:paren].strip()
            bucket = seg[paren + 1:seg.find("）", paren)].strip()
            groups.append((bucket, split_fish_list(head), [seg]))
        elif groups and groups[-1][0]:
            groups[-1][2].append(seg)      # 延续当前桶组
        else:
            groups.append(("", [], [seg]))
    return groups


def parse_row(fish, text):
    """解析一行「扩容任务」→ (gates, problems)

    gates: [{fish, population, materials, counts, raw, bucket, _src}]
    problems: [(reason, raw_text)]
    """
    gates, problems = [], []
    text = (text or "").strip()
    if not text:
        return gates, problems

    for bucket, bucket_fishes, segs in split_groups(text):
        group_raw = "；".join(segs)         # 桶组：整组原文
        for si, seg in enumerate(segs):
            body_src = seg
            if bucket and si == 0:
                # 桶标题段：剥掉「鱼A、鱼B（BucketId）：」前缀
                colon = seg.find("：")
                body_src = seg[colon + 1:].strip()
            elif bucket:
                body_src = seg

            m = POP_RE.match(body_src)
            if not m:
                problems.append(("不是门文本（说明行）", seg))
                continue
            pop = cn_to_int(m.group(1))
            if pop is None:
                problems.append(("条数无法解析", seg))
                continue
            items = parse_gate_body(m.group(2))
            if not items:
                problems.append(("材料列表无法解析", seg))
                continue

            raw = group_raw if bucket else seg
            targets = bucket_fishes if bucket else [fish]
            for f in targets:
                gates.append({
                    "fish": f,
                    "population": pop,
                    "materials": [n for n, _ in items],
                    "counts": dict(items),
                    "raw": raw,
                    "bucket": bucket,
                    "_src": body_src,
                })
    return gates, problems


def main():
    rows = json.load(io.open(SRC, encoding="utf-8"))
    rows = rows if isinstance(rows, list) else rows.get("ponds", [])

    all_gates = []
    unparsed = []
    no_task = []
    row_stats = []

    for r in rows:
        fish = r.get("鱼", "")
        text = (r.get("扩容任务") or "").strip()
        if not text:
            no_task.append({"fish": fish, "note": (r.get("备注") or "").strip()})
            row_stats.append((fish, 0, "noTask"))
            continue
        gates, problems = parse_row(fish, text)
        for reason, seg in problems:
            unparsed.append({"fish": fish, "raw": text, "reason": reason + "：" + seg})
        if not gates:
            if not problems:
                unparsed.append({"fish": fish, "raw": text, "reason": "未识别到任何扩容门"})
            row_stats.append((fish, 0, "unparsed"))
            continue
        all_gates.extend(gates)
        row_stats.append((fish, len(gates), "ok"))

    # ── 追溯性校验：每个门的原始片段必须逐字出现在 fishPonds.json 原文里 ──
    raw_pool = [(r.get("鱼", ""), (r.get("扩容任务") or "").strip()) for r in rows]
    bad = [g["raw"] for g in all_gates if not any(g["_src"] in t for _, t in raw_pool)]
    if bad:
        raise SystemExit("追溯性校验失败（片段不在原文中）：%r" % bad[:5])

    # ── 去重（同鱼+同条数门槛+同材料组合只留首次出现）────────────────────────
    seen = {}
    gates = []
    dropped = []
    for g in all_gates:
        key = (g["fish"], g["population"], tuple(g["materials"]), tuple(sorted(g["counts"].items())))
        if key in seen:
            dropped.append((g["fish"], g["population"], g["materials"], g["raw"], seen[key]["raw"]))
            continue
        seen[key] = g
        gates.append(g)

    # ── 反查索引 byMaterial ──────────────────────────────────────────────────
    by_material = {}
    for g in gates:
        for name, cnt in g["counts"].items():
            by_material.setdefault(name, []).append({
                "fish": g["fish"],
                "population": g["population"],
                "count": cnt,
            })

    # ── 落盘（utf-8 / 无 BOM / CRLF / indent=1，与项目其它 json 一致）─────────
    out = {
        "gates": [
            {
                "fish": g["fish"],
                "population": g["population"],
                "materials": g["materials"],
                "counts": g["counts"],
                "raw": g["raw"],
                "bucket": g["bucket"],
            }
            for g in gates
        ],
        "byMaterial": {k: by_material[k] for k in sorted(by_material)},
        "unparsed": unparsed,
        "noTask": no_task,
    }
    with io.open(DST, "w", encoding="utf-8", newline="\r\n") as f:
        json.dump(out, f, ensure_ascii=False, indent=1)

    # ── 验收报告 ────────────────────────────────────────────────────────────
    total_rows = len(rows)
    ok_rows = sum(1 for _, _, s in row_stats if s == "ok")
    unparsed_rows = sum(1 for _, _, s in row_stats if s == "unparsed")
    notask_rows = sum(1 for _, _, s in row_stats if s == "noTask")

    print("=" * 72)
    print("c211 鱼塘扩容门反查索引 · 生成报告")
    print("=" * 72)
    print("源文件            : %s" % SRC)
    print("目标文件          : %s" % DST)
    print("总行数            : %d" % total_rows)
    print("解析成功行数      : %d / %d" % (ok_rows, total_rows))
    print("  ├ 有门可解析    : %d 行" % ok_rows)
    print("  ├ 说明行/失败   : %d 行" % unparsed_rows)
    print("  └ 无扩容任务    : %d 行（扩容任务为空）" % notask_rows)
    print("门条目 gates      : %d 条（原始 %d 条，去重 %d 条）"
          % (len(gates), len(all_gates), len(dropped)))
    print("byMaterial 材料数 : %d 种" % len(by_material))
    print("byMaterial 关联数 : %d 条" % sum(len(v) for v in by_material.values()))
    print()
    print("── unparsed 清单 ──")
    if not unparsed:
        print("  （空）")
    for u in unparsed:
        print("  * %s → %s" % (u["fish"], u["reason"]))
        print("    原文：%s" % u["raw"])
    print()
    print("── noTask 清单（扩容任务为空）──")
    for n in no_task:
        print("  * %s  备注：%s" % (n["fish"], n["note"] or "（无）"))
    print()
    print("── 去重明细（%d 条，保留首次出现）──" % len(dropped))
    for fish, pop, mats, raw, kept in dropped:
        print("  * %s / %d 条 / %s" % (fish, pop, "、".join(mats)))
        print("    丢弃：%s" % raw)
        print("    保留：%s" % kept)
    print()
    print("── 逐行解析明细 ──")
    for fish, n, st in row_stats:
        print("  %-14s %-9s 门数=%d" % (fish, st, n))
    print()
    print("── 类别桶展开（bucket 非空）──")
    for g in gates:
        if g["bucket"]:
            print("  %-16s %d 条  鱼=%-5s  raw=%s" % (g["bucket"], g["population"], g["fish"], g["raw"]))
    print()
    print("── 随机抽 5 条核对（byMaterial 条目 → 原文片段）──")
    random.seed(20261104)
    flat = [(mat, it) for mat in sorted(by_material) for it in by_material[mat]]
    for mat, it in random.sample(flat, 5):
        hit = None
        for g in gates:
            if g["fish"] == it["fish"] and g["population"] == it["population"] \
                    and mat in g["materials"] and g["counts"].get(mat) == it["count"]:
                hit = g
                break
        print("  材料=%s | 鱼=%s | 条数门槛=%d | 数量=%d" % (mat, it["fish"], it["population"], it["count"]))
        print("    原文片段：%s" % (hit["raw"] if hit else "!! 未找到对应门 !!"))
    print()
    print("── byMaterial 覆盖材料（按关联鱼数排序）──")
    for mat, lst in sorted(by_material.items(), key=lambda kv: (-len(kv[1]), kv[0])):
        fishes = sorted(set(it["fish"] for it in lst))
        print("  %-16s %2d 条关联  鱼: %s" % (mat, len(lst), "、".join(fishes)))
    print()
    size = os.path.getsize(DST)
    print("pondGates.json 体积: %d 字节 (%.2f KB)" % (size, size / 1024.0))
    print("写入完成。")


if __name__ == "__main__":
    main()
