package com.stardew.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v2.100.0 / c181「我今天该做什么」建议区（百科 × 存档第 3 层）；
 * v2.100.0 / c182 修「两个今天」与品种计数。
 *
 * ## 断言钉的是**语义**，不是某份存档的内容
 * ① **条数 ≤ 3**（钉上限，不钉具体几条）；
 * ② **每条都能指回依据数据**（basis 非空且指向真字段，不许写死具体建议文案）；
 * ③ **数字与今日页同源**（建议里的数 = FarmToday 里那个数，两处不许各算一套）；
 * ④ **算不出来就不出现**（反向断言：没有存档 / 数据不足 ⇒ 零条，不许兜底塞一条泛泛建议）；
 * ⑤ **数据不足时优雅退场**（零条 ⇒ UI 整卡不出现）；
 * ⑥ **c182：「今天」只有一个 —— 存档里的那天**（存档日期缺年 / 缺日 / 缺季 ⇒ 整段退场；
 *    当季作物按**存档季节**筛；生日名单是调用方按存档日期给的那份）。
 * ⑦ **c182：枯死那条的「等 N 种」数的是品种**，不是行数。
 */
class TodayAdviceTest {

    // ── 造数据的小工具 ────────────────────────────────────────────────

    private fun row(
        name: String,
        count: Int,
        location: String = "农场",
        unitPrice: Int? = 35,
        total: Int = unitPrice!! * count,
    ) = FarmRow(location = location, name = name, count = count, unitPrice = unitPrice, total = total)

    /**
     * 存档（FarmToday）。
     *
     * ⚠ **年 / 季 / 日就是建议里的「今天」**（c182）—— 所以这三个参数可单独改，
     *   用来测「存档日期判不出 ⇒ 整段退场」。
     */
    private fun farm(
        ready: Int = 0,
        total: Int = 0,
        harvest: List<FarmRow> = emptyList(),
        dead: List<FarmRow> = emptyList(),
        money: Int? = 1000,
        year: Int? = 2,
        season: String = "春",
        day: Int? = 10,
    ) = FarmToday(
        farmer = "测试", year = year, season = season, day = day, money = money,
        readyStocks = ready, harvest = harvest, harvestTotal = total, unnamedReady = 0,
        soon = emptyList(), dead = dead, todos = emptyList(), animalTotal = 0,
        savedAt = 0L, sourceLabel = "测试",
    )

    private fun state(emptyPlots: Int?) = CodexState(
        farmer = "测试", year = 2, season = "春", day = 10, money = 1000,
        itemCounts = emptyMap(), unmappedItems = 0, crops = emptyMap(),
        friendships = emptyMap(), emptyPlots = emptyPlots,
    )

    private fun crop(
        name: String,
        seedPrice: Int? = 20,
        growth: Int? = 4,
        seasons: List<String> = listOf("春"),
    ) = Crop(
        name = name, seedPrice = seedPrice, sellPrice = 35, growth = growth, regrow = null,
        seasons = seasons, channels = "", note = "", giant = false, trellis = false,
    )

    private val 防风草 = crop("防风草", seedPrice = 20, growth = 4)
    private val 青豆 = crop("青豆", seedPrice = 60, growth = 10)

    private fun build(
        farm: FarmToday? = null,
        state: CodexState? = null,
        prof10: String? = null,
        birthdays: List<String> = emptyList(),
        crops: List<Crop> = listOf(防风草, 青豆),
    ) = TodayAdviceBuilder.build(
        farm = farm,
        state = state,
        prof10 = prof10,
        birthdays = birthdays,
        crops = crops,
    )

    // ── ④ 算不出来就不出现（反向断言）────────────────────────────────

    @Test
    fun 没有存档就一条都不给() {
        assertTrue("没导入存档 ⇒ 没有「我」⇒ 零条", build(farm = null).isEmpty())
    }

    @Test
    fun 数据不足时不兜底塞泛泛建议() {
        // 有存档但田里没事、空地判不出、今天没人过生日 ⇒ 零条
        val r = build(farm = farm(), state = state(emptyPlots = null), birthdays = emptyList())
        assertTrue("算不出来就不出现（不给泛泛话术充数）", r.isEmpty())
    }

    @Test
    fun 空地判不出就不给种那条() {
        val r = build(farm = farm(), state = state(emptyPlots = null))
        assertTrue("空地数判不出（null）≠ 0 块空地 ⇒ 不给建议", r.isEmpty())
        val r2 = build(farm = farm(), state = state(emptyPlots = 0))
        assertTrue("确实没有空地 ⇒ 不给建议", r2.isEmpty())
    }

