package com.stardew.assistant.data

import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * v2.96.0：收集包「选 N」判定的验收测试（**真数据** + **真判定逻辑**）。
 *
 * ## 为什么要有这个
 *
 * 2026-10-26 用户报「收集簿里需要勾选的『茶水间高品质作物』是 4 选 3」。
 * 根因在**数据**（`bundles.json` 那个包没有 `choose` 字段 ⇒ 判定成「4 项全交」，
 * 而游戏里其实是 4 选 3 —— 玩家会以为这个包永远做不完）。
 *
 * 但「数据里有没有 choose」和「判定对不对」是**两件事**：前者 verify 能静态查，
 * 后者查不了 —— `bundleIsFull` 的口径写在 `ProgressCalc.kt` 里，静态断言钉的是
 * 「代码长什么样」，**不是「勾 3 项会判成完成」这个结果**（第 97 节的教训：
 * 静态查不出的漏项只有真机能暴露）。
 *
 * 所以这里在 JVM 上跑**真数据**（assets 里那份 bundles.json）+ **真代码**
 * （同一份 `ProgressCalc.bundleIsFull` / `bundleDoneCount`）：
 * `org.json` 在测试源集有真实现，判定函数刻意不依赖 Android 的 Context。
 *
 * ## 这些断言钉的是什么
 *
 * ① **茶水间「高品质作物」是 4 选 3**：4 项候选、`choose == 3`；
 * ② **判定结果**：勾 2 项 = 未完成，勾 3 项 = 完成，勾 4 项 = 完成；
 * ③ **勾选键格式**：`cc:<房间下标>:<包下标>:<物品名>` —— 下标必须与
 *    `GameData.bundleRooms` 的**全局下标**一致（v2.58 加电影院时踩过「子集重新编号」
 *    那个坑：键错位 ⇒ 老用户已勾的条目静默跑到别的包上）；
 * ④ **回归**：冒险家仍是 4 选 2、蟹笼仍是 10 选 5（选 N 机制本身没被改坏）；
 * ⑤ **回归**：`choose == 0` 的普通包**仍要求全交**（4 项的全交包勾 3 项不算完成）——
 *    ⚠ 这条最容易在改「选 N」时被顺手改坏：分母口径不许跟着 choose 缩水。
 */
class BundleChooseTest {

    /** 一个收集包在「全局下标」里的位置 + 它自己的数据。 */
    private data class Located(
        val roomIdx: Int,
        val bundleIdx: Int,
        val room: String,
        val bundle: NamedItems,
    )

    /** 在工程目录里找一份资产文件（测试跑在 app/ 或工程根，两种相对路径都试）。 */
    private fun assetFile(rel: String): File {
        var dir: File? = File("").absoluteFile
        var found: File? = null
        while (dir != null && found == null) {
            for (p in listOf("app/src/main/assets/$rel", "src/main/assets/$rel")) {
                val f = File(dir, p)
                if (f.isFile) {
                    found = f
                    break
                }
            }
            dir = dir.parentFile
        }
        assertTrue("找不到 $rel", found != null)
        return found!!
    }

    private val bundlesFile: File by lazy { assetFile("data/bundles.json") }

    /**
     * 别名表（**真数据**）：`assets/data/itemAliases.json`。
     *
     * ⚠ 反查要用 [GameData.canonicalName]（别名归一）⇒ 单测必须把真别名表装进 GameData，
     *   否则测的是「没有别名表的假世界」。
     */
    private val aliasTable: Map<String, String> by lazy {
        val jo = JSONObject(assetFile("data/itemAliases.json").readText(Charsets.UTF_8))
        jo.keys().asSequence().associateWith { jo.getString(it).trim() }
    }

    /** 装上真别名表（测完还原，别影响别的测试）。 */
    @Before
    fun 装上别名表() {
        旧别名表 = GameData.itemAliases
        GameData.itemAliases = aliasTable
    }

    @After
    fun 还原别名表() {
        GameData.itemAliases = 旧别名表
    }

    private var 旧别名表: Map<String, String> = emptyMap()

