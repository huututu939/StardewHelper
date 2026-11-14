package com.stardew.assistant.data

/**
 * 「我今天该做什么」的建议模型（v2.100.0 / c181，百科 × 存档第 3 层；
 * v2.100.0 / c182 修「两个今天」与品种计数）。
 *
 * ## 这段是干什么的
 * 今日页的「通用日历」说的是**游戏里今天会发生什么**（谁的生日、哪个节日、播种死线），
 * 跟玩家是谁无关。这一段说的是**我**今天该做什么：收什么、清什么、种什么、给谁备礼。
 * 它的每一条都必须能从存档里算出来 —— 算不出来就不出现。
 *
 * ## 三条纪律（与前两层同源，不另起炉灶）
 * ① **不编数据**：没有存档 ⇒ 一条都不给；某个维度算不出来 ⇒ 那条不出现，
 *    绝不用「有空地记得种点东西」这种泛泛话术凑数。
 * ② **数字与今日页其它段落同源**：株数取 [FarmToday.readyStocks]、产值取
 *    [FarmToday.harvestTotal]、枯死取 [FarmToday.dead]、现金取 [FarmToday.money] ——
 *    全部是「我的农场」那一屏**已经在显示**的数，不在这里另算一遍。
 * ③ **每条都能指回依据**：[TodayAdvice.basis] 写明这条是从哪个字段算出来的，
 *    单测与 verify 都钉它；UI 底部的注脚再用人话把依据说一遍。
 *
 * ## ⚠ v2.100.0 / c182：「今天」只有一个 —— **存档里的那天**
 *
 * 原来建议的「今天」有两个：种什么 / 最后一天按**转盘**日期算，
 * 而田里 / 现金 / 能收几株来自**导入那一刻的存档** ⇒ 同一屏两张卡给出两个「今天」
 * （转盘=春 15、存档=第 3 年夏 6 日 ⇒ 建议说「春 15 日是最后一天」，
 *  照着做就是**在夏 6 日种春作物**）。
 *
 * 现在统一成**存档日期**（[FarmToday.year] / [FarmToday.season] / [FarmToday.day] ——
 * 与「今日农场」卡头部那个日期**同一个字段**）：
 * · 没有存档 ⇒ 建议整段不出现（`farm == null`）；
 * · 所以建议**永远**有存档 ⇒ 用存档日期是唯一自洽的选择；
 * · 存档日期（年 / 季 / 日）缺任一项 ⇒ 整段退场（判不出就别给「今天」的说法）。
 *
 * ## 纯逻辑
 * 不碰 Context、不读文件、不切线程（单测可直接喂内存数据）。
 * UI 侧只负责渲染与跳转：**图鉴跳转一律走 `resolveItemJump`**（全 App 唯一入口），
 * 村民跳转走 `navBus` 的 [com.stardew.assistant.ui.nav.NavRequest.Villager]。
 */
data class TodayAdvice(
    /** 一句人话（主文案）。 */
    val title: String,
    /** 依据：这条是从哪个字段算出来的（可追溯；单测钉它非空）。 */
    val basis: String,
    /** 副文案：补充数字与位置。null = 没有。 */
    val note: String? = null,
    /** 可点的跳转目标。null = 跳不了（那文案里必须写清位置）。 */
    val target: AdviceTarget? = null,
)

/** 一条建议的跳转目标。UI 侧翻译成真正的导航动作。 */
sealed interface AdviceTarget {
    /** 跳图鉴条目 —— UI 侧走 `resolveItemJump`（全 App 唯一图鉴跳转入口）。 */
    data class Codex(val name: String) : AdviceTarget

    /** 跳村民页 —— UI 侧走 `navBus` 的 `NavRequest.Villager`。 */
    data class Villager(val name: String) : AdviceTarget
}