    @Test
    fun 没有当季作物就不给种那条() {
        // 存档那天是夏天（田里只有春作物）⇒ 不给「该种什么」那条
        val r = build(farm = farm(season = "夏"), state = state(emptyPlots = 4))
        assertTrue("夏天的田里没有可种的春作物 ⇒ 不给建议", r.isEmpty())
    }

    @Test
    fun 今天没人过生日就没有生日那条() {
        val r = build(farm = farm(), birthdays = emptyList())
        assertTrue("没有生日就没有生日那条", r.none { it.basis == TodayAdviceBuilder.BASIS_BIRTHDAY })
    }

    // ── ⑥ c182：「今天」= 存档里的那天 ────────────────────────────────

    @Test
    fun 存档日期缺了就整段退场() {
        // 缺年份 ⇒ 判不出「今天」⇒ 整段退场（不拿别的日期凑）
        assertTrue("缺年份 ⇒ 整段退场", build(farm = farm(year = null), state = state(emptyPlots = 4)).isEmpty())
        // 缺日 ⇒ 同理
        assertTrue("缺日 ⇒ 整段退场", build(farm = farm(day = null), state = state(emptyPlots = 4)).isEmpty())
        // 季节认不出来 ⇒ 同理
        assertTrue("季节认不出 ⇒ 整段退场", build(farm = farm(season = "spring"), state = state(emptyPlots = 4)).isEmpty())
    }

    @Test
    fun 当季作物按存档季节筛() {
        // 存档那天是夏 6 日 ⇒ 只推夏作物；春作物（大黄那种）不该出现
        val 夏作物 = crop("辣椒", seedPrice = 40, growth = 5, seasons = listOf("夏"))
        val r = build(farm = farm(season = "夏", day = 6), state = state(emptyPlots = 3), crops = listOf(防风草, 夏作物))
        assertEquals("一条（补种）", 1, r.size)
        val n = r.first().note ?: ""
        assertTrue("推的是当季作物：$n", n.contains("辣椒"))
        assertFalse("不该推春作物：$n", n.contains("防风草"))
    }

    @Test
    fun 死线按存档日期算() {
        // 存档那天是春 18 日 ⇒ 青豆（最晚 18 日）今天必须种
        val r = build(farm = farm(day = 18), state = state(emptyPlots = 2))
        val n = r.first().note ?: ""
        assertTrue("第 18 天先推必须种下的那个：$n", n.contains("青豆"))
        assertTrue("写明是最后一天：$n", n.contains("最后一天"))
    }

    // ── ① 条数 ≤ 3 ──────────────────────────────────────────────────

    @Test
    fun 条数不超过三条() {
        // 四件事同时成立（枯死 / 能收 / 空地 / 生日）⇒ 仍然只给 3 条
        val f = farm(
            ready = 12, total = 420,
            harvest = listOf(row("防风草", 12)),
            dead = listOf(row("大蒜", 3)),
        )
        val r = build(farm = f, state = state(emptyPlots = 4), birthdays = listOf("艾米丽"))
        assertTrue("建议条数 ≤ 3（实际 ${r.size}）", r.size <= TodayAdviceBuilder.MAX_ADVICE)
        assertEquals("恰好截到上限 3 条", 3, r.size)
    }

    @Test
    fun 生日排在补种前面() {
        // c182 口径：生日是「只有那天」的事件 ⇒ 排在「补种」前面
        // （补种任何一天都能做，而生日过了就没了）。
        // 四个候选全成立时只给 3 条 ⇒ 被挤掉的应该是「补种」，生日必须在。
        val f = farm(
            ready = 12, total = 420,
            harvest = listOf(row("防风草", 12)),
            dead = listOf(row("大蒜", 3)),
        )
        val r = build(farm = f, state = state(emptyPlots = 4), birthdays = listOf("艾米丽"))
        val bases = r.map { it.basis }
        assertTrue("生日那条在（没被挤掉）：$bases", bases.contains(TodayAdviceBuilder.BASIS_BIRTHDAY))
        assertFalse(
            "挤掉的是补种那条（生日优先）：$bases",
            bases.contains(TodayAdviceBuilder.BASIS_PLANT),
        )
    }

    // ── ② 每条都能指回依据数据 ───────────────────────────────────────

    @Test
    fun 每条建议都有依据() {
        val f = farm(
            ready = 12, total = 420,
            harvest = listOf(row("防风草", 12)),
            dead = listOf(row("大蒜", 3)),
        )
        val r = build(farm = f, state = state(emptyPlots = 4), birthdays = listOf("艾米丽"))
        assertTrue("有建议", r.isNotEmpty())
        val known = setOf(
            TodayAdviceBuilder.BASIS_DEAD,
            TodayAdviceBuilder.BASIS_HARVEST,
            TodayAdviceBuilder.BASIS_PLANT,
            TodayAdviceBuilder.BASIS_BIRTHDAY,
        )
        for (a in r) {
            assertTrue("依据不为空：${a.title}", a.basis.isNotBlank())
            assertTrue("依据指向真实字段：${a.basis}", a.basis in known)
        }
    }

