package com.stardew.assistant.ui.report

import android.content.Context
import com.stardew.assistant.data.FarmReport
import com.stardew.assistant.data.FarmStatKey
import com.stardew.assistant.data.ReportNames
import com.stardew.assistant.ui.share.ShareHelper
import java.util.Locale

/**
 * v2.74.0「农场年度报告」的**文案层**。
 *
 * ## 为什么单源
 * 长图上写的每一个称号、每一句点评、每一个数字，都只能有一处算。
 * 这里出的是「结构化事实」，渲染层（[ReportCanvas]）只负责把它画出来——
 * 画的时候不再做任何判断，避免「图上写 1,883、App 里写 1883」这类漂移。
 *
 * ## 称号怎么选
 * **手排优先级**，不是纯打分。原因：年度报告的头条要的是「好笑且贴切」，
 * 而这个判断来自写文案的人，不是数学。数据只负责判断「够不够格」：
 * 比如「勇闯矿洞的冒险家」要求杀怪 ≥ 1000 **或** 矿层 ≥ 100，
 * 达到了就上榜，没达到就换下一个。
 *
 * 命中的其余特征降级成**小标签**（最多 3 个），让一张图能同时说好几件事。
 *
 * ## 新档友好
 * 数据很少的新档不显示一堆「暂无」，而是给「农场新人」+「你的农场故事才刚刚开始」。
 */
data class ReportFacts(
    /** 大称号。 */
    val title: String,
    /** 称号下面的一句点评。 */
    val comment: String,
    /** 小标签（最多 3 个）。 */
    val tags: List<String>,
    /** 彩蛋句（没有 favoriteThing 时为 null，这一行整个不画）。 */
    val egg: String?,
    /** 概览大数字（4 个）。 */
    val headline: List<BigNumber>,
    /** 农场标签行（农场类型 / 婚姻 / 矿层）。 */
    val chips: List<String>,
    /** 数据网格分组。空组不显示。 */
    val groups: List<ReportGroup>,
    /**
     * F 方向「鹈鹕镇特刊」专用的事实。
     *
     * 报纸版式是**叙事**的：导语要引用具体数字、每日产量要拿天数去除。
     * 这些换算全在事实层做完，渲染层只负责画。
     */
    val paper: PaperFacts,
    /** 作息时间（画 24 格时钟条）。没有就 null（那一段不画）。 */
    val bedtime: Bedtime? = null,
)

/** 概览大数字。`icon` 是 assets/img/ui 下的图标名。 */
data class BigNumber(val label: String, val value: String, val icon: String)

/** 数据网格的一组。`icon` 是 assets/img/ui 下的图标名；`tint` 是配色键（渲染层映射成颜色）。 */
data class ReportGroup(val title: String, val icon: String, val tint: String, val rows: List<Pair<String, String>>)

/** 平均睡觉时间（画 24 格时钟条用）。 */
data class Bedtime(val hour: Int, val minute: Int, val text: String)

/** 一个称号候选。 */
private class Trait(val title: String, val weight: Int)

/**
 * 算出报告要显示的全部事实。
 *
 * @param ctx 需要读 assets 里的中英对照表，所以要 Context。
 */