    /**
     * 解析口径与 `GameData.buildCodexGroups` 那段**保持一致**：
     * `choose` 缺省 0（全交），`count` 缺省 1，`src` 缺省空串。
     */
    private val located: List<Located> by lazy {
        val arr = JSONArray(bundlesFile.readText(Charsets.UTF_8))
        val out = mutableListOf<Located>()
        for (ri in 0 until arr.length()) {
            val room = arr.getJSONObject(ri)
            val bs = room.getJSONArray("bundles")
            for (bi in 0 until bs.length()) {
                val b = bs.getJSONObject(bi)
                val items = b.getJSONArray("items")
                val parsed = (0 until items.length()).map { ii ->
                    val x = items.getJSONObject(ii)
                    ItemCount(x.getString("name"), x.optInt("count", 1), x.optString("src"))
                }
                out.add(
                    Located(
                        roomIdx = ri,
                        bundleIdx = bi,
                        room = room.getString("room"),
                        bundle = NamedItems(
                            name = b.getString("name"),
                            items = parsed,
                            choose = b.optInt("choose", 0),
                        ),
                    ),
                )
            }
        }
        out
    }

    private fun find(roomPart: String, bundleName: String): Located {
        val hits = located.filter { it.room.contains(roomPart) && it.bundle.name == bundleName }
        assertEquals("「$roomPart · $bundleName」应恰好一个，实际 ${hits.size} 个", 1, hits.size)
        return hits.first()
    }

    /** 造出「勾了前 n 项」的 checks 集合，键格式与 UI 完全一致。 */
    private fun checks(loc: Located, n: Int): Set<String> =
        loc.bundle.items.take(n).mapTo(mutableSetOf()) {
            "cc:${loc.roomIdx}:${loc.bundleIdx}:${it.name}"
        }

    @Test
    fun `茶水间高品质作物是四选三`() {
        val loc = find("茶水间", "高品质作物")
        assertEquals("茶水间「高品质作物」应选 3 项", 3, loc.bundle.choose)
        assertEquals("茶水间「高品质作物」应有 4 项候选", 4, loc.bundle.items.size)
        // 候选是四个「金星」品质作物（剥前缀后才是本体名）
        assertTrue(
            "候选应全是带品质前缀的作物，实际 " + loc.bundle.items.map { it.name },
            loc.bundle.items.all { it.name.startsWith("金星") },
        )
    }

    @Test
    fun `四选三的判定结果是勾三项才算完成`() {
        val loc = find("茶水间", "高品质作物")
        assertEquals("勾 2 项时已勾数应为 2", 2, bundleDoneCount(loc.bundle, loc.roomIdx, loc.bundleIdx, checks(loc, 2)))
        assertFalse(
            "勾 2 项不该算完成",
            bundleIsFull(loc.bundle, loc.roomIdx, loc.bundleIdx, checks(loc, 2)),
        )
        assertTrue(
            "勾 3 项应算完成（4 选 3）",
            bundleIsFull(loc.bundle, loc.roomIdx, loc.bundleIdx, checks(loc, 3)),
        )
        assertTrue(
            "勾满 4 项也算完成",
            bundleIsFull(loc.bundle, loc.roomIdx, loc.bundleIdx, checks(loc, 4)),
        )
    }

    @Test
    fun `勾选键用全局下标`() {
        val loc = find("茶水间", "高品质作物")
        val key = checks(loc, 1).first()
        assertTrue("键应以 cc: 开头，实际 $key", key.startsWith("cc:"))
        assertEquals(
            "键里的房间下标应是全局下标",
            "cc:${loc.roomIdx}:${loc.bundleIdx}:${loc.bundle.items[0].name}",
            key,
        )
        // 物品名必须原样进键（**不剥品质前缀**）：老用户已勾的键不能因为改名失效
        assertTrue("物品名应原样进键，实际 $key", key.endsWith(loc.bundle.items[0].name))
    }