    // ── ③ 数字与今日页同源 ───────────────────────────────────────────

    @Test
    fun 数字与今日页同一份数据() {
        val f = farm(
            ready = 12, total = 420,
            harvest = listOf(row("防风草", 12)),
        )
        val r = build(farm = f)
        assertEquals("只有一条（能收）", 1, r.size)
        val a = r.first()
        // 株数与产值都取 FarmToday 里**同一个**数（今日页「今天能收的」显示的也是这两个）
        assertTrue("株数与 FarmToday.readyStocks 相同：${a.title}", a.title.contains("${f.readyStocks}"))
        assertTrue("产值与 FarmToday.harvestTotal 相同：${a.note}", (a.note ?: "").contains("${f.harvestTotal}"))
        // 品种与株数直接引用 FarmToday.harvest 的行，不另算一套
        assertTrue("引用了存档里的那一行：${a.note}", (a.note ?: "").contains("防风草"))
    }

    @Test
    fun 枯死那条只引用存档里的行() {
        val f = farm(dead = listOf(row("大蒜", 3, location = "农场"), row("花椰菜", 2, location = "温室")))
        val r = build(farm = f)
        val a = r.first { it.basis == TodayAdviceBuilder.BASIS_DEAD }
        val n = a.note ?: ""
        assertTrue("写了地点：$n", n.contains("农场") && n.contains("温室"))
        assertTrue("写了品种与株数：$n", n.contains("大蒜 3 株"))
        assertEquals("跳图鉴的是那个品种", AdviceTarget.Codex("大蒜"), a.target)
    }

    // ── ⑦ c182：枯死那条数的是**品种**，不是行 ───────────────────────

    @Test
    fun 枯死那条数的是品种不是行() {
        // 同一个品种种在两个地方 = 两行，但**一个品种**；
        // 4 行 ⇒ 3 个品种（写成「等 3 种」才对，写「等 4 种」是错的）。
        val f = farm(
            dead = listOf(
                row("大蒜", 3, location = "农场"),
                row("大蒜", 2, location = "温室"),
                row("花椰菜", 1, location = "农场"),
                row("萝卜", 4, location = "农场"),
            ),
        )
        val r = build(farm = f)
        val a = r.first { it.basis == TodayAdviceBuilder.BASIS_DEAD }
        val n = a.note ?: ""
        assertTrue("同品种合并计数：$n", n.contains("大蒜 5 株"))
        assertFalse("不该把同一品种拆成两行：$n", n.contains("大蒜 3 株"))
        assertTrue("品种数是 3（不是行数 4）：$n", n.contains("等 3 种"))
        assertFalse("不该按行数写：$n", n.contains("等 4 种"))
    }

    // ── 补种那条：季节 + 现金 + 种子价 ───────────────────────────────

    @Test
    fun 空地该种什么看当季与现金() {
        val r = build(farm = farm(money = 1000), state = state(emptyPlots = 4), crops = listOf(防风草))
        assertEquals("一条（补种）", 1, r.size)
        val a = r.first()
        assertEquals("依据是空地 + 作物表 + 现金", TodayAdviceBuilder.BASIS_PLANT, a.basis)
        assertTrue("说了有几块空地：${a.title}", a.title.contains("4"))
        val n = a.note ?: ""
        assertTrue("说了种什么：$n", n.contains("防风草"))
        assertTrue("说了种子价：$n", n.contains("20 g"))
        assertTrue("说了买不买得起：$n", n.contains("现金 1,000 g") && n.contains("可买 50 包"))
        assertEquals("跳图鉴的是那个作物", AdviceTarget.Codex("防风草"), a.target)
    }

    @Test
    fun 现金不够就明说不够买() {
        val r = build(farm = farm(money = 10), state = state(emptyPlots = 4))
        val n = r.first().note ?: ""
        assertTrue("明说不够买：$n", n.contains("现金 10 g") && n.contains("不够买一包"))
    }

    @Test
    fun 死线最近的排前面() {
        // 青豆 growth=10 ⇒ 最晚下种日 18；防风草 growth=4 ⇒ 最晚 24。
        // 第 10 天两个都还来得及 ⇒ 推死线更近的那个（与播种死线卡同一口径）。
        val r = build(farm = farm(day = 10), state = state(emptyPlots = 2))
        val n = r.first().note ?: ""
        assertTrue("推死线更近的那个：$n", n.contains("青豆"))
        assertTrue("写明最晚哪天：$n", n.contains("春 18 日前下种"))
    }

