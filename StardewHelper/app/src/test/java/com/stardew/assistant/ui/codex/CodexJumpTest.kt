package com.stardew.assistant.ui.codex

import com.stardew.assistant.data.GameData
import com.stardew.assistant.ui.nav.NavRequest
import com.stardew.assistant.ui.search.SearchHit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * v2.100.0 / c182：图鉴跳转的**回落门**（`resolveItemJump` 的判定逻辑，纯函数）。
 *
 * ## 这条不变式是什么
 * **唯一命中才直达详情**；重名（≥2 条）**不许硬跳**，一律退化成搜索结果
 * （宁可多点一次，也不要点开一个看起来对、其实错的条目）。
 *
 * ## c182 修的洞
 * 原来别名回落的门写成 `if (alt.isEmpty())`（`alt` = 品质前缀回落的结果）：
 * `alt` 只在零命中时才非空 ⇒ **重名时 `alt` 也是空 list** ⇒ 重名也会走进别名回落分支
 * ⇒ 若「别名名本身在图鉴里有 ≥2 条」且「规范名恰好 1 条」，就会**硬跳**到规范名那条
 * —— **绕过「重名不硬跳」这条不变式**。
 *
 * 现在门是显式的 `hits.size == 0`。这些断言把这条门钉死。
 *
 * ⚠ 判定函数刻意做成**纯函数**（[resolveJumpRequest]）：命中表由调用方给，
 *   单测就能喂假命中表，不必起 App、不必读图鉴数据。
 */
class CodexJumpTest {

    private var 旧别名表: Map<String, String> = emptyMap()

    @Before
    fun 装上别名表() {
        旧别名表 = GameData.itemAliases
        // 假别名表：「测试别名」是「测试规范名」的另一个叫法
        GameData.itemAliases = mapOf("测试别名" to "测试规范名")
    }

    @After
    fun 还原别名表() {
        GameData.itemAliases = 旧别名表
    }

    /** 造一条命中（只需要 groupId / index / itemName）。 */
    private fun hit(group: String, index: Int, name: String) =
        SearchHit(groupId = group, groupTitle = group, itemName = name, index = index, summary = "")

    /** 命中表：名字 → 命中列表。 */
    private fun table(vararg pairs: Pair<String, List<SearchHit>>): (String) -> List<SearchHit> {
        val m = pairs.toMap()
        return { m[it].orEmpty() }
    }

    @Test
    fun 唯一命中直达详情() {
        val req = resolveJumpRequest("南瓜", table("南瓜" to listOf(hit("crops", 3, "南瓜"))))
        assertTrue("应是指令", req is NavRequest.Codex)
        req as NavRequest.Codex
        assertEquals("直达那条", "crops", req.groupId)
        assertEquals("直达下标", 3, req.targetIndex)
        assertEquals("不落搜索页", "", req.openSearch)
    }

    @Test
    fun 零命中按别名回落到规范名() {
        val req = resolveJumpRequest(
            "测试别名",
            table("测试规范名" to listOf(hit("crops", 7, "测试规范名"))),
        )
        req as NavRequest.Codex
        assertEquals("别名侧零命中 → 规范名唯一 → 直达", "crops", req.groupId)
        assertEquals("直达下标", 7, req.targetIndex)
    }

    @Test
    fun 零命中按品质前缀回落到本体名() {
        val req = resolveJumpRequest(
            "金星测试",
            table("测试" to listOf(hit("crops", 2, "测试"))),
        )
        req as NavRequest.Codex
        assertEquals("剥前缀后唯一 → 直达本体名", "crops", req.groupId)
        assertEquals("直达下标", 2, req.targetIndex)
    }

    /**
     * ⚠ **c182 的回归断言**：重名 + 别名回落 ⇒ **不许硬跳**。
     *
     * 「测试别名」在图鉴里有 **2 条**（重名），而它的规范名「测试规范名」恰好 **1 条** ——
     * 修之前这里会**硬跳**到规范名那条（绕过「重名不硬跳」）。
     */
    @Test
    fun 重名时别名回落不许硬跳() {
        val req = resolveJumpRequest(
            "测试别名",
            table(
                "测试别名" to listOf(hit("crops", 1, "测试别名"), hit("forage", 5, "测试别名")),
                "测试规范名" to listOf(hit("crops", 9, "测试规范名")),
            ),
        )
        req as NavRequest.Codex
        assertNull("重名不许硬跳（没有下标）", req.targetIndex)
        assertEquals("退化成原名的搜索结果", "", req.groupId)
        assertEquals("落回原名", "测试别名", req.openSearch)
    }

    @Test
    fun 重名时直接落搜索页() {
        val req = resolveJumpRequest(
            "重名",
            table("重名" to listOf(hit("crops", 1, "重名"), hit("forage", 2, "重名"))),
        )
        req as NavRequest.Codex
        assertNull("重名不许硬跳", req.targetIndex)
        assertEquals("落回原名", "重名", req.openSearch)
    }

    @Test
    fun 零命中且规范名也重名时落规范名的搜索页() {
        val req = resolveJumpRequest(
            "测试别名",
            table(
                "测试规范名" to listOf(hit("crops", 1, "测试规范名"), hit("forage", 2, "测试规范名")),
            ),
        )
        req as NavRequest.Codex
        assertNull("规范名重名也不许硬跳", req.targetIndex)
        assertEquals("落规范名的搜索页", "测试规范名", req.openSearch)
    }

    @Test
    fun 什么都查不到就落原名的搜索页() {
        val req = resolveJumpRequest("查无此物", table())
        req as NavRequest.Codex
        assertNull("查无此物不硬跳", req.targetIndex)
        assertEquals("如实落到空结果页", "查无此物", req.openSearch)
    }
}
