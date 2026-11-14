package com.stardew.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * P1 验收：中文名 + 售价映射层。
 *
 * ## 为什么必须是「真存档」验收
 * 映射表写错了，编译能过、JSON 也能解析，界面照样显示「190 ×54」或「锈剑」。
 * 只有拿四份**真实玩家存档**跑一遍，才知道 byId / byNameEn / animalType 三张表
 * 到底覆没覆盖住实际会遇到的形态（数字 id、MOD 英文名、无 id 的野生采集物、
 * mod 品种牲畜……）。
 *
 * 跑法：`gradlew :app:worldEconomyTest`
 */
class WorldEconomyTest {

    private val map: WorldItemMap by lazy { WorldSaveFixtures.loadWorldItemMap() }
    private val prices: Map<String, Int> by lazy { WorldSaveFixtures.loadSellPrices() }

    private fun states(): Map<String, WorldState> {
        val dir = WorldSaveFixtures.savesDir()
        assumeTrue(
            "找不到存档目录 ${dir.absolutePath}（先解压四份存档，或用 -Dworldstate.saves=... 指定）",
            dir.isDirectory
        )
        return WorldSaveFixtures.parseAll()
    }

    /** 某档里映射到某个中文名的作物数。 */
    private fun WorldState.cropsNamed(name: String): List<WorldCrop> =
        crops.filter { map.cropName(it) == name }

    // ── A. 名称映射 ────────────────────────────────────────────────────

    @Test
    fun 作物产出能映射到中文名() {
        val st = states()

        // 穿越：64 株防风草（数字 id 24）
        val chuan = st["穿越"] ?: error("没解析出穿越档")
        assertEquals("穿越 防风草", 64, chuan.cropsNamed("防风草").size)
        // 穿越：19 株「indexOfHarvest 写的是名字 Carrot」的胡萝卜（名字形态）
        val carrots = chuan.crops.filter { it.produceName == "Carrot" }
        assertEquals("穿越 名字形态的胡萝卜株数", 19, carrots.size)
        assertTrue("穿越的胡萝卜全部映射成「胡萝卜」", carrots.all { map.cropName(it) == "胡萝卜" })
        assertEquals("穿越 胡萝卜", 19, chuan.cropsNamed("胡萝卜").size)

        // 重逢：54 株花椰菜（在温室）
        val chong = st["重逢"] ?: error("没解析出重逢档")
        assertEquals("重逢 花椰菜", 54, chong.cropsNamed("花椰菜").size)
        // ff：95 株可收的远古水果 + 41 株枯死大蒜
        val ff = st["ff"] ?: error("没解析出 ff 档")
        assertEquals("ff 远古水果", 95, ff.readyCrops.filter { map.cropName(it) == "远古水果" }.size)
        assertEquals("ff 枯死大蒜", 41, ff.deadCrops.filter { map.cropName(it) == "大蒜" }.size)
    }

    @Test
    fun 牲畜能映射到中文名且未知品种回退英文() {
        val st = states()

        // ff：**1 只猪** → 「猪」
        // ⚠ 提示词写的是「ff 4 只猪」，实测 ff 的 4 只是**山羊**（Goat ×4），猪只有 1 只。
        //   这里按存档实测值断言（提示词那个数是笔误，数字要跟数据走）。
        val ff = st["ff"] ?: error("没解析出 ff 档")
        val pigs = ff.animals.filter { map.animalName(it.type) == "猪" }
        assertEquals("ff 猪（实测 1 只，提示词的「4 只」是山羊）", 1, pigs.size)
        assertTrue("ff 的猪的 type 确实是 Pig", pigs.all { it.type == "Pig" })
        assertEquals("ff 山羊 4 只", 4, ff.animals.count { map.animalName(it.type) == "山羊" })

        // 重逢：10 只牲畜全部有中文名
        val chong = st["重逢"] ?: error("没解析出重逢档")
        val unmapped = chong.animals.filter { map.animalName(it.type) == null }
        assertEquals("重逢 10 只牲畜全部有中文名（未映射：${unmapped.map { it.type }}）", 0, unmapped.size)
        assertEquals("重逢 牲畜总数", 10, chong.animals.size)

        // mod 品种：Dinosaur 在 App 手册里有「恐龙」→ 映射成中文名
        val dinos = ff.animals.filter { it.type == "Dinosaur" }
        assertEquals("ff 恐龙", 1, dinos.size)
        assertEquals("Dinosaur → 恐龙", "恐龙", map.animalName("Dinosaur"))

        // App 没有的品种 → **回退显示英文原名**（不崩、不空、不瞎猜）
        assertNull("App 没有的品种查不到名字（运行期回退英文）", map.animalName("Void Cow"))
        assertNull("查不到名字时不能瞎猜成别的", map.animalName("Magmarmer"))
    }

    // ── B. 产值 ────────────────────────────────────────────────────────

