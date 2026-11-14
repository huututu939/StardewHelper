package com.stardew.assistant.ui.codex

import com.stardew.assistant.ui.search.SearchHit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * c219 + c221：收集包 mate「就地翻页」的验收测试。
 *
 * ## 这个 bug 是什么
 * c219 抽屉里点「📦 同一个收集包里还有」的名字走 resolveItemJump 跳新抽屉，丢上下文。
 * 改为就地翻页后，c221 又修正「唯一命中」的判据：
 * 图鉴把同一物品同时收在多个分类（作物在 crops + shipping、鱼在 fishing + shipping），
 * 跨全部分类的 nameToHits 会把**绝大多数本可直达**的名字误判成「重名」⇒ 13/27 个包
 * 点任何名字都落搜索，等于没就地翻。修法：只把「真正条目的重复」（crops != shipping）
 * 算重名；全落聚合分类（shipping）的名字视同唯一、可进包内；零命中则走品质/别名归一
 * 回落（金星南瓜→南瓜），归一后唯一也可进。
 *
 * ## 这里钉什么
 * · 锚点永远在列表里，用已知 (groupId, idx)；
 * · 本分类唯一命中 → 进；跨真实分类重名 → 不进；全落聚合分类 → 进；
 * · 零命中但归一后唯一 → 进（并定位到归一后的条目）；
 * · 名字按拼音/Unicode 序，锚点落位；去重；`bundleTapTarget` 定位。
 *
 * ⚠ 纯函数 + 注入 hitOf / resolveOf，不依赖真实图鉴数据。
 */
class CodexBundleRefTest {

    private fun hit(groupId: String, index: Int, name: String) = SearchHit(
        groupId = groupId, groupTitle = groupId, itemName = name, index = index, summary = "",
    )

    private fun hitsOf(vararg entries: Pair<String, List<SearchHit>>): (String) -> List<SearchHit> {
        val map = entries.toMap()
        return { name -> map[name].orEmpty() }
    }

    // 「归一即原名」的回落：查不到就回原名再搜一次（等同没别名/品质）
    private fun identityResolve(): (String) -> List<SearchHit> = { name -> hitsOf()(name) }

    @Test
    fun 锚点永远在列表里且用已知下标钉住() {
        val refs = buildBundleRefs(
            "crops", 3, "杨桃", listOf("南瓜"),
            hitOf = hitsOf(
                "杨桃" to listOf(hit("crops", 9, "杨桃"), hit("forage", 1, "杨桃")),
                "南瓜" to listOf(hit("crops", 5, "南瓜")),
            ),
            resolveOf = identityResolve(),
        )
        val anchor = refs.single { it.name == "杨桃" }
        assertEquals("crops", anchor.groupId)
        assertEquals("锚点用传入下标，不按重名反查", 3, anchor.idx)
    }

    @Test
    fun 同包唯一名字进列表_按序() {
        val refs = buildBundleRefs(
            "crops", 0, "B", listOf("C", "A"),
            hitOf = hitsOf(
                "A" to listOf(hit("crops", 1, "A")),
                "B" to listOf(hit("crops", 0, "B")),
                "C" to listOf(hit("crops", 2, "C")),
            ),
            resolveOf = identityResolve(),
        )
        assertEquals("A、B、C", listOf("A", "B", "C"), refs.map { it.name })
        assertEquals(1, refs.indexOfFirst { it.name == "B" })
    }

    @Test
    fun 跨真实分类重名不接受现存_shipping算聚合() {
        // c221：shipping 是聚合展示分类，不计入「唯一命中」。防风草在 crops+shipping
        //   只有 1 个真实分类命中 → 视同唯一，进包内（定位到 crops 那条）。
        val refs = buildBundleRefs(
            "crops", 0, "野", listOf("芒果"),
            hitOf = hitsOf(
                "芒果" to listOf(hit("crops", 1, "芒果"), hit("shipping", 2, "芒果")),
            ),
            resolveOf = identityResolve(),
        )
        assertEquals("crops 那条唯一 → 进包内", 2, refs.size)
        val mango = refs.first { it.name == "芒果" }
        assertEquals("crops", mango.groupId)
        assertEquals(1, mango.idx)
    }