    @Test
    fun `选N机制本身没被改坏`() {
        val adv = find("锅炉房", "冒险家")
        assertEquals("冒险家仍是 4 选 2", 2, adv.bundle.choose)
        assertEquals("冒险家仍是 4 项候选", 4, adv.bundle.items.size)
        assertFalse(
            "冒险家勾 1 项不该算完成",
            bundleIsFull(adv.bundle, adv.roomIdx, adv.bundleIdx, checks(adv, 1)),
        )
        assertTrue(
            "冒险家勾 2 项应算完成",
            bundleIsFull(adv.bundle, adv.roomIdx, adv.bundleIdx, checks(adv, 2)),
        )

        val crab = find("鱼缸", "蟹笼")
        assertEquals("蟹笼仍是 10 选 5", 5, crab.bundle.choose)
        assertEquals("蟹笼仍是 10 项候选", 10, crab.bundle.items.size)
        assertFalse(
            "蟹笼勾 4 项不该算完成",
            bundleIsFull(crab.bundle, crab.roomIdx, crab.bundleIdx, checks(crab, 4)),
        )
        assertTrue(
            "蟹笼勾 5 项应算完成",
            bundleIsFull(crab.bundle, crab.roomIdx, crab.bundleIdx, checks(crab, 5)),
        )
    }

    @Test
    fun `没有choose的普通包仍要求全交`() {
        val spring = find("茶水间", "春季作物")
        assertEquals("春季作物没有选 N 规则", 0, spring.bundle.choose)
        assertEquals("春季作物应有 4 项", 4, spring.bundle.items.size)
        assertFalse(
            "普通包勾 3 项不该算完成（分母不许跟着 choose 缩水）",
            bundleIsFull(spring.bundle, spring.roomIdx, spring.bundleIdx, checks(spring, 3)),
        )
        assertTrue(
            "普通包全交才算完成",
            bundleIsFull(spring.bundle, spring.roomIdx, spring.bundleIdx, checks(spring, 4)),
        )
    }

    // ── v2.99.0 / c179：图鉴「你的状态」的收集包反查 ─────────────────────

    /** located 转成 BundleRoom 列表（结构与 GameData.bundleRooms 同形，全局下标不变）。 */
    private val rooms: List<BundleRoom> by lazy {
        located.groupBy { it.roomIdx }.map { (ri, ls) ->
            BundleRoom(
                room = ls.first().room,
                bundles = ls.map { it.bundle },
            )
        }
    }

    @Test
    fun `反查防风草落在全局下标正确的包里`() {
        val spring = find("茶水间", "春季作物")
        val hits = bundleHitsOf("防风草", rooms)
        assertTrue(
            "反查要命中「春季作物」这个包，实际 ${hits.map { "${it.room}·${it.bundle.name}" }}",
            hits.any { it.room.contains("茶水间") && it.bundle.name == "春季作物" },
        )
        val hit = hits.first { it.bundle.name == "春季作物" }
        assertEquals("反查出的房间下标 = 全局下标", spring.roomIdx, hit.roomIdx)
        assertEquals("反查出的包下标 = 全局下标", spring.bundleIdx, hit.bundleIdx)
        // 已提交判定与 bundleDoneCount 同一个键构造
        assertTrue(
            "键在 checks 里 → 已提交",
            hit.checked(setOf(bundleCheckKey(hit.roomIdx, hit.bundleIdx, "防风草"))),
        )
        assertFalse("空 checks → 未提交", hit.checked(emptySet()))
    }

