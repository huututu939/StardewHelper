package com.stardew.assistant.data

import android.content.Context
import org.json.JSONObject

/**
 * v2.94.0：原版 NPC 行程表（群友反馈「想要 NPC 的行程表」）。
 *
 * ## 数据从哪来
 *
 * `assets/data/npcSchedules.json`，由 `tools/gen_npc_schedules.py` 从
 * 一份中文整理稿（34 位原版 NPC、1883 条时段）转换而来。**不含任何 Mod 村民**。
 *
 * ## 为什么要单独一个对象，不进 [GameData]
 *
 * [GameData] 已经是 1400 多行的巨型单例，里面每张表都是「一行一条」的扁平表
 * （作物 / 鱼 / 礼物 / 配方）。行程表**不是那种形状**：它是「NPC × 季 × 天气 × 星期」
 * 的多层变体，还要带「同春季周六表」这类**交叉引用**。塞进 GameData 会把
 * 「扁平表」的假设带坏——所以另开一个自洽的对象，数据与判定都只在这里。
 *
 * ## 判定口径只有这一处
 *
 * 「今天谁在哪」是**推算**出来的（游戏存档里不存 NPC 的日程），
 * 推算规则写在行程表整理稿里：**特殊日期 > 绿雨 > 雨天 > 特殊星期 > 常规**。
 * 这条规则只在 [applicable] 一处实现，页面全部调它——
 * 多个页面各判一次就是 v2.27「判定逻辑散落多处必然漂移」的同型复发。
 *
 * ⚠ 推算结果**以游戏内为准**：条件分支（好感门槛、事件是否看过、
 *   巴士是否修好、社区中心是否修复）App 读不到，命中不了就明说命中不了。
 */
object NpcSchedules {

    /** 全部 NPC（顺序 = 整理稿里的顺序）。空 = 数据没加载成功。 */
    @Volatile
    var npcs: List<NpcSchedule> = emptyList()
        private set

    /** 加载失败原因（页面据此显示「数据加载失败」而不是空白页）。 */
    @Volatile
    var loadError: String? = null
        private set

    private val byName: Map<String, NpcSchedule> by lazy {
        npcs.associateBy { it.name }
    }

    /** 按中文名取；找不到返回 null。 */
    fun byName(name: String): NpcSchedule? = byName[name]

    /** 全部时段行总数（页脚显示用，也给 verify 核对）。 */
    val rowCount: Int get() = npcs.sumOf { it.variants.sumOf { v -> v.rows.size } }

    /**
     * 从 assets 读行程表。
     *
     * 独立 try：这份表坏掉只让行程页显示「数据加载失败」，不影响别的页面
     * （与 [GameData.load] 里「每份资产各自 runCatching」的同一套纪律）。
     */
    fun load(ctx: Context) {
        val text = runCatching {
            ctx.assets.open("data/npcSchedules.json").bufferedReader().use { it.readText() }
        }
        if (text.isFailure) {
            loadError = "读取 npcSchedules.json 失败"
            return
        }
        runCatching {
            npcs = parseSchedules(text.getOrThrow())
            loadError = null
        }.onFailure {
            loadError = "解析 npcSchedules.json 失败：${it.message ?: it::class.java.simpleName}"
        }
    }
}

/**
 * 从 JSON 文本解析行程表。
 *
 * ## 为什么要单独暴露一个纯函数
 *
 * Android 侧的入口是 [NpcSchedules.load]（要 Context），但**判定逻辑**（今天适用哪张表、
 * 跨午夜怎么算、搜行程）才是最容易出错的地方，而它们必须能用**真数据**验。
 * 所以解析拆成这个不依赖 Android 的纯函数 ⇒ JVM 单测直接拿 assets 里那份
 * JSON 跑同一份 Kotlin 代码（`org.json` 在测试源集里有真实现，见 build.gradle 的
 * `testImplementation(libs.orgjson)`）。
 */