fun reportFacts(ctx: Context, r: FarmReport): ReportFacts {
    val stat = { k: String -> r.stat(k) }

    // ── 称号：手排优先级 ──
    val traits = mutableListOf<Trait>()
    val bedtime = stat(FarmStatKey.AVERAGE_BEDTIME)
    val monsters = stat(FarmStatKey.MONSTERS_KILLED) ?: 0
    val deepest = stat(FarmStatKey.DEEPEST_MINE_LEVEL) ?: 0
    val money = r.totalMoneyEarned
    val fished = stat(FarmStatKey.TIMES_FISHED) ?: 0
    val sown = stat(FarmStatKey.SEEDS_SOWN) ?: 0
    val steps = stat(FarmStatKey.STEPS_TAKEN) ?: 0
    val trash = stat(FarmStatKey.TRASH_CANS_CHECKED) ?: 0
    val cooked = stat(FarmStatKey.ITEMS_COOKED) ?: 0
    val rocks = stat(FarmStatKey.ROCKS_CRUSHED) ?: 0
    val gifts = stat(FarmStatKey.GIFTS_GIVEN) ?: 0
    val foraged = stat(FarmStatKey.ITEMS_FORAGED) ?: 0
    val geodes = stat(FarmStatKey.GEODES_CRACKED) ?: 0
    val quests = stat(FarmStatKey.QUESTS_COMPLETED) ?: 0
    val notes = stat(FarmStatKey.NOTES_FOUND) ?: 0
    val walnuts = stat(FarmStatKey.GOLDEN_WALNUTS_FOUND) ?: 0
    val crafted = stat(FarmStatKey.ITEMS_CRAFTED) ?: 0
    val slimes = stat(FarmStatKey.SLIMES_KILLED) ?: 0
    val treeChopped = stat(FarmStatKey.TREES_CHOPPED) ?: 0
    val wildPlanted = stat(FarmStatKey.WILD_TREES_PLANTED) ?: 0
    val mineBottom = stat(FarmStatKey.TIMES_REACHED_MINE_BOTTOM) ?: 0
    val days = stat(FarmStatKey.DAYS_PLAYED) ?: 0

    // 数据太少 = 新档：先判这个，否则任何阈值都够不着，会掉进「毫无特色」
    val brandNew = days < 10 && sown == 0L && monsters == 0L && r.money == 0L && r.totalMoneyEarned == 0L

    if (brandNew) {
        traits += Trait("农场新人", 0)
    } else {
        if (bedtime != null && bedtime >= 2600) traits += Trait("凌晨战神", 1)
        if (monsters >= 1000 || deepest >= 100) traits += Trait("勇闯矿洞的冒险家", 2)
        if (money >= 1_000_000) traits += Trait("腰缠万贯", 3)
        if (fished >= 1000) traits += Trait("钓鱼佬", 4)
        if (sown >= 1000) traits += Trait("勤劳种田人", 5)
        if (steps >= 100_000) traits += Trait("暴走农民", 6)
        if (trash >= 50) traits += Trait("翻垃圾桶艺术家", 7)
        if (cooked >= 50) traits += Trait("田园大厨", 8)
        if (rocks >= 2000) traits += Trait("采矿狂人", 9)
        if (gifts >= 100) traits += Trait("人缘大师", 10)
        if (foraged >= 500) traits += Trait("采集达人", 11)
        if (geodes >= 100) traits += Trait("晶球收藏家", 12)
        if (quests >= 50) traits += Trait("任务达人", 13)
        if (crafted >= 500) traits += Trait("手工达人", 14)
        if (slimes >= 500) traits += Trait("史莱姆克星", 15)
        if (treeChopped >= 50 || wildPlanted >= 50) traits += Trait("植树造林", 16)
        if (walnuts >= 1) traits += Trait("金核桃猎人", 17)
        if (mineBottom >= 1) traits += Trait("矿洞常客", 18)
        // 睡觉时间兜底：总得有个跟作息有关的说法
        if (bedtime != null && bedtime > 0) {
            traits += Trait(
                when {
                    bedtime >= 2600 -> "凌晨战神"
                    bedtime >= 2400 -> "熬夜冠军"
                    else -> "养生农场主"
                },
                99,
            )
        }
    }

    val title = traits.minByOrNull { it.weight }?.title ?: "农场新人"
    val tags = traits.filter { it.title != title }
        .sortedBy { it.weight }
        .map { it.title }
        .distinct()
        .take(3)

    // ── 点评句 ──
    val comment = commentFor(title, r, stat)

    // ── 彩蛋 ──
    val egg = eggFor(ctx, r)

    // ── 概览大数字 ──
    // 每个数字配一个游戏图标：像素风的大数字 + 图标才有"游戏 UI"的味道，
    // 光秃秃一个数字像表格。
    val headline = buildList {
        add(BigNumber("游玩时长", r.playTimeText() ?: "—", "ach_home"))
        add(BigNumber("游戏内天数", if (days > 0) "$days 天" else "—", "ach_quest"))
        add(BigNumber("累计金币", if (r.totalMoneyEarned > 0) group(r.totalMoneyEarned) else "—", "ach_money"))
        add(BigNumber("当前金币", if (r.money > 0) group(r.money) else "—", "ach_collect"))
    }

    // ── 标签行 ──
    val chips = buildList {
        r.farmTypeName().takeIf { it.isNotBlank() }?.let { add(it) }
        add(spouseChip(ctx, r))
        if (deepest > 0) add("最深层 $deepest")
    }

    // ── 数据网格 ──
    val groups = buildList {
        add(
            ReportGroup("农业", "ach_crops", "crops", rows(
                row("播种", stat(FarmStatKey.SEEDS_SOWN)),
                row("作物出货", stat(FarmStatKey.CROPS_SHIPPED)),
                row("总出货", stat(FarmStatKey.ITEMS_SHIPPED)),
                row("锄地", stat(FarmStatKey.DIRT_HOED)),
                row("采集", stat(FarmStatKey.ITEMS_FORAGED)),
                row("合成", stat(FarmStatKey.ITEMS_CRAFTED)),
                row("做饭", stat(FarmStatKey.ITEMS_COOKED)),
                row("砍树", stat(FarmStatKey.TREES_CHOPPED)),
                row("种野树", stat(FarmStatKey.WILD_TREES_PLANTED)),
                row("砍树桩", stat(FarmStatKey.STUMPS_CHOPPED)),
                rowText("矿石", oreText(stat)),
            )),
        )
        add(
            ReportGroup("探险", "ach_combat", "combat", rows(
                row("杀怪", stat(FarmStatKey.MONSTERS_KILLED)),
                row("史莱姆", stat(FarmStatKey.SLIMES_KILLED)),
                row("最深层", stat(FarmStatKey.DEEPEST_MINE_LEVEL)),
                row("到矿底", stat(FarmStatKey.TIMES_REACHED_MINE_BOTTOM)),
                row("敲石", stat(FarmStatKey.ROCKS_CRUSHED)),
                row("砸晶球", stat(FarmStatKey.GEODES_CRACKED)),
                row("捡石头", stat(FarmStatKey.STONE_GATHERED)),
                row("挖古物点", stat(FarmStatKey.ARTIFACT_SPOTS_DUG)),
                row("挖到的钻石", stat(FarmStatKey.DIAMONDS_FOUND)),
                rowText("最爱杀", topMonstersText(ctx, r)),
            )),
        )
        add(
            ReportGroup("生活", "ach_home", "home", rows(
                row("游戏天数", stat(FarmStatKey.DAYS_PLAYED)),
                rowText("平均睡觉", r.bedtimeText()),
                row("步数", stat(FarmStatKey.STEPS_TAKEN)),
                row("钓鱼次数", stat(FarmStatKey.TIMES_FISHED)),
                row("钓到的鱼", stat(FarmStatKey.FISH_CAUGHT)),
                row("挖松露", stat(FarmStatKey.TRUFFLES_FOUND)),
                row("除草", stat(FarmStatKey.WEEDS_ELIMINATED)),
                row("晕倒", stat(FarmStatKey.TIMES_UNCONSCIOUS)),
                row("翻垃圾桶", stat(FarmStatKey.TRASH_CANS_CHECKED)),
                row("奖券", stat(FarmStatKey.TICKET_PRIZES_CLAIMED)),
            )),
        )
        add(
            ReportGroup("社交", "ach_social", "social", rows(
                row("送礼", stat(FarmStatKey.GIFTS_GIVEN)),
                row("完成任务", stat(FarmStatKey.QUESTS_COMPLETED)),
                row("秘密纸条", stat(FarmStatKey.NOTES_FOUND)),
                row("金核桃", stat(FarmStatKey.GOLDEN_WALNUTS_FOUND)),
                friendRow(ctx, r),
            )),
        )
    }.filter { it.rows.isNotEmpty() }

    return ReportFacts(
        title = title,
        comment = comment,
        tags = tags,
        egg = egg,
        headline = headline,
        chips = chips,
        groups = groups,
        // 睡觉时间画成 24 格时钟条：像素风里这比一串数字有戏得多
        bedtime = r.bedtimeParts()?.let { (h, m) -> Bedtime(h, m, r.bedtimeText() ?: "") },
        paper = paperFacts(ctx, r, stat, title),
    )
}

