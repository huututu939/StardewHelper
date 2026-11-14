package com.stardew.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * P2 验收：「我的农场」要显示的那些数字。
 *
 * UI 不自己算 —— 它只渲染 [FarmToday]。所以这一层断言 = **界面上的数字**：
 * 今日页那一屏显示的每一行，都由这里钉死。
 */
class FarmTodayTest {

    private val map: WorldItemMap by lazy { WorldSaveFixtures.loadWorldItemMap() }
    private val prices: Map<String, Int> by lazy { WorldSaveFixtures.loadSellPrices() }

    private fun build(label: String): FarmToday {
        val dir = WorldSaveFixtures.savesDir()
        assumeTrue("找不到存档目录 ${dir.absolutePath}", dir.isDirectory)
        val st = WorldSaveFixtures.parseAll()[label] ?: error("没解析出 $label 档")
        return FarmTodayBuilder.build(st, map, prices, sourceLabel = label)
    }

    /** 一行「今天能收的」：按名字找。 */
    private fun FarmToday.harvestOf(name: String) = harvest.firstOrNull { it.name == name }

    // ── ff 档 ────────────────────────────────────────────────────────

    @Test
    fun ff档的今日农场数字() {
        val m = build("ff")
        // 今天能收：远古水果 ×95（温室）→ 52,250g
        val fruit = m.harvestOf("远古水果")
        assertEquals("ff 远古水果株数", 95, fruit?.count)
        assertEquals("ff 远古水果在温室", "温室", fruit?.location)
        assertEquals("ff 远古水果单株价", 550, fruit?.unitPrice)
        assertEquals("ff 今日可收总额", 52250, m.harvestTotal)
        assertEquals("ff 今天能收总株数", 102, m.readyStocks)
        // 7 株 mod 产物查不到名字 → 记进「野生采集物」，不进产值
        assertEquals("ff 查不到名字的株数", 7, m.unnamedReady)
        // 马上要熟：远古水果 ×15，还剩 3 天
        val soon = m.soon.firstOrNull { it.name == "远古水果" }
        assertEquals("ff 马上要熟的远古水果", 15, soon?.count)
        assertEquals("ff 还剩 3 天", 3, soon?.daysLeft)
        // 枯死待清理：农场 大蒜 ×41、花椰菜 ×15
        val garlic = m.dead.firstOrNull { it.name == "大蒜" }
        val cauli = m.dead.firstOrNull { it.name == "花椰菜" }
        assertEquals("ff 枯死大蒜", 41, garlic?.count)
        assertEquals("ff 枯死大蒜在农场", "农场", garlic?.location)
        assertEquals("ff 枯死花椰菜", 15, cauli?.count)
        // 牲畜今日待办：4 只，标出心情值
        assertEquals("ff 牲畜总数", 16, m.animalTotal)
        assertEquals("ff 要动手的牲畜", 4, m.todos.size)
        assertEquals("ff 猪的品种名", "猪", m.todos.firstOrNull { it.name == "2022.7.23" }?.type)
        assertEquals("ff 猪的好感", 980, m.todos.firstOrNull { it.name == "2022.7.23" }?.friendship)
        assertEquals("ff 猪的心情", 210, m.todos.firstOrNull { it.name == "2022.7.23" }?.happiness)
        assertTrue(
            "ff 待办里要标出抚摸与心情",
            m.todos.all { it.action.contains("抚摸") && it.action.contains("心情") }
        )
    }

    // ── 重逢档 ────────────────────────────────────────────────────────

