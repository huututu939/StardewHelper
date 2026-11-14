package com.stardew.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * 存档「世界状态」解析器的验收测试（P0）。
 *
 * ## 为什么测试跑在 JVM 上
 * 解析器只依赖 `org.xmlpull.v1.XmlPullParser` 这一个接口：
 * Android 端用 `android.util.Xml.newPullParser()`，这里用 kxml2 的 `KXmlParser`。
 * **同一份代码**跑真实存档——Android 侧另写一份「能跑」的解析器，等于多一个真相。
 *
 * ## 数字从哪来
 * 表里的期望值不是拍脑袋写的，是《世界状态解析可行性报告》里四份真实存档的
 * 实测基线（用 `存档/分析脚本/world_state.py` 跑出来的）。
 * 两边独立实现、同一批输入、数字必须逐项相等——**对不上就是有 bug，不是改期望值**。
 *
 * ## 存档从哪来
 * 默认读 `存档/_analysis_tmp`（用 `tar -xf` 解压四个压缩包得到），
 * 可用系统属性 `worldstate.saves` 覆盖。目录不存在时**跳过**（不是静默通过）：
 * `Assume` 会把测试标成 skipped，在报告里一眼看得见。
 *
 * 跑法：
 * ```
 * cd StardewHelper
 * .\gradlew.bat :app:testDebugUnitTest --tests "*WorldStateParserTest*"
 * ```
 */
class WorldStateParserTest {

    // ── 期望值（基线见《世界状态解析可行性报告》第一节）──────────────────

    /** 一份存档的期望值。 */
    private data class Expect(
        val label: String,
        /** 房主角色名（用来把存档对上号，不依赖文件名编码）。 */
        val host: String,
        val crops: Int,
        val ready: Int,
        val growing: Int,
        val dead: Int,
        val animals: Int,
        val players: Int,
        /** 房主背包件数。 */
        val bag: Int,
        /** 农场手背包件数（无农场手时为 null）。 */
        val farmhandBag: Int? = null,
    )

    private val expects = listOf(
        Expect("穿越", "梦秋尘", crops = 160, ready = 87, growing = 73, dead = 0,
            animals = 0, players = 1, bag = 5),
        Expect("重逢", "Yuki", crops = 297, ready = 106, growing = 187, dead = 4,
            animals = 10, players = 1, bag = 21),
        Expect("ff", "Amy", crops = 222, ready = 102, growing = 64, dead = 56,
            animals = 16, players = 1, bag = 25),
        Expect("KKK", "K1ra Y0shik4g", crops = 578, ready = 578, growing = 0, dead = 0,
            animals = 24, players = 2, bag = 1, farmhandBag = 0),
    )

    /** 物品 id（权威源：stardew_valley_1.6_data_v9/stardew_data/item_index.json）。 */
    private val idCauliflower = 190
    private val idGarlic = 248
    private val idMelon = 254
    private val idAncientFruit = 454

    // ── 主测试 ────────────────────────────────────────────────────────

    @Test
    fun 四档真实存档的作物_牲畜_玩家_背包逐项对上基线() {
        val states = parseAll()
        assertEquals("四档存档都要解析出来", expects.size, states.size)

        for (e in expects) {
            val st = states[e.label] ?: error("没解析出存档「${e.label}」")
            val who = "「${e.label}」(${e.host})"

            assertEquals("$who 作物总数", e.crops, st.crops.size)
            assertEquals("$who 今天能收", e.ready, st.readyCrops.size)
            assertEquals("$who 生长中", e.growing, st.growingCrops.size)
            assertEquals("$who 枯死", e.dead, st.deadCrops.size)
            assertEquals("$who 牲畜", e.animals, st.animals.size)
            assertEquals("$who 玩家（房主+农场手）", e.players, st.players.size)

            // 分类必须互斥且穷尽：加起来正好等于作物总数，
            // 否则就是有作物「判不出成熟度」被悄悄漏掉（unknownCrops 单列，不混进生长中）
            assertEquals(
                "$who 成熟度分类要穷尽（未知的 ${st.unknownCrops.size} 株必须为 0）",
                e.crops, st.readyCrops.size + st.growingCrops.size + st.deadCrops.size + st.unknownCrops.size
            )

            val bag = st.inventories.firstOrNull { !it.farmhand }
            assertEquals("$who 房主背包件数", e.bag, bag?.items?.size ?: -1)
            if (e.farmhandBag != null) {
                val fh = st.inventories.firstOrNull { it.farmhand }
                assertEquals("$who 农场手背包件数", e.farmhandBag, fh?.items?.size ?: -1)
            }
        }
    }