    @Test
    fun 今日可收总额对得上() {
        val st = states()
        // 前提：普通品质「无」= 1.0、不算农耕人加成
        val q = 1.0

        // ff：远古水果 95 × 550 = 52,250
        val ff = st["ff"] ?: error("没解析出 ff 档")
        val ffGroups = WorldEconomy.worldHarvestGroups(ff.readyCrops, map, { prices[it] }, null, null, q)
        assertEquals("ff 今日可收总额", 52250, WorldEconomy.worldHarvestTotal(ffGroups))
        val ffFruit = ffGroups.firstOrNull { it.name == "远古水果" }
        assertEquals("ff 远古水果株数", 95, ffFruit?.count)
        assertEquals("ff 远古水果单株价", 550, ffFruit?.unitPrice)

        // 重逢：提示词列的 5 组 = 12,950（花椰菜/辣椒/向日葵/草莓/远古水果）
        val chong = st["重逢"] ?: error("没解析出重逢档")
        val groups = WorldEconomy.worldHarvestGroups(chong.readyCrops, map, { prices[it] }, null, null, q)
        val five = listOf("花椰菜", "辣椒", "向日葵", "草莓", "远古水果")
        val fiveTotal = groups.filter { it.name in five }.sumOf { it.total }
        assertEquals("重逢 提示词那 5 组的产值", 12950, fiveTotal)
        assertEquals("重逢 花椰菜 54×175", 9450, groups.first { it.name == "花椰菜" }.total)
        assertEquals("重逢 辣椒 15×40", 600, groups.first { it.name == "辣椒" }.total)
        assertEquals("重逢 向日葵 9×80", 720, groups.first { it.name == "向日葵" }.total)
        assertEquals("重逢 草莓 9×120", 1080, groups.first { it.name == "草莓" }.total)
        assertEquals("重逢 远古水果 2×550", 1100, groups.first { it.name == "远古水果" }.total)

        // ⚠ 重逢的**全量**比 12,950 多 315：7 株夏南瓜（7×45）。
        //   提示词前提 3 明确要求「4 条无 item_id 的作物必须能经英文名路径命中」，
        //   夏南瓜正是其中一条（上游 name_en = Summer Squash，无 item_id），
        //   所以它**必须**被映射上 → 全量口径就是 12,950 + 315 = 13,265。
        assertEquals("重逢 全量今日可收总额（含 7 株夏南瓜 315g）", 13265, WorldEconomy.worldHarvestTotal(groups))
        val squash = groups.firstOrNull { it.name == "金皮西葫芦（夏南瓜）" }
        assertEquals("重逢 夏南瓜株数", 7, squash?.count)
        assertEquals("重逢 夏南瓜单株价", 45, squash?.unitPrice)
    }

    @Test
    fun 查不到售价的不进总额() {
        // ⚠ 真实存档里「有名字但查不到售价」的组，恰好都落在**生长中**（不产产值），
        //   所以这里用构造出来的组来验规则本身，不拿存档凑场景。
        val groups = listOf(
            WorldEconomy.WorldHarvestGroup("Farm", "有售价的作物", 2, 100, 200),
            WorldEconomy.WorldHarvestGroup("Farm", "查不到售价的作物", 10, null, 0),
        )
        assertEquals("查不到售价的不计入总额", 200, WorldEconomy.worldHarvestTotal(groups))
        assertTrue("查不到售价的组不算 priced", !groups[1].priced)
        assertEquals("查不到售价的组合计恒为 0", 0, groups[1].total)
        // 四档存档里，**未映射**的产物是整株丢弃的（不显示半截名字，也不进清单）
        val st = states()
        val ff = st["ff"] ?: error("没解析出 ff 档")
        val ffGroups = WorldEconomy.worldHarvestGroups(ff.readyCrops, map, { prices[it] }, null, null, 1.0)
        assertEquals(
            "ff 可收 102 株里只有远古水果有名字（7 株 id=2 的 mod 产物整株丢弃）",
            setOf("远古水果"), ffGroups.map { it.name }.toSet()
        )
    }

    // ── C. 覆盖率 ──────────────────────────────────────────────────────