    @Test
    fun 重逢档的今日农场数字() {
        val m = build("重逢")
        // 今天能收：花椰菜 ×54、辣椒 ×15、向日葵 ×9、草莓 ×9、远古水果 ×2
        assertEquals("重逢 花椰菜", 54, m.harvestOf("花椰菜")?.count)
        assertEquals("重逢 辣椒", 15, m.harvestOf("辣椒")?.count)
        assertEquals("重逢 向日葵", 9, m.harvestOf("向日葵")?.count)
        assertEquals("重逢 草莓", 9, m.harvestOf("草莓")?.count)
        assertEquals("重逢 远古水果", 2, m.harvestOf("远古水果")?.count)
        // 提示词列的那 5 组 = 12,950g
        val five = listOf("花椰菜", "辣椒", "向日葵", "草莓", "远古水果")
        assertEquals(
            "重逢 提示词那 5 组的产值",
            12950,
            m.harvest.filter { it.name in five }.sumOf { it.total },
        )
        // 全量多 7 株夏南瓜（7×45）
        assertEquals("重逢 夏南瓜", 7, m.harvestOf("金皮西葫芦（夏南瓜）")?.count)
        assertEquals("重逢 全量今日可收总额", 13265, m.harvestTotal)
        assertEquals("重逢 今天能收总株数", 106, m.readyStocks)
        assertEquals("重逢 查不到名字的株数", 10, m.unnamedReady)
        // 马上要熟：红甘蓝 ×1，还剩 1 天
        // ⚠ v2.82：红甘蓝是旧的自造名，已改成官方译名「红叶卷心菜」（群友 @王忠明 报错）
        val red = m.soon.firstOrNull { it.name == "红叶卷心菜" }
        assertEquals("重逢 红叶卷心菜", 1, red?.count)
        assertEquals("重逢 红叶卷心菜还剩 1 天", 1, red?.daysLeft)
        // 枯死待清理：农场 大米 ×4（App 的译名是「大米」，上游叫 Unmilled Rice）
        val rice = m.dead.firstOrNull { it.name == "大米" }
        assertEquals("重逢 枯死大米", 4, rice?.count)
        assertEquals("重逢 枯死大米在农场", "农场", rice?.location)
        // 牲畜：10 只，全部状态良好 → 待办为空
        assertEquals("重逢 牲畜总数", 10, m.animalTotal)
        assertEquals("重逢 没有要动手的牲畜", 0, m.todos.size)
    }

    // ── 穿越档 ────────────────────────────────────────────────────────

    @Test
    fun 穿越档的今日农场数字() {
        val m = build("穿越")
        // 今天能收：防风草 ×64、土豆 ×1，另有 22 个野生采集物
        assertEquals("穿越 防风草", 64, m.harvestOf("防风草")?.count)
        assertEquals("穿越 土豆", 1, m.harvestOf("土豆")?.count)
        assertEquals("穿越 野生采集物", 22, m.unnamedReady)
        assertEquals("穿越 今天能收总株数", 87, m.readyStocks)
        // 枯死：无 → 整块不显示
        assertTrue("穿越 没有枯死作物（整块不显示）", m.dead.isEmpty())
        // 牲畜：0 只 → 整块不显示
        assertEquals("穿越 牲畜总数", 0, m.animalTotal)
        assertTrue("穿越 没有牲畜（整块不显示）", m.todos.isEmpty())
    }

    // ── 缓存往返 ──────────────────────────────────────────────────────

    @Test
    fun 展示模型能原样存回来() {
        val m = build("ff")
        val back = farmTodayFromJson(m.toJson())
        assertTrue("模型往返后不为 null", back != null)
        assertEquals("往返后角色名", m.farmer, back?.farmer)
        assertEquals("往返后总株数", m.readyStocks, back?.readyStocks)
        assertEquals("往返后产值", m.harvestTotal, back?.harvestTotal)
        assertEquals("往返后可收行数", m.harvest.size, back?.harvest?.size)
        assertEquals("往返后枯死行数", m.dead.size, back?.dead?.size)
        assertEquals("往返后待办数", m.todos.size, back?.todos?.size)
        // null 字段往返后仍是 null（**不能被兜底成 0**）
        val noPrice = m.harvest.firstOrNull { it.unitPrice == null }
        if (noPrice != null) {
            assertNull("没有售价的行往返后仍是 null", back?.harvest?.firstOrNull { it.name == noPrice.name }?.unitPrice)
        }
        // 小地图也要原样回来
        assertEquals("往返后地图数", m.maps.size, back?.maps?.size)
        assertEquals(
            "往返后每张地图的地块数",
            m.maps.map { it.plotCount },
            back?.maps?.map { it.plotCount },
        )
        assertEquals(
            "往返后每张地图的片数",
            m.maps.map { it.patches.size },
            back?.maps?.map { it.patches.size },
        )
        assertEquals(
            "往返后格子的坐标与状态",
            m.maps.flatMap { mp -> mp.patches.flatMap { p -> p.tiles.map { "${it.x},${it.y},${it.state}" } } },
            back?.maps?.flatMap { mp -> mp.patches.flatMap { p -> p.tiles.map { "${it.x},${it.y},${it.state}" } } },
        )
    }

    // ── P3-a 农场地图 ─────────────────────────────────────────────────

    /** 地图按名字找。 */
    private fun FarmToday.mapOf(name: String) = maps.firstOrNull { it.location == name }