/**
 * 一行数据。**缺字段或值为 0 就是空**（不写 0，也不写「暂无」占位），
 * 由 [rows] 统一把空行剔掉。
 */
private fun row(label: String, v: Long?): Pair<String, String?> =
    label to (if (v == null || v <= 0L) null else group(v))

/** 一行文字数据（值已经是成品文本）。 */
private fun rowText(label: String, text: String?): Pair<String, String?> = label to text

/** 剔除空行。 */
private fun rows(vararg items: Pair<String, String?>?): List<Pair<String, String>> =
    items.mapNotNull { pair ->
        if (pair == null) return@mapNotNull null
        val (k, v) = pair
        if (v.isNullOrBlank()) null else k to v
    }

/** 矿石四项合成一行（「铜 1424 · 铁 1109…」）。 */
private fun oreText(stat: (String) -> Long?): String? {
    val parts = listOf(
        "铜" to stat(FarmStatKey.COPPER_FOUND),
        "铁" to stat(FarmStatKey.IRON_FOUND),
        "金" to stat(FarmStatKey.GOLD_FOUND),
        "铱" to stat(FarmStatKey.IRIDIUM_FOUND),
    ).mapNotNull { (n, v) -> if (v != null && v > 0) "$n ${group(v)}" else null }
    if (parts.isEmpty()) return null
    return parts.joinToString(" · ")
}