/**
 * 按**存档日期**算「那天谁过生日」。
 *
 * ⚠ 与通用日历那份（`TodayFacts.todayBirthdays`，**转盘日期**）**刻意不是同一份**：
 *   建议区的「今天」是存档里的那天，日历卡的「今天」是用户转盘拨到的那天 ——
 *   两者可以不是同一天，那正是建议区存在的意义（不然两条文案一字不差，白重复一遍）。
 *
 * 判据与日历卡同款：生日日期相同 + [villagerPresent] 在场判定
 * （肯特第一年在部队服役 ⇒ 第一年春天不算他过生日）。
 *
 * @param seasonName 季节中文名（春 / 夏 / 秋 / 冬）
 * @param day 当日（1..28）
 * @param year 年份（判在场要用；判不出 ⇒ 不给 —— 不编）
 */
fun birthdaysOnDate(seasonName: String, day: Int, year: Int?): List<String> {
    if (seasonName !in Season.names) return emptyList()
    if (day !in 1..28) return emptyList()
    val y = year ?: return emptyList()
    val si = Season.names.indexOf(seasonName)
    return GameData.gifts
        .filter { it.birthdaySeason == seasonName && it.birthdayDay == day }
        .filter { villagerPresent(it.name, y, si, day) }
        .map { it.name }
}

/**
 * 建议的组装器（纯函数）。
 *
 * ## 四个维度（候选里挑最有把握的三个）
 * ① **田里**：有枯死的要清理 / 今天能收什么（存档里的作物状态）；
 * ② **补种**：空着的地该种什么（空地数 + 当季作物 + 现金买不买得起种子）；
 * ③ **人情**：存档那天谁过生日（**按存档日期**算，与日历卡那份不是同一份名单）。
 *
 * ## 优先级（v2.100.0 / c182）：枯死 → 能收 → 生日 → 补种
 *
 * **生日排在补种前面**，理由写在这里（别再随手改）：
 * · 生日是「**只有那天**」的事件（过了就没有了）；补种**任何一天**都能做
 *   （引擎本来就按「最晚下种日」排推荐，紧迫的作物自己会顶上来）。
 * · 生日这条与通用日历的「🎂 生日」卡**不是同一天**了（一个按存档日期、
 *   一个按转盘日期）⇒ 它不再与那张卡重复，留着有独立信息量。
 * · 条数上限仍是 3（`MAX_ADVICE`）—— 宁可少给一条，也不把「今日清单」摊成长列表。
 *
 * ## 刻意没做的两个候选（理由写在下面，别再重复提）
 * · **今天哪个商店开、卖什么**：营业日表只覆盖 12 家店，货品表有 257 项，
 *   从里面挑「该买什么」需要主观排序 —— 那是推荐，离线数据里没有依据。
 *   通用日历的「本周预览」已经给出营业信息，不重复。
 * · **节日 / 牲畜待办**：节日本身在通用日历有独立卡片 + 顶部横幅；
 *   牲畜待办在同一屏已有整块「牲畜今日待办」。重复一条不增信息量。
 */
object TodayAdviceBuilder {

    /** 建议条数上限。**最多 3 条**，多了就不是「该做什么」而是「今日清单」。 */
    const val MAX_ADVICE = 3

    /** 枯死那条的依据。 */
    const val BASIS_DEAD = "FarmToday.dead"

    /** 能收那条的依据。 */
    const val BASIS_HARVEST = "FarmToday.readyStocks + FarmToday.harvestTotal"

    /** 补种那条的依据。 */
    const val BASIS_PLANT = "CodexState.emptyPlots + GameData.crops + FarmToday.money"

    /**
     * 生日那条的依据。
     *
     * v2.100.0 / c182：原来写的是 `TodayFacts.todayBirthdays`（**转盘日期**那份），
     * 现在改成**存档日期**那份名单 ⇒ 依据字符串也跟着改（别再指向一个没在用的源）。
     */
    const val BASIS_BIRTHDAY = "birthdaysOnDate（存档日期） + GameData.gifts"