    @Test
    fun 农场地图的四档数字() {
        // ── ff ──
        val ff = build("ff")
        assertEquals("ff 地图数", 4, ff.maps.size)
        assertEquals("ff 耕地总数", 281, ff.maps.sumOf { it.plotCount })
        // 地图按地块数降序（视觉重心在前）
        assertEquals("ff 第一张地图是温室（116 块）", "温室", ff.maps.first().location)
        // 温室是规整的 12×10 一片
        val gh = ff.mapOf("温室")
        assertEquals("ff 温室地块数", 116, gh?.plotCount)
        assertEquals("ff 温室只切 1 片", 1, gh?.patches?.size)
        val ghp = gh?.patches?.first()
        assertEquals("ff 温室 x 起", 4, ghp?.x0)
        assertEquals("ff 温室 x 止", 15, ghp?.x1)
        assertEquals("ff 温室 y 起", 10, ghp?.y0)
        assertEquals("ff 温室 y 止", 19, ghp?.y1)
        assertEquals("ff 温室这片 116 格", 116, ghp?.tiles?.size)
        // 温室里 95 株可收 + 21 株在长
        assertEquals("ff 温室可收格数", 95, ghp?.tiles?.count { it.state == TileState.READY })
        assertEquals("ff 温室生长中格数", 21, ghp?.tiles?.count { it.state == TileState.GROWING })
        // 农场地 71 块切成 3 片（散在农场的三处）
        val farm = ff.mapOf("农场")
        assertEquals("ff 农场地块数", 71, farm?.plotCount)
        assertEquals("ff 农场切 3 片", 3, farm?.patches?.size)
        assertEquals("ff 农场第一片 40 块", 40, farm?.patches?.first()?.tiles?.size)
        // 农场里 56 块枯死
        assertEquals("ff 农场枯死格数", 56, farm?.patches?.flatMap { it.tiles }?.count { it.state == TileState.DEAD })
        // 姜岛西部：91 块散成 6 片（跨度大、块很散 —— 这就是必须切分的原因）
        val iw = ff.mapOf("姜岛西部")
        assertEquals("ff 姜岛西部地块数", 91, iw?.plotCount)
        assertEquals("ff 姜岛西部切 6 片", 6, iw?.patches?.size)
        assertEquals("ff 姜岛西部最宽那片", 73, iw?.patches?.maxOf { it.width })

        // ── 重逢 ──
        val cf = build("重逢")
        assertEquals("重逢 地图数", 4, cf.maps.size)
        assertEquals("重逢 耕地总数", 300, cf.maps.sumOf { it.plotCount })
        val cfarm = cf.mapOf("农场")
        assertEquals("重逢 农场地块数", 174, cfarm?.plotCount)
        assertEquals("重逢 农场切 2 片", 2, cfarm?.patches?.size)
        assertEquals("重逢 农场主片 173 块", 173, cfarm?.patches?.first()?.tiles?.size)
        assertEquals("重逢 农场主片 x 起", 27, cfarm?.patches?.first()?.x0)
        assertEquals("重逢 农场主片 x 止", 74, cfarm?.patches?.first()?.x1)

        // ── 穿越 ──
        val cy = build("穿越")
        assertEquals("穿越 地图数", 4, cy.maps.size)
        assertEquals("穿越 耕地总数", 162, cy.maps.sumOf { it.plotCount })
        assertEquals("穿越 农场地块数", 140, cy.mapOf("农场")?.plotCount)
        assertEquals("穿越 农场切 2 片", 2, cy.mapOf("农场")?.patches?.size)

        // ── KKK ──
        val kk = build("KKK")
        assertEquals("KKK 地图数", 5, kk.maps.size)
        assertEquals("KKK 耕地总数", 582, kk.maps.sumOf { it.plotCount })
        assertEquals("KKK 姜岛西部地块数", 319, kk.mapOf("姜岛西部")?.plotCount)
        assertEquals("KKK 姜岛西部切 2 片", 2, kk.mapOf("姜岛西部")?.patches?.size)
        assertEquals("KKK 温室地块数", 120, kk.mapOf("温室")?.plotCount)
    }