/** 「杀得最多的 3 种怪」。查不到中文名就显示英文原名（不编）。 */
private fun topMonstersText(ctx: Context, r: FarmReport): String? {
    if (r.topMonsters.isEmpty()) return null
    return r.topMonsters.joinToString(" · ") {
        "${ReportNames.monster(ctx, it.name)} ${group(it.count.toLong())}"
    }
}

/** 「最好的伙伴」那一行。没有好友数据时返回 null（这一行整个不显示）。 */
private fun friendRow(ctx: Context, r: FarmReport): Pair<String, String?>? {
    val best = r.bestFriends.firstOrNull() ?: return null
    val names = r.bestFriends.joinToString("、") { ReportNames.npc(ctx, it.name) }
    val extra = if (r.friendCount > r.bestFriends.size) "（认识 ${r.friendCount} 位）" else ""
    return "最好的伙伴" to "$names$extra"
}

/** 配偶标签（未婚就是「单身」）。 */
private fun spouseChip(ctx: Context, r: FarmReport): String {
    val s = r.spouse.trim()
    return if (s.isEmpty()) "单身" else "已婚 · ${ReportNames.npc(ctx, s)}"
}

/** 千分位。 */
internal fun group(n: Long): String = String.format(Locale.CHINA, "%,d", n)

/**
 * 点评句。
 *
 * 原则：**每句都带一个具体数字**，不要空洞的形容词。
 */