    @Test
    fun 真正跨真实分类重名才不进() {
        // 草莓在 crops + forage 两个**真实**分类 → 真重名 → 不进包内
        val refs = buildBundleRefs(
            "crops", 0, "野", listOf("草莓"),
            hitOf = hitsOf(
                "草莓" to listOf(hit("crops", 1, "草莓"), hit("forage", 2, "草莓")),
            ),
            resolveOf = identityResolve(),
        )
        assertEquals("跨真实分类重名不进包内", listOf("野"), refs.map { it.name })
    }

    @Test
    fun 全落聚合分类的名字视同唯一_可进包内() {
        val refs = buildBundleRefs(
            "crops", 0, "野", listOf("防风草"),
            hitOf = hitsOf(
                "防风草" to listOf(hit("shipping", 5, "防风草")),
            ),
            resolveOf = identityResolve(),
        )
        assertEquals("全落聚合分类 → 可进", 2, refs.size)
        val grass = refs.first { it.name == "防风草" }
        assertEquals("shipping", grass.groupId)
        assertEquals("定位到聚合条目下标（非列表位置）", 5, grass.idx)
    }

    @Test
    fun 零命中但归一回落唯一_可进包内() {
        // 金星南瓜：原名零命中，剥品质前缀「南瓜」唯一 → 进包内并定位到南瓜的下标
        val refs = buildBundleRefs(
            "crops", 0, "野", listOf("金星南瓜"),
            hitOf = hitsOf("野" to listOf(hit("crops", 0, "野"))),
            resolveOf = { name ->
                when (name) {
                    "金星南瓜", "南瓜" -> listOf(hit("crops", 7, "南瓜"))
                    else -> emptyList()
                }
            },
        )
        assertEquals(2, refs.size)
        val golden = refs.first { it.name == "金星南瓜" }
        assertEquals("金星南瓜进包内", "金星南瓜", golden.name)
        assertEquals("定位到归一后的条目下标", 7, golden.idx)
        assertEquals("crops", golden.groupId)
    }

    @Test
    fun 查不到的名字不进列表() {
        val refs = buildBundleRefs(
            "crops", 0, "野", listOf("查不到"),
            hitOf = hitsOf(),
            resolveOf = identityResolve(),
        )
        assertEquals("只有锚点", listOf("野"), refs.map { it.name })
    }

    @Test
    fun 重复名字去重() {
        val refs = buildBundleRefs(
            "crops", 0, "野", listOf("甲", "甲", "野"),
            hitOf = hitsOf("甲" to listOf(hit("crops", 1, "甲"))),
            resolveOf = identityResolve(),
        )
        assertEquals("名字去重 + 锚点不重复", 2, refs.size)
        assertEquals(1, refs.count { it.name == "野" })
        assertEquals(1, refs.count { it.name == "甲" })
    }

    @Test
    fun 空同包名单只留锚点() {
        val refs = buildBundleRefs("crops", 7, "野", emptyList(),
            hitOf = { emptyList() }, resolveOf = { emptyList() })
        assertEquals(1, refs.size)
        assertEquals("crops", refs[0].groupId)
        assertEquals(7, refs[0].idx)
        assertTrue(refs.any { it.name == "野" })
    }

    @Test
    fun bundleTapTarget_在名单里定位_或返回null() {
        val refs = listOf(BundleRef("crops", 0, "A"), BundleRef("crops", 1, "B"))
        assertEquals("B", bundleTapTarget("B", refs, null))
        assertEquals("B", bundleTapTarget("B", refs, refs))
        assertNull("不在名单 → null（走搜索）", bundleTapTarget("Z", refs, refs))
    }

    @Test
    fun bundleTapTarget_不在名单返回null_就地点名返回本身() {
        val refs = listOf(BundleRef("crops", 0, "A"), BundleRef("crops", 1, "B"))
        assertEquals("B", bundleTapTarget("B", refs, null))
        assertEquals("B", bundleTapTarget("B", refs, refs))
        assertNull("不在名单 → null（走搜索）", bundleTapTarget("Z", refs, refs))
    }
}