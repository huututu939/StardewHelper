package com.stardew.assistant.ui.codex

import com.stardew.assistant.data.CodexGroup
import com.stardew.assistant.data.CodexItem
import com.stardew.assistant.data.FishSpotTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * c218：图鉴详情抽屉**脱筛选语境**修复的验收测试。
 *
 * ## 这个 bug 是什么
 * 详情抽屉的翻页原来直接用 `group.items`（整组几十~上百条），**绕过筛选/搜索**。
 * 用户在「鱼塘」里筛「珊瑚」看到 8 条，点开一条，抽屉里左右滑却会滑到整个
 * 鱼塘分类（上百条）的无关条目，页码也按整组算 —— 用户明明只看筛选结果，
 * 翻页却在全部条目里翻。
 *
 * ## 这里钉什么
 * [filterItems] 是**网格与抽屉共用的唯一筛选出口**：
 * · 输入「当前分类 + 全套筛选条件」，输出「实际应该显示的条目列表」。
 * · 抽屉从这个函数的结果里翻页 ⇒ 筛出几条，就往那几条里翻，不再脱语境。
 *
 * ⚠ 只用**合成组**测筛选逻辑（不依赖真实图鉴数据）——筛选是纯逻辑，
 *   与内容无关；真数据那条由 verify 静态断言守着。
 */
class CodexFilterTest {

    private fun group(vararg names: String, id: String = "test"): CodexGroup =
        CodexGroup(id = id, title = "测试", items = names.map { CodexItem(name = it) })

    private fun call(
        g: CodexGroup,
        query: String = "",
        checkFilter: Int = 0,
        checks: Set<String> = emptySet(),
        season: Int = 0,
        spot: String = "",
    ): List<CodexItem> = filterItems(
        g, query, checkFilter, checks,
        fishSeasonFilter = season, fishLocFilter = 0, fishTypeFilter = 0,
        fishWeatherFilter = 0, fishDifficultyFilter = 0, fishSpotFilter = spot,
        fishTable = FishSpotTable(spots = emptyList(), byFish = emptyMap()),
    )

    @Test
    fun 无筛选时返回整组() {
        val g = group("甲", "乙", "丙")
        val r = call(g)
        assertEquals("不筛选 ⇒ 整组原样", listOf("甲", "乙", "丙"), r.map { it.name })
    }

    @Test
    fun 搜索框只留命中的条目() {
        val g = group("防风草", "土豆", "防风草种子")
        val r = call(g, query = "防风草")
        assertEquals("搜「防风草」只留两条", listOf("防风草", "防风草种子"), r.map { it.name })
    }

    @Test
    fun 搜索大小写不敏感() {
        val g = group("Aberdeen", "Abilene", "Zebra")
        val r = call(g, query = "ab")
        assertEquals("小写 ab 也命中大写 AB 开头", listOf("Aberdeen", "Abilene"), r.map { it.name })
    }

    @Test
    fun 勾选筛选未勾时返回整组() {
        val g = group("甲", "乙", "丙", id = "crops")
        val r = call(g, checkFilter = 1)
        assertEquals("未勾筛选 + 全未勾 ⇒ 整组", 3, r.size)
    }

    @Test
    fun 勾选筛选已勾时只留已勾() {
        // crops 的勾选键前缀是 codex:crops（见 CHECK_PREFIX）
        val g = group("甲", "乙", "丙", id = "crops")
        val r = call(g, checkFilter = 2, checks = setOf("codex:crops:丙"))
        assertEquals("已勾筛选 ⇒ 只留丙", listOf("丙"), r.map { it.name })
    }

    @Test
    fun 序列不变式_命中条目保持整组顺序() {
        val g = group("a", "search1", "b", "search2", "c")
        val r = call(g, query = "search")
        assertEquals("命中条目顺序与整组一致", listOf("search1", "search2"), r.map { it.name })
        // 抽屉翻页按 r 的下标 → 整组下标映射：mapping 必须与整组顺序一致
        val wholeIdx = r.map { i -> g.items.indexOfFirst { it.name == i.name } }
        assertTrue("映射下标仍按整组递增（不重排）", wholeIdx.sorted() == wholeIdx)
    }

    // ── c221：加工组同名成品不折叠（蛋黄酱×2、铜锭×2…）──
    @Test
    fun 同组同名条目各自保留下标_不折叠() {
        // 「加工」类组里同名成品：蛋黄酱 ×2（鸡蛋、鸵鸟蛋），必须都进 pager
        val g = group("啤酒", "蛋黄酱", "奶酪", "蛋黄酱", id = "processing")
        val idx = filterItemIndices(
            g, "", 0, emptySet(),
            fishSeasonFilter = 0, fishLocFilter = 0, fishTypeFilter = 0,
            fishWeatherFilter = 0, fishDifficultyFilter = 0, fishSpotFilter = "",
            fishTable = FishSpotTable(spots = emptyList(), byFish = emptyMap()),
        )
        // 整组 4 条全过筛，下标 0,1,2,3 —— 蛋黄酱出现两次（下标 1 和 3）都不丢
        assertEquals("全过筛：4 个下标（蛋黄酱×2 不折叠）", listOf(0, 1, 2, 3), idx)
    }

    @Test
    fun 只筛出一条同名时仍保留下标() {
        // 番茄酱也只有一条，但整组处理方式保持一致：按名字集合过滤保留真实下标
        val g = group("番茄酱", "番茄酱", "洋葱", id = "processing")
        val idx = filterItemIndices(
            g, "番茄酱", 0, emptySet(),
            fishSeasonFilter = 0, fishLocFilter = 0, fishTypeFilter = 0,
            fishWeatherFilter = 0, fishDifficultyFilter = 0, fishSpotFilter = "",
            fishTable = FishSpotTable(spots = emptyList(), byFish = emptyMap()),
        )
        assertEquals("搜索只留番茄酱的两个下标", listOf(0, 1), idx)
    }
}