private fun commentFor(title: String, r: FarmReport, stat: (String) -> Long?): String {
    val hours = r.millisecondsPlayed / 3600000
    val days = stat(FarmStatKey.DAYS_PLAYED) ?: 0
    val monsters = stat(FarmStatKey.MONSTERS_KILLED) ?: 0
    val steps = stat(FarmStatKey.STEPS_TAKEN) ?: 0
    val sown = stat(FarmStatKey.SEEDS_SOWN) ?: 0
    val money = r.totalMoneyEarned
    val deepest = stat(FarmStatKey.DEEPEST_MINE_LEVEL) ?: 0
    val bedtime = r.bedtimeText()

    return when (title) {
        "农场新人" -> "你的农场故事才刚刚开始——先种下第一颗种子吧。"
        "凌晨战神" -> "平均 $bedtime 才睡，这不是农场，这是修仙。"
        "熬夜冠军" -> "平均 $bedtime 熄灯，村庄的狗都比你精神。"
        "养生农场主" -> "平均 $bedtime 上床，早睡的人运气不会太差。"
        "勇闯矿洞的冒险家" -> "杀过 $monsters 只怪，矿井第 $deepest 层以下你说了算。"
        "腰缠万贯" -> "累计赚了 ${group(money)} 金币，钱包已经装不下了。"
        "钓鱼佬" -> "钓了 ${stat(FarmStatKey.TIMES_FISHED) ?: 0} 次鱼，钓竿比锄头用得还勤。"
        "勤劳种田人" -> "播下 $sown 颗种子，你和这片地都清楚彼此的辛苦。"
        "暴走农民" -> "一年走 $steps 步，这农场比你家到镇上的路还大。"
        "翻垃圾桶艺术家" -> "翻了 ${stat(FarmStatKey.TRASH_CANS_CHECKED) ?: 0} 次垃圾桶，拾荒的尽头是收藏家。"
        "田园大厨" -> "在灶台前站了 ${stat(FarmStatKey.ITEMS_COOKED) ?: 0} 顿，锅铲比锄头更忙。"
        "采矿狂人" -> "敲碎 ${stat(FarmStatKey.ROCKS_CRUSHED) ?: 0} 块石头，镐子已经磨短了。"
        "人缘大师" -> "送出了 ${stat(FarmStatKey.GIFTS_GIVEN) ?: 0} 份礼物，全村都记得你。"
        "采集达人" -> "在林子里采了 ${stat(FarmStatKey.ITEMS_FORAGED) ?: 0} 次，春天的脚步都是跟着你走的。"
        "晶球收藏家" -> "砸开 ${stat(FarmStatKey.GEODES_CRACKED) ?: 0} 个晶球，只为那一声脆响。"
        "任务达人" -> "完成 ${stat(FarmStatKey.QUESTS_COMPLETED) ?: 0} 个任务，村民的委托簿都写满了你的名字。"
        "手工达人" -> "亲手做了 ${stat(FarmStatKey.ITEMS_CRAFTED) ?: 0} 件东西，工坊的灯没熄过。"
        "史莱姆克星" -> "史莱姆见了你绕路——你杀了 ${stat(FarmStatKey.SLIMES_KILLED) ?: 0} 只。"
        "植树造林" -> "砍树 ${stat(FarmStatKey.TREES_CHOPPED) ?: 0} 棵、种树 ${stat(FarmStatKey.WILD_TREES_PLANTED) ?: 0} 棵，森林在你手里轮回。"
        "金核桃猎人" -> "捡到 ${stat(FarmStatKey.GOLDEN_WALNUTS_FOUND) ?: 0} 颗金核桃，够换一把高级工具了。"
        "矿洞常客" -> "到底 ${stat(FarmStatKey.TIMES_REACHED_MINE_BOTTOM) ?: 0} 次，矿洞底部的门都认识你了。"
        else -> {
            if (days > 0 && hours > 0) "在 Pelican Town 之外的农场里，你已经待了 $days 天、$hours 小时。"
            else "故事刚刚开始，先认识一下这片土地吧。"
        }
    }
}

/**
 * 彩蛋句。
 *
 * 「最喜欢的东西」是**玩家自己输入的自由文本**（不是游戏枚举），所以：
 * - 有内容就原样显示玩家写的那句（配一句调侃）；
 * - 查不到中文名也照显示，**不编**；
 * - 唯一有官方彩蛋的是 `ConcernedApe`（吃星之花时猿猴会说谢谢）。
 */
private fun eggFor(ctx: Context, r: FarmReport): String? {
    val raw = r.favoriteThing.trim()
    if (raw.isEmpty()) return null
    val cn = ReportNames.favorite(ctx, raw)
    val shown = cn ?: raw
    return if (cn != null) {
        "你说最喜欢的是「$shown」——猿猴听到了会回你一句谢谢。"
    } else {
        "你说最喜欢的是「$shown」，这个秘密只有你知道。"
    }
}

// ── F 方向「鹈鹕镇特刊」的事实 ───────────────────────────────────────

/**
 * 报纸版式要用的全部事实。
 *
 * 「这行字有多长」在报纸里是**版式问题**：一行放不下就得换版式，
 * 所以事实层直接把「这是不是长值」标出来（[PaperNumber.big]），
 * 渲染层照着排就行，不用自己猜。
 */