    /**
     * 组装今天该做的事。
     *
     * @param farm 今日农场模型（导入存档才有）。null ⇒ **一条都不给**（没存档就没有「我」）。
     * @param state 图鉴状态折算（同一份存档折出来的第二份小快照；空地数在这里）。
     * @param prof10 农业学家之类的职业（影响播种死线，与播种死线卡同一个口径）。
     * @param birthdays **存档日期**那天过生日的村民（[birthdaysOnDate] 的结果，不是日历卡那份）。
     * @param crops 作物表（与播种死线卡、图鉴「种子买价」同一张表）。
     * @return 最多 [MAX_ADVICE] 条；按「最急的在前」排序。
     */
    fun build(
        farm: FarmToday?,
        state: CodexState?,
        prof10: String?,
        birthdays: List<String>,
        crops: List<Crop>,
    ): List<TodayAdvice> {
        // 没导入存档 ⇒ 没有「我」⇒ 整段退场（不是给一句安慰话）
        if (farm == null) return emptyList()
        // ⚠ 「今天」= **存档里的那天**（与「今日农场」卡头部同一个字段）。
        //   缺年 / 缺季 / 缺日 ⇒ 判不出「今天」⇒ 整段退场（不拿转盘日期凑 ——
        //   那正是 c182 修掉的「同屏两个今天」）。
        val year = farm.year ?: return emptyList()
        val day = farm.day ?: return emptyList()
        val seasonName = farm.season
        if (seasonName !in Season.names) return emptyList()
        val out = ArrayList<TodayAdvice>(MAX_ADVICE)
        deadAdvice(farm)?.let { out += it }
        harvestAdvice(farm)?.let { out += it }
        birthdayAdvice(birthdays)?.let { out += it }
        plantAdvice(farm, state, seasonName, day, prof10, crops)?.let { out += it }
        return out.take(MAX_ADVICE)
    }

    /**
     * ① 枯死待清理。
     *
     * 株数与地点**直接引用** [FarmToday.dead] 的行，不在这里另算一个总数 ——
     * 今日页「枯死待清理」那一块显示的就是这些行。
     *
     * ⚠ v2.100.0 / c182：**按品种聚合后再数**（原来数的是「行」）：
     *   同一个品种种在农场 + 温室是两行（两行 ⇒ 写成「等 2 种」是错的，
     *   那是 1 个品种），`take(2)` 还会把同一品种拆成「大蒜 1 株、大蒜 2 株」。
     *   行键 = 「地点 + 品种」（见 FarmTodayBuilder），所以这里按**品种名**归并。
     */
    private fun deadAdvice(farm: FarmToday): TodayAdvice? {
        val rows = farm.dead
        if (rows.isEmpty()) return null
        val where = rows.map { it.location }.distinct().joinToString("、")
        // 按品种归并：同一品种散在几个地点 ⇒ 合成一条，株数相加；
        // 排序按**合计株数**降序（与原来「行按株数降序」一致：先说最惨的那片）
        val byName = LinkedHashMap<String, Int>()
        for (r in rows) byName[r.name] = (byName[r.name] ?: 0) + r.count
        val kinds = byName.size
        val sorted = byName.entries.sortedByDescending { it.value }
        val what = sorted.take(2).joinToString("、") { "${it.key} ${it.value} 株" }
        val more = if (kinds > 2) " 等 $kinds 种" else ""
        return TodayAdvice(
            title = "有枯死的作物要清理",
            note = "$where · $what$more",
            basis = BASIS_DEAD,
            target = AdviceTarget.Codex(rows.first().name),
        )
    }

    /**
     * ② 今天能收什么。
     *
     * 株数取 [FarmToday.readyStocks]、产值取 [FarmToday.harvestTotal] ——
     * 与「我的农场」头部那两个数**同一个来源**，两处不许打架。
     */
    private fun harvestAdvice(farm: FarmToday): TodayAdvice? {
        if (farm.harvest.isEmpty() && farm.unnamedReady <= 0) return null
        val parts = ArrayList<String>(3)
        parts += "已知售价合计 ${formatG(farm.harvestTotal)} g"
        val names = farm.harvest.take(2).joinToString("、") { "${it.name} ${it.count} 株" }
        if (names.isNotEmpty()) parts += names
        if (farm.unnamedReady > 0) parts += "另有 ${farm.unnamedReady} 株野生采集物"
        return TodayAdvice(
            title = "今天能收 ${farm.readyStocks} 株",
            note = parts.joinToString(" · "),
            basis = BASIS_HARVEST,
            target = farm.harvest.firstOrNull()?.let { AdviceTarget.Codex(it.name) },
        )
    }