    @Test
    fun 地图的格子能按坐标查回来() {
        val m = build("ff")
        val gh = m.mapOf("温室")!!
        val p = gh.patches.first()
        val t = p.tiles.first()
        assertEquals("按坐标查格子", t, p.tileAt(t.x, t.y))
        assertNull("片外的格子查不到", p.tileAt(p.x0 - 1, p.y0))
        // 每一格都落在这一片的包围盒里（切分没错位）
        assertTrue(
            "所有格子都在包围盒内",
            p.tiles.all { it.x in p.x0..p.x1 && it.y in p.y0..p.y1 },
        )
        // 一个坐标只有一格（四档实测重名 0）
        assertEquals(
            "同一片里坐标不重复",
            p.tiles.size,
            p.tiles.map { it.x to it.y }.toSet().size,
        )
    }

    @Test
    fun 地图的作物名查不到就不写() {
        val m = build("ff")
        val named = m.maps.flatMap { it.patches }.flatMap { it.tiles }.count { it.name != null }
        val unnamed = m.maps.flatMap { it.patches }.flatMap { it.tiles }.count { it.name == null }
        // 温室里 95 株远古水果查得到名字；空地（没有作物）没有名字
        assertTrue("温室的远古水果查得到名字", named > 0)
        assertTrue("空地没有名字（不编）", unnamed > 0)
        assertTrue(
            "有作物且查得到名字的格子都带名字",
            m.maps.flatMap { it.patches }.flatMap { it.tiles }
                .filter { it.state != TileState.EMPTY }
                .any { it.name != null },
        )
    }

    // ── v2.99.0 / c179 图鉴「你的状态」（CodexStateBuilder + statusLines）──

    // ⚠ **这一段的断言钉的是「折算规则」与「两个折算器之间的关系」，不是「某份存档的内容」**。
    //   原因：存档目录（`存档/_analysis_tmp`）是可选的，存档本身也会变 ——
    //   把「某档有 42 个某物」写成断言，换台机器 / 换个存档就红，而红的原因跟代码对不对无关。
    //   真正该钉的是两件事：
    //   ① **规则**：折算出来的东西自洽（可收 ≤ 在田、计数 ≥ 1、名字只能来自映射表…）；
    //   ② **同源**：图鉴里的数与今日页的数**相等**（同一个数在两处不许打架）——
    //      这正是本轮功能的核心，断言写成「A == B」而不是「A == 95」，既钉死口径又不吃存档内容。

    /**
     * 村民英文名 → 中文名，读 App 自己的 reportNames.json（与 ReportNames.npcTable 同一张）。
     *
     * 键**两种写法都收一份**：表里的键是 norm(名)（去空白→小写→下划线转空格→去
     * 「 (dangerous)」），而折算器查表用的是「去空白→小写」—— 两边都收下，
     * 测试就不必替某一边的实现细节做假设（表的内容仍只有 App 自己那一份）。
     */
    private val npcNames: Map<String, String> by lazy {
        val o = org.json.JSONObject(WorldSaveFixtures.assetFile("reportNames.json").readText())
        val npcs = o.getJSONObject("npcs")
        val out = LinkedHashMap<String, String>()
        npcs.keys().forEach { k ->
            val cn = npcs.getString(k)
            out[normNpc(k)] = cn
            out[k.trim().lowercase()] = cn
        }
        out
    }

    /** 与 ReportNames.norm 同款：去空白 → 小写 → 下划线转空格 → 去「 (dangerous)」后缀。 */
    private fun normNpc(s: String): String = s.trim().lowercase()
        .replace('_', ' ')
        .replace(" (dangerous)", "")
        .replace("(dangerous)", "")
        .trim()

    /** App 自己的村民中文名全集 —— 折算出的中文名只能出自这里（不编名字）。 */
    private val appNpcNames: Set<String> by lazy { npcNames.values.toSet() + giftNames }