data class PaperFacts(
    /** 报头期号行：「第 3 年 · 夏 6 日 · 农场主特刊」。 */
    val issueLine: String,
    /** 眉题：「Amy 的农场 · 河流农场」。 */
    val kicker: String,
    /** 头条大字：农场名。 */
    val headline: String,
    /** 副题：称号。 */
    val dek: String,
    /** 导语。 */
    val lede: String,
    /** 「本期数字」栏。 */
    val numbers: List<PaperNumber>,
    /** 「本报评的每日产量」栏。 */
    val perDay: List<PerDayItem>,
    /** 编者按那句话（根据每日产量现算的吐槽；没有可算的就空，那一段不画）。 */
    val editorNote: String,
    /** 作息那句：「平均 23:50 上床。」 */
    val clockNote: String,
    /** 「手气最重的怪」：怪名 → 数量。 */
    val monsters: List<Pair<String, String>>,
    /** 「关系最铁的人」：「罗宾、莱纳斯、艾米丽」。 */
    val friendsLine: String,
    /** 「共认识 35 位村民」。 */
    val friendNote: String,
    /** 彩蛋（没有就 null，那一段不画）。 */
    val egg: String?,
    /** 底栏左：水印。 */
    val footLeft: String,
    /** 底栏右：人名 · 日期。 */
    val footRight: String,
)

/** 「本期数字」的一行。[big] = 长值，字号更大。 */
data class PaperNumber(val key: String, val value: String, val big: Boolean = false)

/** 「每日产量」的一行。[per] 是每日原始数值，渲染层按最大值归一化画柱状条。 */
data class PerDayItem(val label: String, val value: String, val per: Double)

/**
 * 算报纸版式要用的全部事实。
 *
 * ## 为什么缺数据要改写整句话，而不是留个洞
 * 导语是「在 258 个游戏日里种下 2,540 颗种子、钓起 2,160 条鱼、打倒 3,437 只怪」。
 * 存档里没有钓鱼记录时，写成「钓起 0 条鱼」是**错的**（那是没钓过，不是钓了 0 条）。
 * 所以缺哪项就从句子里**整项去掉**，句式跟着变。
 *
 * ## 「每日产量」是每天的量，不是总量
 * 战斗 13.3 只怪/天 = 杀怪总数 ÷ 游戏天数。倍数关系由这里算，
 * 渲染层只拿现成的字符串和比例画条。
 */