fun parseSchedules(text: String): List<NpcSchedule> {
    val root = JSONObject(text)
    val arr = root.getJSONArray("npcs")
    return (0 until arr.length()).map { parseNpc(arr.getJSONObject(it)) }
}

/* ────────────────────────── 数据模型 ────────────────────────── */

/** 一个时段：「8:00 AM」+「离开自己的房间…」 */
data class ScheduleRow(val t: String, val a: String)

/**
 * 一个日期区间（春 15-17 这种）。
 *
 * [day2] >= [day]；单日就是 day == day2。
 */
data class ScheduleDate(val season: String, val day: Int, val day2: Int)

/**
 * 一个行程变体 = 某季 + 某天气/某星期下的**整张时间线**。
 *
 * @param season spring/summer/fall/winter/married/any
 * @param kind clear/rain/green/special/other
 * @param group 条件分组（如雷欧的「好感不足6心」）；空串 = 无分组
 * @param weekdays 1=周一…7=周日；空 = 不限
 * @param dates 具体日期（特殊日期）
 * @param container 这是不是**分组标题**（如「特殊星期/特殊日期」）——
 *   它只是下面子变体的分组名，**本身不是一张表**。
 */
data class ScheduleVariant(
    val id: String,
    val season: String,
    val kind: String,
    val group: String,
    val label: String,
    val cond: String,
    val weekdays: List<Int>,
    val dates: List<ScheduleDate>,
    val year: Int,
    val note: String,
    val rows: List<ScheduleRow>,
    val container: Boolean = false,
)

/** 一位 NPC 的全部行程 */
data class NpcSchedule(
    val name: String,
    val en: String,
    val note: String,
    val variants: List<ScheduleVariant>,
)

/* ────────────────────────── 口径常量 ────────────────────────── */

object ScheduleSeasons {
    const val SPRING = "spring"
    const val SUMMER = "summer"
    const val FALL = "fall"
    const val WINTER = "winter"
    const val MARRIED = "married"
    const val ANY = "any"

    /** 英文 id → 季下标（0..3）；非四季返回 -1。 */
    fun idxOf(id: String): Int = when (id) {
        SPRING -> 0
        SUMMER -> 1
        FALL -> 2
        WINTER -> 3
        else -> -1
    }

    /** 季下标 → 英文 id。 */
    fun idOf(idx: Int): String = when (idx) {
        0 -> SPRING
        1 -> SUMMER
        2 -> FALL
        3 -> WINTER
        else -> ANY
    }
}

object ScheduleKind {
    const val CLEAR = "clear"
    const val RAIN = "rain"
    const val GREEN = "green"
    const val SPECIAL = "special"
    const val OTHER = "other"
}

object ScheduleWeather {
    const val CLEAR = "clear"
    const val RAIN = "rain"
    const val GREEN = "green"

    /** 界面标签（顺序即界面顺序） */
    val LABELS = listOf("晴" to CLEAR, "雨" to RAIN, "绿雨" to GREEN)
}

/** 星期名（1=周一）。与今日页 `(day-1)%7` 同一口径：游戏第 1 天是周一。 */
val WEEKDAY_NAMES = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/* ────────────────────────── 解析 ────────────────────────── */

private fun parseNpc(o: JSONObject): NpcSchedule {
    val va = o.optJSONArray("variants")
    val variants = if (va == null) emptyList() else (0 until va.length()).map { i ->
        val v = va.getJSONObject(i)
        val rows = v.optJSONArray("rows")
        val dates = v.optJSONArray("dates")
        val weekdays = v.optJSONArray("weekdays")
        ScheduleVariant(
            id = v.optString("id"),
            season = v.optString("season").ifBlank { ScheduleSeasons.ANY },
            kind = v.optString("kind").ifBlank { ScheduleKind.OTHER },
            group = v.optString("group"),
            label = v.optString("label"),
            cond = v.optString("cond"),
            weekdays = if (weekdays == null) emptyList() else
                (0 until weekdays.length()).map { weekdays.getInt(it) },
            dates = if (dates == null) emptyList() else
                (0 until dates.length()).map { j ->
                    val d = dates.getJSONObject(j)
                    val d1 = d.optInt("day", 1)
                    ScheduleDate(
                        season = d.optString("season"),
                        day = d1,
                        day2 = d.optInt("day2", d1).coerceAtLeast(d1),
                    )
                },
            year = v.optInt("year", 0),
            note = v.optString("note"),
            container = v.optBoolean("container", false),
            rows = if (rows == null) emptyList() else
                (0 until rows.length()).map { k ->
                    val r = rows.getJSONObject(k)
                    ScheduleRow(t = r.optString("t"), a = r.optString("a"))
                },
        )
    }
    return NpcSchedule(
        name = o.optString("name"),
        en = o.optString("en"),
        note = o.optString("note"),
        variants = variants,
    )
}