    /**
     * ③ 存档那天谁过生日。
     *
     * 名单由调用方按**存档日期**给（[birthdaysOnDate]）—— **不是**通用日历那份
     * （`TodayFacts.todayBirthdays` 按转盘日期）：两者可以不是同一天，
     * 那正是这条建议不与「🎂 生日」卡重复的原因。
     *
     * ⚠ 文案**不许与生日卡那句逐字相同**（日历卡写的是「生日送礼效果 ×8，记得备最爱礼物」）
     *   —— 一字不差就等于同一句话说两遍。
     */
    private fun birthdayAdvice(birthdays: List<String>): TodayAdvice? {
        if (birthdays.isEmpty()) return null
        val names = birthdays.joinToString("、")
        return TodayAdvice(
            title = "今天是${names}的生日",
            note = "送礼效果 ×8 · 备好 TA 最爱的礼物",
            basis = BASIS_BIRTHDAY,
            target = AdviceTarget.Villager(birthdays.first()),
        )
    }

    /**
     * ④ 空地该种什么。
     *
     * 选谁：**今天必须种的排第一**，其余按「播种死线最近」排 —— 口径与今日页
     * 「播种死线」卡完全一致（同一个 [Calc.deadline] / [Calc.lastPlantDay]，
     * 同一张作物表）。
     *
     * ⚠ v2.100.0 / c182：`seasonName` / `day` 现在是**存档日期**的那个 ——
     *   所以「当季可种」这个过滤（`it.seasons.contains(seasonName)`）筛的是
     *   存档那天真正能种的作物（夏 6 日不会再推大黄这种春作物）。
     *
     * 现金：与图鉴「种子买价」行同一套说法（够买几包 / 不够买一包）。
     * 种子价判不出来（Mod 作物常缺）⇒ 不挑它 ⇒ 挑不到就不给这条。
     */
    private fun plantAdvice(
        farm: FarmToday,
        state: CodexState?,
        seasonName: String,
        day: Int,
        prof10: String?,
        crops: List<Crop>,
    ): TodayAdvice? {
        val empty = state?.emptyPlots ?: return null
        if (empty <= 0) return null
        val pick = crops
            .filter { it.seasons.contains(seasonName) }
            .filter { it.growth != null }
            .filter { it.seedPrice != null && it.seedPrice > 0 }
            .filter { Calc.deadline(it, seasonName, day, prof10) != Calc.Deadline.TOO_LATE }
            .sortedWith(
                compareBy<Crop> {
                    if (Calc.deadline(it, seasonName, day, prof10) == Calc.Deadline.MUST_PLANT) 0 else 1
                }.thenBy { Calc.lastPlantDay(it, seasonName, prof10) }
                    .thenBy { it.name }
            )
            .firstOrNull() ?: return null
        val seed = pick.seedPrice ?: return null
        val last = Calc.lastPlantDay(pick, seasonName, prof10)
        val must = Calc.deadline(pick, seasonName, day, prof10) == Calc.Deadline.MUST_PLANT
        val whenText = if (must) "$seasonName $last 日是最后一天" else "$seasonName $last 日前下种"
        val money = farm.money
        val moneyText = when {
            money == null -> "现金未声明，以游戏内为准"
            money >= seed -> "现金 ${formatG(money)} g，可买 ${money / seed} 包"
            else -> "现金 ${formatG(money)} g，不够买一包"
        }
        return TodayAdvice(
            title = "有 $empty 块空地要种",
            note = "${pick.name}（$whenText）· 种子 ${seed} g · $moneyText",
            basis = BASIS_PLANT,
            target = AdviceTarget.Codex(pick.name),
        )
    }
}
