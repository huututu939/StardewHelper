package com.stardew.assistant.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * v2.100.0 / c184「🎒 你的状态」总览卡。
 *
 * ## 断言钉的是**语义**
 * ① **没导入存档 ⇒ 整卡不出现**（返回 null；绝不显示「0 类 0 件」骗人）；
 * ② **背包**：种类数 / 件数分开算；名字没对上的件数**如实带出来**；
 * ③ **村民**：人数照实；好感点数**判不出就不排名**（不补 0 当最高）；
 * ④ **图鉴进度**：分母 = 13 个可打勾分类的条目数之和，**实时算**；
 * ⑤ **收集包**：与进度页**同一份** ProgressStats（不另算一遍）。
 */
class MyStatusTest {

    private fun state(
        items: Map<String, Int> = emptyMap(),
        unmapped: Int = 0,
        friends: Map<String, CodexNpcState> = emptyMap(),
    ) = CodexState(
        farmer = "阿明",
        year = 3,
        season = "夏",
        day = 6,
        money = 1625666,
        itemCounts = items,
        unmappedItems = unmapped,
        crops = emptyMap(),
        friendships = friends,
    )

    private fun group(id: String, vararg names: String) =
        CodexGroup(id, id, names.map { CodexItem(it, it) })

    // ── ① 没导入存档 ⇒ 整卡不出现 ──────────────────────────────────

    @Test
    fun `没导入存档时总览整卡不出现`() {
        assertNull(
            myStatusSummary(
                state = null,
                checks = emptySet(),
                groups = listOf(group("crops", "虞美人")),
                bundle = null,
            )
        )
    }

    // ── ② 背包：种类 / 件数 / 没对上的件数 ──────────────────────────

    @Test
    fun `背包种类数与件数分开算`() {
        val s = myStatusSummary(
            state = state(items = mapOf("木材" to 300, "石头" to 100, "纤维" to 2)),
            checks = emptySet(),
            groups = emptyList(),
            bundle = null,
        )!!
        assertEquals(3, s.bagKinds)
        assertEquals(402, s.bagItems)
        assertEquals(0, s.unmappedItems)
    }

    @Test
    fun `背包里名字没对上的件数如实带出来`() {
        val s = myStatusSummary(
            state = state(items = mapOf("木材" to 10), unmapped = 33),
            checks = emptySet(),
            groups = emptyList(),
            bundle = null,
        )!!
        assertEquals(33, s.unmappedItems)
        assertEquals(1, s.bagKinds)
        assertEquals(10, s.bagItems)
    }

    // ── ③ 村民：人数照实，点数判不出不排名 ──────────────────────────

    @Test
    fun `好感最高的那位被挑出来`() {
        val s = myStatusSummary(
            state = state(
                friends = mapOf(
                    "潘妮" to CodexNpcState(points = 2068, giftsThisWeek = 0),
                    "雷欧" to CodexNpcState(points = 66, giftsThisWeek = 0),
                    "罗宾" to CodexNpcState(points = 2749, giftsThisWeek = 1),
                )
            ),
            checks = emptySet(),
            groups = emptyList(),
            bundle = null,
        )!!
        assertEquals(3, s.friendCount)
        assertEquals("罗宾", s.topFriendName)
        assertEquals(2749, s.topFriendPoints)
    }

    @Test
    fun `点数判不出的村民不参与排名也不补零`() {
        val s = myStatusSummary(
            state = state(
                friends = mapOf(
                    // 点数没写（null）—— 不许当成 0 点来排名
                    "海莉" to CodexNpcState(points = null, giftsThisWeek = null),
                    "阿比盖尔" to CodexNpcState(points = 450, giftsThisWeek = 0),
                )
            ),
            checks = emptySet(),
            groups = emptyList(),
            bundle = null,
        )!!
        // 人数照实（存档里确实有 TA 的记录）
        assertEquals(2, s.friendCount)
        // 排名只认判得出点数的
        assertEquals("阿比盖尔", s.topFriendName)
        assertEquals(450, s.topFriendPoints)
    }

    @Test
    fun `一个都判不出点数时不编最高`() {
        val s = myStatusSummary(
            state = state(friends = mapOf("海莉" to CodexNpcState(points = null, giftsThisWeek = null))),
            checks = emptySet(),
            groups = emptyList(),
            bundle = null,
        )!!
        assertEquals(1, s.friendCount)
        assertNull(s.topFriendName)
        assertNull(s.topFriendPoints)
    }

    // ── ④ 图鉴进度：分母实时算，只算可打勾分类 ──────────────────────

    @Test
    fun `图鉴进度分母只算可打勾分类`() {
        val s = myStatusSummary(
            state = state(),
            checks = setOf("codex:crops:虞美人", "codex:crops:大蒜"),
            groups = listOf(
                group("crops", "虞美人", "大蒜", "防风草"),   // 可打勾 → 3 条
                group("buildings", "鸡舍", "畜棚"),            // **不可打勾** → 不计
                group("movies", "雷欧"),                        // 不可打勾 → 不计
            ),
            bundle = null,
        )!!
        assertEquals(3, s.codexTotal)
        assertEquals(2, s.codexDone)
    }

    @Test
    fun `图鉴进度分母随数据包变化不写死`() {
        val a = myStatusSummary(
            state = state(), checks = emptySet(),
            groups = listOf(group("crops", "虞美人")),
            bundle = null,
        )!!
        val b = myStatusSummary(
            state = state(), checks = emptySet(),
            groups = listOf(group("crops", "虞美人", "大蒜", "防风草")),
            bundle = null,
        )!!
        assertEquals(1, a!!.codexTotal)
        assertEquals(3, b!!.codexTotal)
    }

    // ── ⑤ 收集包：与进度页同一份 ────────────────────────────────────

    @Test
    fun `收集包两行直接取进度页那份数据`() {
        val stats = ProgressStats(
            bundleChecked = 89,
            bundleTotal = 95,
            totalBundles = 26,
            fullBundles = 5,
            museumDone = 40,
            museumTotal = 50,
            achDone = 0,
            achTotal = 49,
            walnutDone = 0,
            walnutTotal = 11,
            raccoonDone = 0,
            raccoonTotal = 12,
            laterDone = 0,
            laterTotal = 6,
        )
        val s = myStatusSummary(
            state = state(),
            checks = emptySet(),
            groups = emptyList(),
            bundle = stats,
        )!!
        assertEquals(89, s.bundleDone)
        assertEquals(95, s.bundleTotal)
    }

    @Test
    fun `总览四行都有值不是空壳`() {
        val s = myStatusSummary(
            state = state(
                items = mapOf("木材" to 5),
                unmapped = 2,
                friends = mapOf("潘妮" to CodexNpcState(points = 100, giftsThisWeek = 0)),
            ),
            checks = setOf("codex:crops:虞美人"),
            groups = listOf(group("crops", "虞美人", "大蒜")),
            bundle = ProgressStats(
                bundleChecked = 10, bundleTotal = 20, totalBundles = 5, fullBundles = 1,
                museumDone = 1, museumTotal = 2, achDone = 0, achTotal = 3,
                walnutDone = 0, walnutTotal = 1, raccoonDone = 0, raccoonTotal = 1,
                laterDone = 0, laterTotal = 1,
            ),
        )
        assertNotNull(s)
        assertEquals(1, s!!.bagKinds)
        assertEquals(1, s.friendCount)
        assertEquals(1, s.codexDone)
        assertEquals(2, s.codexTotal)
        assertEquals(10, s.bundleDone)
    }
}