/* ────────────────────────── 时间 ────────────────────────── */

private val TIME_RE = Regex("""^(\d{1,2}):(\d{2})\s*(AM|PM)$""", RegexOption.IGNORE_CASE)

/**
 * 显示时间串 → 当天 0 点起的分钟数；不是具体时刻（`全天`/`白天`/`夜间`）返回 -1。
 *
 * 12 小时制换算：`12:00 AM` → 0（午夜）、`12:50 AM` → 50、`12:00 PM` → 720（正午）。
 *
 * ⚠ 行程表整理稿的口径：**跨午夜的时间是「当日深夜延续」**——
 *   沙漠节那种 `12:00 AM 离开摊位` 指的是这一天深夜（= 次日 0 点），
 *   不是「这一天开头」。所以它排在当天时间轴的**末尾**，由 [rowAt] 处理。
 */
fun scheduleMinute(t: String): Int {
    val m = TIME_RE.matchEntire(t.trim()) ?: return -1
    val h = m.groupValues[1].toInt()
    val mi = m.groupValues[2].toInt()
    val pm = m.groupValues[3].equals("PM", ignoreCase = true)
    val h24 = when {
        !pm && h == 12 -> 0
        pm -> if (h == 12) 12 else h + 12
        else -> h
    }
    return h24 * 60 + mi
}

/** 显示时间串 → 24 小时制 `HH:MM`（用于「此刻在哪」的时段标题）。非具体时刻返回原串。 */
fun scheduleClock(t: String): String {
    val m = scheduleMinute(t)
    if (m < 0) return t
    return "%02d:%02d".format(m / 60, m % 60)
}

/* ────────────────────────── 上下文与判定 ────────────────────────── */

/**
 * 「今天」的上下文。
 *
 * @param seasonIdx 0..3（春/夏/秋/冬）
 * @param day 1..28
 * @param weather clear/rain/green
 * @param married 是否婚后（婚后另有一套行程）
 */
data class ScheduleContext(
    val year: Int = 1,
    val seasonIdx: Int = 0,
    val day: Int = 1,
    val weather: String = ScheduleWeather.CLEAR,
    val married: Boolean = false,
) {
    /** 1=周一…7=周日。与今日页 `(day-1)%7` 同一口径。 */
    val weekday: Int get() = ((day - 1) % 7) + 1

    val seasonId: String get() = ScheduleSeasons.idOf(seasonIdx)
}

/** 某变体在某年是否适用（`year == 0` = 不限年份；绿雨「第 1 年」用 year=1）。 */
private fun ScheduleVariant.yearOk(ctx: ScheduleContext): Boolean = year == 0 || year == ctx.year

/**
 * 奇偶年条件（源稿里「沙漠节·偶**数**年作为摊主」/「奇**数**年作为游客」这一对）。
 *
 * ⚠ 这是**唯一**能被 App 确定的分支条件 —— 因为「第几年」存档里就有。
 *   其余条件（好感门槛 / 事件是否看过 / 巴士是否修好）读不到，一律不猜。
 *
 * ⚠⚠ 匹配的是「偶数 / 奇数」**四个字**，不是「偶年 / 奇年」——
 *   源稿写的是「偶**数**年」（偶、数、年三个字），按「偶年」去 `contains`
 *   **永远匹配不上**，于是奇偶分支静默失效、两条都显示。
 *   这个坑是 JVM 验收测试抓出来的（静态断言查的是「代码里有没有这两个字串」，
 *   而那两个字串确实在代码里 —— 断言照样绿）。
 */