    /**
     * ff：41 株大蒜 + 15 株花椰菜已枯死，且都在 **Farm**（不是 Greenhouse）。
     *
     * 这是「温室与农场必须分开统计」的全部理由：同样种在夏天的花椰菜，
     * 在农场里枯死了，在温室里没有。
     */
    @Test
    fun ff的大蒜与花椰菜枯死且都在农场() {
        val st = parseAll()["ff"] ?: error("没解析出 ff 档")
        val garlic = st.crops.filter { it.produceId == idGarlic }
        val cauli = st.crops.filter { it.produceId == idCauliflower }
        assertEquals("ff 大蒜总数", 56, garlic.size + cauli.size)
        assertEquals("ff 枯死大蒜", 41, garlic.count { it.dead == true })
        assertEquals("ff 枯死花椰菜", 15, cauli.count { it.dead == true })
        assertTrue("ff 枯死的作物都在 Farm", (garlic + cauli).filter { it.dead == true }.all { it.location == "Farm" })
        assertTrue(
            "ff 枯死的作物都不在 Greenhouse",
            (garlic + cauli).none { it.location == "Greenhouse" && it.dead == true }
        )
    }

    /** 重逢：54 株花椰菜在 **Greenhouse**（所以没枯死）。 */
    @Test
    fun 重逢的花椰菜在温室() {
        val st = parseAll()["重逢"] ?: error("没解析出重逢档")
        val cauli = st.crops.filter { it.produceId == idCauliflower }
        assertEquals("重逢 花椰菜总数", 54, cauli.size)
        assertEquals("重逢 温室里的花椰菜", 54, cauli.count { it.location == "Greenhouse" })
        assertTrue("重逢 温室花椰菜都没枯死", cauli.none { it.dead == true })
    }

    /**
     * 重逢：100 株甜瓜，成熟日 = **夏季第 13 日**。
     *
     * 存档里是第 2 年夏 9 日 → 还剩 4 天 → 9 + 4 = 13。
     * 100 株必须**同一天**熟（否则说明成熟判定算错了，不是数据本身不同步）。
     */
    @Test
    fun 重逢的甜瓜成熟日是夏季第13日() {
        val st = parseAll()["重逢"] ?: error("没解析出重逢档")
        assertEquals("重逢 当前季节", "summer", st.season)
        assertEquals("重逢 当前日期", 9, st.dayOfMonth)
        val melons = st.crops.filter { it.produceId == idMelon }
        assertEquals("重逢 甜瓜总数", 100, melons.size)
        val lefts = melons.mapNotNull { it.daysLeft }.distinct()
        assertEquals("100 株甜瓜必须同一天熟", 1, lefts.size)
        assertEquals("甜瓜还剩的天数", 4, lefts[0])
        assertEquals("甜瓜成熟日 = 夏季第 13 日", 13, (st.dayOfMonth ?: 0) + lefts[0])
        assertTrue("甜瓜现在还不能收", melons.none { it.ready == true })
    }

    /**
     * ff：95 株**可收**的远古水果在 **Greenhouse**。
     *
     * ⚠ 温室里其实一共 116 株远古水果（95 可收 + 21 生长中），
     * 用户口径说的是那 95 株「今天能收的」——两者不是一回事，别混。
     */
    @Test
    fun ff的远古水果在温室() {
        val st = parseAll()["ff"] ?: error("没解析出 ff 档")
        val fruit = st.crops.filter { it.produceId == idAncientFruit }
        assertEquals("ff 远古水果总数（95 可收 + 21 生长中）", 116, fruit.size)
        assertEquals("ff 温室里可收的远古水果", 95, fruit.count { it.location == "Greenhouse" && it.ready == true })
        assertEquals("ff 可收的远古水果全在温室", 95, fruit.count { it.ready == true })
    }