    /** App 的村民中文名集合（读 assets 的 gifts.json；GameData 需要 Context，测试里直接读文件）。 */
    private val giftNames: Set<String> by lazy {
        val arr = org.json.JSONArray(WorldSaveFixtures.assetFile("gifts.json").readText())
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) out.add(arr.getJSONObject(i).getString("name"))
        out
    }

    /** 解析过的四档存档（懒加载，避免每个测试都重解析 35MB）。 */
    private val parsedStates: Map<String, WorldState> by lazy {
        val dir = WorldSaveFixtures.savesDir()
        if (!dir.isDirectory) emptyMap() else WorldSaveFixtures.parseAll()
    }

    /** 取一档的存档原貌。 */
    private fun stateOf(label: String): WorldState {
        val dir = WorldSaveFixtures.savesDir()
        assumeTrue("找不到存档目录 ${dir.absolutePath}", dir.isDirectory)
        return parsedStates[label] ?: error("没解析出 $label 档")
    }

    /** 折出图鉴状态（与 App 里同一条折算路径）。 */
    private fun buildCodex(label: String): CodexState =
        CodexStateBuilder.build(stateOf(label), map, npcNames)

    /** 折出今日页展示模型（与 App 里同一条折算路径）。 */
    private fun buildToday(label: String): FarmToday =
        FarmTodayBuilder.build(stateOf(label), map, prices, sourceLabel = label)

    /** 有存档的档名（有哪些就测哪些，缺档不算失败）。 */
    private val labels: List<String> get() = listOf("ff", "重逢", "穿越", "KKK").filter { it in parsedStates }

    /**
     * ① 折算**规则**：背包折成「App 中文名 → 件数」，原样背包绝不进模型。
     *
     * 钉的是规则（件数守恒 / 计数 ≥ 1 / 名字只能来自映射表），不是这份存档有多少件。
     */
    @Test
    fun 背包折算守恒且不编名字() {
        for (label in labels) {
            val st = stateOf(label)
            val m = buildCodex(label)
            assertTrue("$label 背包计数里不该出现 0 或负数", m.itemCounts.values.none { it <= 0 })
            // 不编名字：折算出来的中文名**只能**来自 worldItemMap（映射表里没有的名字不可能出现）
            val mapNames = (map.byId.values + map.byNameEn.values).toSet()
            for (k in m.itemCounts.keys) {
                assertTrue("$label 背包里的「$k」必须来自映射表（不编名字）", k in mapNames)
            }
            // 件数守恒：映射成功的 + 未识别的 == 房主背包里带 stack 的总件数（不重复不遗漏）
            val inv = st.inventories.firstOrNull { !it.farmhand }
            val stackTotal = inv?.items?.sumOf { it.stack ?: 0 } ?: 0
            assertEquals(
                "$label 背包件数守恒（映射成功 + 未识别 = 全部带 stack 的件数）",
                stackTotal,
                m.itemCounts.values.sum() + m.unmappedItems,
            )
            assertTrue("$label 未识别件数不该是负数", m.unmappedItems >= 0)
        }
    }

    /** ② 折算**规则**：田里状态自洽（可收 ≤ 在田、最快天数要么判不出要么为正）。 */
    @Test
    fun 田里状态自洽() {
        for (label in labels) {
            val m = buildCodex(label)
            for ((name, c) in m.crops) {
                assertTrue("$label「$name」今天能收的株数不该超过在田株数", c.ready <= c.total)
                assertTrue("$label「$name」枯死株数不该是负数", c.dead >= 0)
                assertTrue(
                    "$label「$name」最快能收天数要么判不出（null）要么为正",
                    c.minDays == null || c.minDays > 0,
                )
            }
        }
    }

    /**
     * ③ **同源**（本轮的核心）：图鉴「你的状态」里的数与今日页「我的农场」的数**相等**。
     *
     * 断言写成「A == B」而不是「A == 95」—— 换存档换机器照样成立，
     * 而一旦两处口径漂移（v2.27「判定口径散落多处必然漂移」那个病根）立刻红。
     *
     * ⚠ 两边**分组粒度不同**是事实（今日页按「地图 + 品种」分行，图鉴按品种合并），
     *   所以比之前先按同名求和 —— 求和之后才要求相等。
     */
    @Test
    fun 图鉴状态与今日页同源() {
        for (label in labels) {
            val m = buildCodex(label)
            val ft = buildToday(label)
            // 基础字段：同一个来源折出来的
            assertEquals("$label 现金两处一致", ft.money, m.money)
            assertEquals("$label 角色名两处一致", ft.farmer, m.farmer)
            assertEquals("$label 年份两处一致", ft.year, m.year)
            assertEquals("$label 季节两处一致", ft.season, m.season)
            assertEquals("$label 日期两处一致", ft.day, m.day)
            // 今天能收的株数：今日页按「地图 + 品种」分行（同一品种可能占好几行：
            // 温室 116 + 农场 8 = 124），图鉴按品种合并 ⇒ **先按同名求和再比**，
            // 别拿单行去比合并值（那不是口径打架，是分组粒度不同）。
            val readyByName = ft.harvest.groupBy { it.name }
                .mapValues { (_, rows) -> rows.sumOf { it.count } }
            for ((name, n) in readyByName) {
                val c = m.crops[name]
                assertTrue("$label 今日页能收的「$name」在图鉴状态里也该有", c != null)
                assertEquals("$label「$name」今天能收的株数两处一致", n, c?.ready)
            }
            // 反向：图鉴说「今天能收」，今日页也得有这一行（不能一边有另一边空）
            for ((name, c) in m.crops) {
                if (c.ready > 0) {
                    assertTrue(
                        "$label「$name」图鉴说能收 ${c.ready} 株，今日页也得有这一行",
                        name in readyByName,
                    )
                }
            }
            // 枯死株数：同样按同名求和再比
            val deadByName = ft.dead.groupBy { it.name }
                .mapValues { (_, rows) -> rows.sumOf { it.count } }
            for ((name, n) in deadByName) {
                assertEquals("$label「$name」枯死株数两处一致", n, m.crops[name]?.dead)
            }
            for ((name, c) in m.crops) {
                if (c.dead > 0) {
                    assertTrue(
                        "$label「$name」图鉴说有 ${c.dead} 株枯死，今日页也得有这一行",
                        name in deadByName,
                    )
                }
            }
        }
    }

    /** ④ 折算**规则**：村民好感——不漏、不多、不编中文名、数值不为负。 */
    @Test
    fun 村民好感不漏不编() {
        for (label in labels) {
            val st = stateOf(label)
            val m = buildCodex(label)
            // 折算规则：英文名 → App 中文名（查不到就保留英文原名，一个字都不多编）
            val expected = st.friendships
                .mapNotNull { f -> f.name?.trim()?.takeIf { it.isNotEmpty() } }
                .map { en -> npcNames[en.trim().lowercase()] ?: en }
                .toSet()
            // 不漏也不多：好感表的键集合 == 按同一规则折出来的键集合
            assertEquals("$label 好感表的键不多不少", expected, m.friendships.keys)
            // 不编中文名：带汉字的键必须出自 App 自己的村民名表（reportNames ∪ gifts）
            for (k in m.friendships.keys) {
                val isCn = k.any { it.code in 0x4E00..0x9FFF }
                assertTrue(
                    "$label 好感表里的「$k」要么是 App 村民名，要么保留英文原名（不编中文名）",
                    !isCn || k in appNpcNames,
                )
            }
            // 数值不为负；null 仍是 null（判不出不补 0）
            for ((k, v) in m.friendships) {
                assertTrue("$label「$k」好感点数不该是负数", (v.points ?: 0) >= 0)
                assertTrue("$label「$k」本周送礼次数不该是负数", (v.giftsThisWeek ?: 0) >= 0)
            }
        }
    }

    /**
     * ⑤ 折算**规则**：背包里映射不到的东西**只进未识别计数**，不冒充任何名字；
     * 且「没找到」这个结论只有在映射全成功时才成立（UI 靠 unmapped 决定措辞）。
     */
    @Test
    fun 未识别物品不冒充名字() {
        for (label in labels) {
            val st = stateOf(label)
            val m = buildCodex(label)
            val inv = st.inventories.firstOrNull { !it.farmhand }
            // 房主背包里映射不出的那几件，总数必须落在 unmappedItems 里
            // ⚠ 判据必须与 CodexStateBuilder **同一套**（同一个 qualifiedInt 剥前缀），
            //   否则这里算「映射不出」而折算器算「映射得出」，两边数字永远对不上。
            val unmappedNames = (inv?.items ?: emptyList())
                .filter { it.stack != null }
                .filter {
                    map.nameOfEn(it.name) == null &&
                        map.nameOfId(it.name?.toIntOrNull()) == null &&
                        map.nameOfId(qualifiedInt(it.itemId)) == null
                }
                .sumOf { it.stack ?: 0 }
            assertEquals(
                "$label 映射不出的件数 = 未识别计数",
                unmappedNames,
                m.unmappedItems,
            )
            // 未识别的东西绝不能顶着 App 的中文名进计数表
            val mapNames = (map.byId.values + map.byNameEn.values).toSet()
            for (name in m.itemCounts.keys) {
                assertTrue("$label 计数表里的「$name」是映射表里的真名", name in mapNames)
            }
        }
    }

    /** ⑥ 缓存往返：折好的模型原样存回来，null 不被兜底成 0。 */
    @Test
    fun codex状态能原样存回来() {
        val m = buildCodex(labels.firstOrNull() ?: return)
        val back = codexStateFromJson(m.toJson())
        assertTrue("往返后不为 null", back != null)
        assertEquals("往返后现金", m.money, back?.money)
        assertEquals("往返后背包计数", m.itemCounts, back?.itemCounts)
        assertEquals("往返后未识别数", m.unmappedItems, back?.unmappedItems)
        assertEquals("往返后田里状态", m.crops, back?.crops)
        assertEquals("往返后好感表", m.friendships, back?.friendships)
        // null 语义往返：minDays 判不出仍是 null，不是 0
        val someNullDays = m.crops.entries.firstOrNull { it.value.minDays == null }
        if (someNullDays != null) {
            assertNull(
                "minDays 判不出的那条往返后仍是 null",
                back?.crops?.get(someNullDays.key)?.minDays,
            )
        }
    }

    // ── statusLines：「🎒 你的状态」行的组装（纯函数，内存数据直接喂）────

    /** 造一个最小 CodexState。 */
    private fun st(
        counts: Map<String, Int> = emptyMap(),
        unmapped: Int = 0,
        crops: Map<String, CodexCropState> = emptyMap(),
        friends: Map<String, CodexNpcState> = emptyMap(),
        money: Int? = 500,
    ) = CodexState(
        farmer = "测试", year = 2, season = "秋", day = 10, money = money,
        itemCounts = counts, unmappedItems = unmapped, crops = crops, friendships = friends,
    )

    @Test
    fun 状态行_作物条目_库存田里与种子() {
        val r = com.stardew.assistant.ui.codex.codexStatusLines(
            com.stardew.assistant.ui.codex.CodexStatusInput(
                groupId = "crops", itemName = "防风草",
                state = st(
                    counts = mapOf("防风草" to 3),
                    crops = mapOf("防风草" to CodexCropState(64, 64, null, 0)),
                ),
                checks = emptySet(),
                snapYear = 2, snapSeason = 2, snapDay = 10,
                seedPrice = 20, rooms = emptyList(),
            ),
        )
        assertTrue("田里行", r.lines.contains("田里" to "64 株在种"))
        assertTrue("今天能收行", r.lines.contains("今天能收" to "64 株"))
        assertTrue("背包行", r.lines.contains("背包" to "3 个"))
        assertTrue("种子行（现金够买）", r.lines.contains("种子" to "20 g/包 · 现金 500 g，可买 25 包"))
        assertFalse("minDays 判不出 → 不显示最快能收行", r.lines.any { it.first == "最快能收" })
    }

    @Test
    fun 状态行_配方材料_够与不够与未识别() {
        val inp = { unmapped: Int, counts: Map<String, Int> ->
            com.stardew.assistant.ui.codex.codexStatusLines(
                com.stardew.assistant.ui.codex.CodexStatusInput(
                    groupId = "crafting", itemName = "宝箱",
                    state = st(counts = counts, unmapped = unmapped),
                    checks = emptySet(),
                    snapYear = 2, snapSeason = 2, snapDay = 10,
                    ingredients = listOf("木头" to 50, "石头" to 1),
                    rooms = emptyList(),
                ),
            )
        }
        val ok = inp(0, mapOf("木头" to 99, "石头" to 1))
        assertTrue("材料够", ok.lines.contains("材料 · 木头" to "背包 99 个，够（要 50 个）"))
        val lack = inp(0, mapOf("木头" to 30))
        assertTrue("材料差", lack.lines.contains("材料 · 木头" to "背包 30 个，还差 20（要 50 个）"))
        assertTrue(
            "没找到且映射全成功 → 可以下结论",
            lack.lines.contains("材料 · 石头" to "背包没有（需要 1 个）"),
        )
        val unknowable = inp(5, emptyMap())
        assertTrue(
            "有未识别物品 → 不下「没有」的结论",
            unknowable.lines.contains("材料 · 木头" to "50 个 · 背包里有未识别物品，以游戏内为准"),
        )
        assertEquals("尾注带件数", "背包里还有 5 件名字没对上的物品，相关数量以游戏内为准", unknowable.footnote)
    }

    @Test
    fun 状态行_村民_好感送礼生日() {
        val r = com.stardew.assistant.ui.codex.codexStatusLines(
            com.stardew.assistant.ui.codex.CodexStatusInput(
                groupId = "movies", itemName = "阿比盖尔",
                state = st(friends = mapOf("阿比盖尔" to CodexNpcState(1740, 2))),
                checks = emptySet(),
                snapYear = 2, snapSeason = 2, snapDay = 10,   // 秋 10
                isVillager = true, birthdaySeason = "秋", birthdayDay = 13,
                rooms = emptyList(),
            ),
        )
        assertTrue("好感行（1740/250=6 心）", r.lines.contains("好感" to "1740 点（约 6 心）"))
        assertTrue("本周送礼行", r.lines.contains("本周送礼" to "2 次（送了什么存档里没记，以游戏内为准）"))
        assertTrue("生日行（秋 10 → 秋 13 = 3 天）", r.lines.contains("下次生日" to "秋 13（还有 3 天）"))
        // 没见过面的村民：不下 0 的结论
        val stranger = com.stardew.assistant.ui.codex.codexStatusLines(
            com.stardew.assistant.ui.codex.CodexStatusInput(
                groupId = "movies", itemName = "雷欧",
                state = st(friends = emptyMap()),
                checks = emptySet(),
                snapYear = 2, snapSeason = 2, snapDay = 10,
                isVillager = true, birthdaySeason = "夏", birthdayDay = 26,
                rooms = emptyList(),
            ),
        )
        assertTrue("没见过面 → 还没有记录", stranger.lines.contains("好感" to "存档里还没有 TA 的记录"))
    }

    @Test
    fun 状态行_鱼与收集包_勾选全走单源() {
        // 图鉴进度：fishing 组的键由 codexCheckKey 单源构造（fish:）
        val fishChecks = setOf(codexCheckKey("fishing", "太阳鱼")!!)
        val r = com.stardew.assistant.ui.codex.codexStatusLines(
            com.stardew.assistant.ui.codex.CodexStatusInput(
                groupId = "fishing", itemName = "太阳鱼",
                state = st(),
                checks = fishChecks,
                snapYear = 2, snapSeason = 2, snapDay = 10,
                rooms = emptyList(),
            ),
        )
        assertTrue("进度已勾", r.lines.contains("图鉴进度" to "已勾"))
        // 未勾
        val r2 = com.stardew.assistant.ui.codex.codexStatusLines(
            com.stardew.assistant.ui.codex.CodexStatusInput(
                groupId = "fishing", itemName = "鲶鱼",
                state = st(), checks = fishChecks,
                snapYear = 2, snapSeason = 2, snapDay = 10,
                rooms = emptyList(),
            ),
        )
        assertTrue("进度未勾", r2.lines.contains("图鉴进度" to "未勾"))
        // 收集包：内存造两个房间，防风草只在一个包里
        val rooms = listOf(
            BundleRoom("储物间（茶水间）", listOf(NamedItems("春季作物", listOf(ItemCount("防风草", 1), ItemCount("土豆", 1))))),
            BundleRoom("电影院（废弃 Joja 超市改建）", listOf(NamedItems("失踪的收集包", listOf(ItemCount("银星果酒", 1))))),
        )
        val checked = setOf(bundleCheckKey(0, 0, "防风草"))
        val r3 = com.stardew.assistant.ui.codex.codexStatusLines(
            com.stardew.assistant.ui.codex.CodexStatusInput(
                groupId = "crops", itemName = "防风草",
                state = st(), checks = checked,
                snapYear = 2, snapSeason = 2, snapDay = 10,
                rooms = rooms,
            ),
        )
        assertTrue(
            "收集包行标已提交",
            r3.lines.contains("收集包" to "储物间（茶水间）·春季作物：已提交"),
        )
        val r4 = com.stardew.assistant.ui.codex.codexStatusLines(
            com.stardew.assistant.ui.codex.CodexStatusInput(
                groupId = "crops", itemName = "土豆",
                state = st(), checks = checked,
                snapYear = 2, snapSeason = 2, snapDay = 10,
                rooms = rooms,
            ),
        )
        assertTrue(
            "收集包行标未提交",
            r4.lines.contains("收集包" to "储物间（茶水间）·春季作物：未提交"),
        )
    }

    @Test
    fun 状态行_枯死品种不报田里零株() {
        val r = com.stardew.assistant.ui.codex.codexStatusLines(
            com.stardew.assistant.ui.codex.CodexStatusInput(
                groupId = "crops", itemName = "大蒜",
                state = st(crops = mapOf("大蒜" to CodexCropState(0, 0, null, 41))),
                checks = emptySet(),
                snapYear = 2, snapSeason = 2, snapDay = 10,
                rooms = emptyList(),
            ),
        )
        assertFalse("不显示「田里 0 株在种」", r.lines.any { it.first == "田里" })
        assertTrue("只报枯死", r.lines.contains("枯死待清理" to "41 株"))
    }
}