private fun ScheduleVariant.parityOk(year: Int): Boolean {
    val even = cond.contains("偶数") || cond.contains("偶年")
    val odd = cond.contains("奇数") || cond.contains("奇年")
    if (even && year % 2 != 0) return false   // 偶数年分支 ⇒ 奇数年不适用
    if (odd && year % 2 == 0) return false    // 奇数年分支 ⇒ 偶数年不适用
    return true
}

/** 某变体在某星期是否适用（weekdays 为空 = 不限）。 */
private fun ScheduleVariant.weekdayOk(weekday: Int): Boolean = weekdays.isEmpty() || weekday in weekdays

/**
 * 「今天适用哪张表」的判定结果。
 *
 * @param primary 今天实际生效的变体（可能有多个：同一日期有多套分支，如沙漠节奇/偶年）
 * @param fallbackNote 口径提示（如「常规表仅周一/周四/周日适用」）
 */
data class SchedulePick(
    val primary: List<ScheduleVariant>,
    val fallbackNote: String = "",
)

/**
 * 该 NPC 在 [ctx] 下「今天适用哪张表」。
 *
 * ## 优先级（整理稿原文的规则，只此一处）
 *
 * 特殊日期 > 绿雨 > 雨天 > 特殊星期 > 常规
 *
 * ## 为什么特殊星期排在雨天后面
 *
 * 这是**整理稿写明的口径**，照抄不自行加料：雨天/绿雨是「整天换一套表」，
 * 而「周三/周五」是同一套表里的分支。照抄也让 UI 能把规则原样告诉用户，
 * 而不是让用户猜 App 凭什么这么判。
 *
 * ## 条件分支命中不了就明说
 *
 * 「条件：玩家与海莉或亚历克斯均未达 6 心」这类门槛 App 读不到，
 * 所以**不因为门槛而排除**某张表——否则会把「其实可能适用」的表藏起来。
 * 门槛文字原样显示在表头，让用户自己判断。
 */