    @Test
    fun 映射覆盖率与未命中清单() {
        val st = states()
        println()
        println("%-6s %8s %8s %8s   %s".format("存档", "作物", "已映射", "覆盖率", "未命中的产出键"))
        var totalCrops = 0
        var totalMapped = 0
        for (label in listOf("穿越", "重逢", "ff", "KKK")) {
            val s = st[label] ?: error("没解析出 $label 档")
            val crops = s.crops
            val mapped = crops.count { map.cropName(it) != null }
            val miss = crops.filter { map.cropName(it) == null }
                .groupBy { it.produceId?.toString() ?: "(无 id)" }
                .map { (k, v) -> "$k×${v.size}" }
                .sorted()
            totalCrops += crops.size
            totalMapped += mapped
            println("%-6s %8d %8d %7.1f%%   %s".format(
                label, crops.size, mapped, mapped * 100.0 / crops.size, miss.joinToString(" ")))
        }
        println("%-6s %8d %8d %7.1f%%".format(
            "合计", totalCrops, totalMapped, totalMapped * 100.0 / totalCrops))
        println()

        // 覆盖率逐档钉死（数字来自实测，改表就会红）。
        // c228 重钉 KKK 三项：玩家 10-16 更新过 KKK 存档 + c205 的 worldItemMap
        // 认全了纤维（id=771 出未命中表），实测 427/578（73.9%）、未命中 151 株。
        assertEquals("穿越 已映射 138 / 160（86.3%）", 138, st["穿越"]!!.crops.count { map.cropName(it) != null })
        assertEquals("重逢 已映射 287 / 297（96.6%）", 287, st["重逢"]!!.crops.count { map.cropName(it) != null })
        assertEquals("ff 已映射 215 / 222（96.8%）", 215, st["ff"]!!.crops.count { map.cropName(it) != null })
        assertEquals("KKK 已映射 427 / 578（73.9%）", 427, st["KKK"]!!.crops.count { map.cropName(it) != null })
        assertEquals("四档合计 已映射 1067 / 1257（84.9%）", 1067, totalMapped)
        assertEquals("四档合计 作物 1257 株", 1257, totalCrops)

        // 关键：未命中的必须是「查不到对应物」的那些，不许有「明明有却没映射」的
        assertEquals("穿越 只有 id=1/2 的采集物查不到（22 株）", 22,
            st["穿越"]!!.crops.count { map.cropName(it) == null })
        assertEquals("重逢 只有无 id 的野生采集物查不到（10 株）", 10,
            st["重逢"]!!.crops.count { map.cropName(it) == null })
        assertEquals("ff 只有 id=2 的 mod 产物查不到（7 株）", 7,
            st["ff"]!!.crops.count { map.cropName(it) == null })
        // KKK：id=1/2 的采集物 7 株（1×2 + 2×5）+ id=889 的 mod 果实 144 株 = 151 株
        //      （id=771 纤维 c205 起可映射，退出未命中清单）
        assertEquals("KKK 查不到的有 151 株", 151,
            st["KKK"]!!.crops.count { map.cropName(it) == null })
        assertEquals("KKK 未命中的产出键集合", setOf(1, 2, 889),
            st["KKK"]!!.crops.filter { map.cropName(it) == null }.map { it.produceId }.toSet())
    }

    // ── D. 与现有计算页的售价口径等值 ──────────────────────────────────

    @Test
    fun worldSell与现有计算页口径等值() {
        // Logic.kt 的 effectiveSell 是 private 且被现有计算页依赖 → **一个字都不改**，
        // 这里用反射把它调出来逐项对账。
        val m = Calc::class.java.getDeclaredMethod(
            "effectiveSell", Crop::class.java, String::class.java, Double::class.java
        )
        m.isAccessible = true
        val profs = listOf(null, "农耕人", "农业学家", "工匠")
        val quals = listOf(1.0, 1.1, 1.25, 1.5, 2.0)
        var n = 0
        for (price in listOf(20, 35, 60, 80, 175, 250, 550, 750, 3000)) {
            for (prof in profs) {
                for (q in quals) {
                    val crop = Crop(
                        name = "测试作物", seedPrice = 10, sellPrice = price, growth = 4,
                        regrow = null, seasons = listOf("春"), channels = "", note = "",
                        giant = false, trellis = false,
                    )
                    val expected = m.invoke(Calc, crop, prof, q) as Int
                    assertEquals(
                        "worldSell(售价=$price, 职业=$prof, 品质=$q) 要与现有计算页一致",
                        expected,
                        WorldEconomy.worldSell(price, prof, q)
                    )
                    n++
                }
            }
        }
        assertTrue("对账样本数够（实得 $n）", n >= 100)
        // 顺手钉住两个具体值
        assertEquals("普通品质原价", 175, WorldEconomy.worldSell(175, null, 1.0))
        assertEquals("农耕人 ×1.1（取整）", 192, WorldEconomy.worldSell(175, "农耕人", 1.0))
        assertEquals("铱星 ×2.0", 350, WorldEconomy.worldSell(175, null, 2.0))
    }

    @Test
    fun 单株产量区间按公式且缺字段不补数() {
        // minHarvest .. (maxHarvest + maxHarvestIncreasePerFarmingLevel × 耕种等级)
        assertEquals(
            "产量区间",
            1..4,
            WorldEconomy.worldYieldRange(1, 2, 1, 2)
        )
        // 缺字段 → null（**不补 1、不补 0**）：「算不出来」和「就是 1」是两件事
        assertNull("缺 minHarvest → null", WorldEconomy.worldYieldRange(null, 2, 1, 2))
        assertNull("缺 maxHarvest → null", WorldEconomy.worldYieldRange(1, null, 1, 2))
        assertNull("缺耕种等级 → null", WorldEconomy.worldYieldRange(1, 2, 1, null))
        // 加成字段缺失 → 按「没有加成」算（0 是这里唯一站得住的读法）
        assertEquals("缺加成字段 → 不加", 1..2, WorldEconomy.worldYieldRange(1, 2, null, 2))
    }
}