    @Test
    fun 今天必须种的排第一() {
        // 青豆 growth=10 ⇒ 最晚下种日 28-10=18；第 18 天它必须种
        // 防风草 growth=4 ⇒ 最晚 24 号。所以第 18 天该推青豆。
        val r = build(farm = farm(day = 18), state = state(emptyPlots = 2))
        val n = r.first().note ?: ""
        assertTrue("第 18 天先推必须种下的那个：$n", n.contains("青豆"))
        assertTrue("写明是最后一天：$n", n.contains("最后一天"))
    }

    @Test
    fun 种子价判不出来就不挑那个作物() {
        // 只有种子价判不出来的作物 ⇒ 挑不出能算的 ⇒ 不给这条
        val r = build(farm = farm(), state = state(emptyPlots = 2), crops = listOf(crop("神秘菜", seedPrice = null)))
        assertTrue("算不出买不买得起 ⇒ 不给建议", r.isEmpty())
    }

    // ── 生日那条 ─────────────────────────────────────────────────────

    @Test
    fun 生日那条用存档日期那份名单() {
        val r = build(farm = farm(), birthdays = listOf("艾米丽", "乔治"))
        val a = r.first { it.basis == TodayAdviceBuilder.BASIS_BIRTHDAY }
        assertTrue("写了名字：${a.title}", a.title.contains("艾米丽") && a.title.contains("乔治"))
        assertEquals("跳村民页", AdviceTarget.Villager("艾米丽"), a.target)
    }

    @Test
    fun 生日那条文案与生日卡那句不逐字相同() {
        // 通用日历的「🎂 生日」卡写的是「生日送礼效果 ×8，记得备最爱礼物」
        // —— 一字不差就等于同一句话说两遍。
        val 生日卡那句 = "生日送礼效果 ×8，记得备最爱礼物"
        val r = build(farm = farm(), birthdays = listOf("艾米丽"))
        val a = r.first { it.basis == TodayAdviceBuilder.BASIS_BIRTHDAY }
        assertFalse("标题不与生日卡那句逐字相同：${a.title}", a.title == 生日卡那句)
        assertFalse("副文案不与生日卡那句逐字相同：${a.note}", a.note == 生日卡那句)
        assertFalse(
            "副文案不抄生日卡那句：${a.note}",
            (a.note ?: "").contains(生日卡那句),
        )
    }

    // ── 空地数的折算（CodexStateBuilder）与缓存往返 ───────────────────

    @Test
    fun 空地数只算农场() {
        val st = WorldState(
            year = 2, season = "spring", dayOfMonth = 10, goldenWalnuts = 0,
            locations = listOf("Farm", "Greenhouse"),
            plots = listOf(
                WorldPlot("Farm", 1, 1, null),
                WorldPlot("Farm", 2, 1, null),
                WorldPlot("Greenhouse", 1, 1, null),
                WorldPlot("Greenhouse", 2, 1, null),
            ),
            animals = emptyList(), inventories = emptyList(),
            players = listOf(
                WorldPlayer("测试", false, 1000, 1.0, emptyMap(), null)
            ),
            skippedDuplicateAnimals = 0,
        )
        val cs = CodexStateBuilder.build(st, WorldSaveFixtures.loadWorldItemMap(), emptyMap())
        assertEquals("空地只算 Farm（温室不算）", 2, cs.emptyPlots)
    }

    @Test
    fun 空地数缓存往返不变() {
        val m = state(emptyPlots = 7)
        val back = codexStateFromJson(m.toJson())
        assertNotNull("往返后不为 null", back)
        assertEquals("空地数往返一致", 7, back?.emptyPlots)
        // null 语义往返：判不出仍是 null，不能被兜底成 0
        val m2 = state(emptyPlots = null)
        val back2 = codexStateFromJson(m2.toJson())
        assertNull("判不出往返后仍是 null（不是 0 块）", back2?.emptyPlots)
        // 老缓存（没有这个键）⇒ null，不是 0
        val legacy = codexStateFromJson("""{"farmer":"x","season":"春","day":1}""")
        assertNull("老缓存没有这一项 ⇒ 判不出", legacy?.emptyPlots)
    }

    @Test
    fun 建议里不出现未声明的凑数文案() {
        val f = farm(ready = 3, total = 105, harvest = listOf(row("防风草", 3)))
        val r = build(farm = f, state = state(emptyPlots = 1), birthdays = listOf("艾米丽"))
        val vague = listOf("可以试试", "随便", "记得看看", "说不定")
        for (a in r) {
            for (v in vague) {
                assertFalse("没有泛泛话术「$v」：${a.title}", a.title.contains(v))
            }
        }
    }
}
