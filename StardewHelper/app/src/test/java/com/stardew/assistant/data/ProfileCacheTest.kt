package com.stardew.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * c217：多存档缓存**隔离**的验收测试。
 *
 * ## 这个 bug 是什么
 * 「我的农场」缓存原来只有**一个** `farm_today.json`（图鉴状态同理），
 * 文件名**不带存档标识**。于是切存档后，今日页「我的农场」显示的还是
 * **上一个存档**的农场 —— 哪块地种了什么、今天收多少钱，全是错的。
 *
 * 代码注释写着「宁可空着也不能显示上一份存档的数据」，但那条纪律**只守住了
 * 「导入解析失败」这一条路径**，切档这条路整个绕过去了。
 *
 * ## 这里钉什么
 * · 每个存档一份自己的缓存文件（读存档1 拿不到存档2 的数据）；
 * · 没导入过的存档读出来是 null（**不是**上一份的数据）；
 * · 存档名是**用户可改的自由文本**（含 `/` `:` 空格等）⇒ 文件名必须安全化；
 * · 改名时缓存跟着搬（不然改完名农场数据就「消失」了）。
 */
class ProfileCacheTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): File = tmp.newFolder("files")

    /** 造一份最小的农场模型（内容可辨：角色名 + 总株数 + 现金）。 */
    private fun farm(name: String, stocks: Int, money: Int) = FarmToday(
        farmer = name,
        year = 1,
        season = "春",
        day = 1,
        money = money,
        readyStocks = stocks,
        harvest = emptyList(),
        harvestTotal = 0,
        unnamedReady = 0,
        soon = emptyList(),
        dead = emptyList(),
        todos = emptyList(),
        animalTotal = 0,
        savedAt = 0L,
        sourceLabel = name,
    )

    private fun codex(name: String) = CodexState(
        farmer = name,
        year = 1,
        season = "春",
        day = 1,
        money = 0,
        itemCounts = mapOf(name to 7),
        unmappedItems = 0,
        crops = emptyMap(),
        friendships = emptyMap(),
    )

    // ── 隔离 ──────────────────────────────────────────────────────────

    @Test
    fun 两个存档各读各的() {
        val d = dir()
        FarmTodayCache.writeIn(d, "存档1", farm("甲农场", 11, 1000))
        FarmTodayCache.writeIn(d, "存档2", farm("乙农场", 22, 2000))

        val a = FarmTodayCache.readIn(d, "存档1")
        val b = FarmTodayCache.readIn(d, "存档2")
        assertEquals("存档1 读到甲农场", "甲农场", a?.farmer)
        assertEquals("存档1 总株数", 11, a?.readyStocks)
        assertEquals("存档2 读到乙农场", "乙农场", b?.farmer)
        assertEquals("存档2 总株数", 22, b?.readyStocks)
        assertNotEquals("两个存档的数据不能相同", a?.farmer, b?.farmer)
    }

    @Test
    fun 没导入过的存档读出来是空而不是别人的数据() {
        val d = dir()
        FarmTodayCache.writeIn(d, "存档1", farm("甲农场", 11, 1000))
        // 存档2 从没导入过 ⇒ 必须是 null（**不是**存档1 的那份）
        assertNull("没导入过的存档读到 null", FarmTodayCache.readIn(d, "存档2"))
    }

    @Test
    fun 图鉴状态也按存档隔离() {
        val d = dir()
        CodexStateCache.writeIn(d, "存档1", codex("甲"))
        CodexStateCache.writeIn(d, "存档2", codex("乙"))
        assertEquals("存档1 状态", "甲", CodexStateCache.readIn(d, "存档1")?.farmer)
        assertEquals("存档2 状态", "乙", CodexStateCache.readIn(d, "存档2")?.farmer)
        assertNull("没导入过的存档没有状态", CodexStateCache.readIn(d, "存档3"))
    }

    @Test
    fun 清一个存档不影响另一个() {
        val d = dir()
        FarmTodayCache.writeIn(d, "存档1", farm("甲农场", 11, 1000))
        FarmTodayCache.writeIn(d, "存档2", farm("乙农场", 22, 2000))
        CodexStateCache.writeIn(d, "存档1", codex("甲"))
        CodexStateCache.writeIn(d, "存档2", codex("乙"))

        FarmTodayCache.clearIn(d, "存档1")

        assertNull("存档1 农场被清掉了", FarmTodayCache.readIn(d, "存档1"))
        assertNull("存档1 图鉴状态也一起清掉（同一份纪律）", CodexStateCache.readIn(d, "存档1"))
        assertEquals("存档2 农场不受影响", "乙农场", FarmTodayCache.readIn(d, "存档2")?.farmer)
        assertEquals("存档2 状态不受影响", "乙", CodexStateCache.readIn(d, "存档2")?.farmer)
    }

    // ── 文件名安全化 ──────────────────────────────────────────────────

    @Test
    fun 存档名带非法字符时文件名仍然安全() {
        val d = dir()
        // 存档名是用户可改的自由文本：留着 / \ : * ? " < > | 与空格
        val weird = "我的/存档:1 *测试?"
        FarmTodayCache.writeIn(d, weird, farm("怪名字农场", 33, 3300))

        val back = FarmTodayCache.readIn(d, weird)
        assertEquals("怪名字存档也能读回来", "怪名字农场", back?.farmer)

        // 文件必须落在给定的目录里（不能写到别处去）
        val files = d.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
        assertEquals("只落一个缓存文件", 1, files.size)
        val f = d.resolve(files.first())
        assertEquals("文件就在给定的目录里", d.canonicalPath, f.parentFile?.canonicalPath)
        assertTrue("文件名不含路径分隔符", !files.first().contains("/") && !files.first().contains("\\"))
    }

    @Test
    fun 清洗后撞名的两个存档不共用一个文件() {
        val d = dir()
        // 「A/B」与「A_B」清洗后都是「A_B」⇒ 必须靠哈希分开
        FarmTodayCache.writeIn(d, "A/B", farm("斜杠农场", 1, 100))
        FarmTodayCache.writeIn(d, "A_B", farm("下划线农场", 2, 200))
        assertEquals("斜杠档", "斜杠农场", FarmTodayCache.readIn(d, "A/B")?.farmer)
        assertEquals("下划线档", "下划线农场", FarmTodayCache.readIn(d, "A_B")?.farmer)
    }

    @Test
    fun 存档名含emoji也能安全读回() {
        val d = dir()
        // emoji 是**代理对（两个 UTF-16 char）**—— 文件名安全化必须按码点处理，
        // 如果按 char 会把 emoji 拆成两半甚至产生非法字符。
        val emoji = "存档🌾"
        FarmTodayCache.writeIn(d, emoji, farm("表情农场", 55, 5500))
        assertEquals("emoji 存档读回", "表情农场", FarmTodayCache.readIn(d, emoji)?.farmer)
        CodexStateCache.writeIn(d, emoji, codex("表"))
        assertEquals("emoji 存档状态", "表", CodexStateCache.readIn(d, emoji)?.farmer)
        // 文件名干净、落在给定目录
        val files = d.listFiles()?.filter { it.isFile }?.map { it.name } ?: emptyList()
        for (n in files) {
            assertTrue("文件名不含路径分隔符", !n.contains("/") && !n.contains("\\"))
        }
    }

    @Test
    fun 相同存档名永远得到同一个token() {
        assertEquals(
            "token 稳定（同样的名字 ⇒ 同样的文件）",
            FarmTodayCache.tokenOf("存档1"),
            FarmTodayCache.tokenOf("存档1"),
        )
        assertNotEquals(
            "不同存档名 ⇒ 不同 token",
            FarmTodayCache.tokenOf("存档1"),
            FarmTodayCache.tokenOf("存档2"),
        )
    }

    // ── 改名 ──────────────────────────────────────────────────────────

    @Test
    fun 存档改名后缓存跟着搬() {
        val d = dir()
        FarmTodayCache.writeIn(d, "存档1", farm("甲农场", 11, 1000))
        CodexStateCache.writeIn(d, "存档1", codex("甲"))

        FarmTodayCache.renameCacheIn(d, "存档1", "存档9")

        assertEquals("改名后农场还在", "甲农场", FarmTodayCache.readIn(d, "存档9")?.farmer)
        assertEquals("改名后图鉴状态还在", "甲", CodexStateCache.readIn(d, "存档9")?.farmer)
        assertNull("旧名字下不该还有缓存", FarmTodayCache.readIn(d, "存档1"))
        assertNull("旧名字下不该还有图鉴状态", CodexStateCache.readIn(d, "存档1"))
    }
}