fun NpcSchedule.applicable(ctx: ScheduleContext): SchedulePick {
    val season = ctx.seasonId
    // ⚠ 容器标题（`#### 特殊星期/特殊日期`）**必须先剔掉**：
    //   它 kind=special 且 weekdays 为空 → 会被第 ④ 步当成「不限星期的特殊表」命中，
    //   结果是给用户弹一张**空表**（容器本身没有时段）。
    val pool = variants.filter {
        !it.container && (it.season == season || it.season == ScheduleSeasons.ANY)
    }

    if (pool.isEmpty()) return SchedulePick(emptyList())

    // ① 特殊日期（含「春 15-17」这类区间）
    //    ⚠ 同时看星期：有的表**既**有日期**又**有星期（海莉夏季「周三（…，替代室内行程）」
    //    这类被摊成了整季日期），只按日期命中就会变成「天天成立」。
    val byDate = pool.filter { v ->
        v.dates.any { d ->
            ScheduleSeasons.idxOf(d.season) == ctx.seasonIdx &&
                ctx.day >= d.day && ctx.day <= d.day2
        } && v.weekdayOk(ctx.weekday) && v.yearOk(ctx) && v.parityOk(ctx.year)
    }
    if (byDate.isNotEmpty()) return SchedulePick(byDate.preferRows())

    // ② 绿雨。⚠ **必须看当前天气**：绿雨表只在绿雨那天生效，
    //    不看天气就会在晴天也弹出「绿雨」那张表 —— 那是把「他今天去哪」答错。
    //    也要看星期：源稿里有「雨天（且为周四）」这种带星期限定的雨天表。
    if (ctx.weather == ScheduleWeather.GREEN) {
        val green = pool.filter {
            it.kind == ScheduleKind.GREEN && it.yearOk(ctx) && it.weekdayOk(ctx.weekday)
        }
        if (green.isNotEmpty()) return SchedulePick(green.preferRows())
    }

    // ③ 雨天（同上：**只在雨天生效**，且看星期）
    if (ctx.weather == ScheduleWeather.RAIN) {
        val rain = pool.filter { it.kind == ScheduleKind.RAIN && it.weekdayOk(ctx.weekday) }
        if (rain.isNotEmpty()) return SchedulePick(rain.preferRows())
    }

    // ④ 特殊星期。
    //    ⚠ 两道闸：
    //    ① dates 必须为空 —— 带日期的分支只在 ① 里生效（那才是它唯一的成立条件）；
    //    ② **weekdays 必须非空** —— 源稿里有「沙漠节（作为摊主）」这类
    //       **既没有日期也没有星期**的条目：它是同一条目的补充说明（同一个节日的
    //       另一种身份）。不给它锚点，它就会「每天成立」⇒ 平日常年显示沙漠节行程。
    //       （能定位到同节日/同名的兄弟时，转换器已把日期继承过来了。）
    val special = pool.filter {
        it.kind == ScheduleKind.SPECIAL &&
            it.dates.isEmpty() &&
            it.weekdays.isNotEmpty() &&
            it.weekdayOk(ctx.weekday) &&
            it.parityOk(ctx.year)
    }
    if (special.isNotEmpty()) return SchedulePick(special.preferRows())

    // ⑤ 常规
    val clearAll = pool.filter { it.kind == ScheduleKind.CLEAR && it.dates.isEmpty() }
    val clear = clearAll.filter { it.weekdayOk(ctx.weekday) }
    if (clear.isNotEmpty()) return SchedulePick(clear.preferRows())
    if (clearAll.isNotEmpty()) {
        // 常规表限定了星期而今天不在范围内：仍给出这张表，但**明说**限定在星期几。
        // 直接不显示 = 用户以为「今天他没安排」；给出并标注 = 用户知道要挑日子。
        val days = clearAll.flatMap { it.weekdays }.distinct().sorted()
        val note = if (days.isEmpty()) "" else
            "常规表仅${days.joinToString("、") { WEEKDAY_NAMES[it - 1] }}适用"
        return SchedulePick(clearAll.preferRows(), note)
    }

    // ⑥ 兜底：任何**无锚点**的有内容的表（数据缺口时不至于整页空白）。
    //    ⚠ 不能兜底「带日期 / 带星期」的表 —— 那张表只在那几天成立，
    //    拿它当兜底等于**天天显示体检日 / 夜市行程**。
    val any = pool.filter {
        it.rows.isNotEmpty() && it.dates.isEmpty() && it.weekdays.isEmpty()
    }
    return SchedulePick(any, if (any.isEmpty()) "这一季没有行程数据" else "")
}

/**
 * 同一层里**有表的就用有表的**。
 *
 * 有些分支在整理稿里是「内联摘要」（括号里直接写了时间轴，没有表），
 * 比如玛露夏季「周二/周四（诊所上班）」。它是有内容的（note 里是摘要），
 * 但同一层里若还有别的**带表**分支（雨天方案 A / B），就优先给带表的——
 * 用户要看的是逐条时段，不是摘要。
 */
private fun List<ScheduleVariant>.preferRows(): List<ScheduleVariant> {
    val withRows = filter { it.rows.isNotEmpty() }
    return if (withRows.isNotEmpty()) withRows else this
}

/** 婚后变体（可结婚对象的「婚后」一节）。容器标题不算。 */
fun NpcSchedule.marriedVariants(): List<ScheduleVariant> =
    variants.filter { it.season == ScheduleSeasons.MARRIED && !it.container }