    /**
     * v2.100.0 / c182：反查**两侧都过别名归一**，但**拼键用收集包条目自己的名字**。
     *
     * 背景：收集包里 10 个条目的名字是**别名侧**（罂粟 / 动物毛 / 果酒…），
     * 它们的规范名（虞美人 / 羊毛 / 酒…）**根本不在** bundles.json 里 ⇒
     * 只按原名精确匹配的话，规范名条目永远匹配不到收集包
     * ⇒「🎒 你的状态」没有「收集包」行，而同屏「用途速查」说有 ⇒ 自相矛盾。
     *
     * ⚠ 拼键**不能**用归一后的名字：勾选键是用户勾选那一刻按**收集包条目名**
     * 写下的（`cc:<ri>:<bi>:罂粟`）⇒ 拿「虞美人」当键会误报「未提交」。
     */
    @Test
    fun `别名侧的反查：规范名也能命中收集包且拼键用条目自己的名字`() {
        // ⚠ **不写死具体名字**：从 bundles.json 里**动态取第一个别名侧条目**
        //   （写死名字 = 把「这份数据的当前内容」钉成断言，数据一改就红）。
        val aliasSide = located
            .flatMap { l -> l.bundle.items.map { l to it } }
            .firstOrNull { (_, it) -> aliasTable.containsKey(it.name) }
        assertTrue("bundles.json 里应有别名侧的条目（别名表里的名字）", aliasSide != null)
        val (loc, item) = aliasSide!!
        val canon = aliasTable.getValue(item.name)

        // ① 规范名（别名归一后的那侧）也能命中那个包
        val hits = bundleHitsOf(canon, rooms)
        assertTrue(
            "规范名「$canon」要命中「${item.name}」所在的包，实际 ${hits.map { "${it.room}·${it.bundle.name}" }}",
            hits.any { it.roomIdx == loc.roomIdx && it.bundleIdx == loc.bundleIdx },
        )
        val hit = hits.first { it.roomIdx == loc.roomIdx && it.bundleIdx == loc.bundleIdx }

        // ② 命中的条目名是**收集包条目自己的名字**（别名侧），不是图鉴条目名
        assertTrue(
            "命中的是收集包条目自己的名字「${item.name}」，实际 ${hit.itemNames}",
            hit.itemNames.contains(item.name),
        )

        // ③ 拼键用收集包条目自己的名字 ⇒ 勾了就显示「已提交」
        assertTrue(
            "按收集包条目名拼的键 → 已提交",
            hit.checked(setOf(bundleCheckKey(loc.roomIdx, loc.bundleIdx, item.name))),
        )
        // ④ ⚠ 拿归一后的名字当键 ⇒ 判成「未提交」（这正是本轮要防的错）
        assertFalse(
            "拿归一后的名字「$canon」当键不该算已提交",
            hit.checked(setOf(bundleCheckKey(loc.roomIdx, loc.bundleIdx, canon))),
        )
    }

    @Test
    fun `反查按规范名匹配但拼键仍用收集包条目自己的名字`() {
        // ⚠ **不写死具体名字**：带品质前缀的条目从 bundles.json 里**动态取第一个**
        //   （写死名字就等于把「这份数据的当前内容」钉成断言，数据一改就红）。
        val prefixed = located
            .flatMap { l -> l.bundle.items.map { l to it } }
            .firstOrNull { (_, it) -> it.name.startsWith("金星") || it.name.startsWith("银星") || it.name.startsWith("铱星") }
        assertTrue("bundles.json 里应有带品质前缀的条目", prefixed != null)
        val (loc, item) = prefixed!!
        val full = item.name
        val bare = full.removePrefix("金星").removePrefix("银星").removePrefix("铱星")
        val hits = bundleHitsOf(full, rooms)
        assertTrue(
            "「$full」精确命中它所在的包，实际 ${hits.map { "${it.room}·${it.bundle.name}" }}",
            hits.any { it.roomIdx == loc.roomIdx && it.bundleIdx == loc.bundleIdx },
        )
        // v2.100.0 / c182：两侧都过 canonicalName（与 usagesOf 同一口径）⇒ 剥掉前缀的
        //   本体名**也能**命中那条（原来只按原名精确匹配，匹配不上）。
        val bareHits = bundleHitsOf(bare, rooms)
        assertTrue(
            "「$bare」也命中「$full」那一条（两侧都过别名归一）",
            bareHits.any { it.roomIdx == loc.roomIdx && it.bundleIdx == loc.bundleIdx },
        )
        val hit = bareHits.first { it.roomIdx == loc.roomIdx && it.bundleIdx == loc.bundleIdx }
        // ⚠ 但**拼键**仍用收集包条目自己的名字（带前缀那个）—— 键模板单源，老用户已勾的键不能失效
        assertTrue("命中的是带前缀那个条目：${hit.itemNames}", hit.itemNames.contains(full))
        assertTrue(
            "按收集包条目名拼的键 → 已提交",
            hit.checked(setOf(bundleCheckKey(loc.roomIdx, loc.bundleIdx, full))),
        )
        assertFalse(
            "拿剥掉前缀的名字当键不该算已提交",
            hit.checked(setOf(bundleCheckKey(loc.roomIdx, loc.bundleIdx, bare))),
        )
        // 不在包里的条目 → 空列表 → 图鉴里不显示收集包行
        assertTrue("图鉴独有的条目反查为空", bundleHitsOf("不存在的东西", rooms).isEmpty())
    }
}