private fun paperFacts(
    ctx: Context,
    r: FarmReport,
    stat: (String) -> Long?,
    title: String,
): PaperFacts {
    val days = stat(FarmStatKey.DAYS_PLAYED) ?: 0L
    val farmer = r.farmerName.ifBlank { "这位农场主" }

    // ── 导语 ──
    val sown = stat(FarmStatKey.SEEDS_SOWN) ?: 0L
    // ⚠ 用 TIMES_FISHED（钓鱼**次数**）而不是 FISH_CAUGHT（钓到的**条数**）：
    //   「钓了 N 次鱼」说的是次数；用错了预览稿与 App 就会对不上
    //   （同一份存档两个数：2160 次 / 1773 条）。
    val fished = stat(FarmStatKey.TIMES_FISHED) ?: 0L
    val monsters = stat(FarmStatKey.MONSTERS_KILLED) ?: 0L
    val deepest = stat(FarmStatKey.DEEPEST_MINE_LEVEL) ?: 0L
    val acts = buildList {
        if (sown > 0) add("种下 ${group(sown)} 颗种子")
        if (fished > 0) add("钓了 ${group(fished)} 次鱼")
        if (monsters > 0) add("打倒 ${group(monsters)} 只怪")
    }
    val lede = buildString {
        append(farmer).append(" 本报获悉：这位农场主")
        if (days > 0 && acts.isNotEmpty()) append("在 ${group(days)} 个游戏日里")
        if (acts.isNotEmpty()) append(acts.joinToString("、"))
        if (deepest > 0) append("，最高下到矿井第 $deepest 层")
        append("。本刊特派员表示，")
        append(
            if (acts.isEmpty() && days <= 0) "鹈鹕镇刚来了位新邻居，本刊将持续跟踪报道。"
            else "鹈鹕镇终于有了一个像样的对手。"
        )
    }

    // ── 本期数字（缺值 / 为 0 的整行不出现） ──
    val numbers = buildList {
        r.playTimeText()?.let { add(PaperNumber("游玩时长", it, big = true)) }
        if (days > 0) add(PaperNumber("游戏内天数", "${group(days)} 天"))
        if (r.totalMoneyEarned > 0) add(PaperNumber("累计金币", group(r.totalMoneyEarned)))
        if (r.money > 0) add(PaperNumber("当前金币", group(r.money)))
        r.bedtimeText()?.let { add(PaperNumber("平均上床", it)) }
    }

    // ── 每日产量 = 总数 ÷ 游戏天数 ──
    val perDay = if (days <= 0) {
        emptyList()
    } else {
        listOf(
            Triple("战斗", stat(FarmStatKey.MONSTERS_KILLED), "只怪/天"),
            Triple("耕种", stat(FarmStatKey.SEEDS_SOWN), "颗种/天"),
            Triple("钓鱼", stat(FarmStatKey.TIMES_FISHED), "次/天"),
            Triple("采集", stat(FarmStatKey.ITEMS_FORAGED), "次/天"),
            Triple("社交", stat(FarmStatKey.GIFTS_GIVEN), "份礼/天"),
        ).mapNotNull { (label, v, unit) ->
            if (v == null || v <= 0L) {
                null
            } else {
                val per = v.toDouble() / days.toDouble()
                PerDayItem(label, String.format(Locale.CHINA, "%.1f %s", per, unit), per)
            }
        }
    }

    // ── 手气最重的怪 / 关系最铁的人 ──
    val monsterRows = r.topMonsters.take(3).map {
        ReportNames.monster(ctx, it.name) to group(it.count.toLong())
    }
    val friendsLine = r.bestFriends.take(3).joinToString("、") { ReportNames.npc(ctx, it.name) }
    val friendNote = if (r.friendCount > 0) "共认识 ${r.friendCount} 位村民" else ""

    // ── 编者按：用每日产量现算一句吐槽 ──
    // 预览稿里写死的是「一天下来：打 13 只怪、种 10 颗菜、钓 8 次鱼」，
    // 这里按真实数据现算（战斗 / 耕种 / 钓鱼三项取整），换档也对得上。
    val editorNote = if (perDay.isEmpty()) {
        ""
    } else {
        val wording = listOf(
            "战斗" to "打 %d 只怪",
            "耕种" to "种 %d 颗菜",
            "钓鱼" to "钓 %d 次鱼",
        )
        val parts = wording.mapNotNull { (label, fmt) ->
            val item = perDay.firstOrNull { it.label == label } ?: return@mapNotNull null
            val n = Math.round(item.per).toInt()
            if (n <= 0) null else String.format(Locale.CHINA, fmt, n)
        }
        if (parts.isEmpty()) "" else "一天下来：" + parts.joinToString("、")
    }

    return PaperFacts(
        issueLine = listOfNotNull(
            r.dateText().takeIf { it.isNotBlank() },
            "农场主特刊",
        ).joinToString(" · "),
        kicker = listOfNotNull(
            "$farmer 的农场",
            r.farmTypeName().takeIf { it.isNotBlank() },
        ).joinToString(" · "),
        headline = r.farmName.ifBlank { "我的农场" },
        dek = title,
        lede = lede,
        numbers = numbers,
        perDay = perDay,
        editorNote = editorNote,
        clockNote = r.bedtimeText()?.let { "平均 $it 上床。" } ?: "",
        monsters = monsterRows,
        friendsLine = friendsLine,
        friendNote = friendNote,
        egg = eggFor(ctx, r),
        footLeft = ShareHelper.watermark(),
        footRight = listOfNotNull(
            r.farmerName.takeIf { it.isNotBlank() },
            r.dateText().takeIf { it.isNotBlank() },
        ).joinToString(" · "),
    )
}