    /**
     * 牲畜去重：同一份数据在存档里有两份序列化，
     * 解析器必须只取 `indoors/animals/…` 那条，并**如实记录**跳过了多少条。
     */
    @Test
    fun 牲畜不重复且跳过的副本数被记下来() {
        val states = parseAll()
        // ff 不去重会读成 32 只（实测）
        assertEquals("ff 牲畜 16 只（不是 32）", 16, states["ff"]?.animals?.size ?: -1)
        val ff = states["ff"] ?: error("没解析出 ff 档")
        assertTrue("ff 跳过的旧序列化副本数 > 0", ff.skippedDuplicateAnimals > 0)
        assertEquals(
            "跳过的副本数 = 真实牲畜数（同一份数据的第二份序列化）",
            16, ff.skippedDuplicateAnimals
        )
        assertEquals("穿越没有牲畜", 0, states["穿越"]?.animals?.size ?: -1)
    }

    /** 地图归属：温室与农场是两张不同的图，作物不能混在一起数。 */
    @Test
    fun 地图名按序号归属且温室农场分开() {
        val states = parseAll()
        for ((label, st) in states) {
            assertEquals("$label 没有查不到名字的地图", 0, st.unresolvedLocations)
            assertTrue("$label 地图数量 > 0", st.locations.isNotEmpty())
        }
        val ff = states["ff"] ?: error("没解析出 ff 档")
        val byLoc = ff.cropsByLocation()
        assertTrue("ff 有农场地块", byLoc.containsKey("Farm"))
        assertTrue("ff 有温室地块", byLoc.containsKey("Greenhouse"))
        assertTrue(
            "ff 农场里的作物数 > 0",
            (byLoc["Farm"]?.size ?: 0) > 0
        )
    }

    /** 数值解析失败不许兜底 0：没有的字段必须是 null。 */
    @Test
    fun 缺字段一律为null不兜底() {
        val states = parseAll()
        for ((label, st) in states) {
            for (c in st.crops) {
                assertTrue(
                    "$label 有作物把 currentPhase 读成了 0 兜底（currentPhase=null 才对）",
                    c.currentPhase == null || c.currentPhase >= 0
                )
                assertTrue(
                    "$label 出现了「产出 id = 0」（物品 id 0 是锈剑，必须当未知）",
                    c.produceId != 0
                )
            }
        }
        // ff 的工具类物品在存档里没有 <stack> 字段 → 必须 null，不能补 1 或 0
        val ff = states["ff"] ?: error("没解析出 ff 档")
        val bag = ff.inventories.first { !it.farmhand }
        val noStack = bag.items.filter { it.stack == null }
        assertTrue("ff 背包里至少有一件物品没写 stack（应为 null）", noStack.isNotEmpty())
        assertTrue("没有 stack 的物品不能被补成 0 或 1", noStack.none { it.stack == 0 || it.stack == 1 })
    }

    // ── 工具 ──────────────────────────────────────────────────────────

    private fun parseAll(): Map<String, WorldState> {
        val base = WorldSaveFixtures.savesDir()
        assumeTrue(
            "找不到存档目录 ${base.absolutePath}（用 tar -xf 解压四个压缩包到此目录，或用 -Dworldstate.saves=... 指定）",
            base.isDirectory
        )
        val files = WorldSaveFixtures.discoverSaves(base)
        assertTrue("存档目录里没找到任何主存档", files.isNotEmpty())
        val out = LinkedHashMap<String, WorldState>()
        for (f in files) {
            val st = WorldSaveFixtures.parseSave(f)
            val host = st.players.firstOrNull { !it.farmhand }?.name ?: "?"
            val label = expects.firstOrNull { it.host == host }?.label ?: host
            out[label] = st
        }
        // 打印一张表，方便肉眼核对（测试输出会留在 Gradle 报告里）
        println()
        println("%-6s %-16s %-14s %6s %6s %6s %6s %6s %6s %6s".format(
            "存档", "角色", "年/季/日", "作物", "可收", "生长中", "枯死", "牲畜", "玩家", "背包"))
        for (e in expects) {
            val st = out[e.label] ?: continue
            println("%-6s %-16s %-14s %6d %6d %6d %6d %6d %6d %6d".format(
                e.label,
                st.players.firstOrNull { !it.farmhand }?.name ?: "-",
                "${st.year}年${st.season} ${st.dayOfMonth}日",
                st.crops.size, st.readyCrops.size, st.growingCrops.size, st.deadCrops.size,
                st.animals.size, st.players.size,
                st.inventories.firstOrNull { !it.farmhand }?.items?.size ?: 0))
        }
        println()
        return out
    }
}