/**
 * 某一季**可浏览**的变体：去掉容器标题。
 *
 * 容器（`#### 特殊星期/特殊日期`）只是分组名，选它没有表可看，
 * 放进选择器里就是一颗点开是空的按钮。
 */
fun NpcSchedule.browseVariants(season: String): List<ScheduleVariant> =
    variants.filter { it.season == season && !it.container }

/**
 * 该变体在 [minute]（当天 0 点起的分钟）时，人在哪。
 *
 * ## 跨午夜怎么算
 *
 * 整理稿的 `12:00 AM` 是「当日深夜延续」——它在**当天时间轴的末尾**，
 * 而不是开头。所以判定不是「找最后一个 m <= minute 的行」，而是：
 *
 * 1. 沿时间轴把每行的分钟数**单调化**：某行比上一行小（说明跨了午夜）就 +1440；
 * 2. 取最后一条「单调化后 <= minute」的行；
 * 3. 一条都没到（minute 早于第一行）→ 取当天最后一行（**承接前一天的深夜**）。
 *
 * 例（沙漠节）：7:50 AM / 10:20 AM / 10:30 AM / 12:00 AM
 * → 单调化后 470 / 620 / 630 / **1440**。
 * 23:00 → 命中 630（还在摊位）；00:30 → 一条都没到 → 取最后一行（已离开摊位）。都对。
 */
fun ScheduleVariant.rowAt(minute: Int): ScheduleRow? {
    val timed = rows.filter { scheduleMinute(it.t) >= 0 }
    if (timed.isEmpty()) return rows.firstOrNull()
    var prev = -1
    var pick: ScheduleRow? = null
    for (r in timed) {
        val m = scheduleMinute(r.t)
        val dayM = if (prev >= 0 && m < prev) m + 1440 else m
        prev = m
        if (dayM <= minute) pick = r
    }
    return pick ?: timed.last()
}

/** 该变体里「非具体时刻」的行（`全天`/`白天`/`夜间`）。 */
fun ScheduleVariant.wholeDayRows(): List<ScheduleRow> = rows.filter { scheduleMinute(it.t) < 0 }

/* ────────────────────────── 搜索 ────────────────────────── */

/** 一条搜索命中 */
data class ScheduleHit(
    val npc: String,
    val variant: ScheduleVariant,
    val row: ScheduleRow?,
)

/**
 * 搜行程：关键词命中**变体标题/条件**（整张表算命中）或**某一行行为**。
 *
 * ⚠ 搜的是**行为原文**，不是抽取出来的「地点」——
 *   行程表的行为是散文（「站在皮埃尔杂货店的厨房中」），
 *   从散文里抽地点必然抽错（同一个地名写法有好几种）。
 *   老实做全文命中，命中什么显示什么。
 */
fun searchSchedules(query: String): List<ScheduleHit> = searchSchedules(NpcSchedules.npcs, query)

/**
 * 搜行程（对给定 NPC 集合做全文命中）。
 *
 * ⚠ 搜的是**行为原文**（「站在皮埃尔杂货店的厨房中」），
 *   不是从散文里抽出来的「地点」—— 行程表的行为是散文，
 *   同一个地名在整理稿里有好几种写法，抽地点必然抽错。
 */
fun searchSchedules(npcs: List<NpcSchedule>, query: String): List<ScheduleHit> {
    val key = query.trim()
    if (key.isEmpty()) return emptyList()
    val out = ArrayList<ScheduleHit>()
    for (npc in npcs) {
        for (v in npc.variants) {
            // 容器标题（分组名）不是表，搜出来也没有内容可显示
            if (v.container) continue
            if (v.label.contains(key) || v.cond.contains(key)) {
                if (v.rows.isEmpty()) {
                    out.add(ScheduleHit(npc.name, v, null))
                } else {
                    v.rows.forEach { out.add(ScheduleHit(npc.name, v, it)) }
                }
            } else {
                v.rows.filter { it.a.contains(key) }.forEach { out.add(ScheduleHit(npc.name, v, it)) }
            }
        }
    }
    return out
}
